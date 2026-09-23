# Landing Analysis: PLAN-25 — Configuration security hardening (BFF egress TLS, cookie name, trusted-proxy breadth)

epic: deployment-configurability
workstream: WS-08
pr: [#306](https://github.com/cuioss/API-Sheriff/pull/306) — merged through the merge queue as squash `7ba9734`, 2026-09-16

> Reconciled 2026-09-16 from the operator's landing paste. ⚠ **No inbox message**: this plan ran with
> `lessons-capture` and `emit-landing` on lane `off`, so the epic's OUTBOX channel carried nothing and the
> paste is the only narrative. Every claim below names what corroborated it against `c74f5d2`.

## Deliverable Fidelity vs Spec

The spec carried 11 deliverables; the outline re-cut them to 8 after four operator design answers recorded
in the plan's decision log (`bff_knob=A`, `proxy_breadth=A`, `jwks_activation=A`, `jdk_hostname_property=A`).

| Deliverable (spec) | Verdict | Evidence checked at `c74f5d2` |
|---|---|---|
| 1. Pin the BFF client leg | ✅ shipped, **as new peer keys** | `gateway.schema.json` gains `egress_tls.oidc_verify_hostname` (default `true`) and `oidc_tls_profile`; `BffRuntimeProducer` +121. Operator answer A: peer keys rather than joining an existing knob — the "no third vocabulary" constraint honoured |
| 2. Egress inventory + guard | ✅ shipped | `arch/EgressTlsPostureArchTest.java` (+512) with matched `specimen/PinnedEgressClientSpecimen` and `UnpinnedEgressClientSpecimen` — a positive and negative control, not a one-sided rule |
| 3. JWKS deployment-activation gap | ✅ **settled as recorded rationale, no IT instance** | Operator answer A (decision.log `54fbfd`/`e828b7`): unit-level proof recorded. `JwksTrustProfileResolverTest` (+83) and `SanMismatchedJwksServer` (+86) are that proof. The spec licensed this arm explicitly |
| 4. #269 array-key inventory guard | ✅ shipped | `config/DocumentedSetsContractTest.java` (+868) — the contract test the issue asked for; #269 closed |
| 5. Validate `session.cookie_name` | ✅ shipped — **refusal**, as the spec preferred | `ConfigValidator` `HOST_COOKIE_PREFIX = "__Host-"` (`:158`) with the guarantee stated at `:113`/`:154`; `ConfigValidatorTest` +152 |
| 6. `max_cookie_size` residual | ✅ closed out | No re-implementation; schema keeps the validator as sole enforcing authority, as the spec required |
| 7. Trusted-proxy posture | ✅ shipped — **warn kept**, no second threshold | Operator answer A: `BROAD_PREFIX_IPV4` stays `16` (`:138`), the un-warned residual re-stated, redundant threshold tests removed; #256 closed |
| 8. ADR(s) | ✅ shipped — **two** | `doc/adr/0044-Trusted-proxy_breadth…` (+247) and `doc/adr/0045-The_BFF_OIDC_back-channel_posture…` (+376); `0040` amended |
| 9. Reversion proofs | ✅ claimed by the paste for all five surfaces; ArchUnit specimens are the durable half | — |
| 10. Documentation | ✅ shipped | `configuration.adoc` +350, `security-threat-model.adoc` +231, `bff-cookie.adoc` +49, `bff-session.adoc` +48, `tls-edge.adoc` +17, `LogMessages.adoc`, `declared-limit-assertion-coverage.adoc` +47 |
| 11. Close #256 / #269 | ✅ both `state: closed`, corroborated through the CI abstraction | — |

### Surface fidelity — expansion detected, and one declaration that was wrong

Declared 20, realized 30: **15 added, 5 missing**.

- Added and legitimate: the ArchUnit specimens, `JwksTrustProfileResolver` (+81) and its test, `ConfigLoader`
  (+24), `DocumentedSetsContractTest`, `ConfigLoaderTest`, `GatewayEdgeRouteTest`, the two new ADRs and the
  amended `0040`, `doc/user/bff-session.adoc`, `doc/user/tls-edge.adoc`.
- ⛔ **`doc/adr/0044-trusted-proxies-breadth-threshold.adoc` was declared as an exact filename and the real
  file is `0044-Trusted-proxy_breadth_is_warned_beyond…`.** A named-file declaration of a file that does not
  exist yet is a guess; the corpus convention is the long descriptive ADR title. Recorded for future specs.
- Missing because the operator's answers removed them: `TokenValidatorProducer.java`,
  `integration-tests/docker-compose.yml`, `sheriff-config-jwks-relaxed/gateway.yaml`,
  `integration-test-topology.adoc` — all four were the deliverable-3 IT instance that answer A declined.
- ⚠ **`doc/user/tls-edge.adoc` was PLAN-24's file**, edited here four commits after PLAN-24 landed. No
  conflict, but it is the second plan in two days to edit an undeclared operator-facing TLS doc.

## Metrics and Anomalies

- Tokens **7,487,113**; wall **22h56m**, worked 4h44m, idle **18h11m** — 6-finalize alone reports 16h6m wall
  against 1h15m worked. Six finalize iterations, the sixth admitted by explicit operator override.
- The headline token was `[LOOP_BACK]`, not `[MERGED]` — template precedence, not a failure.
- `pre-push-quality-gate` recorded **module-tests DEGRADED** (no whole-tree module-tests canonical in this
  project) and coverage skipped once with a logged rationale. Carried honestly, not papered over.
- `prune-local-and-remote-ref` returned `branch_delete_failed` — the same defect this epic routed upstream as
  truthful-signals `…-016` (`-012` in its bundle). Recovered by hand; both refs confirmed gone.
- Sonar: 2 new-code issues confirmed, 2 filed for triage.

## Routing and Merge Behavior

- Review: `automatic-review` reports 0 comments — 1 bot reviewed, 1 empty, 1 refused-structural.
- CI/merge: `ci checks status --pr-number 306` → `overall_status: success`, 32 checks. Merge queue, squash.
- ⚠ **The `main` Maven Build for `7ba9734` is NOT verified** — no commit-addressed CI read exists
  (truthful-signals `-013`). Same standing Watch as `fb65222`.
- No rebase conflict with PLAN-18, which landed from the same round two commits later.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` 306; `landing` `landings/PLAN-25.md`; `plan_marshall_plan_id` stamped
- [x] Open Defect "`session.cookie_name` ENTIRELY UNVALIDATED" retired — refusal shipped
- [x] Open Defect "reuse detection claimed COVERED" left open — PLAN-26 owns it
- [x] PLAN-24-reported residues re-checked: `bff-cookie.adoc` logout leg **fixed here**; the compose-sample
      trusted-proxy comment is **not reproducible** at `c74f5d2` (the only `applyRegeneratedForwarding`
      mention is a correct passage at `tls-edge.adoc:139`, authored by PLAN-08)
- [x] Watch "PLAN-25 landing → scenario-guide fold" **fired**: the two new peer keys are documented in
      `configuration.adoc` (24 hits) and `tls-edge.adoc` (1) but **not** in `doc/user/tls-scenarios.adoc`
      (0) — folded into PLAN-26 deliverable 12, with `tls-scenarios.adoc` added to its Expected Surface
- [x] PLAN-26's ADR ordinal corrected: `0045` is consumed, next free is `0046`
- [x] START-HERE and Ordered Queue regenerated

## Follow-Ups

- PLAN-26 now carries the scenario-guide fold for `oidc_verify_hostname` / `oidc_tls_profile`.
- The declared-vs-real ADR filename mismatch is a spec-authoring note: declare the ordinal, not a guessed
  descriptive filename.
- Two Sonar issues filed for triage during this run are outside the epic ledger; they ride Sonar's own queue.
