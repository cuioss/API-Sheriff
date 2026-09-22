# PLAN-V02-16: container image metadata fidelity — the image should state truthfully what it is and what built it

epic: api-sheriff-0-2-0
workstream: WS-01

> **Re-homed from `api-sheriff-roadmap` on 2026-08-08 as that epic closed.** Carries Open Defect (54)
> plus a related follow-up raised during the 0.1.1 Trivy pre-check. Both are OCI image labels, both
> are small, and they belong together because they answer the same question from opposite ends:
> *what is this image, and which commit produced it?*

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **Every claim in this spec holds; no correction is
owed.** It was authored on 2026-08-08 against `b8dde22` and the surface has not moved.

- `Dockerfile.native.jfr`:8 still carries `LABEL org.opencontainers.image.version="0.1.0-SNAPSHOT"`
  verbatim — the falsehood D1 fixes.
- `Dockerfile.native` still carries the rationale at :11–13, `ARG APP_VERSION=dev` at :14 and
  `LABEL org.opencontainers.image.version="${APP_VERSION}"` at :18. The sibling treatment D1 copies
  is intact.
- **Asserted absence re-confirmed**: `org.opencontainers.image.revision` appears in **neither**
  Dockerfile, so D2 is genuinely net-new.

**This plan is the epic's cleanest emit candidate.** It is surface-disjoint from all sixteen other
plans, carries no ADR, no sequencing predecessor and no unsettled verify-first clause. Its only cost
is the one it already names: `.github/workflows/**` is gate-requiring, so D2's `release.yml` edit
pays a full quality gate.

**One caution the spec is right to state and that this pass reinforces.** D3 says to assert the
labels on a **built image**, not on Dockerfile text. That is not a style preference — the epic's
standing rule is that a `LABEL` line present but never applied is the vacuous-assertion shape, and
`build success is not evidence that work happened`. A green that only proves the Dockerfile contains
a string proves nothing about the published artifact.

## Objective

Make the published and profiling images' OCI metadata true. One label currently states a falsehood;
another that would answer the most common provenance question does not exist.

## Deliverables

1. **Open Defect (54) — `Dockerfile.native.jfr` hard-codes a stale version label.**

   OBSERVED at `origin/main` `b8dde22`, `api-sheriff/src/main/docker/Dockerfile.native.jfr`:8:

   ```dockerfile
   LABEL org.opencontainers.image.version="0.1.0-SNAPSHOT"
   ```

   **Its sibling was deliberately fixed and carries the reasoning that condemns this line.**
   `Dockerfile.native`:12–18:

   ```dockerfile
   # honest: a locally or PR-built image is never published, and a version-shaped default would lie the
   # moment the project version moved. The release lane exports the released version into this arg.
   ARG APP_VERSION=dev
   LABEL org.opencontainers.image.version="${APP_VERSION}"
   ```

   The JFR variant is exactly the lie that comment forbids, and it was **already wrong at the 0.1.0
   cut** — `0.1.0-SNAPSHOT` was never a released version — so this is not drift from 0.1.1.

   **Severity LOW**: the JFR image is a profiling variant and is **not published** (the GHCR package's
   versions are all the distroless image). But it is supply-chain metadata stating a falsehood
   directly next to the reasoning that forbids it, and the fix is the one-line `ARG APP_VERSION=dev`
   treatment already proven in the sibling.

   **Check whether the JFR image has a build path that could export a real version** before copying
   the sibling wholesale — if nothing feeds it, `dev` is the honest value and should stay `dev`.

2. **Add `org.opencontainers.image.revision` so provenance is answerable from the image itself.**

   Raised 2026-08-07 by the Tier A Trivy pre-check session. The image carries **no git-sha label**,
   so *"which commit is this image?"* currently rests on clean-tree-at-HEAD plus timestamps — an
   argument reconstructed from outside rather than an assertion carried inside.

   This is the natural companion to deliverable 1: the release lane already exports the released
   version into `APP_VERSION`, so exporting the SHA alongside it is the same mechanism applied once
   more. `release.yml` already computes the tag SHA — it pushes `sha-${TAG_SHA}` as a registry tag —
   so the value exists and is not being labelled.

   **Do not invent a second provenance mechanism.** Cosign already signs the digest and the
   certificate carries `github_workflow_sha` (verified 2026-08-07 on the 0.1.1 signature:
   OID `1.3.6.1.4.1.57264.1.3` = `f3b9ed69…`). The label is a *convenience for a reader with the
   image and no registry access*, not the authority. Say so, so nobody later treats a mutable label
   as a security control.

3. **Tests / verification.** Assert the labels on a built image rather than asserting the Dockerfile
   text — a `LABEL` line that is present but never applied is exactly the vacuous-assertion shape this
   project has been repeatedly bitten by. If asserting on a built image is disproportionate for a
   profiling variant, say so and state what the weaker check does and does not establish.

## Claim Labels

- OBSERVED (2026-08-07, `b8dde22`): `Dockerfile.native.jfr`:8 carries the hard-coded literal;
  `Dockerfile.native`:14 carries `ARG APP_VERSION=dev` with the quoted rationale at :12–13.
- OBSERVED (2026-08-07): the GHCR package `api-sheriff` carries six versions, all of the distroless
  image; no JFR image is published.
- OBSERVED (2026-08-07): `release.yml` pushes a `sha-${TAG_SHA}` registry tag, so the commit SHA is
  already computed in the release lane.
- **WHY THE RELEASE SKILL'S SWEEP MISSED (54), and it is the reusable part**: Step 10 is doc-scoped
  (*"version-bearing examples"*), so a **build input** carrying a version literal is outside it by
  construction. A version sweep scoped to documentation will always miss Dockerfiles.

## Expected Surface

- `api-sheriff/src/main/docker/Dockerfile.native.jfr` — D1
- `api-sheriff/src/main/docker/Dockerfile.native`, `.github/workflows/release.yml` — D2
- OBSERVED (absence, asserted): the Cosign signing block and its identity are **NOT** edited. They
  were settled by `api-sheriff-roadmap` PLAN-50 (#195) against a first-party certificate read;
  reopening them here would re-litigate a closed decision.

## Dependencies and Sequencing

- No dependency on other 0.2.0 plans. Surface-disjoint from all of them.
- **`.github/workflows/**` is now gate-requiring** — `build.map` gained the glob in `api-sheriff-roadmap`
  PLAN-51 (#196), pinned by `BuildGateCoverageContractTest`. D2's `release.yml` edit therefore pays a
  full quality gate. That is the intended trade, not a surprise.

## Issue Closure

No GitHub issue tracks either item — both were found by first-party sweeps rather than reported. If
one is filed before this plan runs, link and close it on landing; otherwise there is nothing to close.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-16-image-metadata-fidelity.md" plan_id=plan-v02-16-image-metadata-fidelity
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message.
