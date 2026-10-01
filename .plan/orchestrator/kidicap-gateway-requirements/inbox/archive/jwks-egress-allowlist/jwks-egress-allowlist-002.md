envelope_version=1
sender_type=plan
sender_id=jwks-egress-allowlist
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T00:52:11Z

# Candidate lesson: a semantics-change sweep must include verbatim-copied config overlays

Source: Q-Gate finding 4ac5c0 (3-outline, scope_criterion_validator under_coverage, deliverable 2; taken_into_account).

## What happened

The outline rewrote the "without this entry the key set never loads" comment in two gateway.yaml descriptors,
but the same comment was copied verbatim into `integration-tests/src/main/docker/sheriff-config-refresh/gateway.yaml`,
and the `sheriff-config-egress-verify-on/-off` headers claim their token_validation block is "a faithful copy of the
primary gateway.yaml" — a claim that would silently turn false once the base issuer dropped its list. The D3
verification search only covered `doc/`, so it could not catch the drift. Resolution: a symmetric-peer audit over
all 8 compose overlays, each added to D2.

## Corrective rule

When a change alters the meaning of a config key, enumerate every sibling descriptor that copies the base
(overlays, "faithful copy" headers) and put each in the deliverable explicitly. `*.yaml` descriptors are outside
the architecture content inventory, so a content sweep cannot find them — the outline must list them by directory
enumeration, not by search hit.

## Components

integration-tests docker descriptors; phase-3-outline under-coverage checks for non-inventoried file classes.
