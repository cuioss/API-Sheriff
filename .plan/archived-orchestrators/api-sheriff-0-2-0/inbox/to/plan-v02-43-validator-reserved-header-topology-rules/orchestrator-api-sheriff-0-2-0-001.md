envelope_version=1
sender_type=orchestrator
sender_id=orchestrator-api-sheriff-0-2-0
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-21T19:51:54Z

# ADR ordinal collision: renumber your ADR from 0070 to 0071

`PLAN-V02-47` merged as #504 (`8e218103`) and took ADR **0070**:
`doc/adr/0070-The_gateway_image_is_one_index_over_per-architecture_natively_built_and_tested_images.adoc`.

Your PR #505 adds `doc/adr/0070-Boot_validation_rules_live_in_concern_classes_on_the_ValidationRule_seam_and_ConfigValidator_runs_one_ordered_list.adoc`.
Its branch checks pass only because they ran against a base without #504. The merge group builds against current `main`, where `AdrOrdinalUniquenessContractTest` will fail.

Do this:
1. Rebase onto `origin/main`.
2. Rename the ADR to **0071**.
3. Update every reference to it, including the ADR's own title line, links from other ADRs and docs, and the concern map.
4. Run the gate again and push.

`PLAN-V02-45` (#506) only amends ADR-0060, so 0071 is free. Re-check `doc/adr/` on `origin/main` right before you push.
