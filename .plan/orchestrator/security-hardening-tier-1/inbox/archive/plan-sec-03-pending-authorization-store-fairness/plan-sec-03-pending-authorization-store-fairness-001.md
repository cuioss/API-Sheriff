envelope_version=1
sender_type=plan
sender_id=plan-sec-03-pending-authorization-store-fairness
epic=security-hardening-tier-1
kind=candidate-lesson
created=2026-11-09T11:07:32Z

component=plan-marshall:phase-6-finalize
category=bug
title=Review agents call a documented review_completeness roster verb that does not exist

# Review agents call a documented review_completeness roster verb that does not exist

## Observation

During phase-6-finalize of plan-sec-03-pending-authorization-store-fairness, the dispatched review agents followed their workflow docs and invoked the `review_completeness roster` verb to obtain the required and optional review-bot roster. The call was rejected by argparse with exit code 2: the verb is documented but not registered.

The agents fell back to reading `required_bots` / `optional_bots` from the step-params snapshot (`manage-execution-manifest step-params get --phase 6-finalize --step-id automatic-review`), which carried the correct values, so the run was not harmed - but only because the agents improvised a fallback the doc does not sanction.

## Why it matters

- A documented verb that argparse rejects is the verb-paraphrase / doc-drift class: every review dispatch pays a failed call and then improvises.
- The "never invent script subcommands" rule tells agents to quote verbs verbatim from docs - here the doc itself is the source of the invented verb.

## Suggested correction

- Either implement `review_completeness roster` or remove it from the review workflow docs and name `step-params get` (the snapshot that already carries `required_bots` / `optional_bots`) as the sanctioned roster read.
- Add the doc's invocation to the `manage-invocation-invalid` plugin-doctor accept-set check so a documented-but-unregistered verb fails the quality gate.
