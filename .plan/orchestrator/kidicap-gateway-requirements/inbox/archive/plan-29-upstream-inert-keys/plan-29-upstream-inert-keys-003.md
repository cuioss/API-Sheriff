envelope_version=1
sender_type=plan
sender_id=plan-29-upstream-inert-keys
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-11-09T13:04:35Z

# Candidate lesson: outline extended a deliverable beyond the request's listed docs without recording operator confirmation

Source record: Q-Gate finding 04c35d (phase 3-outline, severity warning, resolution accepted).

## What happened

Deliverable 3 edited doc/architecture/terminal-actions-and-protocols.adoc (Attempt timeout pointer, :40-43)
and doc/features-analysis.adoc (Tier 2 row, :134-139). Neither was among the request's listed docs, and the
clarified request said to STOP if the work reached beyond the config model, schema, tests and listed docs.
Both edits were justified on the merits (a dangling pointer once D3 trimmed Implementation Deviations; a row
claiming per-route timeout options that do not exist), but the outline only said "see the operator question"
and recorded no answer. The operator confirmed inclusion at the orchestrator review.

## Candidate rule

When an outline adds files outside a request's explicit scope fence, it must either record the operator's
answer in the outline or drop the files; a pointer to a pending question is not a resolution.

## Note for classification

This was caught and resolved at the outline Q-Gate as designed, so it may be a confirmation that the gate
works rather than a defect. A possible improvement is for the outline author to surface the scope-fence
question before writing the deliverable rather than leaving it to the gate.
