# PLAN-02: Configurable Context Path (Application and Management Interfaces)

epic: deployment-configurability
workstream: WS-02

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

API Sheriff serves from the root context on the application port, and its management interface
serves health and metrics from the root of port 9000. Neither is configurable — no context-path
property exists anywhere in the repository. That is unusable behind an ingress that routes by path
prefix. Establish HOW a context path is configured, covering the management interface as well as the
application port, and prove it. **Research the stock Quarkus mechanism before writing anything
bespoke**: the expected best case is that `quarkus.http.root-path`, `quarkus.http.non-application-root-path`
and the management interface's own root-path key already cover it, making this a configuration,
example and documentation change. Stretch goal: the whole thing drivable from a documented
environment variable, so an operator sets it without rebuilding the image.

## Deliverables

1. **Research and record the mechanism.** Determine which stock Quarkus properties own the
   application context path, the non-application (`/q`) path, and the management interface's root
   path, for the Quarkus version this project builds against. Do NOT introduce a bespoke property if
   a stock one exists. Settle explicitly whether MicroProfile-Config environment-variable mapping
   makes the stretch goal free — the compose stack already drives
   `QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES`, `QUARKUS_MANAGEMENT_PORT` and `LOG_FILE_PATH` this
   way, so the precedent exists.
2. **Configure it**, defaulting to today's root behaviour so no existing consumer changes. Cover the
   management interface alongside the application port per the operator's instruction. Respect
   ADR-0025: if the context path is TLS-adjacent policy it belongs in gateway.yaml's neutral
   vocabulary; if it is a deployment-bound knob like the port, it stays a Quarkus property. Record
   which, and why, against the ADR's own boundary rule.
3. **De-hardcode every consumer of the paths.** `start-integration-container.sh` builds its probe URL
   from a compose label plus the published port; the benchmark stack and any test asserting a
   management or application path all need to follow the configured value rather than a literal.
   Route them through one configured source. ⚠ Preserve the label indirection — the script must still
   restate no service name and no port number.
   ⚠ **Re-scoped 2026-08-29 after PLAN-01 landed (re-grounded at `843a038`).**
   `deployment/compose-sample/scripts/wait-for-ready.sh` **no longer exists** — PLAN-01 deleted it and
   replaced it with a two-layer gate: the image's own baked `HEALTHCHECK` plus `up -d --wait`, then one
   single-shot `/q/health/ready` assertion, driven from `scripts/start-sample.sh`. The consumers this
   deliverable must de-hardcode are therefore `scripts/start-sample.sh` (both gate layers) and the
   `healthcheck:` blocks in both compose files. ⛔ Note the compose comment at
   `deployment/compose-sample/docker-compose.yml:213-215`: a Compose `healthcheck:` key **overrides**
   the image's declaration rather than merging with it, so a context path that moves the probe URL must
   be applied to the image and the compose copy together or the copy silently replaces the real probe.
4. **Verify it at the bar the finding justifies.** Stock Quarkus configuration ⇒ a **documented
   manual test procedure** (exact commands and expected responses at the default and at a non-root
   path, on BOTH the application and the management interface, in both management schemes) is
   sufficient and preferred. Anything needing more than configuration ⇒ an integration test is owed.
   State which bar was met and why.
5. **Document it** — the context path, the management-interface interaction, the environment-variable
   form, and the compose example. Include the re-verification of PLAN-01's health-check probe under a
   non-root context path.

## Claim Labels

- OBSERVED: neither `quarkus.http.root-path` nor `quarkus.http.non-application-root-path` appears
  anywhere in the repository — a repo-wide grep over `*.properties`, `*.java`, `*.yml`, `*.adoc`
  (excluding `target/` and `.plan/`) returned zero hits for either. Asserted **absence**; carries the
  same verification obligation as a presence.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: application.properties:123-124 now declares quarkus.http.root-path and quarkus.http.non-application-root-path=q
- OBSERVED: `quarkus.management.enabled=true` is set unconditionally, and the management interface is
  documented as *"application on 8443, operations on 9000"*, HTTPS by default, with **no `ssl-port`
  and no `insecure-requests` key** because it has exactly one port — read at
  `api-sheriff/src/main/resources/application.properties` § the management block.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: quarkus.management.enabled=true still at :171, application on 8443/operations on 9000 text still present
- OBSERVED: the plain-HTTP management opt-out is `quarkus.management.tls-configuration-name` pointed
  at the key-less `quarkus.tls.plain-management` bucket, declared unconditionally and in no profile —
  read at the same file.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: quarkus.management.tls-configuration-name -> plain-management bucket still declared unconditionally
- OBSERVED: ADR-0025's boundary rule is stated inline: *"gateway.yaml names TLS policy in neutral
  terms while ports and trust material stay deployment-supplied; a `management.port` written in
  gateway.yaml is refused at boot"* — read at the same file § the management block. This is the rule
  deliverable 2 must classify the context path against.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ADR-0025 boundary-rule text about ports/trust material staying deployment-supplied still stated inline
- OBSERVED: the application listener is `quarkus.http.port=8080`, `quarkus.http.ssl-port=8443`,
  `quarkus.http.insecure-requests=redirect`, and the comment records that a `tls.passthrough_sni`
  deployment overrides `QUARKUS_HTTP_SSL_PORT` to move the terminated listener to the internal
  loopback port 8444 — read at the same file. A context path must not collide with that relocation.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: quarkus.http.port=8080, ssl-port=8443, insecure-requests=redirect unchanged at :15-17; internal-https-port=8444 override still documented
- OBSERVED: environment-variable configuration is the established deployment pattern here —
  `QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES`, `QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME`,
  `QUARKUS_MANAGEMENT_PORT`, `QUARKUS_LOG_FILE_ENABLED`, `LOG_FILE_PATH` are all named as the
  deployment-supplied surface — read at the same file.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME/_PORT, QUARKUS_LOG_FILE_ENABLED, LOG_FILE_PATH all still named as deployment-supplied
- OBSERVED: `start-integration-container.sh` discovers every `api-sheriff*` service from the resolved
  Compose model and builds its readiness probe URL from the `de.cuioss.sheriff.management-scheme`
  label plus the host port published against container port 9000 — read at
  `integration-tests/docker-compose.yml` § `labels`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: start-integration-container.sh still discovers services and builds probe URL from management-scheme label plus published port
- OBSERVED: `doc/configuration.adoc` already documents the management interface, including why
  `quarkus.management.enabled=false` is *not* the way to get a plain probe endpoint — read at
  `doc/configuration.adoc:556-601`, the management block, with the quoted sentence at `:601`. That is
  the documentation home. ⚠ Re-scoped 2026-08-27: the original citation named `:971`/`:1001`, which
  were true at `5e73e94` — a commit that is **not an ancestor of `main`**. At HEAD those lines carry
  the auth `require:` table and `issuers[].audience` instead.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: quoted management.enabled=false sentence still present in doc/configuration.adoc, now at line 708
- HYPOTHESIS: `quarkus.http.root-path` relocates application endpoints while
  `quarkus.http.non-application-root-path` independently governs `/q`, and a non-absolute
  `non-application-root-path` resolves relative to `root-path` — confirm/refute at the Quarkus HTTP
  reference for the pinned version and at the running container's actual responses (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ContextPathDefaultsTest.java exists and exercises this hypothesis directly
- HYPOTHESIS: the management interface carries its own root-path key independent of
  `quarkus.http.root-path`, so mounting the application under a prefix does NOT implicitly move the
  probe endpoints on port 9000 — confirm/refute at the Quarkus management-interface reference and by
  observing port 9000 after setting the application root path (verify-at-outline). This materially
  changes deliverable 3's scope: if it IS implicit, every probe URL moves.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: quarkus.management.root-path declared as an independent absolute key at application.properties:125
- Verify-first clause: settle both hypotheses against the Quarkus reference for the version this
  project builds against AND against the running container — never against a standards doc, an ADR,
  or this spec's prose. A refutation loops back and re-scopes deliverables 2 and 3.

## Expected Surface

⚠ **CORRECTED 2026-09-02 by the PLAN-11 landing analysis, under the same-act rule** — the original
declaration named **9** paths while the branch at `040f2ac` touches **52**. Under-declaration at ~4x,
and it was not harmless: it produced two real collisions the disjointness gate could not predict
(`doc/adr/0038` vs PLAN-10's claimed ordinal; `doc/user/` vs PLAN-09's surface). The classes below are
derived from `git diff --name-only main...feature/configurable-context-path`, so this is now a
MEASURED surface rather than an authored one.

Originally declared (all realized):

- OBSERVED: `api-sheriff/src/main/resources/application.properties`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/model/ManagementConfig.java`
- OBSERVED: `integration-tests/docker-compose.yml`
- OBSERVED: `integration-tests/docker-compose.benchmark.yml`
- OBSERVED: `integration-tests/scripts/start-integration-container.sh`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BaseIntegrationTest.java`
- OBSERVED: `deployment/compose-sample/docker-compose.yml`
- OBSERVED: `deployment/compose-sample/scripts/start-sample.sh`
- OBSERVED: `doc/configuration.adoc`

Realized but NEVER declared — the under-declaration, by class:

- OBSERVED: `doc/adr/` — `0038-A_build-time_key…carrier_key…adoc` (NEW) and an amendment to
  `0031-Host-side_readiness_gates…adoc`. ⛔ **This is the collision surface**: PLAN-10 was staged to
  land ADR-0038 on an unrelated subject and its ordinal claim is now contradicted.
- OBSERVED: `doc/user/` — `README.adoc`, `context-path.adoc` (new), `downstream-parent.adoc`,
  `environment-variable-overrides.adoc`, `compose-sample.adoc`, `container-image.adoc`.
  ⛔ Two of these are PLAN-09's declared surface.
- OBSERVED: `build-parent/` — `pom.xml`, `example/pom.xml`; plus `deployment/pom.xml`, root `pom.xml`,
  `api-sheriff/pom.xml`
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/quarkus/` — `ContextPathDefaultsTest.java`,
  `BuildParentContractTest.java`, `DefaultProfileReadinessTest.java` (all new), and
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/quarkus/GatewayReadinessCheck.java`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/` — seven further
  IT classes plus `ImageLabelInspector.java`; and `integration-tests/scripts/verify-invalid-config-fails.sh`,
  `integration-tests/prometheus.yml`
- OBSERVED: `benchmarks/` — `src/main/resources/k6-scripts/**` (`lib/target.js`, `gateway_health.js`,
  `health_live.js`, `pre-benchmark-health-check.sh`), `README.adoc`
- OBSERVED: `demo-client/` — `scripts/start-dev-environment.sh`, `doc/playwright-suite.adoc`
- OBSERVED: `doc/development/` — `README.adoc`, `context-path-verification.adoc` (new),
  `compose-sample.adoc`, `integration-test-topology.adoc`
- OBSERVED: `.github/workflows/release.yml`, `.claude/skills/run-integration-tests/SKILL.md`,
  `README.adoc`, `doc/architecture.adoc`, `doc/plan/09-release-readiness.adoc`
- OBSERVED: `.plan/project-architecture/` — derived descriptors re-enriched (`_project.json`,
  `api-sheriff-build-parent/enriched.json`, `my-gateway/enriched.json`)

## Dependencies and Sequencing

- Depends on: PLAN-01 — owns re-verifying its health-check probe under a non-root context path.
- Overlaps with: PLAN-01 on both compose files; PLAN-03 on `application.properties`.
- Adjacent to: the management PORT, which ADR-0025 fixes as deployment-bound and refuses from
  gateway.yaml. Untouched — this plan moves paths, never ports.
- Adjacent to: the ADR-0017 passthrough port relocation (`quarkus.http.ssl-port` → 8444). Untouched,
  but the context path must not assume the terminated listener owns the public port.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-02-configurable-context-path.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
