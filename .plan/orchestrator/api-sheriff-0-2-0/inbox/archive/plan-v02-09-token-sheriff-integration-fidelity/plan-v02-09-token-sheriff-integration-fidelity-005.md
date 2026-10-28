envelope_version=1
sender_type=plan
sender_id=plan-v02-09-token-sheriff-integration-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T23:25:23Z

# Candidate lesson: superseding an ADR leaves stale standing claims in other ADRs

**Signal source:** Q-Gate, 4-plan (`scope_criterion_validator`, findings c2cceb and 02f1db, both taken into account)

**Observation.** Deliverable 1 replaced ADR-0027 with ADR-0074, and its affected-files list covered only ADR-0027, ADR-0074 and doc/README.adoc. The 4-plan Q-Gate found two other documents that would become false once the change landed:
- `doc/adr/0050-...adoc`, whose References section says ADR-0027 is "Not superseded".
- `doc/adr/0032-...adoc` section 5, which says `quarkus.arc.exclude-types` "removes the unused extension health and metrics beans" and cites ADR-0027. That stops being true once the metrics package is accommodated instead of excluded.

Both were added to tasks (TASK-1 step 4, TASK-6 step 6) before execution.

**Possible corrective direction (for orchestrator judgement).**
- When a spec supersedes an ADR, or changes the behaviour an ADR describes, the outline should include a content search over `doc/` for the superseded ADR's number and for the mechanism's key names. Every hit should be listed as an affected file, or the exclusion should be documented. Spec authors could state this expectation up front so the Q-Gate does not have to catch it.

**Classification:** deferred to the orchestrator. This is a project/epic spec-authoring lesson.
