# PLAN-14: Correct the HealthProbe Javadoc's unreachable port-override remedy

> ⛔ **SUPERSEDED 2026-09-04 by `PLAN-15-trusted-proxy-breadth-and-probe-doc.md`, which carries this
> plan's sole deliverable as its deliverable 4.** Retained as the audit record of why it was retired;
> a superseded spec is never deleted.
>
> **Why:** the deliverable is one Javadoc sentence, and its entire cost is the gate-and-ship cycle.
> Measured baseline: PLAN-10's `6-finalize` phase alone consumed **2,338,588 tokens across 375 tool
> uses** — 65% of that plan's total — and PLAN-10 was documentation-only, so it ran **no quality gate
> at all**. This footprint is gate-requiring (one `.java` file), so its finalize is strictly more
> expensive than that baseline, against one changed comment: roughly **40:1 machinery-to-work**.
>
> **Why merged rather than held:** WS-01 has no remaining staged work — PLAN-01 and PLAN-10 both
> shipped — so "wait for a WS-01 companion" was indistinguishable from "indefinitely", which this
> spec's own Dependencies section warned against. Issue #256 (`ConfigValidator.BROAD_PREFIX_IPV4`)
> is the same footprint class, unowned, surface-disjoint, and carries real design content, so it
> earns the gate cycle this deliverable cannot earn alone.
>
> **The one cost accepted:** deliverable 4 is a WS-01 concern living in a WS-05 plan. Recorded in
> PLAN-15's Objective rather than hidden.


epic: deployment-configurability
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Staged 2026-09-04 from PLAN-10's landing (owed follow-up 1), which could not carry it: one
> `.java` file makes the whole commit gate-requiring and would have broken that plan's
> documentation-only skip.

## Objective

`HealthProbe.java`'s class Javadoc tells a deployment that overrides `quarkus.management.port` to
"override the image's `HEALTHCHECK` to match". That remedy does not exist, and ADR-0039 — merged
2026-09-04 as `337af0d` — now says so explicitly. This plan brings the source comment into agreement
with the ADR: replace the unreachable remedy with the real consequence (the probe port is compiled
in, so moving the management port makes the baked check fail closed until the image is rebuilt with
a matching constant).

⛔ **This is a correctness fix, not a tidy-up.** The current state is the worse of the two possible
disagreements: a reader who trusts the code comment over the ADR gets an instruction that cannot
work. It is deliberately small and is a **good candidate to batch** with any other WS-01 or
`api-sheriff` source work that lands first — see Dependencies and Sequencing.

## Deliverables

1. Correct the class Javadoc in
   `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/HealthProbe.java` so the port-override
   sentence names an image rebuild as the only remedy, matching ADR-0039's corrected wording at both
   of its sites. Preserve the fail-closed framing, which was correct in both documents.

## Claim Labels

- OBSERVED: the Javadoc carries the unreachable remedy verbatim — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/HealthProbe.java`, class Javadoc: *"A
  deployment that overrides `quarkus.management.port` must override the image's `HEALTHCHECK` to
  match; otherwise the probe measures a port nothing is bound to and the container never reports
  healthy."*
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: HealthProbe.java's class Javadoc now says the only remedy is an image rebuild; unreachable override sentence gone, fixed by successor PLAN-15
- OBSERVED: the remedy is unreachable because the probe port is a compile-time constant — read at
  `HealthProbe.java` § `PROBE_PORT` (`private static final int PROBE_PORT = 9000`, line 67) and
  § `probe()` (line 111, `socket.connect(new InetSocketAddress("127.0.0.1", PROBE_PORT), …)`), which
  takes no port argument and reads no configuration.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: PROBE_PORT still private static final int = 9000, probe() still takes no port argument
- OBSERVED: the distroless image carries no executable other than `/app/application`, so an
  overridden `HEALTHCHECK` can only re-invoke the same binary and still probes 9000 — read at
  `api-sheriff/src/main/docker/Dockerfile.native`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: Dockerfile.native still ships no executable other than /app/application
- OBSERVED: ADR-0039 already carries the corrected wording at both of its sites — read at
  `doc/adr/0039-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc`,
  merged as `337af0d`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ADR-0039 still carries the corrected wording at both sites
- HYPOTHESIS: no OTHER site in the repository repeats the same claim — confirm/refute by a repo-wide
  search for `HEALTHCHECK` near `management.port` at outline (verify-at-outline). ⚠ An asserted
  absence, so it is verified exactly as an asserted presence: if a third site exists it joins
  deliverable 1 rather than becoming a second plan.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed no other site repeats the unreachable override-HEALTHCHECK claim
- Verify-first clause: re-read the Javadoc at HEAD before scoping. PLAN-13 is in finalize and may
  land `api-sheriff` test-tree changes first; that cannot touch this file, but HEAD will have moved.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/HealthProbe.java` — the class
  Javadoc, the sole edit

⛔ **One file, and the declaration is deliberately exact.** This epic has now measured three distinct
disjointness-gate blind spots, two of them caused by declarations that were broader or vaguer than
the work. A single named file is the strongest declaration available; do not widen it to
`gateway/` for convenience.

⚠ **Gate-requiring despite its size.** One `.java` file makes the whole commit subject to the
quality gate and full verify per CLAUDE.md. That is the entire reason this is a separate plan rather
than a hunk in PR #257, and it must not be re-litigated as "too small to gate".

## Dependencies and Sequencing

- **Depends on: none.** ADR-0039 has landed; this plan records agreement with a decision already
  merged.
- ✅ **Surface-disjoint from everything currently staged or running.** No other spec in this corpus
  declares `HealthProbe.java`; PLAN-13 touches only the `api-sheriff` test tree.
- ⚠ **Batching is preferred to solo emission if an opportunity exists.** A one-Javadoc plan pays a
  full gate-and-PR cycle for a comment fix. If a later WS-01 or `api-sheriff` source plan is staged,
  folding this deliverable into it is the better outcome — fold under the same-act rule, updating
  that spec's Expected Surface in the same edit. Emitting it solo is correct only if no such plan
  appears; the defect is real and must not sit indefinitely waiting for a companion.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-14-healthprobe-javadoc-port-claim.md"
```
