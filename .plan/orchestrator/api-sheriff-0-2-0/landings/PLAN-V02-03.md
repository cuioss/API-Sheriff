# Landing — PLAN-V02-03: Documentation restructure

epic: api-sheriff-0-2-0 · workstream: WS-02
**PR [#197](https://github.com/cuioss/API-Sheriff/pull/197) · merge commit `89a3cfe` · merged 2026-08-09 01:38 UTC**
plan_marshall_plan_id: `plan-v02-03-documentation-restructure`

> **This landing was not reported by the operator** — it was discovered by the orchestrator when the
> inbox scan for `PLAN-V02-02` returned twelve messages from two senders. Both plans were emitted as
> a parallel pair; this one landed ~2h15m earlier.

## Corroboration — what the orchestrator verified first-party

| Claim | Verdict | Evidence |
|---|---|---|
| Merged to `main` as `89a3cfe` | **corroborated** | ancestor of `origin/main`; subject `docs: split configuration monolith, purge archive, retire doc/plan (#197)` |
| `doc/archive/` deleted | **corroborated** | directory absent |
| `doc/plan/` retired | **corroborated, and partial BY DESIGN** | 9 of 12 files deleted; 3 survive deliberately — see below |
| `configuration.adoc` reduced | **corroborated** | 2539 → **2119** lines |
| `doc/user/` expanded | **corroborated** | 8 → **10** pages |
| Open Defect (5) closed | **corroborated** | `environment-variable-overrides.adoc` now carries `SHERIFF_CLIENT_SECRET` / `SHERIFF_SESSION_KEY` (3 hits; previously zero against a passing control) |

**The `doc/plan/` survivors are the reason a naive check would have mis-read this landing.** A
directory-exists test says "D3 did not run". Reading the plan's own report says the opposite: D3 ran
in full, verified all 12 files **by content** against the 41-record landings corpus, and refused two
deletions on evidence. That is the deliverable working, not failing.

## Deliverable fidelity — three of three, with two operator rulings

**D1** — `doc/archive/` deleted (10 files) and **7 inbound references repaired across 5 files**. The
spec's HYPOTHESIS that the archive had no load-bearing inbound link was therefore **refuted in the
useful direction**: links existed and were fixed rather than broken.

**D2** — sections 6–8 moved into the operator layer (two new `doc/user/` pages plus a fold into
`environment-variable-overrides.adoc`). **Sections 1–5, 9 and 10 were retained by operator ruling,
not by omission** — so the spec's "reduce `configuration.adoc` to a navigational index" was
deliberately not taken to completion, and 2119 lines is the intended landing state, not a shortfall.

**D3** — supersession verified **per file, by content**, exactly as the spec demanded (*"a bulk
'verified' is not acceptable for a delete of this size"*). Nine confirmed superseded and deleted.

## The survivor finding — one file becomes actionable at the 1.0 cut

- **`01-base-implementation.adoc` — not superseded, expected.** No landing in the 41-record corpus
  covers the TokenSheriff-scaffolding-to-verified-foundation work. Confirmed by enumerating every
  record rather than sampling. The spec predicted exactly this file (*"has no obvious counterpart and
  is most likely to hold unsuperseded content — check it first and hardest"*). **The spec's own
  highest-risk prediction was correct.**
- **`09-release-readiness.adoc` — not superseded, NOT expected. This is the finding.** The outline
  listed it under *files expected to mutate (delete)*; verification refused the deletion. Its
  defining deliverable — **the 1.0.0 cut and the flip of the pre-1.0 rules** — has not happened; the
  project is 0.1.1 alpha and `CLAUDE.md` still carries the Pre-1.0 Rules. **An expectation is a prior,
  not a licence.** Carried to the epic as an open item that becomes actionable at the 1.0 cut.
- `README.adoc` reduced to its project-wide conventions rather than deleted.

## Reconciliation actions

- **Queue**: `PLAN-V02-03` → `shipped`; `pr`, `landing`, `plan_marshall_plan_id` stamped.
- **Open Defect (5) CLOSED** — the env-var page omission that 0.1.0 and 0.1.1 both shipped with.
  Verified first-party, not accepted on report.
- **`PLAN-V02-13`'s `doc/plan/04-request-pipeline.adoc`:181 reference is DISCHARGED** — that file is
  among the nine deleted. V02-13's re-grounding section anticipated this and told the reader to check
  before editing; the check now returns *deleted*, and the directory must not be resurrected.
- **`PLAN-V02-17`'s sequencing note is now settled by events**: V02-03 landed first, so V02-17 writes
  into the settled `doc/development/**` layout rather than racing it.
- **No parallelization collision** with the concurrently-running `PLAN-V02-02`. The predicted soft
  overlap on `CLAUDE.md` did not materialise.

## Residue carried to the epic

1. **A `/marshall-steward` run is owed.** The architecture inventory's project description still
   reads *"…follow the plans under `doc/plan/`"* — stale now that D3 landed. It is
   steward-regenerated, not hand-editable, so the plan **flagged it rather than patching it** — the
   correct call, and it joins the steward work Watch (31) already names.
2. **Traceability gap on the deleted `doc/archive/others/excluded.adoc`.** Its content (Apiman, WSO2
   and other considered-but-not-evaluated gateways) traces to no live design document —
   `doc/features-analysis.adoc` distils only the six *evaluated* gateways. Zero inbound refs and git
   history preserves it, so the deletion stood, but `doc/README.adoc`'s "fully adapted" claim
   **over-reached for that one file**. Reported rather than quietly left.
3. **An operator ruling overrode the spec's "no code change of any kind".** Repointing the k6
   script's `@fileoverview` comment made the plan **gate-bearing**, so a full Maven gate ran where
   the spec had assumed a documentation-only exemption. Worth noting because the spec's absence
   assertion was explicit and was consciously overridden, not violated.
4. **The spec's open question is answered: `host: api.example.com` is CORRECT at its layer.**
   `match.host` is an **ingress** matcher; ADR-0004 governs the **egress** leg, so the two do not
   conflict. The real defect was three unqualified "no concrete host" claims in `configuration.adoc`,
   all narrowed. The spec's instruction — *"if the literal is correct at that layer, say why"* — was
   discharged.

## Addendum — 2026-08-09, from the operator's landing narrative

The operator pasted this landing after it had already been reconciled from the inbox. **Three items
in that narrative were not in the OUTBOX messages and are folded in here** rather than recorded as a
second landing.

### A security-relevant documentation defect, caught by finalize's own security-audit step

The new secrets section told operators the placeholder form worked *"exactly like"* the defaulted
`${VAR:-default}` form. It does not. **`ConfigLoader` refuses a defaulted placeholder for a secret at
boot — but only after a `client_secret: ${…:-changeme}` is already in git history.** The failure is
loud and it is also too late: the damage is the committed default, not the refused boot.

**Verified fixed, first-party at `89a3cfe`:**
`doc/user/environment-variable-overrides.adoc`:242 now states the secrets form must be a **bare
`${VAR}` reference** and explicitly separates it from the `${VAR:-default}` form the topology and
endpoint-enablement surfaces use; `doc/configuration.adoc`:2071 carries the same distinction; and
`EnvSecretResolver`:45 documents it at the implementing source. No stale *"exactly like"* claim
survives in the secrets documentation.

**This is the sharpest instance in the epic of a documentation defect being a security defect.** The
prose was wrong in the direction that costs something — it invited operators into a shape the product
refuses, at a point where the refusal cannot undo the consequence.

### Finalize's review steps found five defects the execute phase missed

Not one, and not cosmetic: dangling references left by the deletions, plus **13 relocated-section
pointers — 8 of them inside YAML listing blocks where `link:` macros do not render at all** — plus
the security item above. `pre-submission-self-review` needed **two fix rounds** to come clean, and
`finalize-step-simplify` and `finalize-step-security-audit` each landed edits.

Read against the sibling landing this is a pattern worth naming: on `PLAN-V02-02` the same
review-step family caught a **fail-open authentication path**, and here it caught a security-relevant
doc defect and 13 broken pointers. **In both plans the execute phase's own gates were green and the
finalize review steps were where the real defects surfaced.** That is also the counter-evidence to
the self-review surfacer's silent zero over AsciiDoc footprints (lesson `2026-08-09-07-006`) — the
step is high value exactly where it runs.

### Partial review coverage on the fix commit, deliberately not re-triggered

CodeRabbit hit its **OSS rate limit** on the second review round, so the 5-file triage-fix commit
carried **PR-Agent coverage only**. Per the standing note (`coderabbit-oss-rate-limit-blocks-merge`)
no re-trigger was budgeted — a refusal permanently consumes the range. **The substantive diff had
full CodeRabbit coverage from round 1**; what went uncovered was the fix commit for findings the bots
themselves raised. Recorded as accepted residual risk, not as full coverage.

## Post-merge verification — OWED, and the gap is now re-verified by execution

The main-branch `deploy-snapshot` run for `89a3cfe` is the orchestrator's to check, and the operator
handed it over explicitly. **It was attempted and the mechanism refused:**

```text
ci checks status --head 89a3cfe
  → error: no pull requests found for branch "89a3cfe"
```

`--head` resolves as a **branch name** used to find a PR, not as a commit SHA, so the
by-merge-commit lookup Watch (16)+(20) describes is genuinely unreachable through the CI abstraction
— and `gh` is outside the carve-out. The same call fails identically for `e343404`.

**Recorded as OWED for both merge commits, never as complete.** This satisfies the Watch's own
instruction to re-check by **executing the mechanism** rather than by reading a version number: the
mechanism was executed, and it still refuses.

> **UPDATE 2026-08-09 — POST-MERGE VERIFICATION IS NO LONGER OWED. IT IS DONE AND GREEN.**
> `build / deploy-snapshot` = **success** for `89a3cfe` (Maven Build run `31289270907`), as is every other job
> in that run. The check was reachable all along via one read-only `gh` call; the abstraction has no
> commit-to-run path, which had been mistaken for the axis being unverifiable. See
> `epic.md` § Post-Merge Verification for the method and the abbreviated-SHA false negative it survived.
