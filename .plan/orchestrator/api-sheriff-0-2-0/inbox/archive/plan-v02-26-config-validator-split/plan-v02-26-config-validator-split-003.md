envelope_version=1
sender_type=plan
sender_id=plan-v02-26-config-validator-split
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-14T20:03:08Z

# Candidate lesson: docs hand-copied the size and order of ConfigValidator.DEFAULT_RULES

**Source signal:** automated review (CodeRabbit, PR #463, finding 45a146), resolved `fixed` in-run.

**Observation:** The concern map in `doc/development/boot-validation-rules.adoc` and the summary row in `doc/development/README.adoc` stated a rule total ("51 rules / 16 concern classes") and 1-based `DEFAULT_RULES` positions. The authoritative list is `DEFAULT_RULES` in `ConfigValidator.java`; every added rule and every follow-up extraction (this plan is the first of several split PRs) shifts the count and positions, and nothing bound the docs to the code, so stale figures would pass CI.

**Fix applied:** The figures were removed. The map now names each rule's owning concern class and points at `DEFAULT_RULES` as the only record of count and order. No contract test was added: the rules are unnamed lambdas, so only a count could be bound.

**Candidate corrective direction (for orchestrator classification):** When a doc describes a code-held list (rule chains, registries, dispatch tables), name owners and point at the source rather than restating totals or positions, unless a contract test in the style of `DocumentedSetsContractTest` binds them. Relevant to the remaining ConfigValidator split plans, which will keep moving rules.

**Suggested component:** project-local (api-sheriff boot-validation docs)
