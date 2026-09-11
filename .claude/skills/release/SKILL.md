---
name: release
description: Cut an API Sheriff release along one of exactly two paths — merging a .github/project.yml version bump, which IS the publishing act once the central cuioss-organization version-changed guard lets it through, or a deliberate workflow_dispatch, which fires unconditionally and is the fallback. Covers the pre-cut safety assertions that must precede the merge, the dispatch, the non-atomic jars-then-image lane and its recovery paths, the post-cut "exactly one of each" verification, the mandatory GHCR public-package action after the first release, and the release-notes house format.
user-invocable: true
allowed-tools: Bash, Read, Edit, Write
---

# Release Skill — API Sheriff

Cuts an API Sheriff release end to end: confirm the version, re-assert the pre-cut safety evidence,
**cut the release along one of the two paths below**, wait, verify that **exactly one** release
fired, reformat the generated notes, and carry out the mandatory post-release action.

The GitHub repository is **`cuioss/API-Sheriff`**. Always pass `--repo cuioss/API-Sheriff` to `gh`.

> **Frontmatter shape — a deliberate divergence from the sibling skill, recorded here.**
> The one sibling under `.claude/skills/`, `run-integration-tests`, declares `mode: knowledge`:
> it is reference material you read while doing something else. This skill is the opposite —
> a **procedural runbook that is followed step by step and mutates the world irreversibly**, so it
> takes the `user-invocable: true` + `allowed-tools:` shape instead. The divergence is intentional
> and is not an oversight to "correct" back into line with the sibling.

> **Activation latency (harness surface).** A newly authored `.claude/skills/**` body is not
> discoverable as an invocable skill in the session that wrote it. Whoever cuts the release either
> starts a fresh session or simply **follows this file as a document**. It is written to read
> correctly both ways.

---

## How the release is wired — READ THIS FIRST

`.github/workflows/release.yml` carries **two triggers, and there are exactly two release paths**:

```yaml
on:
  workflow_dispatch:
  pull_request:
    types: [closed]
    branches: [main]
    paths: ['.github/project.yml']
```

| Path | When it fires | Role |
|---|---|---|
| **Merge of a version bump** — a `pull_request` closed with `merged == true`, base `main`, touching `.github/project.yml` | only when the **central guard** inside the pinned `cuioss-organization` reusable workflow sees `release.current-version` actually changed between the merge commit and its first parent, **and** no tag for that version already exists | the ordinary way a release is cut |
| **`workflow_dispatch`** | whenever dispatched **from `main`** — the `release` job's `if:` confines the dispatch path to `refs/heads/main`, and nothing else gates it, which includes re-releasing an unchanged version | the deliberate fallback: recover a failed lane, or cut a version whose declaration already landed |

**State them as a pair.** An operator whose merge did *not* cut a release needs to know which
mechanism decided that before reaching for the other path — Step 5 *Path A* carries that diagnosis.

### The merge of a `.github/project.yml` version bump IS the release

**Merging `current-version: X → Y` with no `Y` tag present publishes** — jars to Maven Central and
the container image to GHCR, at merge time. Every safety assertion in this runbook therefore has to
happen **before** that merge, not after it. That is what Step 1 and Steps 2–4 are ordered around.

### The `paths:` filter is a prefilter, never the decision

`.github/project.yml` carries non-release sections (`maven-build`, `sonar`, `pages`,
`github-automation`, and that list is open), so a merge *arriving* at the workflow proves nothing
about the version. **The central guard decides.** A merge that reaches the workflow and cuts no
release is the guard working, not the guard failing.

### The incident this trigger caused once, and what changed

The trigger above is the same *shape* that fired for real on *2026-07-12*: a merged pull request
touching `.github/project.yml` cut a genuine Maven Central release, publishing
`de.cuioss.sheriff.api:*:1.0.0` from a project still in pre-1.0 development. Maven Central releases
are immutable — a published GA coordinate cannot be withdrawn, only superseded. The `1.0.0` GA was
abandoned, relocation stubs were published at `1.0.1`, the coordinates moved to
`de.cuioss.sheriff.gateway`, and the version line restarted at `0.1.0-SNAPSHOT`.

**What changed is the location of the decision, not the forgiveness of the trigger.** The 2026-07-12
trigger fired on *merged-ness* — every merge touching the file was a publication. The pinned
`cuioss-organization` workflow now owns the version-changed decision, which is precisely the
discrimination that trigger lacked. It is **consumed** here and **never re-implemented locally**:
`publish-image` keys off `needs.release.outputs.released-version != ''`, which is the guard's own
verdict surfaced. The decision is recorded in **ADR-0035** (`doc/adr/`), which `release.yml`'s header
references and which supersedes ADR-0034; read it there for the reasoning. This section remains the
narrative account of the 2026-07-12 incident.

Consequently:

- **The `pull_request` trigger is permitted only in its guarded form** — the `merged == true` job
  gate, the `branches: [main]` base filter, and the centrally-owned version-changed guard, all
  present together. A trigger of this shape without the central guard is the 2026-07-12 defect.
- **Never re-derive "the version changed" in this repository.** A missing or broken central guard is
  a block to report, never something to substitute locally.
- A change that removes or weakens an event-driven trigger must merge **on its own**, before any
  change that would fire that trigger — for a `pull_request` event GitHub evaluates the workflow
  definition **from the base branch**. `doc/development/release-process.adoc` records that rule; do
  not restate it from memory.

### The guard has fired once and has never been observed refusing — read this before cutting

**The merge path has run exactly once.** `0.1.1` was cut on 2026-08-07 by merging the version-bump
PR (#186): its signing certificate carries `github_workflow_trigger = pull_request` at
`github_workflow_sha = f3b9ed6`, the merge commit. The central version-changed guard has therefore
been observed **firing** on a real version change. The `0.1.0` cut before it went out by
`workflow_dispatch`, the *unconditional* path, which exercises no discrimination at all.

**What has never been observed is the guard REFUSING.** No merge leaving the declared version
unchanged has been watched arriving at the workflow and being turned away. That half ships proven in
self-test and **unproven here**.

That is not a reason to avoid the merge path. It is a reason to **read the run** rather than assume
it: after the merge, open the `Release` run and observe what the `release` and `publish-image` jobs
actually did (Step 5, *Path A*), instead of inferring the outcome from the merge having happened.

### What one release run produces — on either path

| job | publishes |
|---|---|
| `release` (job `release` in `release.yml`) — `uses: reusable-maven-release.yml` | Maven artifacts to Maven Central; the SCM tag; the GitHub release |
| `publish-image` (job `publish-image` in `release.yml`) — local, `needs: [release]` | the container image to GHCR at the **same** version, plus an SPDX SBOM and a Cosign signature |

---

## Ported reference — the facts this procedure depends on

These six sections are the operative content of `doc/development/release-process.adoc`. They are
reproduced here so the runbook can be followed cold; that document remains the canonical narrative.
None of the six sections below concerns the trigger, so all six stand on their own terms.

### The image version is the Maven version

The image is a packaging of the same released source tree, not a separately versioned product, so
`ghcr.io/cuioss/api-sheriff:<version>` carries the Maven version **verbatim**. An operator who knows
the Maven version must be able to derive the image reference without a mapping table.

**This is a checked fact rather than a convention.** The `publish-image` job checks out the release
tag the Maven release pushed, reads `current-version` from `.github/project.yml` with the same pinned
action the release job used, and asserts the checked-out `project.version` equals it
(step `Assert the checked-out project version matches the released version`). A mismatch fails the release **before the image is built or pushed to
GHCR**.

### The release is NOT atomic

`publish-image` declares `needs: [release]`, so **the Maven Central publication has already
happened** by the time any image step runs. This cannot be reordered away: the released version does
not exist until the release job has cut it.

**A failure in the integration-test suite, the SBOM step, the Trivy gate, the registry push, the
smoke test or the Cosign signature leaves a *partial release*** — the jars are on Maven Central and
are irrevocable, while no image, or an unsigned image, exists in GHCR.

Everywhere this file or the workflow says a step runs "before anything is pushed", read it as
**before anything is pushed to GHCR**.

> A skill that assumes success is the wrong skill for a non-atomic release. Plan for the partial
> outcome before you cut.

### If the image lane fails after the Maven release

1. **Do not re-run the whole workflow, and do not reach for the other path.** A second dispatch would
   attempt another Maven release of a version that is already published — the dispatch path is
   unconditional and will not refuse it. The merge path *would* refuse (the tag now exists), so
   re-declaring the same version and merging it again achieves nothing but a confusing no-op run.
   Neither is the remedy; the classification below is.
2. **Classify the failure before doing anything — a re-run cannot pick up a fix.** *Re-run failed
   jobs* creates a new **attempt of the same run**: it reuses the original event context and the
   original workflow definition, and `publish-image` checks out the **release tag**, not `main`. A
   commit merged to `main` after the cut is therefore invisible to the re-run. "Fix it on
   `main`, then re-run" does not work here and must not be attempted.
   - **Transient job/runtime failure** — a registry timeout, a runner flake, a Maven Central
     propagation race. **Re-run the failed `publish-image` job alone** from the Actions UI (*Re-run
     failed jobs*). It reads its version from `.github/project.yml` at the commit that triggered the
     run and checks
     out the release tag, so it reproduces the same inputs without touching Maven Central.
   - **Anything carried in the tree or the workflow** — the source, the Dockerfile, the base-image
     pin, a `.trivyignore`, `release.yml` itself. A re-run reproduces the same inputs and so fails
     the same way, every time. Fix the cause on `main` and **cut a new patch version**. Do not
     re-run.
3. If the failure was the Trivy gate on an unfixable base-image CVE, see the next section for the
   remedy — but note that both remedies (re-pinning the base, adding a `.trivyignore`) are **tree**
   changes, so they land under the second bullet above and need a new patch version rather than a
   re-run.
4. If the version has to be abandoned: **cut a patch version and publish relocation stubs.** Maven
   Central artifacts are never deleted.

### If the scan blocks on an unfixable base-image CVE

The gate runs with `ignore-unfixed: 'false'`, so it fails on HIGH and CRITICAL findings **including
those with no upstream fix available**. Combined with a digest-pinned base image, a release can
become blocked by a CVE in a layer this repository does not control and cannot patch. That is the
intended default.

In order of preference:

1. **Re-pin the base image** to a newer digest carrying the fix. This resolves the finding rather
   than hiding it, and is almost always available for a distroless base.
2. **If, and only if, no fixed base exists**, add a `.trivyignore` at the repository root with one
   entry per CVE, each carrying a comment recording the CVE id, why it is not exploitable in this
   image, who accepted it, and the date. Trivy picks the file up automatically. There is
   deliberately no `.trivyignore` in the tree today — an empty suppression file is an invitation to
   append to it without the accompanying justification.

> **STANDING PROHIBITION.** Never relax `severity`, and never flip `exit-code` to `'0'`, to get a
> release out. Those change the gate for **every future release**; a `.trivyignore` entry is scoped
> to the specific finding and is visible in review.

### Container image tags

Every release publishes exactly **two** tags, both resolving to the **same tested digest**.

- **`ghcr.io/cuioss/api-sheriff:<version>`** — the human-facing release reference. `<version>` is
  the Maven version, verbatim.
- **`ghcr.io/cuioss/api-sheriff:sha-<commit>`** — **provenance, not immutability.** `<commit>` is
  the **full 40-character SHA of the commit the release tag points at** — the `release:prepare`
  commit whose poms carry the released version — and **not** the `main` HEAD the release was cut
  from.

**Neither tag is a deployment pin.** Only `ghcr.io/cuioss/api-sheriff@sha256:...` is immutable. That
is exactly why Cosign signs the **digest** rather than either tag.

### One-time action after the first release

A GHCR package created by a `GITHUB_TOKEN` push is **private by default**, and the in-workflow smoke
step pulls with the job's own credentials — **so it cannot detect this**.

**After the first release, open the organisation's package settings and set the `api-sheriff`
package to *public*.** **Public — not *Internal*.** Internal leaves anonymous pulls failing while
every org-member check passes, so it looks done and is not; Step 9 carries the full trap and the
assertion that catches it.

Until that is done, **every check stays green while `docker pull` fails for everyone outside the
organisation.** See Step 9 — for the 0.1.0 cut this is live, not hypothetical.

---

## Workflow

> **EVERY GUARDED BLOCK BELOW TERMINATES ON FAILURE — run each one as a script, not pasted
> line-by-line into a login shell.** The `STOP` / `ERROR` branches all end in `exit 1` on `stderr`.
> That is the guard mechanism: a message an operator has to notice is not a guard, because the very
> failure mode being guarded against is *continuing anyway*. Run each block through the Bash tool
> (or `bash -c`, or save it and `bash the-block.sh`) so the non-zero status is what stops the
> procedure. Pasting an `exit 1` into an interactive shell would close that shell — which is why
> the blocks are scripts, not paste-ins.
>
> **Each block is its own shell, so shell variables do not carry across blocks.** That is why every
> block that captures a value (`MAIN_SHA`, `PREV_RUN_ID`, `RUN_ID`, the image digests) also `echo`s
> it: the echo is the hand-off. A later block that needs one re-declares it at the top from the
> echoed value. Assuming a variable survived from an earlier block is how `RUN_ID` ends up empty.

### Step 1 — Determine the version, choose the path, and prepare (do NOT merge yet)

Read the release block in `.github/project.yml`:

- `release.current-version` — the version that will be released
- `release.next-version` — the following development version

**`current-version` is what the release publishes.** Both the `release` job and the `publish-image`
job read it; the version-identity assertion compares it against the checked-out `project.version`.

#### 1a — Derive every version once, from `.github/project.yml`

`.github/project.yml` is the source of truth for **every** version this runbook touches. Derive
them into shell variables **once** and thread those variables verbatim through the branch name,
commit subject, PR title and body, the tag check and the release notes. **Never hand-substitute a
literal `<version>` placeholder**, and never take the number from a git tag, a previous GitHub
release, or your memory of the last cycle.

> **This repository's semantics, which are NOT TokenSheriff's — read before copying anything
> across.** Here `release.current-version` is *the version the cut publishes*: the `release` job and
> the `publish-image` job both read it, and Path A bumps it **to** the version being released.
> TokenSheriff's skill instead treats `current-version` as the *last released* version and derives
> the release from `next-version`. **Do not port that derivation.** The two repos genuinely differ,
> and the evidence is in this repo's own history: commit `f3b9ed6` (*"declare version 0.1.1"*) moved
> `current-version` `0.1.0 → 0.1.1` and **left `next-version` untouched at `0.2.0-SNAPSHOT`** — so
> `next-version` here has been stale across a release and cannot be trusted as the input.

```bash
eval "$(python3 -c '
import pathlib, re
text = pathlib.Path(".github/project.yml").read_text()
def field(key):
    m = re.search(r"^\s*" + key + r":\s*(\S+)\s*$", text, re.M)
    if not m:
        raise SystemExit(key + " not found in .github/project.yml")
    return m.group(1)
print("PREV_VERSION=" + field("current-version"))
print("DECLARED_NEXT=" + field("next-version"))
')"
echo "declared: current=$PREV_VERSION next=$DECLARED_NEXT"
```

`PREV_VERSION` must be captured **before** the Step 1c edit — once `current-version` is bumped the
previous release is no longer recoverable from the file, and the release notes' `**Full Changelog**`
compare range needs it.

**Now read the reactor version through Maven, not by grepping the file:**

```bash
POM_VERSION=$(./mvnw -B -q help:evaluate -Dexpression=project.version -DforceStdout -N)
echo "pom.xml reactor version: $POM_VERSION"
```

> **`help:evaluate` is used deliberately instead of a grep.** The **first** `<version>` in `pom.xml`
> is the *parent's* (`cui-quarkus-parent`); the reactor version is the one following `</parent>`. A
> first-match grep picks the parent and reports a mismatch that is not there. Resolving through Maven
> removes that trap by construction rather than warning about it.

> **Do NOT hand-edit `project.version` in `pom.xml`.** It would collide with the release plugin and
> break the version-identity assertion in the `Assert the checked-out project version matches the
> released version` step.

**Set `RELEASE_VERSION` and `NEXT_VERSION` explicitly, and confirm both with the user.** The usual
shape is `RELEASE_VERSION` = `$POM_VERSION` with `-SNAPSHOT` stripped — the trunk is already floating
on the line being cut:

```bash
RELEASE_VERSION="${POM_VERSION%-SNAPSHOT}"
# NEXT_VERSION is a DECISION, not a derivation - see below.
echo "$PREV_VERSION -> $RELEASE_VERSION (then NEXT_VERSION=?)"
```

⛔ **`NEXT_VERSION` is a decision and has no safe default here.** Because `next-version` went stale
across the 0.1.1 cut it cannot be believed, and the choice — next patch (`X.Y.(Z+1)-SNAPSHOT`) or
next minor (`X.(Y+1).0-SNAPSHOT`) — changes what the trunk floats on afterwards.

**Put both numbers to the user with `AskUserQuestion` before opening the version-bump PR** — the
version being cut *and* the resulting `next-version`. On Path A that PR's merge **is** the release,
so there is no later point at which to reconsider. State the determined pair and proceed only once
it is confirmed.

#### 1b — Choose the path

| what you find | the path |
|---|---|
| `current-version` **already** declares the version you intend to release | **Path B — `workflow_dispatch`.** There is nothing to bump, so the merge path cannot fire: with the declared version unchanged, the central guard refuses by design. Go to Step 2. |
| `current-version` **must change** to the version you intend to release | **Path A — the version-bump merge.** Prepare the PR below, then go to Step 2. **The merge itself happens in Step 5, after Steps 2–4.** |

#### 1c — Path A only: prepare the version-bump PR, and do not merge it

Uses the variables exported in 1a verbatim — no placeholder is hand-substituted anywhere below.

```bash
git checkout -b "chore/release_${RELEASE_VERSION}"
# edit .github/project.yml: current-version -> ${RELEASE_VERSION}, next-version -> ${NEXT_VERSION}
git add .github/project.yml
git commit -m "chore(release): declare version ${RELEASE_VERSION}"
git push -u origin "chore/release_${RELEASE_VERSION}"

# Ensure the label exists before it is used; harmless and idempotent when it already does.
gh label create skip-bot-review --repo cuioss/API-Sheriff \
  --description "Skip automated bot review" --color ededed 2>/dev/null || true

gh pr create --repo cuioss/API-Sheriff --base main \
  --label "skip-bot-review" \
  --title "chore(release): declare version ${RELEASE_VERSION}" \
  --body "Declare \`current-version\` \`${RELEASE_VERSION}\`, \`next-version\` \`${NEXT_VERSION}\`.

**MERGING THIS PR CUTS THE RELEASE.** The central version-changed guard sees \`current-version\` move
with no \`${RELEASE_VERSION}\` tag present, so the merge publishes the jars to Maven Central and the
container image to GHCR. Do NOT merge until the release runbook (\`.claude/skills/release/SKILL.md\`)
Steps 2-4 have passed."
```

> **The body is double-quoted here so `${RELEASE_VERSION}` and `${NEXT_VERSION}` expand, which means
> every backtick inside it MUST be backslash-escaped.** In a double-quoted shell string an unescaped
> backtick opens a command substitution: the body would silently lose the literal and the shell would
> run whatever sat between the backticks. The earlier single-quoted form avoided that by forbidding
> expansion altogether — this form buys variable threading and pays for it with the escapes. **Keep
> both halves of the trade in mind when editing this block.**

> **`skip-bot-review` matches the other cuioss release skills.** The version-bump PR is mechanical —
> a two-line change to `.github/project.yml` — so a bot review adds nothing and costs a review cycle
> on the one PR whose merge is irrevocable. ⚠ **This label skips the *bot* review only. It does not
> skip CI, and it does not skip Steps 2-4**, which are what actually gate the cut.

> **THIS PR IS THE RELEASE. Do not merge it here.** Steps 2, 3 and 4 are the pre-cut safety
> assertions, and on Path A they exist to gate *this merge* — the Trivy posture, the tag-absence
> check, the green-`main` gate and the quiesced queue all belong **before** the publishing act, not
> after it. Merging now would run every one of them on an irrevocable release that has already
> happened.

### Step 2 — Confirm the tree and the pull-request queue are clean

```bash
gh pr list --repo cuioss/API-Sheriff --state open --json number,title,isDraft
```

The expected count differs by path, and the difference is exact:

- **Path B (dispatch)** — **zero** open PRs.
- **Path A (version-bump merge)** — **exactly one** open PR: the `chore/release_${RELEASE_VERSION}` PR from
  Step 1c, and nothing else. It is open by construction; every *other* open PR is the same hazard it
  is on Path B.
- **Any other open PR, on either path** → these would normally merge before a release. Surface the
  list and **ask the user** whether to proceed or wait. Do not silently ignore them.

```bash
git status --porcelain
git fetch origin main
git rev-parse HEAD origin/main
```

The working tree must be clean. On Path B, `HEAD` must equal `origin/main`. On Path A you are on the
`chore/release_${RELEASE_VERSION}` branch, so `HEAD` is the PR head — what must be true there is that the
branch is **up to date with `origin/main`** (`git merge-base --is-ancestor origin/main HEAD`), so the
commit the queue produces is the `origin/main` the rest of this step gates on plus the version bump
and nothing else.

### Step 3 — Re-assert the pre-cut safety evidence (MANDATORY)

The plan that authored this skill recorded a baseline and the method. **Items (i), (iii) and (iv)
are TIME-VARYING — they were true at the SHA they were recorded at, not forever. Re-assert all three
here, at cut time. Do not inherit them.**

**On Path A every item in this step runs BEFORE the merge**, because the merge is the release. On
Path B it runs before the dispatch. Same assertions, same ordering rule.

**(i) Confirm the release trigger is still in its guarded form, and name the SHA you read it at.**

```bash
git fetch origin main && git rev-parse origin/main   # record this SHA in your report
```

**Read `.github/workflows/release.yml` at that SHA** — `origin/main`, not your local `HEAD`: for a
`pull_request` event GitHub evaluates the workflow definition **from the base branch**, so on Path A
it is the base-branch copy that decides what your merge does.

Confirm **all four**, and treat any one of them failing as a stop:

1. The `on:` block carries **exactly** `workflow_dispatch` plus the `pull_request` trigger with
   `types: [closed]`, `branches: [main]`, `paths: ['.github/project.yml']` — **and nothing else.** No
   `push`, no `schedule`, no additional path, no widened `branches:`, no extra `types:`.
2. The `release` job's `if:` still carries **both** operands, ANDed:
   `(github.event_name != 'workflow_dispatch' || github.ref == 'refs/heads/main')` and
   `(github.event_name != 'pull_request' || github.event.pull_request.merged == true)`. The second is
   the merged-ness gate. The first is what confines a dispatch to `main` — `workflow_dispatch:`
   carries no branch filter of its own, and the Cosign identity Step 8 verifies accepts only
   `refs/heads/main`, so losing that operand would let a dispatch from any ref publish under an
   identity the project's own verification command then rejects.
3. The `publish-image` job still carries `if: needs.release.outputs.released-version != ''` — the
   central guard's own verdict, surfaced. Without it a guard-skipped merge would still spend 90
   minutes on a native build and then re-push and re-sign an already-released tag.
4. The pinned `cuioss-organization` ref is still a **release** ref carrying the central guard
   (`uses: cuioss/cuioss-organization/.github/workflows/reusable-maven-release.yml@<sha> # vX.Y.Z`).
   A moved pin, or a pin to something that is not a release, moves the decision out from under this
   procedure.

**A claim about the trigger that does not name a SHA is unverifiable later** — and that is *more*
load-bearing now, not less: the trigger is armed, so what the `on:` block and these two `if:` gates
say at the base-branch SHA is exactly what decides whether your merge publishes.

**(ii) Confirm `release.yml` is the only invocation of `reusable-maven-release.yml`.**

```bash
git ls-files .github/workflows/
git status --short --untracked-files=all .github/workflows/
```

Then **`Read` every listed file** and confirm exactly one invokes `reusable-maven-release.yml`.

> **A CONTENT SEARCH IS NOT VALID EVIDENCE HERE.** The architecture inventory does not walk
> `.github/**`. `architecture search --content --literal --pattern "reusable-maven-release.yml"`
> returns `count: 0` with `files_scanned: 934`, **no unreadable entries and no elision** — a
> clean-looking zero — **while the string is present in `release.yml`'s `release` job.** That zero is a
> **coverage gap, never an absence.** Use direct `Read` enumeration, always.

**(iii) Confirm the merge queue is quiesced and `main` is settled — IMMEDIATELY before the cut.**

```bash
gh pr list --repo cuioss/API-Sheriff --state open --json number,title
git fetch origin main && git rev-parse origin/main
```

A queue entry requires an open PR, so the open-PR list bounds the queue. **Path B: zero open PRs
means an empty queue. Path A: the only permitted entry is the `chore/release_${RELEASE_VERSION}` PR itself** —
anything else open can land ahead of it and move the commit your release is cut from.

> **CONCURRENCY HAZARD — this is a check-then-act (TOCTOU) window on two shared resources: the merge
> queue and `main` itself.** `main` is merge-queue gated, and the release **force-pushes to `main`
> twice** (`Push changes` and `Push tag`, both `force: true`), succeeding against the protected
> branch because `cuioss-release-bot` is a queue bypass actor. **A force push racing a merge-queue
> landing can discard commits.**
>
> The window is **not** closed by ordering. It is **bounded** — partly mechanically, partly by
> procedure — and the two halves are not equally strong:
> 1. Assert quiescence **immediately before** the cut, not minutes earlier.
> 2. **The pre-cut half has a mechanism, and it differs by path.** On Path B, Step 5 re-reads
>    `origin/main` and aborts non-zero unless it still equals the `$MAIN_SHA` Step 4 gated on, so a
>    landing between the gate and the dispatch stops the release instead of publishing an unverified
>    commit. On Path A the merge queue supplies it: the queue re-runs the required checks on the
>    `merge_group` ref, so the commit that lands has been tested against whatever else landed first —
>    which is why *no other entry* may be in the queue with the version-bump PR.
> 3. **The post-cut half has none: nothing merges until the release run completes.** Tell the
>    team, and do not merge anything yourself, from the cut until Step 7 reports the run finished.
>    No branch policy, merge freeze or workflow check enforces this — it is an operator obligation,
>    and a merge landing inside that window can still race the release force-push. Treat it as a
>    residual operator risk, not a closed safeguard — ADR-0035 records it as exactly that residual
>    risk, and states plainly that nothing enforces the post-dispatch merge hold.

**(iv) Confirm no tag for the release version already exists.**

```bash
git fetch --tags --force \
  || { echo "ERROR: git fetch --tags failed - both checks below would read a stale tag view" >&2; exit 1; }
git rev-parse --verify --quiet 'refs/tags/<version>'; case $? in
  0) echo "STOP: local tag <version> already exists" >&2; exit 1 ;;
  1) echo "OK: no local tag <version>" ;;
  *) echo "ERROR: the check did not evaluate - this is NOT a pass" >&2; exit 1 ;;
esac
git ls-remote --exit-code --tags origin 'refs/tags/<version>'; case $? in
  0) echo "STOP: remote tag <version> already exists" >&2; exit 1 ;;
  2) echo "OK: no remote tag <version>" ;;
  *) echo "ERROR: the check did not evaluate - this is NOT a pass" >&2; exit 1 ;;
esac
```

**Both must report `OK`. Anything else stops the release** — including `ERROR`, which means the
check never evaluated and is therefore not a pass.

> **Match the full ref, and branch on the exit code.** `git tag --list '<version>'` and
> `git ls-remote … | grep …` only *print*; a procedure that reads their output by eye is not a guard,
> and a re-run that slips past it reaches the workflow's `force: true` tag push. `grep -w '<version>'`
> is doubly wrong: `.` is a regex any-character, and `-w` treats `-` as a word boundary, so
> `-w '0.1.0'` also matches `refs/tags/0.1.0-rc1`. The exact `refs/tags/<version>` forms above match
> one ref and nothing else.
>
> **Why this is checked directly:** the tag push is `force: true`, so a re-run can **MOVE** an
> existing release tag rather than refusing. `release:prepare` would likely fail first, but that is
> *incidental* protection, not a designed guard.
>
> **The central guard checks tag-absence too — that is why this check is worth running first, not
> why it is redundant.** On Path A a pre-existing tag is one of the two conditions on which the guard
> refuses, so a `STOP` here tells you *in advance* that the merge would publish nothing. Learning it
> here costs a re-read; learning it from the run costs a merge you then have to explain. On Path B
> nothing central refuses at all — the dispatch is unconditional — so this is the only check standing
> between a re-dispatch and a moved release tag.

**(v) Confirm smallrye-config still matches the Quarkus this project resolves.**

```bash
python3 .claude/skills/release/check-quarkus-alignment.py --repo . --check-resolved
```

| exit | meaning |
|------|---------|
| 0 | aligned — proceed |
| 1 | **misaligned** — stop, fix, restart |
| 2 | **could not determine** — also a stop. An unresolvable check is never a pass. |

⛔ **`version.quarkus` is INHERITED here, not project-owned — corrected 2026-09-11, and the previous
wording had silently disabled this gate.** The parent is `de.cuioss:cui-quarkus-parent`, which
declares `version.quarkus` (3.39.2 at the time of writing) and drives **both** the
`quarkus-maven-plugin` version and the `io.quarkus:quarkus-bom` import from that one property.
Properties **do** inherit through a real `<parent>`; what cannot supply them is an *imported* BOM.
`pom.xml` restates the `quarkus-bom` import deliberately — first, so it wins the smallrye-config
convergence over `java-ee-10-bom` and `token-sheriff-bom` — using the same inherited property, so
the plugin and the platform still cannot drift apart.

> **Why this paragraph is worth its length.** The earlier text asserted the opposite — *"the parent
> chain declares no Quarkus version at all, and it cannot"* — and the script was written to match,
> scanning reactor POM **text** only. Once the project adopted `cui-quarkus-parent`, an inherited pin
> read as *"not declared anywhere"* and the check returned exit **2** on every invocation. **Exit 2 is
> a hard blocker by design, so the gate went from protecting the release to blocking it — and a gate
> that cannot run is the one that gets waved through, which is precisely the outage it exists to
> prevent.** TokenSheriff hit this identically on the 0.9.5 cut and fixed the script rather than
> removing it; that fix is ported here. **Do not "simplify" this check away because the stack now
> comes from the parent** — the inheritance is what made the *plugin-versus-platform* drift
> inexpressible, and it is not what this check measures.

**What this check still measures, and why the parent does not subsume it.** The risk is a *second*
Quarkus line entering the build and splitting the `io.smallrye.config` family. `token-sheriff-bom`
carries its own `version.quarkus`, inherited from `token-sheriff-parent` — **a separate input that
currently resolves equal only because both parents sit on `cui-quarkus-parent` 1.7.3.** `pom.xml`
records that explicitly: *"That agreement is a coincidence of the moment, not a guarantee."* The
moment the lines diverge, the import ordering is what keeps a single smallrye-config line, and
`--check-resolved` is what proves it. Verified at `7fce677`: Quarkus `3.39.2`, every
`io.smallrye.config` artifact `3.17.2`, unsplit.

Why it matters: Quarkus' deployment classes are compiled against one specific smallrye-config
release, so a newer version — even an internally coherent one — fails augmentation with
`failed to access io.smallrye.config.ConfigMappingLoader$ConfigMappingImplementation`. That shipped
twice through `cuioss-parent-pom` and cost five weeks of red builds the first time. The
`requireSameVersions` enforcer guard cannot catch it: nothing is split, so it stays correctly silent.

⚠ **Keep the script in sync with the TokenSheriff copy** — the two are maintained in parallel and
there is no shared parent to inherit it from. ⚠ **Re-measure the `token-sheriff-bom` paragraph in
`pom.xml` on every `${version.token-sheriff}` bump**; that is a dependency-bump-time obligation this
release gate does not discharge.

**(vi) Confirm the non-reactor example POM's parent version still tracks the reactor.**

```bash
python3 -c "import xml.etree.ElementTree as ET; ns={'m':'http://maven.apache.org/POM/4.0.0'}; r=ET.parse('pom.xml').getroot().find('m:version',ns).text.strip(); e=ET.parse('build-parent/example/pom.xml').getroot().find('m:parent/m:version',ns).text.strip(); print('reactor=%s example=%s' % (r, e)); raise SystemExit(0 if r == e else 1)"
```

(Written as a single `python3 -c` rather than a heredoc on purpose: an agent executing this procedure
is bound by the one-command-per-call and no-heredoc rules, so a heredoc here would prescribe a step
it may not run.)

Exit `0` proceeds; exit `1` **stops the release** — fix `build-parent/example/pom.xml` first, then
restart Step 3.

**`build-parent/example/pom.xml` is NOT a reactor module** — it is absent from the root `<modules>`,
deliberately, because a native build must never enter the default lane. The consequence is the whole
reason this item exists: **no version-bump tooling reaches it.** `release:prepare` inside the pinned
`reusable-maven-release.yml` rewrites the reactor's POMs only, this repository configures no
`versions-maven-plugin` or `maven-release-plugin` of its own, and nothing else edits that file. Its
`<parent><version>` is a hand-maintained pin.

`BuildParentContractTest.adoptingAReleaseIsAParentVersionBump` asserts that pin *tracks* the reactor
root version, so a missed bump is not silent — it turns `main` red on the first build after the cut.
This item catches a drift that already exists *before* spending a release on it; **Step 10's
`build-parent/example/pom.xml` sub-step is what stops the cut itself creating one.** Both are
required: this one looks backward, that one forward.

⛔ **NEITHER of them can see the release-tag skew, and at the 0.2.0 cut that cost a release.** The two
checks compare the example against the reactor **as it stands on the trunk**, where the two agree by
construction before a cut and again after Step 10. The commit the release *tags* is a third state
neither one reads: `release:prepare` transitions the reactor to the released version (`0.2.0`) and
walks past the non-reactor example, which still carries `0.2.0-SNAPSHOT`. `publish-image` checks out
**that tag** and runs the suite there. So an assertion demanding exact equality fails every release,
and it fails *after* the Maven deployment step — which is exactly what happened on 2026-09-11.

**Do not "fix" this by re-tightening the assertion.** `exampleParentVersionTracksReactor` now admits
that one state — a release reactor against exactly that version's `-SNAPSHOT` — and nothing else; a
genuine lag names a *different* version and still fails, on the trunk and at a tag alike. Test `(2a)`
exercises the release-tag branch directly, because the trunk only ever takes the equality branch and
the branch would otherwise stay unproven until the next cut failed on it.

### Step 4 — Gate on a green `main`

The release builds from `main`, so the gate must be bound to **a named commit** — the `origin/main`
SHA you just recorded in (iii) — and not to "the last few runs on the branch". `--branch main
--limit 5` spans several workflows and several commits, and reading it by eye passes a red required
run as readily as a green one.

```bash
MAIN_SHA=$(git rev-parse origin/main)   # the SHA from (iii); re-read it, do not retype it
echo "$MAIN_SHA"
gh run list --repo cuioss/API-Sheriff --commit "$MAIN_SHA" \
  --json workflowName,event,status,conclusion,databaseId,url
```

**Every required workflow must appear for `$MAIN_SHA` with `status: completed` and
`conclusion: success`.** Three distinct outcomes, and only the first permits a cut:

| What you see for `$MAIN_SHA` | Verdict |
|---|---|
| Each required workflow `completed` / `success` | Green — proceed |
| Any required workflow `failure` / `cancelled` / `timed_out` | Red — **do not cut.** Fix and re-check |
| A required workflow absent, `queued` or `in_progress` | **Not a pass.** An absent run is a check that never ran, not a check that passed — wait for it, or establish why it is legitimately absent |

**Never cut a release on a red `main`, and never on an unproven one.** Fix and re-check.

> **Path A gates one commit further, and it is a commit that does not exist yet.** The dispatch
> builds `$MAIN_SHA` itself; the version-bump merge builds the **merge commit the queue produces**,
> which is `$MAIN_SHA` plus the version bump. So Path A owes **both** legs:
> 1. `$MAIN_SHA` green, exactly as the table above requires — it is the base the merge commit is
>    built on; **and**
> 2. the `chore/release_${RELEASE_VERSION}` PR's own required checks green
>    (`gh pr checks <n> --repo cuioss/API-Sheriff`), plus the merge queue's `merge_group` run green
>    before the entry lands.
>
> Neither substitutes for the other. A green PR on a red base still produces a merge commit nobody
> proved, and a green base says nothing about the bump merged into it.

### Step 5 — Cut the release

**Two paths, chosen in Step 1b. Steps 2, 3 and 4 must have passed before either one.**

#### Path A — merge the version-bump PR (the ordinary cut)

**This merge is the publishing act.** Everything gating it has already run.

**Record the pre-cut high-water mark first.** Run ids increase monotonically, so the newest existing
`Release` run id is what lets you tell *your* run apart from one that was already in flight:

```bash
PREV_RUN_ID=$(gh run list --repo cuioss/API-Sheriff --workflow "Release" --limit 1 \
  --json databaseId --jq 'first | .databaseId // 0')
echo "$PREV_RUN_ID"
```

Then merge the `chore/release_${RELEASE_VERSION}` PR:

```bash
gh pr merge <n> --repo cuioss/API-Sheriff --squash
```

> **NEVER pass `--delete-branch`.** `main` is merge-queue gated, so `gh pr merge` only **enqueues**.
> The flag removes the head ref out from under the queued entry and closes the PR **unmerged** while
> the command still reports success. Poll `origin/main` (or the PR `state`) to learn what actually
> happened; never trust the merge command's immediate output.

**Capture the run — bound to this merge, and failing closed when the match is not unique:**

```bash
PREV_RUN_ID=<the id the block above echoed>   # re-declare it: this block is its own shell

RUN_ID=$(gh run list --repo cuioss/API-Sheriff --workflow "Release" \
  --event pull_request --limit 20 \
  --json databaseId --jq "[.[] | select(.databaseId > ${PREV_RUN_ID}) | .databaseId] | \
    if length == 1 then .[0] else empty end")
test -n "$RUN_ID" \
  || { echo "STOP: no unique new Release run on the pull_request event - read the diagnosis below" >&2; exit 1; }
echo "$RUN_ID"
gh run view "$RUN_ID" --repo cuioss/API-Sheriff --json displayTitle,headBranch,headSha,event,url
```

> **Why there is no `--commit` filter here, unlike Path B.** A `pull_request` run is not keyed to the
> merge commit the queue produced, so filtering on `origin/main` would select nothing and the empty
> result would read exactly like a run that never started. The binding here is the high-water mark
> plus the `pull_request` event; the `gh run view` line is what turns that into an identification —
> confirm it names the version-bump PR before watching it.

**Now READ THE RUN. Do not infer the outcome from the merge having happened** — see *The guard has
fired once and has never been observed refusing* above. Exactly one merge-triggered cut (`0.1.1`) has
been read off a run, so a firing guard is a confirmation of that single precedent; a *refusing* guard
would still be a first observation:

| what the run shows | what decided it | published? |
|---|---|---|
| No `Release` run at all | The trigger did not match: the merge touched no `.github/project.yml` path, or the PR's base was not `main`. The `paths:`/`branches:` prefilter, not the guard | nothing |
| Run exists, `release` job **skipped** | The caller-side `merged == true` gate — the PR was closed **without** merging | nothing |
| Run exists, `release` ran, `publish-image` **skipped** on `released-version != ''` | **The central guard refused**: `release.current-version` did not change between the merge commit and its first parent, **or** a tag for it already exists. Both are correct refusals | nothing |
| `release` succeeded and `publish-image` is running | The guard proceeded — **the release is being cut** | jars are, or shortly will be, irrevocable — go to Step 6 |

**Tell a correct refusal apart from a broken guard BEFORE reaching for a dispatch.** A dispatch on
top of a refusal you have not diagnosed publishes while the defect goes unreported:

```bash
git fetch origin main
MERGE_SHA=$(git rev-parse origin/main)   # the commit the merge produced
git show "${MERGE_SHA}:.github/project.yml"  | sed -n '/^release:/,/^$/p'
git show "${MERGE_SHA}^:.github/project.yml" | sed -n '/^release:/,/^$/p'
git ls-remote --refs --tags origin 'refs/tags/<version>'
```

- `current-version` **differs** between the merge commit and its first parent **and** no tag for it
  exists → the guard **should** have proceeded. **That is a broken guard. Stop.** Report it against
  `cuioss-organization`; do not substitute a local decision and do not paper over it with a dispatch.
- Otherwise the refusal was correct. Decide deliberately — with the user — whether the situation
  genuinely calls for Path B, and remember Path B is unconditional: it will publish whatever
  `current-version` currently declares.

#### Path B — dispatch, deliberately

The fallback: recover a lane that failed after a correct guard decision, or cut a version whose
declaration already landed on `main`. The `release` job's `if:` confines a dispatch to
`refs/heads/main` — a dispatch from any other ref is skipped, silently. Past that ref test
**nothing central refuses on this path**: the operator is the only remaining gate, which is why
Steps 2–4 are not optional here either.

**Record the pre-dispatch high-water mark first.** Run ids increase monotonically, so the newest
existing `Release` run id is what lets the next step tell *your* dispatch apart from one that was
already in flight:

```bash
PREV_RUN_ID=$(gh run list --repo cuioss/API-Sheriff --workflow "Release" --limit 1 \
  --json databaseId --jq 'first | .databaseId // 0')
echo "$PREV_RUN_ID"
```

**Then re-assert that `main` has not moved, and dispatch in the same block.** The dispatch builds
whatever `main` points at *at dispatch time* — not the SHA Step 4 proved green. Between the Step 4
gate and this command, a merge-queue landing can move `main`, and the release would then publish an
unverified commit:

```bash
MAIN_SHA=<the SHA Step 4 echoed>   # re-declare it: this block is its own shell

git fetch origin main \
  || { echo "ERROR: could not re-read origin/main - the drift check did not evaluate" >&2; exit 1; }
test "$(git rev-parse origin/main)" = "$MAIN_SHA" \
  || { echo "STOP: origin/main moved since the Step 4 gate - re-run Steps 3(iii), 4 and 5" >&2; exit 1; }

gh workflow run "Release" --repo cuioss/API-Sheriff --ref main
```

(Equivalently, in the UI: **Actions → Release → Run workflow** — but the UI has no drift check, so
run the two guarded commands above first and dispatch immediately after.)

> **Why `--ref main` and not `--ref "$MAIN_SHA"`.** Dispatching at the gated SHA looks like the
> tighter fix, and it is the wrong one here — for three independent reasons:
> 1. **The signature identity is bound to the ref, and the accepted set is a single value.** Step 8
>    verifies the Cosign signature against a pattern accepting exactly one ref, `refs/heads/main`.
>    That value is the OIDC `job_workflow_ref` of the `publish-image` job, so it changes with the
>    dispatch ref. A dispatch at a SHA does not match that pattern, so it produces a certificate this
>    runbook's own verification would reject.
> 2. **The release force-pushes to a branch.** `maven-release-plugin` commits the version transition
>    and the workflow force-pushes it to `main`; a dispatch at a detached SHA has no branch to push.
> 3. **`ref` is documented as a branch or tag name.** The workflow-dispatch API documents exactly
>    that, and SHA acceptance is at best undocumented behaviour. The single irreversible act in this
>    repository is the last place to depend on it.
>
> So the window is narrowed the other way: by **re-asserting the SHA immediately before the dispatch
> and aborting non-zero on drift**, which is what the block above does. It is narrowed, not closed —
> the residual is the few milliseconds between the `test` and the API call. The next step is what
> catches that residual: the `--commit "$MAIN_SHA"` filter means a run built at a drifted commit
> yields no match and stops the procedure.

**The dispatch publishes whatever `.github/project.yml` declares at `$MAIN_SHA`** — nothing central
will refuse it, including a re-release of an unchanged version. Step 3(iv) is the only thing standing
between that and a moved release tag.

Capture the run — **bound to this dispatch, and failing closed when the match is not unique**:

```bash
MAIN_SHA=<the SHA Step 4 echoed>            # re-declare both: this block is its own shell
PREV_RUN_ID=<the id the block above echoed>

RUN_ID=$(gh run list --repo cuioss/API-Sheriff --workflow "Release" \
  --event workflow_dispatch --commit "$MAIN_SHA" --limit 20 \
  --json databaseId --jq "[.[] | select(.databaseId > ${PREV_RUN_ID}) | .databaseId] | \
    if length == 1 then .[0] else empty end")
test -n "$RUN_ID" \
  || { echo "STOP: no unique new Release run for $MAIN_SHA - do NOT proceed" >&2; exit 1; }
echo "$RUN_ID"
```

> **Why the binding and the emptiness check are both load-bearing.** Selecting merely the *first*
> queued-or-in-progress `Release` run adopts whatever run happens to be active — an older release
> still finishing, or a colleague's dispatch — and then watches it as though it were yours. And an
> empty selection is worse than a wrong one: `gh run watch "$RUN_ID"` with `RUN_ID` unset does not
> fail cleanly, so a silent miss becomes a confident watch of the wrong thing. Requiring **exactly
> one** run that is newer than the high-water mark, on the `workflow_dispatch` event, at the SHA
> Step 4 gated on, is what makes the identification provable. **The check exits non-zero, so the
> block stops there** — find the run in the Actions UI and confirm which dispatch it belongs to
> before watching anything. A brand-new run can take a moment to appear; re-run the selection once
> before treating an empty result as a real miss.
>
> **An empty selection has a second meaning, and it is the serious one.** Because the filter is
> `--commit "$MAIN_SHA"`, a run that exists but was built at a *different* commit does not match
> either. That is the residual of the pre-dispatch drift check above: it means `main` moved inside
> the last few milliseconds and the release is publishing an unverified commit. Check the Actions UI
> for a `Release` run newer than `$PREV_RUN_ID` at *any* commit before concluding the dispatch
> simply has not appeared yet — and if one exists at another SHA, treat it as an in-flight
> unverified release and cancel it immediately.

### Step 6 — Hold the quiescence window

**From the cut until the run completes, nothing merges to `main`** — on Path A that window opens when
the version-bump PR is **enqueued**, not when the run starts, because the queue can land another
entry behind it while the release is still running. This is point 3 of the (iii) mitigation and is
not optional: the release force-pushes to `main` twice. **It is also the half with no mechanism
behind it:** the pre-cut mechanisms of (iii)-2 cover only the window before the cut, and nothing in
the repository rejects a merge landing inside this one. Holding it is on you.

### Step 7 — Wait for the run

```bash
RUN_ID=<the id Step 5 echoed>   # re-declare it: this block is its own shell

test -n "$RUN_ID" || { echo "STOP: RUN_ID is empty - gh run watch would not fail cleanly" >&2; exit 1; }
gh run watch "$RUN_ID" --repo cuioss/API-Sheriff
```

Two legs, and they fail differently:

- The **`release` job** publishes to Maven Central. Once it is green, **the jars are irrevocable.**
- The **`publish-image` job** (`timeout-minutes: 90`) runs the integration-test suite, which
  performs a **GraalVM native compile** — the dominant and most variable term. A long wait here is
  the native compile or Maven Central propagation, not a hang.

**If `publish-image` fails after `release` succeeded**, you have a partial release: go to
*If the image lane fails after the Maven release* above and **classify the failure before touching
anything** — a re-run replays the original inputs and only helps a transient failure. **Do not
re-run the whole workflow.**

### Step 8 — Verify that EXACTLY ONE release fired

> **"It worked" is not the check. "Exactly one of each" is.** A `force: true` tag push and a
> re-runnable workflow both make "more than one" a real failure mode, and a duplicate is far harder
> to see than an absence.

Expected artifact set at `<version>`:

**1 — one git tag** (bare, no prefix; `cui-parent-pom` sets `<tagNameFormat>@{project.version}</tagNameFormat>`):

```bash
git fetch --tags --force \
  || { echo "ERROR: git fetch --tags failed - the counts below would read a stale view" >&2; exit 1; }
test "$(git tag --list '<version>' | wc -l)" -eq 1 \
  || { echo "STOP: expected exactly one LOCAL tag <version>" >&2; exit 1; }
test "$(git ls-remote --refs --tags origin 'refs/tags/<version>' | wc -l)" -eq 1 \
  || { echo "STOP: expected exactly one REMOTE tag <version>" >&2; exit 1; }
echo "OK: exactly one tag <version>, local and remote"
```

> **`| grep -w '<version>'` would be the wrong check here** — for the reasons Step 3(iv) already
> gives: `.` is a regex any-character and `-w` treats `-` as a word boundary, so `-w '0.1.0'` also
> matches `0.1.0-rc1`. The exact `refs/tags/<version>` pattern plus `--refs` (which drops the
> `^{}` peeled ref an annotated tag also publishes) counts one ref and nothing else.

**2 — one GitHub release:**

```bash
gh release view '<version>' --repo cuioss/API-Sheriff --json tagName,name,createdAt,url
gh release list --repo cuioss/API-Sheriff --limit 10
```

**3 — one Maven Central deployment** of `de.cuioss.sheriff.gateway:*:<version>`. Propagation lags
the run; allow time before treating an absence as a failure:

⛔ **A `404` here is lag, not "unpublished" — and the release log will tempt you to read it the other way.**
The effective release profile configures `central-publishing-maven-plugin` with `autoPublish=true` and
`waitUntil=validated`, so the `release` job returns as soon as the deployment is validated and the
Portal then publishes it on its own. The plugin nevertheless prints *"Deployment `<uuid>` has been
validated. To finish publishing visit https://central.sonatype.com/publishing/deployments"* — that line
is **boilerplate emitted at the validated state regardless of `autoPublish`**. The line that states what
actually happens is the one **before** it: *"Uploaded bundle successfully … Deployment will publish
automatically"*.

At the 0.2.0 cut that boilerplate was misread as "validated, awaiting a manual Publish, still
droppable", and on that premise the `0.2.0` tag and GitHub release were deleted — while the Portal was
already publishing. Both had to be restored. The measured lag was ~38 minutes (validated `08:21:44`,
`maven-metadata.xml` `lastUpdated` `08:59:28`). **A green `release` job means the jars are irrevocable,
exactly as *The release is NOT atomic* says. Never act on "it is still droppable" without reading the
deployment's state in the Portal itself.**

```bash
curl -sSf "https://repo1.maven.org/maven2/de/cuioss/sheriff/gateway/api-sheriff/<version>/" > /dev/null \
  && echo "present on Central" || echo "not yet propagated"
```

**4 — one container image, at the matching version.** Precisely: **one manifest digest carrying two
tags.**

Resolve **both** tags to a manifest digest and compare them. `imagetools inspect` answers from the
registry rather than the local cache, so the comparison is about what was published, not about what
this machine happens to hold:

```bash
IMAGE=ghcr.io/cuioss/api-sheriff
TAG_SHA=sha-<40-char release-tag commit SHA>

VERSION_DIGEST=$(docker buildx imagetools inspect "$IMAGE:<version>" --format '{{.Manifest.Digest}}')
SHA_DIGEST=$(docker buildx imagetools inspect "$IMAGE:$TAG_SHA" --format '{{.Manifest.Digest}}')
echo "version tag -> $VERSION_DIGEST"
echo "sha tag     -> $SHA_DIGEST"

if [ -n "$VERSION_DIGEST" ] && [ "$VERSION_DIGEST" = "$SHA_DIGEST" ]; then
  echo "OK: one digest, two tags"
else
  echo "STOP: the tags disagree, or a digest did not resolve - this release is NOT verified" >&2
  exit 1
fi
```

**An empty digest is a failed check, not a passed one** — hence the `-n` guard: an unauthenticated
pull of a still-private *or still-internal* package (Step 9) returns nothing, and comparing two
empty strings would otherwise report success.

Then confirm the version label on the resolved digest:

```bash
docker pull "$IMAGE@$VERSION_DIGEST"
docker image inspect "$IMAGE@$VERSION_DIGEST" \
  --format '{{index .Config.Labels "org.opencontainers.image.version"}}'
```

The label must equal `<version>`. The workflow already asserts the same-digest property in its
`Resolve the pushed manifest digest` step; the commands above are the **independent** confirmation,
which is the whole point of this step — a check that only re-reads the workflow's own claim confirms
nothing.

Verify the Cosign signature **against that resolved digest**, using **exactly** these two identity
values. They are derived from the `publish-image` job's OIDC identity, which embeds the ref the run
was triggered from — and every release this project has cut embedded the **same** one,
`refs/heads/main`:

```bash
cosign verify "$IMAGE@$VERSION_DIGEST" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity-regexp '^https://github\.com/cuioss/API-Sheriff/\.github/workflows/release\.yml@refs/heads/main$'
```

**Both release paths sign under `refs/heads/main`, and that is read off the certificates rather than
inferred from the trigger config.** 0.1.0 (`github_workflow_trigger = workflow_dispatch`) and 0.1.1
(`github_workflow_trigger = pull_request`) both carry
`job_workflow_ref = .../workflows/release.yml@refs/heads/main`. A pull request closed *by being
merged* runs against the branch it was merged into, so the merge path never produces a merge-ref
identity. The value is a *regexp* only because the identity string contains dots that must be
escaped; it stays anchored at both ends and bound to this repository and this workflow file — **do
not** relax it to a wildcard. The workflow runs this same command against every digest it signs, so a
failure here is a real supply-chain signal, not a stale expectation.

**Four sites in three files carry this pattern** — `.github/workflows/release.yml` **twice** (the
`A CONSUMER VERIFIES WITH EXACTLY THESE TWO VALUES` comment block and the `publish-image` verify
step's `IDENTITY_REGEXP` env value), `doc/user/container-image.adoc` (the operator-facing command),
and this runbook. Nothing mechanical keeps them in sync; changing one means changing all four.

> **Ignore the benchmark run this triggers.** `.github/workflows/benchmark.yml` fires on
> `push: tags: ["*"]`, so the release's tag push **starts a benchmark run**. That run belongs to the
> release, not to a code change, and **its lane is known-flaky**. Do **not** read it as a pass or a
> failure of the released code, and do not block the release on it.

### Step 9 — MANDATORY post-release action after the FIRST release

**Set the GHCR `api-sheriff` package to *public*.**

Organisation package settings → `api-sheriff` → *Change visibility* → **Public**.

This is a GitHub UI action; it cannot be done from the repository, and the in-workflow smoke step
**cannot detect that it is missing** because it pulls with the job's own credentials.

> **The `0.1.0` cut IS the first release, so this section is live, not hypothetical.** Until it is
> done, every check stays green while `docker pull` fails for everyone outside the organisation.

> **PICK *PUBLIC*, NOT *INTERNAL* — the dialog offers all three and `Internal` is the trap.** On the
> 0.1.0 cut the first attempt landed on `Internal`, which reads as "not private, job done" and is
> not. Internal grants every `cuioss` member a pull, so **an org member's `docker pull` succeeds,
> `imagetools inspect` resolves, and `cosign verify` passes** — while anonymous consumers still get
> `401`. Every authenticated check an operator is likely to reach for confirms the wrong thing.
> Internal is also the default landing spot for org-owned packages under some enterprise settings,
> so it is easy to select without noticing.

Confirm afterwards. **Assert the field, then prove the anonymous path — both, in this order.** The
API check names the exact failure (`internal` vs `public`); the anonymous pull is what actually
proves an outside consumer can get the image:

```bash
# 1. ASSERT THE FIELD. Requires a token with read:packages
#    (`gh auth refresh -h github.com -s read:packages` if yours lacks it).
VIS=$(gh api /orgs/cuioss/packages/container/api-sheriff --jq .visibility)
test "$VIS" = "public" \
  || { echo "STOP: package visibility is '${VIS}', not 'public' - outside consumers cannot pull" >&2; exit 1; }
echo "OK: package visibility is public"

# 2. PROVE THE ANONYMOUS PATH. `docker logout` first, or a cached credential
#    silently turns this into an authenticated check that passes while internal.
docker logout ghcr.io
docker pull ghcr.io/cuioss/api-sheriff:<version>
```

> **A still-`401` anonymous probe right after the change is usually `internal`, not propagation
> lag.** The visibility switch takes effect immediately. Re-read the `visibility` field before
> waiting on a delay that is not happening — on the 0.1.0 cut, ten polls over ~150 s all returned
> `401` and the field said `internal` the whole time. A quick unauthenticated corroboration:
>
> ```bash
> curl -sS -o /dev/null -w "%{http_code}\n" \
>   "https://ghcr.io/token?scope=repository%3Acuioss%2Fapi-sheriff%3Apull&service=ghcr.io"
> ```
>
> `200` means public; `401` means it is not, whatever the settings page appears to show.

### Step 10 — Update the version-bearing content

**Every file that names a concrete version must name the version just released — and every note
written as *"until `$RELEASE_VERSION` ships"* must be deleted, not left to be puzzled over.** These
are the files a new user copy-pastes first, so a stale pin here is the most visible possible defect:
it sends them to an image that either does not exist or is not the release they think they are
running.

**This step runs AFTER the image is verified (Step 8) and public (Step 9), never before.** Pointing
an example at a version that has not finished publishing is the same defect aimed forward instead of
backward. ⛔ **That ordering is not a preference — `deployment/compose-sample/.env` states the rule
about itself: it names an image that exists.** Bumping it inside the version-bump PR would make the
sample name an unpublished tag for the whole window between merge and publish.

#### 10a — Enumerate, in TWO passes, because one grep cannot see both classes

**Pass 1 — image pins.** Catches every literal `ghcr.io` coordinate:

```bash
git grep -n -e 'ghcr\.io/cuioss/api-sheriff:[0-9]'
```

**Pass 2 — version-stamped prose.** The maturity callouts, the verification stamps and the
*"until X ships"* notes carry the version as ordinary text, so pass 1 is structurally blind to them:

```bash
git grep -n "$PREV_VERSION"
```

**Pass 3 — stale stamps, keyed on the PHRASE and not on any version.** ⛔ **Passes 1 and 2 are both
keyed to `$PREV_VERSION`, so neither can see a document that ALREADY fell behind**: once a file
misses one re-stamp it names an older version and becomes permanently invisible to a
`$PREV_VERSION` grep. Sweep the stamp wording itself:

```bash
git grep -n -i -e 'is an ALPHA release' -e 'as of the .* alpha' -e 'at the .* cut' -e 'as of the [0-9]'
```

⚠ **This pass is not hypothetical — it is the one that found a live miss.** At the `0.2.0` cut,
`doc/fapi_status.adoc:8`, `doc/fapi_next_steps.adoc:7` and `doc/features-analysis.adoc:220` were all
still stamped *"the 0.1.0 alpha"* **through the entire 0.1.1 release**, and the repo's own audit had
already recorded it as finding `DOC-8`. A `$PREV_VERSION` grep at the 0.2.0 cut would have looked
for `0.1.1` and walked straight past all three. **Run pass 3 every time.**

⛔ **Pass 2 and pass 3 return BOTH classes of hit, and they are opposites. Partition every hit before editing
anything:**

| class | what to do | why |
|---|---|---|
| **Release-coupled** — a claim about *the current release* | **update to `$RELEASE_VERSION`** | it is asserting something about the cut that just happened |
| **Historical record** — a claim about *what a past version shipped* | ⛔ **NEVER touch** | bumping it rewrites a record of a past fact into a false claim about the present |

**The NEVER-touch set, with the reason each one is in it** — re-derive it, but start here:

- `doc/adr/**` — ADRs record what a named version *actually shipped* (`0005` *"as 0.1.0 actually
  ships it"*, `0015` *"not wired at 0.1.0"*, `0035`'s signing evidence). **An ADR is immutable.**
- `.github/workflows/release.yml` — the `0.1.0` / `0.1.1` comments are *signing evidence* proving
  which trigger produced which certificate. Bumping them destroys the evidence.
- `doc/development/release-process.adoc` — cites the `0.1.1` cut as a worked historical example.
- `doc/quality-report/**` — dated audit reports. They *describe* stale stamps (`DOC-8` is literally
  titled *"FAPI status not re-stamped for 0.1.1"*); rewriting the finding would erase the record of
  the defect instead of fixing it. **Fix what the finding points at, never the finding.**
- `doc/user/container-image.adoc` — carries `0.1.1` as *signing evidence* (*"the 0.1.1 case is the
  one that settles it"*), in addition to its `<version>` placeholders. Both reasons to leave it alone.
- Anything that is not a version at all. ⚠ `git grep "0.1.1"` also matches the CIDR `10.1.0.0/16` in
  `ConfigValidatorTest` and `"regexp-tree": "~0.1.1"` in `demo-client/package-lock.json`.
  **Read each hit; do not stream-edit the grep output.**

#### 10b — The release-coupled set

Re-derive it with 10a rather than trusting this table — but it is exhaustive as of the `0.1.1` cut,
and a hit here that 10a does not also produce means the enumeration has regressed:

| file | what carries the version | found by |
|---|---|---|
| `deployment/compose-sample/.env` | `API_SHERIFF_IMAGE=ghcr.io/cuioss/api-sheriff:<version>` — the sample's single image pin | pass 1 |
| `doc/user/compose-sample.adoc` | the `docker pull ghcr.io/cuioss/api-sheriff:<version>` in *Route A* | pass 1 |
| `README.adoc` | the **ALPHA maturity callout** (*"X is an ALPHA release"*) | pass 2 / 3 |
| `README.adoc` | the **Known Limitations preamble** (*"Verified as still open at the X cut"*) — see 10c | pass 2 / 3 |
| `doc/README.adoc` | the same ALPHA callout, stated for the doc layer | pass 2 / 3 |
| `doc/user/README.adoc` | the same ALPHA callout again — ⛔ **there are THREE, not two** | pass 2 / 3 |
| `doc/fapi_status.adoc`, `doc/fapi_next_steps.adoc`, `doc/features-analysis.adoc` | *"as of the X alpha"* — ⛔ **stale since 0.1.0, see pass 3** | pass 3 ONLY |
| `deployment/compose-sample/docker-compose.yml` | the **`VERSION SKEW, until X ships` comment block** — see 10d | pass 2 |

> **Do NOT "fix" `doc/user/container-image.adoc`.** It uses a literal `<version>` placeholder
> throughout, deliberately — it is the reference layer and is written to stay true across releases.
> Substituting a concrete version there would make it wrong at the *next* cut. A placeholder is not
> a stale pin; leave it alone.

#### 10c — `README.adoc` Known Limitations: RE-VERIFY the content, not just the stamp

⛔ **The preamble says *"Verified as still open at the `$PREV_VERSION` cut"*, and
`doc/README.adoc` plus `doc/user/README.adoc` both cite that section as "the verified limitations at
this cut". It is therefore a claim of verification, and re-stamping it without re-checking the
entries makes the claim false in the most damaging possible way — it asserts an audit nobody ran.**

**Walk every entry and confirm it is still true at `$RELEASE_VERSION`.** Delete what the cycle
fixed; add what it introduced. **This is not optional polish** — it is the one section of the README
whose correctness the release explicitly vouches for.

⚠ **Precedent from the 0.2.0 cycle, kept because it is the shape this fails in:** two entries went
false *during* the cycle and neither was noticed until a pre-cut review. The
`token-sheriff 0.9.5-SNAPSHOT` limitation was resolved by an out-of-band dependency PR, and *"cookie
mode and refresh do not work together"* was superseded by the cookie-packaging work — **both were
fixed by merges that never looked at the README.** Expect that: a limitation is retired by whoever
fixes the underlying gap, not by whoever wrote the entry.

#### 10d — `deployment/compose-sample/docker-compose.yml`: retire the VERSION SKEW block

✅ **The compose files carry no image pin to bump, by design.** `docker-compose.yml` declares
`image: ${API_SHERIFF_IMAGE}` with **no inline `:-default`**, so the pin lives once in `.env` and the
compose file derives it. `docker-compose.plain-http.yml` introduces no gateway image at all (it adds
only the nginx TLS-terminating hop), and every `integration-tests/docker-compose*.yml` uses the
locally built `api-sheriff:distroless` tag, which is never a released coordinate. **None of them is
release-coupled for the image.**

⛔ **What IS release-coupled there is PROSE.** `docker-compose.yml` carries a comment block opening
*"VERSION SKEW, until 0.2.0 ships"* that explains why the pinned default cannot boot the sample and
tells the reader to override with a locally built image. **The moment `$RELEASE_VERSION` is published
and `.env` is bumped, that entire block is false** — it describes a skew that no longer exists and
sends readers to a workaround they no longer need.

**Delete the block in the same change that bumps the pin.** The two are one act: the block exists
*because* the pin lagged, so retiring one without the other leaves the sample self-contradictory.

⚠ **A block of this shape is self-identifying — grep for its own expiry:**

```bash
git grep -n -i -e 'until .* ships' -e 'not published yet' -e 'PREDATES'
```

**Never leave an example carrying a caveat the release has overtaken.** The `0.1.0` cut shipped
`deployment/compose-sample/.env` still reading *"0.1.0 is not published yet"* — accurate when
written, false the moment the release landed, and contradicted by the pin on the very next line.

#### 10e — Report what you checked, including the nothing-to-do case

If the version-bearing files are already correct — the common case, since they are usually written
during the cycle leading up to the cut — say so explicitly in the Step 12 report rather than silently
skipping the step. **"Checked, already correct" and "forgot to check" must not look alike.** Report
the counts from both 10a passes, so a zero states which zero it is.

#### `build-parent/example/pom.xml` — the one pin the release moves *past* rather than *to*

**This one is not in the table above, and it is not optional.** Every other file there names the
version just *released*; this one must name the trunk's new *SNAPSHOT*, because it is a `<parent>`
pin on this repository's own build parent. Set it to whatever `pom.xml`'s `<version>` says on `main`
after the release force-pushed its version bump:

```bash
git fetch origin main && git checkout main && git pull
python3 -c "import xml.etree.ElementTree as ET; ns={'m':'http://maven.apache.org/POM/4.0.0'}; r=ET.parse('pom.xml').getroot().find('m:version',ns).text.strip(); e=ET.parse('build-parent/example/pom.xml').getroot().find('m:parent/m:version',ns).text.strip(); print('reactor=%s example=%s' % (r, e)); raise SystemExit(0 if r == e else 1)"
```

Non-zero means the example lags. Edit its `<parent><version>` to the reactor value, re-run the check,
and ship it in the same PR as the rest of this step.

**Why no tooling does this for you.** `build-parent/example/pom.xml` is deliberately *not* a reactor
module — it is absent from the root `<modules>` so a native build never enters the default lane — so
`release:prepare` inside the pinned `reusable-maven-release.yml` rewrites every reactor POM's version
and walks straight past this one. This repository configures no `versions-maven-plugin` or
`maven-release-plugin` of its own, so nothing else touches it either. The pin is hand-maintained, and
this step is the hand.

**What fails when it is missed.** `BuildParentContractTest.adoptingAReleaseIsAParentVersionBump`
asserts the example's parent version equals the reactor root version, so the first build of `main`
after the cut goes red. Fix the POM; **do not widen that assertion to tolerate a lagging version** —
its own failure message argues against exactly that, and the guard is the only reason a missed bump
is visible at all.

Do **not** solve this by adding the example to `<modules>`. Its header records why it is outside the
reactor, and that reasoning is unchanged by the release cadence.

### Step 11 — Reformat the generated release notes

The release is created with **auto-generated** notes (a flat `## What's Changed` list). Rewrite them
in place using the house format below.

```bash
mkdir -p .plan/temp
gh release view '<version>' --repo cuioss/API-Sheriff --json body --jq .body > .plan/temp/release-<version>-orig.md
# ...build the reformatted body in .plan/temp/release-<version>.md...
gh release edit '<version>' --repo cuioss/API-Sheriff --notes-file .plan/temp/release-<version>.md
```

**Cross-check coverage BEFORE editing the release.** Extract the `pull/<n>` numbers from both files
and confirm that every original PR is either kept, collapsed into a chain, or intentionally dropped,
and that **no PR appears in the new file that was not in the original**.

> **THE INITIAL RELEASE IS THE ONE EXCEPTION — it gets no changelog at all.** The house format below
> describes what changed *since the previous release*, which for the first cut is meaningless:
> everything is new, so a "changelog" is just the build history. The 0.1.0 notes were deliberately
> rewritten as a short statement of what the project is, what ALPHA means for surface stability, how
> to get the artifact, and where the docs are — the auto-generated 152-PR list was dropped whole.
> Apply the per-theme rules below to **every subsequent** release; do not reconstruct a PR list for a
> first release just because the generator emitted one.

#### House format rules (apply exactly)

1. **Three top-level groups, in this order:** `## Quarkus`,
   `## Features & Enhancements`, and `## Dependency Updates`.
2. **Quarkus is the headline** — the Quarkus platform version is the single most
   important fact in a release, so it gets its **own top-level section at the very top**,
   never a bullet buried under dependency updates. Open it with a one-line statement of the
   target version, then the PR line(s):

   ```
   ## Quarkus

   This release targets **Quarkus <new>** (previously <old>).

   * <PR line(s)>
   ```

   If Quarkus did **not** change in this cycle, state the unchanged version in the same
   one-line form and omit the PR line. Never also list Quarkus under `### Java`.
3. **Features & Enhancements** — group functional PRs by theme with `###` subheadings:
   - `### API & Code Quality` — also the home for refactor/standards/cleanup recipes, **not** under
     build/tooling
   - `### Security`
   - `### Testing & Standards`
   - `### Documentation`
   - `### Build & CI` — manually-authored CI/build improvements; **not** mechanical dependency bumps

   Add release-specific themes when the cycle has a dominant thread. Adapt headings to the actual
   PRs; omit empty sections.
4. **Dependency Updates** — group by type with `###` subheadings:
   - `### Java` — Java libraries (cui-*, …). **Not** Quarkus — that has its own top
     section (rule 2).
   - `### Infra` — platform/build/CI: build plugins, GitHub Action bumps (harden-runner,
     `actions/*`, claude-code-action), `cui-java-parent`, and cuioss-organization workflow bumps
5. **Collapse by library identity — one line per library, spanning the full range.**
   The unit of collapsing is the *library*, not the PR title. Merge into a single line
   whenever the PRs concern the same library, in all three shapes that occur:
   - **Version chains** — several bumps of one artifact (`A → B → C`) collapse to one line
     spanning `A → C`, carrying the latest PR's author.
   - **The same library in several places** — one library bumped in more than one module or
     directory is **one** line naming them all, not one line each. Those titles differ only
     by that suffix, so do not wait for identical titles before merging.
   - **One upstream release landing as several coordinates** — when a single upstream bump
     arrives as separate PRs against different coordinates (e.g. a version property *and*
     a BOM or parent), that is **one** bump: one line naming the coordinates in parentheses.

   Carry every merged PR's URL onto the surviving line, comma-separated.
6. **Recover versions the title omits.** Dependabot truncates a title to
   `bump <lib> in /<dir>`, with no versions, when several dependencies must move together.
   Never publish a dependency line without a version range: read the PR body, which states
   ``Updates `<lib>` from X to Y``, and use those versions when computing the range:

   ```bash
   gh pr view <n> --repo cuioss/API-Sheriff --json body --jq .body | head -6
   ```
7. **Remove all OpenRewrite bumps and friends** — drop every `rewrite-maven-plugin`,
   `rewrite-migrate-java`, `rewrite-testing-frameworks` and related PR.
8. **Remove internal tooling churn** — drop PRs that only touch dev/build orchestration with no
   user-facing effect: `marshal.json` / plan-marshall config migrations, plan-marshall build wiring,
   internal dev-skill changes, and **the mechanical version-declaration PR itself**.
9. **Preserve each kept PR line** in its original
   `* <title> by @author in <url>` shape. Rules 5 and 6 **override** verbatimness where
   they conflict: rewrite the title's version range to span the collapsed chain, and name
   the several modules or coordinates on the surviving line.
10. **Keep the trailing `**Full Changelog**: ...compare/<prev>...<version>` line.**

#### Verify before publishing (mandatory)

These rules are easy to under-apply: a duplicate survives whenever two PRs touch the same
library under differing titles. After building the notes file and **before**
`gh release edit`, assert that every library appears exactly once:

```bash
grep -oE '(bump|update) [^ ]+ (from|in)' .plan/temp/release-<version>.md \
  | sort | uniq -c | sort -rn | head
```

Every count must be `1`. Any count `>1` is an unmerged duplicate — collapse it per rule
5 and re-run. Also confirm that no dependency line is missing a version range
(rule 6).


### Step 12 — Report

Report: the released version; **which path cut it — Path A (version-bump merge) or Path B
(dispatch)**, and on Path A **what the run actually showed** for the `release` and `publish-image`
jobs rather than that the merge happened; the SHA recorded in Step 3(i); the release URL; the resolved image
digest; confirmation that **exactly one** of each expected artifact exists; the GHCR package's
observed `visibility` value (Step 9) — report the field verbatim, `public` / `internal` / `private`,
never a bare "done", since `internal` is exactly the outcome that reads as done and is not; which
version-bearing examples (Step 10) were updated, or that they were **checked and already correct**;
the `reactor=… example=…` line from the `build-parent/example/pom.xml` parity check, so the bump is
reported as a *value*, not as a tick; and how many dependency PRs were collapsed or removed while
reformatting the notes.

State plainly which image checks were made **anonymously** and which were authenticated. An
authenticated check is not evidence about outside consumers, and on an `internal` package every
authenticated check passes.

---

## Critical rules

- **There are exactly two release paths: the merge of a `.github/project.yml` version bump, and
  `workflow_dispatch`.** The `pull_request` trigger is permitted **only** in its guarded form —
  `types: [closed]` + `branches: [main]` + `paths: ['.github/project.yml']`, ANDed with the
  `merged == true` job gate and with the **centrally-owned** version-changed guard consumed through
  `needs.release.outputs.released-version`. **Never re-implement that guard locally**; a missing or
  broken central guard is a block to report, never one to substitute.
- **On Path A the merge IS the release**, so every pre-cut assertion (Steps 2–4) runs **before** it.
  Never merge a version bump and then run the safety work.
- **A merge that cut no release is a verdict to read, not a reason to dispatch.** Diagnose which
  mechanism refused — prefilter, `merged == true`, or the central guard — before reaching for Path B.
  A dispatch from `main` faces no central refusal and will publish over an undiagnosed one.
- **Re-assert items (i), (iii) and (iv) at cut time.** They are time-varying and are never inherited
  from a recorded baseline.
- **Dispatch only after re-reading `origin/main`.** Step 5 Path B aborts non-zero unless it still
  equals the `$MAIN_SHA` Step 4 gated on — the dispatch builds `main` as it is *then*, not as it was
  gated.
- **Nothing merges to `main` between the cut and run completion** — the release force-pushes to
  `main` twice as a queue bypass actor. On Path A that window opens at **enqueue**, not at run start.
  This one is unenforced: it is an operator obligation, not a mechanism.
- **Set the GHCR package to *public*, not *internal*, and assert the `visibility` field** rather than
  inferring it from a pull. Internal passes every authenticated check while anonymous pulls 401.
- **Update the version-bearing examples (Step 10) after the image is public**, and delete any caveat
  the release has overtaken. `doc/user/container-image.adoc` is exempt — its `<version>` is a
  deliberate placeholder, not a stale pin.
- **Bump `build-parent/example/pom.xml`'s `<parent><version>` to the trunk's new SNAPSHOT (Step 10).**
  It is not a reactor module, so `release:prepare` walks past it and no plugin in this repository
  updates it. `BuildParentContractTest.adoptingAReleaseIsAParentVersionBump` turns `main` red on the
  first build after a missed bump — fix the POM, never the assertion.
- **The release is not atomic.** A green `release` job means the jars are already irrevocable. On an
  image-lane failure, never re-run the whole workflow — and re-run **`publish-image` alone** only
  for a *transient* failure. A re-run replays the original event context and checks out the release
  tag, so it **cannot** pick up a fix merged to `main`; a cause carried in the tree or the workflow
  needs a new patch version instead.
- **Never relax the Trivy `severity` or flip `exit-code` to `'0'`** to get a release out. Use a
  documented `.trivyignore` entry, or re-pin the base image.
- **Verify "exactly one of each", not "it worked"** — one tag, one GitHub release, one Central
  deployment, one image digest (carrying two tags) at the matching version.
- **`main` is merge-queue gated**, so any `gh pr merge` **enqueues** rather than completing. **Never
  pass `--delete-branch`** — it destroys the queued entry. Poll `origin/main` (or the PR `state`)
  rather than trusting the merge command's immediate output.
- **Never cut on a red `main`**, and never merge a red PR. On Path A gate **both** the base
  `$MAIN_SHA` and the version-bump PR's own checks — neither substitutes for the other.
- **Ignore the tag-triggered `benchmark.yml` run** — it fires on `push: tags: ["*"]`, belongs to the
  release rather than to a code change, and its lane is known-flaky.
- **Always pass `--repo cuioss/API-Sheriff`** to `gh`.
- **Temporary files go under `.plan/temp/`.**

## See also

- `doc/development/release-process.adoc` — the canonical process narrative, including the two release
  paths, the guard's role, and the rule that *a change which removes or weakens an event-driven
  trigger merges on its own*. **This runbook — not `release-process.adoc` — carries the surviving
  narrative account of the 2026-07-12 incident**; see *How the release is wired* above, and ADR-0035
  for the decision record. **ADR-0034 is superseded by ADR-0035**, which records the
  guarded-merge-or-dispatch decision and which `release.yml`'s header references.
- `.github/workflows/release.yml` — the release workflow, and the authoritative statement of both
  triggers. Its in-file comments carry the guard rationale, the run/skip-vs-value scope split, and
  the composability, ordering and attestation caveats behind the steps above.
- `doc/user/container-image.adoc` — the operator layer: pulling, running and verifying the image.
