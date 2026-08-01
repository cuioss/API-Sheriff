/**
 * The coordinates the suite drives: the gateway's reserved paths, the demo SPA entry points, and the
 * Keycloak realm credentials.
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

/** The `integration` realm's test user, imported from integration-realm.json. */
export const REALM_USER = {
  username: process.env.KEYCLOAK_USERNAME ?? 'integration-user',
  password: process.env.KEYCLOAK_PASSWORD ?? 'integration-password',
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
