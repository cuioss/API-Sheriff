# PLAN-19: Release-readiness review of README and docs, and honest badges

epic: deployment-configurability
workstream: WS-06

> ⛔ **SUPERSEDED 2026-09-15 by `PLAN-24-release-docs-and-tls-scenario-guide.md`, which carries this plan's scope as its deliverables 7-11.** Retained as
> the audit record of why it was retired; a superseded spec is never deleted. **Do not emit.**
> **Why:** operator decision at the 2026-09-15 corpus revisit (`origin/main` `a2969b9`) — up to 12
> deliverables per plan are authorized, and this spec collided on surface with the others merged
> into the successor, so they could only have run sequentially as separate PR cycles. Its queue row
> is `parked` because the queue status vocabulary has no `superseded` value.
>
> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-10 ahead of a release cut.** Its subject is what an operator reads *before*
> deciding to adopt this release, so its bar is *is this true at the cut*, not *is this well written*.

## ⛔ RE-SCOPED 2026-09-11 — THE RELEASES ALREADY HAPPENED. READ FIRST.

This spec was written to run **before** a cut. **Two cuts have since landed**: `0.2.0` (PR #292) and
`0.2.1` (PR #294), both tagged. Its framing — *"a release is where those claims reach people who
cannot check them"* — is now **post-hoc**, and that changes what the plan is for.

⛔ **BOTH RELEASES SHIPPED WITH `0.1.1`-STAMPED CONTENT.** Verified at `origin/main`:
`README.adoc:52` still reads *"0.1.1 is an ALPHA release"* and `README.adoc:146` still reads
*"Verified as still open at the 0.1.1 cut"* — while the published artifacts are `0.2.0` and `0.2.1`.
**This is exactly the defect class this plan exists to close, and it shipped twice while the plan sat
staged.**

✅ **Deliverable 1 is being discharged OUTSIDE this plan, by open PR #299**
(`chore(release): point the version-bearing content at 0.2.1`). It corrects all three ALPHA
callouts, the Known Limitations stamp, the `.env` pin, the compose VERSION-SKEW block, the
`docker pull` line, `build-parent/example/pom.xml`, and the three FAPI/features stamps that had been
stale since **0.1.0** — through two releases. ⛔ **Confirm #299 landed before planning D1**, and if it
did, D1 collapses to *verifying* the content rather than authoring it.

⚠ **The Known Limitations CONTENT question is NOT settled by #299.** That PR re-stamps and corrects;
it does not re-run the verification the section claims. **D1's real remaining work is the audit** —
walk every entry against `0.2.1` and establish it is still true, because the section asserts
*"Verified as still open at the … cut"* and two doc indexes cite it as such.

✅ **Deliverables 2 and 3 are UNCHANGED and now more valuable, not less.** The badges are still
asserted rather than derived, and a post-release sweep reviews the tree people are actually running.

✅ **#299 LANDED — `64fe822`, re-grounded at `origin/main` `0515e15` by the 2026-09-11 `cleanup` pass.
D1 now starts from verification, not authoring.** What that commit actually did, read from its diff:

- `README.adoc:52` now reads *"0.2.1 is an ALPHA release"*; `.env:15` pins `ghcr.io/cuioss/api-sheriff:0.2.1`,
  so D1's compose-sample bullet ("does not boot until 0.2.0") is **resolved** — do not carry it.
- ⛔ `README.adoc:146` was **re-stamped** to *"Verified as still open at the 0.2.1 cut"* and **one entry
  was added** (the published image runs on x86-64-v3 only), but **no existing entry was re-verified**.
  The stamp now looks current while the content is not — the audit is the whole of D1's remaining work.
- ⚠ **Two stamps #299 missed, for D3's sweep:** `doc/fapi_status.adoc:1` still titles the document
  *"(0.1.0 Alpha)"* while its own line 8 says 0.2.1; `doc/features-analysis.adoc:219` still heads the row
  *"TARGET, NOT SHIPPED IN 0.1.0"* beside a line 220 that speaks of 0.2.1. ⛔ Neither file is in this
  spec's declared surface — **widen the declaration before editing them, or report them.**
- D5 is untouched by #299: `Dockerfile.native.jfr:29` still runs `chmod 777 /tmp/jfr-output`, and
  `.env:4` still names the removed `wait-for-ready.sh`.

## Objective

A release publishes the README and `doc/` as the product's own account of itself. This epic has spent
three weeks finding places where a stated rule had no mechanism behind it — **six instances so far** —
and a release is where those claims reach people who cannot check them. Review what the documentation
asserts against what the artifact does, correct the gaps, and make two badges honest.

⛔ **The bar is not "well written". It is "true at this cut, and checkable".**

## Deliverables

1. **Re-verify `Known Limitations` against what this epic actually landed, and complete it.**
   `README.adoc:143` (`[[_known_limitations]]`) is cited by `doc/README.adoc:24` and
   `doc/user/README.adoc:25` as *"the verified limitations at this cut"* — a claim of verification, so
   it owes one. **Each item below is a candidate; confirm each against HEAD before adding, and do not
   copy this list in.**
   - ✅ **RESOLVED 2026-09-10 BY PR #289 (`9692f91`) — DO NOT CARRY THIS AS A LIMITATION.**
     *"chore(deps): move token-sheriff to released 0.9.5, drop snapshot repository"*, `pom.xml` only.
     ⛔ **The release-blocking question this bullet posed is answered: the cut no longer resolves a
     SNAPSHOT.** Verify at HEAD before writing anything, but do not restate the limitation. Original
     candidate text follows.
   - ⛔ ~~**`token-sheriff` resolves as `0.9.5-SNAPSHOT` on the authentication path**, and *nothing in
     the build refuses a release that still resolves a SNAPSHOT* — the condition lives in `pom.xml`
     prose only. **This is a release-blocking question, not a limitation to document**: settle whether
     the release cuts against a SNAPSHOT at all before writing anything about it.
   - ⛔ **SUPERSEDED 2026-09-10 BY PLAN-17 (PR #288 -> `6c1b6b6`) — THE LIMITATION AS WRITTEN IS NO
     LONGER TRUE, AND THE REPLACEMENT IS NOT "IT WORKS".** PLAN-17 settled the question at **4019**
     and shipped a compression stage (`Deflater` before the seal). ⛔ **Do not simply delete this
     bullet and do not assert the capability either** — establish from `doc/development/bff-cookie.adoc`
     (+231) and ADR-0043 what the *conditioned* capability statement now is, and write that. The spec
     that shipped it required exactly this: *"cookie mode supports refresh under THESE conditions, or
     it does not."*
     ✅ **THE NUMBERS ARE SETTLED AND VERIFIED (2026-09-11), so this is an authoring task, not an
     investigation**: three tokens seal to **2700 bytes against a 4019-byte budget — 32.8 % spare**,
     and **a real Chromium confirmed the cookie is stored** (Demo Client E2E 28/28 green on `main` at
     `6c1b6b6`). ⛔ **Re-derive them at HEAD before publishing** — this epic has been bitten four
     times by a number that was true when written. Original candidate text follows.
   - ⛔ ~~**Cookie mode and refresh do not work together.**~~ After PLAN-16 the sealed session with a
     refresh token exceeds the browser's ~4096-byte cookie budget. Server mode is the supported path
     for deployments needing refresh. PLAN-17 is staged to change this; **at this cut it is a
     limitation and must be stated.**
   - ⚠ **The compose sample's default path does not boot until 0.2.0** — `.env:15` pins
     `ghcr.io/cuioss/api-sheriff:0.1.1`, whose loader predates the array-valued `${VAR}` arm.
     ⛔ **Bumping that pin is a release-time action, not a documentation one** — the epic ledger has
     carried "do not cut 0.2.0 without bumping the pin" since 2026-09-03.
   - ⚠ **`tls.passthrough_sni` cannot be supplied from the environment** — it is the one map-shaped,
     deployment-varying key, and `coerce` has no object case.
   - ⚠ **cui-http 3.0's `paranoid()` preset is not exposed**; `SecurityProfile` offers `STRICT`,
     `LENIENT`, `MINIMAL` only.
   - ⚠ **`RotationResult.scopeDelta` is unread**, so a narrowed scope on refresh is unobserved.
2. **Make the Cosign and Trivy badges honest — link to evidence, or remove them.**
   ⛔ **Both claims are TRUE today, and that is not the problem.** Verified: `release.yml:545` runs
   `cosign sign --yes "ghcr.io/cuioss/api-sheriff@${DIGEST}"` (keyless OIDC), and `release.yml:364`
   sets `exit-code: '1'` — a real gate.
   ⛔ **The problem is that they are ASSERTED, not DERIVED.** Both are static `img.shields.io/badge/`
   images with hardcoded text and a hardcoded `brightgreen`, linking to **prose**
   (`doc/user/container-image.adoc`, `doc/development/release-process.adoc`). **They would stay green
   if `exit-code` flipped to `0` or the signing step were deleted** — nothing measures them.
   ⚠ **Their own neighbours show the standard**: the Container Image badge reads live GHCR, the
   Release badge reads live workflow status, and the benchmark badges read a live endpoint JSON.
   Three derived badges beside two asserted ones.
   ⚠ **`maven.yml:84` makes the confusion concrete**: it states *"`exit-code: 0` on EVERY Trivy step
   below is what makes this lane non-gating"* — so the repository runs both a non-gating Trivy scan
   and a gating one, and a badge saying "HIGH/CRITICAL gate" without naming which is ambiguous even
   while true.
   **Settle one of:** point both at `release.yml` — the workflow where signing and the gating scan
   actually run, and the target the sibling Release badge already uses; publish an endpoint JSON as
   the benchmark badges do; or **remove them**, since a claim nobody can check is worth less than no
   claim. ⛔ **Record the rejected alternatives** — this is the sixth stated-rule-without-a-mechanism
   in this epic and the reasoning should outlive the fix.
3. **Sweep the README and `doc/` for claims the artifact does not support.** The two deliverables
   above are the known instances; this is the pass that looks for the rest. ⚠ **Give it the epic's own
   test**: for each claim of a guarantee, a check or a gate — *what would fail if this were false?*
   A claim with no answer is the class this epic has recorded six times. Report what is found;
   correct what is unambiguous; escalate what needs a decision.
4. **Correct the `benchmarks` module metadata, which describes a harness the module no longer uses.**
   ⛔ **Folded in 2026-09-10 by the epic coverage audit.** Verified at HEAD `990aebf`:
   `.plan/project-architecture/benchmarks/enriched.json` carries **11 WRK mentions** across
   `responsibility`, `purpose_reasoning`, `key_dependencies_reasoning` and a package key
   `de.cuioss.sheriff.api.wrk.benchmark` **that no longer exists** — while the module genuinely runs
   **k6** (`benchmarks/target/classes/k6-scripts/`). Pre-existing drift that PR #230 aggregated rather
   than created.
   ⚠ **Fix at the `enriched.json` root** — `architecture discover` regenerates `_project.json` from
   it, so correcting the derived file alone is undone by the next regeneration.
   ⚠ **`CLAUDE.md` carries the same drift** (*"`benchmarks/` — WRK HTTP load testing benchmarks"*) and
   `benchmarks/README.adoc` and `benchmarks/pom.xml` also mention WRK. Establish which mentions are
   genuine (a WRK result-publication pipeline may still be real) and which are stale before editing —
   ⛔ **this is a correction, not a find-and-replace.**
5. **Fix the two shipped-artifact defects the audit found, both verified at HEAD.**
   - ⛔ **The JFR overlay creates a world-writable directory.**
     `api-sheriff/src/main/docker/Dockerfile.native.jfr:29` runs
     `RUN mkdir -p /tmp/jfr-output && chmod 777 /tmp/jfr-output`. ⚠ **Settle the least-privilege fix
     rather than deleting the line** — `integration-tests/docker-compose.jfr.yml:14` bind-mounts that
     path and `integration-tests/pom.xml:530-532` runs a `prepare-jfr-output-dir` step against it, so
     the permission exists to make a mount writable by the container user. Own the directory to that
     user instead of widening it to everyone.
   - ⚠ **`deployment/compose-sample/.env` names a script that no longer exists.** Line 4 reads *"the
     same derive-don't-restate rule wait-for-ready.sh follows for the readiness probe (ADR-0031)"*;
     `deployment/compose-sample/scripts/` now holds only `start-sample.sh` and `stop-sample.sh`.
     ✅ Verified tracked by git, so it is committable — the ledger's note that "tooling prohibits
     committing `.env`" does not describe this repository's git state. A one-line comment fix.

## Authoring Discipline (from the 2026-09-11 lessons intake)

The Known Limitations audit is a set of claims about runtime behaviour that no build gate executes.
Archived lesson `2026-09-04-07-001` records this exact class shipping twice in this epic:

- **Every limitation re-verified, and every new sentence, names the implementing symbol that enacts or
  refutes it** — and that symbol is **read at HEAD, not recalled** from an earlier cut. Re-stamping a
  section without re-running its verification is the defect this plan exists to close.
- **A paragraph that states a consequence AND explains its mechanism holds two claims — check them
  against each other**, not only against the source.
- **Verify a reviewer's refutation against the source before adopting it**; record a correction that
  cannot ride the same commit as an explicit follow-up.

## Claim Labels

- OBSERVED: `README.adoc:143` declares `[[_known_limitations]] == Known Limitations`, and
  `doc/README.adoc:24` plus `doc/user/README.adoc:25` cite it as *"the verified limitations at this
  cut"*.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: README.adoc known-limitations section and citation pattern still present, cut number moved to 0.2.2
- OBSERVED: the two badges are static and link to prose — read at `README.adoc:34-35`,
  `img.shields.io/badge/cosign-signed-brightgreen` → `doc/user/container-image.adoc` and
  `img.shields.io/badge/trivy-HIGH%2FCRITICAL%20gate-brightgreen` → `doc/development/release-process.adoc`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: the two static Cosign/Trivy badges have been removed from README.adoc entirely, per successor PLAN-24's deliverable 8
- OBSERVED: both underlying claims are true — `release.yml:545` `cosign sign --yes …`, and
  `release.yml:364` `exit-code: '1'`.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: release.yml still runs cosign sign and exit-code 1 Trivy gating
- OBSERVED: the same repository also runs a **non-gating** Trivy lane — `maven.yml:84`, *"`exit-code:
  0` on EVERY Trivy step below is what makes this lane non-gating"*.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: maven.yml still documents the exit-code 0 non-gating Trivy lane
- OBSERVED: the sibling badges are derived, not asserted — `README.adoc:31` (GHCR), `:32` (workflow
  status), `:40-41` (endpoint JSON).
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: GHCR/workflow-status/benchmark-endpoint badges remain derived
- OBSERVED: `pom.xml:87` carries `<version.token-sheriff>0.9.5-SNAPSHOT</version.token-sheriff>` with
  a REMOVAL CONDITION at `:68` that is prose and enforced by nothing.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: yes | evidence: reaffirmed: pom.xml now pins version.token-sheriff at 0.9.6, a further release past the 0.9.5 this claim already noted
- HYPOTHESIS: the existing `Known Limitations` list is stale against this epic's landings —
  confirm/refute by reading `README.adoc:143` onward at HEAD (verify-at-outline). ⛔ Twelve plans have
  shipped since it was last reviewed.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: the re-stamp-without-re-verification pattern is a recurring epic-wide finding the successor plan PLAN-24 was staged to address
- Verify-first clause: re-read every cited line at HEAD. ⚠ PLAN-16 is running and PLAN-08's fold
  touches documentation; both may move these citations.

## Expected Surface

- OBSERVED: `README.adoc` — the limitations section and the two badges
- HYPOTHESIS: `doc/README.adoc` — only if its citation of the limitations section needs updating (verify-at-outline)
- HYPOTHESIS: `doc/user/README.adoc` — same (verify-at-outline)
- HYPOTHESIS: `doc/user/container-image.adoc` — only if deliverable 2 re-points or removes the Cosign badge (verify-at-outline)
- HYPOTHESIS: `doc/development/release-process.adoc` — same for the Trivy badge (verify-at-outline)
- OBSERVED: `.plan/project-architecture/benchmarks/enriched.json` — deliverable 4
- OBSERVED: `CLAUDE.md` — deliverable 4, the same WRK drift
- HYPOTHESIS: `benchmarks/README.adoc` — deliverable 4, only where the mention is genuinely stale (verify-at-outline)
- OBSERVED: `api-sheriff/src/main/docker/Dockerfile.native.jfr` — deliverable 5
- OBSERVED: `deployment/compose-sample/.env` — deliverable 5

⛔ **`doc/configuration.adoc` is deliberately NOT declared.** Deliverable 3's sweep may find claims
there, but that file is the epic's most contended path and is declared by ten specs. **Report findings
against it; do not edit it here.**

## Dependencies and Sequencing

- ⛔ **Depends on PLAN-16 landing** — `main` is red until then, and a release-readiness review against
  a red `main` reviews a tree nobody can ship.
- ⚠ **Deliverable 1 depends on decisions this plan does not own.** The SNAPSHOT question is a release
  decision; the compose-sample pin is a release-time action. **Surface them; do not settle them
  alone.**
- ⛔ **NO LONGER a documentation-only footprint.** Deliverable 5 touches `Dockerfile.native.jfr`, and
  CLAUDE.md names `Dockerfile*` as gate-requiring explicitly — it is exercised by `-Pintegration-tests`.
  **Both gates run.** ⚠ `deployment/compose-sample/.env` is *itself* doc-only-eligible (CLAUDE.md names
  that exact file as non-build config) and `.plan/**` triggers nothing, but one gate-requiring file
  makes the whole commit gate-requiring. The original note read: *Documentation-only footprint as
  scoped, so it skips both gates.*
- ⚠ **Deliverable 4 touches `.plan/project-architecture/`**, which no Maven profile reads — it cannot
  break a build and cannot be validated by one either. Deliverable 5 is what pulls the gate in.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-19-release-readiness-doc-review.md"
```
