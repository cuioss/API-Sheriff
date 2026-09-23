# PLAN-08: Plain-HTTP Termination Mode (Ingress / Sidecar Deployments)

epic: deployment-configurability
workstream: WS-06

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

A large class of deployments terminates TLS *before* the gateway — an ingress controller, a service
mesh sidecar, a cloud load balancer — and wants the gateway to serve plain HTTP on its own port
inside a trusted network boundary. **API Sheriff has no supported shape for that today.**
`quarkus.http.insecure-requests=redirect` ships as the default, no shipped configuration uses any
other value, the operator sample supplies certificates unconditionally, and the word *sidecar*
appears nowhere in the documentation. An operator who simply omits the certificate variables does not
get plain HTTP — they get redirects to an HTTPS listener that was never started.

Make it a **first-class, named, tested deployment mode** rather than an undocumented configuration
accident. The gateway already treats a plain-HTTP *management* port as a legitimate deployment behind
a trusted boundary and says so loudly once at boot; this plan extends the same reasoning, and the same
honesty, to the application port.

⚠ **This is a security-relevant relaxation and must read like one.** The mode removes the gateway's
own transport protection and makes the surrounding topology load-bearing. Secure-by-default is
preserved by making the mode explicit and opt-in — never by making it reachable through omission.

## ⛔ RE-SCOPED 2026-09-10 — PLAN-07 SHIPPED MOST OF THIS PLAN. READ FIRST.

PR #283 (`a30fe6f`) did not merely refuse an undeclared plain-HTTP boot — **it named the mode,
settled its trigger, shipped its boot audit and documented it as a deployment posture.** Verified at
HEAD:

| This plan's deliverable | State after PLAN-07 |
|---|---|
| 1. Name the mode and settle its trigger | ⛔ **DONE.** `QUARKUS_HTTP_INSECURE_REQUESTS=enabled`, with the governing principle stated in `doc/user/tls-edge.adoc:60` — *"Plain HTTP is something you declare, never something the gateway infers"* — and inference explicitly rejected in the ADR-0025 seam amendment |
| 2. Implement the mode and its boot audit | ⛔ **DONE.** `ApiSheriff-121` (`doc/LogMessages.adoc:78`) reports the **resolved** listener state, `WARN` and never a refusal |
| 3. Settle the interaction with every TLS-adjacent feature | ⚠ **PARTIAL.** A second refusal already covers a named-but-empty TLS bucket; the rest is unverified |
| 4. Ship the mode as a runnable example and prove it | ✅ **STILL OWED — and it is now this plan's centre of gravity.** `integration-tests/src/main/docker/sheriff-config-plain-http/` **does not exist**, and `INSECURE_REQUESTS` appears only twice in `docker-compose.yml`. There is no plain-HTTP instance and no end-to-end proof |
| 5. Document it as a deployment topology | ⛔ **SUBSTANTIALLY DONE.** `doc/user/tls-edge.adoc:58-95` is that topology, boundary stated (*"Expose that port only behind such a boundary"*), with both ways out of the refusal enumerated |

### What this plan is now

1. **Deliverable 4 — the runnable instance and its end-to-end proof.** Nothing else has shipped it,
   and a mode with no instance in the integration stack is a mode nobody exercises.
2. **The remainder of deliverable 3**, scoped to what the shipped refusals do *not* already settle.
3. **The issue #285 fold** (below), which is independent of all of this.
4. ⛔ **Deliverables 1, 2 and 5 are RETIRED — do not re-ship them.** Re-documenting a posture
   `tls-edge.adoc` already states, or re-announcing a mode `ApiSheriff-121` already reports, is
   duplication. **Extend what is there; do not restate it.**

⚠ **This is the THIRD time in this epic that a landing has overtaken a downstream spec's premise** —
PLAN-03/04's `ApiSheriff-119`/`-120` overtook PLAN-07's outbound half, the cleanup pass caught it, and
now PLAN-07 has overtaken this plan's naming, audit and documentation halves. **The pattern is that
adjacent TLS work keeps landing the general mechanism while the specific plan is still staged**, so
re-read this section against HEAD at outline rather than trusting it.

## Deliverables

1. **Name the mode and settle its trigger.** Decide how a deployment selects it and record why: an
   explicit opt-in key, or the ADR-0025-consistent reading that supplying no certificate material *is*
   the selection. ⛔ **Prefer the explicit trigger.** Selection-by-omission is exactly the shape a
   misconfiguration takes — an operator whose certificate path is wrong or whose secret failed to
   mount gets a silently plain-HTTP gateway instead of a failure — and this plan exists partly to
   remove that failure mode, not to bless it. Bind to PLAN-07's deliverable-3 vocabulary rather than
   inventing a second one.
2. **Implement the mode and its boot audit.** Serve plain HTTP on the application port with no
   redirect and no HTTPS listener. Extend PLAN-07's main-listener audit so the boot log names the
   plain-HTTP application mode as loudly as `ManagementPlainHttpAudit` names the management one — and
   for the same reason: it is legitimate, it must not be blocked, and it must not be silent.
   ⛔ **Refuse the incoherent combinations rather than resolving them** — a plain-HTTP application port
   requested *together with* certificate material, or together with `tls.mtls.enabled`, or together
   with an ADR-0017 `tls.passthrough_sni` block. Each is an operator who believes something the
   runtime is not doing.
3. **Settle the interaction with every TLS-adjacent feature the gateway already has**, and record each
   verdict — this is the deliverable that keeps the mode from being a hole. At minimum: inbound mTLS
   (`tls.mtls`), which cannot function without termination here; the ADR-0017 SNI front listener; the
   `forwarded.trusted_proxies` allow-list, which becomes *load-bearing* in this mode because the real
   client address now arrives only in a header from the terminating hop; and the BFF's cookie
   attributes, where `Secure` cookies over a plain-HTTP hop need an explicit answer.
   ⚠ The `forwarded` interaction is the sharp one — see PLAN-06, which owns that allow-list's
   configurability. **This mode makes correct `trusted_proxies` configuration mandatory rather than
   optional**, and the two plans must land a consistent story.
4. **Ship the mode as a runnable example and prove it.** Add a plain-HTTP variant to
   `deployment/compose-sample/` — the shipped operator-facing artifact — showing a terminating hop in
   front of the gateway, with `forwarded.trusted_proxies` correctly set for that hop. Add an
   integration instance covering the mode end to end, following the established pattern: a
   `sheriff-config-*` directory, a `de.cuioss.sheriff.management-scheme` label, and host-side readiness
   through the same Compose-derived loop, with **no branch on the service name** anywhere.
   ### ⛔ Folded in 2026-09-10 — remove the ten dead `QUARKUS_TLS_DEFAULT_TRUST__STORE_*` pairs

   Operator direction, carrying **issue #285** out of PLAN-07, which correctly declined it as
   out-of-scope there. This plan already declares `integration-tests/docker-compose.yml` and is the
   only live spec that does.

   **What is there**: `QUARKUS_TLS_DEFAULT_TRUST__STORE_P12_PATH` / `_PASSWORD` at
   `integration-tests/docker-compose.yml:340-341, 476-477, 572-573, 675-676, 762-763, 872-873` and
   four further pairs — **20 lines, 10 pairs**, verified by count at HEAD `a30fe6f`.

   ⛔ **They resolve to NOTHING, and this repository's own documentation already says so.**
   `doc/user/environment-variable-overrides.adoc:161-166` carries a WARNING block naming this exact
   mistake: *"Writing `__` where a dash belongs produces a variable that resolves to nothing. The
   runtime decodes `QUARKUS_TLS_TRUST__STORE_P12_PATH` to the malformed
   `quarkus.tls.trust."store.p12.path`, which matches no property, so the material is silently absent
   rather than rejected — the failure surfaces later as an opaque handshake error against an endpoint
   whose anchor was never loaded."* The compose file does precisely what that warning forbids, ten
   times over.

   ⛔ **SETTLE DELETE-VERSUS-CORRECT BEFORE REMOVING ANYTHING. They are not the same fix.**
   - **Surplus** → the truststore was never needed, nothing degraded, and the ten pairs are noise.
     **Delete them.**
   - **Broken** → something *does* need that trust anchor and has been silently running without it,
     exactly as the warning predicts. **Correct the spelling** to the single-underscore form; deleting
     would make a real gap permanent.

   The doc's own sentence gives the test: the failure "surfaces later as an opaque handshake error
   against an endpoint whose anchor was never loaded." **If no such error exists anywhere in the
   integration stack today, they are surplus.** Establish that, do not assume it.

   ✅ **The DOCUMENTATION needs no removal — it is the half that is correct.** The operator's
   direction said "source and documentation"; on inspection the only doc occurrence *is* the warning
   against this spelling. ⚠ What the documentation may deserve is the observation that the repository
   shipped ten instances of the mistake it warns about — decide whether that belongs there.

   ⛔ **THIS IS THE THIRD APPEARANCE OF THE SAME CONVENTION ERROR, which is the argument for a
   guard rather than a third manual removal.** (1) These ten pairs, pre-existing. (2) The new gate
   accepting `KEY__STORE`, caught by CodeRabbit in PR #283 by reading SmallRye 3.17.2 sources.
   (3) PLAN-07's own `security-audit` step **introduced** the same wrong convention into the
   documentation, then corrected it. ⚠ **Consider a mechanical check** — a contract test or build-time
   grep refusing `__` in a `QUARKUS_*` variable except where a quoted profile name genuinely requires
   it (the doc's `QUARKUS_TLS__MY_IDP__TRUST_STORE_P12_PATH` case at `:157`). Removing ten lines
   without one leaves the eleventh free to arrive. Record the decision either way.

5. **Document it as a deployment topology, with the boundary stated.** Cover when the mode is
   appropriate, what it gives up, and what the surrounding topology must guarantee for it to be safe —
   explicitly: the network boundary, the terminating hop's own TLS, and the `trusted_proxies` set that
   makes client-address attribution trustworthy. Add the threat-model entry. ⛔ A reader must not be
   able to reach this mode believing it costs nothing.

## Claim Labels

- OBSERVED: `quarkus.http.insecure-requests=redirect` is the shipped default, declared unconditionally
  and in no profile — read at `api-sheriff/src/main/resources/application.properties:17`, with
  `quarkus.http.port=8080` at `:15` and `quarkus.http.ssl-port=8443` at `:16`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: application.properties:15-17 still declares the unconditional port/ssl-port/insecure-requests default
- OBSERVED: **no shipped configuration uses any other `insecure-requests` value.** A repo-wide grep
  over `integration-tests/`, `deployment/` and `benchmarks/` for an `enabled` setting or the
  `QUARKUS_HTTP_INSECURE__REQUESTS` environment form returns zero hits. Asserted absence; re-derive at
  HEAD.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: integration-tests/docker-compose.yml:1076 now sets QUARKUS_HTTP_INSECURE_REQUESTS=enabled on api-sheriff-no-certificate
- OBSERVED: **the word *sidecar* appears nowhere in `doc/` or `deployment/`**, and the only `ingress`
  hits are ADR-0017/ADR-0023 prose about the gateway's own inbound path and one SVG label — there is no
  ingress-termination or TLS-terminated-upstream topology documented. Asserted absence.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: "sidecar" now appears repeatedly across compose-sample.adoc, tls-scenarios.adoc, docker-compose.plain-http.yml and nginx tls-terminator.conf
- OBSERVED: the shipped operator sample presents certificates as mandatory —
  `deployment/compose-sample/docker-compose.yml:184-190` supplies all four
  `QUARKUS_*_SSL_CERTIFICATE_*` variables unconditionally and mounts `./docker/certificates` at `:199`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: primary docker-compose.yml still supplies certificates unconditionally; plain-HTTP ships as an additive overlay
- OBSERVED: `quarkus.http.insecure-requests` is a **sanctioned ADR-0025 exception**, classified
  deployment-bound rather than policy — *"a port/exposure decision the deployment owns, not TLS
  policy"* — read at
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/SingleSourceTlsContractTest.java:105-108`
  and `doc/configuration.adoc:664-667`. So driving this mode through that key is ADR-consistent and
  needs no new exception.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: SingleSourceTlsContractTest still classifies insecure-requests as a sanctioned ADR-0025 exception
- OBSERVED: the precedent for a legitimate-but-loud plain-HTTP surface already exists.
  `ManagementPlainHttpAudit` warns rather than refuses because *"a plain-HTTP management port behind a
  trusted network boundary is a legitimate deployment, and the gateway must not block it"* — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/ManagementPlainHttpAudit.java:47-53`. The
  integration stack already runs such an instance: `api-sheriff-plain-mgmt` carries
  `de.cuioss.sheriff.management-scheme: "http"` at `integration-tests/docker-compose.yml:750`.
  ⛔ That is plain **management**, not a plain application port — a precedent for the shape, never
  evidence that this mode exists.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ManagementPlainHttpAudit warn-never-refuse rationale still stated inline
- OBSERVED: the integration stack's instance pattern is fixed and deliverable 4 must follow it — a
  per-instance `sheriff-config-*` directory, a `management-scheme` label, and readiness derived
  host-side from that label plus the published port, with the compose comment at
  `integration-tests/docker-compose.yml:819-822` stating the probe comes *"from this service's
  management-scheme label above, not from a branch on its name."*
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: start-integration-container.sh still derives probe scheme/root-path from labels rather than service name
- OBSERVED: `forwarded.trusted_proxies` is a **mandatory** CIDR allow-list under ADR-0003 and no
  shipped `gateway.yaml` declares a `forwarded` block at all — see PLAN-06, which owns that gap. In
  this mode the gateway sits behind a terminating hop, so the client address arrives only in a header
  and that allow-list stops being optional. This is the cross-plan interaction deliverable 3 must
  settle.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: gateway.yaml now declares a forwarded: block, landed by PLAN-06
- HYPOTHESIS: omitting the certificate variables today yields redirects to a dead HTTPS port rather
  than working plain HTTP — confirm/refute by booting the container with the four
  `QUARKUS_*_SSL_CERTIFICATE_*` variables unset (verify-at-outline). **Shared with PLAN-07's
  deliverable 3**, which settles it first; this plan consumes that verdict rather than re-deriving it.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: ServerTlsDeclarationGate.java (landed by PLAN-07) now refuses an undeclared-plain-HTTP boot outright
- HYPOTHESIS: the BFF's session cookies carry `Secure` unconditionally, so a plain-HTTP application
  port would make them undeliverable to a browser reaching the gateway directly — confirm/refute at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/` and the session cookie codec
  (verify-at-outline). If confirmed, deliverable 3 owes an explicit answer rather than a silent
  behaviour change, and the mode's documented boundary tightens.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed: SealedSessionCookieCodec.HARDENING_ATTRIBUTES still hardcodes Path/Secure/HttpOnly/SameSite=Lax unconditionally
- Verify-first clause: settle every hypothesis against a live boot of the built image — never against
  this spec's prose or the pasted chat that prompted the workstream. ⛔ Do **not** ship the mode
  before deliverable 3's interaction verdicts are recorded: a plain-HTTP mode that silently breaks
  mTLS, client-address attribution or session cookies is worse than no mode at all, because an
  operator would reach for it precisely when those matter.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/resources/application.properties` — the mode's trigger and its documented default
- OBSERVED: `deployment/compose-sample/` — the plain-HTTP variant with its terminating hop
- OBSERVED: `integration-tests/docker-compose.yml` — the new instance, following the label pattern; **and the ten dead `QUARKUS_TLS_DEFAULT_TRUST__STORE_*` pairs the deliverable-4 fold removes (issue #285)**
- OBSERVED: `doc/user/environment-variable-overrides.adoc` — ⚠ **read-only for the fold** (its `:161-166` WARNING is the authority the fold cites, and is CORRECT); declared because the fold may add the observation that this repo shipped ten instances of the mistake it warns about
- OBSERVED: `doc/configuration.adoc` and `doc/security-threat-model.adoc` — the topology and its residual risk
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/tls/` — the audit extension and the incoherent-combination refusals (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/sheriff-config-plain-http/` — the instance's config directory (verify-at-outline: name TBD)
- HYPOTHESIS: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — the end-to-end test (verify-at-outline: exact class TBD)
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/cookie/` — only if deliverable 3's `Secure`-cookie verdict requires a change (verify-at-outline)

## Dependencies and Sequencing

- **Depends on: PLAN-07 — hard, on its deliverables 1-3.** This mode is the degenerate "no server
  certificate" case of PLAN-07's material contract, its audit extends PLAN-07's audit, and its trigger
  binds to PLAN-07's vocabulary. Do not start before PLAN-07 has landed.
- Depends on: **PLAN-06 for coherence.** This mode makes `forwarded.trusted_proxies` load-bearing, and
  PLAN-06 owns that key's configurability. If PLAN-06 has not landed, deliverable 3 must still state
  the requirement and deliverable 4's example must configure the allow-list correctly by hand.
- Overlaps with: PLAN-01 on `integration-tests/docker-compose.yml` and
  `deployment/compose-sample/docker-compose.yml`; PLAN-02 on the compose files and the probe URLs;
  PLAN-06 and PLAN-07 on the documentation and the shipped `gateway.yaml` files. Sequence last within
  the epic.
- Adjacent to: the ADR-0017 SNI front listener and `PassthroughRelay`. Untouched — but deliverable 2
  must **refuse** the combination rather than leave it undefined, since a passthrough topology and a
  plain-HTTP application port are mutually incoherent.
- Adjacent to: `MtlsServerCustomizer`. Untouched, and likewise refused in combination: inbound client
  certificates cannot be verified by a gateway that terminates no TLS.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-08-plain-http-termination-mode.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
