# WS-08: Configuration Security Hardening

epic: deployment-configurability

## Charter

Opened 2026-09-15 by operator decision at the corpus revisit, to own the merged successor of three
plans that were cut into three different workstreams (WS-03 hostname verification, WS-04 refresh-token
reliability, WS-05 forwarded-trust configurability) but shared one mechanism and one file set:
**configuration or construction paths through which an operator, or an unreviewed environment variable,
can silently give up a security guarantee the product states elsewhere.** The workstream closes when
every such path either refuses at boot, emits a boot record naming what was given up, or is pinned
explicitly and guarded against regression — and each decision is recorded.

## Scope

- In scope: the TLS posture of every outbound client the gateway constructs, and a guard that keeps new
  ones explicit; validation of the session-cookie name and the cookie-size budget; the `trusted_proxies`
  breadth threshold and its warn-versus-reject decision; the ADR(s) those decisions owe; the issues
  #256 and #269 they close.
- Out of scope: the `egress_tls` vocabulary itself (settled by PLAN-03/PLAN-04 and ADR-0040/0041 — this
  workstream binds to it); the inbound TLS material contract (WS-06); the cookie codec, compression and
  budget constant (settled by PLAN-16/PLAN-17 and ADR-0043); operator-facing TLS scenario documentation
  (PLAN-24, WS-06).

## Plans

| Plan | Status | Notes |
|------|--------|-------|
| PLAN-25-configuration-security-hardening | staged | Merges PLAN-20 (WS-03), PLAN-21 (WS-04), PLAN-22 (WS-05); 11 deliverables |

## Sequencing and Surface Notes

- PLAN-25 is surface-disjoint from PLAN-24 and PLAN-18 by named-file declaration; the three form one
  parallel round.
- PLAN-23 (WS-07) declares `api-sheriff/src/test/` wholesale and runs after PLAN-25.
- ⚠ Soft coupling with PLAN-24: a new BFF-client-leg knob belongs in PLAN-24's TLS scenario guide and
  is folded in by the orchestrator at PLAN-25's landing, not by editing the guide inside PLAN-25.
- ⚠ Security-bearing: every relaxation or refusal owes a threat-model statement and a boot record.
