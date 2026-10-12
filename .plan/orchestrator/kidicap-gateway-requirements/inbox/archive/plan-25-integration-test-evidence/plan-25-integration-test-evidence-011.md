envelope_version=1
sender_type=plan
sender_id=plan-25-integration-test-evidence
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-08T21:27:11Z

component=benchmarks
category=anti-pattern
created=2026-10-08

# A tool behaviour stated from documentation was wrong on both sides; only a measurement on the pinned image settled it

## What happened

Plan plan-25-integration-test-evidence (PR #408), review-bot finding 0e051a (CodeRabbit, `pending_login_flood.js`). The reviewer stated, citing k6 documentation and source, that on k6 2.1.0 a threshold on a counter that never received a sample passes, so a run ended early by `fail()` could exit 0. The script header and `benchmarks/README.adoc` stated the opposite, also without measurement. Triage could not establish validity and deferred to the operator, who chose: measure, then fix.

Measured on the pinned image (`grafana/k6:2.1.0@sha256:65c920dc...`): a one-iteration script calling `fail()` with a never-incremented counter under `count==1` printed the threshold as failed and exited 99. So the reviewer's premise did not hold on that image, and the script's own claim had been unproven. `exec.test.abort()` exits 108 and still calls `handleSummary`. The three `fail()` calls were replaced by `exec.test.abort()` (commit 05fc1a1e) and header and README now state the measured behaviour.

## Rule

- A statement in code comments or documentation about how a pinned third-party tool behaves needs a measurement on the pinned version, not a citation.
- When a review comment and the code contradict each other on such a point, do not pick a side in triage: measure, then answer the thread with the measurement.
- Prefer a mechanism whose failure does not depend on the disputed behaviour (here: a run-level abort instead of relying on threshold evaluation).

## Evidence

- Review-bot finding 0e051a (resolution fixed, reply recorded); decision log entries b80a5b, 27b7fd, a5fd80.
- Not measured: the full flood against a running stack.
