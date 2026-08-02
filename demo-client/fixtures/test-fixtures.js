import { test as base } from '@playwright/test';

import { SCREENSHOT_DIR, SPA } from '../utils/constants.js';

/**
 * Shared fixtures for the demo suite.
 *
 * Two of them, both closing a gap a raw `page` leaves:
 *
 *   - `demoPage`: a page already on the SPA entry, so no spec repeats the navigation or restates the
 *     entry path.
 *   - `probe`: issues same-origin fetches FROM INSIDE the page context, so the HttpOnly session
 *     cookie is attached exactly as the SPA itself would attach it. Driving the same request from
 *     Playwright's APIRequestContext would use a separate cookie jar and would prove something
 *     weaker than what a browser actually observes.
 */

/**
 * The observable shape of one reserved-endpoint response.
 *
 * @typedef {object} ProbeResult
 * @property {number} status the HTTP status
 * @property {boolean} redirected whether the response followed a redirect (must be false for the info endpoint)
 * @property {string} contentType the response Content-Type
 * @property {string} cacheControl the response Cache-Control
 * @property {?object} body the parsed JSON body, or null when the body is not JSON
 */

export const test = base.extend({
  /**
   * A page already sitting on the demo SPA entry.
   *
   * @param {{page: import('@playwright/test').Page}} fixtures the base fixtures
   * @param {Function} use the fixture consumer
   */
  demoPage: async ({ page }, use) => {
    await page.goto(SPA.index);
    await use(page);
  },

  /**
   * A same-origin fetch probe evaluated inside the page context.
   *
   * @param {{page: import('@playwright/test').Page}} fixtures the base fixtures
   * @param {Function} use the fixture consumer
   */
  probe: async ({ page }, use) => {
    /**
     * Fetches a same-origin path from inside the page and reports what the browser observed.
     *
     * `redirect: 'manual'` is deliberate: it lets a spec assert that the info endpoint did NOT
     * redirect, which is the contract. Note that no `Accept` header is sent — both reserved
     * endpoints are Accept-blind, and asserting otherwise is a PROHIBITED ASSERTION.
     *
     * @param {string} path the same-origin path to probe
     * @returns {Promise<ProbeResult>} the observed response
     */
    const probePath = (path) =>
      page.evaluate(async (target) => {
        const response = await fetch(target, {
          credentials: 'same-origin',
          redirect: 'manual',
        });
        let body;
        try {
          body = await response.json();
        } catch {
          body = null;
        }
        return {
          status: response.status,
          redirected: response.redirected,
          contentType: response.headers.get('content-type') ?? '',
          cacheControl: response.headers.get('cache-control') ?? '',
          body,
        };
      }, path);

    await use(probePath);
  },
});

export { expect } from '@playwright/test';

/**
 * Captures a documentation screenshot on the SUCCESS path.
 *
 * A deliberate divergence from capture-only-on-failure: these images are documentation artifacts of
 * the meaningful states, written under `target/screenshots/{project}/` so both session-mode projects
 * produce a parallel set. Failure-path retention stays configured separately for diagnostics.
 *
 * @param {import('@playwright/test').Page} page the page to capture
 * @param {import('@playwright/test').TestInfo} testInfo the running test's info, for the project name
 * @param {string} name the screenshot name, without extension
 * @returns {Promise<void>} resolves once the file is written
 */
export async function captureState(page, testInfo, name) {
  await page.screenshot({
    path: `${SCREENSHOT_DIR}/${testInfo.project.name}/${name}.png`,
    fullPage: true,
  });
}
