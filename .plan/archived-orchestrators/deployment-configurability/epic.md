# Epic: Deployment Configurability — Health-Check, Context Path, Hostname Verification, Refresh Reliability

slug: deployment-configurability

> Ledger document for one epic under `.plan/local/orchestrator/deployment-configurability/`.
> `status.json` is the machine authority; any statement here that conflicts with it is stale prose.
> Layout and authority contract: `persona-plan-orchestrator/standards/orchestration-model.md`.

## Vision

API Sheriff ships a real deployable artifact — a distroless native OCI image published to GHCR —
and four things a production operator needs are today either absent or not configurable. The main
image carries **no `HEALTHCHECK`**, so a docker-only deployment gets no in-band health signal. The
gateway serves from the root context with no way to mount it under a path prefix, on either the
application port or the management port. TLS **hostname verification is unconditional and
unconfigurable** on every connection the gateway originates. And the BFF's transparent
near-expiry token refresh — the path a user hits while simply working — has **no test that ever
forces it to run**, while a user has reported a logged exception at exactly that point.

This is too large for one plan because the four concerns sit at different layers: an image and
compose concern; an application-config plus probe-URL concern; a transport-security concern
spanning the terminated-listener's upstream dial and the JWKS back-channel; and a
session-lifecycle test-coverage concern. Done at the epic level means a docker-only operator can
set a context path and a health-check policy from environment variables, can relax hostname
verification where their topology requires it, and every switch has a test or a documented manual
procedure proving it — with secure-by-default preserved throughout.

## Provenance

This epic was first scaffolded in `/Users/oliver/git/TokenSheriff` on the strength of an on-disk
example path the operator named there. The operator identified API Sheriff as the intended
repository; that epic is **closed and archived** at
`TokenSheriff/.plan/local/archived-orchestrators/deployment-configurability/`, and its `history.md`
records the mis-targeting, the corroboration, and three findings that remain true of TokenSheriff
and are now unowned there. **Nothing was carried over verbatim** — every claim below was re-read
against API Sheriff's own source. Ground-truth reading confirmed rather than
merely accepted the correction: all four workstreams fit this repository better, and two of them
(the management interface, and TLS termination as a *named mode*) match the operator's original
wording literally here where they had to be interpreted there.

### ⛔ Provenance correction — 2026-08-27, `cleanup` re-grounding pass

This section previously claimed the corpus was re-derived at HEAD `5e73e94`. **That stamp is wrong
and has been retracted.** Three independent checks refute it:

1. **`5e73e94` is not an ancestor of `main`.** `git merge-base --is-ancestor 5e73e94 HEAD` returns
   false; `git branch -a --contains 5e73e94` names only `feature/pr-agent-java-pack`. No state of
   `main` ever looked like that commit.
2. **It predates surfaces the specs cite as OBSERVED.**
   `git show 5e73e94:deployment/compose-sample/docker-compose.yml` fails — the whole
   `deployment/compose-sample/` tree is absent there — yet PLAN-01 and PLAN-02 both cite it as
   observed surface.
3. **The line numbers do not agree with it.** PLAN-03 cites `WebSocketRelayStage:148` and
   `GatewayEdgeRoute:1295-1297`; at `5e73e94` those are `:142` and `:1269/:1271`. They became true at
   `e343404`, a descendant. PLAN-04's "health **and metrics** beans are excluded" likewise matches
   HEAD (`health.*,metrics.*`) and not `5e73e94` (`health.*` alone).

**The corpus was in fact read against the working tree at/near HEAD `cea163c`, and it is not stale.**
One claim is the exception and pulls the other way — PLAN-02's `doc/configuration.adoc:971`/`:1001`
citation was true at `5e73e94` and is false at HEAD (the text is at `:556-601`). So the corpus was
**mixed-provenance**, not uniformly stale. That single claim is now re-scoped in place and carries
`verdict: contradicted | rescoped: yes`.

⛔ **Do not re-derive this.** **40** of the 52 claims across the five specs now carry a persisted
`verdict:` field checked at `cea163c` (38 `corroborated`, 2 `contradicted`-and-rescoped;
`blocking_count: 0`, none stale). That is **every OBSERVED claim in the corpus (36)**, plus **PLAN-04's
four mechanism claims (4)**, which the `cui-http#165` pass settled outright — see that plan's
deliverable 1. The **12** still-unstamped claims are HYPOTHESIS and verify-first entries on PLAN-01,
PLAN-02, PLAN-03 and PLAN-05, deliberately left open because settling them is the launched plan's own
job (field-absent admits).

## START HERE

<!-- GENERATED BLOCK — never hand-write. Regenerate via:
     orchestrator resume-summary --slug deployment-configurability
     Paste the returned `summary` verbatim between the markers. Hand notes go in the
     annotation zone below, outside the markers. -->

<!-- BEGIN GENERATED: resume-summary -->
**Resume anchor**: 2026-09-23: PRE-CLOSE HANDOFF COMPLETE. Reviewed all 18 local lessons -- none were genuinely plan-marshall-specific (0/18 split; plan-marshall tooling gaps were always routed straight to lessons-routing, never promoted locally in the first place). All 18 handed to api-sheriff-0-2-0s inbox as candidate-lesson messages (deployment-configurability-002..019), plus Sonar java:S3398 as a finding (-020) -- that epic now owns Promote/Fold/Discard and the S3398 disposition, not decided here. Local lessons corpus confirmed EMPTY (manage-lessons list: total 0), tombstones preserved. Epic has ZERO standing blockers now: queue settled (21 shipped, 1 landed, 7 superseded, 0 live), S3398 routed (was the last open item), lessons corpus empty. READY TO CLOSE. NEXT: run /plan-marshall:plan-orchestrator close slug=deployment-configurability whenever the operator confirms.
**Phase**: orchestrating
**Inbox (derived)**: 0 queued, 63 archived
**Queue** (staged, in order):
- (empty)
- PLAN-01 (WS-01) — plan=distroless-health-check — PR 230 — landing=landings/PLAN-01.md — status: shipped
- PLAN-02 (WS-02) — plan=configurable-context-path — PR 248 — landing=landings/PLAN-02.md — status: shipped
- PLAN-03 (WS-03) — plan=upstream-hostname-verification — PR 268 — landing=landings/PLAN-03.md — status: shipped
- PLAN-04 (WS-03) — plan=jwks-hostname-verification — PR 272 — landing=landings/PLAN-04.md — status: shipped
- PLAN-05 (WS-04) — plan=bff-refresh-integration-coverage — PR 282 — landing=landings/PLAN-05.md — status: shipped
- PLAN-06 (WS-05) — plan=forwarded-trust-env-configurability — PR 254 — landing=landings/PLAN-06.md — status: shipped
- PLAN-07 (WS-06) — plan=tls-material-audit-and-trust-contract — PR 283 — landing=landings/PLAN-07.md — status: shipped
- PLAN-08 (WS-06) — plan=plain-http-termination-mode — PR 286 — landing=landings/PLAN-08.md — status: shipped
- PLAN-09 (WS-06) — status: superseded
- PLAN-10 (WS-01) — plan=adr-preboot-health-probe — PR 257 — landing=landings/PLAN-10.md — status: shipped
- PLAN-11 (WS-07) — plan=macos-loopback-hang-investigation — PR 243 — landing=landings/PLAN-11.md — status: shipped
- PLAN-12 (WS-07) — landing=landings/PLAN-12.md — (!) missing: pr — status: landed
- PLAN-13 (WS-07) — plan=loopback-stall-fix-and-instrumentation — PR 255 — landing=landings/PLAN-13.md — status: shipped
- PLAN-14 (WS-01) — status: superseded
- PLAN-15 (WS-05) — plan=trusted-proxy-breadth-and-probe-doc — PR 267 — landing=landings/PLAN-15.md — status: shipped
- PLAN-16 (WS-04) — plan=cookie-deliverability-and-ceiling — PR 284 — landing=landings/PLAN-16.md — status: shipped
- PLAN-17 (WS-04) — plan=cookie-mode-refresh-viability — PR 288 — landing=landings/PLAN-17.md — status: shipped
- PLAN-18 (WS-06) — plan=pro-forma-integration-test-fixes — PR 308 — landing=landings/PLAN-18.md — status: shipped
- PLAN-19 (WS-06) — status: superseded
- PLAN-20 (WS-03) — status: superseded
- PLAN-21 (WS-04) — status: superseded
- PLAN-22 (WS-05) — status: superseded
- PLAN-23 (WS-07) — plan=unit-lane-vacuity-audit — PR 336 — landing=landings/PLAN-23.md — status: shipped
- PLAN-24 (WS-06) — plan=release-docs-and-tls-scenario-guide — PR 305 — landing=landings/PLAN-24.md — status: shipped
- PLAN-25 (WS-08) — plan=configuration-security-hardening — PR 306 — landing=landings/PLAN-25.md — status: shipped
- PLAN-26 (WS-04) — plan=refresh-failure-dispositions — PR 314 — landing=landings/PLAN-26.md — status: shipped
- PLAN-27 (WS-04) — status: superseded
- PLAN-28 (WS-09) — plan=closeout-residual-hardening — PR 341 — landing=landings/PLAN-28.md — status: shipped
- PLAN-29 (WS-09) — plan=final-gap-closure — PR 348 — landing=landings/PLAN-29.md — status: shipped
<!-- END GENERATED: resume-summary -->

### Annotations

<!-- ANNOTATION ZONE — hand-written, OUTSIDE the generated markers; survives regeneration. -->

- Epic-wide: **`parallelization_scope = 3`** as of 2026-09-02, raised 1 → 2 → 3 as work actually went
  parallel. ⛔ Each raise **RECORDS reality rather than authorising more**: three things are in flight
  (PLAN-02 running, PLAN-05 launched-not-started, PLAN-12 running outside the lifecycle), and a lower
  knob would leave the ledger internally inconsistent with every future `N − R` going negative. The
  original 2026-08-27 sequential decision was argued on PLAN-01..04 sharing the container and
  gateway.yaml surfaces; none of the three raises touched that reasoning, and it still governs those
  four.
- ✅ **Tooling — RESOLVED 2026-08-27, annotation retired.** This zone previously warned that
  `.plan/execute-script.py` was stale (Jul 22) and failed with
  `ModuleNotFoundError: No module named 'plan_logging'`, and that the ledger had to be driven with
  TokenSheriff's executor. That is no longer true: commit `cea163c`
  (*"chore(steward): refresh marshal config…"*) refreshed the executor, and the whole `cleanup`
  re-grounding pass ran through this repo's own copy. No `marshall-steward upgrade` is owed.

## Ordered Queue

<!-- GENERATED BLOCK — never hand-write the table between the markers. Per-row narrative goes in
     the annotation zone below, outside the markers. -->

<!-- BEGIN GENERATED: ordered-queue -->
| # | Plan | Workstream | Status | Surface (expected) |
|---|------|------------|--------|--------------------|
| — | (empty) | — | — | — |
<!-- END GENERATED: ordered-queue -->

### Queue annotations

<!-- ANNOTATION ZONE — hand-written, OUTSIDE the generated table markers. -->

- ✅ **RETIRED 2026-09-23 — PLAN-29 shipped (#348).** See `landings/PLAN-29.md` and the "PLAN-29
  landing and inbox drain" Decisions entry. This was the epic's last staged plan — the queue is now
  fully terminal.
- ▶ **2026-09-23 — PLAN-29 EMITTED and auto-recorded `launched`** (`auto_emit=true`), intended as
  this epic's LAST plan. Staged after a full close-readiness sweep re-verified every live Open Defect
  and Watch against HEAD `1994f28`, found 13 already resolved (never marked), retired 5 Watches as
  moot, and confirmed the genuinely-remaining residue is small enough to bundle into one 3-deliverable
  plan: `TokenRefreshCoordinator.scopeDelta` observability, a stale `BuildGateCoverageContractTest`
  line citation, and a new doc for the machine-local port-collision flakiness. Two further candidates
  (the `GatewayEdgePipelineTest` flake, the `TlsEdgeProducerTest`/`SniFrontListenerTest` loopback
  residual) were verified already fixed by unrelated PRs from sibling epics and dropped before
  staging — see the spec's Claim Labels for the evidence. `corpus surfaces` confirms `declarative`
  (7/7 paths resolved). Same known admission-gate false-positive as PLAN-28's emit
  (`candidate_comparison_determinate: false`, caused by the `NO_PLAN` sentinel) — verified directly,
  emitted anyway.
- ✅ **RETIRED 2026-09-23 — PLAN-28 shipped (#341).** See `landings/PLAN-28.md` and the "PLAN-28
  landing and inbox drain" Decisions entry. The stray candidate-lesson-scanner surface note below is
  now moot — the epic is once again fully terminal except for the ADR-0050 routing above.
- ▶ **2026-09-22 — PLAN-28 EMITTED and auto-recorded `launched`** (`auto_emit=true`; operator asked to
  emit it directly). ⚠ **Disjointness admission note**: the third conjunct
  (`candidate_comparison_determinate`) read `false` on the `corpus cross-check` read, not `true`.
  Root-caused rather than overridden blind: the `live_plan` candidate population was exactly 1, and
  that one row was `.plan/local/plans/NO_PLAN` — the documented plan-marshall infra sentinel
  (`status.json`: *"Shared directory backing genuinely plan-less callers"*, `metadata.sentinel: true`),
  not a real plan. Its `references.json` carries no comparable surface, so the scanner reported it
  `indeterminate`, and per ADR-019 that makes the comparison as a whole indeterminate rather than
  clean. Verified directly (not re-derived from the count): `.plan/local/plans/` holds nothing but
  `NO_PLAN` — no other repo-local plan is running, so there is no real collision risk. Emitted anyway
  on that direct verification. **Candidate tooling gap for truthful-signals, not yet routed**: the
  `live_plan` candidate scanner should exclude the `NO_PLAN` sentinel the way it presumably excludes
  other known non-plan directories, or every epic's `next`/emit in every repo hits this same false
  `indeterminate` whenever no other plan happens to be running.
- ▶ **2026-09-22 — PLAN-28 STAGED (WS-09, new), and PLAN-09/19/20/21/22/27 transitioned `parked` →
  `superseded`.** Operator asked the orchestrator to sweep every Open Defect, Watch and parked plan
  and determine what remains. Finding: all six parked plans were already fully absorbed into shipped
  PLAN-24 (#305) / PLAN-25 (#306) / PLAN-26 (#314) per the 2026-09-15 corpus-regroup — confirmed, not
  assumed, by re-reading PLAN-25's landing report for the highest-risk absorbed item (the fifth
  unpinned egress leg) and finding it shipped as `egress_tls.oidc_verify_hostname`/`oidc_tls_profile`.
  The queue vocabulary now accepts `superseded` as a `--transition` target (it did not on 2026-09-15,
  the reason they were parked instead), so all six were transitioned rather than left `parked`.
  Separately, six genuinely unowned Open Defect items — never absorbed by any shipped plan — were
  re-verified fresh at HEAD `3e3addc` (not transcribed from their original, older, dated prose) and
  bundled into ONE new plan, PLAN-28, in a new workstream WS-09 (none of the eight existing
  workstreams' charters fit this eclectic bundle without dilution). Two candidates from the older
  prose were checked and DROPPED as already resolved: the TLS/key-material diagram (shipped by
  PLAN-24 as `doc/resources/diagrams/tls-key-material.svg`, embedded in `tls-scenarios.adoc:66`) and
  a stale `release.yml` comment (no longer present). `corpus surfaces`/`corpus verdicts`/`corpus
  cross-check` confirm PLAN-28 parses `declarative` (18/18 paths resolved, 0 unresolved), carries no
  blocking claim-verdict row, and has no live sibling in the queue to collide with (it is now the
  only non-terminal row). Not yet emitted — the operator has not asked for a launch.
- ✅ **PLAN-23 SHIPPED 2026-09-21 (#336 → `cc10ce2`), and IT WAS THE LAST LIVE SPEC.**
  Full landing record: `landings/PLAN-23.md`. Verified against ground truth: `git log
  origin/main` shows `cc10ce2` at HEAD with the matching commit message; `ci pr view
  --pr-number 336` confirms `state: merged` and the same `merge_commit_sha`.
  ⛔ **THE CORPUS IS NOW EMPTY OF LIVE WORK.** All 27 rows are terminal: 19 shipped, 1
  landed, 1 superseded, 6 parked=superseded (never emitted). **The epic is closeable.**
  🔄 **SUPERSEDED BY THE 2026-09-22 ANNOTATION ABOVE**: the corpus is no longer entirely terminal —
  PLAN-28 (WS-09) is now `staged` and the six parked rows are formally `superseded` rather than
  `parked=superseded` prose. Read this entry as the historical record of the 27-row terminal state,
  not the current one.
  ✅ **Deliverable 5's Expected-Surface residual (`SniFrontListener.java`) is a measured
  over-declaration, not a gap** — the predicted two-file gate-churn rewrite it was
  scoped against did NOT reproduce, so the file was declared but correctly never
  touched. ✅ **Deliverable 6's HYPOTHESIS claim (spec claim index 2, the
  `WebSocketRelayStageTest` flakiness question) is now stamped** `contradicted \|
  rescoped: yes` via `corpus set-verdict`, checked at `cc10ce2` — no artifact and no CI
  signal across the 30 most recent Maven Build runs.
  🔄 **Two inbox messages drained (2/2)**: the plan's own machine-readable
  `landing-facts/1` block (`complete: true`) reconciled this landing; a
  `candidate-lesson` message was **Promoted** — not to `manage-lessons` (its
  `category: best-practice` is not a lessons category) but to the project's own
  architecture best-practices via `architecture enrich best-practice --module
  api-sheriff`, exactly as the payload's own `verb:` field named — recording that
  Sonar test-shape rules (`java:S3577`, `java:S2699`) are suppressed by design on
  ArchTest positive/negative-control specimen fixtures.
  ⛔ **NEW OPEN DEFECT — Sonar `java:S3398` on `UpstreamAssetSource.defaultSslContext()`**
  (finding `93017f`), surfaced by PLAN-23's own Residue note: the branch moved that
  method's call site (confirmed in the PR diff, `UpstreamAssetSource.java` +/-81 lines)
  but did not author the method, so it declined the fix in-scope. **This is the one
  item worth a decision before `close`** — fold into a tiny follow-up plan, resolve it
  ad hoc, or accept it and close with the defect recorded for a future epic.
  ✅ **ROUTED 2026-09-23, not decided here.** Re-verified at HEAD `070eda5` — the method is
  unchanged since PLAN-23. Rather than this closing epic deciding its disposition, it was handed to
  `api-sheriff-0-2-0`'s inbox as a `finding` (`deployment-configurability-020.md`, envelope
  validated) — that epic continues touching this repository's code and can own the decision
  (fold/resolve/accept-and-close) instead of it being left to whichever epic happens to be open next.
  This closes the "decision owed before close" obligation by transferring ownership, not by deciding.

- ▶ **2026-09-15 — PLAN-26 staged (refresh failure dispositions + IdP-side reuse detection shipped and proven,
  12 deliverables, unit AND IT lanes).** Order once slots free: **PLAN-26 after PLAN-25, and not concurrent with
  PLAN-18** (both run IT lanes — shared local images); then **PLAN-23 after PLAN-18, PLAN-25 and PLAN-26**.
  PLAN-27 shows `parked`: read as **SUPERSEDED** by PLAN-26 — never emit.

- ▶ **2026-09-15 — PLAN-24 SHIPPED (#305).** Round 1 now PLAN-18 + PLAN-25 running. ⚠ **Shared-image
  hazard for future pairings**: plans that run IT lanes (`-Pintegration-tests`, `-Pjfr`) on one machine
  share the local `api-sheriff:*` image tags, which no Expected Surface declares. PLAN-23 is unit-lane only
  and unaffected; any future IT-lane pair is sequenced or told to verify the container revision label.

- ▶ **REGROUP 2026-09-15 — the live queue is FOUR plans.** PLAN-09, PLAN-19, PLAN-20, PLAN-21 and
  PLAN-22 show `parked`: read that as **SUPERSEDED** (by PLAN-24 / PLAN-25; the status vocabulary has
  no `superseded`). ⛔ **Never emit them.** Round 1 = PLAN-18, PLAN-24, PLAN-25 (mutually disjoint).
  PLAN-23 waits for PLAN-18 **and** PLAN-25. See `## Decisions` § "Corpus regroup — 2026-09-15".

- ▶ **EMIT ROUND 2026-09-10 — nothing running, three slots free, TWO emitted and the third
  DELIBERATELY LEFT UNFILLED.** PLAN-16's landing unblocked PLAN-17, PLAN-18 and PLAN-19 at once.
  **PLAN-08** and **PLAN-17** emitted (queue order, disjoint).
  ⛔ **PLAN-18 is excluded by a collision the matcher CANNOT SEE** — it declares ten named files
  inside `integration-tests/…/integration/` while PLAN-08 declares that **directory**, so
  `cross-check` reports nothing. Recorded in PLAN-18's own spec in advance; this is the round it
  would have been walked into. **PLAN-18 goes next, after PLAN-08 lands.**
  ⛔ **PLAN-19 is HELD despite qualifying, and the reason is not disjointness.** Its deliverable 3
  sweeps README and `doc/` for claims the artifact does not support — while PLAN-08 and PLAN-09 are
  actively editing `doc/`. **A release-readiness review of documentation being concurrently rewritten
  reviews a tree that is not the one being released.** It should run **last**, once the doc-changing
  plans have landed. ⚠ The machine would have admitted it: its only visible collision is with PLAN-09
  on `doc/user/README.adoc`, and PLAN-09 is not launched. Disjointness is necessary, not sufficient —
  this is the case where the difference bites.
- ✅ **PLAN-08 is much smaller than it looks** — the cleanup and PLAN-07's landing retired its
  deliverables 1, 2 and 5. What remains is the **runnable plain-HTTP instance and its end-to-end
  proof** (`sheriff-config-plain-http/` still does not exist), the remainder of deliverable 3, and
  the issue #285 fold.
- ✅ **PLAN-17's brief was sharpened by PLAN-16's landing before emission**: its own hypothesis that
  the browser limit applies to the whole `Set-Cookie` line **was confirmed** — the usable value
  budget is **4019, not 4096** — and the refresh leg is explicitly *not* covered by PLAN-16's new
  assertion, which is precisely the case PLAN-17 must prove.

- ⛔ **ISSUE #285 FOLDED INTO PLAN-08's DELIVERABLE 4 (2026-09-10, operator direction), with the
  surface updated in the same act — 9 → 10 resolved entries, verified through the parser.** PLAN-08
  is the only *live* spec declaring `integration-tests/docker-compose.yml`, so it is the correct
  host.
  ✅ **The documentation half needs no removal — it is the half that is RIGHT.** The direction said
  "source and documentation"; on inspection the only doc occurrence is
  `doc/user/environment-variable-overrides.adoc:161-166`, a WARNING block naming this exact mistake:
  *"Writing `__` where a dash belongs produces a variable that resolves to nothing… the material is
  silently absent rather than rejected."* **The compose file does precisely what the repo's own
  documentation forbids, ten times over.**
  ⛔ **The fold requires settling DELETE-versus-CORRECT before removing anything.** Surplus → delete;
  broken → the anchor was genuinely needed and has been silently absent, so correct the spelling
  instead, because deleting would make a real gap permanent. The doc's own sentence supplies the
  test: the failure *"surfaces later as an opaque handshake error against an endpoint whose anchor
  was never loaded"* — if no such error exists in the stack today, they are surplus.
  ⛔ **THIRD APPEARANCE OF THE SAME CONVENTION ERROR, which is the case for a guard rather than a
  third manual removal**: these ten pairs (pre-existing); the new gate accepting `KEY__STORE` (caught
  by CodeRabbit in PR #283 by reading SmallRye 3.17.2 sources); and PLAN-07's own `security-audit`
  step **introducing** the same wrong convention into the docs before correcting it. The fold asks
  for a mechanical check refusing `__` in a `QUARKUS_*` variable except where a quoted profile name
  requires it, and requires the decision be recorded either way.
- ⚠ **OBSERVATION TO SETTLE AT PLAN-16's LANDING — it appears to have realized a file its spec
  deliberately EXCLUDED.** `corpus cross-check` now returns a `live_plan` row: PLAN-08 ∩ running
  `cookie-deliverability-and-ceiling` on **`doc/configuration.adoc`**. That is the file PLAN-16's
  spec fenced off — *"deliberately NOT declared… if deliverable 4 turns out to need it, stop and
  sequence behind PLAN-07 rather than widening this surface mid-flight."* ⚠ **Do not read this as a
  violation yet**: the live-plan surface comes from `references.affected_files`, which can be
  predicted rather than realized. ✅ **And the reason for the fence is gone** — PLAN-07 shipped, so
  nothing collides today. **Verify at PLAN-16's landing** whether the file was actually touched, and
  if so whether the spec's stop-and-sequence instruction was considered. The process point stands
  even though the hazard evaporated.

- ⛔ **PLAN-18 — STAGED 2026-09-10 from RUNNING PLAN-16's deliverable-5 audit.** Nine pro-forma
  methods across eight files, from an **exhaustive** read of all 56 files under
  `integration-tests/src/test/**` at `497c592`. PLAN-16's spec required that audit to *report*
  out-of-lane findings rather than fix them; PLAN-18 is where they get fixed, with the audit's
  per-site prescribed fix carried across verbatim.
  ✅ **Added one deliverable the audit did not ask for**: prove each fix **by reversion**, not by
  green — a strengthened test must fail when the behaviour it names is reverted. That is the epic's
  own standing rule applied to tests, and it is precisely the check all nine sites failed. ⚠ Three of
  the fixes are *derive from the sibling constant* rather than new assertions, so reversion there
  means moving the **source** constant and confirming the dependent test follows.
  ⛔ **Two sequencing hazards written in ADVANCE rather than discovered at an emit round**: it
  hard-depends on running PLAN-16 (whose deliverables 2-4 touch the same tree, so every
  `497c592`-relative citation must be re-read — the staleness that has bitten this epic twice), and
  it declares **ten named files inside `integration-tests/…/integration/` while PLAN-08 declares that
  DIRECTORY**, so the equality matcher will report **no** collision. They must not run concurrently.
  ⚠ **Scope note carried from the audit**: the remaining `test-corpus-integrity.adoc` backlog is
  **29 files / 87 marker occurrences entirely inside `api-sheriff/src/test/**`**, five of them
  entered by corpus growth rather than any decision. The integration-lane half is now empty — **do
  not read the exhaustive integration pass as coverage of the 29 files nobody has opened.**

- ▶ **PLAN-16 — OPERATOR-CONFIRMED STARTED 2026-09-09, row `running`** (`1-init`,
  `location: current`). ⚠ It runs on the main checkout; PLAN-07 has since moved into its own
  worktree, so only one plan sits on main and the two-plans-on-main hazard is not live. The one dirty
  file is `.plan/marshal.json`.
- ✅ **Its verify-first caught a false premise BEFORE the start — in the source finding, not in the
  code.** PLAN-05's message asserted *"zero hits for `length` / `4096` / `budget`"* across the two
  cookie ITs. At HEAD `481b05f` that grep returns **2 hits in each**, and every one is noise: a
  Javadoc sentence about base64 padding (`BffCookieSessionIT:278`) and an array index in a tamper
  test (`raw[raw.length - 1] ^= 0x01;`, `:289`).
  ⛔ **The finding is right and the search that establishes it is wrong** — no cookie IT asserts the
  sealed value's size, but anyone running the suggested grep gets hits and could conclude the premise
  is refuted. The claim was promoted `HYPOTHESIS → OBSERVED` and rewritten to say *look for an
  assertion on the emitted `Set-Cookie` value's size*, with an explicit "do not run the bare grep".
  ⚠ **Left alone this would have cost the plan an outline detour disproving its own brief** — the
  cheapest possible failure to prevent, and only visible because the claim was re-read rather than
  trusted.

- 🔄 **PLAN-17 deliverable 4 REWRITTEN 2026-09-09 as a PIPELINE + THREAT-MODEL task (operator
  direction).** The operator sketched `cookie(s) → serialize → base64 → compress → encrypt` and asked
  for the order to be verified — correctly, because **two stages are misplaced**. The corrected order
  is recorded in the spec as **serialize → compress → encrypt → base64url → split**, with a reason
  per stage: compression must PRECEDE encryption (ciphertext is high-entropy and does not compress),
  and base64 must FOLLOW it (it is transport encoding; base64 before compression inflates ~33% and
  then compresses that inflation back out). Splitting is last, over the final encoded string, so
  chunking is pure byte-slicing.
  ✅ **And the insertion point is already located**: `SealedSessionPayload.java:103-105` shows today's
  pipeline is **serialize → seal**, with `encode()` producing UTF-8 bytes the codec seals — so a
  compression stage goes *between* `encode()` and the seal, making this an **insertion rather than a
  re-ordering**. A second claim flags that the field-level `Base64` at `:109` may sit INSIDE the
  sealed plaintext, which would inflate what compression then has to work on.
  ⛔ **The threat model is scoped rather than gestured at.** Compress-then-encrypt is the CRIME/BREACH
  shape, and the spec names the discriminator: those attacks need **many samples with attacker-varied
  input**, while this cookie is sealed **once at login** (and on refresh), not per request. So the
  oracle is far weaker than the per-response case — and the spec requires stating whether that makes
  the risk *acceptable* or merely *unlikely*, **which are different answers**. A verdict of "does not
  apply here" is legitimate but must be reached and recorded as an ADR, not assumed.
- ⛔ **PLAN-17 — STAGED 2026-09-09, and it exists because PLAN-16 does NOT solve the cookie problem.**
  PLAN-16 deliverable 2 turns `refresh.enabled: false` on the cookie overlay — that returns `main` to
  green and is **a deliberate retreat, not a fix**. After PLAN-16, the honest capability statement is
  *cookie mode and refresh do not work together*, and deliverable 4 will make the gateway **refuse**
  the seal rather than emit it — better, and still not working. PLAN-17 makes them work together.
  **Four routes in the operator's binding preference order**: enlarge (highly preferred, most likely
  refused — the limit is the browser's); **split across multiple cookies** (operator-raised as
  potentially the most elegant, since it *circumvents* the limit); reduce via fewer claims
  (Keycloak-side, the preferred reduction); package/compress (last). ⚠ **Not mutually exclusive** —
  the spec asks for the combination, not the first route that works.
  ✅ **Two findings from staging that shape it.** The payload is **three JWTs with no compression
  stage at all** (`bff/cookie/` has `Base64` and no `Deflater`/`GZIP`), and the **ID token rides every
  request for a logout-only purpose** (`SealedSessionPayload.java:51` — *"retained for the logout
  `id_token_hint`"*), which may be a larger win than trimming claims.
  ⛔ **On splitting specifically, the spec engages the recorded rejection rather than rediscovering
  it.** `SealedSessionCookieCodec.java:70-72` calls it *"a deliberate non-goal"* — but read precisely,
  **that is a SIMPLICITY argument, not a SAFETY one**: it says the operator is *expected* to reduce or
  switch modes. So reopening it is a product decision. ✅ And one argument the original does not appear
  to have weighed: **the seal makes splitting fail-closed by construction** — the AEAD tag covers the
  whole plaintext, so a missing or stale chunk yields a failed tag and a clean rejection, never a
  half-session. The spec requires verifying that against the actual construction before relying on it.
  ⛔ **The trap the spec names**: every request carries all chunks, so the `Cookie` header grows into
  the gateway's pre-route header-value cap — the *same declared number* that
  `SealedSessionCookieCodec.java:74-79` records as having broken cookie mode once before when two
  constants disagreed.
  **Sequenced behind PLAN-16 — hard, on its deliverable 3.** Without that deliverability assertion a
  "fix" that still overflows is indistinguishable from one that does not, which is exactly the
  blindness that produced this situation. Also overlaps it on `doc/user/bff-cookie.adoc`.
  ✅ **Not urgent and must not be treated as urgent** — `main` goes green via PLAN-16 regardless.
  ⚠ **A negative verdict is a legitimate outcome**: if the budget cannot be enlarged and the payload
  cannot be reduced enough, the deliverable is a documented statement that cookie mode does not
  support refresh and server mode is the supported path.

- ⛔ **PLAN-16 — STAGED AND EMITTED 2026-09-09 AGAINST A RED `main`, and it outranks the queue.** It
  carries PLAN-05's own D1/D2/D3 verbatim from message `…-007.md`. **Deliverable 1 alone returns
  `main` to green** and is config-plus-docs only.
  ✅ **Disjointness verified rather than assumed** — `corpus cross-check` returns **no `live_plan`
  row** against running PLAN-07, and the surfaces confirm it by inspection: PLAN-07 holds `tls/`,
  `application.properties`, `doc/LogMessages.adoc`, `doc/adr/`, `doc/configuration.adoc` and
  `doc/security-threat-model.adoc`; PLAN-16 holds none of them.
  ⛔ **`doc/configuration.adoc` was deliberately EXCLUDED from PLAN-16's surface** even though
  `max_cookie_size` is documented there, precisely because PLAN-07 declares it. The spec instructs:
  if deliverable 3 turns out to need that file, **stop and sequence behind PLAN-07** rather than
  widening mid-flight. That is the epic's under-declaration lesson applied in advance for once,
  rather than after a landing measured it.
  ⚠ **Its own verification depends on a gate it cannot run before merging** — `Demo Client E2E` is
  post-merge on `main` only. Deliverable 2 exists to move that signal onto the PR; until it lands,
  this plan ships on the same blind spot it is closing.
- ✅ **PLAN-05 SHIPPED WITH REGRESSION, recorded that way deliberately** (PR #282 → `b5369ca`,
  `landings/PLAN-05.md`). Its reproduction **succeeded** and the root cause was upstream of the
  leeway arithmetic entirely: `CallbackEndpoint` never stored the refresh token, so
  `TokenRefreshCoordinator.java:127` returned at its first guard **before any logging** — which is
  why the failure was silent and why an IdP-revoked session kept answering 200. ⚠ **TokenSheriff's
  H4 was right and Residual 4 was not where it lived** — this epic carried discovery-resolved
  metadata as the highest-priority lead, and the fault was upstream of the refresh call.
- ⚠ **PLAN-08 and PLAN-09 are unblocked by PLAN-05's ship but still collide with running PLAN-07** on
  `doc/configuration.adoc` and `doc/security-threat-model.adoc`; PLAN-09 additionally hard-depends on
  PLAN-07. Both wait.

- **PLAN-07 — EMITTED and auto-marked `launched` 2026-09-08.** Its dependencies (PLAN-02, PLAN-06)
  shipped long ago; PLAN-04's landing freed the `doc/configuration.adoc` contention that held it.
  ⚠ **ADR ordinal is `0042`** — `doc/adr/` now ends at `0041` (PLAN-04's). Re-resolve at outline.
  ⚠ Its subject — *"configuring trust through Quarkus REPLACES the platform CA bundle rather than
  extending it"* — now sits directly beside PLAN-04's finding that a **fifth** egress leg carries the
  `client_secret` with no bound hostname posture. Both are about what the gateway silently trusts on
  egress; PLAN-07 should read that defect before scoping.
- ✅ **PLAN-08 — BLOCKED BY THE MACHINE, and the machine is RIGHT this time.** `corpus cross-check`
  reports a genuine `live_plan` row: PLAN-08 ∩ running PLAN-05 on
  `integration-tests/docker-compose.yml`. ⚠ **Worth recording as a positive**: after three landings
  where the gate was blind (directory-vs-file twice, production/test-pair once) this is a checked
  collision on an exact path, caught without judgement. The blind spots are real and so is the gate.
- **PLAN-09 — still sequenced, two ways.** It hard-depends on PLAN-07 for scenario 6 *and* collides
  with it on `doc/configuration.adoc`, so it could not have taken the second slot even if its
  dependency were waived. ⚠ Its recorded split option (ship scenarios 1-5 now) remains available and
  remains a deliberate re-scope decision, not a default.
- ⚠ **One slot is unfilled and that is correct, not a shortfall to fix.** N−R = 2, one emitted:
  PLAN-08 is blocked by a live collision and PLAN-09 by a dependency plus a collision. Filling it
  would mean emitting a collider.

- ▶ **PLAN-05 — OPERATOR-CONFIRMED STARTED 2026-09-07, row `running`. The phantom is over.** It had
  been `launched` with no plan record since 2026-09-04; that watch is now closed by the start rather
  than by a release. Verify-first discharged first: `accessTokenLifespan: 900` confirmed in
  `integration-realm.json:6` **and** `benchmark-realm.json:6`, the conceding comment intact at
  `BffSessionMediationIT.java:103-105`, `TokenRefreshCoordinator` at `bff/refresh/…:73`. ⚠ **One
  citation was stale and was corrected in the same act** — the discovery seam is
  `BffRuntimeProducer.java:213`, not `:214`, which is the line my own 2026-09-07 prior-art fold
  wrote. Fixed in two places before the transition, since `running` would have barred it.
- ⛔ **TWO PLANS ARE NOW EXECUTING ON THE MAIN CHECKOUT AT ONCE — the recorded 2026-09-03 hazard, back
  (2026-09-07).** `manage-status list` shows PLAN-04 (`jwks-hostname-verification`) at `1-init` with
  `location: current`, and PLAN-05 starting into the same checkout; neither worktree is cut yet,
  because the lifecycle creates them later. **Until both worktrees exist, both plans are operating on
  `/Users/oliver/git/API-Sheriff` at `5467a80`.** ⚠ The two are surface-disjoint in their *declared
  work* — PLAN-04 in `…/auth/`, PLAN-05 in `integration-tests/` — so the risk is not their code
  colliding; it is `.plan/` state and any uncommitted tree churn while they share one working
  directory. ⚠ **Re-check `git status` after any tool run that touches `.plan/`**, exactly as the
  earlier instance taught. ⚠ **Also expect verify-budget contention**: this epic measured local runs
  clipped at wall-clock under a concurrent plan (load 150–200), and PLAN-05's Docker
  `-Pintegration-tests` suite is the heaviest thing in the repository.

- ▶ **PLAN-04 — OPERATOR-CONFIRMED STARTED 2026-09-07, row `running`.** Verify-first discharged before
  the transition: `JwksTrustProfileResolver.resolve(:119)` and `TokenValidatorProducer` intact,
  `pom.xml:93` still `<version.cui.http>3.0</version.cui.http>`; the two commits since `a8c9834`
  (#270 steward reconcile, #271 `review_rate_window_await` off) are config-only and touch no claim
  surface. ⛔ Its refutation block is the brief — the mechanism is `verifyHostname`, the question is
  the `sslContext` conflict, and the ADR ordinal is `0041`.
- ▶ **PLAN-05 — command re-emitted 2026-09-07 for an operator start.** Row was already `launched`
  since 2026-09-04 with no plan record, so no transition was made; the phantom simply ends when the
  record appears. ✅ Disjoint from running PLAN-04 on every dimension including the hidden one —
  PLAN-04 declares `api-sheriff/src/test/java/…/auth/`, nowhere near `integration-tests/`, so the
  directory-vs-file blindness that barred PLAN-05 ∥ PLAN-03 does not apply. ⚠ Its brief was sharpened
  on 2026-09-07 by the TokenSheriff prior-art fold: copy the in-realm client-attribute fixture
  pattern, **re-derive the lifespan value** against `TokenRefreshCoordinator` rather than reusing 35s,
  and start at Residual 4 (discovery-resolved metadata, `BffRuntimeProducer.java:214`).

- **PLAN-04 — EMITTED and auto-marked `launched` 2026-09-07**, its hard dependency on PLAN-03
  satisfied by `a8c9834`. ⛔ **Read its new opening block before anything else** — its mechanism was
  refuted the same day and the spec now leads with the refutation: cui-http 3.0 ships
  `verifyHostname(boolean)`, so the hand-rolled trust manager is retired and the TokenSheriff
  coordination is dissolved. Its central question is now the `sslContext` conflict, not the mechanism.
  ⚠ **ADR ordinal**: `doc/adr/` ends at `0040` (PLAN-03's), so **`0041` is next free** — re-resolve at
  outline, never trust a filename.
- ✅ **PLAN-05 IS NOW CLEAR TO RUN — the blocker I named on 2026-09-07 was PLAN-03, and PLAN-03 has
  landed.** It is disjoint from PLAN-04 on every dimension including the hidden one: PLAN-04 declares
  `api-sheriff/src/test/java/…/auth/`, nowhere near `integration-tests/`, so the directory-vs-file
  blindness that made PLAN-05 ∥ PLAN-03 unsafe does not apply here. ⛔ **But it has had NO plan record
  since 2026-09-04** — the phantom watch is three days overdue and this is its second occurrence.
  Either start it or release it to `staged`; leaving it `launched` holds a slot on an unsubstantiated
  claim for the second time.
- **PLAN-07, PLAN-08 — sequenced behind PLAN-04, and the reason is unchanged.** All three collide
  pairwise on `doc/configuration.adoc` (PLAN-04 ∩ PLAN-07 also on `doc/adr/` and the threat model).
  PLAN-04 took the slot on queue order. ⚠ PLAN-07 also declares `doc/adr/` and must re-resolve the
  ordinal against `0040` having been taken.

- ▶ **PLAN-03 — OPERATOR-CONFIRMED STARTED 2026-09-06, row now `running`.** ⚠ No plan record at the
  transition (`manage-status list` shows only `NO_PLAN`) — expected pre-`phase-1-init`, the same shape
  PLAN-10 and PLAN-15 both showed before registering normally.
- ✅ **Its verify-first clause was discharged BEFORE the transition, and it CAUGHT SOMETHING.** Five
  claims re-read at `3fc4c83` and stamped; **four line citations were stale and were corrected in the
  same act**, which `running` would have barred an hour later:

  | Citation | Was | Is | Moved by |
  |---|---|---|---|
  | `DispatchStage` `.setSsl` | `:244` | `:243` | drift since `c6e6f52` |
  | `WebSocketRelayStage` `.setSsl` | `:148` | `:147` | drift since `c6e6f52` |
  | `GatewayEdgeRoute` HTTP/2 options | `:1295-1297` | `:1294-1296` | drift since `c6e6f52` |
  | tripwire method / constant / comparison | `:513` / `:515` / `:518` | `:519` / `:521` / `:524` | **PLAN-13's own rewrite of that file** |

  ⛔ **Every claim's SUBSTANCE held** — `setVerifyHost` is still absent repo-wide, all three binding
  sites exist with identical code, the tripwire is intact, and `builderSeededFrom` still makes exactly
  24 `.component(preset.component())` calls, **counted at HEAD rather than carried over**. Only the
  coordinates had moved. ⚠ **The last row is the instructive one**: PLAN-13 shipped two days ago and
  its rewrite of `GatewayEdgeRouteTest.java` displaced a citation that deliverable 6 — authored
  *after* PLAN-13 merged but read *before* it — still pointed at. A citation taken from a
  pre-merge read is stale the moment that merge lands, even when the reading session felt current.

- ⛔ **THE EPIC'S SERIALIZATION BOTTLENECK IS NOW MEASURED, AND IT IS NOT PLAN-13 (2026-09-06).**
  PLAN-13's merge unblocked **three** plans at once — PLAN-03, PLAN-07, PLAN-08, each of which was
  blocked solely by a gate blind spot against it — and freed a slot, leaving `N − R = 2`. **Only ONE
  could be emitted.** `corpus cross-check` reports all three pairwise colliding:

  | Pair | Overlap |
  |---|---|
  | PLAN-03 ∩ PLAN-07 | `doc/configuration.adoc`; `doc/security-threat-model.adoc` |
  | PLAN-03 ∩ PLAN-08 | those two + `integration-tests/src/test/…/integration/` |
  | PLAN-07 ∩ PLAN-08 | `tls/` + `application.properties` + both docs |

  ⛔ **`doc/configuration.adoc` is declared by NINE of the fifteen specs.** It is the single most
  contended path in the corpus and it serializes almost the whole remaining epic — a *documentation*
  file, not a code one. ⚠ **Raising `parallelization_scope` cannot help**: the knob caps concurrency,
  disjointness decides eligibility, and the second slot goes unfilled rather than filled with a
  colliding plan. The only things that would help are structural — splitting the shared docs per
  concern, or accepting the serialization as the epic's real shape. Recorded so a future session does
  not re-diagnose an idle slot as a knob problem.
- **PLAN-03 — EMITTED and auto-marked `launched` 2026-09-06** (`auto_emit`). It took the one usable
  slot on queue order, and that also happens to be the highest-value choice: it is the chain head
  PLAN-04 hard-depends on, so emitting it is the only move that shortens a two-deep chain.
  ⚠ **Carry the cui-http hazard into it**: PLAN-03 declares
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/` as a DIRECTORY, which contains
  `SecurityConfigurations.java` — the file the pre-diagnosed parent-pom 1.6.3 break must edit. If an
  automated 1.6.3 bump PR arrives while PLAN-03 runs, they collide on that file.
- **PLAN-07 and PLAN-08 — sequenced, not blocked.** Their dependencies are satisfied and their
  surfaces are declarative; they lose only to PLAN-03 on queue order and to each other on overlap.
  Whichever of the two goes next, the other waits — they collide on four entries including `tls/`.

- ▶ **PLAN-15 — OPERATOR-CONFIRMED STARTED 2026-09-04, row now `running`.** ⚠ No plan record was
  observable at the transition (`manage-status list` showed only `NO_PLAN` and PLAN-13) — expected
  pre-`phase-1-init`, and the same shape PLAN-10 showed before it registered normally. The
  discriminator is elapsed time; re-verify on the next check.
- ✅ **PLAN-15's verify-first clause was DISCHARGED before the transition, not deferred into it.**
  Main advanced **nine commits** from `337af0d` to `eedfda6` between staging and start, so all three
  load-bearing claims were re-read at the new HEAD and stamped `corroborated` through
  `corpus set-verdict` (claims 0, 3, 4). ⛔ **One of them was genuinely at risk and had to be re-read
  rather than assumed**: PR #262 bumped `quarkus-distroless-image` `5d8bc90` → `ae97db1`, directly
  under the "the image ships no other executable" half of claim 4. Re-read at `Dockerfile.native:26`
  (new digest), `:49` (single `COPY` of `/app/application`), `:61` (`ENTRYPOINT` on it) — a distroless
  digest bump adds no shell, so the claim holds. Had it not, deliverable 4's premise would have moved
  under a plan already being started.

- 🔄 **CORPUS CONSOLIDATION REVIEW 2026-09-04 (operator-requested: "revisit all plans, what can be
  merged sensibly"). Result: EXACTLY ONE merge was available, and it is now applied.** The review is
  recorded with its arithmetic because the answer is counter-intuitive — the corpus looked ripe for
  consolidation and is not.

  | Spec | Deliverables | Merge verdict |
  |---|---|---|
  | PLAN-03 | 5 | ⛔ no — any pair among these six yields 10 |
  | PLAN-04 | 5 | ⛔ no — and see the standing mechanism decision below |
  | PLAN-05 | 5 | ⛔ no — sole `integration-tests/` surface, no partner |
  | PLAN-07 | 5 | ⛔ no |
  | PLAN-08 | 5 | ⛔ no |
  | PLAN-09 | 5 | ⛔ no — and merging it would be actively harmful, see below |
  | PLAN-14 | **1** | ✅ **the only undersized spec; merged into PLAN-15** |

  ⛔ **The blocking fact is arithmetic, not judgement.** Six of the seven carry **exactly five**
  deliverables against a split guard that fires at roughly six. Any merge among them produces a
  ten-deliverable plan — **double** the threshold — so every such merge is refused by the standing
  guard before taste enters. The corpus was already correctly sized; PLAN-14 was the sole outlier
  and it was an outlier in the *other* direction.

  ⚠ **PLAN-03 + PLAN-04 was the obvious candidate and is refused on TWO independent grounds.** The
  arithmetic above, and a decision this epic already recorded: PLAN-04's spec states the operator
  asked this once before and the two stay split *"because their MECHANISMS differ — Vert.x
  `setVerifyHost` exists; cui-http has no such lever"*, while the contract they share is decided once
  in PLAN-03's deliverable 2. ⛔ Do not re-open on the strength of the hard dependency between them:
  a strictly sequential pair is not evidence they are one plan.

  ⛔ **PLAN-09 must NOT be merged into PLAN-07 or PLAN-08, and the reason inverts the usual
  batching argument.** It is **documentation-only**, so per CLAUDE.md it skips both the quality gate
  and full verify. Folding it into a gate-requiring TLS plan would *force* it through a gate it is
  currently exempt from — paying cost rather than saving it. Batching is only ever a saving when the
  riders share the heavier footprint class, which is precisely why PLAN-14 (`.java`, gate-requiring)
  belonged with PLAN-15 (`.java`, gate-requiring) and PLAN-09 belongs with neither.
- **PLAN-15 — STAGED AND EMITTED 2026-09-04**, superseding PLAN-14. Carries issue #256's broad-prefix
  threshold (the design-bearing half) plus PLAN-14's Javadoc correction as deliverable 4, sharing one
  gate run. ⚠ **Recorded impurity**: deliverable 4 is a WS-01 concern in a WS-05 plan, accepted
  because WS-01 has no remaining staged work to batch it with and the ADR/source contradiction is
  live. ⚠ Its `corpus cross-check` row shows an overlap with **PLAN-14 on `HealthProbe.java`** — that
  is the *superseded* spec still declaring the file it handed over, not a real collision. A
  superseded spec is retained, never deleted, so this row is permanent and expected.

- 🔄 **PLAN-05 — RELEASED AND RE-EMITTED 2026-09-04 by operator decision. The round trip is not a
  no-op.** Its previous `launched` was a **phantom**: stamped days earlier, never accompanied by a
  plan record, and holding a third of `N=3` capacity while asserting an in-flight state the ledger
  could not substantiate. Releasing it to `staged` made the queue honest; it then re-entered the
  rotation and qualified on merit — `declarative`, `admits_disjointness_check: true`, 5 resolved
  paths, **zero overlap rows of any kind**, `blocking_count: 0`. It took the slot ahead of PLAN-07
  and PLAN-08 purely by queue order among eligible candidates. ⚠ **The failure mode can recur**: an
  emit is only real when the operator runs the command, and `auto_emit` stamps `launched` without
  observing a start. If no plan record appears within a session or two, release it again rather than
  letting a phantom re-accumulate.
- **PLAN-14 — STAGED AND EMITTED 2026-09-04 in the same pass**, from PLAN-10's owed follow-up 1.
  ⚠ Its own spec records that **batching was preferred to solo emission** — a one-Javadoc plan pays a
  full gate-and-PR cycle for a comment fix. It was emitted solo because it was the only eligible
  candidate at the time and its spec is explicit that the defect must not sit indefinitely waiting
  for a companion. If a WS-01 or `api-sheriff` source plan is staged before PLAN-14 actually starts,
  folding it in is still the better outcome — under the same-act rule, updating that spec's Expected
  Surface in the same edit.

- ▶ **PLAN-10 — OPERATOR-CONFIRMED STARTED 2026-09-03 ~19:30, row now `running`.** The operator
  reported starting it; that report is the sole authority for this transition, exactly as the
  emit≠running invariant reserves. ⚠ **No plan record was observable at the moment of the
  transition** — `manage-status list` showed only `NO_PLAN` and PLAN-13 — which is expected for a
  plan whose `phase-1-init` has not yet registered, and is NOT the same shape as PLAN-05's
  launched-never-started. ⛔ **The discriminator is elapsed time, not the absent record**: if a
  record still has not appeared on the next check, this row is wrong and PLAN-10 has joined PLAN-05
  in the same trap. Re-verify before trusting `running`.
- **PLAN-10 — EMITTED and auto-marked `launched` 2026-09-03** (`orchestrator.auto_emit == true`; the
  started/`running` transition remains operator-owned). It took the one slot freed by PLAN-06's ship.
  ⚠ **Its ordinal is `0039`, not the `0038` in its own filename**: `doc/adr/` tops out at `0038`
  (PLAN-02's carrier-key ADR) and PLAN-06 did not touch the directory. The spec already instructs
  re-resolving the ordinal at outline against what has landed — follow that, never the filename.
- **PLAN-03 — blocked by hand, NOT by the machine.** `corpus cross-check` returns no `live_plan` row
  for it, because it declares `edge/WebSocketRelayStage.java` (production) while PLAN-13 declares
  `edge/WebSocketRelayStageTest.java` (its test). Production/test pair of one class; the matcher
  compares paths for equality and a pair is not an equality. Re-check when PLAN-13 lands.
- **PLAN-04 — blocked by a HARD dependency, independent of any surface question.** Its spec: *"must
  not start before [PLAN-03] has landed"*, PLAN-03 being the sole naming authority for both outbound
  surfaces. PLAN-03 is itself blocked, so this is a two-deep chain.
- **PLAN-07 and PLAN-08 — their DEPENDENCIES cleared with PLAN-06's ship; only the gate blind spot
  now blocks them.** PLAN-07 depended on PLAN-02 + PLAN-06 for sequencing (both shipped); PLAN-08
  depended on PLAN-06 for coherence (shipped). Both still declare
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/` as a DIRECTORY against PLAN-13's three
  FILES inside it, which the matcher cannot see. ⛔ **Both unblock the moment PLAN-13 lands** — they
  are the two best-prepared candidates for the next free slot.
- **PLAN-09 — hard dependency on PLAN-07, but only for scenario 6.** Its spec explicitly records the
  split option: *"scenarios 1-5 are documentable today and depend on nothing"*. Taking it needs a
  deliberate split decision (re-scope + re-stage), which is why the slot went to PLAN-10 instead —
  PLAN-10 needed no decision at all. ⚠ Recorded so the option stays visible rather than forgotten.

- PLAN-01 — ✅ **SHIPPED** 2026-08-29 as PR #230 → `909d43f`. Full record: `landings/PLAN-01.md`.
  The running-liveness note and the do-not-re-scope note that stood here are spent and retired.
  ⚠ **One claim this orchestrator made was refuted by the landing** and is recorded so it is not
  re-derived: the pre-launch note said the probe "must read the scheme off the label". Compose labels
  are daemon-side and invisible in-container, so no in-container probe can read one. The shipped
  TCP-accept probe is protocol-blind and covers both schemes. The label indirection remains correct
  for the **host-side** gate, which is where it always lived.
- PLAN-02 — 🟡 **LAUNCHED** 2026-08-29 (`auto_emit` recorded it; operator has NOT confirmed a start).
  Re-grounded at `0d01973` first: all 8 OBSERVED claims re-verified and re-stamped, none stale.
  ⚠ **Two real breaks were found and applied before emitting.**
  `deployment/compose-sample/scripts/wait-for-ready.sh` was deleted by PLAN-01 — deliverable 3 and the
  Expected Surface now name `scripts/start-sample.sh` and the two-layer gate instead. And a Compose
  `healthcheck:` key **overrides** the image's baked declaration rather than merging with it
  (`docker-compose.yml:213-215`), so a context path that moves the probe URL must change the image and
  the compose copy together.
  ⚠ **Quarkus moved to 3.39.1** (`30eb370`) during the re-grounding. The OBSERVED claims are
  version-independent, but deliverable 1's root-path hypotheses are **not** — settle them against
  3.39.1, not an older reference.
- PLAN-02 — After PLAN-01: a context path can move the probe URL, so PLAN-02 owns re-verifying
  PLAN-01's probe under a non-root path. Overlaps PLAN-01 on the compose files.
- PLAN-03 — Overlaps PLAN-02 on `api-sheriff/src/main/resources/application.properties` and on the
  gateway.yaml config model. Sequenced.
- PLAN-04 — Surface-disjoint from PLAN-01/02 (auth package, not the edge or the container), but
  shares the gateway.yaml config-model surface with PLAN-03. ⚠ **Hard dependency on PLAN-03's
  deliverable 2**, which is the single naming authority for both outbound surfaces; PLAN-04 is
  implement-only against it and invents no key names. Do not emit before PLAN-03 lands.
- PLAN-09 — Staged 2026-08-31 from an operator request: a **scenario-organised** `doc/user/` guide,
  each scenario carrying Goal → Preconditions → Concrete configuration (keys and `QUARKUS_*` values).
  ✅ The gap is real and was verified: `tls-edge.adoc` organises TLS by **mechanism** and
  `environment-variable-overrides.adoc` by **key**; neither organises by operator goal, so today an
  operator synthesises their answer from two references plus the compose sample.
  ⛔ **It must not become a third source of truth** — it composes and links, delegating key semantics
  and mechanism rationale to the two existing references by xref.
  ⚠ Documentation-only footprint, so it skips both gates and is the workstream's cheapest and
  earliest operator-facing value. Scenarios 1-5 depend on nothing; only scenario 6 waits on PLAN-07,
  and PLAN-08 adds scenario 7 **into this same document** rather than starting a second guide.
- PLAN-07 / PLAN-08 — Staged 2026-08-28 from a third-party chat transcript the operator pasted.
  ⛔ **The transcript is a lead, not evidence.** Its claims were checked against this repo before any
  ledger write; what remains unchecked rides as a labelled HYPOTHESIS, and both specs' verify-first
  clauses name the chat explicitly as something that may not be cited as evidence.
  ⚠ Much of that chat's advice is **already settled here** — ADR-0025 classifies the whole server-TLS
  surface and `ManagementPlainHttpAudit` already implements the "name the effective mode at boot"
  pattern, keyed on resolved material rather than a config key. The plans EXTEND those; they must not
  re-open them or invent a second pattern.
  ⚠ **PLAN-07 hard-blocks PLAN-08.** PLAN-08's mode is the degenerate "no server certificate" case of
  PLAN-07's contract and shares its boot guard; building the mode first means settling the vocabulary
  twice.
  ⚠ PLAN-08 makes **PLAN-06's `trusted_proxies` load-bearing** — behind a terminating hop the client
  address arrives only in a header. The two must land a consistent story.
  ⛔ Declined and recorded, not dropped: switching the primary artifact to JVM, and the base image to
  UBI minimal. PLAN-07 deliverable 5 writes the trade-off down and explicitly forbids making it.
- PLAN-06 — Staged 2026-08-28 from an operator question. Surface-disjoint from PLAN-01/03/04/05;
  overlaps PLAN-02 on `doc/configuration.adoc` and the shipped `gateway.yaml` / compose-sample files.
  Sequenced **after PLAN-02** by theme, not by dependency — both are "a deployment-varying value an
  operator must express without rebuilding", and they should read as one story.
  ⚠ Its deliverable 1 sets the **precedent for every other list-valued `gateway.yaml` key**
  (`tls.alpn`, `tls.cipher_suites`, `tls.passthrough_sni`, `auth.required_scopes`). That is why the
  three options are staged with costs and the plan chooses — the orchestrator deliberately did not.
- PLAN-02 — 🟢 **RUNNING** since 2026-08-31, operator-confirmed. `plan_marshall_plan_id=configurable-context-path`,
  handed off at phase `1-init`, deep planning lane, worktree enabled. ✅ Verified before the transition
  was recorded rather than taken on report: the plan directory exists (created 11:33:24Z), and
  `inbox detect` on its persisted `source_id` returns `orchestrated: true` for this epic — so its
  landing and its lessons route through the inbox instead of bypassing it, which is the PLAN-01 failure
  that must not repeat. The one-line pointer ingested correctly: `request.md` carries the spec's
  deliverables, claim labels and expected surface as its body.
  🔁 **Re-emitted as-is earlier the same day** at HEAD `c170779`, by operator decision. Ground truth
  had confirmed the 2026-08-29 `launched` stamp was a **phantom launch**: `.plan/local/plans/` held only
  `NO_PLAN`, no worktree, no branch and an empty `pr` field, so the plan was never picked up. ⚠ **That
  is the case the `emit ≠ running` invariant exists for** — `auto_emit` recorded `launched` at emit
  time and nothing ever contradicted it, so the ledger carried a started plan for two days while the
  slot sat idle. Only the operator-confirmed transition above closes that gap, and it is why the two
  states are kept distinct. ✅ Its re-grounding is
  sound despite the stale flags: all 8 claims were re-stamped at `c6e6f52`, which is *after* PLAN-01's
  landing, and the only commits since are `f770f0b` (steward stamp) and `c170779` (`.gitignore`) —
  neither touches a claim surface. The earlier watch saying PLAN-02's claims "should be re-grounded
  before it is emitted" is therefore **spent**; do not re-run it.
  ⚠ Declined alternatives, recorded so they are not re-derived: parking PLAN-02 to emit PLAN-05
  first, re-scoping PLAN-02 before emitting, and raising `parallelization_scope` to 2 to run PLAN-05
  alongside it.
- PLAN-02 — ⚠ **MID-FLIGHT DEFECT surfaced 2026-09-01: the committed `-Dsheriff.context-path` build
  seam is INERT.** The POM profiles map the user property onto a Maven project property (SmallRye
  ordinal ~100) while `application.properties:60-62` declares the three context-path keys literally at
  ordinal 250, which always wins. ⛔ **Deliverable 9 inherits the same defect** — a downstream
  build-parent sets a POM property, landing at the same low ordinal.
  🔑 **Root cause, and it generalises: an analogy that dropped the contest.** The POM comment justifies
  the profile shape by pointing at the `native` profile, which binds `quarkus.native.enabled` and
  `quarkus.ssl.native` the same way — but NEITHER is declared in `application.properties` (line 141
  records `quarkus.ssl.native` being deliberately moved out to the POM). The analogy proves the
  Maven-property source acts when **uncontested**; it never proves it wins a contest at 250. The
  adjacent claim that inactive-by-default is load-bearing "so the build does not ship a declared key
  that some higher-ordinal source silently overrides" is backwards — the override runs the other way.
  💡 **Orchestrator recommendation, ADVISORY ONLY — the plan owns the decision and the
  implementation:** prefer the expression carrier (`quarkus.http.root-path=${sheriff.context-path:/}`,
  `quarkus.management.root-path=${sheriff.management-context-path:/q}`) over dropping the
  declarations. Deliverable 9 is decisive: any literal at 250 defeats a build-parent POM property
  permanently, while the expression resolves `sheriff.context-path`, a name nothing contests at 250 —
  so the CLI `-D` (400), the build-parent POM property (100, uncontested) and the default all work. It
  also REMOVES machinery: both POM profiles and their 25-line justification become unnecessary. Not
  blocked by `ShippedApplicationPropertiesTest`, which detects `%profile` keys only and whose own
  control fixture uses this exact form, and the idiom already ships at `application.properties:292`.
  ⚠ **Verify-first:** the load-bearing assumption is that a Maven CLI `-D` reaches augmentation as a
  system property at ordinal 400. NOT proven here — settle it at TASK-002's config-source assertions.
  If false, dropping the declarations is the fallback and still fixes both the CLI seam and
  deliverable 9.
  ⛔ **Narrowing deliverable 9 to `-D` documentation is the one option with a LEDGER consequence** — it
  is a scope reduction, so if the plan takes it, the narrowing must come back here as a fold and this
  spec's deliverable set and Expected Surface are corrected in the same act.
- ⛔ **SURFACE COLLISION 2026-09-02 — PLAN-02 under-declared by ~4x and the gate was blind to it.**
  Its spec declared **9** paths; its branch at `040f2ac` touches **52**. The Expected Surface has been
  corrected in the same act, per the standard's rule that a collision the gate did not predict is
  evidence a DECLARED surface was wrong. Two real collisions followed:
  ⛔ **`doc/adr/0038`** — PLAN-02 authored `0038-A_build-time_key…carrier_key…adoc` (an unrelated
  subject) and is at `6-finalize`, so it takes the ordinal PLAN-10 was staged to use. PLAN-10 is
  re-scoped: its ordinal claim is stamped `contradicted | rescoped: yes` and deliverable 1, the
  Expected Surface and the sequencing note all now resolve the ordinal **at outline**. ⚠ PLAN-10's
  spec had already written the contingency — but predicted it would come from PLAN-04/06/07, the three
  that DECLARE `doc/adr/`. It came from the one that didn't, which is the whole lesson.
  ⚠ **`doc/user/README.adoc` + `doc/user/environment-variable-overrides.adoc`** — both are PLAN-09's
  declared surface and PLAN-02 is writing them first. PLAN-09 must re-read them at outline rather than
  scoping against what it saw when it was staged.
  ✅ `doc/README.adoc` is untouched, so PLAN-10's index-row patch target is clean — re-verified at
  `5948962`, `index 53da5d8` still matches and `git apply --check` passes.
  🔑 **The correction immediately changed the gate's verdict**: with PLAN-02's real surface declared,
  PLAN-03, PLAN-06, PLAN-07, PLAN-09 and PLAN-10 all now collide with the running plan. Before the
  correction the gate saw none of it. That is the gate working, not failing.
- PLAN-02 — ✅ **SHIPPED 2026-09-03** as PR #248 → `b200bed`. Full record: `landings/PLAN-02.md`.
  ✅ The carrier-key recommendation was adopted and recorded as **ADR-0038**; the inert-seam defect is
  closed. ⚠ **Spec declared 5 deliverables, 10 shipped** — a clean 1:many decomposition except
  deliverable 9 (a published downstream build-parent POM), which was **added unplanned** and is the
  largest scope addition this epic has absorbed without a fold recorded at the time.
  ⚠ **Cost profile worth carrying forward**: 18.5 M tokens (3.4× PLAN-11) and 54 files. Wall-clock
  59h16m but **53h35m idle — worked time 5h41m**; read worked time, not wall, the same ~10× gap
  PLAN-11 showed at 4×.
  ✅ **Three PR wrappers were a cost that bought real detection.** #246/#247 closed unmerged on
  CodeRabbit rate-limiting; each reopen bought a fresh full-diff review, and four defects the plan had
  shipped were caught that way — including a CVE-scanning remedy whose documented re-enable route was
  inert. ⛔ Do not read the wrapper churn as pure waste.
  ✅ The macOS flake ran 8 gate rounds at 2 green / 6 red, eight disjoint failure sets, every failing
  file untouched by the branch, CI green at every pushed HEAD — accepted under `1e1720`. Textbook
  application of lesson `2026-09-01-14-002`.
- PLAN-05 — 🟡 **LAUNCHED 2026-09-02** (`auto_emit` recorded it; operator has NOT confirmed a start).
  It was the **only** admissible candidate for the slot PLAN-11 freed: zero `file_overlap_matches`
  rows against anything, `declarative`, and 0 of 7 verdict rows blocking. Every other staged plan is
  either dependency-blocked (PLAN-04 behind PLAN-03, PLAN-08 behind PLAN-07) or collides with the
  running PLAN-02.
  ⚠ **Re-check the watch before scoping**: the reported refresh exception still has no error text, and
  the epic's standing note says any detail that surfaces materially sharpens this plan.
- PLAN-11 — ✅ **SHIPPED 2026-09-02** as PR #243 → `5948962`. Full record: `landings/PLAN-11.md`.
  ✅ **Its declared surface was EXACT — 4 declared, 4 realized, zero drift**, the corpus's only such
  instance. The reason is structural and does not generalise: the surface was DERIVED from the plan's
  own `references.json` rather than authored ahead of the work, and a declaration copied from a
  footprint cannot under-declare. Set beside PLAN-02's 9-of-52 in the same week, the two make the case
  for deriving surfaces wherever a plan is already live.
- PLAN-12 — ▶ **RUNNING 2026-09-02, OUTSIDE the plan-marshall lifecycle** by operator decision. The
  kqueue-readiness follow-up PLAN-11 left standing is executed with Claude Code directly, from
  `.plan/temp/kqueue-readiness-instrumentation.md` (15,061 bytes, verified present), with the command
  `Read .plan/temp/kqueue-readiness-instrumentation.md and work from it.`
  ⛔ **Its `plan_marshall_plan_id` is permanently empty BY CONSTRUCTION, not merely unstamped** — there
  is no plan id, no phases, no execution manifest and no lifecycle finalize. Do not read the empty
  field as an unreconciled row and do not try to stamp it.
  ⛔ **It has NO inbox channel at all** — the `inbox/{sender}-{seq}` carve-out presumes a plan id this
  work does not have. Nothing will ever arrive from it in a drain, so reconciling this row is a
  MANUAL `analyze` from its PR and commits. An empty inbox at the next drain says nothing about it.
  ✅ **It carries a row anyway for one reason: gate coverage.** A row is the only way the
  disjointness gate can see the work, and the corpus requires row↔spec both ways, so a spec without a
  row would itself be a reported defect. This is the direct lesson of PLAN-11, which spent its first
  day invisible to the gate — and of the ADR-0038 collision, which is what an unseen surface costs.
  ⚠ **Its only overlap rows are with PLAN-11, which is `shipped`** — expected, since it continues that
  work on the same files, and inert because a shipped plan is never in flight. Nothing currently in
  flight collides with it.
  ⚠ **Watch the PLAN-03 pairing, because the gate structurally cannot.** PLAN-03 modifies
  `edge/WebSocketRelayStage.java`; this work instruments `edge/WebSocketRelayStageTest.java`. Different
  files, so no overlap is reported — but they are the production/test pair of one class.
  ⚠ Its Expected Surface is the **least certain in the corpus**: the work is exploratory, step 2 may
  add a reproducer whose location is undecided, and there is no `references.json` to derive from.
- PLAN-10 — Staged 2026-08-31 by operator decision, resolving the standing ADR-0038 question.
  **Documentation-only**: it lands the drafted ADR at `doc/adr/0038-…adoc` plus its index row, so per
  CLAUDE.md it skips both gates. ⚠ **The index row goes in `doc/README.adoc`, NOT `doc/adr/README.adoc`**
  — the latter does not exist at HEAD, and the Open Defect prose below named it wrongly; the spec
  carries the correction. ✅ All five OBSERVED claims stamped `corroborated` at `c170779`, including a
  verified `git apply --check` of the drafted patch (its `index 53da5d8` matches
  `git rev-parse HEAD:doc/README.adoc` exactly) and confirmation that `0038` is the next free ordinal.
  ⚠ Its deliverable 3 is a real open question, not a formality: the draft says `Proposed` while the
  mechanism is **merged and live** at `Dockerfile.native:56` / `HealthProbe.java`, so the plan must
  settle the status at both sites together.
  ⛔ Nominal `doc/adr/` overlap with PLAN-04/06/07 — those add ADRs at later ordinals and none touches
  `0038` or the ADR-0037 insertion point. If one lands first and claims `0038`, PLAN-10 takes the next
  free ordinal and updates its own link.
- PLAN-05 — Surface-disjoint from every other row (BFF refresh + realm fixture). The epic's most
  promotable plan: it is the only one answering a live user-reported defect.
- PLAN-05 — ⚠ **Second half of a cross-repo diagnostic.** The first half is TokenSheriff's
  `deployment-and-refresh-gaps` → PLAN-01, which tests the same failure at the LIBRARY level
  (`RefreshFlow` / `TokenLifecycleManager`) — the engine this BFF runs. By operator decision that
  plan runs FIRST, as the elimination step: a reproduction there localizes the defect to the engine
  and this plan becomes a regression guard rather than a search; a bounded non-reproduction clears
  the engine and makes this BFF's wiring the prime suspect, sharpening this plan's matrix.
  **Do not start PLAN-05 before reading that verdict** — the two ledgers do not talk to each other
  and nothing carries it across automatically.
- PLAN-12 — ✅ **CONCLUDED 2026-09-03, status `landed`, record `landings/PLAN-12.md`.** Its
  hypothesis was **REFUTED**: the macOS-local loopback stall is not a missed kqueue readiness event
  but a wildcard/loopback listener collision — `.listen(0)` binds `*:P`, `SO_REUSEADDR` lets it take
  an ephemeral port another process holds as a `127.0.0.1` listener, and BSD most-specific-match
  routes the client to that other process. Measured, not argued: 179 foreign listeners in
  49152–65535 → 1.09 % per bind; a matched loopback-bound control arm took 0 of them over 20 000
  binds. Evidence in `.plan/temp/kqueue-probe/`. ⛔ A refutation is a deliverable, not a failure —
  its own spec said the reproduction's outcome counts "positive or negative". `plan_marshall_plan_id`
  stays permanently empty by construction; the terminal status is `landed` rather than `shipped`
  precisely because there is no PR to name.
- PLAN-13 — ▶ **RUNNING, tracked but NOT launched from this ledger.**
  `plan_marshall_plan_id=loopback-stall-fix-and-instrumentation`, created 08:54:52Z, phase
  `5-execute`, `source_id: none` — so `inbox detect` will never classify it as orchestrated and no
  landing will arrive by drain. It carries a row for gate coverage alone, on the precedent PLAN-12's
  spec states. Its Expected Surface is declared from the plan's own `references.affected_files`
  (19 entries) rather than re-derived, so the declaration is the plan's, not the orchestrator's
  guess at it. It lands the fix PLAN-12 measured: `.listen(0)` → `.listen(0, "127.0.0.1")` at 20
  call sites across 9 files, guarded by a new ArchUnit test.
- PLAN-06 — 🟢 **RUNNING 2026-09-03**, operator-confirmed ("I started plan-06 just now"),
  into the single slot PLAN-02's shipping freed (N=3, R=2).
  ⚠ **Recorded on the operator's word alone — disk did NOT corroborate it**, departing from the
  PLAN-02 precedent where the plan directory was verified first. At the moment of recording
  `.plan/local/plans/` held only `NO_PLAN`, no PLAN-06 worktree existed and no feature branch was
  present. `plan_marshall_plan_id` is therefore deliberately left **unstamped rather than guessed**:
  stamp it once phase-1-init has written the directory, and if the artifacts never appear, revisit
  the running claim with the operator.
  Chosen **against queue order by an explicit operator decision** — PLAN-03 was the queue-order
  candidate and the machine gate passed it; see the PLAN-03 note below for why that verdict was
  refused. PLAN-06 was preferred over the other risk-free alternative (PLAN-10) on throughput: it
  clears PLAN-07's sequencing precondition, and PLAN-07 hard-blocks PLAN-08 and soft-blocks
  PLAN-09's scenario 6 — three plans downstream, against none for PLAN-10.
  Prep-ready at `1c7308c`: 8 verdicts, 0 blocking, 5 stale — staleness reported, never promoted.
- **Slots FULL, and the knob is not the constraint** (2026-09-03). The operator asked whether a
  second plan could be emitted. No candidate qualifies at **any** value of `parallelization_scope`:
  `corpus cross-check` reports **all six** remaining staged plans colliding with running PLAN-06 on
  declared paths — PLAN-03/08/09 on `doc/configuration.adoc`, PLAN-04/07 on `doc/adr/` +
  `doc/configuration.adoc`, PLAN-10 on `doc/adr/`. Disjointness decides eligibility; the knob only
  caps how many **disjoint** plans may be in flight. Raising it emits nothing, and lowering it to 2
  would strictly reduce capacity — three are already in flight (PLAN-05 launched, PLAN-06 and
  PLAN-12 running).
- PLAN-10 — ⛔ **NO LONGER RISK-FREE — this supersedes the "nominal `doc/adr/` overlap" note above.**
  PLAN-06's spec (line 139) declares `doc/adr/` as a **HYPOTHESIS**: "a mechanism ADR, only if
  option (b) lands (verify-at-outline)". If that option lands, PLAN-06 takes the next free ordinal —
  `0039`, the exact ordinal PLAN-10 wants. Emitting the two concurrently re-runs the ordinal race
  this epic **already lost once**, when PLAN-02 took `0038` out from under PLAN-10 unpredicted.
  Wait for PLAN-06's outline to settle whether it adds an ADR at all before emitting PLAN-10.
- PLAN-09 — The next real option when a slot frees, but **not an emit as it stands**. Its own spec
  records that scenarios 1–5 are documentable today and that the plan "can legitimately be split
  into 'ship 1-5 now, add 6 after PLAN-07'". Taking that option needs the split **staged as a spec
  first** — orchestrator work — and even then it collides with PLAN-06 on `doc/configuration.adoc`,
  so it cannot run concurrently with it either.
- PLAN-03 — ⛔ **GATE-BLIND while PLAN-12 runs. Re-check this pairing by hand every round; the
  machine will never surface it.** `corpus surfaces` reports PLAN-03 `declarative` with
  `admits_disjointness_check: true`, and `corpus cross-check` returns no overlap row against any
  in-flight plan — so the gate passes it. That pass is unsound: PLAN-03 declares
  `edge/WebSocketRelayStage.java` while running PLAN-12 declares `edge/WebSocketRelayStageTest.java`,
  the production/test pair of ONE class. An exact-path matcher cannot see that coupling, so its
  silence here is not a checked negative.
  ⚠ **Compounding cause, and it is not PLAN-03-specific:** PLAN-12 runs outside the lifecycle and
  therefore has no `references.json`. `corpus cross-check` scanned `plans_scanned: 1` — the
  `NO_PLAN` placeholder — and produced **zero `live_plan` rows**. The in-flight half of the
  disjointness test is currently VACUOUS for **every** candidate; only the `corpus_spec` comparison
  against PLAN-12's own staged spec carries any signal at all, and that comparison is exact-path.
  PLAN-12's own spec already declares itself the least certain surface in the corpus and warns that
  step 2 may add a reproducer at an undecided location.

## Decisions

{Curated human-facing VIEW; `logs/decision.log` is authoritative. NARRATIVE — never regenerated.}

- 2026-08-27 — **Epic relocated from TokenSheriff to API-Sheriff**, closed and archived there
  rather than deleted. Every spec re-derived from this repo's HEAD; no claim carried over.
- 2026-08-27 — **`parallelization_scope = 1`** (strictly sequential), carried over as an operator
  decision from the retired epic and still correct: PLAN-01..04 all touch either the container
  surface or the shared gateway.yaml config model.
- 2026-08-27 — **"TLS termination mode" is read literally, not interpreted.** ADR-0017 defines an
  actual named mode: a front listener SNI-splits between opaque L4 passthrough and an internal
  terminating HTTPS listener. Hostname verification is only meaningful in the *terminating* branch,
  because a passthrough connection is relayed at L4 and never inspected. The operator's "at least
  for the TLS termination mode" is therefore a precise scoping instruction, and WS-03 is cut on it.
- 2026-08-27 — **WS-03 split into PLAN-03 (upstream dial) and PLAN-04 (JWKS back-channel).** Both
  are outbound connections the gateway originates, but they run through different clients with
  different fixes: the upstream dial is Vert.x `HttpClientOptions`/`RequestOptions`, where
  `setVerifyHost` is a first-class method never called; the JWKS fetch is cui-http's `HttpHandler`
  over `java.net.http.HttpClient`, which exposes no such method at all. One plan cannot carry both
  mechanisms honestly.
- 2026-08-27 — **PLAN-03 / PLAN-04 stay split, but the vocabulary is decided ONCE** (operator, after
  asking whether they could be combined — they do the same thing for two different targets). They are
  one feature, and the risk of splitting is two differently-named knobs for one concept. They stay
  split because their MECHANISMS genuinely differ: Vert.x exposes `setVerifyHost(boolean)` as a
  first-class method that is simply never called, while cui-http exposes no such lever at all and its
  ADR may land on an upstream `cui-http` release — an unbounded external dependency that would
  otherwise hold the cheap upstream-dial fix hostage. Combined they would also total ~10 deliverables,
  well past the split guard. The mitigation is structural rather than procedural: **PLAN-03's
  deliverable 2 is now the sole naming authority for BOTH surfaces** — key names, value shape,
  default, documentation section and the shared `T-TLS` entry — and PLAN-04 is implement-only against
  it, forbidden from inventing a name. Alternatives declined: merging into one plan, and splitting the
  JWKS mechanism ADR into its own plan.
- 2026-08-27 — **PLAN-05 stages reproduction only; no fix plan pre-staged.** The report carries no
  error text, so a fix cannot be scoped before the defect is characterised. A fix plan is staged
  by a later `analyze` from PLAN-05's landing.

### Lessons intake — 2026-09-11

Operator direction: go through every lesson in `.plan/local/lessons-learned/`, check that it is still
valid, consolidate, categorise by component; move plan-marshall lessons to plan-marshall's
`truthful-signals` inbox; incorporate this repo's lessons here and move them to this epic's archive;
list the rest and ask. File operations explicitly authorised.

**Scanned 27. Clustered into 16 groups.** Every premise was re-checked read-only — API-Sheriff at
`origin/main` `428bbec`, plan-marshall at `origin/main` `356973d80`. Routed: **12 → truthful-signals**
(8 bundled messages `api-sheriff-deployment-configurability-001..008`, each carrying a per-claim
verdict table, pointers to existing tracking, and the original bodies verbatim), **11 → incorporated
here** (originals in [`lessons-archive/`](lessons-archive/README.md)), **4 → held for the operator, then
relocated to truthful-signals `-009` on the operator's ruling** ("send to plan-marshall", as standards input). Every moved lesson was retired via `manage-lessons remove` with a tombstone, only
after its destination copy was verified — **except `2026-09-01-15-001`**, whose file carries its
metadata as markdown bullets rather than `id=` headers, so the store never indexed it (`remove` →
`not_found`); it was deleted directly after a `cmp`-verified copy, and has **no tombstone**.

| Lesson | Cluster | Component | Verdict at HEAD | Disposition → destination |
|---|---|---|---|---|
| `2026-07-16-09-002` | C1 pre-commit rewrite | api-sheriff maven build | RESOLVED (#242: composites dropped, `pom.xml:172` `<release>25`) | already-covered → archive; residue `pom.xml:201` comment (Open Defects) |
| `2026-09-01-19-001` | C1 | api-sheriff maven build | RESOLVED (#242) | already-covered → archive |
| `2026-07-16-15-001` | C16 | api-sheriff CI | RESOLVED (all five setup-java workflows on `'25'`) | stale → archive |
| `2026-08-29-16-002` | C2 loopback flake | api-sheriff test suite | mechanism CLOSED (PLAN-13); residuals open | clustered → archive; PLAN-23 D3 |
| `2026-09-10-09-001` | C2 | api-sheriff test suite | STILL VALID, remedy misdescribed | clustered → archive; PLAN-23 D3 corrected |
| `2026-09-01-14-002` | C2 | api-sheriff test suite | rule stands | clustered → archive; PLAN-23 D3 |
| `2026-08-29-16-001` | C15 CI trigger gap | api-sheriff CI / org workflows | STILL OPEN (org filter `'!.plan/**'`) | already-covered → archive; Open Defects entry re-verified |
| `2026-09-02-22-001` | C14 root path `/` | api-sheriff (WS-02) | STILL VALID (7 ad-hoc normalisers, no `/` case) | standalone → archive; new Open Defect |
| `2026-09-01-14-003` | C12 tests that cannot fail | api-sheriff tests | original fixed; NEW site `TlsEdgeProducerTest:343-344` | clustered → archive; PLAN-23 |
| `2026-09-01-15-001` | C12 | api-sheriff tests | followed (`ContextPathDefaultsTest:132`) | clustered → archive; PLAN-23 methodology |
| `2026-09-04-07-001` | C13 prose claims | api-sheriff docs | all named instances fixed | clustered → archive; PLAN-09 / PLAN-19 authoring rule |
| `2026-08-29-16-003` | C3 digests lossy | plan-marshall:build-maven | STILL VALID (new root cause: `[deprecation]` char class) | → truthful-signals `-001` |
| `2026-09-10-22-003` | C11 targeted `-Dtest` | plan-marshall:build-maven | STILL VALID, untracked | → truthful-signals `-001`; local residue in Open Defects |
| `2026-09-02-22-003` | C4 review-bot coverage | plan-marshall:automatic-review | partly fixed (#1409); lesson premise on `fetch_findings` REFUTED | → truthful-signals `-002` |
| `2026-09-02-22-004` | C4 | plan-marshall:automatic-review | fixed, default off (#1433) | → truthful-signals `-002` |
| `2026-09-02-22-005` | C5 findings triage | plan-marshall:phase-6-finalize | gate fixed (#1199); log wording residue | → truthful-signals `-003` |
| `2026-09-10-22-002` | C5 | plan-marshall triage | STILL VALID, untracked | → truthful-signals `-003` |
| `2026-08-29-16-004` | C6 anchors | plan-marshall agent briefing | STILL VALID, untracked | → truthful-signals `-004` |
| `2026-09-02-13-003` | C6 | plan-marshall:manage-references | STILL VALID; already forwarded to CIS-050 D7 | → truthful-signals `-004` |
| `2026-09-05-07-001` | C7 lost return path | plan-marshall:phase-5-execute | partly fixed (prose only) | → truthful-signals `-005` |
| `2026-09-01-14-001` | C10 ledger freshness | plan-marshall:manage-change-ledger | STILL VALID; PLAN-TRUTH-105 fold is backwards | → truthful-signals `-006` |
| `2026-09-06-01-001` | C8 merge-queue | plan-marshall:tools-integration-ci | STILL VALID | → truthful-signals `-007` |
| `2026-09-06-07-001` | C9 light lane | plan-marshall:phase-3-outline | half fixed (#1399); `pr_title` open | → truthful-signals `-008` |
| `2026-09-02-13-001` | C12 | generic testing method | instance fixed (`AwaitsTest:177`) | held → operator ruled → truthful-signals `-009` |
| `2026-09-10-22-001` | C12 | generic code-quality method | guard landed and fired | held → operator ruled → truthful-signals `-009` |
| `2026-09-02-22-002` | C13 | generic documentation method | all instances fixed | held → operator ruled → truthful-signals `-009` |
| `2026-09-02-13-002` | C13 | generic investigation method | no local residue | held → operator ruled → truthful-signals `-009` |

⚠ **Two corrections surfaced by verification, recorded so they are not re-derived:** (1) lesson
`2026-09-10-09-001`'s remedy "bind the control listener to 127.0.0.1" names a listener that does not
exist — `startsAndStopsFrontListener` holds no port, its precondition is a loopback *connect* to a port
allocated by a wildcard `new ServerSocket(0)` (`TlsEdgeProducerTest:282`); (2) lesson
`2026-09-02-22-003`'s claim that `fetch_findings` already extracts the reviewed sha from a comment is
false — it stamps the PR head (`github_pr.py:1258`).

⚠ **Stale user memory corrected in the same pass:** the `precommit-formatter-churn` memory still
described the `<release>`→21 rewrite as live; rewritten to record the #242 resolution.

### Corpus regroup — 2026-09-15

- 2026-09-15 — **Up to 12 deliverables per plan are authorized** (operator), overriding the ~6
  split presumption for this epic; thin plans that must run sequentially anyway are a mis-assembly,
  because each costs a full PR and bot-review cycle.
- 2026-09-15 — **Seven staged plans regrouped into four** (operator chose the recommended layout).
  Revisit at `origin/main` `a2969b9` found PLAN-20/21/22 colliding on `ConfigValidator`,
  `ConfigLogMessages`, `BffRuntimeProducer`, `gateway.schema.json`, `doc/LogMessages.adoc`,
  `doc/configuration.adoc` and ADR `0044` — forced-sequential, three PR cycles for one stream — and
  PLAN-09/19 colliding on `doc/user/` while sharing one subject. Moves, source → destination:
  PLAN-09 D1-6 → **PLAN-24** D1-6; PLAN-19 D1-5 → PLAN-24 D7-11; PLAN-20 → **PLAN-25** D1-4 (+9-11);
  PLAN-21 → PLAN-25 D5-6; PLAN-22 → PLAN-25 D7-8; PLAN-23 D3 + D5 → **PLAN-18** D3-6; PLAN-23's
  `ConfigValidatorTest` redundant-method item → PLAN-25 D7; Open Defect "CLAUDE.md/AGENTS.md `-am`"
  → PLAN-24 D10. **PLAN-23** keeps the open-ended audit. Round 1 = PLAN-18 + PLAN-24 + PLAN-25
  (`corpus cross-check`: zero overlap pairs among them, all named-file surfaces); PLAN-23 follows
  PLAN-18 and PLAN-25. Alternatives declined: 5 plans (PLAN-18/23 untouched), config-only merge,
  references-only fix.
- 2026-09-15 — **New workstream WS-08 "Configuration Security Hardening"** (operator) owns PLAN-25,
  rather than filing a three-workstream merge under WS-03.
- 2026-09-15 — **Superseded specs are parked, not `superseded`.** `VALID_STATUS_VOCABULARY` has no
  `superseded` value, and the whole-array `plans` rewrite is reserved to seeding. `parked` is the only
  sanctioned non-emittable state; each spec carries a SUPERSEDED banner. See Open Defects.
- 2026-09-15 — **Two premises found stale during the rewrite, corrected in the successors:** PLAN-22
  still described `BROAD_PREFIX_IPV4 = 8` although #267 (`558a38b`, 2026-09-04 — before PLAN-22 was
  staged) had raised it to 16/48; PLAN-20 declared `quarkus/TokenValidatorProducer.java`, which does not
  exist (the file is under `auth/`), so the gate compared against a path nothing would ever touch.

### PLAN-24 landing and inbox drain — 2026-09-15

- 2026-09-15 — **PLAN-24 shipped** (#305, squash `fb65222`, merge queue). 10/10 outline deliverables
  covering all 11 spec deliverables; see `landings/PLAN-24.md`. Surface expansion 18 files, none in a
  running plan's declared surface.
- 2026-09-15 — **PLAN-24's spec was wrong on one point, and the plan was right to depart from it.** Spec
  deliverable 10 mandated `-Dsurefire.failIfNoSpecifiedTests=false` on the targeted-test example; under
  `-pl api-sheriff -am` it only hides a misspelled selector. Orchestrator authoring error, self-reported
  upstream as truthful-signals `api-sheriff-deployment-configurability-018`.
- 2026-09-15 — **Concurrent plans share local Docker image tags — a surface the file-disjointness gate
  cannot see (HYPOTHESIS, unconfirmed).** PLAN-18 hit a foreign `api-sheriff:jfr` (built 13:32 UTC, revision
  `f4a035c`, no longer resolvable) during reversion proofs; PLAN-24's first — red — jfr-lane run falls in
  that window. Any two plans that run `-Pintegration-tests` / `-Pjfr` on the same machine can overwrite each
  other's `api-sheriff:distroless` / `:jfr` images, invalidating production-mutation reversion proofs
  silently. Recorded on local lesson `2026-09-15-16-001`. **Pairing consequence:** before emitting two
  IT-lane plans concurrently, treat the image tags as shared surface — see Queue annotations.
  ✅ **CONFIRMED AND FIXED 2026-09-23 by PLAN-28 (#341).** No longer a hypothesis: PLAN-28's own
  `-Pintegration-tests` run hit the identical shape directly (a stale week-old `api-sheriff:jfr`
  started the primary gateway against 8 fixture YAMLs it never touched, 11/12 other instances on the
  fresh `:distroless` stayed healthy — decisive disproof of branch attribution). Fixed at the root:
  image selection is now explicit per lane (`integration-tests/pom.xml`,
  `start-integration-container.sh`, `stop-integration-container.sh`, commit `9a114f4`) — a lane that
  builds an artifact now runs that artifact, no "prefer whatever tag exists" fallback. Folded into
  lesson `2026-09-15-16-001` as its third recurrence. The revision-label discrimination gap the
  second recurrence found (local builds stamp `revision=dev`) is NOT addressed by this fix and stays
  open on the lesson.
- 2026-09-15 — **Inbox drain: 13 messages, 13 archived, 0 invalid.** Dispositions:

  | Message | Disposition | Destination / reason |
  |---|---|---|
  | `-013` landing | reconciled | PLAN-24 full ship |
  | `-001`, `-002`, `-003`, `-008` | promoted | truthful-signals `…-014` (phase-3-outline bundle) |
  | `-004` | promoted | truthful-signals `…-015` (manage-tasks `files_exist`) |
  | `-009`, `-011`, `-012` | promoted | truthful-signals `…-016` (phase-6-finalize bundle; `-009` adjacent to PLAN-TRUTH-166) |
  | `-010` | promoted | truthful-signals `…-017` (marshalld re-attach) |
  | `-006` | promoted | truthful-signals `…-018` (orchestrator self-report) |
  | `-007` | folded | local lesson `2026-09-15-16-001` (recurrence, inverse direction) |
  | `-005` | discarded | fixed in-run; recurrence of this epic's Authoring Discipline rule (lesson `2026-09-04-07-001`) |

  "Promoted" here means relocated to plan-marshall's corpus via its epic inbox, the routing this epic
  has used since 2026-09-11; envelopes validated.

### Cross-repo refresh-failure finding — 2026-09-15

- 2026-09-15 — **PLAN-26 staged (WS-04) from Token-Sheriff finding `e7dd80`** (epic
  `lessons-handling-26-09-04-01`, TokenSheriff PR #725), relayed by the operator. Every claim re-verified at
  API-Sheriff `fb65222`, `token-sheriff-client-0.9.5.jar` (`javap`) and Token-Sheriff tag `0.9.5`: the
  coordinator destroys the session on every `TokenSheriffException` and never calls `RefreshFlow.classify`.
- 2026-09-15 — **The verification found a larger gap than the finding named.** API Sheriff references no
  `RefreshTokenFamily`, `TokenLifecycleManager` or `RevocationClient`, yet the coordinator javadoc and
  `doc/security-threat-model.adoc` (server mode **COVERED**, `:1115-1128`) credit refresh-token reuse
  detection to the engine. `RefreshFlow`'s own javadoc says the caller feeds the family. And if the family
  were wired, its reuse signal (`ClientProtocolException`) would classify as `PRE_REDEMPTION` — so a naive
  fix of the finding alone could keep a stolen session alive. Folded in as PLAN-26 deliverable 5, with the
  wire-it versus state-the-truth choice left as an operator decision at refine.
- 2026-09-15 — **Sequencing: PLAN-26 after PLAN-25, PLAN-23 after PLAN-26.** PLAN-26 overlaps PLAN-25 on
  `BffRuntimeProducer.java` (the only other `RefreshOutcome` consumer), `BffRuntimeProducerTest.java`,
  `configuration.adoc`, `LogMessages.adoc` and `security-threat-model.adoc`; PLAN-23's audit should read the
  rewritten refresh tests, so its spec gained a PLAN-26 dependency.
- 2026-09-15 — **Routed back to Token-Sheriff** (operator decision): whether the engine's `classify` should
  special-case a revoked-family `ClientProtocolException` is a Token-Sheriff question. Filed as a `finding` in
  Token-Sheriff epic `lessons-handling-26-09-04-01` inbox, `api-sheriff-deployment-configurability-001`
  (envelope validated).

### Refresh-token reuse detection — operator decision

- 2026-09-15 — **Option D chosen** (of four presented via AskUserQuestion): reuse detection is the **IdP's**
  job; enable strict rotation (`revokeRefreshToken: true`, `refreshTokenMaxReuse: 0`) in the compose-sample and
  integration realms; prove end to end that a replayed or IdP-revoked refresh token ends the gateway session.
  Declined: **A** adopt `TokenLifecycleManager` for server mode (largest; needs a `SessionStore` adapter and a
  revocation client; cookie mode would keep a separate refresh path; its family map is an in-memory LRU and in
  server mode sees no attacker-reachable replay), **B** wire only `RefreshTokenFamily` into the coordinator
  (makes the docs literally true, guards essentially nothing an attacker can reach, and its reuse signal
  classifies `PRE_REDEMPTION`), **C** docs only (leaves the shipped stacks with no reuse detection at all).
  Rationale: API Sheriff is a confidential client, so a gateway-side family only sees tokens the gateway
  presents; the IdP is the only state shared across modes and instances. Benchmark realm deliberately unchanged.
- 2026-09-15 — **One plan, not two** (operator correction). The orchestrator first split option D's realm +
  end-to-end half into PLAN-27; the operator directed *"do not split into too fine granular plans, remember the
  12 deliverables per plan"*. PLAN-27 was folded back into PLAN-26 (now 12 deliverables, 21 named paths) and
  parked as superseded. Orchestrator error recorded: the IT-lane separation was not worth a second PR cycle for
  one sequential stream — the shared-image hazard is handled by sequencing, not by splitting.

### Round-1 landings — 2026-09-16

- 2026-09-16 — **PLAN-25 shipped** (#306, `7ba9734`, merge queue): BFF OIDC back-channel pinned through new
  peer keys `egress_tls.oidc_verify_hostname` / `oidc_tls_profile`, an ArchUnit posture guard with matched
  specimens, `session.cookie_name` **refused** without `__Host-`, trusted-proxy breadth kept at WARN with
  ADR-0044, the #269 contract test, ADR-0045, and #256/#269 closed. 8 outline deliverables covering all 11
  spec deliverables; four design questions answered by the operator mid-run. `landings/PLAN-25.md`.
- 2026-09-16 — **PLAN-18 shipped** (#308, `c74f5d2`) — **and nobody reported it.** Found by `analyze` while
  corroborating PLAN-25. Both round-1 plans ran with `emit-landing` and `lessons-capture` on lane `off`, so
  the epic's OUTBOX carried nothing for either. `landings/PLAN-18.md`.
- 2026-09-16 — **The shared-Docker-image hazard is OBSERVED, not hypothetical.** Local lesson
  `2026-09-15-16-001` records that PLAN-18's first reversion proofs ran against a foreign `api-sheriff:jfr`
  (revision `f4a035c`), so production-side mutations could not turn anything red until the image was retagged
  and the run repeated. Attribution to PLAN-24's concurrent jfr lane stays unproven (`f4a035c` no longer
  resolves), but the *class* is now measured. **Consequence for pairing: two plans that run IT lanes are
  sequenced, whatever their file surfaces say.**
- 2026-09-16 — **Declare an ADR by ORDINAL, not by a guessed filename.** PLAN-25 declared
  `doc/adr/0044-trusted-proxies-breadth-threshold.adoc` and wrote
  `0044-Trusted-proxy_breadth_is_warned_beyond_one_provisioned_network…`. The declaration named a file that
  never existed; the corpus convention is the long descriptive title. PLAN-26's ADR entry corrected.
- 2026-09-16 — **PLAN-25's landing fired the scenario-guide watch.** The two new peer keys are in
  `configuration.adoc` and `tls-edge.adoc` but appear zero times in `doc/user/tls-scenarios.adoc`. Folded into
  PLAN-26 deliverable 12 with `tls-scenarios.adoc` added to its Expected Surface in the same act (21 → 22
  declared paths). ⚠ Second instance of a guide going understated one landing after it was written.

### Reporting channel restored — 2026-09-16

- 2026-09-16 — **`emit-landing` turned on for orchestrated plans** (operator). Landed as PR #309 (`3c68ee4`)
  together with two other operator-authored `marshal.json` edits: `default:verify:coverage` dropped from
  phase-5 verification, and build queue `max_slots` 5 → 2. ⚠ **PLAN-26 is already `launched`** — it was
  emitted before this landed, so whether its finalize picks up the new lane depends on when its manifest is
  composed; check its landing for an inbox message rather than assuming one.
- 2026-09-16 — **Gate judgement recorded**: the Maven gates were deliberately not run for #309. The whole
  footprint is `.plan/marshal.json`, which is not in CLAUDE.md's gate-requiring enumeration, `build_map` was
  untouched (drift clean), and CI's `check-changes` ignores `.plan/**` — so a build would have observed
  nothing. `manage-config list-finalize-steps` re-resolved the step set as the substitute check.

### PLAN-26 landing — 2026-09-17

- 2026-09-17 — **PLAN-26 shipped** (#314, `a475cff`, merge queue): refresh failures disposed by engine kind
  (`PRE_REDEMPTION` keeps the session with a ~5 s bounded retry and the new `ApiSheriff-127`;
  `CREDENTIAL_REJECTED` and `REDEEMED` end it, the latter revoking best-effort through `EndedRefreshTokens`,
  64 in flight), strict rotation shipped in **both** realms, `BffRefreshReuseIT` proving replay end to end,
  ADR-0046, and the `oidc_*` keys folded into the scenario guide. 8/8 deliverables, 26/26 tasks.
  `landings/PLAN-26.md`.
- 2026-09-17 — ✅ **The reporting channel works.** First landing reconciled from the plan's own inbox message
  (`landing-check` → `complete: true`), two days after a shipped plan was invisible to this epic. PR #309 is
  discharged as a fix, not just a config change.
- 2026-09-17 — ⛔ **The defect class this epic exists to close was RE-CREATED by a native-image gap, and only
  the native lane saw it.** An unregistered `_TokenErrorResponse_DslJsonConverter` turned an IdP
  `400 invalid_grant` into a `TransportException`, which classified as `PRE_REDEMPTION` — so a revoked or
  replayed session was **kept alive**. The JVM lane was green throughout. Fixed in-run and pinned by
  `TokenClientDslJsonReflectionTest`; promoted as local lesson `2026-09-17-06-001`.
- 2026-09-17 — **Keycloak's reuse verdict flipped on first contact with a live IdP.** The spec predicted
  "only the replayed token is revoked"; Keycloak 26.5.7 also revokes the successor grant while leaving the
  user session alive. The test was re-pinned to the observed verdict and the semantics recorded in ADR-0046;
  the generalizable rule is local lesson `2026-09-17-06-002`.
- 2026-09-17 — **Inbox drain: 7 messages, 7 archived, 0 invalid.** `-007` reconciled; `-001`/`-002` promoted
  to the local corpus; `-003`/`-004` relocated to plan-marshall truthful-signals (`…-019`, `…-020`, the
  latter as an explicit recurrence of `…-017`); `-005` folded into PLAN-23 deliverable 6 with its surface
  updated in the same act; `-006` folded into local lesson `2026-09-15-16-001` as a second recurrence.
- 2026-09-17 — **Six loop-backs against a cap of five**, five of them CodeRabbit-driven at roughly hourly
  cadence, plus re-stamped (not re-run) self-review / simplify / security-audit on doc-, test- and
  rename-only deltas. All logged by the plan as deviations; the sixth round carried a two-line test fix.

### PLAN-28 landing and inbox drain — 2026-09-23

- 2026-09-23 — **PLAN-28 shipped** (#341, squash `1994f28d2334fc4698d2352d47f3b72e6e3448ee`, merge
  queue). All 7 spec deliverables landed, two of them wider than scoped (a genuine CWE-522
  secret-pointer bypass fixed alongside the `ConfigLoader` object-coercion arm; three real bugs plus a
  CWE-117 log-injection fix alongside the back-channel logout work). See `landings/PLAN-28.md`.
  `ConfigLoader.java`'s declared path is now stale (moved to a new `config/load/` subpackage during
  execution) — noted, not corrected, since the corpus is otherwise fully terminal.
- 2026-09-23 — **The epic's own standing HYPOTHESIS about concurrent-plan Docker-image collisions is
  now CONFIRMED and FIXED**, not merely observed a third time — see the retired entry above and
  lesson `2026-09-15-16-001`'s third recurrence.
- 2026-09-23 — ⛔ **NEW: ADR-0050 is a cross-epic ordinal collision.** PLAN-28's own ADR
  (`0050-A_JWT_whose_type...`) landed first (`1994f28`); an unrelated PR #343
  (`feature/plan-16-application-portal`) — belonging to `api-sheriff-0-2-0`'s own PLAN-16, matched via
  that epic's own resume_anchor independently recording "ADR corpus is now 50" — landed second
  (`69b322b`, same day) and claimed the identical ordinal for
  `0050-Portal_templates_render_on_a_standalone_Qute_engine...`. Verified: `doc/adr/` now holds two
  files numbered 0050, the only duplicate ordinal in the corpus; genuinely next-free is 0053. Not
  PLAN-28's fault (it landed first, when 0050 was genuinely free); the later PR did not re-check
  freshness against a concurrently-merging sibling epic. **Routed** to `api-sheriff-0-2-0`'s inbox as
  a cross-epic finding (`deployment-configurability-001.md`) rather than staged as a plan here — this
  epic's queue is otherwise fully terminal, and the offending later PR belongs to that epic's own
  PLAN-16. See Open Defects.
- 2026-09-23 — **Inbox drain: 19 messages, 19 archived, 0 invalid.** Dispositions:

  | Message | Disposition | Destination / reason |
  |---|---|---|
  | `-019` landing | reconciled | PLAN-28 full ship |
  | `-004` | promoted | local lesson `2026-09-23-07-001` (unmeasured-vs-clean collapse) |
  | `-001` | promoted | local lesson `2026-09-23-07-002` (closed set restated across schema sites) |
  | `-002` | promoted | local lesson `2026-09-23-07-003` (distribution-boundary duplication) |
  | `-003` | promoted | local lesson `2026-09-23-07-004` (fork option vs. file format) |
  | `-005` | promoted | local lesson `2026-09-23-07-005` (cross-reference read as shared text) |
  | `-007` | promoted | local lesson `2026-09-23-07-006` (DEBUG-only security rejection path) |
  | `-008` | promoted | local lesson `2026-09-23-07-007` (count + enumeration, two claims) |
  | `-009` | promoted | local lesson `2026-09-23-07-008` (pin-vs-derive contract test) |
  | `-010` | promoted | local lesson `2026-09-23-07-009` (doc permissive, code refuses — CWE-522 adjacent) |
  | `-011` | promoted | local lesson `2026-09-23-07-010` (outside-diff-range findings are first-class) |
  | `-006` | folded | local lesson `2026-09-15-16-001`, third recurrence (confirms + fixes the standing Docker-image HYPOTHESIS) |
  | `-012` | routed | 🔄 **CORRECTED 2026-09-23** — plan-marshall's `lessons-routing` epic inbox, `api-sheriff-deployment-configurability-001.md` there (`scope_creep_check` finding-type rejection); also reported via `SendFeedback` |
  | `-013`, `-015`, `-016`, `-017`, `-018` | routed | 🔄 **CORRECTED 2026-09-23** — bundled into one finding, `lessons-routing` epic inbox, `api-sheriff-deployment-configurability-003.md` there (argparse/flag-shape recurrence class across 5 scripts) |
  | `-014` | routed | 🔄 **CORRECTED 2026-09-23** — `lessons-routing` epic inbox, `api-sheriff-deployment-configurability-002.md` there (`pre-submission-self-review` stale local base ref) |

  Ten new lessons promoted, plus one existing lesson folded (11 corpus-touching dispositions), 7
  routed to plan-marshall's `lessons-routing` epic (as 3 findings there, one bundling 5 of the 7 — see
  above) rather than discarded, 1
  reconciled as the full ship — 10 + 1 + 7 + 1 = 19. `manage-lessons list` confirms **15** active
  local lessons after this drain (was 5, +10 new).

### PLAN-29 landing and inbox drain — 2026-09-23

- 2026-09-23 — **PLAN-29 shipped** (#348, squash `070eda54d465f532e77cca717d2f2bfd06172328`, merge
  queue). 2/2 spec deliverables landed; deliverable 1 (gateway-side `scopeDelta` WARN) dropped by
  operator decision after finding the engine already logs it — independently re-verified via `javap`
  against `token-sheriff-client:0.9.6`, not taken on trust. See `landings/PLAN-29.md`.
- 2026-09-23 — **Opportunistic fix, outside spec**: the ADR-0050 cross-epic ordinal collision (routed
  to `api-sheriff-0-2-0` on 2026-09-22) was fixed directly in this PR instead — renumbered to 0053, 5
  files touched. Both affected Open Defects (scopeDelta, ADR-0050) closed above with evidence.
- 2026-09-23 — **The `re_review_on_loopback: false` cost is now quantified, not merely predicted.**
  This run burned its entire 5-iteration loop-back budget on `participated_stale` churn from that
  setting — recorded as lesson `2026-09-23-15-003`. The PLAN-28 rationale for keeping the setting
  still stands; this is evidence for a future reconsideration, not an action taken now.
- 2026-09-23 — **Inbox drain: 7 messages, 7 archived, 0 invalid.** Dispositions:

  | Message | Disposition | Destination / reason |
  |---|---|---|
  | `-007` landing | reconciled | PLAN-29 full ship |
  | `-001` | promoted | local lesson `2026-09-23-15-001` (new doc duplicated existing coverage) |
  | `-002` | promoted | local lesson `2026-09-23-15-002` (diagnostic doc evidence overclaim) |
  | `-003` | promoted | local lesson `2026-09-23-15-003` (re_review_on_loopback cost, quantified) |
  | `-004`, `-005`, `-006` | routed | bundled into one finding, `lessons-routing` epic inbox, `api-sheriff-deployment-configurability-004.md` there — `-005` is a confirmed recurrence of an already-forwarded finding (`process-compliance`) |

  3 lessons promoted, 3 routed, 1 reconciled — 3 + 3 + 1 = 7.

### Pre-close lessons-corpus and Sonar handoff — 2026-09-23

- 2026-09-23 — **Operator directive: empty the local lessons corpus before close, splitting by
  audience rather than discarding.** Reviewed all 18 lessons then live in `.plan/local/lessons-learned/`
  (the 3 promoted from PLAN-29's own drain plus 15 accumulated over the epic's life, including one
  malformed lesson, `2026-09-22-09-001`, whose non-standard `- id:`/`- component:` header
  `manage-lessons` could not parse — recovered from the raw file rather than dropped). Finding: **none
  were genuinely plan-marshall-specific** — every lesson this epic ever promoted into the local
  `api-sheriff`-component corpus was already correctly scoped to this repository's own code, docs, or
  authoring practice, because plan-marshall tooling gaps were always routed straight to
  `lessons-routing` as findings rather than promoted here in the first place (see the PLAN-28 and
  PLAN-29 drain sections above). So the plan-marshall/other split resolved to 0 / 18, not an even
  division.
- 2026-09-23 — **All 18 lessons handed to `api-sheriff-0-2-0`'s inbox** as `kind: candidate-lesson`
  messages (`deployment-configurability-002.md` through `-019.md`, envelopes validated), each
  carrying the full original content plus its current status (several — the stale-jfr-image lesson,
  the DSL-JSON-converter lesson, the overdetermined-guard lesson — are already marked FIXED in their
  own body so the receiving epic does not re-open closed work; two — the AGENTS.md/CLAUDE.md
  module-list contract test, and the image-revision-label discrimination half of the jfr lesson —
  are marked genuinely still open). That epic now owns Promote/Fold/Discard for each, per its own
  review — not decided here.
- 2026-09-23 — **Sonar `java:S3398` (finding `93017f`) also routed, not decided here** — see the Open
  Defects entry below. Same rationale: `api-sheriff-0-2-0` continues touching this code and can own
  the disposition.
- 2026-09-23 — **Local lessons corpus confirmed empty**: `manage-lessons list` returns `total: 0`.
  Each removal used `--coverage-verdict superseded` (custody transferred to the receiving epic's own
  review, not obsoleted or redundant) and the malformed lesson used `--allow-unreadable` — tombstones
  preserved for all 18 at `.plan/local/lessons-learned/.tombstones/`.

## Open Defects

> ↪ Relocated to `settled.md` § "Open Defects — handled (relocated 2026-09-11)" — 59 resolved, retired, superseded or retracted entries, each with its closing evidence; the entries below are the live ones.

- ⛔ **ADR-0050 is a cross-epic ordinal collision, confirmed at HEAD (2026-09-23, PLAN-28 landing).**
  `doc/adr/` holds two files both numbered `0050`: PLAN-28's own
  `0050-A_JWT_whose_type_the_engine_exposes_no_entry_point_for_is_verified_signature-only...`
  (landed first via PR #341, `1994f28`) and `api-sheriff-0-2-0`'s own PLAN-16's
  `0050-Portal_templates_render_on_a_standalone_Qute_engine...` (landed second, same day, via PR #343,
  `69b322b`). Verified: `ls doc/adr/ | grep -oE '^[0-9]{4}' | sort | uniq -d` returns exactly `0050`
  — the only duplicate ordinal. Genuinely next-free is `0053`. Not this epic's plan's fault — 0050 was
  free when PLAN-28 claimed it; the later PR did not re-check freshness against a concurrently-merging
  sibling epic. **Routed, not owned here**: filed as a `finding` in `api-sheriff-0-2-0`'s inbox
  (`deployment-configurability-001.md`, envelope validated) rather than staged as a plan in this epic
  — this epic's queue is otherwise fully terminal, and the offending later PR belongs to that epic's
  own PLAN-16 work. Unowned by `deployment-configurability`; awaiting that epic's disposition.
  ✅ **RESOLVED 2026-09-23 by PLAN-29 (#348) — fixed opportunistically, not by `api-sheriff-0-2-0`.**
  While executing an unrelated deliverable, PLAN-29 found and fixed the collision directly: the
  portal-templates ADR renamed `0050-Portal_templates...` → `0053-Portal_templates...` (commit
  `32c20d1`), with all four referencing docs updated. Verified: no duplicate ADR ordinals remain in
  `doc/adr/`. The routed finding in `api-sheriff-0-2-0`'s own inbox
  (`deployment-configurability-001.md`) is superseded by this fix — left as-is there (not this epic's
  ledger to edit), but this epic's own record now carries the correct attribution.
- ⚠ **`GatewayEdgePipelineTest.metersDisallowedVerbUnderItsRoute` flaked once, self-resolved on retry
  (2026-09-22, surfaced via PR #342, an unrelated automated `chore/update-org-workflows-v0.29.0` bump).**
  Operator-pasted CI failure, verified against ground truth rather than taken at face value: PR #342
  confirmed merged (`7b8715a`), its footprint confirmed workflow-YAML-only (`git show --stat` — six
  `.github/workflows/*.yml` files, no application code), and the cited run
  (`build / sonar-build`, run `35722510258`) confirmed present in `ci checks status --pr-number 342`.
  The failure: `assertEquals(1.0, meterRegistry.counter(...).count())` at
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgePipelineTest.java:281` read
  `0.0`. Reran (`gh run rerun 35722510258 --failed`); passed; PR merged with no code change.
  ⛔ **The paste's own root-cause guess is NOT supported by the code, and the ledger records the
  correction rather than the guess**: the failure message includes a `GeneratorController` seed and
  reads as generator-driven non-determinism, but `metersDisallowedVerbUnderItsRoute` (`:272-286`) calls
  no generator at all — the seed line is `@EnableGeneratorController`'s class-level boilerplate
  (`:88`), printed on any failure in that class, not evidence tied to this assertion. The more likely
  mechanism, and consistent with the paste's OWN alternative hypothesis (*"timing/ordering race"*):
  `send()` (`:206`) awaits the HTTP response future via `Awaits.connect`, but nothing in the test
  awaits the metering side effect itself before reading `meterRegistry.counter(...).count()` — if the
  gateway increments the counter on a different Vert.x context/callback than the one that completes
  the response, the read can race the increment. One occurrence in one run is not enough to confirm
  this either; it is the more evidence-consistent hypothesis, not a settled diagnosis.
  Unowned — a WS-07 candidate. Suggested next step if it recurs: reproduce locally with
  `-Dde.cuioss.test.generator.seed=1059526449437993292` AND, separately, add a bounded `Awaits`-style
  wait on the counter reaching `1.0` before asserting, to test the race hypothesis directly rather than
  only the seed. Not staged as a plan — a single unreproduced occurrence does not yet warrant one; fold
  into a future test-suite-reliability plan if it recurs.
  ✅ **RESOLVED, verified 2026-09-23 at HEAD `3e3addc`.** The exact fix suggested above is already in
  place: `metersDisallowedVerbUnderItsRoute` (`:274-289`) now runs `Awaits.until(...)` polling the
  counter to `>= 1.0` (ceiling `Awaits.CONNECT_CEILING_SECONDS`) before the assertion, with a comment
  confirming the diagnosed mechanism verbatim — *"The edge meters from RoutingContext.addEndHandler,
  which runs asynchronously with respect to the client receiving the response, so wait for it."*
  Landed via PR #343 (`69b322b`, an unrelated portal-feature PR from a sibling epic) — not attributed
  to any deployment-configurability plan, just confirmed fixed. No action owed.

- ⛔ **CROSS-REPO: `cui-java-parent` 1.7.3's `-Ppre-commit` still activates the recipe that destroyed
  this repo's source — every cuioss Java 22+ consumer WITHOUT API-Sheriff's override is exposed
  (2026-09-11, lessons intake C1 follow-up, operator-requested read-only check).** Verified in the
  installed POM: `cui-java-parent-1.7.3.pom:231` profile `pre-commit` → rewrite `activeRecipes` →
  `:302` `org.openrewrite.java.migrate.UpgradeToJava21` (the parent already drops `JavaUtilAPIs`, `:312`,
  but not this). Recipe graph read out of `rewrite-migrate-java-3.42.1.jar`: `UpgradeToJava21 →
  UpgradeToJava17 → Java8toJava11 → lang.RenameUnderscoreIdentifier` (strips the Java 22+ unnamed
  variable `_` — ~180 uncompilable files here before #242), plus `UpgradeToJava21 → UpgradeBuildToJava21`
  (rewrites the build's Java version — the `<release>`→21 churn here). API-Sheriff is safe only because
  its root `pom.xml:460-510` overrides `activeRecipes` with `combine.children="override"`.
  ⚠ Not established: which `rewrite-migrate-java` version other consumers resolve (the parent and
  `cui-java-bom` 1.7.3 declare none), and which cuioss repos actually use `_` or a
  `${maven.compiler.release}` property. OBSERVED in the same jar: `UpgradeToJava21`'s recipe list also
  carries `org.openrewrite.github.SetupJavaUpgradeJavaVersion`, which rewrites workflow `setup-java`
  versions. HYPOTHESIS, unverified: that is how the old `benchmark.yml` JDK 21 pin arose (lesson
  `2026-07-16-15-001`) — confirm/refute at `.github/workflows/benchmark.yml` § the `setup-java` step's
  `git log -S "java-version: '21'"` history. Remedy belongs in `cui-java-parent`. No issue drafted or filed
  — operator's call.
  ✅ **OPERATOR DECISION 2026-09-15: not raised upstream — a no-op for every other consumer.** Operator:
  API-Sheriff is the first Java 25 baseline project; all other cuioss projects are baseline 21.
  OBSERVED: `cui-parent-pom-1.7.4.pom:136` sets `maven.compiler-plugin.release` to 21, API-Sheriff
  overrides it to 25 at `pom.xml:75`; `cui-java-parent-1.7.4.pom:302` still activates `UpgradeToJava21`.
  On a 21 baseline the recipe has nothing to raise, and `_` cannot appear in Java 21 code without
  preview, so the rename has nothing to hit. Latent only: it bites the NEXT project to move to 25,
  and API-Sheriff's own override comment (`pom.xml:360-374`) already documents the trap. Downgraded
  from ⛔ cross-repo exposure to a note; no action owed.
- ⚠ **Root-path `/` still has no single normalisation, and no test carries `/` as a case (2026-09-11,
  lessons intake C14, lesson `2026-09-02-22-001` archived).** The lesson recorded five `/`-case defects
  on PLAN-02's branch and directed one canonical form plus one join per language boundary. Verified at
  `428bbec`: **seven independent normalisers**, each collapsing `/` → `""` correctly *today* —
  `BaseIntegrationTest.normalisePath:160`, `target.js:114-118` `rootPathSegment`, two copies in
  `pre-benchmark-health-check.sh:33-56`, and inline Python `rstrip("/")` in
  `start-integration-container.sh:185`, `start-dev-environment.sh:169`, `start-sample.sh:335`. Joins are
  bare concatenation. `ContextPathDefaultsTest:134-139` is parameterised but pins declared-vs-effective
  values, not normalisation; `ManagementRootPathLabelIT` is not parameterised. So the class the lesson
  named is **latent, not live**: nothing is wrong now, and nothing makes the eighth call site right.
  WS-02. Unowned — candidate for a small staged plan; no spec written.
  ✅ **RESOLVED 2026-09-23 by PLAN-28 (#341) deliverable 4.** Consolidated per language boundary: new
  `integration-tests`-scoped `RootPaths.java`/`RootPathsTest.java`, `lib-docker-compose.sh`'s
  `normalize_root_path` shared by `integration-tests`/`demo-client`, `deployment/compose-sample`
  deliberately kept its own inline copy (a documented distribution-boundary exception, not an
  unfinished consolidation). `/`-input test cases added per boundary.
- ⚠ **`CLAUDE.md:38-39` and `AGENTS.md:44` give the targeted-test example without `-am` (2026-09-11,
  lessons intake C11).** Correct for a sibling-clean tree, but it is the shape that silently tests
  installed artifacts after a sibling-module change; with `-am` the reactor reds every sibling on
  `No tests were executed!`. The fix is `-am -Dsurefire.failIfNoSpecifiedTests=false`; the string
  appears nowhere in the repo. The plan-marshall half (build-maven emits the same shape) was relocated to
  truthful-signals `api-sheriff-deployment-configurability-001`. Documentation-only fix. Unowned.
  ▶ **OWNED 2026-09-15 by PLAN-24 deliverable 10.** Still present at `a2969b9` (`CLAUDE.md:38`).
  ✅ **RESOLVED 2026-09-15 by PLAN-24 (#305, `fb65222`)** — `-am` added; the surefire flag deliberately NOT added (it only hides a misspelled selector under
  `-pl api-sheriff -am`, CodeRabbit d44822, operator decision) and documented as conditional at `CLAUDE.md:41`, `AGENTS.md:52`.
- ⚠ **PLAN-24 (#305) reported, not fixed — residue in files it did not own (2026-09-15, landing).**
  Recorded from the PR body and the landing message; each is a lead to re-verify before acting.
  - ⛔ **On PLAN-25's subject — check at PLAN-25's landing, do not fold into its running spec:**
    `doc/user/bff-cookie.adoc` still lists logout among the BFF back-channel trust legs (`:256`,
    *"discovery, token, refresh, logout"*) and carries a transitional note; the compose sample
    `deployment/compose-sample/docker/sheriff-config/gateway.yaml` trusted-proxy comment misdescribes
    `ForwardPolicyStage.applyRegeneratedForwarding`.
  - Unowned small doc residue: `doc/technical_aspects.adoc` cites a token-sheriff version below 0.9.5;
    `release.yml`'s "Both signed digests" comment is over-broad; `BuildGateCoverageContractTest`'s failure
    message cites `CLAUDE.md` line numbers now 3 off after #305; legacy dotted package keys and a stale
    `ConfigLoader.coerce()` note remain in `.plan/project-architecture` metadata that `architecture enrich`
    cannot remove; `configuration.adoc`, `LogMessages.adoc` and `security-threat-model.adoc` were swept by
    targeted search only, not read in full.
    ✅ **PARTIALLY RESOLVED 2026-09-23.** `doc/technical_aspects.adoc` version citation fixed by PLAN-28
    (#341) deliverable 5. `release.yml`'s "Both signed digests" comment verified ABSENT at HEAD `3e3addc`
    (no longer present — fixed at an unrecorded point, not reproducible now). The stale `ConfigLoader.coerce()`
    architecture note is re-verified ACCURATE (describes a distinct digit/boolean coercion ambiguity,
    unaffected by PLAN-28's new object-coercion arm) — not stale, kept as-is. Still genuinely unowned:
    `BuildGateCoverageContractTest`'s line-number citation (still cites `CLAUDE.md lines 65-66`,
    unverified whether still accurate) and the legacy dotted package keys.
  - ✅ **RESOLVED 2026-09-16 by PLAN-25 (#306).** `doc/user/bff-cookie.adoc`'s logout leg was fixed at
    PLAN-25's landing (confirmed in `landings/PLAN-25.md`'s reconciliation actions); the compose-sample
    trusted-proxy comment was checked and found not reproducible at `c74f5d2`.
  - Deferred by operator Hold: a contract test tying the `AGENTS.md` / `CLAUDE.md` module lists to the root
    `pom.xml` `<modules>` (CodeRabbit 6da2fb) — lesson `2026-09-15-17-001`.
- ✅ **RESOLVED 2026-09-17 by PLAN-26 (#314, `a475cff`).** The claim is now true and carries a test: strict
  rotation is enabled in both shipped realms, `BffRefreshReuseIT` proves a replayed token is refused end to
  end, and ADR-0046 records that detection is the IdP's job with the observed Keycloak semantics. The entry,
  for the record:
  ⛔ **Refresh-token reuse detection is claimed COVERED in server mode but no gateway mechanism exists
  (2026-09-15, cross-repo finding verification).** `doc/security-threat-model.adoc:1115-1128` and the
  `TokenRefreshCoordinator` javadoc attribute it to the engine's `RefreshTokenFamily` +
  `TokenLifecycleManager`; `api-sheriff/src` references neither. No shipped realm sets `revokeRefreshToken`,
  and `BffTokenRefreshIT` states it does not exercise reuse. The only unit test models reuse with an exception
  `classify` would read as `PRE_REDEMPTION`. ▶ **OWNED by PLAN-26** (deliverables 5-12; operator chose IdP-side
  detection, shipped in the realms and proven end to end).
- ✅ **RESOLVED 2026-09-16 by PR #309 (`3c68ee4`) — `default:emit-landing` lane is now `minimal`.** Operator
  decision; the orchestrator landed the operator-authored config change (config-only footprint, `build_map`
  drift clean, `skip-bot-review`, merge queue). Plans from here on file their own `kind: landing` inbox
  message, so a landing reconciles from machine-readable facts rather than a paste. The entry, for the record:
  ⛔ **Both round-1 plans shipped with `emit-landing` on lane `off`, so a merged plan was invisible to the
  epic (2026-09-16).** PLAN-18 (#308) reached the ledger only because `analyze` fetched `main` while
  corroborating PLAN-25; its inbox message never existed. This is the fifth instance of the epic learning
  nothing from a landing, and the first whose cause is a deliberate lane setting rather than a skipped step —
  the four earlier ones are routed upstream as truthful-signals `…-010`. ⚠ Decide whether the lane should be
  on for orchestrated plans; until then every landing needs a paste or an `analyze` sweep of `main`.
- ⚠ **The `Awaits.until`-then-re-assert mechanical guard was not landed (2026-09-16, PLAN-18).** The spec made
  it conditional (*"if cheap"*) and `AwaitsReassertionArchTest` is the one declared path with no realized
  file, so the last instance was removed with nothing stopping the next.
  ▶ **OWNED 2026-09-16 by PLAN-23 deliverable 4.** Feasibility checked before folding: the sweep mechanism
  already exists (`LoopbackEphemeralBindArchTest` § `WildcardHostLiteralSweep`, a source walk over
  `TEST_SOURCE_ROOT`), and only five `Awaits.until` sites exist tree-wide, so "cheap" holds. The fold adds no
  file surface — PLAN-23 declares `api-sheriff/src/test/` wholesale.
  ✅ **RESOLVED 2026-09-21 by PLAN-23 (#336) deliverable 3** (renumbered from D4 at outline) —
  `arch/AwaitsReassertionArchTest.java` (650 lines) landed with matched positive/negative control
  specimens (`AwaitsWithReassertionSpecimen`/`AwaitsWithoutReassertionSpecimen`) per
  `landings/PLAN-23.md`.
- ✅ **RESOLVED 2026-09-16 — PLAN-18's reversion proofs WERE performed.** The operator's report (arriving
  after reconciliation) states every change was mutated, observed red, restored and re-run green. The entry
  stood for the hours between the merge and the report: *"no paste, no inbox message, no readable reversion
  record in the merged artifact"*. PLAN-23 audits those tests like any others regardless.
- ⛔ **Three ADR filename declarations in a row named a file nobody wrote (2026-09-17).** PLAN-22 declared
  `0044-trusted-proxies-breadth-threshold.adoc`, PLAN-25 wrote `0044-Trusted-proxy_breadth_is_warned…`;
  PLAN-26 declared `0046-refresh-failure-dispositions.adoc` and wrote
  `0046-Refresh_failures_are_disposed_by_the_engines_failure_kind…` — **even though its spec carried the
  warning from PLAN-25's identical miss.** A warning in prose did not change the outcome. **Standing rule for
  every future spec: declare the ADR ORDINAL, never a guessed descriptive filename.**
- ⚠ **The `automatic-review` record's summary text can be stale (2026-09-17, PLAN-26).** The final record said
  *"unified triage pending"* while the pre-merge review barrier reported zero pending findings and complete
  bot participation. The barrier is the reliable signal; do not read the record's prose as a verdict.
- ⚠ **Server-mode back-channel logout is structurally unreachable in the integration realm (2026-09-17).**
  `refresh-client` registers no `backchannel.logout.url`, so the push has nowhere to go; the realm also omits
  the built-in `basic` client scope, so access tokens carry no `sid`. Reported by PLAN-26 deliverable 9 rather
  than worked around. Unowned — a successor that wants IdP-initiated logout coverage must fix the realm first.
  ✅ **RESOLVED 2026-09-23 by PLAN-28 (#341) deliverable 3.** `integration-realm.json` now registers
  `backchannel.logout.url` and the `basic` client scope; `BffBackchannelLogoutIT` (new, +214) proves
  IdP-initiated logout end-to-end — plus 3 real bugs fixed along the way (host-gated routing, a
  misapplied ID-token verifier now replaced by `SignatureOnlyTokenVerifier`, and the DEBUG-only
  rejection-path observability gap).
- ⚠ **Plan-marshall tooling gap: the orchestrator queue cannot record `superseded` (2026-09-15, corpus
  regroup).** `orchestrator.py` `VALID_STATUS_VOCABULARY` is `staged, launched, running, parked,
  shipped, landed`, yet the orchestration standard's cleanup apply-policy says a duplicate is
  *superseded, never deleted*, and PLAN-14's row already carries `superseded` from before the
  vocabulary was enforced. The only sanctioned write paths (`queue --transition`) refuse it, and the
  whole-array `plans` rewrite is reserved to seeding — so PLAN-09/19/20/21/22 had to be `parked`, which
  reads as "paused, may resume". Mitigated by spec banners and the queue annotation. Candidate for
  plan-marshall truthful-signals; not yet routed.
  ✅ **RESOLVED 2026-09-22.** The tooling gap closed upstream — `queue --transition` now accepts
  `superseded` as a target. PLAN-09/19/20/21/22/27 all transitioned `parked` → `superseded` this
  session; no longer routed as a defect since the fix already shipped.
- ⚠ **Stale comment at `pom.xml:201` (2026-09-11, lessons intake C1).** It still reads
  "UpgradeToJava21 -> UpgradeToJava25 replacement below", but PR #242 dropped **both** composites and
  `pom.xml:469`/`:493` now record them as intentionally excluded. The comment describes a mechanism that
  no longer exists — the exact shape lesson `2026-09-01-19-001` warned about ("a guard everyone believed
  in had been a no-op"). One-line fix; fold into any plan touching the root POM. Unowned.
  ✅ **RESOLVED, verified absent 2026-09-22 at HEAD `3e3addc`.** `pom.xml`'s exclusion comment block
  (now `:360-431`) is current and accurate — the stale line no longer exists. Fixed at an unrecorded
  point; not reproducible now.

- ⛔ **`session.cookie_name` is ENTIRELY UNVALIDATED — dropping the `__Host-` prefix silently loses
  the no-`Domain` guarantee (2026-09-10, finding `45e0ba`).** A security property removable by a
  typo, with nothing to catch it. ⚠ Needs `gateway.schema.json`, out of PLAN-16's scope. ⛔ **This is
  the sharpest of the four residuals** — the other three are sizing questions; this one silently
  widens where a session cookie is sent. Unowned. — source: PLAN-16 landing paste.
  ▶ **OWNED 2026-09-15 by PLAN-25 deliverable 5.** Re-verified at `a2969b9`:
  `ConfigValidator.resolvedCookieName()` (`:1327`) still only folds a blank name onto the default.
  ✅ **RESOLVED 2026-09-16 by PLAN-25 (#306, `7ba9734`)** — refusal shipped: `ConfigValidator`
  `HOST_COOKIE_PREFIX = "__Host-"` (`:158`), guarantee stated at `:113`/`:154`, `ConfigValidatorTest` +152.
- ⚠ **KEY MATERIAL IS THE ONE SURFACE WITH NO DIAGRAM, AND IT IS THE ONE THAT MOST NEEDS ONE
  (2026-09-10, operator-raised).** Verified at HEAD: `doc/resources/diagrams/` holds **ten** SVGs —
  sequences, topologies and flows — and **not one covers TLS, certificates, keys or trust**.
  `doc/user/tls-edge.adoc` and `doc/configuration.adoc`, the two documents that most need one, carry
  **zero `image::` blocks**.

  ⛔ **The subject is genuinely graph-shaped, which is why prose keeps losing.** This epic has now
  documented **seven distinct key-material paths** across five documents and six log records, and the
  relationships — not the individual facts — are what an operator gets wrong:
  server identity via `certificate.files` / `key-store-file` / `credentials-provider` / a named
  `quarkus.tls.*` bucket; the management interface's key-less `plain-management` bucket and the
  never-declare-a-default-key-store rule; inbound `client_ca` for mTLS; **outbound
  `egress_tls.upstream_tls_profile`, which REPLACES the JVM bundle** (`ApiSheriff-119`); **JWKS
  `jwks.tls_profile`, whose SSLContext is MUTUALLY EXCLUSIVE with `verifyHostname(false)` and refused
  at boot** (ADR-0041); and `CookieKeyMaterial`, which is key material of an entirely different kind.
  **Three of those replace rather than extend, and one pair is refused in combination.** That is a
  diagram, not a paragraph.

  ⛔ **BUT THE DELIVERY MECHANISM IS THE RISK, AND THIS EPIC IS ALREADY CARRYING THE PROOF.** No
  `.puml`, `.mmd` or `.dot` source exists anywhere — **every SVG is hand-authored XML**, geometry
  placed by coordinate arithmetic. That is precisely why PLAN-03's topology SVG has an **open
  read-back defect**: overlap and both themes unverified, with a WARNING block still sitting in the
  document until someone renders it. ⚠ **Adding hand-computed SVGs to the most complex surface in the
  product multiplies unverifiable artifacts** — a diagram nobody can render is a claim, and this epic
  has recorded five of those already.
  🔄 **RECOMMENDATION CORRECTED 2026-09-10 — my "adopt a generated format" counsel is WITHDRAWN, and
  SVG is right.** The operator pointed at `pm-documents:ref-svg-diagrams`, and reading it dissolves
  the concern: the standard **already mandates** exactly the verification I was proposing to invent.
  Its Step 4 is titled *"Verify the render (MANDATORY, BLOCKING)"* and is **non-skippable** — render
  against `#ffffff` and `#0d1117` and **inspect the PNGs**, with the rationale stated outright:
  *"Authoring an SVG and trusting that 'the markup looks right' is forbidden — coordinate math, font
  fallback, alignment, marker placement and theme contrast can only be evaluated on the rendered
  output. Skipping this step has shipped misaligned diagrams to users in the past and is the most
  common defect class for hand-authored SVGs."*
  ⛔ **So the gap was never missing capability — it was a skipped mandatory step, and the standard had
  already predicted the exact failure.** The skill also supplies a palette, theme strategies, an
  AsciiDoc embedding macro, accessibility requirements (`role="img"`, `<title>`, `<desc>`) and
  seven per-type standards. For key material the type is `diagram-type-graph.md` (hub-and-spoke) or
  `diagram-type-block.md` (producer/store/consumer), decided at authoring.
  ✅ **Net: a key-material diagram is a straight YES on hand-authored SVG**, provided Step 4 is
  actually run — which is now demonstrably two commands on this machine. Unowned. — source: `doc/resources/diagrams/` listing,
  `image::` counts, and source-format search at HEAD `a30fe6f`.
  ✅ **RESOLVED — shipped by PLAN-24 (#305), confirmed 2026-09-22.** `doc/resources/diagrams/tls-key-material.svg`
  exists and is embedded at `doc/user/tls-scenarios.adoc:66`. Discovered already-shipped while
  authoring PLAN-28's spec, and deliberately excluded from that plan's deliverables as already done.

- ⛔ **`tls.passthrough_sni` CANNOT be supplied from the environment — the exact limitation PLAN-06
  fixed for `trusted_proxies`, left unfixed here (2026-09-10, operator-raised).** Verified at HEAD:
  `gateway.schema.json:137-141` declares it an **object** — *"Map of SNI hostname → topology alias,
  relayed at L4 without decryption"* — with `additionalProperties: {type: string}`.
  ⛔ **PLAN-06's list-valued `${VAR}` arm covers ARRAYS of strings, not MAPS.** So a single variable
  cannot supply the pairs, and **the map's cardinality is baked into the mounted file**: an operator
  cannot add a passthrough backend without editing YAML. That is verbatim the complaint PLAN-06
  answered for `forwarded.trusted_proxies` — *"an operator cannot go from three trusted proxies to
  four without editing YAML"* — and it is still true one level over.
  ✅ **The schema itself calls it topology** (*"topology alias"*), and ADR-0025's own classification
  puts environment-specific, topology-load-bearing values on the deployment-supplied side.
  ⚠ **The fix is an extension, not a redesign**: `coerce` gained an `array` case in PLAN-06; it needs
  the `object`/map case. The declaration stays in `gateway.yaml` where the schema validates its shape;
  only the values move. Unowned. — source: schema read and ADR-0025 § classification at HEAD `a30fe6f`.
  ✅ **RESOLVED 2026-09-23 by PLAN-28 (#341) deliverable 2.** `ConfigLoader` (moved to
  `config/load/`) gained an `object` coercion arm, proven against `tls.passthrough_sni`. A real
  CWE-522 secret-pointer bypass was found and fixed in the same deliverable (whole-object
  substitution now refuses when the object contains a secret-classified field).
- ⛔ **cui-http 3.0's `paranoid()` preset is UNADOPTED — the second unadopted capability from the same
  bump (2026-09-10, operator-raised).** `SecurityConfiguration` in 3.0 offers four presets —
  `strict()`, `paranoid()`, `lenient()`, `defaults()` — and `paranoid()` is documented as *"strict()
  plus the application-layer content detection"* (`SecurityConfiguration.java:227`). This gateway's
  `SecurityProfile` (`:56-68`) exposes only `STRICT`, `LENIENT` and its own `MINIMAL`, and
  `preset()` (`:121-127`) maps `STRICT → strict()`, `LENIENT → lenient()` with **no `PARANOID` arm at
  all**.
  ✅ **Operator ruling: `strict` stays the default; `paranoid` becomes an option, in code and
  documentation.** Adding it fits the existing pattern exactly — `PARANOID` is a cui-http preset like
  the other two, unlike `MINIMAL` which is this gateway's own partial-disable concept.
  ⛔ **This is the SECOND capability the 3.0/0.9.5 bumps carried in unnoticed.** The first is
  `RotationResult` widening 5→7 components with an unread `scopeDelta` (PLAN-04's landing, still
  open). **The pattern is that a dependency bump taken for one API arrives carrying others nobody
  enumerated** — PLAN-03 took cui-http 3.0 for `verifyHostname` and `paranoid()` came with it.
  ⚠ **Worth a standing practice rather than two one-off fixes**: a major-version bump should
  enumerate what else the new surface offers, not just confirm the API it was taken for. Unowned. —
  source: cui-http 3.0 sources jar and `SecurityProfile.java` at HEAD `a30fe6f`.
  ✅ **RESOLVED 2026-09-23 by PLAN-28 (#341) deliverable 1.** `SecurityProfile` gained a `PARANOID`
  arm mapped to `SecurityConfiguration.paranoid()`; `STRICT` stays `DEFAULT_PROFILE`. Documented at
  all three symmetric JSON-Schema enum sites (refine's own Q-Gate caught the third site the spec
  didn't name) and bound by a contract test.

- ⚠ **`.plan/marshal.json` is stale — `0.1.1617` against `0.1.1632`** (🔄 re-read 2026-09-11: still
  `system.provisioned_version = 0.1.1617`, while the executor is now `MARSHALL_VERSION = '0.1.1636'` —
  further behind, not caught up). Run `/marshall-steward` when
  convenient. ⚠ Note the epic's own history here: the last steward sync introduced churn that had to
  be re-applied mid-flow, so do it between plans rather than while two are running. — source: PLAN-07
  landing paste.
  🔄 **RE-READ 2026-09-23: gap narrowed but not closed.** `provisioned_version` is now `0.1.1744`,
  executor is `0.1.1741` — 3 versions behind, down from the much larger original gap, but still stale
  by this entry's own criterion. `/marshall-steward` still owed; low severity. Genuinely open.
- ⚠ **Machine-local port-collision flakes cost two full-suite re-runs this session.** Something on
  this workstation intermittently holds loopback ports these tests assume free. ⚠ **This is the
  fourth sighting of the class** — PLAN-11 measured it, PLAN-13 fixed the wildcard-bind half, PLAN-03
  saw `TlsEdgeProducerTest` and `SniFrontListenerTest` fail once each under load 119, and now this.
  ⛔ **PLAN-13's fix is not refuted by it**: that closed the dual-stack wildcard bind, while these are
  *foreign* holders of ports the fixtures assume free — a different mechanism with the same symptom.
  Unowned. — source: PLAN-07 landing paste.

- ⛔ **A FIFTH UNPINNED OUTBOUND HTTPS LEG, AND IT CARRIES THE `client_secret` (2026-09-08).**
  `BffRuntimeProducer:208` builds `ClientConfiguration` with **neither** `verifyHostname` nor
  `sslContext`, and it is dialled by `DiscoveryResolver` / `TokenEndpointClient` / `RefreshFlow` —
  discovery, code exchange **and refresh**. It is secure today only via the upstream
  `@Builder.Default true`, which is **precisely the reliance `TokenValidatorProducer` refuses for the
  lower-value JWKS leg** — so the gateway now holds two opposite postures on the same question, and
  the weaker one guards the higher-value secret.
  ⛔ **This is the SECOND fifth-leg-class finding in two landings.** PLAN-03's security audit found a
  fourth egress leg that five documentation sites denied existed; PLAN-04 found this one. **The egress
  inventory has now been wrong twice in a row**, which makes "we have enumerated the egress paths" a
  claim this epic should stop accepting without a fresh sweep. ⚠ The code half is out of PLAN-04's
  footprint and **wants its own plan** — a WS-03 successor, and a natural pairing with any repeat of
  the egress sweep. — source: PLAN-04 landing message, corroborated against
  `TokenValidatorProducer:266`/`:271` at HEAD `054b3e4`.
  ✅ **RESOLVED 2026-09-16 by PLAN-25 (#306) deliverable 1 — never marked resolved in this ledger
  until now (2026-09-23 correction).** New peer keys `egress_tls.oidc_verify_hostname` /
  `oidc_tls_profile` pin exactly this leg (discovery/token/refresh via `BffRuntimeProducer`), with an
  ArchUnit posture guard (`EgressTlsPostureArchTest`) carrying matched positive/negative specimens.
- ⚠ **`RotationResult` widened 5→7 components in 0.9.5 and its `scopeDelta` signal is unread.** A
  NARROWED or BROADENED scope on token refresh is currently **unobserved** by this gateway; the
  fixture was migrated but the signal was not adopted. ⚠ **Directly relevant to RUNNING PLAN-05**,
  whose entire subject is the refresh path — a scope change during refresh is exactly the class of
  silent behaviour its reproduction attempt might otherwise miss. Unowned. — source: PLAN-04 landing
  message.
  ✅ **CLOSED 2026-09-23 by PLAN-29 (#348) — not fixed gateway-side, closed as already covered.**
  PLAN-29 staged a gateway-side `TokenRefreshCoordinator` WARN for this (deliverable 1), then dropped
  it after finding the engine's `RefreshFlow.reportScopeDelta` already logs `TokenSheriffClient-110`
  (`SCOPE_NARROWED`) / `-111` (`SCOPE_BROADENED`) at WARN — independently verified via `javap` against
  the resolved `token-sheriff-client-0.9.6.jar` rather than taken on the plan's word. A gateway-side
  record would have duplicated an existing signal. `TokenRefreshCoordinator.rotate()` still does not
  read `scopeDelta()` itself, and that is now the correct end state, not a residual gap.
- ⚠ **The JWKS knob has no deployment-activation guard** — proven at unit level only, by operator
  decision. Recorded so a later reader does not mistake unit coverage for deployment proof. — source:
  PLAN-04 landing message.

- ⚠ **Issue #269 — `doc/configuration.adoc`'s array-key inventory claims a guard it does not have.**
  The inventory is hand-maintained against two schemas while stating it *"is checked against those
  files."* Pre-existing, surfaced by PLAN-03; needs a contract test. ⚠ **Note the shape**: this is
  lesson `2026-09-02-22-002` (*"A comment naming its own guard is a claim, not a guard — open the
  guard and look"*) recurring in **documentation** rather than code, which is where it is harder to
  notice because no build ever reads it. Unowned. — source: PLAN-03 landing paste, issue filed.
  ✅ **RESOLVED 2026-09-16 by PLAN-25 (#306) deliverable 4 — never marked resolved in this ledger
  until now (2026-09-23 correction).** `config/DocumentedSetsContractTest.java` (+868) is the
  contract test issue #269 asked for; issue closed.
- ⚠ **`TlsEdgeProducerTest` and `SniFrontListenerTest` are RESIDUAL SITES of the loopback class PR
  #255 addressed.** Each failed once locally on **port-collision control preconditions under load
  average 119**, passing on re-run and in CI. ⛔ **Read this precisely — it is not a refutation of
  PLAN-13's fix**: those are the fixtures PLAN-13 explicitly carved out or left to their own
  preconditions, and a control precondition failing under load 119 is the *contended-verify-budget*
  defect meeting the *loopback* defect, not either one alone. ⚠ It is also the second independent
  sighting of `TlsEdgeProducerTest` in that class — the ArchUnit guard's single carve-out is exactly
  `tls.TlsEdgeProducerTest`. ~~Unowned.~~ **OWNED by PLAN-23 deliverable 3** (staged 2026-09-10). —
  source: PLAN-03 landing paste, corroborated against the guard's own carve-out constant.
  ✅ **RE-VERIFIED 2026-09-11 at `origin/main` `428bbec` (lessons intake, C2) — and the site is not
  what two lessons said it was.** `startsAndStopsFrontListener` holds **no** port: its precondition
  (`:117`) is a loopback *connect* to a port allocated by a **wildcard** `new ServerSocket(0)` in
  `freePort()` (`:282`, re-probe `:309`). The three collision holders (`:160`, `:202`, `:229`) must stay
  wildcard — binding them to loopback would invert the refusal they assert — but the **allocation
  socket** at `:282` can allocate on loopback while keeping the wildcard re-probe, which inverts
  nothing. `SniFrontListenerTest:145` is a residual wildcard site *through production*
  (`SniFrontListener.java:98` `netServer.listen(publicPort)` with `0`), dialled on loopback at `:151`;
  the arch rule cannot see it because the call is in `src/main`. Carried into PLAN-23 D3.
  ✅ **RESOLVED, verified 2026-09-23 at HEAD `3e3addc`.** Correction to the "carried into PLAN-23 D3"
  note above — the actual fix landed via PLAN-18 (#308, `c74f5d2`), not PLAN-23. Confirmed in current
  code: `TlsEdgeProducerTest.freePort()` (`:284`) now allocates via
  `new ServerSocket(0, 0, InetAddress.getByName(LoopbackHost.ADDRESS))` — loopback-specific, exactly
  the fix this entry's own re-verification prescribed — while the three collision-holder controls
  (`:160`, `:203`, `:231`) correctly stay wildcard `new ServerSocket(0)`. `SniFrontListener` gained a
  `host` constructor parameter (defaulting to `NetServerOptions.DEFAULT_HOST` for production),
  enabling the loopback override. No further action needed.

- ⛔ **Two commits were pushed without a complete local verify, and the cause is structural for this
  epic (2026-09-06).** PLAN-13 disclosed it rather than letting it pass. Both local runs were clipped
  at their wall-clock budget by a **concurrent plan-marshall run in another worktree driving load to
  150–200** — explicitly *not* stalled: zero errors, steady progress, changed tests green at the cut
  point. ✅ **Nothing shipped unverified**: the stricter `-Ppre-commit` gate completed fully green on
  the final round, and CI ran the complete suite on JDK 25 **and** 26 and gated the merge. ⛔ **But
  the local half of the documented pre-commit process was not completed on those two commits**, and
  this epic cannot treat that as a one-off: `parallelization_scope = 3` makes concurrent plans the
  *design*, so local verify budgets are contended **by construction** and this will recur whenever
  two plans execute at once. Either the budget accounts for concurrent load, or concurrent plans need
  a load-aware gate. ⚠ Note the interaction with the epic's own history: a local red here is already
  presumptively environmental (lesson `2026-09-01-14-002`), and a local *timeout* under contention is
  a second way the local signal degrades without the branch being at fault. Unowned. — source:
  PLAN-13 landing paste, recorded as disclosed.

- ⛔ **AN ADR IS OWED AND WAS DELIBERATELY NOT WRITTEN — the one open item from PLAN-15 (2026-09-05).**
  `adr-propose` scanned all **39** corpus ADRs and found **no** coverage of `trusted_proxies` breadth
  or the warn-vs-reject threshold. Draft title: *"The trusted_proxies breadth warning is thresholded
  at one operator-provisioned network, and its un-warned residual is declared"*. The full draft is
  preserved in PLAN-15's decision log. ✅ **The decline was correct and must not be read as an
  oversight**: the plan's declared scope (record the reasoning) was met by the `validateForwardedTrust`
  Javadoc plus `doc/configuration.adoc`, and adding a file post-review would have re-staled BOTH
  required bots and forced another trigger/review/triage cycle — a real cost on a PR whose review was
  already the long pole. ⛔ **But the decision it records is exactly what the ADR corpus is for**: a
  security threshold moved on stated reasoning with named rejected alternatives, and a declared
  un-warned residual (IPv4 `/16`, IPv6 `/48`). Ordinal `0040` is next free. **Epic decision owed:
  schedule it, or record why the Javadoc + configuration.adoc pair is a sufficient home.** — source:
  landing message `trusted-proxy-breadth-and-probe-doc-005.md`, corroborated against `doc/adr/` at
  HEAD `558a38b`.
  ✅ **RESOLVED 2026-09-16 by PLAN-25 (#306) — never marked resolved in this ledger until now
  (2026-09-23 correction).** `doc/adr/0044-Trusted-proxy_breadth_is_warned_beyond...adoc` written
  (declared-filename-vs-real-file mismatch noted separately as the standing ADR-authoring rule).
- ⚠ **`re_review_on_loopback: false` has no recorded rationale, and it costs a loop-back every time.**
  Confirmed at `.plan/marshal.json:108`. It is why CodeRabbit stayed silent after PLAN-15's fix push.
  If the setting is intentional, the loop-back path owes an **explicit trigger for every required
  bot** as part of the loop-back rather than leaving it to be discovered by a stalled barrier. Paired
  with PR-Agent's own workflow, which never re-triggers on push, this means **two different manual
  acts on every loop-back**. Unowned; a project-config decision. — source: read of
  `.plan/marshal.json:108` and `.github/workflows/pr-agent.yml` triggers at HEAD `558a38b`.
  ✅ **RESOLVED 2026-09-23 by PLAN-28 (#341) deliverable 7.** Rationale recorded in
  `doc/development/re-review-on-loopback.adoc` (new); `re_review_on_loopback: false` kept, contrasted
  explicitly with `re_review_on_branch_cleanup: true`.
- ⚠ **Two now-redundant tests survive in `ConfigValidatorTest`.** `shouldAcceptTightlyScopedCidrs` and
  `shouldWarnBroadButNotTotalCidr` are subsumed by PLAN-15's new parameterized
  `broadPrefixThresholdControls`. Removing them would have reached outside that changeset's
  line-level scope, so the simplify pass recorded rather than applied it. ✅ **Not worth a plan** — a
  fold candidate for any future `ConfigValidatorTest` work. — source: PLAN-15 landing residue.

- ⚠ **`WebSocketRelayStageTest.preservesSecurityHeadersOnHandshakeFailure` is flaky in CI.** It
  ejected PR #254 from the merge queue once, on a tree identical to one that had already passed CI
  twice, in a component the branch never touched — corroborated: the test is at
  `WebSocketRelayStageTest.java:252` and `git show --stat 6ba8879` shows that file absent from the
  merged diff. The plan established the flake before retrying rather than assuming it, and the
  re-queue was green. ⛔ **Distinct from the wildcard-bind defect and NOT fixed by PLAN-13's
  conversion** — PLAN-13 edits this file for the `listen(0)` class only. It will eject someone else.
  Unowned. — source: file read plus merged-diff check at HEAD `6ba8879`.
  🔄 **RECURRENCE 2026-09-05, from a CROSS-REPO run — folded here, not filed as a second defect.** A
  different repository's build saw this same test file fail on a **5023 ms against a 5000 ms
  timeout**, under parallel-build load, in the control arm only. ⛔ **It is a different SYMPTOM on the
  same file**, not a repeat of the merge-queue ejection: a 23 ms overshoot of a wall-clock ceiling,
  which is the fixed-await class this epic has been chasing since PLAN-11, rather than a handshake
  assertion failing. ⚠ **It also predicted itself** — the other repo classified it as
  "timing-sensitive, not migration-related", which is the same *presumptively environmental* reading
  lesson `2026-09-01-14-002` prescribes, reached independently. ⛔ **Still not fixed by PLAN-13**: a
  5 s ceiling overshoot has nothing to do with wildcard binds. The evidence now spans two
  repositories and two distinct symptoms, which strengthens rather than dilutes the case that this
  file's awaits are under-budgeted for loaded machines. — source: operator-relayed cross-repo
  observation, treated as a lead; the local half (test file and its ceiling) is first-party.
  ✅ **CLOSED (contradicted) 2026-09-21 by PLAN-23 (#336) deliverable 6.** The spec's own HYPOTHESIS
  claim (index 2) was investigated and stamped `contradicted | rescoped: yes` via `corpus set-verdict`
  — "no artifact in the tree, no CI signal across the 30 most recent Maven Build runs." Distinct from,
  and not to be confused with, the idle-release-await ceiling PLAN-28 (#341) separately widened in the
  same file (deliverable 6 of that plan) — two different sites, both now addressed.
- ⚠ **Issue #256 — `BROAD_PREFIX_IPV4 = 8` lets a `/12` trust range boot silently, and PLAN-06 raised
  its severity.** `ConfigValidator.checkFamilyTrust` warns only below the threshold, so
  `172.16.0.0/12` — Docker's entire default bridge pool, 1,048,576 addresses — and every IPv4 range
  from `/9` to `/32` boot with no warning at all. The value used to sit in a committed `gateway.yaml`
  a human reviewed; PLAN-06 deliberately moved it into an environment variable **no code review
  sees**, leaving this boot warning as the only remaining signal on it. Filed by the plan and
  corroborated in full via `ci issue view --issue 256`. Unowned — a WS-05 successor candidate. —
  source: issue body read through the CI abstraction at HEAD `6ba8879`.
  ✅ **RESOLVED 2026-09-16 by PLAN-25 (#306) deliverable 7 — never marked resolved in this ledger
  until now (2026-09-23 correction).** `BROAD_PREFIX_IPV4` raised to `16` (kept at WARN, no reject
  threshold added — operator ruling), ADR-0044 records the decision, issue #256 closed.
- ⚠ **`deployment/compose-sample/.env` line 4 names the renamed `wait-for-ready.sh`.** Verified: the
  comment reads *"the same derive-don't-restate rule wait-for-ready.sh follows for the readiness probe
  (ADR-0031)"*, and `deployment/compose-sample/scripts/` now holds only `start-sample.sh` and
  `stop-sample.sh`. Disclosed by PLAN-02 rather than dropped; tooling prohibits committing `.env`.
  ✅ A one-line comment fix on a non-build input, so it skips both gates. Unowned. — source: file read
  at HEAD `1c7308c`.
  ▶ **OWNED 2026-09-15 by PLAN-24 deliverable 11.** Still present at `a2969b9` (`.env:4-5`).
  ✅ **RESOLVED 2026-09-15 by PLAN-24 (#305, `fb65222`)** — `.env:1-6` now names `start-sample.sh`'s step 2 and the management labels.
- **`benchmarks` module metadata is stale.** Described as WRK-based but genuinely k6, with WRK wording
  in five fields of `benchmarks/enriched.json` including a package key that no longer exists.
  Pre-existing drift PR #230 aggregated rather than created. Fix at the `enriched.json` root —
  `architecture discover` regenerates `_project.json` from it. Unowned.
  ▶ **OWNED 2026-09-15 by PLAN-24 deliverable 10.**
  ✅ **RESOLVED 2026-09-15 by PLAN-24 (#305, `fb65222`)** — `enriched.json` corrected through `architecture enrich`, `CLAUDE.md:14` says k6.
- **CI blind spot: `.plan/marshal.json` is not a build-triggering path**, so
  `BuildGateCoverageContractTest` never runs on CI — which is how the `build.map` glob erasure reached
  `main` unseen in `cea163c`. Needs an org-managed `cuioss-organization` workflow change. CLAUDE.md
  now documents the gap and the manual mitigation. Unowned here.
  ✅ **RE-VERIFIED 2026-09-11 at `428bbec` (lessons intake, C15, lesson `2026-08-29-16-001` archived).**
  The exclusion is explicit: the pinned reusable workflow's `check-changes` filter emits `'!.plan/**'`
  with `predicate-quantifier: 'every'`, and `.github/project.yml` adds no extra path. The guard itself
  is intact (`BuildGateCoverageContractTest:131-134` pins all four globs; `build.map` carries them).
  ⚠ Documentation gap: `doc/development/build-gate-discipline.adoc` does **not** describe this — only
  `CLAUDE.md:134-135` does. The generalisable rule the lesson taught — *a guard over file class X is
  armed only if a change to X triggers the job that runs it* — has no home in the repo's own docs.
  📝 **Issue DRAFTED, not filed (operator request, 2026-09-11):**
  [`drafts/cuioss-organization-issue-check-changes-force-include.md`](drafts/cuioss-organization-issue-check-changes-force-include.md).
  Verified at the pin and at `v0.26.0`: the filter offers `paths-ignore-extra` (add ignores) and
  `skip-on-docs-only: false` (disable the skip wholesale) but **no force-include** — so the only
  project-side remedy today is building every docs-only PR. Filing is the operator's call.
  ✅ **OPERATOR DECISION 2026-09-15: draft NOT filed.** Operator: `build.map` is relevant only to
  plan-marshall, not to CI builds. Correct — `build.map` is read only by plan-marshall's local
  `build-decision`; CI decides what to build from its own path filter, so an erased entry cannot make
  CI skip or mis-build code. Asking the org to change a shared workflow for a plan-marshall-internal
  config is disproportionate. Residual CI effect is small: a `marshal.json`-only PR that breaks
  `BuildGateCoverageContractTest` turns the NEXT code PR red rather than itself. The erasure mechanism
  (`build-map seed --force` / steward reconcile dropping hand-added entries) is a plan-marshall-side
  concern if it is ever pursued. Draft kept as the record; no upstream action owed.
- **JFR overlay creates a world-writable `/tmp/jfr-output` (`chmod 777`)**, same pattern in
  `integration-tests/scripts/prepare-jfr-output-dir.sh`. Pre-existing, explicitly accepted as out of
  scope by PLAN-01. Unowned.
  ▶ **OWNED 2026-09-15 by PLAN-24 deliverable 11** (the Dockerfile half; the script is a test fixture).
  ✅ **RESOLVED 2026-09-15 by PLAN-24 (#305, `fb65222`)** — `Dockerfile.native.jfr:32` now `chown 1001:root` + `chmod 0750`; the prepare script aligned.
## Watches

> ↪ Relocated to `settled.md` § "Watches — handled (relocated 2026-09-11)" — 38 discharged, moot or settled entries, each with its closing evidence; the entries below are the live ones.

- ✅ **RETIRED 2026-09-23, moot by advancement.** 👁 **The `main` Maven Build runs for `fb65222` / `7ba9734` / `c74f5d2` — NOT YET VERIFIED (2026-09-16).**
  ✅ **Benchmark half discharged**: #308's `Run Integration Benchmarks` (run `35077796596`) and #306's
  (`35074374324`) both finished SUCCESS; `ci checks status` now reports `overall_status: success` for both
  PRs. What remains is the push-to-`main` half. The push-to-`main` runs
  carrying `deploy-snapshot` are not attached to either PR and the CI abstraction has no commit-addressed
  read (truthful-signals `-013`). Retire once confirmed, e.g. `! gh run list --commit c74f5d2`.
  ✅ **Never individually confirmed, but moot**: `main` has since advanced 436 commits past `fb65222`
  to `1994f28` (PLAN-28's own merge) and beyond, through many more merges and release cuts, each of
  which required its own push-to-`main` build to succeed. The commit-addressed CI read gap
  (truthful-signals `-013`) is still real as a tooling gap, but this specific watch is discharged by
  advancement — a build pipeline this broken this long ago would have been unmissable since.
- ✅ **RETIRED 2026-09-23, moot by advancement (same reasoning as above).** 👁 **`main` Maven Build for `fb65222` (PLAN-24's merge) — NOT YET VERIFIED (2026-09-15).** The PR-attached
  post-merge benchmark run passed (`ci checks status --pr-number 305`), but the push-to-`main` run carrying
  `deploy-snapshot` is not attached to the PR and the CI abstraction has no commit-addressed read
  (truthful-signals `-013`). Retire once confirmed green, e.g. operator `! gh run list --commit fb65222`.
- ✅ **RETIRED 2026-09-16 — fired and discharged.** 👁 **PLAN-25 landing → fold into PLAN-24's TLS scenario guide (2026-09-15).** PLAN-24 landed first, so
  any BFF-client-leg knob PLAN-25 ships is missing from `doc/user/tls-scenarios.adoc`. At PLAN-25's
  analyze: add the row (small follow-up spec or fold into a live doc plan).
- ✅ **RETIRED 2026-09-16 — both landed with no rebase collision; their realized footprints were disjoint.** 👁 **Running PLAN-18 / PLAN-25 rebase over `fb65222` (2026-09-15).** #305 rewrote seven
  `.plan/project-architecture/**` files and `CLAUDE.md`; neither is in either running plan's declared
  surface, but both plans' own finalize `architecture-refresh` regenerates that metadata. Expect a
  regeneration rebase, not a code conflict; record any real collision at their landings.
- 📌 **RELEASES 0.2.0 AND 0.2.1 ARE CUT AND TAGGED (2026-09-11) — the epic's deployment goal is
  SHIPPED.** Sequence on `main`: `0b93499` declare 0.2.0 (#292) → `733a4cb` prepare release 0.2.0 →
  `e42a080` repair build-parent so the example contract survives a release tag (#293) → `e85b551`
  declare 0.2.1 (#294) → `a2ce218` prepare release 0.2.1 → `428bbec` next development iteration.
  ⚠ **`0.2.1` exists because `0.2.0` needed a build-parent repair** (#293) — read that PR before
  treating 0.2.0 as the reference cut.
  ⚠ **Five PRs are open at restart**: #299 (the post-release content remediation, this session's
  branch), #295 (org workflows v0.26.0), and dependabot #296/#297/#298. ⛔ **The release runbook's
  Step 2 requires ZERO open PRs on the dispatch path and exactly one on the merge path** — so the tree
  is NOT in a cuttable state until these drain. Not a defect; a precondition for the next cut.
  🔄 **UPDATED 2026-09-11 (`cleanup`)**: #299 (`64fe822`), #296, #297 and #298 have **merged** —
  `origin/main` is `0515e15`. Open now: **#295** (org workflows v0.26.0) and a new **#300**
  (`cui-quarkus-parent` update). Still two open PRs, so still not cuttable on the dispatch path.
  ✅ **RETIRED 2026-09-23, moot by advancement.** `main` is now at `1994f28` (PLAN-28) and beyond
  (436 commits, through PR #345), many releases past 0.2.1. This watch's specific PR list (#295,
  #300) is ancient history; whatever release-cuttability state matters now is a fresh question for
  whoever next runs the release process, not something this stale snapshot can answer.

- ✅ **THE CAPABILITY NUMBER IS NOW HARD, AND PLAN-19 NEEDS IT (2026-09-11).** Three tokens seal to
  **2700 bytes against the 4019-byte budget — 32.8 % spare**, corroborated in PR #288's own body
  (*"3433 bytes of framed plaintext seal to a 2700-byte cookie value"*), and **a real Chromium
  confirmed it stores the cookie**. ⛔ **That browser check is precisely the control whose absence
  let nine integration tests pass against a session no browser could hold** — the epic's founding
  cookie defect, now closed by a measurement of the thing itself rather than of a proxy for it.
  ⚠ **PLAN-19's deliverable 1 must state the conditioned capability using these numbers**, not the
  retired *"cookie mode and refresh do not work together"*.

- ✅ **RETIRED 2026-09-23, moot — the queue is fully terminal.** ⚠ **TWO SURFACES ARE NOW LIVE THAT NO STAGED SPEC DECLARES (2026-09-11).**
  `doc/user/compose-sample.adoc` (+125, new in PR #286) and `demo-client/` (PLAN-17 added
  `tests/05-cookie-size-budget.spec.js`, `utils/constants.js`, `doc/playwright-suite.adoc` in
  PR #288). ⛔ **PLAN-09 and PLAN-19 both sweep `doc/user/` and neither declares the new file**, so
  the disjointness matcher will report nothing about it — the directory-versus-file blind spot, now
  arriving from the *landing* side rather than the staging side. Decide ownership before the next
  emit round. — source: diff read at HEAD `7fce677`.
  PLAN-09 and PLAN-19 are both `superseded` and will never emit; no future round exists for this to
  matter to.

- 📌 **STANDING DIRECTION 2026-09-07 — DPoP is the sender-constraining direction; mTLS is ALPHA and not
  first-production relevant.** Operator decision, spanning this repo **and** TokenSheriff. Tests and
  structures adapt accordingly, mTLS is labelled alpha explicitly, and non-mTLS paths are the focus.

  ⛔ **The scope boundary is load-bearing, because this repository has TWO different "mTLS" things and
  only one is affected:**

  | | Affected? |
  |---|---|
  | **OAuth client auth / sender-constrained tokens** — `tls_client_auth`, `MtlsClientAuth` (RFC 8705), the DPoP alternative. The gateway proving *its own* identity to the IdP. | ⛔ **YES — alpha, deprioritized** |
  | **Inbound client-certificate TLS** — `MtlsServerCustomizer`, `TlsConfig`, the `client_ca` trust anchor. The gateway verifying *a client* at its own listener. | ✅ **NO — untouched, shipped feature** |

  DPoP is not an alternative to the second. A directive read too broadly would strip a shipped
  capability, and PLAN-03's own spec already keeps that surface out of scope for the same reason
  (*"the INBOUND client-cert surface. Untouched: that is the gateway verifying a client, not a host
  name."*).

  ✅ **Verified current state at `3fc4c83`: this gateway uses NEITHER.** The BFF hard-codes
  `client_secret_basic` (`BffRuntimeProducer.java:210`, `:212` — no selector, no config key) and `:226`
  states outright *"no sender constraint (DPoP is not in use)"*. So this is a forward-looking direction,
  **not a migration off something in place** — nothing is being removed.

  ✅ **The direction is already evidence-backed upstream.** TokenSheriff's
  `refresh-elimination-verdict.md` §1.3 variant 3 exercises DPoP-bound sender-constraint continuity
  across a real rotation, asserting the `cnf.jkt` thumbprint survives. So DPoP refresh is *covered*
  while mTLS is not — which under this direction makes the mTLS residual **out of scope, not a gap**.
- ✅ **RETIRED 2026-09-23, moot — the queue is fully terminal, no future emit round exists.** ⛔ **A SECOND, DISTINCT GATE BLIND SPOT, measured while staging PLAN-13: directory-versus-file.**
  PLAN-07 and PLAN-08 declare `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/` as a
  DIRECTORY; PLAN-13 declares three FILES inside it (`PassthroughRelayTest.java`,
  `SniFrontListenerTest.java`, `TlsEdgeProducerTest.java`). `corpus cross-check` produces **no
  overlap row** for either pair, because the matcher compares normalized paths for equality and a
  file never equals its containing directory. This is NOT the production/test-pair blindness already
  recorded against PLAN-03 — it is a separate failure mode with a separate cause, and it means
  PLAN-13's own spec prose is the ONLY thing that will stop a PLAN-07 or PLAN-08 emit while PLAN-13
  runs. Re-check both by hand at every emit round.
  The underlying gate defect is real and worth routing upstream if not already (candidate for
  `lessons-routing`); this watch itself is retired because PLAN-07/08/13 are all terminal.
- The management interface is HTTPS-by-default on a **single** port with no `ssl-port` and no
  `insecure-requests` key, and `quarkus.tls.plain-management` is the named key-less bucket that is
  the *only* sanctioned plain-HTTP opt-out. PLAN-01's probe and PLAN-02's context path must both
  work in either scheme. ADR-0025 forbids adding raw `quarkus.management.ssl.*` policy keys.
- `start-integration-container.sh` derives each instance's readiness probe URL from the
  `de.cuioss.sheriff.management-scheme` compose **label** plus the host port published against
  container port 9000, deliberately restating no service name or port. PLAN-01 and PLAN-02 must not
  break that indirection — the label is the single source of truth for the scheme.
- ADR-0025 boundary rule: gateway.yaml names TLS **policy** in neutral terms; **ports and trust
  material stay deployment-supplied**, and a `management.port` written into gateway.yaml is refused
  at boot. Both hostname-verification knobs (PLAN-03, PLAN-04) are policy and therefore belong in
  gateway.yaml, not in `application.properties`. Re-check at each landing.
- `deployment/compose-sample/` is a shipped operator-facing example (its own `docker-compose.yml`,
  `gateway.yaml`, realm and `wait-for-ready.sh`). The operator asked for examples to be updated;
  every plan here should check whether the sample needs the same change as the integration stack.