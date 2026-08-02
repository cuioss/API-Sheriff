import { captureState, expect, test } from '../fixtures/test-fixtures.js';
import { login } from '../utils/keycloak-login.js';
import { CLAIMS, RESERVED, SPA } from '../utils/constants.js';

/**
 * Login and identity disclosure: the auth-code round-trip through the real IdP, and what the info
 * endpoint discloses once a session exists.
 *
 * Login is a TOP-LEVEL NAVIGATION. The gateway answers `302` into the OIDC authorization endpoint,
 * and only the browser can follow that meaningfully — which is precisely why this suite exists
 * alongside the Java integration tests.
 */
test.describe('login and identity', () => {
  test('login drives the auth-code flow and lands back on the same-origin returnUrl', async ({
    demoPage,
    probe,
  }, testInfo) => {
    await login(demoPage);

    // The callback leg landed the browser back on the returnUrl the SPA supplied — its own URL,
    // always same-origin because the gateway serves the SPA.
    await expect(demoPage).toHaveURL((url) => url.pathname === SPA.index);

    const result = await probe(RESERVED.userInfo);
    expect(result.status).toBe(200);
    expect(result.contentType).toContain('application/json');
    expect(result.cacheControl).toBe('no-store');

    // The curated DEFAULT view: what the operator decided a client sees when it asks for nothing in
    // particular. Exactly these claims, no more.
    expect(Object.keys(result.body.claims).sort()).toEqual([...CLAIMS.defaultView].sort());

    // Session metadata is the gateway's own bookkeeping, never token material.
    expect(result.body.session).toHaveProperty('expires_at');

    await expect(demoPage.getByTestId('auth-state')).toHaveText('authenticated');
    await expect(demoPage.getByTestId('logout')).toBeVisible();
    await expect(demoPage.getByTestId('login')).toBeHidden();

    await captureState(demoPage, testInfo, 'authenticated-default-view');
  });

  test('no token material is exposed to the client', async ({ demoPage, probe }) => {
    await login(demoPage);

    const result = await probe(RESERVED.userInfo);
    const disclosed = JSON.stringify(result.body);
    for (const forbidden of ['access_token', 'refresh_token', 'id_token']) {
      expect(disclosed).not.toContain(forbidden);
    }

    // The session cookie is HttpOnly and belongs to the gateway, and the SPA invents no place to
    // put a token: neither storage backend holds anything.
    const stored = await demoPage.evaluate(() => ({
      local: globalThis.localStorage.length,
      session: globalThis.sessionStorage.length,
    }));
    expect(stored.local).toBe(0);
    expect(stored.session).toBe(0);
  });

  test('login with a live session short-circuits, with no fresh authorization flow', async ({
    demoPage,
  }) => {
    await login(demoPage);

    // A second navigation to the login endpoint while the session is live must redirect straight to
    // the validated returnUrl. The proof that no fresh flow started is that the Keycloak form is
    // never rendered.
    await demoPage.goto(`${RESERVED.login}?returnUrl=${encodeURIComponent(demoPage.url())}`);

    await expect(demoPage).toHaveURL((url) => url.pathname === SPA.index);
    await expect(demoPage.locator('#kc-login')).toHaveCount(0);
    await expect(demoPage.getByTestId('auth-state')).toHaveText('authenticated');
  });
});
