envelope_version=1
sender_type=plan
sender_id=integration-test-evidence-follow-ups
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T21:08:44Z

# Candidate lesson: a guard's scope narrowed but the prose claim it backs was not

Source: 6-finalize Q-Gate finding d4e3af (fixed in-run, evidenced by 75f19e91), producer pm-plugin-development:ext-self-review-plan-marshall, defect class touched_claim_unverified.

## What happened

`doc/development/declared-limit-assertion-coverage.adoc` (lines ~948-958) claimed that a new descriptor directory breaks the exact-inventory equality and fails the build. `DescriptorInventoryWiringTest` had been narrowed to walk only `INVENTORY_ROOTS` (`sheriff-config*` and `reserved-path-overlap`), so a directory outside those roots is invisible to both assertions. A contributor adding `integration-tests/src/main/docker/foo-fixtures/x.yaml` with a limit-shaped key would trust the doc while the guard stays green and the limit gets no matrix row.

## Candidate rule

When a plan narrows what a guard test walks (roots, globs, populations), re-read every prose sentence that asserts what the guard catches, in the same change. The self-review caught it only at finalize; a phase-5 check of "claims about this test" would have caught it earlier.

Classification deferred to the orchestrator.
