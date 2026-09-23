# Landing Analysis: PLAN-03 — Configurable Upstream Hostname Verification

epic: deployment-configurability
workstream: WS-03
pr: [#268](https://github.com/cuioss/API-Sheriff/pull/268) — merged as `a8c9834` (squash)

> **The highest-consequence landing in this epic**, not because of what it shipped but because of
> what it settled downstream: it carried the cui-http 3.0 move, which refuted PLAN-04's mechanism.
> Analysed by paste — the inbox was empty, and that is a **defect**, not an abstention.

## Deliverable Fidelity vs Spec

Spec carried **six** deliverables; the plan shipped **seven**. The growth is legitimate and named.

| Deliverable | Verdict |
|---|---|
| 1. ADR-0040 recording the egress-TLS decisions | added-unplanned, correct — the spec's deliverable 1 was "research and record the mechanism"; an ADR is the right carrier and `doc/adr/` now ends `0040-Upstream_hostname_verification_is_a_global_egress-TLS_knob_bound_at_client_construction.adoc` |
| 2. Global `egress_tls` config surface, three keys | shipped — the spec left global-vs-per-upstream open and required the choice be justified; **global** was taken |
| 3. Deployment-bound upstream trust-profile resolver | added-unplanned — ADR-0025-consistent (material stays deployment-supplied) |
| 4. Bind at every egress client-construction site | shipped-as-specified, **and widened** — see below |
| 5. Matched positive/negative/**chain-trust** control | shipped-expanded; the spec asked for positive/negative, the plan added a chain-trust leg |
| 6. Documentation with explicit security framing | shipped-as-specified |
| 7. cui-http 3.0 override, absorbed atomically | ✅ **the folded deliverable, shipped exactly as specified** — property override, not a parent bump, with the three source edits in one commit |

### ⛔ The security audit refuted an asserted absence — five documentation sites were wrong

The audit found a **fourth https egress leg**: `UpstreamAssetSource`'s JDK client, which **five
documentation sites claimed did not exist**. Corrected rather than papered over; ADR-0040 records the
`SSLParameters` seam as deliberately out of scope.

⛔ **This is the verify-first contract's own headline case.** An asserted *absence* — "there are three
egress paths" — is the higher-risk half precisely because nothing trips over it: the knob would have
covered three of four legs and the uncovered one would have failed differently, which is the exact
failure mode the spec's deliverable 3 warned about in the abstract (*"a knob that covers two of three
paths is worse than none"*). It was found by an audit, not by the spec's own verification.

### ✅ CodeRabbit caught the control proving nothing

The TLS backends' healthcheck ran only `nginx -t`, which never tests connection acceptance — so a
**refused connection would have satisfied the verify-on leg's expected 502 for entirely the wrong
reason**. That is the control this plan exists to build, quietly passing on a false premise. Fixed
with a real TCP probe. ⚠ A green control is not a working control; this one needed an external
reviewer to notice.

## Metrics and Anomalies

- **17h56m wall / 3h30m worked / ~6.7M tokens** — the epic's largest plan by every measure.
- Security audit: 3 findings, 2 fixed, 1 accepted. Self-review: 1 defect, round 2 clean.
- ⚠ **`TlsEdgeProducerTest` and `SniFrontListenerTest` each failed once locally** on port-collision
  control preconditions **under load average 119**, passing on re-run and in CI. Recorded as a defect
  below — these are residual sites of the class PR #255 addressed.

## Routing and Merge Behavior

- CodeRabbit: 5 findings fixed and resolved, re-review clean. Sonar: 0 new-code issues, confirmed.
- Merged as `a8c9834` (squash); worktree removed, main clean.
- ⛔ **`emit-landing` reported `outcome=skipped`** — see the defect below. This landing reached the
  epic only because the operator pasted it.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `268`, `landing`, `plan_marshall_plan_id` stamped
- [x] ⛔ **PLAN-04 RE-SCOPED — its mechanism is refuted.** Claim 5 stamped
      `contradicted | checked_at: a8c9834 | rescoped: yes`; the spec now opens with the refutation
      block. See Follow-Ups
- [x] **Watch retired** — the cui-http re-grounding trigger fired and is discharged
- [x] Four defects opened (emit-landing recurrence, #269, the SVG read-back, the residual loopback sites)
- [x] **ADR-0040 consumed the ordinal** — PLAN-07 and PLAN-09 must re-resolve; `0041` is next free

## Follow-Ups

1. ⛔ **PLAN-04's mechanism is deleted and replaced — the single largest consequence of this landing.**
   cui-http 3.0 ships `HttpHandlerBuilder#verifyHostname(boolean)`, default `true`, skipping *only*
   the SAN/CN match while chain trust, validity and algorithm constraints stay enforced, with a WARN
   (`HTTP-116`) per relaxed handler. `cuioss/cui-http#165` landed. **The hand-rolled delegating
   `X509ExtendedTrustManager` is retired and the TokenSheriff coordination is dissolved** — neither
   repository needs to write that class now.
   ⛔ **But the same read found a hard constraint that becomes PLAN-04's central design question**:
   combining `verifyHostname(false)` with a **caller-supplied** `sslContext` is *rejected at `build()`
   with `IllegalArgumentException`*, and `JwksTrustProfileResolver.resolve(:119)` returns exactly such
   a context for any issuer declaring `jwks.tls_profile`. The two features are mutually exclusive as
   the API stands — and the corporate-internal-CA deployment most likely to need relaxation is
   precisely the one that supplies a profile. Three candidate resolutions are recorded in the spec.
2. ⛔ **`emit-landing` skipped for an orchestrated plan — third occurrence, and the two successes did
   not mean it was fixed.** See the defect entry.
3. ⚠ **Issue #269** — `configuration.adoc`'s array-key inventory is hand-maintained against two
   schemas while claiming it *"is checked against those files."* Pre-existing; needs a contract test.
   ⚠ Note the shape: a **comment asserting a guard that does not exist**, which is lesson
   `2026-09-02-22-002` (*"A comment naming its own guard is a claim, not a guard"*) recurring in
   documentation rather than code.
4. ⚠ **The topology SVG read-back is owed.** Geometry was placed by coordinate arithmetic with no
   renderer available, so overlap and both themes are unverified. An explicit WARNING block sits in
   the doc until someone renders it. ✅ Correctly disclosed in-place rather than claimed.
