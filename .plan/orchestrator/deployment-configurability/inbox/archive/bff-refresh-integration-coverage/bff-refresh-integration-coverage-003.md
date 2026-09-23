envelope_version=1
sender_type=plan
sender_id=bff-refresh-integration-coverage
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-09T11:45:22Z

# Candidate lesson: "untestable" uncovered lines are usually a testability defect, not missing test intent

## Category
improvement

## Component
java-core / persona-module-tester

## What happened

The new-code coverage shortfall was concentrated in decisions that lived as
lambdas inside one long assembly method (a CDI producer method building a
runtime object). No test could invoke those decisions independently, because
they had no name and no reachable entry point — reaching them required
constructing the whole runtime.

The remedy was not more tests against the assembly method. It was extracting the
decisions into named, package-private seams. New-code coverage moved from 38% to
95% with seven ordinary unit tests, and the tests that resulted assert the
decision rather than the assembly.

## Generalisable rule

When a coverage gap sits on lines nobody can reach:

1. First ask **why they cannot be reached**, not how to reach them. A decision
   embedded in a lambda inside a long construction/wiring method is unreachable
   by construction.
2. Extract each such decision to a **named, package-private (or otherwise
   directly invocable) seam** and test the seam. This is a refactor of the
   production code, and it is the correct fix — the coverage number is a symptom.
3. The anti-pattern to avoid is the inverse: driving the whole assembly through
   an integration-shaped test purely to make lines execute. That buys the number
   without buying an assertion about the decision, and it is exactly the vacuous
   coverage a gate exists to prevent.

## Recurrence signature

A long producer / factory / builder / wiring method whose body contains
conditional logic inside lambdas, paired with a coverage report that is red
precisely on those lambda bodies.
