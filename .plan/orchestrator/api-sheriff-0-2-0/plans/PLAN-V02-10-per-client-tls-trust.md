# PLAN-V02-10: Per-Client TLS Trust

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-48-per-client-tls-trust.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**⚠ D2 IS NEARLY TWICE THE SIZE THE SPEC STATES.** The spec says the process-global truststore
override is *"12 arguments across 4 gateway services"*. Mechanical enumeration at HEAD:
**22 arguments across 7 gateway services** in `integration-tests/docker-compose.yml` —
`api-sheriff`, `api-sheriff-cookie`, `api-sheriff-cookie-2`, `api-sheriff-mtls`,
`api-sheriff-passthrough-empty`, `api-sheriff-plain-mgmt`, `api-sheriff-ws-admission`.
The spec already instructs *"delete by service, not by line number, and re-grep at outline"* — that
instruction is now load-bearing rather than cautious. `api-sheriff-passthrough-empty` is the newest
of the seven (added by roadmap PLAN-46, #156). **Removing the override changes trust resolution for
all seven**, so the coordination note with the benchmark lane widens accordingly.

**MOVED — re-anchor D1 by content, not by line.** `TokenValidatorProducer`:187 is **javadoc**, not
the construction site; the class takes `JwksTrustProfileResolver` by constructor injection at :69 and
the trust contract is documented at :165–187. `JwksTrustProfileResolver`:78 is likewise javadoc — the
`trust-all` refusal that PLAN-31C added executes at **:126–131**, ahead of the anchor-free guard as
claimed. The substance of both Observed Facts holds; only the anchors decayed.

**✅ DISCHARGED 2026-08-08 — the lead that gates this plan is now VERIFIED, and it holds.**
The spec carried *"upstream PR #607 is reported to have shipped the optional per-client `SSLContext`
this work depends on … never verified against the resolved artifact"*, with an instruction to
re-scope if refuted. **It is confirmed, first-party, against the jar this project actually compiles
against** — `token-sheriff-client-0.9.4.jar` (`version.token-sheriff` = 0.9.4, `pom.xml`:60):

```text
de.cuioss.sheriff.token.client.config.ClientConfiguration
  private final javax.net.ssl.SSLContext sslContext;
  public javax.net.ssl.SSLContext getSslContext();
ClientConfiguration.ClientConfigurationBuilder
  public ClientConfigurationBuilder sslContext(javax.net.ssl.SSLContext)
```

`SSLContext` is additionally referenced by `auth/MtlsClientAuth` and `internal/BackChannelHttp`, so
the seam is wired through the back-channel path rather than merely declared on the config record.
**D1 has its route: build the `SSLContext` from the resolved trust profile and pass it through the
builder.** This also confirms the spec's complementary Observed Fact — `BffRuntimeProducer` sets no
SSL context today (`PLAN-V02-08` verified the same point independently), so the asymmetry D1 closes
is real.

**The two riders on #607 remain unverified leads** and are NOT discharged by the above: the
blank-`acr_values` fail-closed change and the scheme-keyed HTTP-client caching rework, both
verdicted no-impact from the PR page alone. Neither gates D1. If either is load-bearing at outline,
verify it the same way — against the resolved jar, never against the PR page.

> **Method note, because it is the reusable part.** This was settled by reading the **resolved
> artifact in `~/.m2`**, not the upstream repository and not the PR. The distinction is not
> pedantic: on the same day, `PLAN-V02-08`'s upstream blocker (`#618`) was found **fixed on
> TokenSheriff `main` and absent from the resolved 0.9.4 jar**, because no release has been published
> since the fix merged. Same upstream, same version, opposite answers — and only the jar
> distinguishes them.

**GATE COST, new since this spec was written.** `docker-compose*.yml` is now gate-requiring
(`build.map`, roadmap PLAN-51 / #196), so D2 pays a full quality gate. Intended trade.

**RENUMBERING.** "Adjacent to PLAN-46" means **PLAN-V02-09** (token-sheriff integration fidelity) —
and the sequencing advice stands and sharpens: **if V02-09's D2 selects the mapping direction, D1's
wiring may become moot.** Sequence V02-09 first, or accept the rework knowingly. "PLAN-31C",
"PLAN-31D", "PLAN-35", "PLAN-34", "PLAN-43" are all landed `api-sheriff-roadmap` work.


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

**ANCHORS HELD** at `95dd566` — including the corrected count: the process-global truststore override
is still **22 arguments across 7 gateway services**, and `ClientConfiguration` at the resolved 0.9.4
still exposes the per-client `SSLContext` D1 needs (verified 2026-08-08 against the jar).

**⚠ DISTRIBUTION CHANGE 2026-08-09 — D1 HAS MOVED OUT OF THIS PLAN INTO `PLAN-V02-09` (its D6).
THIS PLAN IS NOW A SINGLE DELIVERABLE: D2, THE COMPOSE CLEANUP.**

**Why.** D1 (wire the client `SSLContext` through the trust-profile mechanism) was conditional on
V02-09's D2 verdict — under the *mapping* direction it may be moot. A deliverable whose existence
depends on another plan's decision belongs in the plan that takes the decision; keeping it here
created a dependency edge whose only purpose was to re-import a verdict. **V02-09 now owns it as D6,
explicitly conditional on its own D2.**

**What this plan is now, and it is better for it.** Delete the process-global truststore override —
**22 `-Djavax.net.ssl.trustStore*` arguments across 7 gateway services** in
`integration-tests/docker-compose.yml`. That is correct under **either** branch of V02-09's decision,
so **this plan is UNBLOCKED and independently emittable today**. Delete by service, not by line
number, and re-grep at outline. Removing the override changes trust resolution for all seven
services, so the coordination note with the benchmark lane stands.

**Retitle at outline if it helps the reader**: the plan is now *"delete the process-global truststore
override"* rather than *"per-client TLS trust"*, which describes work that has moved. The file name
and `plan_id` are deliberately unchanged — renaming them would break the queue row and every
reference in this ledger for no gain.

**Superseded note (kept for the audit record):**

- **D1 is conditional on `PLAN-V02-09` D2.** If V02-09 selects the *mapping* direction, the extension
  builds the validator and D1's wiring may be moot. **Do not start D1 before V02-09 lands**, and read
  its landing message for the branch taken.
- **D2 is unconditional.** Deleting the process-global override is correct under either V02-09 branch
  — it is integration-stack hygiene, not validation wiring.

**Consequence for scheduling, and it is the reason this note exists:** a two-deliverable plan whose
halves have different predecessors is a candidate for being **split or folded** rather than held
whole. Two options, to be decided at emit rather than drifted into: fold D1 into `PLAN-V02-09` (which
already owns the file and the decision), leaving this plan as the single-deliverable compose
cleanup; or keep it whole and emit it strictly after V02-09. **Recorded as a decision owed, not
taken.**

**Open Defect (14) — issue #201 — is a candidate home here** (`MtlsHandshakeIT` fails 2/3 under
`-Pjfr` only, fail-open on handshake rejection). It is mTLS handshake behaviour, which is this plan's
neighbourhood, but the defect is **lane-conditional** rather than trust-wiring — see `PLAN-V02-11`,
which is the better home. Do not adopt it here without saying why.

## Re-Grounded (3) 2026-09-22 at `af63895`

**D2'S COUNT GREW AGAIN, AND THE OVERRIDE IS NO LONGER SCOPED TO 7 SERVICES.**
`integration-tests/docker-compose.yml` now carries **36** `-Djavax.net.ssl.trustStore*` arguments
(12 services × 3 args, not 22 across 7): `api-sheriff`, `-mtls`, `-cookie`, `-cookie-2`,
`-ws-admission`, `-plain-mgmt`, `-passthrough-empty`, `-no-certificate`, `-egress-verify-on`,
`-egress-verify-off`, `-refresh`, `-cookie-refresh`. Re-grep at outline, per the spec's own
"delete by service, not by line number" instruction — that instruction is exactly right and now
matters more, since the surface has grown by 63%.

**A THIRTEENTH OVERRIDE SITE, OUTSIDE `docker-compose.yml` ENTIRELY.**
`integration-tests/src/test/java/.../NoCertificatePlainHttpOptInIT.java`:745-747 independently
constructs the identical `-Djavax.net.ssl.trustStore*` triplet. D2's "delete by service in
docker-compose.yml" framing does not cover this file — re-grep this test at outline too, or D2 ships
with one override site still live.

**D6 (the deliverable that moved to `PLAN-V02-09`) IS NOW MOOT — CONFIRMED FROM BOTH SIDES.**
`BffRuntimeProducer.java`:501-522 already calls
`ClientConfiguration.builder()....sslContext(trustProfileResolver.resolveEgressProfile(...))` —
the BFF OIDC back-channel leg's client-engine `SSLContext` is already wired through
`JwksTrustProfileResolver`, per shipped (but still `Status: Proposed`) ADR-0045. `PLAN-V02-09`'s own
independent re-grounding found the identical fact. **The asymmetry D1/D6 was written to close no
longer exists for this leg** — flag to `PLAN-V02-09` at its own outline; nothing for this plan to act
on beyond noting D1 stays out (already correctly excluded here since 2026-08-09).

**Expected Surface no longer needs `TokenValidatorProducer.java` / `JwksTrustProfileResolver.java`**
— those were the D1 construction-site files, and D1 left this plan on 2026-08-09. This plan is D2-only;
correct Expected Surface at outline to the compose tree, the newly-found test file above, and
`doc/user/tls-scenarios.adoc` (which carries worked `-Djavax.net.ssl.trustStore*` examples D2 will
make stale, ~lines 500-502 and 676-678) — `doc/user/` is a more specific and correct doc target than
the spec's current under-specific "the private-CA authorization-server documentation."

**Upstream lead not re-verifiable this pass.** `pom.xml`:63 now pins `version.token-sheriff=0.9.6`
(was 0.9.4 when the 2026-08-08 discharge note checked `ClientConfiguration`'s `SSLContext` builder);
no 0.9.6 jar is cached locally, so the prior discharge is stale to the version bump and needs
re-verification at outline against the artifact actually resolved then — not load-bearing for D2.

## Re-Grounded (4) 2026-09-24 at `05f6ee3` — after 18 commits (#343–#354, release 0.2.3)

D1 is confirmed discharged (`BffRuntimeProducer.java:539` wires the egress `SSLContext`, carried by V02-09's D6), so this plan remains D2 only. D2 is open: 37 trustStore-family arguments across 12 `integration-tests/docker-compose.yml` services, plus a 13th site in `NoCertificatePlainHttpOptInIT.java`, which D2 must also retire. `doc/user/tls-scenarios.adoc` exists as a reconciliation target. A `## Claim Labels` section was added 2026-09-24 (cleanup A3).

## Objective

The gateway's JWKS trust is already neutral and fail-closed: `gateway.yaml` names a logical
`jwks.tls_profile`, `JwksTrustProfileResolver` binds it to concrete runtime trust and refuses a name
it cannot resolve, and PLAN-31C hardened it further by rejecting a `trust-all` bucket ahead of the
anchor-free check. What is not yet wired through that mechanism is the **client engine's own
`SSLContext`**, and the integration stack still leans on a **process-global truststore override**
across its gateway services.

Wire the client engine through the existing logical trust-profile mechanism, and delete the global
override.

**Carried over from `api-sheriff-roadmap` PLAN-31D, which was struck on 2026-08-03** — see
`## Provenance`. This plan is that plan's remaining two deliverables and nothing else.

## Deliverables

1. **Wire the client-engine `SSLContext` through the existing logical trust-profile mechanism.**
   `TokenValidatorProducer`:187 and `JwksTrustProfileResolver`:103-111 are the model — the
   validation-side trust path is **already correct**, so this is the client side only. If the work
   forces a change to the validation side, that is a finding to report, not a patch to apply.

2. **Delete the process-global truststore override — its own named line item.** It is 12 arguments
   across 4 gateway services in `integration-tests/docker-compose.yml`. **Delete by service, not by
   line number, and re-grep at outline** — the file has been restructured twice since this was
   written (PLAN-31B's +148-line compose restructure, and PLAN-42's Compose-model-derived probe work).
   Removing the global override changes how every gateway service resolves trust, so coordinate with
   the benchmark lane on the discontinuity.

## Provenance and what was already done elsewhere

`api-sheriff-roadmap` PLAN-31D carried three deliverables. **D3 — a positive bearer-validation
integration test — is obsolete and was struck, not carried:**

- PLAN-35 shipped `validBearerTokenAdmittedAndForwarded()` in `BearerValidationIT` (+101).
- PLAN-34 shipped `BearerSecurityFilterInteractionIT` (+160), the anchor route carrying bearer
  protection **and** a `security_filter` — which closed roadmap Open Defect (8) outright.

Roadmap Open Defect **(13)** (`azpAudienceFallbackEnabled` has no behavioural test) was PLAN-31D's
other carry. It is **not orphaned**: roadmap PLAN-43 names it explicitly and makes
`TokenValidatorProducerTest`:68-99 a *mandatory* row of its sweep. **Do not re-do it here.**

## Observed Facts

- **UPSTREAM LEAD, NOT A FACT** — upstream PR #607 is reported to have shipped the optional per-client
  `SSLContext` this work depends on. It was read from the PR page and **never verified against the
  resolved artifact**. Confirm at outline against the resolved `token-sheriff` version; **if refuted,
  re-scope rather than proceed.** #607 also bundles a blank-`acr_values` fail-closed change and a
  scheme-keyed HTTP client caching rework — both verdicted no-impact, both likewise unverified leads.
- The validation-side trust path is already correct at `TokenValidatorProducer`:187.
- `JwksTrustProfileResolver`:78 refuses a `trust-all` bucket ahead of the anchor-free check
  (PLAN-31C).

## Claim Labels

Added 2026-09-24 by the cleanup pass (A3 ambiguity: the spec had no claim section). Each claim was corroborated at `05f6ee3`.

- OBSERVED: D1's client `SSLContext` wiring has landed — `BffRuntimeProducer.java:539` `resolveEgressProfile(OIDC_TLS_PROFILE_KEY, …)`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: BffRuntimeProducer.java:539 .sslContext(trustProfileResolver.resolveEgressProfile(...))
- OBSERVED: the process-global truststore override spans 12 services in `integration-tests/docker-compose.yml` (37 trustStore-family arguments)
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: integration-tests/docker-compose.yml: 12 services, 37 trustStore-family args
- OBSERVED: a 13th override site exists in `NoCertificatePlainHttpOptInIT.java` — HYPOTHESIS that it must be retired with D2 (verify-at-outline)
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: NoCertificatePlainHttpOptInIT.java constructs the identical trustStore triplet
- OBSERVED: `doc/user/tls-scenarios.adoc` exists and carries worked trustStore examples — D2's documentation target
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: doc/user/tls-scenarios.adoc present with worked trustStore examples

## Expected Surface

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java`,
  `JwksTrustProfileResolver.java` — the client construction site
- `integration-tests/docker-compose.yml` — the four gateway services
- `doc/user/` — the private-CA authorization-server documentation
- No change to the validation-side trust path is expected.

## Dependencies and Sequencing

- **Post-0.1.0.** Not release-gating: the shipped trust mechanism is neutral and fail-closed today,
  and the global override lives in the integration stack rather than the shipped artifact
  (roadmap PLAN-31C's whole subject). This is an improvement, not a defect fix.
- **Adjacent to PLAN-46** (token-sheriff integration fidelity), which asks whether the gateway should
  map onto the extension's config surface at all. **If PLAN-46 selects the mapping direction, D1's
  wiring may become moot** — sequence PLAN-46 first, or accept the rework.
- Coordinate with the benchmark lane on D2's trust-resolution discontinuity.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-10-per-client-tls-trust.md" plan_id=plan-v02-10-per-client-tls-trust
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The executing plan MUST NOT create or edit any file under
`.plan/local/orchestrator/api-sheriff-0-2-0/`. Its two channels back to the epic are its PR and its
`inbox/` OUTBOX.
