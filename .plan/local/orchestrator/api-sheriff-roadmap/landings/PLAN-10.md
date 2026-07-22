# Landing Analysis: PLAN-10 — Anchor Types + Asset Serving (roadmap 10, new)

epic: api-sheriff-roadmap
workstream: WS-02
pr: cuioss/API-Sheriff#84 (squash-merged as 0f7f957 via merge queue)

> Landing record for one shipped plan. Written after verifying claims against ground truth on
> main @ 0f7f957 — a pasted claim is a lead, never a fact.

## Deliverable Fidelity vs Spec

Spec staged 9 deliverable groups (with a recommended (A)/(B) split); the lifecycle grouped them
into 12 — the extra three are decomposition plus one **re-scoped** deliverable (below). Not
split into two PRs; landed as one. All 12 verified present on main.

| Deliverable (spec) | Verdict | Evidence (main @ 0f7f957) |
|--------------------|---------|----------------------------|
| Doc-tree bootstrap (re-homed from PLAN-09) | shipped-as-specified | `doc/user/README.adoc` + `doc/development/README.adoc` exist; `doc/plan/README.adoc` `== Conventions` now has a **fifth bullet** ("Three-layer documentation") naming `configuration.adoc`'s dual reference/design role |
| Plan doc 10 (doc-first) | shipped-as-specified | `doc/plan/10-anchor-types-assets.adoc` |
| ADRs (type/access; asset action) | shipped-as-specified | ADR-0013 (type/access axes), ADR-0014 (asset terminal action) present |
| Two axes on anchor model + schema | shipped-as-specified | `type: proxy\|bff\|asset` + `access: public\|authenticated` in test + IT `gateway.yaml`; `RouteTableBuilder`, `ConfigValidator` |
| **D2a — validation fires on the startup path** | **shipped-as-specified, verified in depth** | `TokenValidatorProducer.onStartup(@Observes StartupEvent, validator)` calls `validator.toString()` to force proxy resolution, with a comment citing the exact lazy-proxy trap and a Javadoc warning that observing alone is insufficient. `ConfigProducer.onStartup` → `buildOnce()`. `ConfigProducerTest` on the startup path. **This was the folded PLAN-09 lesson and it landed correctly.** |
| Asset config + envelope + confinement + corpus | shipped-modified (improved) | `asset/` package: `AssetConfig`, `AssetResponseEnvelope`, `PathConfinement`, `AssetSource`. Symlink hardening was **missing until the security audit caught it** (below) |
| Asset source: directory | shipped-as-specified | `DirectoryAssetSource`; `DirectoryAssetServingIT` against a real mount |
| Asset source: upstream (SSRF-controlled) | shipped-as-specified | `UpstreamAssetSource` on the existing data plane |
| Threat-model reopen | shipped-as-specified | **GW-11** (path traversal) created + **GW-05** upstream-asset clause — the stale COVERED rating the orchestrator flagged is corrected |
| Integration tests (directory AND upstream) | shipped-as-specified | `DirectoryAssetServingIT` + upstream IT |
| configuration.adoc + architecture.adoc | shipped-as-specified | reference updates present |
| **Runtime data-plane wiring (RE-SCOPED IN)** | shipped-as-specified | `ResolvedRoute` union, `ResolvedAsset`, `RouteTableBuilder`, dispatch integration — see the re-scope note |

## The Re-Scope — outline under-scoped the terminal-action wiring

Deliverable 12 was **not** in the staged deliverable list as its own item; the spec named
"route-table assembly: terminal-action materialization" inside deliverable 6/7. Per lesson
`2026-07-21-04-002`: the phase-3 outline **folded the terminal-action materialization away into
an abstraction**, so no deliverable carried it forward. The gap stayed invisible through
planning and surfaced at execute time as a **TASK-015 infeasibility** — the asset route could
not actually serve because the wiring it depended on had never been scoped. Recovered in-plan
and shipped. Orchestrator note: the spec **did** name it (Expected Surface: "route-table
assembly"), so this was an outline-fidelity miss inside the plan, not a staging gap — but it is
the second "the outline dropped a named deliverable" event in this epic (cf. the doc bootstrap
dropped by PLAN-09). Recorded as a watch.

## Security — the audit earned its keep (two required controls were narrative-only)

Two finalize security-audit findings, both **fixed pre-push**, both cases of *prose claiming a
control that did not exist in the production path*:

1. **Symlink escape unimplemented** (lesson `2026-07-21-04-003`). The deliverable narrative
   claimed "canonicalize-and-confine closed the symlink-escape class". The audit found
   `PathConfinement` was **lexical-only** — `DirectoryAssetSource` still followed symlinks after
   it. This was an **explicit** requirement of the hand-off command ("reject … symlink escape").
   It shipped only because the audit read the actual code path rather than the claim.
2. **Upstream size cap buffered the full body first** — `UpstreamAssetSource` bought the whole
   upstream response before enforcing `max_bytes`, defeating the DoS guard the spec required
   ("abort mid-flight"). Fixed to abort mid-flight.

Also fixed by the audit: cache governance now keys on **effective auth**, not the anchor's
static `access` — closing a path where an authenticated asset could be cached.

**This is the epic's clearest evidence for D2a-style skepticism**: on a security-focused
gateway, "the deliverable says the class is closed" is not evidence the control exists. Two of
this plan's required security controls were narrative-only until independently verified.

## CI caught three defects the local gates structurally could not

Per the completion notes, confirmed against lessons:

1. **Native reflection gap** (lesson `2026-07-21-04-001`): `AssetConfig` bound green under every
   JVM gate but the native image went red — never registered for reflection. JVM-mode gates
   cannot see this by construction. Fixed + native-boot test added.
2. **ConfigProducer never fed upstream asset-route aliases into topology resolution** — masked
   locally by a stale `target/*-runner`.
3. **`verify-invalid-config-fails.sh` fixture pre-dated the mandatory axes** — masked by the
   script reusing any existing `api-sheriff:distroless` image tag.

## Sonar — real, not environmental

3 MAJOR bugs (two `byte[]`-record `equals`/`hashCode` gaps, one unguarded `Optional`) + 77.2%
new-code coverage failed the gate. Fixed via SonarCloud's public API; gate went green. **Actionable
for the operator**: configuring Sonar credentials (`credentials configure --skill
workflow-integration-sonar`) lets the `sonar-roundtrip` step do this natively next time.

## Metrics and Anomalies

- 6h13m worked / 11h45m wall / 6.22M tokens / 1671 tool uses. Far less idle than PLAN-09
  (5h31m vs 19h48m) — a much tighter run despite larger scope.
- Finalize burned 2.44M tokens / 507 tool uses — again the expensive half, and here it earned
  it (3 security fixes + 3 Sonar fixes + 3 CI re-settles).
- **Freshness reconciliations fired again** (security-audit mutated source post-build; two
  reconcile records, one correcting a mis-transcribed SHA). Same class as the PLAN-09 watch —
  CI verified the post-mutation tree, so late-verification not escape. Watch holds.
- **Follow-up flagged by the plan** (lesson `2026-07-21-07-001`): `verify` once reported success
  while **3 tests errored** — possible `testFailureIgnore` / quarkus-jacoco quirk. Potentially
  serious (a green build hiding test errors); recorded as an open defect to investigate.
- CodeRabbit nitpick (HEAD requests fetch full upstream bodies) declined as out-of-scope — a
  legitimate future optimization, noted.
- ADR outcome: plan authored its own (0013/0014); adr-propose added none. Preference-emitter
  promoted 2 patterns; wrote 2 uncommitted `enriched.json` hint files (see Working Tree).

## Working Tree / Uncommitted on main

Three uncommitted files on main: the pre-existing `.plan/marshal.json` change (operator's, from
before the epic) and two `enriched.json` architecture-hint files written by the preference
emitter. The next plan's `architecture-refresh` will commit the hints; harmless to leave.

## Queue Impact

- **PLAN-04, PLAN-05, PLAN-06, PLAN-07 now author against the final config model** — the whole
  reason PLAN-10 was repositioned early. That payoff is realized.
- **PLAN-11** now has its `static-public`-equivalent (`type: asset`, `access: public`) delivered
  and can serve the demo SPA from it.
- **The three-layer documentation convention is now live and binding** — every subsequent plan's
  standing clause is enforceable, and the "roadmap plan 10 creates the trees" reference in the
  downstream hand-offs is now satisfied.
- **PLAN-08 audit gains items**: confirm the two narrative-only security controls stayed fixed;
  confirm PLAN-09's four accepted benchmark anti-patterns never leaked to shipped defaults.
- Next in queue: **PLAN-04** (protocol processors).
