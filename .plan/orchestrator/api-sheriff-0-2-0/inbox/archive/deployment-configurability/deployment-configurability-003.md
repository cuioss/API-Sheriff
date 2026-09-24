envelope_version=1
sender_type=orchestrator
sender_id=deployment-configurability
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-09-23T15:48:23Z

# Candidate lesson (handed off from `deployment-configurability`'s local corpus): AGENTS.md and CLAUDE.md module lists mirror the root pom.xml modules with no contract test binding them

Original lesson `2026-09-15-17-001` (component `api-sheriff-parent`, category `improvement`, created
2026-09-15).

## What happened

`AGENTS.md` and `CLAUDE.md` each restate the reactor module set the root `pom.xml` declares in
`<modules>`. Nothing ties either list to that source. A module added to or removed from the reactor
leaves both documents silently stale, and they still read as authoritative. This has already happened
once (before `release-docs-and-tls-scenario-guide`, `CLAUDE.md` listed three modules and described
`benchmarks/` as WRK when the reactor has six modules and benchmarks use k6). CodeRabbit raised the gap
on PR #305 (finding `6da2fb`); the operator chose Hold: record it here for a later plan.

## Candidate rule / solution

`DocumentedSetsContractTest`'s own javadoc states the project's review policy treats a hardcoded list
mirroring a set defined elsewhere as a defect unless derived from that source. Add a contract test
(next to or sibling of `DocumentedSetsContractTest`) that reads `<modules>` from the root `pom.xml`
with the JDK XML parser, extracts the module bullets under `AGENTS.md`'s and `CLAUDE.md`'s module-list
sections, and asserts set equality (plus a pre-dedup count so a duplicate bullet cannot pass).

## Status: still open (operator Hold as of 2026-09-15)

Not fixed by PLAN-28 or PLAN-29. Genuinely open, unowned work.
