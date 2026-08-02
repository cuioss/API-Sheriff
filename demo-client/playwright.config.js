import { defineConfig } from '@playwright/test';

/**
 * Playwright configuration for the API Sheriff BFF demo suite.
 *
 * Two projects run the SAME specs and differ ONLY in `baseURL`: `session-server` against the
 * server-session gateway and `session-cookie` against the cookie-session gateway. That single
 * difference is what makes "the two session modes are browser-observably identical" an executable
 * assertion rather than a claim in a design document.
 *
 * Every URL and credential comes from the environment, which the POM supplies from its own
 * properties — no port number is restated in JavaScript.
 */

/**
 * Reads a required environment variable.
 *
 * Failing loudly here is deliberate: a missing base URL would otherwise surface as every spec
 * navigating to `about:blank` and failing with an unrelated assertion message.
 *
 * @param {string} name the variable name
 * @returns {string} the value
 */
function required(name) {
  const value = process.env[name];
  if (!value) {
    throw new Error(
      `${name} is not set. Run the suite through 'verify -Pe2e-demo -pl demo-client', which supplies it from the POM.`
    );
  }
  return value;
}

const keycloakHostUrl = required('KEYCLOAK_HOST_URL');

/**
 * Builds the Chromium host-resolver rule that maps Keycloak's container-internal authority onto its
 * published host address.
 *
 * THIS IS LOAD-BEARING, not a convenience. The `integration` realm pins
 * `frontendUrl https://keycloak:8443`, so every authorization URL the gateway redirects to carries a
 * container-internal authority the host cannot resolve. The landed Java integration helper sidesteps
 * this by string-rewriting each redirect location — a real browser cannot do that, because following
 * redirects is the browser's own job. So the resolution is pushed down into the browser's resolver.
 *
 * Do NOT "fix" this by changing the realm's frontendUrl: that value is the issuer authority baked
 * into every token the realm mints, and changing it breaks every landed bearer-token test.
 *
 * @returns {string} the `--host-resolver-rules` flag value
 */
function hostResolverRule() {
  const { hostname, port } = new URL(keycloakHostUrl);
  return `MAP keycloak:8443 ${hostname === 'localhost' ? '127.0.0.1' : hostname}:${port}`;
}

const chromiumLaunchArgs = [
  // The stack serves a self-signed localhost bundle; this covers the browser's own navigations,
  // while ignoreHTTPSErrors covers the API-level requests.
  '--ignore-certificate-errors',
  `--host-resolver-rules=${hostResolverRule()}`,
];

export default defineConfig({
  testDir: './tests',
  // Everything generated lands under the Maven-standard target/ tree, which is already git-ignored.
  outputDir: 'target/test-results',

  // The suite drives ONE shared stack with a real IdP and real server-side sessions. Running specs
  // in parallel would have them log in and log out from under each other, so serial execution is a
  // correctness requirement rather than a performance trade-off.
  fullyParallel: false,
  workers: 1,
  // No retries: a flaky browser assertion against this contract is a defect to diagnose, not to
  // paper over by re-running until it passes.
  retries: 0,
  forbidOnly: !!process.env.CI,
  timeout: 60_000,
  expect: { timeout: 10_000 },

  // Deliberately NO html reporter: it would spawn a report server, and a blocked run in CI is
  // indistinguishable from a hang.
  reporter: [
    ['list'],
    ['json', { outputFile: 'target/test-results/results.json' }],
    ['junit', { outputFile: 'target/test-results/junit.xml' }],
  ],

  use: {
    ignoreHTTPSErrors: true,
    // Failure-path artefacts for diagnostics. The DOCUMENTATION screenshots are captured explicitly
    // on the success path by the specs themselves — see fixtures/test-fixtures.js.
    screenshot: 'retain-on-failure',
    trace: 'retain-on-failure',
    video: 'retain-on-failure',
    launchOptions: { args: chromiumLaunchArgs },
  },

  projects: [
    {
      name: 'session-server',
      use: {
        browserName: 'chromium',
        baseURL: required('PLAYWRIGHT_BASE_URL_SERVER'),
      },
    },
    {
      name: 'session-cookie',
      use: {
        browserName: 'chromium',
        baseURL: required('PLAYWRIGHT_BASE_URL_COOKIE'),
      },
    },
  ],
});
