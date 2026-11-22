# Landing Analysis: PLAN-31 — Publish a Refreshed JWKS Key Set by Comparing Key Ids, Not by the Triggering Key Id

epic: kidicap-gateway-requirements
workstream: WS-01
pr: #605 (merged through the merge queue as `5aa17338`, 2026-11-15)

> Landing record for one shipped plan. Written by the `analyze` verb after verifying claims against ground
> truth (the merge commit, PR state through the CI abstraction, the merged code). The lane's own report was
> treated as a lead, never a fact.

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — the exact publish rule in `RetryingJwksLoader.settleRefresh` | shipped-modified | The triggering key id plays no part and `kid` is gone from `onUnknownKeyId`, `runRefresh` and `settleRefresh`. The rule that shipped is narrower than the spec's "key ids differ at settle time": the refresh carries a `RefreshBasis` captured at the claim (current delegate, its key ids, plus the key ids of a retired delegate still inside its grace period), and `settleRefresh` (`RetryingJwksLoader.java:668-675`) discards when `basis.holdsAll(fresh.getKeyIds())` or `!basis.isCurrent(snapshot.get().delegate())`. Drop-only, empty and equal sets are discarded; the reasoning is in ADR-0063. |
| D2 — tests that pin the change | shipped-as-specified | `RetryingJwksLoaderTest`: `inventedKeyIdPublishesARotatedKeySet` :1010, unchanged set under a flood :872, drop-only :1081, empty :994, id-less current :1299, superset :1173, basis moved during the fetch :1153. `JwksKeyRotationIT` leg 3 `inventedKeyIdPicksUpARotatedKey` :307 (one key-set request, in-window deadline). |
| D3 — remove the residual from the record | shipped-as-specified | ADR-0063 amended in place (rule table, "Why a set that only drops key ids is discarded" :116, "Why a replaced loader's key ids count as held" :118, risks). GW-07 re-derived in the threat model (flood bullet, residual, status, summary row); `authentication-and-oidc.adoc` key-rotation section rewritten; the `JwksKeyRotationIT` Javadoc and assertion messages rewritten. No "triggering key id" publish wording is left in `doc/`, `api-sheriff/src` or `integration-tests/src` (grep at `5aa17338`; the one hit is a test assertion that the WARN never carries it). |

Expected surface: all six touched files lie inside the declared surface; no file is unexplained. The three
hypothesis-only files (`StubIdentityProviderRig`, the two `doc/development/` pages) were not touched.
The landing's `surface_delta` is `unmeasured` (it supplied neither declared nor realized paths).

## Three-Layer Analysis

The landing was read on three layers by one read-only leaf (`execution-context-level-3`, `orchestrator.analyze`
surface, one leaf per landing as the rule requires), and the orchestrator re-read the points recorded below as
`checked` at their `file:line` on `5aa17338`. The reading is by reading, no build, no test run, no write.

- **Dispatch:** one read-only leaf per landing — reason: the three-layer read of merged code is the depth the dispatch rule exists for, and the landing is a single full ship.
- **Verdict:** layer 1 findings (3 minor/major, spec-side); layer 2 findings (1 major, 2 minor, the rest confirm); layer 3 findings (4 minor) — **no blocker, no major defect in the merged code.**
- **Re-read by the orchestrator:** `RetryingJwksLoader.java` :222-250 (`RefreshBasis`), :540-565 (claim, basis before flag), :650-685 (`settleRefresh` and its Javadoc), :596-636 (`runRefresh`), :720-728 (`answeringRetired`); `RetryingJwksLoaderTest.java` :1130-1175; ADR-0063 :116-118; `JwksKeyRotationIT.java` leg structure; the stale-wording grep. **Not re-read** (recorded as unverified leads): the test helper `refreshOutrunByTheLoadedKeySet`, the `getKeyIds()` contract in the TokenSheriff 0.9.9 jar, the `package-info.java` check, and a clean `-Werror` build.

### Layer 1 — Specified

| Finding | Claim | Verdict | Polarity | Evidence |
|---------|-------|---------|----------|----------|
| L1-1 | The spec's D1 text ("publish when `getKeyIds()` differs from the current delegate's, compared at settle time") is not the rule that shipped (claim-time basis incl. retired ids, plus a basis-unchanged check); the frozen criterion is narrower than the code | checked | refutes | spec D1 vs `RetryingJwksLoader.java:668-675` |
| L1-2 | The D1 case list ("fresh adds a key id -> publish") and its headline ("differs") are not one predicate: drop-only differs yet is discarded | checked | refutes | spec D1 |
| L1-3 | The spec's design point "the background refresh can change the current delegate" is imprecise: the delegate cannot change during a refresh, only its key-id set moves; the shipped code reflects this | checked | refutes | `RetryingJwksLoader.java:654-658` |
| L1-4 | The drop-only requirement ("decide and state why") was resolved as discard with its rationale in ADR-0063 :116 | checked | confirms | ADR-0063 :116 |

**Layer 1 verdict:** findings — 3 (one major, two minor), all about the spec text, none about the shipped rule's soundness

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|-----------|--------------------|---------------------------------|---------|
| D1 | `RetryingJwksLoader.java:553-557` (basis before flag), :668-675 | `inventedKeyIdPublishesARotatedKeySet` :1010 | none; but two deviations from the spec's rule (basis moved during the fetch, retired key ids counted as held) have no `criteria-authorize` record | checked |
| D2 | — | the seven cases above | `refreshOutrunByADropIsDiscarded` :1133 does not appear able to fail if `isCurrent` were deleted, because `holdsAll` already discards its fetch (leaf reading; the helper was not re-read here); `inventedKeyIdPublishesARotatedKeySet` :1010 and `inventedKeyIdRefreshPublishesANewlyRotatedKey` :1035 are near-duplicates; IT leg 5 (:356) repeats leg 3 | checked (leaf) / unverified lead for the helper |
| D3 | ADR-0063, GW-07, configuration page, IT Javadoc | n/a | none | checked |

**Layer 2 verdict:** findings — 3 (D1 deviation, one possibly vacuous test, redundancy), all minor or advisory

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `RetryingJwksLoader.java:553-557` | a claim opened while an earlier refresh is still in flight, whose publish lands between the new claim's snapshot read and the flag take (needs a fetch longer than the window) | the new refresh carries a stale basis, is discarded by `isCurrent`, and wastes its claimed window; the class Javadoc :654-668 itself names the open check-then-act window | minor |
| L3-2 | `RetryingJwksLoader.java:670-675` | a refresh discarded because the held set moved, or because it only re-surfaces retired key ids | the window is consumed, so a genuinely rotated key can wait one further window; the cost is stated in ADR-0063 :205-209 and is bounded | minor |
| L3-3 | `RetryingJwksLoader.java:596-606`, :668 | an exception from `fresh.getKeyIds()` or `publishRefreshed` inside `settleRefresh` (no catch in the `whenComplete` lambda) | the flag is released by the `finally`, but the exception is swallowed by the dependent future, `fresh` stays unclosed and no `JWKS_REFRESH_FAILED` is logged; a loaded `OK` delegate is not expected to throw | minor |
| L3-4 | `RetryingJwksLoader.java:226-229` | a throwing or null-containing `getKeyIds()` on the request thread | `RefreshBasis.of` runs outside the try that shields scheduling, after the window is claimed and the WARN logged | minor, **not shown by reading** |

**Layer 3 not shown by reading:** whether an in-flight flag held forever by a fetch future that never completes is reachable (unchanged by this PR; needs the library source); clean `-Werror` build and the 7992 / 8295 test counts (run facts).

**Layer 3 verdict:** findings — 3, plus 1 not shown by reading; no blocker

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md Pre-1.0 | No `@Deprecated`; remove unused code | conforms | `carriesNewKey` and `kid` removed |
| CLAUDE.md Logging | `%s`, exception first, `LogRecord` | conforms | new calls are DEBUG with `%s`; WARNs reuse existing records |
| CLAUDE.md Javadoc | `@since`, thread-safety | conforms | class Javadoc :159-162; `package-info` not opened |
| CLAUDE.md Testing | JUnit 5, AAA, no Mockito | conforms | hand-written `MovingKeySetLoader` instead of a mock |
| CLAUDE.md Testing | a parsing key must act | conforms | no new configuration key |
| CLAUDE.md Compiler | zero warnings, no new `@SuppressWarnings` | not_shown_by_reading | no new suppression in the diff; a clean build needs a run |
| `adr-writing.adoc` | amend the ADR in its own record | conforms | ADR-0063 amended in place |

The leaf reported one deviation from its own read-only brief: a repository-wide `git grep` matched tracked
`.plan/` files and echoed some lines into its output. It wrote nothing and did not use that text.

## Metrics and Anomalies

- Tokens: 46,652,073.
- Duration: 385,296 s of wall clock (about 4.5 days), which spans three merges or rebases of `main` and several re-fires of both finalize reviews.
- Anomalies: PR #607 landed on `main` while this plan was in finalize and changed the same decision with a different rule; the owner kept this plan's claim-time basis, carried over #607's retired-delegate extra and removed #607's triggering-key-id clause (merge commit `7c0ee841` on the branch). The pre-push gate failed once earlier on an unrelated flaky `SniFrontListenerTest` (fixed on `main` by #609).

## Routing and Merge Behavior

- Review: CodeRabbit and Sourcery both reported success on the PR. The re-read through `review-gate` on the merged head gives `unresolved_review_threads: 0`, which **matches** the landing, and `review_body_findings_total: 4` against the landing's **5**: a **difference, recorded as a false claim** (the landing asserted a count the provider does not show, which may be a recount of one finding folded into another, not shown by reading). `review_body_findings_handled` is **not re-verified**: the provider shows 0 dispositions and the run's progress file is the only source of the dispositions.
- CI/merge: merged through the merge queue, merge-group run `43710119529` concluded `success`; every PR check is green, including SonarCloud and the PR-attached post-merge benchmark (`Run Integration Benchmarks`, run `43711768997`). The push-triggered Maven Build and Integration Tests runs on `5aa17338` are **not verified**: the CI abstraction reads PR-attached runs only.
- Sonar: the landing's `sonar_open_new_issues=0` is consistent with a green SonarCloud check on the PR; no separate measured figure was read.
- Collision observed: #607 overlapped this plan's decision and file (`RetryingJwksLoader.java`, its test, ADR-0063, the threat model). The declared surface did not name #607's plan, which was another epic's, so the gate had nothing to compare; recorded in `epic.md`.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-31 --status shipped`
- [x] row `pr` stamped — `#605`
- [x] row `landing` stamped — `landings/PLAN-31.md`
- [x] row `plan_marshall_plan_id` stamped — `jwks-exact-publish-rule`
- [x] epic.md narrative reconciled (PLAN-31 annotation, PLAN-33 annotation, the closed Watch retired, new defects and watches opened)
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- Layer-3 L3-1 and L3-3 and the layer-2 test findings go to the operator for a decision (one small fix-and-tidy plan would close all of them); nothing was fixed inline.
- The 457-file blank-line churn of `verify -Ppre-commit` after #618, which the landing reports: recorded as an Open Defect, owner not assigned (a formatter PR with the `skip-bot-review` label, like #608).
- Verify the push runs on `5aa17338` by hand before the next release (Watch).
