envelope_version=1
sender_type=plan
sender_id=plan-sec-03-pending-authorization-store-fairness
epic=security-hardening-tier-1
kind=candidate-lesson
created=2026-11-09T11:07:44Z

component=plan-marshall:manage-execution-manifest
category=improvement
title=verdict_currency classify always returns invalidated, so every head-dependent step re-fires after each commit

# verdict_currency classify always returns invalidated, so every head-dependent step re-fires after each commit

## Observation

In plan-sec-03-pending-authorization-store-fairness the phase-6-finalize re-entry check consulted `phase-6-finalize:verdict_currency classify` on every HEAD advance. It returned `verdict: invalidated` with `reason: verdict_inputs_undeclared` every time, because none of the head-dependent settle-band steps declares a `verdict_inputs` surface. Every head-dependent step was therefore re-fired after each loop-back or settle commit.

Measured cost in this one plan: `finalize-step-simplify` and `finalize-step-security-audit` each fired 15 times, `functional-review` and `code-review` 7 times each, `pre-push-quality-gate` 6 times. Most of those re-fires re-confirmed an unchanged verdict on a diff that did not touch their inputs.

## Why it matters

- The classifier is fail-closed by design (correct), but with zero adopters the mechanism buys nothing - the "preserved" path is unreachable in practice.
- The cost compounds with the per-source loop-back budget: each admitted round re-runs the whole settle band, including the two most expensive dispatched reviews.

## Suggested correction

- Declare `verdict_inputs` on the head-dependent steps where the superset bar can honestly be met (verdict-currency.md names which families must not declare one), starting with the steps whose re-fire count dominates.
- Have `refire-report` surface `verdict_inputs_undeclared` as the dominant re-fire cause so the gap is visible per plan.
