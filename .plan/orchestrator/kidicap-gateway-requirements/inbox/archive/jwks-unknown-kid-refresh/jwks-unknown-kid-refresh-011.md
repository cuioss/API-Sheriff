envelope_version=1
sender_type=plan
sender_id=jwks-unknown-kid-refresh
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T15:30:23Z

component=api-sheriff:gateway-auth
category=improvement
created=2026-10-12

# token-sheriff 0.9.6 JwksLoader cannot list its key ids, so the refresh publish rule stays approximate

Source: observation from the orchestrating session of plan `jwks-unknown-kid-refresh` (PR #430). Operator decision recorded.

The unknown-key-id refresh publishes a refreshed key set only when that set holds the key id that triggered the refresh. An exact rule would compare the key ids the old and the new key set hold. That rule cannot be written: `JwksLoader` in token-sheriff 0.9.6 exposes no way to list or compare the key ids a loaded key set holds.

## Residual

Invented key ids can delay pick-up of a genuine rotation until the background refresh. This is the residual documented with the change (ADR-0063, threat model); the mechanics are stated there and are not re-derived here.

## Decision

The operator chose to ship with this residual documented, and to ask upstream (token-sheriff) for a key-id accessor.

## Candidate lesson

Two follow-ups are owed:

- An upstream request to token-sheriff for a key-id accessor on `JwksLoader` or its loaded key set.
- Once a release carries it, replace the "holds the triggering key id" publish condition in `RetryingJwksLoader` with the exact rule, and remove the residual from the documentation.

Whether either is tracked anywhere outside this message was not checked by this step.

General form: when a rule has to be approximated because a library hides the state the exact rule needs, record the approximation as a residual and file the accessor request alongside it. Otherwise the approximation becomes permanent by default.

## Impact

Delayed pick-up of a genuinely rotated key while invented key ids are being sent, until the background refresh runs.
