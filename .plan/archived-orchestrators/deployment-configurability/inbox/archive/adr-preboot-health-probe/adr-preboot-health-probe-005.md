envelope_version=1
sender_type=plan
sender_id=adr-preboot-health-probe
epic=deployment-configurability
kind=landing
created=2026-09-04T07:16:34Z

PLAN-10 landed: ADR-0039 records the pre-boot health probe, merged via the merge queue as 337af0d.

```landing-facts
schema=landing-facts/1
plan_id=adr-preboot-health-probe
epic=deployment-configurability
pr=257
merge_state=merged
merge_commit=337af0d74d8e8a1c38b9c745ec5a45a33bdd1df0
deliverables_total=1
deliverables_done=1
total_tokens=3570817
total_wall_seconds=42889
adr_ordinal=0039
footprint=doc/adr/0039-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc,doc/README.adoc
build_decision=not_necessary
steps=finalize-step-sync-baseline:done,pre-push-quality-gate:done,finalize-step-simplify:done,architecture-refresh:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done,emit-landing:done,archive-plan:pending
step.record-metrics.total_tokens=3570817
step.record-metrics.total_wall_seconds=42889
step.record-metrics.any_phase_missing_end_time=false
step.finalize-step-sync-baseline.action=noop
step.finalize-step-sync-baseline.upstream_commit_count=0
```

## Residue

**The ordinal resolved to 0039, not the 0038 this plan spec was named for.** PLAN-02 took 0038
(PR #248 -> b200bed). Re-verified at outline and again at execute: `manage-adr next-number`
returned 39 before the write and 40 after. PLAN-04 and PLAN-07 remain staged and both declare
`doc/adr/`, so the next plan to land there must re-resolve rather than trust this number.

**Deliverable 3 settled as `Accepted`.** The drafted ADR said `Proposed`; the mechanism is merged
and live at HEAD, and fifteen sibling ADRs use `Accepted` with `*(Status: accepted.)*` index rows.
Both sites were changed together.

**The drafted README patch was NOT applied as a patch.** It was verified applicable
(`git apply --check` clean, blob 53da5d8 matching its index line) but the row was placed by editing
the file, because the patch's single physical line carries both the `0038` link target and the
`[ADR-0038]` label, so applying it verbatim would have landed the wrong ordinal at two sites.

**A defect in the drafted ADR was caught by review and fixed on-branch (commit 6330a46).** The
draft twice claimed a deployment moving the management port "must override the image's HEALTHCHECK
to match". That remedy is unreachable: `HealthProbe.PROBE_PORT` is a compiled-in 9000,
`probe()` takes no port argument, and the distroless image has no other executable, so an
overridden HEALTHCHECK re-invokes the same binary and still probes 9000. Both sites now name an
image rebuild as the only remedy. The fail-closed framing was correct and was preserved.

## Owed follow-ups (out of this plan's documentation-only scope)

1. **`HealthProbe.java` Javadoc lines 36-38 carry the identical incorrect port-override claim.**
   The ADR prose is now correct and the source comment is not. It could not ride this commit: one
   `.java` file makes the whole commit gate-requiring and breaks the documentation-only skip.

2. **Repo-wide `marshal.json` config defect — affects every plan in this repository.**
   `required_bots` names `cuioss-review-bot`, which is the reviewer's AUTHOR LOGIN, not a registry
   `bot_kind`. `automatic-review/standards/pr-agent.md` declares `bot_kind: pr-agent` with
   `author_login: cuioss-review-bot`, and no `cuioss-review-bot.md` registry doc exists, so the
   configured token can never resolve and the reviewer is classified `absent` forever - the
   participation quorum cannot converge by awaiting. Root cause: commit 1c7308c renamed the config
   token to the login while the registry key stayed `pr-agent`. The project CLAUDE.md still
   documents the correct value. This run patched it PLAN-LOCALLY only.

3. **Producer-side re-review matcher gap.** `_references_head_sha` inspects only the `review`
   signal, so pr-agent's `issue_comment`-published re-review reports `head_sha_verified=false` and
   escalates as a DECLINE even when its body names the reviewed commit verbatim. The barrier's own
   producer (`github_pr fetch_findings`) resolves the same commit correctly, so two resolvers
   disagree and only one is right. Cost this run: loop-backs to the 3/3 ceiling plus a
   hand-verified `rereview-timeout-override` grant.

4. **`doc/README.adoc` NOTE enumeration is a hand-maintained mirror of `doc/adr/`.** It now reads
   "ADR-0020 through ADR-0035 and ADR-0038"; it will drift again when ADR-0040 lands.
