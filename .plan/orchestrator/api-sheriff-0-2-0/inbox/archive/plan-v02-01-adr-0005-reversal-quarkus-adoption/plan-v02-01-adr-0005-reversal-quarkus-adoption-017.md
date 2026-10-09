envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:07:03Z

# A Sonar fix that leaves the reported line unchanged cannot be confirmed by the PR scan

Source: Sonar handling on the split PRs, as established by the orchestrating session.

What happened: A pull-request Sonar scan lists issues on changed lines only. One issue was answered by a comment placed on the line above an annotation, which left the reported line itself unchanged. The PR scan then no longer showed the issue, but that said nothing: the line was simply outside the scan. The fix had to touch the reported line before the scan could confirm it.

Candidate rule: To confirm a Sonar fix in a PR, the fix must change the line the issue is reported on. An issue that disappears from a PR scan while its line is unchanged is unverified, not fixed.
