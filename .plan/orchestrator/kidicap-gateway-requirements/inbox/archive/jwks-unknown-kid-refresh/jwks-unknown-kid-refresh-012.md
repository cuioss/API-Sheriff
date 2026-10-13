envelope_version=1
sender_type=plan
sender_id=jwks-unknown-kid-refresh
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T15:30:34Z

component=api-sheriff:gateway-auth
category=bug
created=2026-10-12

# RetryingJwksLoader.runRetry does not release its in-flight state on an unanticipated throwable (open)

Source: observation from the orchestrating session of plan `jwks-unknown-kid-refresh` (PR #430). Found by the security re-check. NOT fixed in this plan.

`RetryingJwksLoader.runRefresh` had a weakness that this plan fixed: its in-flight state was not released in a `finally` block, so a throwable the method did not anticipate would leave the state set.

`RetryingJwksLoader.runRetry` has the same weakness and was left as it is. Its in-flight state is likewise not released in `finally` on an unanticipated throwable.

## Candidate lesson

Open defect, to be scheduled: release the in-flight state of `runRetry` in a `finally` block, the way `runRefresh` now does, and add a test that makes the delegate throw an unanticipated throwable and asserts a later retry can still start.

The consequence of the stuck state in `runRetry` was not analysed in this plan. By analogy with `runRefresh` the expected effect is that no further retry starts for that loader, but that is an inference, not a finding.

General form: when a concurrency defect is fixed in one method, check every sibling method in the same class that holds the same kind of state before closing the fix. Here the sibling was found only by the later security re-check, after the fix had been written for one method.

## Impact

Unfixed in `main` as of commit c013781d. Severity not assessed.
