# Landing Analysis: PLAN-V02-48 — ADR Corpus Cleanup, Part 2 (shipped as part 2a)

epic: api-sheriff-0-2-0
workstream: WS-02
pr: #544 (https://github.com/cuioss/API-Sheriff/pull/544)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-30 from the inbox message
> `plan-v02-48-adr-corpus-cleanup-part-2-001` (the landing). Checked against `origin/main` at `6f06e7b2`, the merge commit's file
> list and stat, the PR body and its D11 report, the corpus re-measured from `main`'s git blobs, the PR's review state and the CI runs
> (PR-branch Integration Tests at two heads, merge-group and `main` push runs). The plan ran once under its own plan id.
> `landing-check` reported `complete: true`, `effort=low`, `model=claude-opus-5-5`.

## Deliverable Fidelity vs Spec

#544 changed 77 files (+5,811 / −9,760): 59 ADR records (50 rewritten, 9 reworded), 3 production Java files (Javadoc only),
3 changed test sources plus 1 new contract test, 3 top-level documents (`README.adoc`, `benchmarks/README.adoc`,
`deployment/compose-sample/.env`) and 8 further documents. The plan's own functional review counts 9 deliverables met and 3 met
with residuals, none unmet. **That is a shipped-in-part plan:** the operator ruled, at the plan's own cut line, that this PR is
part 1 of 2, and four items the spec named were not done. The landing message and the PR body state it; I checked it against `main`.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — `0036`, `0037`, `0047`; decide the `0047` merge | shipped; **merge dropped, boundary stated** | All three rewritten and Accepted. `0036` owns which client entries cross, `0047` owns the form in which the query is validated and the spelling the query lists compare; both records state it. No ordinal moved, so none of the ~18 inbound references repointed. |
| D2 — BFF identity-provider legs (`0043`, `0046`, `0050`, `0051`, `0058`, `0069`) | shipped | `0058` decision 2 stated as current fact; the `AMENDED (A1)` marker is off the `summary:` line (verified: no `AMENDED` in `0058`). |
| D3 — scopes and session routes | shipped | `0049`/`0057` rewritten; amendment notes moved out of Status. |
| D4 — config neutrality and TLS edge | shipped | `0011` 388 → 160, `0025` 515 → 238; `0017` Proposed → Accepted against the front listener. |
| D5 — anchors, assets, policy resolution | shipped | `0007`, `0013`, `0014`, `0015`, `0028`, `0067` rewritten in place; ordinals, filenames and anchors kept. |
| D6 — token validation | **partial** | `0063` rewritten. **The `0022` relocation to `doc/technical_aspects.adoc` was not done**: `0022` is byte-identical to the base (149 lines, still `Proposed`). |
| D7 — release and image | shipped | `0034`, `0035`, `0039`, `0042`, `0070` rewritten; `0035` flipped to Accepted (`release.yml` line 19 carries the decision). |
| D8 — testing and structure conventions | **partial** | `0005`, `0033`, `0061`, `0062`, `0068`, `0071` rewritten; `0033` and `0068` examined and kept. **The `0030` relocation to `doc/development/test-corpus-integrity.adoc` was not done**: `0030` is byte-identical (160 lines, `Proposed`). |
| D9 — transport and routing singletons | shipped | 14 records rewritten, `0003` gains its Alternatives section (106 → 115). |
| D10 — wording pass | shipped | Nine records reworded, "outbound TLS" aligned to "egress TLS" in `doc/configuration.adoc`, `doc/security-threat-model.adoc` and two test sources. |
| D11 — report | shipped | I re-measured from `origin/main`: **72 records, 13,123 lines, mean 182.3**, exactly the PR's figures (before 17,328 / 240.7). Count includes all seven records outside the 52. The ceiling guard is unchanged. |
| D12 — documentation residue | **partial** | Fixed: the sub-document statement, `benchmarks/README.adoc`, `bff-session.adoc`, the `ResponseEnds` Javadoc, the `BffRuntimeProducer` `memoize` Javadoc, the `DefaultProfileReadinessTest` comment, and `DocumentedTokenValidationCategoriesContractTest` binds the documented metric categories to `EventCategory`. **Not done: the `oidc_discovery` record** (no ADR for it; next free ordinal is still 0079). |

**Operator-ruled cut.** The spec's split guard told the plan to cut the repoint-heavy decisions if the projection passed 90 files and
"report the remainder to the orchestrator as a finding so a successor can be staged". The plan did exactly that, with one addition: it
folded amendments into each Decision with no visible marker and kept the amendment ids as invisible anchors, so every `ADR-00NN A1/A2`
citation still lands. The cost is a known, temporary inconsistency: about 56 files (counted by me with a broad pattern, `doc/adr/`
included) cite an amendment label the rendered record no longer shows, until the successor rewrites the citations and removes the anchors.
That successor is staged as `PLAN-V02-52`.

**Two things the spec did not predict.** (a) All 24 `Proposed` records among the 50 became `Accepted`, which the spec expected only for
`0010`, `0017` and `0035`; the PR's report records the evidence per flip (reading code, tests and workflows, not a running system).
(b) A dead link was found and fixed: `doc/architecture/tls-and-inbound-security.adoc` pointed at a `0026-…` file that does not exist.

**Declared surface vs realised.** The spec's Expected Surface named `doc/technical_aspects.adoc`, `doc/development/test-corpus-integrity.adoc`
and the five ArchUnit/contract tests that cite `0030` as destinations of relocated content; none is in the merge diff, which is
consistent with the cut. The merge diff adds `deployment/compose-sample/.env`, `README.adoc` and `doc/user/compose-sample.adoc`, which came
from review-comment corrections. The spec is shipped, so no correction is made to its declaration.

## Metrics and Anomalies

- Tokens: 83,325,035. Wall time: 32,245 s (about 9.0 h) for 12 deliverables. Model claude-opus-5-5, **effort `low`**, plugin 0.1.2032,
  executor 0.1.2032. About twice V02-07's tokens in a third of its wall time. **Open Defect 18 is answered**: this landing carries `effort`.
- Step list: every finalize step `done` (adr-propose absent), `branch-cleanup` done, `cleanup_owed=false`. Parsed with a last-colon split.
- Functional review: 9 met, 3 met with residuals, 0 not met, 0 criteria unevidenced.
- **Code review closed by operator override with partial coverage**: 40 of the 50 rewritten ADRs were not read by it, and the final commit was
  not code-reviewed (the PR body lists them). `code-review` records `acceptance=operator_override`. This is the third recent plan to close a
  review by override (V02-20, V02-27 earlier); the change is prose, so the exposure is wording, not behaviour.
- The merge gate refused the operator's re-fire waiver for simplify and functional review, so both re-ran at the final head on the
  changes since their last pass. Only a factual error could block; none was found.

## Routing and Merge Behavior

- Review: CodeRabbit `SUCCESS` at the final head `30dcf800`, Sourcery `APPROVED` at the same head. Twelve CodeRabbit review threads from the
  first review at `254f69d2`; the CI abstraction reports **zero unresolved resolvable threads** (the four unresolved rows are
  `review_body` and `issue_comment` kinds with empty `thread_id`: CodeRabbit's two summaries, Sourcery's approval and its earlier
  rate-limit notice).
- **A lead I could not close.** CodeRabbit's summary at the merged head carries `Merge Risk: Moderate` with the text "The documentation
  refresh retains material timeout and security-contract inaccuracies. Correct these before merging…", coverage `reviewed` at `30dcf800`. It
  attaches to no thread, so I cannot say which statements it means. That is third-party text: a lead to verify, not an instruction. The
  successor carries a verification item for it (spec `PLAN-V02-52` D5).
- Merge: through the merge queue as `6f06e7b2` (merged 2026-10-30T16:38:42Z).
- CI: Integration Tests passed at both PR heads I could list (`254f69d2`, `30dcf800`). Post-merge on `main` at `6f06e7b2`, read the same day:
  Maven Build, Integration Tests, Config Validation, Demo Client E2E and Scorecard completed `success`; merge-group Maven Build and Integration
  Tests `success`. **The PR-attached Performance Benchmark run for `30dcf800` (`41091771555`) was still `in_progress` when I read it**, so
  the benchmark is unobserved, not green. For a documentation-and-Javadoc change it cannot move a measurement; the Watch below says to read it
  at the next analyze.

## Reconciliation Checklist

| Item | Action |
|------|--------|
| Queue row `PLAN-V02-48` | `running` → `shipped`, `pr` 544, landing `landings/PLAN-V02-48.md`, `plan_marshall_plan_id` `plan-v02-48-adr-corpus-cleanup-part-2` |
| ADR corpus | 72 records, next free ordinal **0079** (unchanged; no record added). `0022` and `0030` stay `Proposed`, untouched |
| Open Defect 18 | answered: this landing carries `effort=low` and is `complete: true`; strike at next compaction |
| Successor | `PLAN-V02-52` staged (see Follow-Ups); the plan's own message says no orchestrator-staged successor carried the work |
| `PLAN-V02-49` | in flight as PR #546 (52 files); five of its files are inside the successor's footprint, see Follow-Ups |
| Spec `PLAN-V02-49` | no change owed; its shared file with V02-48 (`test-corpus-integrity.adoc`) was not touched by V02-48 |

## Follow-Ups and Leads

1. **`PLAN-V02-52`** carries the four deferred items: the `0022` relocation, the `0030` relocation, the `oidc_discovery` record (`0079`) and the
   amendment-citation rewrite with the anchors removed, plus a verification item for CodeRabbit's merge-risk text.
2. **Known wording left in place by the plan** (its own list): `0007` line 118, `0008` line 90, `0070` lines 133 and 145,
   `benchmarks/README.adoc` lines 62-66 (the APISIX `bearer` row), `deployment/compose-sample/.env` lines 15-16. `0008` line 90 is the
   timeout sentence kidicap `PLAN-29` D6 owns. The successor re-reads the others and fixes what still holds.
3. **Open with no owner:** the APISIX `bearer` lane cannot validate tokens, so `run-comparison.sh --target both` stops there. Corroborated by
   `benchmarks/README.adoc` line 46 ("`bearer` has no valid APISIX side for now"). Recorded as a Watch, not a defect: it is a documented limit of a
   deliberately workflow-less comparison.
4. **Kidicap:** the `PLAN-29` line references into `0008` and `0064` are stale. The operator holds the hand-off text; nothing is written to the
   kidicap ledger from here.
5. Security notes were recorded locally by the plan; their content is not in tracked text, as the rule requires.
