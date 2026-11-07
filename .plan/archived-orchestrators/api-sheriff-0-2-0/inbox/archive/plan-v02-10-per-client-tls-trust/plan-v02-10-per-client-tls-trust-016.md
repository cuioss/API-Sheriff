envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:58Z

component=integration-tests
category=bug
source=pr-comment finding 37183c (PR #382, CodeRabbit inline @ ItProfileConfigBindingWiringTest.java:225, fixed by TASK-9)

# A Compose argument guard must read entrypoint as well as command, in both list and string form

## What happened

The compose JSSE guard read only each gateway service's `command`. A `-Djavax.net.ssl.*` argument placed in `entrypoint` (Compose accepts it as a list or a string) would have passed the guard unnoticed. TASK-9 changed the scan to read `entrypoint` and `command` in both forms. The guard's Javadoc and the coverage document now name both keys. Two falsification runs showed the guard turns red on a list-form and a string-form entrypoint argument.

## Rule

A guard over container launch arguments must cover every field the runtime turns into argv: for Compose that is `entrypoint` plus `command`, each in list and string form. Prove each route with a falsification run before claiming it is covered.
