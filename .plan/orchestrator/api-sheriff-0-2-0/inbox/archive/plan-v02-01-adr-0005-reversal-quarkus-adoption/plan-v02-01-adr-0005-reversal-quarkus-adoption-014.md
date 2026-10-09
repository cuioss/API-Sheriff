envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:48Z

# The plan's single PR was too large for both review bots and had to be split after the fact

Source: automated review of PR #409, as established by the orchestrating session.

What happened: The plan produced one PR with 164 changed files. CodeRabbit refused it (limit 100 files) and Sourcery refused it (limit 150,000 diff characters). The PR was split after the fact into three sequential PRs against main: #410, #412 and #409. CI in this repository triggers only on PRs against main, so the parts could not be stacked on each other and each had to wait for the one before it to merge.

Candidate rule: Size a plan's PR under the reviewers' limits at outline time: at most 100 changed files and at most 150,000 diff characters per PR. When the outline's declared file count exceeds that, plan the split into sequential PRs against main then, with a commit boundary each part can build and pass on its own.
