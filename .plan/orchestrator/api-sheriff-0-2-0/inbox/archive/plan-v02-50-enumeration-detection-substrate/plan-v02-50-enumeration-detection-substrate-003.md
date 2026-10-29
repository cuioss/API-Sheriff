envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T07:59:06Z

# Candidate lesson: CI-red integration tests were real production race bugs, not flakiness

**Source signal:** CI integration-test failures on the plan's PR during finalize.

**Observation:** Integration tests that went red in CI but passed locally looked like flakiness. Investigation showed they exposed genuine ordering/race defects in production code, which were then fixed in-run. A rerun would have hidden them.

**Corrective rule:** Treat a CI-only integration-test failure as a suspected production defect first. Reproduce it under constrained scheduling (for example `-XX:ActiveProcessorCount=1`, or a loaded machine) before classifying it as a flake, and do not retry-to-green or add retries/sleeps to the test until a root cause is named. Record the root cause in the fix commit.

**Scope:** project integration-test triage practice.
