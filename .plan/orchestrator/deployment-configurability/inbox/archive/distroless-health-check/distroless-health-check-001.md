envelope_version=1
sender_type=plan
sender_id=distroless-health-check
epic=deployment-configurability
kind=landing
created=2026-08-29T16:28:27Z

# Landing: PLAN-01 — Minimal Container Health-Check for the Distroless Main Image

**Outcome:** merged. PR #230 → `909d43f` on `main` (squash, via merge queue), 2026-08-29T16:18:06Z.

## What shipped

The distroless image now publishes a health state, answered by the application binary itself.

- **Pre-boot probe.** `ApiSheriffApplication.main` short-circuits on `--health-probe` *before*
  `Quarkus.run(...)`, delegating to a new `HealthProbe` class that opens a bare TCP connection to
  `127.0.0.1:9000` and exits 0/1. It binds no port, writes no file and reads no gateway config.
- **Exec-form `HEALTHCHECK` on both images.** `Dockerfile.native` and `Dockerfile.native.jfr` carry
  the identical check on the management port, so the two images report health the same way. The JFR
  image's former `/dev/tcp` shell probe on 8443 is gone.
- **Two-layer readiness gate.** `compose up -d --wait --wait-timeout`, scoped to the derived gateway
  service list, followed by a single-shot `/q/health/ready` assertion per instance. Replaces the
  82-line per-instance retry loop. `GATEWAY_READY_ATTEMPTS`, `GATEWAY_PROBE_OPTS` and
  `GATEWAY_DIAG_OPTS` are retired; diagnostics are factored into `capture_gateway_diagnostics`.
- **Seven gateway services** rewired in `integration-tests/docker-compose.yml`; the operator sample
  gets a visible `healthcheck:` block mirroring the image cadence.
- **`ContainerHealthIT`** asserts the signal in the integration run (3 tests), so a broken check
  fails the build rather than being cosmetic.
- **Deletions:** `integration-tests/src/main/docker/health-check.sh` (dead) and
  `deployment/compose-sample/scripts/wait-for-ready.sh` (superseded) — a net surface reduction.
- **ADR-0031 amended**, not superseded; documentation updated across eight surfaces.

## Deviations from the staged spec, and why

The spec's deliverable 1 assumed the JFR `/dev/tcp` form was merely unportable and that the shipped
counter-claim was refutable. Measured against the pulled base image, the counter-claim's *conclusion*
was right for a stronger reason than it gave: `quay.io/quarkus/quarkus-distroless-image:2.0` carries
**no executable at all** — `/usr/bin` and `/usr/sbin` are empty. So an exec-form `HEALTHCHECK` can
only ever name `/app/application`. A statically-linked probe binary and a UBI-micro base change were
both rejected on the attack-surface constraint the spec itself sets; a compose-level-only healthcheck
fails identically, since `healthcheck.test` also runs inside the container.

The spec's deliverable 3 required the probe to read the `de.cuioss.sheriff.management-scheme` label.
That is not satisfiable: Compose labels are daemon-side and invisible in-container. A TCP-accept probe
is protocol-blind, which dissolves the two-scheme problem instead of solving it — both the `http` and
`https` management schemes are covered by the same check.

Deliverable 4's host-side gate was **replaced** rather than kept. `depends_on: service_healthy` alone
could not gate it (the suite's runner is host-side, not a compose service), so `compose up --wait` is
the concrete equivalent, with a single-shot readiness assertion retained to preserve the semantic the
container check cannot provide.

## Out-of-footprint repairs carried in the same PR (all operator-approved)

- `.plan/marshal.json` + `BuildGateCoverageContractTest` + `CLAUDE.md` — restored four hand-added
  `build.map` globs, deliberately excluding `.github/workflows/*` (no Maven profile reads workflow
  YAML, so registering it buys a gate that cannot observe the change).
- `.mvn/maven.config` + `doc/development/build-gate-discipline.adoc` — `-T1C` → `-T1`. The reactor
  parallelism was failing the gate 5 runs out of 5 across 6 distinct tests, reproducing on clean
  `main`, at no wall-clock saving.
- `integration-tests/scripts/verify-invalid-config-fails.sh` — GNU `timeout` → portable deadline
  poll; on macOS the missing binary made the negative control report the inverse of what happened.
- `.plan/project-architecture/deployment/enriched.json` — module enrichment.

## Open items for the epic

1. **ADR-0038 is drafted but not landed.** Records the pre-boot probe mechanism and its three refuted
   alternatives; deferred to a follow-up docs PR to avoid re-gating a merged-ready branch. Draft
   preserved outside the worktree.
2. **`benchmarks` module metadata is stale** — described as "WRK-based" but genuinely k6
   (`benchmarks/enriched.json` carries WRK wording in five fields including a package key that no
   longer exists). Pre-existing drift this PR aggregated rather than created; needs re-enrichment at
   the `enriched.json` root, since `architecture discover` regenerates `_project.json` from it.
3. **`ea651d`** — the JFR overlay's world-writable `/tmp/jfr-output` (`chmod 777`), accepted as
   pre-existing and out of scope. The same pattern exists in
   `integration-tests/scripts/prepare-jfr-output-dir.sh`.
4. **CI blind spot** — a change to `.plan/marshal.json` is not a build-triggering path, so
   `BuildGateCoverageContractTest` never runs on CI. That is how the glob erasure reached `main`
   unseen. Fixing it needs the org-managed `cuioss-organization` workflow.
5. **Two producer-side defects** in `plan-marshall` bundles, belonging to the marketplace store rather
   than this project's: the automatic-review self-response filter is start-anchored on a heading
   `post_responses` does not emit, so the tool re-ingests its own output as a finding (observed
   twice); and PR-Agent's participation goes stale at the parent commit on push, needing an explicit
   `/review`.
