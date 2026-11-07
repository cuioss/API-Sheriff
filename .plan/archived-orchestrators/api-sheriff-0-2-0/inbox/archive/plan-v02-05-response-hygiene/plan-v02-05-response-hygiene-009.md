envelope_version=1
sender_type=plan
sender_id=plan-v02-05-response-hygiene
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-27T17:39:05Z

# Deliverable dependencies not derived from cross-document links

**Surface:** `plan-marshall:phase-3-outline` / `plan-marshall:phase-4-plan` (deliverable dependency ordering)
**Signal:** Q-Gate record 81436b — 3-outline, warning, taken_into_account
**Plan:** plan-v02-05-response-hygiene

## Observation

Deliverable 7 added xrefs into content that only deliverable 8 creates (`doc/user/anchors.adoc` security-header guide, `doc/user/tls-edge.adoc` fingerprint residual note), yet D7 declared `depends: 1, 2, 3, 4, 5` and D8 `depends: none`. Task order could run D7 first, failing D7's own "every added cross-reference resolves" criterion. The orchestrator carried the fix into phase-4-plan (D7 tasks depend on D8 tasks) to avoid a third outline pass.

## Candidate rule

When a deliverable adds links to sections another deliverable writes, the linking deliverable must depend on the writing one. The outline should derive this from the deliverables' change entries (new anchors vs new xrefs) rather than rely on the author.
