envelope_version=1
sender_type=plan
sender_id=plan-16-application-portal
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-22T17:13:05Z

# Candidate lesson: IT routes under /proxy/* reach go-httpbin's /anything echo base, so origin-status controls need an HTTPBIN_ROOT route

- Signal source: orchestrator-tier verify -Pintegration-tests, decision.log 10:57:30 (220/221 green)
- Component: integration-tests (project-local; HtmlErrorPageIT and any IT needing a real origin status code)
- Suggested category: improvement (test-fixture knowledge)

## What happened

HtmlErrorPageIT.relayedOriginErrorPassesThrough expected 503 and got 200. The fixture requested /proxy/status/503, but
that route goes through httpbin-proxy whose UPSTREAM alias carries the `/anything` echo base, so go-httpbin echoed the
request with 200 instead of returning 503. The pass-through assertions held; only the fixture was wrong. TASK-16 was
re-dispatched to route the control through an HTTPBIN_ROOT-based route to /status/{code} (new endpoints/origin-headers.yaml).

## Suggested corrective rule

An IT that needs the origin to return a specific status or header must use a route whose upstream is HTTPBIN_ROOT
(e.g. /status/{code}), never the /proxy/* routes, which always echo 200 via /anything. Good candidate for an
architecture hint on the integration-tests module.
