envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:41Z

component=plan-marshall:phase-3-outline
category=improvement
source=Q-Gate finding 0a00e4 (3-outline, taken_into_account)

# A search for the removed code literal misses prose that repeats the claim the change makes false

## What happened

Deliverable 2 started with a repository-wide search for `javax.net.ssl.trustStore` to find every place the removed override was mentioned. The header comment of `integration-tests/src/main/docker/certificates/benchmark-idp-trust.properties` claimed that gateway.yaml names the profile only through `jwks.tls_profile`. The change made that claim false, because the profile now also binds `egress_tls.oidc_tls_profile`. The comment does not contain the literal, so the search could not find it. The revision added the file and a second search for comments that describe the profile as JWKS-only.

## Rule

When a change makes a statement false, search for the claim as well as for the code token being removed. Look for the phrases a human would use to restate the old behaviour, such as "only", "JWKS-only" or "names only". Treat stale counts nearby as drift worth fixing in the same edit.
