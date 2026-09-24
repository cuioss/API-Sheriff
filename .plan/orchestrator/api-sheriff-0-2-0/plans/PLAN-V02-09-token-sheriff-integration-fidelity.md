# PLAN-V02-09: Token-Sheriff Integration Fidelity — Health Coverage and the Config-Mapping Question

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-46-token-sheriff-integration-fidelity.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This spec is the most current in the epic** — it was
updated in place on 2026-08-06 and again on 2026-08-07 (issue #174 routing). Every anchor re-checked
this pass **holds**; no correction is owed.

- `token-sheriff` resolves to **0.9.4** (`pom.xml`:60) — the version D1 is told to read.
- The exclusion block is present in `api-sheriff/src/main/resources/application.properties` around
  the stated `:203`, and its in-file commentary already states D3's readiness thesis verbatim: the
  datum is *"a BOOT-TIME constructibility fact, not a live JWKS signal"*, and *"the excluded
  `JwksEndpointHealthCheck` polled `JwksLoader#getLoaderStatus()` per issuer and would have carried
  it."*
- **D3's metrics gap CONFIRMED**: `SheriffMetrics` imports only cui-http's `SecurityEventCounter`
  (:23) and `bindSecurityEventCounter` (:159) iterates `UrlSecurityFailureType.values()` (:161).
  The token validator's `SecurityEventCounter` is bound **nowhere** — JWT signature failures,
  expiries, issuer mismatches and JWKS failures are unmetered.
- **D3's discovery gap (#174) CONFIRMED**: `DiscoveryResolver` appears at exactly two places in the
  whole gateway — the import at `BffRuntimeProducer`:64 and the memoised resolve at :214. Readiness
  never touches it. The fix must **preserve lazy discovery** rather than eagerly resolving at boot to
  make readiness honest.

### ⬆ UPSTREAM ISSUE FILED 2026-08-08 — `cuioss/TokenSheriff#641`. READ IT BEFORE SCOPING D2.

**<https://github.com/cuioss/TokenSheriff/issues/641>** — *"Extension health/metrics beans resolve
issuers from the property namespace, not from the produced `TokenValidator`."*

Filed by the orchestrator on operator instruction, and it **discharges an intent this project already
recorded**: ADR-0027 § Alternatives Considered § *"Escalate to the extension as a configuration-model
finding and wait"* says the report is *"sound and worth raising"*, rejects it **only as the sole
remedy and on timing rather than merit**, and states that *"raising it upstream and excluding here
are complementary, not alternatives."* The exclusion shipped; the escalation had not been made until
now. **It is now made.**

**What the issue asks for**, in one line: *observe the object, not the keys* — resolve the
`TokenValidator` as a CDI dependency rather than reconstructing it from `IssuerConfigResolver`, so
that any producer satisfies the beans, including an embedder's own. It also asks, as the smaller
fallback, that a bean finding no property-based issuers **degrade honestly** — report *not
configured* and go idle rather than report `DOWN` and throw on a schedule, with the scheduled
collector warning **once** rather than on every tick.

**WHY THIS MATTERS TO D2 SPECIFICALLY, AND IT CUTS BOTH WAYS.** D2 weighs *map* against *keep the
re-implementation*. #641 is a **third direction that dissolves the question rather than answering
it**: if upstream resolves the validator object, the extension's beans observe this gateway's real
issuers with no `ConfigSource` projection at all, `quarkus.arc.exclude-types` has nothing left to
exclude, and both of D3's gaps (the live JWKS signal and the unbound validation metrics) close as a
side effect. **Do not treat that as a reason to wait** — ADR-0027 already rejected putting this
gateway's readiness and its log on another project's release cycle, and that reasoning is unchanged.
Treat it as a fourth option to weigh explicitly, with its dependency on an upstream release stated as
the cost it is.

**CHECK ITS STATE AT OUTLINE.** If #641 has been resolved *and released* by then, D2's verdict may be
"adopt the upstream fix and delete the exclusion", which is a materially smaller plan than either
current direction. **Verify against the resolved jar, never against the issue thread** — the same
day this was filed, `#618` was found fixed upstream and absent from the resolved 0.9.4 artifact
because no release had been published since the merge. A closed issue is not a released capability.

> **ADR-0027 MUST BE UPDATED BY THIS PLAN, and the orchestrator cannot do it.** `doc/adr/**` is
> repository source and outside the orchestrator's write boundary, so this instruction is recorded
> here rather than enacted. **Add `cuioss/TokenSheriff#641` to ADR-0027** — against the
> *"Escalate to the extension…"* alternative, which currently reads as an un-actioned recommendation,
> and against the § Risks entry *"the exclusion outlives its justification"*, which #641 is the
> mechanism for retiring. If this plan's verdict supersedes ADR-0027, that supersession carries the
> reference instead. **Do not leave the ADR stating that the escalation is owed when it has been
> made.**

**SEQUENCING — one sequential chain, and this plan is LAST of three.** `PLAN-V02-08` (FAPI 2.0) and
`PLAN-V02-12` (idp-addressing) both write `BffRuntimeProducer` / the `oidc` block. Order:
**V02-08 → V02-12 → V02-09.** Never concurrent.

**ADR-0027 note for readers of the sequencing section**: the rename this spec cites (health probes →
unqualified beans) is landed and visible in the corpus filename. `PLAN-V02-04` audits that record and
is told not to pre-empt this plan's re-opening of it — so **V02-04 and this plan must not run
concurrently either**.


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

**ANCHORS HELD** at `95dd566`: token-sheriff still 0.9.4, the exclusion block still in
`application.properties`, `DiscoveryResolver` still only at `BffRuntimeProducer`:64/:214.

**OPEN DEFECT (13) IS FOLDED IN HERE — this is its home, decided 2026-08-09.**
`TokenValidatorProducer.applyJwks` qualifies for a switch conversion. `PLAN-V02-02` identified it and
correctly left it alone as outside its declared surface. **This plan already owns that file**, so the
conversion rides along as a small item inside whichever deliverable touches `TokenValidatorProducer`
— it is NOT a new deliverable and must not be allowed to grow into one. If no deliverable ends up
touching that file, report it as still unowned rather than manufacturing a reason to edit it.

**⚠ DISTRIBUTION CHANGE 2026-08-09 — `PLAN-V02-10`'s D1 MOVES INTO THIS PLAN AS A NEW DELIVERABLE 6.**

**Deliverable 6 — wire the client engine's own `SSLContext` through the logical trust-profile
mechanism.** Verified available at the resolved artifact: `ClientConfiguration` (token-sheriff 0.9.4)
declares a private `SSLContext`, exposes `getSslContext()`, and its Builder takes
`sslContext(SSLContext)`; `MtlsClientAuth` and `internal/BackChannelHttp` consume it, so the seam is
wired through the back-channel path rather than merely declared. `BffRuntimeProducer` sets no SSL
context today, and that asymmetry is what D6 closes. **The validation-side trust path is already
correct — if the work forces a change there, that is a finding to report, not a patch to apply.**

**Why it moved here rather than staying in V02-10.** It was conditional on *this plan's* D2 verdict:
if D2 selects the *mapping* direction, the extension builds the validator and the wiring may be moot.
A deliverable whose existence depends on another plan's decision belongs **in the plan that takes the
decision** — otherwise the epic carries a dependency edge that exists only to re-import a verdict.
**D6 is therefore explicitly conditional on D2: if D2 selects mapping, D6's correct outcome may be
"not needed, and here is why" — record that rather than wiring something the verdict just made
redundant.**

**Deliverable count is now six — at the split guard, evaluated, proceeding unsplit.** D6 is small
(one construction site) and is bound to D2's verdict, so splitting it back out would recreate exactly
the cross-plan conditional this change removes. If a split is ever forced, the line is D1+D2+D6 (the
mapping decision and everything that resolves against it) | D3+D4+D5.

**What remains in `PLAN-V02-10`** is its D2 alone — deleting the process-global truststore override
(22 arguments across 7 gateway services). That is **unconditional under either branch of D2 here**,
so V02-10 is now unblocked and independently emittable.

**The residual relationship to `PLAN-V02-10` is now one-way and advisory.**
V02-10 D1 (wire the client `SSLContext` through the trust-profile mechanism) **may become moot** if
D2 here selects the mapping direction. V02-10 D2 (delete the process-global truststore override, 22
args across 7 services) is **independent of that decision and moot under neither branch**. Say which
branch you took in the landing message so V02-10 can be re-scoped rather than re-derived.

## Re-Grounded (3) 2026-09-22 at `af63895`

**⚠ THE PLAN'S CENTRAL PREMISE IS STALE. RE-SCOPE D1–D6 AT OUTLINE, NOT A LINE-NUMBER REFRESH.**
`api-sheriff/pom.xml`:63 now pins `version.token-sheriff=0.9.6` (two releases past 0.9.4). Both
upstream issues this plan frames as open are **closed with released fixes already in the pinned
artifact**, and D6 (the deliverable moved in from `PLAN-V02-10`) is **already done**:

- **`TokenSheriff#641`** (extension health/metrics beans resolve issuers from the property namespace,
  not the produced `TokenValidator`) — closed 2026-08-10; fix (PR #645) is an ancestor of 0.9.5/0.9.6.
  `GatewayReadinessCheck.java`:62-92 already reads `IssuerKeySetStatus` (a non-fetching, per-issuer
  live view) via `RetryingJwksLoader` — landed by PR #334 (2026-09-21). **D3's readiness half is
  already done.**
- **`TokenSheriff#617`** — closed 2026-08-09; fix (PR #642) ships `META-INF/native-image/` from the
  plain validation-core library, an ancestor of 0.9.5/0.9.6.
- **D6 is already landed.** `BffRuntimeProducer.java`:522 already calls
  `builder.sslContext(trustProfileResolver.resolveEgressProfile(OIDC_TLS_PROFILE_KEY, tlsProfile))` —
  landed by an unrelated PR (#306, 2026-09-16), before D6 was even moved into this plan. Confirmed
  independently by `PLAN-V02-10`'s own re-grounding pass. **Report D6 as already satisfied, not as
  work to do.**
- **Open Defect (13) (`applyJwks` switch-conversion candidate) is gone with the method it named** —
  `TokenValidatorProducer.java` was substantially rewritten by PR #334; no `applyJwks` method exists
  any more. Do not carry this item forward.
- **Issue #174** (`GatewayReadinessCheck` reporting a false `issuer_reachability: reachable`) — the
  underlying false-claim bug is fixed (the same PR #334 removed the `ISSUER_REACHABLE`/
  `ISSUER_UNREACHABLE` constants entirely; the datum is now always `unverified`), but **the GitHub
  issue itself is still OPEN** — no active discovery-attempt probe was added, only the false claim was
  stopped. Distinguish "the lie is gone" from "the feature exists" when reporting.

**Consequence for D1–D5.** With the exclusion's stated rationale (JWKS readiness as boot-time-only) no
longer accurate and both named upstream blockers resolved, D2's "adopt upstream fix, delete the
exclusion" option is now the live default rather than a contingency — re-derive D1–D5 against that,
not against the plan's original framing.

**Secondary corrections:** Expected Surface is missing `BffRuntimeProducer.java` and
`SheriffMetrics.java` (both are direct write sites for the above); `application.properties §§ 150-203`
citation should read ~§§ 260-337 (block grew/shifted); the "0.9.3 artifact" read-only note should read
0.9.6. `ADR-0027` (the beans-exclusion decision) still carries no reference to `#641`/`#617` — the
spec's own instruction to update it there has not yet been paid, consistent with this plan being
unexecuted.

## Re-Grounded (4) 2026-09-24 at `05f6ee3` — after 18 commits (#343–#354, release 0.2.3)

Pin is `0.9.6` (`api-sheriff/pom.xml:63`). The `quarkus.arc.exclude-types` exclusion is unchanged (`application.properties:345`). **D3 half discharged:** readiness reads live `IssuerKeySetStatus` via `RetryingJwksLoader` (`GatewayReadinessCheck`). The metrics half is still open: `SheriffMetrics` binds only cui-http's `SecurityEventCounter`. **D4 half discharged:** the `application.properties` rationale block is already current and names the metrics gap. `ADR-0027` still lacks the `#641`/`#617` references. **D5:** the `0.9.6` `token-sheriff-validation` jar now SHIPS `META-INF/native-image/.../reflect-config.json` (verified by `unzip -l`). This confirms the `#617` fix recorded above and widens D2's options to "map + drop the extension". The Observed Facts line saying the plain library "ships NO native-image metadata" is STALE and is outranked by this section. `applyJwks` no longer exists (Open Defect 13 is moot). A `## Claim Labels` section was added 2026-09-24 (cleanup A3).

## Objective

API Sheriff runs **two parallel token-validation mechanisms**. The request path builds a
`@GatewayValidator`-qualified `TokenValidator` from `gateway.yaml`'s `token_validation` block via
`TokenValidatorProducer`; the `token-sheriff-validation-quarkus` extension independently builds an
**unqualified** `TokenValidator` from its own `sheriff.token.issuers.<name>.*` property namespace,
which this gateway never populates. Because that namespace is empty, the extension's two health
probes reported `DOWN` and dragged `/q/health/ready` down, so PLAN-31C excluded them by bean type
(`application.properties`:203).

The exclusion was correct as a fix for a false negative, and it is well-reasoned in place. **The
question this plan answers is whether the shape it froze is the right one.** Excluding the probes
left the gateway with a single readiness datum for JWKS that is a **boot-time constructibility fact,
not a live signal** — an IdP that becomes unreachable, a stalled key rotation, or an expired signing
key do not take readiness `DOWN`. Meanwhile the extension ships an issuer-config resolver and a live
JWKS-endpoint probe that the gateway re-implements and then cannot use.

Establish what the extension's health checks actually cover, decide whether the gateway should
**map its configuration onto the extension's namespace instead of re-implementing it**, and close the
live-JWKS readiness gap either way.

## Deliverables

1. **Establish what the excluded beans actually cover.** Read the shipped extension, not its
   description: `de.cuioss.sheriff.token.quarkus.health.JwksEndpointHealthCheck` and
   `TokenValidatorHealthCheck`, **and now `metrics.JwtMetricsCollector`**, in
   `token-sheriff-validation-quarkus` **0.9.4**. Report, per bean, what it reads, when it can fail,
   and whether it observes a **live** condition or a construction fact. Two things are already
   visible from the jar and are the starting point, not the answer: the JWKS probe carries
   `CachedResponse` and `EndpointResult` inner classes and is ~4× the size of the validator probe,
   which is ~1.6 KB — **the asymmetry suggests the validator probe verifies very little, and the
   operator's question is precisely whether it should assert on the `TokenValidator` instance
   itself.** If it under-covers, that is an upstream finding to report, not something to work around
   here.

   > **The scope grew, and the growth is evidence for this plan rather than noise.** When this spec
   > was written the exclusion covered two health probes. On 2026-08-06 issue #173 added a third
   > bean — `JwtMetricsCollector`, a **scheduled** bean rather than a probe — which failed on every
   > 10-second tick against the same empty `sheriff.token.issuers.*` namespace. **ADR-0027 was
   > renamed** from *"…unqualified **health probes** are excluded, not accommodated"* to
   > *"…unqualified **beans** are excluded, not accommodated"*, on the reasoning that one structural
   > mismatch was presenting as two symptoms depending on what drove each bean. That is this plan's
   > own thesis, reached independently and under time pressure. **Read the 0.9.4 jar for any further
   > bean of the same shape rather than assuming the list is now complete** — see D2's new argument.

2. **Decide the config-mapping question, with the answer written down either way.** Three candidate
   directions; the deliverable is a reasoned verdict, not a presupposed one:
   - **Map** — project `gateway.yaml`'s `token_validation` onto `sheriff.token.issuers.*` through a
     `ConfigSource`, letting the extension build the validator and its probes observe real issuers.
     **The precedent exists in this codebase**: `NeutralTlsConfigSource` already projects the neutral
     `management` block onto a `quarkus.*` key.
   - **Keep the re-implementation** and close the readiness gap in the gateway's own probe.
   - **A hybrid** — map for observability, keep the qualified producer for the request path.

   **`NeutralTlsConfigSource`'s own javadoc argues against blind projection where a live CDI seam
   exists**, and that is the strongest counter-argument to the mapping direction: it deliberately does
   *not* project the sibling `tls` block because `TlsServerCustomizer` can observe what Quarkus
   actually built. `TokenValidatorProducer` is such a live seam. Weigh that explicitly.

   > **NEW ARGUMENT FOR THE MAPPING DIRECTION, established 2026-08-06 by trying it and failing — do
   > NOT re-derive this.** Issue #173's session attempted to derive the exclusion scope from the
   > extension's CDI registrations, at CodeRabbit's request. **Two implementations were written and
   > neither works**: the producer stays resolvable even with every reaching bean excluded, and bean
   > enumeration is a membership snapshot **ADR-0030 forbids**. The shipped fitness function
   > `ExtensionUnqualifiedBeanExclusionTest` therefore covers the **scheduler-driven case only**;
   > the residual risk is recorded in ADR-0027 § 2 and Risks, and the mitigation is a standing
   > obligation to **re-read the exclusion's scope on every token-sheriff upgrade**.
   >
   > **Why this changes the balance rather than merely informing it.** *Keep the re-implementation*
   > now carries a permanent, unautomatable maintenance tax that grows with every upstream release —
   > and it is already being paid badly: `0.9.3 → 0.9.4` (`d44b1eb`) landed **without** the scope
   > re-read it demands. *Map* does not merely close a readiness gap; **it dissolves the exclusion
   > entirely**, because beans that observe real issuers have nothing to be excluded from. Weigh the
   > `NeutralTlsConfigSource` counter-argument against a cost that is now measured rather than
   > hypothetical.

   Two properties any mapping must preserve, or the mapping is a regression: the fail-closed
   `jwks.tls_profile` resolution (`JwksTrustProfileResolver` refuses a name it cannot resolve rather
   than falling back to default trust), and the eager boot-time validation in
   `TokenValidatorProducer.onStartup`, which aborts startup on a misconfigured issuer.

3. **Close the live-JWKS readiness gap AND the unbound validation-metrics gap.** Both have the same
   shape — the gateway re-implements the extension's mechanism and then cannot observe it — and both
   are answered differently depending on deliverable 2's verdict, so they are one deliverable rather
   than two.

   - **Readiness.** `GatewayReadinessCheck` currently reports a cached construction fact. Give it a
     live loader-status read — the excluded `JwksEndpointHealthCheck` polled
     `JwksLoader#getLoaderStatus()` per issuer and would have carried this, had it ever been pointed
     at these issuers.
   - **Metrics.** Verified first-party at `bcfe3c9`: `SheriffMetrics.bindSecurityEventCounter`
     iterates `UrlSecurityFailureType.values()` — **cui-http's URL-security counter only**. The token
     validator's `SecurityEventCounter` is a different counter with a different enum and is **bound
     nowhere**, so JWT signature failures, expiries, issuer mismatches and JWKS failures are
     **unmetered**. The seam is a *second* binding at `SheriffMetrics.bindSecurityEventCounter` for
     the qualified validator's counter — if deliverable 2 keeps the re-implementation.

   **Two corrections this carries, because both are easy to inherit wrongly.** #173's exclusion
   removed *stack traces*, not metrics: `JwtMetricsCollector` never published a meter, so nothing was
   lost — and nothing was gained, because the gap predates it. And an `api-sheriff-roadmap`
   orchestrator note stated *"the gateway owns its own security-event metrics independently"*, which
   is true and **misleadingly incomplete**: it does not cover JWT validation.

   - **Discovery — GitHub issue [#174](https://github.com/cuioss/API-Sheriff/issues/174), routed
     here 2026-08-07. This is a THIRD gap of the same shape, and it is the one an operator meets
     first.** Verified at `origin/main` (`b8dde22`): `GatewayReadinessCheck` reports
     `issuer_reachability: reachable`, and `DiscoveryResolver` appears **only** in
     `BffRuntimeProducer`:64/:214 — the readiness check never touches it. Two different clients reach
     the same issuer: `HttpJwksLoader` fetches the key set and is what `jwks: ready` /
     `issuer_reachability: reachable` report on, while `DiscoveryResolver` fetches
     `.well-known/openid-configuration` and **every BFF flow needs it**. So the reported field is
     true in the narrow sense — one endpoint on that issuer was reached — while the endpoint the BFF
     actually depends on was never tried.

     **The reported symptom is the severe part**: `/q/health` returns `UP` on an instance where
     *every* BFF path answers `500`. An operator deploying this sees green and reasonably concludes
     the gateway works.

     **Do NOT treat this as fixed by TokenSheriff#628.** That size-limit defect merely surfaced it;
     the issue says so explicitly and it is right. Discovery can still fail for ordinary operational
     reasons — issuer down, DNS, certificate, egress policy — and readiness stays green through all
     of them. The gap is structural.

     **Preserve laziness while closing it.** Discovery is resolved on first use, which is deliberate
     and good: the gateway boots without the IdP. A fix that eagerly resolves at boot to make
     readiness honest would trade one correct property for another. The shape that satisfies both is
     a readiness probe that *attempts* discovery and reports its outcome without making boot depend
     on it — state which property you chose and why.

   If deliverable 2 selects the mapping direction, either half may be satisfied by the extension's
   own beans instead; say so rather than building both.

   **CLOSE [#174](https://github.com/cuioss/API-Sheriff/issues/174) WHEN THIS DELIVERABLE LANDS** —
   comment on the issue naming the PR and the merge commit, then close it. A PR body that merely
   *mentions* an issue does not link or close it: use a closing keyword, or close it explicitly after
   the merge. Issues #182/#183 sat open after their implementing PR landed for exactly that reason.

4. **Reconcile the outcome with the shipped `application.properties` rationale.** The ~50-line comment
   block at `application.properties`:150-203 is the current explanation of this design and it is
   accurate today. Whatever this plan concludes, that block is updated to match, and the three-layer
   documentation follows. A stale rationale next to a changed mechanism is worse than no rationale,
   because it is believed.

5. **File the upstream packaging finding, and re-test the "the extension must stay" assumption against
   it.** A library that supports native image should carry its own reflection metadata
   (`META-INF/native-image/reflect-config.json`, or `@RegisterForReflection` on the types), so that
   **any** consumer gets correct native behaviour. Delivering it only from the Quarkus deployment
   processor means the plain `token-sheriff-validation` library is **not natively usable standalone**,
   and it couples "I need reflection metadata" to "I must keep the Quarkus extension" — which is
   exactly the coupling that makes deliverable 2's verdict look pre-decided when it is not.

   Report it upstream. If upstream ships the metadata in the library, the constraint dissolves and
   deliverable 2 gains a fourth option: **map the config and drop the extension entirely.** Do not
   wait on the upstream fix to land — record the finding, and scope deliverable 2 against the
   dependency as it actually resolves today.

   **A SECOND upstream finding, added 2026-08-06 — carry it alongside #617, do NOT file it as a third
   issue.** The extension's `JwtMetricsCollector` should **warn once and go idle** rather than fail
   hard on every tick against an empty namespace. A bean whose configuration namespace is
   legitimately empty in a valid deployment should degrade quietly, not emit ~60 stack-trace lines
   every ten seconds. **State the tension rather than resolving it silently**: if deliverable 2
   selects the mapping direction this fix becomes unnecessary for API Sheriff specifically, but it
   remains correct for every other consumer — which is exactly the framing D5 already applies to
   #617.

## Observed Facts

Read first-party at `36508b2` (API Sheriff `main`) and from the resolved
`token-sheriff-validation-quarkus` 0.9.3 jar.

> **STALENESS SWEEP 2026-08-06 — the facts below were true at `36508b2`; these six moved. Re-anchor
> on content before trusting any line number.**
>
> | Recorded below | Now |
> |---|---|
> | HEAD `36508b2` | **`bcfe3c9`** |
> | token-sheriff **0.9.3** throughout | **0.9.4** (`d44b1eb`, PR #181) |
> | exclusion covers `…health.*` | covers `…health.*` **and** `…metrics.*` (`bcfe3c9`, PR #184) |
> | rationale block at `application.properties`:**150-203** | **line numbers have moved** |
> | ADR-0027 = *"…unqualified **health probes**…"* | renamed to *"…unqualified **beans**…"* — the old filename resolves nowhere |
> | D4's *"~50-line comment block … is accurate today"* | it now describes a **widened** exclusion; re-read before trusting |
>
> D1 must re-point at the 0.9.4 jar. 0.9.4's changelog carries no health or metrics change — its
> substance is the `DiscoveryResolver` size-ceiling fix (its PR #629, closing TokenSheriff#628) — so
> the probe analysis is unlikely to move. **But that is an inference from release notes, not a read
> of the jar, and D1's entire discipline is to read the shipped artifact rather than its
> description.**

- `application.properties`:203 — `quarkus.arc.exclude-types=de.cuioss.sheriff.token.quarkus.health.*`,
  declared unconditionally and in no profile. Lines 150-203 carry the rationale.
  **Superseded 2026-08-06**: the value now also carries `de.cuioss.sheriff.token.quarkus.metrics.*`.
  The value has **four carriers**, and a change touching only the properties file leaves three
  behind — `application.properties`, `ShippedApplicationPropertiesTest` (which asserts the exact
  property line), `DefaultProfileReadinessTest` (`EXTENSION_HEALTH_PACKAGE`), and ADR-0027's prose.
  The two tests are a tripwire doing its job: the correct response to their failure is a deliberate
  assertion update, never a loosened matcher.
- The extension ships `health/JwksEndpointHealthCheck` (~6.9 KB, plus `CachedResponse` and
  `EndpointResult`) and `health/TokenValidatorHealthCheck` (~1.7 KB).
- It also ships `config/IssuerConfigResolver` (~21.9 KB) and a `JwtPropertyKeys$HEALTH$JWKS` key group
  — the extension has a configurable health surface the gateway currently cannot reach.
- The gateway's parallel implementation is `auth/TokenValidatorProducer.java` (207 lines) plus
  `auth/JwksTrustProfileResolver.java` (157 lines).
- `config/NeutralTlsConfigSource.java` is the in-repo precedent for projecting `gateway.yaml` onto a
  Quarkus namespace — and its javadoc records why it declines to do so for the `tls` block.
- **The extension dependency currently cannot be dropped — but that is an UPSTREAM PACKAGING
  CONSEQUENCE, not a property of the gateway, and deliverable 5 treats it as a question.** Verified by
  inspecting the resolved 0.9.3 artifacts:
  - `token-sheriff-validation-quarkus-deployment` ships exactly one class,
    `deployment/TokenSheriffProcessor` (~12.6 KB), which emits `ReflectiveClassBuildItem`s for a long
    list of `de.cuioss.sheriff.token.validation.*` types — `TokenValidator`, `IssuerConfig`,
    `IssuerConfigCache`, the whole `pipeline/*` (`NonValidatingJwtParser`, the three validators,
    `TokenBuilder`, `DecodedJwt`), `jwks/*` (`HttpJwksLoader`, `JWKSKeyLoader`, `KeyInfo`,
    `JwksParser`), `security/*` preferences, `jwe/*`, and the `domain/token` + `domain/claim` types.
  - **`token-sheriff-validation` — the plain library — ships NO native-image metadata at all.** Its
    `META-INF/` carries only `MANIFEST.MF`, `jandex.idx`, a DSL-JSON `Configuration` service and the
    Maven descriptor. There is no `META-INF/native-image/`, no `reflect-config.json`.
  - `token-sheriff-validation-quarkus` (the runtime artifact) likewise ships none.

  **That token-sheriff builds a native image of its own does not transfer to a consumer**: reflection
  metadata must be present in *this* application's build, and here it arrives only through the Quarkus
  extension's build step.
- **A TokenSheriff checkout DOES exist at `/home/oliver/git/TokenSheriff`** (remote
  `https://github.com/cuioss/TokenSheriff.git`, at `a303ef07`). An earlier revision of this spec said
  it did not — that claim was wrong, produced by globbing only the lowercase `token-sheriff*`.
  Deliverable 1 reads that source directly; it does not infer behaviour from this spec.
- Confirmed **from source**, not only from the jars: `token-sheriff-validation/src/main/resources/META-INF/`
  contains only `services/`, and every `@RegisterForReflection` in the repository is under
  `token-sheriff-quarkus-parent/` — none in the core module.
- **Deliverable 5's upstream finding is ALREADY FILED: [cuioss/TokenSheriff#617](https://github.com/cuioss/TokenSheriff/issues/617)**
  (2026-08-03). D5 is therefore reduced to *tracking* it and re-testing the drop-the-extension option
  against its outcome — do not file a second issue. Its state was not checked for duplicates at filing
  time; if it is closed as one, follow the surviving issue instead.
  **Re-verified 2026-08-06: still OPEN**, so the reduction to tracking stands and the
  drop-the-extension option remains unavailable today.
- **The exclusion cannot be derived programmatically — established by attempting it, 2026-08-06.**
  Two implementations were written during issue #173 and neither works: the producer stays resolvable
  even with every reaching bean excluded, and bean enumeration is a membership snapshot **ADR-0030
  forbids**. `ExtensionUnqualifiedBeanExclusionTest` covers the scheduler-driven case only. **This is
  D2 input, not trivia** — see the argument recorded under D2.
- **`SheriffMetrics.bindSecurityEventCounter` iterates `UrlSecurityFailureType.values()`** — cui-http's
  URL-security counter only. The token validator's `SecurityEventCounter` is bound nowhere. Read
  first-party at `bcfe3c9`; this is D3's second half.
- **`GatewayEdgeRoute`:651 stamps `SheriffMetrics#NO_ROUTE`** for an unmatched request — noted because
  it establishes that the gateway's own metric surface is route-shaped and carries no
  token-validation dimension at all.

## Claim Labels

Added 2026-09-24 by the cleanup pass (A3 ambiguity: the spec had no claim section). Each claim was corroborated at `05f6ee3`.

- OBSERVED: token-sheriff is pinned at `0.9.6` — `api-sheriff/pom.xml:63`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: api-sheriff/pom.xml:63 version.token-sheriff 0.9.6
- OBSERVED: the `quarkus.arc.exclude-types` exclusion of token-sheriff health/metrics beans is unconditional — `application.properties:345`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: application.properties:345 exclusion unconditional, no profile scoping
- OBSERVED: readiness already reads live `IssuerKeySetStatus` — `GatewayReadinessCheck` `:19`, `:141`, `:153`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: GatewayReadinessCheck imports IssuerKeySetStatus :19, fields :141/:153
- OBSERVED: the token validator's `SecurityEventCounter` is bound to no meter; `SheriffMetrics` binds only cui-http's counter — D3's open half
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: SheriffMetrics binds only de.cuioss.http SecurityEventCounter; validator counter feeds SignatureOnlyTokenVerifier only
- OBSERVED: `ADR-0027` carries no `#641`/`#617` reference — `doc/adr/0027-*.adoc`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: grep #641|#617 in doc/adr/0027-*.adoc: no match
- OBSERVED: `token-sheriff-validation-0.9.6.jar` ships `META-INF/native-image/.../reflect-config.json` — `unzip -l` of the resolved artifact
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: unzip -l token-sheriff-validation-0.9.6.jar lists META-INF/native-image/.../reflect-config.json

## Expected Surface

- `api-sheriff/src/main/resources/application.properties` §§ 260-337 (drifted from §§ 150-203)
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java`
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/TokenValidatorProducer.java`,
  `JwksTrustProfileResolver.java`, `GatewayValidator.java`
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java`,
  `SheriffMetrics.java` — added 2026-09-22: the D6 (already-landed) and D3-metrics write sites,
  found understated by re-grounding
- Possibly new: a `ConfigSource` under `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/`, if deliverable 2 selects the mapping
- `doc/` — the three-layer documentation, and an ADR if the verdict changes the architecture
- Read-only: the `token-sheriff-validation-quarkus` 0.9.6 artifact (was 0.9.3/0.9.4)

## Dependencies and Sequencing

- **Strongly adjacent to PLAN-38 (ADR-0005 reversal).** PLAN-38's premise is that hand-rolled
  equivalents were built where a Quarkus mechanism already existed, and this plan is the same question
  asked of token-sheriff. **Sequence after PLAN-38** — its ADR verdict sets the standing rule this
  plan's deliverable 2 should apply rather than re-derive.
- **Overlaps `api-sheriff-roadmap` PLAN-08A.** That plan's audit scope already carries the readiness
  gap as a finding (routed there because it ships with `0.1.0`). PLAN-08A **records** the gap;
  this plan **closes** it. Read 08A's audit output before scoping deliverable 3.
- Not release-gating for `0.1.0`; the exclusion is a defensible shipped posture.
- **Re-confirmed 2026-08-06 against a live 0.1.1 cut, on operator challenge: this does NOT move into
  0.1.1.** It is an architecture decision touching the token-validation core, possibly introducing a
  `ConfigSource`, sequenced behind PLAN-V02-01's ADR verdict, on a patch cut already carrying three
  bug fixes plus `api-sheriff-roadmap` PLAN-52. **Issue #173 made the shipped posture MORE defensible,
  not less**: the exclusion is now pinned by a fitness function rather than resting on a comment. The
  cost of waiting is the maintenance tax recorded under D2 — real, but bounded and visible.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-09-token-sheriff-integration-fidelity.md" plan_id=plan-v02-09-token-sheriff-integration-fidelity
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The executing plan MUST NOT create or edit any file under
`.plan/local/orchestrator/api-sheriff-0-2-0/`. Its two channels back to the epic are its PR and its
`inbox/` OUTBOX.
