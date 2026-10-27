envelope_version=1
sender_type=plan
sender_id=plan-v02-20-cookie-key-file-model
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-26T22:33:39Z

# Candidate lesson: a value change in a fixture leaves its self-describing header and sibling-text stale

- Source: Q-Gate findings eaf82a (3-outline, cookie-refresh overlay header counts THREE differences, D5) and f52cbc (3-outline, BffClientKeyWiringTest Javadoc claims every key file holds PEM blocks, D5), resolution taken_into_account
- Suggested component: project-local (integration-tests fixtures and test prose); possibly plan-marshall:phase-3-outline for the "read-only file whose stated contract becomes false" class

## What happened

1. integration-tests/src/main/docker/sheriff-config-cookie-refresh/gateway.yaml enumerates its deliberate differences from the sibling overlay ("THREE values ... Difference N of 3"). Pointing the two overlays at different key files adds a fourth difference; the outline only planned to change "the comments around it".
2. BffClientKeyWiringTest (class Javadoc and a DisplayName) states every key file in the signing-keys directory holds one private and one public PEM block. The new base64 cookie sealing key files land in that directory; the assertions stay green, but the stated contract becomes false. The file was listed read-only.

## Corrective rule

When a change adds files to a directory or a difference between sibling fixtures, re-read every comment, header enumeration and test Javadoc/DisplayName that describes that directory or difference set, and move them into the mutate set. Green assertions do not prove that a test's prose contract is still true.
