envelope_version=1
sender_type=plan
sender_id=plan-16-application-portal
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-22T17:13:33Z

# Candidate lesson: review bots caught portal doc over-claims and a substring (not directive-aware) Cache-Control assertion

- Signal source: automatic-review (6 pr-comment findings resolved `fixed`), triage decision.log 13:42:58
- Component: api-sheriff / integration-tests / doc (project-local); pre-submission-self-review (did not catch them)
- Suggested category: improvement

## What happened

PR #343 round 1 union triage: 18 pending (12 sonar-issue, 6 pr-comment), all fixed via TASK-18..20. The 6 CodeRabbit
pr-comment fixes were:

- Documentation over-claims (TASK-20) across doc/architecture.adoc, doc/user/portal.adoc, doc/configuration.adoc,
  doc/security-threat-model.adoc, ADR 0050 and LogMessages.adoc: reservation scope (OIDC reserved paths are matched on
  the configured OIDC host only, yet the docs said a prefix route "never sees" them on any host), error contract,
  cache class, boot checks, and Qute duplication.
- HtmlErrorPageIT relayed-origin-error control (TASK-19) checked `no-store` by substring instead of checking every
  Cache-Control directive.

pre-submission-self-review had run with 12 prose-contract candidates and returned 0 findings, so these slipped to the
review bots and cost loop-back iteration 1.

## Suggested corrective rule

When documenting a host-scoped or conditional mechanism (ReservedPathRegistry, cache class, boot refusals), state the
scope condition explicitly; never write "never / always" without the qualifier the code applies. Header assertions in
ITs must parse the directive list (split on commas, trim, compare tokens), not substring-match.
