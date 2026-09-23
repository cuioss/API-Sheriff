# Landing Analysis: PLAN-10 — ADR-0039, the Pre-Boot Health Probe

epic: deployment-configurability
workstream: WS-01
pr: [#257](https://github.com/cuioss/API-Sheriff/pull/257) — merged as `337af0d`

> **The first landing this epic received through its own inbox rather than by paste**, and the first
> to pass `inbox landing-check` with `complete: true` / `missing_keys[0]`. Every claim below was
> corroborated first-party before it was recorded — the message was the lead, not the fact.

## Deliverable Fidelity vs Spec

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. Create ADR-0039 from the drafted source and insert its index row | shipped-as-specified, **corrected on-branch** | `doc/adr/0039-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc` (+222) present on main; index row at `doc/README.adoc:285` |

Merged footprint: **exactly 2 files, +234/−1**, both `.adoc`. The documentation-only constraint held
end to end — `build_decision=not_necessary`, `pre-push-quality-gate` 0 bundles.

### The three spec-time hypotheses, all settled

- **Ordinal `0039`, not the `0038` in the plan's own filename.** Corroborated: `doc/adr/` now reads
  `…0037`, `0038` (PLAN-02's), `0039`. The spec's standing instruction to re-resolve at outline
  rather than trust the filename was correct and was followed — `manage-adr next-number` returned 39
  before the write and 40 after.
- **Status settled as `Accepted`, not the drafted `Proposed`.** The mechanism is merged and live, and
  fifteen sibling ADRs use `Accepted`; both the ADR and its index row were changed together.
- **The drafted README patch was NOT applied as a patch.** It was verified applicable
  (`git apply --check` clean) but the row was placed by editing, because the patch's single physical
  line carried both the `0038` link target and the `[ADR-0038]` label — applying it verbatim would
  have landed the wrong ordinal at two sites. ✅ Correct call: the patch was *appliable* and still
  *wrong*, which is precisely the case a clean `git apply --check` cannot detect.

## Metrics and Anomalies

- Tokens: **3,570,817**; wall **42,889 s (11h54m)** against **1h41m worked**. ⚠ The plan flagged the
  idle ratio itself; recorded as reported, not re-derived.
- ⛔ **The scope-creep guard could not run during execute** — `references.json` carried no
  `plan_creation_sha`, so that fence was verified **by hand** rather than automatically. This is a
  gap in the evidence, not a finding against the plan: the footprint it was guarding is independently
  confirmed at exactly two `.adoc` files by `git show --stat 337af0d`, so the *conclusion* holds on
  other evidence even though the automatic check did not produce it.
- `finalize-step-sync-baseline`: `action=noop`, `upstream_commit_count=0` — already current.

## Routing and Merge Behavior

- **Review caught a real defect and it was fixed before merge (commit `6330a46`).** The draft twice
  claimed a deployment moving the management port "must override the image's `HEALTHCHECK` to match".
  ⛔ **Corroborated first-party as unreachable**: `HealthProbe.java:67` declares
  `private static final int PROBE_PORT = 9000`; `:111` connects to a hardcoded
  `new InetSocketAddress("127.0.0.1", PROBE_PORT)`; `probe()` takes no port argument; the distroless
  image has no other executable. Both sites now name an image rebuild. **Had this merged as drafted,
  the ADR would have documented a recovery path operators cannot take.**
- ✅ The correction was verified against the source rather than accepted on the reviewer's word —
  recorded as the right disposition, and promoted to the lessons corpus.
- **CI/merge**: merged via the queue as `337af0d`; `ci pr view` reports `state: merged`,
  `merge_commit_sha: 337af0d74d8e8a1c38b9c745ec5a45a33bdd1df0`, matching the landing-facts
  `merge_commit` exactly. Worktree removed; main clean.
- ⚠ **Both required bots reviewed `6330a46`, but the barrier said otherwise** — see the folded
  recurrence on lesson `2026-09-02-22-003`. The run burned its 3/3 loop-back ceiling and needed a
  hand-verified `rereview-timeout-override`. The merge was correct; the path to it was not.

## Reconciliation Actions

- [x] row `status` → `shipped` — `orchestrator queue --transition PLAN-10 --status shipped`
- [x] row `pr` stamped `257`, row `landing` stamped `landings/PLAN-10.md`,
      row `plan_marshall_plan_id` stamped `adr-preboot-health-probe` — one `--set-row` call each
- [x] **Inbox drained: 5 messages, 5 archived, 0 invalid, 0 archive-failed.** Dispositions:
      001 `discarded`, 002 `promoted`, 003 `folded`, 004 `folded`, 005 `reconciled`
- [x] **Open Defect RETIRED — the `listen(0)` guard hazard resolved itself correctly.** PLAN-13 has
      rebased past `6ba8879` and its branch copy of `GatewayEdgeRouteTest.java` now carries **zero**
      bare `listen(0)` calls and six loopback-bound ones: it converted PLAN-06's two new sites along
      with its own four, unprompted
- [x] Open Defect **folded** (not duplicated) — the `required_bots` recurrence sharpened from "false
      block" to "structurally unconvergeable quorum"
- [x] PLAN-14 staged for the `HealthProbe.java` Javadoc residual
- [x] START-HERE and Ordered Queue blocks regenerated; `resume_anchor` updated

## Follow-Ups

1. ⛔ **`HealthProbe.java` Javadoc lines 36-38 carry the identical incorrect claim — STAGED as
   PLAN-14.** Corroborated verbatim at HEAD `337af0d`: *"A deployment that overrides
   `quarkus.management.port` **must override the image's `HEALTHCHECK` to match**"*. The ADR is now
   right and the source comment is not, which is the worse of the two states to be in — a reader who
   trusts the code comment over the ADR gets the wrong answer. It could not ride PR #257 because one
   `.java` file makes the whole commit gate-requiring and breaks the documentation-only skip.
2. ⛔ **`required_bots` — folded as a recurrence, still owed, still the operator's call.** Two plans
   have now patched it plan-locally. `.plan/marshal.json:115` unchanged at
   `coderabbit,cuioss-review-bot`.
3. ⚠ **Re-review matcher gap — folded into lesson `2026-09-02-22-003`.** Upstream plan-marshall
   defect; the cost is paid locally on every plan.
4. ⚠ **`doc/README.adoc`'s NOTE enumeration will drift again at ADR-0040.** Carried from the plan's
   report; not independently verified here beyond confirming the `0039` row landed. Whichever plan
   lands `0040` inherits it — PLAN-04 and PLAN-07 both still declare `doc/adr/`.
