# PLAN-11: Demo SPA Client + Playwright E2E

epic: api-sheriff-roadmap
workstream: WS-03

> Staged plan spec. NEW 2026-07-20 (operator). No `doc/plan/*.adoc` exists yet — this plan is
> **doc-first**: it authors `doc/plan/11-demo-client-e2e.adoc` plus the integration-sample
> documentation BEFORE implementing. The design docs govern.

## Objective

Build a **small, deliberately simple** JavaScript/SPA client that (a) **proves** the BFF works
end to end against a real IdP, and (b) **is the integration sample** a downstream frontend team
copies. Exercise it with Playwright — functional assertions and screenshots only.

Dual purpose is the point: the demo is not a throwaway test fixture. It ships as documented
reference material, so its code quality and its docs carry the same weight as its assertions.

## Ground Truth (checked 2026-07-20)

- API Sheriff has **zero** Playwright/node tooling today — this is new build infrastructure.
- Prior art: `TokenSheriff/token-sheriff-quarkus-parent/e-2-e-playwright/` — separate Maven
  module, `packaging: pom`, every Java plugin skipped (`compiler`, `checkstyle`, `spotbugs`,
  `pmd`, `jacoco`, `javadoc`, `enforcer`, `license`, `dependency-check`, `openrewrite`),
  `frontend-maven-plugin` pinning node/npm, `skipPlaywrightTests=true` so the suite is
  **opt-in via profile**, layout `tests/ fixtures/ utils/`.
- Operator scoping: functional + screenshots **only**. TokenSheriff's `@axe-core/playwright`
  WCAG/accessibility suite is explicitly **out of scope**.
- PLAN-06 doc D2 reserved paths are exactly four (callback, logout, logout-return,
  back-channel); D4 sets the content negotiation this demo depends on.

## Design Decisions (operator, 2026-07-20)

### D1 — Module placement: new top-level `demo-client/`

A new top-level Maven module holding **both** the SPA assets and the Playwright suite,
mirroring the TokenSheriff module shape. Rationale: the sample must be discoverable as a
sample, not buried inside the IT coordinator. The gateway serves it through an
`type: asset` / `access: public` anchor (PLAN-10) pointed at the module's asset directory.

Consequence to handle in the plan: the Keycloak compose stack lives in `integration-tests/`,
so `demo-client/` must drive or reuse that infrastructure rather than standing up a second
one. Decide the reuse mechanism at outline time; do **not** duplicate the compose stack.

### D2 — Dependency approval (recorded, not open)

This module adds npm devDependencies (`@playwright/test`, lint/format tooling) and the
`frontend-maven-plugin`. Under CLAUDE.md these would need approval — **the operator named
Playwright and named TokenSheriff as the pattern, so this scope is approved**. Anything
beyond that minimal set still needs asking. `@axe-core/playwright` is excluded by decision,
not by oversight.

### D3 — What the SPA does (keep it simple)

- Calls the BFF session/user-info endpoint (folded into PLAN-06) and **displays the result**.
- Unauthenticated: receives `401 problem+json` — **not** a redirect — and reacts by offering
  a login affordance that performs a **top-level navigation** to the login-initiation path
  (PLAN-06 fold).
- Renders the different result shapes: default curated set, and a parameter-selected /
  fuller view, so the allowlist behaviour is visible to a human looking at the page.
- A **logout button** driving RP-initiated logout to completion (`final_redirect`, session
  gone, subsequent info call 401 again).
- No framework requirement — plain JS/CSS is preferable to a build toolchain. Simplicity is
  an explicit acceptance criterion, not a default.

### D4 — Redirect-to-login semantics: the plan is CORRECT, the SPA adapts

Operator asked whether the info endpoint "returns redirect to login" and to verify the plan.
**Verified against `doc/plan/07-bff-server-session.adoc` D4 + the PLAN-06 folded scope: the
info endpoint must NOT redirect an XHR, and that is right.** Redirecting a `fetch()` sends
the browser after the IdP *inside* the fetch; the SPA receives opaque HTML or a CORS failure
instead of an actionable signal. Content negotiation exists precisely to prevent this:

- XHR / `fetch` → `401 application/problem+json`. The SPA reads it and shows a login button.
- Top-level **navigation** (`Accept: text/html`) → `302` into the auth-code flow.

The demo must **assert both halves** — that is its most valuable test, because it pins the
negotiation contract that every real integrator will depend on. An assertion that the info
endpoint 302s an XHR would encode the bug, not the behaviour.

### D5 — Both variants under one suite

The same SPA and the same Playwright specs run against **`session.mode: server`** (PLAN-06)
and **`session.mode: cookie`** (PLAN-07), parameterized by gateway configuration. Identical
observable behaviour across both modes IS the assertion — the browser-facing contract is
supposed to be variant-independent, and a shared suite proves it rather than asserting it in
prose. Where behaviour legitimately differs (e.g. Variant 3's back-channel endpoint is
deliberately `404`), the difference is expressed explicitly, never by skipping a variant.

### D6 — Test scope: functional + screenshots

Functional assertions plus screenshot capture at the meaningful states (unauthenticated,
post-login default view, parameter-selected view, post-logout). Screenshots serve the
documentation as much as the tests. No accessibility suite, no visual-regression gating
(screenshots are artifacts and doc material, not pass/fail pixel diffs, unless the plan makes
a deliberate case otherwise).

### D7 — The demo must not become production attack surface

Security-relevant for a security gateway: the demo module is **excluded from the production
image and the native build**, and the asset anchor serving it is demo/compose
configuration only — never a default. The SPA displays only what the info endpoint returns,
which already forbids raw token material; the demo must not add any path that surfaces
tokens, and must not weaken CSRF or cookie flags "to make the demo work". If something in the
demo can only be made to work by weakening a control, that is a finding about the gateway to
report, not a knob to turn.

## Deliverables (indicative — the plan doc defines the final breakdown)

1. Doc-first: `doc/plan/11-demo-client-e2e.adoc`; the **integration-sample documentation**
   (how a frontend integrates with the BFF: login initiation, info endpoint, 401-vs-redirect
   contract, logout, CSRF expectations) with screenshots from the suite.
2. `demo-client/` module skeleton: `pom.xml` (packaging pom, Java plugins skipped,
   frontend-maven-plugin, opt-in `skipPlaywrightTests` profile), `package.json`.
3. The SPA itself: info display, variant-selectable views, login affordance, logout button.
4. Asset-anchor configuration serving it — `type: asset`, `access: public` (the real-world
   proof of PLAN-10's asset serving — first consumer outside its own tests). Prefer
   `source: directory` for the demo; if `source: upstream` is the better fit for how the
   module builds, say so and record why.
5. Playwright suite: `tests/ fixtures/ utils/`, functional specs + screenshot capture,
   parameterized across both session modes.
6. CI wiring for the opt-in profile; compose-stack reuse from `integration-tests/`.

## Expected Surface

- NEW `demo-client/` module (SPA + Playwright); root `pom.xml` module list
- `integration-tests/` compose reuse; `doc/**` integration-sample documentation
- No `api-sheriff/` production-source change expected — **if the demo forces one, that is a
  gap in PLAN-06/07/10 surfacing late; report it rather than patching it here.**

## Dependencies and Sequencing

- **After PLAN-07, before PLAN-08.** Hard dependencies:
  - PLAN-06 — session variant, the info endpoint, and the login-initiation path (folded in
    2026-07-20, see below).
  - PLAN-07 — the cookie variant, required by D5's both-variants mandate.
  - PLAN-10 — asset serving (`type: asset`, `access: public`), which delivers the SPA.
- Before PLAN-08 deliberately: PLAN-08 writes the operator guide and cuts 1.0, so the
  integration sample and its docs must exist for PLAN-08 to reference and audit. A demo that
  lands after the release cut proves nothing about the release.
- **Fold-in dependency resolved 2026-07-20**: this plan surfaced that no login-initiation
  endpoint existed anywhere in the BFF design. Operator decision — fold it into **PLAN-06**,
  not here. PLAN-11 therefore *consumes* the endpoint; it does not introduce it.
- Surface: essentially disjoint from every other plan (new module + docs). Parallelizable
  with PLAN-08 prep in principle, but its dependency chain makes it naturally last-but-one.

## Scope-Bloat Note

Six deliverable groups, one new module, no production-source change — comfortably inside the
guard. The risk here is the opposite of bloat: "keep it simple" is an operator instruction and
a scope ceiling. If the SPA grows a framework, a build pipeline, or a component library, that
is drift — the sample's value is that an integrator can read it in one sitting.

## Hand-Off Command

```text
/plan-marshall Author doc/plan/11-demo-client-e2e.adoc doc-first and implement it: a small, deliberately simple JavaScript SPA demo client that proves the BFF works end to end AND serves as the documented integration sample for downstream frontend teams. Read doc/plan/07-bff-server-session.adoc (especially D2 reserved paths and D4 content negotiation), doc/variants/02-bff-session.adoc, doc/variants/03-bff-cookie.adoc, and the anchor-types/asset-serving docs from roadmap plan 10 first — the design docs govern. MODULE (operator decision 2026-07-20): a NEW top-level demo-client/ module holding both the SPA assets and the Playwright suite, mirroring TokenSheriff/token-sheriff-quarkus-parent/e-2-e-playwright — packaging pom, every Java plugin skipped (compiler, checkstyle, spotbugs, pmd, jacoco, javadoc, enforcer, license, dependency-check, openrewrite), frontend-maven-plugin pinning node/npm, skipPlaywrightTests=true so the suite is opt-in via profile, layout tests/ fixtures/ utils/. Reuse the Keycloak compose stack in integration-tests/ — do NOT stand up a second one. DEPENDENCIES: the npm devDependencies (@playwright/test plus lint/format) and frontend-maven-plugin are operator-approved as named scope; anything beyond that minimal set needs asking; @axe-core/playwright and any accessibility/WCAG suite are excluded by decision. SPA: call the BFF session/user-info endpoint and display the result; render both the curated default set and a parameter-selected/fuller view so allowlist filtering is visible; a login affordance; a logout button driving RP-initiated logout to completion. Plain JS/CSS strongly preferred — simplicity is an explicit acceptance criterion, no framework, no build toolchain. REDIRECT SEMANTICS (verified 2026-07-20, do not "fix" this): the info endpoint must NOT redirect an XHR — an unauthenticated fetch gets 401 application/problem+json and the SPA reacts by offering a login affordance that performs a TOP-LEVEL NAVIGATION to the login-initiation path; only a navigation request (Accept: text/html) gets the 302 into the auth-code flow. Assert BOTH halves of that negotiation — it is the contract every real integrator depends on. Serve the SPA through an anchor with type: asset and access: public — the first real consumer of roadmap plan 10's asset serving; prefer source: directory for the demo, and if source: upstream fits the module build better, record why. TESTS: functional assertions plus screenshot capture at the meaningful states (unauthenticated, post-login default view, parameter-selected view, post-logout); screenshots feed the documentation; no accessibility suite, no pixel-diff gating. Run the SAME SPA and SAME specs against both session.mode: server and session.mode: cookie, parameterized by gateway config — identical observable behaviour across both variants IS the assertion; where behaviour legitimately differs (Variant 3's back-channel endpoint is deliberately 404) express it explicitly rather than skipping a variant. SECURITY: exclude the demo from the production image and native build; the asset anchor serving it is demo/compose configuration only, never a default; never surface raw token material; never weaken CSRF or cookie flags to make the demo work — if a control has to be weakened, report it as a gateway finding instead. Deliver the plan doc, the integration-sample documentation (login initiation, info endpoint, the 401-vs-redirect contract, logout, CSRF expectations) illustrated with the suite's screenshots, the module skeleton, the SPA, the anchor configuration, the Playwright suite, and CI wiring for the opt-in profile. No api-sheriff/ production-source change is expected — if the demo forces one, that is a gap in roadmap plans 07/08/10 surfacing late: report it rather than patching it here. DOCUMENTATION (standing epic convention, operator 2026-07-20 — applies to every plan; THREE layers, all in the SAME PR): (a) REFERENCE — every new or changed configuration key, default, enum value, and endpoint this plan introduces MUST land in doc/configuration.adoc, and structural changes in doc/architecture.adoc; these are the exhaustive per-key references and they are not optional. (b) OPERATOR GUIDE — doc/user/, task-oriented (how an operator accomplishes the thing), linking to the reference rather than duplicating its key tables. (c) DEVELOPER GUIDE — doc/development/, contributor-facing (build, test, run, extend). The doc/user/ and doc/development/ trees and the convention itself are created by roadmap plan 10 (anchor types + assets) — NOT by plan 04b, which shipped without them; follow what doc/plan/README.adoc records once plan 10 lands. Updating plan docs, variant docs or ADRs does NOT satisfy (a), (b) or (c). If this plan genuinely changes nothing an operator or a contributor would do differently, state that explicitly in the PR rather than silently skipping it. SONAR ZERO-FINDINGS (standing epic convention, operator 2026-07-21): the SonarCloud quality gate (project cuioss_API-Sheriff) must be GREEN before merge — a red gate is a HARD STOP, never merge over red or on a stale green while analysis is still pending; deliver ZERO new findings (new bugs = 0, new vulnerabilities = 0, new code smells = 0, security hotspots 100% reviewed, new_reliability / new_security / new_maintainability ratings all A, new coverage >= 80%); the GOAL is to FIX findings, and where a fix is genuinely not sensible an IN-CODE suppression carrying a documented rationale (e.g. // NOSONAR or @SuppressWarnings("java:SXXXX") with a justifying comment) is an acceptable way to reach zero because it stays auditable in the repo alongside the code — what is NOT acceptable is reaching zero by silent server-side won't-fix / false-positive marking; and this plan is NOT 'done' / 'all green' until the PROJECT-level gate on the merged result is green — the completion report must reflect the actual post-merge gate, not a transient PR-scoped new-code green.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-11.md)
