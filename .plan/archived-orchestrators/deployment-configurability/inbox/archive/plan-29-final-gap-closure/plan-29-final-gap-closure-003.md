envelope_version=1
sender_type=plan
sender_id=plan-29-final-gap-closure
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-23T15:12:10Z

# Candidate lesson: loop-back churn from stale required-bot participation

Source signal: automatic-review step history (firing_count 5, two loop_back outcomes with
target 6-finalize). Plan-level loop_back_iteration reached 5 of 5.

## What happened

After the TASK-3 fix push (HEAD 927947c, later 32c20d1), automatic-review recorded
`loop_back` twice. The second time the leaf logged:

> participation guard: required bot cuioss-review-bot is participated_stale (issue_comment
> predates merge candidate 32c20d1); re_review_on_loopback=false so no re-review trigger
> posted; recording loop_back (target=6-finalize). Remedy is a re-review trigger, not a
> longer wait

PR-Agent (cuioss-review-bot) does not re-review on push by design: `.github/workflows/pr-agent.yml`
triggers only on opened/reopened/ready_for_review and on `/review` comments. With
`re_review_on_loopback=false`, each fix push leaves the required bot's participation stale,
and the loop only settles after a manual re-review trigger. Every such loop re-ran
pre-push-quality-gate, ci-verify, automatic-review and sonar-roundtrip and used up
loop-back iterations. By the end all 5 were spent and no headroom was left.

A related detail: the automatic-review leaf logged an uninformed "iteration 1" line because
it cannot read the persisted counter. The orchestrator had to correct it.

## Candidate rule

For a required review bot that does not re-review on push (like PR-Agent here), either
enable `re_review_on_loopback` or have the loop-back path post the bot's re-review trigger
(`/review`) itself. A participated_stale verdict is then answered in the same iteration
rather than by extra loop-backs. Consider also passing the persisted loop_back_iteration
into the automatic-review leaf so its log lines are accurate.
