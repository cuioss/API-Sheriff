# PLAN-V02-04: ADR Corpus Content Audit and Consolidation

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

## Objective

`doc/adr/` has grown past seventy records and seventeen thousand lines without anyone ever reading it
as a whole. An ADR for a project like this is **short and focused**: one decision, the alternatives
rejected, the consequences. A record that runs past a few hundred lines is a novel, and a novel is not
read, so it is not believed.

**The rule this plan enforces: no record exceeds 500 lines, excluding the meta block** (title, status,
date, supersession and amendment pointers). Most records should land far below it. A decision that
cannot be stated in 500 lines is two decisions, or it is implementation narrative that belongs in
`doc/development/**` or `doc/user/**`.

The plan reads the corpus as a whole and asks four questions per record: **is it actually an
architectural decision, is it still true, does another record already say it, and does it fit the
ceiling while staying focused on one decision?** The output is a smaller corpus where every remaining
record is a real decision, stated once, at a length that matches its weight and never above the ceiling.

**The aim is a good reduction of the whole corpus, not a fixed total.** There is no total line limit
and the plan does not hit a number. It reports the corpus size and the mean record length before and
after, and it explains the result. The reference point is the early records: `0001`–`0017` average about
136 lines, and the mean doubled mid-project. A result near that mean is the expectation; a result far
above it needs a stated reason.

**This is a content audit, not a hygiene pass.** Numbering, filename form and the layout of the meta
block are explicitly *not* the subject — the operator ruled on that. Fix such things only where a merge
or deletion forces it.

**Every count in this spec is a lead.** The corpus has grown at each measurement (33, 37, 49, 55, 62, 71
records). Enumerate `doc/adr/` at outline and work from that listing, resolving records by current
filename, never by a number quoted here.

## Deliverables

1. **Classify every record, then delete, relocate or keep.** An ADR records a decision between real
   alternatives, with consequences that outlive the code that implements it. A scope statement, a build
   convention, a process policy or a testing convention is developer documentation, not a decision.
   Write the rubric down first, apply it to every record, and give one verdict per record: **keep,
   relocate (naming the destination), or delete.**

   Leads worth examining first, **not verdicts, and not exhaustive**: `0002` (scope statement), `0012`
   (a CI process decision), `0021` (a build convention), `0022` (a policy), `0030` and `0031` (testing
   conventions). Records from `0034` on have never been screened. `doc/development/build-gate-discipline.adoc`
   exists and is the natural destination for a relocated build-convention record; check it before
   creating a page.

2. **Merge what is said twice.** Read for *content* overlap, not title similarity. Known clusters, drawn
   from the first 33 records, to be extended by reading the later ones:
   - **Inbound validation bounds** — `0023`, `0024`, `0026`, `0029` (`0029` applies the principle `0026`
     states; the clearest merge candidate).
   - **Config neutrality** — `0011`, `0025`, `0032`: one principle stated three times.
   - **Anchors and assets** — `0007`, `0013`, `0014`, `0028`. **BFF** — `0018`, `0019`. **TLS edge** —
     `0017`, `0025`.

   Per cluster, merge into one record, or keep them apart with the boundary stated, or extract the
   shared principle into one record the others reference. Record which, and why.

3. **Rewrite every survivor to one skeleton and one writing rule.** This is what shrinks the corpus
   as a whole; the ceiling in deliverable 4 only catches the outliers.
   - **One skeleton.** Each record has: the decision, the alternatives rejected and why, the
     consequences, and links out. Content that fits none of these moves out or is deleted.
   - **Derivable-content test.** If the code, the config schema, a test or a page under `doc/**`
     already says it, the ADR links to it and does not restate it. Implementation narrative, field
     lists and operator how-to fail this test by default.
   - **Writing rule, in the spirit of ASD-STE100, not the full standard.** Sentences under about 20
     words. One idea per sentence. One term per thing: a concept keeps one name across the whole
     corpus, never a synonym for style. Build the term list first from the config, BFF, TLS and
     routing vocabulary and apply it to every record. Active voice. The rules are language-neutral.
   - **The rule must not cut the reasoning.** Short sentences are fine; dropping the "why not
     alternative X" argument is not. Where a short-sentence rewrite would lose a connective step of the
     argument, keep the step.
   - **Record the term list** in `doc/development/**` so the next ADR author uses it.

4. **Bring every survivor under the ceiling and make it true.**
   - **Ceiling.** Enumerate every record over 500 lines at outline (at `e70743f7`: `0058` 806, `0018` 677,
     `0066` 527, `0060` 520, `0040` 518, `0025` 515; #513 grew `0066` and `0060`) and every record near it. Cut each to the ceiling or below by
     **moving or deleting content, never by summarising away the reasoning**: implementation narrative,
     restated rationale and operator how-to go to `doc/development/**` or `doc/user/**` and the ADR links
     there. The rejected alternatives and the "why not the other thing" are the load-bearing part of an
     ADR and the first thing a careless edit removes; they stay. A record that cannot get under the ceiling
     without losing its reasoning is split into focused records, not trimmed blind.
   - **Report before/after line counts per record, plus the corpus total and mean before and after,**
     so the reduction is auditable, and add a guard so the
     ceiling holds: an architecture/contract test next to `AdrOrdinalUniquenessContractTest` that fails
     the build on a record over 500 lines excluding its meta block. A guard that is not wired to a
     test proves nothing, so the plan demonstrates it red on an oversized fixture before green.
   - **Truth.** An ADR that describes superseded behaviour is worse than none, because it is believed.
     Check each survivor against the shipped code and mark superseded ones as such rather than editing
     history. **Statuses are the largest part of this:** 45 of 71 records read `Proposed` at `00293178`,
     most for shipped work (`0059` and `0060` are among them); re-count at outline.
   Known lead for the truth check: ADR-0066 Amendment A2 names three triggers of the abortive upstream close
   (the early answer, the client abort, the 25 s body deadline) and omits the request-body idle timeout. That
   timeout resets the upstream request and closes a partly sent HTTP/1.1 connection the same way (`DispatchStage`
   Javadoc, ADR-0066 A1). Add it as a fourth trigger or state why it is not one, and check that a test covers it.

   Not this plan's to settle:
   - **`0008`**: its untrue timeout sentence (*"Timeouts are transport options on the shared Vert.x client
     … not fault-tolerance timeouts"*) belongs to kidicap `PLAN-29` D6. Classify `0008`'s status like any
     other record; leave the sentence alone unless `PLAN-29` has landed.
   - **`0027`** (the token-validation extension's unqualified beans are excluded, not accommodated) is
     re-examined by `PLAN-V02-09`. Note it; do not settle it.
   - **`0005`** reads `Superseded by ADR-0062` (landed by `PLAN-V02-01`, #410); `PLAN-V02-01` also amended
     `0018`, `0051`, `0057` and `0061`. Check each against the shipped code like the rest.

## Claim Labels

- OBSERVED: `doc/adr/` holds 71 records (`0001`–`0071`, contiguous; ~17,445 lines) at `00293178`, and has grown at every measurement — `git ls-tree --name-only 00293178 doc/adr/`
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: doc/adr/ holds 71 records (0001-0071 contiguous, ~17,445 lines) at 00293178; claim and body re-scoped
- OBSERVED: 45 of 71 records read status `Proposed` at `00293178` (24 Accepted or Accepted-and-amended, 2 Superseded: `0005` and `0034`) — first line of the `== Status` block per record
  - verdict: contradicted | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: 45 of 71 Proposed, 24 Accepted or amended, 2 Superseded (0005, 0034) at 00293178; claim and D3 re-scoped
- OBSERVED: every ordinal in `doc/adr/` is claimed by exactly one record, and `AdrOrdinalUniquenessContractTest` fails the build on a duplicate — `doc/adr/` basenames `uniq -d`; the former duplicate `0053` was resolved by renumbering the header-matcher record to `0056`
  - verdict: corroborated | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: 71 basenames, no duplicate ordinal; AdrOrdinalUniquenessContractTest guards it
- OBSERVED: the artifact-purity and nullable-not-Optional records are `0032` and `0033`, each with an ordinal of its own — `doc/adr/0032-*`, `0033-*`
  - verdict: corroborated | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: 0032 and 0033 each hold their own ordinal
- OBSERVED: `0005-module-structure.adoc` reads `Superseded by ADR-0062` (landed by `PLAN-V02-01`), and `0027` is `Proposed` and not re-opened, so V02-09 has not pre-empted this audit — confirm again at outline (verify-at-outline)
  - verdict: corroborated | checked_at: 00293178dd8772344f5a908878719b78d67b6524 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: 0005 Superseded by ADR-0062; 0027 still Proposed, untouched since 386f3f74

## Expected Surface

- `doc/adr/**` — every record
- Destinations for anything relocated out of the decision log — `doc/development/**`, `doc/user/**`
- The ADR term list under `doc/development/**`
- The ceiling guard test beside `AdrOrdinalUniquenessContractTest` under `api-sheriff/src/test/**`
- Every inbound `ADR-00NN` reference and `link:…adr/…adoc` across `doc/`, `api-sheriff/src/**`,
  `integration-tests/**`, `benchmarks/**`, `pom.xml`, `*.properties`, `*.json`, `*.yaml` — merges and
  deletions break links, and a dangling xref is a broken AsciiDoc build
- Further inbound references outside those trees: `CLAUDE.md`, `README.adoc`,
  `.claude/skills/release/SKILL.md`, `.github/workflows/**`
- No production behaviour change. If the audit finds an ADR that contradicts shipped behaviour, that
  is a finding to report — the ADR is corrected, the code is not.

## Dependencies and Sequencing

- Depends on: none. `PLAN-V02-19` has landed, so the audit starts from a corpus with unique ordinals.
- **Not concurrent with any plan that authors or re-opens an ADR**: `PLAN-V02-06`, `PLAN-V02-07`,
  `PLAN-V02-09`, `PLAN-V02-11`. A merge or
  deletion here moves the ground under a record being written there. The ceiling guard also fails any
  concurrent plan that lands an over-long record, so such a plan must be rebased onto it.
- Merges and deletions break links in released versions' documentation. That cost is real and is
  accepted; report every removed or merged record with its replacement so the break is traceable.
- Size the PR at outline: the review bots refuse PRs over 100 files, and a corpus-wide edit can reach
  that. Split on cluster boundaries if it would.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-04-adr-corpus-cleanup.md" plan_id=plan-v02-04-adr-corpus-cleanup
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
