envelope_version=1
sender_type=plan
sender_id=plan-v02-03-documentation-restructure
epic=api-sheriff-0-2-0
kind=finding
created=2026-08-08T22:03:21Z

# D3 survivor report — `doc/plan/` supersession verification

PLAN-V02-03 deliverable 3 verified all 12 `doc/plan/*.adoc` files by **content** against the
41 landing records under `.plan/local/archived-orchestrators/api-sheriff-roadmap/landings/`.
Nine were confirmed superseded and deleted. **Three survive**, and two of those are reported
here because they were expected to be deleted.

## Survivor 1 — `doc/plan/01-base-implementation.adoc` (expected)

**Verdict: not superseded.** No landing in the 41-record corpus covers the
TokenSheriff-scaffolding-to-verified-foundation work. Confirmed by enumerating every record,
not by sampling. Kept in place per ruling A4, exactly as the outline predicted.

## Survivor 2 — `doc/plan/09-release-readiness.adoc` (NOT expected — this is the finding)

**Verdict: not superseded.** The outline listed this file under *Files expected to mutate*
(delete), but verification refuses the deletion, and the expectation is a prior rather than a
licence.

Its defining deliverable — **the 1.0.0 cut and the flip of the pre-1.0 rules** — has not
happened. The project is at 0.1.1 alpha and `CLAUDE.md` still carries *Pre-1.0 Rules (HIGHEST
PRIORITY)*. Only components of the plan are covered by landings:

- full-surface security audit → PLAN-08A, but that was the sweep **before the 0.1.0 cut**
- benchmark consolidation → PLAN-25 *Benchmark Suite Completion*
- native + container hardening → PLAN-26 *Release Artifacts and OCI Publication*

Uncovered by any landing: the **ADR-0005 module-extraction checkpoint** and the **1.0.0
release** itself. PLAN-48/50/51 and RELEASE-0.1.1 are all 0.1.x release *mechanics*, not the
1.0 cut.

**Consequence for the epic:** `doc/plan/` does not reduce to conventions-only, and a future
plan that assumes `doc/plan/` can be retired wholesale should re-read this verdict first. The
file is the only remaining written record of the 1.0 release gate.

## Flagged, deliberately not hand-edited

The architecture inventory's project description still reads *"…follow the plans under
`doc/plan/`"*. That description is `marshall-steward`-regenerated, so per the deliverable it is
**flagged for regeneration rather than hand-edited**. A `marshall-steward` run is owed to
refresh it; it is stale as of this plan, not wrong-by-authoring.

## Also recorded

`doc/archive/others/excluded.adoc` (deliverable 1) was deleted with its content traceable to
**no** live design document — `features-analysis.adoc` distils only the six *evaluated*
gateways, never the considered-and-excluded list (Apiman, WSO2 and friends). It carried zero
inbound references and git history preserves it, so deletion stood, but the adaptation claim in
`doc/README.adoc` had over-reached for that one file.
