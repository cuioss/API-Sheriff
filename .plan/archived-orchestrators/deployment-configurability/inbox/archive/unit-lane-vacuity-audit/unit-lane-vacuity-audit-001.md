envelope_version=1
sender_type=plan
sender_id=unit-lane-vacuity-audit
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-21T19:44:42Z

module: cuioss_API-Sheriff
category: best-practice
verb: architecture enrich best-practice --module cuioss_API-Sheriff

Prefer to suppress Sonar test-shape rules (e.g. java:S3577 class-naming,
java:S2699 assertion-presence) in cuioss_API-Sheriff via in-code
`@SuppressWarnings` when they fire on ArchTest positive/negative-control
specimen fixtures, because those specimens are deliberately shaped to violate
the rule under test — a non-matching class name is what keeps a specimen off
Surefire's default include pattern, and a missing assertion on a negative
control IS the control — and complying would break the fitness function's own
controls. Recurred 3 times within plan `unit-lane-vacuity-audit` (PR #336,
`AwaitsWithReassertionSpecimen.java` / `AwaitsWithoutReassertionSpecimen.java`),
all disposed `suppressed`.
