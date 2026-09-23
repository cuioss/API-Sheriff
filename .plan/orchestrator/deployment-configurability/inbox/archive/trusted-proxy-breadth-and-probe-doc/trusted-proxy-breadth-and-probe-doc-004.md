envelope_version=1
sender_type=plan
sender_id=trusted-proxy-breadth-and-probe-doc
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-05T00:11:05Z

## Candidate: neither review bot re-reviewed on its own after the fix push, for two different reasons

**Signal source**: dispatcher-supplied candidate material, adjacent to
`signal_automated_review_count` — the remediation cycle that followed finding `b8f5e6`.

**What happened**

After the fix push that resolved the CodeRabbit finding, the run needed **two separate manual
triggers** to get the branch re-reviewed, and the two had different causes:

1. **CodeRabbit** would normally re-review automatically on push, but this project's
   `re_review_on_loopback` is `false`, so the loop-back path did not request one. It had to be
   triggered explicitly.
2. **PR-Agent** does not re-review on push at all, by its own workflow design —
   `.github/workflows/pr-agent.yml` triggers only on `opened` / `reopened` / `ready_for_review`
   plus on-demand `issue_comment` commands. It needed a separate `/review` comment.

So one bot was quiet because of a plan-marshall setting and the other because of the bot's own
trigger configuration, and the two look identical from the outside: a pushed fix, and no fresh
review.

**Why it is candidate-lesson material**

The failure mode here is not "a bot was slow" — it is that **the absence of a re-review is
indistinguishable from a re-review that found nothing.** Both present as "no new comments after the
fix push". A run that reads that silence as approval merges over an unreviewed fix; the gate looks
green because nothing spoke, not because something passed. This is the same silence-versus-clean-
negative distinction that plan-marshall enforces on its own zero-valued reports, applied to the
review-bot surface.

That both required bots are affected — for unrelated reasons — is what raises this above a note.
`required_bots: coderabbit,pr-agent` means both gate the merge, and after any fix push *neither*
speaks on its own. The re-review obligation therefore has to be discharged explicitly on every
loop-back, and the two bots need two different explicit acts.

The generalisable shape: **a review gate that depends on a bot re-reviewing must establish that the
re-review actually ran, not merely that no comment arrived.** The per-bot completion state is
observable (`workflow-integration-github bot_completion`); the correct post-fix-push assertion is
"each required bot reports a completed review against the current head SHA", not "the unresolved
count is zero".

A secondary, cheaper observation: if `re_review_on_loopback: false` is the intended setting for this
project, then the loop-back path owes an explicit trigger for *every* required bot as part of the
loop-back, rather than leaving it to be noticed.

**Cross-plan relevance for the epic**

Every plan in `deployment-configurability` that takes a review loop-back hits this, and the bots'
configuration is project-level, so the behaviour is identical across the epic.

**Evidence**

- required bots: `coderabbit`, `pr-agent`; optional: `sourcery` (`.plan/marshal.json`)
- `re_review_on_loopback: false`
- `.github/workflows/pr-agent.yml` triggers: `opened`, `reopened`, `ready_for_review`,
  `issue_comment`
- fix push for finding `b8f5e6`; PR #267, merged as `558a38b`
