envelope_version=1
sender_type=plan
sender_id=plan-v02-25-edge-route-decomposition
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-17T23:29:57Z

## Candidate lesson: ArchUnit root-package rule forbade only GatewayEdgeRoute, not the whole edge root package

- Source: review-bot finding 1cbb81 (coderabbit, PR #487, resolution: fixed by TASK-10, fix commit f6f3a7e)
- File: api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/EdgeSubPackageArchTest.java:282
- Plan: plan-v02-25-edge-route-decomposition (V02-25, edge route decomposition)

### Observation

`edge/package-info.java` and ADR-0068 state that edge sub-packages must not depend on the edge root package. The ArchUnit predicate forbade only `GatewayEdgeRoute` and its nested classes. If a second class were added to the root and a sub-package depended on it, both architecture rules would still pass.

### Resolution in run

The rule now forbids a dependency on any class in the exact edge root package. The package-info, ADR-0068 and the test Javadoc describe the rule as built.

### Candidate rule (for orchestrator classification)

Check a guard's predicate against the rule it claims to enforce: when the documented rule is about a package, match by package, not by the one class that happens to live there today. A predicate narrower than its documented rule passes while the rule is broken.
