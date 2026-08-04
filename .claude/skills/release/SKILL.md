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
2. Fix the cause on `main` and merge it.
3. **Re-run the failed `publish-image` job alone** from the Actions UI (*Re-run failed jobs*). It
   reads its version from `.github/project.yml` at the dispatch SHA and checks out the release tag,
   so it reproduces the same inputs without touching Maven Central.
4. If the failure was the Trivy gate on an unfixable base-image CVE, see the next section before
   re-running.
5. If the version has to be abandoned: **cut a patch version and publish relocation stubs.** Maven
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
package to *public*.**

Until that is done, **every check stays green while `docker pull` fails for everyone outside the
organisation.** See Step 9 — for the 0.1.0 cut this is live, not hypothetical.

---

## Workflow

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
> git merge-base --is-ancestor origin/release/relocation-stubs origin/main && echo "STOP: stubs branch is in main" || echo "OK: stubs branch is not in main"
> ```

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
> The window is **not** closed by ordering — it is closed **by procedure, here**:
> 1. Assert quiescence **immediately before** dispatching, not minutes earlier.
> 2. **Nothing merges until the release run completes.** Tell the team, and do not merge anything
>    yourself, from dispatch until Step 7 reports the run finished.

**(iv) Confirm no tag for the release version already exists.**

```bash
git fetch --tags --force
git tag --list '<version>'
git ls-remote --tags origin | grep -w '<version>' || echo "no such tag"
```

Both must be empty.

> **Why this is checked directly:** the tag push is `force: true`, so a re-run can **MOVE** an
> existing release tag rather than refusing. `release:prepare` would likely fail first, but that is
> *incidental* protection, not a designed guard.

### Step 4 — Gate on a green `main`

The dispatch builds from `main`. Confirm the latest `main` run is green before releasing:

```bash
gh run list --repo cuioss/API-Sheriff --branch main --limit 5 \
  --json databaseId,workflowName,status,conclusion,headSha
```

**Never dispatch a release on a red `main`.** Fix and re-check.

### Step 5 — Dispatch the release, deliberately

```bash
gh workflow run "Release" --repo cuioss/API-Sheriff --ref main
```

(Equivalently, in the UI: **Actions → Release → Run workflow**.)

**This is the only way a release is cut on this repository.** There is no auto-trigger to fall back
on and none to wait for.

Capture the run:

```bash
RUN_ID=$(gh run list --repo cuioss/API-Sheriff --workflow "Release" --limit 10 \
  --json databaseId,status --jq 'map(select(.status=="in_progress" or .status=="queued")) | first | .databaseId')
echo "$RUN_ID"
```

### Step 6 — Hold the quiescence window

**From dispatch until the run completes, nothing merges to `main`.** This is the second half of the
(iii) mitigation and is not optional — the release force-pushes to `main` twice.

### Step 7 — Wait for the run

```bash
gh run watch "$RUN_ID" --repo cuioss/API-Sheriff
```

Two legs, and they fail differently:

- The **`release` job** publishes to Maven Central. Once it is green, **the jars are irrevocable.**
- The **`publish-image` job** (`timeout-minutes: 90`) runs the integration-test suite, which
  performs a **GraalVM native compile** — the dominant and most variable term. A long wait here is
  the native compile or Maven Central propagation, not a hang.

**If `publish-image` fails after `release` succeeded**, you have a partial release: go to
*If the image lane fails after the Maven release* above. **Do not re-run the whole workflow.**

### Step 8 — Verify that EXACTLY ONE release fired

> **"It worked" is not the check. "Exactly one of each" is.** A `force: true` tag push and a
> re-runnable workflow both make "more than one" a real failure mode, and a duplicate is far harder
> to see than an absence.

Expected artifact set at `<version>`:

**1 — one git tag** (bare, no prefix; `cui-parent-pom` sets `<tagNameFormat>@{project.version}</tagNameFormat>`):

```bash
git fetch --tags --force
git tag --list '<version>'
git ls-remote --tags origin | grep -w '<version>'
```

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

```bash
docker pull ghcr.io/cuioss/api-sheriff:<version>
docker image inspect ghcr.io/cuioss/api-sheriff:<version> \
  --format '{{index .Config.Labels "org.opencontainers.image.version"}}'
```

Confirm the label equals `<version>`, and that `ghcr.io/cuioss/api-sheriff:<version>` and
`ghcr.io/cuioss/api-sheriff:sha-<40-char release-tag commit SHA>` resolve to the **same digest**.
The workflow already asserts this at `release.yml:246-258`; this is the independent confirmation.

Verify the Cosign signature, using **exactly** these two values (they are derived from the
`publish-image` job's identity and change silently if the workflow file is renamed or the release is
dispatched from a ref other than `main`):

```bash
cosign verify ghcr.io/cuioss/api-sheriff@<digest> \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com \
  --certificate-identity https://github.com/cuioss/API-Sheriff/.github/workflows/release.yml@refs/heads/main
```

> **Ignore the benchmark run this triggers.** `.github/workflows/benchmark.yml` fires on
> `push: tags: ["*"]`, so the release's tag push **starts a benchmark run**. That run belongs to the
> release, not to a code change, and **its lane is known-flaky**. Do **not** read it as a pass or a
> failure of the released code, and do not block the release on it.

### Step 9 — MANDATORY post-release action after the FIRST release (R3)

**Set the GHCR `api-sheriff` package to *public*.**

Organisation package settings → `api-sheriff` → *Change visibility* → **Public**.

This is a GitHub UI action; it cannot be done from the repository, and the in-workflow smoke step
**cannot detect that it is missing** because it pulls with the job's own credentials.

> **The `0.1.0` cut IS the first release, so this section is live, not hypothetical.** Until it is
> done, every check stays green while `docker pull` fails for everyone outside the organisation.

Confirm afterwards — ideally unauthenticated, from outside the org:

```bash
docker logout ghcr.io
docker pull ghcr.io/cuioss/api-sheriff:<version>
```

### Step 10 — Reformat the generated release notes

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

### Step 11 — Report

Report: the released version; the SHA recorded in Step 3(i); the release URL; the resolved image
digest; confirmation that **exactly one** of each expected artifact exists; whether the GHCR
public-package action (Step 9) is done or still outstanding; and how many dependency PRs were
collapsed or removed while reformatting the notes.

---

## Critical rules

- **`workflow_dispatch` is the only way a release is cut.** A `.github/project.yml` edit does **NOT**
  and **MUST NOT** fire a release. Never reintroduce an event-driven trigger.
- **Re-assert items (i), (iii) and (iv) at cut time.** They are time-varying and are never inherited
  from a recorded baseline.
- **Nothing merges to `main` between dispatch and run completion** — the release force-pushes to
  `main` twice as a queue bypass actor.
- **`release/relocation-stubs` must NEVER be merged**, and the release must not pick it up.
- **The release is not atomic.** A green `release` job means the jars are already irrevocable. On an
  image-lane failure, re-run **`publish-image` alone** — never the whole workflow.
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

- `doc/development/release-process.adoc` — the canonical narrative, including *why* the automatic
  trigger was removed and why the guard had to merge on its own.
- `.github/workflows/release.yml` — the dispatch-only workflow. Its in-file comments carry the
  composability, ordering and attestation caveats behind the steps above.
- `doc/user/container-image.adoc` — the operator layer: pulling, running and verifying the image.
