# PLAN-20: Pin the fifth outbound leg and re-sweep the egress inventory

epic: deployment-configurability
workstream: WS-03

> ⛔ **SUPERSEDED 2026-09-15 by `PLAN-25-configuration-security-hardening.md`, which carries this plan's scope as its deliverables 1-4, 9-11.** Retained as
> the audit record of why it was retired; a superseded spec is never deleted. **Do not emit.**
> **Why:** operator decision at the 2026-09-15 corpus revisit (`origin/main` `a2969b9`) — up to 12
> deliverables per plan are authorized, and this spec collided on surface with the others merged
> into the successor, so they could only have run sequentially as separate PR cycles. Its queue row
> is `parked` because the queue status vocabulary has no `superseded` value.
>
> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-10 from the epic coverage audit.** The ledger has carried this finding since
> 2026-09-08 marked *"wants its own plan"*; no plan was ever staged for it. This is that plan.

## Objective

The gateway holds **two opposite postures on the same question**, and the weaker one guards the
higher-value secret. `TokenValidatorProducer` refuses to rely on an upstream library default for the
JWKS leg; `BffRuntimeProducer` relies on exactly that default for the leg that carries the
`client_secret`. Settle the posture once, apply it everywhere, and re-derive the egress inventory
rather than trusting it.

⛔ **The inventory is the real subject.** PLAN-03's security audit found a *fourth* egress leg that
five documentation sites denied existed. PLAN-04 then found a *fifth*. **The egress inventory has
been wrong twice in a row**, so "we have enumerated the egress paths" is a claim this epic must stop
accepting without a fresh, mechanical sweep.

## Deliverables

1. **Pin the BFF client leg.** `BffRuntimeProducer:208` builds `ClientConfiguration` with **neither**
   `verifyHostname` nor `sslContext`. It is dialled by `DiscoveryResolver`, `TokenEndpointClient` and
   `RefreshFlow` — discovery, code exchange **and** refresh — and the code-exchange leg carries the
   `client_secret`. Bind it explicitly to the same `egress_tls` surface PLAN-03/PLAN-04 landed.
   ⛔ **Do not invent a third vocabulary.** `egress_tls.upstream_verify_hostname` (ADR-0040) and
   `egress_tls.jwks_verify_hostname` (ADR-0041) already exist with boot records `ApiSheriff-118` /
   `-120`. Decide whether this leg joins one of them or earns its own named knob, and record why.
   ⚠ **`upstream_tls_profile` REPLACES the JVM default trust store** (`ApiSheriff-119`) — if this leg
   binds a profile, an operator mounting a corporate CA loses public trust on the IdP dial. State the
   consequence wherever the knob is documented.
2. **Re-derive the egress inventory mechanically, and make it checkable.** Enumerate every outbound
   HTTPS dial in the codebase from the source, not from the existing documentation. ⛔ **The output is
   not a list in a document** — this epic has recorded *seven* instances of a stated rule with no
   mechanism, and a hand-maintained egress inventory is that shape exactly. Ship a guard: an
   ArchUnit rule, a contract test, or a build-time check that fails when a new outbound client is
   constructed without an explicit TLS posture. **Record the decision if you conclude no guard is
   feasible**, with the reason.
3. **Settle the JWKS knob's deployment-activation gap.** The knob is proven at **unit level only**,
   by an explicit operator decision recorded at the time. Either add the deployment-level activation
   proof (an integration instance exercising it end to end, following the established
   `sheriff-config-*` pattern) or record why unit coverage is sufficient here when this epic's own
   standing rule is *a key that parses is not a key that acts*. ⛔ **Do not leave it implied.**
4. **Reconcile the documentation to the re-derived inventory.** Wherever the egress paths are
   enumerated, make the count and the set match deliverable 2's output. ⚠ Five documentation sites
   previously asserted a leg set that was wrong; the correction is worth nothing if the next reader
   cannot tell which sites were re-derived and which were merely left alone.

5. **Ship the guard issue #269 asks for — the same shape, a different inventory.**
   ⛔ **Folded in 2026-09-10 by the epic coverage audit**, which found it unowned and recognised it as
   deliverable 2's problem in a second place. `doc/configuration.adoc`'s array-key inventory is
   hand-maintained against two schemas while **stating that it "is checked against those files."**
   It is not. Pre-existing, surfaced by PLAN-03, issue filed.
   ⚠ **Note the shape**: this is lesson `2026-09-02-22-002` — *"A comment naming its own guard is a
   claim, not a guard — open the guard and look"* — recurring in **documentation** rather than code,
   which is where it is harder to notice because no build ever reads it.
   ✅ **Reuse deliverable 2's decision.** If that deliverable lands a contract-test mechanism for the
   egress inventory, this inventory should be guarded the same way rather than by a second, different
   mechanism. If deliverable 2 concludes no guard is feasible, the honest fix here is to **delete the
   claim of checking** rather than leave an unbacked assertion standing.

## Claim Labels

- OBSERVED: `BffRuntimeProducer.java:208` constructs `ClientConfiguration` with neither
  `verifyHostname` nor `sslContext`. Re-verified by direct read at HEAD `990aebf` during the
  2026-09-10 coverage audit.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: BffRuntimeProducer.java now reads egressTls.oidcVerifyHostname() and pins the BFF client leg
- OBSERVED: the opposite posture is taken for the JWKS leg at `TokenValidatorProducer:266`/`:271`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: TokenValidatorProducer still calls .verifyHostname(...) unconditionally on the JWKS leg
- OBSERVED: `egress_tls.upstream_verify_hostname` (ADR-0040), `egress_tls.jwks_verify_hostname`
  (ADR-0041) and `egress_tls.upstream_tls_profile` (`ApiSheriff-119`) all exist at HEAD; `doc/adr/`
  runs to `0042`, so `0043` is the next free ordinal should this plan owe an ADR.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: doc/adr/ now runs to 0047; 0043 is nowhere close to being next free ordinal
- ⛔ HYPOTHESIS: the leg is secure today **only** via cui-http's `@Builder.Default true` — confirm at
  outline by reading the library version actually resolved, not the one documented. If that default
  ever changed, this is a live vulnerability rather than a posture inconsistency.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: the BFF leg now explicitly sets verifyHostname from egressTls.oidcVerifyHostname rather than any cui-http builder default
- ⛔ HYPOTHESIS: the egress inventory is still incomplete — a sixth leg exists. Two consecutive
  sweeps found one more each; treat "five" as the current lower bound, not the answer.
  - verdict: unverifiable | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: whether a sixth egress leg beyond the five now-pinned ones exists cannot be settled without re-running the EgressTlsPostureArchTest sweep
- Verify-first clause: re-read every line citation at HEAD before editing. All citations here are
  `990aebf`-relative.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/TokenValidatorProducer.java`
- OBSERVED: `api-sheriff/src/main/resources/schema/gateway.schema.json`
- OBSERVED: `doc/configuration.adoc`
- OBSERVED: `doc/LogMessages.adoc`
- OBSERVED: `doc/security-threat-model.adoc`
- OBSERVED: `api-sheriff/src/test/` — ⛔ **contended, see Dependencies**; deliverables 2 and 5 land guards as tests

⛔ **Named files only** — this epic has measured a directory declaration going wrong three times.

## Dependencies and Sequencing

- ⚠ **Overlaps PLAN-09 and PLAN-19 on `doc/configuration.adoc`.** Sequence against whichever is live;
  do not run concurrently with either.
- ✅ **Independent of PLAN-17 and PLAN-21** — different files, different subject. The BFF *client*
  leg is not the BFF *cookie* codec.
- ⛔ **Contends with PLAN-23 on `api-sheriff/src/test/**`**, which declares that tree wholesale; the
  matcher compares paths for equality so it will report **no** collision. **Not concurrent.**
- ⛔ **Security-bearing.** If deliverable 1's hypothesis resolves to "the default is not `true` in the
  resolved version", stop and escalate rather than continuing through the deliverable list.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-20-egress-leg-pinning-and-sweep.md"
```
