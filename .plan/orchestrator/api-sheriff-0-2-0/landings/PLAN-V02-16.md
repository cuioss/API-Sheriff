# Landing — PLAN-V02-16: container image metadata fidelity

epic: api-sheriff-0-2-0 · workstream: WS-01
**PR [#199](https://github.com/cuioss/API-Sheriff/pull/199) · merge commit `aeb80c5` · merged 2026-08-09**
plan_marshall_plan_id: `plan-v02-16-image-metadata-fidelity`

## Corroboration — verified first-party

| Claim | Verdict | Evidence |
|---|---|---|
| Merged to `main` as `aeb80c5` | **corroborated** | ancestor of `origin/main`, and its head |
| D1 — the JFR literal is gone | **corroborated** | `Dockerfile.native.jfr`:10 `ARG APP_VERSION=dev`, :14 `LABEL …version="${APP_VERSION}"`. No `0.1.0-SNAPSHOT` anywhere |
| D2 — revision label exists and is plumbed | **corroborated** | `Dockerfile.native`:16 `ARG APP_REVISION=dev`, :21 `LABEL …image.revision="${APP_REVISION}"`; `docker-compose.yml`:180 `APP_REVISION: ${APP_REVISION:-dev}`; `release.yml`:223 exports the resolved tag SHA |
| D3 — assertions read a **built image** | **corroborated** | `ImageMetadataIT`, `ImageMetadataJfrIT`, `ImageLabelInspector` all present under `integration-tests/src/test` |
| The lane verifies its own output | **corroborated** | `release.yml`:381 inspects `org.opencontainers.image.revision` off the **pulled** image and fails the lane on mismatch (:383) |

**D3 is the deliverable that mattered and it was honoured literally.** The spec insisted on asserting
labels *on a built image*, because "a `LABEL` line present but never applied is the vacuous-assertion
shape this project has been repeatedly bitten by". Five ITs across two lanes now read labels off real
images, and `release.yml` re-checks the published one after the pull.

## The spec defect — mine, and it would have shipped a new falsehood

**The Expected Surface I wrote named `Dockerfile.native` + `.github/workflows/release.yml` for D2.
That surface is not implementable.** `release.yml` performs **no `docker build`** of its own and
explicitly forbids adding one (`release.yml`:214–215). The only channel into the image is
`integration-tests/docker-compose.yml`'s `build.args` — a file the spec never named.

**Implementing the literal Expected Surface would have shipped an empty `revision` label on every
published image — a new falsehood replacing a missing value**, in the exact plan whose objective was
to stop the image stating things that are not true.

This is the **sixth** instance of the epic's enumerate-every-carrier rule, and the most pointed:
- It is in a spec **I authored**, not an inherited one.
- At the 2026-08-08 re-grounding I read this spec and recorded *"every claim in this spec holds; no
  correction is owed"* — **and that was wrong.** I verified the claims the spec made and never asked
  whether the surface was *sufficient to implement the deliverable*. A surface can be entirely
  accurate and still not reach the thing it must change.
- The distinction the spec missed is **consumer vs. channel**: `release.yml` *consumes* the value,
  `docker-compose.yml` *carries* it into the build. Naming the consumer reads like naming the
  surface, and does not build.

Recorded as a standing rule in the epic Watches: an Expected Surface must be checked for
**sufficiency**, not only accuracy — trace the value from its producer to the artifact and name every
hop.

## The operator ruling that paid off twice

The operator chose **"fix the lane too"** rather than scope around a broken profile. That decision
returned two distinct findings:

1. **The `-Pjfr` lane could never start.** `docker-compose.jfr.yml` bind-mounts
   `./target/jfr-recordings`; whichever compose command touched it first created it **root-owned**,
   so the uid-1001 container could not write and the gateway died at startup. `ImageMetadataJfrIT`
   had nowhere to run. Fixed here.
2. **Running the repaired lane exposed a pre-existing security-relevant defect — issue
   [#201](https://github.com/cuioss/API-Sheriff/issues/201), verified OPEN.** `MtlsHandshakeIT` fails
   **2/3 under `-Pjfr` only — fail-open on handshake rejection** — while passing 3/3 under
   `-Pintegration-tests` on the identical tree. **Not fixed here**, correctly: it is out of this
   plan's declared boundary and reporting it is the standing rule.

**The generalisable shape: a test lane nobody can run is not a passing lane, it is an unmeasured
one.** The JFR lane's green was the absence of execution. Repairing it converted a silent gap into a
filed defect within one run — which is the argument for fixing a lane rather than routing around it.

## Self-correction on a public thread

The plan **posted a public correction on the PR**. Its first reply to Sourcery had said the IT would
be parameterized over the image tag (it was not — JFR coverage came from a separate test) and had
declined a `ProcessBuilder` extraction (which then happened, as `ImageLabelInspector`). Both
statements had become false as the work moved, so it corrected them rather than let them stand.

**This is the behaviour the epic's standing clauses ask for, executed without being asked** — a reply
is a claim, and a claim that has become false is corrected at its own surface, not silently
superseded.

## Reconciliation actions

- **Queue**: `PLAN-V02-16` → `shipped`; `pr`, `landing`, `plan_marshall_plan_id` stamped.
- **New Open Defect (14)**: issue #201, the `-Pjfr` fail-open handshake defect. Needs a home — the
  natural candidate is `PLAN-V02-11` (which owns the "what are the test lanes for" question) or
  `PLAN-V02-10` (per-client TLS trust). **Do not let it sit unowned**; it is fail-open on a security
  control.
- **New Watch**: Expected-Surface sufficiency (above).
- **No parallelization collision.** `PLAN-V02-17` ran concurrently and did not touch this surface;
  `PLAN-V02-01` remained `launched`-not-started throughout, so the arch-gate hazard never
  materialised — the carve-out recorded at the emit held.

## Two smaller notes carried

- **The quality gate reverted two `finalize-step-simplify` edits.** The plan **took the gate's
  output** and verified idempotence rather than fighting it — exactly the disposition standing rule
  (4) prescribes, and the first clean application of it since that rule was clustered.
- **Two "review comments" were the plan's own hand-written replies re-entering** through a
  start-anchored self-response filter. Dispositioned, and filed as a plan-marshall defect.

## Post-merge verification — OWED

`aeb80c5`'s main-branch `deploy-snapshot` run is unreachable through the CI abstraction
(`ci checks status --head <sha>` resolves `--head` as a branch name; re-verified by execution this
same day). **Recorded as OWED, never as complete** — now for three merge commits: `89a3cfe`,
`e343404`, `aeb80c5`.

> **UPDATE 2026-08-09 — POST-MERGE VERIFICATION IS NO LONGER OWED. IT IS DONE AND GREEN.**
> `build / deploy-snapshot` = **success** for `aeb80c5` (Maven Build run `31327146763`), as is every other job
> in that run. The check was reachable all along via one read-only `gh` call; the abstraction has no
> commit-to-run path, which had been mistaken for the axis being unverifiable. See
> `epic.md` § Post-Merge Verification for the method and the abbreviated-SHA false negative it survived.
