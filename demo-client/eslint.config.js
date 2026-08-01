import js from '@eslint/js';
import globals from 'globals';
import security from 'eslint-plugin-security';
import prettier from 'eslint-config-prettier';

/**
 * Flat ESLint configuration for the demo client.
 *
 * Two source trees with different runtimes share one config:
 *
 *   - `src/main/resources/spa/**` runs in the BROWSER. It is the demo SPA the gateway serves as a
 *     public asset, so it gets browser globals only. It must not reach for Node APIs.
 *   - `tests/**`, `fixtures/**`, `utils/**` run under NODE, inside the Playwright runner, and get
 *     node globals plus the Playwright test globals.
 *
 * `eslint-config-prettier` is last so it can switch off every stylistic rule Prettier owns.
 * Formatting is Prettier's job; this config only carries correctness and security rules.
 */
export default [
  {
    // Generated and downloaded material — never linted.
    ignores: ['target/**', 'node_modules/**', 'test-results/**', 'playwright-report/**'],
  },

  js.configs.recommended,

  {
    // Shared baseline for every JavaScript file in the module.
    languageOptions: {
      ecmaVersion: 2023,
      sourceType: 'module',
    },
    plugins: { security },
    rules: {
      ...security.configs.recommended.rules,
      eqeqeq: ['error', 'always'],
      'no-var': 'error',
      'prefer-const': 'error',
      'no-implicit-coercion': 'error',
    },
  },

  {
    // The demo SPA: browser runtime, no bundler, no framework.
    files: ['src/main/resources/spa/**/*.js'],
    languageOptions: {
      globals: globals.browser,
    },
    rules: {
      // The SPA is a sample an integrator reads. It must not log anything, and it must never be
      // the place a token or a session value gets written to the console.
      'no-console': 'error',
      // The gateway owns the response envelope and serves the SPA same-origin; there is no reason
      // for the demo to build DOM from strings. Rendering goes through textContent.
      'no-alert': 'error',
    },
  },

  {
    // The Playwright suite: node runtime plus the runner's globals.
    files: ['tests/**/*.js', 'fixtures/**/*.js', 'utils/**/*.js', 'playwright.config.js'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
    rules: {
      // Playwright reads credentials and base URLs from the environment the POM supplies; the
      // security plugin's non-literal-fs-filename rule is noisy against the runner's own APIs and
      // carries no signal for a test tree that touches no filesystem paths from user input.
      'security/detect-non-literal-fs-filename': 'off',
    },
  },

  prettier,
];
