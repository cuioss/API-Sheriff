/**
 * @fileoverview Bound proof for the gateway's store of started, not yet completed logins.
 *
 * Every login a browser starts leaves a pending record in the gateway until its callback consumes
 * it. Unauthenticated callers create these records, so the store is bounded: it holds 10 000 records
 * and, beyond that, drops the oldest one for every new one. This script pins that bound from both
 * sides, against a running gateway and a real identity provider:
 *
 *   * Phase A -- one login is opened up to the identity provider's login form, then 9 999 further
 *     logins are started and left unfinished. The store now holds 10 000 records, the first among
 *     them. The first login is completed and must yield a session.
 *   * Phase B -- one login is opened, then 10 000 further logins are started. The first one is now
 *     the 10 001st-newest record and has been dropped. It is completed at the identity provider, and
 *     the gateway's callback must refuse it: no session cookie, and the session route answers 401.
 *     The newest login of the flood is completed as well and must yield a session, so the refusal is
 *     the first login's alone and not a gateway that refuses every login under load.
 *
 * Whether a login is dropped depends only on how many logins were started after it, not on what the
 * store held before, so the two phases are exact whatever an earlier goal left behind, and phase B is
 * exact with the 9 999 records phase A leaves.
 *
 * This is a proof, not a measurement. The run produces no throughput or latency figure worth a
 * trend: the request mix is one phase of unfinished logins. Its summary document is therefore written
 * with `published: false` -- it exists, so the coverage step finds the goal's summary, and the
 * converter makes no report entry from it (see `lib/summary.js`).
 *
 * What fails the run. Each of the four expectations increments a counter of its own, and each counter
 * has the threshold `count==1`: the first login of phase A kept, the first login of phase B dropped,
 * the newest login of phase B kept, and the gateway answering its readiness probe afterwards. The two
 * flood counters have the thresholds `count==9999` and `count==10000`, so a flood that started one
 * login too few or too many fails the run as well. An expectation that is not reached leaves its
 * counter at zero; a threshold is evaluated on a counter that was never incremented, so an aborted
 * run cannot pass.
 *
 * The run is one iteration of one virtual user, and that is deliberate. Opening a login, flooding and
 * completing the login have to happen in that order, twice, and the cookie jar of the opened login
 * has to be the one that completes it. Scenarios run side by side and share no state, so the order
 * is kept by one iteration; the flood itself is sent in parallel batches.
 *
 * The pending record lives five minutes. Each phase must finish inside that, or its first login
 * expires instead of being kept or dropped. Phase A then fails on its own expectation. Phase B would
 * pass for the wrong reason, so it checks the age of its first login before it reads the refusal.
 *
 * The aspect has no counterpart on another gateway and is not part of the comparison lane.
 */
import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter } from 'k6/metrics';
import { buildSummary, SUMMARY_TREND_STATS } from './lib/summary.js';
import { baseUrl, managementUrl, targetUrl } from './lib/target.js';

const BENCHMARK_NAME = 'pendingLoginFlood';

/** The capacity of the gateway's pending-login store. Stated here: it is the bound under proof. */
const PENDING_LOGIN_CAPACITY = 10000;

/** Phase A: with this many logins started after it, the first login is still stored. */
const FLOOD_KEEPING_THE_FIRST = PENDING_LOGIN_CAPACITY - 1;

/** Phase B: with this many logins started after it, the first login has been dropped. */
const FLOOD_DROPPING_THE_FIRST = PENDING_LOGIN_CAPACITY;

/** How many logins of a flood are started in parallel. */
const FLOOD_BATCH = 25;

/**
 * The oldest a phase's first login may be when it is completed, in milliseconds: the five-minute
 * lifetime of a pending record, less a margin for the completion itself.
 */
const MAX_FIRST_LOGIN_AGE_MS = 270000;

// The gateway-owned login-initiation path (oidc.login.path). Each request to it starts one login:
// the gateway stores one pending record, sets the browser-binding cookie and redirects to the
// identity provider's authorization endpoint.
const LOGIN_URL = __ENV.LOGIN_URL || targetUrl('/auth/login');

// The session route. With a session it answers 200; without one a non-navigation request gets 401.
const SESSION_URL = __ENV.TARGET_URL || targetUrl('/bff-session/get');

// The gateway's callback (the path of oidc.redirect_uri), where a completed login ends up.
const CALLBACK_URL = targetUrl('/auth/callback');

const READINESS_URL = managementUrl('/health/ready');

// Matches SessionCookieCodec.DEFAULT_COOKIE_NAME.
const SESSION_COOKIE_NAME = __ENV.SESSION_COOKIE_NAME || '__Host-sheriff-session';

// The integration realm's user, as in session_mediated.js: the session route is mediated for that
// realm, not for the benchmark realm of the bearer aspect.
const KEYCLOAK_USERNAME = __ENV.KEYCLOAK_USERNAME || 'integration-user';
const KEYCLOAK_PASSWORD = __ENV.KEYCLOAK_PASSWORD || 'integration-password';

const AUTHORIZATION_ENDPOINT_PATH = '/protocol/openid-connect/auth';

const floodKeepingStarted = new Counter('pending_login_flood_a_logins_started');
const floodDroppingStarted = new Counter('pending_login_flood_b_logins_started');
const firstLoginKept = new Counter('pending_login_flood_a_first_login_kept');
const firstLoginDropped = new Counter('pending_login_flood_b_first_login_dropped');
const newestLoginKept = new Counter('pending_login_flood_b_newest_login_kept');
const gatewayReady = new Counter('pending_login_flood_gateway_ready');

export const options = {
    scenarios: {
        default: {
            executor: 'shared-iterations',
            vus: 1,
            iterations: 1,
            // Two phases, each bounded by the five-minute lifetime of a pending record.
            maxDuration: '12m',
        },
    },
    batch: FLOOD_BATCH,
    batchPerHost: FLOOD_BATCH,
    summaryTrendStats: SUMMARY_TREND_STATS,
    insecureSkipTLSVerify: true,
    thresholds: {
        pending_login_flood_a_logins_started: [`count==${FLOOD_KEEPING_THE_FIRST}`],
        pending_login_flood_b_logins_started: [`count==${FLOOD_DROPPING_THE_FIRST}`],
        pending_login_flood_a_first_login_kept: ['count==1'],
        pending_login_flood_b_first_login_dropped: ['count==1'],
        pending_login_flood_b_newest_login_kept: ['count==1'],
        pending_login_flood_gateway_ready: ['count==1'],
        checks: ['rate==1'],
    },
};

/**
 * Opens one login up to the identity provider's login form, in a cookie jar of its own.
 *
 * The request chain is the one a browser follows: the gateway stores the pending record, sets the
 * binding cookie and redirects; the identity provider serves its login form. The login is then left
 * there. A login that does not reach the form aborts the run: nothing after it would be what it
 * claims to be.
 *
 * @param {string} which what this login is, for the failure message
 * @returns {{jar: object, formAction: string, openedAt: number}} the opened login
 */
function openLogin(which) {
    const jar = new http.CookieJar();
    const loginPage = http.get(LOGIN_URL, { jar: jar, redirects: 10, tags: { step: 'open' } });
    if (loginPage.status !== 200) {
        fail(`${which}: ${LOGIN_URL} did not reach a login form: HTTP ${loginPage.status}`);
    }
    const formAction = extractFormAction(loginPage.body);
    if (!formAction) {
        fail(`${which}: the login form at ${loginPage.url} carried no form action`);
    }
    return { jar: jar, formAction: formAction, openedAt: Date.now() };
}

/**
 * Completes an opened login: posts the credentials to the identity provider and follows the redirect
 * to the gateway's callback, with the cookies of that login.
 *
 * @param {{jar: object, formAction: string}} login the opened login
 * @returns {object} the last response of the chain
 */
function completeLogin(login) {
    return http.post(
        login.formAction,
        { username: KEYCLOAK_USERNAME, password: KEYCLOAK_PASSWORD },
        { jar: login.jar, redirects: 10, tags: { step: 'complete' } },
    );
}

/**
 * Starts `count` logins and leaves them unfinished. Each is one request to the login-initiation
 * path in a cookie jar of its own, not followed: the gateway has stored the record by the time it
 * answers the redirect. Every answer must be that redirect, or the run aborts -- a flood that
 * started fewer logins than it counts proves nothing about the bound.
 *
 * @param {number} count how many logins to start
 * @param {object} started the counter that records each started login
 */
function flood(count, started) {
    let remaining = count;
    while (remaining > 0) {
        const size = Math.min(FLOOD_BATCH, remaining);
        const requests = [];
        for (let index = 0; index < size; index++) {
            requests.push({
                method: 'GET',
                url: LOGIN_URL,
                params: { jar: new http.CookieJar(), redirects: 0, tags: { step: 'flood' } },
            });
        }
        for (const response of http.batch(requests)) {
            if (!startedALogin(response)) {
                fail(`a login of the flood was not started: HTTP ${response.status} from ${LOGIN_URL}`);
            }
            started.add(1);
        }
        remaining -= size;
    }
}

/**
 * Whether a response is the gateway's redirect of a started login to the authorization endpoint.
 *
 * @param {object} response the response to the login-initiation request
 * @returns {boolean} true for a 302 to the identity provider's authorization endpoint
 */
function startedALogin(response) {
    const location = response.headers['Location'];
    return response.status === 302 && typeof location === 'string'
        && location.indexOf(AUTHORIZATION_ENDPOINT_PATH) >= 0;
}

/**
 * Extracts the login form's POST action from the identity provider's login page.
 *
 * @param {string} html the login-page body
 * @returns {string|null} the form action URL, or null when no form is present
 */
function extractFormAction(html) {
    if (typeof html !== 'string') {
        return null;
    }
    const match = html.match(/<form[^>]*\baction="([^"]+)"/i);
    // The action is HTML-escaped; the ampersands between its query parameters are unescaped.
    return match ? match[1].replace(/&amp;/g, '&') : null;
}

/**
 * Whether the jar of a login holds the gateway's session cookie.
 *
 * @param {object} jar the cookie jar of the login
 * @returns {boolean} true when the gateway set a session cookie
 */
function holdsSessionCookie(jar) {
    const values = jar.cookiesForURL(baseUrl() + '/')[SESSION_COOKIE_NAME];
    return Array.isArray(values) && values.length > 0 && values[0] !== '';
}

/**
 * One non-navigation request on the session route with the cookies of a login.
 *
 * @param {object} jar the cookie jar of the login
 * @returns {number} the status: 200 with a session, 401 without one
 */
function sessionRouteStatus(jar) {
    return http.get(SESSION_URL, {
        jar: jar,
        redirects: 0,
        headers: { Accept: 'application/json' },
        tags: { step: 'session' },
    }).status;
}

/**
 * Whether a completed login yielded a session: the session cookie is set and the session route
 * serves it.
 *
 * @param {object} login the completed login
 * @param {string} which what this login is, for the check names
 * @returns {boolean} true when both hold
 */
function yieldedASession(login, which) {
    return check(login, {
        [`${which}: the gateway set a session cookie`]: (l) => holdsSessionCookie(l.jar),
        [`${which}: the session route answers 200`]: (l) => sessionRouteStatus(l.jar) === 200,
    });
}

export default function () {
    // Phase A: 1 + 9 999 logins. The first is the oldest of exactly as many records as the store holds.
    const keptFirst = openLogin('phase A, the first login');
    flood(FLOOD_KEEPING_THE_FIRST, floodKeepingStarted);
    completeLogin(keptFirst);
    if (yieldedASession(keptFirst, `the first of ${PENDING_LOGIN_CAPACITY} started logins`)) {
        firstLoginKept.add(1);
    }

    // Phase B: 1 + 10 000 logins. The last of the 10 000 is opened up to the login form, so it can be
    // completed afterwards; it is one started login like the others.
    const droppedFirst = openLogin('phase B, the first login');
    flood(FLOOD_DROPPING_THE_FIRST - 1, floodDroppingStarted);
    const newest = openLogin('phase B, the newest login of the flood');
    floodDroppingStarted.add(1);

    const firstLoginAge = Date.now() - droppedFirst.openedAt;
    const refused = completeLogin(droppedFirst);
    const dropped = check(refused, {
        'the dropped login was completed inside the lifetime of a pending record':
            () => firstLoginAge < MAX_FIRST_LOGIN_AGE_MS,
        // The refusal has to be the gateway's: the identity provider accepted the credentials and
        // redirected to the callback, and the callback is where the chain ended.
        'the dropped login reached the callback of the gateway': (r) => r.url.indexOf(CALLBACK_URL) === 0,
        'the callback of the dropped login is refused': (r) => r.status >= 400 && r.status < 500,
        'the dropped login set no session cookie': () => !holdsSessionCookie(droppedFirst.jar),
        'the session route answers the dropped login 401': () => sessionRouteStatus(droppedFirst.jar) === 401,
    });
    if (dropped) {
        firstLoginDropped.add(1);
    }

    completeLogin(newest);
    if (yieldedASession(newest, `the newest of ${PENDING_LOGIN_CAPACITY + 1} started logins`)) {
        newestLoginKept.add(1);
    }

    const readiness = http.get(READINESS_URL, { tags: { step: 'readiness' } });
    if (check(readiness, { 'the gateway still answers its readiness probe': (r) => r.status === 200 })) {
        gatewayReady.add(1);
    }
}

export function handleSummary(data) {
    return buildSummary(BENCHMARK_NAME, data, { published: false });
}
