envelope_version=1
sender_type=plan
sender_id=plan-v02-53-plan-15-follow-up-issues
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-11-05T19:06:42Z

## Candidate lesson: outline sweep snapshot under-covered its own success criterion (D7)

- Source signal: Q-Gate finding 208399 (phase 3-outline, scope_criterion_validator, under_coverage, deliverable 7), resolved in-run (fixed)
- Plan: plan-v02-53-plan-15-follow-up-issues (PR #565, merge 7a5d52a)
- Surface touched: solution outline D7(b) committed-YAML sweep snapshot; doc/user/endpoint-routes.adoc and inline YAML fixtures

### What happened

The success criterion "the committed-YAML sweep covers every declared file on merged code" was checked by a content search for `redirect:` / `forward:` YAML, which returned files outside D7(b)'s snapshot:

1. The snapshot said "the 25 YAML files above" but listed 26 descriptors (the list matched `git ls-files`; the stated count did not).
2. `doc/user/endpoint-routes.adoc` carries `redirect:` and `forward:` YAML examples and was a D7 write target, but was missing from the sweep table.
3. YAML embedded outside `.yaml` files was not in the snapshot at all: inline fixtures in ConfigLoaderTest, ConfigProducerTest, PolicyBlockTest, the heredoc fixture in `integration-tests/scripts/verify-invalid-config-fails.sh` (case 8), and `doc/variants/01-base-gateway.adoc`.

### Resolution in-run

D7(b) snapshot widened: count corrected to 26, an embedded-YAML part added (each file pre-assessed clean against main 954d7d88), and a stale endpoint-routes.adoc sentence added to D7(a). Success criteria unchanged.

### Candidate rule

When a deliverable's success criterion is a corpus sweep ("covers every file carrying X"), derive the snapshot from the same content search the criterion will be verified with (including YAML embedded in Java, shell heredocs and AsciiDoc), and state counts from the derived list rather than by hand.
