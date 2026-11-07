envelope_version=1
sender_type=plan
sender_id=plan-v02-09-token-sheriff-integration-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T23:25:34Z

# Candidate lesson: a retired key should be guarded by an exact key-set assertion, not by negative checks that spell the key

**Signal source:** Q-Gate, 6-finalize (functional review residual on D4.R1, finding b90327, taken into account)

**Observation.** Deliverable D4 retired the `issuer_reachability` key. `SheriffMetricsTest.java` still spelled the retired key literally five times, as `assertNull` guards checking that it was absent. The functional review flagged this as a residual: the retired name stays in the codebase, a later grep for it still finds hits, and a negative check only catches that one spelling, so any other unexpected key passes unnoticed.

**Possible corrective direction (for orchestrator judgement).** When a key, metric or tag is retired, assert the exact expected key set (for example `assertEquals(Set.of(...), actual.keySet())`) rather than writing `assertNull(map.get("<retired>"))` per retired name. That catches the retired key and any other unexpected key, without spelling the retired name.

**Classification:** deferred to the orchestrator. This is a project testing lesson.
