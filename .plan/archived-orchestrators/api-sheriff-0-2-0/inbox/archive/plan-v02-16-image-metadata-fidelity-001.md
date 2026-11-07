envelope_version=1
sender_type=plan
sender_id=plan-v02-16-image-metadata-fidelity
epic=api-sheriff-0-2-0
kind=landing
created=2026-08-09T17:50:53Z

## What landed

**Plan**: plan-v02-16-image-metadata-fidelity — "Container image metadata fidelity"
**PR**: #199, merged via merge queue as `aeb80c5`
**Branch/worktree**: removed; plan directory moved back to main.

Both spec deliverables landed:

- `api-sheriff/src/main/docker/Dockerfile.native.jfr` no longer hard-codes
  `0.1.0-SNAPSHOT` — it now declares `ARG APP_VERSION=dev` and labels with
  `${APP_VERSION}`.
- `api-sheriff/src/main/docker/Dockerfile.native` gained `ARG APP_REVISION` plus
  `org.opencontainers.image.revision`, plumbed through
  `integration-tests/docker-compose.yml` `build.args` and
  `.github/workflows/release.yml`.

## Surface deviation from the spec (deliberate, and load-bearing)

The implemented surface is **one file wider** than the spec's Expected Surface.
`integration-tests/docker-compose.yml` had to be included: `release.yml` performs
no `docker build` of its own and explicitly forbids adding one, so the only
channel into the published image is the Compose service's `build.args`.
Implementing the literal Expected Surface would have shipped an **empty**
`org.opencontainers.image.revision` label on every published image — a new
falsehood in place of a missing value, i.e. the exact defect this plan existed to
remove. This was caught by the 2-refine Q-Gate (finding `b4ca14`) before any code
was written, and absorbed into the outline.

## How the claim is verified

Verification is on **built images**, not on Dockerfile text:

- `ImageMetadataIT` — 3 tests against `api-sheriff:distroless`, executed green.
- `ImageMetadataJfrIT` — 2 tests against `api-sheriff:jfr`, executed green.
- `ImageLabelActivationWiringTest` — a fast surefire test that derives the
  expected label-backed ARG set from `Dockerfile.native` at run time and pins the
  Compose `build.args` hop, which no built-image read can observe. Its
  drift-detection was proven with a real negative control.

## Residue the epic should track

1. **Open defect filed, not fixed — issue #201.** Running the JFR integration-test
   lane exposed a pre-existing defect: `MtlsHandshakeIT` fails 2/3 under `-Pjfr`
   only (fail-open on handshake rejection) while passing 3/3 under
   `-Pintegration-tests` on the identical tree. Out of scope here, and `-Pjfr` is
   not in CI's gating set — so it is invisible to the gate until someone runs that
   lane.
2. **JFR lane could not start at all** until fixed in this PR:
   `docker-compose.jfr.yml` bind-mounts `./target/jfr-recordings` at
   `/tmp/jfr-output`, and whichever compose command touched the service first
   created that host directory root-owned, so the uid-1001 container could not
   write the recording and the gateway died at startup. Fixed by
   `integration-tests/scripts/prepare-jfr-output-dir.sh`.
3. **Why Open Defect (54) survived a release sweep**: the release skill's version
   sweep is doc-scoped, so a version literal living in a *build input* (a
   Dockerfile) is outside it by construction. Worth the epic's attention as a
   mechanism gap, not a one-off miss.

## Gate / review outcome

- `verify -Ppre-commit` green at the loop-back HEAD; CI all green.
- Sonar new-code issues: 0 (confirmed).
- CodeRabbit posted 3 actionable inline comments; all three were real and all
  three were fixed on-branch (see the accompanying candidate-lesson messages).
  Sourcery reviewed as an optional bot and its two points were answered on their
  merits.
