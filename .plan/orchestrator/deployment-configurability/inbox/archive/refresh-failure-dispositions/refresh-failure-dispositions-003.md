envelope_version=1
sender_type=plan
sender_id=refresh-failure-dispositions
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-17T03:49:23Z

component=plan-marshall:automatic-review
category=improvement

# CodeRabbit on cuioss allows one included review per hour and pauses auto-review, so every review-driven loop-back costs at least an hour

## What happened

PR #314 went through six finalize loop-backs. Five of them were driven by CodeRabbit findings, typically one new actionable per round on the concurrency code in `TokenRefreshCoordinator`. The run showed three things about CodeRabbit that shape finalize wall-clock:

1. **Hourly budget.** Every CodeRabbit review summary ended with "Your plan provides up to 1 included review per hour; 0 remain after this review." The reviewed rounds landed at roughly 17:15, 19:19, 23:57, 01:08 and 02:37 UTC, so a fix push inside the hour waits for the budget, not for the bot.
2. **Auto-review pause.** CodeRabbit posted a pause notice partway through. After that a push did not trigger a review; one had to be requested with `@coderabbitai review`. The command's reply says it "is applicable only when automatic reviews are paused".
3. **Incremental only.** The same reply states that CodeRabbit "does not re-review already reviewed commits". Its "Review finished" acknowledgement is an issue comment with no feedback, and triage must not read it as a review verdict.

The loop-back count went past `max_iterations=5` (iteration 6, a test-only fix), which the run logged as a deviation.

## Rule

- Budget a review-driven loop-back at 60 minutes or more of wall-clock. Size `re_review_await_timeout_seconds` and the expectations for unattended runs accordingly. A 600 s re-review wait inside the hour ends in a timeout or a decline, not a review.
- Once the pause notice appears, every fix push needs an explicit `@coderabbitai review`. Waiting for an automatic re-review that will never come is wasted wall-clock.
- On concurrency-heavy diffs, expect CodeRabbit to surface one new finding per incremental round. Batch related concurrency hardening before the first push, or accept that `max_iterations=5` may not be enough.

## Coverage note

The existing auto-memory covers quota and rate-limit refusals (sleep 90 minutes or more). It does not cover the one-per-hour included-review budget, the pause-then-command mechanics, or the incremental-only behaviour.

## Evidence

- Plan refresh-failure-dispositions, PR #314. CodeRabbit review bodies 554ebf, 96e367, 3e0388, 401790; command acknowledgements faf369, ff5159. Decision log: loop-back iteration 6 deviation.
