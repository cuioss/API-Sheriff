envelope_version=1
sender_type=plan
sender_id=adr-preboot-health-probe
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-04T07:12:14Z

## Candidate: scope classification is persisted independently of the scope sensor, so the two can disagree

**Source signal**: Q-Gate finding `a7a792`, phase `2-refine`, type `anti-pattern`, severity `warning`, component `plan-marshall:manage-status`. Resolution: `taken_into_account` (2026-09-03T20:51:09Z).

**Observation**

`references.scope_estimate` was persisted as `surgical` while the scope sensor banded the same request body `multi_module`. The sensor bands on glob / pattern fan-out markers in the request body; the persisted estimate is derived from `module_mapping`. Because the two read different inputs, they disagree whenever the request body cites patterns that are not deliverable fan-out.

In this run the markers the sensor banded on (`Dockerfile*`, `docker-compose*.yml`, `*.adoc` under `doc/**`, `.plan/*`) were all exclusion prose or evidence citations, and the nine explicit paths were read-only verification targets. `module_mapping` carried exactly two concrete paths — `doc/adr/0039-*.adoc` (create) and `doc/README.adoc` (modify) — both in the `documentation` module with no public API surface, so the `surgical` estimate was correct and the finding was resolved `taken_into_account` rather than fixed.

**Why it is still a candidate**

The finding was a false positive here, but the mismatch is structural, not incidental: any request whose narrative cites path patterns for exclusion or evidence will re-trigger it. The guarded risk — a narrow band suppressing S3/S4 escalation and projecting the minimal execution posture — was unreachable in this run only because the plan was independently on `planning_lane=deep` / `track=complex`. A run that is not already on the maximal planning posture would not have that cover.

**Candidate rule for the orchestrator to judge**

Either the sensor should discount pattern markers appearing in exclusion prose and evidence citations, or the finding should state that a `deep` / `complex` posture already neutralises the escalation-suppression risk it guards, so the reviewer is not asked to re-adjudicate a risk that cannot fire.

**Classification deferred** — the plan transmits this candidate; it makes no global-vs-epic judgement.
