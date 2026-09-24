# PLAN-V02-08: FAPI 2.0 Conformance for the Confidential-Client Flow

epic: api-sheriff-0-2-0
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-49-fapi-2-0-conformance.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**THIS SPEC AGED WELL — its Observed Facts cite classes and methods rather than line numbers, and
every one of them still holds.** Verified first-party:

- `BffRuntimeProducer` builds `ClientConfiguration` with `ClientAuthMethod.CLIENT_SECRET_BASIC`
  (:211) and `ClientSecretBasicAuth` (:213); constructs `AuthorizationCodeFlow` at :229 and
  `RefreshFlow` at :232; sets no SSL context.
- **The gateway still holds no reference to `ParClient`.** The only `ParResponse` mentions in the
  whole tree are the GraalVM reflection registrations in `TokenClientDslJsonReflection` (:20, :21,
  :65, :69) — registered for serialization, never called. **This is the exact shape of the epic's
  standing rule that a capability present in a dependency is not a capability of the product**, and
  it is why D6(b)'s reconciliation of `doc/features-analysis.adoc` matters.
- `token-sheriff` resolves to **0.9.4** (`pom.xml`:60).

**DISCHARGED — D6(b)'s precondition.** The spec says all three FAPI documents shipped in PR #152,
*"which was still open when this spec was written"*, and instructs a verify-or-report. **Verified:
all three are on `main`** — `doc/fapi_status.adoc`, `doc/fapi_next_steps.adoc` and
`doc/features-analysis.adoc`. No finding to report; read them as the authoritative starting point
exactly as the Objective instructs, and do **not** author fresh copies.

**ADR numbering** — the corpus is contiguous `0001`–`0037`; **`0038` is next free and genuinely
free**. Re-check against open branches at write time.

### Upstream `#618` — settled 2026-08-08 against the resolved artifact, and the answer is "fixed, but not for us yet"

Operator supplied `cuioss/TokenSheriff` **PR #640** (*"feat(dpop): support EC/OKP keys and
PS256/ES256/EdDSA signing"*), **merged**, which **closes `#618`** — the exact blocker D1 names. It
generalises `DpopProofGenerator` to accept RSA / EC (P-256) / Ed25519 keys and extends the algorithm
dispatch to `PS256`, `ES256` and `EdDSA`, on the JDK baseline with no new dependency.

**D1's instruction — *"Check #618's state at outline; if it has landed, both routes are open"* — is
now ambiguous in a way that would mislead, so read this instead.** #618 has landed **upstream on
`main`**, but it is **not in the artifact this project resolves**, so the routes are *not* both open:

| Check | Result |
|---|---|
| `version.token-sheriff` (`pom.xml`:60) | **0.9.4** |
| Latest published TokenSheriff release | **0.9.4** — nothing published after #640 merged |
| `DpopProofGenerator` in the resolved `token-sheriff-client-0.9.4.jar` | class dated **2026-07-21**; carries `RS256`/`RS384`/`RS512` and `RSAPublicKey` only. **`PS256`, `ES256`, `EdDSA`, `ECPublicKey`, `EdECPublicKey` are all absent.** |

**So the spec's Observed Fact about `DpopProofGenerator` is CONFIRMED, not stale** — it describes the
jar we actually compile against, exactly.

**What this changes for D1.** The DPoP route is blocked *for this project* until (a) a TokenSheriff
release carrying #640 is published **and** (b) `version.token-sheriff` is bumped. That bump is a
build-input change, so it pays the full quality gate and is an operator-approval question under
`CLAUDE.md` § Dependency Management. **Until then the mTLS route remains the only one available
without an upstream release dependency** — which is what the Objective already assumes, so the
plan is not blocked; only the *route decision's* framing changes. **Re-check the published version at
outline** rather than inheriting this line: a release may have landed in between, and that would flip
the answer cleanly.

> **This is the epic's standing rule one level deeper.** *A capability present in a dependency is not
> a capability of the product* — and a capability present in a dependency's **repository** is not a
> capability of its **released artifact**. "Merged" was the word that made this look settled. Settle
> it at the resolved jar, never at the PR.

### The inbound-DPoP HYPOTHESIS is CONFIRMED — and it removes work from this plan

The spec asks whether `DpopProofValidator` resolves algorithms through `SignatureAlgorithmPreferences`
rather than a hardcoded switch, *"which decides whether inbound DPoP support is in scope here at
all."* **Verified in the resolved `token-sheriff-validation-0.9.4.jar`**: `DpopProofValidator`,
`SignatureAlgorithmPreferences`, `EcdsaSignatureFormatConverter` and `JwkThumbprintUtil` are all
present, and `SignatureAlgorithmPreferences` already admits **`RS256`, `RS384`, `RS512`, `PS256`,
`PS384`, `ES256`, `ES384` and `EdDSA`** — the full FAPI 2.0 §5.4.1 set and more.

**The asymmetry the spec suspected is real and now measured: inbound validation was never the
constraint; only the outbound generator was RSA-locked.** Inbound DPoP support is therefore **out of
scope for this plan**. PR #640 is consistent with this — it *extended* those two utility classes
(adding an outbound DER→JOSE conversion and extracting a shared canonical-JSON builder) rather than
creating them.

**RENUMBERING AND SEQUENCING.** "PLAN-46 (WS-07)" is **PLAN-V02-09**, in **WS-05**, and the
sequencing instruction stands: *never concurrent against `BffRuntimeProducer`*. The chain is wider
than this spec knows — **`PLAN-V02-12` (idp-addressing-model) also writes the `oidc` block and
`OidcConfig`**. All three are one sequential chain, and **this plan goes first**: it is the larger
reshaping of that block, and V02-12 inherits its RFC 9207 `iss` constraint.

**Also not concurrent with `PLAN-V02-04`** (ADR corpus audit), whose spec forbids concurrency with
any ADR-authoring plan.

**GATE COST, new since this spec was written.** `.github/workflows/**`, `Dockerfile*` and
`docker-compose*.yml` are now gate-requiring (`build.map`, roadmap PLAN-51 / #196). Any
integration-stack or workflow edit this plan makes pays a full quality gate.


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

**ANCHORS HELD** at `95dd566`: `BffRuntimeProducer` still builds with `CLIENT_SECRET_BASIC` (:211)
and still constructs `RefreshFlow` at :232; `DiscoveryResolver` is still confined to :64/:214.

**THE UPSTREAM DPoP POSITION IS UNCHANGED AND MUST BE RE-CHECKED, NOT INHERITED.** As of 2026-08-08
`#618` was fixed on TokenSheriff `main` (PR #640) and **absent from the resolved 0.9.4 artifact**,
with no release published since. **Re-check the published version at outline** — this is exactly the
class of premise the epic's new standing Watch says must be re-established rather than inherited, and
a release may have landed in between.

**Inbound DPoP remains out of scope** — `SignatureAlgorithmPreferences` at 0.9.4 already admits the
full FAPI 2.0 §5.4.1 set. That finding is stable.

## Re-Grounded (3) 2026-09-22 at `af63895`

**⚠ THE ROUTE CALCULUS IS INVERTED. RE-DERIVE D1/D3/D4/SPLIT-GUARD FROM THIS BEFORE OUTLINE — DO NOT
INHERIT ANY PRIOR RE-GROUNDING'S ROUTE FRAMING.** `api-sheriff/pom.xml`:63 now pins
`version.token-sheriff=0.9.6` (two releases past the 0.9.4 every prior pass checked against). Two
upstream changes shipped in that gap, and together they reverse the plan's central premise:

1. **mTLS is now the FORECLOSED route, not the available one.** TokenSheriff PR #731
   (`b6b1a94d`, in 0.9.6) reclassified `tls_client_auth` as alpha: `MtlsClientAuth`'s constructor now
   **unconditionally throws `UnsupportedOperationException`**, and `ClientAuthenticationSelector`
   never selects `TLS_CLIENT_AUTH`. It is not constructible at the pinned version. The spec's framing
   — *"the mTLS route is unblocked today... the only one available without an upstream release
   dependency"* — is now exactly backwards.
2. **DPoP is now the fully open route.** PR #640 (fixing upstream #618, the RSA-only blocker this
   spec's D1 named) is an ancestor of both the 0.9.5 and 0.9.6 tags. `DpopProofGenerator` at 0.9.6
   admits `RS256/RS384/RS512/PS256/ES256/EdDSA` and RSA/EC-P256/OKP-Ed25519 keys — the exact blocker
   is resolved in the artifact this project now resolves. Upstream's own PR #731 states DPoP is now
   "the recommended client-authentication direction," with `private_key_jwt` as "the supported
   key-based authentication method."

**Consequence.** D1's route decision, D3's `client_secret_basic` → `tls_client_auth`/`private_key_jwt`
replacement, D4's "proof key or client certificate" framing, and the Split-Guard's route-cost
evaluation all assumed the OLD calculus and must be re-derived, not re-verified, at outline.

**Secondary corrections, lower stakes than the above:**

- `BffRuntimeProducer.java`:516-522 already wires an egress `SSLContext` into `backChannelConfiguration`
  when `oidc.egress_tls.oidc_tls_profile` is set (ADR-0040/0041/0045, landed since last grounding) —
  D5's "no SSL context / JVM default trust store" premise needs NARROWING, not re-verifying: the
  trust-anchor configurability already landed, the still-open half is presenting client *key* material
  (a certificate), not trust-anchor configurability.
- `RefreshFlow` construction moved out of `BffRuntimeProducer.java` entirely, into
  `ScopedEngineFlows.java`:142, and now takes a 4-arg constructor (TokenSheriff added a
  `validationBridge` param) — no 3-arg ctor exists any more.
- ADR numbering: corpus is now contiguous `0001`–`0049`; next free is `0050`, not `0038`.
- Expected Surface's 8 entries all still resolve correctly at their stated paths — no path staleness.

## Re-Grounded (4) 2026-09-24 at `05f6ee3` — after 18 commits (#343–#354, release 0.2.3)

Premises hold. Upstream facts re-checked against the pinned `token-sheriff-client-0.9.6` jar (javap): **mTLS is FORECLOSED** (`MtlsClientAuth` ctor throws unconditionally) and **DPoP is OPEN** (`DpopProofGenerator` handles RSA/EC/EdEC). This matches the operator's standing mTLS-alpha / DPoP-direction ruling. The BFF still authenticates with `CLIENT_SECRET_BASIC` (`BffRuntimeProducer.java:329,:533`). No `ParClient` reference exists in production source. The `RefreshFlow` construction site is `ScopedEngineFlows.java:142` (4-arg, no sender constraint), not `BffRuntimeProducer`, so D4's anchor moves there. `doc/fapi_status.adoc` still has three `UNMET` rows. D5 is partially discharged: the egress `SSLContext` is wired (`BffRuntimeProducer.java:539`), and client-certificate presentation is moot while mTLS is foreclosed. A `## Claim Labels` section was added 2026-09-24 (cleanup A3).

**ADR numbering, corrected across the corpus:** `doc/adr/` now holds 55 records. `0053` is DUPLICATED (#348 renamed the portal ADR `0050`→`0053` while #346 claimed `0053` concurrently), and the next free ordinal is `0055`. Every earlier "next free is 0038/0050" line in this spec is stale. Re-derive the ordinal at write time, and prefer landing after `PLAN-V02-19`, which fixes the duplicate and adds an ordinal-uniqueness test.

## Objective

Make API Sheriff's BFF a conformant **FAPI 2.0 Security Profile** relying party. Three of the
profile's mandatory client requirements are unmet at 0.1.0; this plan closes all three and puts the
configuration, key material and upstream contract they need in place.

The assessment this plan starts from is in-tree and current: `doc/fapi_status.adoc` (the
requirement-by-requirement position, with evidence) and `doc/fapi_next_steps.adoc` (the routes,
workstream breakdown and the decisions owed). Both landed on `main` before this plan was staged and
are the authoritative starting point — read them first rather than re-deriving the assessment.

**The work is wiring, configuration and key lifecycle, not architecture.** Every protocol capability
the profile requires, except the DPoP algorithm set, is already implemented in `token-sheriff-client`
and merely unused here.

## Deliverables

### D1 — Route selection and the sender-constraint seam

Decide and record the route — mTLS, DPoP, or a seam carrying both — then build the seam.
`SenderConstraint` already models exactly this as one type with two factory methods
(`SenderConstraint.mtls(thumbprint)` and `SenderConstraint.dpop(generator)`), so the deliverable is
the gateway-side selection and assembly, not a new abstraction.

The mTLS route is unblocked today; the DPoP route is blocked upstream on
`cuioss/TokenSheriff#618` (proof generation is RSA/`RS256`-only, and the profile permits `PS256`,
`ES256` or `EdDSA`). **Check #618's state at outline** — if it has landed, both routes are open and
the decision is made on operational fit rather than availability.

### D2 — Pushed Authorization Requests

Route the authorization request through `ParClient.pushAuthorizationRequest` and redirect with the
returned `request_uri`. `LoginFlow.initiate` changes shape: the front-channel URL then carries only
`client_id` and `request_uri`. PAR is a new outbound leg to the AS — it inherits the existing
back-channel egress and scheme validation, but its timeout and failure handling at login initiation
are new surface.

Do **not** treat this as retiring the `response_mode=query` tradeoff. The authorization *response*
still returns `code`/`state`/`iss` on a URL, so the binding-cookie design is unchanged and
`form_post` remains a separate decision on its own merits.

### D3 — Client authentication

Replace `client_secret_basic` with `tls_client_auth` or `private_key_jwt` per D1's route. Both exist
in the engine as `MtlsClientAuth` and `PrivateKeyJwtAuth` and are selectable through
`ClientAuthMethod`. This is a **breaking configuration change**: `oidc.client_secret` gives way to a
key or certificate reference, and `ConfigLoader`'s secrets-rule pointer list changes with it.

### D4 — Sender constraint wiring and key lifecycle

Wire the D1 constraint into **both** `AuthorizationCodeFlow` (its code-exchange constructor takes
it) and `RefreshFlow` (its four-argument constructor takes it), as the **same instance** — a refresh
presented against a token bound to a different key fails.

Key lifecycle is the substantive part. The proof key or client certificate must outlive the tokens
bound to it, so a key generated at boot silently kills refresh for every live session on restart,
and a per-node key breaks refresh across nodes. That is the same single-node constraint the
in-memory `PendingAuthorizationStore` already imposes, reached by a different route, and it applies
to the sealed-cookie mode too. The key is provisioned deployment material, never a per-process
artifact.

### D5 — Trust and key-material plumbing, config surface, native registration

`ClientConfiguration` is built with no SSL context today, so the BFF's back-channel legs use the JVM
default trust store while the JWKS leg is trust-profile-configurable. Presenting a client
certificate on those legs requires closing that asymmetry.

`JwksTrustProfileResolver` maps a logical profile name to an `SSLContext` and deliberately refuses a
profile carrying no trust anchors; client *key* material is a different shape, so this needs either a
widened contract there or a second seam beside it. Preserve the property that makes the existing one
sound: a named profile means *these anchors*, and never *no verification at all*.

New `oidc` sub-blocks for client authentication and the sender constraint follow, with the secrets
rule applied to any new secret-bearing pointer — **and every new nested configuration record
registered in `ConfigModelReflection`**, which registers the config model explicitly. A record
omitted there boot-fails only in the native image while every JVM-level gate stays green.

### D6 — The `cnf` forwarding contract, and the documentation reconciliation

Two named halves. **Neither may be collapsed into the other or into a generic "update the docs"
line at outline** — the documentation half is the one this plan is most likely to under-deliver,
because it is the half that has no test.

#### D6(a) — the `cnf` forwarding contract

A `require: session` route injects the mediated token upstream as `Authorization: Bearer`
(`SessionAuthenticationStage`). Once that token is sender-constrained it carries `cnf`. A
`cnf`-blind upstream accepts it unchanged; a DPoP-aware upstream must reject it for want of a proof.
Resolve this explicitly — either document that upstreams must not validate `cnf`, or stop forwarding
the bound token by exchanging it at the gateway (RFC 8693) for an upstream-scoped one.

#### D6(b) — reconcile the three FAPI documents

**Three documents describe this plan's subject and all three become wrong the moment it lands.**

**Precondition, check it first.** All three were authored on 2026-08-04 and shipped in **PR #152,
which was still open when this spec was written**. At outline, verify they are on `main`. If that PR
never landed, that is a **finding** — report it and re-scope. Do **not** author them fresh: they
carry review history and an evidence trail, and a second hand-authored copy would diverge from the
one under review.

1. **`doc/fapi_status.adoc`** — its requirement matrix carries three `UNMET` rows this plan closes,
   plus a `N/A today, blocking later` row for signing algorithms that becomes live the moment
   `private_key_jwt` or DPoP is wired. Move each to `MET` **with its evidence updated to the call
   site**, not to the import. Its `IMPORTANT` verdict block names a version and states plainly that
   0.1.0 is not conformant — rewrite it rather than leaving a stale verdict at the top of the page.
2. **`doc/fapi_next_steps.adoc`** — ***this plan is that document's content.*** Once it lands, the
   document either describes finished work as future — which is **exactly the false-claim class that
   produced this plan** — or it must be retired, or rewritten to carry only what genuinely remains
   (most likely the DPoP route, if only the mTLS route landed). **Decide which and act; do not leave
   it.** If it is retired, remove its `doc/README.adoc` index entry with it.
3. **`doc/features-analysis.adoc`** — its differentiator currently carries a *TARGET, NOT SHIPPED*
   admonition and its summary matrix row reads `Planned (not in 0.1.0)`. Restate both **to what
   actually shipped**, never to what this plan intended to ship.

**The claim discipline this plan inherits:** a conformance claim is settled by a **conformance-suite
run**, never by inspection and never by a completed deliverable list. Until such a run exists the
honest wording is "implements the FAPI 2.0 controls", not "FAPI 2.0 conformant" — and if only one of
the two sender-constraining routes landed, say which.

## Observed Facts

All verified first-party on 2026-08-04 at `main` `b39b271`, against the shipped source of both
repositories. Cited by class and method rather than line number, which decays.

- **OBSERVED** — `BffRuntimeProducer` builds `ClientConfiguration` with
  `ClientAuthMethod.CLIENT_SECRET_BASIC` and `ClientSecretBasicAuth`, constructs
  `AuthorizationCodeFlow` with a `null` `senderConstraint`, and uses `RefreshFlow`'s three-argument
  constructor. It sets no SSL context.
- **OBSERVED** — the gateway holds no reference to `ParClient`. The only mention of `ParResponse` in
  the repository is a GraalVM reflection registration in `TokenClientDslJsonReflection`, which
  registers the type for serialization and never calls it.
- **OBSERVED** — `ParClient.pushAuthorizationRequest` is complete and returns a `ParResponse`
  carrying `request_uri`.
- **OBSERVED** — `MtlsClientAuth` and `PrivateKeyJwtAuth` exist; `ClientAuthMethod` carries
  `PRIVATE_KEY_JWT` and `TLS_CLIENT_AUTH`.
- **OBSERVED** — `DpopProofGenerator` rejects non-RSA key pairs, serializes an RSA-only header `jwk`,
  computes `jkt` RSA-only, and its algorithm switch admits `RS256`/`RS384`/`RS512` and throws
  otherwise.
- **OBSERVED** — `SenderConstraint.mtls(...)` records a transport-level binding: no header is added
  and no signing algorithm is involved.
- **OBSERVED** — the engine's `AuthorizationRequestBuilder` always emits `code_challenge` /
  `code_challenge_method=S256` and throws when the provider does not advertise `S256`;
  `AuthorizationCodeFlow.exchange` applies `IssValidator` and requires `iss` when the provider
  advertises support.
- **OBSERVED** — FAPI 2.0 Security Profile (Final): §5.3.3.1 requires sender-constrained tokens via
  mTLS and/or DPoP and permits client authentication by mTLS or `private_key_jwt`; §5.3.3.2 requires
  PAR, PKCE `S256` and the RFC 9207 `iss` check; §5.4.1 restricts signing to `PS256`, `ES256` or
  `EdDSA` (Ed25519).
- **HYPOTHESIS**, verify at outline — `DpopProofValidator` resolves algorithms through
  `SignatureAlgorithmPreferences` rather than a hardcoded switch, so the inbound side may already
  admit the wider algorithm set. Confirm or refute at `DpopProofValidator` and the default
  `SignatureAlgorithmPreferences`; it decides whether inbound DPoP support is in scope here at all.
- **HYPOTHESIS**, verify at outline — Keycloak's support for `tls_client_auth` and
  certificate-bound tokens in the deployed realm configuration. The route decision in D1 depends on
  it, and an asserted presence needs the same verification as an asserted absence.

## Claim Labels

Added 2026-09-24 by the cleanup pass (A3 ambiguity: the spec had no claim section). Each claim was corroborated at `05f6ee3`.

- OBSERVED: mTLS client auth is foreclosed at the pinned engine — `token-sheriff-client-0.9.6` `MtlsClientAuth` constructor throws `UnsupportedOperationException` unconditionally
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: javap MtlsClientAuth ctor unconditionally throws UnsupportedOperationException (token-sheriff-client-0.9.6)
- OBSERVED: DPoP is available at the pinned engine — `DpopProofGenerator` supports RSA/EC/EdEC keys (RS256/PS256/ES256/EdDSA)
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: javap DpopProofGenerator RSA/EC/EdEC -> RS256/PS256/ES256/EDDSA
- OBSERVED: the BFF still authenticates with `CLIENT_SECRET_BASIC` — `BffRuntimeProducer.java:329` `ClientSecretBasicAuth`, `:533`
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: BffRuntimeProducer.java:329 ClientSecretBasicAuth, :533 CLIENT_SECRET_BASIC
- OBSERVED: no production reference to `ParClient` — whole-tree search over `api-sheriff/src/main/java`, control-queried
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: grep ParClient over api-sheriff/src/main/java: zero hits
- OBSERVED: `RefreshFlow` is constructed at `ScopedEngineFlows.java:142` with 4 args and no sender constraint — D4's anchor
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ScopedEngineFlows.java:142-143 4-arg RefreshFlow, no SenderConstraint
- OBSERVED: `doc/fapi_status.adoc` carries three `UNMET` rows (`:43`, `:50`, `:58`)
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: doc/fapi_status.adoc :43/:50/:58 UNMET; :63/:69 MET

## Expected Surface

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/BffRuntimeProducer.java` — the
  assembly point for D1–D4
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/login/LoginFlow.java` — D2
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` and
  `config/load/ConfigLoader.java` — D3, D5
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/ConfigModelReflection.java` — D5
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/auth/JwksTrustProfileResolver.java` — D5
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/runtime/SessionAuthenticationStage.java`
  — D6
- `doc/fapi_status.adoc`, `doc/fapi_next_steps.adoc`, `doc/features-analysis.adoc`,
  `doc/configuration.adoc` and the `doc/user/` BFF pages — D6
- `integration-tests/` — the BFF suites and the realm configuration the route decision touches

## Dependencies and Sequencing

- **D1 sequences everything.** D3, D4 and D5 all resolve differently per route.
- **D6's forwarding decision is a prerequisite, not a follow-up** — it determines whether
  conformance is a gateway-local property or a contract change reaching every backend. Settle it
  before D4 is implemented, not after.
- **`cuioss/TokenSheriff#618`** gates the DPoP route only. It does not block this plan: the profile
  accepts mTLS *or* DPoP, and the mTLS route needs no upstream change.
- This plan touches the BFF assembly point that **PLAN-46** (WS-07) also studies. If both are live,
  sequence them — do not run them concurrently against `BffRuntimeProducer`.
- The `oidc` block change is **breaking**, so it wants to land before any external consumer depends
  on the 0.1.0 shape.

## Split-Guard Evaluation

Six deliverables — at the presumptive split threshold, evaluated and **proceeding unsplit**, with
the rationale recorded as the guard requires. The parts do not ship independently: D3 and D4 both
resolve against D1's route, D5 supplies the material both consume, and shipping PAR (D2) alone would
close one requirement while leaving the profile unmet, which delivers no claimable property.

**If it must be split later, the line is D1–D3 + D5 (the credential and configuration half, which
closes two of the three unmet requirements) and D4 + D6 (sender-constraining and the upstream
contract, which carry the key-lifecycle constraint and the only decision reaching outside the
gateway).** Do not split anywhere else — any other cut separates a route decision from its
consequences.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-08-fapi-2-0-conformance.md" plan_id=plan-v02-08-fapi-2-0-conformance
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

*Added 2026-08-08 — this spec was staged without the section every other spec in the epic carries.*

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message —
the orchestrator owns every other ledger write — and reports its outcome through its PR and its
inbox message.
