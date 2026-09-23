# PLAN-24: Operator documentation that is true at 0.2.1 — the TLS scenario guide, Known Limitations, badges and shipped-artifact drift

epic: deployment-configurability
workstream: WS-06

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-15 by the orchestrator's corpus revisit at `origin/main` `a2969b9`, MERGING
> `PLAN-09-tls-scenario-guide.md` (deliverables 1-6 here) and `PLAN-19-release-readiness-doc-review.md`
> (deliverables 7-11 here).** Both are retained as superseded audit records. Operator decision: up to
> 12 deliverables per plan are authorized, and the two specs were one subject — *what an operator reads
> is true at the cut and checkable* — split across two PR cycles that collided on `doc/user/`.

## Objective

API Sheriff has shipped `0.2.0` and `0.2.1`. The documentation an operator reads before adopting it has
two gaps: it has **no scenario-organised TLS entry point** (the TLS material is documented by mechanism
in `doc/user/tls-edge.adoc` and by key in `doc/user/environment-variable-overrides.adoc`, never by
operator goal), and several of its **claims of verification are stamped current without having been
re-verified** — `Known Limitations`, two static badges, module metadata, and two shipped-artifact
defects.

Write the scenario guide, then review the whole operator-facing surface — including the new guide —
against what the artifact at HEAD actually does, and correct what is wrong.

⛔ **The bar is not "well written". It is "true at this cut, and checkable".** ⛔ **The scenario guide
must not become a third source of truth**: it composes and links. Key semantics stay in
`environment-variable-overrides.adoc`, mechanism rationale in `tls-edge.adoc`, by xref.

## Deliverables

### Part A — the TLS scenario guide (from PLAN-09)

1. **Enumerate the scenarios, and justify the set — against HEAD, not against a catalogue.** Each
   scenario must be one an operator recognises as *their situation*, not a mechanism dressed as one.
   The starting set below is a lead; confirm every row against the shipped configuration and state any
   addition or removal with its reason.

   | # | Scenario | Source to draw from |
   |---|---|---|
   | 1 | Terminate TLS at the gateway (default single listener) | `tls-edge.adoc` § TLS policy on the terminated listener |
   | 2 | Terminate TLS and require client certificates (mTLS) | `integration-tests/src/main/docker/sheriff-config-mtls/gateway.yaml` |
   | 3 | Pass some hostnames through untouched (SNI passthrough) | `tls-edge.adoc` § Passthrough SNI; `application.properties` front-listener comment |
   | 4 | Management interface over HTTPS (shipped default) | `tls-edge.adoc` § The management interface |
   | 5 | Management interface over plain HTTP — one scenario, two doors | `QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME=plain-management` or `management.tls.enabled: false`; `ApiSheriff-115` |
   | 6 | Validate tokens against an IdP with a private CA | `jwks.tls_profile`; ADR-0041 sslContext/`verifyHostname(false)` refusal |
   | 7 | Terminate TLS upstream (ingress / sidecar), serve plain HTTP | `tls-edge.adoc` § Serving plain HTTP; `deployment/compose-sample/docker-compose.plain-http.yml`; `ApiSheriff-121` |
   | 8 | Dial upstreams / the IdP through a corporate CA | `egress_tls.upstream_tls_profile` — anchors **REPLACE** the JVM bundle (`ApiSheriff-119`); `tls-edge.adoc` § Outbound trust material replaces the platform bundle; `ApiSheriff-122` |
   | 9 | Relax outbound hostname verification (and when never to) | `egress_tls.upstream_verify_hostname` / `jwks_verify_hostname`; ADR-0040/0041; `ApiSheriff-118` / `-120` |

   ⚠ Rows 7-9 did not exist when PLAN-09 was staged; scenario guides in this epic have been understated
   three times by knobs that landed later. ⚠ **The BFF client leg's TLS posture is being settled by
   PLAN-25 concurrently** — document the posture as shipped at HEAD and do not anticipate a knob that
   has not landed; the orchestrator folds any new knob in at PLAN-25's landing.
2. **Write each scenario to a fixed three-part shape**, in the operator's order: **Goal** (what it
   achieves, when to pick it, when *not* to) → **Preconditions** (certificates and their properties,
   files, mounts, directory structure) → **Configuration** (exact keys, split into `gateway.yaml` policy
   and `QUARKUS_*` deployment material, with real values). ⛔ Every scenario carries all three headings;
   "none" is written, never omitted.
3. **Make every configuration block copy-pasteable and true.** Values come from
   `deployment/compose-sample/` and the `integration-tests/src/main/docker/sheriff-config*/` instances,
   never invented. ⚠ A TLS bucket name is a configuration *map key* and its environment encoding is
   **not** a plain dot-to-underscore substitution (the shipped stack uses
   `QUARKUS_TLS_DEFAULT_TRUST__STORE_P12_PATH`) — copy exact spellings, do not derive them.
4. **State the failure mode for each scenario** — what the operator sees when it is wrong and which
   signal names it. The model is `ApiSheriff-115`, which reports the *observed effective state* rather
   than a declared key.
5. **Wire it into the documentation graph.** Index row in `doc/user/README.adoc`; reciprocal "see also"
   links from `tls-edge.adoc` and `environment-variable-overrides.adoc`.
6. **Draw the key-material graph.** No diagram under `doc/resources/diagrams/` covers TLS, certificates,
   keys or trust. The subject is graph-shaped: server identity material, the key-less
   `plain-management` bucket, inbound `client_ca`, outbound `egress_tls.upstream_tls_profile`
   (replaces), `jwks.tls_profile` (mutually exclusive with `verifyHostname(false)`, refused at boot),
   operator default trust store (replaces, `ApiSheriff-122`), and `CookieKeyMaterial` (a different kind
   of key entirely). Hand-authored SVG per `pm-documents:ref-svg-diagrams`; ⛔ **its Step 4 render check
   on `#ffffff` and `#0d1117` is mandatory and blocking — run it and look at both PNGs.**

### Part B — release-truth review (from PLAN-19)

7. **Audit `Known Limitations` entry by entry against HEAD.** `README.adoc:144` is cited by
   `doc/README.adoc` and `doc/user/README.adoc` as *the verified limitations at this cut*, and #299
   re-stamped it *"Verified as still open at the 0.2.1 cut"* without re-verifying any existing entry.
   - Write the **conditioned** cookie-mode refresh capability from `doc/development/bff-cookie.adoc` and
     ADR-0043 (settled at a 4019-byte value budget) — ⛔ neither delete the old limitation silently nor
     assert an unconditional capability; re-derive the numbers at HEAD before publishing them.
   - ⛔ Do **not** carry the token-sheriff SNAPSHOT limitation (resolved by #289) or the compose-sample
     "does not boot until 0.2.0" limitation (resolved by #299).
   - Verify, then keep or remove: `tls.passthrough_sni` not suppliable from the environment; cui-http
     `paranoid()` preset not exposed; `RotationResult.scopeDelta` unread; the x86-64-v3-only image entry
     #299 added.
8. **Make the Cosign and Trivy badges honest — link to evidence, or remove them.** Both underlying claims
   are true (`release.yml:545` signs, `release.yml:364` gates with `exit-code: '1'`), but both badges are
   static `img.shields.io/badge/` images linking to prose (`README.adoc:34-35`) and would stay green if
   either step were removed — while three neighbouring badges are derived. ⚠ `maven.yml:84` runs a
   *non-gating* Trivy lane beside the gating one, so "HIGH/CRITICAL gate" is ambiguous even while true.
   Settle one of: point both at `release.yml`; publish endpoint JSON; remove. ⛔ Record the rejected
   alternatives.
9. **Sweep the README and `doc/` for claims the artifact does not support** — including this plan's own
   new guide. For each claim of a guarantee, check or gate, ask *what would fail if this were false?*
   Known instances to start from: `doc/fapi_status.adoc:1` still titled *"(0.1.0 Alpha)"* and
   `doc/features-analysis.adoc:219` *"TARGET, NOT SHIPPED IN 0.1.0"*, both missed by #299. ⛔ **Report,
   do not edit, findings in files another staged plan owns** — `doc/configuration.adoc`,
   `doc/LogMessages.adoc`, `doc/security-threat-model.adoc`, `doc/user/bff-cookie.adoc` (PLAN-25).
10. **Correct agent- and module-facing metadata that describes a tree that no longer exists.**
    - The `benchmarks` module runs **k6**, but `.plan/project-architecture/benchmarks/enriched.json`
      describes a WRK harness (six WRK-bearing lines, including a package key
      `de.cuioss.sheriff.api.wrk.benchmark`), and `CLAUDE.md:14` says *"WRK HTTP load testing
      benchmarks"*. Fix at the `enriched.json` root (`architecture discover` regenerates from it).
      ⚠ `benchmarks/README.adoc:602` mentions WRK *historically* ("the `WRK_MAX_ERROR_RATE` Lua gate is
      replaced by native k6 thresholds") — correct, not find-and-replace.
    - `CLAUDE.md:38` and `AGENTS.md:44` give the targeted-test example without `-am`, the shape that
      silently tests installed artifacts after a sibling-module change. Folded from the epic's Open
      Defects (lessons intake C11); the corrected form is `-am -Dsurefire.failIfNoSpecifiedTests=false`.
11. **Fix the two shipped-artifact defects.**
    - `api-sheriff/src/main/docker/Dockerfile.native.jfr:29` runs `chmod 777 /tmp/jfr-output`. Settle the
      least-privilege fix (own the directory to the container user) — `integration-tests/docker-compose.jfr.yml`
      bind-mounts the path and `integration-tests/pom.xml` runs `prepare-jfr-output-dir` against it, so
      the permission exists for a reason. ⛔ Not a line deletion.
    - `deployment/compose-sample/.env:4-5` names `wait-for-ready.sh`, which no longer exists
      (`deployment/compose-sample/scripts/` holds only `start-sample.sh` and `stop-sample.sh`).

## Authoring Discipline (from the 2026-09-11 lessons intake)

This plan's deliverables are **statements about what a knob or an artifact does**, and most of its
footprint is documentation — so nothing executes these claims except the reader who follows them.
Archived lesson `2026-09-04-07-001` records the class shipping twice in this epic:

- **Every sentence about runtime or deployment behaviour names its implementing symbol before it is
  written**, and that symbol is **read at HEAD, not recalled**.
- **A paragraph that states a consequence AND explains its mechanism holds two claims** — check them
  against each other, not only against the source.
- **Verify a reviewer's refutation against the source before adopting it.**
- **Any SVG added or redrawn is rendered and viewed on both themes before it ships.**

## Claim Labels

- OBSERVED: `doc/user/` carries no scenario-organised TLS guide — its twelve entries are topic
  references, and `doc/user/tls-edge.adoc` (385 lines) is organised by mechanism: TLS policy on the
  terminated listener, Serving plain HTTP, Passthrough SNI, mTLS, emergency trust-root replacement,
  outbound trust material replacing the platform bundle, and the management interface.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: doc/user/tls-scenarios.adoc now exists as this plan's own landed deliverable
- OBSERVED: the TLS-bearing boot records the guide's failure modes draw on exist at HEAD —
  `ApiSheriff-115`, `-118`, `-119`, `-120`, `-121`, `-122` in `doc/LogMessages.adoc:72-79`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ApiSheriff-115/118/119/120/121/122 all still present in doc/LogMessages.adoc
- OBSERVED: the plain-HTTP termination shape ships as an example — `deployment/compose-sample/docker-compose.plain-http.yml`
  and `deployment/compose-sample/docker/nginx/tls-terminator.conf` exist at HEAD.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: docker-compose.plain-http.yml and nginx/tls-terminator.conf both still exist
- OBSERVED: `doc/resources/diagrams/` holds ten SVGs and none covers TLS, certificates, keys or trust.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: doc/resources/diagrams/ now holds eleven SVGs including tls-key-material.svg, landed by this plan's own deliverable 6
- OBSERVED: `README.adoc:144` is `== Known Limitations`; `README.adoc:34-35` are the two static badges;
  `release.yml:545` signs and `release.yml:364` gates; `maven.yml:84` documents the non-gating lane.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: the two static Cosign/Trivy badges removed from README.adoc entirely by this plan's own deliverable 8
- OBSERVED: `doc/fapi_status.adoc:1` reads *"(0.1.0 Alpha)"* and `doc/features-analysis.adoc:219` reads
  *"TARGET, NOT SHIPPED IN 0.1.0"* at HEAD.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: fapi_status.adoc and features-analysis.adoc no longer carry the 0.1.0 Alpha framing, fixed by this plan's own deliverable 9
- OBSERVED: `Dockerfile.native.jfr:29` runs `chmod 777 /tmp/jfr-output`; `deployment/compose-sample/.env`
  lines 4-5 name `wait-for-ready.sh`; `CLAUDE.md:14` says WRK; `CLAUDE.md:38` and `AGENTS.md:44` omit `-am`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: Dockerfile.native.jfr now uses chown/chmod 0750, .env no longer mentions wait-for-ready.sh, CLAUDE.md examples now include -am - fixed by this plan's own deliverables 10-11
- HYPOTHESIS: at least one existing `Known Limitations` entry is no longer true after this epic's
  seventeen landings — confirm/refute per entry at `README.adoc` § `Known Limitations` against the
  implementing symbol each names (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: the re-stamp-without-full-re-verification risk remains a plausible unresolved concern given the epic's continuing pace of landings
- HYPOTHESIS: `tls.passthrough_sni` is still not suppliable from the environment because the loader's
  `coerce` has no object case — confirm/refute at the config loader's `coerce` method before keeping the
  limitation (verify-at-outline).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed: ConfigLoader still has no object case in coerce, tls.passthrough_sni remains unsuppliable from a single env var
- Verify-first clause: every configuration block in the guide is settled against the shipped
  configuration files and a real boot, never against this spec's table or a derived variable spelling.
  A scenario that turns out not to be supported is removed and said so.

## Expected Surface

- OBSERVED: `doc/user/tls-scenarios.adoc` — the new guide (new file; the name may change at outline, and the declaration changes with it)
- OBSERVED: `doc/user/README.adoc` — the index row and the limitations citation
- OBSERVED: `doc/user/tls-edge.adoc` — reciprocal link
- OBSERVED: `doc/user/environment-variable-overrides.adoc` — reciprocal link
- OBSERVED: `doc/resources/diagrams/tls-key-material.svg` — the new diagram (new file)
- OBSERVED: `README.adoc` — Known Limitations and the two badges
- OBSERVED: `doc/README.adoc` — the limitations citation
- HYPOTHESIS: `doc/user/container-image.adoc` — only if deliverable 8 re-points the Cosign badge (verify-at-outline)
- HYPOTHESIS: `doc/development/release-process.adoc` — only if deliverable 8 re-points the Trivy badge (verify-at-outline)
- OBSERVED: `doc/fapi_status.adoc` — stale version stamp
- OBSERVED: `doc/features-analysis.adoc` — stale version stamp
- OBSERVED: `.plan/project-architecture/benchmarks/enriched.json`
- OBSERVED: `CLAUDE.md`
- OBSERVED: `AGENTS.md`
- HYPOTHESIS: `benchmarks/README.adoc` — only where a WRK mention is genuinely stale (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/docker/Dockerfile.native.jfr`
- OBSERVED: `deployment/compose-sample/.env`

⛔ **Named files only, deliberately.** The earlier `doc/user/` and `doc/resources/diagrams/` directory
declarations would contain PLAN-25's `doc/user/bff-cookie.adoc` and the integration-topology SVG and
serialize two plans that do not actually collide. ⛔ **`doc/configuration.adoc` is NOT declared** — it
is PLAN-25's; report findings against it.

## Dependencies and Sequencing

- ✅ **No hard dependency.** PLAN-07, PLAN-08, PLAN-16 and PLAN-17 — every dependency the two source
  specs carried — have shipped.
- ✅ **Surface-disjoint from PLAN-25 and PLAN-18 as declared** — the three form the parallel round.
- ⚠ **Soft coupling with PLAN-25**: if PLAN-25 lands a knob for the BFF client leg, the guide gains a
  row. Do not anticipate it; the orchestrator folds it in at PLAN-25's landing.
- ⛔ **Not a documentation-only footprint.** `Dockerfile.native.jfr` is gate-requiring (exercised by
  `-Pintegration-tests`) and an `.svg` is not in CLAUDE.md's documentation-only enumeration. **Both
  gates run.**

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-24-release-docs-and-tls-scenario-guide.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
