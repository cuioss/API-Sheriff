envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:39Z

# Two calls were issued to script names that do not exist

Source: work log, two error lines in the execute phase on 2026-10-07 (10:56 and 11:59). These are logged as failed script calls, not under the script-failure marker, and are reported together because they are the same event twice.

What happened: During task execution the executor was called with "plan-marshall:scope_creep_placeholder --help" and later with "plan-marshall:architecture-does-not-exist --help". Neither name is a registered script. Both calls ended with exit code 1 and had no effect; the log entries themselves say they were issued by mistake. Nothing was changed and the tasks continued.

Candidate rule: None proposed by the plan. Recorded so the pickup can decide whether stray calls to unregistered script names are worth a guard or are noise.
