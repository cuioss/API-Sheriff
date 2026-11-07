envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:20:27Z

# Outline planned a per-case stream reset that a governing ADR forbids

- Source: Q-Gate finding d17e81 (phase 3-outline, resolution taken_into_account)
- Component: api-sheriff edge response ends (ResponseEnds, GatewayEdgeRoute); ADR-0066

## Observation

Deliverable 1 of the outline planned to reset an admission-refused HTTP/2 stream with a
dedicated error code. ADR-0066 already decides that every response end on the edge passes one
seam with one reset code and explicitly rejects per-case marks. The outline neither reconciled
that nor planned an ADR amendment.

## Outcome

The operator kept the ADR-0066 rule: the per-case code was dropped, admission refusals stay on
the one end seam, and ADR-0060 Amendment A2 records why the alternative was not adopted.

## Candidate rule

Before an outline plans a change to how a response or stream ends, check the ADRs that govern
the end path (ADR-0066 here) and either stay inside them or plan the amendment as a deliverable.
