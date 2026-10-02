envelope_version=1
sender_type=plan
sender_id=route-header-matcher-defects
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T20:56:55Z

# Candidate lesson: review-fix commits can re-introduce validator/runtime semantic drift

**Source signal**: automated-review remediation + finalize security-audit re-fire (plan route-header-matcher-defects, PR #346, merged f6067588)

## What happened

A CodeRabbit-driven fix added a boot-time ConfigValidator check refusing contradictory same-name header matchers in one route. The validator compared header names with `equalsIgnoreCase`, while the runtime (`RouteMatcher.from`) normalises names with `toLowerCase(Locale.ROOT)`. The two notions of "same name" differ (e.g. non-ASCII case folding), so the validator and the runtime disagreed about which matchers collide. The mismatch was introduced by the review-fix commit itself and was only caught by the finalize security-audit re-fire, not by the review loop or the tests written for the fix.

## Candidate rule

When a validator guards a runtime behaviour, it must reuse the runtime's normalisation function (or a shared helper), never a re-implemented equivalent. A review-fix that adds a validation rule needs a parity test that drives the same inputs through both the validator and the runtime matcher.

## Suggested component

Project-local (api-sheriff config validation / routing); possibly generalisable as a plan-marshall review-fix guidance item (re-run security/semantic audit after review-fix commits that add new logic).
