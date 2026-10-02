envelope_version=1
sender_type=plan
sender_id=plan-16-application-portal
epic=kidicap-gateway-requirements
kind=candidate-lesson
created=2026-09-22T17:13:53Z

# Candidate lesson: marshal.json still sets build.queue.max_slots=2, which is ignored and logs a WARNING on every build

- Signal source: work.log [BUILD-QUEUE] WARNING, repeated on every build-server submit in this run (about 35 times)
- Component: project config (.plan/marshal.json) / plan-marshall:manage-build-server
- Suggested category: improvement (config hygiene)

## What happened

Every build logged:
"marshal.json sets build.queue.max_slots=2, which is NOT in effect: the build-slot cap is machine-global. The cap in
effect is 5 (source=default, path=~/.plan-marshall/marshalld/machine-config.json)."
The warning names the one-step remedy: `manage-build-server config migrate` (or `config set --max-slots N` and delete
build.queue.max_slots from marshal.json).

## Why it matters

The project believes it caps concurrent builds at 2 but the effective cap is 5. On a WSL host running native-image
and docker ITs, that difference is material. The repeated WARNING also buries real warnings in the work log.

## Suggested corrective rule

Run `manage-build-server config migrate` once (operator decision on the intended cap), which removes the dead key from
.plan/marshal.json. Consider having marshall-steward's health check flag a non-effective build.queue.max_slots.
