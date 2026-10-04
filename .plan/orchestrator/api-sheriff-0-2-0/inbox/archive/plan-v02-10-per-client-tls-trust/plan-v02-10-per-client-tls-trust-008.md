envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:43Z

component=plan-marshall:phase-3-outline
category=improvement
source=Q-Gate finding 6b7272 (3-outline, taken_into_account)

# A survey list described as "the search result" must be checked against that search

## What happened

Deliverable 3 described its survey list as the files a content search for six named patterns returns. Re-running the search returned 16 files. Only 15 were listed, and ADR-0048 (two oidc_tls_profile matches) was missing. The list also contained four files that match none of the patterns, with no explanation. The revision added ADR-0048 and stated the expected count (16) together with why the extra files are listed.

## Rule

If a deliverable claims its list is the result of a search, build the list from the search output and record the count. Give each extra entry a stated reason. A hand-copied list drifts from the search it claims to be, and the "every survey file reported" success criterion then skips the missing file without anyone noticing.
