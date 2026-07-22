# Epic: Lessons handling 26-07-22

slug: lessons-handling-26-07-22-01

> Ledger document for one lessons-handling epic under
> `.plan/local/orchestrator/lessons-handling-26-07-22-01/`. The layout and authority
> contract live in the central standard — see
> `persona-marshall-orchestrator/standards/orchestration-model.md`. `status.json` is
> the machine authority; any statement here that conflicts with it is stale prose.

## Vision

Operator-directed corpus cleanup (2026-07-22): "Go through each lessons learned and
remove what is no more applicable." Local-only pass over the 20 active lessons —
per-lesson disposition against ground truth from the api-sheriff-roadmap epic
(shipped plans PLAN-01..04/09/10/12, verified landings), removal of lessons whose
premise no longer holds. No remediation queue items staged — this run is cleanup,
not work generation.

## START HERE

<!-- GENERATED BLOCK — never hand-write or hand-edit this section.
     Regenerate after every queue-touching state change via:
     python3 .plan/execute-script.py plan-marshall:marshall-orchestrator:orchestrator resume-summary --slug lessons-handling-26-07-22-01
     Paste the returned block verbatim between the markers. -->

<!-- BEGIN GENERATED: resume-summary -->
**Resume anchor**: Corpus finalized 2026-07-22: 17 lessons ORCHESTRATOR-OWNED at .plan/local/orchestrator/lessons-handling-26-07-22-01/lessons/ (operator-designated lessons home, inside this epic tree; tombstones + plugin-path symlink removed; self-contained for transport). Standing rules: (1) the plugin recreates .plan/local/lessons-learned/ at the next plan's phase-6 lessons-capture — SWEEP it into this dir each lessons run; (2) future dated lessons-handling epics use THIS dir as the corpus home. Epic ready to close.
**Phase**: orchestrating
**Queue** (staged, in order):
- (empty)
<!-- END GENERATED: resume-summary -->

## Ordered Queue

Empty by design — the operator asked for corpus cleanup only; every scanned lesson
received a disposition (below) and none warranted a staged remediation plan (the
still-applicable lessons are standing rules, already tracked upstream-filing
candidates in the api-sheriff-roadmap epic, or pending operator actions tracked
there).

## Decisions

- 2026-07-22 — Full local scan: 20 active lessons read in full and disposed against
  epic ground truth. **3 REMOVED** (tombstoned via manage-lessons remove --force;
  the interactive prompt auto-declines in a non-interactive shell, and the removals
  are operator-directed): see dispositions. **17 KEPT** — each still-applicable.
  No clusters staged (cleanup-only run).

### Per-lesson dispositions (every scanned lesson)

| Lesson | Disposition | Rationale (ground truth) |
|---|---|---|
| 2026-07-18-11-001 wrk gate false-green | **stale → REMOVED** | Entire wrk surface deleted by PLAN-09 k6 migration (verified zero wrk/lua files); generalizable rules carried into k6 harness design |
| 2026-07-18-22-001 YAML anchor/alias inert via Jackson | **already-covered → REMOVED** | Compose-phase pre-pass shipped (PLAN-02) with tests; rationale durably in ADR-0010 (on main) + threat model |
| 2026-07-18-22-002 S3655 hoist-Optional idiom | keep (standalone) | Generic idiom, still applies to future Optional code; zero-findings policy makes recurrence a must-fix; PLAN-12 used exactly this fix |
| 2026-07-18-22-003 negative-IT mktemp 0700 + marker assert | keep (standalone) | Standing IT-authoring rule; native IT suite alive and growing (PLAN-04 added suites) |
| 2026-07-18-22-004 auth-mandatoriness most-specific level | keep (standalone) | ADR-0007 defines precedence, but the coarse-vs-fine TESTING discipline binds future override-capable attributes (CORS/rate limits, PLAN-05/06/07) |
| 2026-07-19-17-001 warm daemon masks cold classpath | keep (standalone) | marshalld daemon still enrolled and serving builds; recurrence observed twice already |
| 2026-07-19-17-002 deletion sweep incl. integration-tests | keep (standalone) | Standing multi-module rule; pre-1.0 aggressive-deletion policy keeps it live |
| 2026-07-19-17-003 Content-Length end-to-end; h2-vs-h1 masking | keep (standalone) | Specific bug fixed (PLAN-03), but hop-by-hop-allowlist + test-both-HTTP-versions rules bind upcoming PLAN-05 tls-edge |
| 2026-07-19-17-004 unit-green ≠ integrated (wiring asserts) | keep (standalone) | Standing; failure class recurred (PLAN-09 lazy-proxy validator); binds every remaining plan |
| 2026-07-19-17-005 Sonar coverage = unit-JaCoCo only | keep (standalone) | Coverage gate ≥80% live; constraint unchanged |
| 2026-07-20-18-001 OpenRewrite mutation → clean verify | keep (standalone) | Finalize steps still run recipes; same class recurred in PLAN-10 (epic watch open) |
| 2026-07-20-18-002 CDI lazy proxy vs StartupEvent | keep (standalone) | Fixed for the PLAN-10 validator (startup-path test), but standing CDI pattern for future eager-init beans (BFF startup wiring ahead) |
| 2026-07-20-21-001 marshal.json documentation.implementation empty | keep (already-tracked) | Premise still true — operator's architecture-enrichment action pending (roadmap-epic open item); lesson is the durable tracker |
| 2026-07-21-04-001 native reflection gap for config classes | keep (standalone) | Standing GraalVM rule; PLAN-05/06 add config classes |
| 2026-07-21-04-002 outline folded away named deliverable | keep (upstream-candidate) | Upstream phase-3 behavior unfixed; emit-discipline watch in roadmap epic depends on it; PLAN-04 proved the discipline works |
| 2026-07-21-04-003 security control claimed but unimplemented | keep (standalone) | Standing verify-the-control rule; PLAN-08 re-verification of the two PLAN-10 controls explicitly pending |
| 2026-07-21-07-001 verify green while 3 tests errored | **stale/resolved → REMOVED** | PLAN-04 D8 closed the investigation: build-wrapper layer, NOT pom (surefire/jacoco hypothesis disproven); residual upstream filing tracked in roadmap epic |
| 2026-07-22-00-001 run native IT locally before push | keep (standalone) | Standing practice for every remaining plan |
| 2026-07-22-00-002 baseline-reconcile git-version fail-closed | keep (upstream-candidate) | Upstream plan-marshall bug unfixed/unfiled; lesson is the durable record backing the pending filing |
| 2026-07-22-00-003 stale worktree scripts post-upgrade | keep (upstream-candidate) | Upstream unfixed; interim routine (preflight + sync-defaults) still needed after every plugin upgrade |

## Open Defects

None.

## Watches

- **Corpus ownership finalized (2026-07-22)**: lessons are ORCHESTRATOR-OWNED at
  `.plan/local/orchestrator/lessons-handling-26-07-22-01/lessons/` (17 files; tombstones and the plugin-path
  symlink removed — fully self-contained for transport, no restore step needed).
  The plugin's `manage-lessons` path now resolves to nothing by design; the NEXT
  plan's phase-6 lessons-capture will recreate `.plan/local/lessons-learned/` and
  write new lessons there. STANDING RULE for every future lessons-handling run:
  sweep any lessons found at the recreated plugin path into `lessons-handling-26-07-22-01/lessons/`
  (operator 2026-07-22: corpus placed INSIDE THIS lessons epic tree — the designated
  lessons home; future dated lessons-handling epics read/write it here and sweep the
  recreated plugin path into it). The orchestrator reads it directly and stages fix-plans from lessons only
  when the operator instructs. — Re-check: at every future lessons-handling run
- The three keep-(upstream-candidate) lessons (-04-002, -00-002, -00-003) discharge
  when the corresponding upstream plan-marshall filings land — the filings themselves
  are tracked as operator actions in the api-sheriff-roadmap epic, alongside the
  credentials-key-prefix report and the build-wrapper green-while-errored finding.
  A future lessons-handling run may remove them once upstream fixes ship.
