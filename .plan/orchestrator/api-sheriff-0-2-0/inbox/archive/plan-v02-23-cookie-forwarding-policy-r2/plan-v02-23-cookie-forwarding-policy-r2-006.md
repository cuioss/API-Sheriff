envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:17Z

component=api-sheriff
category=bug
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=Q-Gate 5-execute findings 940e6e, 125737 (accepted, environmental)

# HealthProbeTest hard-codes 127.0.0.1:9000 and fails when a foreign process holds the port

## What happened

HealthProbeTest.shouldReturnOneWhenPortRefusesConnection and shouldReturnZeroWhenPortAccepts
(api-sheriff/src/test/java/de/cuioss/sheriff/gateway/HealthProbeTest.java, ~lines 97 and 110)
failed during module tests because 127.0.0.1:9000 was held by a foreign build on the machine. The
failure reproduced in isolation; an isolated re-run on the unchanged tree later passed 13/13. The
findings were accepted as environmental, but they cost a triage round on an unrelated plan.

## Why it matters

A fixed well-known port makes the test depend on machine state. Port 9000 is a common default for
other tooling, so the collision will recur.

## Proposed rule / fix

- Make HealthProbeTest allocate an ephemeral port (bind to port 0 on 127.0.0.1 for the accepting
  case) and use the UnreachablePort fixture for the refusing case, instead of a fixed 9000.
- If the probe under test must read a configured port, inject the allocated port into its config.
