envelope_version=1
sender_type=plan
sender_id=plan-v02-42-validator-auth-oidc-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T11:36:37Z

# Candidate lesson: the test-integrity check flags moved tests as weakened (plan-marshall tooling)

**Routing:** plan-marshall tooling candidate. Send it to the plan-marshall lessons-routing inbox, not to the local store.
**Suggested component:** plan-marshall:phase-6-finalize (the test-integrity / weakened_test check in review_support)
**Category:** bug

## What happened

PLAN-V02-42 (PR #502) was a move-only refactor. It used git mv to move tests from
ConfigValidatorTest, ConfigValidatorBackChannelOriginTest and ConfigValidatorSessionFallbackTest
(renames R095/R097, plus nested groups moved into AuthRulesTest, OidcClientRulesTest and
OidcSessionRulesTest). The test-integrity check counts assertions per file path against
origin/main. It therefore reported `weakened_test` blocking findings for all three old paths
(30, 29 and 111 assertions "gone"), in BOTH the functional review and the code review:
findings a73f39, d36795, 42d8ab, 0355c5, 940127, 4a2170, plus verdict_refused e72c83.

The facts were unchanged: 139 test methods before and after, no test body changed, and
257 test-body assertions on both sides. The verifier confirmed the per-path artefact
reading each time. However, only an operator close could clear the blocking findings,
and it was needed twice (once per review).

## Corrective action

The weakened_test check should follow git rename detection (`git diff -M`) and also compare
totals across the changed set of test files. Assertions that leave one path and arrive in a
new test file in the same diff are a move, not a loss. At minimum, when the verifier
classifies the finding as a move artefact, the check should downgrade the finding from
blocking. It should not require an operator close in each review.
