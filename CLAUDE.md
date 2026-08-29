# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

API Sheriff is a security-focused API Gateway with a lightweight approach, currently in pre-1.0 development. Built with Maven, Java 25 (compile + runtime; CI matrix 25 + 26), and Quarkus. Follows CUI (CUIoss) standards.

## Project Structure

Multi-module Maven project:
- `api-sheriff/` — Deployable Quarkus application (core library, CDI producers, REST endpoints, native executable)
- `integration-tests/` — Integration test coordinator (Docker infrastructure, IT suites, scripts)
- `benchmarks/` — WRK HTTP load testing benchmarks

## Development Notes

### Build Commands

Never hard-code Maven build tool commands (`mvn`, `./mvnw`) — always invoke Maven through the executor. The **general form** is:

`python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "<any maven goals & options>" [--project-dir <path>]`

- `--command-args` accepts **any** Maven goals and options — profiles, `-pl`, `-Dtest=…`, system properties. Pass the goals/options **only**, never the `mvn`/`./mvnw` binary itself (the executor supplies the wrapper).
- `--project-dir <path>` is the escape hatch to build a checkout **outside** the main tree — e.g. an ad-hoc worktree under `.plan/local/worktrees/` or in the scratchpad. It defaults to the project root; use it instead of `cd`-ing into a worktree and running `./mvnw` there.

The entries below are common **examples, not a closed set** — any Maven invocation goes through this same `run --command-args`:

- Compile: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "compile"`
- Quality gate: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Ppre-commit"`
- Full verify: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify"`
- Coverage: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pcoverage"`
- Module tests (api-sheriff): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl api-sheriff -am"` — only on api-sheriff
- Module tests (benchmarks): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl benchmarks -am"` — only on benchmarks
- Module tests (integration-tests): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl integration-tests -am"` — only on integration-tests
- Integration tests (integration-tests): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pintegration-tests -pl integration-tests -am"` — only on integration-tests
- Benchmark (benchmarks): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pbenchmark -pl benchmarks -am"` — only on benchmarks
- Targeted test (single test / pattern): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl api-sheriff -Dtest=ConfigLoaderTest"`
- Targeted test in an ad-hoc worktree: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl api-sheriff -Dtest=ConfigLoaderTest" --project-dir /path/to/worktree`
- Native executable: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "clean install -Pnative -pl api-sheriff -am -DskipTests"`
- Use a 10-minute Bash timeout (600000ms) for build invocations
- Analyze each build's TOON result: `status`, `errors[N]{file,line,message,category}`, `log_file`

The executor rule covers **Maven builds only**. Genuinely non-Maven tooling runs directly — the sole case here is the production Docker image build:

```bash
docker build -f api-sheriff/src/main/docker/Dockerfile.native -t api-sheriff:latest api-sheriff/
```

### Pre-Commit Process

**CRITICAL** — run before every commit that touches build inputs; both must pass with zero errors/warnings:

1. Quality gate (canonical `quality-gate` command above)
2. Full verify (canonical `verify` command above)

**"Zero warnings" is now enforced by the compiler, not just asked for.** The reactor-wide
`maven-compiler-plugin` configuration sets `<showDeprecation>true</showDeprecation>` **and**
`<failOnWarning>true</failOnWarning>`, so javac runs with `-Werror`: a compiler warning — a
deprecated API, an unchecked cast — **fails the build** in all six modules rather than scrolling past
in the log. The failure reaches the executor's structured payload, with the offending file and line
on the `warnings[]` row and the `-Werror` cause naming the file on `errors[]`. Read both arrays; the
line number lives on the warning row.

Answer such a failure by **migrating off the warned construct**, the way every site this gate was
turned on over was retired. A `@SuppressWarnings` added to get back to green hollows the gate out
while leaving it reporting success, which is worse than not having it — and it collides with the
Pre-1.0 rule below that forbids carrying deprecated code at all.

**A successful build is not evidence that work happened.** `BUILD SUCCESS` says the build
completed — not that it compiled what you changed, ran what you wrote, or kept what you fixed. The
gate below and every specific case documented off it are instances of that one rule.

**A gate that exits 0 can still have changed your files**, and three different mechanisms in this
repository do. So a review-bot suggestion is verified by *surviving* the gate, never by being
implemented: run the gate, then `git status --porcelain`, and attribute a dirty tree before
reverting it. Revert unrelated churn; keep the rewrite only for files the branch itself authored.
See `doc/development/build-gate-discipline.adoc` for the three mechanisms, the three operational
consequences and the recipe-scoping trap.

**Documentation-only commits skip both.** A commit whose entire footprint is prose or agent
instructions cannot change build output, so a Maven run proves nothing and only burns minutes.
Skip when **every** changed file is one of:

- `*.adoc`, `*.md` (including `README.adoc`, `CLAUDE.md`, `doc/**`)
- `.claude/**` — skills, agent instructions
- non-build config carrying no code and no build wiring (e.g. `deployment/compose-sample/.env`)

**Run the full process if the footprint touches anything else** — any `*.java`, `pom.xml`, `*.js`,
`*.ts`, `*.css`, `src/main/resources/**`, `.github/workflows/**`, `Dockerfile*`, `docker-compose*.yml`
or a build script. A mixed commit is *not* documentation-only: one Java file in an otherwise-prose
change makes the whole commit subject to the gate.

This enumeration is matched by the `build.map` contract that `build-decision` reads, so the gate
above fires automatically for every class it names. That alignment is **maintained by hand, not
derived**, and the distinction is the whole point of this section.

`build.map` is seeded from the build-system extensions' `classify_globs()` vocabulary, which for the
`java` domain derives only `pom.xml`, `*.sh` and the `*/src/{main,test}/**` classes, and for
`javascript` `package.json`, `*.js`, `*.spec.js`. Four of the declared gate-requiring classes are
derived by no extension at all and are therefore **hand-added** entries in `build.map.java`, carried
with `role: config` / `build_class: verify`: `Dockerfile*`, `docker-compose*.yml`, `*.css` and
`*.ts`.

**`.github/workflows/**` is declared above but deliberately NOT in the map, and that is not an
oversight.** The two lists answer different questions. The enumeration above asks "may this change
pass as documentation-only?" — a workflow edit may not, so it is listed there. `build.map` asks "does
this footprint need a *build*?", and for workflow YAML the answer is no: no Maven profile in this
repository reads `.github/workflows/`, so a workflow-only change compiles and tests exactly the same
code as before the edit. Registering it would buy a multi-minute gate that cannot observe the file it
was triggered by. Workflow YAML is validated by CI executing it, which happens whatever this map
says. The four entries above are kept precisely because each one *can* break something a Maven build
catches — `Dockerfile*` and `docker-compose*.yml` are exercised by `-Pintegration-tests`, which
builds the native image, runs `ImageMetadataIT` against it and starts the stack from those compose
files; `*.css` and `*.ts` are front-end sources the reactor's npm modules build.

Read the consequence precisely, because it cuts the opposite way from an exemption. **Being
hand-added means being erasable.** Because no `classify_globs()` derives them, the four are
re-derivable from nothing and are removed by any re-seed — `manage-config build-map seed --force`,
and a `marshall-steward` reconcile driving that same seeding path. `manage-config build-map drift`
reports them as removed rather than restoring them. An erasure leaves no local symptom: the map
still parses and every surviving entry is still correct. The only consequence is that a footprint
made solely of those classes then intersects no registered glob, `build-decision` returns
`decision: not_necessary`, and that positive verdict drops `pre-push-quality-gate` from the composed
execution manifest — so the change ships ungated.

`BuildGateCoverageContractTest` is the guard that converts that silence into a failing build. Do not
delete it to get green: re-add the missing entry to `.plan/marshal.json` instead. It has already
fired once for real — commit `cea163c` regenerated `build.map` from the derivation, dropped every
hand-added entry, and reached `main` because the commit's own footprint (`.plan/marshal.json` +
`CLAUDE.md`) is not a build-triggering path, so CI skipped the build job and never ran the test that
would have caught it.

That last point is the standing gap, and it is a CI-side one rather than a map-side one: a change to
`.plan/marshal.json` does not trigger a build, so this contract is currently guarded only by local
gate runs. Until that changes, treat any commit touching `build.map` as gate-requiring by hand,
whatever `build-decision` says about it.

Doubt resolves toward running it. The cost of an unnecessary build is minutes; the cost of a skipped
one is a red `main`.

## Pre-1.0 Rules (HIGHEST PRIORITY)

- **NEVER deprecate code** — remove it directly
- **NEVER add @Deprecated** — delete unnecessary code immediately
- **NEVER enforce backward compatibility** — make breaking changes freely
- **Clean APIs aggressively** — remove unused methods, classes, patterns

## Code Standards

- Java 25 features encouraged (records, sealed classes, pattern matching, text blocks, virtual threads)
- Lombok: `@Builder`, `@Value`, `@NonNull`, `@ToString`, `@EqualsAndHashCode`
- Prefer immutable objects, final fields, empty collections over null, Optional for nullable returns
- Indentation: 4 spaces, LF line endings, UTF-8

### Logging

- Logger: `de.cuioss.tools.logging.CuiLogger` (private static final LOGGER)
- Format: always `%s` for substitution (NEVER `{}`, `%.2f`, `%d`)
- Structured: `de.cuioss.tools.logging.LogRecord` for INFO/WARN/ERROR
- Ranges: INFO (001-099), WARN (100-199), ERROR (200-299)
- Exception parameter always comes first
- Document in `doc/LogMessages.adoc`

### Testing

- JUnit 5 exclusively, AAA pattern (Arrange-Act-Assert)
- Minimum 80% coverage
- CUI Test Generator for test data (`@GeneratorsSource` preferred)
- **Forbidden**: Mockito, PowerMock, Hamcrest
- **A configuration key that parses is not a configuration key that acts.** Ask: *if the key were deleted entirely, would any test go red?* If not, the control it names is not test-proven — that is all a green suite settles, so trace the key to its production reader before concluding anything about whether it is in effect — see `doc/development/declared-limit-assertion-coverage.adoc`

### Javadoc

- Every public/protected class and method documented
- Include `@since` tags, thread-safety notes, usage examples
- Every package must have `package-info.java`

## OpenRewrite Markers

Markers like `/*~~(TODO: INFO needs LogRecord)~~>*/` indicate **actual bugs**:
- Fix placeholder/parameter mismatches, wrong format specifiers
- Create LogRecord constants for production INFO/WARN/ERROR logs
- Replace generic Exception/RuntimeException with specific types
- For test diagnostic logging: add `// cui-rewrite:disable CuiLogRecordPatternRecipe` suppression
- **Never commit code with markers present**

## Security

As a security-focused API Gateway:
- All changes must consider security implications
- Never expose sensitive data in logs
- Follow OWASP guidelines, validate all inputs/outputs
- Use secure defaults

## Sonar / Quality Gate

The cuioss-organization SonarCloud gate (project `cuioss_API-Sheriff`, declared via `.github/project.yml`) is the **authoritative, blocking source of truth** for code quality — target zero new findings, and never merge over a red gate or a stale green while analysis is pending. Thresholds are org-owned; reference the gate, never restate them here.

**Fix by default.** Where a fix is genuinely not sensible (false positive, deliberate idiom, a rule fighting the design), suppress in-code with a rationale — `// NOSONAR java:SXXXX <why>` or `@SuppressWarnings("java:SXXXX")` — never by silently marking issues won't-fix / false-positive in the Sonar UI.

See `doc/development/sonar-quality-gate.adoc` for the complete compliance policy (including the PR-new-code vs post-merge project-gate auditability nuance).

## Dependency Management

- **Parent POM**: `de.cuioss:cui-java-parent` — version pinned in the root `pom.xml`
- **CRITICAL**: Never add dependencies without explicit user approval

## Git Workflow

**Scope**: the manual workflow below applies to ad-hoc (non-plan-marshall) changes only. Work executed through plan-marshall (`/plan-marshall` plans) is governed by the plan-marshall configuration (`.plan/marshal.json` and the per-plan execution manifest) — including merge behavior (`final_merge_without_asking`, merge queue), review-comment triage, and branch cleanup — and merges without a manual approval prompt when so configured.

**Minimize the number of PRs.** Batch related changes into a single PR rather than splitting them; only open a second PR when a single one would exceed 150 changed files.

All cuioss repositories have branch protection on `main`. Direct pushes to `main` are never allowed. Always use this workflow:

1. Create a feature branch: `git checkout -b <branch-name>`
2. Commit changes: `git add <files> && git commit -m "<message>"`
3. Push the branch: `git push -u origin <branch-name>`
4. Create a PR: `gh pr create --repo cuioss/API-Sheriff --head <branch-name> --base main --title "<title>" --body "<body>"`
5. Wait for CI and the automated reviewers — CodeRabbit and PR-Agent gate the merge; Sourcery is **optional** (`.plan/marshal.json` records `required_bots: coderabbit,pr-agent`, `optional_bots: sourcery`), so a Sourcery skip, absence or rate-limit never blocks (waits until checks complete): `gh pr checks --watch`
6. **Handle the automated review comments from every reviewer that commented** — CodeRabbit, PR-Agent and, when it reviews, Sourcery each comment independently, so treat the union of their comments as the work list. Sourcery being optional governs only whether it *gates the merge*: comments it does post owe a reply-and-resolve exactly like any other. Fetch with `gh api repos/cuioss/API-Sheriff/pulls/<pr-number>/comments` and for each comment, whichever bot authored it:
   - If clearly valid and fixable: fix it, commit, push, then reply explaining the fix and resolve the comment — a bot-fix commit is a commit, so the **Pre-Commit Process** section above applies unchanged
   - If disagree or out of scope: reply explaining why, then resolve the comment
   - If uncertain (not 100% confident): **ask the user** before acting
   - Every comment MUST get a reply (reason for fix or reason for not fixing). Resolution applies to **resolvable threads only** — the rows carrying a non-empty `thread_id`. The `review_body`, `review` and `issue_comment` kinds carry an empty `thread_id` and cannot be resolved at all, so they owe a reply where one is warranted and nothing more; do not treat them as a blocking work list (see step 8)
   - **Re-review after pushing fixes is not uniform**: CodeRabbit and Sourcery re-review automatically on push; PR-Agent deliberately does **not** (`.github/workflows/pr-agent.yml` triggers only on `opened`/`reopened`/`ready_for_review` plus on-demand `issue_comment` commands). Re-request a PR-Agent pass explicitly by posting a `/review` comment on the PR after the fix push.
7. Do **NOT** enable auto-merge unless explicitly instructed. Wait for user approval.
8. **Assert zero unresolved review threads, then report the merge and stop.**
   - Assert zero unresolved review threads: `python3 .plan/execute-script.py plan-marshall:tools-integration-ci:ci pr comments --pr-number <n> --unresolved-only`. **Read the `thread_id` discriminator, not the row count.** Only rows carrying a **non-empty `thread_id`** are resolvable review threads. The kinds `review_body`, `review` and `issue_comment` carry an **empty `thread_id`** — they are not resolvable threads at all, so they are reported unresolved forever and no amount of replying will clear them. Treat only the non-empty-`thread_id` rows as the work list. Where a straggler among those is routed into a follow-up issue, that does **not** clear it: filing the issue leaves the thread unresolved and the gate still red. Reply on the thread linking the issue **and resolve the thread** — the reply-and-resolve rule of step 6 applies unchanged, and a follow-up issue is a reason for not fixing now, never an exemption from resolving.
   - **The plan reports the merge and stops there.** Verifying the post-merge state is the **orchestrator's** job, not the plan's. Two runs belong to it: the **PR-attached** post-merge run (`.github/workflows/benchmark.yml` is `pull_request: types: [closed]` gated on `merged == true`, so it stays attached to the PR and remains visible through the CI abstraction after the merge), and the **main-branch** run, which is *not* attached to the PR (`maven.yml` triggers on `push: branches: [main]`, and its `deploy-snapshot` job is skipped on pull requests but runs on that push — so a snapshot-deployment failure appears only in the Maven Build run for the merge commit on `main`, never on the PR, and is looked up by merge commit rather than by PR number).
9. Return to main: `git checkout main && git pull`

## Temporary Files

Use `.plan/temp/` for ALL temporary and generated files (covered by `Write(.plan/**)` permission — avoids permission prompts).

## Tool Usage

- Use proper tools (Edit, Read, Write) instead of shell commands (echo, cat)
- Never use Bash for file operations (find, grep, cat, ls) — use Glob, Read, Grep tools instead
