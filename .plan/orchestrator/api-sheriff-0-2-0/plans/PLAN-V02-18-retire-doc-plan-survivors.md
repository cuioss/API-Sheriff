# PLAN-V02-18: retire the three surviving doc/plan/ files

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Source: operator instruction, 2026-09-22, staged after an orchestrator coverage review of
> `doc/plan/`. The orchestrator EMITS the command below; it never launches the plan inline.

> **This spec explicitly reverses a standing ruling.** `PLAN-V02-03`'s landing record
> (`landings/PLAN-V02-03.md`, 2026-08-09) found `doc/plan/09-release-readiness.adoc` "not
> superseded, NOT expected... carried to the epic as an open item that becomes actionable at
> the 1.0 cut" — because its defining deliverable (the 1.0.0 cut and the flip of the pre-1.0
> rules) had not happened, and found `01-base-implementation.adoc` "not superseded, expected"
> because it is the only published account of that merged work (landing records live under
> `.plan/`, which is not the published documentation tree). **Neither condition has changed**:
> API Sheriff is still pre-1.0 (0.1.1/0.2.2 alpha per `doc/user/README.adoc`), `CLAUDE.md` still
> carries the Pre-1.0 Rules, and no landing record covers Plan 01's subject. **The operator
> reviewed both reasons and instructed retirement anyway, ahead of the 1.0 cut** — this plan
> executes that instruction; it does not re-litigate it.

## Re-Grounded (4) 2026-09-24 at `05f6ee3` — after 18 commits (#343–#354, release 0.2.3)

All claims hold. `doc/plan/` still holds exactly 3 files. `doc/quality-report/documentation.adoc` is still the sole outside reference. The Pre-1.0 Rules section (`CLAUDE.md:145`) carries no flip condition yet (D2 is open). Sequence against `PLAN-V02-19`, whose module-list test reads `CLAUDE.md`.

## Objective

Delete `doc/plan/01-base-implementation.adoc`, `doc/plan/09-release-readiness.adoc` and
`doc/plan/README.adoc` from the repository, repair the one outside reference to them, and — this
is the part the reversal makes load-bearing — durably preserve the one fact `09-release-readiness.adoc`
was the sole repository record of: that the 1.0.0 version cut and the flip of `CLAUDE.md`'s
Pre-1.0 Rules are still an open, undone milestone. The content itself is not lost: the orchestrator
has already archived verbatim copies at
`.plan/orchestrator/api-sheriff-0-2-0/archive/doc-plan-{01-base-implementation,09-release-readiness,README}.adoc`,
and full git history survives the deletion regardless. This plan's job is the repository-source
side only — deletion and reference repair — plus the one new-tracking obligation below.

## Deliverables

1. **Delete the three files.** `git rm doc/plan/01-base-implementation.adoc
   doc/plan/09-release-readiness.adoc doc/plan/README.adoc` and remove the now-empty
   `doc/plan/` directory. Do not resurrect it — this matches the precedent `PLAN-V02-03` already
   set for the other nine files in the same directory.

2. **Preserve the 1.0-cut milestone somewhere a reader will actually find it.** This is the one
   genuinely new obligation the reversal creates, not a copy-paste of deleted prose. Before or in
   the same commit as the deletion, confirm the following is durably tracked in the repository (not
   only in this epic's `.plan/` ledger, which most readers never open): that API Sheriff has not
   yet cut 1.0.0, and that `CLAUDE.md`'s Pre-1.0 Rules section (delete-not-deprecate, break freely)
   is expected to flip at that cut. A short standing note in `CLAUDE.md`'s own Pre-1.0 Rules
   section is the most likely fit (it already states the rules; it does not yet state when they
   end) — but the exact placement is an outline decision, not dictated here. **Do not simply
   restate `09-release-readiness.adoc`'s six-item work breakdown** — a same-day orchestrator
   coverage review (see `.plan/orchestrator/api-sheriff-0-2-0/archive/doc-plan-09-release-readiness.adoc`'s
   provenance header) found five of its six items already delivered or superseded elsewhere; only
   the milestone fact itself (not-yet-cut, rules-will-flip) is what needs a durable home.

3. **Repair the one outside reference.** `doc/quality-report/documentation.adoc` cites
   `doc/plan/09-release-readiness.adoc:8-9` and `doc/plan/01-base-implementation.adoc:41` by path
   and line, and mentions `doc/plan/` generally in its scope description (line 6) and its
   `doc/variants/` cross-reference (line 228). That report is itself a dated audit snapshot (it
   still says "37 ADRs"; the corpus is 49 at outline time — re-check the live count rather than
   trusting either number) — it is not continuously maintained, so the fix is a note that the
   cited paths were retired and archived under `.plan/orchestrator/api-sheriff-0-2-0/archive/`,
   not a rewrite of the report's own historical findings. `doc/README.adoc` was checked and does
   **not** index `doc/plan/` at all — nothing to repair there. Grep for `doc/plan` and
   `plan/README` across `*.adoc`/`*.md`/`*.yml`/`*.yaml`/`*.js` at outline to confirm nothing else
   appeared between staging and launch.

4. **Tests and documentation**: none of this plan's own subject is code, so no test obligation.
   Confirm the standard doc-only commit path applies per `CLAUDE.md`'s Pre-Commit Process
   ("Documentation-only commits skip both" gates) — this plan's footprint should be exactly
   `doc/**` plus `.plan/orchestrator/api-sheriff-0-2-0/**`'s already-landed archive files (which
   this plan does not need to touch further), and neither `*.java`, `pom.xml`, nor any other
   build-triggering path. If outline finds the footprint has grown beyond that, say so rather than
   assuming the skip still applies.

## Claim Labels

- OBSERVED: `doc/plan/` holds exactly three files — `01-base-implementation.adoc`,
  `09-release-readiness.adoc`, `README.adoc` — confirmed via `git ls-files doc/plan/`, 2026-09-22.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: doc/plan/ holds exactly 01-base-implementation, 09-release-readiness, README
- OBSERVED: the only repository-source references to these files outside `.plan/` are in
  `doc/quality-report/documentation.adoc` (path+line citations at lines 116 and 140, plus general
  mentions at lines 6 and 228); `doc/README.adoc` carries none. Confirmed via
  `grep -rln "fapi_next_steps\|doc/plan\|plan/README" --include="*.adoc" --include="*.md"
  --include="*.yml" --include="*.yaml" --include="*.js" .` excluding `.plan/` and `.git/`,
  2026-09-22.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: doc/quality-report/documentation.adoc sole outside reference (:5-6,:116,:140,:226-232)
- OBSERVED: no site-navigation or build-config file (`*.yml`/`*.yaml`/`*.json`) references
  `doc/plan/`. Confirmed via the same grep restricted to those extensions, 2026-09-22.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: no yml/yaml/js site-nav or build config references doc/plan/
- HYPOTHESIS: `CLAUDE.md`'s Pre-1.0 Rules section is the best-fit home for the 1.0-cut milestone
  note (Deliverable 2). Confirm/refute at outline — an ADR, a GitHub issue, or `doc/README.adoc`'s
  own index are also plausible and were not ruled out, only not chosen here (verify-at-outline).
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: CLAUDE.md-as-home is a placement choice; Pre-1.0 Rules :145 carries no flip condition
- Verify-first clause: re-run the outside-reference grep at outline, not only at staging — a file
  added between now and launch that references `doc/plan/` would not appear in the OBSERVED grep
  above.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: outside-reference grep re-run at 05f6ee3, same result set

## Expected Surface

- `doc/plan/01-base-implementation.adoc`, `doc/plan/09-release-readiness.adoc`,
  `doc/plan/README.adoc` — deleted, D1
- `doc/quality-report/documentation.adoc` — D3
- `CLAUDE.md` — D2 (HYPOTHESIS placement, verify-at-outline)

## Dependencies and Sequencing

- Depends on: none.
- Overlaps with: none of the other WS-02/WS-03 specs touch `doc/plan/`, `doc/quality-report/` or
  `CLAUDE.md`'s Pre-1.0 Rules section.
- Adjacent to: `PLAN-V02-04` (adr-corpus-cleanup) also does documentation-corpus housekeeping in
  this epic, but touches `doc/adr/**`, not `doc/plan/` — disjoint, no sequencing needed.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-18-retire-doc-plan-survivors.md"
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message — the
orchestrator owns every other ledger write, and the archive copies this spec references have
already been written by the orchestrator, not by this plan.
