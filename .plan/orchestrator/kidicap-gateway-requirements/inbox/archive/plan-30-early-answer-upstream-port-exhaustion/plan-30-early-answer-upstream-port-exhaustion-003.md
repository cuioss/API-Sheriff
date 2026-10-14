envelope_version=1
sender_type=plan
sender_id=plan-30-early-answer-upstream-port-exhaustion
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-10-14T12:54:25Z

## Candidate lesson: a catch-all exception-drop handler shipped without a rationale for every path it covers

**Source**: review-bot finding 7bc9af (CodeRabbit `review_body` on PR #448, at reviewed commit 3757c48a), resolved `fixed` in-run (fix commit bc002caf).
**Plan**: plan-30-early-answer-upstream-port-exhaustion

### What happened

The upstream-request exception handler in `DispatchStage` (`api-sheriff/src/main/java/de/cuioss/sheriff/gateway/edge/DispatchStage.java`, `onUpstreamRequestFailure`) drops every `StreamResetException`. Its Javadoc justified the drop with one argument: Vert.x also fails the send future or the response stream with the same exception. That argument covers a pending send or a streaming response. It does not cover the forced-HTTP/2 (gRPC) path, where an upstream can reset the stream after the response has completed. CodeRabbit pointed out that such a late reset has no consumer, so the handler hides it silently.

### Resolution in-run

Only the Javadoc changed. It now gives a separate reason for the late reset: it fails nothing, because the response was already relayed. The handler still drops every `StreamResetException`. Logging the late reset at DEBUG, or installing the handler for HTTP/1.x only, was rejected: the second option would send every HTTP/2 upstream reset back to the Vert.x unhandled-exception ERROR log. No late-reset test was added, because the change is documentation only.

### Candidate rule

When a handler swallows a whole exception type, check the documented rationale against every protocol path the handler is installed on (HTTP/1.x, HTTP/2, and late or after-completion events), not only the path that motivated it. Name each case the drop covers and why each is safe.

Likely owning component: project-local (`api-sheriff` edge module). Possibly also a Java review-rule candidate for `pm-dev-java:java-core`.
