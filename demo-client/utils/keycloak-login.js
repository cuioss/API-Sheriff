import { expect } from '@playwright/test';

import { REALM_USER, SPA } from './constants.js';

/**
 * Drives the Keycloak login form and waits for the auth-code round-trip to land back on the SPA.
 *
 * The whole flow is a sequence of TOP-LEVEL NAVIGATIONS the browser owns:
 *
 *   click Log in -> /bff/login?returnUrl=... -> 302 -> Keycloak authorization endpoint
 *   -> the login form -> 302 -> /bff/callback?code&state -> 302 -> the validated returnUrl
 *
 * The browser reaches Keycloak directly: the realm's identity is the published authority
 * `https://localhost:1443`, so every authorization URL the gateway redirects to resolves on the host.
 *
 * @param {import('@playwright/test').Page} page a page already on the SPA entry
 * @returns {Promise<void>} resolves once the browser is back on the SPA with a live session
 */
export async function login(page) {
  await page.getByTestId('login').click();

  // The Keycloak login form. Waiting on the field rather than on a URL keeps the helper independent
  // of the authority Keycloak is published under.
  const username = page.locator('#username');
  await expect(username).toBeVisible();
  await username.fill(REALM_USER.username);
  await page.locator('#password').fill(REALM_USER.password);
  await page.locator('#kc-login').click();

  // The callback leg lands on the validated returnUrl, which is the SPA entry the login started from.
  await page.waitForURL(`**${SPA.index}`);
  await expect(page.getByTestId('auth-state')).toHaveText('authenticated');
}
