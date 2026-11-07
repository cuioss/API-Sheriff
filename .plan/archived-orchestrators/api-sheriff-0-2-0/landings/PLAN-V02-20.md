# Landing Analysis: PLAN-V02-20 — Provide the Cookie Sealing Key the Way the Signing Keys Are Provided

epic: api-sheriff-0-2-0
workstream: WS-04
pr: #527 (https://github.com/cuioss/API-Sheriff/pull/527)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-27 from the inbox messages
> `plan-v02-20-cookie-key-file-model-001` to `-014` (candidate lessons) and `-015` (the landing). Checked against
> `origin/main` at `6ec1393b`, the merge commit's diff, and the CI runs. The plan ran once under its own plan id.

## Deliverable Fidelity vs Spec

#527 changed 57 files (+2,680 / −742): under the 100-file review-bot cap. Production code is in `bff/` (3 files, with the
new `KeyFiles`), `config/validation/rule/` (2), `config/` loader and schema (2) and `quarkus/` (1); the rest is tests, the two
cookie overlays and their key files, compose, one workflow, and documentation.

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| D1 — decide the symmetric key file format, record it in an ADR | shipped | `doc/adr/0072-…adoc` (226 lines, `Accepted`) records base64 text that decodes to exactly 32 bytes, the bounded read, and every derivation from the key. It is within the 500-line body guard (CI green). The ordinal was derived on the branch, as the spec asked. |
| D2 — replace `encryption_key` with a key-file reference | shipped | `oidc.session.key_file` replaces `oidc.session.encryption_key`. On `origin/main`, `git grep` for `encryption_key`, `SESSION_ENCRYPTION_KEY` and `encryptionKey` finds only the deliberate residuals: the unknown-key refusal test in `ConfigLoaderTest`, the retired-variable assertion in `BffCookieActivationWiringTest`, the migration section of `doc/user/bff-cookie.adoc`, and ADR-0072's account of the replaced field. The old `2700` / `32.8` figure is gone from `integration-tests` and `doc`. |
| D3 — tests for both modes | shipped | The key file is read bounded; malformed, oversized and unreadable files fail the boot naming the field and the defect. `KeyFiles.readBounded` reads `maxBytes + 1` to detect an oversized file; `CookieKeyMaterial.MAX_KEY_FILE_BYTES = 256`. |
| D3a — same-key refusal and the `cnf` cookie size | shipped, residuals | The boot refuses one key for both client authentication and DPoP, by file (`Files.isSameFile` when both exist; otherwise equal paths with no `..`) and by key id. ADR-0058 decision 2 is amended in place and its `summary:` carries an `AMENDED (A1)` marker. The sealed cookie with the `cnf` claim measures 2572–2580 bytes, leaving 1439 of the 4019-byte default (35.8 %); the figure is in `doc/development/bff-cookie.adoc:378-409`. See the residue below. |
| D4 — documentation | shipped | `doc/user/bff-cookie.adoc`, `doc/configuration.adoc`, `doc/user/environment-variable-overrides.adoc` and `doc/LogMessages.adoc` are changed. The key-change procedure now says to replace the file on every replica and restart every replica. |

The functional review ended with 4 deliverables met, 2 met with residuals and 0 not met. The residuals were fixed before
the merge (the stale cookie-budget comment, and the ADR-0072 decision 3 wording after the `CookieKeyMaterial` refactor).

## Metrics and Anomalies

- Tokens: 37,069,851. Wall time: 38,181 s (10.6 h) for 6 deliverables. Model claude-opus-5-5, effort low. Plugin and
  executor 0.1.2010.
- Code review was closed by operator override after two non-converging rounds (two findings accepted, see the residue).
- The pre-merge HEAD binding then refused the merge (`merge_candidate_not_gated`) because simplify, security-audit and
  functional-review were bound to older heads. The operator chose delta re-checks, which ran clean at `008f7f7f`. A
  plan-marshall tooling cluster; routed outward, see below.
- The criteria were re-frozen twice (3-outline and 4-plan) because an exit criterion required count 0 for names the plan
  itself kept as residuals. A project lesson was promoted.
- Sonar: 0 new-code issues. CodeRabbit findings 1 (`ADR-0072:75`) and 2 (`doc/user/bff-cookie.adoc:237`) fixed in
  `36229c1c`.
- No security note was recorded by the plan.

## Routing and Merge Behavior

- Merged through the merge queue as `6ec1393b` at 2026-10-26T22:19:16Z. The squash message is the repository default
  (PR title plus PR body), so the commit body carries the PR summary. No repository setting was changed.
- Post-merge on `main` at `6ec1393b`, all completed with success: Maven Build (40411521497), Integration Tests
  (40411521314), Config Validation, Demo Client E2E and Scorecard. The PR-attached Performance Benchmark run
  (40411525106, head `008f7f7f`) passed.
- Effect on the ADR corpus: 66 records on `main` (65 plus ADR-0072). ADR-0058 is 526 lines in the file (523 at
  `f5826465`) with the amendment, and `AdrRecordLengthContractTest` is still green in CI.

## Residue and Owners

Verified on `main` at `6ec1393b`:

- **`OidcClientRules.nameSameFile` Javadoc** (lines ~290–300) says "the pre-boot verdict cannot depend on the machine it
  is reached on". With both files present it does depend on the machine, because `Files.isSameFile` decides. Confirmed.
  Owner: `PLAN-V02-48` D12.
- **`doc/user/offline-config-validation.adoc:262`** says "the offline verdict never differs from the boot's". It does:
  a pair that locates one file through `..` or a link can pass offline and be refused at boot. The next clause of the
  same sentence already concedes part of this. Confirmed. Owner: `PLAN-V02-48` D12. The outline change note and the
  TASK-7 description inside the finished plan still name `a/../b` vs `b` as refused offline; that lives in the plan's
  own working files, which are archived, so no owner is needed.
- **The `AMENDED (A1)` marker in ADR-0058's `summary:`** reads as bookkeeping. `PLAN-V02-48` rewrites group F2 (which
  holds `0058`); its wording pass states the amendment as current fact. Owner: `PLAN-V02-48` D2 (group F2).
- **Constraint lifted:** V02-20 was the reason `PLAN-V02-09` and `PLAN-V02-12` could not run beside it. Both are free of
  that constraint now (V02-12 is parked and is not affected by this landing).
- **ADR ordinal:** the next free ordinal is 0073 (derive it at write time). `PLAN-V02-05` and `PLAN-V02-06` are running and
  each writes one record; they derive their ordinal on their own branch and rebase over `0072`.
- **Review-bot and merge-queue facts:** nothing owed.
- **Candidate lessons.** `-001`, `-002` and `-004` are one project lesson (2026-10-27-05-001: derive the removal search
  from statements, sweep outside the inventory, list residuals in the criterion). `-003`, `-005` and `-007` are one
  project lesson (2026-10-27-05-002: stale prose after a change that green tests do not catch). `-006` is one project
  lesson (2026-10-27-05-003: a same-file path rule). `-008` and `-009` (finalize re-fire and HEAD binding), `-010` and
  `-013` (guards learned from a refused transition), `-011`, `-012` and `-014` (invented flags and verbs) and `-002`'s
  inventory gap are plan-marshall tooling and were routed to the `lessons-routing` inbox as four `finding` messages.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-V02-20 --status shipped`
- [x] row `pr` stamped — `orchestrator queue --set-row PLAN-V02-20 --field pr --value 527`
- [x] row `landing` stamped — `orchestrator queue --set-row PLAN-V02-20 --field landing --value landings/PLAN-V02-20.md`
- [x] row `plan_marshall_plan_id` already stamped
- [x] `PLAN-V02-48` spec brought to the `6ec1393b` state (66 records, D5 group F2, D12 extended, exclusion list)
- [x] `PLAN-V02-09` and `PLAN-V02-12` sequencing constraint on `PLAN-V02-20` retired
- [x] epic.md narrative reconciled
- [x] resume anchor updated in `resume_anchor.md`
- [x] `queue-view.md` regenerated

## Follow-Ups

- `PLAN-V02-48` D12 owns the two overstated "never differs" statements.
- A slot is free (two of three running: V02-06 and V02-05). The next candidates are listed in the resume anchor.
