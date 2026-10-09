# PLAN-V02-07: Threat Classification & Loud Signal

epic: api-sheriff-0-2-0
workstream: WS-03

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

## Objective

Separate a genuine attack campaign from benign-but-malformed traffic, and surface a loud, SIEM-ready
signal. The shipped per-event taxonomy classifies by HTTP category but has no cross-request judgement —
one clumsy client and a systematic scanner look identical. This plan adds a **weighted anomaly score**
over the `EventCategory` set, accumulated per client in a sliding window (reusing `PLAN-V02-06`'s
per-client substrate), with a **two-tier signal**: per-request violations stay low-severity
(WARN + metric, as today), while a window crossing the campaign threshold emits a single **ALERT** in an
**ECS/OCSF-shaped** structured record — loud but naturally deduplicated per client-window.

State is in-process and single-node; a SIEM owns cross-node correlation.

## Deliverables

1. **Weighted anomaly score** — assign severity weights to the existing `EventType`/`EventCategory`
   set (mirroring OWASP CRS anomaly scoring: per-category points), accumulated **per client per window**
   over `PLAN-V02-06`'s substrate (not per-request). The set includes the `ROUTING` category
   `PLAN-V02-13` added — see Dependencies.
2. **Two-tier signal / campaign threshold.** Per-request violation → WARN + Micrometer counter
   (existing behaviour, unchanged). Window score ≥ configurable threshold → a single **ALERT** event.
   **Stated as its own line item** — the dedup-per-client-window is what prevents alert fatigue and is
   exactly what an outline abstracts away.
3. **ECS/OCSF-shaped structured emission** for the ALERT: a stable field set (`event.category`,
   `event.severity`, `source.ip`, an ATT&CK technique id, and the contributing per-category breakdown),
   emitted as a structured log record — pure formatting, no new runtime dependency.
4. **Config** for the weights, window, and campaign threshold (secure defaults).
5. **Tests** (a scan pattern crosses the threshold and emits exactly one ALERT per window; benign
   single-malformed does not; ECS/OCSF field shape).
6. **Architecture documentation + ADR** — **stated as its own named line item**. Document the NEW
   threat-classification subsystem (the anomaly-score model, the per-client window over `PLAN-V02-06`'s
   substrate, the two-tier signal flow) in `doc/architecture.adoc`, and record an **ADR** for the
   scoring/threshold model + the **ECS/OCSF signal architecture** + the single-node-state decision
   (SIEM owns cross-node correlation). Plus **three-layer documentation** (`configuration.adoc` weights/
   window/threshold, `doc/user/` on consuming the ALERT signal, `doc/development/`) and the `LogRecord`
   registration in `doc/LogMessages.adoc`, including the single-node caveat. Derive the ADR ordinal
   from `doc/adr/` on the branch at write time; a duplicate ordinal fails the build.

**Split-guard.** Six deliverables, proceeding unsplit: the score, the threshold and the emission are
one signal and deliver nothing separately. If the ECS/OCSF emit (D3) and the scoring engine (D1) both
prove large at outline, split the emit into its own follow-on rather than bloat.

## Claim Labels

- OBSERVED: the per-event taxonomy exists and is category-typed — `events/EventType.java` (categories
  INPUT_VALIDATION / ROUTING / AUTHENTICATION / AUTHORIZATION / UPSTREAM / CONFIGURATION — `ROUTING` added
  by #383, ADR-0059), surfaced via
  `events/GatewayEventCounter.java` → `quarkus/SheriffMetrics.java` (Micrometer) and WARN logging in
  `edge/GatewayEdgeRoute.java` (`SECURITY_FILTER_VIOLATION`, payload-safe). Find the WARN site by
  content — `LOGGER.warn(ApiSheriffLogMessages.WARN.SECURITY_FILTER_VIOLATION, …)` — not by line.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: EventCategory has six values incl. ROUTING; GatewayEdgeRoute.reportRejection WARNs SECURITY_FILTER_VIOLATION; SheriffMetrics.ERRORS_TOTAL exists
- OBSERVED absence: no cross-request scoring / campaign notion exists — the counter is global-per-event,
  not per-client-windowed. (CRS itself scores per-request only; the cross-request accumulation is the
  net-new value here.)
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: no anomaly, campaign, ECS, OCSF or score construct in api-sheriff/src/main/java
- HYPOTHESIS: `PLAN-V02-06`'s per-client substrate is reusable as the accumulation window for the score
  rather than a second parallel per-client store. Confirm/refute at the substrate `PLAN-V02-06` actually
  ships § its per-client state API (verify-at-outline) — **central risk**: if that substrate is
  bucket-only (recon codes) and not general-purpose, the gap is a finding against `PLAN-V02-06`'s
  substrate contract, not work to absorb here and not a reason to build a second store.
  - verdict: unverifiable | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: PLAN-V02-06's substrate has not shipped, so reuse cannot be judged
- Verify-first clause: scope the ECS/OCSF field set against the actual schema (Elastic Common Schema /
  OCSF event classes), not against this spec's field list, before emitting — the shape must validate
  against a real consumer.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: procedural clause still applicable: no ECS or OCSF emitter exists

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java` — severity weights per category
- OBSERVED absence → NEW: an anomaly-score / campaign-classifier component (consumes the `PLAN-V02-06` substrate)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/GatewayEventCounter.java` / `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/SheriffMetrics.java` — the two-tier signal wiring
- OBSERVED absence → NEW: an ECS/OCSF structured-emit formatter + a new `LogRecord` ALERT constant (`doc/LogMessages.adoc`)
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/**` — weights / window / threshold config; `doc/**`; `api-sheriff/src/test/**`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` (`reportRejection`, the WARN dispatch site), `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/ApiSheriffLogMessages.java` (the ALERT record)

## Dependencies and Sequencing

- Depends on **`PLAN-V02-06` (enumeration-hardening)**, a hard predecessor: this plan cannot be
  scoped before it lands. The taxonomy D1 weights is final: `PLAN-V02-13` landed (#383, ADR-0059) and
  moved `NO_ROUTE_MATCHED`, `PASSTHROUGH_HOST_SMUGGLED` and `METHOD_NOT_ALLOWED` into the new `ROUTING`
  category. Routing misses are operationally ordinary while filter violations are a security signal,
  so weight the two categories apart.
  - **`PLAN-V02-06`.** Its per-client substrate and its written,
     general-purpose substrate contract — which names this plan as first consumer — are what D1's
     accumulation runs on. Read the shipped contract first at outline.
- This is the most downstream plan of WS-03; emit it last among them.
- Overlaps with: `PLAN-V02-06`, and `PLAN-V03-01` (honeypot) in the `api-sheriff-0-3-0` epic — all
  touch the event system and the substrate. Never concurrent with either.
- `GatewayEdgeRoute.reportRejection` is the WARN dispatch site, and `PASSTHROUGH_HOST_SMUGGLED` is a
  second WARN case beside `SECURITY_FILTER_VIOLATION`; both are in the Expected Surface.
- ADR-0062 (platform-first, landed by `PLAN-V02-01`): the ECS/OCSF formatter serialises through
  `bff/runtime/GatewayJson`, not a hand-rolled writer, and the scoring ADR records the reason for any
  hand-rolled state.

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan. CUI
logging (LogRecord for the ALERT; ranges per `doc/LogMessages.adoc`).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-07-threat-classification.md" plan_id=plan-v02-07-threat-classification
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
