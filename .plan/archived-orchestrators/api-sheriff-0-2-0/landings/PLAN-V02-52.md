# Landing Analysis: PLAN-V02-52 — ADR Relocations, the `oidc_discovery` Record and the Amendment-Citation Rewrite

epic: api-sheriff-0-2-0
workstream: WS-02
pr: #554 (https://github.com/cuioss/API-Sheriff/pull/554), merged as `48715de4` through the merge queue

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-11-01 from the inbox message
> `plan-v02-52-adr-relocations-and-citation-rewrite-001`. Checked against `origin/main`, the squash commit (98 files, +771 / −665), the PR body,
> the CI runs on the merge commit, the PR review state, and `doc/adr/` and a content search on `origin/main`.

## Deliverable Fidelity vs Spec

The landing counts 12 deliverables against the spec's 6 (the plan split the spec's items into outline deliverables); all 12 are reported done, 10 met and 2 met with residuals,
none unmet (`functional-review`). I checked the spec's six against `origin/main`:

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — relocate `0022` | shipped | `doc/adr/` no longer holds `0022-*`; the PR body names the replacement ("Adopting Upstream Security-Default Changes", `doc/technical_aspects.adoc`). A content search on `origin/main` outside `.plan` finds **no** remaining `ADR-0022` or `0022-` reference. |
| D2 — relocate `0030` | shipped | `0030-*` is gone; replacement "Comment-Only Invariants Become Fitness Functions" in `doc/development/test-corpus-integrity.adoc`. The ArchUnit tests and `LogMessagesCatalogueTest` now cite it by heading. No reference to `ADR-0030` or `0030-` is left. |
| D3 — the `oidc_discovery` record | shipped | `doc/adr/0079-The_oidc_discovery_readiness_datum_reports_provider-metadata_resolution_and_never_changes_the_verdict.adoc` exists. `doc/adr/` holds **71** records, the number the spec predicted. The spec's premise that ADR-0074 states the datum was wrong, and the plan said so in the PR: 0074 only carries ADR-0027's key-set passage. `0027` and `0074` mention `oidc_discovery` to state the boundary. |
| D4 — amendment citations and anchors | shipped | The search for the spec's patterns (`ADR-00NN A<n>`, `Amendment A<n>`, `#amendment`, `#_amendment`, `[[amendment`, `<<amendment`, `AMENDED`) returns **0** hits on `origin/main`. The PR counts 266 occurrences rewritten across 88 files. `0023`, `0027` and `0073` were settled with named current-fact headings. |
| D5 — the CodeRabbit lead and the wording left in place | shipped, three items cut | The lead **was real**: ADR-0008's upstream-timeout sentence was wrong. See the next section. The three cut wording leads (ADR-0070, `benchmarks/README.adoc`, compose-sample `.env`) are open follow-ups, as the spec allowed when the footprint passed the cap. |
| D6 — report | shipped | The PR body carries the removed-record table with replacements and the occurrence counts per form. |

**Deviation worth recording: ADR-0008.** The spec said to leave `0008` line 90 alone because kidicap `PLAN-29` D6 owns that timeout sentence. The plan
corrected it anyway, because verifying the CodeRabbit lead showed the sentence was false, and it said so in the landing. I read the new text against the code: the
upstream attempt is bounded by a fixed `withTimeout().duration(UPSTREAM_TIMEOUT_SECONDS, SECONDS)` in `UpstreamGuards.java`, and no `setConnectTimeout` or `setReadTimeout`
exists in `api-sheriff/src/main/java`, so "no timeout of its own on the shared client, Vert.x defaults apply" holds. **Kidicap `PLAN-29` D6 is therefore already done on our side.**
Nothing was written to the kidicap ledger; the hand-off text is the operator's (below).

**Declared surface vs realised.** Of the 98 files, 26 were never declared. Eleven are the expected consequence of the rewrite reaching further than the spec's 18 entries: `ConfigLoaderTest`,
`HeaderRulesTest`, `GatewayEdgePipelineTest`, `WebSocketRelayStageTest`, `doc/development/{bff-session,build-gate-discipline,integration-test-topology}.adoc`,
`doc/quality-report/security-posture.adoc` and `doc/user/{anchors,endpoint-routes,environment-variable-overrides}.adoc`. **Fifteen sit in `integration-tests/` or in tests another staged plan owns**, and
that is the part that matters for sequencing: `integration-tests/docker-compose.yml`, `scripts/{start-integration-container,verify-invalid-config-fails}.sh`, the `sheriff-config/` YAML and
properties (`cookie-policy`, `grpc`, `httpbin`, `origin-headers`, `gateway.yaml`, `topology.properties`), `BenchmarkScriptConfigContractTest`, `BffTlsRelaxationIT`, `EnumerationOracleIT`,
`FailsafeConcurrencyContractTest`, `MtlsLaneSelectionContractTest`, and `TerminalActionRulesRedirectTest`. I read the diff of every one: **each hunk is a comment, Javadoc, description
or assertion-message change** (an `ADR-00NN Amendment A<n>` citation became `ADR-00NN`, or `ADR-0030` became the heading of the relocated section). No assertion, key, endpoint or compose
service changed. That is why `PLAN-V02-55` and `PLAN-V02-53` are not invalidated: their baselines read behaviour and per-class timing, not comment text.

## Post-Merge Verification (the orchestrator's own)

- `Maven Build` push run `41340502346` on `48715de4`: **success**. `Demo Client E2E`, `Config Validation` and `Scorecard`: success.
- **`Integration Tests` push run `41340502392` on `48715de4`: FAILURE, and it is not a test failure.** The `integration-tests / test` job failed in the `exec:3.6.4:exec (generate-mtls-certificates)` step
  with `java.io.IOException: Broken pipe` at 19:13:22Z, before any test ran. The `merge_group` run on the same tree (`41338388725`) and the PR run passed. The footprint is comments and prose, so the failing
  step is the certificate-generation script, not anything this PR touched in behaviour. I read this as a flake of the step, **and I have one run's worth of evidence for it**: the plan did not re-run it and neither did I (a re-run is a CI write
  action outside my carve-out). It is recorded as a Watch; **`main` has a red push run until someone re-runs it**.
- A `Dependabot` run (`npm_and_yarn in /demo-client for minimatch`) also failed on the same commit; it is unrelated to this change.
- The post-merge `Performance Benchmark` run was still pending when finalize ended; I did not wait for it (the change is not behavioural).
- Reviews: `functional-review` and `code-review` are `done` (no override this time), `blocking_count=0`, three advisories, listed below. The PR comment table holds 18 rows, all of kinds `issue_comment`, `review_body` and `reviews`;
  none carries a `thread_id`, so no resolvable thread is open.

## Metrics and Anomalies

- Tokens: 31,441,024. Wall time: 83,544 s (about 23 h). Model claude-opus-5-5, effort `low`, plugin and executor 0.1.2036.
- 15 finalize steps `done`, `cleanup_owed=false`; `archive-plan` follows the message by construction and is not claimed.
- The plan trimmed the PR description to Summary plus trailer before the merge so that the queue's squash message carries no bot summary; the other sections went into a PR comment.
- The pre-commit formatter rewrote about 380 unrelated files' import spacing again (fifth plan to report it). The plan reverted it. See the Watch.
- `module-tests` did not resolve at whole-tree scope in the pre-push gate and ran DEGRADED. That is a tooling finding, not a defect of the change.

## Follow-ups (from the landing; none is a defect of what shipped)

- "Whole dispatch" timeout wording in `GatewayEdgeHttp2AbuseTest`, `GatewayEdgeRequestBodyIdleTimeoutTest`, `EdgeHardeningOptionsTest`, `GrpcDispatchStageTest`; the `DispatchStage` Javadoc still names a route read timeout; the
  bodyless-send code comment says the stream "stays unsubscribed" although the watch subscribes it. These are wording corrections in Java comments.
- Two quotations in `test-corpus-integrity.adoc` differ by one comma from the relocated text; its "Matched positive controls" paragraph still lists two near misses after a third limit and control were added.
- The three cut wording leads (ADR-0070, `benchmarks/README.adoc`, compose-sample `.env`); the unchecked BFF session-sweep 60 s statement; the idempotent-method list is not bound by a contract test.
- Sequencing consequence: nothing in flight now shares a file with `PLAN-V02-55`. `PLAN-V02-52` gave up its only blocking claim on the epic.
