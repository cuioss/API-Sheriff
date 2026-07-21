# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

API Sheriff is a security-focused API Gateway with a lightweight approach, currently in pre-1.0 development. Built with Maven, Java 25 (compile + runtime; CI matrix 25 + 26), and Quarkus 3.37.2. Follows CUI (CUIoss) standards.

## Project Structure

Multi-module Maven project:
- `api-sheriff/` — Deployable Quarkus application (core library, CDI producers, REST endpoints, native executable)
- `integration-tests/` — Integration test coordinator (Docker infrastructure, IT suites, scripts)
- `benchmarks/` — WRK HTTP load testing benchmarks

## Development Notes

### Build Commands

Never hard-code build tool commands (`mvn`, `./mvnw`) — invoke builds via the canonical executor commands below:

- Compile: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "compile"`
- Quality gate: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Ppre-commit"`
- Full verify: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify"`
- Coverage: `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pcoverage"`
- Module tests (api-sheriff): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl api-sheriff -am"` — only on api-sheriff
- Module tests (benchmarks): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl benchmarks -am"` — only on benchmarks
- Module tests (integration-tests): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "test -pl integration-tests -am"` — only on integration-tests
- Integration tests (integration-tests): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pintegration-tests -pl integration-tests -am"` — only on integration-tests
- Benchmark (benchmarks): `python3 .plan/execute-script.py plan-marshall:build-maven:maven run --command-args "verify -Pbenchmark -pl benchmarks -am"` — only on benchmarks
- Use a 10-minute Bash timeout (600000ms) for build invocations
- Analyze each build's TOON result: `status`, `errors[N]{file,line,message,category}`, `log_file`

Special builds without a canonical command:

```bash
# Build native executable
./mvnw clean install -Pnative -pl api-sheriff -am -DskipTests

# Build production Docker image
docker build -f api-sheriff/src/main/docker/Dockerfile.native -t api-sheriff:latest api-sheriff/
```

### Pre-Commit Process

**CRITICAL** — run before every commit; both must pass with zero errors/warnings:

1. Quality gate (canonical `quality-gate` command above)
2. Full verify (canonical `verify` command above)

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

- **Parent POM**: `de.cuioss:cui-java-parent:1.5.1`
- **CRITICAL**: Never add dependencies without explicit user approval

## Git Workflow

**Scope**: the manual workflow below applies to ad-hoc (non-plan-marshall) changes only. Work executed through plan-marshall (`/plan-marshall` plans) is governed by the plan-marshall configuration (`.plan/marshal.json` and the per-plan execution manifest) — including merge behavior (`final_merge_without_asking`, merge queue), review-comment triage, and branch cleanup — and merges without a manual approval prompt when so configured.

**Minimize the number of PRs.** Batch related changes into a single PR rather than splitting them; only open a second PR when a single one would exceed 150 changed files.

All cuioss repositories have branch protection on `main`. Direct pushes to `main` are never allowed. Always use this workflow:

1. Create a feature branch: `git checkout -b <branch-name>`
2. Commit changes: `git add <files> && git commit -m "<message>"`
3. Push the branch: `git push -u origin <branch-name>`
4. Create a PR: `gh pr create --repo cuioss/API-Sheriff --head <branch-name> --base main --title "<title>" --body "<body>"`
5. Wait for CI + Gemini review (waits until checks complete): `gh pr checks --watch`
6. **Handle Gemini review comments** — fetch with `gh api repos/cuioss/API-Sheriff/pulls/<pr-number>/comments` and for each:
   - If clearly valid and fixable: fix it, commit, push, then reply explaining the fix and resolve the comment
   - If disagree or out of scope: reply explaining why, then resolve the comment
   - If uncertain (not 100% confident): **ask the user** before acting
   - Every comment MUST get a reply (reason for fix or reason for not fixing) and MUST be resolved
7. Do **NOT** enable auto-merge unless explicitly instructed. Wait for user approval.
8. Return to main: `git checkout main && git pull`

## Temporary Files

Use `.plan/temp/` for ALL temporary and generated files (covered by `Write(.plan/**)` permission — avoids permission prompts).

## Tool Usage

- Use proper tools (Edit, Read, Write) instead of shell commands (echo, cat)
- Never use Bash for file operations (find, grep, cat, ls) — use Glob, Read, Grep tools instead
