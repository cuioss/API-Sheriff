envelope_version=1
sender_type=plan
sender_id=plan-v02-17-lessons-into-source
epic=api-sheriff-0-2-0
kind=landing
created=2026-08-09T19:10:28Z

## What landed

PR #200 — merged through the merge queue at `95dd566`. Plan `plan-v02-17-lessons-into-source`
("lessons into source") landed 6 deliverables plus 4 review-fix tasks.

The plan's purpose was to fold the epic's accumulated lessons back into the project's own
source-of-truth documents rather than leaving them in the lessons corpus. The delivered footprint
is documentation plus CI-workflow wiring: `CLAUDE.md`, `doc/development/sonar-quality-gate.adoc`,
`doc/development/declared-limit-assertion-coverage.adoc`, `demo-client/doc/playwright-suite.adoc`,
and a new OpenRewrite rewrite-report job in `.github/workflows/maven.yml`.

## PR reference

- PR: #200 (cuioss/API-Sheriff)
- Merge commit: `95dd566` (merge queue)
- Final reviewed-and-rebased HEAD: `e03b6a6`
- Review-bot coverage: CodeRabbit reviewed at `e7a5c7c`; 9 pr-comment findings filed, 6 fixed,
  the rest answered with a recorded rationale and resolved.

## Residuals the epic should track

### (a) No bot reviewed the final rebased HEAD `e03b6a6`

CodeRabbit's OSS-tier rate limit refused a re-review, and — per the known behaviour of that
limit — the refusal permanently consumed the range, so no re-trigger was available. The operator
granted a HEAD-bound `barrier-ask-override` on the explicit grounds that the rebase replayed
already-reviewed content byte-identically onto a disjoint upstream commit, so the diff the bots
had seen and the diff that merged are the same content.

This is an accepted gap, not a clean pass. The epic should carry it as such: the merged HEAD
itself never received a bot review, and the argument for merging was an equivalence claim about
the rebase, not evidence of review at that SHA.

### (b) The plan's own D1(a) premise was falsified during execution

Deliverable D1(a) was specced to document that reaching the OpenRewrite fixed point "is the
durable fix nobody has done". A full-reactor gate run during execution showed the repository IS
at the fixed point today. The executor escalated rather than landing the false claim, and the
text that shipped records the observed state plus the drift risk instead of the specced
assertion.

Consequence for the epic: any other staged plan whose spec asserts a fact about current
repository state inherited that assertion from the same authoring pass and may carry the same
staleness. The premise is worth re-establishing at execution time rather than trusted from
the spec.

## Signals

- Q-Gate pending at gate evaluation: 6
- Automated review: 1 (findings remediated in-run)
- Script-failure clusters: 0
