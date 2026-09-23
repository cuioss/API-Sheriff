envelope_version=1
sender_type=plan
sender_id=bff-refresh-integration-coverage
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-09T11:45:26Z

# Candidate lesson: fixing a vacuous-green selector obliges sweeping every sibling lane that shares it

## Category
anti-pattern

## Component
junit-integration / maven-profiles

## What happened

The plan fixed a genuine vacuous-green defect: a Failsafe `<include>` pattern
matched no test class under Failsafe 3.6.0, so the integration-tests lane
reported BUILD SUCCESS while executing zero ITs — a green that proved nothing.

The same correction was then applied to a sibling profile (`jfr`). But that
profile declares only a subset of the system properties the tests need. Widening
its selector newly selected an mTLS handshake IT into a lane that supplies no
mTLS port or client keystore, so the test would silently fall back to the
non-mTLS port and pass — green, having proven nothing about mTLS.

In other words: **the fix for one vacuous green manufactured a second vacuous
green one lane over.** It was caught by the automated PR reviewer, not by any
build, because both the before and after states were green.

## Generalisable rule

- A test-selection change is never local to the profile it is edited in. Enumerate
  **every** lane sharing that selector and, for each newly-selected test, check
  that the lane supplies the fixtures/properties that test needs to be meaningful.
- A test whose configuration is absent must **fail**, not fall back to a default
  that lets it pass. A silent fallback to a default port / null keystore /
  disabled feature converts a missing fixture into a false green. Where such a
  fallback exists, either remove it or add a precondition assertion that fails
  when the required property is unset.
- Corollary: neither the before-state nor the after-state of a vacuous-green
  defect is observable from the build's exit status. Verifying such a fix requires
  asserting the **executed test count** (or the specific test names) changed as
  intended, in every affected lane.

## Recurrence signature

A `<includes>` / `<excludes>` / test-pattern edit applied by symmetry to a sibling
profile, without checking that profile's `systemPropertyVariables` block.
