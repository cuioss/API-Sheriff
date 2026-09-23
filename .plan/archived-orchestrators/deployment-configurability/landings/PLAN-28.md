# Landing Analysis: PLAN-28 — Closeout Residual Hardening

epic: deployment-configurability
workstream: WS-09
pr: [#341](https://github.com/cuioss/API-Sheriff/pull/341)

> Reconciled 2026-09-23 from the plan's own `kind: landing` inbox message
> (`plan-28-closeout-residual-hardening-019.md`, `complete: true`), corroborated against ground
> truth: `ci pr view --pr-number 341` (state `merged`, squash `1994f28d2334fc4698d2352d47f3b72e6e3448ee`,
> matching the message's `footprint_base_sha`), `git show --stat` (56 files, +4089/-335), and
> `ci checks status --pr-number 341` (`overall_status: success`, 33 checks).

## Deliverable Fidelity vs Spec

All 7 spec deliverables shipped, plus a substantial security-audit residue the plan surfaced and
fixed during finalize rather than deferring.

| Deliverable (spec) | Verdict | Evidence |
|---|---|---|
| 1. `PARANOID` security preset | shipped-as-specified, **wider than scoped** | `SecurityProfile.java` +63 (`PARANOID` arm, `:188` `case PARANOID -> PARANOID_PRESET`); refine's own Q-Gate caught that the mode is restated at **three** symmetric JSON-Schema enum sites, not the one the spec named — `gateway.schema.json:36,229` and `endpoint.schema.json:31` all carry `paranoid`, plus a bound `SecurityProfileTest` |
| 2. `ConfigLoader` object-coercion arm | shipped-as-specified, **plus a real CWE-522 fix** | `ConfigLoader.java` +110, moved to a new `config/load/` subpackage (a declared-surface miss on this spec — see Surface fidelity below); a secret-pointer refusal guard was found and fixed as a genuine whole-object-substitution bypass around secret-classified fields, corroborated by candidate-lesson `2d252c`-sibling finding `594592` and `ConfigLoaderTest` +294 |
| 3. Back-channel logout, proven end-to-end | shipped-as-specified, **plus 3 real bugs + 1 CWE-117 fix** | `BffBackchannelLogoutIT` (+214, new), `SignatureOnlyTokenVerifier` (+179, new) replacing an ID-token verifier misapplied to logout tokens, `LogoutRejection`/`LogoutRejectionLog` (+193 combined) adding a bounded-cardinality `WARN.LOGOUT_TOKEN_REJECTED` where every rejection branch previously logged at DEBUG only (unobservable in production); `ReservedPathRegistry` +56 for host-gated routing |
| 4. Root-path normalisation consolidated | shipped-as-specified, **with a documented split, not a single helper** | New `integration-tests`-scoped `RootPaths.java` (+68) / `RootPathsTest.java` (+131) plus `lib-docker-compose.sh`'s existing `normalize_root_path`; `deployment/compose-sample/start-sample.sh` deliberately kept its own inline copy — a distribution-boundary exception the plan's own Q-Gate (finding `34a7e9`) surfaced and the operator ruled on, not an unfinished consolidation |
| 5. Stale `doc/technical_aspects.adoc` version citation | shipped-as-specified | `doc/technical_aspects.adoc` +8/- |
| 6. `WebSocketRelayStageTest`'s shared 5s ceiling | shipped-as-specified | `Awaits.java` +21 (purpose-named tier), `AwaitsTest.java` +83 — converted from three literal pinned values to a derived-inventory assertion after a CodeRabbit finding (`19a92e`) that the literal pins would silently pass an unpinned fourth tier |
| 7. `re_review_on_loopback` rationale | shipped-as-specified, **narrower than the spec offered** | `doc/development/re-review-on-loopback.adoc` (+70, new); the spec's own "comment or adjacent doc" fork was invalid for JSON, corrected at refine (finding `f3e1d0`) |

### Surface fidelity — one real miss, three declared-but-absorbed sequencing notes

Declared 18, realized 56 (matching the diff's full file count is not meaningful here — the useful
comparison is against this plan's OWN edits, not incidental doc/finalize residue):

- ⛔ **`ConfigLoader.java`'s declared path is now wrong** — this spec declared
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLoader.java`; the plan moved it to
  a new `config/load/` subpackage during execution. Not a defect in the plan (a legitimate refactor
  decision made at outline/execute time, outside this ledger's visibility), but the corpus's declared
  surface for this closed spec is now stale should anything ever re-read it.
- The seven deliverables' Claim Labels were all `OBSERVED`, none `HYPOTHESIS` — nothing here needed a
  verify-first re-grounding pass.
- Three of this spec's declared paths (`doc/LogMessages.adoc`, `integration-tests/src/main/docker/keycloak/integration-realm.json`,
  `integration-tests/.../BffLogoutIT.java`) were realized as declared; the remainder of the diff's 56
  files is finalize-stage residue (new ADRs, doc fixes, test conversions) the spec's Expected Surface
  was never meant to enumerate.

## Metrics and Anomalies

- Tokens: **10,287,592** total.
- Duration: wall **112,655s** (~31.3h).
- Anomalies, all self-disclosed rather than hidden:
  - `ci-verify`/`sonar-roundtrip` FIND steps re-fired multiple times against advancing HEADs (a full
    phase-5 rollback for wait-region-triage tasks, then two re-fires); the `steps` fact reports only
    the final settled state.
  - `cuioss-review-bot` needed a manual out-of-band re-review trigger three times (`re_review_on_loopback: false`
    — the same setting deliverable 7 documented the rationale for).
  - Two plan-marshall tooling defects surfaced and were reported via `SendFeedback` (not blocking):
    `scope_creep_check` emits a finding type `manage-findings` rejects outright (never persists — fired
    three times, residual counts 9/11/28 against threshold 5, all confirmed as the plan's own committed
    loop-back footprint rather than genuine creep); `ci_verify run`'s `mark-step-done` silently failed
    to overwrite a prior `loop_back` record on two green re-fires (corrected manually before landing).
  - ADR-0024 was found stale (missing `paranoid` from its mode-set enumeration) by this plan's own
    `adr-propose` pass and amended in the same commit as the three new ADRs.
  - ⛔ **ADR-0050 is a cross-epic ordinal collision, confirmed at HEAD.** This plan's ADR
    (`0050-A_JWT_whose_type_the_engine_exposes_no_entry_point_for_is_verified_signature-only...`)
    landed first (`1994f28`); an unrelated PR #343 (`feature/plan-16-application-portal`, belonging to
    a different orchestrator epic's own PLAN-16 — matched via `api-sheriff-0-2-0`'s own resume_anchor,
    which independently recorded "ADR corpus is now 50" for its portal work) landed second
    (`69b322b`) and claimed the same ordinal for an unrelated ADR
    (`0050-Portal_templates_render_on_a_standalone_Qute_engine...`). `doc/adr/` now holds two files
    both numbered 0050; genuinely next-free is 0053. Not this plan's fault — the later-landing PR did
    not re-check freshness against a concurrently-merging sibling epic. Cross-epic routing below.
  - A bounded jti-replay residual on the back-channel logout token (finding `e05266`) was accepted
    with rationale rather than fixed; CodeRabbit raised the same point independently and it was
    declined against the standing decision (`taken_into_account`, hash `01be48`).

## Routing and Merge Behavior

- Review: 26 review comments across the run; CodeRabbit found and the plan fixed a CWE-522 real bypass
  (message `594592`), a CWE-117 log-injection issue, two outside-diff-range `review_body` findings
  (closed-set blast radius the diff didn't touch — message `5b7752`), and the `AwaitsTest` pin-vs-derive
  gap (`19a92e`). A jti-replay finding (`e05266`/`01be48`) was declined with recorded rationale, not
  fixed.
- CI/merge: `ci checks status --pr-number 341` → `overall_status: success`, 33 checks. Merged via merge
  queue (squash), `1994f28`.

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` stamped `341`
- [x] row `landing` stamped `landings/PLAN-28.md`
- [x] row `plan_marshall_plan_id` stamped `closeout-residual-hardening`
- [x] epic.md queue reconciled from status.json
- [x] Watch retired: the epic's standing HYPOTHESIS about concurrent IT-lane plans sharing local Docker
      image tags is now CONFIRMED and FIXED — this plan hit it directly (a stale week-old
      `api-sheriff:jfr` produced 13 spurious config-violation failures across 8 fixture files this
      plan never touched), root-caused, and fixed (`integration-tests/pom.xml`,
      `start-integration-container.sh`, `stop-integration-container.sh` now select the just-built
      image explicitly per lane, commit `9a114f4`). Local lesson `2026-09-15-16-001` recurrence noted.
- [x] New Open Defect opened: ADR-0050 cross-epic ordinal collision (see above); routed to
      `api-sheriff-0-2-0`'s inbox as a cross-epic finding (that epic's own PLAN-16/PR#343 is the
      later-landing side of the collision).
- [x] resume_anchor updated
- [x] START-HERE and Ordered Queue blocks regenerated

## Follow-Ups

- ADR-0050 collision: routed to `api-sheriff-0-2-0` via cross-epic inbox message; not this epic's to
  fix unilaterally since the offending later PR belongs to that epic.
- `ConfigLoader.java`'s moved path (`config/load/` subpackage): no action needed — this epic's queue
  has no other live plan whose declared surface names the old path.
- Ten candidate-lesson messages from this plan's Q-Gate/review findings were promoted to the local
  lessons corpus, plus one folded into an existing lesson (see the inbox-drain disposition log). Seven
  plan-marshall tooling defects (`scope_creep_check` finding-type rejection, a stale-local-base
  refusal in `pre-submission-self-review`, and five flag-shape argparse rejections) were routed as 3
  findings to plan-marshall's own `lessons-routing` epic inbox (`api-sheriff-deployment-configurability-001/002/003`
  there) rather than duplicated into this corpus.
