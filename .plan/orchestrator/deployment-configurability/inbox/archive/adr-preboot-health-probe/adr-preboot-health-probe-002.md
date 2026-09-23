envelope_version=1
sender_type=plan
sender_id=adr-preboot-health-probe
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-04T07:12:19Z

## Candidate: an ADR asserted a deployment remedy the implementing source cannot provide (caught by review, not by authoring)

**Source signal**: `pr-comment` finding `9c1939`, PR #257, CodeRabbit inline comment (Major) on `doc/adr/0039-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc:77`. Resolution: `fixed`.

**Observation**

The ADR documented a `HEALTHCHECK` override as sufficient to move the pre-boot probe port. The implementing source refutes it: `HealthProbe.PROBE_PORT` is a compiled-in `9000`, `HealthProbe.probe()` takes no port argument and reads no configuration, and it connects to a hardcoded `127.0.0.1:PROBE_PORT`. The distroless image carries no executable other than `/app/application`, so an overridden `HEALTHCHECK` can only re-invoke the same binary and still probes 9000. The documented remedy was unreachable.

The claim appeared at two sites — the Decision bullet on the compiled-in constant, and the Consequences/Negative bullet whose "does not move the HEALTHCHECK" phrasing implied the same false remedy. Both were corrected to state the real consequence: the port is compiled in, so moving `quarkus.management.port` away from 9000 makes the baked check fail closed, never healthy, until the image is rebuilt with a matching constant.

**Why it is a candidate**

This is the slipped-then-caught class. The defect was authored into a documentation deliverable and survived outline, planning, execution and the phase-5 verification sweep; the only thing that caught it was an automated reviewer reading the ADR against the source. The generalisable shape: **a prose deliverable that describes runtime or deployment behaviour is not verified by any build, so its claims must be traced to the implementing symbol at authoring time — a documentation-only footprint removes the gate that would otherwise catch the error.**

The correction was verified against the source rather than accepted on the reviewer's word, which is the right disposition and worth preserving as the pattern.

**Residual, recorded here so it is not lost**: the identical incorrect port-override claim still stands in `HealthProbe.java`'s own Javadoc (lines 36-38, decision.log `116b0d`). It could not be fixed in this plan because a single `.java` file would have made the commit gate-requiring and broken the documentation-only skip. It is a code fix, not a process lesson, and is owed as follow-up work.

**Classification deferred** — the plan transmits this candidate; it makes no global-vs-epic judgement.
