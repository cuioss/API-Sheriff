envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:34Z

component=plan-marshall:manage-architecture
category=anti-pattern
source=script failure cluster plan-marshall:manage-architecture:architecture (work log 49f802)

# architecture search rejected an invocation with --plan-id placed before the verb

## What happened

During the finalize security audit (2026-10-03T20:38:29Z) the call `architecture.py --plan-id plan-v02-10-per-client-tls-trust search --content --pattern pipeTo` failed with exit_code=2, failure_kind=argparse_rejection.

## Rule

Do not copy the `ci` router's "--plan-id before the verb" placement to other scripts. Look up the `--plan-id` position, and whether the script accepts the flag at all, in each script's canonical-invocation block. Run `--help` when unsure.

## Note

This was a single rejection that did not block the audit. It belongs to the known recurrence pattern of a flag placed at the wrong level.
