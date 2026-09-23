envelope_version=1
sender_type=plan
sender_id=adr-preboot-health-probe
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-04T07:12:31Z

## Candidate: the re-review head_sha check inspects only the `review` signal, so an `issue_comment`-publishing bot is reported as a false decline

**Source signal**: surfaced during `plan-marshall:automatic-review` (the step behind `signal_automated_review_count`), recorded in this plan's decision.log and in the `rereview-timeout-override` merge authorization on `status.json`. Producer-side matcher gap.

**Observation**

The re-review head_sha verification (`_references_head_sha`) inspects only the `review` signal. `pr-agent` publishes its re-review via a persistent `issue_comment`, so its review is classified `head_sha_verified=false` and reported as a **DECLINE** even when the comment body names the exact reviewed commit verbatim ("Review updated until commit `<sha>`").

In this run that produced a false `declined` escalation: `pr-agent` answered the `/review` trigger at 23:19:35Z naming commit `6330a46`, and CodeRabbit separately declared `coveredCommitId=6330a46 kind=reviewed` with no actionable comments — both required reviewers had verifiably reviewed the merge candidate, yet the barrier reported `participation_complete=false` with `unproven_bots=[pr-agent]`.

**The two paths disagree with each other**

The barrier's own producer — `github_pr fetch_findings` — resolves it correctly: it reported `reviewed_commit_sha=6330a46` and an empty `stale_participation_bots`. So one code path in the same pipeline already extracts the commit from the comment-shaped signal while the verification path does not. That internal disagreement is the strongest evidence this is a matcher gap and not a genuine stale review.

**Cost paid in this run**

The false decline consumed loop-back iterations to the ceiling (3/3). Because a barrier `fail_into_loopback` would then dead-end rather than re-converge, the run required a hand-verified `rereview-timeout-override` merge authorization, with the evidence read directly from the provider. That is a manual, judgement-bearing override standing in for a matcher that should have resolved automatically.

**Candidate rule for the orchestrator to judge**

Durable fix, either: (a) extend the comment-path matcher to scan the body for a HEAD reference — reusing whatever `fetch_findings` already does, rather than adding a second extractor; or (b) give `pr-agent` a review-shaped signal so the existing check applies unchanged. Option (a) is preferable if the two paths can be collapsed onto one resolver, since the defect here is precisely that two resolvers exist and only one is correct.

**Classification deferred** — the plan transmits this candidate; it makes no global-vs-epic judgement.
