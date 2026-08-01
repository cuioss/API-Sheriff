import { captureState, expect, test } from '../fixtures/test-fixtures.js';
import { RESERVED, SPA } from '../utils/constants.js';

/**
 * The anonymous contract: what a browser observes with no session.
 *
 * PROHIBITED ASSERTION — this suite MUST NOT send `Accept: text/html` (or any `Accept` value) to the
 * info endpoint expecting a `302`, and MUST NOT assert the endpoint varies on `Accept`. Both reserved
 * endpoints are `Accept`-blind: the info endpoint always answers `401 application/problem+json`
 * without a session, and the login endpoint always answers `302`. The split is PATH-based.
 */
test.describe('anonymous contract', () => {
  test('the SPA is served by the gateway, same-origin with the reserved paths', async ({
    demoPage,
  }) => {
    // Served through the gateway-owned asset envelope. Note the explicit filename: the directory
    // asset source performs no directory-index resolution.
    await expect(demoPage).toHaveURL((url) => url.pathname === SPA.index);
    await expect(demoPage.getByRole('heading', { name: 'API Sheriff BFF Demo' })).toBeVisible();
  });

  test('the info endpoint answers 401 problem+json, uncacheable, and never redirects', async ({
    demoPage,
    probe,
  }) => {
    // The probe runs inside the page context, so the page must be on the SPA origin first.
    await expect(demoPage).toHaveURL((url) => url.pathname === SPA.index);

    const result = await probe(RESERVED.userInfo);

    expect(result.status).toBe(401);
    expect(result.contentType).toContain('application/problem+json');
    // Every info response — success or error — is uncacheable, so an identity view is never written
    // to a shared cache or the browser's.
    expect(result.cacheControl).toBe('no-store');
    // The endpoint is an XHR probe. A redirect here would be a contract violation, not something a
    // client should follow.
    expect(result.redirected).toBe(false);
    expect(result.status).not.toBe(302);
  });

  test('the SPA renders the login affordance and discloses nothing', async ({ demoPage }, testInfo) => {
    await expect(demoPage.getByTestId('auth-state')).toHaveText('anonymous');
    await expect(demoPage.getByTestId('login')).toBeVisible();
    await expect(demoPage.getByTestId('logout')).toBeHidden();
    await expect(demoPage.getByTestId('response-status')).toHaveText('401');
    await expect(demoPage.getByTestId('claims')).toContainText('No claims disclosed.');

    await captureState(demoPage, testInfo, 'anonymous');
  });
});
