# PLAN-V02-53: The Seven PLAN-15 Follow-Up Issues, #323 to #329, and Their Closure

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-31 from an issue review of `cuioss/API-Sheriff`: each of the seven issues was checked against `main`
> at `ec8c90d5`, a verification comment was posted on it, and its stale file references were corrected in its body.
> `#422` and `#423` (the test-cycle duration issues) are NOT in this plan: they are measurement and test-timing work, not code defects.

## Objective

Seven small follow-ups that `PLAN-15` filed as issues after #320 are still open and still true on `main`: a same-origin check that does not
look at encoded spellings, a coverage guard that partitions files instead of keys, an anchor-coverage rule restated in several places,
integration fixtures that hand-mirror the base document, seven slash-stripping helpers, a redirect `no-store` header no native integration
test observes, and a `forward` block on a redirect route that is accepted and never read. This plan fixes all seven in one PR, each with
a test that fails when its fix is removed, and then closes the seven issues, each on the evidence that its own acceptance is met.

## Deliverables

Each deliverable names its issue. Re-read every cited site at outline: the files moved in the validator split and the edge
decomposition, and the issue bodies were corrected on 2026-10-31 to the current paths. A finding that no longer holds is dropped, not acted on.

1. **#323 — one same-origin normalisation.** `PendingAuthorizationRecord.sameOrigin` decides "same origin" for the BFF return URL and has no
   handling of percent-encoded spellings of a separator, which `LocationPathReview` already refuses for `redirect.location`. Choose ONE
   normalisation and apply it to both surfaces, so the two cannot drift. Callers that inherit it: `LoginFlow`, `LoginInitiationEndpoint`,
   `StepUpEndpoint`, `StepUpCoordinator`, `SessionWidening`, `ReturnTargetScopes` and the boot check in `OidcClientRules`; enumerate each one's
   accepted and refused values before and after, one criterion per caller. Add regression tests aimed at `sameOrigin` with the encoded
   spellings `LocationPathReviewTest` covers, and show each red against the pre-fix `sameOrigin`.
2. **#324 — limit-shaped keys at key granularity.** `DescriptorInventoryWiringTest#limitShapedKeysLiveOnlyInTheRecordedSurfaces` partitions
   descriptor FILES by a boolean. Move the partition to keys, or state in the contract why files suffice; the contract note
   `doc/development/declared-limit-assertion-coverage.adoc` ("Report-Only Finding: the inventory guard partitions files, not keys") says the
   second is not true and defers the first to a plan of its own because the matrix's key column is non-uniform (qualified-with-value,
   bare key, two keys in one row) and YAML anchors resolve one literal to several qualified paths. Add the test that adding a limit-shaped key to an
   already limit-bearing file fails until a matrix row exists, shown red. **If key-level derivation does not fit this plan, cut this
   deliverable, say so in the landing, and leave #324 open with that stated** — that is the first cut, see the split guard.
3. **#325 — one home for the anchor-coverage rule.** Validator rule 4b (namespace coverage) is stated in prose in `AnchorRules`, `MatchConfig`,
   `ConfigValidator`, `doc/adr/0007-anchor-scoped-policy.adoc`, `doc/configuration.adoc`, `doc/user/anchors.adoc` and
   `doc/security-threat-model.adoc`. Give the rule one home and make the others link to it, so there is one statement to correct. Keep each place's
   own context (what it adds beyond the rule), and keep `AnchorRulesTest` the single place the behaviour is asserted. Update the issue's title if
   it still says "four places".
4. **#326 — the mirrored IT fixtures cannot drift.** `sheriff-config-passthrough-empty/gateway.yaml` and `sheriff-config-refresh/gateway.yaml` carry
   `security_headers`, `cors` and the `/proxy` anchor block copied from `sheriff-config/gateway.yaml`, and the passthrough-empty overlay also
   mirrors the portal block. Add a guard test that fails when a mirrored block differs from the base (the existing
   `*ActivationWiringTest` classes already walk `sheriff-config*/gateway.yaml`), or derive the blocks. Show it red against a changed mirror.
5. **#327 — one slash-stripping utility.** Seven methods in six files strip slashes: `LocationRewriter`, `EdgeRequests`, `DispatchStage`,
   `DirectoryAssetSource`, `PathConfinement` and `RouteTableBuilder` (two). They are NOT equivalent: `EdgeRequests.stripTrailingSlash` strips at most
   ONE trailing slash and the others strip every one. Decide which behaviour each call site needs, with the call site's evidence, put the utility in
   one place with its own tests (empty, only slashes, none, one, many, leading and trailing, interior), and migrate all seven. Remove the old helpers
   (pre-1.0, no shim). A call site whose behaviour must stay different keeps a named, tested variant, not a private copy.
6. **#328 — a native integration assertion for the redirect `no-store`.** `RedirectStage` serves `Cache-Control: no-store` when the route's
   `require` is not `none` or the answer carries a `Set-Cookie`. The one redirect IT (`RoutingAndResponseHeadersIT`, `/proxy/redirect-entry`) is a
   `require: none` route and asserts nothing about `Cache-Control`. Add an assertion against the native image on a route that triggers it, and
   keep the `require: none` answer as the negative control (the header absent). Decide at outline which fixture is the smallest: an
   authenticated redirect route reached with a credential an existing IT already holds, or a redirect that carries a cookie. An unauthenticated
   request to an authenticated redirect route is challenged before the location is disclosed, so it cannot be the case under test. Show the assertion red
   against a temporary cut of `requiresNoStore`. A new descriptor file changes the inventory the declared-limit contract counts: keep that contract and
   its test green.
7. **#329 — refuse `forward` on a redirect route at boot.** The edge says "no forward policy is consumed" for a redirect route, and no boot rule
   reads `forward` with `redirect`, so the key reads as a filter and does nothing. Refuse a route-level `forward` on a redirect route (pre-1.0: refuse,
   do not warn), in the same shape as the existing refusal of a route-level `cookies` block on an asset or redirect route, with a broader endpoint block
   staying accepted. Add the boot-validation test (`TerminalActionRulesRedirectTest`) shown red without the rule, and state the refusal in
   `doc/configuration.adoc`, `doc/user/endpoint-routes.adoc` and the schema description. Before refusing, find every committed YAML that declares
   `forward` on a redirect route — the redirect declarations are `integration-tests/.../httpbin.yaml`, `portal-hidden.yaml`, the YAML in `ConfigLoaderTest` and
   `ConfigProducerTest`, `integration-tests/scripts/verify-invalid-config-fails.sh` and `doc/user/endpoint-routes.adoc` — and fix those that do. **Asset
   routes are out of scope unless the outline proves an asset route also never reads `forward`**: an upstream-sourced asset contacts an upstream, so
   that is not assumed.
   **Outside PR #555** (opened 2026-11-01 by an outside contributor, "Fixes #329") implements this refusal in `TerminalActionRules` with tests in `TerminalActionRulesRedirectTest` and a
   `doc/configuration.adoc` edit. At outline, check its state on `main`: if it has merged, this deliverable shrinks to what it left undone (the YAML sweep, `doc/user/endpoint-routes.adoc`, the schema description, the
   closure of #329 on the merged code); if it is still open, the operator has ruled on it first.
8. **Close the seven issues after the PR has landed.** Last, after the PR is merged and CI on the merge commit is green, close each of #323 to #329
   through the CI abstraction (`ci issue comment`, then `ci issue close`), each with a comment that names the merged PR and says which acceptance
   criterion the merged change meets and which test proves it. An issue whose deliverable was cut or only partly met stays open with a comment saying
   what remains. **The PR description names the issues as `#NNN` WITHOUT a closing keyword**, because merge-time auto-close would close #324 even
   when it was cut. Report in the landing which issues were closed and which were left open, and why.

Split guard: 8 deliverables. Each is thin and they share one origin, so they ship as one PR (recorded as an epic decision; the plan ceiling is
12). The footprint is estimated at about 40 files. If the diff passes 100 files or 150,000 diff characters, cut in this order: #324 (its own
verification, the largest and the least settled), then #327 (touches six production files), and report the cut items as unfinished; they stay open.

## Standing Constraints

- **Every fix has a test that goes red when the fix is removed.** Show each red once, against a temporary cut that is then reverted. A fix without a
  shown-red test does not close its issue.
- **Pre-1.0 rules:** no deprecation, no shim, remove what is replaced.
- **Use the simple type name wherever the file imports the type.** `verify -Ppre-commit` rewrites a fully qualified name and, on this repository, has
  rewritten the import spacing of about 380 untouched files on four recent plans; revert any rewrite of a file this plan did not author, and run
  `git status --porcelain` after the gate (see `doc/development/build-gate-discipline.adoc`).
- **No dependency is added** (CLAUDE.md).
- **Security-shaped keys are refused, not warned about** (the #329 and #323 deliverables), and `sameOrigin` keeps refusing everything it refuses today.
- **Issue text is public.** Comments and the closure comments state what the change does and what proves it; they carry no unfixed finding (see
  Security Findings).
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an open Sonar issue is fixed in a pushed commit, or
  dismissed with a stated reason recorded as `{issue-key}: {reason}` in the run's progress record and transmitted through the plan-less transition verb,
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. A dismissal the run cannot
  transmit is a blocker, never a silent pass. The merge gate reads the open-issue count at the head, and Done-when lists that read: the open-issue count
  and the rules left open, `unknown` for a failed read and never `0`.

## Claim Labels

- OBSERVED: `PendingAuthorizationRecord.sameOrigin` handles `//`, a raw backslash, control characters and absolute-URL scheme/host/port, and has no handling
  of a percent-encoded `/` or `\`; the unit tests that call it directly cover no percent-encoded spelling —
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationRecord.java` § `sameOrigin`; `PendingAuthorizationStoreTest`
- OBSERVED: `declaresLimitShapedKey` is a per-file boolean and the contract note records that as reported, not fixed (22 of 34 files limit-bearing) —
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/DescriptorInventoryWiringTest.java`;
  `doc/development/declared-limit-assertion-coverage.adoc` § "Report-Only Finding: the inventory guard partitions files, not keys"
- OBSERVED: rule 4b is stated in prose in `AnchorRules`, `MatchConfig`, `ConfigValidator`, ADR-0007, `doc/configuration.adoc`, `doc/user/anchors.adoc` and
  `doc/security-threat-model.adoc` — `git grep -n -i 'strictly contains\|namespace coverage'` at `ec8c90d5`
- OBSERVED: the three gateway descriptors carry the same `security_headers`, `cors` and `/proxy` anchor blocks today, with only comments differing, and no
  test compares them — `integration-tests/src/main/docker/sheriff-config*/gateway.yaml`
- OBSERVED: seven slash-stripping methods in six files, and `EdgeRequests.stripTrailingSlash` strips at most one trailing slash while the others strip all —
  `git grep -n -E 'static String (strip|trim)[A-Za-z]*Slash'` at `ec8c90d5`
- OBSERVED: `RedirectStage.requiresNoStore` is `effectiveAuth.require() != Require.NONE || !setCookies.isEmpty()`, and no IT asserts `Cache-Control` on a
  redirect answer — `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/terminal/RedirectStage.java`; `RoutingAndResponseHeadersIT`
- OBSERVED: no boot rule reads `forward` together with `redirect`, and the edge states "no forward policy is consumed" for a redirect route —
  `ForwardingRules`, `TerminalActionRules.validateRedirectExclusivity`, `GatewayEdgeRoute` (before `forwardPolicyStage.process`)
- HYPOTHESIS: no committed YAML declares `forward` on a redirect route, so the refusal breaks no fixture — sweep every redirect declaration listed in D7
  (verify-at-outline; the orchestrator's own check of this was inconclusive)
- HYPOTHESIS: a route-level `forward` refusal can follow the existing route-level `cookies` refusal on asset and redirect routes with a broader block
  staying accepted — `ConfigValidator` class Javadoc and `CookieRules` (verify-at-outline)
- HYPOTHESIS: the key-level derivation for #324 fits one plan — it may not, given the non-uniform key column; the contract note says it needs its own
  verification (verify-at-outline: attempt it, and cut D2 if it does not fit)
- HYPOTHESIS: a fixture for the #328 assertion exists that needs no new Keycloak flow (verify-at-outline against the ITs that already hold a bearer token
  or session)
- Verify-first clause: the plan re-derives each issue's current state at outline; an issue that has since been closed or fixed is dropped from D1 to D7
  and from D8.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationRecord.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/LocationPathReview.java` — only if the shared normalisation lands there (verify-at-outline)
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/bff/pending/PendingAuthorizationStoreTest.java`,
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/http/LocationPathReviewTest.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/DescriptorInventoryWiringTest.java`,
  `doc/development/declared-limit-assertion-coverage.adoc`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/AnchorRules.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/MatchConfig.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java`
- OBSERVED: `doc/adr/0007-anchor-scoped-policy.adoc`, `doc/configuration.adoc`, `doc/user/anchors.adoc`, `doc/security-threat-model.adoc`
- OBSERVED: `integration-tests/src/main/docker/sheriff-config/gateway.yaml`,
  `integration-tests/src/main/docker/sheriff-config-passthrough-empty/gateway.yaml`,
  `integration-tests/src/main/docker/sheriff-config-refresh/gateway.yaml`
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — one new guard test for the mirrored fixtures and the #328 assertion in
  `RoutingAndResponseHeadersIT.java` or a sibling (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config/endpoints/httpbin.yaml` — only if the #328 fixture is a route there (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/routing/LocationRewriter.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/request/EdgeRequests.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/upstream/DispatchStage.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/PathConfinement.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/DirectoryAssetSource.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/RouteTableBuilder.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/http/` — the new shared slash utility and its test under
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/http/` (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/TerminalActionRules.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/rule/ForwardingRules.java`,
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/TerminalActionRulesRedirectTest.java`,
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/rule/ForwardingRulesTest.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/endpoint.schema.json`, `doc/user/endpoint-routes.adoc`
- HYPOTHESIS: `integration-tests/scripts/verify-invalid-config-fails.sh`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/load/ConfigLoaderTest.java`,
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/ConfigProducerTest.java` — only if the D7 sweep finds a `forward` on a redirect route there
  (verify-at-outline)

## Dependencies and Sequencing

- Depends on: `PLAN-V02-52`, merged, and `PLAN-V02-55` (integration-test concurrency, #423), merged: both edit `integration-tests/` (this plan a fixture guard and a redirect `no-store` assertion, `PLAN-V02-55` the failsafe partition and the IT classes), and the operator ordered `PLAN-V02-55` first. It rewrites citations across `doc/adr/`, `doc/configuration.adoc`, `doc/security-threat-model.adoc` and Javadoc and comments
  under `api-sheriff/src/main/java/**`, and this plan edits `doc/adr/0007-anchor-scoped-policy.adoc`, `doc/configuration.adoc`, `doc/security-threat-model.adoc`,
  `AnchorRules.java` and six more production files; two plans in those files collide. It is also an ADR-corpus plan's neighbour: no plan runs beside one
  that authors an ADR, and `PLAN-V02-52` writes one.
- Overlaps with: none after `PLAN-V02-52` has landed. `PLAN-V02-49` (tests and fixtures in `config/`, `routing/`, `edge/`) is on `main`, so the test files
  this plan edits are read from there.
- Adjacent to: `doc/development/declared-limit-assertion-coverage.adoc` and the Coverage Matrix: a new endpoint descriptor for #328 changes the inventory it counts.
  The GW-07 residue and ADR-0066 notes are not touched.
- The seven issues are `cuioss/API-Sheriff` #323 to #329; their bodies carry the current file references as of 2026-10-31.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-53-plan-15-follow-up-issues.md" plan_id=plan-v02-53-plan-15-follow-up-issues
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Security Findings

Fix a security finding inside this plan whenever possible; that is the preferred outcome. A finding
that is not fixed right away (out of scope, unconfirmed, deferred) stays out of git: it is never
written into a tracked file, a commit message, the PR title, body or review replies, a code comment,
the docs, or this plan's inbox and landing messages. Record it instead in
`.plan/local/security_notes.adoc` (git-ignored; create the file if it is missing and append, never
rewrite). Each entry carries the date, this plan's id, the location (file and line or component),
what was observed, and why it was not fixed. Tracked text may say only that a security note was
recorded locally, never what it says. **That includes the GitHub issues this plan closes: their closure comments name
the fix and its test, never an unfixed weakness.** D1 (#323) and D7 (#329) are the two deliverables most likely to turn one up.

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message. Closing the seven GitHub issues (deliverable 8) is the one action outside
the repository: it goes through the CI abstraction, after the merge, and nowhere else.
