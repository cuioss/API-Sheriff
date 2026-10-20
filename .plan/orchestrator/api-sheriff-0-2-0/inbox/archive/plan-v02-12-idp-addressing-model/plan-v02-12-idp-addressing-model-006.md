envelope_version=1
sender_type=plan
sender_id=plan-v02-12-idp-addressing-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-20T20:30:43Z

# Candidate lesson: Sonar java:S2583 flags a null guard on a parameter that is not @Nullable inside a @NullMarked package

Plan plan-v02-12-idp-addressing-model (PR #492, merge 03cb4755), lessons-capture step. Component: api-sheriff Java code / Sonar quality gate.

## Observation

A defensive `if (param == null)` guard on a parameter not annotated `@Nullable`, in a package whose `package-info.java` carries `@NullMarked`, was reported by SonarCloud as java:S2583 (condition always evaluates to false). Under JSpecify `@NullMarked`, an unannotated parameter is non-null, so Sonar treats the guard as dead code.

## Rule

In a `@NullMarked` package, decide nullability in the signature: either annotate the parameter `@Nullable` (and keep the guard), or drop the guard and rely on the non-null contract (use `Objects.requireNonNull` only where a hard fail-fast at a trust boundary is wanted). Do not leave an unannotated parameter with a null branch — it fails the new-code gate.

## Destination suggestion

Project lesson; fits the `java-null-safety` / Sonar compliance guidance (`doc/development/sonar-quality-gate.adoc`).
