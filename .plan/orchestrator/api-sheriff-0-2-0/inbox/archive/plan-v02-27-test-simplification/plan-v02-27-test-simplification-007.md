envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-25T22:00:57Z

# Candidate lesson: JaCoCo exec data accumulates across `verify -Pcoverage` runs without `clean`

**Suggested route:** project (stays local to API Sheriff). This concerns the coverage profile and how before/after coverage comparisons are run.

**Source:** plan-v02-27-test-simplification. Finding 32f308, decision-log entries 0abb39 and f12f7a (functional-review residual D4.R3), and finding 863f50.

## What happened

The coverage command runs without `clean`. The merged report and `jacoco:check` therefore read every test JVM session since the baseline run, so a before/after comparison reads a union of runs. On their own, those figures cannot show a coverage drop in a class whose bytecode did not change.

The plan had to fall back on a probe-level comparison of the run's own sessions against the union. That comparison ran from a scratchpad script the plan does not keep, so a reviewer could check its reported outcome but could not re-run it. It did surface a real one-probe drop in AnchorRules, a class the plan did not touch (finding 863f50).

## Corrective direction

- For any before/after coverage measurement, run `clean verify -Pcoverage -pl api-sheriff -am`. Alternatively, delete the module's jacoco exec files before each measured run, so that each figure reflects exactly one tree.
- Consider making the coverage profile reset its exec data itself, so that a measurement without `clean` cannot read a union. That would be a follow-up change to the build.
- Keep any comparison script that a plan relies on as evidence inside the plan directory, not in a session scratchpad.
