# CLAUDE.md

This file provides guidance to Claude Code when working with this repository.

## Project Overview

API Sheriff is a security-focused API Gateway with a lightweight approach, currently in pre-1.0 development. Built with Maven, Java 21+, and Quarkus 3.32.1. Follows CUI (CUIoss) standards.

## Project Structure

Multi-module Maven project:
- `api-sheriff/` — Deployable Quarkus application (core library, CDI producers, REST endpoints, native executable)
- `integration-tests/` — Integration test coordinator (Docker infrastructure, IT suites, scripts)
- `benchmarks/` — WRK HTTP load testing benchmarks

## Build Commands

```bash
# Full build with tests
./mvnw clean install

# Build without tests
./mvnw clean install -DskipTests

# Single module
./mvnw clean install -pl <module-name>

# Single test
./mvnw test -Dtest=ClassName#methodName

# Integration tests
./mvnw clean verify -Pintegration-tests -pl integration-tests -am

# Integration benchmarks (WRK)
./mvnw clean verify -pl benchmarks -Pbenchmark

# Build native executable
./mvnw clean install -Pnative -pl api-sheriff -am -DskipTests

# Build production Docker image
docker build -f api-sheriff/src/main/docker/Dockerfile.native -t api-sheriff:latest api-sheriff/
```

### Pre-Commit Process

**CRITICAL** — run before every commit:

1. Quality verification (fix ALL errors/warnings):
   ```bash
   ./mvnw -Ppre-commit clean verify -DskipTests
   ```
2. Full verification (must pass completely):
   ```bash
   ./mvnw clean install
   ```

## Pre-1.0 Rules (HIGHEST PRIORITY)

- **NEVER deprecate code** — remove it directly
- **NEVER add @Deprecated** — delete unnecessary code immediately
- **NEVER enforce backward compatibility** — make breaking changes freely
- **Clean APIs aggressively** — remove unused methods, classes, patterns

## Code Standards

- Java 21+ features encouraged (records, sealed classes, pattern matching, text blocks)
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

## Dependency Management

- **Parent POM**: `de.cuioss:cui-java-parent:1.4.4`
- **CRITICAL**: Never add dependencies without explicit user approval

## Git Workflow

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
