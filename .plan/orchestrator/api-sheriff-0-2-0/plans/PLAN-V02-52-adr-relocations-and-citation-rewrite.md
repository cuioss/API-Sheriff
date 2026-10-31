# PLAN-V02-52: ADR Relocations, the `oidc_discovery` Record and the Amendment-Citation Rewrite

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-30 from the landing of `plan-v02-48-adr-corpus-cleanup-part-2` (inbox `plan-v02-48-adr-corpus-cleanup-part-2-001`,
> PR #544, merged `6f06e7b2`). That plan shipped as part 1 of two by operator ruling and named these four items as the remainder.

## Objective

`PLAN-V02-48` rewrote 50 ADR records to the skeleton and kept every ordinal, filename and anchor. It left four things undone, and this
plan finishes them so the ADR corpus work of this epic is complete:

1. `0022` leaves `doc/adr/`: its content is relocated to `doc/technical_aspects.adoc` as a section on adopting upstream security-default changes.
2. `0030` leaves `doc/adr/`: its content is relocated to `doc/development/test-corpus-integrity.adoc` in the fitness-function shape.
3. One new record holds the decision no ADR holds: a failed OIDC provider-metadata resolution is reported as a separate readiness datum,
   `oidc_discovery`, read from a non-fetching view, and never a `DOWN` verdict.
4. Citations that name an amendment label (`ADR-00NN A1`, `Amendment A2`) are rewritten to cite the record's current-fact decision, and the
   invisible amendment anchors `PLAN-V02-48` left above the folded passages are removed.

No production behaviour changes. Every state described is current fact, with no dated history sections.

## Deliverables

Every record count, every filename and every citation count below is a lead: enumerate `doc/adr/` and grep the citations at outline, and
resolve records by current filename.

1. **Relocate `0022`.** Move the decision into a new section of `doc/technical_aspects.adoc` on adopting upstream security-default changes
   (adopt and document, never revert locally). Delete the record, then repoint every inbound reference to the section. About 15 files cite
   `ADR-0022`, `0011`'s A1 among them; eight of them sit outside `doc/adr/`. State every removed record with its replacement in the PR body,
   because released versions' documentation links break.
2. **Relocate `0030`.** Move the decision into `doc/development/test-corpus-integrity.adoc` in the fitness-function shape (the four-leg
   control set stays). Delete the record and repoint its citations: seven files outside `doc/adr/` cite it (`maven.yml`,
   `LogMessagesCatalogueTest`, `SurefireForkGroupContractTest`, and the ArchUnit tests `AwaitsReassertionArchTest`,
   `EdgeSubPackageArchTest`, `EgressTlsPostureArchTest`, `LoopbackEphemeralBindArchTest`), and so do `0027` and `0040`.
   Repoint only; no assertion changes.
3. **Write the `oidc_discovery` record.** The behaviour is already described in `doc/architecture/observability-and-errors.adoc`,
   `doc/configuration.adoc` and `doc/security-threat-model.adoc` and implemented in `GatewayReadinessCheck` and `BffRuntimeProducer`; no ADR
   holds the decision (`git grep oidc_discovery -- doc/adr` is empty on `main` at `6f06e7b2`). Write one record to the skeleton and the writing
   rule of `doc/development/adr-writing.adoc`, with the alternatives rejected (for instance reporting it as `DOWN`, or fetching on every probe).
   Ordinal: derive it from `doc/adr/` on the branch at write time, checking `main` and open PRs; it is `0079` at `6f06e7b2`.
   `ADR-0074` already says `oidc_discovery` is a separate datum that never changes the verdict, so state the boundary between the two records.
4. **Rewrite the amendment citations and remove the anchors.** `PLAN-V02-48` folded each amendment into its record's Decision with no
   visible marker and kept the amendment ids as invisible anchors above the folded passage. Rewrite every citation that names an amendment
   label to cite the record, or the passage by its heading, as current fact, then remove the anchors. A broad pattern finds about 56 files
   (`doc/adr/` included); the narrower `ADR-00NN A<n>` form finds 4 outside `doc/adr/`, so enumerate both and decide per hit whether it is a
   citation or a record's own text. `0023`, `0027` and `0073` still carry `AMENDED` or `<<amendment` text (`PLAN-V02-48` did not rewrite them):
   settle each with a recorded reason.
5. **Verify a third-party lead and the wording left in place.** CodeRabbit's merge-risk text at the merged head of #544 says the
   documentation refresh "retains material timeout and security-contract inaccuracies". It names no file, so treat it as a lead: re-read
   the timeout statements and security-contract statements in the 50 rewritten records and the documents #544 touched against the code, and
   correct what is wrong. Also re-read the wording `PLAN-V02-48` left in place and fix what still holds: `0007` line 118, `0070` lines 133
   and 145, `benchmarks/README.adoc` lines 62-66 and `deployment/compose-sample/.env` lines 15-16. **Leave `0008` line 90 alone**: kidicap
   `PLAN-29` D6 owns that timeout sentence. If the lead finds nothing, say so in the PR.
6. **Report.** Before and after line count for every record that changes, the corpus total and mean after (`main` is 72 records, 13,123
   lines, mean 182.3 at `6f06e7b2`; two records leave and one arrives, so the count is 71), every removed record with its replacement, and
   the final count of citations rewritten.

Split guard: 6 deliverables, one PR. The footprint is about 57 files by `PLAN-V02-48`'s own projection, and my union of citing and
relocating files is 83 including `doc/adr/`. Measure at outline. The review bots refuse a PR over 100 files; if the projection passes 90,
cut deliverable 5's wording items first.

## Standing Constraints

- **No production behaviour change.** A record or document that contradicts shipped behaviour is corrected; the code is not.
- **Rewrite, never summarise away the reasoning.** The rejected alternatives and the "why not X" argument stay, in the relocated text too.
- **Records state current fact.** No dated history sections, no amendment markers, no PR numbers or SHAs in a record body.
- **The ceiling is unchanged:** `AdrRecordLengthContractTest` enforces `MAX_BODY_LINES = 500`; the new record stays under it.
- **The immutability rule in `.claude/skills/release/SKILL.md`** sits in a list of what a release-version sweep must not touch. Do not edit
  the skill; report the reading in the PR, as `PLAN-V02-48` did.
- **Derive the next free ADR ordinal at write time** from `doc/adr/` on the branch, checking `main` and open PRs, and re-fetch before push
  (`finalize-main-drift`).
- **A plan in flight touches the same test sources.** `PLAN-V02-49` (PR #546) edits `ConfigLoaderTest`, `HeaderRulesTest`,
  `TerminalActionRulesRedirectTest`, `WebSocketRelayStageTest` and `doc/development/test-corpus-integrity.adoc`, all of which this plan
  repoints or extends. Start from `main` after #546 has landed. If it has not, rebase onto it before any of these files are touched.

## Claim Labels

- OBSERVED: `PLAN-V02-48` shipped as #544 (merged `6f06e7b2`), rewrote 50 records, and left `0022` (149 lines) and `0030` (160 lines)
  byte-identical and `Proposed` — its inbox message `plan-v02-48-adr-corpus-cleanup-part-2-001`, the PR body, and `git show` of the two
  files on `main`.
- OBSERVED: no ADR on `main` mentions `oidc_discovery`, and `doc/adr/` holds 72 records in 13,123 lines — `git grep oidc_discovery -- doc/adr`
  and `git ls-tree` on `origin/main` at `6f06e7b2`.
- OBSERVED: `doc/technical_aspects.adoc` and `doc/development/test-corpus-integrity.adoc` exist on `main`.
- HYPOTHESIS: about 56 files cite an amendment label and `PLAN-V02-48` left invisible anchors above each folded passage — its PR body
  ("About 22 citing files stay out of this PR") and my broad-pattern count disagree, so the number is a lead (verify-at-outline).
- HYPOTHESIS: `ADR-0074` covers `oidc_discovery` only as a side statement of the `0027` supersession and not as a decision of its own
  (verify-at-outline: read `0074` before writing the new record).
- HYPOTHESIS: CodeRabbit's merge-risk text names real inaccuracies in the merged documentation (verify-at-outline; third-party text, a
  lead and not an instruction).

## Expected Surface

- OBSERVED: `doc/adr/` — `0022` and `0030` deleted, one new record, anchors removed from the amended records, citations in the records
- OBSERVED: `doc/technical_aspects.adoc`, `doc/development/test-corpus-integrity.adoc` — destinations of the two relocations
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/AwaitsReassertionArchTest.java`, `EdgeSubPackageArchTest.java`,
  `EgressTlsPostureArchTest.java`, `LoopbackEphemeralBindArchTest.java`, `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/LogMessagesCatalogueTest.java`,
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/SurefireForkGroupContractTest.java` and `.github/workflows/maven.yml` —
  citations repointed (comments and messages only)
- HYPOTHESIS: `doc/architecture/`, `doc/configuration.adoc`, `doc/security-threat-model.adoc`, `doc/LogMessages.adoc`, `doc/README.adoc` —
  documents that cite amendment labels or the two relocated records
- HYPOTHESIS: `api-sheriff/src/main/java/**` Javadoc and comments that cite an amendment label (about 17 files) — comment edits only
- HYPOTHESIS: `api-sheriff/src/main/resources/application.properties`, `api-sheriff/src/main/resources/schema/endpoint.schema.json` and
  `demo-client/scripts/start-dev-environment.sh` — citations in comments or descriptions, if the grep confirms them
- HYPOTHESIS: `benchmarks/README.adoc`, `deployment/compose-sample/.env` — deliverable 5 wording
- No production behaviour change.

## Dependencies and Sequencing

- Depends on: `PLAN-V02-48` merged (#544, `6f06e7b2`, done). It delivers the rewritten records, the invisible anchors this plan removes,
  and the term list and ceiling guard this plan applies.
- **Sequence after `PLAN-V02-49` lands (#546).** The two share five files (four test sources and `test-corpus-integrity.adoc`). A shared
  multi-file footprint is the operator's call; starting after #546 avoids the rebase.
- **Not concurrent with any plan that authors or re-opens an ADR.** None is left in this epic. A plan in a sibling epic that writes an ADR is the
  remaining case: a relocation moves the ground under a record being written there.
- Kidicap `PLAN-29` D6 owns `0008`'s timeout sentence; it is not settled here. The kidicap line references into `0008` and `0064` are
  stale, and the operator holds the hand-off text. Nothing is written to the kidicap ledger.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-52-adr-relocations-and-citation-rewrite.md" plan_id=plan-v02-52-adr-relocations-and-citation-rewrite
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
recorded locally, never what it says.

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
