# Landing Analysis: PLAN-07 — TLS Material Audit and Trust Contract

epic: deployment-configurability
workstream: WS-06
pr: [#283](https://github.com/cuioss/API-Sheriff/pull/283) — merged as `a30fe6f` (squash)

> Analysed by paste. ⛔ **The inbox held no PLAN-07 message and `emit-landing` never ran** — see
> Routing. Six deliverables against the re-scoped spec's five; deliverable 3 was **inverted
> mid-finalize** on an operator ruling and carries a breaking change.

## Deliverable Fidelity vs Spec

| Deliverable | Verdict |
|---|---|
| 1. Terminated main-listener TLS audit on a shared discriminator | shipped — **the deliverable the cleanup pass identified as this plan's surviving centre of gravity**, after ApiSheriff-119/120 closed the outbound half |
| 2. Effective JVM-default trust-source audit, both tiers | shipped |
| 3. Refuse an undeclared plain-HTTP boot | ⛔ **INVERTED mid-finalize — see below** |
| 4. Trust-material replacement hazard documented first | shipped |
| 5. ADR-0042 artifact and base-image trade-off | shipped — `0042-JVM_over_native_and_UBI-minimal_over_distroless_are_considered_and_declined.adoc` |
| 6. Integration test in the shipped native image | added-unplanned |

✅ **The cleanup pass's re-scope was correct.** On 2026-09-08 this plan's premise was partially
refuted — `ApiSheriff-119`/`-120` had already closed the "nothing says trust config replaces the CA
bundle" half — and deliverable 1 was named as what survived. It shipped as the plan's centre.

### ⛔ Deliverable 3 inverted — and it is a breaking change

As staged it **inferred** plain-HTTP intent from a missing certificate. On the operator's ruling it
now **refuses the boot** unless `insecure-requests=enabled` is explicitly declared. Marked
`feat(tls)!`: a deployment that previously booted cleartext by inference now refuses until intent is
stated.

✅ **That is the right direction and it matches this epic's own repeated finding** — inference from
absence is how the fourth egress leg went undeclared, how the cookie ceiling looked legitimate, and
how five documentation sites asserted a leg that existed. Requiring the operator to *say* it converts
a silent assumption into a stated one.

## Metrics and Anomalies

6h13m worked / 39h48m wall / 7.1M tokens. Sonar 0 new-code issues confirmed; coverage 96.3%.
CI green at `3784898`, integration-tests included.

### ⛔ What the review chain caught that 2029 local tests did not

Worth recording in full, because all three are the same family — a check that passes while measuring
the wrong thing:

1. **PR-Agent**: both TLS audits read only `certificate.files`, producing a **false-positive
   plain-HTTP warning** on any `key-files` / `key-store-file` deployment. The audit would have
   mis-reported the very thing it was built to report.
2. **CodeRabbit**: the new gate accepted `KEY__STORE`, a spelling SmallRye does not resolve — a
   verdict reached by **reading SmallRye 3.17.2 sources**, not by pattern-matching. ⛔ **It
   invalidated an earlier fix of our own**: the `security-audit` step had introduced the same wrong
   convention into the documentation. A fix that made it worse, caught only because a reviewer went
   to the implementing source.
3. **The local integration run**: a test passing **vacuously** — an unrelated startup crash was
   indistinguishable from a real refusal on the two properties it checked.

⚠ **Item 3 is the same class PLAN-16's deliverable 5 was commissioned to hunt**, found independently
in a different lane on the same day. That is corroboration the class is real and not confined to the
cookie ITs.

## Routing and Merge Behavior

CodeRabbit and PR-Agent both reviewed the merged HEAD; 0 blocking findings, 0 resolvable threads
unresolved. Merged as `a30fe6f`; worktree removed.

⛔ **`emit-landing` never ran.** `grep -c 'emit-landing'` over the archived `work.log` returns **0**
while every neighbouring step logs its completion line — so the step was **absent from the composed
manifest**, a third failure mode distinct from the runtime skips seen on PLAN-01/02/03. Recorded as
its own defect.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `283`, `landing`, `plan_marshall_plan_id` stamped
- [x] **PLAN-18 staged** from PLAN-16's deliverable-5 audit (nine pro-forma tests, eight files)
- [x] Six defects opened — the vacuous Java self-review, the third `emit-landing` failure mode, the
      second missing scope-creep verdict, issue #285, stale `marshal.json`, the port-collision flakes
- [x] **ADR ordinal is now `0043`** — `doc/adr/` ends at `0042`

## Follow-Ups

1. ⛔ **`pre-submission-self-review` has been vacuous on every Java plan in this epic.** No
   `ext-self-review-java` surfacer exists; its detectors read Python and skill docs. Belongs upstream
   in plan-marshall. **This retroactively weakens every "self-review clean" line in this epic's
   landing records** — they mean the step ran, not that anything was checked.
2. ⚠ **The scope-creep fence was absent for the second time** (PLAN-10, now PLAN-07) — no
   `plan_creation_sha` in `references.json`. Two of eight shipped plans is a pattern.
3. ⚠ **Issue #285** — ten dead `QUARKUS_TLS_DEFAULT_TRUST__STORE_*` pairs, pre-existing, correctly
   routed out rather than fixed in an unrelated PR.
4. ⚠ **`marshal.json` is stale** (`0.1.1617` vs `0.1.1632`). Do the steward run **between** plans —
   the last one introduced churn that had to be re-applied mid-flow while plans were running.
