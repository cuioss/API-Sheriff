# PLAN-V02-48: ADR Corpus Cleanup, Part 2 — The Records PLAN-V02-04 Deferred

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-25 on the request of `plan-v02-04-adr-corpus-cleanup` (inbox `plan-v02-04-adr-corpus-cleanup-001`),
> which audited all 71 records and shipped six groups in PR #521. This plan takes the nine groups it deferred.

## Objective

`PLAN-V02-04` set the rule for the ADR corpus: no record exceeds 500 lines excluding its meta block, every
record follows one skeleton (decision, alternatives rejected, consequences, links out), and one writing rule and
term list apply. It shipped the rubric, the skeleton, the term list and the ceiling guard, and rewrote 12 records
plus 5 relocations. This plan applies the same rubric, skeleton and writing rule to the 52 records that remain,
and settles the status of each against the shipped code. The aim is the same as before: a good reduction of the
whole corpus, not a fixed total, reported as corpus size and mean record length before and after.

Content audit, not hygiene: numbering, filename form and meta-block layout are not the subject. Rewrites keep
their ordinals, so inbound references stay valid; only a merge, a deletion or a relocation repoints references.

## Deliverables

The verdicts below come from `PLAN-V02-04`'s deliverable 2 table (copied into its PR body). "Keep" means: rewrite
to the skeleton and the writing rule, then check the status against the shipped code. Every record count and every
filename is a lead: enumerate `doc/adr/` at outline and resolve records by current filename.

1. **Forward and header policy.** `0036`, `0037`, `0047`. `0037`'s ceiling cut is done (body 583 → 498, status
   Accepted); its skeleton rewrite is owed. Decide whether `0047` merges into `0036`. That merge alone repoints
   about 18 files, so decide it on content overlap, and state the boundary if the two stay apart.
2. **BFF identity-provider legs.** `0043`, `0046`, `0050`, `0051`, `0058`, `0069`. `0046`: move the amendment
   note out of Status into the body. `0058`'s ceiling cut is done (780 → 497, Accepted); it has no Alternatives
   heading, its alternatives are inline, so the skeleton rewrite is owed.
3. **Scopes and session routes.** `0048`, `0049`, `0055`, `0057`. `0049`'s session half is superseded by `0057`:
   mark that cleanly in place of the 16-line note inside Status. `0057` is already Accepted; its three amendment
   notes sit inside Status (a 58-line meta block) and move into the body. Moving notes raises the body count:
   measure against the ceiling before the rewrite.
4. **Config neutrality and TLS edge.** `0011`, `0017`, `0025`, `0032`, `0038`. `0011`: the deferred-knobs table
   and the amendment detail are candidates for `doc/configuration.adoc`. `0017`: check the Proposed status against
   the shipped front listener. `0025` is 491 lines (9 under the ceiling) and joins two clusters. `0032`:
   `doc/development/README.adoc` § Artifact Purity restates most of it, so make one of the two link to the other.
   `0038` is close to a build convention: re-examine it beside the `0021` relocation `PLAN-V02-04` shipped.
5. **Anchors, assets, policy resolution.** `0007`, `0013`, `0014`, `0015`, `0028`, `0067`. `0014` owes its
   skeleton rewrite. About 75 files reference this group, so rewrite in place and keep every anchor and ordinal.
6. **Token validation.** `0022` relocates to `doc/technical_aspects.adoc` (a section on adopting upstream
   security-default changes); about 15 files reference it, `0011` A1 among them. `0063` is kept. **`0027` is not
   touched:** `PLAN-V02-09` re-examines it.
7. **Release and image.** `0034` (superseded by `0035`, marked), `0035`, `0039`, `0042`, `0070`. `0035`: flip
   Proposed to Accepted only after confirming the shipped `.github/workflows/release.yml` implements it.
   `PLAN-V02-04` repointed only `0039`'s `0031` mention.
8. **Testing and structure conventions.** `0005` (superseded by `0062`, marked; remove the "superseded by"
   sentence inserted into the 0.1.0 snapshot section), `0030`, `0033`, `0061`, `0062`, `0068`, `0071`. `0030`
   relocates to `doc/development/test-corpus-integrity.adoc` (the fitness-function shape); five ArchUnit and
   contract tests cite it. `0033` and `0068` are close to coding and code-organisation conventions: re-examine each
   against the rubric.
9. **Transport and routing singletons (skeleton rewrite only).** `0001`, `0003`, `0004`, `0006`, `0008`,
   `0009`, `0010`, `0016`, `0020`, `0044`, `0052`, `0053`, `0059`, `0064`. `0003`: add the missing Alternatives
   section. `0004`: fold A1 and A2 into the decision and move the A2 migration list out as implementation
   narrative. `0006`: the competitor table is research evidence that may be trimmed. `0008`: **leave its timeout
   sentence alone** unless kidicap `PLAN-29` D6 has landed. `0010`: check the Proposed status against
   `ConfigLoader`.
10. **Wording pass.** Apply the term list to the residue `PLAN-V02-04` left in records it rewrote: "refuse" for a
    request stopped at runtime (`0029`, `0060`, `0066`), "routing" for route selection (`0019`, `0026`, `0029`,
    `0054`), unqualified "warning" for the boot WARN (`0023`, `0024`, `0054`), and "outbound" beside "egress"
    (`0040`). Outside the records, "outbound TLS" remains in `doc/configuration.adoc`,
    `doc/security-threat-model.adoc` and two test sources; align those to "egress TLS".
11. **Report.** Before and after line count per record, plus corpus total and mean before and after, as
    `PLAN-V02-04` did. The ceiling stays enforced by `AdrRecordLengthContractTest`; the plan changes no ceiling.

Split guard: 11 deliverables, one PR (under the 12 the operator authorised per plan). The footprint is the 52
rewrites plus the repointed files of three decisions: the `0022` relocation (about 15 files), the `0030` relocation
(5) and the `0036`/`0047` merge (about 18). That sums to about 90 files against the 100-file review-bot cap. Measure
the projection at outline. If it exceeds 90 files, deliverables 1, 6 and 8 carry the repoint-heavy decisions: cut
there, ship the rest, and report the remainder to the orchestrator as a finding so a successor can be staged.

## Standing Constraints

- **No production behaviour change.** If the audit finds an ADR that contradicts shipped behaviour, report it: the
  ADR is corrected, the code is not.
- **Rewrite, never summarise away the reasoning.** The rejected alternatives and the "why not X" argument are the
  load-bearing part of a record and stay. A record that cannot reach the ceiling without losing its reasoning is
  split into focused records, not trimmed blind.
- **The immutability rule in `.claude/skills/release/SKILL.md`** ("An ADR is immutable") sits in a list of what a
  release-version sweep must not touch. In-place rewrites are what both ADR-cleanup plans do. Read the rule at
  outline and do not edit the skill unless the operator says so; report the reading in the PR.
- **One test gap stays unfixed, by operator ruling:** no test pins the abortive HTTP/1.1 upstream close on the
  request-body idle-timeout trigger or the 25 s deadline trigger (`0066`). The gap is recorded in `0066` and the
  GW-07 threat-model entry. Report it; do not write a test in this plan.
- Size the PR at outline (see the split guard). Review bots refuse PRs over 100 files.
- Derive the next free ADR ordinal from `doc/adr/` on the branch at write time, checking `main` and open PRs. This
  plan adds no new record unless a split forces one.

## Claim Labels

- OBSERVED: `PLAN-V02-04` deferred nine groups covering 53 records and shipped 12 rewrites, 5 relocations and the
  ceiling guard — its inbox message `plan-v02-04-adr-corpus-cleanup-001` and PR #521 (open at staging)
- HYPOTHESIS: `doc/development/adr-writing.adoc` carries the rubric, skeleton, writing rule and term list, and
  `AdrRecordLengthContractTest` enforces the 500-line body ceiling — both arrive with #521; confirm on `main` after
  the merge (verify-at-outline)
- HYPOTHESIS: the verdict table of `PLAN-V02-04` deliverable 2 holds for every record named above — read it in the
  #521 body; refutation per record re-scopes that record (verify-at-outline)
- OBSERVED: `doc/adr/` holds `0001`–`0071` on `main` at `6b8d88f4`, and `0027-*` is Proposed and owned by
  `PLAN-V02-09` — `git ls-tree --name-only 6b8d88f4 doc/adr/`
- OBSERVED: the destinations `doc/technical_aspects.adoc`, `doc/configuration.adoc`,
  `doc/development/README.adoc`, `doc/development/test-corpus-integrity.adoc` and `doc/user/tls-edge.adoc` exist on
  `main` at `6b8d88f4`
- HYPOTHESIS: about 18 files reference `0047`, 22 reference `0036` and about 15 reference `0022` —
  `git grep -l 'ADR-00NN'` at `6b8d88f4`, counted before #521's repoints (verify-at-outline)
- HYPOTHESIS: the Proposed records `0010`, `0017` and `0035` describe shipped behaviour and flip to Accepted — read
  `ConfigLoader`, the front listener (`tls/SniFrontListener.java`) and `.github/workflows/release.yml`
  (verify-at-outline)

## Expected Surface

- OBSERVED: `doc/adr/` — the 52 records of deliverables 1–10 (not `0027`); records are rewritten in place
- HYPOTHESIS: `doc/development/adr-writing.adoc` — the term list, read and applied (extended only if a term is missing)
- OBSERVED: `doc/technical_aspects.adoc`, `doc/configuration.adoc`, `doc/development/README.adoc`,
  `doc/development/test-corpus-integrity.adoc` — destinations of relocated content
- OBSERVED: `doc/security-threat-model.adoc` — "outbound TLS" wording and the `0066` gap entry (deliverable 10)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/` — the ArchUnit tests that cite `0030`, and
  the two test sources that say "outbound TLS" (repointed or reworded only)
- OBSERVED: every inbound `ADR-00NN` reference and `link:…adr/…adoc` across `doc/`, `api-sheriff/src/**`,
  `integration-tests/**` and `CLAUDE.md`, touched only by a relocation, merge or deletion
- No production behaviour change.

## Dependencies and Sequencing

- Depends on: `PLAN-V02-04` merged (#521). It delivers the rubric, the skeleton, the term list and the ceiling guard
  this plan applies; staged work must not start on a corpus without them. #521 was `DIRTY` against `main` at
  staging.
- **Not concurrent with any plan that authors or re-opens an ADR:** `PLAN-V02-04`, `PLAN-V02-06`, `PLAN-V02-07`,
  `PLAN-V02-09` (re-opens `0027`) and `PLAN-V02-11`. A relocation or merge here moves the ground under a record
  being written there, and the ceiling guard fails a concurrent plan that lands an over-long record.
- `PLAN-V02-09` re-examines `0027`; kidicap `PLAN-29` D6 owns `0008`'s timeout sentence. Neither is settled here.
- Merges and relocations break links in released versions' documentation. Report every removed or merged record
  with its replacement so the break is traceable.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-48-adr-corpus-cleanup-part-2.md" plan_id=plan-v02-48-adr-corpus-cleanup-part-2
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
