# Landing Analysis: PLAN-06 — Forwarded-Trust Allow-List Environment Configurability

epic: deployment-configurability
workstream: WS-05
pr: [#254](https://github.com/cuioss/API-Sheriff/pull/254) — merged as `6ba8879`

> Landing record for one shipped plan. Every claim below was corroborated against first-party ground
> truth before it was recorded — `git show --stat 6ba8879`, the merged diff, `ci pr view`,
> `ci issue view`, `ci checks status`, and reads of the shipped files. The operator's paste was the
> lead; none of it was recorded on its own authority. Two paste claims are recorded here with a
> DIFFERENT verdict than the paste gave them, and one first-party finding the paste did not report
> is the most consequential item in this document.

## Deliverable Fidelity vs Spec

Merged footprint: 9 files, +845 / −23.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. `ConfigLoader` list-valued `${VAR}` arm + empty-resolution refusal | shipped-as-specified | `ConfigLoader.java` +123, `ConfigLoaderTest.java` +143 — both declared OBSERVED entries |
| 2. Full-boot effect test through `TcpPeerGate` | **shipped-modified (location AND assertion point)** | Landed as the nested `ForwardedTrustFromEnvironment` class in `edge/GatewayEdgeRouteTest.java` (+132), **not** in the declared `test/…/forward/`. Its own javadoc states it asserts through the routing decision *"rather than an assertion about `TcpPeerGate`"* — so the boot-through-to-effect goal holds, the named assertion target does not |
| 3. Compose-sample example + no-Docker wiring guard | shipped-as-specified, **with a disclosed defect** | `docker-compose.yml` +36, `docker/sheriff-config/gateway.yaml` +18, `ComposeSampleForwardedTrustWiringTest.java` +312 (undeclared file) — see Open Defect below |
| 4. `doc/configuration.adoc` uniform contract | shipped-as-specified | +88, the declared OBSERVED entry |
| — (mechanical) | added-unplanned | `.plan/project-architecture/_project.json` ±8, `doc/quality-report/code-correctness.adoc` ±8 — `architecture-refresh` output |

**All four HYPOTHESIS entries were refuted by non-realization.** `EnvSecretResolver.java`,
`ConfigValidator.java`, `test/…/forward/` and `doc/adr/` were **not touched**. The fourth is
load-bearing for the queue: see Reconciliation Actions.

### Declared-vs-realized surface (the gate's own accuracy on this landing)

| Direction | Count | Entries |
|---|---|---|
| Declared and realized | 5 of 9 | the five OBSERVED entries, all of them |
| Declared, never realized | 4 of 9 | every HYPOTHESIS entry |
| **Realized, never declared** | **2 substantive** | `edge/GatewayEdgeRouteTest.java`; `integration-tests/…/ComposeSampleForwardedTrustWiringTest.java` |

⛔ **The under-declaration has a named mechanism, not merely a gap.** Deliverable 2's test was
declared at `test/…/forward/` and landed in `edge/`. The spec's own hedge — *"verify-at-outline:
exact class TBD"* — is exactly where the error entered: an unresolved location declared as a
directory the plan then did not use. The gate compared the wrong directory and reported no
collision. This is the third distinct blindness class this epic has measured, and unlike the
directory-vs-file and production/test-pair classes it is not a matcher limitation — **the
declaration was simply wrong**, and a correct matcher would have been equally blind.

## Metrics and Anomalies

Read from the archived plan at `.plan/local/archived-plans/2026-09-03-forwarded-trust-env-configurability/metrics.md`.

- Tokens: **2,729,310** — the paste's "2.7M" is corroborated, but the figure carries `(n=5/6)
  (spans populations)`: 5-execute is an inline main-context measurement, 6-finalize is a dispatched
  total with its inline spend excluded. It is not a dispatched total and must not be compared as one.
- Duration: **2h27m worked** `(n=4/6)` against **11h42m wall** and **9h14m idle**. The worked figure
  covers four of six phases; 1-init and 5-execute contribute no worked measurement at all.
- Cost-bearing outlier: 6-finalize at 1,109,173 tokens and 256 tool uses — larger than any
  implementation phase, consistent with 19 finalize steps including a 3-round self-review and a
  merge-queue ejection.
- Anomalies: one merge-queue ejection (below); no loop-backs recorded.

## Routing and Merge Behavior

- **Review**: 3 automatic-review comments triaged. CodeRabbit's Major on the compose sample is
  **corroborated as correct and was accepted rather than deferred** — see Open Defect 1.
- **CI/merge**: merged via the queue as `6ba8879`; `ci pr view` reports `state: merged`,
  `merge_commit_sha: 6ba8879eaef5595052b8c41aca1e8dbe1405e8ec`. Worktree and branch removed;
  `git worktree list` confirms only PLAN-13's worktree remains, main clean at `6ba8879`.
- **Merge-queue ejection (1)**: `WebSocketRelayStageTest.preservesSecurityHeadersOnHandshakeFailure`.
  Corroborated first-party: the test exists at `WebSocketRelayStageTest.java:252`, and
  `git show --stat 6ba8879` confirms **this branch never touched that file**. The plan established
  the flake before retrying rather than assuming it — the correct order — and re-queued once under an
  explicit stop rule.
- **Post-merge, still in flight at analysis time**: the PR-attached `Run Integration Benchmarks`
  (`Performance Benchmark`, `pull_request: closed` gated) is `IN_PROGRESS`. Every gating check is
  `SUCCESS`: `build / conclusion`, `integration-tests / conclusion`, `build / build (25)`,
  `build / build (26)`, `build / sonar-build`.

### ⛔ The finding the paste did not report — PLAN-13 will go RED on rebase

This is a first-party measurement made during this analysis, and it is the most consequential item
in this landing.

Deliverable 2's new test block introduces **two NEW bare `listen(0)` calls** into
`edge/GatewayEdgeRouteTest.java` (the stub upstream server and the front server), now on `main`.
PLAN-13's whole subject is eliminating that exact overload, and it lands an ArchUnit fitness
function that fails the build on it. Verified against the guard's own source in PLAN-13's worktree:

- `LoopbackEphemeralBindArchTest` imports `BASE_PACKAGE = "de.cuioss.sheriff.gateway"` with
  `ImportOption.Predefined.ONLY_INCLUDE_TESTS` — the whole module test tree.
- The single carve-out is `CARVED_OUT_TEST = "de.cuioss.sheriff.gateway.tls.TlsEdgeProducerTest"`.
  `GatewayEdgeRouteTest` is **in the guarded selection**.
- The rule forbids `ServerSocket(int)` and the single-int `listen(int)` overload, directing callers
  to `listen(port, LoopbackHost.ADDRESS)`.

⚠ **The prediction's FORM was wrong and its SUBSTANCE was right.** The standing watch predicted a
*guaranteed rebase conflict* on this file. There is none: `git merge-tree --write-tree main
feature/loopback-stall-fix-and-instrumentation` returns a tree with **no conflict** — PLAN-06
appended at old line 749 while PLAN-13 edits lines 751/776/792, adjacent but non-overlapping, and
git resolves it. The collision is real but it is a **guard violation, not a text conflict**, and it
surfaces at build time rather than at rebase time. A ledger that had recorded only "expect a
conflict" would have been disarmed by the clean rebase and walked into the red build.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-06 --status shipped`
- [x] row `pr` stamped `254` — `orchestrator queue --set-row PLAN-06 --field pr`
- [x] row `landing` stamped `landings/PLAN-06.md` — `orchestrator queue --set-row PLAN-06 --field landing`
- [x] row `plan_marshall_plan_id` already stamped `forwarded-trust-env-configurability` (verified, no re-write)
- [x] PLAN-06's `## Expected Surface` corrected to the realized footprint, per the Step 4 item 5
      same-act obligation — the spec is terminal, but leaving a measured-wrong declaration in the
      corpus would feed `corpus cross-check` a false surface for every future comparison
- [x] **Watch RETIRED — PLAN-10's ADR-ordinal risk is CLEARED.** PLAN-06 did not touch `doc/adr/`;
      `doc/adr/` tops out at `0038` (PLAN-02's carrier-key ADR), so **0039 is free** and PLAN-10 no
      longer waits on PLAN-06's outline
- [x] **Watch RESOLVED-AND-REPLACED** — the PLAN-06 ↔ PLAN-13 collision watch is closed as a rebase
      hazard and reopened as a build-guard hazard (above)
- [x] Two PLAN-06-owned Open Defects retired (half-env-configurable allow-list; nothing exercises
      the forwarded block)
- [x] Five Open Defects opened (below)
- [x] START-HERE and Ordered Queue blocks regenerated — `orchestrator resume-summary`
- [x] `resume_anchor` updated

## Follow-Ups

1. ⛔ **`listen(0)` reintroduced on main — belongs to PLAN-13, folded into its spec.** PLAN-13 must
   convert the two new call sites in `GatewayEdgeRouteTest.java` as part of its rebase, or its own
   ArchUnit guard fails. Not a new plan: it is inside PLAN-13's existing charter and its declared
   surface already names that file.
2. ⛔ **Compose sample's default path does not boot until 0.2.0.** `.env:15` pins
   `ghcr.io/cuioss/api-sheriff:0.1.1`, whose loader predates the array arm; the placeholder resolves
   to a single string against a schema-declared `array` and fails the boot. Verified in-place: the
   skew, its cause and the `API_SHERIFF_IMAGE=api-sheriff:distroless` workaround are documented at
   `docker-compose.yml:159-170`. Accepted deliberately — the `.env` pin names an image that exists,
   which is that file's own stated rule. Clean revert if wanted: deliverable 3's `gateway.yaml` hunk.
   **Self-resolving at the 0.2.0 release** — recorded so the release is not cut without bumping it.
3. ⛔ **`required_bots` config token is broken and UNFIXED in project config.**
   `.plan/marshal.json:115` still carries `"required_bots": "coderabbit,cuioss-review-bot"`.
   `cuioss-review-bot` is pr-agent's `author_login`, not a registry `bot_kind`, so participation
   resolves absent for a bot that did participate — it would have blocked this merge and will block
   every future plan. The plan fixed it plan-locally only, correctly leaving project config to the
   operator, because commit `1c7308c` made that rename deliberately. Durable fix:
   `coderabbit,pr-agent`. **Operator decision owed.**
4. ⚠ **`WebSocketRelayStageTest.preservesSecurityHeadersOnHandshakeFailure` is flaky in CI.** One
   merge-queue ejection on a tree identical to one that had already passed twice. Component untouched
   by this branch (verified). PLAN-13 already declares this file in its surface and edits it —
   candidate fold, but the flake is a *different* defect from the wildcard bind and must not be
   assumed fixed by it.
5. ⚠ **Issue #256 filed and corroborated** — `ConfigValidator.BROAD_PREFIX_IPV4 = 8` lets a `/12`
   (Docker's whole default bridge pool, 1,048,576 addresses) boot with no warning. Body verified via
   `ci issue view --issue 256`. This landing **raised its severity**: the value now comes from an
   environment variable no code review sees, making the boot warning the only remaining signal.
   Unowned — candidate for a WS-05 successor spec.
6. ⛔ **The main-branch post-merge run could NOT be verified through the sanctioned tooling.** Every
   `ci` verb is PR-keyed (`checks status` accepts only `--pr-number` / `--head`, and `--head main`
   returns `no pull requests found for branch "main"`); there is no verb that reads a push-triggered
   run by commit. CLAUDE.md § Git Workflow step 8 assigns exactly that lookup to the orchestrator,
   and `deploy-snapshot` appears **only** there — the PR rows show it `SKIPPED`, as designed. So the
   epic cannot currently discharge its own stated post-merge obligation without leaving the CI
   abstraction. Recorded as an unverified lead, not as a green.
7. ✅ **Self-correction accepted as reported.** The plan's "202 tests" figure was a build-wrapper
   summary artifact, not the suite size (CI runs 1921 in `api-sheriff` alone). Not independently
   re-measured here; the green results are unaffected, and the correction is recorded because a
   coverage claim sourced from that number would have been wrong.
