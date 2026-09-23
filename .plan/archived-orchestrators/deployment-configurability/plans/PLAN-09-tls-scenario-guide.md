# PLAN-09: TLS Configuration Scenario Guide (`doc/user/`)

epic: deployment-configurability
workstream: WS-06

> ⛔ **SUPERSEDED 2026-09-15 by `PLAN-24-release-docs-and-tls-scenario-guide.md`, which carries this plan's scope as its deliverables 1-6.** Retained as
> the audit record of why it was retired; a superseded spec is never deleted. **Do not emit.**
> **Why:** operator decision at the 2026-09-15 corpus revisit (`origin/main` `a2969b9`) — up to 12
> deliverables per plan are authorized, and this spec collided on surface with the others merged
> into the successor, so they could only have run sequentially as separate PR cycles. Its queue row
> is `parked` because the queue status vocabulary has no `superseded` value.
>
> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

API Sheriff's TLS documentation is strong on two axes and absent on a third. `doc/user/tls-edge.adoc`
organises by **mechanism** (policy, passthrough SNI, mTLS, the management interface).
`doc/user/environment-variable-overrides.adoc` organises by **key** (which variable supplies what,
and the precedence rule). Neither organises by **scenario** — so an operator who knows what they want
to *achieve* ("terminate TLS at the gateway with a certificate from our internal CA, and validate
tokens against an internal IdP") has to synthesise the answer from two reference documents plus the
compose sample.

Write the missing third document: a task-oriented guide that enumerates the supported TLS
configuration scenarios and, for each one, states **what it achieves**, **what it requires** before
you start (certificates, files, directory structure), and **the concrete configuration** — the exact
`gateway.yaml` keys and `QUARKUS_*` environment variables, with values.

⛔ **This document must not become a third source of truth.** It is an *entry point* that composes and
links: it carries the recipe and the ordering, and delegates key semantics to
`environment-variable-overrides.adoc` and mechanism rationale to `tls-edge.adoc` by xref. A scenario
that restates why a key exists, or re-derives a precedence rule, has drifted into being a second
copy of a reference document — and the epic already carries evidence of what that costs.

## ⚠ RE-GROUNDED 2026-09-08 by the `cleanup` pass — the scenario set is UNDERSTATED

This spec was staged before `egress_tls` existed and mentions it **zero times**. Since then PLAN-03
(#268) and PLAN-04 (#272) landed an entire outbound-TLS configuration surface with **two ADRs and
three boot records**, none of which any scenario in the catalogue below covers:

| Landed since staging | Where |
|---|---|
| `egress_tls` config block | `gateway.schema.json:153` |
| `egress_tls.upstream_verify_hostname` | ADR-0040, boot WARN `ApiSheriff-118` |
| `egress_tls.upstream_tls_profile` — anchors **REPLACE** the JVM default trust store | boot record `ApiSheriff-119` |
| `egress_tls.jwks_verify_hostname` | ADR-0041, boot WARN `ApiSheriff-120` |

⛔ **A task-oriented guide that omits the knobs an operator is most likely to reach for is
understated, not merely incomplete.** The `upstream_tls_profile` replacement semantics in particular
are the kind of thing this guide exists to state plainly — an operator mounting a corporate CA loses
public trust, and today that fact lives only in a log template and an ADR.

✅ **Deliverable 1 already anticipates this** — *"Enumerate the scenarios, and justify the set. The
catalogue below is the starting point"* — so this is a re-enumeration the spec's own shape allows,
not a re-scope it resists. Read ADR-0040, ADR-0041 and `ApiSheriff-118`/`-119`/`-120` before fixing
the set.

✅ **No Expected Surface change is owed**: the new scenarios land in `doc/user/`, already declared.
Recorded explicitly per the same-act rule — this fold adds no file surface.

## Deliverables

1. **Enumerate the scenarios, and justify the set.** The catalogue below is the starting point,
   derived from what the repository actually ships; confirm it against the shipped configurations and
   state any addition or removal with its reason. Each scenario must be one an operator would
   recognise as *their situation*, not a mechanism dressed as a scenario.
2. **Write each scenario to a fixed three-part shape**, in this order, because the order is the
   operator's own: **Goal** (what this achieves, and when to pick it — including when *not* to) →
   **Preconditions** (certificates and their properties, files, mount points, directory structure,
   anything that must exist before the first boot) → **Configuration** (the exact keys, split into
   `gateway.yaml` policy and `QUARKUS_*` deployment material, with real values). ⛔ Every scenario
   gets all three parts; a scenario with no preconditions says "none" rather than omitting the
   heading, so a reader can trust the shape.
3. **Make every configuration block copy-pasteable and true.** Values come from the shipped
   configurations — `deployment/compose-sample/` and the `integration-tests/src/main/docker/sheriff-config*/`
   instances — rather than being invented for the document. ⚠ Note the environment-variable encoding
   trap `environment-variable-overrides.adoc` already documents: a TLS bucket name is a configuration
   *map key* and its encoding is **not** a plain dot-to-underscore substitution (the shipped stack
   uses `QUARKUS_TLS_DEFAULT_TRUST__STORE_P12_PATH`). Copy exact spellings; do not derive them.
4. **State the failure mode for each scenario.** What an operator sees when they get it wrong, and
   which signal names it — e.g. `ApiSheriff-115` for the management downgrade, which reports the
   *observed effective state* rather than a declared key. This is the half that turns a recipe into a
   troubleshooting aid, and it is where most of the document's value to a real operator sits.
5. **Wire it into the documentation graph.** Add the row to `doc/user/README.adoc` alongside the
   existing TLS entries, and add reciprocal "see also" links from `tls-edge.adoc` and
   `environment-variable-overrides.adoc` so a reader arriving at either reference is pointed at the
   scenario guide. A guide nothing links to is a guide nobody finds.
6. **Draw the key-material graph — the one surface with no diagram, and the one that most needs one.**
   ⛔ **Folded in 2026-09-10 by the epic coverage audit**, which found this unowned. Verified at HEAD:
   `doc/resources/diagrams/` holds **ten** SVGs — sequences, topologies, flows — and **not one covers
   TLS, certificates, keys or trust**; `doc/user/tls-edge.adoc` and `doc/configuration.adoc` carry
   **zero** `image::` blocks.

   ⛔ **The subject is genuinely graph-shaped, which is why prose keeps losing.** This epic has
   documented **seven distinct key-material paths** across five documents and six log records, and it
   is the *relationships* an operator gets wrong, not the individual facts: server identity via
   `certificate.files` / `key-store-file` / `credentials-provider` / a named `quarkus.tls.*` bucket;
   the management interface's key-less `plain-management` bucket and the never-declare-a-default-key-store
   rule; inbound `client_ca` for mTLS; outbound `egress_tls.upstream_tls_profile`, which **REPLACES**
   the JVM bundle (`ApiSheriff-119`); JWKS `jwks.tls_profile`, whose SSLContext is **MUTUALLY
   EXCLUSIVE** with `verifyHostname(false)` and refused at boot (ADR-0041); and `CookieKeyMaterial`,
   which is key material of an entirely different kind. **Three of those replace rather than extend,
   and one pair is refused in combination.** That is a diagram, not a paragraph.

   ✅ **Hand-authored SVG is the right format** — the orchestrator's earlier "adopt a generated
   format" counsel was **withdrawn**. `pm-documents:ref-svg-diagrams` already mandates the exact
   verification that concern was proposing to invent: its **Step 4 is titled "Verify the render
   (MANDATORY, BLOCKING)"** and is non-skippable — render against `#ffffff` and `#0d1117` and
   **inspect the PNGs**. ⛔ **Run it. The gap was never missing capability; it was a skipped mandatory
   step**, and PLAN-03's topology SVG carries an open read-back defect for precisely that reason.
   Diagram type is `diagram-type-graph.md` (hub-and-spoke) or `diagram-type-block.md`
   (producer/store/consumer), decided at authoring.

## The scenario catalogue (starting point, confirm at outline)

| # | Scenario | Exists today | Notes |
|---|---|---|---|
| 1 | **Terminate TLS at the gateway** — the default single-listener topology | yes | `QUARKUS_HTTP_SSL_CERTIFICATE_FILES` / `_KEY_FILES`; `tls` policy block in `gateway.yaml` |
| 2 | **Terminate TLS and require client certificates (mTLS)** | yes | `tls.mtls.enabled` + `client_ca` in `gateway.yaml`; the mTLS instance is the worked example |
| 3 | **Pass some hostnames through untouched (SNI passthrough)** | yes | `tls.passthrough_sni` STARTS the front listener; `QUARKUS_HTTP_SSL_PORT` then relocates the terminated listener to the internal loopback port |
| 4 | **Management interface over HTTPS** — the shipped default | yes | `QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES` / `_KEY_FILES`; deliberately unset in the shipped configuration because they are container paths |
| 5 | **Management interface over plain HTTP** — behind a trusted boundary | yes | `QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME=plain-management`, or `management.tls.enabled: false`; both land on the same runtime key and both emit `ApiSheriff-115` |
| 6 | **Validate tokens against an IdP with a private CA** | yes | `jwks.tls_profile: <name>` in `gateway.yaml` names the profile; `QUARKUS_TLS_<PROFILE>_TRUST__STORE_*` binds the material |
| 7 | **Terminate TLS upstream (ingress / sidecar), serve plain HTTP** | ⛔ **no — PLAN-08 builds it** | Add this scenario **into this same document** when PLAN-08 lands; do not start a second guide |

## Authoring Discipline (from the 2026-09-11 lessons intake)

This plan's deliverable is **statements about what a knob does**, and a documentation-only footprint
skips both build gates — so nothing executes these claims except the reader who follows them. Archived
lesson `2026-09-04-07-001` (PLAN-10's ADR, then PLAN-15's own paragraph) records the class shipping twice:

- **Every sentence about runtime or deployment behaviour names its implementing symbol before it is
  written** — the constant, method, config key reader, or image layer that enacts it — and that symbol
  is **read, not recalled**. A plausible, normally-true remedy (override the `HEALTHCHECK`) was false here
  because of a compiled-in constant.
- **A paragraph that states a consequence AND explains its mechanism holds two claims — check them
  against each other**, not only against the source. PLAN-15's paragraph passed a per-sentence source
  check and still contradicted itself.
- **When a reviewer refutes a prose claim, verify the refutation against the source before adopting
  it**, and record any correction that cannot ride the same commit as an explicit follow-up.
- **Any SVG this plan adds or redraws under `doc/resources/diagrams/` is rendered and viewed on both
  themes before it ships** — `pm-documents:ref-svg-diagrams` Step 4 (*"Verify the render (MANDATORY,
  BLOCKING)"*: `rsvg-convert -b "#ffffff"` and `-b "#0d1117"`, then look at both PNGs). The
  integration-topology diagram's read-back went stale once because a redraw did not re-run it
  (`settled.md` § "Open Defects — handled (relocated 2026-09-11)", entry O42).

## Claim Labels

- OBSERVED: `doc/user/` already carries **mechanism-organised** and **key-organised** TLS
  documentation, and **no scenario-organised** guide. `tls-edge.adoc` (234 lines) has sections for TLS
  policy on the terminated listener, passthrough SNI, mTLS, emergency trust-root replacement and the
  management interface; `environment-variable-overrides.adoc` (520 lines) has the three classes, the
  precedence rule, certificate/key/trust material and the management TLS selection. Neither is
  organised by operator goal. Asserted absence of the third axis; re-derive at HEAD.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: yes | evidence: doc/user/tls-scenarios.adoc now exists (landed by successor PLAN-24), closing the gap; spec header explicitly names PLAN-24 as carrier
- OBSERVED: the material keys are already documented and must be **linked, not restated** —
  `QUARKUS_HTTP_SSL_CERTIFICATE_FILES` / `_KEY_FILES`, `QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES` /
  `_KEY_FILES` and `QUARKUS_TLS_<PROFILE>_TRUST__STORE_*` are tabulated at
  `doc/user/environment-variable-overrides.adoc:114-144`, together with the ⛔ never-populate-the-default-bucket
  rule and the map-key encoding warning.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: doc/user/environment-variable-overrides.adoc still tabulates the material keys and never-populate-default-bucket rule
- OBSERVED: `QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME` selects the key-less `plain-management` bucket
  and is *"the deployment door onto the same lever as `gateway.yaml`'s `management.tls.enabled: false`;
  both land on the same runtime key"* — read at `environment-variable-overrides.adoc:154-172`. Scenario 5
  must present them as one scenario with two doors, never as two scenarios.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: environment-variable-overrides.adoc:205 still frames the tls-configuration-name/enabled:false doors
- OBSERVED: `ApiSheriff-115` is the boot WARN for a plain-HTTP management interface and reports the
  **observed effective state** — *"the TLS material the listener actually resolved — not a declared
  key, so it cannot drift from reality when the activation route changes"* — read at
  `doc/LogMessages.adoc:71`. This is the model for deliverable 4's failure-mode sections.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: doc/LogMessages.adoc still carries ApiSheriff-115 with the observed-effective-state framing
- OBSERVED: every scenario has a shipped worked example to draw real values from — the mTLS instance
  at `integration-tests/src/main/docker/sheriff-config-mtls/gateway.yaml` (whose header states the
  ONLY intentional difference from the primary is its `tls` block), the passthrough-empty variant at
  `sheriff-config-passthrough-empty/` (whose header states *"THE ABSENCE OF `tls.passthrough_sni` IS
  THE FEATURE UNDER MEASUREMENT"*), `tls_profile: benchmark-idp` in three shipped `gateway.yaml`
  files, and `deployment/compose-sample/` as the production-shaped stack.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: sheriff-config-passthrough-empty/gateway.yaml still carries the absence-is-the-feature header
- OBSERVED: declaring a non-empty `tls.passthrough_sni` is what **starts** the front listener; with it
  absent the terminated Quarkus listener keeps the public port — read at
  `api-sheriff/src/main/resources/application.properties:100-105`. Scenario 3's precondition section
  must state that this is the trigger, not a mode flag.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: application.properties:208 still states front listener starts only when tls.passthrough_sni is declared
- HYPOTHESIS: scenario 6's precondition is materially affected by whether a configured truststore
  **replaces** or **extends** the platform CA bundle — an operator with a private IdP *and* any
  public-CA outbound dependency needs a concatenated bundle rather than their CA alone.
  Confirm/refute via PLAN-07, which owns that question (verify-at-outline). ⛔ **Do not document
  scenario 6's trust precondition before PLAN-07 has settled it** — this is the one scenario whose
  correctness depends on an unverified claim, and shipping a confident recipe built on it would
  publish an assertion this project has not checked.
- Verify-first clause: every configuration block is settled against the **shipped configuration files
  and a real boot**, never against this spec's table, the existing prose, or a derived
  environment-variable spelling. ⛔ The catalogue above is a **starting point the outline must
  confirm**, not a settled contract: a scenario that turns out not to be supported must be removed and
  said so, never documented into apparent existence.

## Expected Surface

- OBSERVED: `doc/user/` — the new scenario guide (filename TBD at outline; `tls-scenarios.adoc` is the obvious candidate)
- OBSERVED: `doc/user/README.adoc` — the index row
- OBSERVED: `doc/user/tls-edge.adoc` — reciprocal "see also" link
- OBSERVED: `doc/user/environment-variable-overrides.adoc` — reciprocal "see also" link
- HYPOTHESIS: `doc/configuration.adoc` — a pointer from the reference table, only if the outline finds a natural site (verify-at-outline)
- OBSERVED: `doc/resources/diagrams/` — the new key-material diagram (deliverable 6; filename TBD at outline)

⛔ **Deliverable 6 BREAKS THIS PLAN'S DOCUMENTATION-ONLY FOOTPRINT.** An `.svg` is **not** in
CLAUDE.md's documentation-only enumeration (`*.adoc`, `*.md`, `.claude/**`, non-build config), so a
commit carrying one is **gate-requiring**: run the quality gate and full verify. ⚠ The Dependencies
note below predates this fold — read it together with this paragraph, not instead of it.

## Dependencies and Sequencing

- **Depends on: PLAN-07 — hard, but for scenario 6 ONLY.** The trust precondition depends on the
  replaces-vs-extends question PLAN-07 settles. ⚠ **Scenarios 1-5 are documentable today** and depend
  on nothing: if the operator wants operator-facing value sooner, this plan can legitimately be split
  into "ship 1-5 now, add 6 after PLAN-07" — recorded here so that option is visible rather than
  discovered late.
- **PLAN-08 extends this document, not replaces it.** Scenario 7 is added into this same guide when
  the plain-HTTP mode lands. ⛔ A second scenario guide is the failure mode to avoid.
- Overlaps with: PLAN-07 on `doc/user/` and the TLS documentation graph. Surface-disjoint from
  PLAN-02, PLAN-03, PLAN-04, PLAN-05 and PLAN-06 in *code* — this plan touches **documentation only**.
- ⛔ **NO LONGER a documentation-only footprint — see the Expected Surface ⛔.** Deliverable 6 adds an
  `.svg`, which CLAUDE.md's enumeration does not cover, so **both gates run**. The original note read:
  *Per CLAUDE.md a commit whose entire footprint is `*.adoc` under `doc/**` skips both the quality gate and full verify.* Confirm the footprint really is documentation-only
  before relying on that: one non-doc file makes the whole commit gate-requiring.
- Adjacent to: `tls-edge.adoc` and `environment-variable-overrides.adoc`. **Linked, never restated** —
  see the Objective's ⛔.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-09-tls-scenario-guide.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.
