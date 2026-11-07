envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:05:57Z

# Test task and implementation task of one deliverable sat in different modules

Source: quality-check finding 0c0696 (plan phase, accepted in the run).

What happened: For deliverable 4 the test task listed a file in the integration-tests module while its sibling implementation task listed only api-sheriff paths. The check flagged the mismatch. It was intentional: the integration-tests file calls a method overload the implementation task removes, so its edit has to land together with the removal, and the deliverable is verified with a build of integration-tests and its dependencies. Nothing in the task list said so, which is why the check could not tell intent from mistake.

Candidate rule: When removing a method, list its callers in other modules in the same deliverable and write one sentence in the outline that says the cross-module pairing is intended and which build verifies it.
