envelope_version=1
sender_type=plan
sender_id=bff-refresh-integration-coverage
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-09T11:45:18Z

# Candidate lesson: a green test suite is not coverage; assert the gate's own measurement

## Category
anti-pattern

## Component
build-maven / sonar-quality-gate

## What happened

This plan's entire deliverable was integration coverage. The integration suite
went green, and the plan still failed the Sonar quality gate on new-code coverage
at 43.3% against an 80% threshold.

Both facts were true about different things. The ITs run in a separate Maven
module, against a native binary inside Docker, so JaCoCo never instruments the
code they exercise. The coverage the gate measures and the code the suite covers
have no mechanical relationship at all.

The failure was not catchable locally by the documented pre-commit process:
neither `verify` nor `verify -Ppre-commit` runs the coverage profile, so the
first observation of the number came from CI after the PR was open.

## Generalisable rule

- **Coverage is a property of the instrumented run, not of the test's intent.**
  A test that exercises a line through a process boundary (a separate JVM, a
  container, a native binary, a subprocess, an HTTP call to a deployed instance)
  contributes zero to a line-coverage gate unless the instrumentation explicitly
  spans that boundary.
- Where a plan's stated value IS a coverage number, the coverage measurement is
  the acceptance criterion and must be run **before** the PR is opened — not the
  test suite's exit status. Run the profile that produces the number the gate
  reads.
- More generally: **whenever a gate is authoritative but the local pre-commit
  process cannot run it, that gap is a known defect of the local process**, not
  an acceptable division of labour. Either add the gate's own command to the
  pre-submission checklist for plans of that class, or expect to discover its
  verdict only after review has started.

## Recurrence signature

"All tests pass" reported as evidence for a claim about a metric that no local
command computed.
