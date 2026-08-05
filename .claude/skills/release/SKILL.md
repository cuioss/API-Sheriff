---
name: release
description: Cut an API Sheriff release by deliberately dispatching the Release workflow (workflow_dispatch only — merging a .github/project.yml change does NOT and MUST NOT fire a release). Covers the pre-dispatch safety re-assertion, the dispatch itself, the non-atomic jars-then-image lane and its recovery paths, the post-dispatch "exactly one of each" verification, the mandatory GHCR public-package action after the first release, and the release-notes house format.
user-invocable: true
allowed-tools: Bash, Read, Edit, Write
---

# Release Skill — API Sheriff

Cuts an API Sheriff release end to end: confirm the version, re-assert the pre-dispatch safety
evidence, **deliberately dispatch** the Release workflow, wait, verify that **exactly one** release
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

`.github/workflows/release.yml` is **`workflow_dispatch` only**:

```yaml
on:
  workflow_dispatch:
```

### A `.github/project.yml` edit does NOT and MUST NOT fire a release

This is the single most important line in this skill, and it is the exact opposite of how the
sibling `cuioss` project Token-Sheriff works. **Do not port Token-Sheriff's trigger mechanics.**

Token-Sheriff cuts a release by *merging* a `.github/project.yml` change
(`on: pull_request: types: [closed], paths: ['.github/project.yml']`). **API Sheriff deleted exactly
that trigger.** On *2026-07-12* it fired for real: a merged pull request touching
`.github/project.yml` cut a genuine Maven Central release, publishing
`de.cuioss.sheriff.api:*:1.0.0` from a project still in pre-1.0 development. Maven Central releases
are immutable — a published GA coordinate cannot be withdrawn, only superseded. The `1.0.0` GA was
abandoned, relocation stubs were published at `1.0.1`, the coordinates moved to
`de.cuioss.sheriff.gateway`, and the version line restarted at `0.1.0-SNAPSHOT`.

Consequently:

- **The dispatch is the only way a release is cut on this repository.**
- `release.yml` carries an in-file instruction not to reintroduce an event-driven trigger. **Never
  reintroduce one.**
- A change that removes or weakens an event-driven trigger must merge **on its own**, before any
  change that would fire that trigger — for a `pull_request` event GitHub evaluates the workflow
  definition **from the base branch**. See `doc/development/release-process.adoc` for the rule; do
  not restate it from memory.

### What one dispatch produces

| job | publishes |
|---|---|
| `release` (`release.yml:16-26`) — `uses: reusable-maven-release.yml` | Maven artifacts to Maven Central; the SCM tag; the GitHub release |
| `publish-image` (`release.yml:45-365`) — local, `needs: [release]` | the container image to GHCR at the **same** version, plus an SPDX SBOM and a Cosign signature |

---

## Ported reference — the facts this procedure depends on

These six sections are the operative content of `doc/development/release-process.adoc`. They are
reproduced here so the runbook can be followed cold; that document remains the canonical narrative.

### The image version is the Maven version

The image is a packaging of the same released source tree, not a separately versioned product, so
`ghcr.io/cuioss/api-sheriff:<version>` carries the Maven version **verbatim**. An operator who knows
the Maven version must be able to derive the image reference without a mapping table.

**This is a checked fact rather than a convention.** The `publish-image` job checks out the release
tag the Maven release pushed, reads `current-version` from `.github/project.yml` with the same pinned
action the release job used, and asserts the checked-out `project.version` equals it
(`release.yml:125-134`). A mismatch fails the release **before the image is built or pushed to
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
> outcome before you dispatch.

### If the image lane fails after the Maven release

1. **Do not re-run the whole workflow.** A second dispatch would attempt another Maven release of a
   version that is already published.
2. **Classify the failure before doing anything — a re-run cannot pick up a fix.** *Re-run failed
   jobs* creates a new **attempt of the same run**: it reuses the original event context and the
   original workflow definition, and `publish-image` checks out the **release tag**, not `main`. A
   commit merged to `main` after the dispatch is therefore invisible to the re-run. "Fix it on
   `main`, then re-run" does not work here and must not be attempted.
   - **Transient job/runtime failure** — a registry timeout, a runner flake, a Maven Central
     propagation race. **Re-run the failed `publish-image` job alone** from the Actions UI (*Re-run
     failed jobs*). It reads its version from `.github/project.yml` at the dispatch SHA and checks
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
  commit whose poms carry the released version — and **not** the `main` HEAD the release was
  dispatched from.

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

### Step 1 — Determine and confirm the version

Read the release block in `.github/project.yml`:

- `release.current-version` — the version that will be released
- `release.next-version` — the following development version

**`current-version` is what the release publishes.** Both the `release` job and the `publish-image`
job read it; the version-identity assertion compares it against the checked-out `project.version`.

Check it against the reactor version in `pom.xml` (`<version>`, expected `<current-version>-SNAPSHOT`
before the cut). `maven-release-plugin` owns the `X.Y.Z-SNAPSHOT` → `X.Y.Z` transition during
`release:prepare`.

> **Do NOT hand-edit `project.version` in `pom.xml`.** It would collide with the release plugin and
> break the version-identity assertion at `release.yml:125-134`.

**If `current-version` is already the version you intend to release, there is nothing to change** —
skip to Step 2. (This is the case for the `0.1.0` cut: `.github/project.yml` already declares
`current-version: 0.1.0` / `next-version: 0.2.0-SNAPSHOT`.)

**Only if the version must change**, edit the `release` block on a `chore/` branch, open a PR and
merge it in the normal way — and be clear about what that merge does and does not do:

> **Run Step 3(i) BEFORE merging that PR — do not defer it to Step 3.** The "merging this PR
> publishes nothing" guarantee below holds *only* while `release.yml` is `workflow_dispatch` only,
> and this skill's own premise is that the guard is **one commit from regressing**. Merging a
> `.github/project.yml` change is the exact action that fired the irrevocable 2026-07-12 release.
> Read the `on:` block at the SHA you are merging into, confirm it, record that SHA — *then*
> merge. Step 3(i) re-asserts it again at dispatch time; that later re-assertion protects the
> dispatch, not this merge.

```bash
git checkout -b chore/release_<version>
# edit .github/project.yml: current-version / next-version
git add .github/project.yml
git commit -m "chore(release): declare version <version>"
git push -u origin chore/release_<version>
gh pr create --repo cuioss/API-Sheriff --base main \
  --title "chore(release): declare version <version>" \
  --body "Declare current-version <version>, next-version <next>-SNAPSHOT. Publishes nothing: release.yml is workflow_dispatch only."
```

**Merging this PR publishes nothing.** The merge records *what* the next version is; the dispatch in
Step 5 is the explicit decision to *publish* it. Ask the user if `current-version` and
`next-version` look inconsistent or the numbers do not follow the expected pattern.

### Step 2 — Confirm the tree and the pull-request queue are clean

```bash
gh pr list --repo cuioss/API-Sheriff --state open --json number,title,isDraft
```

- **No open PRs** → proceed.
- **Open PRs exist** → these would normally merge before a release. Surface the list and **ask the
  user** whether to proceed or wait. Do not silently ignore them.

```bash
git status --porcelain
git fetch origin main
git rev-parse HEAD origin/main
```

The working tree must be clean and `HEAD` must equal `origin/main`.

> **HARD BOUNDARY — `release/relocation-stubs`.** That branch exists on the remote and **must NEVER
> be merged**. It carries the relocation-only stubs published under the abandoned
> `de.cuioss.sheriff.api` coordinates. The release must not pick it up. Confirm it is not in the
> ancestry of what you are about to release:
>
> ```bash
> git fetch origin '+refs/heads/release/relocation-stubs:refs/remotes/origin/release/relocation-stubs' \
>   || { echo "ERROR: could not fetch the stubs branch - the check did not evaluate" >&2; exit 1; }
> git merge-base --is-ancestor origin/release/relocation-stubs origin/main; case $? in
>   0) echo "STOP: stubs branch is in main" >&2; exit 1 ;;
>   1) echo "OK: stubs branch is not in main" ;;
>   *) echo "ERROR: the check did not evaluate - this is NOT a pass" >&2; exit 1 ;;
> esac
> ```
>
> **FULLY QUALIFY THE SOURCE REF, AND FORCE IT — `+refs/heads/…`, never the bare branch name.**
> The unqualified form `git fetch origin release/relocation-stubs:refs/remotes/origin/…` **DELETES
> the remote-tracking ref instead of creating it** on any clone with `fetch.prune=true` (or
> `remote.origin.prune=true`), which is a common global setting. `git fetch` then reports
> `- [deleted] (none) -> origin/release/relocation-stubs`, the very next line dies with
> `fatal: Not a valid object name origin/release/relocation-stubs`, and the guard lands on its
> `*)` arm. Observed on the 0.1.0 cut: the branch was present on the remote the whole time.
>
> **That failure looks exactly like the branch having been deleted, and it is not.** Before
> concluding anything from an `ERROR` here, check the remote directly — `git ls-remote --heads
> origin | grep relocation` — and re-run with the qualified refspec above. Never "resolve" it by
> skipping the check or appending `|| true`; the whole point of this boundary is that it fails
> closed.
>
> **Branch on the exit code, never on `&&` / `||`.** `git merge-base --is-ancestor` exits `0` for
> ancestor, `1` for not-ancestor and `128` on error — and an absent
> `origin/release/relocation-stubs` remote-tracking ref (a single-branch or shallow clone, a clone
> predating the branch, or the prune misfire above) is exactly such an error. A
> `… && echo STOP || echo OK` idiom collapses `1` and `128` into the same branch, so a check that
> never ran prints the reassuring `OK`. **An error is not a pass**, and the explicit `git fetch`
> above is what stops the common case from reaching that arm at all.

### Step 3 — Re-assert the pre-dispatch safety evidence (MANDATORY)

The plan that authored this skill recorded a baseline and the method. **Items (i), (iii) and (iv)
are TIME-VARYING — they were true at the SHA they were recorded at, not forever. Re-assert all three
here, at cut time. Do not inherit them.**

**(i) Confirm `release.yml` is still dispatch-only, and name the SHA you read it at.**

```bash
git rev-parse HEAD          # record this SHA in your report
```

Read `.github/workflows/release.yml` and confirm the `on:` block is `workflow_dispatch:` **only** —
no `pull_request`, no `push`, no `schedule`. The dispatch-only guard is one commit from regressing,
and **a claim about it that does not name a SHA is unverifiable later.**

**(ii) Confirm `release.yml` is the only invocation of `reusable-maven-release.yml`.**

```bash
git ls-files .github/workflows/
git status --short --untracked-files=all .github/workflows/
```

Then **`Read` every listed file** and confirm exactly one invokes `reusable-maven-release.yml`.

> **A CONTENT SEARCH IS NOT VALID EVIDENCE HERE.** The architecture inventory does not walk
> `.github/**`. `architecture search --content --literal --pattern "reusable-maven-release.yml"`
> returns `count: 0` with `files_scanned: 934`, **no unreadable entries and no elision** — a
> clean-looking zero — **while the string is present at `release.yml:19`.** That zero is a
> **coverage gap, never an absence.** Use direct `Read` enumeration, always.

**(iii) Confirm the merge queue is quiesced and `main` is settled — IMMEDIATELY before dispatching.**

```bash
gh pr list --repo cuioss/API-Sheriff --state open --json number,title
git fetch origin main && git rev-parse origin/main
```

Zero open PRs means an empty merge queue (a queue entry requires an open PR).

> **CONCURRENCY HAZARD — this is a check-then-act (TOCTOU) window on two shared resources: the merge
> queue and `main` itself.** `main` is merge-queue gated, and the release **force-pushes to `main`
> twice** (`Push changes` and `Push tag`, both `force: true`), succeeding against the protected
> branch because `cuioss-release-bot` is a queue bypass actor. **A force push racing a merge-queue
> landing can discard commits.**
>
> The window is **not** closed by ordering. It is **bounded** — partly mechanically, partly by
> procedure — and the two halves are not equally strong:
> 1. Assert quiescence **immediately before** dispatching, not minutes earlier.
> 2. **The pre-dispatch half has a mechanism.** Step 5 re-reads `origin/main` and aborts non-zero
>    unless it still equals the `$MAIN_SHA` Step 4 gated on, so a landing between the gate and the
>    dispatch stops the release instead of publishing an unverified commit.
> 3. **The post-dispatch half has none: nothing merges until the release run completes.** Tell the
>    team, and do not merge anything yourself, from dispatch until Step 7 reports the run finished.
>    No branch policy, merge freeze or workflow check enforces this — it is an operator obligation,
>    and a merge landing inside that window can still race the release force-push. Treat it as a
>    residual operator risk, not a closed safeguard (ADR-0034 records it as one).

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

> **Match the full ref, and branch on the exit code — the same discipline as the stubs check in
> Step 2.** `git tag --list '<version>'` and `git ls-remote … | grep …` only *print*; a procedure
> that reads their output by eye is not a guard, and a re-run that slips past it reaches the
> workflow's `force: true` tag push. `grep -w '<version>'` is doubly wrong: `.` is a regex
> any-character, and `-w` treats `-` as a word boundary, so `-w '0.1.0'` also matches
> `refs/tags/0.1.0-rc1`. The exact `refs/tags/<version>` forms above match one ref and nothing else.
>
> **Why this is checked directly:** the tag push is `force: true`, so a re-run can **MOVE** an
> existing release tag rather than refusing. `release:prepare` would likely fail first, but that is
> *incidental* protection, not a designed guard.

### Step 4 — Gate on a green `main`

The dispatch builds from `main`, so the gate must be bound to **the exact commit the dispatch will
build** — the `origin/main` SHA you just recorded in (iii) — and not to "the last few runs on the
branch". `--branch main --limit 5` spans several workflows and several commits, and reading it by
eye passes a red required run as readily as a green one.

```bash
MAIN_SHA=$(git rev-parse origin/main)   # the SHA from (iii); re-read it, do not retype it
echo "$MAIN_SHA"
gh run list --repo cuioss/API-Sheriff --commit "$MAIN_SHA" \
  --json workflowName,event,status,conclusion,databaseId,url
```

**Every required workflow must appear for `$MAIN_SHA` with `status: completed` and
`conclusion: success`.** Three distinct outcomes, and only the first permits a dispatch:

| What you see for `$MAIN_SHA` | Verdict |
|---|---|
| Each required workflow `completed` / `success` | Green — proceed |
| Any required workflow `failure` / `cancelled` / `timed_out` | Red — **do not dispatch.** Fix and re-check |
| A required workflow absent, `queued` or `in_progress` | **Not a pass.** An absent run is a check that never ran, not a check that passed — wait for it, or establish why it is legitimately absent |

**Never dispatch a release on a red `main`, and never on an unproven one.** Fix and re-check.

### Step 5 — Dispatch the release, deliberately

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
> 1. **The signature identity is bound to `refs/heads/main`.** Step 8 verifies the Cosign signature
>    against `--certificate-identity …/release.yml@refs/heads/main`. That value is the OIDC
>    `job_workflow_ref` of the `publish-image` job, so it changes with the dispatch ref. A dispatch
>    at a SHA produces a certificate this runbook's own verification would reject.
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

**This is the only way a release is cut on this repository.** There is no auto-trigger to fall back
on and none to wait for.

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

**From dispatch until the run completes, nothing merges to `main`.** This is point 3 of the (iii)
mitigation and is not optional — the release force-pushes to `main` twice. **It is also the half
with no mechanism behind it:** Step 5's drift check covers only the pre-dispatch window, and nothing
in the repository rejects a merge landing inside this one. Holding it is on you.

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

The label must equal `<version>`. The workflow already asserts the same-digest property at
`release.yml:246-258`; the commands above are the **independent** confirmation, which is the whole
point of this step — a check that only re-reads the workflow's own claim confirms nothing.

Verify the Cosign signature **against that resolved digest**, using **exactly** these two identity
values (they are derived from the `publish-image` job's identity and change silently if the workflow
file is renamed or the release is dispatched from a ref other than `main`):

```bash
cosign verify "$IMAGE@$VERSION_DIGEST" \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity https://github.com/cuioss/API-Sheriff/.github/workflows/release.yml@refs/heads/main
```

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

### Step 10 — Update the version-bearing examples

**Every example that names a concrete version must name the version just released.** These are the
files a new user copy-pastes first, so a stale pin here is the most visible possible defect: it
sends them to an image that either does not exist or is not the release they think they are running.

**This step runs AFTER the image is verified (Step 8) and public (Step 9), never before.** Pointing
an example at a version that has not finished publishing is the same defect aimed forward instead
of backward.

Enumerate the current pins rather than trusting this list — files move:

```bash
grep -rn --include='*.adoc' --include='*.md' --include='*.env' --include='*.yml' --include='*.yaml' \
  -e 'ghcr\.io/cuioss/api-sheriff:[0-9]' . \
  | grep -v node_modules | grep -v '/target/' | grep -v '^\./\.plan/'
```

Known version-bearing locations, as of the 0.1.0 cut:

| file | what carries the version |
|---|---|
| `deployment/compose-sample/.env` | `API_SHERIFF_IMAGE=ghcr.io/cuioss/api-sheriff:<version>` — the sample's single image pin |
| `doc/user/compose-sample.adoc` | the `docker pull ghcr.io/cuioss/api-sheriff:<version>` in *Route A* |
| `README.adoc` | the ALPHA/maturity callout and the known-limitations preamble, both naming the cut |
| `doc/user/README.adoc` | the same maturity callout, stated for the user-doc layer |

> **Do NOT "fix" `doc/user/container-image.adoc`.** It uses a literal `<version>` placeholder
> throughout, deliberately — it is the reference layer and is written to stay true across releases.
> Substituting a concrete version there would make it wrong at the *next* cut. A placeholder is not
> a stale pin; leave it alone.

**Never leave an example carrying a caveat the release has overtaken.** The 0.1.0 cut shipped
`deployment/compose-sample/.env` still reading *"0.1.0 is not published yet"* — accurate when
written, false the moment the release landed, and contradicted by the pin on the very next line.
When a release makes such a note obsolete, delete the note rather than leaving it to be puzzled over.

If the version-bearing files are already correct — the common case, since they are usually written
during the cycle leading up to the cut — say so explicitly in the Step 12 report rather than
silently skipping the step. "Checked, already correct" and "forgot to check" must not look alike.

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

1. **Two top-level groups:** `## Features & Enhancements` and `## Dependency Updates`.
2. **Features & Enhancements** — group functional PRs by theme with `###` subheadings:
   - `### API & Code Quality` — also the home for refactor/standards/cleanup recipes, **not** under
     build/tooling
   - `### Security`
   - `### Testing & Standards`
   - `### Documentation`
   - `### Build & CI` — manually-authored CI/build improvements; **not** mechanical dependency bumps

   Add release-specific themes when the cycle has a dominant thread. Adapt headings to the actual
   PRs; omit empty sections.
3. **Dependency Updates** — group by type with `###` subheadings:
   - `### Java` — Java libraries (Quarkus, cui-*, …)
   - `### Infra` — platform/build/CI: build plugins, GitHub Action bumps (harden-runner,
     `actions/*`, claude-code-action), `cui-java-parent`, and cuioss-organization workflow bumps
4. **Collapse version chains** — when the same artifact is bumped repeatedly (`A → B → C`), keep only
   the **latest** entry spanning the full range, using the latest PR's URL/author (e.g.
   `version.quarkus 3.34.2 → 3.35.0 → 3.37.0` becomes a single `3.34.2 to 3.37.0`). This matters:
   `step-security/harden-runner` and `anthropics/claude-code-action` are bumped dozens of times per
   cycle — collapse each to one Infra line.
5. **Remove all OpenRewrite bumps and friends** — drop every `rewrite-maven-plugin`,
   `rewrite-migrate-java`, `rewrite-testing-frameworks` and related PR.
6. **Remove internal tooling churn** — drop PRs that only touch dev/build orchestration with no
   user-facing effect: `marshal.json` / plan-marshall config migrations, plan-marshall build wiring,
   internal dev-skill changes, and **the mechanical version-declaration PR itself**.
7. **Preserve each kept PR line verbatim** (`* <title> by @author in <url>`); when two PRs share an
   identical title, merge them onto one line with both URLs. For collapsed chains keep the latest
   PR's line and adjust only the version span.
8. **Keep the trailing `**Full Changelog**: ...compare/<prev>...<version>` line.**

### Step 12 — Report

Report: the released version; the SHA recorded in Step 3(i); the release URL; the resolved image
digest; confirmation that **exactly one** of each expected artifact exists; the GHCR package's
observed `visibility` value (Step 9) — report the field verbatim, `public` / `internal` / `private`,
never a bare "done", since `internal` is exactly the outcome that reads as done and is not; which
version-bearing examples (Step 10) were updated, or that they were **checked and already correct**;
and how many dependency PRs were collapsed or removed while reformatting the notes.

State plainly which image checks were made **anonymously** and which were authenticated. An
authenticated check is not evidence about outside consumers, and on an `internal` package every
authenticated check passes.

---

## Critical rules

- **`workflow_dispatch` is the only way a release is cut.** A `.github/project.yml` edit does **NOT**
  and **MUST NOT** fire a release. Never reintroduce an event-driven trigger.
- **Re-assert items (i), (iii) and (iv) at cut time.** They are time-varying and are never inherited
  from a recorded baseline.
- **Dispatch only after re-reading `origin/main`.** Step 5 aborts non-zero unless it still equals
  the `$MAIN_SHA` Step 4 gated on — the dispatch builds `main` as it is *then*, not as it was gated.
- **Nothing merges to `main` between dispatch and run completion** — the release force-pushes to
  `main` twice as a queue bypass actor. This one is unenforced: it is an operator obligation, not a
  mechanism.
- **`release/relocation-stubs` must NEVER be merged**, and the release must not pick it up. Fetch it
  with the **fully-qualified forced refspec** (`+refs/heads/…`); the bare branch name self-prunes
  under `fetch.prune=true` and the guard then reports `ERROR` for a branch that is still there.
- **Set the GHCR package to *public*, not *internal*, and assert the `visibility` field** rather than
  inferring it from a pull. Internal passes every authenticated check while anonymous pulls 401.
- **Update the version-bearing examples (Step 10) after the image is public**, and delete any caveat
  the release has overtaken. `doc/user/container-image.adoc` is exempt — its `<version>` is a
  deliberate placeholder, not a stale pin.
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
- **Never dispatch on a red `main`**, and never merge a red PR.
- **Ignore the tag-triggered `benchmark.yml` run** — it fires on `push: tags: ["*"]`, belongs to the
  release rather than to a code change, and its lane is known-flaky.
- **Always pass `--repo cuioss/API-Sheriff`** to `gh`.
- **Temporary files go under `.plan/temp/`.**
- **Commit trailers** follow this project's convention:

  ```
  🤖 Generated with [Claude Code](https://claude.com/claude-code)

  https://claude.ai/code/session_<id>

  Co-authored-by: Claude Opus 5 <noreply@anthropic.com>
  ```

## See also

- `doc/development/release-process.adoc` — the canonical process narrative, and the normative
  **Trigger rules** it records: *dispatch is the only trigger*, and *a change that removes or
  weakens an event-driven trigger merges on its own*. It states those rules rather than the
  2026-07-12 incident that motivated them. **This file is now the surviving account of that
  incident** — see *How the release is wired* above. The former *Coordinate history* note in
  `README.adoc` was removed once `de.cuioss.sheriff.api` had been abandoned long enough that the
  history was of no use to a reader arriving at the project; ADR-0034 records the decision the
  incident produced.
- `.github/workflows/release.yml` — the dispatch-only workflow. Its in-file comments carry the
  composability, ordering and attestation caveats behind the steps above.
- `doc/user/container-image.adoc` — the operator layer: pulling, running and verifying the image.
