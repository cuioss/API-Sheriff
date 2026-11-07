# Close-out state — api-sheriff-0-2-0

**The mechanism lives in the `epic-closeout` skill** (`.claude/skills/epic-closeout/SKILL.md`). Run
it with this epic's slug; it disposes and never closes.

**This document is state only** — what *this* epic has pending, never how any of it is done. Do not
reintroduce mechanism here; the predecessor let it drift into two copies once and it was promoted
out precisely so a closed-and-archived tree would not own it.

> **Created 2026-08-08 by the `api-sheriff-roadmap` close-out (step 7).** That epic is dispositioned;
> whether it is closed is a separate operator decision.

## Bundle round

- **Round numbering starts at 1 in this epic.** Do NOT continue the predecessor's sequence — it
  reached round 8 and its carriers are named for it.
- `bundle-handoff-index.md` and `archive/bundle-handoffs/` were implanted 2026-08-08 and carry the
  two rules learned expensively next door: **the index is the durable dedup base, kept forever**, and
  **rows flip `UNSENT` → delivered in the same action as the send**.
- **Round 1 already carries three rows, all UNSENT**, handed over because the predecessor's round 8
  had shipped and it has no round 9:
  1. `tools-integration-ci` — the main-branch `deploy-snapshot` check has no sanctioned execution
     path. **This was the ledger sweep's one genuine miss across eight rounds** — it had never
     reached the predecessor's index at all.
  2. `plan-marshall` — an agent's report that it fixed a config file is not evidence the file changed.
  3. `manage-lessons` / `lessons-capture` — a lesson written in an unparseable header format that
     `list` reported with empty fields and `remove` could not find.
- **Read the row count from `bundle-handoff-index.md`, never from here.** A second copy of a count is
  a second thing to go stale — the predecessor's document said 26 while its index carried 27.

## Ledger sweep candidates

Nothing accumulated yet. When sweeping, note that the predecessor's dominant finding was a **class**,
not a list: *a mechanism reporting a clean result it never computed* — nine instances in one round,
across search, gates, assessments and the lessons corpus itself. **Prefer one named class in the
carrier over N local-looking rows.**

## Lessons

**Corpus is at zero as of 2026-08-08**, drained by the predecessor's close-out (25 consumed). The
corpus is **global to the repository**, so this epic drains whatever accumulates from here.

**The consume convention is identical in every epic and must stay so**, or which lessons keep an
audit record becomes arbitrary: archive to `archive/lessons-consumed/{lesson-id}.md`,
**persist-then-remove** (destination write → archive copy → `manage-lessons remove`), and **archive
discards too, with their rationale**. Twelve of the predecessor's lessons were dispositioned into
`PLAN-V02-17-lessons-into-source.md` in this epic — that plan is the open end of that disposition.

## Defects and watches inherited

- **(6)**, **(7)**, **(8)** — re-homed from the predecessor on 2026-08-04 and **verified present in
  this tree** by the 2026-08-08 audit.
- **Open Defect (53)** → `PLAN-V02-05` deliverable 5 (forward-all / `not_modified` asymmetry).
- **Open Defect (54)** → `PLAN-V02-16` (image metadata fidelity).
- **Five standing rules** inherited into the anchor's `STANDING RULES INHERITED` block.
- **The 0.1.x pre-flight knowledge** is in the anchor's `HARVESTED PRE-FLIGHT KNOWLEDGE` block. **Read
  it before the 0.2.0 cut** — it is the only copy, and it includes the one still-owed item: reading
  the pinned reusable workflow's checkout depth, which is what actually settles whether the release
  guard works.

## Memory

**21 files** at 2026-08-08 after the predecessor's consolidation. Re-read each against ground truth
rather than trusting its summary line — that pass found a memory whose *index line* still carried a
prescription the *body* had already refuted.

## Pending state — refreshed 2026-08-09 (4 of 17 plans shipped)

**Nothing here is close-out-ready.** The epic is mid-flight; this section is the running list of what
close-out will have to dispose when the time comes.

- **Open Defects with homes** — (1) → V02-05, (10) → **successor epic, becomes actionable at the 1.0
  cut and 0.2.0 CANNOT close it**, (12) → V02-01 (conditional), (13) → folded into V02-09, (14) →
  V02-11. **(2), (3), (4), (6), (7), (8), (9), (11) still have no owning plan** and are the
  close-out's largest disposition batch.
- **(8) needs an OPERATOR live-gate check**, not plan work, and has needed one since 2026-08-04.
- **A `/marshall-steward` run is owed by two independent routes** — Watch (31)'s config pairing, and
  the stale architecture-inventory description left by V02-03 D3. Neither is fixable by any plan or
  by the orchestrator.
- **Bundle round 9 is UNSENT** — 14 items in `bundle-handoff-index.md`. Flip to delivered in the same
  action as the send.
- **The post-merge `deploy-snapshot` axis is OWED for four merge commits** — `89a3cfe`, `e343404`,
  `aeb80c5`, `95dd566`. Unreachable through the CI abstraction; re-verified by execution 2026-08-09.
  It will still be owed at close-out unless the tooling changes.
- **`cuioss/TokenSheriff#641` is open upstream** and its resolution would materially shrink V02-09.
  Check it at that plan's outline, **against the resolved jar rather than the issue thread**.
- **The release dry-run brief is still a direct-session item, not a queue item** — see epic.md
  § Deferred Item. Its hard gate has opened; the brief itself is unstarted.

## Close-out disposition — 2026-11-07

Run by the `epic-closeout` skill, operator-approved (full close-out, no close/archive). Re-verified at
`main` `7f5c61af`. SUCC = `api-sheriff-0-3-0/epic.md`; line numbers refer to this epic's `epic.md` after
the 2026-11-07 cleanup relocation. Re-homed text in SUCC names its 0.2.0 source; this table is the
source-side record.

| Entry | Disposition | Evidence / lands at |
|---|---|---|
| **OD 10** — `doc/plan/09-release-readiness.adoc` actionable at the 1.0 cut (L529) | **closed** (premise retired by #368; a pointer Watch "THE 1.0 CUT IS AN OPEN MILESTONE" is carried) | The premise is stale: the file no longer exists. #368 (`4228d42f`, PLAN-V02-18, operator-instructed reversal of V02-03's ruling) deleted `doc/plan/` and moved the one fact into `CLAUDE.md:152` and `AGENTS.md:86` ("When these rules end" — the 1.0 cut of the `de.cuioss.sheriff.gateway` line). `ls doc/plan/` → no such directory. Verbatim copy archived at EPIC `archive/doc-plan-09-release-readiness.adoc`. landings/PLAN-V02-18.md:20-21. The L529 text ("the file is not superseded … must survive") was never updated after #368. |
| **OD 4** — test-corpus integrity backlog "43 files / 140 marker occurrences" (L545) | **re-home** (with corrected count) | SUCC (own entry).  |
| Two advisory nits left by #566 (L559) | **re-home** → merge into SUCC "wording residue" (W-A) | SUCC Watch "WORDING RESIDUE ON `main`".  |
| `npm run format:check` fails in demo-client (L563) | **re-home** | SUCC (own entry).  |
| `marshal.json` stale against the plugin (L568) | **keep until cut** (merge with L793, L856) | `.plan/marshal.json:389` `provisioned_version: 0.1.1986`; plugin cache has `0.1.2036`. Not yet run. EPIC resume_anchor NEXT ACTION 2 places it before the cut. Afterwards re-check `build.map`'s four hand-added entries. |
| #324 open — declared-limit inventory partitions files, not keys (L572) | **re-home** | SUCC (own entry).  |
| Integration Tests main push red at `48715de4` — Broken pipe in `generate-mtls-certificates` (L573) | **re-homed** into the flake-family Watch (operator ruling 2026-11-05 keeps it a Watch) | SUCC Watch "EDGE / HTTP/2 / IT FLAKE FAMILY".  |
| CodeRabbit merge-risk text on #544 (L574) | **closed** | The lead was real and fixed: PLAN-V02-52 D5 (#554, `48715de4`) corrected ADR-0008's upstream-timeout sentence (landings/PLAN-V02-52.md:22-29); benchmark half closed earlier (run `41091771555` success). **Unowned remainder (not on any Watch):** V02-52's follow-ups — three cut wording leads (ADR-0070, `benchmarks/README.adoc`, compose-sample `.env`), the unchecked BFF session-sweep 60 s statement, the unbound idempotent-method list, "whole dispatch" timeout wording in four tests and the `DispatchStage` Javadoc (landings/PLAN-V02-52.md:60-66). Not checked against #575/#576. |
| APISIX `bearer` benchmark lane has no valid side (L578) | **kept until the cut**, then frozen (documented in `benchmarks/README.adoc`) | `benchmarks/README.adoc:46, :326, :591` document it. Matters only if a gateway-vs-APISIX comparison goes into the release notes; decide at the cut, then frozen (repo-documented). |
| Threat alert ECS shape not schema-validated (L581) | **re-home** | SUCC (own entry).  |
| `Http2StreamAbuseIT.declaredBodyReachesTheUpstreamWithItsLength` failed once (L586) | **re-home** into **W-B** | SUCC Watch "EDGE / HTTP/2 / IT FLAKE FAMILY".  |
| Three guard readers have untested refusal branches (L594) | **frozen** (fails loud by construction; informational) | SUCC (own entry). Informational, fails loud by construction; sizes now 1,600 / 2,050 / 845 lines. Rule (widen the allowlist with a specimen, never loosen the reader) is lesson `2026-10-30-05-001`, not in repo docs. |
| `Http2StreamAbuseIT` rapid-reset `PROTOCOL_ERROR` cause inferred (L600) | **re-home** into **W-B** | SUCC Watch "EDGE / HTTP/2 / IT FLAKE FAMILY".  |
| Final-tree IT suite not run locally for #532 (L608) | **frozen** | Informational residual of a shipped plan; CI integration lane green then and since (main `793612f6`, PRs #566/#573/#575). No landing reports a local `-Pintegration-tests` green run, so the literal retire trigger is unobserved — nothing to act on. |
| `gw-02` PARTIAL — upstream reset pinned against HTTP/1.1 only (L611) | **re-home** | SUCC (own entry).  |
| General-admission negative control cannot wait for gateway-side admission (L613) | **re-home** into **W-B** | SUCC Watch "EDGE / HTTP/2 / IT FLAKE FAMILY".  |
| `PLAN-V02-49` residue (L618) | **split**: (1) **re-home** → **W-C**; (2) **re-home** → **W-B**; (3), (4) **closed** | SUCC Watch "EDGE / HTTP/2 / IT FLAKE FAMILY". (3) worktree removed 2026-11-05; (4) done by #566 D8 (landings/PLAN-V02-56.md:25). (1) JaCoCo comparison pre-dates restored tests; `pom.xml:271, :282` still `<append>true</append>`. (2) `GatewayEdgeEarlyResponseResetTest` one failure under load ~30. |
| Flaky `GatewayEdgeRouteTest$RelayStartFailure` — watch 1 more run (L626) | **closed** | Retire condition ("next green `main` run after a load-sensitive CI job") met: main push Maven Build `42232027584` and Integration Tests `42232027775` at `793612f6` succeeded (landings/PLAN-V02-56.md:47-48); Maven Build green on PRs #573 (`42293405102`), #575 (`42377132308`), #576. No recurrence in any landing since #532. Its "other open flakes" tail is carried by **W-B**. |
| Per-route upstream timeouts parse but do not act — kidicap PLAN-29 (L636) | **re-home: kidicap owns it** | Still inert at HEAD: `endpoint.schema.json:259-260`, `UpstreamConfig.java:52-53`, no runtime reader. Kidicap tracks it: KIDICAP `epic.md:252-256` (Open Defect "Four upstream keys parse and act on nothing"), `plans/PLAN-29-upstream-inert-keys.md:32` (DECIDED 2026-10-26: remove all four keys), `:110-117` and `:188-194` (relation to V02-45's fixed 25 s deadline, "this plan owns the statement"), `queue/PLAN-29.json` status `staged`. Nothing to write to SUCC. |
| First real release after #509 — not yet happened (L648) | **keep until cut** | Latest tag `0.2.4`; `.github/project.yml:5` `current-version: 0.2.4`, `next-version: 0.2.5-SNAPSHOT`; `deployment/compose-sample/.env:23` still pins `ghcr.io/cuioss/api-sheriff:0.2.4`. The checklist (candidate→sign→verify→promote; both platform digests; container-image.adoc verification; `leg-*` tags not counted; `cleanup-dry-run` skipped; `.env` pin bump; `check-bff-login.sh` incl. the `/auth`-fronted variant) runs at the cut. Close on a passing real release; any failed item is re-homed. |
| HTTP/1.1 drain 502 flake in the framing corpus (+ `Http2EarlyResponseResetIT`) (L670) | **re-home** into **W-B** + **operator hand-off for kidicap** | SUCC Watch "EDGE / HTTP/2 / IT FLAKE FAMILY". KIDICAP `epic.md:243-246` says the flake is "tracked in ONE place: `api-sheriff-0-2-0` epic.md, Watch 'HTTP/1.1 DRAIN 502 FLAKE IN THE FRAMING CORPUS' … Report new sightings there". Once 0.2.0 closes that pointer dangles. No recurrence recorded since 2026-10-22. |
| Test-cycle rework residue (#424) (L697) | **closed** (remainder → **W-C**) | SUCC Watch "COVERAGE MEASUREMENT TRAP". #422 closed (done by #553, `267a285d`); #423 closed 2026-11-03 (#558, `d70d2f16`); anchor-overlap operand orders pinned by V02-56 D6 (`AnchorRulesTest`, landings/PLAN-V02-56.md:24); commit-message wording is history. Only the JaCoCo `<append>` trap remains (`pom.xml:271, :282`). |
| Accepted BFF residuals, carry to 0.3.0 (L707) | **re-home** (2 of 3); RFC 7009 logout revocation → **kidicap owns** | Documented at `ADR-0058:235` and `doc/security-threat-model.adoc:2974` (refresh token discarded, not revoked) and `:2705` ("Not delivered: token revocation at logout"). Logout revocation is KIDICAP `plans/PLAN-26-logout-truth-up.md:103` (D2 "Implement RFC 7009 refresh-token revocation on logout — DECIDED 2026-10-26"), `queue/PLAN-26.json` staged. No kidicap plan covers the nonce-retry gaps or the refused-response discard. |
| Declined refactor from #387 (L712) | **frozen** | Repo-recorded: ADR-0061 `=== Risks` (:127-130) "The NOT CHECKED catalogue is maintained by hand…". Trigger-based, no owner. |
| Post-FAPI follow-ups left by PLAN-V02-08 (L718) | **closed** (remainder → **W-A**) | SUCC Watch "WORDING RESIDUE ON `main`". Folded items all discharged or owned: same-key refusal + `cnf` size shipped in #527 (landings/PLAN-V02-20.md:22); canonical-form check on `oidc.login.path`/`oidc.user_info.path` exists (`ConfigValidator.java:163-165`, `ReservedPathRules`); the two conservative answers decided by V02-56 D3 (ADR-0058 decisions 11 and 19); login rate limit → KIDICAP `plans/PLAN-21-login-flood-pending-records.md:36,59` (staged, held); refresh/nonce → L707. No conformance-suite run: documented at `doc/fapi_status.adoc:25, :315` (frozen). **Remainder:** the "three imprecise executable texts" — two still verbatim: `ClientJwksEndpoint.java:112` ("carries a private key member"), `PushedAuthorizationRequests.java:197` ("for the rest of the process"); the third (`PushedAuthorizationRequests.java:59-60`) now reads "Every failure refuses the login, the widening or the step-up re-drive with 502", likely accurate after ADR-0058 decision 11. |
| Inherited tooling and project-config constraints (31)(22)(25)(30)(16+20) (L730) | **re-home** | SUCC (own entry).  |
| STANDING CLAUSES, still binding (L774) | **re-home** → **W-D** (standing rules block) | SUCC § Standing Rules → From `api-sheriff-0-2-0`.  |
| A capability present in a dependency is not a capability of the product (L780) | **re-home** → **W-D** | SUCC § Standing Rules → From `api-sheriff-0-2-0`.  |
| In-body `PLAN-NN` references not rewritten (L787) | **frozen** | Specific to 0.2.0 specs; SUCC already carries the same rule for its own specs at SUCC `epic.md:48-54`. |
| A spec's asserted surface absence can be overturned by its own outline (L791) | **re-home** → **W-D** | SUCC § Standing Rules → From `api-sheriff-0-2-0`.  |
| `/marshall-steward` run owed by several routes (L793) | **keep until cut** (merge with L568, L856) | Still owed (`marshal.json:389` 0.1.1986). |
| A spec-asserted count is a claim (L800) | **re-home** → **W-D** | SUCC § Standing Rules → From `api-sheriff-0-2-0`.  |
| Every spec sentence asserting repo state is a timestamped measurement (L808) | **re-home** → **W-D** | SUCC § Standing Rules → From `api-sheriff-0-2-0`.  |
| An Expected Surface must be checked for sufficiency (L825) | **re-home** → **W-D** | SUCC § Standing Rules → From `api-sheriff-0-2-0`.  |
| A test lane nobody can run is unmeasured (L838) | **re-home** → **W-D** | SUCC § Standing Rules → From `api-sheriff-0-2-0`.  |
| Asserted absences need a control query (L843) | **re-home** → **W-D** | SUCC § Standing Rules → From `api-sheriff-0-2-0`.  |
| Contract tests run only when a build runs (L851) | **re-home** | SUCC (own entry).  |
| Architecture descriptors still name `doc/plan/` (L856) | **keep until cut** (steward) | Still stale: `.plan/project-architecture/_project.json:59` and `documentation/enriched.json:16` say "…alongside the quality report and the remaining implementation plans under doc/plan/" — both `doc/plan/` (#368) and the quality report (#576) are gone. Steward-regenerated, not hand-editable. Re-home if the steward run leaves it. |
| Tooling gaps reported by PLAN-V02-19, owned upstream (L861) | **frozen** | Upstream-owned unverified leads; (a) is in memory `ci-verify-policy-producer-rejected` (route via finalize-feedback); the `NO_PLAN` sentinel (`.plan/local/plans/NO_PLAN` still exists) is already in SUCC. |
| GW-07 request-body PARTIAL — operator-named residual (L874) | **re-homed** (operator-named residual) | SUCC (own entry). `doc/security-threat-model.adoc:4018` `<<gw-07>>` PARTIAL; the `dispatchGrpc` hand-over of the five-minute stream-idle bound is still unobserved by a test. The `timeoutMillis()` sub-item was closed by #549. |
| ADR-0066 has no test for two abortive-close triggers (L881) | **frozen** | Operator ruled "report, no test"; repo-recorded at ADR-0066:331-332 ("not pinned by a test for the request-body idle timeout or the request-body deadline") and GW-07. |
| Release skill "An ADR is immutable" vs in-place ADR rewrites (L885) | **keep until cut** | Still at `.claude/skills/release/SKILL.md:1360-1361`, inside the version-sweep NEVER-touch list; ADRs keep being rewritten in place (#544, #554, #576). The cut's sweep is the point where the reading (scoped to version stamps) matters; settle the wording then, or re-home a one-line skill fix. |
| ADR term-list residue in records V02-04 rewrote (L890) | **closed** | PLAN-V02-48 D10 shipped (#544): landings/PLAN-V02-48.md:32 "'outbound TLS' aligned to 'egress TLS' in `doc/configuration.adoc`, `doc/security-threat-model.adoc` and two test sources". `git grep -i 'outbound TLS' -- doc api-sheriff/src/test` → only `doc/development/adr-writing.adoc:370`, the term table's "avoid" column. |
| **Release Pre-Flight Knowledge for the 0.2.0 Cut** (L922-956) | **keep until cut, then harvest into SUCC** (as "Release Pre-Flight Knowledge for the next cut"), updated with what the cut itself teaches | It says "the only place this knowledge exists". Its claims still match HEAD (dry-run input, guard reason line in `doc/development/release-process.adoc`, `--delete-branch` caution). The 0-2-0 `epic-closeout.md:56-59` "one still-owed item" (checkout depth of the guard) is settled by L951-952 (`fetch-depth: 0`, `fetch-tags: true`). **Naming caveat:** tags `0.2.0`–`0.2.4` already exist and `project.yml` says `next-version: 0.2.5-SNAPSHOT`, so "the 0.2.0 cut" will publish some other number — make sure the close-out records which version it actually was. |
| **Standing Rules Inherited from the `api-sheriff-roadmap` Close-Out** (L958-987) | **harvest into SUCC** (with W-D) | The cited verbatim copies at `.plan/local/archived-orchestrators/api-sheriff-roadmap/archive/lessons-consumed/` **no longer exist** (no `lessons-consumed` dir anywhere under `.plan`), so this section is now the only copy of the five rules. Carry the text verbatim (incl. the 2026-11-05 recurrence note on rule 3). |
| **Deferred Item — the release dry run is NOT a queue item** (L989-1010) | **keep until cut, then frozen** | The mechanism largely exists now: `release.yml` has a `dry_run` dispatch input (#504 `8e218103`, #509 `00293178`), documented in `.claude/skills/release/SKILL.md:38-47` and `doc/development/release-process.adoc:314` § "Proving the image lane with a dry run". Whether the brief's "`/release dry` mode on the skill" is fully satisfied was not judged. NEXT ACTION 3 still runs a dry run before the cut. After the cut: frozen with the brief archived. |
| **Sequencing Constraints** (L481-517) | **frozen** | All plans shipped; every chain is discharged or moot. One generalisable line could go into W-D: concurrent Docker IT suites contend for local CPU, so prefer a doc-only or build-light plan for extra slots (kidicap carries a sibling rule at its Watch on native-IT wall clock). |

**Kept until the cut → moved to `api-sheriff-0-3-0` at close (2026-11-07).** The operator closed this epic
before its next release ran, so the six kept items (the `/marshall-steward` run and the stale architecture
descriptors, FIRST REAL RELEASE AFTER #509, the APISIX bearer lane, the release skill's "an ADR is immutable"
line), the Release Pre-Flight Knowledge and the dry-run Deferred Item were carried verbatim into
`api-sheriff-0-3-0/epic.md` (Watch "RELEASE-CUT CARRY FROM `api-sheriff-0-2-0`", § Release Pre-Flight Knowledge
for the Next Cut). That epic owns the next release, `0.2.5`.

**Operator hand-off — kidicap is read-only to this epic.** For `kidicap-gateway-requirements` `epic.md`
(the framing-corpus flake entry, ~:243-246): the flake is said to be tracked in ONE place, the
`api-sheriff-0-2-0` Watch "HTTP/1.1 DRAIN 502 FLAKE IN THE FRAMING CORPUS". That tracker moved on
2026-11-07 to `api-sheriff-0-3-0` `epic.md`, Watch "EDGE / HTTP/2 / IT FLAKE FAMILY". Repoint the entry
and report new sightings there. (PLAN-29, PLAN-26 and PLAN-21 already carry their items; no hand-off.)
