envelope_version=1
sender_type=plan
sender_id=plan-v02-25-edge-route-decomposition
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-17T23:29:44Z

## Candidate lesson: ArchUnit non-vacuity list hand-mirrored the sub-package set instead of deriving it

- Source: review-bot finding cdccc6 (coderabbit, PR #487, resolution: fixed by TASK-10, fix commit f6f3a7e)
- File: api-sheriff/src/test/java/de/cuioss/sheriff/gateway/arch/EdgeSubPackageArchTest.java:102
- Plan: plan-v02-25-edge-route-decomposition (V02-25, edge route decomposition)

### Observation

`DECOMPOSED_SUB_PACKAGES` in `EdgeSubPackageArchTest` was a hand-written copy of the sub-package set the production `edge/*/package-info.java` files declare. If a tenth sub-package is added and later loses its production classes, the cycle rule loses that slice, but the non-vacuity check still passes because the list never contained the new package.

### Resolution in run

The list is now derived from the `package-info` declarations beneath the edge root, and each derived sub-package must resolve to at least one production class other than `package-info`.

### Candidate rule (for orchestrator classification)

An architecture guard's population (packages, slices, classes) must be derived from the source it guards, not hand-listed. A hand-listed population turns the non-vacuity check itself into a mirror that can go stale while still passing. This is the same rule (a hardcoded list that must mirror a set defined elsewhere is a defect) as the DispatchPath switch finding in this run, applied to test code.
