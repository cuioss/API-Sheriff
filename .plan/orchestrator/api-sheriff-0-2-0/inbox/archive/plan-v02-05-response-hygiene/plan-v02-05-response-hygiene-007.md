envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:38:56Z

# IT probe route assumed exclusive but shared with concurrent suites

**Surface:** API-Sheriff integration-tests (circuit-breaker keyed routes)
**Signal:** Q-Gate record c63827 — 3-outline, warning, taken_into_account
**Plan:** plan-v02-05-response-hygiene

## Observation

The outline picked `/proxy/untrusted` for the new `ErrorContractIT` 5xx probe on the premise that its breaker key is "dialled by no other suite on that instance". False: `HtmlErrorPageIT` dials the same route concurrently on the primary instance (its Javadoc states the breaker counts every suite's refusals), and `UpstreamHostnameVerificationIT` uses it on another instance. The route may therefore answer the breaker's 503 instead of 502.

Resolved by making the 5xx assertion accept 502 or the full-signature open-circuit 503 (Retry-After 5, upstream problem type) and fail a bare 503, following the `HtmlErrorPageIT` precedent.

## Candidate rule

Before claiming an IT route is exclusive to one suite, search every IT for the path; breaker-keyed routes shared across concurrent suites must tolerate the gateway-originated open-circuit 503 with its full signature.
