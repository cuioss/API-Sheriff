# Landing Analysis: PLAN-SEC2-01 — Doc honesty and config guards

epic: security-hardening-tier-2
workstream: WS-01
pr: #623 (https://github.com/cuioss/API-Sheriff/pull/623), merged through the merge queue as `31595bca`

> Landing record for one shipped plan. Written by the `analyze` verb after verifying the
> landing message against ground truth (git, the PR through the CI abstraction, the merged code).
> Disclosure is mechanism-level: possible weaknesses the read raised that are not yet vetted are
> reported to the operator in the session and are NOT written here (this file is tracked).

## Deliverable Fidelity vs Spec

Landing facts: `deliverables_total=8`, `deliverables_done=8`. The spec lists 6. The two extras are not
named by the landing narrative; the PR body names a test-only `HtmlErrorPageIT` stabilisation and a
review-follow-up round, and the diff bears both out (`HtmlErrorPageIT.java` +263 lines, plus the code
the review round touched). Neither is a spec deliverable, so both are recorded as added-unplanned.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Documentation and Javadoc corrections | shipped-modified | All listed sites corrected (ADR-0039, SVG, portal.adoc, `RpInitiatedLogout`, event-loop Javadoc, cookie-rename guidance in `authentication-and-oidc.adoc`). `doc/user/bff-session.adoc` was declared and not touched. ADR-0060 and `doc/development/protocol-processors.adoc` were rewritten beyond the spec. |
| 2. HTTP/2 header-list bound announced at 16 KiB | shipped-as-specified | `EdgeHardeningOptions.apply` sets 16 KiB unconditionally on main HTTP, HTTPS and (new override) domain-socket listeners; grey-zone pair in `GatewayEdgeHttp2AbuseTest`; announced value pinned in `EdgeHardeningOptionsTest:498` |
| 3. Refuse a boot that configures the extension's issuers | shipped-modified | New `ExtensionIssuerNamespaceGate` at `PLATFORM_BEFORE - 1`, `ApiSheriff-203`; proof is direct gate construction plus a native-image IT rather than the literal start-failure `@QuarkusTest` profile the Done-when named |
| 4. Configuration echo hygiene | shipped-as-specified | Route id pattern `^[a-z][a-z0-9-]*$` at `endpoint.schema.json:195`; `RuleSupport.renderForMessage` across the named rule classes, `RouteTableBuilder`, `WebSocketRelayStage`; `RefusalEchoEscapingTest` |
| 5. Management plain-HTTP audit predicate | shipped-modified | Credentials-provider-only no longer counts as key material; widened beyond `ManagementPlainHttpAudit` to `TerminatedListenerTlsAudit`, `ServerTlsDeclarationGate`, `ResolvedServerTlsMaterial` via key-bearing views in `DeclaredKeyMaterialKeys` |
| 6. Failed-relay head drops CDN fields | shipped-as-specified | `TerminalDispatch.UNSTORABLE_ANSWER_REMOVES` adds `CDN-Cache-Control` and `Surrogate-Control` (:118); `TerminalDispatchLateBodyTimeoutTest:214` |
| (added) `HtmlErrorPageIT` stabilisation | added-unplanned | test-only; reworked with a bounded agreeing-pair loop and per-request deadlines, not removed |
| (added) review follow-up round | added-unplanned | per the PR body; covered by the surface delta below |

Surface delta (declared 27, realized 63): 25 realized paths were never declared (the new gate and its
tests, the shared TLS audit classes, `GrpcDispatchStage`, three ADR/doc files, four integration-test
files, test-support files); one declared path (`doc/user/bff-session.adoc`) was untouched.
`expansion_detected`. The declaration of the landed spec is not corrected (the plan is shipped); the
under-declaration is recorded here as the evidence the next two specs' declarations are compared
against.

## Three-Layer Analysis

The landing was read on three layers — the spec, the deliverable trace, and the merged code — by one
read-only leaf, and the orchestrator re-read every point recorded as `checked` at its `file:line`
before writing it here. The reading is **by reading, no build, no test run, no write**: what only a run
could show is tagged `not shown by reading`.

- **Dispatch:** one read-only leaf per landing — reason: the landing's merged diff is 63 files / +3027 −486 and reading it is the depth the dispatch rule exists for.
- **Verdict:** layer 1 findings — 2 minor; layer 2 findings — 4 minor; layer 3 findings — 3 recorded here, further items reported to the operator only (see below).

### Layer 1 — Specified

All seven labelled spec claims settle in the spec's favour and are persisted as `corroborated` on the spec
(`checked_at 31595bca`, `by security-hardening-tier-2/analyze`).

| Finding | Claim | Verdict | Polarity | Evidence (file:line) |
|---------|-------|---------|----------|----------------------|
| L1-1 | ADR-0039 said plain HTTP only through a named opt-out | checked | confirms | `doc/adr/0039-…adoc:29` at `6ee137df` |
| L1-2 | Announced header-list size was the 8192 default and only lowered | checked | confirms | `EdgeHardeningOptions.java` `boundHeaderListSize` at `6ee137df`; merged `:297` |
| L1-3 | No reference to the extension's issuer namespace and no boot refusal | checked | confirms | `application.properties:383` at `6ee137df` (comments mention the key, so "no reference" is imprecise) |
| L1-4 | HYPOTHESIS: a refusal can run before the extension's startup initializer | checked | confirms | `ExtensionIssuerNamespaceGate.java:86`, `:112` |
| L1-5 | Route id had no pattern | checked | confirms | `endpoint.schema.json:195` |
| L1-6 | HYPOTHESIS: management TLS is not enabled by a credentials provider alone | checked | confirms | `ManagementPlainHttpAuditTest.java:168-184` |
| L1-7 | `prepareFailedRelayHead` did not strip the CDN fields | checked | confirms | `TerminalDispatch.java:1031-1040` at `6ee137df`; merged `:118`, `:1047` |
| L1-8 | Spec tension: deliverable 2 says correct the wording everywhere while the Standing Constraints forbid any `edge/upstream/**` change except `DispatchStage` Javadoc; `GrpcDispatchStage` Javadoc was changed (no code) | checked | refutes | `GrpcDispatchStage.java:47-48` |
| L1-9 | Deliverable 3's Done-when names a `@QuarkusTest` profile that fails to start; the class is `@QuarkusTest` (`:66`) but proves the refusal by constructing the gate, plus a native IT | checked | refutes | `ExtensionIssuerNamespaceGateTest.java` |

**Layer 1 verdict:** findings — 2 (L1-8, L1-9, both minor, both spec-form not behaviour)

### Layer 2 — Implemented

| Deliverable | Code trace | Test that can fail | Unexplained file / unborne claim | Verdict |
|-------------|------------|--------------------|---------------------------------|---------|
| 2 | `EdgeHardeningOptions.java:292-309` | `GatewayEdgeHttp2AbuseTest` grey-zone pair, `EdgeHardeningOptionsTest:498` | none | checked |
| 3 | `ExtensionIssuerNamespaceGate.java:112-140` | `ExtensionIssuerNamespaceGateTest`, `ExtensionIssuerNamespaceRefusalIT` | none | checked |
| 4 | `RuleSupport.java`, `endpoint.schema.json:195` | `RefusalEchoEscapingTest`, `ConfigLoaderTest` | none | checked |
| 5 | `ManagementPlainHttpAudit.java:121-129` | `ManagementPlainHttpAuditTest:168-184` | `ServerTlsDeclarationGate`, `TerminatedListenerTlsAudit`: widening changes boot behaviour — a credentials-provider-only main listener is now refused by the gate | checked |
| 6 | `TerminalDispatch.java:118,1047` | `TerminalDispatchLateBodyTimeoutTest:214` | none | checked |
| 1 | docs and Javadoc | n/a (`order: none`) | `GrpcDispatchStage.java` Javadoc, ADR-0060 rewrite, `protocol-processors.adoc` | checked |

Landing-narrative claims against the diff: the accepted test removals are inverted or rewritten rather
than lost (`ManagementPlainHttpAuditTest:171,180` now prove a real plain-HTTP management port;
`EdgeHardeningOptionsTest:498` carries the three header-list tests in their new semantics;
`HtmlErrorPageIT` is strengthened, not removed). One genuine loss: the 32 KiB complete-block case was
the only test of "a compressed header block over 1.25× the bound ends the connection"; a
CONTINUATION-flood test that drops the connection remains (`GatewayEdgeHttp2AbuseTest:404-430`) but is a
different trigger. The narrative's statement that `HtmlErrorPageIT::unreachableUpstream` was *dropped*
is not borne out; the diff shows it reworked.

**Layer 2 verdict:** findings — 4 (minor): unexplained `GrpcDispatchStage` Javadoc, unexplained ADR-0060
rewrite (not shown by reading why), unexplained `HtmlErrorPageIT` rework, the unpinned
compressed-block claim (L3-1 below).

### Layer 3 — Correct

| Defect | File:line | Triggering input | Effect | Severity |
|--------|-----------|------------------|--------|----------|
| L3-1 | `EdgeHardeningOptions.java:91`; `doc/user/edge-security.adoc:605`; `doc/architecture/tls-and-inbound-security.adoc:166`; `doc/security-threat-model.adoc:2097`; ADR-0076:194 | n/a (documentation) | The documented rule that a compressed header block over 1.25× the bound ends the connection is stated in five places and pinned by no test | minor |
| L3-2 | `application.properties:322`, `:346-350` (untouched by the landing) | n/a (comment) | The comment still describes the extension's issuer namespace as an enabled-issuer hazard and its initializer as inert unless an enabled issuer exists; any such key now refuses the boot | info |
| L3-3 | `EdgeHardeningOptions` Javadoc, ADR-0076 | n/a (rationale) | The direction of the header-list change (8 → 16 KiB, not "document 8 KiB") is justified in the PR body but not in the Javadoc or the ADR, which the spec asked for | minor |

Further layer-3 items concerning echo coverage, gate-coverage reach and the key-material views were
returned by the leaf and re-read at their lines. They are unvetted weakness-class observations, so they
are reported to the operator in the session and are not written into this tracked record. Whether any
becomes a staged fix-plan spec is the operator's decision.

**Layer 3 not shown by reading:** whether the management listener truly keeps the transport default
(8 KiB) — the documented mechanism rests on a Quarkus source reading, no test pins it; whether the
extension can build its own validator through a path that runs before the gate's observer.

**Layer 3 verdict:** findings — 3 recorded here (2 minor, 1 info), plus items reported to the operator only

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md § Logging | `CuiLogger`, `%s`, exception first; ERROR range 200-299 documented | conforms | `ApiSheriff-203` in `ConfigLogMessages.java` and `doc/LogMessages.adoc` |
| CLAUDE.md § Testing | JUnit 5, AAA, no Mockito/PowerMock/Hamcrest | conforms | no such import in the added test lines |
| CLAUDE.md § Pre-1.0 | no `@Deprecated`, no hollowing suppression | conforms | two Sonar rationales in `HtmlErrorPageIT`; one suppression removed |
| CLAUDE.md § Javadoc | public API documented with `@since` | conforms | `ExtensionIssuerNamespaceGate` |
| CLAUDE.md § Testing (declared-limit rule) | a key that parses must be proven to act | conforms | gate ordering read from the container's resolved observer set (`ExtensionIssuerNamespaceGateTest:215`); management verdict observed on a live listener (`:180`) |
| spec Standing Constraints | documentation claims match behaviour | deviates | L3-1 and the unpinned management claim |
| spec Standing Constraints | no code change in `edge/upstream/**` except `DispatchStage` Javadoc | deviates | `GrpcDispatchStage.java:47-48` Javadoc only |

Points re-read by the orchestrator at `31595bca` (and `6ee137df` where a base claim was tested):
ADR-0039:29, `EdgeHardeningOptions.java:276-312`, `ExtensionIssuerNamespaceGate.java:80-140`,
`endpoint.schema.json:192-197`, `ManagementPlainHttpAuditTest.java:168-184`,
`TerminalDispatch.java:114-122,1040-1050`, `ManagementPlainHttpAudit.java:120-130`,
`DeclaredKeyMaterialKeys.java:150-170`, `RouteTableBuilder.java:200-208,484-499`,
`ConfigProducer.java:228-236`, `OriginValidationStage.java:82-90`, `application.properties:346-352`,
`GatewayEdgeHttp2AbuseTest.java:400-426`. Not re-read: the leaf's `jar listing` conclusions about
token-sheriff 0.9.9 and quarkus-vertx-http 3.40.1 — recorded as the leaf's reading, unverified lead.

## Metrics and Anomalies

- Tokens: 48,870,277 total
- Duration: 129,774 s wall (about 36 h) across the plan
- Model: `claude-sonnet-5-5`; effort `unknown` (the landing facts block omitted a real value, so `inbox landing-check` reports `complete: false`, missing key `effort`)
- Anomalies: functional review closed three times by operator override (accepted test removals, recorded in the PR body); pre-push freshness gate refused every pass with `worktree_root_unreadable` and the push used the logged `force-override` basis three times — the lane attributes it to a plan-marshall build-recording defect that was not investigated; squash-message composition skipped (the merge queue offers no per-enqueue message override); post-merge CI wait stopped at the tool limit.

## Routing and Merge Behavior

- Review: CodeRabbit. `runbook_lane review-gate --pr-number 623` on the merged head: `unresolved_review_threads: 0` (matches the landing). `review_body_findings_total`: the provider shows **10**; the landing claimed **2** — a **false claim** (difference), not a rounding. `review_body_findings_handled`: the provider-side parser reports 0 dispositioned and `merge_allowed: false, reason: body_findings_undisposed`; dispositions live in the run's own record, which this analysis did not read, so the landing's `2` is **not re-verified**.
- CI/merge: merged through the merge queue (`merge_mechanism=merge_queue`); the queue's merge-group run reported success per the landing, **not independently re-read here**.
- Sonar: landing claims `sonar_open_new_issues=0`. `read_open_issues` on PR 623 returns `count_status: undecidable` (`head_revision_unavailable`, analysis revision `eb1f78cd`), so the figure is **unknown**, never `0`, and the landing's `0` is not re-verified.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-SEC2-01 --status shipped`
- [x] row `pr` stamped — `623`
- [x] row `landing` stamped — `landings/PLAN-SEC2-01.md`
- [x] row `plan_marshall_plan_id` stamped — `sec2-01-doc-honesty-and-config-guards`
- [x] epic.md narrative reconciled (queue annotations, Open Defect for the incomplete landing, Watches)
- [x] layer-1 verdicts persisted on the PLAN-SEC2-01 spec; verified-at-HEAD verdicts persisted on the PLAN-SEC2-02 and PLAN-SEC2-03 claims
- [x] resume anchor updated; `queue-view.md` regenerated and committed

## Follow-Ups

- Landing `effort` fact missing: recorded as an Open Defect in `epic.md`.
- `review_body_findings_total` mismatch (landing 2 vs provider 10) and the unread dispositions: Watch in `epic.md`; operator to confirm the ten review-body findings were handled.
- Sonar open-new-issues unknown for the merged PR: Watch in `epic.md`.
- The pre-push freshness gate's `worktree_root_unreadable` on a null worktree root in every build row is a plan-marshall defect: operator to route it to the plan-marshall lessons inbox (not done here).
- L3-1 (unpinned 1.25× compressed-block claim) and L3-3: candidates to fold into a later plan on the operator's word; not folded into PLAN-SEC2-02/03, whose surfaces they do not touch.
- ADR-0060 and ADR-0076 were edited by this landing. PLAN-SEC2-02 and PLAN-SEC2-03 amend them again; both specs hold the ADR body ceiling (500 lines) in mind — noted in the queue annotations.
