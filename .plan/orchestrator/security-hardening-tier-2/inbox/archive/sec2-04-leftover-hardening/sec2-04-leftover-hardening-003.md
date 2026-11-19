envelope_version=1
sender_type=plan
sender_id=sec2-04-leftover-hardening
epic=security-hardening-tier-2
kind=candidate-lesson
created=2026-11-19T17:41:56Z

# Owed architecture hint (preference-emitter): timing-bound tests flake under foreign host load

**Category**: improvement · **Component**: plan-marshall:phase-6-finalize (preference-emitter)

- **Pattern**: (module `maven`, finding-class `test failure [test_failure]`, disposition `accepted`). It occurred 2 times in plan sec2-04-leftover-hardening, which meets the threshold of 2.
- **Target `--module`**: `api-sheriff` (the finding was attributed to `maven`, the tool; the tests belong to the api-sheriff module).
- **Enrich verb**: `insight`
- **Hint text**: "Tests in api-sheriff that wait on wall-clock time, such as GatewayEdgeHeldAnswerBudgetTest's 1 s ConditionTimeout and the Vert.x close and deadline margins, can fail under foreign host load (load 13+). Treat a single failure as environmental until it reproduces on an idle host or in repeated runs; check `uptime` before attributing it to the branch."
