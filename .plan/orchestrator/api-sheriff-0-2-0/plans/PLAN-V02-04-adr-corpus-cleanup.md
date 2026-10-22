# PLAN-V02-04: ADR Corpus Content Audit and Consolidation

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

## Objective

`doc/adr/` has grown past sixty records and fourteen thousand lines without anyone ever reading it as
a whole. This plan reads it as a whole and answers four questions per record: **is it actually an
architectural decision, is it still true, does another record already say it, and does it need to be
this long?**

The output is a smaller corpus where every remaining record is a real decision, stated once, at a
length that matches its weight.

**This is a content audit, not a hygiene pass.** Numbering, filename form and metadata blocks are
explicitly *not* the subject — the operator ruled on that. Fix such things only where a merge or
deletion forces it.

**Every count in this spec is a lead.** The corpus has grown at each measurement (33, 37, 49, 55, 62, 71
records). Enumerate `doc/adr/` at outline and work from that listing, resolving records by current
filename, never by a number quoted here.

## Deliverables

1. **Classify every record: is it an ADR?** An ADR records a decision between real alternatives, with
   consequences that outlive the code that implements it. Several records look like something else —
   a scope statement, a build convention, a process policy, a testing convention — and those belong
   in developer documentation, not in the decision log. **Write the rubric down first, then apply it
   to every record and give a verdict per record: keep, relocate (naming the destination), or delete.**

   Candidates worth examining first, from a read of the titles — **leads, not verdicts, and the list
   is not exhaustive**: `0002` (initial protocol and configuration scope — a scope statement),
   `0012` (comparative benchmarking runs on-demand — a CI process decision), `0021` (Quarkus BOM
   imported first — a build convention), `0022` (upstream security defaults adopted, never reverted —
   a policy), `0030` and `0031` (fitness functions and readiness gates — testing conventions).
   Some of these will survive the rubric; the point is that each must face it. The list predates
   every record from `0034` on, so those have not been screened at all.

   `doc/development/build-gate-discipline.adoc` exists and is the natural destination for a relocated
   build-convention record (`0021`, `0022`); check it before creating a new page.

2. **Find what is said twice.** Read for *content* overlap, not title similarity. The corpus clusters
   heavily and the clusters are where duplication hides:
   - **Inbound validation bounds** — `0023` (the declared body cap is the effective one, breach is
     413), `0024` (inbound filter modes / `minimal`), `0026` (a bound declared at one stage is
     re-asserted at every later stage), `0029` (the GET-with-body opt-in relaxes only the
     `Content-Length` leg). **`0026` states a general principle and `0029` applies that same
     principle to one gate** — the clearest merge candidate in the corpus.
   - **Config neutrality** — `0011` (JWKS trust and egress as neutral names), `0025` (the whole
     server-TLS surface is neutral, bound by two seams), `0032` (the shipped artifact declares
     nothing test-shaped). One recurring principle, stated three times against three surfaces.
   - **Anchors and assets** — `0007`, `0013`, `0014`, `0028`.
   - **BFF** — `0018` and `0019`.
   - **TLS edge** — `0017` and `0025`.

   These clusters were drawn from the first 33 records. Look for further clusters among the later
   ones — header matching, portal templates, the session-fallback route and the BFF client
   authentication records are untested against this question.

   For each cluster: merge into one record, keep them separate with the boundary stated, or extract
   the shared principle into one record the others reference. **Record which, and why.**

3. **Verify each surviving record is still true.** An ADR that describes superseded behaviour is worse
   than none, because it is believed. Check each against the shipped code, and mark superseded ones as
   such rather than editing history — a superseded decision is legitimate content, an inaccurate one
   is not.

   **Statuses are the largest part of this.** 45 of 71 records read `Proposed` at `00293178`, most
   of them for shipped work. That makes this a sweep, not a spot-fix; re-count at outline. ADR-0059
   and ADR-0060 are among them; both decisions have shipped.

   **ADR-0008 is not this plan's to correct.** Its untrue timeout sentence (*"Timeouts are transport
   options on the shared Vert.x client … not fault-tolerance timeouts"*, line 82-83) belongs to
   kidicap `PLAN-29` D6, because that plan's wire-or-remove decision on `connect_timeout_ms` /
   `read_timeout_ms` is what decides the true sentence. The breaker half was already fixed by #433
   (ADR-0008 Amendment A2). Classify ADR-0008's status like any other record; if `PLAN-29` has
   landed by outline, check its amendment against the code, otherwise leave the sentence alone.

   **`0005`** (framework-agnostic core) reads `Superseded by ADR-0062`; `PLAN-V02-01` landed that
   supersession (#410). Classify both records like any other. `PLAN-V02-01` also amended `0018`,
   `0051`, `0057` and `0061`; check each amendment against the shipped code like the rest.

   One record is owned by another plan and must not be pre-empted:
   - **`0027`** (*the token-validation extension's unqualified beans are excluded, not accommodated*)
     is the exclusion `PLAN-V02-09` re-examines. Note it; do not settle it.

4. **Compress what survives.** The corpus mean doubled mid-project: among the first 31 records,
   `0001`–`0017` average about 136 lines and `0018`–`0031` about 273, with `0018` (677 lines), `0025`
   (515) and `0019` (403) the longest. **A decision does not take 400 lines** — the excess is usually
   implementation narrative, rationale restated several ways, or content that belongs in the
   developer docs the ADR should link to instead.

   **Compress by moving or deleting content, never by summarising away the reasoning.** The rejected
   alternatives and the "why not the other thing" are the load-bearing part of an ADR and the first
   thing a careless edit removes. Report before/after line counts per record so the reduction is
   auditable.

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
  `PLAN-V02-09`, `PLAN-V02-11`, `PLAN-V02-46` (amends ADR-0060/0066). A merge or
  deletion here moves the ground under a record being written there.
- Merges and deletions break links in released versions' documentation. That cost is real and is
  accepted; report every removed or merged record with its replacement so the break is traceable.

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
