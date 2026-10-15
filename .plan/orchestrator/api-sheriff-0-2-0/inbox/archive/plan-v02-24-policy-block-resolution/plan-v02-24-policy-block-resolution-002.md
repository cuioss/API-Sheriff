envelope_version=1
sender_type=plan
sender_id=plan-v02-24-policy-block-resolution
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-15T09:38:59Z

# Candidate lesson: prose that enumerates PolicyBlock.ALL goes stale; link the test-bound scope table instead

**Source**: plan-v02-24-policy-block-resolution, PR #465, CodeRabbit inline findings a6698a (`doc/adr/0007-anchor-scoped-policy.adoc`), 8c538b (`doc/architecture.adoc`) and 7cab73 (`doc/user/anchors.adoc`). All three were fixed in-run in commit 5d3ffd36.

## What happened

Three documents each restated the full list of policy blocks in running prose. `PolicyBlockSchemaContractTest` binds exactly one documented list to `PolicyBlock.ALL`: the scope table in `doc/user/anchors.adoc` (`_policy_block_scopes`). The other three copies had no guard, so a future descriptor change would leave them stale without any failing test. CodeRabbit raised the same path instruction on each copy.

## Corrective rule

A document must not enumerate the policy-block inventory, or any other set that the code owns, outside the one test-bound table. Point to `_policy_block_scopes` instead. When the enumeration is the point of the passage, as in an ADR amendment that decides scope sets, label it as the set decided at that time and link the live inventory. Before a plan touching `PolicyBlock` is submitted, run a content search for the block names across `doc/` to find unguarded copies.

## Suggested classification

Project-local (API-Sheriff docs). Same defect class as the boot-reader scope candidate from this run. Could be one lesson: "derive or link, never re-enumerate, a set the code owns."
