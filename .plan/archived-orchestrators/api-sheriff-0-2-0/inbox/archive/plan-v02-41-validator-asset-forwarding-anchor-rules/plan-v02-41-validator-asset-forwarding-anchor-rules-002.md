envelope_version=1
sender_type=plan
sender_id=plan-v02-41-validator-asset-forwarding-anchor-rules
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-18T12:15:49Z

# Candidate lesson: move-only extraction re-triggers Sonar new-code rules on moved methods

Source: run fact reported by the finalize dispatcher (PR #489, squash commit 3a252fca). Project-scoped
(API-Sheriff Sonar gate), not a plan-marshall tooling lesson.

## Observation

The plan extracted AssetDefaultsRules, ForwardingRules and AnchorRules out of ConfigValidator as a
verbatim move. SonarCloud then raised java:S3776 (cognitive complexity) and java:S135 (more than one
`continue` in a loop) on a moved method. The code was unchanged — the finding appeared only because the
method now lives in a NEW file, and Sonar's new-code period attributes every line of a new file as new
code, so pre-existing debt in the moved body becomes a gate-blocking new-code finding.

Fix in-run: merged the two `continue` guards into one condition. That one-line fix forced a loop-back
that re-ran the whole verify/review chain (see the separate tooling candidate on verdict_inputs).

## Candidate rule (for orchestrator classification)

For a move-only extraction (decomposition refactors such as the V02-* ConfigValidator / GatewayEdgeRoute
splits), treat every moved method as new code for Sonar: before or during execute, check moved methods
for S3776 / S135 / similar complexity rules and fix them in the same deliverable, rather than discovering
them from the PR's Sonar analysis after the review chain has closed. Cheapest at outline time: list
"Sonar new-code clean-up of moved methods" in the extracting deliverable's scope.

Recurrence risk: high within this epic — several remaining staged plans are further extract-class
refactors of large validators/routes.
