# PLAN-V02-55: Run More of the Integration Suite Concurrently, and Close the Local Echo-Image Hazard — #423

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-31 for issue #423 ("Test-cycle duration: IT concurrency parts not realised"). The operator asked that the orchestrator DRIVE
> this one to verify the change: the per-class baselines below were read by the orchestrator from the archived failsafe reports before the plan starts,
> and the orchestrator re-reads the same reports after it lands.

## Objective

The #424 test-cycle rework moved most sleep-bound integration suites into a concurrent failsafe execution, and left four suites sequential plus one
suite whose two legs still run one after the other. #423 records why, with numbers, and one local hazard. This plan realises what can be realised
without weakening any assertion: overlap the two idle-timeout legs, give the three log-counting suites a way to attribute their own log records so
they can run concurrently, isolate the TLS-relaxation suite from the shared echo container, and make the local lane rebuild the gRPC echo image. A
part that cannot be done soundly is cut and reported, and the issue stays open for it.

## Deliverables

1. **`BffSessionIdleTimeoutIT`: overlap the two legs.** Run the server-mode leg and the cookie-mode leg at the same time, each test method waiting only
   for its own leg, with every assertion and timeout unchanged. The first attempt failed with a Keycloak 400 because the suite then ran first against a
   freshly started Keycloak; the class now runs inside the concurrent execution, after the sequential one has logged in many times. Try the plain overlap
   first, then a lock that serialises only the two `login` calls; neither was verified when the issue was written. Baseline for the class: **194.6 s, 196.3 s,
   196.3 s** in three archived runs; the issue puts the shorter leg at about 33 s. A flaky overlap is not accepted: run the suite at least five times in the
   concurrent execution, and if the 400 or any other failure recurs, revert to the sequential legs and say so.
2. **Make the three log-counting suites attributable, then move them.** `BffTokenRefreshIT` (counts `ApiSheriff-111`), `BffClientSecretModeIT` and
   `BffFapiControlsIT` (count `ApiSheriff-12`) attribute a log record to their own request by counting records of a shared instance log before and after, and
   the records (`TOKEN_REFRESHED`, `SESSION_REFRESH_FAILED`) carry nothing that identifies the suite, so a record caused by a concurrent suite satisfies the
   count. Choose the way to make a record attributable, deciding at outline between: (a) a non-sensitive, suite-attributable value in the record, which is a
   production log change in `BffLogMessages`, `TokenRefreshCoordinator`, `doc/LogMessages.adoc` and `LogMessagesCatalogueTest`; and (b) a dedicated gateway
   instance per suite on the test side. For (a) the value must pass the security review below: it is never a token, a session identifier, a subject, or a
   value derived from one that is reversible or correlates a user across logs. **If neither is acceptable, cut this deliverable** and leave the three suites
   sequential with the reason stated. Once attributable, convert the three suites to count by that value, tag them `sleep-bound`, and move them to the concurrent
   execution. Baselines (three archived runs each): `BffTokenRefreshIT` 46.9 / 48.7 / 49.1 s, `BffFapiControlsIT` 24.6 / 25.8 / 26.0 s, `BffClientSecretModeIT`
   24.2 / 25.2 / 25.6 s, so about 96 to 101 s leave the sequential execution.
3. **`BffTlsRelaxationIT`: stop sharing the echo container.** The suite connects the compose echo container to a network of its own and disconnects it, three
   times per run, while every concurrent suite's mediated call goes through that container, which is why isolation "could not be established from the code".
   Give the suite its own echo container and network (or another isolation the outline can show), tag it, and move it. Baseline: **33.5 / 34.4 / 35.1 s**.
   If isolation cannot be shown, cut this deliverable with the reason.
4. **Close the local echo-image hazard.** `docker-build-distroless` runs `docker compose build api-sheriff` only, and `grpc-echo` is built only when `up -d`
   finds no image, so a local run after a change to the echo service tests the old image. Make the local lane build `grpc-echo` as well (in `integration-tests/pom.xml`,
   and in the `jfr` profile's build step if it has the same gap), and show it: change the echo service, run the lane, and see the new image tested.
5. **Keep the partition honest.** `FailsafeConcurrencyContractTest` reads the failsafe block and the IT sources and fails when the two executions stop partitioning
   the classes; keep it green, update the comment in `integration-tests/pom.xml` that lists the classes kept sequential and why, and remove from it every class this plan moves.
6. **Close what is done, after the PR has landed.** After the merge, and CI on the merge commit green, comment on #423 with the per-class figures before and after and the PR,
   and close it through the CI abstraction (`ci issue comment`, `ci issue close`) only if every part is done. If a part was cut, leave #423 open with a comment saying which
   part remains and why. The PR names #423 WITHOUT a closing keyword.

Split guard: 6 deliverables, one PR. The footprint is estimated at fewer than 20 files: five IT classes, `integration-tests/pom.xml`, `docker-compose.yml`, and, only if D2(a) is
chosen, four production and documentation files. If the PR passes 100 files or 150,000 diff characters, cut D2 first, then D3.

## Standing Constraints

- **No assertion is weakened and no timeout is lengthened to make a suite concurrent.** A class that fails, or is flaky, only when it runs concurrently loses
  the tag; do not change the test to keep it (the rule already written in `integration-tests/pom.xml`).
- **Show each change red or necessary:** the attribution must be shown to fail a count when a record from another suite is present (a negative control), and
  the isolation must be shown to hold while another tagged suite runs beside it.
- **Production log change (D2a only):** one record id keeps one meaning; follow the logging rules (`%s`, exception first, documented in `doc/LogMessages.adoc`,
  `LogMessagesCatalogueTest` green), and the record range and level stay as they are.
- **Executor only.** Maven runs go through `plan-marshall:build-maven:maven run --command-args "..."`; integration runs through `-Pintegration-tests`. Never run two
  Maven builds at once in one worktree. The integration-test stack binds ports on the host, so a VS Code port-forward on `127.0.0.1:1443` makes the Bff* logins hang in the TLS
  handshake: check `lsof` before concluding a suite is broken.
- **No dependency is added.** Use the simple type name where the file imports the type; revert any pre-commit gate rewrite of a file this plan did not author.
- Where the project analyses its pull requests with Sonar (`resolve` reports `sonar: configured`): an open Sonar issue is fixed in a pushed commit, or
  dismissed with a stated reason recorded as `{issue-key}: {reason}` in the run's progress record and transmitted through
  `plan-marshall:workflow-integration-sonar:sonar_rest transition --issue-key {key} --transition {falsepositive|wontfix}`. The merge gate reads the open-issue count at the head
  (`unknown` for a failed read, never `0`).

## Verification by the orchestrator

The orchestrator does not build or run the suites (it never runs implementation builds), so it verifies at `analyze` from records the plan does not write:

- **Pre-registered baselines** (read by the orchestrator, 2026-10-31, from the failsafe XML that CI archives under `api-sheriff/integration-tests-{timestamp}/failsafe-reports/` in the
  `cuioss/cuioss.github.io` repository, runs of 2026-10-31 13:25, 16:30 and 19:48 UTC; the `time` attribute of each class's `testsuite`):

  | Class | Baseline `time` (s), three runs | Execution today |
  |---|---|---|
  | `BffSessionIdleTimeoutIT` | 194.6 / 196.3 / 196.3 | concurrent (`sleep-bound`); legs sequential inside |
  | `BffTokenRefreshIT` | 46.9 / 48.7 / 49.1 | sequential |
  | `BffFapiControlsIT` | 24.6 / 25.8 / 26.0 | sequential |
  | `BffClientSecretModeIT` | 24.2 / 25.2 / 25.6 | sequential |
  | `BffTlsRelaxationIT` | 33.5 / 34.4 / 35.1 | sequential |
  | `BffRefreshReuseIT` (control, unchanged by this plan) | 69.0 / 76.4 / 77.7 | concurrent |

  The spread between runs of one class is about 1 to 8 s, so a class-level change of 30 s or more is readable and a change of a few seconds is not.
- **The workflow duration is not evidence.** `Integration Tests` on `main`, push runs, twelve full runs: median about 1,435 s, range 1,096 to 1,720 s. That spread is several times larger
  than the 130 to 170 s this plan can move, so the orchestrator will not read a gain or a loss off it; it is recorded so nobody does.
- **What the orchestrator re-derives itself.** (a) In the merge commit, the `sleep-bound` tag on each moved class and the updated `integration-tests/pom.xml` comment, and
  `FailsafeConcurrencyContractTest` green in CI. (b) From the archived reports of the merge-head run and of at least two later `main` runs: every moved class ran and passed, its
  `time` against the baseline (a concurrent class may run somewhat slower under contention; one that is more than doubled is a finding), and the idle-timeout class's `time` below its baseline by
  roughly the leg that now overlaps (about 33 s) if D1 shipped. (c) That the three later `main` runs are green with no new IT failure in a moved class; a failure in one is a finding against
  the plan, not a flake, until reproduced alone. (d) For D2(a), the record's new value in `doc/LogMessages.adoc` and a read of what it carries; for D4, the changed build step in the pom.
- **Verdict rule.** `verified` when (a) to (d) hold for every shipped deliverable; `partly verified` naming each deliverable the reports do not settle; `unverified` when the reports of the merge
  head cannot be read. A cut deliverable is reported as cut, and #423 is left open for it.

## Claim Labels

- OBSERVED: `BffSessionIdleTimeoutIT` carries `@Tag("sleep-bound")` and runs in the concurrent execution, with its two legs still sequential inside the class —
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffSessionIdleTimeoutIT.java`, the failsafe block of `integration-tests/pom.xml`
- OBSERVED: `BffTokenRefreshIT`, `BffClientSecretModeIT`, `BffFapiControlsIT` and `BffTlsRelaxationIT` are in the default (sequential) execution for the reasons the
  `integration-tests/pom.xml` comment states
- OBSERVED: `TOKEN_REFRESHED` (`ApiSheriff-12`) and `SESSION_REFRESH_FAILED` (`ApiSheriff-111`) are emitted by `TokenRefreshCoordinator` and defined in `BffLogMessages`, and neither
  carries a suite-attributable value — `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java` § `TOKEN_REFRESHED`, `SESSION_REFRESH_FAILED`
- OBSERVED: the local lane builds `api-sheriff` only — the `docker-build-distroless` execution runs `docker compose build api-sheriff`, and `grpc-echo` has a `build:` stanza in
  `integration-tests/docker-compose.yml` that `up -d` uses only when no image exists
- OBSERVED: the per-class baselines in the table above, from the archived failsafe XML
- HYPOTHESIS: the in-class overlap of D1 works now that Keycloak's keys exist by the time the class runs; if not, a lock around the two `login` calls does (verify-at-outline, by running it)
- HYPOTHESIS: a non-sensitive attributable value exists for D2(a), or a per-suite instance (D2(b)) is affordable in CI time; otherwise D2 is cut (verify-at-outline, with the security review)
- HYPOTHESIS: `BffTlsRelaxationIT` can run against its own echo container without a second shared resource (verify-at-outline against `BffTlsRelaxationIT` and the compose file)
- Verify-first clause: re-read the failsafe block of `integration-tests/pom.xml` and the five classes at outline; a class that has since moved or changed is re-stated, and the baselines are re-read
  from the archive if any of the five has changed.

## Expected Surface

- OBSERVED: `integration-tests/pom.xml`, `integration-tests/docker-compose.yml`
- OBSERVED: `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffSessionIdleTimeoutIT.java`,
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffTokenRefreshIT.java`,
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffClientSecretModeIT.java`,
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffFapiControlsIT.java`,
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/BffTlsRelaxationIT.java`,
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/FailsafeConcurrencyContractTest.java`
- HYPOTHESIS: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/BffLogMessages.java`,
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/bff/refresh/TokenRefreshCoordinator.java`,
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/LogMessagesCatalogueTest.java`, `doc/LogMessages.adoc` — only if D2(a) is chosen (verify-at-outline)
- HYPOTHESIS: `integration-tests/src/main/docker/` — one new or changed service definition only if D3 or D2(b) needs its own container or instance (verify-at-outline)

## Dependencies and Sequencing

- Depends on: `PLAN-V02-52`, merged (operator ruling). The two share 11 files: `BffTlsRelaxationIT`, `FailsafeConcurrencyContractTest`, `integration-tests/docker-compose.yml`, five
  fixture files under `integration-tests/src/main/docker/sheriff-config/` (`gateway.yaml`, `topology.properties` and the `httpbin`, `cookie-policy`, `grpc` and `origin-headers` endpoints),
  `doc/LogMessages.adoc` and `LogMessagesCatalogueTest`. `PLAN-V02-52` edits citations in comments and messages there; this plan changes test and compose code in some of them, so it starts from
  `main` after that plan has landed and re-reads each file.
- Overlaps with: `PLAN-V02-53` (staged, also after `PLAN-V02-52`), which edits `integration-tests/` (a fixture guard and a redirect `no-store` assertion) and
  `doc/development/declared-limit-assertion-coverage.adoc`. Do not run the two together; one lands before the other starts.
- Adjacent to: `PLAN-V02-54` (unit-run measurement, #422). Both need a quiet host for their own builds; never run their builds at the same time on one machine.
- Adjacent to: `BffPendingLoginBoundIT`, `MtlsHandshakeIT`, `WebSocketProxyIT` and the stub identity-provider rig trio, which stay sequential for reasons that are not this plan's; their
  reasons in the pom comment are kept.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-55-it-concurrency-and-local-image-hazard.md" plan_id=plan-v02-55-it-concurrency-and-local-image-hazard
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Security Findings

Fix a security finding inside this plan whenever possible; that is the preferred outcome. A finding
that is not fixed right away (out of scope, unconfirmed, deferred) stays out of git: it is never
written into a tracked file, a commit message, the PR title, body or review replies, a code comment,
the docs, or this plan's inbox and landing messages. Record it instead in
`.plan/local/security_notes.adoc` (git-ignored; create the file if it is missing and append, never
rewrite). Each entry carries the date, this plan's id, the location (file and line or component),
what was observed, and why it was not fixed. Tracked text may say only that a security note was
recorded locally, never what it says. **D2(a) adds a field to a log record on the session-refresh path of a security product: review what the new value carries against
the secure-logging rules (`pm-dev-java:java-security`, `plan-marshall:persona-security-expert`) before it is written, and record the review in the progress record.**

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message. Closing #423 (deliverable 6) is the one action outside the
repository: it goes through the CI abstraction, after the merge, and nowhere else.
