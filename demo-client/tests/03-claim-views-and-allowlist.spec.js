import { captureState, expect, test } from '../fixtures/test-fixtures.js';
import { login } from '../utils/keycloak-login.js';
import { CLAIMS, FULL_VIEW, RESERVED } from '../utils/constants.js';

/**
 * The claim views and the operator allowlist cap.
 *
 * The load-bearing property is the last test: the OPERATOR — never the browser — widens disclosure.
 * A claim outside `allowed_claims` is refused `403` before any disclosure happens, even though the
 * identity provider issued it and it is present in the validated ID token.
 */
test.describe('claim views and the operator allowlist', () => {
  test.beforeEach(async ({ demoPage }) => {
    await login(demoPage);
  });

  test('an explicit selection discloses exactly the named claims', async ({ probe }) => {
    const result = await probe(`${RESERVED.userInfo}?claims=email`);

    expect(result.status).toBe(200);
    expect(result.cacheControl).toBe('no-store');
    expect(Object.keys(result.body.claims)).toEqual(['email']);
  });

  test('claims=* discloses the full allowlisted view and nothing beyond it', async ({
    demoPage,
    probe,
  }, testInfo) => {
    const result = await probe(`${RESERVED.userInfo}?claims=${encodeURIComponent(FULL_VIEW)}`);

    expect(result.status).toBe(200);
    expect(result.cacheControl).toBe('no-store');
    // The widest disclosure that exists is still capped by the operator's allowlist, not by the
    // client's request.
    expect(Object.keys(result.body.claims).sort()).toEqual([...CLAIMS.allowed].sort());
    expect(Object.keys(result.body.claims)).not.toContain(CLAIMS.disallowed);

    await demoPage.getByTestId('claim-view').selectOption('full');
    await expect(demoPage.getByTestId('response-status')).toHaveText('200');
    await captureState(demoPage, testInfo, 'full-allowlisted-view');
  });

  test('a claim outside the allowlist is refused 403 before any disclosure', async ({
    demoPage,
    probe,
  }, testInfo) => {
    const result = await probe(`${RESERVED.userInfo}?claims=${CLAIMS.disallowed}`);

    expect(result.status).toBe(403);
    expect(result.contentType).toContain('application/problem+json');
    expect(result.cacheControl).toBe('no-store');
    // The refusal is total: the problem document carries no claims member at all.
    expect(result.body).not.toHaveProperty('claims');

    await demoPage.getByTestId('claim-view').selectOption('denied');
    await expect(demoPage.getByTestId('response-status')).toHaveText('403');
    await expect(demoPage.getByTestId('problem')).toBeVisible();
    await captureState(demoPage, testInfo, 'claim-denied');
  });
});
