import { captureState, expect, test } from '../fixtures/test-fixtures.js';
import { login } from '../utils/keycloak-login.js';
import { RESERVED, SPA } from '../utils/constants.js';

/**
 * RP-initiated logout: the full round-trip through the IdP end-session leg and the state-verified
 * return leg, ending on the configured `final_redirect`.
 *
 * THE AUTHORITATIVE COMPLETION PROOF IS THE 401 RE-PROBE. The landing page improves the
 * human-visible flow and gives the screenshot something to show; it does NOT replace that assertion,
 * and a green landing page with a live session still fails this spec.
 */
test.describe('logout', () => {
  test('the round-trip lands on the landing page and the session is genuinely destroyed', async ({
    demoPage,
    probe,
  }, testInfo) => {
    await login(demoPage);

    await demoPage.getByTestId('logout').click();

    // final_redirect names a CONCRETE FILE under the already-existing public asset anchor. It cannot
    // be '/': a path_prefix: / anchor fails the pairwise-disjointness boot check fail-closed, and the
    // directory asset source performs no directory-index resolution.
    await demoPage.waitForURL(`**${SPA.landing}`);
    await expect(demoPage.getByTestId('landing')).toBeVisible();

    // The landing page links back to the demo, and the link works.
    const back = demoPage.getByTestId('back-to-demo');
    await expect(back).toBeVisible();

    // THE assertion: the session is gone, not merely that a static page rendered.
    const afterLogout = await probe(RESERVED.userInfo);
    expect(afterLogout.status).toBe(401);
    expect(afterLogout.contentType).toContain('application/problem+json');
    expect(afterLogout.cacheControl).toBe('no-store');

    await captureState(demoPage, testInfo, 'logged-out');

    await back.click();
    await expect(demoPage).toHaveURL((url) => url.pathname === SPA.index);
    await expect(demoPage.getByTestId('auth-state')).toHaveText('anonymous');
  });

  test('the landing page is public static content, reachable without a session', async ({ page }) => {
    const response = await page.goto(SPA.landing);

    expect(response.status()).toBe(200);
    expect(response.headers()['content-type']).toContain('text/html');
    // The gateway owns the response envelope regardless of the served content.
    expect(response.headers()['x-content-type-options']).toBe('nosniff');
  });
});
