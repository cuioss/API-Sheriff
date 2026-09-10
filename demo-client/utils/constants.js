/**
 * The coordinates the suite drives: the gateway's reserved paths, the demo SPA entry points, the
 * Keycloak realm credentials, and the one browser limit the suite asserts against.
 *
 * No port number appears here. Ports live in the POM properties and reach the suite as environment
 * variables, so a published-port change is made in exactly one place.
 */

/** The gateway-owned reserved paths (exact-match, on the OIDC host). */
export const RESERVED = {
  /** Login initiation. Answers 302 into the OIDC authorization endpoint — a NAVIGATION, never a fetch. */
  login: '/auth/login',
  /** The XHR identity probe. 200 with a live session, 401 application/problem+json without one — never a redirect. */
  userInfo: '/auth/userinfo',
  /** RP-initiated logout. Answers 302 into the IdP end-session leg — a NAVIGATION. */
  logout: '/auth/logout',
};

/**
 * The demo SPA entry points.
 *
 * Both name a CONCRETE FILE. The gateway's directory asset source performs no directory-index
 * resolution, so `/assets/demo/` without a filename returns 404.
 */
export const SPA = {
  /** The demo application. */
  index: '/assets/demo/index.html',
  /** The post-logout landing target, configured as oidc.logout.final_redirect. */
  landing: '/assets/demo/landing.html',
};

/**
 * The `integration` realm's test user, imported from integration-realm.json.
 *
 * Stated literally rather than read from the environment: the suite drives the realm that
 * docker-compose imports, so these credentials are a property of that fixed realm, not a knob.
 */
export const REALM_USER = {
  username: 'integration-user',
  password: 'integration-password',
};

/**
 * The operator's claim allowlist as the two demo gateways configure it
 * (`oidc.user_info.allowed_claims` / `default_view`). The suite asserts against these rather than
 * against whatever the IdP happens to mint, because the ALLOWLIST — not the token — is what bounds
 * disclosure.
 */
export const CLAIMS = {
  /** Disclosed when no `claims` parameter is supplied. */
  defaultView: ['sub', 'preferred_username'],
  /** The full allowlisted view, selected by `claims=*`. */
  allowed: ['email', 'groups', 'preferred_username', 'sub'],
  /** Present in the validated ID token, deliberately OUTSIDE the allowlist — earns a 403. */
  disallowed: 'given_name',
};

/** The `claims` parameter value selecting the full allowlisted view (UserInfoEndpoint.FULL_VIEW). */
export const FULL_VIEW = '*';

/** Where the documentation screenshots land, one parallel set per session-mode project. */
export const SCREENSHOT_DIR = 'target/screenshots';

/**
 * The per-cookie byte floor RFC 6265 section 6.1 obliges a user agent to support: at least 4096
 * bytes per cookie, measured over the name, the value AND the attributes — that is, over the whole
 * `Set-Cookie` header value.
 *
 * This is deliberately the suite's own constant, not a value read from the gateway. The gateway
 * carries its own idea of this number (`SealedSessionCookieCodec.BROWSER_PER_COOKIE_HEADER_GUARANTEE`)
 * and derives its configured budget from it, so a test that imported either could not detect the
 * gateway getting the browser's limit wrong — it would assert the product against itself and pass by
 * construction. The browser does not read our configuration; 4096 is its number, so the suite states
 * it independently. The two are expected to agree; the point is that nothing makes them agree.
 *
 * It is a FLOOR rather than the limit: a browser may store more, and Chromium does. A measurement
 * above this number is observed headroom, never licence to raise what the gateway emits — the floor
 * is what every browser guarantees, and the gateway ships to all of them.
 */
export const BROWSER_COOKIE_BUDGET_BYTES = 4096;
