# WS-03: Deployment and Distribution

epic: api-sheriff-0-3-0

> Charter document for one workstream — a coherent slice of the epic with its own goal and surface.
> Re-cut 2026-08-04 when the `api-sheriff-next` backlog was split by target version.

## Goal

Extend the distribution surface beyond the container image and Docker Compose sample that 0.1.0 ships, starting with a real-cluster-verified Helm chart.

## Surface

- **In scope**: the Helm chart, its real-cluster (kind) verification, the Kubernetes deployment diagram, and later distribution targets (an operator, a Kustomize base, a published chart repo).
- **Out of scope**: the compose sample and container image, both shipped in 0.1.0.

## Plans

- **PLAN-V03-04** — Helm chart, verified against a real cluster.

**Carries a known blocker that is not a version bump:** Helm chart YAML classifies as `unknown` in plan-marshall's path classifier, a phase-4 Q-Gate hard error. Re-verify by EXECUTING the classifier over `deployment/helm/*.yaml`, never by reading a version.
