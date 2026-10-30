# Epic: API Sheriff 0.2.0 — Cleanup, Consolidation and Hardening

slug: api-sheriff-0-2-0

> Ledger document for one epic under `.plan/local/orchestrator/api-sheriff-0-2-0/`. The layout and
> authority contract live in the central standard — see
> `persona-marshall-orchestrator/standards/orchestration-model.md`. `status.json` is the
> machine authority; any statement here that conflicts with it is stale prose. This document
> describes the epic's **current state** only — no history is retained.

## Vision

Take what 0.1.0 shipped and make it **honest, current and defensible** — without adding capability.
"Done" means: the framework-agnostic architecture question is settled rather than worked around; the
code reads as current Java; the documentation and ADR corpus say what is true, once; the
reconnaissance surface is closed; the BFF meets the FAPI 2.0 Security Profile; and the
`token-sheriff` integration runs through one trust mechanism rather than a process-global override.

**Scope**: consolidation and hardening only. Every net-new capability — the deception layer, the
inventory endpoint, the Helm chart — belongs to `api-sheriff-0-3-0`. The one deliberate exception is
stated in WS-03: the per-client detection substrate is *built* here because its consumer in 0.3.0
would otherwise force a retrofit.

## START HERE

## Ordered Queue

### Queue annotations

- **PLAN-V02-19** — SHIPPED 2026-10-01 as PR #367 (`6bb90765`), see `landings/PLAN-V02-19.md`. Its
  two sequencing constraints are discharged: `PLAN-V02-04` now starts from a corpus with unique
  ordinals, guarded by `AdrOrdinalUniquenessContractTest`, and `PLAN-V02-18` may edit `CLAUDE.md`.
- **PLAN-V02-18** — SHIPPED 2026-10-01 as PR #368 (`4228d42f`), see `landings/PLAN-V02-18.md`.
  `doc/plan/` is gone; the 1.0-cut milestone now lives in the Pre-1.0 Rules section of `CLAUDE.md`
  and `AGENTS.md`. Its edit added one paragraph to each file and touched no module list, but no
  build has yet run `ReactorModuleListContractTest` against it (documentation-only commit, build
  skipped locally and in CI) — the next build-triggering change is the first proof.
- **Every ADR-authoring plan** (V02-01, V02-04, V02-06, V02-08, V02-11, V02-12, V02-13), note added
  2026-10-01: the header-matcher record is now **ADR-0056** (was the duplicate `0053`; the portal
  record keeps `0053`). The next free ordinal on `origin/main` at `6bb90765` is `0057`, which
  supersedes the "next free ADR is 0055" figure in the resume anchor. Re-derive it on the branch at
  implementation time; a duplicate now fails the build.
- **PLAN-V02-08** — SHIPPED 2026-10-02 as PR #377 (`e8db85bf`, code and ADR-0058) plus PR #378
  (`3a1182e5`, documentation), see `landings/PLAN-V02-08.md`. `PLAN-V02-12`, `PLAN-V02-09` and
  `PLAN-V02-15` no longer wait on it. Its held follow-up is staged as `PLAN-V02-20`.
- **PLAN-V02-20** — SHIPPED 2026-10-26 as PR #527 (`6ec1393b`, ADR-0072), see `landings/PLAN-V02-20.md`. The cookie
  sealing key is `oidc.session.key_file` (base64 text, 32 bytes, bounded read shared with the signing keys through
  `KeyFiles`); `oidc.session.encryption_key` is gone. The boot refuses one key for both client authentication and DPoP
  (ADR-0058 decision 2 amended). `PLAN-V02-12` and `PLAN-V02-09` no longer wait on it.
- **PLAN-V02-13** — SHIPPED 2026-10-03 as PR #383 (`5ddf8081`, ADR-0059), see
  `landings/PLAN-V02-13.md`. `PLAN-V02-06` no longer waits on it; `PLAN-V02-07` now waits on
  `PLAN-V02-06` alone.
- **PLAN-V02-10** — SHIPPED 2026-10-04 as PR #382 (`35f2bb37`), see `landings/PLAN-V02-10.md`.
- **PLAN-V02-14** — SHIPPED 2026-10-06 as PR #387 (`1a20edad`, ADR-0061), see `landings/PLAN-V02-14.md`.
  Issue #175 closed. Its residue for `PLAN-V02-01` (command-mode revisit, three unused overloads) is in
  that spec.
- **PLAN-V02-01** — SHIPPED 2026-10-09 as PR #410 (`1591972d`, ADR-0062), #412 (`862d574e`), #409
  (`9f9eeae8`), #415 (`b3185ce0`, import spacing) and #417 (`386f3f74`, Sonar), see
  `landings/PLAN-V02-01.md`. The board is no longer held by a runs-alone plan: up to three slots are
  free. Its realized footprint reached `edge/`, `pipeline/`, `events/`, `config/validation/` and
  `quarkus/` beyond its declared surface, so every staged spec touching those packages re-reads them
  at outline.
- **`PLAN-V02-12` running in two halves (operator rulings 2026-10-13, inbox `-002`).** D1–D4 ship
  now as the first of two PRs: the reserved BFF paths move to `/bff/*`, userinfo becomes
  `/bff/session`, the client-auth JWKS default becomes `/bff/jwks`, the `bff` / `bff-session` sample
  anchors are renamed, and the canonical-form check covers login, user_info and step_up — open as
  **#450**. **D5–D7 (back-channel addressing, identity repoint plus removal of
  `--host-resolver-rules`, ADR) are UNBLOCKED since 2026-10-14:** token-sheriff-client **0.9.7**
  ships `ClientConfiguration.backChannelOrigin` (cuioss/TokenSheriff#800 → PR #805), and `main` pins
  0.9.7 since #449 (`5c2a8143`: release-bot bump plus `RetryingJwksLoader.getKeyIds()` as a delegate;
  CI green including the integration lane, so the extension health checks that 0.9.7 now registers
  stay excluded). The bump is therefore done and no longer part of V02-12. The upstream shape differs
  from the request: **origin only, no `discoveryUrl`, no per-endpoint overrides, JWKS not covered**
  (the client engine fetches none). Endpoints on the issuer's origin — discovery, token, PAR,
  userinfo, **revocation** — go to the back-channel origin; other origins are left alone; metadata is
  never rewritten. So the planned `oidc.discovery_url` is dropped: the spec's D1 exposes one `oidc`
  back-channel-origin key, refuses `http` unless explicitly allowed (`allowInsecureHttp` is client-wide
  upstream), keeps JWKS on `token_validation.issuers[].jwks.url`, and uses a static Keycloak hostname
  (no dynamic back-channel). The held half runs as V02-12's second PR once #450 has merged; `PLAN-V02-23`, which it was to run
  alongside, is parked behind the 2026-10-14 refactor, and `PLAN-V02-24` waits for this second PR.
  Downstream: `PLAN-V02-09` and `PLAN-V02-15` sequence behind V02-12's D1 (that second PR). **D3 (the `/auth`-fronted Keycloak sample) is out of V02-12's running half**
  (inbox `-001`): it needs the cookie policy of `PLAN-V02-23` and V02-12's own D1, so it joins the held
  half and runs after V02-23. Since the 2026-10-13 dedup it is also an overlay of `PLAN-V02-15`'s
  BFF sample and extends that plan's CI login proof. Both have landed (#515, #516, 2026-10-24), so no plan holds
  D3 any more; it has no staged row yet, and staging one is the operator's call.
- **`PLAN-V02-21` shipped 2026-10-13** as #439 (`48e8d5a6`): D1, D2, D3 and D5; D4 (`ImageMetadataIT`)
  declined by the operator at refine. `integration-tests/docker-compose.yml` now has two YAML
  anchors (`x-gateway-hardening`, `x-gateway-backend-deps`) merged into all twelve gateway services,
  and `start-integration-container.sh` selects readiness targets positively (`^api-sheriff`) and
  emits `port …` rows: any plan adding a gateway service or a compose port merges the anchors and
  follows that row format (`PLAN-V02-23` D8, `PLAN-V02-12`'s held D3). Landing:
  `landings/PLAN-V02-21.md`.
- **Every ADR-authoring plan** (V02-04, V02-06, V02-11, V02-12): the next free ordinal on
  `origin/main` at `09edaea9` is `0067` — `0063`–`0065` came from the `kidicap-gateway-requirements`
  epic (#430 and its siblings), `0066` from the spin-off #431. Ordinals move fast; derive at write time. ADR-0005 is `Superseded by ADR-0062`; ADR-0018,
  ADR-0051 and ADR-0057 were amended by `PLAN-V02-01`.
- **Test-cycle rework landed outside the queue (2026-10-10)** — #414 (`9aac0bca`, the analysis) and
  #424 (`9f9dc6f9`, nine of its ten proposals; the analysis document deleted again), from the direct
  brief `test-duration-reduction-claude-brief.md`, not a queued plan. It touched 89 files under
  `api-sheriff/src/test/**`, `integration-tests/**`, `benchmarks/` and both test POMs. Every staged
  plan that adds or moves a test now follows three new conventions, each guarded by a contract test:
  - `api-sheriff` runs three surefire executions chosen by JUnit `@Tag` (plain classes in one reused
    fork, `@QuarkusTest` classes in another, isolated classes one JVM each) —
    `SurefireForkGroupContractTest`. A new class that pins a logger level or other JVM-wide state
    needs the isolation tag.
  - A second failsafe execution runs seven wall-clock-bound IT classes three at a time —
    `FailsafeConcurrencyContractTest`. A new IT joins it only if it shares no Keycloak user, log
    counter or fixture with another member.
  - `UnreachablePort` hands out each port once per JVM.
  `PLAN-V02-12` additionally meets two new users in `integration-realm.json` and the reworked
  `grpc-echo` build in `integration-tests/docker-compose.yml`; `PLAN-V02-11` meets the changed
  `benchmarks/pom.xml` (`package` instead of `compile` for the nested build).
- **Collision the gate did not predict (2026-10-04):** `PLAN-V02-13` and `PLAN-V02-10` ran together
  as the only disjoint pair, and both changed `edge/DispatchStage.java`, `edge/GatewayEdgeRoute.java`,
  their two tests and `doc/configuration.adoc`. Neither spec declared `DispatchStage.java`; V02-10's
  overlap came from an unplanned relay-race fix found at the merge queue. It integrated cleanly. Treat
  any plan touching the edge relay as overlapping `edge/**`, whatever its declared surface says.
- **Out-of-epic edge work (2026-10-05):** two plans outside this queue changed the edge relay and
  have both landed. `fix-four-audit-findings-and-bff-flaky-test` as #385 (`11f9c38a`): bodyless-method
  dispatch, a bounded `status_family` metric label, abort attribution, the flaky `BffRuntimeProducerTest`
  fix. `relay-release-and-jsse-env-guard` as #386 (`84afdba0`): abandoned upstream calls released,
  refused retries kept out of the circuit breaker, a non-decimal `Content-Length` refused by the framing
  gate. No plan outside this queue now holds `edge/**`. Every staged spec declaring `edge/**` was
  grounded before both; re-read `DispatchStage`, `GatewayEdgeRoute` and `FramingGate` at outline.

- **PLAN-V02-27** — SHIPPED 2026-10-25 as PR #520 (`6b8d88f4`), see `landings/PLAN-V02-27.md`. Scope cut by the
  operator at outline: `edge/` plus the five test gaps. Seven edge classes still have no test class named for
  them, and the HTTP/2 late-timeout cases still call the retirement methods directly. The rest of the cleanup
  (`config/validation/`, `config/`, `routing/`) is staged as `PLAN-V02-49`. GW-07 request body stays PARTIAL by
  operator ruling (Watch).
- **PLAN-V02-04** — SHIPPED 2026-10-26 as PR #521 (`f5826465`), see `landings/PLAN-V02-04.md`. It audited all 71
  records, removed six (five relocated, `0056` merged into `0054`) and rewrote 12; 65 remain, 17,899 → 15,289 lines
  (−14.6%), mean 252 → 235 against the early-record reference of about 136. `AdrRecordLengthContractTest` enforces
  the 500-line body ceiling. One operator-approved production fix: `WebSocketRelayStage` ends its pre-upgrade 502/504
  through `ResponseEnds.endResponse`. Residue is owned by `PLAN-V02-48` (D10, D12) and `PLAN-V02-49` (D3). Its side
  fix #522 (`e087e5ce`) repaired two flaky tests (`EdgeAdmissionTest` race vacuity, `GatewayCookiesTest` case variant).
- **PLAN-V02-48** (staged 2026-10-25): the nine ADR groups `PLAN-V02-04` deferred (52 records), plus its
  documentation residue (D12). Unblocked since #521 merged; it never runs beside an ADR-authoring plan (V02-06,
  -07, -09, -11).
- **PLAN-V02-49** (staged 2026-10-25): the `config/validation/`, `config/` and `routing/` duplicates plus the edge
  residue of V02-27 (FU-1 and FU-2 merged). It collides on `config/` and `edge/` tests with V02-05, -06, -07, -09
  and -20, so it runs after them, not beside them.

## Workstreams

| Workstream | Title | Plans |
|---|---|---|
| WS-01 | Architecture and Code Quality | PLAN-V02-01, PLAN-V02-02, PLAN-V02-14, PLAN-V02-16, PLAN-V02-24, PLAN-V02-25, PLAN-V02-26, PLAN-V02-27, PLAN-V02-28 and PLAN-V02-40 … PLAN-V02-43 (validator slices; V02-29 … -39 superseded), PLAN-V02-44, PLAN-V02-49 |
| WS-02 | Documentation and Records | PLAN-V02-03, PLAN-V02-04, PLAN-V02-15, PLAN-V02-17, PLAN-V02-18, PLAN-V02-48 |
| WS-03 | Threat Hardening | PLAN-V02-05, PLAN-V02-06, PLAN-V02-07, PLAN-V02-13, PLAN-V02-23, PLAN-V02-45, PLAN-V02-46, PLAN-V02-50 |
| WS-04 | OIDC Client Conformance | PLAN-V02-08, PLAN-V02-12 |
| WS-05 | Integration and Test Fidelity | PLAN-V02-09, PLAN-V02-10, PLAN-V02-11 |

**PLAN-V02-12 through PLAN-V02-17 are NOT in the renumbering map below.** They were authored fresh
(V02-12 on 2026-08-04 from an operator-directed analysis; V02-13/14/15 on 2026-08-07 from the
open-issue triage; V02-16/17 on 2026-08-08 from the `api-sheriff-roadmap` close-out) and carry no
`api-sheriff-next` predecessor — so an in-body `PLAN-NN` reference inside them means what it says and
needs no translation.

## Renumbering Map — `api-sheriff-next` → this epic

> ↪ Relocated to `settled.md` § "Renumbering Map — `api-sheriff-next` → this epic" — the 2026-10-02 restatement removed old plan numbers from every staged spec; older log and anchor text still uses them

## Decisions

- **2026-10-30 — `PLAN-V02-48` landed (#544, `6f06e7b2`) as part 1 of two: nine deliverables met, three met with residuals, four named items NOT done; `PLAN-V02-52`
  staged for them and held until #546 lands.** 50 ADR records were rewritten in place to the skeleton (ordinals, filenames and anchors kept; all 24 `Proposed`
  among them became `Accepted`, each with its evidence in the PR report) and nine more reworded. I re-measured the corpus from `main`: **72 records, 13,123 lines,
  mean 182.3** (before 17,328 and 240.7), the PR's figures. `0036` and `0047` stay apart, the boundary stated in both. The operator cut four items at the spec's own
  split guard: the `0022` relocation, the `0030` relocation, the `oidc_discovery` record and the amendment-citation rewrite with the invisible anchors removed. They
  are now `PLAN-V02-52`; until it ships about 56 files cite an amendment label the rendered record no longer shows, a known inconsistency, not a defect. The landing
  is `complete: true` with `effort=low`, which answers Open Defect 18. Code review closed by operator override with partial coverage (40 of 50 records unread), so
  CodeRabbit's `Merge Risk: Moderate` text about "material timeout and security-contract inaccuracies" at the merged head is carried as a lead into
  `PLAN-V02-52` D5. CI green at both PR heads, merge group and `main`; the PR-attached benchmark was still running when read (Watch). The operator answered
  "hold until #546 lands" to the overlap prompt: `PLAN-V02-52` shares two files with the running `PLAN-V02-49` (`doc/development/test-corpus-integrity.adoc`,
  `ResponseStage.java` by a glob) and four test sources by citation. Slots: `PLAN-V02-49` alone is running, in PR #546.
- **2026-10-30 — `PLAN-V02-07` landed (#537, `996f36bd`), six of six deliverables (four met, two with non-consequential residuals); `PLAN-V02-48` and `PLAN-V02-49`
  emitted.** The per-source threat score is on `main`: its own `SourceLedger` instance, weights by `EventCategory` (`UPSTREAM`, `RATE_LIMITING`, `CONFIGURATION` fixed
  at 0), one WARN ALERT (`ApiSheriff-143`) per threat episode carrying an ECS 8.17.0-shaped document, `ApiSheriff-144` for ledger overflow, a new
  `threat_classification` block with secure defaults, ADR-0078 (72 records now). **The ledger only observes**: no answer, status, header or timing changes, and the
  block has no `action` member. The dedup the ALERT promises is one per **episode** (the substrate re-arms after the score drains to zero), and a recon trip is an
  input to the score, never a second ALERT. 47 files (+5,205 / −269). Merged under an operator barrier override, not an unreviewed change: CodeRabbit reviewed clean at
  the final head, but its rewritten summary lacks the participation marker the gate keys on (routed to `lessons-routing` as `-074`, with `-075` and `-076`). Verified
  green on `main` at `996f36bd`: Maven Build, Integration Tests, Config Validation, Demo Client E2E, Scorecard and the PR benchmark (the landing reported the benchmark
  as unobserved; it passed). The integration run passed at attempt 1 at all three PR heads, so the `Http2StreamAbuseIT` Watch has no recurrence. The landing was
  `complete: false` again (`effort` unknown, second in a row): Open Defect 18 routed as `lessons-routing` `-077`. Spec surface and counts of `PLAN-V02-48`
  (71 → 72 records) and `PLAN-V02-49` (dependency text, inventory predates #532 and #537) were corrected in place and two claim verdicts re-stamped. **Emitted both**: the
  gate admits each against in-flight work (nothing is running), but the two share 8 declared paths with each other (V02-48's "repoint every inbound ADR reference" clause
  against V02-49's test directories; real overlap is `doc/development/test-corpus-integrity.adoc`). Overlap prompt answered by the operator: **emit both**. Rows are `launched`
  (`auto_emit` is on); `launched → running` stays with the operator, who confirmed the start of both the same day, so both are `running` (2 of 3 slots).
- **2026-10-30 — `PLAN-V02-51` landed (#538, `7755b948`), three of three deliverables; Open Defects 14 (#201), 7 and 9 closed.** #201 is **settled as a
  lane misconfiguration, not a gateway fail-open**, by nine counted runs (the two rejection legs pass 3/3 with the `test.mtls.*` properties and fail 0/3
  without them); no production file changed, the `jfr` exclusion stays, and `MtlsLaneSelectionContractTest` guards it. `BenchmarkScriptConfigContractTest`
  binds the k6 scripts to the gateway keys they use; `LogMessagesCatalogueTest` now covers every reactor module. 7 files (+4,401 / −231), all test code, a POM
  comment and developer docs. The post-merge `main` runs at `7755b948` and the PR-attached benchmark are green. CI showed one
  `Http2StreamAbuseIT.declaredBodyReachesTheUpstreamWithItsLength` failure on attempt 1 of the PR's integration run (green on attempt 2, same head); it is a Watch,
  not a closed flake. Of four candidate lessons: one promoted (`2026-10-30-05-001`, guard readers are narrow allowlists), three routed to `lessons-routing`
  (`-071` recurrence of `-049`/`-062`, `-072` recurrence of `-032`, `-073` new, related to `-040`/`-061`). The landing was `complete: false` (`effort` unknown; Open Defect 18).
  `PLAN-V02-07` is now the only running plan; `PLAN-V02-48` and `PLAN-V02-49` stay behind it.
- **2026-10-29 — `PLAN-V02-50` landed (#532, `9794fa41`), nine of nine deliverables (seven met, two with residuals).** The
  per-source detection substrate (`detection/`), its trip response, the HTTP/2 abuse bounds (`gw-08` is `COVERED`; `gw-02` stays
  `PARTIAL`, upstream reset pinned against HTTP/1.1 only), ADR-0076, ADR-0077, ADR-0073 Amendment A1 and the #528 residue are on
  `main`; 91 files; the post-merge `main` runs and the PR benchmark are green. **The shipped default action is `observe`**, so
  detection records a trip and changes nothing on the wire unless an operator opts into `block` or `delay`. `PLAN-V02-07` is now
  eligible; its spec was corrected in place (substrate claim corroborated, dedup is per `CLEAR` episode, `doc/architecture/`,
  next ordinal `0078`). `PLAN-V02-48` (71 records) and `PLAN-V02-51` (ordinal, dependency note) were corrected the same way. Of
  nine candidate lessons: three promoted, four folded or discarded as recurrences, two routed to `lessons-routing`; the owed
  `architecture enrich` hint from `-009` was declined (it would record a tolerance for load flakes that contradicts the standing
  rule that a CI-red integration test is a suspected production defect).
- **2026-10-28 — `PLAN-V02-11` landed (#534, `c1ded6c1`); Open Defects 14, 7 and 9 staged as `PLAN-V02-51`.** ADR-0075 adopted
  Open Defect 6 (narrowed, now resolved) and declined 14 (#201), 7 and 9, so the ledger's routing of them to V02-11 left them
  ownerless. Asked the operator; answer: **stage one plan for all three** (options were a #201-only plan, record-only, close 14
  as a refuted premise). `PLAN-V02-51 lane-conditional-guards`, WS-05, staged, lane `plan-marshall`; D1 settles #201 with runs
  (fix on fail-open, lane guard on misconfiguration, report if neither), D2 guards the k6 consumers of the shared
  `gateway.yaml`, D3 extends `LogMessagesCatalogueTest` past one module. Surface `declarative`, 12 entries; overlaps V02-50 on
  `integration-tests/**`. #201 was NOT closed on the exclusion comment alone.
- **2026-10-28 — `PLAN-V02-11` is running** (operator confirmed the start); with `PLAN-V02-50` two of three slots are taken.
- **2026-10-28 — emitted `PLAN-V02-11` into a free slot.** Overlap prompt answered "Emit V02-11" (gate: 15 shared files with
  V02-50, 4 with the now-landed V02-09). The row is `launched`; `launched → running` stays with the operator. The spec gained
  the ADR-writing guidance (`adr-writing.adoc`, the 500-line guard, next ordinal `0075`). The pre-commit gate churn stays a
  Watch (operator: watch only). No other staged plan is eligible: V02-07 waits on V02-50, V02-48 may not run beside an
  ADR-writing plan, V02-49 collides with both on tests.
- **2026-10-28 — `PLAN-V02-09` landed as #530 (`0114065c`, ADR-0074); analyzed.** Verified green on `main`: Maven Build,
  Integration Tests, Config Validation, Demo Client E2E, Scorecard and the PR benchmark; issue #174 is closed. It decided the
  config-mapping question as a hybrid: the extension's metrics collector now observes the gateway's `@GatewayValidator`
  validator (JWT validation metrics are published), the extension's health probes stay excluded
  (`quarkus.arc.exclude-types=de.cuioss.sheriff.token.quarkus.health.*`), and no projection onto `sheriff.token.issuers.*` was
  built. ADR-0074 supersedes ADR-0027; ADR-0032, ADR-0041 and ADR-0050 were amended in place. `oidc_discovery` is a separate
  readiness datum that never changes the verdict. The BOM comment in `pom.xml` was re-measured at token-sheriff 0.9.8. Residue
  went to `PLAN-V02-48` D12 (three statements and one ADR record no ADR holds yet) and `PLAN-V02-49` D3 (one weak test). The
  pre-commit gate rewrote about 400 files on every run: see the Watch. Two slots are free; V02-50 runs.
- **2026-10-27 — free slot held again.** After `PLAN-V02-05` landed, V02-11 was the only eligible plan (gate: 15 shared files with V02-50, 4 with V02-09); the operator chose to hold it. Nothing was emitted.
- **2026-10-27 — `PLAN-V02-05` landed as #526; analyzed.** Verified: Maven Build, Config Validation, Demo Client E2E and
  Scorecard, Integration Tests (40551758384) and the PR benchmark (40551763060) green on `main` at `be7034ac`. Shipped: the
  identity-header strip (`Server`, `X-Powered-By`) on the HTTP relay, the gRPC relay and the upstream asset action with a
  per-route `keep_identity_headers` opt-out; the `not_modified` close narrowed by two operator decisions to the two 304
  validators on `GET`/`HEAD`; `headers_deny` folded over `_`/`-`; the fingerprint documentation. **Spec D2 (security
  headers default-on) was not delivered: the operator ruled at refine that no header is default-on and every header is
  configured explicitly; D2 became documentation.** If default-on headers are wanted, they are a new plan. Open Defect 1
  is closed. `PLAN-V02-48` also keeps ADR-0008 and ADR-0037, amended in place. Lessons: three project lessons
  (2026-10-27-17-001 to -003) and four plan-marshall findings routed to `lessons-routing`; the `finalize-main-drift`
  memory was extended.
- **2026-10-27 — `PLAN-V02-50` and `PLAN-V02-09` are running** (operator confirmed the starts); with `PLAN-V02-05` all three slots are taken.
- **2026-10-27 — emitted `PLAN-V02-50` and `PLAN-V02-09` into the two free slots.** Operator ruling of 2026-10-26
  (fill up to three at once when `PLAN-V02-06` lands), overlap prompt answered "V02-50, V02-09". The gate shows
  V02-50/V02-05 sharing 13 files, V02-09/V02-05 26 and V02-50/V02-09 10 (config and edge sources and tests); the operator
  accepted them. Both rows are `launched`; `launched → running` stays with the operator. `PLAN-V02-09` gained the ADR
  guidance (`adr-writing.adoc`, the 500-line guard, next ordinal `0074`). Held back: V02-07 (waits for V02-50), V02-11
  (held earlier), V02-48 (never beside an ADR-writing plan) and V02-49 (last).
- **2026-10-27 — `PLAN-V02-06` landed as #528, partly; `PLAN-V02-50` staged for the rest.** #528 (`c957ee51`, ADR-0073,
  98 files) shipped spec D1 (the uniform `404` for untrusted callers), D2 (the response-timing floor and its step rule)
  and D9 (the `rate_limit` boot WARN and the wording), with their tests and docs. The operator split the other four
  deliverables off at outline because nine deliverables in one PR would have passed the 100-file cap: D3 (per-source
  detection substrate), D4 (response on trip), D7 (substrate contract) and D8 (HTTP/2 stream-abuse bounds). They are
  `PLAN-V02-50` (`enumeration-detection-substrate`, WS-03), with the unwritten ADR for the kept-but-inert `rate_limit`
  key, five documentation advisories, the findings deferred to the follow-up (recorded in the git-ignored security
  notes) and a flaky test. `PLAN-V02-07` now waits on `PLAN-V02-50`. The sibling epics' references to V02-06's
  substrate (`PLAN-V03-01`; kidicap `PLAN-21`, `PLAN-30`) point at a plan that no longer owns it. Verified green on
  `main` at `c957ee51`: Maven Build, Integration Tests, Config Validation, Demo Client E2E, Scorecard and the PR
  benchmark. #529 (`c9c8479c`), an operator docs fix, closed the two overstated offline-check statements that #527
  left (they are no longer owed to `PLAN-V02-48`). `PLAN-V02-48` also learned that #528 amended seven ADRs in place.
  Lessons: one project lesson (2026-10-27-14-001) and three plan-marshall findings routed to `lessons-routing`.
- **2026-10-27 — `PLAN-V02-20` landed as #527; analyzed.** Verified on `main` at `6ec1393b`: Maven Build, Integration
  Tests, Config Validation, Demo Client E2E, Scorecard and the PR benchmark are green; the retired-name sweep finds only
  the deliberate residuals. The corpus is 66 records (`0072` added). Two overstated "offline verdict never differs from
  the boot's" statements (an `OidcClientRules` Javadoc and `offline-config-validation.adoc`) are owned by `PLAN-V02-48`
  D12, which also states the `0058` amendment as current fact (D2). Fourteen candidate lessons: seven were project
  lessons and became three local lessons (2026-10-27-05-001 to -003); seven were plan-marshall tooling and went to
  `lessons-routing` as four findings. The `PLAN-V02-09`/`-12` sequencing constraint on `PLAN-V02-20` is retired.
  Free slot: the operator chose to hold it until `PLAN-V02-06` lands (both running plans are in finalize), then fill
  up to three slots at once. Nothing was emitted.
- **2026-10-26 — the stale local plan `plan-v02-23-cookie-forwarding-policy` was discarded** by operator instruction
  (`manage-status delete-plan`, 14 files, no lesson to carry back). It was the first, paused attempt at `PLAN-V02-23`
  (stopped at 4-plan on 2026-10-14 for the refactor chain); `plan-v02-23-cookie-forwarding-policy-r2` shipped as #515.
  The gate no longer counts its 66-file surface as a live plan.
- **2026-10-26 — `PLAN-V02-06`, `PLAN-V02-05` and `PLAN-V02-20` are running** (operator confirmed the starts).
- **2026-10-26 — emitted `PLAN-V02-06`, `PLAN-V02-05` and `PLAN-V02-20` when `PLAN-V02-04` landed.** Operator
  instruction: "after ingesting, emit the next three". `PLAN-V02-07` waits for `PLAN-V02-06`. The gate read every
  pair: V02-06/V02-05 share `SecurityHeadersConfig.java`, V02-06/V02-20 share `OidcConfig.java` and
  `LogMessages.adoc`, V02-05/V02-20 share documentation only (`configuration.adoc`, the cookie pages,
  `tls-scenarios.adoc`). The operator accepted those overlaps for this triple (overlap prompt answered "V02-06,
  V02-05, V02-20"). The three rows are `launched`; `launched → running` stays with the operator. The three specs now
  point their ADR step at `doc/development/adr-writing.adoc` and the 500-line guard. Held back: V02-07 (waits for
  V02-06), V02-09 (never beside V02-20), V02-11 (held earlier), V02-48 and V02-49 (V02-48 never beside an ADR-writing
  plan, and V02-06, V02-05 and V02-20 each write one; V02-49 collides with all three on `config/` and `edge/` tests).
- **2026-10-26 — free slots are filled when the running plan lands, three at a time.** Operator ruling at the
  emit after `PLAN-V02-27` landed: with `PLAN-V02-04` running, nothing else is emitted. When it lands, the
  orchestrator emits up to three plans (n=3) instead of filling slots one by one. Every candidate still passes the
  disjointness gate and the overlap prompt. Recorded as the answer to the overlap prompt for V02-05, V02-20 and
  V02-49: hold back, no overlap judged.
- **2026-10-25 — the V02-27 follow-ups are one plan, the V02-04 follow-up is one plan with a cut line.**
  `PLAN-V02-27` named two follow-ups (config/validation, and config plus routing); both are thin and touch one
  fixture class, so they are merged as `PLAN-V02-49`. `PLAN-V02-04` asked for one follow-up for its nine deferred
  groups; it is staged as `PLAN-V02-48` with twelve deliverables (the twelfth added at its landing), one PR and a stated cut line (the three
  repoint-heavy decisions) if the projection passes the 100-file bot cap.
- **2026-10-22 — security findings that are not fixed stay out of git.** Operator decision. A plan
  fixes a security finding right away whenever possible. One that is not fixed is recorded only in
  the git-ignored `.plan/local/security_notes.adoc`, never in tracked text (commits, PRs, code, docs,
  inbox or landing messages, this ledger). Every unshipped spec carries this as its "Security
  Findings" section; specs staged from now on carry it too. Running plans get it through their mailbox.
- **2026-08-04 — the backlog is split by target version.** `api-sheriff-next` was a single
  undifferentiated backlog; it is replaced by this epic and `api-sheriff-0-3-0`, and removed.
- **2026-08-04 — enumeration hardening lands here, not in 0.3.0.** Operator decision. The plan
  closes an existing status-code oracle, which is hardening. The recorded and accepted cost: the
  per-client detection substrate it builds ships a release *ahead* of its principal consumer
  (honeypot, `PLAN-V03-01`). Design the substrate general-purpose here — retrofitting it later is
  the more expensive order.
- **2026-08-04 — FAPI 2.0 conformance lands here.** Operator decision. It closes a gap against a
  security profile in an already-shipping flow, and it carries a breaking `oidc` configuration
  change that is better taken earlier than deferred.
- **2026-08-04 — workstreams re-cut from WS-01.** The old WS-05..WS-09 split across both new epics,
  so carrying the numbers would have left gaps in each and put WS-05 in both.
- **2026-08-08 — `parallelization_scope` = 3, `auto_emit` = true.** Operator decision at `decompose`,
  discharging the opening anchor's obligation (3). Both were previously unset at epic level while
  `.plan/marshal.json` carried `2` / `true`, so the epic was inheriting by accident — which is what
  the anchor warned against. **Carrier note**: `marshal.json` still reads `parallelization_scope: 2`
  and was deliberately **not** changed — `.plan/` config is outside the orchestrator's write boundary
  per Watch (31), and it is the *epic metadata* that `orchestrate.md` Step 4 reads. The `2` is a
  dormant project default nothing in this epic consumes. A `marshall-steward` run owns reconciling it.
- **2026-08-08 — the `PLAN-V02-07` → `PLAN-V02-06` merge is RETIRED**, superseding the 2026-07-27
  ruling and discharging obligation (2). Two facts absent when that ruling was taken changed the
  balance: `PLAN-V02-13` now re-categorises the `EventType`/`EventCategory` taxonomy V02-07 weights
  (so V02-07 must follow it, a dependency the substrate work does not need), and a merged spec would
  carry ~11 deliverables — far past the split guard. **The original intent is preserved by contract
  rather than by merger**: V02-06 gains a new deliverable 7, a written general-purpose substrate
  contract naming V02-07 as its first consumer. The substrate is still designed once. A substrate
  V02-07 must generalise on arrival is V02-06 D7 failing, and is a finding against V02-06.
- **2026-08-08 — the epic is re-grounded at `963e422`**, discharging obligation (1). All 17 specs
  carry a `## Re-Grounded` section that outranks their stale bodies. Per-plan carries now live in the
  specs, per the decompose contract, rather than being duplicated in the resume anchor.
- **2026-08-08 — the lessons corpus was audited and `PLAN-V02-17` re-clustered.** > ↪ Relocated to `settled.md` § "Decision 2026-08-08 — lessons corpus audit and PLAN-V02-17 re-clustering" — V02-17 shipped (PR #200); `logs/decision.log` remains authoritative
- **2026-10-02 — the twelve staged specs are restated as current state, on operator instruction.**
  `PLAN-V02-01`, `-04`, `-05`, `-06`, `-07`, `-09`, `-10`, `-11`, `-12`, `-13`, `-14` and `-15` no longer carry
  `## Re-Grounded` sections, renumbering notes, status trails or dated provenance. Each finding those
  sections held is folded into the Objective, Deliverables, Dependencies or an "already in place"
  section, so a spec is read top to bottom as the brief. The earlier decision that every spec carries
  a `## Re-Grounded` section outranking its body is retired with this one. The superseded text is
  in the ledger's git history. `## Claim Labels` verdict bullets are kept: they are the
  machine-read field the prep-ready test parses, not narrative. One declared surface changed —
  `PLAN-V02-10` now declares the compose file, `NoCertificatePlainHttpOptInIT.java` and
  `doc/user/tls-scenarios.adoc`, and no longer `TokenValidatorProducer.java` or `doc/user/`. Running
  `PLAN-V02-08` and the shipped specs are untouched. From here on a spec is corrected in place;
  no dated section is appended to it.
- **2026-10-06 — the server-mode session store stays custom; a session-management security audit is
  added to `PLAN-V02-01`.** Operator decision after a review of the store against the usual failures of
  hand-rolled HTTP sessions. `@SessionScoped` is rejected (Servlet-backed, needs `quarkus-undertow`,
  never active on the Vert.x edge route); Vert.x `SessionStore` is rejected (fixes idle timeout and
  reaping but drops the size cap, the absolute lifetime and `sid`/`sub` logout, and needs new
  infrastructure to cluster). The store keeps its design and gains ID rotation on step-up and widening,
  an idle timeout and a periodic sweep (D5). Multi-replica server mode is served by sticky sessions in
  the orchestration layer. D7 is a very thorough audit of both session modes against every known
  successful attack class on session management — the plan's most important deliverable; unfixed
  findings are reported to the operator only. `PLAN-V02-01` grows to seven deliverables, unsplit.

- **2026-10-13 — the absolute `Cookie` strip is a bug; cookies become an operator policy (`PLAN-V02-23`).**
  Operator ruling on `PLAN-V02-12`'s finding (inbox `-001`): "removing all cookies is a bug". Settled
  in discussion after a survey of other gateways (Kong/Traefik/NGINX forward all; KrakenD opts in per
  endpoint, all-or-nothing; Duende BFF strips the whole header) and an adversarial review. Policy:
  `none` / `all` / a YAML list of names, resolved gateway → anchor → endpoint → route with whole-block
  replacement; default `none`; global `all` refused; API Sheriff's own cookies never reach an upstream
  under any policy; the same policy limits which cookies an upstream may set, and `Domain` is dropped
  from relayed `Set-Cookie`; a gateway-side same-origin check guards every cookie-forwarding route
  (Envoy model); `headers_allow: [Cookie]` and `set_headers` naming `Cookie` become boot errors.
  **ADR-0037 is rewritten in place, not superseded** (it is still `Proposed`). User docs are part of
  the plan. Eight deliverables, proceeding unsplit: the two directions, the CSRF guard and the record
  are one contract.
- **2026-10-17 — one plan at a time.** Operator ruling: plans run serially, never two at once. The
  orchestrator hands out the next command only when the running plan has landed. The order is V02-40
  (running) → V02-25 → V02-41 → V02-12's second PR → V02-42 → V02-43 → V02-45 → V02-46 → V02-23
  (re-grounded) → V02-27 → the rest. This replaces the "two at once under the rebase rule" allowance in
  the slice specs. Off-surface fillers (V02-11, V02-22, V02-04) also wait their turn.
- **2026-10-15 — plan-marshall findings go to plan-marshall, not into this repository's lesson corpus.**
  This is an operator standing rule, the same one `kidicap-gateway-requirements` follows. Every
  plan-marshall-related finding drained from this epic's inbox is routed to the `lessons-routing` epic of
  the sibling repository `plan-marshall`, sender `api-sheriff-api-sheriff-0-2-0`. That covers phases,
  finalize steps, the orchestrator, build tooling and review automation. Its inbox is
  `.plan/local/worktrees/_orchestrator/.plan/orchestrator/lessons-routing/inbox/`, relative to that
  repository's root, and messages are written with that repository's own `orchestrator inbox write
  --slug lessons-routing`. Project-local findings (API-Sheriff code, docs, tests) are still promoted
  here. **A plan-marshall lesson already filed here is removed once routed** ("always remove if filed").
  First batch: `api-sheriff-api-sheriff-0-2-0-001` … `-005` (V02-28's five candidates). Then `-006` …
  `-009` (V02-26's lessons `2026-10-14-20-002` … `-005`). The local copies of both batches are removed
  (`2026-10-15-15-001` … `-004` and `2026-10-14-20-002` … `-005`). Older plan-marshall lessons filed here
  before 2026-10-14 are not yet swept.
- **2026-10-15 — re-packed the refactor: four validator slices instead of eleven; V02-25 split in two.**
  Operator approval of the orchestrator's proposal.
  - (1) The 150,000-character cap is Sourcery's, and Sourcery is optional in `.plan/marshal.json`. For
    **move-only** work the cap is lifted. The 100-file cap (CodeRabbit, required) still holds. A PR
    passing ~350 KB, or one that CodeRabbit declines, splits at a group boundary. The eleven slices
    `PLAN-V02-29` … `-39` (superseded) become `PLAN-V02-40` … `-43`. That saves about seven plan runs
    (each ~5–7 M tokens and 5 h or more), and V02-23 now waits behind about seven refactor PRs instead of
    about fifteen.
  - (2) `PLAN-V02-25` is split up front (lesson `2026-10-14-20-004`). `PLAN-V02-44` merges `ResolvedRoute`
    and `RouteRuntime` and carries V02-24's residue; it runs now, concurrently with kidicap PLAN-32
    (#466), under the rebase rule. `PLAN-V02-25` keeps the `GatewayEdgeRoute` decomposition, which is
    move-only under rule (1), and runs after V02-44 and PLAN-32.
  - The auth-floor derivation moves with `AnchorRules` into `PLAN-V02-41`.
- **2026-10-14 — the whole `ConfigValidator` split runs before V02-23 (`PLAN-V02-28` … `-39`).**
  Operator ruling at V02-26's landing. V02-26 shipped only slice 1 (#463, `RouteMatchingRules`,
  `TokenValidationRules`, `RuleSupport`). Its spec had asked for the whole split in one PR under the
  150,000-character cap, and those two constraints could not both hold. The remaining 13 rule groups,
  about 1 MB of diff, are staged as 12 slice specs that run serially. Two may run at once under the rebase
  rule, and the slices run alongside V02-24 and V02-25. This changes ruling (7) below: the cookie bug now
  waits behind about 15 refactor PRs, not two to three. Rejected: running only the slices V02-23 touches
  before it, and stopping the split at slice 1.
- **2026-10-14 — refactor design settled (operator, in discussion).** (1) **Normalise** the policy blocks
  onto the one documented rule gateway → anchor → endpoint → route; today each block has its own chain,
  scope set and fallback (`auth` has no gateway scope, `allowed_methods` no route scope, `security_filter`
  only route → anchor, `security_headers` only anchor → gateway). V02-24 first lands the mechanism with the
  current chains (behaviour-neutral), then applies per-block scope sets the operator decides at outline,
  as explicit breaking changes. (2) The override WARN keeps meaning "both scopes declare". (3) Lombok
  `@Builder` on the config records; tests stop constructing them positionally. (4) V02-25 MERGES
  `ResolvedRoute` and `RouteRuntime` (the per-route context already exists as `RouteRuntime`; the cost is
  the field-by-field copy in `RouteRuntimeAssembler`), not a wrapper. (5) `GatewayEdgeRoute`'s jobs move
  into sub-packages of `edge/`, guarded by an architecture test. (6) V02-26 is done now. (7) The cookie bug
  waiting behind two to three refactor PRs is accepted. (8) Order as proposed. (9) Proof is the existing
  suite, no golden comparison; once the refactor has merged, a separate plan simplifies the tests
  (`PLAN-V02-27`, after V02-23).
- **2026-10-14 — rework the edge pipeline and policy-block resolution before the cookie policy
  (`PLAN-V02-24`, `-25`, `-26`; `PLAN-V02-23` parked).** Operator ruling during V02-23's outline
  ("GatewayEdgeRoute is a nightmare. We need a major rework here."), reported in inbox V02-23 `-001`. V02-23's
  outline came to ~67 files and 170k–230k diff characters, over the review bots' limit, and about half was
  structural cost every new per-route policy pays: no shared policy-block resolution (each block has its
  own hand-written chain in `RouteTableBuilder`), records constructed positionally in tests (`AuthConfig`
  104 times in 8 files), a 2,664-line `GatewayEdgeRoute` threading per-route values by hand, a 3,273-line
  `ConfigValidator`. Three plans, split by surface so each PR stays under the limits: V02-24 (one
  resolution mechanism, test builders), then V02-25 (edge, resolved route context) in parallel with V02-26
  (validator by concern). V02-23 resumes afterwards, re-grounded on those seams, with the rulings it already
  carries; its docs deliverables first create one canonical never-forward section. The refactor waits for
  the two plans in flight on those surfaces (V02-12's second PR, kidicap PLAN-32) rather than rebasing
  across them.
- **2026-10-13 — `rate_limit` is planned for later: kept, but no longer silent (`PLAN-V02-06` D9).**
  Operator ruling. The key stays in the endpoint schema, still not implemented, but setting it logs a
  boot WARN, and every document says the same thing: planned, not implemented, no `429` today. This
  replaces both wordings on `main` ("out of scope", from `6acbadac`, and "deferred"). Its future
  implementation builds on the per-source mechanism of `PLAN-V02-50` (moved there from `PLAN-V02-06`), as its own plan after that lands;
  none is staged yet. Rejected: removing the key now (the PLAN-29 / ADR-0064 route), and wiring it into
  V02-06 (which already carries eight deliverables).

## Sequencing Constraints

Derived at the 2026-08-08 re-grounding. These bound every `next` emit; disjointness alone is not
sufficient.

| Chain | Order | Why |
|---|---|---|
| structural refactor, then cookie policy | **V02-24 (shipped 2026-10-15, #465, ADR-0067) → V02-44 (route-type merge, running; concurrent with kidicap PLAN-32 #466 under the rebase rule) → V02-25 (decomposition, after V02-44 and PLAN-32); in parallel the validator slices V02-26 (shipped, #463) → V02-28 → V02-40 → V02-41 → (V02-12's second PR merged) → V02-42 → V02-43 (closes the split, writes the ADR) → V02-23 (re-grounded, after V02-25 and V02-43) → V02-27 (test simplification) → every other plan touching `edge/**` or `config/**`** | Operator decisions 2026-10-14 (inbox V02-23 `-001`, V02-26 landing): V02-23 paused and the edge and policy-block structure reworked first; the whole validator split finishes before V02-23. V02-24 makes a policy block a declaration. V02-25 (edge) and the validator slices (`config/validation/`) are disjoint from it and from each other. The slices edit `ConfigValidator` and `ConfigValidatorTest` and run serially, or at most two at once under the rebase rule. Held behind them: V02-05, -06, -07, -20, -45, -46; kidicap PLAN-20, -21, -26, -29. Edge hardening order after V02-25: V02-45 (request-body and HTTP/2 buffer bounds) and V02-06 D8 (`gw-08`) run one after the other, never together; then V02-46 (upstream close). |
| oidc / `BffRuntimeProducer` | **V02-08 → V02-12 → V02-09** | All three write the `oidc` block and the BFF assembly point. V02-08 first: it is the larger reshaping and settles the RFC 9207 `iss` constraint V02-12 inherits. |
| ~~rejection taxonomy~~ | **DISCHARGED 2026-10-30** | V02-13 (#383), V02-06 (#528) and V02-07 (#537) have all shipped. |
| sample vs. model | **V02-12 → V02-15** | V02-15 D5 defers the browser-vs-container issuer address to V02-12 rather than letting a sample become the de-facto specification. |
| doc structure | **V02-03 before or after V02-17, chosen deliberately** | Both write `doc/development/**`. Landing prose into documents V02-03 is about to split wastes both. |
| ADR-authoring exclusion | **V02-04 runs against none of** V02-06, V02-07, V02-11, V02-12 (V02-01 and V02-13 have shipped) | V02-04's spec forbids concurrency with any ADR-authoring plan. Also excluded: V02-09, whose ADR-0027 re-opening V02-04 is told not to pre-empt. |
| ~~broad Java sweeps~~ | **DISCHARGED 2026-10-09** | V02-01 and V02-02 have both shipped. |
| ~~runs alone~~ | **DISCHARGED 2026-10-09** | V02-01 shipped; the arch gate is now `PreBootFrameworkFreeArchTest` over the five pre-boot packages. |
| ~~trust wiring~~ | **EDGE REMOVED 2026-08-09** | V02-10's D1 moved into V02-09 as its D6, so the conditional now lives inside the plan that takes the decision. **V02-10 is reduced to the compose cleanup and is UNBLOCKED.** |

**Freest candidates, revised 2026-08-09 after four landings** (V02-16 and V02-17 have shipped):
**V02-10** (now a single-deliverable compose cleanup, unblocked by the redistribution below),
**V02-14**, **V02-05**, **V02-04**. **V02-13** is the head of the WS-03 chain and unblocks two plans
behind it, so it has the highest downstream value of any staged plan.

**Updated 2026-08-09 after the first two landings.** Three constraints in the table above are now
settled by events rather than pending:

- **`V02-01` is unblocked.** Its only live blocker was concurrency with `V02-02`, which has shipped.
  It is the epic's load-bearing plan and it **runs alone**, so emitting it consumes the whole board.
- **The doc-structure row is discharged.** `V02-03` landed first, so `V02-17` now writes into the
  settled `doc/development/**` layout rather than racing it — the deliberate choice the row demanded
  was made by the landing order.
- **`V02-13`'s `doc/plan/04-request-pipeline.adoc` reference is discharged** — that file is among the
  nine `V02-03` deleted. The directory survives (three deliberate keepers) and **must not be
  resurrected**.

**Second-order cost of a filled slot**: concurrent Docker IT suites contend for local CPU in a repo
with documented contention-driven IT startup flakes, and `main` is merge-queue-gated. Prefer a
doc-only or build-light plan for the second and third slots.

## Open Defects

17. > ↪ Relocated to `settled.md` § "Open Defect 17 — post-merge benchmark red since #408 (resolved 2026-10-10 by #427)" — the flood moved to `BffPendingLoginBoundIT`; benchmark green again

16. > ↪ Relocated to `settled.md` § "Open Defect 16 — upload benchmark HTTP/2 GOAWAY ENHANCE_YOUR_CALM (resolved 2026-10-13 by #431)" — gateway resets early-answered streams itself (ADR-0066); five clean runs

15. > ↪ Relocated to `settled.md` § "Open Defect 15 — snapshot deploy 401 after the Maven 3.10.0 bump (resolved 2026-10-03)" — resolved by cuioss-organization v0.35.0, verified on main

14. > ↪ Relocated to `settled.md` § "Open Defect 14 — `MtlsHandshakeIT` fails under `-Pjfr` only, issue #201 (settled 2026-10-30 by #538, a lane misconfiguration)" — nine counted runs; the `jfr` lane never supplied the mTLS properties; no production change; guarded by `MtlsLaneSelectionContractTest`

10. **CARRIED PAST THIS EPIC — `doc/plan/09-release-readiness.adoc` becomes actionable AT THE 1.0
    CUT.** Opened 2026-08-09 by `PLAN-V02-03` D3. The file is **not superseded**: its defining
    deliverable is the 1.0.0 cut and the flip of the pre-1.0 rules, which has not happened — the
    project is 0.1.1 alpha and `CLAUDE.md` still carries the Pre-1.0 Rules. The outline had listed it
    for deletion; **verification refused, because an expectation is a prior, not a licence.**
    `01-base-implementation.adoc` likewise survives (no counterpart across all 41 landing records,
    enumerated rather than sampled) and needs no action. **This entry must survive into the successor
    epic** — 0.2.0 cannot close it, and closing it here would silently convert "not yet due" into
    "done".
11. > ↪ Relocated to `settled.md` § "Open Defect 11 — doc/README.adoc fully-adapted claim (closed 2026-10-09)" — closed; #197 removed the sentence
12. > ↪ Relocated to `settled.md` § "Open Defect 12 — RouteRuntimeAssembler dead WebSocket allocation (resolved by #476)" — resolved by #476 (PLAN-V02-44)
13. > ↪ Relocated to `settled.md` § "Open Defect 13 — applyJwks orphan, folded into PLAN-V02-09 (closed 2026-08-09)" — closed; the method no longer exists

1. > ↪ Relocated to `settled.md` § "Open Defect 1 — upstream stack-identity leak (closed 2026-10-27 by #526)" — `Server` and `X-Powered-By` stripped on all three upstream-header paths
2. > ↪ Relocated to `settled.md` § "Open Defect 2 and Issue #189 remainder — closed 2026-10-22 by #512"
3. > ↪ Relocated to `settled.md` § "Open Defect 3 — demo start script readiness contract (closed 2026-10-09)" — already fixed since #341
4. **LOW, not release-gating** — the test-corpus integrity backlog PLAN-43 left as a countable
   residual: 43 files / 140 marker occurrences, enumerated in
   `doc/development/test-corpus-integrity.adoc`. Reported as a count rather than as prose.
5. > ↪ Relocated to `settled.md` § "Open Defect 5 — BFF secrets missing from the env-var page (closed 2026-08-09)" — closed by `PLAN-V02-03` D2 (PR #197, `89a3cfe`)
6. **STRUCTURAL** — the IT suite cannot detect resource-lifecycle or layer-boundary defect classes.
   Its stated owner was roadmap PLAN-34, **which shipped**, so it arrived here orphaned rather than
   fixed. Not strikeable on sight: it carries independently CI-observed defects and a deferral
   transmitted to a public PR thread. **Re-verify against the landed PLAN-42 work before striking** —
   PLAN-34's D4/D5 left in the 2026-08-02 three-way split and PLAN-42 shipped both, so the gap may be
   narrower than this entry says. Read the landed code, not the split decision. **RESOLVED 2026-10-28 by `PLAN-V02-11` (#534,
   `c1ded6c1`, ADR-0075):** the gating detector already existed (`WebSocketProxyIT#sequentialRelaysReturnTheirAdmissionPermitsAtTeardown`
   on `api-sheriff-ws-admission`); what was missing was a demonstrated failure, which two committed negative controls now supply
   (relay sub-budget and general admission budget). Residue: the general-admission control cannot wait for gateway-side
   admission (Watch). Strike at the next compaction.
7. > ↪ Relocated to `settled.md` § "Open Defect 7 — the k6 benchmark scripts are an unguarded consumer of the shared gateway configuration (closed 2026-10-30 by #538)" — `BenchmarkScriptConfigContractTest` binds each k6 script to the keys it depends on
8. **Unverified Sonar claim** — PLAN-06's uncorroborated Sonar claim is still unverified with no
   recovered evidence, and needs an **operator live-gate check** rather than plan work. Carried
   rather than closed because "we could not recover the evidence" is not "the claim was false", and
   closing it would silently convert one into the other.
9. > ↪ Relocated to `settled.md` § "Open Defect 9 — `K6BenchmarkLogMessages` outside the catalogue guard (closed 2026-10-30 by #538)" — `LogMessagesCatalogueTest` derives the module set from the root `pom.xml`

18. **LOW, informational** — the `PLAN-V02-51` landing message (`plan-v02-51-lane-conditional-guards-005`) is `complete: false`: `landing-check`
    names the missing key `effort`, carried as `unknown`. Every other required fact drained, and the landing was reconciled in full. `PLAN-V02-50`'s landing
    carried `effort=medium`, so this is intermittent, not a missing feature. The cost: the run's effort level is unrecorded in `landings/PLAN-V02-51.md`'s
    metrics. Recorded 2026-10-30; no owner. Strike if the next landing carries `effort`; if two in a row do not, route it to `lessons-routing`.
    **Update 2026-10-30:** the `PLAN-V02-07` landing (`plan-v02-07-threat-classification-001`) is `complete: false` for the same key, so two in a row. Routed to
    `lessons-routing` as `api-sheriff-api-sheriff-0-2-0-077`; the plan's own residue says the model was self-reported and no effort was stamped. Stays open here as
    informational until a landing carries `effort` again or plan-marshall answers.
    **ANSWERED 2026-10-30:** the `PLAN-V02-48` landing carries `effort=low` and is `complete: true`. The gap was intermittent. Strike at the next compaction.

## Watches

- **THE `PLAN-V02-48` PR BENCHMARK WAS UNOBSERVED, AND CODERABBIT'S MERGE-RISK TEXT NAMES INACCURACIES IT DOES NOT LOCATE** (added 2026-10-30, `PLAN-V02-48` landing).
  The PR-attached Performance Benchmark run for #544's head (`41091771555`) was `in_progress` when I read it. The change is ADR prose, Javadoc and one contract test,
  so a measurement cannot have moved; still read the run's conclusion at the next analyze before calling it green. Separately, CodeRabbit's final summary on #544 says
  the documentation refresh "retains material timeout and security-contract inaccuracies" and attaches to no thread. It is third-party text and unverified; `PLAN-V02-52`
  D5 re-reads the timeout and security-contract statements in the rewritten records against the code. If that finds nothing, strike this Watch.
- **THE APISIX `bearer` BENCHMARK LANE HAS NO VALID SIDE, SO `run-comparison.sh --target both` STOPS THERE** (added 2026-10-30, `PLAN-V02-48` landing).
  `benchmarks/README.adoc` line 46 says so ("`bearer` has no valid APISIX side for now"), and the comparison is deliberately wired into no workflow. No owner; low. It
  matters only if a gateway-versus-APISIX comparison is wanted for the 0.2.0 notes.
- **THE THREAT ALERT'S ECS SHAPE IS ASSERTED BY FIELD NAME, NOT VALIDATED AGAINST AN ECS SCHEMA** (added 2026-10-30, `PLAN-V02-07` landing). The spec's verify-first
  clause asked that the field set be scoped against the actual ECS schema and "validate against a real consumer". ADR-0078 pins `ThreatAlertDocument.ECS_VERSION` at
  `8.17.0` and the tests assert the document's fields; I found no test that checks the document against an ECS schema artifact or a SIEM consumer. The field names are the
  published ECS names, so the likely cost of being wrong is a mapping tweak at the consumer. No owner; if an operator reports a SIEM rejecting the document, fix the field in
  `ThreatAlertDocument` and add the schema check then. The OCSF mapping is documented only, by decision (ADR-0078).
- **`Http2StreamAbuseIT.declaredBodyReachesTheUpstreamWithItsLength` FAILED ONCE IN CI WITH NO PRODUCTION CHANGE ON THE BRANCH** (added 2026-10-30,
  `PLAN-V02-51` landing). Run `40968785321` at head `917b4797`: attempt 1 failed at `Http2StreamAbuseIT.java:212` ("the h2 -> HTTP/1.1 framing of a
  declared body (4 failures)", 358 tests, 1 failure); attempt 2 of the same head passed. `#538` changes no production file, so the failure sits on `PLAN-V02-50`'s
  code or test. The plan files it under "the known HTTP/2 framing flake class"; that is a classification, not a root cause. **Treat a recurrence as a suspected
  production defect**: reproduce under `-XX:ActiveProcessorCount=1`, and read it beside the `PROTOCOL_ERROR` Watch below (second failure of this class, in a
  different method of the same class) and the framing-corpus memory. `main` at `7755b948` is green. No owner; stage a fix plan if it recurs.
  **2026-10-30, `PLAN-V02-07` landing:** no recurrence. The pull-request Integration Tests run passed at attempt 1 at all three heads of #537 (`929e0fdc`, `54a3af18`,
  `5988f533`), as did the merge-group and the `main` push run at `996f36bd`. One more clean landing; not enough to close it.
- **THE THREE NEW GUARD READERS HAVE UNTESTED REFUSAL BRANCHES** (added 2026-10-30, `PLAN-V02-51` landing). `MtlsLaneSelectionContractTest` (1,599 lines),
  `BenchmarkScriptConfigContractTest` (2,048) and the widened `LogMessagesCatalogueTest` (+868) parse POM, glob, k6 and Java-source forms by hand; four code-review
  rounds and two CodeRabbit rounds each found another input form misread, and the readers converged only after becoming narrow allowlists. The plan records the
  remaining untested refusal branches (and `any()`/`none()` and reference-depth advisories) as notes in its decision log, not fixed. Informational: the size is a
  maintenance cost, and a guard that refuses an input it should read fails loudly, not open. If a legitimate POM or script edit trips a refusal, widen the allowlist with a
  specimen (lesson `2026-10-30-05-001`) rather than loosening the reader. No owner.
- **`Http2StreamAbuseIT` RAPID-RESET `PROTOCOL_ERROR` CAUSE INFERRED, NOT REPRODUCED** (added 2026-10-29, `PLAN-V02-50` landing,
  residual D4.R2). CI once saw `rapidResetFloodEndsTheConnectionWithEnhanceYourCalm` end with `GOAWAY PROTOCOL_ERROR` instead of
  `ENHANCE_YOUR_CALM` (run at `4f69c4ef`). The plan fixed the raw test client (`3cc695db`: the `SETTINGS` acknowledgement could
  precede the client preface); that cause is inferred from RFC 9113 ordering. Later runs, including the `main` run at `9794fa41`,
  are green. If this IT recurs red, treat it as a suspected production defect and reproduce under `-XX:ActiveProcessorCount=1`
  before classifying it as a flake; the plan's local notes hold a second candidate cause. **Recurrence note 2026-10-30:** the
  same class failed again in `declaredBodyReachesTheUpstreamWithItsLength` (see the Watch above), so the class has now
  shown two failures in two landings.
- **UNIFORM-404 FLOOR MEASURED ON EIGHT PATHS ONLY** (added 2026-10-29, `PLAN-V02-50` landing, residual D8.R1). The declared-length
  fast-reject, a route-scoped filter violation, the cookie-forwarding same-origin refusal and the `protocol: grpc` route are
  caller-drivable pre-trust paths outside `EnumerationOracleIT`'s measurement. ADR-0073 A1 and `gw-16` state the scope as eight.
  No owner; fold into the next plan that touches `edge/error/` or the oracle IT. `PLAN-V02-07` landed (#537) and touched `ErrorResponseWriter` but not the oracle IT or
  the floor, so this stays open; the threat feed changes no answer, which its `ErrorResponseWriterTest::threatFeedChangesNoAnswer` pins.
- **FINAL-TREE INTEGRATION SUITE NOT RUN LOCALLY FOR #532** (added 2026-10-29, `PLAN-V02-50` landing, residual D4.R1). CI's
  integration lane (JDK 25 and 26, native image) and the post-merge `main` run are green, so this is informational; it is the
  same environmental block `PLAN-V02-11` reported. Retire it with the first plan that runs `-Pintegration-tests` locally to green.
- **`gw-02` PARTIAL: UPSTREAM RESET PINNED AGAINST AN HTTP/1.1 UPSTREAM ONLY** (added 2026-10-29, `PLAN-V02-50` landing). The
  threat model names the open clause. No owner; a later hardening plan (`PLAN-V02-07` landed without touching it).
- **LOW-BUDGET FIXTURE HEADER OVERSTATES ITS FIDELITY** (added 2026-10-28, inbox
  `plan-v02-11-resource-exhaustion-test-home-001` item 1). `integration-tests/src/main/docker/sheriff-config-ws-admission/gateway.yaml`
  says the overlay differs from the primary `gateway.yaml` in "exactly two" things; it also lacks
  `allow_get_with_content_length_body: true` and the TLS `min_version` / `cipher_suites` pins. ADR-0075 was corrected; the comment
  was not. Comment fix only, no owner; fold into the next plan touching `integration-tests/src/main/docker/**`.
- **GENERAL-ADMISSION NEGATIVE CONTROL CANNOT WAIT FOR GATEWAY-SIDE ADMISSION** (added 2026-10-28, same message, item 2). The
  control counts a held request once the client subscribed to the withheld body; nothing confirms `EdgeAdmission.tryAdmit()` ran.
  The race can only fail the control loudly, never pass it vacuously (accepted on #534). If it flakes, the fix is an in-flight
  admission gauge on the management interface, a production change needing its own plan. Watch for a flake in
  `WebSocketProxyIT`; the `EdgeAdmission` area is shared with `PLAN-V02-50`.
- **PRE-COMMIT GATE REWRITES ABOUT 400 FILES (IMPORT SPACING) — UNVERIFIED, NO OWNER** (added 2026-10-28, inbox
  `plan-v02-09-token-sheriff-integration-fidelity-002`). The memory records the churn as fixed by #415; `PLAN-V02-09` saw it on
  every `verify -Ppre-commit` run in its worktree and reverted it each time, and OpenRewrite also put a `TODO` marker on a
  catch block that records an error and rethrows it. Not reproduced by the orchestrator. Check it on a clean `main` worktree
  before the next plan that runs the gate; if it reproduces, stage a fix plan. **Recurrence 2026-10-29:** `PLAN-V02-50` (candidate
  `-001`) reported the same unauthored-file rewrites on every gate round and reverted them each time; two plans in a row now.
  **Recurrence 2026-10-30:** `PLAN-V02-07` hit it as well (three plans in a row) and paid a second cost: reverting the rewrite left the tree at a hash no build row carried, so
  the freshness gate went stale and an extra non-mutating build (about 7 minutes) was needed to mint a matching row. Routed to `lessons-routing` as `-076`. The churn itself
  is still unreproduced by the orchestrator and still has no fix plan; with three plans reporting it, check it on a clean `main` worktree before `PLAN-V02-48` and
  `PLAN-V02-49` run the gate.
- **HAND-MAINTAINED INVENTORIES NOT BOUND BY A TEST — NO OWNER** (added 2026-10-27, inbox
  `plan-v02-05-response-hygiene-012`). `DocumentedHeaderSetsContractTest` binds the identity set and both protocol tiers to
  their constants. The route-field inventory (`doc/configuration.adoc`, `endpoint.schema.json`) and the never-forward and
  response-strip sets are still hand-maintained, and the repository's review bot re-raises such lists until a test binds
  them. Bind them in whichever plan next touches those sets.
- **FLAKY `GatewayEdgeRouteTest$RelayStartFailure.relayThatFailsToStartReleasesTheUpstreamResponse` — FIXED BY `PLAN-V02-50` (#532), WATCH 1 MORE RUN** (added
  2026-10-27, inbox `plan-v02-06-enumeration-hardening-007`; fixed 2026-10-29). The test is rewritten to a deterministic shape
  (a 4 MiB unread upstream body, an awaited condition) and the relay release path it exposed was fixed
  (`UpstreamAttemptReleaseOrderTest`). The `ActiveProcessorCount=1` confirmation is the plan's claim, not the orchestrator's;
  `main` CI at `9794fa41` and `7755b948` is green (its Maven Build, which runs the unit lane, passed).
  Retire at the next green `main` run after a load-sensitive CI job. Other open flakes:
  `GatewayEdgeFramingCorpusTest` and `Http2EarlyResponseResetIT`.

- > ↪ Relocated to `settled.md` § "Watch — superseded reviewer cuioss-review-bot (closed 2026-10-22 by #505)" — PR-Agent prose retired by #505 (PLAN-V02-43), guarded by ReviewerRosterDocsContractTest

- **PER-ROUTE UPSTREAM TIMEOUTS PARSE BUT DO NOT ACT — OWNED BY KIDICAP `PLAN-29`** (added 2026-10-15,
  inbox `plan-32-early-answer-it-timeouts-001`). `connect_timeout_ms` and `read_timeout_ms` exist only in
  `UpstreamConfig` and `endpoint.schema.json`, and no runtime reader applies them. Verified with
  `git grep` at `73721077`; ADR-0064 lists the gap as open. Kidicap `PLAN-29` (upstream inert keys)
  already carries the wire-or-remove decision for both keys, so no spec here, per the one-owner rule. The
  finding's comparison (connect defaults 3 s Caddy, 5 s Envoy, 30 s Traefik and Spring Cloud Gateway,
  60 s NGINX and Kong; HAProxy warns at boot for missing timeouts) is in the archived message for
  PLAN-29's outline. `PLAN-V02-45` (#506) shipped a fixed 25 s request-body deadline and a fixed
  five-minute gRPC stream-idle bound (ADR-0060 Amendment A2), neither configurable; a per-route
  `read_timeout_ms` wired by PLAN-29 has to state how it relates to them. PLAN-29 is
  held behind this epic's refactor chain (kidicap's ruling).

- **FIRST REAL RELEASE AFTER #509, NOT YET HAPPENED** (added 2026-10-22, inbox
  `release-lane-hardening-001`). #509 (`00293178`) reordered the image join in
  `.github/workflows/release.yml`: the index is pushed under a candidate tag `leg-<sha>-index`, asserted,
  signed and verified there, and only then promoted to `<version>` and `sha-<sha>` and read back. Dry runs
  39732222258, 39751125940 and 39759377284 passed, but they promote to `dryrun-*-promoted`; no release
  run has used the real tags yet (latest tag still `0.2.4`, linux/amd64 only). The next release is also
  the first two-platform index (`PLAN-V02-47`). Check on that run: (1) candidate, sign and verify steps
  all succeed before `Promote the verified index to its final tags` and its assert step; (2) `<version>`
  and `sha-<sha>` resolve to the same digest, an index of exactly linux/amd64 and linux/arm64, the
  digest that was signed (`docker buildx imagetools inspect ghcr.io/cuioss/api-sheriff:<version>`);
  (3) the verification command in `doc/user/container-image.adoc` verifies the index and both platform
  digests; (4) `leg-<commit>-index` sits next to the two `leg-<commit>-<arch>` tags and is not counted
  by the release skill's "exactly one of each" check; (5) `cleanup-dry-run` is skipped. A failure
  before promotion leaves no release tag (re-run the join alone); a promote or read-back failure can
  leave one; a promoted digest that differs from the signed one is a real defect, so the release is
  treated as partial. Close this watch once one real release passes the checklist. **Also owed by that release
  (from `PLAN-V02-15`, #516, 2026-10-24):** bump the pin in `deployment/compose-sample/.env` (still
  `ghcr.io/cuioss/api-sheriff:0.2.4`, which predates the BFF keys the sample now sets) and run
  `deployment/compose-sample/scripts/check-bff-login.sh` against the published image; until then the default
  sample runs only against a local build through `API_SHERIFF_IMAGE`. CI proves the HTTPS variant's login
  only; the plain-HTTP variant (`start-sample.sh --plain-http`) was proven locally.

- **RELEASE-GUARD DOCS CHECK FAILS A PIN MOVE UNTIL THE SECTION IS RE-CHECKED** (added 2026-10-22, from the
  `PLAN-V02-22` landing, #512). New workflow `.github/workflows/release-guard-docs.yml` runs on pull
  requests into `main` that touch `release.yml`, `doc/development/release-process.adoc` or itself, with
  `ReleaseGuardPinDocsContractTest` behind it. When the `cuioss-organization` release workflow or the
  `release-guard` pin moves, the pins and quoted reason lines in the section must be re-read first. The
  check is intended; the Watch exists so that the first pin bump (Renovate or manual) is not read as a
  broken build. It is path-triggered, so an absent check-run is not a pass (standing rule 2). Close it
  once one pin bump has gone through the check.

- **`PLAN-V02-21` DOCUMENTATION LEFTOVERS, NONE STAGED** (added 2026-10-13, from the landing).
  ADR-0031 line 115 still says the integration stack runs "seven gateway instances"; there are twelve.
  `demo-client/scripts/start-dev-environment.sh` keeps a literal `120` for its Keycloak wait (lines
  244–260), against the amended ADR-0031's one-named-constant rule. Both are still present at `abf911dc`:
  `PLAN-V02-22` (#512) edited the script but did not take the literal. No owner; fold them into the next
  plan that touches ADR-0031 or the script, or into `PLAN-V02-04`'s truth check for the ADR.

- **HTTP/1.1 DRAIN 502 FLAKE IN THE FRAMING CORPUS, NONE STAGED** (added 2026-10-13). The `main` push
  `Maven Build` on `09edaea9` (#431, run 38364519502) failed in `build (25)`:
  `GatewayEdgeFramingCorpusTest.wellFramedControl` — *"the drain request must be proxied: HTTP/1.1
  502 Bad Gateway"*; 1 of 899 in the isolated fork, `build (26)` cancelled after it. The merge-group
  run on the same tree passed. This is the HTTP/1.1 drain 502 that stayed open after #403 fixed the
  HTTP/2 framing flake and predates #431. #431 did edit `GatewayEdgeRoute` and `ResponseStage`, which
  the HTTP/1.1 path shares, so whether it changed the flake's rate is not established; watch the next
  push runs before attributing either way. **Second sighting (2026-10-13):** #437 (kidicap
  `PLAN-28`) reports a CI-only 502 in the same corpus on `soleTransferEncodingNotExactlyChunked`, not
  reproduced locally, which it calls "partly related to #431" and leaves unexplained. **Earlier
  sightings (kidicap `PLAN-24`):** a `502` on Java 26 in CI and a 30 s timeout locally, in two
  methods; PLAN-28 could not reproduce either on Java 25 and did not run Java 26; #431 reworked the
  timeout's path, so that half may be gone (kidicap lesson `2026-10-12-16-012`). It guards request
  framing, so re-running until green can hide a regression. **This is the only entry tracking the
  flake across both epics** (kidicap's Open Defect now points here). Owner when it recurs: whichever plan next touches `edge/` (`PLAN-V02-06`), or a dedicated
  fix. Reproduce with `-XX:ActiveProcessorCount=1`.
  **Related, separate test (2026-10-22, `PLAN-V02-46` landing, #513):**
  `Http2EarlyResponseResetIT.earlyAnsweredUploadsStayAnsweredWithoutGoAway` failed once in the merge_group
  Integration Tests run `39812067667`: *"every stream must end in the gateway's NO_ERROR reset; the client
  reset the rest itself with CANCEL … expected: <400> but was: <390>"*, two connections used. The same tree
  was green on PR CI and on the re-enqueue (`39823097862`), and post-merge `main` at `e70743f7` is green. First
  observed failure of this test; it guards the #431 early-response reset path. A merge-queue-only failure
  here may be a real reset-before-write race, as the HTTP/2 framing flake was (#403), so a re-enqueue is not
  an answer. On a recurrence, reproduce under `-XX:ActiveProcessorCount=1` with repeated runs first, and check
  whether an early answer plus the abortive upstream close can still send CANCEL or GOAWAY to unrelated
  streams. Same owner rule as above. Source: inbox `plan-v02-46-upstream-close-after-early-answer-001`.

- **`PLAN-V02-23` (#515) ADVISORIES LEFT UNFIXED, NONE STAGED** (added 2026-10-24, from the landing). The
  plan left 7 code-review and 15 functional-review advisories open. The landing names these: commit messages
  that still say `ApiSheriff-123` (history, not fixable); the reader list in `http/package-info.java`;
  `RouteTableBuilder` emitting `ApiSheriff-101` on terminal routes; the `@return` wording of `setCookieName`;
  the BFF-2 heading in `doc/quality-report/`; the outline's D2 change note. Read the full list in the plan's
  archived findings before acting. Owner: whichever plan next touches `config/RouteTableBuilder`, `http/` or
  `doc/quality-report/`, or a dedicated cleanup. The security audit also recorded one design follow-up; it is
  in the git-ignored local security notes only.

- **TEST-CYCLE REWORK RESIDUE (#424), NONE STAGED** (added 2026-10-10). Open issues #422 (lazy
  key-material generators from TokenSheriff, proposal 7) and #423 (IT concurrency not realised: the
  idle-timeout legs and four sequential suites). Quality points the brief's "no decrease" rule
  makes worth watching: `BffCookieRefreshIT` now observes one refresh per run where it observed
  three, and tests sharing a wait fail together; the two operand orders of `ConfigValidator`'s
  anchor-overlap check are covered only by map iteration order (no test fixes it); the commit
  messages of three #424 commits still claim "identical" coverage from accumulated JaCoCo data,
  which the PR body itself retracts. The cause is `<append>true</append>` on one `target/jacoco.exec` with no `clean` in the coverage command (corroborated 2026-10-25 from `pom.xml`); `PLAN-V02-27` hit it again and lesson `2026-10-25-22-001` records the rule (measure with `clean`). A profile that resets its own exec data would be a build change and has no plan. Fourteen tests were removed as duplicates, each with a named
  survivor in the hygiene commit message — not re-read here.

- **`PLAN-V02-01` D7 RESIDUE, NONE STAGED** (added 2026-10-09, from the landing). Not found in the
  landed diff: a recorded verdict on `PLAN-V02-08`'s two status choices (`502` on a refused widening
  re-drive push, `400` with ApiSheriff-134 for an unbound token on a widening callback); whether the
  refresh-revocation and nonce-retry items folded into D7 on 2026-10-06 were handled was not
  checked. `BFF-21` itself names two controls no test asserts (`no-store` on a gateway-rendered page
  that sets a cookie; no upstream `Set-Cookie` relayed on a WebSocket upgrade). D6's check of
  `ClientHelloSniParser` for open Sonar findings was not reported. The per-attack-class audit
  catalogue is an operator-only document and is not in the repository. Stage a plan when wanted.

- **DECLINED REFACTOR FROM #387, NONE STAGED** (added 2026-10-06). CodeRabbit proposed deriving the
  offline validator's NOT CHECKED catalogue from shared refusal descriptors instead of a hand-kept list
  (ADR-0061 § Risks). Declined in #387 because it reaches the TLS, JWKS, BFF and portal refusal paths.
  Stage a plan when the hand-kept list drifts or the refusal paths are touched anyway.

- > ↪ Relocated to `settled.md` § "Open Defect 2 and Issue #189 remainder — closed 2026-10-22 by #512"
- **ADR-0059 IS STILL `Proposed` — OWNER: `PLAN-V02-48` D9** (added 2026-10-04). The decision is shipped (the ROUTING
  problem category, #383). ADR-0060 was flipped to Accepted by #521 (`PLAN-V02-04`, 2026-10-26); 0059 sits in the
  deferred group V02-48 D9 and needs its acceptance decision there.
- **OPERATOR DECISION OWED: a simplification in `DispatchStage`'s inbound-body handling** (added
  2026-10-05, inbox `fix-four-audit-findings-and-bff-flaky-test-001.md`). The plan behind #385 left one
  simplification finding there for an operator decision instead of changing it. The finding text is
  not in this ledger; read it in that plan's archived findings. `DispatchStage` is shared with live
  work, so decide before another plan touches the edge relay.

- **POST-FAPI FOLLOW-UPS LEFT BY `PLAN-V02-08` — FOLDED 2026-10-06: the refresh-revocation, nonce-retry and two status-code items into `PLAN-V02-01` D7; the same-key-file refusal and the `cnf` cookie size into `PLAN-V02-20` D3a (shipped in #527); the canonical-form check into `PLAN-V02-12` D4; login rate limiting to kidicap `PLAN-21`; the three imprecise texts and the conformance-suite run stay here, unstaged** (added 2026-10-03; the plan's claims,
  each already stated in #377/#378 or their documents). A rate limit in front of login initiation,
  which now drives one outbound pushed request per call (threat model `BFF-20`) — owned by the sibling
  epic `kidicap-gateway-requirements` as its staged `PLAN-21` (login-flood pending records), so not a
  candidate for this queue; refusing a
  configuration whose two key files name the same file; re-measuring the cookie size with the `cnf`
  claim present; the engine's nonce-retry gaps, and a refused token response discarding its refresh
  token instead of revoking it; no canonical-form check on `oidc.login.path` and
  `oidc.user_info.path`; three imprecise executable texts; two answers chosen conservatively at the
  merge (`502` on a refused widening re-drive push, `400` with ApiSheriff-134 for an unbound token on
  a widening callback). No conformance-suite run was made, so no FAPI conformance claim exists.
  Stage a plan from this list when one of them is wanted; until then it is a record, not work.
- **INHERITED TOOLING AND PROJECT-CONFIG CONSTRAINTS — not fixable by any plan here, and every plan
  in this epic runs under them.** Carried as one watch rather than five duplicated entries so the
  copies cannot drift; full text and evidence live in the `api-sheriff-roadmap` ledger under the
  numbers given.
  - **(31) HIGH, the dangerous one**: `q_gate_validation: once` routes Q-Gate findings to a review
    gate that `plan_without_asking: true` **disables**, so BLOCKING findings reach task planning
    ungated and nothing reports it. The pairing occurs twice — `phase-3-outline` and `phase-4-plan`.
    On PLAN-27 it let through a hardening block that would not have started and a bucket
    misassignment that would have stripped the verify lane, both caught only by manual operator
    intervention. The two settings are individually reasonable and **jointly unsound**. OWNER: a
    `marshall-steward` run — `.plan/` config is outside every plan's write boundary **and the
    orchestrator's**.
  - **(22) HIGH**: no plan runs the Docker IT suite pre-push, so IT feedback comes only from CI after
    push. `verification_steps` holds quality-gate, module-tests and coverage only, and the root
    resolve fails. **Do not work around it via `per_deliverable_build`**: the config layer accepts
    the entry but the standard forbids the placement and a non-zero exit is a hard STOP, so a
    deliverable touching only `api-sheriff` would halt the plan. Attempted and reverted 2026-07-30.
  - **(25)**: `documentation.skills_by_profile.module_testing` is empty, so a task legitimately
    domained documentation under module_testing resolves the persona floor alone — no doc testing
    standards, no verification recipes — and nothing reports it.
  - **(30)**: the deep-lane component-assessment sink is empty (a two-plan recurrence), so section
    2.2 assessment-coverage is unevaluable on any deep-lane plan; an empty sink is indistinguishable
    from "the pass ran and matched nothing". **Third recurrence, 2026-09-24** (inbox
    `deployment-configurability-007.md`, from sibling PLAN-28 / PR #341): `assessment list` again
    returned `total_count: 0` on a deep-lane run, so the Q-Gate evaluated 35 declared paths over an
    empty population. The same run hit two more instances of the same shape:
    `derive_gate_bundles` left 19/37/52/56 footprint paths unbundled, and no module-tests canonical
    resolved. Folded here as a recurrence, not as a new entry, and still owned upstream.
  - **(16)+(20) — PARTLY DISCHARGED 2026-08-09, and the correction matters.** The *mechanism* half is
    confirmed by exhausting the surface: `ci checks status` accepts only `--pr-number` / `--head`,
    `--head` resolves as a **branch name** rather than a SHA, `checks wait` takes only
    `--pr-number`, and `checks rerun` / `checks logs` take a `--run-id` that **no verb in the
    abstraction can discover from a commit**. `ci repo` offers only merge-queue and label. The
    by-merge-commit lookup is genuinely unreachable through the abstraction.
    **What was WRONG is the conclusion drawn from it.** "No sanctioned execution path" was read as
    "unverifiable", and the axis then went unchecked across four merge commits. It is verifiable in
    one read-only `gh` call, and on operator instruction it was done: **all four are green** — see
    § Post-Merge Verification. The standing rule is corrected: a landing report records the
    post-merge check as OWED **until it is checked**, not indefinitely, and the check is an
    operator-authorised `gh` read rather than a tooling change.

  All five are delivered upstream as bundle material. **Re-check by executing the mechanism, never by
  reading a version number.**

- **STANDING CLAUSES, still binding**: three-layer docs in the same PR; Sonar zero-findings with red
  a HARD STOP; **named line items survive outlining** (this project has twice had named deliverables
  abstracted away); activate-it-in-an-IT (lesson `2026-07-25-15-001`, which already recurred verbatim
  once); **the plan stops at the merge** and the post-merge check is the orchestrator's; every emit
  carries an explicit `plan_id`; mark a claim OBSERVED only with a read behind it; **a green suite is
  not evidence for a behavioural claim** until its assertions are read.
- **A capability present in a dependency is not a capability of the product.** Earned on the FAPI
  claim: `doc/features-analysis.adoc` asserted a PAR-driven sender-constrained flow because the
  engine shipped `ParClient` and `SenderConstraint` while the gateway wired neither. The
  native-image reflection registration is what made it look evidenced — it lists the dependency's
  whole DTO surface, so grepping for a class name finds a feature that is never invoked. **Settle
  every completion claim at the call site, never at the import.** *Re-confirmed 2026-08-08: the only
  `ParResponse` mentions in the tree are still reflection registrations.*
- **In-body `PLAN-NN` references in these specs were deliberately not rewritten** at the 2026-08-04
  renumbering, because many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve
  through the renumbering map above; a number absent from that map belongs to another epic and is
  unchanged. Each spec's `## Re-Grounded` section now translates the ones that matter to it.
- **A spec's asserted surface absence can be overturned by its own outline**, so a disjointness check
  against a RUNNING plan reads that plan's **current outline**, never its staged spec.
- **A `/marshall-steward` RUN IS NOW OWED BY TWO INDEPENDENT ROUTES.** Added 2026-08-09. Beside
  Watch (31)'s config pairing, `PLAN-V02-03` D3 left the architecture inventory's project description
  reading *"…follow the plans under `doc/plan/`"*, which is stale now that nine of those twelve files
  are deleted. It is **steward-regenerated, not hand-editable**, so the plan flagged it rather than
  patching it — the correct call. Two unrelated findings now point at the same un-run mechanism.
  **Third route, 2026-10-09:** `PLAN-V02-01` reported `marshal.json` stale against the regenerated
  executor (the plan's claim, not checked here).
- **A SPEC-ASSERTED COUNT IS A CLAIM, NOT A MEASUREMENT — and the orchestrator is the author who owes
  the re-derivation.** Earned 2026-08-09 on `PLAN-V02-02` D3: a headline occurrence count propagated
  spec → clarified request → outline unchallenged, arriving at implementation as three
  mutually-corroborating statements of one unverified number. **The contested instance is itself the
  second data point** — the plan measured 20, this epic's own spec table sums to 19, and a
  re-measurement at `e343404` returns 19. Two parties each derived a confident total from the same
  document and disagreed. Re-derive every count in a staged spec from its own breakdown before the
  spec is emitted; the check needs no codebase access.
- **EVERY SPEC SENTENCE ASSERTING CURRENT REPOSITORY STATE IS A MEASUREMENT WITH A TIMESTAMP — the
  consuming phase re-establishes it rather than inheriting it.** Earned 2026-08-09 on `PLAN-V02-17`,
  and it completes a three-instance pattern in four landings, all in specs the orchestrator authored:

  | Plan | The defect | Found by |
  |---|---|---|
  | `V02-02` | an occurrence count **asserted, not derived** | measuring during the run |
  | `V02-16` | an Expected Surface **accurate but insufficient** — named the consumer, not the channel | attempting to implement it |
  | `V02-17` | a premise about current repo state **stale and false** | running the gate |

  The V02-17 case is the sharpest because the orchestrator supplied the reasoning: resolving the
  formatter contradiction it asserted that *"bringing `main` to the fixed point is the durable fix
  nobody has done"*. **A full-reactor gate run produced zero rewrites — the repository is already at
  the fixed point.** The verdict (*revert unrelated churn*) survived; the reason did not, and the
  shipped page carries a better one: the fixed point is a property of the tree at a point in time,
  not a guarantee. **The common shape is structural, not carelessness** — a spec asserts facts at
  authoring time and executes later, and nothing in between re-measures unless someone does.
- **AN EXPECTED SURFACE MUST BE CHECKED FOR SUFFICIENCY, NOT ONLY ACCURACY — and this one is the
  orchestrator's own.** Earned 2026-08-09 on `PLAN-V02-16`. Its spec named `Dockerfile.native` +
  `.github/workflows/release.yml` for the revision-label deliverable. Every claim in that surface was
  **accurate**, and the surface was **not implementable**: `release.yml` performs no `docker build`
  and forbids adding one, so the only channel into the image is `integration-tests/docker-compose.yml`'s
  `build.args` — a file the spec never named. **Implementing the literal surface would have shipped an
  empty `revision` label on every published image**, a new falsehood inside the plan whose objective
  was to stop the image lying. At the 2026-08-08 re-grounding the orchestrator read this spec and
  recorded *"every claim holds; no correction is owed"* — verifying the claims made, never asking
  whether the surface reached the deliverable. **The distinction is consumer vs. channel**: naming the
  consumer reads like naming the surface and does not build. Trace the value from producer to
  artifact and name every hop. Sixth instance of the enumerate-every-carrier rule, and the first
  inside a spec the orchestrator wrote itself.
- **A TEST LANE NOBODY CAN RUN IS NOT A PASSING LANE, IT IS AN UNMEASURED ONE.** Earned 2026-08-09:
  the `-Pjfr` lane could never start (root-owned bind mount vs. a uid-1001 container), so its green
  was the **absence of execution**. Repairing it surfaced Open Defect (14) — a fail-open handshake
  rejection — within the same run. Where a lane is broken, prefer fixing it to scoping around it; the
  cost is bounded and the yield is whatever the lane has silently not been checking.
- **ASSERTED ABSENCES NEED A CONTROL QUERY — three known false-negative mechanisms, not one.**
  `architecture search --content` returns a clean `count: 0` over compose/infra YAML and
  `src/main/resources`; `git grep <pattern> origin/main -- 'path/**'` returns a clean zero for every
  query in this tree while the same search without the pathspec returns hits; and — **added
  2026-08-08, found during this epic's own re-grounding** — an **unquoted `--include=*` in `grep -r`
  is shell-expanded into a filename list**, silently dropping real hits. That third one is a
  different mechanism from the other two (shell expansion, not tool behaviour), so knowing the first
  two does not predict it. **Run a control query before trusting any asserted absence.**
- **CONTRACT TESTS RUN ONLY WHEN A BUILD RUNS** (added 2026-10-01, `PLAN-V02-19` landing). The ADR
  ordinal-uniqueness test and the module-list test are Maven tests, so a documentation-only change
  — which skips the build by this repository's own rule — can still land a duplicate ordinal or a
  drifted module list, caught only at the next build-triggering change. Left open by design; same
  shape as the `build.map` CI-side gap `CLAUDE.md` already documents. No plan staged.
- **ARCHITECTURE DESCRIPTORS STILL NAME `doc/plan/`** (added 2026-10-01, `PLAN-V02-18` landing,
  the plan's claim — unverified lead). `.plan/project-architecture/_project.json` and
  `documentation/enriched.json` still describe "remaining implementation plans under doc/plan/";
  the finalize architecture refresh reported no structural change and did not rewrite them. Outside
  the epic tree, so not edited here. Needs a `/marshall-steward` pass; no plan staged.
- **TOOLING GAPS REPORTED BY `PLAN-V02-19`, OWNED UPSTREAM IN plan-marshall — UNVERIFIED LEADS**
  (added 2026-10-01). Reported by the plan, not corroborated by the orchestrator: (a) `ci-verify`
  filed red-CI findings under producer `ci-verify-policy`, which the verification-feedback workflow
  rejected as `unknown_producer`; (b) the pre-push gate derived no gate bundles for Maven paths and
  the whole-tree module-tests canonical did not resolve at the reactor root, so the gate row read
  DEGRADED and a full verify was run instead; (c) `manage-status transition`'s mailbox probe
  reported the plan as not orchestrated while `orchestrator inbox detect` reported orchestrated;
  (d) `cuioss-review-bot` is not re-requested on a loop-back (`re_review_on_loopback` false), so a
  `/review` comment was posted by hand. **Observed by the orchestrator itself, same day:** `corpus
  cross-check` counts the plan-less sentinel `.plan/local/plans/NO_PLAN` (`"sentinel": true`) as a
  live plan with no comparable surface, so `candidate_comparison_determinate` is false on every
  `next` while it exists; the operator ruled that verdict wrong and overrode it.

- **GW-07 REQUEST-BODY STATUS STAYS PARTIAL — OPERATOR-NAMED RESIDUAL** (added 2026-10-25, from `PLAN-V02-27`'s
  landing). No test observes the public `TerminalDispatch#dispatchGrpc` handing its fixed five-minute stream-idle
  bound to the overload the tests drive. The operator chose to hold it as the named residual: do not close it by
  adding a test inside another plan, and do not move the threat-model status. Also open and not this plan's: the
  `gw-07-upstream-ports` matrix row reads COVERED while naming an effect no test observes (not authored by #520).
  `GrpcStreamIdleBound.timeoutMillis()` is a public production method added only for a test; `PLAN-V02-49`
  D3 decides whether to remove it.
- **ADR-0066 HAS NO TEST FOR TWO ABORTIVE-CLOSE TRIGGERS — OPERATOR RULED: REPORT, NO TEST** (added 2026-10-25, from
  `PLAN-V02-04`'s message). No test pins the abortive HTTP/1.1 upstream close on the request-body idle-timeout trigger
  or the 25 s deadline trigger. The gap is recorded in ADR-0066 and the GW-07 threat-model entry. Owner: none; it
  closes only if the operator reverses the ruling.
- **THE RELEASE SKILL'S "AN ADR IS IMMUTABLE" LINE VS THE IN-PLACE ADR REWRITES** (added 2026-10-25, from
  `PLAN-V02-04`'s message). `.claude/skills/release/SKILL.md` lists `doc/adr/**` in the set a release-version sweep
  must not touch, and says an ADR is immutable. `PLAN-V02-04` and `PLAN-V02-48` rewrite records in place. The line
  sits in a list about version stamps, so the orchestrator reads it as scoped to that sweep. Unconfirmed; the plan
  raised it with the operator. `PLAN-V02-48` reports its reading and does not edit the skill.
- **ADR TERM-LIST RESIDUE IN RECORDS `PLAN-V02-04` REWROTE** (added 2026-10-25). "refuse" for a runtime stop, "routing"
  for route selection, unqualified "warning" for the boot WARN, "outbound" beside "egress", and "outbound TLS" in
  `doc/configuration.adoc`, `doc/security-threat-model.adoc` and two test sources. Owner: `PLAN-V02-48` D10.

## Inbox Drain — 2026-10-09 (sender `plan-v02-01-adr-0005-reversal-quarkus-adoption`, at its landing)

> ↪ Relocated to `settled.md` § "Inbox Drain — 2026-10-09 (sender `plan-v02-01-adr-0005-reversal-quarkus-adoption`, at its landing)" — every message consumed and archived; logs/decision.log stays authoritative

## Inbox Drain — 2026-10-06 (sender `plan-v02-14-offline-config-validation`)

> ↪ Relocated to `settled.md` § "Inbox Drain — 2026-10-06 (sender `plan-v02-14-offline-config-validation`)" — every message consumed and archived; logs/decision.log stays authoritative

## Inbox Drain — 2026-10-05 (sender `fix-four-audit-findings-and-bff-flaky-test`, a plan outside this epic)

> ↪ Relocated to `settled.md` § "Inbox Drain — 2026-10-05 (sender `fix-four-audit-findings-and-bff-flaky-test`, a plan outside this epic)" — every message consumed and archived; logs/decision.log stays authoritative

## Inbox Drain — 2026-10-04 (senders `plan-v02-13-terminal-rejection-contract`, `plan-v02-10-per-client-tls-trust`)

> ↪ Relocated to `settled.md` § "Inbox Drain — 2026-10-04 (senders `plan-v02-13-terminal-rejection-contract`, `plan-v02-10-per-client-tls-trust`)" — every message consumed and archived; logs/decision.log stays authoritative

## Inbox Drain — 2026-10-03 (sender `plan-v02-08-fapi-2-0-conformance`, at its landing)

> ↪ Relocated to `settled.md` § "Inbox Drain — 2026-10-03 (sender `plan-v02-08-fapi-2-0-conformance`, at its landing)" — every message consumed and archived; logs/decision.log stays authoritative

## Inbox Drain — 2026-09-24 (sender `deployment-configurability`, closing hand-off)

> ↪ Relocated to `settled.md` § "Inbox Drain — 2026-09-24 (sender `deployment-configurability`, closing hand-off)" — every message consumed and archived; logs/decision.log stays authoritative

## Post-Merge Verification — `deploy-snapshot`

> ↪ Relocated to `settled.md` § "Post-Merge Verification — `deploy-snapshot` (four V02 landings, 2026-08-09)" — all four merge commits verified green; the standing gh-run method for the next landing is carried there verbatim

## Release Pre-Flight Knowledge for the 0.2.0 Cut

Harvested from the predecessor epic, which cut **both** 0.1.0 and 0.1.1. **This is the only place
this knowledge exists.** These are instance-specific cautions; the process itself lives in
`.claude/skills/release/`.

- **Re-run the Trivy gate immediately before the cut.** A green is worth **days, not weeks** — it was
  RED on 2026-08-04 and green on the 5th only because a Dependabot base bump landed between. The DB
  snapshot carries a `NextUpdate` ~24h out: cut that day, or re-run.
- **Rebuild the scan target from the tree under test.** A stale image's green looks **identical** to
  a real one — the 0.1.0 rehearsal's first target predated its code by hours, and at 0.1.1 a
  tag-keyed reuse would have scanned an image built *before* the commit under test. Scan by
  content-derived image ID, as the workflow does.
- **The Trivy gate runs AFTER Maven Central publication.** `publish-image` is `needs: [release]`, so
  jars are irrevocably on Central before the scan or the signature happens. **That ordering is why
  the pre-cut dry run exists, and it is not optional.**
- **Assert GHCR `visibility == 'public'` via `gh api`, never by pulling as an org member.** The 0.1.0
  cut landed on INTERNAL first, which keeps anonymous pulls broken while every member check passes.
  At 0.1.1 the release skill checked **anonymously** throughout (no `ghcr.io` credential) — that is
  the standing method, and authenticating the verification would reintroduce the blind spot.
- **Never pass `--delete-branch` on a queue-gated merge.** It closed PR #167 **unmerged** while
  returning `merged: true`. Verify via the GitHub API and `mergeQueue.entries`, never the tool's
  return.
- **The merge of the version bump IS the release.** `.github/project.yml` `current-version` is the
  trigger; editing that file is by construction a release trigger.
- **The guard is proven in both directions.** It fired correctly at 0.1.1 (run 31157727796) and
  refused correctly at PLAN-51 (run 31256991225). In that refusal both predicates were true
  (`current-version` stayed `0.1.1`, tag `0.1.1` existed), so the job rendering alone cannot tell
  them apart — **but the guard log can**: it reads `unchanged at 0.1.1`, and the guard checks
  "unchanged" before "already tagged", so a guard stuck on `changed` would have logged the tag reason.
  A guard stuck on `unchanged` would log the same line; that direction fails safe. The mechanism is
  settled: the pinned guard job checks out with `fetch-depth: 0` and `fetch-tags: true`.
  `doc/development/release-process.adoc` now rests these claims on the guard's reason line, at the
  v0.42.0 pin (#512). `release-guard-docs.yml` fails a pull request that moves the pin until the section
  is re-checked.

## Standing Rules Inherited from the `api-sheriff-roadmap` Close-Out

Five corpus lessons were dispositioned into this ledger because they govern **how this epic is run**
rather than how the code is built. Verbatim copies live at
`.plan/local/archived-orchestrators/api-sheriff-roadmap/archive/lessons-consumed/{id}.md`.

1. **`2026-08-02-15-002` — BUILD SUCCESS IS NOT EVIDENCE THAT WORK HAPPENED.** Assert positive
   evidence, never the exit code. This is the single most-recurring shape of the predecessor epic:
   a clean result nobody computed, nine separate instances in one bundle round.
2. **`2026-08-05-05-001` — for a path-triggered check-run, absence of a failure is not a pass.** Only
   presence-and-success **on the commit that changed the file** is.
3. **`2026-08-06-08-002` — a fix to a duplicated value is not done until every carrier is enumerated
   mechanically.** Learned when a Cosign identity change hit two of three carriers; recurred within
   hours of being promoted (a four-carrier config value), and again at PLAN-50 where a spec's own
   carrier list was stale and a published SVG was missed. **Enumerate, never recall.** *This epic's
   own re-grounding found four more instances — see the decompose decision log.*
4. **`2026-08-08-11-001` — a review-bot fix can be reverted by the project's own quality gate.**
   OpenRewrite `SimplifyTestThrows` re-widens narrowed test `throws` clauses **and exits 0 while
   rewriting the file**, so an accepted bot suggestion re-dirties the tree on every future gate run.
   **A bot suggestion is not verified by implementing it, only by surviving the gate.**
   *Re-verified 2026-08-08: `JUnit5BestPractices` is in the `pre-commit` recipe list (`pom.xml`:436)
   and `BuildGateCoverageContractTest`:142/:171 still read `throws Exception` — the gate won and the
   narrowing did not survive.* **This rule is one of three faces of a single mechanism** — the other
   two are `2026-08-02-17-001` (non-idempotent import churn, which also poisons the change-ledger
   freshness stamp) and `2026-07-27-09-001` (the formatter is non-idempotent and `main` is not its
   fixed point). `PLAN-V02-17` D2 lands all three as one class, and a post-gate
   `git status --porcelain` assertion is the check that covers them.
5. **`2026-08-04-05-001` — out-of-scope Sonar findings on a bounded-write-boundary plan are reported,
   not absorbed.**

## Deferred Item — the release dry run is NOT a queue item

**Operator ruling 2026-08-05**: the release dry-run standardisation runs as a **direct Claude Code
session**, not a plan-marshall plan. The brief is `release-dry-run-standardisation-claude-brief.md`
in this tree. A WS-06 workstream and a `PLAN-V02-13` spec were staged and then **removed** when the
ruling landed — do not be surprised by the gap, and **do not re-add them** (the `PLAN-V02-13` number
was subsequently reused for terminal-rejection-contract).

**Its hard gate has opened**, and the thing it gated on moved further than the gate anticipated.
Roadmap PLAN-48 shipped (#171 / `b5ce4b6`) and the release trigger is re-armed — but its D4 did not
ship with it: the release-skill work went to PLAN-50, and PR #179 (`9a8dc3c`) then **rewrote**
`.claude/skills/release/SKILL.md` substantially (a two-path release, Step 1 restructured so the
safety work precedes the merge, Step 3(i) replaced, the frontmatter description rewritten), after
PR #169 had already renumbered its steps. **So the brief's own instruction — re-read `release.yml`
and the skill on `main` and RE-DERIVE rather than patch — is now load-bearing rather than cautious.**
PLAN-50 has since landed, so the Cosign-identity collision that deferred this behind the 0.1.1 cut is
cleared.

**The form decision is taken and argued in the brief**: a dry-run **mode** on the existing `/release`
skill, not a sibling skill, because a dry run is worth exactly its fidelity to the real steps and a
second document drifts — three first-party drift defects in this same lane on one day are the
evidence. Overturnable only on evidence; record the rationale either way.
