envelope_version=1
sender_type=plan
sender_id=plan-v02-09-token-sheriff-integration-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T23:25:12Z

# Candidate lesson: staged-spec claims about dependency behaviour and test coverage were wrong when checked

**Signal source:** Q-Gate (functional review / spec re-grounding during execute)

**Observation.** Two claims in the PLAN-V02-09 spec turned out to be false when checked during execution:
1. The spec said the token-sheriff extension's beans were "never registered before 0.9.7". Inspecting the shipped jars showed this was wrong, and ADR-0074 had to correct the account.
2. The spec said `ShippedApplicationPropertiesTest` pins the exclusion. It does not. The test the spec named as the guard does not actually guard that property.

**Why it matters.** A plan that trusts its spec would have built on a false history and treated the exclusion as already guarded by a test when it was not.

**Possible corrective direction (for orchestrator judgement).**
- When re-grounding a spec, check claims about third-party artifact behaviour against the actual jar or bytecode of the pinned version, not against prose or release notes.
- Verify "test X pins Y" claims before relying on them, for example by checking whether removing Y would turn X red.
- Correct the staged spec text in place when a claim is disproven.

**Classification:** deferred to the orchestrator. This is mainly an epic/orchestrator lesson (spec re-grounding).
