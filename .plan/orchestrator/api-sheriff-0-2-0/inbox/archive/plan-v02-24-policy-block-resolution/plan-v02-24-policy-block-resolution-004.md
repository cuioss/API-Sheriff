envelope_version=1
sender_type=plan
sender_id=plan-v02-24-policy-block-resolution
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-15T09:39:25Z

# Candidate lesson: Sonar java:S2637 / java:S2638 flip-flop on a nullable type argument under @NullMarked

**Source**: plan-v02-24-policy-block-resolution, Sonar findings on PR #465 (resolved in-run).

## What happened

The code is in a `@NullMarked` package and carries a `@Nullable` type argument. Sonar alternated between java:S2637 ("non-null" value may be null) and java:S2638 (nullability contract of an overridden method changed). Each code-level fix for one rule triggered the other, so the code went round trips without converging.

## Corrective rule

Once a nullable type argument under `@NullMarked` produces this S2637/S2638 pair, stop reshaping the code. Suppress in code at the narrowest site with `@SuppressWarnings("java:S2637")` / `@SuppressWarnings("java:S2638")` and a rationale comment saying the JSpecify type-argument nullability is deliberate. This is the stable answer the project's Sonar policy allows for a rule that conflicts with the design (CLAUDE.md "Sonar / Quality Gate": suppress in code with a rationale, never in the Sonar UI). Do not loosen the `@NullMarked` contract to satisfy the analyzer.

## Suggested classification

Project-local (API-Sheriff code quality / Sonar compliance). Possibly worth a note in `doc/development/sonar-quality-gate.adoc` as a known rule-pair conflict.
