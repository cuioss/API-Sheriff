import { expect, test } from '../fixtures/test-fixtures.js';
import { login } from '../utils/keycloak-login.js';
import { BROWSER_COOKIE_BUDGET_BYTES } from '../utils/constants.js';

/**
 * The per-cookie size budget, measured in a real browser.
 *
 * This is the only spec in the repository that measures a BROWSER limit rather than asserting a
 * gateway behaviour, and it exists because that limit is enforced nowhere else this suite can see:
 * a programmatic HTTP client records an oversized `Set-Cookie` and replays it happily, while a
 * browser discards it silently — no error, no header, no session. That asymmetry is how a session
 * cookie no browser could hold once shipped green through nine cookie-mode integration tests.
 *
 * Two legs, each proving something the other cannot:
 *
 *   (a) the threshold measurement — what THIS browser actually enforces, bisected from inside the
 *       page context over the two arms the two specifications budget differently;
 *   (b) the live-session deliverability check — whether the cookie the gateway just set was in fact
 *       stored, which is the end-to-end form of the same question.
 *
 * A measured threshold above the RFC floor is observed HEADROOM, never a relaxed bar: the gateway
 * ships to every browser, and the floor is what all of them guarantee.
 */

/** The probe cookie's name — short and fixed, so its length is a known constant in every sum. */
const PROBE_NAME = '__pw_budget_probe';

/**
 * The attributes held fixed while the value grows (arm 1). `path=/` keeps the probe readable from
 * the SPA's own path, which is what makes the read-back a measurement rather than a path miss.
 */
const FIXED_ATTRIBUTES = '; path=/; SameSite=Lax';

/**
 * The name+value size arm 2 holds fixed while the attributes grow. Comfortably below the RFC floor,
 * so the only variable in that arm is the attribute length.
 */
const FIXED_NAME_VALUE_BYTES = 3000;

/**
 * The ceiling arm 2's search stops at. A browser that budgets name+value only (RFC 6265bis section
 * 5.6) never rejects for attribute length at all, so the arm must be bounded or it would not
 * terminate. Reaching the ceiling is a legitimate result, reported as such rather than as a failure.
 */
const ATTRIBUTE_ARM_CEILING_BYTES = 8192;

/**
 * The gateway's session cookie name.
 *
 * Spelled out rather than imported from the gateway: this is a black-box browser test and the cookie
 * name is part of the wire contract it observes, not an implementation detail it may reach into. The
 * `__Host-` prefix is itself part of the contract — a browser honours it only together with
 * `Secure`, `Path=/` and no `Domain`.
 */
const SESSION_COOKIE_NAME = '__Host-sheriff-session';

/**
 * Bisects the largest storable size for one arm, evaluated entirely inside the page context.
 *
 * Each probe writes one cookie, reads `document.cookie` back to see whether the browser kept it, and
 * then deletes it. Deleting between probes is load-bearing rather than tidy: a browser also caps the
 * TOTAL bytes one domain may hold, so leaving multi-kilobyte probes behind would eventually make an
 * unrelated limit look like the per-cookie one.
 *
 * @param {import('@playwright/test').Page} page the page whose origin is measured
 * @param {object} arm the arm to measure
 * @param {'value'|'attributes'} arm.grow which part of the cookie grows
 * @param {number} arm.low a size known to be storable
 * @param {number} arm.high a size at or above the first rejection (the search ceiling)
 * @returns {Promise<{storable: number, rejectedAt: (number|null)}>} the largest storable size for
 *   the arm, and the smallest size observed to be rejected (null when the ceiling was reached
 *   without a rejection)
 */
function measureArm(page, arm) {
  return page.evaluate(
    ({ grow, low, high, probeName, fixedAttributes, fixedNameValueBytes }) => {
      /**
       * Writes one cookie, reads back whether it was stored, and removes it again.
       *
       * @param {string} cookieString the full `document.cookie` assignment
       * @returns {boolean} whether the browser stored the cookie
       */
      const storable = (cookieString) => {
        // `globalThis.document` rather than a bare `document`: this arrow runs in the PAGE, but the
        // lint config gives the tests tree node globals, exactly as the sibling specs' in-page
        // `globalThis.localStorage` reads do.
        globalThis.document.cookie = cookieString;
        const kept = globalThis.document.cookie
          .split('; ')
          .some((pair) => pair.startsWith(`${probeName}=`));
        globalThis.document.cookie = `${probeName}=; path=/; Max-Age=0`;
        return kept;
      };

      /**
       * Renders the probe cookie for one candidate size on the arm under measurement.
       *
       * @param {number} size the candidate size in bytes — name+value on the value arm, the whole
       *   assignment on the attribute arm
       * @returns {string} the `document.cookie` assignment to attempt
       */
      const candidate = (size) => {
        if (grow === 'value') {
          const valueBytes = size - probeName.length - 1;
          return `${probeName}=${'a'.repeat(valueBytes)}${fixedAttributes}`;
        }
        const valueBytes = fixedNameValueBytes - probeName.length - 1;
        const head = `${probeName}=${'a'.repeat(valueBytes)}${fixedAttributes}; pad=`;
        return `${head}${'p'.repeat(Math.max(0, size - head.length))}`;
      };

      /**
       * Renders one candidate size and reports whether the browser kept it.
       *
       * @param {number} size the candidate size
       * @returns {boolean} whether the browser stored it
       */
      const attempt = (size) => storable(candidate(size));

      // The low bound is an assumption the measurement rests on, so it is checked rather than
      // trusted: a browser that cannot store even the small probe would otherwise bisect its way to
      // a meaningless answer.
      if (!attempt(low)) {
        return { storable: 0, rejectedAt: low };
      }
      if (attempt(high)) {
        return { storable: high, rejectedAt: null };
      }

      let keep = low;
      let reject = high;
      while (reject - keep > 1) {
        const middle = Math.floor((keep + reject) / 2);
        if (attempt(middle)) {
          keep = middle;
        } else {
          reject = middle;
        }
      }
      return { storable: keep, rejectedAt: reject };
    },
    {
      grow: arm.grow,
      low: arm.low,
      high: arm.high,
      probeName: PROBE_NAME,
      fixedAttributes: FIXED_ATTRIBUTES,
      fixedNameValueBytes: FIXED_NAME_VALUE_BYTES,
    }
  );
}

test.describe('per-cookie size budget', () => {
  test('the browser enforces a per-cookie budget at or above the RFC floor', async ({
    demoPage,
  }) => {
    // Arm 1 — RFC 6265 section 6.1 budgets the whole Set-Cookie header, so this arm grows the VALUE
    // with the attributes held fixed. Its ceiling is deliberately far above the floor: the question
    // is where this browser stops, not whether it reaches a number we picked.
    const valueArm = await measureArm(demoPage, {
      grow: 'value',
      low: 64,
      high: 65_536,
    });

    // Arm 2 — RFC 6265bis section 5.6 budgets the name and value ONLY, so this arm holds them fixed
    // and grows the ATTRIBUTES. A browser implementing 6265bis rejects nothing here and the search
    // reaches its ceiling; one implementing the 6265 whole-header budget rejects partway. Both are
    // reported, because which of the two a browser does is exactly what the two arms separate.
    const attributeArm = await measureArm(demoPage, {
      grow: 'attributes',
      low: FIXED_NAME_VALUE_BYTES + FIXED_ATTRIBUTES.length + 8,
      high: ATTRIBUTE_ARM_CEILING_BYTES,
    });

    const measured =
      `value arm (attributes fixed): stored ${valueArm.storable} bytes of name+value, ` +
      `first rejection at ${valueArm.rejectedAt ?? 'none below the ceiling'}; ` +
      `attribute arm (name+value fixed at ${FIXED_NAME_VALUE_BYTES}): stored ${attributeArm.storable} ` +
      `total bytes, first rejection at ${attributeArm.rejectedAt ?? 'none below the ' + ATTRIBUTE_ARM_CEILING_BYTES + '-byte ceiling'}`;

    // The assertion is the FLOOR, not the measurement. A browser storing more than 4096 is headroom
    // this project does not spend: the gateway's bar stays at the RFC floor less its cookie name and
    // attributes, because it ships to every browser and the floor is what all of them guarantee.
    expect(
      valueArm.storable,
      `the browser must honour the RFC 6265 section 6.1 per-cookie floor — ${measured}`
    ).toBeGreaterThanOrEqual(BROWSER_COOKIE_BUDGET_BYTES);

    // Guards the measurement itself rather than the browser: a bisection that never observed a
    // rejection on the value arm measured its own ceiling, not the browser's limit.
    expect(
      valueArm.rejectedAt,
      `the value arm reached its own 65536-byte ceiling without a rejection, so it measured the ` +
        `search bound rather than the browser — ${measured}`
    ).not.toBeNull();
  });

  test('the session cookie the gateway sets is actually stored by the browser', async ({
    demoPage,
    context,
  }) => {
    await login(demoPage);

    const stored = (await context.cookies()).find((cookie) => cookie.name === SESSION_COOKIE_NAME);

    // This runs under BOTH projects, and the server-mode arm is the matched control: server mode's
    // cookie is a small opaque handle that can never approach the budget, so its passing shows the
    // assertion is not cookie-mode-specific and is not passing by construction in the mode where the
    // cookie is large. The cookie-mode arm is the one that can actually fail here — in that mode the
    // sealed cookie IS the session, so an oversized seal is dropped silently and this is the only
    // place in the repository that would notice.
    expect(
      stored,
      `the browser stored no ${SESSION_COOKIE_NAME} cookie after a completed login: either the ` +
        `gateway set none, or it set one the browser silently discarded`
    ).toBeDefined();
    expect(stored.value, 'a stored session cookie with an empty value is not a session').not.toBe(
      ''
    );
  });
});
