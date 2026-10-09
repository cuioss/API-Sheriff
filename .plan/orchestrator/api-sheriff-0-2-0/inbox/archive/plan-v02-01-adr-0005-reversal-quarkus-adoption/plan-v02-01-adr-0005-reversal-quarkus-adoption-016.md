envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:58Z

# The integration-test job was cancelled by its own timeout with every test green

Source: CI runs of the split PRs, as established by the orchestrating session.

What happened: The integration-test job ran for 34.8 minutes against a job timeout of 35 minutes. It was cancelled once by the timeout while every test in it had passed. The check showed red although nothing had failed.

Candidate rule: A job timeout with no margin produces a red check that is not a test failure. Keep the timeout clearly above the normal run time, and raise it in the same change that adds integration tests. When a required check is red, read whether it failed or was cancelled before looking for a broken test.
