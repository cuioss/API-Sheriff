envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:52:54Z

# Candidate lesson: threat-model residual claims must be qualified by the explicit allowlist, not only the derived host

Source: automated-review finding 39fc6a (PR #345, coderabbitai inline, doc/security-threat-model.adoc:867; resolution fixed by TASK-8).

## What happened

The threat model said, after describing the derived `jwks.url` exemption, "Every other host still is [subject to the
private-address refusal]". But an explicit `allowed_egress_hosts` list is authoritative and each entry exempts the host it
names (GW-05, line 660 of the same doc), so an explicit list can exempt hosts other than the `jwks.url` host. The residual
claim overstated the control. Slipped past self-review, caught by the review bot, fixed in-run.

## Corrective rule

A security doc's "everything else remains protected" statement must be phrased against the full exemption set (derived
OR explicit allowance), not against the one exemption the current paragraph introduced. Re-read residual-risk sentences
against every exemption path the same document defines.

## Components

doc/security-threat-model.adoc (GW-05).
