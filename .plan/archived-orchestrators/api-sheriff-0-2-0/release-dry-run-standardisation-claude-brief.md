# Standardise the release dry run — brief for a direct Claude Code session

**Not a plan-marshall plan.** Hand this to an ordinary Claude Code session, working in its own git
worktree. It is deliberately *not* in the `api-sheriff-0-2-0` queue: it ships a skill and a CI
artifact, not product code, and it wants one focused session rather than the full plan lifecycle.

> **DO NOT START THIS YET.** It is hard-gated — see § Gate. Starting early guarantees rework.

**Source:** operator request 2026-08-05, made while the 0.1.0 release was dispatching — *"The dry run
we just did: I want to make it standard. Either a parameter for release like `/release dry` or a
separate skill."*

## Why this exists — the measurement, not the theory

The 0.1.0 cut established the need empirically. The gating Trivy scan was **red on 2026-08-04**
(CVE-2026-31789 CRITICAL plus five HIGH in `libssl3t64` 3.5.4-1~deb13u2) and green on **2026-08-05**
only because Dependabot bumped the distroless base to `58c3090` in between.

A cut on the 4th would have published jars to Maven Central **irrevocably** and *then* failed the
image gate — the exact partial release the non-atomic jars-then-image lane makes possible.

The rehearsal that caught this was a hand-written brief, executed once, by a session that had to be
told the exact flags. **That is not a mechanism. This makes it one.**

## Gate — read before starting

**`PLAN-48` in `api-sheriff-roadmap` must have landed first.** It re-arms the merge-triggered release
behind a version-changed guard, **and its D4 edits the same `.claude/skills/release/SKILL.md` this
work modifies**. A dry-run mode authored against the pre-PLAN-48 lane is stale on arrival and
collides on the same file.

Also gated on 0.1.0 being published — the whole 0.2.0 epic is.

**First action when the gate opens:** re-read `release.yml` and the release skill on `main` and
confirm the step set below still matches. Every OBSERVED fact here was read on 2026-08-05,
pre-PLAN-48. **Where PLAN-48 moved something, re-derive rather than patch.**

## The form decision — already taken, overturnable only on evidence

The operator offered two shapes: a parameter on `/release`, or a separate skill. **The call is a
dry-run mode on the existing skill.** The reasoning is not aesthetic:

**A dry run is worth exactly as much as its fidelity to the real steps.** A second document
describing the same pipeline drifts — and this project produced three first-party instances of
exactly that, all on 2026-08-05, all describing this same release lane:

1. The 0.1.0 ad-hoc brief named two container images that **do not exist**
   (`aquasecurity/trivy:latest`, `anchorepkg/syft:latest`). Both commands failed as written.
2. `release.yml`:32 cited a reusable-workflow SHA two versions stale.
3. That same comment's central claim — that the reusable workflow declares no
   `on.workflow_call.outputs` — was **false**, and it is the sole justification for a workaround
   still in the file.

Three drift defects in one lane in one day is the argument. A mode shares one step set with the real
run by construction; a sibling skill re-states it and rots.

**Overturn this only on evidence** — for instance, if the skill format cannot express a mode without
duplicating the step bodies anyway, in which case the duplication has merely moved. Record the
rationale either way; do not silently re-decide.

## Setup

```bash
git -C /home/oliver/git/API-Sheriff fetch origin
git -C /home/oliver/git/API-Sheriff worktree add \
  .plan/local/worktrees/release-dry-run-mode \
  -b feat/release-dry-run-mode origin/main
cd /home/oliver/git/API-Sheriff/.plan/local/worktrees/release-dry-run-mode
```

## What to build

### 1. A dry-run mode on the `/release` skill

Same step set, same flags, same pinned versions as the real lane — **sourced from the lane, not
re-typed beside it**. Two hard requirements:

- It must be impossible to trigger the real cut by mistyping the dry form.
- The mode must state plainly, **in its own output**, which steps it did *not* exercise. A rehearsal
  that lets its reader infer full coverage is worse than none.

### 2. The local tier — the gating scan, minutes, no install

This is the highest-value half and the one that caught the near-miss. Mirror `release.yml`:211–219
exactly: `--severity HIGH,CRITICAL --exit-code 1 --ignore-unfixed=false --format table`, addressing
the image by content-derived ID. Run Trivy as a container — `ghcr.io/aquasecurity/trivy:latest` (or
`aquasec/trivy:latest`); **`aquasecurity/trivy:latest` does not exist**.

Two things it must get right, both learned the hard way:

- **Image freshness is a precondition, not a detail.** The 0.1.0 rehearsal's first scan target was an
  image built four hours before the code it was meant to represent. **A stale image's green looks
  identical to a real one.** The mode must establish that its scan target was built from the tree
  under test — and **refuse rather than guess**. The image is `api-sheriff:distroless`, built by the
  integration-test lifecycle (`release.yml`:143–147); there is exactly one build in the lane by
  design, which is what makes "push exactly what was tested" true by construction.
- **Never soften the gate to make the rehearsal pass.** `--ignore-unfixed=false` is deliberate and
  there is deliberately **no `.trivyignore`** in the tree — an empty suppression file was judged an
  invitation to silent drift. A rehearsal weakened until it went green has measured nothing.

### 3. The CI tier — and make the standing-exposure decision explicitly

Cosign keyless signing needs an Actions OIDC token; GHCR push and pull-by-digest need a real
registry. Neither has a local equivalent, so a `workflow_dispatch` workflow is the only vehicle —
and **`workflow_dispatch` fires only for workflows present on the default branch**, so there is no
third "local only" option.

**Decide deliberately and record the trade:**

- **Permanent** dry-run workflow — convenient, but leaves a job holding `packages: write` and
  `id-token: write` standing on `main`.
- **Add-and-remove** per rehearsal — two PRs through a gated merge queue each time.

Two constraints inherited from the real lane, both verified there:

- **Throwaway tags only** (`dryrun-<sha>`), never the release tag shape (`:<version>`,
  `:sha-<tag-sha>`). A stray tag in the release namespace is worse than no dry run.
- **Do not switch the push to `docker buildx build --push`.** Buildx's default attestations produce a
  manifest *list*, which changes what a pull-by-digest smoke pulls and what Cosign signs. The lane
  carries this warning explicitly; keep plain `docker tag` + `docker push`.

### 4. Make the certificate-identity difference explicit, do not paper over it

The real lane's verify pins `--certificate-identity …/.github/workflows/release.yml@refs/heads/main`.
A dry run signs under its *own* workflow's identity. **Pin the dry run's verify to its own identity —
do not widen the matcher to span both**, which would weaken the assertion into one proving less than
the real release's. State in the output that the rehearsal proves the **mechanism** (OIDC issuance,
Fulcio cert, Rekor entry, signature bound to the digest), **not** the identity string, which only
`release.yml` ever produces.

### 5. Retire the ad-hoc brief and fold its durable findings in

Delete `.plan/local/orchestrator/api-sheriff-roadmap/release-dry-run-claude-brief.md`. Leaving it
beside a shipped capability recreates the two-documents-drift problem this work exists to remove.

**What must survive into the mode's own text:**

- the corrected container references;
- the **scanner-version parity** caveat — `release.yml`:212 passes no `version:` input to
  `aquasecurity/trivy-action@ed142fd0` (v0.36.0), so CI runs that action's pinned default (v0.70.0);
  a local `:latest` is ahead, so a local run is not bit-for-bit CI parity;
- the **scope-honesty** point — Trivy reports `num=0` language-specific files, so the gate scans the
  pinned base's OS packages and says **nothing** about the Java dependency tree (that is the PR-lane
  filesystem scan, Sonar and Dependabot);
- **why a local green transfers anyway** — the finding surface is entirely the base image's 13 OS
  packages and the base is pinned **by digest**, so that surface does not depend on who builds the
  image. Strong evidence, not proof: a native build is not reproducible and CI builds its own;
- the **shelf-life rule** — a green is worth **days, not weeks**, so it is re-run *immediately*
  before dispatch.

Also update `doc/development/release-process.adoc`: the dry run becomes part of the documented
procedure.

## Facts to re-verify, not to trust

All read first-party on 2026-08-05, **before PLAN-48**:

- `release.yml` is `on: workflow_dispatch:` with **no inputs**; no `dry_run` flag exists anywhere
  under `.github/`, `.claude/`, or `doc/development/release-process.adoc`.
- The gating scan is `aquasecurity/trivy-action@ed142fd0` (v0.36.0) at `release.yml`:211, flags as
  above, no `version:` input.
- The `publish-image` job holds `contents: read`, `packages: write`, `id-token: write`
  (`release.yml`:53–57).
- **Open question, deliberately left alone:** `release.yml`:32's composability caveat is *false*
  (v0.18.0 does declare `on.workflow_call.outputs`), which makes the re-read workaround at :81–93
  redundant. PLAN-48 may have resolved this. **Check, and do not re-open it here** — it is a
  behavioural change to the release lane and out of scope for a rehearsal capability.

## Gates, commit, PR

Run the **Pre-Commit Process** from `CLAUDE.md` — both gates, zero errors/warnings, via the Maven
executor with `--project-dir` pointed at this worktree.

> **Known trap:** `verify -Ppre-commit` emits non-idempotent OpenRewrite import churn across ~170
> Java files (lesson `2026-08-02-17-001`). **This change touches zero Java.** Any Java file that comes
> back modified is churn — `git checkout --` it before committing, and confirm with `git status` that
> the staged set is exactly your footprint.

Then the standard flow from `CLAUDE.md` § Git Workflow. **Do not enable auto-merge**; report the PR
number and stop for approval.

> **Merge hazard, learned on PR #167:** this repo is merge-queue gated. **Never pass
> `--delete-branch`** — `pr merge` only *enqueues*, and the flag removes the head ref out from under
> the queued entry, closing the PR unmerged while the tool still reports `merged: true`. Verify the
> merge against the GitHub API and the actual tip of `origin/main`, never the wrapper's return value.

## Boundaries

- **No behavioural change to the release lane.** Not its trigger, not its `uses:` pins, not its
  permissions, not its gate flags. That is PLAN-48's territory.
- Do not dispatch `release.yml`. Do not create release tags. Do not touch `.github/project.yml`.
- The only orchestrator-tree write permitted is the single deletion named in item 5.
- If the gate check at the top shows PLAN-48 has not landed, **stop and say so** rather than
  proceeding against a lane that is about to move.
