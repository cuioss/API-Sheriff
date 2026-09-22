envelope_version=1
sender_type=plan
sender_id=plan-v02-03-documentation-restructure
epic=api-sheriff-0-2-0
kind=landing
created=2026-08-09T01:59:45Z

# Landing — PLAN-V02-03 Documentation Restructure

**Status:** MERGED. PR #197, merge commit `89a3cfe`. Worktree removed; plan dir back on `main`.

## What shipped — all three deliverables

**D1 — `doc/archive/` retired.** Deleted the directory entirely (10 files) and repaired 7 inbound
references across 5 files.

**D2 — operator layer split out of `doc/configuration.adoc`.** Sections 6-8 moved into the operator
layer: two new `doc/user/` pages, plus section 8 folded into `environment-variable-overrides.adoc`.
Sections 1-5, section 9 (Field Reference) and section 10 were **retained by operator ruling**, not
by omission. The split closed the live Open Defect (5): the env-var page was missing
`SHERIFF_CLIENT_SECRET` and `SHERIFF_SESSION_KEY`.

**D3 — `doc/plan/` supersession verified per file** against the 41-record landings corpus; the 9
confirmed-superseded files were deleted.

## Residue the epic should carry

Full detail for items 1-3 is in message `plan-v02-03-documentation-restructure-001` (kind:
`finding`) — summarised here only so the landing is self-contained.

1. **Two `doc/plan/` survivors, kept deliberately.** `01-base-implementation.adoc` has no
   counterpart in the landings corpus. `09-release-readiness.adoc` is **not** superseded — its
   defining deliverable (the 1.0 cut and the pre-1.0 rule flip) has not happened; the project is
   0.1.1 alpha and `CLAUDE.md` still carries the pre-1.0 rules. **This becomes actionable at the
   1.0 cut and the epic should carry it.** `doc/plan/README.adoc` was reduced to its project-wide
   conventions rather than deleted.

2. **Traceability gap on `doc/archive/others/excluded.adoc`.** Its content (Apiman, WSO2 and other
   considered-but-not-evaluated gateways) traces to no live design document —
   `doc/features-analysis.adoc` distils only the six *evaluated* gateways. Zero inbound refs and git
   history preserves it, so the deletion stood, but `doc/README.adoc`'s "fully adapted" claim
   over-reached for that one file.

3. **A `/marshall-steward` run is owed.** The architecture inventory's project description still
   reads "…follow the plans under `doc/plan/`" — stale now that D3 landed. It is
   steward-regenerated, not hand-editable, so it was flagged rather than patched.

4. **Operator ruling q3 overrode the spec's "no code change of any kind".** The k6 script's
   `@fileoverview` comment was repointed, which made the plan **gate-bearing** — a full Maven gate
   was run where the spec had assumed a documentation-only exemption.

5. **Open question answered: `host: api.example.com` is CORRECT at its layer.** `match.host` is an
   **ingress** matcher; ADR-0004 governs the **egress** leg, so the two do not conflict. The real
   defect was three unqualified "no concrete host" claims in `configuration.adoc`, all narrowed.

## Mechanism gaps reported separately

Four plan-marshall mechanism gaps (not project defects) ride as `kind: candidate-lesson` messages
from this same sender: the self-review domain surfacer returning zero over an AsciiDoc footprint,
`asciidoc verify-links` not parsing `link:` macros, the build-maven learned-timeout kill and its
port-8081 cross-run contamination, and build attribution landing under `NO_PLAN`.
