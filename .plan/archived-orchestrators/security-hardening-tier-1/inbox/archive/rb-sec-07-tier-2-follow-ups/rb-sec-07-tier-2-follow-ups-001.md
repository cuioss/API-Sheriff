envelope_version=1
sender_type=plan
sender_id=rb-sec-07-tier-2-follow-ups
epic=security-hardening-tier-1
kind=landing
created=2026-11-14T11:24:52Z

```landing-facts
schema=landing-facts/1
plan_id=rb-sec-07-tier-2-follow-ups
epic=security-hardening-tier-1
pr=#619
merge_state=merged
cleanup_owed=false
deliverables_total=4
deliverables_done=3
total_tokens=unknown
harness=claude
model=unknown
effort=unknown
plugin_version=0.1.2118
executor_version=0.1.2118
steps=setup:done,implement:done,build-gate:done,self-review:clear,pr:done,reviews:done,merge:done
unresolved_review_threads=0
review_body_findings_total=6
review_body_findings_handled=6
sonar_open_new_issues=0
step.sonar-roundtrip.open_issue_rules=none
```

guard: present (the guard log `.plan/temp/guard.log` does not exist, so it records no refusal; see Rule check)

PR #619, merge commit `e1b8a2cb3049fe839c4217e0b665941c6f2b6ef1`, head merged `5fedc056`. A second, separate PR #618 (merge `e6fda739`) landed the gate's formatter churn, at the operator's direction.

## 1. Deliverables

**D1 — not met (stopped by the spec's own verify-first clause).** No code written. Reason, mechanism-level: the only `connectionHandler` on the Quarkus-terminated TCP listeners is the one Quarkus itself installs inside the `quarkus.http.limits.max-connections` block (one shared counter for the plain-HTTP and HTTPS listeners); `HttpServerOptionsCustomizer` only receives `HttpServerOptions`; `HttpServerStart` / `HttpsServerStart` fire once per server start; the only Netty `ChannelInitializer` hook in the recorder is for the virtual server. No supported per-connection hook exists without patching Quarkus or adding a dependency, both barred. A local note was recorded in the git-ignored security notes. The evidence is a summary of the public Quarkus 3.40.1 `VertxHttpRecorder` source (WebFetch, small-model summary, not the local jar) plus an in-repo read of `MtlsServerCustomizer.customizeHttpsServer` (`tls/MtlsServerCustomizer.java:70-86`); the in-repo scan was not exhaustive. Order: none (no test; nothing to test).

**D2 — met.** `order: test-first`.
- Cap above default `admission_cap`: `ShippedApplicationPropertiesTest.packagedFileDeclaresTheConnectionCap` (`>` default, `== 2 x default`, literal `4096`). Failed against the unchanged 2048 cap: log `5d7e308b870a451bb966e6cbe04f61c0` (1 failure). Cap is `4096` in `application.properties`.
- Boot WARN: `ConnectionCapAuditTest` (7 cases: real `StartupEvent` path under a lowered-cap profile, above, equal, below as matched control, no cap, cap of 0 and -1). Failed against a no-op audit stub: log `c6810dcbf69c46079fd2da39e19bb8fb` (3 of 5 failed, the two silent controls passed). The 0 / -1 case was added after the CodeRabbit review and was red before its fix: log `0b94957c8a1f438a9f11b0c32f4c547c` (2 failures). New `LogRecord` `ApiSheriff-148` in `ConfigLogMessages` and `doc/LogMessages.adoc`; documented next to `admission_cap` in `doc/configuration/security-and-edge-hardening.adoc`.
- Caveat on the order: the failing run for the audit was against a stub that compiled and did nothing, not against a repository without the feature; the pin's failing run was against the real unchanged value.
- Descriptor sizing and nofile advice corrected (16384; 32768 under `passthrough_sni`); derivation comment and the Connection-cap row rewritten.
- Green after: log `5b20fe97c3ef43bca7aa75e6de198b45` (26 tests, both Surefire executions confirmed in the Maven log), final 81-test run `e9207d54eae64d36a264299c4b88c061`.
- Commits `3df0e4eb`, `ac68c209`, `1f2f378c`, `5fedc056`.

**D3 — met.** `order: mutation-after`. New cases in `ForwardingRulesTest` (`shouldLeaveNonWebSocketProtocolAlone` over the undeclared protocol plus every non-WebSocket `Protocol` constant, `shouldLeaveNonProxyRouteAlone`, matched positive control `shouldRefuseTheIdenticalWebSocketProxyRoute`). Green with the guard: 46 tests, log `31d961f6d5a546489909a349d110c8ed`. Mutation: the guard at `ForwardingRules.java:274` deleted gave 4 failures of 46, log `f4ad37c93ec249ccb4606ff047b08baa`; guard restored. Commits `85a0f2a9`, `ac68c209`, `5fedc056`.

**D4 — met.** `order: none`. Repository-wide case-insensitive searches for an "HTTPS (by) default" management claim and for a "gateway-sent resets never count" statement are empty after the edits. The searches and their results are recorded in commit `46fa1147`; one earlier hit (a comment in `Dockerfile.native`) was corrected. The one remaining match of a broader pattern, ADR-0066:122, concerns metering as `INBOUND_BODY_ABORTED`, not the Rapid-Reset limit. The search output is not a stored file; it exists in the commit message and the progress record. Files: GW-08 assertion, ADR-0076, observability and container-image docs, both SVGs, `Dockerfile.native` comment, ApiSheriff-115 remedy (`ConfigLogMessages`, `LogMessages.adoc`), GW-09 control and summary row, GW-07 / `edge-security.adoc` / `tls-and-inbound-security.adoc` for D1/D2 changes.

Per-head test runs: no separate `module-tests` run per head; the gate canonicals served. Pushed `85a0f2a9` (identical tree gated by `verify -Ppre-commit`, job `fa9b187baf024b15b426c7ef255deea1`, green); pushed `5deae7ff` (verify green, job `ed31678dd9c249e8bb0a9b97ff2001ab`); pushed `5fedc056` (verify -Ppre-commit job `bd235d6bbdef4a86bbebaaa2898c14ae`, verify job `ad08dd117e00445281c6e23563299f13`, both green). One earlier verify at `64b4ed3e` failed on `GrpcDispatchStageTest.clientTrafficKeepsTheCallOpenWhileTheUpstreamIsSilent` ("Stream no longer exists: 1"; 1 of 1493, a file this branch does not touch, job queued 338 s on a machine at load ~17); the class passed alone 40/40 and the full verify re-run at the next head was green.

## 2. Personas and skills

Intended per deliverable: D1 security-expert, D2 implementer, D3 module-tester, D4 documenter; self-review round: code-reviewer (the sub-agent was told to load it). I did not load the declared skills or persona skills with the Skill tool in this run and did not record doing so; the work followed the repository's own CLAUDE.md standards and existing code patterns. Treat `skills: named, not loaded` as the accurate statement. No skills were added for surface drift.

## 3. Hypotheses

- D1 hypothesis (a supported hook sees every accepted connection): **refuted**, evidence as in D1 above.

## 4. Findings

- Self-review round 1 (sub-agent, read-only): no blocker or major. Fixed: fixture constant renamed, literal `4096` pin added, sizing wording made complete, GraphQL added to D3 controls (`ac68c209`). Accepted: below-cap control is a direct case, not a boot (shipped value pinned statically); `>=` warns at equality (documented). Refuted: concern that the boot WARN would pollute the silent controls (the real run was green). Round 2 was not dispatched; the fixes were retested (57 tests green) and the final diff gated.
- CodeRabbit on `5deae7ff`: 6 inline findings, all fixed in `5fedc056` with replies and resolved threads (Dockerfile key-material spellings; cap of zero or less; headroom wording at four places; derive protocol controls from `Protocol.values()`; opt-out wording and system-property precedence; GW-07 wording). Not fixed: the same "forces plain HTTP" phrase in `ManagementConfig.java` and `doc/configuration/tls-and-egress.adoc`, which predates this PR.
- Sonar: 0 open issues, confirmed, at `5fedc056` (also 0 at `5deae7ff`). The new-code quality-gate status was not read.
- Gate: first `verify -Ppre-commit` after the review fixes timed out at the 728 s bound under load (clipped, not failed); the re-run with a larger bound passed in 707 s.

## 5. Reviewers

- coderabbit (required): reviewed `5deae7ff` (marker `kind=reviewed`, covering `5deae7ff`). It is `review_complete_after_handling`, so no re-trigger was posted for `5fedc056`; every finding handled. A repeat trigger I posted right after PR creation was answered "already reviewed".
- sourcery (optional): refused both triggers (review budget of 250,000 diff characters per 7 days, retry in about 2 h 15 m); not re-triggered. A Sourcery check on `5fedc056` later showed success with no comments.

## 6. Operator questions

1. Question tool: the main checkout had one modified file, `.plan/marshal.json` (`provisioned_version` stamp written by the executor regeneration). Options: "Stop; I'll commit/handle it" / "Proceed, leave it untouched". Selected: **Proceed, leave it untouched**.
2. Question tool: 450 unrelated files modified by the pre-commit gate (one import-block blank line each); options: allow `git restore` of the churn / commit by name and leave the churn / stop. The operator answered with **free text** (the "Other" path): "land them separatly, use skip-bot-review as pr label". I acted on it by landing the churn as its own PR #618 with that label. That action was not one of the listed options (see Residue).
- Free-text messages: "is regenerated, continue" (after the executor-skew block), "pause after the current task", "continue".

## 7. Rule check

- guard: present; the guard log `.plan/temp/guard.log` does not exist, so it carries no line for this run. No refusal was observed in-session. That is not proof the rules below were kept.
- Rules this run did not keep:
  - Shell file operations: single `ls` of a directory, several `grep` calls (`grep -n`, `grep -rn`, `grep -rIn`, `grep -c` on the guard log), one `sed -i` on `ConnectionCapAuditTest.java` (it changed nothing; redone with file edits), `uptime`. No Grep or Glob tool existed this session, which is why the greps were used.
  - Staging: the churn commit used `git add -u` with an exclude pathspec rather than a list of names; every other commit staged named files.
  - Two PRs instead of the spec's "one PR" (the churn PR #618), by the operator's free-text direction.
  - Commits `3df0e4eb` (D2) and `46fa1147` (D4) were made before a fresh gate; the gates ran on the merged tree and on every later pushed head.
  - A branch (`chore/pre-commit-import-churn`) was created inside the plan worktree to carry the churn.
  - Reading the public Quarkus source with WebFetch is a read outside the repository (no credentials or environment involved).
- Sub-agents: two read-only sub-agents (hook search, self-review), both completed once, none killed or re-dispatched. The hook-search sub-agent reported its search was non-exhaustive (no directory listing or content search tool); that limitation applies to the D1 in-repo evidence.
- Restarts: none. One executor-skew stop at the start (executor 0.1.2106 vs lane text 0.1.2118) until the operator regenerated; the verdict is `match` at landing time.
- Spec read: `spec_source: ledger_worktree`, no `spec_differ` marker.
- Mailbox: read at setup, before self-review, before PR creation and immediately before enqueue; always empty (`no_mailbox`).

## 8. Build server

- Setup preflight for the worktree: `ready`. Every build was routed through the daemon (`mechanism=daemon_longpoll`, about 25 builds); no in-process fallback, no launch-fault retry, no killed job. Some jobs queued (up to 338 s) behind other builds. One build hit its explicit 728 s bound (`timeout`) and was re-run with a larger bound (1500 s). `runtime_stamp_reason` was not returned; `model` and `effort` resolved to `unknown` at setup and at landing.

## 9. Residue

- **D1 is open:** a per-source connection share on the Quarkus-terminated side was not implemented. Direction and detail are in the git-ignored local security note dated 2026-11-13 (PLAN-SEC-07 D1); not repeated here.
- The operator's free-text churn direction was acted on although it was not a listed option; if that was not intended, #618 is a small separate whitespace-only change that can be reviewed on its own.
- The main checkout still carries the uncommitted `.plan/marshal.json` `provisioned_version` change from the executor regeneration (left untouched by the operator's selection).
- Pre-existing "forces plain HTTP" phrasing remains in `ManagementConfig.java` and `doc/configuration/tls-and-egress.adoc`.
- The failing and mutation logs live in the build daemon's job-log directory (`~/.plan-marshall/marshalld/job-logs/`), outside the repository; the identifiers above name them and they may be cleaned up.
- `step.sonar-roundtrip.quality_gate_status` was not read.
