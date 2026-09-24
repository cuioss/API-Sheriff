# PLAN-V03-01: Honeypot / Deception Layer

epic: api-sheriff-0-3-0
workstream: WS-01
track: **POST-0.1.0**

> **MOVED TO `api-sheriff-next` 2026-07-27** by operator decision at the full plan revisit: this is
> post-0.1.0 work. The `api-sheriff-roadmap` epic now carries the release track only and closes at
> the cut. Re-ground this spec against HEAD at that epic's decompose — its claim labels were written
> 2026-07-25 and several were already refuted by PLAN-06 and PLAN-23 (see
> `../../api-sheriff-roadmap/archive.md` § 6).

> Staged plan spec — ready for `/plan-marshall` hand-off. NEW 2026-07-25 (operator requirements intake
> + prior-art research; AskUserQuestion: "in-process decoy + tarpit + local ban + emit").
> ~~Gated POST-first-landing.~~ **That gate EXPIRED.**
>
> **KEPT SEPARATE at the 2026-07-27 revisit** (PLAN-19 merged into PLAN-18; this one did not).
> Deception is a distinct feature with its own legal/operational review, and folding it into the
> substrate plan would bury that review. Its hard dependency is now **the merged PLAN-18** — which
> is good news for its two HYPOTHESES: the substrate's ban API and the ECS/OCSF emit formatter will
> come from **one** plan rather than two, so there is a single place to verify them against.

> **Renumbered 2026-08-04.** This spec was `PLAN-20-honeypot-deception.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Objective

Add an in-process deception layer for probes of sensitive endpoints the API does not expose
(`/admin`, `/.env`, `/actuator`, `/wp-login`, `/.git`, …). No off-the-shelf JVM library exists, so this
implements the OWASP Web-App-Deception pattern directly: a config-driven decoy bad-path stage that, on a
match, records a high-signal structured event, applies a **non-blocking tarpit** (a Vert.x timer — holds
no thread), and feeds a **per-source strike-ban** built on PLAN-18's substrate — with a **crawler
allowlist** to avoid false-positives, and events emitted for optional external consumption
(CrowdSec / SIEM). The gateway acts locally and self-contained; it never reimplements external
reputation infrastructure.

## Deliverables

**Six deliverables.** Tests are not a numbered deliverable here — **each of D1–D5 owes its own tests
in the same PR**, per the epic convention. The specific coverage the former standalone test item
named is now an obligation on the deliverables that produce it: decoy match produces event + tarpit +
strike escalation; an allowlisted crawler is exempt; **the tarpit holds no thread** — that last one is
the load-bearing assertion, because a blocking sleep would turn the defence into a self-inflicted DoS.

1. **Config-driven decoy bad-path stage.** A curated, config-overridable list of sensitive non-exposed
   paths; a match produces a high signal-to-noise structured security event (OWASP "high S/N"). Decoy
   responses are indistinguishable from a normal miss (uniform-404 per PLAN-18) so the decoy is not
   itself a tell.
2. **Non-blocking tarpit.** A configurable delayed response via a Vert.x timer (`setTimer`) — **stated
   as its own line item** because the non-blocking property is load-bearing: a blocking sleep would
   consume a virtual thread per probe and become a self-inflicted DoS.
3. **Per-source strike-ban.** Repeated decoy hits from one source escalate to a temporary local block,
   built on PLAN-18's per-client substrate (not a second store).
4. **Crawler / scanner allowlist.** Known-good crawlers and authorized security scanners (PCI ASV, etc.)
   are exempt from ban/tarpit — **stated as its own line item** (false-positive control; operational and
   sometimes contractual).
5. **Emit for external consumption**: decoy-hit and ban events are emitted in the ECS/OCSF shape
   (PLAN-19) so an external CrowdSec bouncer / SIEM can consume them — the gateway does not itself do
   cross-node reputation.
6. **Architecture documentation + ADR** — **stated as its own named line item**. Document the NEW
   deception subsystem (the decoy stage, the non-blocking-tarpit mechanism, the strike-ban on PLAN-18's
   substrate) in `doc/architecture.adoc`, and record an **ADR** for the in-process deception approach
   (why in-pipeline vs external, the non-blocking-tarpit decision, and the legal/operational review it
   warrants). Plus **three-layer documentation** (`configuration.adoc` decoy list / tarpit / ban /
   allowlist config, `doc/user/`, `doc/development/`), including the risk notes: decoy-list maintenance,
   the crawler-allowlist requirement, and the legal/operational review a deception layer warrants.

## Claim Labels

Corroborated against HEAD 3f60d49, 2026-07-25.

- OBSERVED: no deception layer exists — a grep for honeypot/decoy/tarpit across
  `api-sheriff/src/main/java` returns nothing; probes of `/admin` etc. currently get a plain
  `NO_ROUTE_MATCHED` 404.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: grep honeypot|decoy|tarpit across api-sheriff/src/main/java still zero hits at 05f6ee3; NO_ROUTE_MATCHED still live
- OBSERVED: the non-blocking primitive is available — the edge already uses Vert.x
  timers/`runOnContext` (`edge/GatewayEdgeRoute.java` renders on `ctx.vertx().runOnContext`), so a
  timer-based tarpit fits the existing async model.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: GatewayEdgeRoute.java setTimer:646, cancelTimer:670/678/686, 11 runOnContext sites; 2f4254d3 touched WebSocketRelayStage only
- HYPOTHESIS: PLAN-18's per-client substrate (D3 there) exposes a strike/ban API this plan can escalate
  through. Confirm/refute at the substrate PLAN-18 ships § its ban API (verify-at-outline) — if it only
  counts and does not ban, this plan adds the ban action on top rather than duplicating the counter.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: api-sheriff-0-2-0 queue/PLAN-V02-06.json still staged, never launched -- substrate unshipped
- HYPOTHESIS: PLAN-19's ECS/OCSF formatter is reusable for the decoy/ban emit. Confirm/refute at what
  PLAN-19 ships § its emit formatter (verify-at-outline) — reuse it, do not fork a second shape.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: api-sheriff-0-2-0 queue/PLAN-V02-07.json still staged, never launched -- ECS/OCSF formatter unshipped
- Verify-first clause: confirm the decoy responses cannot be distinguished (status, timing, headers)
  from a genuine route-miss, else the honeypot becomes its own fingerprint — verify against the landed
  uniform-404 behaviour (PLAN-18), not this spec's assertion.
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: grep uniform-404 across src+doc still zero hits; premise not landed, nothing to verify against

## Expected Surface

- CORRECTED 2026-09-24 (cleanup): the four main-source entries were package-relative
  (pipeline/**, edge/...) and did not resolve, so the disjointness gate saw no production-code
  surface at all. Rewritten as full repo paths, one per bullet, path on the bullet's first line
  (continuation lines are not read by the surface parser).
- OBSERVED absence → NEW: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/**` — decoy/deception stage
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRoute.java` — tarpit timer point
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/events/EventType.java` — decoy-hit / ban events
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/**` — the decoy config model
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json` — schema for the decoy config block
- OBSERVED: `doc/architecture.adoc`
- OBSERVED: `doc/configuration.adoc`
- OBSERVED: `doc/user/`
- OBSERVED: `doc/development/`
- OBSERVED: `api-sheriff/src/test/**`
- Narrative (unchanged): decoy config covers bad-path list, tarpit delay, ban thresholds, crawler
  allowlist; the tarpit uses `setTimer`/`runOnContext` on the edge route; the four doc targets are the
  ones Deliverable 6 names.
- OBSERVED (reused, not duplicated, not this plan's own surface): `PLAN-V02-06`'s per-client
  strike-ban substrate and `PLAN-V02-07`'s emit formatter — both still staged/unlanded as of this
  re-grounding; do not start this plan until they ship, per the epic's Cross-Epic Dependency

## Dependencies and Sequencing

- Depends on: **PLAN-18 (substrate) and PLAN-19 (emit shape) — hard.** Sequence PLAN-18 → PLAN-19 →
  PLAN-20. Last in the WS-05 chain.
- Overlaps with: PLAN-18 / PLAN-19 (shared substrate + event system) — strictly sequential, never
  concurrent within WS-05.
- **Split-guard note:** 6 deliverables — at the presumption boundary. Proceeding as one plan is
  provisional; **re-evaluate at emit** — a natural split is (decoy-stage + tarpit) vs (strike-ban +
  allowlist + emit). Record the split-or-proceed decision at the emit, not now.

## Standing Conventions

Three-layer docs, Sonar zero-findings, named line items, integration tests in the same plan.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-3-0/plans/PLAN-V03-01-honeypot-deception.md" plan_id=plan-v03-01-honeypot-deception
```

## Write-Boundary

Touches only its own repository source and tests; creates/edits NO file under `.plan/orchestrator/`.

## Status Trail

- plan_marshall_plan_id: {set at launch}
- pr: {set when the PR opens}
- landing: {set when landings/PLAN-20.md is recorded}
