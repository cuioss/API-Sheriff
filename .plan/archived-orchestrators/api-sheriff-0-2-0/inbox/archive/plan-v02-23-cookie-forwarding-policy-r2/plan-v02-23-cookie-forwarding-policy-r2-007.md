envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:22Z

component=plan-marshall:phase-3-outline
category=improvement
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=finalize security audit rounds

# Security-relevant gaps in a security-feature plan surfaced only late, in finalize security audits

## What happened

For a plan whose subject is itself a security control (a cookie forwarding policy for the gateway),
the finalize security audits repeatedly found genuine gaps in the policy's coverage, each needing a
further fix-and-review cycle late in finalize. Details are intentionally not recorded here; they
are kept in the git-ignored local security notes.

## Why it matters

Each late finding re-opens the full review/CI/bot cycle. The gaps were of the kind an adversarial
"which inputs does this rule NOT cover" pass at outline time would have enumerated up front.

## Proposed rule / fix

- For plans that introduce or change a security control, run a security-expert lens during
  phase-3-outline that enumerates the input space the control must cover (edge-case names, empty /
  malformed values, every mode/variant of the deployment) and turns each into an explicit success
  criterion and test, rather than relying on the finalize audit to find them.
- Keep weakness details out of tracked artifacts (plan specs, PR text, inbox messages); reference
  the local security notes instead.
