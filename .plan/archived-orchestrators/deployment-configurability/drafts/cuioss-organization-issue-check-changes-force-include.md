# DRAFT — not filed. Target: `cuioss/cuioss-organization` issues.

> Drafted 2026-09-11 by the `deployment-configurability` orchestrator from lesson `2026-08-29-16-001`
> (archived in `lessons-archive/`), on operator request ("draft, not file"). Facts verified read-only
> against `reusable-maven-build.yml` at `014db4eafdf4e9d32c74a1dd0a3f945c72437b00` (v0.25.1, the pin
> API-Sheriff uses) and spot-checked at `v0.26.0` (line unchanged). Review before filing.

---

**Title:** reusable-maven-build: `check-changes` can ignore paths but never force-include one, so a guard test over a `.plan/**` file can never run in CI

## Problem

`reusable-maven-build.yml` → job `check-changes` builds an all-negation `dorny/paths-filter` spec
(lines 184-215 at v0.25.1) that hard-codes `'!.plan/**'`, `'!.claude/**'`, `'!doc/**'`, `'!**/*.md'`,
`'!**/*.adoc'`, … with `predicate-quantifier: 'every'`. Projects can **add** ignores
(`paths-ignore-extra`) or **disable** the skip entirely (`skip-on-docs-only: false`), but there is no
way to say "this one path under an ignored tree IS build input".

That matters when a repository keeps build-relevant configuration under an ignored tree. In
`cuioss/API-Sheriff`, `.plan/marshal.json` carries a `build.map` that decides which footprints the local
pre-push gate runs for. `BuildGateCoverageContractTest` guards that map. Because `.plan/**` is ignored, a
PR whose footprint is only `.plan/marshal.json` skips `build`, `sonar-build` and `deploy-snapshot` — the
guard never executes on exactly the change it exists to catch.

This already happened: commit `cea163c` regenerated `build.map`, erased four guarded globs, CI reported
green (all build jobs skipped), and the erasure reached `main`. It was caught later by hand.

## Why the existing knobs don't fit

| Knob | Effect | Cost |
|---|---|---|
| `paths-ignore-extra` | adds more `!pattern` entries | can only widen the skip, never narrow it |
| `skip-on-docs-only: false` | disables `check-changes` entirely | every docs-only PR runs the full Maven + Sonar build |
| edit the reusable workflow | — | not available to consuming repos |

## Proposal

Add a project config key (e.g. `maven-build.paths-build-extra`), space-separated globs that count as
code **even under an ignored tree**. Two implementation options for the filter step:

1. A second filter `force:` with the positive globs, and `should-build = code || force`.
2. Emit the force-includes as positive patterns in a separate `dorny/paths-filter` step (the
   `every`-quantified negation spec cannot express an exception on its own).

Default empty, so no behaviour change for existing consumers. API-Sheriff would set
`paths-build-extra: '.plan/marshal.json'`.

## Related

- API-Sheriff `CLAUDE.md` § Pre-Commit Process documents the gap and a manual mitigation ("treat any
  commit touching `build.map` as gate-requiring by hand").
