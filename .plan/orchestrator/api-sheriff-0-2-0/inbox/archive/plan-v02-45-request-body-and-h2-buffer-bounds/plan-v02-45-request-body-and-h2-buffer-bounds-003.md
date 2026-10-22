envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:20:46Z

# Test file shared by several deliverables without ordering or stated ownership

- Source: Q-Gate finding d7d542 (phase 3-outline, profile overlap, taken_into_account)
- Component: api-sheriff edge tests (DispatchStageTest, GatewayEdgeRequestBodyIdleTimeoutTest)

## Observation

DispatchStageTest.java sat in a test-only deliverable (D5, depends: none) while deliverables 3 and
4 listed DispatchStage.java under their module_testing profile, and
GatewayEdgeRequestBodyIdleTimeoutTest.java was written by both D3 and D5. The tests were distinct,
but nothing ordered the deliverables or said who owns which file.

## Outcome

D5 now depends on D3 and D4, and the outline carries a test-file ownership table: each shared test
file has one owning deliverable; other deliverables' production changes are additive, proven in
their own test files, and must keep the owned test green unchanged.

## Candidate rule

When two deliverables touch the same test file, the outline states the owner and orders the
deliverables with depends; a test-only deliverable that edits files other deliverables also edit
does not get depends: none.
