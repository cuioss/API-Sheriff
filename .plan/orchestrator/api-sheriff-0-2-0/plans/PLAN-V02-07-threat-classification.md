# PLAN-V02-07: Threat Classification & Loud Signal

epic: api-sheriff-0-2-0
workstream: WS-03
track: **POST-0.1.0**

> **MOVED TO `api-sheriff-next` 2026-07-27** by operator decision at the full plan revisit: this is
> post-0.1.0 work. The `api-sheriff-roadmap` epic now carries the release track only and closes at
> the cut. Re-ground this spec against HEAD at that epic's decompose — its claim labels were written
> 2026-07-25 and several were already refuted by PLAN-06 and PLAN-23 (see
> `../../api-sheriff-roadmap/archive.md` § 6).

> Staged plan spec — ready for `/plan-marshall` hand-off. NEW 2026-07-25 (operator requirements intake
> + prior-art research; AskUserQuestion: "build in-process (single-node) + ECS/OCSF emit").
> ~~Gated POST-first-landing.~~ **That gate EXPIRED.**
>
> **⚠ THIS PLAN MERGES INTO PLAN-18 at this epic's DECOMPOSE (decided 2026-07-27).** It is retained
> as a separate spec only until then, because its content must be re-grounded before the merge is
> authored. See `PLAN-18-enumeration-hardening.md` § header for the rationale. **Do not launch it
> standalone.**

> **Renumbered 2026-08-04.** This spec was `PLAN-19-threat-classification.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**⚠ THIS PLAN NO LONGER MERGES INTO `PLAN-V02-06` — OPERATOR DECISION 2026-08-08.**
The header above says *"THIS PLAN MERGES INTO PLAN-18 at this epic's DECOMPOSE … Do not launch it
standalone."* **That instruction is retired.** This plan stays separate and IS launchable on its own
terms, once its two dependencies land. Two facts that were not in evidence on 2026-07-27 changed the
balance: `PLAN-V02-13` now re-categorises the taxonomy this plan weights, and a merged spec would
carry ~11 deliverables, far past the split guard.

**What replaces the merge, and why the original concern is still covered.** The merge existed to stop
this plan finding V02-06's substrate too narrow and having to generalise it. That is now handled by
**V02-06's new deliverable 7 — a written, general-purpose substrate contract with this plan named as
its first consumer.** So the spec's own *central risk* below — *"if PLAN-18's substrate is
bucket-only and not general-purpose, this plan must generalize it, not duplicate it"* — is
**answered by contract rather than by merger**. At outline, read V02-06's shipped contract first: if
it does not cover this plan's accumulation needs, **that is a finding to report against V02-06, not
work to absorb silently here.**

**SEQUENCING — TWO HARD PREDECESSORS, IN ORDER.**
1. **`PLAN-V02-13` (terminal-rejection-contract) FIRST.** It moves `NO_ROUTE_MATCHED`,
   `PASSTHROUGH_HOST_SMUGGLED` and `METHOD_NOT_ALLOWED` out of `EventCategory.INPUT_VALIDATION`
   (likely into a new `ROUTING` category). **D1 assigns severity weights to exactly that taxonomy.**
   Weighting it before V02-13 lands means doing it twice, and the second time silently. V02-13's own
   spec states the reason from the other side: routing misses are operationally ordinary while filter
   violations are a security signal, and one bucket cannot alert on both.
2. **`PLAN-V02-06` (enumeration-hardening) SECOND** — its D3 substrate plus its D7 contract are what
   D1's per-client-per-window accumulation runs on.

**CONFIRMED, first-party.** `EventCategory` carries all five values (`INPUT_VALIDATION`:34,
`AUTHENTICATION`:37, `AUTHORIZATION`:40, `UPSTREAM`:43, `CONFIGURATION`:46).
`SheriffMetrics` exists and binds Micrometer counters. The cross-request scoring absence holds — no
per-client windowed accumulation exists anywhere in `api-sheriff/src/main/java`.

**MOVED — the WARN site.** The `SECURITY_FILTER_VIOLATION` warn path is at
`GatewayEdgeRoute.java`:**791–794**, not `:408-411`. Re-anchor by content
(`LOGGER.warn(ApiSheriffLogMessages.WARN.SECURITY_FILTER_VIOLATION, …)`), not by line.

**WORKSTREAM CORRECTION.** The Sequencing section says *"strictly sequential within WS-05"*. This
plan and V02-06 are both **WS-03** (Threat Hardening); WS-05 is Integration and Test Fidelity.

**RENUMBERING.** "PLAN-18" is **PLAN-V02-06**; "PLAN-20" is `api-sheriff-0-3-0`'s **PLAN-V03-01**
(honeypot), a different epic entirely.

**ADR numbering** — the corpus is contiguous `0001`–`0037`, so **`0038` is the next free number and
is genuinely free** (roadmap PLAN-50 landed `0035`). Re-check against open branches at write time;
note V02-06, V02-11, V02-12, V02-13 and V02-01 also author ADRs.


## Re-Grounded (2) 2026-08-09 at `95dd566` — after four landings

`PLAN-V02-02`, `-03`, `-16` and `-17` have shipped. **This section outranks the 2026-08-08
re-grounding above it wherever they conflict.**

**EPIC-WIDE, AND NO SPEC BELOW KNOWS IT: THE BUILD NOW FAILS ON ANY COMPILER WARNING.**
`PLAN-V02-02` turned on `<showDeprecation>true</showDeprecation>` **and**
`<failOnWarning>true</failOnWarning>` reactor-wide (`pom.xml`:163, :178), so javac runs with
`-Werror` across all six modules. A deprecated API or an unchecked cast is now a **build failure**,
not a log line. Two consequences bind every plan:

1. **Answer such a failure by migrating off the warned construct.** `CLAUDE.md` states it directly:
   a `@SuppressWarnings` added to get back to green *"hollows the gate out while leaving it reporting
   success"*, and it collides with the Pre-1.0 rule forbidding deprecated code at all.
2. **The failure reaches the executor as a `warnings[]` row plus a `-Werror` `errors[]` row.** Read
   both arrays — the line number lives on the warning row.

**ANCHORS HELD** at `95dd566`: `EventCategory`'s five values, and the `SECURITY_FILTER_VIOLATION` warn
site still at `GatewayEdgeRoute`:791/:794.

**BOTH PREDECESSORS ARE STILL UNSTARTED**, so the two-step sequence is unchanged: **V02-13 → V02-06 →
this plan**. V02-13 re-cuts the taxonomy this plan weights; V02-06 D7 ships the substrate contract
this plan consumes. Neither has run, so nothing here can be scoped yet — **this is the most
downstream plan in the epic and should be emitted last among WS-03**.

## Objective

Separate a genuine attack campaign from benign-but-malformed traffic, and surface a loud, SIEM-ready
signal. The shipped per-event taxonomy classifies by HTTP category but has no cross-request judgement —
one clumsy client and a systematic scanner look identical. This plan adds a **weighted anomaly score**
over the existing `EventCategory`, accumulated per client in a sliding window (reusing PLAN-18's
per-client substrate), with a **two-tier signal**: per-request violations stay low-severity
(WARN + metric, as today), while a window crossing the campaign threshold emits a single **ALERT** in an
**ECS/OCSF-shaped** structured record — loud but naturally deduplicated per client-window.

## Deliverables

1. **Weighted anomaly score** — assign severity weights to the existing `EventType`/`EventCategory`
   set (mirroring OWASP CRS anomaly scoring: per-category points), accumulated **per client per window**
   over PLAN-18's substrate (not per-request).
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
   threat-classification subsystem (the anomaly-score model, the per-client window over PLAN-18's
   substrate, the two-tier signal flow) in `doc/architecture.adoc`, and record an **ADR** for the
   scoring/threshold model + the **ECS/OCSF signal architecture** + the single-node-state decision
   (SIEM owns cross-node correlation). Plus **three-layer documentation** (`configuration.adoc` weights/
   window/threshold, `doc/user/` on consuming the ALERT signal, `doc/development/`) and the `LogRecord`
   registration in `doc/LogMessages.adoc`, including the single-node caveat.

## Claim Labels

Corroborated against HEAD 3f60d49, 2026-07-25.

- OBSERVED: the per-event taxonomy exists and is category-typed — `events/EventType.java` (categories
  INPUT_VALIDATION / AUTHENTICATION / AUTHORIZATION / UPSTREAM / CONFIGURATION), surfaced via
  `events/GatewayEventCounter.java` → `quarkus/SheriffMetrics.java` (Micrometer) and WARN logging at
  `edge/GatewayEdgeRoute.java`:408-411 (`SECURITY_FILTER_VIOLATION`, payload-safe).
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: EventCategory/EventType/GatewayEventCounter/SheriffMetrics all present. WARN site content-confirmed but line drifted for a THIRD time: :408-411 -> :791-794 (2026-08-08) -> now GatewayEdgeRoute.java:888. Re-anchor by content (SECURITY_FILTER_VIOLATION), not line, as spec already instructs.
- OBSERVED absence: no cross-request scoring / campaign notion exists — the counter is global-per-event,
  not per-client-windowed. (CRS itself scores per-request only; the cross-request accumulation is the
  net-new value here.)
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: Repo-wide search for window/sliding/campaign/anomaly/perclient: zero hits. GatewayEventCounter.java:35-45 is a flat ConcurrentHashMap<EventType,AtomicLong>, no client key, no time dimension.
- HYPOTHESIS: PLAN-18's per-client substrate is reusable as the accumulation window for the score rather
  than a second parallel per-client store. Confirm/refute at the substrate PLAN-18 actually ships §
  its per-client state API (verify-at-outline) — **central risk**: if PLAN-18's substrate is
  bucket-only (recon codes) and not general-purpose, this plan must generalize it, not duplicate it.
  - verdict: unverifiable | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: PLAN-V02-06 has not landed (landings/ has only V02-02/-03/-16/-17); its per-client substrate does not exist on main yet, so the hypothesis correctly remains open. Spec's own verify-at-outline framing still accurate.
- Verify-first clause: scope the ECS/OCSF field set against the actual schema (Elastic Common Schema /
  OCSF event classes), not against this spec's field list, before emitting — the shape must validate
  against a real consumer.
  - verdict: unverifiable | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: Procedural instruction, not a codebase fact; repo-wide search for ecs/ocsf returns zero hits -- nothing exists yet to corroborate/contradict. Remains a valid forward instruction.

## Expected Surface

- OBSERVED: `events/EventType.java` — severity weights per category
- OBSERVED absence → NEW: an anomaly-score / campaign-classifier component (consumes PLAN-18 substrate)
- OBSERVED: `events/GatewayEventCounter.java` / `quarkus/SheriffMetrics.java` — the two-tier signal wiring
- OBSERVED absence → NEW: an ECS/OCSF structured-emit formatter + a new `LogRecord` ALERT constant (`doc/LogMessages.adoc`)
- OBSERVED: `config/model/**` — weights / window / threshold config; `doc/**`; `api-sheriff/src/test/**`

## Dependencies and Sequencing

- Depends on: **PLAN-18 (hard)** — reuses its per-client substrate. Sequence PLAN-18 → PLAN-19.
- Overlaps with: PLAN-18 and PLAN-20 all touch the event system / substrate — **strictly sequential
  within WS-05**, never concurrent with each other.
- **Split-guard note:** 5 deliverables. If the ECS/OCSF emit (D3) plus the scoring engine (D1) both
  prove large at outline, split the emit into its own follow-on rather than bloat.

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan. CUI
logging (LogRecord for the ALERT; ranges per `doc/LogMessages.adoc`).

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-07-threat-classification.md" plan_id=plan-v02-07-threat-classification
```

## Write-Boundary

Touches only its own repository source and tests; creates/edits NO file under `.plan/local/orchestrator/`.

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PR opens}
- landing: {set when landings/PLAN-19.md is recorded}
