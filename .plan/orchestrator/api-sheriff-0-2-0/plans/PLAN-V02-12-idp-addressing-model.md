# PLAN-V02-12: IdP Addressing Model — one issuer identity, a frontchannel/backchannel split, and the `/auth`-fronted variant

epic: api-sheriff-0-2-0
workstream: WS-04

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
>
> **Authored 2026-08-04** from an operator-directed analysis and web research
> (`/auth` anchor semantics + IdP exposure in the integration topology). Operator ruling the
> same day: *"The IDP exposing directly is a bug. Some DNS trickery is not solution … Make a
> thorough research on this on how to handle sensibly (no hacks, engineering). Eventually this
> will be part of 0.2.0."*

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**CONFIRMED EXACTLY — the core findings are intact, line numbers included.**
- All three realm imports still pin the internal authority as canonical:
  `integration-realm.json`:12, `benchmark-realm.json`:12, `sample-realm.json`:7, each
  `"frontendUrl": "https://keycloak:8443"`. **The root cause is unchanged.**
- `demo-client/playwright.config.js`: `hostResolverRule()` at :51, the
  `--host-resolver-rules=` flag at :60. **The hack is still in the tree, so D2's evidence test —
  a green suite with the flag deleted — is still exactly the right acceptance.**
- `OidcConfig` still carries a **single** `@Nullable String issuer` (:48; the spec's `:47-57`
  window is close enough to re-anchor from) and no backchannel or discovery override. The defect
  D1 closes is unchanged.
- `ADR-0018`:75–76 still anticipates the relocation in the quoted words.

**⚠ REFUTED — STRIKE THE SVG OBLIGATION, AND ITS ANCHOR HAS MOVED TOO.** The Claim Label reading
*"the topology SVG omits `api-sheriff-plain-mgmt` in both drawing and `<desc>`, disclosed at
`doc/development/integration-test-topology.adoc`:242-249"* is **false at HEAD**. The SVG's collapsed
group now reads **`variant instances (6)`** — `api-sheriff-roadmap` PLAN-46 (#156, `6057b66`) redrew
it as a side effect of adding the `api-sheriff-passthrough-empty` instance. Separately, :242–249 of
that document now holds different content (the hand-authored/redraw caution, not the disclosed
omission). **Do not budget work for closing that gap and do not re-attempt it** — but note the
diagram now carries a **sixth** instance that did not exist when this spec was written, so if D5
redraws it, redraw from the current file.

**STALE COUNT — and it weakens an asserted absence.** D5 and the Claim Labels both say *"No ADR among
the **33** records covers the IdP-addressing decision — verified by enumeration."* The corpus is now
**37 records** (`0034`–`0037` added). The absence is very likely still true, but it was established
over a smaller set. **Re-establish it by enumerating all 37 at outline** — the spec's own instruction
(*"an asserted absence: re-verify by enumeration, not by grep alone"*) now has four unexamined
records to cover. `0038` is the next free number and is genuinely free.

**SEQUENCING — one sequential chain of three, and this plan is in the middle.**
`PLAN-V02-08` (FAPI 2.0) → **this plan** → `PLAN-V02-09` (token-sheriff fidelity). All three write
`BffRuntimeProducer` / the `oidc` block. **V02-08 first** — it is the larger reshaping of that block
and settles the RFC 9207 `iss` constraint this plan inherits. **Also not concurrent with
`PLAN-V02-04`** (its spec forbids concurrency with any ADR-authoring plan), and **`PLAN-V02-15`
(bff-compose-sample) waits on this plan's D1 verdict** — its D5 defers the browser-vs-container
issuer address here rather than letting a sample become the de-facto specification.

**GATE COST, new since this spec was written.** `docker-compose*.yml` and `.github/workflows/**` are
now gate-requiring (`build.map`, roadmap PLAN-51 / #196). D3 and D4 both touch the integration
compose stack, so they pay a full quality gate.

**The `ConfigModelReflection` warning in Expected Surface is the sharpest line in this spec** — a new
config record unregistered there boots fine on the JVM and fails **only** in the native image, and
one `treeToValue` masks all but the first omission. D1 adds a record. Treat it as a hard checklist
item, not a caveat.


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

**CORE ANCHORS HELD** at `95dd566`: all three realm imports still pin the internal authority,
`playwright.config.js` still carries the resolver hack at :51/:60, `OidcConfig` still carries a single
`issuer`, and the ADR corpus is still 37 records — so D5's asserted absence must still be
re-established by enumerating **37**, not 33.

**ONE DOC ANCHOR MOVED.** The internally-inconsistent OIDC exhibit the spec cites at
`doc/configuration.adoc`:293–308 now sits at roughly **:305–311** after `PLAN-V02-03`'s restructure.
Re-anchor by content (`redirect_uri`, `/auth/login`, `/auth/userinfo`), not by line. D5's wider
reconciliation target moved with it: `doc/user/` is now **ten** pages including the new
`anchors.adoc` and `endpoint-routes.adoc`, and the reserved-path relocation D4 performs will touch
whichever of those now documents the anchor namespace.

## Objective

The gateway's BFF cannot today address an identity provider that is reachable at different
network locations from the browser and from the gateway. The shipped test and sample topologies
work around that with a **browser-side DNS override**, which is not an engineering solution and
cannot exist in a real deployment. This plan replaces the workaround with the mechanism the
specifications actually permit — **one issuer identity, two transport paths** — and then ships
the `/auth`-fronted Keycloak deployment variant that the corrected model makes expressible.

Nothing here ships in 0.1.0. The alpha is unaffected: the gateway ships no IdP, and the direct
exposure is a property of the test and sample topologies rather than of the product.

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
   run — see the standing lesson that a compile is not a run.

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

   D3 makes this mandatory rather than cosmetic: `/auth/*` becomes the IdP namespace, so the six
   reserved paths must move. Three facts bound the work:

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

   This is a **browser-facing contract change**: it reaches the SPA, the Playwright specs, the
   four variant overlays and the operator docs. ADR-0018 already anticipates it — *"operators can
   move a reserved path out of a namespace they need"*, and *"those namespaces proxied must
   relocate the reserved paths instead."*

5. **Write the ADR, and reconcile the documents this plan invalidates.**

   **No ADR among the 33 records the IdP-addressing decision** — verified by enumeration. Write
   it: one issuer identity, the frontchannel/backchannel split, whether the gateway fronts the
   IdP, and the exposure allowlist. Record the rejected alternatives explicitly, because they are
   the load-bearing part and they are what future readers will otherwise re-propose:

   - browser-side DNS overrides (`--host-resolver-rules`, `/etc/hosts`) — what is in the tree now;
   - rewriting the discovery document in flight — breaks the §3.3 identity check by construction;
   - two issuer values — breaks `iss` validation and RFC 9207;
   - validating `iss` against the internal URL while the browser uses the public one — the
     current arrangement, and the one that reads most plausible.

   Reconcile: `doc/configuration.adoc`'s OIDC exhibit is **internally inconsistent today**
   (`/auth/callback`, `/auth/login`, `/auth/userinfo` beside `/logout`, `/logout/done`,
   `/logout/backchannel` — two conventions, one block, no explanation); the demo-client integrator
   and contributor documents; and `doc/development/integration-test-topology.adoc` **plus its
   hand-authored SVG**, which does not regenerate and which CI does not check.

   That SVG already carries a **pre-existing, disclosed** gap — the collapsed group is labelled
   `variant instances (4)` and omits `api-sheriff-plain-mgmt` and its `10448 · 19005` pair, in
   both the drawing and the `<desc>`. It is deliberately left to a follow-up in the topology doc's
   own words. **If this plan redraws that SVG it must close that gap in the same pass** — leaving
   a known omission in a diagram being edited anyway converts a disclosed debt into a silent one.

**Deliverable count is five, under the split guard.** The natural split line if it grows: D1+D2
are the *correctness* half (the model and its proof) and stand alone; D3+D4+D5 are the *namespace
and sample* half and depend on the first. Split there, never mid-way through D4 — a half-renamed
reserved-path namespace is a broken contract rather than a partial one.

## Claim Labels

- OBSERVED: `OidcConfig` carries a single `issuer` and no backchannel or discovery override —
  read at `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java`:47-57
  § the record header.
- OBSERVED: `token_validation.issuers[]` already carries `issuer` and `jwks.url` as independent
  keys — read at `integration-tests/src/main/docker/sheriff-config/gateway.yaml`:240-245.
- OBSERVED: all three realm imports pin the internal authority as canonical — read at
  `integration-tests/src/main/docker/keycloak/integration-realm.json`:12,
  `.../benchmark-realm.json`:12 and
  `deployment/compose-sample/docker/keycloak/sample-realm.json`:7, each
  `"frontendUrl": "https://keycloak:8443"`.
- OBSERVED: the browser reaches the IdP only via a Chromium host-resolver rule — read at
  `demo-client/playwright.config.js`:51-60 § `hostResolverRule()`, emitting
  `MAP keycloak:8443 127.0.0.1:1443`, and documented at
  `demo-client/doc/playwright-suite.adoc`:290.
- OBSERVED: Keycloak is published directly on the host in both topologies, and no route in any
  shipped configuration proxies it — read at `integration-tests/docker-compose.yml` § `keycloak.ports`
  (`1443:8443`, `1090:9000`) and `deployment/compose-sample/docker-compose.yml`:46-50. The
  gateway's only contact is egress (JWKS + back channel), host-exact allowlisted.
- OBSERVED: reserved paths are configuration-derived with no product default — read at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/reserved/ReservedPathRegistry.java`:95-123.
  There is no `/auth` literal in `src/main/java` outside Javadoc examples. **`/auth` is a sample
  convention, not a product constant.**
- OBSERVED: `/bff` and `/bff-session` anchors already exist — read at
  `integration-tests/src/main/docker/sheriff-config/gateway.yaml`:110-123.
- OBSERVED: the six reserved paths currently live under `/auth` — read at the same file,
  :266, :268, :269, :286, :297, :301.
- OBSERVED: `doc/configuration.adoc`:293-308 mixes `/auth/*` and `/logout*` conventions inside one
  exhibit.
- OBSERVED: no ADR covers IdP addressing or exposure — established by enumerating all 33 records
  in `doc/adr/`. **An asserted absence: re-verify by enumeration at outline, not by grep alone.**
- OBSERVED: ADR-0018 anticipates the relocation — read at
  `doc/adr/0018-BFF_session_mode_is_one_SessionBinding_seam_behind_a_fixed_reserved-path_and_CSRF_model.adoc`:75-76,300.
- OBSERVED: the topology SVG omits `api-sheriff-plain-mgmt` in both drawing and `<desc>`, disclosed
  at `doc/development/integration-test-topology.adoc`:242-249.
- HYPOTHESIS: `hostname-backchannel-dynamic=true` (with `hostname` set to a full URL and
  `proxy-headers=xforwarded`) is the supported Keycloak-side mechanism for the split, and
  `--hostname https://host/auth` with a context path is supported. Sourced from vendor
  documentation at the 26.x line, against the pinned `26.5.7` image. Confirm/refute **against the
  pinned image's own behaviour**, not against the doc page. (verify-at-outline)
- HYPOTHESIS: the exposure allowlist (`/realms/`, `/resources/`, `/.well-known/`, `/lb-check` in;
  `/admin/`, `/realms/master/`, `/metrics`, `/health`, port 9000 out) is complete for a BFF-only
  integration. Confirm/refute by driving the full login, refresh and RP-initiated-logout round
  trip through the fronted variant with everything else blocked. (verify-at-outline)
- HYPOTHESIS: the gateway's canonical-path handling defeats the non-normalized-path route to
  `/admin/`. Confirm/refute with a test, at
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/pipeline/CanonicalPathGuard.java`.
  **Do not ship this as a documented advantage on the strength of reading the code.**
  (verify-at-outline)
- Verify-first clause: the whole spec is grounded at `b39b271` with two plans in flight
  (PLAN-08A touches the security surface broadly; PLAN-46 touches `benchmarks/**`). **Re-ground
  every line reference at outline.** If PLAN-V02-08 lands first it will have moved the `oidc`
  block — read the landed shape, never this spec's line numbers.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/OidcConfig.java` — D1
- HYPOTHESIS: the OIDC discovery/engine wiring that consumes `OidcConfig.issuer()`, and
  `ConfigModelReflection` if D1 adds a record — **a new config record unregistered there boots
  fine on the JVM and fails only in the native image.** (verify-at-outline)
- OBSERVED: `integration-tests/src/main/docker/keycloak/*.json`,
  `deployment/compose-sample/docker/keycloak/sample-realm.json` — D2
- OBSERVED: `demo-client/playwright.config.js`, `demo-client/utils/keycloak-login.js`,
  `demo-client/doc/playwright-suite.adoc` — D2
- OBSERVED: `integration-tests/src/main/docker/sheriff-config*/gateway.yaml` (all five overlays),
  `integration-tests/docker-compose.yml`, `deployment/compose-sample/**` — D3, D4
- OBSERVED: `demo-client/src/main/resources/spa/app.js`, `demo-client/utils/constants.js`,
  `demo-client/tests/*.spec.js` — D4, the browser-facing contract change
- OBSERVED: `doc/configuration.adoc`, `doc/user/bff-session.adoc`, `doc/user/bff-cookie.adoc`,
  `doc/variants/02-bff-session.adoc`, `doc/variants/03-bff-cookie.adoc`,
  `demo-client/doc/integration-sample.adoc`, `demo-client/README.adoc`,
  `doc/development/integration-test-topology.adoc` — D5
- OBSERVED: `doc/adr/` — one new record — and
  `doc/resources/diagrams/integration-test-topology.svg` if the topology changes — D5

## Dependencies and Sequencing

- **Depends on**: the epic's gate (nothing in `api-sheriff-0-2-0` emits until `api-sheriff-roadmap`
  closes), and on this epic's standing `decompose` obligation to re-ground before any emit.
- **Overlaps with PLAN-V02-08 (FAPI 2.0 conformance) on the `oidc` block and `OidcConfig` —
  SEQUENCE THEM, NEVER CONCURRENT.** PLAN-V02-08 also already sequences against PLAN-V02-09 on
  `BffRuntimeProducer`, so all three are one sequential chain. **Prefer V02-08 first**: it settles
  client authentication and sender-constrained tokens, which is the larger reshaping of the same
  block, and it inherits the RFC 9207 `iss` constraint stated above.
- **Overlaps with PLAN-V02-04 (ADR corpus audit)** — D5 adds a record while V02-04 audits the
  corpus. V02-04's own spec says it is *"not concurrent with any plan authoring an ADR."*
- Adjacent to PLAN-V02-03 (documentation restructure), which splits `configuration.adoc` into the
  `doc/user/` pages. If V02-03 lands first, D5's reconciliation target moves — **read the landed
  layout rather than this spec's paths.**
- Adjacent to, and deliberately untouched by, the bearer-path `token_validation` block: D1 mirrors
  its shape but changes nothing there.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-12-idp-addressing-model.md" plan_id=plan-v02-12-idp-addressing-model
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message
— the orchestrator owns every other ledger write — and reports its outcome through its PR and its
inbox message.
