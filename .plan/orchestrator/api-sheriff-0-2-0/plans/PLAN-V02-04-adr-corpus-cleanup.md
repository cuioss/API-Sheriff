# PLAN-V02-04: ADR Corpus Content Audit and Consolidation

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-47-adr-corpus-cleanup.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.
Every number in `## Observed Facts` below is stale — that section was read at `0e7c8d3`.**

**THE CORPUS GREW BY A THIRD.** **37 records / 6,904 lines**, not 33 / 5,620. The four added since:
`0034` (publication trigger), `0035` (release cut), `0036` (three-mode forward filtering), `0037`
(gateway-owned header sets). Numbering is now contiguous `0001`–`0037`.

**DISCHARGED — the duplicate numbering is resolved.** The spec says *"Two files numbered `0026` and
two numbered `0027`. … PLAN-27 D0 may already have renamed them to `0032`/`0033`; check."* Checked:
it did. `0032` is artifact-purity, `0033` is nullable-not-Optional. **Consequence for D4:** the size
table names `0026`-artifact-purity (278) and `0027`-nullable (257) — those are now **`0032`** and
**`0033`**. Re-read the size analysis against current filenames; do not resolve by number.

**THE STATUS PROBLEM IS AN ORDER OF MAGNITUDE LARGER THAN STATED.** The spec says *"Several records
still report status `Proposed` for shipped work (`0010`, `0012` observed)"*. Actual count:
**21 of 37** — `0010 0012 0017 0018 0019 0020 0021 0022 0024 0025 0026 0027 0028 0029 0030 0031
0032 0033 0035 0036 0037`. That is 57% of the corpus, and it changes D3 from a spot-fix into a
sweep. Re-count at outline; a `Proposed` status on shipped work is exactly the believed-and-wrong
content this plan exists to remove.

**DISCHARGED — `0027`.** The spec says *"`0027`-health-probes describes an exclusion
`api-sheriff-next` PLAN-46 is re-opening"*. That plan shipped, and **ADR-0027 was renamed** — it now
reads *"The token-validation extension's unqualified **beans** are excluded, not accommodated"*
(health probes → unqualified beans). The re-opening question now belongs to **PLAN-V02-09**, in this
epic. Note the pending supersession; do not pre-empt it.

**NO LONGER CROSS-EPIC — this tightens the concurrency rule.** The spec files the `0005` reversal
under *"Cross-epic: `api-sheriff-next` PLAN-38"*. That is now **PLAN-V02-01, in this same epic**.
Combined with this spec's own *"Not concurrent with any plan authoring an ADR"*, the exclusion set is
concrete: **PLAN-V02-01, V02-06, V02-11, V02-12 and V02-13 all author an ADR.** This plan runs
against none of them.

**THE PLACEMENT QUESTION IS SETTLED BY EVENTS.** *"Before PLAN-08B, so 0.1.0 ships the consolidated
corpus"* has expired — **0.1.0 and 0.1.1 have both shipped**. The spec's own escape clause (*"if
post-cut was intended, the plan moves … unchanged"*) has resolved: it is here, unchanged, and the
released-link-breakage argument now applies to two released versions rather than none.

**Write-Boundary correction**: the section at the foot names `api-sheriff-roadmap` — the wrong epic,
and a closed one. The binding path is `.plan/local/orchestrator/api-sheriff-0-2-0/`.


## Re-Grounded (2) 2026-08-09 at `95dd566` — after four landings

`PLAN-V02-02`, `-03`, `-16` and `-17` have shipped. **This section outranks the 2026-08-08
re-grounding above it wherever they conflict.**

**EPIC-WIDE, AND NO SPEC BELOW KNOWS IT: THE BUILD NOW FAILS ON ANY COMPILER WARNING.**
`PLAN-V02-02` turned on `<showDeprecation>true</showDeprecation>` **and**
`<failOnWarning>true</failOnWarning>` reactor-wide (`pom.xml`:163, :178), so javac runs with
`-Werror` across all six modules. A deprecated API or an unchecked cast is now a **build failure**,
not a log line. Two consequences bind every plan:

1. **Answer such a failure by migrating off the warned construct.** `CLAUDE.md` states it directly:
   a `@SuppressWarnings` added to get back to green *"hollows the gate out while leaving it reporting
   success"*, and it collides with the Pre-1.0 rule forbidding deprecated code at all.
2. **The failure reaches the executor as a `warnings[]` row plus a `-Werror` `errors[]` row.** Read
   both arrays — the line number lives on the warning row.

**THE CORPUS DID NOT MOVE.** Re-measured at `95dd566`: **37 records, 6905 lines, 21 still reading
`Proposed`** — identical to the 2026-08-08 measurement. The four landings authored **no ADR**, so
every number in the re-grounding above still holds and the audit's size is unchanged.

**One new destination exists for relocated content.** `PLAN-V02-17` created
`doc/development/build-gate-discipline.adoc`. If the rubric relocates a build-convention record out
of the decision log (`0021` and `0022` are the named candidates), that page is now the natural home —
check it before creating a new one.

**The ADR-authoring exclusion list is unchanged**: this plan runs concurrently with none of V02-01,
V02-06, V02-07, V02-11, V02-12, V02-13, and not with V02-09 (whose ADR-0027 re-opening it must not
pre-empt).

## Objective

`doc/adr/` has grown to 33 records and 5,620 lines without anyone ever reading it as a whole. This
plan reads it as a whole and answers four questions per record: **is it actually an architectural
decision, is it still true, does another record already say it, and does it need to be this long?**

The output is a smaller corpus where every remaining record is a real decision, stated once, at a
length that matches its weight.

**This is a content audit, not a hygiene pass.** Numbering, filename form and metadata blocks are
explicitly *not* the subject — the operator ruled on that (2026-08-03). Fix such things only where a
merge or deletion forces it.

## Deliverables

1. **Classify every record: is it an ADR?** An ADR records a decision between real alternatives, with
   consequences that outlive the code that implements it. Several records look like something else —
   a scope statement, a build convention, a process policy, a testing convention — and those belong
   in developer documentation, not in the decision log. **Write the rubric down first, then apply it
   to all 33 and give a verdict per record: keep, relocate (naming the destination), or delete.**

   Candidates worth examining first, from a read of the titles — **leads, not verdicts, and the list
   is not exhaustive**: `0002` (initial protocol and configuration scope — a scope statement),
   `0012` (comparative benchmarking runs on-demand — a CI process decision), `0021` (Quarkus BOM
   imported first — a build convention), `0022` (upstream security defaults adopted, never reverted —
   a policy), `0030` and `0031` (fitness functions and readiness gates — testing conventions).
   Some of these will survive the rubric; the point is that each must face it.

2. **Find what is said twice.** Read for *content* overlap, not title similarity. The corpus clusters
   heavily and the clusters are where duplication hides:
   - **Inbound validation bounds** — `0023` (the declared body cap is the effective one, breach is
     413), `0024` (inbound filter modes / `minimal`), `0026`-security-bound (a bound declared at one
     stage is re-asserted at every later stage), `0029` (the GET-with-body opt-in relaxes only the
     `Content-Length` leg). **`0026` states a general principle and `0029` applies that same
     principle to one gate** — the clearest merge candidate in the corpus.
   - **Config neutrality** — `0011` (JWKS trust and egress as neutral names), `0025` (the whole
     server-TLS surface is neutral, bound by two seams), `0026`-artifact-purity (the shipped artifact
     declares nothing test-shaped). One recurring principle, stated three times against three
     surfaces.
   - **Anchors and assets** — `0007`, `0013`, `0014`, `0028`.
   - **BFF** — `0018` and `0019`, 775 lines between them.
   - **TLS edge** — `0017` and `0025`.

   For each cluster: merge into one record, keep them separate with the boundary stated, or extract
   the shared principle into one record the others reference. **Record which, and why.**

3. **Verify each surviving record is still true.** An ADR that describes superseded behaviour is worse
   than none, because it is believed. Check each against the shipped code, and mark superseded ones as
   such rather than editing history — a superseded decision is legitimate content, an inaccurate one
   is not. Two known cases: **`0005`** (framework-agnostic core) is being reversed by `api-sheriff-next`
   PLAN-38 — **do not pre-empt that**, note the pending supersession; and **`0027`**-health-probes
   describes an exclusion `api-sheriff-next` PLAN-46 is re-opening. Statuses that no longer match
   reality (several still read `Proposed` for shipped work) are corrected here.

4. **Compress what survives.** The corpus mean doubled mid-project: `0001`–`0017` average 112 lines,
   `0018`–`0031` average 232, with `0025` at 412, `0019` at 403 and `0018` at 372. **A decision does
   not take 400 lines** — the excess is usually implementation narrative, rationale restated several
   ways, or content that belongs in the developer docs the ADR should link to instead.

   **Compress by moving or deleting content, never by summarising away the reasoning.** The rejected
   alternatives and the "why not the other thing" are the load-bearing part of an ADR and the first
   thing a careless edit removes. Report before/after line counts per record so the reduction is
   auditable.

## Observed Facts

Read first-party at `0e7c8d3`:

- 33 records, 5,620 lines total.
- Size split: `0001`–`0017` mean 112 lines; `0018`–`0031` mean 232. Largest: `0025` (412), `0019`
  (403), `0018` (372), `0024` (292), `0026`-artifact-purity (278), `0027`-nullable (257).
- Two files numbered `0026` and two numbered `0027`. **Not this plan's subject** — PLAN-27 D0 may
  already have renamed them to `0032`/`0033`; check, and let any merge or deletion here settle the
  rest as a side effect.
- Several records still report status `Proposed` for shipped work (`0010`, `0012` observed).

## Expected Surface

- `doc/adr/**` — all 33 records
- Destinations for anything relocated out of the decision log — `doc/development/**`, `doc/user/**`
- Every inbound `ADR-00NN` reference and `link:…adr/…adoc` across `doc/`, `api-sheriff/src/**`,
  `integration-tests/**`, `benchmarks/**`, `pom.xml`, `*.properties`, `*.json`, `*.yaml` — merges and
  deletions break links, and a dangling xref is a broken AsciiDoc build
- No production behaviour change. If the audit finds an ADR that contradicts shipped behaviour, that
  is a finding to report — the ADR is corrected, the code is not.

## Dependencies and Sequencing

- **After PLAN-08A**, whose audit reads the shipped surface — let it read a stable corpus.
- **Before PLAN-08B**, so 0.1.0 ships the consolidated corpus and no released link breaks later. This
  is the placement "at the end of this orchestration" implies; if post-cut was intended, the plan
  moves to `api-sheriff-next` unchanged.
- **Overlaps PLAN-08B D1** (pre-release doc purge), which deliberately excludes `doc/adr/` — no
  collision, but 08B must see the final layout.
- **Cross-epic**: `api-sheriff-next` PLAN-38 (reverses `0005`) and PLAN-46 (re-opens the `0027`
  health-probe exclusion). Both are post-cut; this plan notes the pending supersessions and does not
  pre-empt either.
- Not concurrent with any plan authoring an ADR.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-04-adr-corpus-cleanup.md" plan_id=plan-v02-04-adr-corpus-cleanup
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The executing plan MUST NOT create or edit any file under
`.plan/local/orchestrator/api-sheriff-0-2-0/`. Its two channels back to the epic are its PR and its
`inbox/` OUTBOX.
