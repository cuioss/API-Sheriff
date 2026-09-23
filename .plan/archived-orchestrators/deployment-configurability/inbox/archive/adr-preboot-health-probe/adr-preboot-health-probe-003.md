envelope_version=1
sender_type=plan
sender_id=adr-preboot-health-probe
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-04T07:12:25Z

## Candidate: a `required_bots` token that matches no `bot_kind` makes the participation quorum unconvergeable (repo-wide, still owed)

**Source signal**: surfaced during `plan-marshall:automatic-review` (the step behind `signal_automated_review_count`), recorded in this plan's decision.log. Scope is repo-wide — it affects every plan in this repository, not just this one.

**Observation**

`marshal.json`'s `plan.phase-6-finalize` step-params for `plan-marshall:automatic-review` carried:

```
required_bots = "coderabbit,cuioss-review-bot"
```

`cuioss-review-bot` is the **author login**, not the registry `bot_kind`. `automatic-review/standards/pr-agent.md` declares `bot_kind: pr-agent` with `author_login: cuioss-review-bot`, and no `cuioss-review-bot.md` registry doc exists. A configured token that matches no `bot_kind` can never resolve, so the reviewer is classified `absent` forever and the participation quorum can **never** converge by awaiting — the loop-back ceiling is burned and the run dead-ends at the barrier rather than failing with a diagnosable cause.

**Root cause**

Commit `1c7308c` ("chore(config): rename the pr-agent reviewer token to cuioss-review-bot") renamed the config token to the login while the registry key stayed `pr-agent`. The project `CLAUDE.md` still documents the correct value (`coderabbit,pr-agent`), so `marshal.json` is the side that drifted — the two now disagree, and the documented one is right.

**Disposition in this run**

Patched **plan-locally only**, via `step-params set`. The repo-wide fix to `marshal.json` is still owed.

**Candidate rule for the orchestrator to judge**

The failure is silent and structurally unconvergeable, which is the worst combination: a token naming a login rather than a `bot_kind` produces an `absent` classification that is indistinguishable from a bot that genuinely did not review. Candidate remedies, in increasing strength: (a) fix `marshal.json` back to `coderabbit,pr-agent`; (b) validate `required_bots` tokens against the live registry `bot_kind` set at config-write or at step entry, so an unresolvable token is rejected at the point of configuration instead of at an exhausted barrier; (c) have the barrier distinguish "configured bot resolves to no registry entry" from "configured bot did not review", since only the second is worth awaiting.

**Classification deferred** — the plan transmits this candidate; it makes no global-vs-epic judgement.
