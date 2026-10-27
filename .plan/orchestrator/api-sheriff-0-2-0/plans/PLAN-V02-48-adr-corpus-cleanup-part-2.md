# PLAN-V02-48: ADR Corpus Cleanup, Part 2 — The Records PLAN-V02-04 Deferred

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-25 on the request of `plan-v02-04-adr-corpus-cleanup` (inbox `plan-v02-04-adr-corpus-cleanup-001`),
> which audited all 71 records and shipped six groups in PR #521 (merged, `f5826465`). This plan takes the nine
> groups it deferred.

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
   heading, its alternatives are inline, so the skeleton rewrite is owed. `0058` decision 2 now requires two
   different key files holding two different keys (the amendment shipped in #527, with `0072` as the sibling record
   for the cookie sealing key). Keep that decision as it stands, state it as current fact, and drop the
   `AMENDED (A1)` marker from the `summary:` line, which reads as bookkeeping.
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
    `doc/security-threat-model.adoc` and two test sources; align those to "egress TLS". Two more corrections in
    records `PLAN-V02-04` rewrote: the `0060` sentence that says no test covers the `dispatchGrpc` hand-off and the
    fixed five-minute value (`TerminalDispatchGrpcStreamIdleTest` now covers both; only the public one-line
    hand-over is unasserted, as the threat model says), and the `0066` list of users of the end rule, which names
    the failed-dial answer and omits the relayed upgrade refusal.
11. **Report.** Before and after line count per record, plus corpus total and mean before and after, as
    `PLAN-V02-04` did. The ceiling stays enforced by `AdrRecordLengthContractTest`; the plan changes no ceiling.
    The corpus stood at 65 records, 15,289 lines and a mean of 235 per file on `main` at `f5826465`; `0072` (new in
    #527, Accepted, 226 lines) and `0073` (new in #528, Accepted) are outside the 52 and make it 67 records on `main`
    at `c957ee51`. Measure the before figure from the branch base, count both in both totals, and say so. The early records average about 136. Say where the
    result lands against that and why.
12. **Documentation residue of `PLAN-V02-04`.** Four statements outside `doc/adr/` that the WebSocket pre-upgrade
    end-rule fix or the rebase left untrue. Re-read each at outline; one that no longer holds is dropped. (The two
    overstated offline-check statements #527 left were fixed by #529, `c9c8479c`, and are no longer owed.)
    `doc/architecture.adoc` (near line 587) says the end rule leaves the WebSocket path unaffected and omits the
    pre-upgrade answer from its list of end-rule writers. `benchmarks/README.adoc` (near line 514) says Methodology
    lists every parity condition, but the list omits the shared auth realm and tokens. `doc/development/bff-session.adoc`
    (near line 302) credits the native-image residual verdict to entry 3 only; its EC key-generation part is verified
    in entry 4. The `ResponseEnds` Javadoc names the failed-dial answer only; add the relayed upgrade refusal.

Split guard: 12 deliverables, one PR (the 12 the operator authorised per plan). The footprint is the 52
rewrites, deliverable 12 (three documentation files and one Javadoc), plus the repointed files of three decisions: the `0022` relocation (about 15 files), the `0030` relocation
(5) and the `0036`/`0047` merge (about 18). That sums to about 90 files against the 100-file review-bot cap. Measure
the projection at outline. If it exceeds 90 files, deliverables 1, 6 and 8 carry the repoint-heavy decisions: cut
there, ship the rest, and report the remainder to the orchestrator as a finding so a successor can be staged.

## Standing Constraints

- **Records amended by recent plans keep their content.** #527 amended `0058`; #528 amended `0007`, `0016`, `0023`,
  `0037`, `0049`, `0055` and `0059` with the uniform-`404` rule (ADR-0073); #526 amended `0008` and `0037` for the identity-header
  strip and the `not_modified` close. Rewrite each to the skeleton and state the
  amended rule as current fact, with no amendment marker or history. Re-read each against `main` at outline.
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
  ceiling guard — its inbox message `plan-v02-04-adr-corpus-cleanup-001` and PR #521 (merged, `f5826465`)
  - verdict: corroborated | checked_at: e087e5cec0ac2a48443e5df02882163bd38ecd53 | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: PR #521 merged as f5826465; doc/adr holds 65 records (6 deleted, 12 rewritten); the deferral message names nine groups and 53 records
- OBSERVED: `doc/development/adr-writing.adoc` carries the rubric, skeleton, writing rule and term list, and
  `AdrRecordLengthContractTest` enforces the 500-line body ceiling (`MAX_BODY_LINES = 500`) — both on `main` at
  `f5826465`
  - verdict: corroborated | checked_at: e087e5cec0ac2a48443e5df02882163bd38ecd53 | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: adr-writing.adoc is 385 lines on main; AdrRecordLengthContractTest has MAX_BODY_LINES = 500 and a fixture control at 500 and 501
- HYPOTHESIS: the verdict table of `PLAN-V02-04` deliverable 2 holds for every record named above — read it in the
  #521 body; refutation per record re-scopes that record (verify-at-outline)
- OBSERVED: `doc/adr/` holds 67 records on `main` at `c957ee51` (the 65 left by #521 plus `0072` and `0073`, both Accepted), and
  `0027-*` is Proposed and owned by `PLAN-V02-09` — `git ls-tree --name-only origin/main doc/adr/`
  - verdict: corroborated | checked_at: c957ee513126fe929dd5b3266212fdb3159f7a3b | by: api-sheriff-0-2-0/analyze | rescoped: n/a | evidence: git ls-tree on origin/main doc/adr/ lists 67 records; 0027 Proposed, 0058 0072 0073 Accepted
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
- HYPOTHESIS: `doc/architecture.adoc`, `benchmarks/README.adoc`, `doc/development/bff-session.adoc` and the
  `ResponseEnds` Javadoc — four statements of deliverable 12 (verify-at-outline)
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/` — the ArchUnit tests that cite `0030`, and
  the two test sources that say "outbound TLS" (repointed or reworded only)
- OBSERVED: every inbound `ADR-00NN` reference and `link:…adr/…adoc` across `doc/`, `api-sheriff/src/**`,
  `integration-tests/**` and `CLAUDE.md`, touched only by a relocation, merge or deletion
- No production behaviour change.

## Dependencies and Sequencing

- Depends on: `PLAN-V02-04` merged (#521, `f5826465`, done). It delivers the rubric, the skeleton, the term list and
  the ceiling guard this plan applies.
- **Not concurrent with any plan that authors or re-opens an ADR:** `PLAN-V02-07`, `PLAN-V02-09` (re-opens `0027`), `PLAN-V02-11` and `PLAN-V02-50` (`PLAN-V02-20`, `PLAN-V02-06` and
  `PLAN-V02-05` landed as #527, #528 and #526).
  A relocation or merge here moves the ground under a record being written there, and the ceiling guard fails a
  concurrent plan that lands an over-long record.
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
