/**
 * API Sheriff BFF demo client.
 *
 * The whole client, deliberately in one readable file: no framework, no bundler, no build step.
 * It exercises the gateway's browser-facing contract and nothing else.
 *
 * Four rules govern everything below, and each is a property of the gateway rather than a style
 * choice here:
 *
 *   1. `/bff/session` is an XHR PROBE. It answers `200 application/json` with a live session and
 *      `401 application/problem+json` without one. It NEVER redirects, and it does not vary on
 *      `Accept`. This client therefore fetches it and branches on the status code -- it must not
 *      follow or expect a redirect from it.
 *   2. `/bff/login` and `/bff/logout` are NAVIGATIONS. They answer `302` into the identity
 *      provider, which only the browser can follow meaningfully. This client uses
 *      `window.location.assign(...)` for both, never `fetch`.
 *   3. No token material exists here to handle. The endpoint discloses none, the session cookie is
 *      `HttpOnly`, and this client neither reads a cookie nor writes to storage. Nothing is logged.
 *   4. Unlike the reserved endpoints, the rejections the panel probes DO negotiate on `Accept`.
 *      With `portal.error_pages: true` (both demo instances set it), an unrouted path or a method
 *      outside a route's allowlist keeps its status and only changes its body: the HTML error
 *      page for a request that lists `text/html` explicitly, `application/problem+json`
 *      otherwise. A session route's floor answers such a request with a `302` into login and
 *      anything else with a `401`. Not every exit negotiates -- a filter `400` or a bearer `401`
 *      keeps its shape whatever `Accept` says. The rejection panel demonstrates the split.
 */

const USERINFO_PATH = '/bff/session';
const LOGIN_PATH = '/bff/login';
const LOGOUT_PATH = '/bff/logout';

const EM_DASH = '—';

const ACCEPT_JSON = 'application/json';
const ACCEPT_HTML = 'text/html';

/** A path under the `/app` session route (`require: session`), whose floor rejects anonymously. */
const SESSION_ROUTE_PATH = '/app/rejection-probe';

/** A path no anchor covers, so deny-by-default routing answers `404`. */
const UNROUTED_PATH = '/unrouted/rejection-probe';

/** This page itself, served by the public `/assets` anchor (`GET`/`HEAD` only). */
const DEMO_PAGE_PATH = '/assets/demo/index.html';

/**
 * The gateway-rejection probes. Each is ONE same-origin request, and none reaches an upstream: the
 * reserved endpoint answers itself, the session floor rejects before anything is forwarded, and
 * the routing rejections never leave the gateway.
 *
 * `userinfo-xhr` is deliberately sent with JSON only. The info endpoint is `Accept`-blind (rule 1),
 * so it is never asked for `text/html` -- a probe that did would demonstrate nothing.
 */
const REJECTION_PROBES = [
  { id: 'userinfo-xhr', method: 'GET', path: USERINFO_PATH, accept: ACCEPT_JSON },
  { id: 'session-xhr', method: 'GET', path: SESSION_ROUTE_PATH, accept: ACCEPT_JSON },
  { id: 'session-navigation', method: 'GET', path: SESSION_ROUTE_PATH, accept: ACCEPT_HTML },
  { id: 'unrouted-navigation', method: 'GET', path: UNROUTED_PATH, accept: ACCEPT_HTML },
  { id: 'unrouted-xhr', method: 'GET', path: UNROUTED_PATH, accept: ACCEPT_JSON },
  // The asset anchor allows GET and HEAD only, so the verb gate answers 405.
  { id: 'method-navigation', method: 'DELETE', path: DEMO_PAGE_PATH, accept: ACCEPT_HTML },
];

/**
 * The four disclosure states the operator's claim allowlist makes reachable. The last one asks for
 * a claim OUTSIDE the allowlist on purpose, so its `403` is a demonstrated state rather than an
 * invisible edge case.
 *
 * `claims` semantics: absent selects the operator's curated default view, `*` selects the full
 * allowlisted view, and any other value is a comma-separated list of specific claim names.
 */
const CLAIM_VIEWS = [
  { id: 'default', label: 'Curated default view (no claims parameter)', claims: null },
  { id: 'selected', label: 'Explicit selection: email', claims: 'email' },
  { id: 'full', label: 'Full allowlisted view (claims=*)', claims: '*' },
  { id: 'denied', label: 'Outside the allowlist: given_name (expects 403)', claims: 'given_name' },
];

const el = (testId) => document.querySelector(`[data-testid="${testId}"]`);

/**
 * Replaces an element's entire content with a single text node.
 *
 * Every value rendered by this client comes from the identity provider by way of the gateway, and
 * is written through `textContent` rather than `innerHTML`. There is no HTML-building sink in this
 * file at all, which is what keeps a hostile claim value inert.
 *
 * @param {Element} target the element to fill
 * @param {string} text the text to render
 */
function setText(target, text) {
  target.textContent = text;
}

/**
 * Renders a key/value map into a definition list, replacing whatever was there.
 *
 * @param {Element} list the `<dl>` to fill
 * @param {Object} values the map to render; an empty map renders the placeholder instead
 * @param {string} emptyMessage the placeholder shown for an empty map
 */
function renderPairs(list, values, emptyMessage) {
  list.replaceChildren();
  const entries = Object.entries(values ?? {});
  if (entries.length === 0) {
    const placeholder = document.createElement('dd');
    placeholder.className = 'empty';
    setText(placeholder, emptyMessage);
    list.append(placeholder);
    return;
  }
  for (const [name, value] of entries) {
    const term = document.createElement('dt');
    setText(term, name);
    const detail = document.createElement('dd');
    detail.dataset.claim = name;
    setText(detail, Array.isArray(value) ? value.join(', ') : String(value));
    list.append(term, detail);
  }
}

/**
 * Builds the info-endpoint URL for a claim view.
 *
 * @param {?string} claims the `claims` parameter value, or `null` for the curated default view
 * @returns {string} the same-origin request path
 */
function userInfoUrl(claims) {
  if (claims === null) {
    return USERINFO_PATH;
  }
  return `${USERINFO_PATH}?claims=${encodeURIComponent(claims)}`;
}

/**
 * Probes the info endpoint for one claim view.
 *
 * `credentials: 'same-origin'` attaches the gateway's `HttpOnly` session cookie -- the only reason
 * the SPA has to be served same-origin with the reserved paths. `redirect: 'error'` encodes rule 1
 * above as a runtime assertion: this endpoint must never redirect, so a redirect is a contract
 * violation rather than something to follow.
 *
 * @param {?string} claims the `claims` parameter value, or `null`
 * @returns {Promise<{status: number, contentType: string, cacheControl: string, body: ?Object}>}
 *          the observed response
 */
async function probeUserInfo(claims) {
  const response = await fetch(userInfoUrl(claims), {
    credentials: 'same-origin',
    redirect: 'error',
    headers: { Accept: 'application/json' },
  });

  let body;
  try {
    body = await response.json();
  } catch {
    // A body-less or non-JSON response is itself reportable state; the status carries the meaning.
    body = null;
  }

  return {
    status: response.status,
    contentType: response.headers.get('content-type') ?? '',
    cacheControl: response.headers.get('cache-control') ?? '',
    body,
  };
}

/**
 * Renders one probe result across the response, claims and session panels.
 *
 * @param {{status: number, contentType: string, cacheControl: string, body: ?Object}} result
 *        the observed response
 */
function render(result) {
  setText(el('response-status'), String(result.status));
  setText(el('response-content-type'), result.contentType || EM_DASH);
  setText(el('response-cache-control'), result.cacheControl || EM_DASH);

  const problem = el('problem');
  const authenticated = result.status === 200;

  el('login').hidden = authenticated;
  el('logout').hidden = !authenticated;

  if (authenticated) {
    setText(el('auth-state'), 'authenticated');
    setText(el('status'), 'A live session is present.');
    problem.hidden = true;
    setText(problem, '');
    renderPairs(el('claims'), result.body?.claims, 'No claims disclosed.');
    renderPairs(el('session'), result.body?.session, 'No session metadata.');
    return;
  }

  // 401 (no live session) and 403 (a claim outside the operator allowlist) are both RFC 9457
  // problem documents. Neither is an error to hide: they are the contract being demonstrated.
  const title = result.body?.title ?? 'Request refused';
  problem.hidden = false;
  setText(problem, `${result.status} ${EM_DASH} ${title}`);

  if (result.status === 403) {
    setText(el('auth-state'), 'authenticated');
    setText(el('status'), 'The session is live; the operator allowlist refused the claim.');
    renderPairs(el('claims'), null, 'Refused -- claim outside the operator allowlist.');
    return;
  }

  setText(el('auth-state'), 'anonymous');
  setText(el('status'), 'No live session. Log in to see the disclosed identity.');
  renderPairs(el('claims'), null, 'No claims disclosed.');
  renderPairs(el('session'), null, 'No live session.');
}

/**
 * Renders a probe that never produced a response at all.
 *
 * Two causes reach here, and the second is the one worth seeing: a transport or TLS failure, and a
 * REDIRECT -- `redirect: 'error'` (rule 1) turns the redirect this endpoint must never send into a
 * rejected promise. Without this the page would sit on its initial `Loading...` forever and the
 * contract violation would exist only as an unhandled rejection in the console.
 *
 * Every panel `render` owns is reset here, so a failure after a success leaves no stale identity on
 * screen. Both navigation buttons are hidden because a failed probe discloses nothing about whether
 * a session exists -- which is exactly what the `unknown` auth state says.
 *
 * @param {unknown} reason the rejection value from the probe
 */
function renderTransportFailure(reason) {
  setText(el('response-status'), EM_DASH);
  setText(el('response-content-type'), EM_DASH);
  setText(el('response-cache-control'), EM_DASH);

  setText(el('auth-state'), 'unknown');
  setText(el('status'), 'The probe did not complete. No response was observed.');

  el('login').hidden = true;
  el('logout').hidden = true;

  // The message only: it describes the transport, never a response body, and there is no token
  // material anywhere in this client to leak into it.
  const detail = reason instanceof Error ? reason.message : String(reason);
  const problem = el('problem');
  problem.hidden = false;
  setText(problem, `Request failed ${EM_DASH} ${detail}`);

  renderPairs(el('claims'), null, 'No claims disclosed.');
  renderPairs(el('session'), null, 'No live session.');
}

/**
 * Runs the selected claim view and renders the outcome.
 *
 * @returns {Promise<void>} resolves once the panels are updated
 */
async function requestSelectedView() {
  const selected = CLAIM_VIEWS.find((view) => view.id === el('claim-view').value);
  try {
    render(await probeUserInfo(selected ? selected.claims : null));
  } catch (reason) {
    renderTransportFailure(reason);
  }
}

/**
 * Fills one probe row's three cells.
 *
 * @param {string} id the probe id the row's `data-testid` hooks are named after
 * @param {string} status the status cell text
 * @param {string} contentType the Content-Type cell text
 * @param {string} redirected the redirected cell text
 */
function renderProbeRow(id, status, contentType, redirected) {
  setText(el(`probe-${id}-status`), status);
  setText(el(`probe-${id}-content-type`), contentType);
  setText(el(`probe-${id}-redirected`), redirected);
}

/** The current probe run. A probe renders only while its own run is still the current one. */
let probeRun = 0;

/**
 * Fires one rejection probe and renders its row, unless a newer run has started meanwhile.
 *
 * `redirect: 'manual'` is what makes a redirect observable instead of followed: under it the
 * browser hands back every redirect, same-origin or not, as an opaque redirect
 * (`response.type === 'opaqueredirect'`, no status, no headers) — here the `302` into login the
 * session floor answers a `text/html` request with. No response body is read at all, so no error
 * page content can reach the DOM. A transport failure is rendered on its own row and never breaks
 * the others.
 *
 * @param {{id: string, method: string, path: string, accept: string}} probe the probe to fire
 * @param {number} run the run this probe belongs to
 * @returns {Promise<void>} resolves once the probe has answered, whatever the outcome
 */
async function fireRejectionProbe(probe, run) {
  const renderCurrent = (...cells) => {
    if (run === probeRun) {
      renderProbeRow(probe.id, ...cells);
    }
  };
  try {
    const response = await fetch(probe.path, {
      method: probe.method,
      credentials: 'same-origin',
      redirect: 'manual',
      headers: { Accept: probe.accept },
    });
    if (response.type === 'opaqueredirect') {
      renderCurrent(EM_DASH, EM_DASH, 'yes');
      return;
    }
    const contentType = response.headers.get('content-type') || EM_DASH;
    renderCurrent(String(response.status), contentType, 'no');
  } catch (reason) {
    const detail = reason instanceof Error ? reason.message : String(reason);
    renderCurrent(`failed ${EM_DASH} ${detail}`, EM_DASH, EM_DASH);
  }
}

/**
 * Fires every rejection probe as a new run, clearing the rows first. A probe of an earlier run
 * that answers late renders nothing, so a re-run never shows a stale answer.
 *
 * @returns {Promise<void>} resolves once every probe of this run has answered
 */
async function fireRejectionProbes() {
  probeRun += 1;
  const run = probeRun;
  for (const probe of REJECTION_PROBES) {
    renderProbeRow(probe.id, EM_DASH, EM_DASH, EM_DASH);
  }
  await Promise.all(REJECTION_PROBES.map((probe) => fireRejectionProbe(probe, run)));
}

/**
 * Wires the claim-view selector, the two navigation buttons and the rejection probes.
 */
function wireControls() {
  const selector = el('claim-view');
  for (const view of CLAIM_VIEWS) {
    const option = document.createElement('option');
    option.value = view.id;
    setText(option, view.label);
    selector.append(option);
  }

  el('apply-view').addEventListener('click', () => {
    void requestSelectedView();
  });
  selector.addEventListener('change', () => {
    void requestSelectedView();
  });

  // Login is a TOP-LEVEL NAVIGATION, never a fetch: the gateway answers 302 into the OIDC
  // authorization endpoint, and following that is the browser's job. The returnUrl is this page's
  // own URL -- always same-origin, because the gateway serves this page. An off-origin returnUrl
  // would not be an error; the gateway would silently fall back to its default return URL.
  el('login').addEventListener('click', () => {
    window.location.assign(`${LOGIN_PATH}?returnUrl=${encodeURIComponent(window.location.href)}`);
  });

  // Logout is likewise a top-level navigation: the RP-initiated round-trip goes through the IdP
  // end-session leg and the state-verified return leg before landing on the configured
  // final_redirect.
  el('logout').addEventListener('click', () => {
    window.location.assign(LOGOUT_PATH);
  });

  el('fire-probes').addEventListener('click', () => {
    void fireRejectionProbes();
  });
}

wireControls();
void requestSelectedView();
