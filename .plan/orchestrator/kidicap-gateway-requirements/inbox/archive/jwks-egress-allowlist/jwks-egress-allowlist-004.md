envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:52:28Z

# Candidate lesson: outline listed a file as "expected to mutate" on a false-positive sweep hit

Source: Q-Gate finding 2ec8b6 (3-outline, triage, severity warning; taken_into_account).

## What happened

Deliverable 3 listed `doc/technical_aspects.adoc` under "Files expected to mutate", claiming the sweep found the old
egress semantics there. It did not: the file has no `egress` / `allowed_egress_hosts` / `secureDefault` hit; its only
"secure default" match (line 101) describes `ResolvedForwarding.empty()` in the forwarded-header section. Conversely,
`doc/quality-report/test-quality.adoc` did need a change, but for a different reason than stated — the real target was
TST-3 ("JWKS SSRF egress guard: no runtime refused-egress test") closed by `JwksEgressMismatchIT`, not the TST-6 hit.

## Corrective rule

A generic phrase match ("secure default") is not evidence a file states the changed semantics. Before placing a file
in the mutate set, confirm the hit is about the changed key/behaviour, and record the actual per-file rationale
(which claim, which line) so the executor rewrites the right statement instead of hunting for one that does not exist.

## Components

phase-3-outline affected-file derivation; doc/ tree.
