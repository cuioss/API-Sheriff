/**
 * API Sheriff BFF demo client.
 *
 * The whole client, deliberately in one readable file: no framework, no bundler, no build step.
 * It exercises the gateway's browser-facing contract and nothing else.
 *
 * Three rules govern everything below, and each is a property of the gateway rather than a style
 * choice here:
 *
 *   1. `/auth/userinfo` is an XHR PROBE. It answers `200 application/json` with a live session and
 *      `401 application/problem+json` without one. It NEVER redirects, and it does not vary on
 *      `Accept`. This client therefore fetches it and branches on the status code -- it must not
 *      follow or expect a redirect from it.
 *   2. `/auth/login` and `/auth/logout` are NAVIGATIONS. They answer `302` into the identity
 *      provider, which only the browser can follow meaningfully. This client uses
 *      `window.location.assign(...)` for both, never `fetch`.
 *   3. No token material exists here to handle. The endpoint discloses none, the session cookie is
 *      `HttpOnly`, and this client neither reads a cookie nor writes to storage. Nothing is logged.
 */

const USERINFO_PATH = '/auth/userinfo';
const LOGIN_PATH = '/auth/login';
const LOGOUT_PATH = '/auth/logout';

const EM_DASH = '—';

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
 * Runs the selected claim view and renders the outcome.
 *
 * @returns {Promise<void>} resolves once the panels are updated
 */
async function requestSelectedView() {
  const selected = CLAIM_VIEWS.find((view) => view.id === el('claim-view').value);
  render(await probeUserInfo(selected ? selected.claims : null));
}

/**
 * Wires the claim-view selector and the two navigation buttons.
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
}

wireControls();
void requestSelectedView();
