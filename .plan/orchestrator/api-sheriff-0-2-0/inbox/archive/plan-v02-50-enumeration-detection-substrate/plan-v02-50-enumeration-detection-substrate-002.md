envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T07:58:56Z

# Candidate lesson: test-count jitter from generator-driven parameterized tests is not a regression signal

**Source signal:** build verification across phase 5 and finalize.

**Observation:** The reported total test count moved between otherwise identical builds. The drift came from generator-driven parameterized tests (CUI Test Generator / `@GeneratorsSource`) whose invocation count varies per run, not from tests being added, dropped or skipped.

**Corrective rule:** Do not use an exact test-count match between runs as evidence that a change did or did not affect the suite. Compare by test class / failure set instead, and treat small count deltas confined to generator-sourced parameterized tests as expected noise. Investigate only a delta that maps to a missing or newly skipped test class.

**Scope:** project test practice / verification evidence.
