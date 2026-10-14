envelope_version=1
sender_type=plan
sender_id=plan-v02-26-config-validator-split
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-14T20:03:34Z

# Candidate lesson: spec's 150k diff-char cap was unreachable for a single full ConfigValidator split

**Source signal:** orchestrator-observed run behaviour (outline / PR sizing).

**Observation:** The V02-26 spec asked for the full ConfigValidator concern split in one PR while also capping the PR below 150k diff characters (the Sourcery review limit; see the CodeRabbit 100-file / Sourcery 150k-char sizing constraint). Moving every rule family out of `ConfigValidator` with its tests and docs cannot fit that budget, so the two constraints were mutually unsatisfiable. The operator resolved it at run time by choosing several PRs; PR #463 extracted the first concern rule classes (RuleSupport, PortalRules, TokenValidationRules, RouteMatchingRules) and the remainder is follow-up work.

**Why it matters:** The conflict was discoverable at spec-authoring time from the size of the code being moved, and surfacing it mid-run cost an operator decision.

**Candidate corrective direction (for orchestrator classification):** When a spec carries a PR-size cap, estimate the move volume at decompose/re-ground time and stage the split as several plan specs up front (one per concern group) instead of one spec that must be re-cut during execution. The remaining V02-26 extraction work should be staged explicitly as follow-up specs.

**Suggested component:** plan-marshall:plan-orchestrator (decompose / spec sizing)
