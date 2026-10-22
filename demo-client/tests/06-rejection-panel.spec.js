import { captureState, expect, test } from '../fixtures/test-fixtures.js';

/**
 * The gateway's own rejections, as the SPA's rejection panel observes them while anonymous.
 *
 * Unlike the reserved endpoints, these DO negotiate on `Accept`, and that is the point of the
 * panel: an unrouted path and a method outside a route's allowlist keep their status and answer an
 * explicit `text/html` request with the HTML error page and anything else with
 * `application/problem+json`; a session route's floor answers the same split with a `302` into
 * login versus a `401`. The info endpoint row stays `Accept`-blind and is only ever asked for JSON,
 * so nothing here contradicts the PROHIBITED ASSERTION of `01-anonymous-contract.spec.js`.
 *
 * Both demo instances negotiate identically, so every expectation holds in both session-mode
 * projects as written. There is no per-project branch, and none may be added: a difference between
 * the two instances is a finding, not something to condition away.
 *
 * Each row's `redirected` cell reads `yes` only for an opaque redirect: the panel fetches with
 * `redirect: 'manual'`, so the `302` into the identity provider is observed, never followed.
 */
const EXPECTATIONS = [
  { id: 'userinfo-xhr', status: '401', contentType: 'application/problem+json', redirected: 'no' },
  { id: 'session-xhr', status: '401', contentType: 'application/problem+json', redirected: 'no' },
  { id: 'session-navigation', redirected: 'yes' },
  { id: 'unrouted-navigation', status: '404', contentType: 'text/html', redirected: 'no' },
  { id: 'unrouted-xhr', status: '404', contentType: 'application/problem+json', redirected: 'no' },
  { id: 'method-navigation', status: '405', contentType: 'text/html', redirected: 'no' },
];

test.describe('gateway rejection panel', () => {
  test('every probe renders the rejection the gateway negotiates for it', async ({
    demoPage,
  }, testInfo) => {
    // The panel is meaningful in the anonymous state: the session floor rejects before any
    // upstream is reached, so every answer is the gateway's own.
    await expect(demoPage.getByTestId('auth-state')).toHaveText('anonymous');

    await demoPage.getByTestId('fire-probes').click();

    for (const expected of EXPECTATIONS) {
      await expect(demoPage.getByTestId(`probe-${expected.id}-redirected`)).toHaveText(
        expected.redirected
      );
      if (expected.status !== undefined) {
        await expect(demoPage.getByTestId(`probe-${expected.id}-status`)).toHaveText(
          expected.status
        );
      }
      if (expected.contentType !== undefined) {
        await expect(demoPage.getByTestId(`probe-${expected.id}-content-type`)).toContainText(
          expected.contentType
        );
      }
    }

    await captureState(demoPage, testInfo, 'rejection-panel');
  });
});
