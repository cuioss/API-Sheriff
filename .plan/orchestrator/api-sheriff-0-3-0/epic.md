# Epic: API Sheriff 0.3.0 — New Capabilities

slug: api-sheriff-0-3-0

> Ledger document for one epic under `.plan/orchestrator/api-sheriff-0-3-0/`. The layout and
> authority contract live in the central standard — see
> `persona-plan-orchestrator/standards/orchestration-model.md`. The machine authority is the ledger
> files — `status.json` (header), `queue/{PLAN-ID}.json` (rows) and `resume_anchor.md`; any statement
> here that conflicts with them is stale prose. This document is hand-written narrative only and
> describes the epic's **current state** — no history is retained.

## Vision

Add the capabilities API Sheriff does not yet have: an active **deception layer** that wastes an
attacker's time and yields high-confidence signal, a first-class **API inventory** the management
plane can serve in the formats the ecosystem consumes, and a **Helm chart** verified against a real
cluster rather than by inspection.

**Scope**: net-new capability only. Cleanup, consolidation and hardening of what already ships
belongs to `api-sheriff-0-2-0`, which this epic depends on.

## START HERE

Generated — see `queue-view.md` (rendered from the ledger by `orchestrator regenerate-view`; never
hand-edited).

## Ordered Queue

The table itself is generated into `queue-view.md`; only the hand-written annotation zone lives here.

### Queue annotations

- **PLAN-V03-01** — double-gated: do not emit until api-sheriff-0-2-0 ships PLAN-V02-06 (and V02-07),
  then re-ground a SECOND time against what actually landed (see § Cross-Epic Dependency).
- **PLAN-V03-02** — merged spec (V03-03 folded in 2026-09-24); 8 deliverables, two PRs. Launch gate is
  the epic-level double-gate below, not its own dependencies.
- **PLAN-V03-05** — staged 2026-11-07 by the `api-sheriff-0-2-0` close-out: ten consumed lessons whose
  rule belongs in repository documentation. Docs-only (no Maven gate); independent of every other row.
- **PLAN-V03-04** — DO NOT LAUNCH: plan-marshall's execution-manifest classifier still has no
  helm/Chart.yaml glob (re-verified 2026-09-24 against `_manifest_core.py` @ 9913740d9).

## Workstreams

| Workstream | Title | Plans |
|---|---|---|
| WS-01 | Deception and Detection | PLAN-V03-01 |
| WS-02 | Management-Plane Introspection | PLAN-V03-02 (PLAN-V03-03 folded in, superseded) |
| WS-03 | Deployment and Distribution | PLAN-V03-04 |
| WS-04 | Carried Engineering Hygiene | PLAN-V03-05 |

## Renumbering Map — `api-sheriff-next` → this epic

The `api-sheriff-next` backlog was split by target version on **2026-08-04** and retired. Plan specs
were renumbered; **their in-body `PLAN-NN` references were deliberately NOT rewritten**, because many
point at `api-sheriff-roadmap` plans that keep their own numbers. Resolve any in-body reference
through this map first; if the number is not listed here, it belongs to `api-sheriff-roadmap` or to
`api-sheriff-0-2-0` and is unchanged.

| Was | Now | Plan |
|---|---|---|
| PLAN-20 | **PLAN-V03-01** | Honeypot / deception layer |
| PLAN-21 | **PLAN-V03-02** | API inventory endpoint |
| PLAN-22 | **PLAN-V03-03** | Inventory interop formats |
| PLAN-41 | **PLAN-V03-04** | Helm chart, real-cluster verified |

The eleven cleanup/consolidation/hardening plans moved to `api-sheriff-0-2-0` — see that epic's map.

## Cross-Epic Dependency

**`PLAN-V03-01` (honeypot) has a hard dependency on `api-sheriff-0-2-0` `PLAN-V02-06`**, which
builds the per-client detection substrate the deception layer consumes. That ordering is a recorded
operator decision, taken with its cost stated: the substrate ships a release ahead of its consumer.

Do not start `PLAN-V03-01` until 0.2.0 has shipped the substrate, and **re-ground it against what
actually landed** rather than against `PLAN-V02-06`'s staged spec — a staged spec is not evidence of
what a plan delivered.

## Decisions

- **2026-08-04 — the backlog is split by target version.** `api-sheriff-next` was a single
  undifferentiated backlog; it is replaced by `api-sheriff-0-2-0` and this epic, and removed.
- **2026-08-04 — the deception layer stays a separate plan from the substrate it consumes.** It
  carries its own legal and operational review, which a merge into the substrate plan would bury.
- **2026-08-04 — workstreams re-cut from WS-01.** The old WS-05..WS-09 split across both new epics,
  so carrying the numbers would have left gaps in each and put WS-05 in both.
- **2026-09-24 — PLAN-V03-03 folded INTO PLAN-V03-02** (cleanup A5), executing the 2026-07-27
  operator decision once both specs were re-grounded. Merged spec carries 8 deliverables — unsplit by
  recorded rationale (operator merge decision, two-PR delivery, 12-deliverable ceiling). V03-03's
  spec is kept as the audit record; its row is `superseded`.
- **2026-11-07 — `api-sheriff-0-2-0` close-out re-homes its open work here.** One Open Defect, the
  Watches and the standing rules arrive in the sections below (each entry names its 0.2.0 source), and
  WS-04 is opened to carry PLAN-V03-05, the docs plan for ten consumed lessons. 0.2.0's release
  pre-flight knowledge was carried too when the operator closed 0.2.0 before its next release (same day).
- **2026-11-07 — every plan spec carries a `## Security Findings` section** (operator rule of 2026-10-22,
  carried from `api-sheriff-0-2-0`): findings a plan uncovers but does not fix are recorded there by
  reference to the git-ignored security notes, never described in tracked text. PLAN-V03-01, -02 and -04
  predate the carry and gain the section at their next re-grounding.

## Open Defects

Inherited from `api-sheriff-0-2-0` at its close-out (2026-11-07). Numbering restarts here; the source
number is named in each entry.

1. **LOW, not release-gating — integration-lane assertion-strength work list** (was 0.2.0 Open Defect 4,
   the PLAN-43 residual carried since 2026-08-04). `doc/development/test-corpus-integrity.adoc`
   § "Open Strengthening Verdicts in the Integration Lane" (anchor `open-work-list`) lists 8 rows /
   9 test methods whose assertions cannot fail for the reason they claim. The list is the authority;
   read each test before acting. The old "43 files / 140 occurrences" figure was retired by #284.

## Watches

Inherited from `api-sheriff-0-2-0` at its close-out (2026-11-07, re-verified at `main` `7f5c61af`).
Each entry names its original date and source; the full history stays in the 0.2.0 tree
(`epic.md`, `settled.md`, `landings/`).

- **THE 1.0 CUT IS AN OPEN MILESTONE** (was 0.2.0 Open Defect 10, opened 2026-08-09 by `PLAN-V02-03`
  D3; closed there 2026-11-07 because #368 retired `doc/plan/`). The 1.0 cut — start of API stability
  and the end of the Pre-1.0 Rules — is recorded in `CLAUDE.md` § Pre-1.0 Rules "When these rules end"
  and in `AGENTS.md`. The retired release-readiness plan is archived in the 0.2.0 tree as
  `archive/doc-plan-09-release-readiness.adoc`. Not due in 0.3.0 unless the operator says so.
- **EDGE / HTTP/2 / IT FLAKE FAMILY — A RECURRENCE IS A SUSPECTED PRODUCTION DEFECT** (consolidated
  from six 0.2.0 Watches, 2026-10-13 .. 2026-11-01). Single-sighting failures with no root cause, all
  green since: `GatewayEdgeFramingCorpusTest` HTTP/1.1 drain 502 (main push `09edaea9`, plus kidicap
  PLAN-24/-28 sightings); `Http2EarlyResponseResetIT` (merge_group `39812067667`, 390/400 NO_ERROR);
  `Http2StreamAbuseIT` rapid-reset `PROTOCOL_ERROR` (fix `3cc695db` inferred, not reproduced) and
  `declaredBodyReachesTheUpstreamWithItsLength` (run `40968785321`); `GatewayEdgeEarlyResponseResetTest`
  once under host load ~30; the general-admission negative control in `WebSocketProxyIT` (cannot wait
  for gateway-side admission, accepted on #534); the `generate-mtls-certificates` "Broken pipe" on main
  push `41340502392` (operator ruling 2026-11-05: a second failure makes it a defect). On any
  recurrence do not re-run to green: reproduce under `-XX:ActiveProcessorCount=1` with repeated runs
  and stage a fix plan; the owner is the next plan touching `edge/`. **This is the only cross-epic
  tracker for the framing-corpus flake; kidicap's Open Defect points at the 0.2.0 Watch and must be
  repointed here by its own orchestrator.**
- **GW-02 IS PARTIAL: THE UPSTREAM RESET IS PINNED AGAINST AN HTTP/1.1 UPSTREAM ONLY** (2026-10-29,
  `PLAN-V02-50` landing). No test resets an HTTP/2 upstream; `doc/security-threat-model.adoc` next
  steps name the open clause. Candidate for a 0.3.0 hardening plan.
- **GW-07 REQUEST-BODY STATUS STAYS PARTIAL — OPERATOR-NAMED RESIDUAL** (operator ruling 2026-10-25,
  `PLAN-V02-27` landing). No test observes `TerminalDispatch#dispatchGrpc` handing its fixed five-minute
  stream-idle bound to the overload the tests drive; the `gw-07-upstream-ports` matrix row reads COVERED
  while naming an effect no test observes. Do not close it by adding a test inside an unrelated plan and
  do not move the threat-model status; it needs a dedicated hardening plan or an operator ruling.
- **#324 OPEN: THE DECLARED-LIMIT INVENTORY GUARD PARTITIONS FILES, NOT KEYS** (2026-11-05,
  `PLAN-V02-53` landing). `declaresLimitShapedKey` works at file level, so a new limit key in a covered
  file needs no Coverage Matrix row. Key-level D2 was cut under the split guard (~1,000 (file, key) rows
  over 22 descriptors; YAML anchors resolve one literal to several paths); the reason is on #324.
- **THE THREAT ALERT'S ECS SHAPE IS ASSERTED BY FIELD NAME ONLY** (2026-10-30, `PLAN-V02-07` landing).
  ADR-0078 pins `ThreatAlertDocument.ECS_VERSION` at 8.17.0 and tests assert the field set, but nothing
  validates the document against an ECS schema or a real SIEM consumer. No owner: if an operator reports
  a SIEM rejecting it, fix the field and add the schema check then.
- **ACCEPTED BFF RESIDUALS** (`PLAN-V02-01` D7 / `PLAN-V02-08`, re-confirmed 2026-11-06 by the
  `PLAN-V02-56` D3 report). (1) A refresh token in a refused token response is discarded, not revoked
  (ADR-0058, threat model BFF-14 residual). (2) The engine's nonce-retry gaps (ADR-0058 decision 15).
  Both are publicly documented. Logout without RFC 7009 revocation is owned by kidicap PLAN-26 D2, not
  here. Candidates for a 0.3.0 BFF plan, not blockers.
- **`npm run format:check` IN `demo-client` FAILS ON `main` FOR ~10 FILES** (2026-11-06, the #572
  session). No Prettier config, so defaults disagree with the committed layout; no CI lane or Maven
  profile runs the script (only `lint:strict` is wired). Fold into the next plan touching `demo-client`:
  add a Prettier config matching the committed style, or drop the script.
- **WORDING RESIDUE ON `main` — fold into the next plan touching each file** (2026-11-06 `PLAN-V02-56`
  landing; 2026-10-03 `PLAN-V02-08`; 2026-11-01 `PLAN-V02-52`). `IdpFrontingCanonicalPathTest` Javadoc
  (:286) says "two" deliberate cuts where `DeliberateCuts` has three; its comment at :175 says
  `check-bff-login.sh` probes "the first and the last" entries where it probes the second-to-last.
  `ClientJwksEndpoint.java:112` ("carries a private key member") and the DEBUG line in
  `PushedAuthorizationRequests.java:197` ("for the rest of the process") are imprecise. `PLAN-V02-52`'s
  unowned follow-ups (ADR-0070, `benchmarks/README.adoc`, compose-sample `.env` wording, the BFF
  session-sweep 60 s statement, the idempotent-method list) were not re-checked after #575/#576.
- **COVERAGE MEASUREMENT TRAP** (2026-10-10 / 2026-10-31, #424 residue and `PLAN-V02-49`). `pom.xml`
  sets `<append>true</append>` on one `target/jacoco.exec`, so a coverage run without `clean`
  accumulates stale data; measure with `clean verify -Pcoverage -pl api-sheriff -am`. `PLAN-V02-49`'s
  per-class comparison (529 classes, 0 lower) was taken at `35c47791`, before four tests were
  restored, and never repeated. Re-measure from a clean tree if `config/` or `routing/` coverage is
  questioned.
- **CONTRACT TESTS RUN ONLY WHEN A BUILD RUNS** (2026-10-01, `PLAN-V02-19` landing). The
  ADR-ordinal-uniqueness and module-list tests are Maven tests, so a documentation-only change — which
  skips the build by `CLAUDE.md` — can land a duplicate ADR ordinal or a drifted module list, caught
  only at the next build-triggering change. Check ordinals by hand on doc-only PRs that add ADRs.
- **INHERITED TOOLING CONSTRAINTS — upstream-owned, every plan runs under them** (carried from
  `api-sheriff-roadmap` via 0.2.0; re-verified 2026-11-07). (31) HIGH: `q_gate_validation: once` with
  `plan_without_asking: true` in phase-3 and phase-4 lets BLOCKING Q-Gate findings reach task planning
  ungated. (22) HIGH: no plan runs the Docker IT suite pre-push; do not work around it via
  `per_deliverable_build`. (25) documentation has no `module_testing` skills. (30) the deep-lane
  component-assessment sink can be empty, indistinguishable from "matched nothing". (16+20) main-branch
  push runs are not reachable through the CI abstraction; the post-merge check is an
  operator-authorised read and is OWED until done. Owner: `/marshall-steward` / plan-marshall;
  re-check by executing the mechanism, never by reading a version.
- **SECURITY FINDINGS LIVE ONLY IN THE GIT-IGNORED `.plan/local/security_notes_verified.adoc`.** On
  2026-11-07 a session outside this ledger wrote that file as a ground-truth check of the former
  `security_notes.adoc` (now empty), including the two leads 0.2.0 carried by reference (the 2026-10-31
  `plan-v02-49` entry and the 2026-11-07 #573 entry). Carried by reference only: no finding from that file
  enters tracked text, a spec or a PR until the operator has decided how each one is handled.
- **RELEASE-CUT CARRY FROM `api-sheriff-0-2-0`** (2026-11-07). The operator closed 0.2.0 before its next
  release ran, so the items it had kept for that release move here verbatim. **The next release is `0.2.5`**
  (tags 0.2.0–0.2.4 exist; `.github/project.yml` `next-version: 0.2.5-SNAPSHOT`); "the 0.2.0 notes/cut" below
  means that release. Close each once that release passes it; see § Release Pre-Flight Knowledge for the Next Cut.
  - **FIRST REAL RELEASE AFTER #509, NOT YET HAPPENED** (added 2026-10-22, inbox
    `release-lane-hardening-001`). #509 (`00293178`) reordered the image join in
    `.github/workflows/release.yml`: the index is pushed under a candidate tag `leg-<sha>-index`, asserted,
    signed and verified there, and only then promoted to `<version>` and `sha-<sha>` and read back. Dry runs
    39732222258, 39751125940 and 39759377284 passed, but they promote to `dryrun-*-promoted`; no release
    run has used the real tags yet (latest tag still `0.2.4`, linux/amd64 only). The next release is also
    the first two-platform index (`PLAN-V02-47`). Check on that run: (1) candidate, sign and verify steps
    all succeed before `Promote the verified index to its final tags` and its assert step; (2) `<version>`
    and `sha-<sha>` resolve to the same digest, an index of exactly linux/amd64 and linux/arm64, the
    digest that was signed (`docker buildx imagetools inspect ghcr.io/cuioss/api-sheriff:<version>`);
    (3) the verification command in `doc/user/container-image.adoc` verifies the index and both platform
    digests; (4) `leg-<commit>-index` sits next to the two `leg-<commit>-<arch>` tags and is not counted
    by the release skill's "exactly one of each" check; (5) `cleanup-dry-run` is skipped. A failure
    before promotion leaves no release tag (re-run the join alone); a promote or read-back failure can
    leave one; a promoted digest that differs from the signed one is a real defect, so the release is
    treated as partial. Close this watch once one real release passes the checklist. **Also owed by that release
    (from `PLAN-V02-15`, #516, 2026-10-24):** bump the pin in `deployment/compose-sample/.env` (still
    `ghcr.io/cuioss/api-sheriff:0.2.4`, which predates the BFF keys the sample now sets) and run
    `deployment/compose-sample/scripts/check-bff-login.sh` against the published image; until then the default
    sample runs only against a local build through `API_SHERIFF_IMAGE`. CI proves the HTTPS variant's login
    only; the plain-HTTP variant (`start-sample.sh --plain-http`) was proven locally.
  - **`marshal.json` IS STALE AGAINST THE PLUGIN** (added 2026-11-06, `PLAN-V02-56` landing advisory). Recorded 0.1.1986, plugin
    0.1.2036. Operator action: run `/marshall-steward` before the next plan. Re-check `build.map`'s four hand-added entries
    afterwards (CLAUDE.md, `BuildGateCoverageContractTest`).
  - **ARCHITECTURE DESCRIPTORS STILL NAME `doc/plan/`** (added 2026-10-01, `PLAN-V02-18` landing,
    the plan's claim — unverified lead). `.plan/project-architecture/_project.json` and
    `documentation/enriched.json` still describe "remaining implementation plans under doc/plan/";
    the finalize architecture refresh reported no structural change and did not rewrite them. Outside
    the epic tree, so not edited here. Needs a `/marshall-steward` pass; no plan staged.
  - **THE APISIX `bearer` BENCHMARK LANE HAS NO VALID SIDE, SO `run-comparison.sh --target both` STOPS THERE** (added 2026-10-30, `PLAN-V02-48` landing).
    `benchmarks/README.adoc` line 46 says so ("`bearer` has no valid APISIX side for now"), and the comparison is deliberately wired into no workflow. No owner; low. It
    matters only if a gateway-versus-APISIX comparison is wanted for the 0.2.0 notes.
  - **THE RELEASE SKILL'S "AN ADR IS IMMUTABLE" LINE VS THE IN-PLACE ADR REWRITES** (added 2026-10-25, from
    `PLAN-V02-04`'s message). `.claude/skills/release/SKILL.md` lists `doc/adr/**` in the set a release-version sweep
    must not touch, and says an ADR is immutable. `PLAN-V02-04` and `PLAN-V02-48` rewrite records in place. The line
    sits in a list about version stamps, so the orchestrator reads it as scoped to that sweep. Unconfirmed; the plan
    raised it with the operator. `PLAN-V02-48` reports its reading and does not edit the skill.

## Release Pre-Flight Knowledge for the Next Cut

Harvested into `api-sheriff-0-2-0` from `api-sheriff-roadmap` (which cut **both** 0.1.0 and 0.1.1), and carried here verbatim at the 0.2.0 close (2026-11-07). **This is the only place
this knowledge exists.** These are instance-specific cautions; the process itself lives in
`.claude/skills/release/`.

- **Re-run the Trivy gate immediately before the cut.** A green is worth **days, not weeks** — it was
  RED on 2026-08-04 and green on the 5th only because a Dependabot base bump landed between. The DB
  snapshot carries a `NextUpdate` ~24h out: cut that day, or re-run.
- **Rebuild the scan target from the tree under test.** A stale image's green looks **identical** to
  a real one — the 0.1.0 rehearsal's first target predated its code by hours, and at 0.1.1 a
  tag-keyed reuse would have scanned an image built *before* the commit under test. Scan by
  content-derived image ID, as the workflow does.
- **The Trivy gate runs AFTER Maven Central publication.** `publish-image` is `needs: [release]`, so
  jars are irrevocably on Central before the scan or the signature happens. **That ordering is why
  the pre-cut dry run exists, and it is not optional.**
- **Assert GHCR `visibility == 'public'` via `gh api`, never by pulling as an org member.** The 0.1.0
  cut landed on INTERNAL first, which keeps anonymous pulls broken while every member check passes.
  At 0.1.1 the release skill checked **anonymously** throughout (no `ghcr.io` credential) — that is
  the standing method, and authenticating the verification would reintroduce the blind spot.
- **Never pass `--delete-branch` on a queue-gated merge.** It closed PR #167 **unmerged** while
  returning `merged: true`. Verify via the GitHub API and `mergeQueue.entries`, never the tool's
  return.
- **The merge of the version bump IS the release.** `.github/project.yml` `current-version` is the
  trigger; editing that file is by construction a release trigger.
- **The guard is proven in both directions.** It fired correctly at 0.1.1 (run 31157727796) and
  refused correctly at PLAN-51 (run 31256991225). In that refusal both predicates were true
  (`current-version` stayed `0.1.1`, tag `0.1.1` existed), so the job rendering alone cannot tell
  them apart — **but the guard log can**: it reads `unchanged at 0.1.1`, and the guard checks
  "unchanged" before "already tagged", so a guard stuck on `changed` would have logged the tag reason.
  A guard stuck on `unchanged` would log the same line; that direction fails safe. The mechanism is
  settled: the pinned guard job checks out with `fetch-depth: 0` and `fetch-tags: true`.
  `doc/development/release-process.adoc` now rests these claims on the guard's reason line, at the
  v0.42.0 pin (#512). `release-guard-docs.yml` fails a pull request that moves the pin until the section
  is re-checked.

### Deferred Item — the release dry run is NOT a queue item (carried verbatim from `api-sheriff-0-2-0`)

**Operator ruling 2026-08-05**: the release dry-run standardisation runs as a **direct Claude Code
session**, not a plan-marshall plan. The brief is `release-dry-run-standardisation-claude-brief.md`
in the archived 0.2.0 tree (`.plan/archived-orchestrators/api-sheriff-0-2-0/`). A WS-06 workstream and a `PLAN-V02-13` spec were staged and then **removed** when the
ruling landed — do not be surprised by the gap, and **do not re-add them** (the `PLAN-V02-13` number
was subsequently reused for terminal-rejection-contract).

**Its hard gate has opened**, and the thing it gated on moved further than the gate anticipated.
Roadmap PLAN-48 shipped (#171 / `b5ce4b6`) and the release trigger is re-armed — but its D4 did not
ship with it: the release-skill work went to PLAN-50, and PR #179 (`9a8dc3c`) then **rewrote**
`.claude/skills/release/SKILL.md` substantially (a two-path release, Step 1 restructured so the
safety work precedes the merge, Step 3(i) replaced, the frontmatter description rewritten), after
PR #169 had already renumbered its steps. **So the brief's own instruction — re-read `release.yml`
and the skill on `main` and RE-DERIVE rather than patch — is now load-bearing rather than cautious.**
PLAN-50 has since landed, so the Cosign-identity collision that deferred this behind the 0.1.1 cut is
cleared.

**The form decision is taken and argued in the brief**: a dry-run **mode** on the existing `/release`
skill, not a sibling skill, because a dry run is worth exactly its fidelity to the real steps and a
second document drifts — three first-party drift defects in this same lane on one day are the
evidence. Overturnable only on evidence; record the rationale either way.

## Standing Rules

Rules about **how this epic is run**, carried from `api-sheriff-0-2-0` at its close-out (2026-11-07).
Copied verbatim from that epic's Watches and its "Standing Rules Inherited from the
`api-sheriff-roadmap` Close-Out" section; the verbatim lesson copies that section once cited under
`.plan/local/archived-orchestrators/api-sheriff-roadmap/` no longer exist, so this is now the only copy.

### From `api-sheriff-0-2-0`

- **STANDING CLAUSES, still binding**: three-layer docs in the same PR; Sonar zero-findings with red
  a HARD STOP; **named line items survive outlining** (this project has twice had named deliverables
  abstracted away); activate-it-in-an-IT (lesson `2026-07-25-15-001`, which already recurred verbatim
  once); **the plan stops at the merge** and the post-merge check is the orchestrator's; every emit
  carries an explicit `plan_id`; mark a claim OBSERVED only with a read behind it; **a green suite is
  not evidence for a behavioural claim** until its assertions are read.
- **A capability present in a dependency is not a capability of the product.** Earned on the FAPI
  claim: `doc/features-analysis.adoc` asserted a PAR-driven sender-constrained flow because the
  engine shipped `ParClient` and `SenderConstraint` while the gateway wired neither. The
  native-image reflection registration is what made it look evidenced — it lists the dependency's
  whole DTO surface, so grepping for a class name finds a feature that is never invoked. **Settle
  every completion claim at the call site, never at the import.** *Re-confirmed 2026-08-08: the only
  `ParResponse` mentions in the tree are still reflection registrations.*
- **A spec's asserted surface absence can be overturned by its own outline**, so a disjointness check
  against a RUNNING plan reads that plan's **current outline**, never its staged spec.
- **A SPEC-ASSERTED COUNT IS A CLAIM, NOT A MEASUREMENT — and the orchestrator is the author who owes
  the re-derivation.** Earned 2026-08-09 on `PLAN-V02-02` D3: a headline occurrence count propagated
  spec → clarified request → outline unchallenged, arriving at implementation as three
  mutually-corroborating statements of one unverified number. **The contested instance is itself the
  second data point** — the plan measured 20, this epic's own spec table sums to 19, and a
  re-measurement at `e343404` returns 19. Two parties each derived a confident total from the same
  document and disagreed. Re-derive every count in a staged spec from its own breakdown before the
  spec is emitted; the check needs no codebase access.
- **EVERY SPEC SENTENCE ASSERTING CURRENT REPOSITORY STATE IS A MEASUREMENT WITH A TIMESTAMP — the
  consuming phase re-establishes it rather than inheriting it.** Earned 2026-08-09 on `PLAN-V02-17`,
  and it completes a three-instance pattern in four landings, all in specs the orchestrator authored:

  | Plan | The defect | Found by |
  |---|---|---|
  | `V02-02` | an occurrence count **asserted, not derived** | measuring during the run |
  | `V02-16` | an Expected Surface **accurate but insufficient** — named the consumer, not the channel | attempting to implement it |
  | `V02-17` | a premise about current repo state **stale and false** | running the gate |

  The V02-17 case is the sharpest because the orchestrator supplied the reasoning: resolving the
  formatter contradiction it asserted that *"bringing `main` to the fixed point is the durable fix
  nobody has done"*. **A full-reactor gate run produced zero rewrites — the repository is already at
  the fixed point.** The verdict (*revert unrelated churn*) survived; the reason did not, and the
  shipped page carries a better one: the fixed point is a property of the tree at a point in time,
  not a guarantee. **The common shape is structural, not carelessness** — a spec asserts facts at
  authoring time and executes later, and nothing in between re-measures unless someone does.
- **AN EXPECTED SURFACE MUST BE CHECKED FOR SUFFICIENCY, NOT ONLY ACCURACY — and this one is the
  orchestrator's own.** Earned 2026-08-09 on `PLAN-V02-16`. Its spec named `Dockerfile.native` +
  `.github/workflows/release.yml` for the revision-label deliverable. Every claim in that surface was
  **accurate**, and the surface was **not implementable**: `release.yml` performs no `docker build`
  and forbids adding one, so the only channel into the image is `integration-tests/docker-compose.yml`'s
  `build.args` — a file the spec never named. **Implementing the literal surface would have shipped an
  empty `revision` label on every published image**, a new falsehood inside the plan whose objective
  was to stop the image lying. At the 2026-08-08 re-grounding the orchestrator read this spec and
  recorded *"every claim holds; no correction is owed"* — verifying the claims made, never asking
  whether the surface reached the deliverable. **The distinction is consumer vs. channel**: naming the
  consumer reads like naming the surface and does not build. Trace the value from producer to
  artifact and name every hop. Sixth instance of the enumerate-every-carrier rule, and the first
  inside a spec the orchestrator wrote itself.
- **A TEST LANE NOBODY CAN RUN IS NOT A PASSING LANE, IT IS AN UNMEASURED ONE.** Earned 2026-08-09:
  the `-Pjfr` lane could never start (root-owned bind mount vs. a uid-1001 container), so its green
  was the **absence of execution**. Repairing it surfaced Open Defect (14) — a fail-open handshake
  rejection — within the same run. Where a lane is broken, prefer fixing it to scoping around it; the
  cost is bounded and the yield is whatever the lane has silently not been checking.
- **ASSERTED ABSENCES NEED A CONTROL QUERY — three known false-negative mechanisms, not one.**
  `architecture search --content` returns a clean `count: 0` over compose/infra YAML and
  `src/main/resources`; `git grep <pattern> origin/main -- 'path/**'` returns a clean zero for every
  query in this tree while the same search without the pathspec returns hits; and — **added
  2026-08-08, found during this epic's own re-grounding** — an **unquoted `--include=*` in `grep -r`
  is shell-expanded into a filename list**, silently dropping real hits. That third one is a
  different mechanism from the other two (shell expansion, not tool behaviour), so knowing the first
  two does not predict it. **Run a control query before trusting any asserted absence.**
- **A local native IT run is not proven to have used the branch's image by its
  `org.opencontainers.image.revision` label** (lesson `2026-09-24-15-001`, consumed 2026-11-07). Local
  builds stamp `revision=dev` (`integration-tests/docker-compose.yml`, `APP_REVISION: ${APP_REVISION:-dev}`),
  so a plan spec that needs that proof either exports `APP_REVISION=$(git rev-parse HEAD)` before the run
  (the pass-through exists since #199; whether the Maven exec step forwards the variable is unverified),
  or names substitute evidence up front: image creation time inside the build window, one active
  worktree, and a branch-only behavioural marker. Never require an exact label match on the `dev` default.

### From the `api-sheriff-roadmap` close-out

1. **`2026-08-02-15-002` — BUILD SUCCESS IS NOT EVIDENCE THAT WORK HAPPENED.** Assert positive
   evidence, never the exit code. This is the single most-recurring shape of the predecessor epic:
   a clean result nobody computed, nine separate instances in one bundle round.
2. **`2026-08-05-05-001` — for a path-triggered check-run, absence of a failure is not a pass.** Only
   presence-and-success **on the commit that changed the file** is.
3. **`2026-08-06-08-002` — a fix to a duplicated value is not done until every carrier is enumerated
   mechanically.** Learned when a Cosign identity change hit two of three carriers; recurred within
   hours of being promoted (a four-carrier config value), and again at PLAN-50 where a spec's own
   carrier list was stale and a published SVG was missed. **Enumerate, never recall.** *This epic's
   own re-grounding found four more instances — see the decompose decision log.*
   *Recurrence 2026-11-05 (`PLAN-V02-53`, inbox -001 and -002):* an outline sweep stated a hand count (25 for 26 files) and missed YAML embedded in Java, shell heredocs and AsciiDoc; a reader-list edit missed the second enumeration in `package-info.java`. The outline Q-Gate caught both in-run. Derive a sweep snapshot from the same content search the criterion is verified with.
4. **`2026-08-08-11-001` — a review-bot fix can be reverted by the project's own quality gate.**
   OpenRewrite `SimplifyTestThrows` re-widens narrowed test `throws` clauses **and exits 0 while
   rewriting the file**, so an accepted bot suggestion re-dirties the tree on every future gate run.
   **A bot suggestion is not verified by implementing it, only by surviving the gate.**
   *Re-verified 2026-08-08: `JUnit5BestPractices` is in the `pre-commit` recipe list (`pom.xml`:436)
   and `BuildGateCoverageContractTest`:142/:171 still read `throws Exception` — the gate won and the
   narrowing did not survive.* **This rule is one of three faces of a single mechanism** — the other
   two are `2026-08-02-17-001` (non-idempotent import churn, which also poisons the change-ledger
   freshness stamp) and `2026-07-27-09-001` (the formatter is non-idempotent and `main` is not its
   fixed point). `PLAN-V02-17` D2 lands all three as one class, and a post-gate
   `git status --porcelain` assertion is the check that covers them.
5. **`2026-08-04-05-001` — out-of-scope Sonar findings on a bounded-write-boundary plan are reported,
   not absorbed.**
