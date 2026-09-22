# PLAN-V02-15: give the BFF a runnable sample, so the only complete example is not the test harness

epic: api-sheriff-0-2-0
workstream: WS-02

> **Owns GitHub issue [#176](https://github.com/cuioss/API-Sheriff/issues/176)**, filed 2026-08-06 by
> an adopter, routed here 2026-08-07. **Deliberately NOT folded into PLAN-V02-03
> (documentation-restructure)** — that plan deletes `doc/archive/`, splits `doc/configuration.adoc`
> and retires `doc/plan/`. Adding a runnable deployment artifact to a deletion-and-split plan mixes
> two unrelated risks.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**CONFIRMED — every substantive claim holds, line numbers included.**
`deployment/compose-sample/docker/sheriff-config/gateway.yaml`:11 still names BFF sessions among the
deliberate omissions (*"…passthrough SNI, BFF sessions, asset anchors…"*) and :44 still documents the
public anchor's absent auth block. `endpoints/demo-api.yaml`:18 still carries *"THIS IS THE FIRST
THING TO CHANGE when adapting the sample"* and :21 is still `require: none`. The Keycloak realm
import is present at `docker/keycloak/sample-realm.json`, so D1's confidential client is an edit
rather than new infrastructure. No `oidc` block or `session` key exists in the sample.

**COUNT CORRECTION.** The Claim Label says the sample directory *"contains exactly ten files"*. It
holds **12**: `docker-compose.yml`, `.env`, `docker/sheriff-config/{gateway.yaml,
topology.properties, endpoints/demo-api.yaml}`, `docker/keycloak/sample-realm.json`,
`docker/nginx/demo-api.conf`, `docker/certificates/{generate-certificates.sh,.gitignore}` and
`scripts/{start-sample.sh,stop-sample.sh,wait-for-ready.sh}`. The three `scripts/` entries matter to
D4 — the CI proof has an existing bring-up path to reuse rather than invent.

**D5's DEPENDENCY IS UNCHANGED AND STILL THE HARD PART.** `PLAN-V02-12` (idp-addressing-model) still
owns the browser-vs-container issuer question, and its root-cause finding re-verified this pass:
`sample-realm.json`:7 pins `"frontendUrl": "https://keycloak:8443"` — the internal authority — so the
sample carries the same defect the IT topology does. **Read V02-12's verdict and apply it; do not
settle the addressing model inside a sample.** V02-12 also has not started, so this plan waits on it.

**GATE COST, new since this spec was written.** `docker-compose*.yml` and `.github/workflows/**` are
now gate-requiring (`build.map`, roadmap PLAN-51 / #196). D1's compose edit and D4's CI wiring each
pay a full quality gate — and the `.env` surface note below is now a gate-requiring file too.

## Re-Grounded (3) 2026-09-22 at `af63895`

**COUNT CORRECTED AGAIN, AND D4'S BRING-UP PATH CHANGED.** `deployment/compose-sample/` now holds
**14** files, not the 12 the prior correction recorded: `docker-compose.plain-http.yml` (a
TLS-terminator variant, per `doc/user/compose-sample.adoc`'s "optional override file adds a fourth
service"), `docker/nginx/tls-terminator.conf` and `docker/certificates/sample-idp-trust.properties`
are new. **`scripts/wait-for-ready.sh` no longer exists** — #230 deleted it and folded its readiness
logic into `scripts/start-sample.sh` (now ~430 lines). D4's "existing bring-up path to reuse" is still
true, but it is `start-sample.sh` alone now, not the three-script set the prior correction named.
Sub-facts unaffected: no `oidc`/`session` key (grep clean across all 14 files), `gateway.yaml`:11
still names BFF sessions among the omissions verbatim, `demo-api.yaml`:21 is still `require: none`;
the `gateway.yaml`:44 anchor-omission comment has moved to :62 (a new tls_profile/SSRF-egress comment
block was inserted above it) — re-anchor by content, not line.

**D1 MAY ALREADY BE PARTIALLY SATISFIED.** `docker/keycloak/sample-realm.json`:26-49 already ships a
fully-formed confidential client `sample-client` (`publicClient:false`,
`clientAuthenticatorType:client-secret`, `standardFlowEnabled:true`, matching `redirectUris`/
`webOrigins`/`defaultClientScopes`), present since the file's creation (#150) and untouched since.
Verify at outline whether only `gateway.yaml`'s `oidc` block plus a session route remain for D1/D2.

**D5's DEPENDENCY STILL HOLDS.** `PLAN-V02-12` is still `staged` in `status.json`; `OidcConfig.java`
still declares a single `@Nullable String issuer` with no backchannel/discovery split. Nothing to
re-scope there.

**EXPECTED SURFACE IS NOW UNDERSTATED FOR D1/D2/D3** — see the Expected Surface section below for the
corrected entries; the two new compose-tree files above and the doc-page-count drift both bear on it.

**ADJACENCY, sharpened.** `PLAN-V02-03` (documentation-restructure) is about to restructure
`doc/user/` — including `compose-sample.adoc`, which D3 edits. That page did **not** exist when
V02-03's spec was written and is one of three `doc/user/` pages V02-03's re-grounding newly
identified. **Sequence deliberately with V02-03**; if it lands first, D3's target has moved.


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

**ANCHORS HELD** at `95dd566`: `deployment/compose-sample/` still holds **12** files (the corrected
count), and the sample's `gateway.yaml` / `endpoints/demo-api.yaml` seed lines are unchanged.

**D3's TARGET MOVED AND GREW.** `doc/user/` is now **ten** pages; `compose-sample.adoc` survives and
is still the page D3 updates, but `anchors.adoc` and `endpoint-routes.adoc` are new neighbours that
may already document what D3 would otherwise restate. Read the landed layout first.

**D5's dependency on `PLAN-V02-12` is unchanged and still hard** — V02-12 has not started, so this
plan cannot complete D5. **It is emittable only if D5 is explicitly deferred**, and deferring D5
means deferring the one deliverable that keeps the sample from becoming the de-facto addressing
specification. Prefer sequencing after V02-12 over emitting a partial.

## Objective

Make the BFF — the feature most adopters take this gateway for — runnable from the sample, so nobody
has to reconstruct it from a test harness the sample's own documentation tells them not to copy.

## The trap, stated precisely because the sample's reasoning is mostly RIGHT

`deployment/compose-sample/docker-compose.yml` says:

> This is a SAMPLE, not a test harness. It deliberately carries none of the integration-tests
> scaffolding […] because every one of those exists to exercise a test, not to show an operator how
> the gateway is deployed.

**That reasoning is correct for toxiproxy and go-httpbin, and it must survive this plan.** What it
also excludes is the `oidc` block — which is **not test scaffolding**. It is the deployment door for
the headline feature.

The sample's `gateway.yaml`:11 states the omission as a principle:

> Everything the integration suite needs and an operator does not — passthrough SNI, BFF sessions,
> asset anchors, mTLS — is absent rather than present-and-disabled.

**The premise "an operator does not need BFF sessions" is the defect.** For anyone adopting the
gateway as a BFF, it is the one thing they do need. **Amend that sentence; do not delete the
paragraph** — its distinction between test scaffolding and deployment shape is the sample's whole
value.

## What the adopter path costs today

`doc/user/bff-session.adoc` gives a field-by-field guide and no complete working document, so the
only full known-good BFF configuration is
`integration-tests/src/main/docker/sheriff-config/`. Copying from there means inheriting:
container-internal issuer URLs, an `it-static` file-based JWKS issuer, anchors named after test
suites, and route settings authored to exercise assertions rather than to model a deployment.
**Working out which parts are deployment shape and which are test harness is exactly the work the
sample exists to save.**

## Deliverables

1. **An `oidc` overlay on the existing sample.** The issue's own suggestion, and it is the right
   scope because the sample already ships Keycloak with a realm import. Missing pieces:
   - a **confidential client** in `docker/keycloak/sample-realm.json`
   - an `oidc` block with `session.mode: server` in `docker/sheriff-config/gateway.yaml`
   - one `require: session` route (today `endpoints/demo-api.yaml`:21 is `require: none`, and :18
     already says *"THIS IS THE FIRST THING TO CHANGE when adapting the sample"* — so the sample
     anticipates this change and points at it)
   - a static page as `final_redirect`

2. **Keep the sample a sample.** Do NOT import toxiproxy, go-httpbin, the `it-static` issuer, the
   test anchors, or the IT naming. **Every addition must be defensible as something a real
   deployment has.** If a piece of the IT config is needed and is not deployment-shaped, that is a
   finding about the product, not a licence to copy.

3. **Point the two BFF operator guides at it.** `doc/user/bff-session.adoc` and
   `doc/user/bff-cookie.adoc` currently describe fields with nothing runnable to point at; give them
   the reference. Update `doc/user/compose-sample.adoc`, which presents the sample as *"a
   deployment-shaped stack you can run"*.

4. **Prove it runs, in CI, and state what the proof covers.** A sample that is documented but never
   executed decays into a second stale example — which is the failure this plan exists to fix.
   Minimum: the stack comes up and one `require: session` route completes a login. **Say plainly
   whether the check is a smoke test or a real flow**; a green that only asserts container start
   would be exactly the vacuous positive control this project has been bitten by before.

5. **Resolve the browser-vs-container issuer address, and do not invent an answer.**

   > **THIS IS THE HARD PART AND IT IS ALREADY OWNED ELSEWHERE.** Keycloak must be reachable *both*
   > from the browser (the auth-code redirect) and from the gateway container (discovery, token
   > exchange), and those are different addresses. That is precisely **PLAN-V02-12's** subject —
   > *"IdP Addressing Model — one issuer identity, a frontchannel/backchannel split"*. The IT config's
   > container-internal issuer URLs are one of the things #176 says an adopter must not copy.
   >
   > **Read PLAN-V02-12's verdict and apply it. Do not settle the addressing model inside a sample**
   > — a sample that picks its own answer becomes the de-facto specification, and the wrong one.

## Claim Labels

- OBSERVED (2026-08-07, `b8dde22`): `deployment/compose-sample/` contains exactly ten files; its
  `docker/sheriff-config/` holds `gateway.yaml`, `topology.properties` and `endpoints/demo-api.yaml`.
  A search for an `oidc` block or a `session` key returns nothing; `gateway.yaml`:11 names BFF
  sessions among the deliberate omissions and :44 documents the public anchor's absent auth block;
  `endpoints/demo-api.yaml`:21 is `require: none`.
  - verdict: contradicted | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: File count now 14, not 10/12: docker-compose.plain-http.yml, docker/certificates/sample-idp-trust.properties, docker/nginx/tls-terminator.conf are new; scripts/wait-for-ready.sh was deleted (#230), folded into start-sample.sh. Absorbed into spec as new Re-Grounded (3) section.
- OBSERVED (2026-08-07): a Keycloak realm import already ships at
  `deployment/compose-sample/docker/keycloak/sample-realm.json`, so D1's confidential client is an
  edit rather than new infrastructure.
  - verdict: corroborated | checked_at: af638952bc02aadda158c78668ccf0960fa379ba | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: sample-realm.json:26-49 still ships a fully-formed confidential client (sample-client, publicClient:false, client-secret auth). D1's confidential client premise holds and may already be partially satisfied -- noted in Re-Grounded (3).

## Expected Surface

- `deployment/compose-sample/docker/sheriff-config/gateway.yaml`, `endpoints/**` — D1
- `deployment/compose-sample/docker/keycloak/sample-realm.json` — D1
- `deployment/compose-sample/docker-compose.yml`, `.env` — D1, D2
- `doc/user/compose-sample.adoc`, `bff-session.adoc`, `bff-cookie.adoc` — D3
- `.github/workflows/**` — D4

## Dependencies and Sequencing

- **Sequence after PLAN-V02-12 (idp-addressing-model)**, or at minimum read its verdict before D5.
  See D5 — this is the one genuine dependency and ignoring it produces a sample that hard-codes the
  wrong answer.
- **Surface note**: `deployment/compose-sample/.env` was edited by PR #187 (0.1.1 version pins). Any
  further pin bump touches the same file; sequence deliberately.
- Surface-disjoint from PLAN-V02-13 and PLAN-V02-14.

## Issue Closure

**CLOSE [#176](https://github.com/cuioss/API-Sheriff/issues/176) WHEN THIS PLAN LANDS** — comment
naming the PR and merge commit, and close it. If D5's addressing answer forced a shape the issue did
not anticipate, say so on the issue rather than closing silently.

**A PR body that merely mentions an issue does NOT link or close it** — use a closing keyword or
close explicitly after the merge. Issues #182/#183 sat open after their implementing PR landed for
exactly this reason.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-15-bff-compose-sample.md" plan_id=plan-v02-15-bff-compose-sample
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message.
