envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:52:46Z

# Candidate lesson: "absent" and "empty" list take the same derived path — docs must say both

Source: automated-review finding e4d60c (PR #345, coderabbitai inline, doc/configuration.adoc:2042; resolution fixed by TASK-7).

## What happened

The configuration reference stated that a hostless `jwks.url` aborts startup when `allowed_egress_hosts` is *absent*.
But `IssuerConfig.Jwks` normalizes absent to `List.of()` and `TokenValidatorProducer` derives on `isEmpty()`, so an
explicitly empty list takes the identical path. The field contract above the sentence already said "omitted or empty";
the startup-rule sentence contradicted it. Slipped past self-review, caught by the review bot, fixed in-run.

## Corrective rule

When absence is normalized to an empty collection in the config model, every doc statement about the "absent" case must
read "absent or empty" (and a schema/doc cross-check should grep for bare "absent"/"omitted" around the key after a
semantics change).

## Components

doc/configuration.adoc; api-sheriff config model (IssuerConfig, TokenValidatorProducer).
