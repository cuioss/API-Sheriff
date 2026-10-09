# Landing Analysis: PLAN-V02-01 — ADR-0005 Reversal, Quarkus Adoption and BFF Session Management

epic: api-sheriff-0-2-0
workstream: WS-01
pr: #409 (https://github.com/cuioss/API-Sheriff/pull/409), with #410, #412, #415 and #417

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-09 from the inbox
> message `plan-v02-01-adr-0005-reversal-quarkus-adoption-019.md` (inbox-scan mode, `landing-check`
> `complete: true`), after verifying each material claim against ground truth.

## Deliverable Fidelity vs Spec

Checked against `origin/main` at `386f3f74` and the PR states read through the CI abstraction. The
plan landed as five squash commits, all `merged`:

| PR | Commit | Content |
|----|--------|---------|
| #410 | `1591972d` | part 1 of 3 — ADR-0062, the substitutions, the narrowed arch gate |
| #412 | `862d574e` | part 2 of 3 — session management (96 files) |
| #409 | `9f9eeae8` | part 3 of 3 — documentation and review fixes (38 files) |
| #415 | `b3185ce0` | the formatter's import-group spacing, 282 files, deletions only |
| #417 | `386f3f74` | the thirteen Sonar findings of the session change (7 files) |

The spec carries **seven** deliverables. The landing message reports 9 of 9 done; that is the count
of the plan's own outline, and its mapping onto the seven could not be read (the plan directory is
gone). The table below is against the spec.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — supersede ADR-0005 with a new ADR | shipped-as-specified | ADR-0062 *Platform mechanisms are preferred over hand-rolled equivalents and the framework-agnostic core rule is retired*, with a stays-hand-rolled table and the rejected alternatives. ADR-0005 reads `Superseded by ADR-0062`. The put-after-delete question is answered from `LocalSessionStoreImpl#put` in vertx-web 4.5.34. Command mode is not adopted (the ADR-0061 revisit). |
| D2 — retire `FrameworkAgnosticArchTest` and the ADR-0005 gate | shipped-with-deviation | The test was not deleted but renamed to `arch/PreBootFrameworkFreeArchTest` and narrowed to the five pre-boot packages (`config.boot`, `.load`, `.model`, `.validation`, `.topology`); `events`, `forward` and `pipeline` are no longer gated. The vacuity guard and the negative control are kept. ADR-0062 records it. |
| D3 — `JsonWriter` replaced by platform JSON | shipped-as-specified | `bff/runtime/JsonWriter` and its test are deleted; `GatewayJson` is a `@Singleton` over a private copy of the injected Jackson mapper. No dependency was added. `GatewayJson` keeps about 150 lines of custom serializers so the output stays byte-identical. |
| D4 — `EnvSecretResolver` / `config/load` against smallrye-config | shipped-as-specified | Kept, with the reason in ADR-0062 and the class Javadoc. The three overloads left without a production caller by `PLAN-V02-14` are removed (`ConfigLoader.load()`, the three-argument `TopologyResolver.resolve`, `EnvSecretResolver.resolve(String)`). |
| D5 — keep the session store, close three gaps | shipped-with-deviation | Idle timeout (`idle_timeout_seconds`, default `min(1800, ttl)`) and the 60-second sweep are in. **Rotation deviates from the spec by operator decision at outline review:** on step-up and widening the browser-facing cookie value is re-issued, while the internal session id, authentication time and absolute expiry stay (ADR-0018 § cookie-reissue, ADR-0057 amended). In cookie mode the earlier sealed cookie stays usable until it expires — an accepted residual recorded as `BFF-21`. `bff/runtime/SessionIdentity.java`, declared in the surface, is untouched. |
| D6 — CDI adoption and the `tls/` review | shipped-as-specified | One bean added (`GatewayJson`); ADR-0062 gives the reasons nothing else is converted. `tls/package-info.java` records which classes are beans and why `ClientHelloSniParser` stays. Not reported: whether `ClientHelloSniParser` has open Sonar findings, which the spec asked to check. |
| D7 — session-management security audit | shipped-with-deviation | Threat-model entry `BFF-21` added and `BFF-09` extended; fixes with tests: logout-token `typ`/`exp`/`jti` validation with a replay guard, RP-initiated logout ending the session without a usable `end_session_endpoint` (ApiSheriff-135), `UpstreamSetCookieFilter`, `no-store` on responses setting a gateway cookie, `Referrer-Policy: no-referrer`, WebSocket relays closed with their session. **Not in any tracked file:** the per-attack-class catalogue with a verdict per class, and the verdicts on `PLAN-V02-08`'s two status choices (`502` on a refused widening push, `400`/ApiSheriff-134 on an unbound token). The epic's rule is that unfixed audit findings go to the operator only, so the catalogue's absence from tracked files is expected; whether the operator received it is not readable from the ledger. |

**Added beyond the seven deliverables**, all corroborated in the diff:

- The cookie-mode activity cookie is authenticated with HMAC-SHA-256 and is not encrypted (42 bytes,
  56 characters, format version 2). Part 2 shipped it AES-GCM-sealed; part 3 changed it during
  review. ADR-0018 records the reason.
- Every back-channel logout rejection reason is logged at WARN once per emitter, then at DEBUG.
  ADR-0051 was rewritten in place for this (its level discriminator is now repeatability), not only
  amended.
- New configuration key `max_sessions_per_subject`; a login destroys the presented session.

**Realized footprint against the declared surface.** No footprint was captured by the plan (the
worktree had a follow-up branch checked out at cleanup), so it was derived here from the four
content commits. About 50 files lie outside the declared surface: `edge/` (5, including
`GatewayEdgeRoute`, `ResponseStage`, `WebSocketRelayStage`), `quarkus/` beyond `BffRuntimeProducer`
(4), `config/` root, `boot`, `validation` and `model` (10), `pipeline/` (3), `events/`, `routing/`,
`auth/`, `doc/development/` (6), `doc/configuration.adoc`, `doc/LogMessages.adoc`, `doc/variants/`
and `doc/quality-report/`. Nothing ran beside this plan, so no collision resulted.

## Metrics and Anomalies

- Tokens: 54,284,519 total.
- Duration: 53h04m wall (191,082 s).
- Anomalies:
  - The plan's single PR (164 files) was refused by CodeRabbit and Sourcery for size and split after
    the fact into three sequential PRs against `main`.
  - From part 2 on, the pre-commit formatter inferred one blank line between import groups where
    282 files had two; resolved by committing the rewrite once (#415, merged without a CodeRabbit
    review because of its size).
  - Finalize stopped at the automated-review step when the PR was split and was closed by the
    operator; review threads were answered on the PRs, outside the findings store.
  - Thirteen new-code Sonar issues at the first scan, all fixed in #417.
  - All recorded as lessons (`2026-10-09-13-001` … `-010` and six recurrences).

## Routing and Merge Behavior

- Review: per the plan, every thread on the five PRs is answered and resolved. Not re-read here.
- CI/merge: all five merged through the merge queue; each merge-group `Maven Build` and
  `Integration Tests` run succeeded.
- Post-merge, checked by the orchestrator 2026-10-09:

  | Commit | Main-branch push runs | PR-attached benchmark |
  |--------|-----------------------|-----------------------|
  | `1591972d` (#410) | all success | success |
  | `862d574e` (#412) | `Maven Build` failure in the non-gating `OpenRewrite dirty-tree report` job only; the rest success | success |
  | `9f9eeae8` (#409) | `Integration Tests` **cancelled** at 35 minutes (job timeout, since raised to 60); the rest success | success |
  | `b3185ce0` (#415) | all success | **failure** |
  | `386f3f74` (#417) | `Integration Tests` still running when this was written; the rest success, `Maven Build` included | **failure** |

  - **The benchmark failures are not attributed to this plan.** Every benchmark run since #408
    (`b7f937d3`, merged between #409 and #415) fails the same way: the
    `run-k6-pending-login-flood-benchmark` execution aborts with `HTTP 404` on `/auth/login`. #408
    changed `benchmark.yml` and `benchmarks/pom.xml`; the three parts of this plan that merged
    before it have green benchmark runs. Recorded as Open Defect 17.
  - The cancelled `Integration Tests` run on `9f9eeae8` did not report a test failure; the next
    push run on `main` succeeded.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `409`; `landing` `landings/PLAN-V02-01.md`
- [x] inbox `-019` archived (`reconciled`); eighteen candidate lessons dispositioned and archived:
      ten promoted, seven folded, one discarded
- [x] Open Defect 12 (`RouteRuntimeAssembler` dead allocation) loses its owner: ADR-0062 lists it as
      not adopted
- [x] Open Defect 17 added (benchmark red on `main` since #408)
- [x] Watch added: the D7 follow-ups (the two `PLAN-V02-08` status verdicts, the two controls
      `BFF-21` names as untested, the `ClientHelloSniParser` Sonar check)
- [x] Sequencing rows naming `PLAN-V02-01` discharged; ADR-authoring note: next free ordinal 0063
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- **D7 residue** — see the Watch. The per-class audit catalogue is an operator-only document.
- **`RouteRuntimeAssembler`** — Open Defect 12 needs a home; no staged plan owns it.
- **Stale configuration** — the plan reported `marshal.json` stale against the regenerated executor;
  a `/marshall-steward` run is owed (existing Watch).
- **Benchmark** — Open Defect 17.
