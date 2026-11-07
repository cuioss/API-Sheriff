# Task: cut test-cycle duration without losing test quality

A brief to hand directly to Claude Code in the API-Sheriff checkout. It is not a plan-marshall
plan spec and has no queue row. Phases 1–3 are read-only and can run at any time. Phase 4 touches
`api-sheriff/src/test/**` and `integration-tests/**`, which `PLAN-V02-01` also claims, so it waits
until that plan has landed.

## Problem

The test cycle (unit tests, integration tests, CI lanes) takes far too long and impedes the build.
Find out where the time goes, why, and what can be changed. CI is the reference environment —
local runs are usually faster and are not the yardstick.

## Hard constraint

Test quality must stay the same or improve. No fix may reduce what is proven. Concretely:

- Never delete or weaken a test unless another named test demonstrably proves the same behaviour
  (name both tests and the assertion that covers it).
- Coverage must not drop; the SonarCloud gate stays green.
- No `@Disabled`, no loosened assertions, no shortened timeouts that turn a real wait into a
  flake, no moving a gating test into a non-gating lane.
- A speed-up that works by running less is a finding to report, not a fix.

## Phase 1 — Measure CI (no code changes)

Use `gh` for the last ~20 successful runs on `main` and on PRs, per workflow: `maven.yml`,
`integration-tests.yml`, `demo-client-e2e.yml`, `config-validation.yml`, `benchmark.yml`.
Produce:

1. Wall-clock per workflow, per job, per step (median and p90), plus queue time.
2. The critical path: which job decides when a PR can merge (required checks are
   `build/conclusion` and `integration-tests/conclusion`).
3. Inside the Maven jobs: time per module and per plugin phase (compile, surefire, failsafe,
   native-image build, Docker build, compose up/down, OpenRewrite, javadoc, Sonar). Pull this from
   the job logs.
4. Per test class: duration from surefire/failsafe reports (download the CI artifacts if
   published; otherwise say so and reproduce locally through the Maven executor in `CLAUDE.md`).
   Rank the top 30 classes and top 30 methods.
5. Fixed overhead vs. test time: how much of each job is setup, dependency resolution, cache
   restore, native build, container start — as opposed to tests actually executing.
6. The Java 25 + 26 matrix: what is duplicated across the legs, and what does the second leg
   actually prove?

State the population behind every number (how many runs, which dates).

## Phase 2 — Analyse the structure (no code changes)

Read the tests thoroughly; do not sample. For each aspect report findings with file references
and an estimated saving in CI seconds.

### A. Technical / architectural problems

- Tests at the wrong level: unit-level behaviour proven only through a Quarkus boot, a container,
  or the native image.
- Components that are hard to test in isolation and force a heavy harness.
- Environments that are not sensible: the `sheriff-config*` variants under
  `integration-tests/src/main/docker/`, the four compose files, Keycloak, nginx, APISIX,
  grpc-echo, late-idp. Which are really needed per test?
- Real sleeps, fixed waits, polling with generous timeouts, retry loops.
- Tests that open real sockets or do real TLS where an in-memory seam exists.

### B. Grouping and infrastructure restarts

- How often does Quarkus restart in the `api-sheriff` unit run? Map every `@QuarkusTest` class to
  its `@TestProfile` / `@QuarkusTestResource` / config overrides; each distinct combination forces
  a restart. Count the restarts and the cost of each.
- How often is the compose stack (or a single container) started, stopped or reconfigured during
  the IT run? Which ITs need which config variant? Can variants be merged, or ITs ordered/grouped
  so each variant starts once?
- Is the native image built more than once per pipeline? Is it rebuilt when only tests changed?
- Surefire/failsafe fork and parallelism settings: forkCount, reuseForks, JUnit parallel
  execution. What prevents parallelism today (shared ports, static state, shared containers)?

### C. Duplicates and overlap

- Build a behaviour-to-test map for the heavy areas (BFF session/cookie, TLS/mTLS,
  routing/rejection, config loading/validation, framing corpus).
- Find behaviours proven several times at the same level, and behaviours proven at unit AND
  Quarkus AND IT level with no added value at the upper levels.
- Find parameterised tests with huge or redundant argument sets, and tests that assert nothing
  meaningful.
- Classify each overlap: genuine duplicate / deliberate defence in depth / different level
  proving a different thing. Only the first is removable.

### D. Hotspots

- From Phase 1's ranking: for each of the top classes, why is it slow, and is the cost inherent
  or accidental?
- Shared fixtures/helpers that every test pays for (certificate generation, key material,
  Keycloak login, realm import, MockWebServer setup).

### E. Other ideas — evaluate each, do not just list

- CI caching (Maven repo, native build, Docker layers, Keycloak image).
- Splitting jobs to run in parallel vs. the fixed overhead that adds.
- Sharding the IT suite across runners.
- Skipping lanes by changed paths (only where the lane cannot observe the change — see the
  `build.map` discussion in `CLAUDE.md`).
- Reusing one built artifact/native image across jobs.
- JVM-mode ITs for most behaviour, with a smaller native smoke set — only if you can show what
  the native run uniquely proves and that it stays covered.
- Keycloak start-up time (pre-imported realm, optimised image).
- Flaky tests that cost reruns (see `doc/development/` and open issues).

## Phase 3 — Report and stop

Write `doc/development/test-duration-analysis.adoc` containing:

- The measured baseline (Phase 1 tables).
- Findings per aspect A–E.
- A ranked list of proposed changes: expected CI saving, effort, risk, and for each one an
  explicit statement of why test quality is unchanged or better.
- What you could not measure or verify, stated separately from what you measured and found fine.

Then STOP and present the ranked list. Do not change any test, POM or workflow before the
operator picks which proposals to implement.

## Phase 4 — Implement the approved proposals

- One proposal per commit, with before/after timing in the commit message.
- After each: full test run of the affected module, coverage compared with the baseline, and a
  check that the test count did not shrink unexplained ("BUILD SUCCESS" is not evidence that the
  tests ran).
- Follow `CLAUDE.md` throughout: Maven only through the executor, pre-commit gate + full verify
  before every commit, no Mockito/Hamcrest, no new dependencies without operator approval, feature
  branch + one PR.
- Finish with a CI before/after comparison on the PR itself.
