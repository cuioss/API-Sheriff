# PLAN-V02-47: Publish the Gateway Image for linux/amd64 and linux/arm64

epic: api-sheriff-0-2-0
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
> Staged 2026-10-21 on the operator's decision for a multi-arch release over a documentation note
> (epic Watch "THE PUBLISHED GATEWAY IMAGE IS AMD64-ONLY", inbox `plan-v02-12-idp-addressing-model-004`).

## Objective

`ghcr.io/cuioss/api-sheriff` is published for `linux/amd64` only, and the native executable inside it
needs x86-64-v3 (AVX2, BMI2, FMA). It does not start on arm64 hosts (Apple Silicon, AWS Graviton), not
even under Docker's amd64 emulation. `README.adoc` documents this and sends those users to a locally built
image. Publish one image tag that resolves to a tested native image for each of `linux/amd64` and
`linux/arm64`.

**The release lane must keep its guarantees for every architecture.** `publish-image` in
`.github/workflows/release.yml` publishes exactly the image the integration-test suite ran against. It
checks that the pushed manifest digest is unique, smoke-tests that digest after pulling it (pulled ID ==
tested ID, version and revision labels), scans it with Trivy at HIGH+CRITICAL, generates an SPDX SBOM and
signs the digest with Cosign keyless. Each of these must hold per architecture and for the multi-arch index.

**GraalVM cannot cross-compile a native executable**, and QEMU emulation of a native build does not fit
the release time budget. Each architecture builds on a runner of its own architecture.

## Deliverables

1. **One build-and-test leg per architecture.** Run `publish-image`'s build-and-test half as a matrix over
   `linux/amd64` (`ubuntu-latest`) and `linux/arm64` (`ubuntu-24.04-arm`, or the arm64 runner label
   available to the cuioss org; confirm at outline). Each leg runs the full `-Pintegration-tests` suite on
   its runner, then resolves the tested image ID and generates its SBOM (artifact name per architecture).
   It runs the gating Trivy scan, pushes the tested image as a plain manifest and asserts exactly one
   pushed digest. Last, it smoke-tests that digest on its own runner with the existing checks. Keep the
   current posture, `docker tag` plus `docker push`, never `docker buildx build --push`, so each leg's
   digest names a plain image manifest and not an attestation list. Leg-local tags must not look like
   release tags; pick the scheme at outline.
2. **Join the legs into one index, then tag it.** A job that needs both legs assembles an OCI image index
   from exactly the two tested per-architecture digests (`docker buildx imagetools create` or equivalent;
   no attestation entries). Only then does it point the release tags (`<version>` and `sha-<sha>`) at the
   index. Assert that both tags resolve to the same index digest, and that the index lists exactly
   `linux/amd64` and `linux/arm64`, each pointing at its leg's tested digest. **A failed leg publishes no
   release tag**: there is no half release. Keep the existing guard against re-pushing or re-signing an
   already released tag (the comment around line 111 of `release.yml`).
3. **Sign what a consumer pulls.** Sign the index digest with Cosign keyless, using the same identity and
   SAN as today, and sign both per-architecture digests too, because a client can pull by platform digest.
   Extend whatever verification documentation exists (`doc/user/container-image.adoc`) to say which
   digests are signed and how to verify each of them.
4. **Set the CPU baseline deliberately on both architectures.** Today no `-march` is configured, so amd64
   gets GraalVM's default, x86-64-v3. Find the default for aarch64 builds and check that it runs on
   Graviton 2 and later and on Apple Silicon under Docker. Decide whether amd64 stays at x86-64-v3 or drops
   to a broader target such as `compatibility`, and measure the cost if it does. Record the decision, and
   configure it explicitly if it differs from the default.
5. **A dry run before the first real release.** The lane can only be tested by running it, so add a path
   that runs both legs and the join without touching release tags. For example, extend the existing
   `workflow_dispatch` with a dry-run input that pushes to a scratch tag and skips signing (or signs it),
   then cleans up. Run it once on the PR branch or after the merge, and record the run in the PR. Without
   that run the plan is not done.
6. **Documentation and decision record.** Rewrite `README.adoc` § "The published image runs on x86-64-v3
   only" into the supported-platforms statement. Update `doc/user/container-image.adoc`,
   `doc/user/compose-sample.adoc` (Route B is then needed only for unsupported CPUs or local changes),
   the `deployment/compose-sample/.env` comment and `doc/development/release-process.adoc`. Update the
   header comments of `release.yml` (the attestation-posture and scope-honesty comments describe one
   image). Record the multi-arch decision and deliverable 4's CPU-baseline decision as a new ADR, or as an
   amendment to the ADR that owns the GHCR publish lane (find it at outline); take the next free ADR
   ordinal at the time of writing.

Split guard: 6 deliverables, one PR, recorded rationale: all six change one release lane that cannot ship
half-done. Deliverables 1–3 are one job graph, 5 proves it, and 4 and 6 describe what it publishes.

## Claim Labels

- OBSERVED: `publish-image` runs on `ubuntu-latest`, has `timeout-minutes: 90`, builds through the
  `-Pintegration-tests` lifecycle, then tags and pushes with `docker tag` / `docker push`; its comments
  forbid `docker buildx build --push` (`.github/workflows/release.yml`, `origin/main` at `6ab4d829`)
- OBSERVED: the digest step requires exactly one `ghcr.io/cuioss/api-sheriff` RepoDigest; the smoke
  pulls by digest and compares the pulled image ID with the tested ID; Cosign signs that one digest — same file
- OBSERVED: `README.adoc` states the image is `linux/amd64` only, needs x86-64-v3, and that no `-march`
  is configured — `origin/main` at `6ab4d829`
- OBSERVED: every base and fixture image the release and IT stack use has a `linux/arm64` variant. The
  base images: `quarkus-distroless-image:2.0` (the pinned digest is a multi-platform index),
  `ubi9-quarkus-micro-image:2.0` and `ubi9-quarkus-mandrel-builder-image:jdk-25`. The IT stack images:
  Keycloak 26.8.1 (pinned digest), `nginx:1.27-alpine` (pinned digest), `toxiproxy:2.12.0`,
  `go-httpbin:2.23.1` and `eclipse-temurin:25-jre` — `docker buildx imagetools inspect`, 2026-10-21
- HYPOTHESIS: the full `-Pintegration-tests` suite passes on an arm64 runner unchanged; no arm64 CI run
  exists yet (verify in deliverable 5)
- HYPOTHESIS: GitHub's arm64 Linux runner is available to this public repository at no extra cost (confirm at outline)

## Expected Surface

- OBSERVED: `.github/workflows/release.yml` (`publish-image` and its new join job)
- OBSERVED: `api-sheriff/src/main/resources/application.properties` or `api-sheriff/pom.xml`, only if
  deliverable 4 sets `-march`
- OBSERVED: `README.adoc`, `doc/user/container-image.adoc`, `doc/user/compose-sample.adoc`,
  `doc/development/release-process.adoc`, `deployment/compose-sample/.env`, `doc/adr/` (one new or amended record)

## Dependencies and Sequencing

- No code dependency on the other open plans. It runs after `PLAN-V02-27` by operator decision
  (2026-10-21) and before the remaining queue.
- Surface overlap: `README.adoc` and `doc/user/compose-sample.adoc` are also touched by `PLAN-V02-15`
  (BFF compose sample). Sequential, as all plans are under the one-plan-at-a-time mode.
- The first real release after this plan publishes the first multi-arch image. Its PR-time evidence is
  deliverable 5's dry run.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-47-multi-arch-image.md" plan_id=plan-v02-47-multi-arch-image
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
