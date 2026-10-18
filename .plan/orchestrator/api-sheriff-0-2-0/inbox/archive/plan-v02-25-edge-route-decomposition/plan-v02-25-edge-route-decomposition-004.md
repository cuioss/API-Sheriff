envelope_version=1
sender_type=plan
sender_id=plan-v02-25-edge-route-decomposition
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-17T23:29:31Z

## Candidate lesson: enum dispatch written as a switch statement is not compiler-checked for exhaustiveness

- Source: review-bot finding f40441 (coderabbit, PR #487, resolution: fixed by TASK-9, fix commit f6f3a7e)
- File: api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/assembly/DispatchPath.java:29
- Plan: plan-v02-25-edge-route-decomposition (V02-25, edge route decomposition)

### Observation

The `DispatchPath` Javadoc said the assembler and the edge dispatch switch "can never disagree". That held only for `Protocol -> DispatchPath`, because `DispatchPath.of` is a switch expression. The consumer in `GatewayEdgeRoute.process` was a switch statement with arrow labels. Java does not require a statement switch over an enum to cover every constant. A fourth `DispatchPath` constant, a step `doc/development/protocol-processors.adoc` prescribes, would compile without an arm. Requests on that route would then fall through, and nothing would answer them until the client timed out.

### Resolution in run

The dispatch became a switch expression with no default arm, so a missing arm is a compile error, and the Javadoc was corrected.

### Candidate rule (for orchestrator classification)

When a switch over an enum has to handle every constant, write it as a switch expression (or give it a throwing default arm). An arrow-label switch statement looks exhaustive but is not checked. Do not let a Javadoc claim exhaustiveness the compiler does not enforce. In this run the slip passed the in-plan reviews and was caught only by the review bot.
