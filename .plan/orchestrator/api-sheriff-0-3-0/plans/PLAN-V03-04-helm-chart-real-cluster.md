# PLAN-V03-04: Helm chart for the gateway, proven on a real cluster

epic: api-sheriff-0-3-0
workstream: WS-03
track: **POST-0.1.0** — gated behind the release cut (PLAN-08B in `api-sheriff-roadmap`).

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Source: **split out of `api-sheriff-roadmap` PLAN-27 on 2026-08-02 by operator decision** —
> *"Move the helm stuff to api-sheriff-next, we start with docker-compose for the alpha release."*
> The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-41-helm-chart-real-cluster.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Objective

Ship a **Helm chart that is proven to actually deploy, not merely to lint.** The alpha (0.1.0) ships
the docker-compose sample only; Kubernetes is the next deployment target and it lands here.

**This plan inherits its substance verbatim from PLAN-27's deliverables 4 and 5.** The compose sample,
the `deployment/` module skeleton and the root-`pom.xml` registration stayed in the release track and
ship as PLAN-27 — so by the time this plan runs, `deployment/` **already exists** and this plan adds a
`helm/` subtree to it rather than creating the module.

## ⚠ HARD BLOCKER — the reason this plan exists separately, and it did NOT travel with the split

**Helm chart YAML does not classify, and this plan is almost entirely Helm chart YAML.**

plan-marshall's `manage-execution-manifest._classify_paths_via_extensions` recognises infrastructure
YAML by **location** (`_INFRA_CONFIG_DIR_TREES` = `.github/workflows`, `.circleci`, `docker`;
`_INFRA_CONFIG_PARENT_DIRS` = `.github` for `.yml`/`.yaml` only) or by **basename**
(`_INFRA_CONFIG_BASENAME_GLOBS` = `docker-compose*`, `compose*`, `.gitlab-ci*`, `.hadolint*`,
`.trivyignore`, `.dockerignore`). Membership is deliberately **not** "any `.yml`". Helm's `templates/`
holds author-chosen filenames in an author-chosen location, so **no allowlist can close it**.

Verified first-party by EXECUTING the predicate at bundle **0.1.1276** on 2026-08-02 — not by reading
the doc, and not stale from the earlier 0.1.1261 measurement:

| Path | Bucket |
|------|--------|
| `deployment/helm/Chart.yaml` | `unknown` ❌ |
| `deployment/helm/values.yaml` | `unknown` ❌ |
| `deployment/helm/templates/deployment.yaml` | `unknown` ❌ |
| `deployment/helm/templates/_helpers.tpl` | `unknown` ❌ |
| `.github/workflows/helm-verify.yml` | `config` ✅ |

An `unknown` tag forces the plan-wide bucket to `unknown`, which downstream guards treat as a **hard
error requiring user resolution** — this plan parks at the phase-4 Q-Gate and burns a cycle.

**DO NOT LAUNCH until Helm chart YAML classifies out of `unknown`.** The unblock signal is that
classification changing, **not** a bundle version bump — re-verify by EXECUTING the predicate over the
paths above. Delivered upstream as bundle hand-off **round 4 item 1**.

*Caveat for a future re-test:* `deployment/pom.xml` also returns `False` from
`_is_infrastructure_config_path`, and that is expected, not a second gap — the predicate is the stage-3
fallback over the residual unclaimed set, and `build-maven` claims `pom.xml` at stage 2.

## Deliverables

**Three deliverables.** Deliberately below the split guard; this is the chart half of a plan that was
already split once.

1. **The Helm chart** — `Chart.yaml`, `values.yaml`, templates, added as a `helm/` subtree under the
   **existing** `deployment/` module PLAN-27 shipped.
   **The gateway's neutral-name config model is the chart's central design problem.** ADR-0025
   (corrected 2026-09-22 — the spec previously miscited ADR-0011, which covers JWKS/egress naming
   only; ADR-0025 is "the whole-server-TLS surface is neutral in gateway.yaml and bound by exactly
   two seams", the invariant this deliverable actually depends on): `gateway.yaml` carries neutral
   logical names and the deployment binds concrete material. Therefore **trust material, keystores
   and passwords MUST be bound by the deployment as Secrets, never templated into `gateway.yaml`
   values.** That is the ADR-0025 invariant and this chart must not erode it.
   **Management is HTTPS-only on 9000** (PLAN-23 — single port, no plain-HTTP fallback), so the
   chart's readiness/liveness probes **must speak TLS**. A chart whose probes assume plain HTTP will
   deploy and then never become ready.

2. **Chart testing on a REAL cluster — its own named line item, and the point of the plan.**
   *Fast layer*: `helm lint`, `helm template`, `kubeconform` against Kubernetes API schemas.
   *Proof layer*: create an ephemeral **kind** cluster in CI, `helm install`, and **assert the gateway
   actually serves `/q/health` over TLS.** Build the image locally and `kind load docker-image` it —
   the chart test MUST NOT depend on a published image.
   **Why the real cluster, stated so it is not optimised away later:** the release epic was burned
   three times by artifacts that passed a shallow check and did not work — the `gatewayHealth`
   benchmark red across 11 merged PRs, eight benchmarks that never executed at all, and lesson
   `2026-07-25-15-001` (an opt-in feature no test activates). **A chart that lints but does not deploy
   is that same failure in a new medium.** Lint is the cheap layer, not the answer.

3. **Documentation and the Kubernetes deployment diagram.** Three-layer per the standing convention:
   Reference — every chart value documented exhaustively; Operator `doc/user/` — the primary layer,
   "how do I actually run this on Kubernetes"; Developer `doc/development/` — how to run the kind test
   locally.
   **THE DEPLOYMENT DIAGRAM IS A NAMED REQUIREMENT, not decoration.** The Kubernetes topology gets an
   SVG deployment diagram authored to the type standard and template **PLAN-30 shipped** (`#132`).
   Use that template; do not invent a parallel style. The standard's render-and-read-back verification
   is blocking — reviewing SVG markup is not verification.

## Claim Labels

Per `persona-marshall-orchestrator/standards/orchestration-model.md` § Verify-First Contract.
Claims inherited from PLAN-27 were read at `5298237`; claims added by the split were read at `818d964`.

- **OBSERVED (`818d964`) — no Helm chart or any helm-shaped file exists anywhere.** A repo find for
  `*chart*` / `Chart.yaml` / `*helm*` returns nothing. Genuinely new infrastructure.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: no chart/helm under deployment/; only compose-sample/ + pom.xml
- **OBSERVED (`818d964`) — the classifier blocker above**, verified by executing the predicate.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: plan-marshall _manifest_core.py (9913740d9, 2026-09-09) INFRA_CONFIG globs still carry no helm/Chart.yaml entry -- blocker stands
- **OBSERVED (`5298237`) — management is HTTPS-only on port 9000** since PLAN-23. Every consumer of
  9000 must speak TLS; chart probes inherit this.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: application.properties:125-193 mgmt still port 9000 HTTPS-only, no plain-HTTP fallback
- **OBSERVED (`818d964`) — the neutral TLS/management key surface has LANDED.** PLAN-31B (`#138`,
  merge `ffa8cef`) single-sourced the TLS surface in `gateway.yaml` bound by `tls/TlsServerCustomizer`,
  **deleted** the raw `quarkus.*` TLS duplicates rather than leaving them as dormant overrides, and
  added a first-class `management:` block with `management.tls.enabled`. **The chart must template the
  NEW neutral keys — the `quarkus.*` env vars it would otherwise have templated no longer exist.**
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: config/NeutralTlsConfigSource.java still projects management.tls.enabled; no raw quarkus.management.ssl.* duplicate
- **HYPOTHESIS — the gateway's config model maps cleanly onto Helm values + Secrets.** ADR-0025
  (corrected 2026-09-22, was miscited as ADR-0011) makes `gateway.yaml` carry neutral logical names
  with the deployment binding concrete material, which is *structurally* what a chart wants. Confirm/refute at
  `api-sheriff/src/main/resources/application.properties` and the `TlsConfig` / `gateway.yaml` binding
  surface (verify-at-outline). **If the binding needs env vars the chart cannot express cleanly,
  report it as a finding about the config model — do not invent a chart-only config path that diverges
  from the documented one.**
  - verdict: unverifiable | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: ADR-0025 present matching corrected citation; design-fit still needs outline-time trace
- **HYPOTHESIS — `deployment/` exists with a `compose-sample/` subtree and is registered in the root
  reactor.** PLAN-27 owns that; this plan adds `helm/` beside it. Confirm at
  `deployment/pom.xml` § the packaging declaration and root `pom.xml` § `<modules>`
  (verify-at-outline). **If PLAN-27 did not ship, this plan creates the module itself and its
  deliverable count grows — re-scope rather than assuming.**
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: root pom.xml:36 module deployment; deployment/pom.xml:12 packaging=pom, compose-sample only
- **Verify-first clause**: re-verify the management-port scheme and the neutral TLS key names against
  the merged tree before templating anything. PLAN-23, PLAN-31B, PLAN-31C and PLAN-36 all move this
  surface, and PLAN-36 reshapes the config record family outright.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-3-0/cleanup | rescoped: n/a | evidence: ADR-0033 confirms PLAN-36 landed; mgmt-port scheme and neutral TLS keys unchanged

## Expected Surface

- OBSERVED absence → NEW: `deployment/helm/` (`Chart.yaml`, `values.yaml`, `templates/**`) — D1
- OBSERVED absence → NEW: a CI job running `helm lint` / `helm template` / `kubeconform` plus a kind
  install and smoke assertion — D2
- OBSERVED: `deployment/pom.xml` — read, and extended only if the chart needs a build hook — D1
- OBSERVED: `doc/user/`, `doc/development/`, `doc/configuration.adoc` — D3
- OBSERVED absence → NEW: one SVG Kubernetes deployment diagram, to PLAN-30's shipped template — D3
- OBSERVED (reference only, do NOT edit): `integration-tests/docker-compose.yml` — the runtime
  hardening prior art (`security_opt: [no-new-privileges:true]`, `cap_drop`, `read_only: true` at
  :244-252 and :342-346). **This plan must not restructure the IT stack.**
- OBSERVED (absence, asserted): **NO `api-sheriff/src/main/java/**` change expected.** If the chart can
  only be made to work by changing gateway code, that is a finding about the config model — report it,
  do not patch it here.
- OBSERVED (absence, asserted): **NO change to `deployment/compose-sample/**`.** That is PLAN-27's
  shipped artifact; this plan sits beside it.

## Dependencies and Sequencing

- **HARD: the classifier blocker above.** Not a soft preference — this plan parks at the Q-Gate.
- **HARD: gated behind the 0.1.0 release cut** (`api-sheriff-roadmap` PLAN-08B), like every plan in
  this epic.
- **Depends on `api-sheriff-roadmap` PLAN-27** (soft but real) — it ships the `deployment/` module this
  plan extends. If PLAN-27 slips past the cut, see the module-existence HYPOTHESIS above.
- **Depends on `api-sheriff-roadmap` PLAN-26** (soft) — the image tag scheme and version-label
  behaviour. Not the published image itself: the kind test builds locally and `kind load`s.
- **Prefer after PLAN-36** (`nullable-type-model`, running as of 2026-08-02) — it reshapes the config
  record family the chart's values map onto.

## Standing Conventions (epic-wide; carried into every emit)

- **THREE-LAYER DOCUMENTATION**, all in the SAME PR — see D3. Plan-doc or ADR updates satisfy NO layer.
- **SONAR ZERO-FINDINGS**: gate GREEN before merge — red is a HARD STOP.
- **DEPENDENCY APPROVAL** (CLAUDE.md): `helm`, `kind` (or `k3d`) and `kubeconform` are
  **operator-approved as named scope**, carried across from PLAN-27 with the chart. Anything beyond
  that minimal set — a chart-testing plugin, a k8s test framework — still needs asking.
- **NAMED LINE ITEMS**: D2 is named for a reason; an outline that collapses the real-cluster proof
  layer into the lint layer is a finding.
- **ACTIVATE IT IN A TEST** (lesson `2026-07-25-15-001`): **D2's kind install IS this plan's discharge
  of that lesson.** A chart that is only linted is an artifact no test activates.
- **POST-MERGE CHECK**: the orchestrator's, per the Finalize Boundary below.

## Finalize Boundary — the plan STOPS at the merge

**Operator ruling, 2026-07-30.** This overrides `CLAUDE.md` § Git Workflow step 8 for plan-executed
work. Everything up to and including the merge is the plan's: CI green, review-comment triage, Sonar
roundtrip, the merge, branch cleanup, metrics, archive.

**The aftermath is the ORCHESTRATOR's and MUST NOT be attempted by the plan** — the PR-attached
post-merge run and the main-branch run for the merge commit, including `deploy-snapshot`. Report the
merge and stop.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-3-0/plans/PLAN-V03-04-helm-chart-real-cluster.md" plan_id=plan-v03-04-helm-chart-real-cluster
```

**The explicit `plan_id` is load-bearing — do not drop it.** Without it, phase-1-init derives the id
from the task description, which is an LLM judgement and has silently dropped the PLAN-NN prefix before.

## Write-Boundary

The plan touches only its own repository source and tests. It creates and edits NO file under
`.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message.
