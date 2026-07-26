/**
 * @fileoverview Benchmark for the session-mediated proxied route (browser -> gateway -> upstream).
 *
 * Measures the steady-state cost of BFF session mediation on the hot path: every request carries a
 * pre-established server-session cookie the gateway resolves to a stored session, injecting the
 * server-held bearer token before forwarding upstream. The session is established exactly once in
 * {@link setup} through the gateway's own login-initiation reserved path and replayed for the whole
 * run, so the measured window is the mediation hot path alone -- cookie -> store lookup -> bearer
 * injection -> upstream -- and never the login handshake.
 *
 * Login-flow throughput is deliberately NOT benchmarked. The auth-code round-trip is IdP-bound
 * (Keycloak authorization, PKCE, callback) rather than a gateway hot path, so folding it into the
 * measured window would report Keycloak's per-login cost as gateway overhead. This mirrors how
 * {@link bearer_proxied.js} mints its token once in {@link setup} and measures only per-request
 * validation, and it is the session-mediation peer of the `bearer` (offline validation) and
 * `proxiedStatic` (plain proxy, no auth) baselines: the three together isolate mediation overhead.
 *
 * Anti-silent-wrong-result gate: `checks` carries an explicit HTTP-200 assertion, so a run whose
 * session cookie is rejected (the gateway falls through to a 302 login redirect or a 401) scores a
 * checks rate of 0 and fails the build instead of reporting the redirect/rejection path as an
 * excellent result. {@link setup} is fail-loud for the same reason: a run that could not establish
 * a session aborts here rather than measuring the unauthenticated fall-through.
 */
import http from 'k6/http';
import { check, fail } from 'k6';
import { buildSummary, duration, maxErrorRate, SUMMARY_TREND_STATS, vus } from './lib/summary.js';
import { baseUrl, targetUrl } from './lib/target.js';

const BENCHMARK_NAME = 'sessionMediated';

// The protected upstream route guarded by `require: session`: a request without a resolvable
// session cookie is redirected to login, so a 200 here proves the mediation path ran end to end.
// It targets the session-gated `/bff-session` anchor (NOT `/secure`, which is `require: bearer` and
// would reject a replayed session cookie with a 401).
const TARGET_URL = __ENV.TARGET_URL || targetUrl('/bff-session/get');

// The gateway-owned login-initiation reserved path (oidc.login.path). Hitting it drives the
// auth-code flow that ends with the gateway setting the opaque session cookie.
const LOGIN_URL = __ENV.LOGIN_URL || targetUrl('/auth/login');

// The opaque server-session cookie the gateway sets on a successful callback. Only its value is
// replayed; no token material ever leaves the gateway. Matches SessionCookieCodec.DEFAULT_COOKIE_NAME.
const SESSION_COOKIE_NAME = __ENV.SESSION_COOKIE_NAME || '__Host-sheriff-session';

// Keycloak is reached by service name on the shared api-sheriff network. Server-mode BFF session
// mediation is wired (the gateway's oidc block) to the `integration` realm and the `integration-client`
// confidential client, so the session login must authenticate an integration-realm user — NOT the
// bearer aspect's benchmark-realm benchmark-user, whose realm the session route does not mediate.
const KEYCLOAK_USERNAME = __ENV.KEYCLOAK_USERNAME || 'integration-user';
const KEYCLOAK_PASSWORD = __ENV.KEYCLOAK_PASSWORD || 'integration-password';

export const options = {
    vus: vus(50),
    duration: duration(),
    summaryTrendStats: SUMMARY_TREND_STATS,
    insecureSkipTLSVerify: true,
    thresholds: {
        http_req_failed: [`rate<=${maxErrorRate()}`],
        checks: [`rate>=${1 - maxErrorRate()}`],
    },
};

/**
 * Establishes one server session before the measured window opens and returns the opaque session
 * cookie every VU iteration replays.
 *
 * The login is driven through the gateway's own login-initiation reserved path so the whole
 * auth-code flow (gateway -> Keycloak authorization -> callback -> session cookie) runs exactly as a
 * browser would drive it, k6 following each 302 and accumulating cookies in its jar. The measured
 * default iteration then never pays this cost: it replays only the resolved cookie.
 *
 * A session that could not be established is fatal here rather than deferred to the run. Without the
 * cookie every request would fall through to the login redirect, and a redirect-only run measures
 * the unauthenticated path, not the mediation path this benchmark exists to measure.
 *
 * @returns {{sessionCookie: string}} the opaque session-cookie value replayed by every VU iteration
 */
export function setup() {
    // The login-initiation path drives the auth-code flow; k6 follows the gateway->Keycloak->callback
    // redirect chain and lands on the Keycloak login form, whose action carries the flow parameters.
    const loginPage = http.get(LOGIN_URL, { redirects: 10 });
    if (loginPage.status !== 200) {
        fail(`login initiation ${LOGIN_URL} did not reach a login form: HTTP ${loginPage.status}`);
    }

    const formAction = extractFormAction(loginPage.body);
    if (!formAction) {
        fail(`login form at ${loginPage.url} carried no resolvable form action to post credentials to`);
    }

    // Posting the credentials completes authentication at Keycloak, which 302s back to the gateway
    // callback; the gateway exchanges the code, stores the session, and sets the session cookie.
    const authenticated = http.post(
        formAction,
        { username: KEYCLOAK_USERNAME, password: KEYCLOAK_PASSWORD },
        { headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, redirects: 10 },
    );
    if (authenticated.status !== 200) {
        fail(`credential post to ${formAction} did not complete the login: HTTP ${authenticated.status}`);
    }

    const sessionCookie = resolveSessionCookie();
    if (!sessionCookie) {
        fail(`login flow set no ${SESSION_COOKIE_NAME} cookie — the gateway did not establish a session`);
    }
    return { sessionCookie: sessionCookie };
}

/**
 * Extracts the Keycloak login form's POST action from the returned HTML.
 *
 * @param {string} html the login-page body
 * @returns {string|null} the form action URL, or {@code null} when no form is present
 */
function extractFormAction(html) {
    if (typeof html !== 'string') {
        return null;
    }
    const match = html.match(/<form[^>]*\baction="([^"]+)"/i);
    if (!match) {
        return null;
    }
    // Keycloak renders the action HTML-escaped; unescape the ampersands so the query parameters
    // (session_code / execution / tab_id) survive the credential post.
    return match[1].replace(/&amp;/g, '&');
}

/**
 * Reads the opaque session-cookie value the gateway set on the callback out of the VU cookie jar.
 *
 * @returns {string|null} the session-cookie value, or {@code null} when it was never set
 */
function resolveSessionCookie() {
    const cookies = http.cookieJar().cookiesForURL(baseUrl() + '/');
    const values = cookies[SESSION_COOKIE_NAME];
    return values && values.length > 0 ? values[0] : null;
}

export default function (data) {
    const response = http.get(TARGET_URL, {
        headers: {
            Cookie: `${SESSION_COOKIE_NAME}=${data.sessionCookie}`,
            Accept: 'application/json',
        },
        // A fresh redirect budget of 0: the mediated route must answer 200 directly. A 302 to login
        // means the session was not resolved, and the check below scores it a failure rather than
        // silently following the redirect and timing the login page.
        redirects: 0,
        tags: { benchmark: BENCHMARK_NAME },
    });
    check(response, { 'status is 200': (r) => r.status === 200 });
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data);
}
