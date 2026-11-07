envelope_version=1
sender_type=plan
sender_id=plan-v02-02-java-idiom-sweep
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T04:18:06Z

component=api-sheriff
category=improvement
proposed_title=Native reflection gap is no longer invisible to JVM gates — the residual is package scope only

# Native reflection gap is no longer invisible to JVM gates — the residual is package scope only

A standing belief in this project's corpus holds that a new config record left
unregistered in `ConfigModelReflection` is invisible to every JVM gate and boot-fails
only in the native image. At the HEAD this plan grounded against, that is **no longer
true**, and acting on the stale form of the belief produces unnecessary work and a
misplaced sense of where the risk lives.

`ConfigModelReflectionTest` walks the record-component graph, so an omission inside the
walked package set fails on the JVM, in the ordinary gate, before any native build.

## The residual — which is real, and narrower

The gap that survives is **package scope only**: a config record introduced *outside*
the package set the test walks is still not reached, and for that record the original
failure mode is unchanged (green on the JVM, boot-fails natively). So the guard is a
guard over a scope, not over the class of defect.

## Rule

- Do not plan work on the premise that reflection-registration omissions are wholly
  invisible to JVM gates — re-check `ConfigModelReflectionTest`'s walked package set first.
- When adding a config record, the question to answer is "is my package inside the
  walked set?", not "did I remember to register it?". If the package is outside the
  set, either widen the walk or expect the native-only failure.

## Provenance

Established at outline time in `plan-v02-02-java-idiom-sweep` as a **refuted premise**
of the plan's own spec, by reading the test rather than by inheriting the belief. The
existing corpus/memory entry asserting invisibility should be narrowed to the package-scope
residual rather than left as written.
