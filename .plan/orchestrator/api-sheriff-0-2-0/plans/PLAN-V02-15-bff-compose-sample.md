# PLAN-V02-15: give the BFF a runnable sample, so the only complete example is not the test harness

epic: api-sheriff-0-2-0
workstream: WS-02

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
>
> **Owns GitHub issue [#176](https://github.com/cuioss/API-Sheriff/issues/176)**, filed by an
> adopter. No other plan in this epic covers it.

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

The sample's `gateway.yaml` states the omission as a principle:

> Everything the integration suite needs and an operator does not — passthrough SNI, BFF sessions,
> asset anchors, mTLS — is absent rather than present-and-disabled.

**The premise "an operator does not need BFF sessions" is the defect.** For anyone adopting the
gateway as a BFF, it is the one thing they do need. **Amend that sentence; do not delete the
paragraph** — its distinction between test scaffolding and deployment shape is the sample's whole
value. Find the sentence by its text; the file has been edited since it was last located by line.

## What the adopter path costs today

`doc/user/bff-session.adoc` gives a field-by-field guide and no complete working document, so the
only full known-good BFF configuration is
`integration-tests/src/main/docker/sheriff-config/`. Copying from there means inheriting:
container-internal issuer URLs, an `it-static` file-based JWKS issuer, anchors named after test
suites, and route settings authored to exercise assertions rather than to model a deployment.
**Working out which parts are deployment shape and which are test harness is exactly the work the
sample exists to save.**

## The sample as it stands

`deployment/compose-sample/` holds 14 tracked files: `docker-compose.yml`,
`docker-compose.plain-http.yml` (an optional TLS-terminator variant that adds a fourth service),
`.env`, `docker/sheriff-config/{gateway.yaml, topology.properties, endpoints/demo-api.yaml}`,
`docker/keycloak/sample-realm.json`, `docker/nginx/{demo-api.conf, tls-terminator.conf}`,
`docker/certificates/{generate-certificates.sh, .gitignore, sample-idp-trust.properties}` and
`scripts/{start-sample.sh, stop-sample.sh}`.

- The realm import already ships a fully-formed confidential client, `sample-client`
  (`publicClient: false`, client-secret authentication, standard flow enabled, matching redirect
  URIs and web origins).
- No `oidc` block and no `session` key exists anywhere in the sample.
- `endpoints/demo-api.yaml` declares `require: none` and says of that line *"THIS IS THE FIRST THING
  TO CHANGE when adapting the sample"* — the sample anticipates this change and points at it.
- `scripts/start-sample.sh` is the single bring-up path, readiness wait included.
- `sample-realm.json` pins `"frontendUrl": "https://keycloak:8443"`, the container-internal
  authority. That is the addressing defect `PLAN-V02-12` owns; see D5.

## Deliverables

1. **An `oidc` overlay on the existing sample.** The issue's own suggestion, and the right scope
   because the sample already ships Keycloak with a realm import and a confidential client. What is
   missing:
   - an `oidc` block with `session.mode: server` in `docker/sheriff-config/gateway.yaml`, written
     against the block as ADR-0058 left it: PAR and DPoP are mandatory, and client authentication is
     `private_key_jwt` with a generated or provided key unless a client secret is configured. Decide
     which the sample shows, and make the realm's `sample-client` match it
   - one `require: session` route
   - a static page as `final_redirect`
   - whatever the confidential client still needs; verify at outline whether `sample-client` is
     usable as shipped
   - the same reach in the plain-HTTP variant (`docker-compose.plain-http.yml`,
     `docker/nginx/tls-terminator.conf`), so the overlay does not work in one variant only

2. **Keep the sample a sample.** Do NOT import toxiproxy, go-httpbin, the `it-static` issuer, the
   test anchors, or the IT naming. **Every addition must be defensible as something a real
   deployment has.** If a piece of the IT config is needed and is not deployment-shaped, that is a
   finding about the product, not a licence to copy.

3. **Point the two BFF operator guides at it.** `doc/user/bff-session.adoc` and
   `doc/user/bff-cookie.adoc` currently describe fields with nothing runnable to point at; give them
   the reference. Update `doc/user/compose-sample.adoc`, which presents the sample as *"a
   deployment-shaped stack you can run"*. Read the neighbouring `doc/user/anchors.adoc` and
   `doc/user/endpoint-routes.adoc` first; they may already document what this would restate.

4. **Prove it runs, in CI, and state what the proof covers.** A sample that is documented but never
   executed decays into a second stale example — which is the failure this plan exists to fix.
   Minimum: the stack comes up and one `require: session` route completes a login. Reuse
   `scripts/start-sample.sh` as the bring-up path. **Say plainly whether the check is a smoke test or
   a real flow**; a green that only asserts container start would be a vacuous positive control.

5. **Resolve the browser-vs-container issuer address, and do not invent an answer.**

   > **THIS IS THE HARD PART AND IT IS ALREADY OWNED ELSEWHERE.** Keycloak must be reachable *both*
   > from the browser (the auth-code redirect) and from the gateway container (discovery, token
   > exchange), and those are different addresses. That is precisely **PLAN-V02-12's** subject —
   > *"IdP Addressing Model — one issuer identity, a frontchannel/backchannel split"*. The IT config's
   > container-internal issuer URLs are one of the things #176 says an adopter must not copy.
   >
   > **Read PLAN-V02-12's verdict and apply it. Do not settle the addressing model inside a sample**
   > — a sample that picks its own answer becomes the de-facto specification, and the wrong one.

`docker-compose*.yml` and `.github/workflows/**` are gate-requiring paths, so D1 and D4 run the full
pre-commit process.

## Claim Labels

- OBSERVED: `deployment/compose-sample/` contains 14 tracked files; its
  `docker/sheriff-config/` holds `gateway.yaml`, `topology.properties` and `endpoints/demo-api.yaml`.
  A search for an `oidc` block or a `session` key returns nothing; `gateway.yaml` names BFF
  sessions among the deliberate omissions and documents the public anchor's absent auth block;
  `endpoints/demo-api.yaml` is `require: none`.
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: 14 tracked files under deployment/compose-sample; no oidc block or session key; gateway.yaml names BFF sessions among the omissions; demo-api.yaml require: none
- OBSERVED: a Keycloak realm import already ships at
  `deployment/compose-sample/docker/keycloak/sample-realm.json`, and it carries the confidential
  client `sample-client`, so D1's client work is at most an edit rather than new infrastructure.
  - verdict: corroborated | checked_at: 1a20edade64aee1cb92fbddec7352a920fb5b46d | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: sample-realm.json carries sample-client, publicClient false, placeholder secret

## Expected Surface

- `deployment/compose-sample/docker/sheriff-config/gateway.yaml`, `endpoints/**` — D1
- `deployment/compose-sample/docker/keycloak/sample-realm.json` — D1
- `deployment/compose-sample/docker-compose.yml`, `.env` — D1, D2
- `deployment/compose-sample/docker-compose.plain-http.yml` — D1: the plain-HTTP / TLS-terminator variant, which the oidc overlay must also reach
- `deployment/compose-sample/docker/nginx/tls-terminator.conf` — D1: same reason
- `doc/user/compose-sample.adoc`, `bff-session.adoc`, `bff-cookie.adoc` — D3
- `.github/workflows/**` — D4

## Dependencies and Sequencing

- Depends on: **`PLAN-V02-12` (idp-addressing-model)**, for D5. This is the one genuine dependency,
  and ignoring it produces a sample that hard-codes the wrong answer. The plan is emittable earlier
  only if D5 is explicitly deferred, which defers the one deliverable that keeps the sample from
  becoming the de-facto addressing specification. Prefer sequencing after `PLAN-V02-12` over
  emitting a partial.
- Overlaps with: `PLAN-V02-12` on the compose sample and `doc/user/`;
  `PLAN-V02-14` on `.github/workflows/**` and `doc/user/compose-sample.adoc`. The disjointness gate
  decides at emit time.
- Surface note: `deployment/compose-sample/.env` carries the version pins. Any pin bump touches the
  same file; sequence deliberately.

## Issue Closure

**CLOSE [#176](https://github.com/cuioss/API-Sheriff/issues/176) WHEN THIS PLAN LANDS** — comment
naming the PR and merge commit, and close it. If D5's addressing answer forced a shape the issue did
not anticipate, say so on the issue rather than closing silently.

**A PR body that merely mentions an issue does NOT link or close it** — use a closing keyword or
close explicitly after the merge.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-15-bff-compose-sample.md" plan_id=plan-v02-15-bff-compose-sample
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
