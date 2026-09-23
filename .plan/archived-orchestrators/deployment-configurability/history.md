# History: deployment-configurability

epic: deployment-configurability
closed: 2026-09-23
created: 2026-08-27

> Frozen record, written at close. `epic.md` and the rest of the tree remain on disk untouched —
> close freezes, never deletes; the tree is the permanent audit record. This document summarizes;
> it does not replace `epic.md`, `settled.md`, the `landings/`, `plans/`, `workstreams/`, or
> `logs/` — all of it stays reachable at `.plan/orchestrator/deployment-configurability/`.

## Vision as pursued

API Sheriff ships a real deployable artifact — a distroless native OCI image published to GHCR —
and this epic set out to close four production-operator gaps that were either absent or
unconfigurable: no in-band `HEALTHCHECK` on the main image; no way to mount the gateway under a
context path prefix; unconditional, unconfigurable TLS hostname verification on every outbound
connection; and no test that ever forced the BFF's near-expiry token refresh to actually run,
despite a reported production exception at exactly that point.

The epic grew well beyond those four founding concerns as work surfaced further gaps: forwarded-trust
allow-list configurability, the TLS material contract and termination modes (plain-HTTP opt-out,
passthrough SNI), test-suite reliability (macOS loopback hangs, wildcard-bind collisions, Docker-image
selection), a dedicated configuration-security-hardening pass (egress TLS pinning, cookie-name
validation, trusted-proxy breadth), and finally a closeout workstream (WS-09) that swept every
residual Open Defect the epic had accumulated but never finished.

Every switch this epic shipped has a test or a documented manual procedure proving it, with
secure-by-default preserved throughout — that was the closing bar the Vision set, and it held.

## Workstreams

| WS | Title | Outcome |
|----|-------|---------|
| WS-01 | Container Health-Check for Docker-Only Deployment | Shipped: PLAN-01 (`HEALTHCHECK`, #230), PLAN-10 (ADR-0038 preboot probe, #257). PLAN-14 superseded (folded in). |
| WS-02 | Configurable Context Path | Shipped: PLAN-02 (#248). |
| WS-03 | Configurable Hostname Verification (Outbound Connections) | Shipped: PLAN-03 (#268), PLAN-04 (#272). PLAN-20 superseded (absorbed into PLAN-25). |
| WS-04 | BFF Refresh-Token Reliability | Shipped: PLAN-05 (#282), PLAN-16 (#284), PLAN-17 (#288), PLAN-26 (#314). PLAN-21, PLAN-27 superseded (absorbed into PLAN-25/PLAN-26). |
| WS-05 | Forwarded-Trust Allow-List Configurability and Coverage | Shipped: PLAN-06 (#254), PLAN-15 (#267). PLAN-22 superseded (absorbed into PLAN-25). |
| WS-06 | TLS Material Contract and Termination Modes | Shipped: PLAN-07 (#283), PLAN-08 (#286), PLAN-18 (#308), PLAN-24 (#305). PLAN-09, PLAN-19 superseded (absorbed into PLAN-24). |
| WS-07 | Test-Suite Reliability and Local Environment | Shipped: PLAN-11 (#243), PLAN-13 (#255), PLAN-23 (#336). PLAN-12 landed (no PR — see `landings/PLAN-12.md`). |
| WS-08 | Configuration Security Hardening | Shipped: PLAN-25 (#306) — merged the absorbed PLAN-20/21/22 scope. |
| WS-09 | Closeout Residual Hardening | Shipped: PLAN-28 (#341), PLAN-29 (#348) — the epic's last two plans, sweeping every residual Open Defect. |

## Queue outcome — all 29 plans

| Plan | Workstream | Outcome | PR / Landing |
|------|------------|---------|--------------|
| PLAN-01 distroless-health-check | WS-01 | shipped | #230, `landings/PLAN-01.md` |
| PLAN-02 configurable-context-path | WS-02 | shipped | #248, `landings/PLAN-02.md` |
| PLAN-03 upstream-hostname-verification | WS-03 | shipped | #268, `landings/PLAN-03.md` |
| PLAN-04 jwks-hostname-verification | WS-03 | shipped | #272, `landings/PLAN-04.md` |
| PLAN-05 bff-refresh-integration-coverage | WS-04 | shipped | #282, `landings/PLAN-05.md` |
| PLAN-06 forwarded-trust-env-configurability | WS-05 | shipped | #254, `landings/PLAN-06.md` |
| PLAN-07 tls-material-audit-and-trust-contract | WS-06 | shipped | #283, `landings/PLAN-07.md` |
| PLAN-08 plain-http-termination-mode | WS-06 | shipped | #286, `landings/PLAN-08.md` |
| PLAN-09 tls-scenario-guide | WS-06 | superseded | absorbed into PLAN-24 D1-6 |
| PLAN-10 adr-0038-preboot-health-probe | WS-01 | shipped | #257, `landings/PLAN-10.md` |
| PLAN-11 macos-loopback-hang-investigation | WS-07 | shipped | #243, `landings/PLAN-11.md` |
| PLAN-12 kqueue-readiness-instrumentation | WS-07 | landed | `landings/PLAN-12.md` (no PR) |
| PLAN-13 loopback-stall-fix | WS-07 | shipped | #255, `landings/PLAN-13.md` |
| PLAN-14 healthprobe-javadoc-port-claim | WS-01 | superseded | folded into WS-01 shipped work |
| PLAN-15 trusted-proxy-breadth-and-probe-doc | WS-05 | shipped | #267, `landings/PLAN-15.md` |
| PLAN-16 cookie-deliverability-and-ceiling | WS-04 | shipped | #284, `landings/PLAN-16.md` |
| PLAN-17 cookie-mode-refresh-viability | WS-04 | shipped | #288, `landings/PLAN-17.md` |
| PLAN-18 pro-forma-integration-test-fixes | WS-06 | shipped | #308, `landings/PLAN-18.md` |
| PLAN-19 release-readiness-doc-review | WS-06 | superseded | absorbed into PLAN-24 D7-11 |
| PLAN-20 egress-leg-pinning-and-sweep | WS-03 | superseded | absorbed into PLAN-25 D1-4,9-11 |
| PLAN-21 session-cookie-config-validation | WS-04 | superseded | absorbed into PLAN-25 D5-6 |
| PLAN-22 trusted-proxy-breadth-threshold | WS-05 | superseded | absorbed into PLAN-25 D7-8 |
| PLAN-23 unit-lane-vacuity-audit | WS-07 | shipped | #336, `landings/PLAN-23.md` |
| PLAN-24 release-docs-and-tls-scenario-guide | WS-06 | shipped | #305, `landings/PLAN-24.md` |
| PLAN-25 configuration-security-hardening | WS-08 | shipped | #306, `landings/PLAN-25.md` |
| PLAN-26 refresh-failure-dispositions | WS-04 | shipped | #314, `landings/PLAN-26.md` |
| PLAN-27 idp-refresh-reuse-strict-rotation-e2e | WS-04 | superseded | folded into PLAN-26 (12 deliverables) |
| PLAN-28 closeout-residual-hardening | WS-09 | shipped | #341, `landings/PLAN-28.md` |
| PLAN-29 final-gap-closure | WS-09 | shipped | #348, `landings/PLAN-29.md` |

**Tally: 21 shipped, 1 landed, 7 superseded, 0 parked, 0 dropped without a successor.** Every
superseded row names the plan that carries its work — none was silently dropped.

## Major decisions

- **2026-09-02 → 2026-09-23, `parallelization_scope` raised 1 → 2 → 3** as concurrent execution
  proved itself, each raise recording reality rather than pre-authorizing more.
- **2026-09-07, standing direction**: DPoP is the sender-constraining direction; inbound mTLS
  (`MtlsServerCustomizer`) is a distinct, shipped, untouched feature — the two "mTLS" surfaces were
  never to be conflated.
- **2026-09-15, corpus regroup**: seven staged plans regrouped into four (PLAN-09/19/20/21/22 →
  PLAN-24/PLAN-25) after `corpus cross-check` found forced-sequential collisions; up to 12
  deliverables per plan authorized for this epic, overriding the ~6-deliverable split presumption.
- **2026-09-15, refresh-token reuse detection — Option D**: reuse detection is the IdP's job
  (strict rotation), not the gateway's; shipped and proven end-to-end by PLAN-26, with the
  alternative (adopting `TokenLifecycleManager` gateway-side) explicitly declined and routed back to
  Token-Sheriff as a cross-repo question.
- **2026-09-16, `default:emit-landing` turned on** for orchestrated plans (PR #309) — closed a
  standing gap where a merged plan could land invisibly to the epic; every plan from PLAN-26 onward
  filed its own machine-readable `landing-facts` inbox message.
- **2026-09-21, cleanup pass**: corpus fully re-grounded at `cc10ce2`, 211 claim verdicts persisted;
  restart-readiness confirmed. The one item held open at that point (Sonar `java:S3398`) was later
  routed rather than decided in-epic (see below).
- **2026-09-22, ledger migration**: the entire epic tree, living since creation in the gitignored
  `.plan/local/orchestrator/` path (never tracked, never shared), was migrated to the canonical
  tracked `.plan/orchestrator/` location matching sibling epics `api-sheriff-0-2-0`/`api-sheriff-0-3-0`
  — triggered by a mid-session executor regeneration that exposed the inconsistency. Landed via PR
  #347.
- **2026-09-23, PLAN-28 and PLAN-29** swept the epic's accumulated residue: PLAN-28 alone resolved a
  security-preset gap (cui-http `paranoid()`), a config-coercion gap (`tls.passthrough_sni`), the
  back-channel-logout realm gap (plus 3 real bugs and a CWE-117 fix), root-path normalisation, and
  confirmed the epic's own standing Docker-image-collision HYPOTHESIS with a root-caused fix. PLAN-29
  closed the last two actionable gaps and opportunistically fixed a cross-epic ADR ordinal collision
  (0050 → 0053) discovered mid-flight.
- **2026-09-23, pre-close handoff**: reviewed all 18 lessons accumulated in the local
  `.plan/local/lessons-learned/` corpus and found none were genuinely plan-marshall-specific (every
  plan-marshall tooling gap this epic ever found was routed directly to plan-marshall's own
  `lessons-routing` epic as a finding, never promoted to the local corpus). All 18 lessons, plus the
  standing Sonar `java:S3398` item, were handed to `api-sheriff-0-2-0`'s inbox rather than decided or
  retired unilaterally — that epic continues touching this repository's code and now owns their
  disposition. The local lessons corpus is confirmed empty at close (tombstones preserved).

## Cross-epic and cross-repo threads left open (carried forward as leads)

These are not failures of this epic — they are deliberately handed to whichever epic is positioned
to act on them, per the routing discipline this epic used throughout its life:

- **`api-sheriff-0-2-0`'s inbox** now holds: the ADR-0050/0053 collision record (already fixed, kept
  for attribution), all 18 handed-off lessons (`deployment-configurability-002` through `-019`), and
  the Sonar `java:S3398` finding (`-020`) — all awaiting that epic's own Promote/Fold/Discard and
  disposition review.
- **plan-marshall's `lessons-routing` epic** holds 4 findings from this epic
  (`api-sheriff-deployment-configurability-001` through `-004`), covering `scope_creep_check`'s
  finding-type rejection, a stale-local-base refusal in `pre-submission-self-review`, a bundled
  5-script argparse/flag-shape recurrence class, and a follow-up bundle (a confirmed recurrence plus
  two new tooling gaps: `qgate list` missing `--phase`, `ci_wait`'s adaptive budget not fitting this
  repo's known-slow CI jobs).
- **Token-Sheriff's own epic** (`lessons-handling-26-09-04-01`) received one routed finding on
  whether the engine's `classify` should special-case a revoked-family `ClientProtocolException`
  (2026-09-15) — a Token-Sheriff-side design question, not actioned here.

## Genuinely open, unowned residue (not routed, recorded here for the record)

A small number of low-priority items were re-verified as still open during the 2026-09-23
close-readiness sweep but judged not worth routing or blocking close over — future work should
re-verify before acting, since this record is a snapshot at close:

- `AGENTS.md`/`CLAUDE.md` module-list drift with no contract test binding them to `pom.xml`
  (operator Hold, 2026-09-15) — also present among the handed-off lessons.
- The image-revision-label discrimination half of the Docker-image-selection lesson (local native
  builds stamp `revision=dev`, so a running container's provenance can't be verified by label alone)
  — also present among the handed-off lessons.
- Machine-local loopback port-collision flakiness — now documented as a known caveat in
  `doc/development/local-test-environment-caveats.adoc` (PLAN-29), not fixed (it isn't fixable
  in-repo; it's a foreign-process contention issue on the workstation).
- Contended local-verify budget under `parallelization_scope=3` — a process observation about
  concurrent plan execution, not an API-Sheriff code defect.

## Closing rationale

The queue is fully settled (21 shipped, 1 landed, 7 superseded, 0 live, 0 staged). Every open item
that could be decided in-epic was decided; every item that belonged to a continuing epic's scope was
handed off with full context rather than either decided unilaterally or left to rot unowned. The
local lessons corpus — this epic's own accumulated knowledge — was reviewed in full and transferred,
not abandoned. Nothing was silently dropped: every superseded plan names its successor, every routed
finding names its destination and reason.
