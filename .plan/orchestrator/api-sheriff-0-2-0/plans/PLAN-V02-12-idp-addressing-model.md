# PLAN-V02-12: IdP Addressing Model — one issuer identity, a frontchannel/backchannel split, and the `/auth`-fronted variant

epic: api-sheriff-0-2-0
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
>
> Operator ruling behind this plan: *"The IDP exposing directly is a bug. Some DNS trickery is not
> solution … Make a thorough research on this on how to handle sensibly (no hacks, engineering)."*

## Objective

The gateway's BFF cannot today address an identity provider that is reachable at different
network locations from the browser and from the gateway. The shipped test and sample topologies
work around that with a **browser-side DNS override**, which is not an engineering solution and
cannot exist in a real deployment. This plan replaces the workaround with the mechanism the
specifications actually permit — **one issuer identity, two transport paths** — and then ships
the `/auth`-fronted Keycloak deployment variant that the corrected model makes expressible.

The gateway ships no IdP. The direct exposure is a property of the test and sample topologies rather
than of the product.

## The constraint that forecloses the obvious design

Read this before scoping. It is the reason "an internal issuer URL and an external issuer URL"
is **not available** and must not be reintroduced under any name:

- **RFC 8414 §3.3** — the `issuer` in the metadata document MUST be identical to the issuer
  identifier used to construct the `.well-known` URL. *"If these values are not identical, the
  data contained in the response MUST NOT be used."*
- **RFC 9068** — the issuer obtained at discovery MUST exactly match the token's `iss` claim.
- **RFC 9207** — the `iss` authorization-response parameter MUST be identical to the metadata
  `issuer`. FAPI 2.0 requires that parameter, so PLAN-V02-08 inherits this constraint.

The exact-match rule is an anti-impersonation control: an attacker publishes metadata claiming
the victim's issuer identifier but carrying its own endpoints and signing keys. **Two issuer
values is the thing the rule exists to prevent, not a configuration style.**

The legitimate split is **transport, not identity**:

- **frontchannel** — authorization and end-session endpoints, login UI. The *browser* goes here.
  Must be reachable and certificate-valid from outside.
- **backchannel** — token, JWKS, introspection. The *gateway* goes here. May stay internal.

Both advertise the same issuer string.

## Deliverables

1. **A backchannel/discovery override on the `oidc` block — the enabling production change.**

   `OidcConfig` today carries a single `issuer` from which discovery derives *both* the
   browser-facing authorization endpoint and the gateway-facing token endpoint. That single
   field is the defect.

   **The correct shape already exists in this codebase and must be mirrored rather than
   invented**: `token_validation.issuers[]` already carries `issuer` (the expected `iss`) and
   `jwks.url` as *independent* keys, so the bearer path can already validate against a public
   identity while fetching keys internally. The BFF path did not get the same treatment. Close
   that asymmetry.

   Secure default is mandatory: **absent means today's behaviour** (one authority for
   everything). The override widens nothing on its own and must never be inferred from request
   headers — deriving a backchannel target from an inbound header is an SSRF primitive. It is
   also subject to the existing egress allowlist, not exempt from it.

   **Hard checklist item:** if this adds a config record, register it in `ConfigModelReflection`.
   An unregistered record boots fine on the JVM and fails **only** in the native image, and one
   `treeToValue` masks all but the first omission.

2. **Repoint the canonical identity outward and delete the resolver hack — the suite is the
   proof.**

   All three realm imports pin the **internal** authority as canonical
   (`"frontendUrl": "https://keycloak:8443"`). That is the root cause, and it is inverted: the
   canonical identity must be the externally reachable one, with the internal hop as the special
   case. Repoint them, then **remove `--host-resolver-rules` from `demo-client/playwright.config.js`
   entirely** — including `hostResolverRule()` and its doc references.

   **A green Playwright suite with that flag deleted is this deliverable's evidence.** The suite
   currently passes *because of* the hack, so removing it is the test. Do not declare D2 complete
   on a suite that still carries the flag, and do not substitute a narrower assertion for the
   run — a compile is not a run.

3. **Ship the `/auth`-fronted Keycloak deployment variant as a documented sample.**

   The gateway fronts the IdP under `/auth/*` on its own origin, so browser and IdP share one
   origin and one authority. Keycloak runs with `--hostname https://{gw}/auth` (a full URL
   including the context path is supported) plus `proxy-headers=xforwarded`; `http-relative-path`
   is the alternative seam. **No IdP goes into any shipped image** — this is deployment sample
   material only, in the same class as `deployment/compose-sample`.

   **Expose an allowlist, never a catch-all.** Per the vendor's own recommendations:
   `/realms/`, `/resources/` (without it the login UI is broken), `/.well-known/`, and `/lb-check`
   are exposed; `/admin/`, `/realms/master/`, `/metrics`, `/health` and the management port are
   **not**. The documented attack on `/admin/` is *relative and non-normalized paths* reaching the
   admin application — the gateway canonicalizes before routing and matches anchors on the
   canonical form, so state that as the sample's concrete advantage over a naive `location` block
   and **prove it with a test**, rather than asserting it.

   **The wrinkle that must be designed for, not discovered:** when the gateway fronts the IdP it
   is simultaneously the fronting proxy *and* the OIDC client, so discovery against the fronted
   issuer would loop back through its own listener. **This variant therefore consumes D1 rather
   than replacing it** — the backchannel override is what keeps the gateway's own token and JWKS
   calls on the internal hop. If the outline finds a design in which D3 needs no part of D1, that
   is a finding worth reporting, not a shortcut to take silently.

4. **Relocate the reserved BFF paths off `/auth`, and rename the info endpoint.**

   D3 makes this mandatory rather than cosmetic: `/auth/*` becomes the IdP namespace, so the
   reserved paths must move. That is the seven BFF paths (callback, logout, logout return, back-channel, userinfo, login, step-up) plus the client-authentication JWKS endpoint
   ADR-0058 added, `oidc.client_authentication.jwks_path`, whose default is `/auth/jwks`; re-count at
   outline. Three facts bound the work:

   - **Reserved paths are not anchors.** They are exact-match carve-outs bound to the OIDC host
     and resolved *ahead of* the route table, so a common prefix is a naming convention that
     nothing validates. Say so in the docs; do not imply a structure that does not exist.
   - **`/bff` is already taken** — the integration sample declares a `bff` anchor
     (`path_prefix: /bff`, `type: proxy`, `access: public`) and a `bff-session` anchor. Nesting
     reserved paths inside an existing *public proxy* prefix works by construction but is the
     most confusing arrangement available. Rename those anchors out of the way.
   - **`userinfo` is a standard OIDC endpoint name** (OIDC Core §5.3). The gateway's endpoint is a
     different thing — an operator-allowlist-capped projection of claims from the session,
     never a proxy of the IdP's UserInfo and never carrying token material. Under a prefix a
     reader takes to mean "the IdP", the name actively misleads. Rename it. Note that
     **Keycloak's own legacy root was `/auth`** (`/auth/realms/…` before KC 17), which is exactly
     why a Keycloak-shaped reader misreads the current layout.

   This is a **browser-facing contract change**: it reaches the SPA, the Playwright specs, every
   `sheriff-config*` overlay under `integration-tests/src/main/docker/` and the operator docs —
   count the overlay directories at outline, there are more than the four variants this plan was
   first scoped against. ADR-0018 already anticipates the move — *"operators can move a reserved
   path out of a namespace they need"*, and *"those namespaces proxied must relocate the reserved
   paths instead."*

   **Give the relocated paths the canonical-form check the others have.** `oidc.login.path` and
   `oidc.user_info.path` are accepted at boot without the canonical-form check the other reserved
   paths get (left open by `PLAN-V02-08`, #377). Add it in `ConfigValidator` with a test while the
   paths move.

5. **Write the ADR, and reconcile the documents this plan invalidates.**

   **No ADR covers the IdP-addressing decision.** That is an asserted absence: re-establish it at
   outline by enumerating every record in `doc/adr/`, not by grep alone. Then write the record: one
   issuer identity, the frontchannel/backchannel split, whether the gateway fronts the IdP, and the
   exposure allowlist. Derive its ordinal from `doc/adr/` on the branch at write time; a duplicate
   fails the build. Record the rejected alternatives explicitly, because they are the load-bearing
   part and they are what future readers will otherwise re-propose:

   - browser-side DNS overrides (`--host-resolver-rules`, `/etc/hosts`) — what is in the tree now;
   - rewriting the discovery document in flight — breaks the §3.3 identity check by construction;
   - two issuer values — breaks `iss` validation and RFC 9207;
   - validating `iss` against the internal URL while the browser uses the public one — the
     current arrangement, and the one that reads most plausible.

   Reconcile:
   - `doc/configuration.adoc`'s OIDC exhibit, which is **internally inconsistent today**
     (`/auth/callback`, `/auth/login`, `/auth/userinfo` beside `/logout`, `/logout/done`,
     `/logout/backchannel` — two conventions, one block, no explanation). Find it by content
     (`redirect_uri`, `/auth/login`, `/auth/userinfo`).
   - the `doc/user/` pages that document the BFF and the anchor namespace — `bff-session.adoc`,
     `bff-cookie.adoc`, and whichever of `anchors.adoc` and `endpoint-routes.adoc` describes the
     reserved paths;
   - the demo-client integrator and contributor documents;
   - `doc/development/integration-test-topology.adoc` **plus its hand-authored SVG**, which does not
     regenerate and which CI does not check. If the topology changes, redraw from the current file:
     its collapsed group reads `variant instances (11)` and already includes
     `api-sheriff-plain-mgmt`. There is no outstanding omission in that diagram to close.

`docker-compose*.yml` is a gate-requiring path, so D3 and D4 run the full pre-commit process.

**Deliverable count is five, under the split guard.** The natural split line if it grows: D1+D2
are the *correctness* half (the model and its proof) and stand alone; D3+D4+D5 are the *namespace
and sample* half and depend on the first. Split there, never mid-way through D4 — a half-renamed
reserved-path namespace is a broken contract rather than a partial one.

## Claim Labels

- OBSERVED: `OidcConfig` carries a single `issuer` and no backchannel or discovery override —
  read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java`
  § the record header.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: OidcConfig has a single nullable issuer and no backchannel or discovery override
- OBSERVED: `token_validation.issuers[]` already carries `issuer` and `jwks.url` as independent
  keys — read at `integration-tests/src/main/docker/sheriff-config/gateway.yaml`.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: integration gateway.yaml token_validation issuers carry issuer and a separate jwks block per issuer
- OBSERVED: all three realm imports pin the internal authority as canonical — read at
  `integration-tests/src/main/docker/keycloak/integration-realm.json`,
  `.../benchmark-realm.json` and
  `deployment/compose-sample/docker/keycloak/sample-realm.json`, each
  `"frontendUrl": "https://keycloak:8443"`.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: frontendUrl https://keycloak:8443 in integration-realm.json, benchmark-realm.json and the compose-sample realm
- OBSERVED: the browser reaches the IdP only via a Chromium host-resolver rule — read at
  `demo-client/playwright.config.js` § `hostResolverRule()`, emitting
  `MAP keycloak:8443 127.0.0.1:1443`, and documented in
  `demo-client/doc/playwright-suite.adoc`.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: playwright.config.js hostResolverRule() maps keycloak:8443; playwright-suite.adoc documents it
- OBSERVED: Keycloak is published directly on the host in both topologies, and no route in any
  shipped configuration proxies it — read at `integration-tests/docker-compose.yml` § `keycloak.ports`
  (`1443:8443`, `1090:9000`) and `deployment/compose-sample/docker-compose.yml`. The
  gateway's only contact is egress (JWKS + back channel), host-exact allowlisted.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: integration compose publishes keycloak 1443:8443 and 1090:9000; compose-sample 127.0.0.1:1443:8443; no route targets keycloak
- OBSERVED: the BFF reserved paths are configuration-derived — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/ReservedPathRegistry.java` — with
  ONE product default: `OidcConfig.ClientAuthenticationSettings.DEFAULT_JWKS_PATH = /auth/jwks`
  (ADR-0058). Every other `/auth` path is a sample convention; that one is a product constant, so D4's
  relocation changes a product default, not only the samples.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ReservedPathRegistry derives all paths from OidcConfig; the only /auth literal in main is DEFAULT_JWKS_PATH /auth/jwks
- OBSERVED: `/bff` and `/bff-session` anchors already exist — read at
  `integration-tests/src/main/docker/sheriff-config/gateway.yaml`.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: integration gateway.yaml declares path_prefix /bff and /bff-session
- OBSERVED: the seven configurable BFF reserved paths currently live under `/auth` — read at the same file.
  - verdict: contradicted | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: yes | evidence: seven configurable BFF reserved paths sit under /auth (callback, logout, logout/return, backchannel, userinfo, login, step-up) plus the JWKS path, not six; claim and D4 re-scoped
- OBSERVED: `doc/configuration.adoc` mixes `/auth/*` and `/logout*` conventions inside one
  exhibit.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: doc/configuration.adoc oidc exhibit still mixes /auth/callback, /auth/login, /auth/userinfo with /logout paths
- OBSERVED: no ADR covers IdP addressing or exposure — a title search over the corpus finds none.
  **An asserted absence: re-verify by enumeration at outline, not by grep alone.**
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: enumerated all 62 ADR titles: none concerns IdP addressing or exposure
- OBSERVED: ADR-0018 anticipates the relocation — read at
  `doc/adr/0018-BFF_session_mode_is_one_SessionBinding_seam_behind_a_fixed_reserved-path_and_CSRF_model.adoc`.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ADR-0018: an operator wanting a reserved namespace proxied must relocate the reserved paths
- OBSERVED: the topology SVG `doc/resources/diagrams/integration-test-topology.svg` shows
  `variant instances (11)` including `api-sheriff-plain-mgmt`; the diagram carries no disclosed
  omission, so this plan owes no gap-closing redraw.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: integration-test-topology.svg shows variant instances (11) including api-sheriff-plain-mgmt; SVG unchanged since 1a20edad
- HYPOTHESIS: `hostname-backchannel-dynamic=true` (with `hostname` set to a full URL and
  `proxy-headers=xforwarded`) is the supported Keycloak-side mechanism for the split, and
  `--hostname https://host/auth` with a context path is supported. Sourced from vendor
  documentation at the 26.x line, against the pinned `26.5.7` image. Confirm/refute **against the
  pinned image's own behaviour**, not against the doc page. (verify-at-outline)
  - verdict: unverifiable | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: needs the pinned keycloak 26.5.7 image running; cannot be settled statically
- HYPOTHESIS: the exposure allowlist (`/realms/`, `/resources/`, `/.well-known/`, `/lb-check` in;
  `/admin/`, `/realms/master/`, `/metrics`, `/health`, port 9000 out) is complete for a BFF-only
  integration. Confirm/refute by driving the full login, refresh and RP-initiated-logout round
  trip through the fronted variant with everything else blocked. (verify-at-outline)
  - verdict: unverifiable | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: needs a full login, refresh and logout round trip through a fronted variant that does not exist
- HYPOTHESIS: the gateway's canonical-path handling defeats the non-normalized-path route to
  `/admin/`. Confirm/refute with a test, at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/CanonicalPathGuard.java`.
  **Do not ship this as a documented advantage on the strength of reading the code.**
  (verify-at-outline)
  - verdict: unverifiable | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: CanonicalPathGuard has unit tests but no test targets a Keycloak-fronting route
- Verify-first clause: **re-ground every file and line reference at outline.** `PLAN-V02-08`
  reshapes the `oidc` block and `OidcConfig` ahead of this plan — read the landed shape, never this
  spec's description of it.
  - verdict: corroborated | checked_at: 386f3f74094516d787f06dca0946825c62421b89 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: procedural clause applicable: OidcConfig and BffRuntimeProducer changed again via #412; re-read at outline

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` — D1
- HYPOTHESIS: the OIDC discovery/engine wiring that consumes `OidcConfig.issuer()`, and
  `ConfigModelReflection` if D1 adds a record — **a new config record unregistered there boots
  fine on the JVM and fails only in the native image.** (verify-at-outline)
- OBSERVED: `integration-tests/src/main/docker/keycloak/*.json`,
  `deployment/compose-sample/docker/keycloak/sample-realm.json` — D2
- OBSERVED: `demo-client/playwright.config.js`, `demo-client/utils/keycloak-login.js`,
  `demo-client/doc/playwright-suite.adoc` — D2
- OBSERVED: `integration-tests/src/main/docker/sheriff-config*/gateway.yaml` (every overlay),
  `integration-tests/docker-compose.yml`, `deployment/compose-sample/**` — D3, D4
- OBSERVED: `demo-client/src/main/resources/spa/app.js`, `demo-client/utils/constants.js`,
  `demo-client/tests/*.spec.js` — D4, the browser-facing contract change
- OBSERVED: `doc/configuration.adoc`, `doc/user/bff-session.adoc`, `doc/user/bff-cookie.adoc`,
  `doc/variants/02-bff-session.adoc`, `doc/variants/03-bff-cookie.adoc`,
  `demo-client/doc/integration-sample.adoc`, `demo-client/README.adoc`,
  `doc/development/integration-test-topology.adoc` — D5
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — D4: the canonical-form check for `oidc.login.path` and `oidc.user_info.path`
- OBSERVED: `doc/adr/` — one new record — and
  `doc/resources/diagrams/integration-test-topology.svg` if the topology changes — D5

## Dependencies and Sequencing

- Depends on: none. `PLAN-V02-08` (FAPI 2.0) has landed (#377, ADR-0058): the BFF now pushes every
  authorization request, binds its tokens with DPoP and authenticates with `private_key_jwt` unless
  a client secret is configured. Build D1 against that `oidc` block, `OidcConfig` and
  `BffRuntimeProducer` as they now stand, and keep the RFC 9207 `iss` constraint it relies on.
- Never concurrent with `PLAN-V02-09` or `PLAN-V02-20`: all three write the `oidc` block and
  `BffRuntimeProducer`. This plan goes before `PLAN-V02-09`.
- Depended on by: `PLAN-V02-15` (bff-compose-sample), which applies this plan's D1 verdict to the
  sample's browser-versus-container issuer address rather than settling it there.
- Not concurrent with `PLAN-V02-04` (ADR corpus audit): D5 adds a record while that plan audits the
  corpus.
- Adjacent to, and deliberately untouched by, the bearer-path `token_validation` block: D1 mirrors
  its shape but changes nothing there.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-12-idp-addressing-model.md" plan_id=plan-v02-12-idp-addressing-model
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
