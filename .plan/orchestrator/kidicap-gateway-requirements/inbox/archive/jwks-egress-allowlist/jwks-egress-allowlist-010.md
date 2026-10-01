envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:53:20Z

# Candidate lesson: finalize-step-simplify and the pre-commit quality gate fight over blank lines between import groups

Source: orchestrator observation 4 (this run).

## What happened

`finalize-step-simplify` removes a double blank line between Java import groups; the `verify -Ppre-commit` quality gate
(OpenRewrite / formatter recipes) re-inserts it. The two steps oscillate on the same line, producing format-only churn
after the gate — exactly the "a gate that exits 0 can still have changed your files" mechanism documented in
`doc/development/build-gate-discipline.adoc`.

## Corrective rule / suggested fix

The formatter owned by the pre-commit gate is the authority on import-group spacing in this repo; the simplify step must not
normalize whitespace the gate owns (or must run the gate's formatter after its edits and accept its output). Either teach
the simplify step to leave import-block whitespace alone for Java in cuioss projects, or record the gate's convention so the
simplifier matches it.

## Components

plan-marshall:phase-6-finalize (simplify step); project pre-commit profile (cui-quarkus-parent rewrite/format recipes); doc/development/build-gate-discipline.adoc.
