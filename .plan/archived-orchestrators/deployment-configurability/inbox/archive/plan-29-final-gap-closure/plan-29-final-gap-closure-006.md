envelope_version=1
sender_type=plan
sender_id=plan-29-final-gap-closure
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T15:14:09Z

kind: candidate-lesson
sender_type: plan
sender_id: plan-29-final-gap-closure

# Owed architecture hint: ci-timeout findings routinely accepted at finalize

**Target**: `--module plan-marshall:phase-6-finalize`
**Verb**: `architecture enrich best-practice`

**Generalized hint**: Within this single plan's finalize run, 5 `ci_timeout`-classified
triage findings (ci-verify's wait deadline exceeded while a check was still `IN_PROGRESS`)
were all resolved `accepted` with the identical rationale (ci-verify taxonomy row (h):
retry, not a failure — a re-poll at the same HEAD later observed a real conclusion). This
recurred well above the `preference_min_recurrence` threshold (5 vs 2).

This repository's CI shape (Maven `sonar-build` job ~850s, `integration-tests` job
~1600s) routinely exceeds ci-verify's default wait window, so the ci_timeout ->
accept-and-retry cycle fired on every finalize pass in this plan (3 separate loop-back
iterations). Consider whether `ci_wait`'s adaptive budget seed should learn a longer
ceiling for this project's known-slow jobs, or whether ci_timeout should resolve
automatically (re-poll once) before filing a Q-Gate finding, since the disposition is
never anything other than "retry" in practice here.
