envelope_version=1
sender_type=plan
sender_id=jwks-unknown-kid-refresh
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-12T15:29:11Z

component=api-sheriff:doc-security-threat-model
category=bug
created=2026-10-12

# Threat model said "one JWKS request" where the bound is one refresh that may span several HTTP attempts

Source record: review-bot finding 3ab006 (pr-comment, CodeRabbit review body on PR #430, outside-diff item on `doc/security-threat-model.adoc`, GW-07 bound, resolution fixed, fix task TASK-8, fix commit f77de777) of plan `jwks-unknown-kid-refresh`.

The opening sentence of the GW-07 bound said an unknown key id causes at most one JWKS request per issuer per window. The closing sentence of the same bullet, and ADR-0063, say something different: one refresh is one library load, and that load may span the HTTP client's bounded retries. The two statements in one bullet disagreed, and the stricter one was false as a statement about outbound requests.

## Resolution in this run

TASK-8 changed the sentence to "at most one JWKS refresh per issuer per window", the term the entry heading and ADR-0063 already use.

## Candidate lesson

A security bound must be stated in the unit the mechanism actually limits. The limiter here counts refreshes, not HTTP requests; a retrying HTTP client sits beneath it. Naming the bound in requests overstates the protection, and a reader sizing the load an attacker can induce on the identity provider would compute the wrong number. Where a threat-model entry and an ADR describe the same bound, use one term in both.

## Impact

Documentation only. The finding arrived as an outside-diff item in a review body, which carries no resolvable thread, so it is easy to miss when working only the inline comments.
