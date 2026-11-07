envelope_version=1
sender_type=plan
sender_id=plan-v02-23-cookie-forwarding-policy-r2
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-24T15:11:26Z

component=plan-marshall:phase-3-outline
category=improvement
source_plan=plan-v02-23-cookie-forwarding-policy-r2
source_signal=Q-Gate 3-outline findings 6b5472, 068525, 10fd7c, fac5b2, fb2ee4 (all taken_into_account)

# Outline missed sibling sites that assert or restate the invariant a deliverable changes

## What happened

The 3-outline Q-Gate raised five under-coverage findings, all of the same shape: a deliverable
changed a rule (cookie forwarding no longer absolute; CsrfDefence widened beyond session routes; a
new block joining the override-WARN opt-in set; a new boot-time refusal), but sites that encode the
OLD rule were in no deliverable's Affected files:

- a test in a deliverable's own verification list asserting the opposite behaviour (would go red);
- package-info / LogRecord template / LogMessages.adoc rows describing the old scope;
- ADRs stating the old opt-in set as a closed enumeration;
- an IT whose assertion message gives a reason that becomes false;
- test fixtures building a configuration the new boot rule refuses (peer sweep found ~10 more).

All were fixed in the outline, but each needed a Q-Gate round.

## Proposed rule

When a deliverable changes an invariant, run a value-change sweep at outline time for every
textual and behavioural copy of the old invariant: tests asserting it, fixtures constructing the
now-refused input, Javadoc/package-info, LogRecord templates and doc/LogMessages.adoc rows, ADRs
with closed enumerations, IT assertion messages. Every hit goes into a deliverable's Affected files
or into an explicit "deliberately unchanged, because ..." list.
