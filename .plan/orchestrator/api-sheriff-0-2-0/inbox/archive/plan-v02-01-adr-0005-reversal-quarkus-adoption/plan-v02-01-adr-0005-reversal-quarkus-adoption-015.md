envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:53Z

# The formatter's inferred import spacing flipped for the whole tree

Source: quality-gate runs during the split of PR #409, as established by the orchestrating session.

What happened: The pre-commit profile orders imports with OpenRewrite, which infers the blank-line style between import groups from the code base. From part 2 of the split on, it inferred one blank line where about 282 files had two. Every quality-gate run then rewrote those files, and every commit had to revert them first. It was resolved by committing the rewrite once in its own PR (#415).

Candidate rule: A formatter whose style is inferred from the code base can flip for the whole tree when a change shifts the majority. When a gate starts rewriting hundreds of files the branch did not touch, do not revert on every commit: commit the rewrite once in a separate PR, or pin the style explicitly so it is no longer inferred.
