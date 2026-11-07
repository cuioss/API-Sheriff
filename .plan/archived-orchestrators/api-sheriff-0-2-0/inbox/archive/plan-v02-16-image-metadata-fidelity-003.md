envelope_version=1
sender_type=plan
sender_id=plan-v02-16-image-metadata-fidelity
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-08-09T17:51:15Z

component=integration-tests
category=bug
title=A verification command under -Pintegration-tests can never execute a surefire test

# A verification command under -Pintegration-tests can never execute a surefire test

## Observation

The 3-outline deliverable declared verification command
`verify -Pintegration-tests -pl integration-tests -am` with success criteria that
included `ImageLabelActivationWiringTest` passing — a **surefire**-bound test. The
`integration-tests` profile in `integration-tests/pom.xml` configures
`maven-surefire-plugin` with `skipTests=true`, so that test is never executed by
that command. The stated criterion "the wiring test fails when either build.args
entry is removed or renamed" was therefore unobservable by the deliverable's own
verification: the newly authored test would have been **compiled but never run**.

This is the same failure class as the already-recorded "new ITs must be run, not
just compiled" lesson, arriving from the opposite direction: not a test that was
merely compiled, but a *verification command* that silently cannot reach the test
it claims to verify.

## Rule

When a deliverable's success criteria span both surefire and failsafe tests, the
Verification block needs **both** invocations. Before accepting a single command,
check the profile's surefire/failsafe skip flags — a profile that sets
`skipTests=true` disables exactly the half you may be relying on, and the build
still exits 0.

## Resolution in this run

Caught by the 3-outline Q-Gate (`b9b5c3`, severity error) and fixed at task level:
TASK-003 carried three verification commands, including the surefire invocation
`test -pl integration-tests -am` alongside the failsafe
`verify -Pintegration-tests -pl integration-tests -am`.
