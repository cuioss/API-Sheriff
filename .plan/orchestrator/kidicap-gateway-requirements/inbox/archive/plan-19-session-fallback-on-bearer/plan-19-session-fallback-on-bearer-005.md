envelope_version=1
sender_type=plan
sender_id=plan-19-session-fallback-on-bearer
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-24T21:10:49Z

# Candidate lesson: review bots caught doc/Javadoc claims the code does not implement

**Source**: automatic-review signal. The run fixed 7 review-bot findings through loop-back fix tasks
TASK-14 (Javadoc) and TASK-15 (docs): 4 CodeRabbit inline comments and 3 Sonar findings.
**Plan**: plan-19-session-fallback-on-bearer (PR #356, merged as 1fa648d)

## The CodeRabbit findings (all resolution=fixed)

- `438607` doc/architecture.adoc:119 said branch resolution happens "once per request by
  `AuthBranch.resolve`". In fact the edge CSRF gate and AuthenticationStage each call the resolver
  separately. The conclusion ("cannot disagree") still holds because the resolver is pure, but the
  mechanism was described wrongly. The same claim was in the AuthBranch Javadoc.
- `a65fa1` doc/configuration.adoc:1947 said `token_relay: false` "sets no Authorization header
  upstream". ForwardPolicyStage merges `set_headers` before the mediated bearer and does not
  restrict names, so a static Authorization still crosses. The same claim was in bff-session.adoc.
- `d100bc` doc/user/endpoint-routes.adoc:92 described the automatic Authorization injection on
  session routes and on both session_fallback branches as unconditional. In fact the session branch
  follows token_relay. The same claim was in the ForwardPolicyStage Javadoc.
- `abf974` doc/user/endpoint-routes.adoc:718 Endpoint scopes: the mapping went by `require` value only.
  It did not say that the bearer branch of session_fallback enforces the needed scopes (403
  insufficient_scope) while the session branch only requests them at login.

The Sonar findings (3) were fixed in the same loop-back. Their content is recorded in the plan's
Sonar findings, not reproduced here.

## Pattern

Every CodeRabbit finding was a prose claim about runtime mechanism or header contract that went
further than the code. The typical shape is an unqualified "always", "once" or "never" for a
behaviour that has a flag or a merge-order exception. Three of the four also appeared in a Javadoc,
so each one had to be fixed in two places.

## Candidate rule

When a deliverable documents a new cross-cutting behaviour (here: session_fallback x token_relay x
set_headers x scopes), the docs task should check each absolute claim against the implementing
class: who calls the resolver, the merge order, and which flag gates the behaviour. The same claim
in Javadoc and in adoc should be fixed together. The CLAUDE.md testing rule "a key that parses is
not a key that acts" has a documentation counterpart: "a claim that reads well is not a claim the
code implements".
