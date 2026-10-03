envelope_version=1
sender_type=plan
sender_id=plan-19-session-fallback-on-bearer
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-24T21:10:22Z

# Candidate lesson: outline missed a user doc that a behaviour widening makes stale

**Source**: Q-Gate finding `65afa1` (phase 3-outline, severity warning, resolved in-run as taken_into_account).
**Plan**: plan-19-session-fallback-on-bearer (PR #356, merged as 1fa648d)

## What happened

Deliverable 1 widened `auth.token_relay` to cover the SESSION branch of a `session_fallback`
route, which is a `require: bearer` route. Deliverable 3 recorded a mediated bearer on both
branches of that route. Deliverable 7 (docs) listed configuration.adoc, user/bff-session.adoc,
security-threat-model.adoc, architecture.adoc, LogMessages.adoc, the new ADR and user/anchors.adoc.
It did not list `doc/user/endpoint-routes.adoc`. That file says in four places that token_relay "acts only on
require: session" and "has no effect on bearer or none routes", and that the mediated
Authorization applies to "session routes" only. All four statements became false.

The Q-Gate caught it. The outline was amended: the file was added to D7 as write-replace, and a
content search confirmed that these four sites were the only mentions.

## Candidate rule

When a deliverable widens where an existing config key or an existing behaviour applies, the outline
should run a content search for that key's current scope statements across `doc/**`, and not only
across the docs the author already expects to change. Every hit that states the old scope belongs
in the docs deliverable's affected files.

## Related

The same class of error came back at PR review: CodeRabbit raised three doc-accuracy findings
on `doc/user/endpoint-routes.adoc` and `doc/configuration.adoc`, all about token_relay and
mediated-bearer scope. The scope sweep caught the files but not every qualifier. See the separate
automated-review candidate.
