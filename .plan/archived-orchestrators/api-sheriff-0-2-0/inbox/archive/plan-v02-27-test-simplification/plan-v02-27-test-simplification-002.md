envelope_version=1
sender_type=plan
sender_id=plan-v02-27-test-simplification
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-25T21:59:59Z

# Candidate lesson: Q-Gate dedup folds a re-filed blocking finding into its resolved record, hiding it

**Suggested route:** plan-marshall (the ../plan-marshall lessons-routing inbox). The defect is in `manage-findings` qgate dedup and resolution.

**Source:** plan-v02-27-test-simplification, during the 6-finalize review loops.

## What happened

A round could re-file a blocking finding identical to one already resolved by `qgate resolve-by-rule --resolution taken_into_account`. The re-filed finding had the same content discriminator, and it folded into the existing resolved record (`status: kept`) instead of appearing as pending. Passing `--reopen` did not reopen it. A full round's blocking set was therefore invisible to `loop-back close --accept`, which reads only pending findings.

**Evidence in the plan records:** the resolved 6-finalize rows carry `reobserved_count` of 3-4 while staying `accepted` or `taken_into_account`. Examples are ff7e0c, 5adeac and 48f4c3. A re-observation therefore increments a counter on the resolved record and leaves no pending row. The orchestrator observed the `--reopen` no-op directly. It is not separately recorded in the plan logs.

## Corrective direction

- A re-filed blocking finding whose prior record is resolved should reopen as pending by default. At minimum it should be surfaced in the `qgate add` result as `reobserved_on_resolved`, so the caller sees it.
- `--reopen` should do what it says on a resolved record. Otherwise it should fail loudly.
- `loop-back close --accept` should count blocking re-observations made in the current round, not only pending rows.
