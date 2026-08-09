# AGENTS.md

Guidelines for AI assistants working in the API-Sheriff repository.

## What This Repository Is

A security-focused API Gateway taking a lightweight approach, in pre-1.0 development. Maven,
Java 25 (compile and runtime; CI matrix 25 and 26), Quarkus 3.37.4, following CUI (CUIoss)
standards.

Modules:

- `api-sheriff/` — deployable Quarkus application: core library, CDI producers, REST endpoints,
  native executable
- `integration-tests/` — integration test coordinator: Docker infrastructure, IT suites, scripts
- `benchmarks/` — WRK HTTP load-testing benchmarks

Because the product is a security gateway, every change is a security change until shown
otherwise. Treat security implications as part of the diff, not as a separate review pass.

## Build Commands

Never hard-code a Maven binary (`mvn`, `./mvnw`). Every Maven invocation goes through the
executor, which supplies the wrapper:

```bash
python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "<goals and options>" [--project-dir <path>]
```

`--command-args` takes any goals and options — profiles, `-pl`, `-Dtest=…`, system properties —
and never the binary itself. `--project-dir` builds a checkout outside the main tree (an ad-hoc
worktree, for instance) and replaces `cd`-ing into it.

Common invocations, examples rather than a closed set:

```bash
--command-args "compile"                                              # compile
--command-args "verify -Ppre-commit"                                  # quality gate
--command-args "verify"                                               # full verify
--command-args "verify -Pcoverage"                                    # coverage
--command-args "test -pl api-sheriff -am"                             # module tests
--command-args "verify -Pintegration-tests -pl integration-tests -am" # integration tests
--command-args "verify -Pbenchmark -pl benchmarks -am"                # benchmarks
--command-args "test -pl api-sheriff -Dtest=ConfigLoaderTest"         # one test
```

Use a 10-minute Bash timeout (600000ms) for build invocations, and read the TOON result —
`status`, `errors[N]{file,line,message,category}`, `log_file` — rather than the exit code.

The executor rule covers Maven only. The one genuinely non-Maven tool is the production image
build, which runs directly:

```bash
docker build -f api-sheriff/src/main/docker/Dockerfile.native -t api-sheriff:latest api-sheriff/
```

## Before Every Commit

Both must pass with zero errors and zero warnings:

1. The quality gate (`verify -Ppre-commit`)
2. Full verify (`verify`)

This applies to every commit, including a commit that only fixes a review comment.

## Pre-1.0 Rules (highest priority)

The project is pre-1.0 and behaves like it. These override the usual instinct toward caution:

- Never deprecate — remove the code directly
- Never add `@Deprecated`
- Never preserve backward compatibility — make breaking changes freely
- Clean APIs aggressively: unused methods, classes and patterns go

A change that keeps an old surface alive "to be safe" is wrong here, not conservative.

## Code Standards

- Java 25 features are encouraged: records, sealed classes, pattern matching, text blocks,
  virtual threads
- Lombok: `@Builder`, `@Value`, `@NonNull`, `@ToString`, `@EqualsAndHashCode`
- Prefer immutable objects and final fields; return empty collections rather than null, and
  `Optional` for a genuinely nullable return
- 4-space indentation, LF line endings, UTF-8

### Logging

- `de.cuioss.tools.logging.CuiLogger`, held as `private static final LOGGER`
- Always `%s` for substitution — never `{}`, `%.2f` or `%d`
- `de.cuioss.tools.logging.LogRecord` for INFO, WARN and ERROR
- Identifier ranges: INFO 001-099, WARN 100-199, ERROR 200-299
- The exception parameter always comes first
- Document messages in `doc/LogMessages.adoc`
- Never log tokens, passwords, secrets, certificate contents, PII or session identifiers

### Testing

- JUnit 5 only, arranged as Arrange-Act-Assert
- Minimum 80% coverage
- CUI Test Generator for test data, `@GeneratorsSource` preferred
- Forbidden: Mockito, PowerMock, Hamcrest

### Javadoc

- Every public and protected class and method is documented, with `@since`, thread-safety notes
  and usage examples
- Every package carries a `package-info.java`

## OpenRewrite Markers

A marker such as `/*~~(TODO: INFO needs LogRecord)~~>*/` marks an actual bug, not a suggestion.
Fix placeholder and parameter mismatches and wrong format specifiers, create `LogRecord`
constants for production INFO/WARN/ERROR logs, and replace generic `Exception` or
`RuntimeException` with a specific type. For diagnostic logging in tests, suppress with
`// cui-rewrite:disable CuiLogRecordPatternRecipe`. Never commit code with a marker still in it.

## Security

- Validate all inputs and outputs at the trust boundary, and reject rather than coerce
- Never expose sensitive data in logs or in error messages returned to callers
- Follow OWASP guidance and prefer secure defaults
- Resolve secrets from external configuration; never hardcode them
- Validate security configuration fail-fast at startup rather than lazily at runtime

## Sonar

The cuioss-organization SonarCloud gate (project `cuioss_API-Sheriff`, declared in
`.github/project.yml`) is authoritative and blocking. Target zero new findings. Never merge over
a red gate, nor over a green one that is stale while analysis is still pending. Thresholds are
organization-owned — reference the gate rather than restating them.

Fix by default. Where a fix is genuinely not sensible — a false positive, a deliberate idiom, a
rule fighting the design — suppress in code with a rationale (`// NOSONAR java:SXXXX <why>` or
`@SuppressWarnings("java:SXXXX")`), never by marking the issue won't-fix or false-positive in the
Sonar UI. See `doc/development/sonar-quality-gate.adoc`.

## Dependencies

Parent POM is `de.cuioss:cui-java-parent`. Never add a dependency without explicit user approval.

## Git Workflow

This section governs ad-hoc changes. Work executed through plan-marshall is governed by
`.plan/marshal.json` and the per-plan execution manifest instead, including merge behaviour,
review triage and branch cleanup.

`main` is branch-protected in every cuioss repository — a direct push is never allowed. Batch
related changes into one pull request; open a second only when one would exceed 150 changed
files.

1. Branch: `git checkout -b <branch-name>`
2. Commit, then push with `git push -u origin <branch-name>`
3. Open the pull request against `main`
4. Wait for CI and the three automated reviewers — CodeRabbit, Sourcery and PR-Agent
5. Treat the union of all three reviewers' comments as the work list. Every comment gets a reply
   and is resolved: fix and explain, or explain why not. When not fully confident, ask the user
   rather than deciding
6. Re-review after a fix push is not uniform — CodeRabbit and Sourcery re-review automatically,
   PR-Agent does not. Request it explicitly by posting a `/review` comment
7. Do not enable auto-merge unless told to
8. After the merge, check both the PR-attached post-merge run and the main-branch run (the
   snapshot deployment appears only on the merge commit, never on the pull request), and assert
   there are no unresolved review threads
9. Return to `main` and pull

## Temporary Files

Use `.plan/temp/` for all temporary and generated files.

## Tool Usage

Use the Edit, Read and Write tools rather than shell commands such as `echo` or `cat`, and use
Glob, Read and Grep rather than `find`, `grep`, `cat` or `ls` for file operations.
