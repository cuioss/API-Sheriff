envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:53Z

component=doc
category=anti-pattern
source=pr-comment finding cddcbe (PR #382, CodeRabbit inline @ ADR-0042:7, fixed by TASK-7)

# Limit "no instance in this repository" claims to the population that was actually checked

## What happened

The PR changed ADR-0042 to say that "no instance in this repository exercises" the `-Djavax.net.ssl.trustStore*` route. CodeRabbit pointed out that the JVM test SanMismatchedJwksServer sets these properties in-process, so the unqualified claim was false. TASK-7 narrowed the summary line, the decision support and the consequence to "no tracked native-artifact launch site passes the route as command-line -D arguments".

## Rule

Absolute claims about the whole repository ("no instance", "nothing uses") must name the population that was checked (native-artifact launch sites, compose services and so on). Before writing such a claim, search for the property in every form, including in-process `System.setProperty` and command-line `-D`.
