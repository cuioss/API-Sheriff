# PLAN-10: Anchor Types + Asset Serving

epic: api-sheriff-roadmap
workstream: WS-02

> Staged plan spec. NEW 2026-07-20 (operator). **Restaged 2026-07-20** — the type model moved
> from a 5-value enum to two explicit axes, and `static` was renamed `asset` with a second
> source kind. No `doc/plan/*.adoc` exists yet — this plan is **doc-first**: it authors
> `doc/plan/10-anchor-types-assets.adoc` plus the `configuration.adoc` / `architecture.adoc`
> sections, the ADRs, and the threat-model update BEFORE implementing. The design docs govern.

## Objective

Introduce **two explicit axes** on every `gateway.yaml` anchor — `type` (terminal action) and
`access` (visibility intent) — and add **asset serving** as a second terminal action alongside
proxying, sourced either from a mounted directory or from a secondary server.

Ground truth checked 2026-07-20: anchors (ADR-0007, delivered by PLAN-02) currently carry
`path_prefix` + policy blocks (`auth`, `security_filter`, `security_headers`,
`allowed_methods`) and have **no** type/kind field. Every anchor implicitly terminates in a
proxy. Asset serving does not exist anywhere in the design corpus.

## Design Decisions (operator, 2026-07-20)

### D1 — Two axes: `type` (action) + `access` (intent)

```yaml
anchors:
  public:  { type: proxy, access: public,        path_prefix: /public }
  api:     { type: proxy, access: authenticated, path_prefix: /api,
             auth: { require: bearer } }
  bff:     { type: bff,   access: authenticated, path_prefix: /bff,
             auth: { require: session } }
  assets:  { type: asset, access: public,        path_prefix: /assets,
             asset: { ... } }
  private: { type: asset, access: authenticated, path_prefix: /app,
             asset: { ... }, auth: { ... } }
```

`type` names **what the anchor does** with a request: `proxy | bff | asset`.
`access` names **who may reach it**: `public | authenticated`. Both are **required**.

**Rationale (must survive into the ADR).** Three separate arguments converged here:

1. *Non-OAuth extensibility* (the original operator rationale, unchanged): the gateway must
   stay open to auth kinds beyond today's OAuth/OIDC pair — mTLS, API key, future schemes.
   Deriving intent from `require: bearer|session|none` would hard-wire the taxonomy to
   OAuth-shaped postures. `access` states the intent; the `auth` block states the mechanism.
2. *Fail-closed declaration.* `access` is what D2 validates the configuration **against**. An
   anchor whose `auth` block is lost to a bad merge or a templating slip must fail the boot,
   not silently serve a protected tree to anyone. This is the whole reason a separate field
   exists rather than deriving auth-ness from block presence.
3. *Linear growth.* Folding intent into the action name (`static-public` /
   `static-authenticated`) means every future terminal action arrives in pairs. Splitting the
   axes adds one enum value per action.

**Prior art, checked 2026-07-20** — the industry splits on exactly this question, and the two
camps are worth recording because the choice is deliberate:

- *Infrastructure proxies* keep the axes orthogonal with no declared intent at all: nginx
  (action implied by `root`/`alias` vs `proxy_pass`; auth via `auth_request`), Caddy
  (`file_server` / `reverse_proxy` handlers plus `forward_auth`), Traefik / Kong / APISIX
  (router + service, auth as a middleware/plugin). Envoy is the partial exception — its route
  action *is* a discriminated union (`route` / `redirect` / `direct_response`) — but auth
  stays orthogonal. **All of them share the failure mode**: delete the auth directive and the
  location silently goes public.
- *Application security frameworks* require declared intent: Spring Security
  (`authorizeHttpRequests` forces `permitAll()` or `authenticated()` per matcher; unmatched is
  denied), ASP.NET Core / Duende BFF (`RequireAuthorization()` + `FallbackPolicy`, with
  `[AllowAnonymous]` as the explicit opt-out).

API Sheriff is a proxy that markets itself as security-focused, so it adopts the
security-framework ergonomics rather than inheriting the proxy camp's footgun.

Alternatives rejected: the 5-value enum `passthrough | authenticated | bff | static-public |
static-authenticated` (mixes visibility into the action name — no surveyed project does this,
and it grows pairwise); and deriving auth-ness purely from `auth`-block presence (the nginx
model — simplest config, but a dropped block silently exposes the tree with no signal).

Naming note: `passthrough` becomes `proxy` + `access: public`. **`static` was rejected as the
type name** because the corpus already uses it for something else — `configuration.adoc` and
`architecture.adoc` use "static configuration" / "Static mode" to mean *not hot-reloadable*.
`asset` avoids overloading a term with an established, different meaning in the same document.

### D2 — Boot validation: `access` is the contract, enforced fail-closed

- `access` is **required** on every anchor. Omission is a boot failure — public must be
  *declared*, never obtained by omission.
- `access: authenticated` MUST resolve to **at least one valid, fully-configured auth posture**
  — not merely a non-`none` keyword, but a posture whose backing config actually exists and
  validates (`require: bearer` demands a usable `token_validation` issuer; `require: session`
  demands a complete `oidc` block). A declared-but-unbacked posture fails the boot with a
  specific message.
- `access: public` declaring an `auth` block is a boot failure — declared intent and
  configured policy must not disagree silently.
- `type: bff` requires `access: authenticated`; the combination `bff` + `public` is rejected.
- The existing auth-floor rule (a member route may not weaken the anchor to `require: none`)
  composes unchanged; `access` adds the *upward* check that the floor is genuinely backed.

**D2a — the validation must actually FIRE at startup (PLAN-09 lesson, folded 2026-07-20).**
PLAN-09 shipped a defect of exactly this class, caught only by a review bot: a
`@Observes StartupEvent` observer had the config validator injected so invalid configuration
would abort boot — and it silently did not work. CDI injects a **lazy client proxy**; the
underlying bean (and its `@PostConstruct` assembly + eager validation) materializes only on the
**first method invocation** through that proxy. The observer merely *held* the reference, so
validation never ran at startup. Unit tests passed because a unit test *calls a method*, which
forces proxy resolution — the validation fired under test and was inert in production.

This is not a general hygiene note; **D2 is entirely a boot-validation feature**, so the whole
fail-closed premise of this plan dies silently if it is wired the same way. Per the lesson's own
words: *"a lazy-proxy-defeated startup validator is worse than none — it manufactures the belief
that misconfiguration aborts boot while the gateway actually starts with invalid configuration."*

Required here:
- The startup observer must **actively invoke a method** on the validator (or the bean must be
  marked for eager initialization) so assembly and validation genuinely run at boot.
- At least one test must assert the rejection **via the startup-event path**, not by calling the
  validator directly — a direct-call test passes even when the startup wiring is inert.
- Applies to every `access`→auth rejection in the D2 matrix and to the boot-TOCTOU fix if that
  fold-in is taken.

### D3 — Asset serving: two sources, one terminal action

An asset anchor declares a **source**. Both sources produce identical *asset semantics*; only
where the bytes come from differs.

```yaml
asset: { source: directory, root: /srv/app }        # volume mount
asset: { source: upstream,  upstream: <existing upstream ref> }   # secondary server
```

- `source: directory` — a configured local directory (container volume mount), so a frontend
  deploys independently of the gateway image. Classpath-embedded resources remain out of scope.
- `source: upstream` — a **configured secondary server** (a container serving a built SPA, an
  object store, a CDN origin). Added on operator instruction 2026-07-20: asset delivery must
  not be tied to a volume mount.

**Asset semantics — what makes this a distinct terminal action and not just `type: proxy`.**
Proxying is *transparent mediation*: the client's headers cross per the `forward` allowlist,
methods and bodies pass through, upgrades and streaming are supported. Asset serving is
*opaque content delivery*: the **gateway owns the response envelope**, whatever the source.

- Content-type comes from a fixed extension map — **never client-influenced, and never taken
  from the secondary server's `Content-Type`**. A compromised or merely sloppy asset origin
  returning `text/html` for `/assets/logo.png` would otherwise get script execution in the
  gateway's own origin. Always `X-Content-Type-Options: nosniff`.
- Caching is gateway-governed per anchor. `access: authenticated` forces `no-store`
  **regardless of what the source says** — an upstream `Cache-Control: public` must never leak
  an authenticated asset into a shared cache.
- Upstream `Set-Cookie` is stripped; a secondary server must not set cookies in the gateway's
  origin.
- `GET`/`HEAD` only; no request-body passthrough, no upgrades.
- Same stage-0 security-header / preflight path as proxy routes.
- SPA index-fallback is opt-in per anchor and must not mask traversal rejections.
- **The auth decision precedes source resolution** — for `source: upstream` this also means
  the gateway never makes the secondary-server call for an unauthorized request.

**Path safety applies to BOTH sources, before the source is touched.** The request path is
canonicalized and confined *first*: reject `..`, encoded traversal, absolute-path escape, and
(directory source) symlink escape. For the upstream source this happens **before the upstream
URL is built**, so a traversal cannot escape the configured base path into another part of the
secondary server. Never serve dotfiles or the config tree, irrespective of source contents.

**`source: upstream` reuses the existing upstream model — do not build a parallel fetch
stack.** Verified present in `configuration.adoc`: `resolved(base_url) + upstream.path +
remainder`, the shared per-upstream-tuple data-plane client, circuit breaker, and
`upstream_defaults`. It must also compose with the existing SSRF controls for threat-model
**GW-05** (fixed-topology upstream, scheme allowlist, `EgressPolicy`, `followRedirects(NEVER)`)
— the asset origin is *configuration*, never client-influenced. Add bounded response size and
timeout: an asset origin that hangs or streams unbounded is a DoS vector.

`access: authenticated` is **auth-kind-agnostic** (operator correction 2026-07-20): it reuses
whatever posture resolves — bearer/OAuth just as legitimately as a BFF session, and any future
kind. It does NOT imply `require: session`.

### D4 — Threat model GW-06 must be reopened (found 2026-07-20)

`doc/security-threat-model.adoc` currently rates the path-traversal entry **COVERED/ELIMINATED**
on an assertion that will become false: *"The residual surface is config-load input"*, with the
assertion *"config file resolution cannot escape the config directory"*. Asset serving
introduces **request-path → filesystem-path (or upstream-URL) construction**, which is exactly
the cited evidence pattern (Tyk CVE-2021-23357, APIID → filesystem path traversal).

This plan MUST update that entry rather than leave a stale COVERED rating — a threat model that
under-reports its own surface is worse than one that never claimed coverage. The entry's own
recorded lesson governs the implementation: the Gravitee double-patch (CVE-2019-25075 then
CVE-2022-38723 — *the same* traversal, patched twice) means **close the whole class:
canonicalize-then-contain plus a full traversal/encoding regression corpus, not one spelling.**

## Deliverables (indicative — the plan doc defines the final breakdown)

0. **Documentation-tree bootstrap** (re-homed here 2026-07-20 after PLAN-09 shipped without it
   — see `landings/PLAN-09.md`; the fold reached that spec *after* its command was emitted, so
   it never executed). This plan is now the genuine first customer: anchor `type`/`access` and
   asset serving are pure operator surface, so it must write guide content regardless. Create:
   - `doc/user/` — task-oriented operator guide; `doc/development/` — contributor guide
     (matching sibling TokenSheriff's `doc/development/` convention).
   - A **fifth bullet** in `doc/plan/README.adoc`'s `== Conventions` section — which today has
     exactly four (integration tests, benchmarks, dependency approvals, pre-1.0 rules) and
     **no documentation convention at all** — stating the three layers: (1) *Reference* —
     every new/changed config key, default, enum value and endpoint into `configuration.adoc`,
     structural changes into `architecture.adoc`, exhaustive and per-key, not optional;
     (2) *Operator guide* — `doc/user/`, task-oriented, linking the reference rather than
     duplicating its key tables; (3) *Developer guide* — `doc/development/`. Plus the
     anti-rubber-stamp rule: plan-doc/variant-doc/ADR updates satisfy no layer, and a plan
     with no operator- or contributor-visible delta says so explicitly.
   - **Classification fix** in `doc/README.adoc`: it files `configuration.adoc` under *Design
     Documents* though it is in practice the operator's configuration **reference**. State the
     dual role in the index entry; do **not** move the file — it is linked heavily across the
     variants, plan docs and threat model, and relocating it would churn every cross-reference
     to fix a labelling problem.
   - **Re-point the stale references**: seven staged hand-offs name "roadmap plan 04b" as the
     creator of these trees. That is now wrong. The orchestrator re-points them at emit; if any
     survive into the plan, treat them as pointing here.
1. Doc-first: `doc/plan/10-anchor-types-assets.adoc`; `configuration.adoc` anchors section +
   exhibit; `architecture.adoc` terminal-action statement.
2. ADR: `type` + `access` as two explicit axes (supersedes/extends ADR-0007's implicit
   proxy-only assumption) — carries the non-OAuth-extensibility, fail-closed-declaration, and
   prior-art rationale.
3. ADR or doc section: asset serving as a second terminal action, its two sources, and its
   trust boundary.
4. Threat-model update (D4): reopen the traversal entry, restate its residual surface, and add
   the asset assertions; review whether GW-05 (SSRF) needs a clause for the upstream source.
5. Config schema + loader/validator: `type` + `access` parsing, the D2 validation matrix,
   `asset` block with both sources.
6. Asset runtime: path canonicalization/confinement shared by both sources; directory
   resolution; upstream source over the existing data-plane client; content-type, caching,
   header governance, SPA fallback.
7. Route-table assembly: terminal-action materialization (anchors still vanish at runtime).
8. Tests: adversarial traversal corpus (generator-driven per `cui-http-testing`) run against
   **both** sources; `access`→auth validation matrix (each invalid combination fails boot with
   its specific message); authenticated-asset 401/403; upstream-source governance (hostile
   `Content-Type`, `Set-Cookie`, `Cache-Control: public` on an authenticated asset — each
   overridden); ITs against a real mounted directory **and** a real secondary server.

## Expected Surface

- `api-sheriff/` config loading/validation/schema, route-table assembly, NEW asset-serving
  package (reusing the existing data-plane client for the upstream source);
  `integration-tests/` asset + type-validation suites; `doc/**` + `doc/adr/` + threat model

## Dependencies and Sequencing

- **After PLAN-09, before PLAN-04** (repositioned 2026-07-20 on operator correction). The
  earlier "after PLAN-06" placement rested on a wrong premise — that an authenticated asset
  anchor needs `require: session`. It does not: bearer/OAuth backs it just as well, and bearer
  enforcement has shipped since PLAN-03 (roadmap 04 stage 4, verified). With that dependency
  dissolved, the taxonomy lands early because it is a **foundational config-model change** —
  PLAN-04, PLAN-05, PLAN-06 and PLAN-07 all declare anchors, so landing `type`/`access` first
  means they author against the final model instead of retrofitting it.
- `type: bff` is the ONE combination needing a posture that does not yet exist: `require:
  session` boot-rejects until PLAN-06 (verified in the roadmap 04 doc). Not a blocker — D2
  failing a `type: bff` anchor until PLAN-06 lands is precisely the intended fail-closed
  behavior. PLAN-06 flips it on; add a test asserting the pre-PLAN-06 rejection and update it
  when session goes live.
- **Surface collision**: touches config loading/validation/schema — the same surface as
  PLAN-02. Not parallelizable with any other config-touching plan. Route-table assembly
  (terminal-action materialization) may brush PLAN-04's pipeline surface — verify disjointness
  at emit before pairing the two. The upstream asset source touches the data-plane client
  neighbourhood; re-check that against PLAN-04 at emit as well.
- **Fold-in candidates** (config-touching plan — these were parked awaiting exactly this):
  the boot-TOCTOU open defect (YAML pre-pass vs bind read) and the destination-type-aware
  placeholder-coercion architecture hint. Decide at emit time.
- Consumed by PLAN-11 (demo SPA served from an `access: public` asset anchor).
- Before PLAN-08 (release-readiness), which documents the final operator surface.

## Scope-Bloat Note

**Nine** deliverable groups — well **over** the ~6 split guard: the upstream asset source and
then the re-homed documentation bootstrap both landed after the guard was first evaluated.
Recommended split, to confirm at emit:

- **(A) doc bootstrap + `type` + `access` + validation + docs/ADR** — independently shippable,
  and it is the piece PLAN-04/05/06/07 need in order to author against the final model. The
  documentation scaffolding belongs here specifically because it must exist before any later
  plan can satisfy the standing convention — it has already been dropped once by riding along
  with a larger plan, so it goes in the half that lands first.
- **(B) asset serving (both sources) + threat-model update + traversal corpus** — depends on
  (A), and is where essentially all of the security risk sits.

Splitting also lets (A) land fast to unblock the queue while (B) gets the review attention a
new filesystem/egress trust boundary deserves.

## Hand-Off Command

```text
/plan-marshall Author doc/plan/10-anchor-types-assets.adoc doc-first and implement it: introduce TWO EXPLICIT AXES on gateway.yaml anchors and add asset serving as a second terminal action. Read doc/configuration.adoc (Anchors section, plus the upstream/upstream_defaults sections), doc/adr/0007-anchor-scoped-policy.adoc, doc/architecture.adoc, and doc/security-threat-model.adoc first — the design docs govern; anchors today carry path_prefix + policy blocks with NO type field and always terminate in a proxy. AXES (operator decision 2026-07-20): `type` names the terminal action (proxy | bff | asset) and `access` names the visibility intent (public | authenticated); BOTH are required on every anchor. `passthrough` is renamed to `proxy` + `access: public`. Do NOT name the asset type `static` — the corpus already uses "static configuration"/"Static mode" to mean not-hot-reloadable, and overloading it in the same document is the reason `asset` was chosen. RATIONALE to record in the ADR (three converging arguments): (1) non-OAuth extensibility — the gateway must stay open to auth kinds beyond today's OAuth/OIDC (mTLS, API key, future schemes), so `access` states intent while the auth block states mechanism; (2) fail-closed declaration — `access` is what boot validation checks the configuration against, so an auth block lost to a bad merge fails the boot instead of silently serving a protected tree publicly; (3) linear growth — folding intent into action names means every future action arrives in pairs. Record the prior-art survey too: infrastructure proxies (nginx root/alias vs proxy_pass + auth_request, Caddy file_server/reverse_proxy + forward_auth, Traefik/Kong/APISIX router+plugin, Envoy's route/redirect/direct_response union) keep the axes orthogonal with no declared intent and ALL share the failure mode that deleting the auth directive silently goes public; application security frameworks (Spring Security authorizeHttpRequests permitAll/authenticated with deny-by-default, ASP.NET Core RequireAuthorization + FallbackPolicy + AllowAnonymous) require declared intent. API Sheriff adopts the latter. THE VALIDATION MUST ACTUALLY FIRE AT STARTUP (lesson from roadmap plan 04b, PR #82 — do not repeat it): that plan shipped a fail-fast startup validator that silently never ran, caught only by a review bot. A @Observes StartupEvent observer had the validator injected, but CDI injects a LAZY CLIENT PROXY — the bean's @PostConstruct assembly and eager validation materialize only on the FIRST METHOD INVOCATION through the proxy, and the observer merely held the reference. Unit tests passed because a unit test calls a method, forcing proxy resolution; validation fired under test and was inert in production. Since D2 IS a boot-validation feature, the entire fail-closed premise of this plan dies silently if wired the same way — a lazy-proxy-defeated startup validator is worse than none, because it manufactures the belief that misconfiguration aborts boot while the gateway starts with invalid configuration. So: the startup observer MUST actively invoke a method on the validator (or the bean must be marked for eager initialization), and at least one test MUST assert rejection via the STARTUP-EVENT path rather than by calling the validator directly, since a direct-call test passes even when the startup wiring is inert. This applies to every access-to-auth rejection in the matrix below and to the boot-TOCTOU fix if that fold-in is taken. VALIDATION, fail-closed: `access` required (omission is a boot failure — public must be declared, never obtained by omission); `access: authenticated` must resolve to at least one VALID, fully-backed auth posture — not merely a non-none keyword, but a posture whose backing config exists and validates (require: bearer demands a usable token_validation issuer; require: session demands a complete oidc block); `access: public` declaring an auth block is a boot failure; `type: bff` requires `access: authenticated`; the existing auth-floor rule composes unchanged. AUTH-KIND-AGNOSTIC: `access: authenticated` does NOT imply a session — bearer/OAuth backs it just as legitimately, as will future kinds. Note that require: session still boot-rejects until roadmap plan 07 ships it, so a type: bff anchor legitimately fails validation for now — assert that rejection in a test rather than working around it. ASSET SERVING — TWO SOURCES, one terminal action (operator instruction 2026-07-20: asset delivery must NOT be tied to a volume mount): `source: directory` (configured local directory / volume mount, so a frontend deploys independently of the gateway image — classpath-embedded resources OUT of scope) and `source: upstream` (a configured secondary server: a container serving a built SPA, an object store, a CDN origin). ASSET SEMANTICS are what distinguish this from type: proxy — proxying is transparent mediation (headers per the forward allowlist, methods, bodies, upgrades, streaming) whereas asset serving is opaque content delivery where THE GATEWAY OWNS THE RESPONSE ENVELOPE whatever the source: content-type from a fixed extension map, never client-influenced AND NEVER taken from the secondary server's Content-Type (a sloppy or compromised asset origin returning text/html for /assets/logo.png would otherwise get script execution in the gateway's own origin), always nosniff; caching gateway-governed per anchor with `access: authenticated` forcing no-store REGARDLESS of what the source says (an upstream Cache-Control: public must never leak an authenticated asset into a shared cache); upstream Set-Cookie stripped; GET/HEAD only, no body passthrough, no upgrades; the same stage-0 security-header/preflight path as proxy routes; opt-in SPA index-fallback that must not mask traversal rejections; and the auth decision precedes source resolution, so an unauthorized request never triggers the secondary-server call. PATH SAFETY applies to BOTH sources and runs BEFORE the source is touched: canonicalize and confine (reject .., encoded traversal, absolute-path escape, and for the directory source symlink escape); for the upstream source this happens before the upstream URL is built so a traversal cannot escape the configured base path into another part of the secondary server; never serve dotfiles or the config tree. The upstream source MUST reuse the existing upstream model — resolved(base_url) + upstream.path + remainder, the shared per-upstream-tuple data-plane client, circuit breaker, upstream_defaults — do NOT build a parallel fetch stack; it must compose with the existing SSRF controls for threat-model GW-05 (fixed-topology upstream, scheme allowlist, EgressPolicy, followRedirects(NEVER)) since the asset origin is configuration and never client-influenced; add bounded response size and timeout because an asset origin that hangs or streams unbounded is a DoS vector. THREAT MODEL (found 2026-07-20, must not be skipped): doc/security-threat-model.adoc currently rates the path-traversal entry COVERED/ELIMINATED on the assertion that "the residual surface is config-load input" and that config file resolution cannot escape the config directory. Asset serving introduces request-path → filesystem-path (and upstream-URL) construction, which is exactly the cited evidence pattern (Tyk CVE-2021-23357, APIID → filesystem path traversal), so that rating becomes false. Reopen and update the entry — a threat model that under-reports its own surface is worse than one that never claimed coverage — and apply its own recorded lesson: the Gravitee double-patch (CVE-2019-25075 then CVE-2022-38723, the same traversal patched twice) means close the whole CLASS via canonicalize-then-contain plus a full traversal/encoding regression corpus, not one spelling. Also review whether GW-05 needs a clause for the upstream asset source. Deliver the ADRs, configuration.adoc + architecture.adoc updates, the threat-model update, schema/loader/validator, the asset runtime, route-table terminal-action materialization, a generator-driven adversarial traversal corpus run against BOTH sources, the full access→auth boot-validation matrix, upstream-source governance tests (hostile Content-Type, Set-Cookie, and Cache-Control: public on an authenticated asset — each overridden by the gateway), and integration tests against a real mounted directory AND a real secondary server. SCOPE: eight deliverable groups is over the split guard — evaluate splitting into (A) type + access + validation + docs/ADR, independently shippable and the piece the later plans need to author against, and (B) asset serving both sources + threat-model update + traversal corpus, where essentially all the security risk sits. DOCUMENTATION (standing epic convention, operator 2026-07-20 — applies to every plan; THREE layers, all in the SAME PR): (a) REFERENCE — every new or changed configuration key, default, enum value, and endpoint this plan introduces MUST land in doc/configuration.adoc, and structural changes in doc/architecture.adoc; these are the exhaustive per-key references and they are not optional. (b) OPERATOR GUIDE — doc/user/, task-oriented (how an operator accomplishes the thing), linking to the reference rather than duplicating its key tables. (c) DEVELOPER GUIDE — doc/development/, contributor-facing (build, test, run, extend). THIS PLAN creates the doc/user/ and doc/development/ trees and records the convention itself (deliverable 0) — plan 04b shipped without them. Updating plan docs, variant docs or ADRs does NOT satisfy (a), (b) or (c). If this plan genuinely changes nothing an operator or a contributor would do differently, state that explicitly in the PR rather than silently skipping it.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-10.md)
