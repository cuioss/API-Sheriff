envelope_version=1
sender_type=plan
sender_id=route-header-matcher-defects
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-23T20:57:04Z

# Candidate lesson: sweep all doc siblings on the first wording-fix review comment

**Source signal**: automated-review remediation (CodeRabbit, PR #346), three loop-back rounds

## What happened

CodeRabbit flagged a "case-insensitive" claim about header-name matching. The fix corrected only the cited location. Subsequent rounds then flagged the same inaccurate claim in sibling locations one surface at a time: first user docs (doc/configuration.adoc, doc/user/endpoint-routes.adoc), then Javadoc, then the ADR. Each round cost a push, a re-review wait and a triage pass. The accurate statement is "compared after Locale.ROOT lower-casing (case-insensitive for ASCII names)", not general case folding.

## Candidate rule

When a review comment corrects a semantic claim (not a typo), treat the claim as the unit of fix, not the cited line: before pushing, run a content sweep (`architecture search --content`) for the claim's phrasing across docs, Javadoc, ADRs and README, and fix every sibling in the same commit. Record the sweep in the reply so the reviewer sees the scope.

## Suggested component

plan-marshall review-comment triage / fix workflow (automatic-review fix path) — generalisable, not project-specific.
