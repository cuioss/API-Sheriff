# PLAN-12: Sonar Zero-Findings Sweep + Compliance Policy

epic: api-sheriff-roadmap
workstream: WS-02

> Staged plan spec. NEW 2026-07-21 (operator: "document a strict zero findings policy for
> sonar" → "fix all open issues"). Reshaped 2026-07-21 after verifying the gate is
> cuioss-org-owned: this plan does NOT define or modify the gate — it drives the code to zero
> findings against the org gate and documents compliance. Runs FIRST, before PLAN-04.

## Objective

Drive the project to **zero open Sonar findings** — clear the entire current backlog (59
issues) so the cuioss-organization quality gate is green and the codebase establishes the zero
baseline that makes "zero *new* findings" enforceable from the next plan onward — and document
the zero-findings policy as **compliance** with the org-owned gate.

## Ground Truth (verified 2026-07-21)

- **The gate + rules are cuioss-ORGANIZATION-owned, not per-repo.** This repo declares only
  `.github/project.yml` → `sonar: { project-key: cuioss_API-Sheriff, enabled: true,
  skip-on-dependabot: true }`. All CI is `cuioss/cuioss-organization/.github/workflows/
  reusable-*.yml@v0.13.0`; the scan runs inside `reusable-maven-build.yml` with the org
  `SONAR_TOKEN`. No pom in API-Sheriff / TokenSheriff / cui-http defines `sonar.organization`,
  a project key, or a quality gate. **Gate conditions + quality profiles live in the cuioss
  SonarCloud org, shared across repos.**
- **The gate is already a REQUIRED check.** PLAN-10 archive: "Merge blocked by required check:
  SonarCloud QG fails on new_reliability_rating=3". Nothing to configure repo-side to make it
  blocking.
- **Operator has configured the sonar credentials** (2026-07-21) — the plan-marshall
  `workflow-integration-sonar` skill can now fetch findings natively.
- **Live backlog (SonarCloud API 2026-07-21): 59 open issues** — 2 BUG + 57 CODE_SMELL; 0
  vulnerabilities, 0 hotspots-to-review. Severity: 4 CRITICAL, 35 MAJOR, 20 MINOR.
- **The gate vs the policy differ, deliberately.** The gate is red only on
  `new_reliability_rating=3` (the 2 BUGs); the 57 smells sit under `new_maintainability` which
  is rating A, so they do NOT fail the gate. Fixing the 2 bugs alone would turn the gate green.
  The operator's zero-findings policy is **stricter than the org gate** — it requires clearing
  all 59, not just the gate-failers.

## Explicitly OUT of scope (org-owned or already done — do NOT attempt)

- Wiring the provider — project key already declared; credentials already configured.
- Making the gate blocking — already a required check.
- Defining / modifying gate conditions or quality profiles — these are **cuioss-org assets**;
  a per-repo plan must not touch them and lacks the permission. If the org gate itself needs
  changing (e.g. to fail on smells too), that is an **operator/org-admin escalation**, recorded
  as a follow-up — not work this plan performs.

## Deliverables (indicative — the plan defines the final breakdown)

1. **Drive all 59 open findings to zero.** Enumerated so none is abstracted away at outline:
   - **2 BUGs (gate-failers), `java:S3655`** — unguarded `Optional` at
     `RouteTableBuilder.java:179` and `RouteRuntimeAssembler.java:133`. Guard each; add tests
     exercising the empty-`Optional` path so the fix is proven. Clears the red gate.
   - **4 CRITICAL** — `S1192` duplicate-literal / use-defined-constant at
     `ConfigValidator.java:409` (`REQUIRE_BEARER`), `:414` (`REQUIRE_SESSION`),
     `TokenValidatorProducer.java:130` ("Issuer "); and `S3776` cognitive-complexity 16→15 at
     `ConfigValidator.java:322`.
   - **The mechanical bulk (mostly MAJOR/MINOR)** — `S5778` assertThrows-single-throwing-call
     (×21, almost all test files), `S7467` unnamed-pattern `_` (×14), `S6878` record-pattern
     (×2), `S6916` pattern-match guard (×3), `S1612` method-reference (×3), `S5976`
     parameterized-test (×3), `S6353` `\w` char class (×2), `S1068` unused field, `S4276`
     `UnaryOperator`. These align with the project's "Java 25 features encouraged" standard.
   - **Care items — NOT blind auto-fixes** (understand before changing): `S2589`
     always-`false` expression at `DispatchStage.java:108` (a dead branch may signal a real
     logic bug — investigate before deleting), `S112` generic-exception at
     `DispatchStage.java:228` (replace with a specific type; check callers/catchers), `S125`
     commented-out code at `DispatchStageTest.java:193` (confirm truly dead), `S3776` (refactor
     for complexity behavior-preservingly, covered by tests).
   - ~1/3 of these are **pre-existing** PLAN-02/03 code (`ConfigLoader`, `EnvSecretResolver`,
     `DispatchStage`, the pipeline stages) — this is a **one-time debt-clear** to set the zero
     baseline, not a regression from one plan.
   - **Fix by default; suppress-with-rationale is the sensible escape hatch** (operator
     clarification 2026-07-21). The general goal is to *fix*. Where a fix is genuinely not
     sensible (a false positive, a deliberate idiom, a rule that fights the design), an **in-code
     suppression carrying a documented rationale** — `// NOSONAR <why>` or
     `@SuppressWarnings("java:SXXXX")` with a justifying comment — is an acceptable way to reach
     zero, because the decision stays auditable in the repo next to the code. What is NOT
     acceptable is reaching zero by **silent server-side won't-fix / false-positive marking** in
     the Sonar UI, which leaves no trace in the code. Every fix keeps the org gate green and new
     coverage ≥ 80%.
2. **Document the compliance policy.** A "Sonar / Quality Gate" section in `CLAUDE.md` and a
   contributor page under `doc/development/` stating: the **cuioss-org SonarCloud gate is
   authoritative and blocking**; zero new findings; never merge over a red gate or a stale
   green while analysis is pending; and a plan is **not "done" / "all green" until the
   post-merge PROJECT gate is green** (the report must reflect that, not a transient
   PR-scoped new-code green). **Reference the org gate as the source of truth; do NOT restate
   its thresholds** (they would drift from the upstream definition). Note the PR-new-code vs
   project-analysis nuance so future reports can be audited against it. The policy page must
   also state the **fix-by-default / suppress-with-rationale** rule: fix is the goal; an in-code
   `// NOSONAR` / `@SuppressWarnings` with a documented rationale is the acceptable escape hatch
   where a fix is not sensible; silent server-side won't-fix marking is not.

## Expected Surface

- `api-sheriff/` — findings fixes across ~15 main files + several test files (asset/, config/,
  edge/, auth/ packages); `CLAUDE.md`; `doc/development/`. No feature change, no config-model
  change, no gate/CI-config change (org-owned).

## Dependencies and Sequencing

- **Runs FIRST — sequential, before PLAN-04** (operator 2026-07-21, reversing the earlier
  parallel call). Reason the reversal is correct: expanding to fix-all pulls in PLAN-04's own
  edge/pipeline surface — `DispatchStage`, `GatewayEdgeRoute`, `RouteRuntimeAssembler`,
  `ForwardPolicyStage`, `TcpPeerGate` — so parallel would mean repeated edge-package merge
  conflicts, not the single-file rebase that justified parallel when the scope was two bugs.
  Landing PLAN-12 first gives PLAN-04 a **green, zero-findings baseline**; PLAN-04 then only has
  to keep its own new code clean.
- Builds on: PLAN-10 (some findings are its code; `doc/development/` is its tree).
- No operator-gated steps remain (credentials done; nothing org-side to change unless the
  operator later wants the org gate itself to fail on smells — an escalation, not this plan).

## Scope-Bloat Note

Two deliverables, but deliverable 1 is a **59-item sweep** — large by volume, mostly mechanical
(Java-25 modernization + test-lambda refactors). The real risk is the 4 care items and the
sheer number of test-file edits (keep coverage ≥ 80%). Not a feature; a debt-clear. If the
sweep proves larger than one clean PR, split by package (asset/config/edge/auth) — but the two
gate-failing bugs must land in the first PR so main goes green fast.

## Hand-Off Command

```text
/plan-marshall Drive the API Sheriff codebase to ZERO open SonarCloud findings and document the zero-findings compliance policy, per the staged spec (operator 2026-07-21). GROUND TRUTH you must respect: the SonarCloud quality gate and quality profiles are cuioss-ORGANIZATION-owned and shared across all cuioss repos — this repo declares only .github/project.yml sonar {project-key: cuioss_API-Sheriff, enabled, skip-on-dependabot}, and CI runs the scan inside cuioss/cuioss-organization reusable-maven-build.yml. Therefore DO NOT define, modify, or add a quality gate, quality profile, gate thresholds, or CI/branch-protection settings — those are org assets and out of scope (the gate is already a required, blocking check; credentials are already configured). If you conclude the ORG gate itself should change (e.g. fail on code smells), STOP and surface it as an operator/org-admin escalation rather than doing it. (1) FIX ALL 59 OPEN FINDINGS to zero (verified via SonarCloud API 2026-07-21: 2 BUG + 57 CODE_SMELL; 0 vulnerabilities; 0 hotspots): the 2 gate-failing java:S3655 unguarded-Optional BUGs at api-sheriff/src/main/java/de/cuioss/sheriff/api/config/RouteTableBuilder.java:179 and .../edge/RouteRuntimeAssembler.java:133 (guard each, add empty-Optional tests, this clears the red gate); the 4 CRITICAL (S1192 use-defined-constant at ConfigValidator.java:409/414 and TokenValidatorProducer.java:130; S3776 cognitive-complexity at ConfigValidator.java:322); and the mechanical bulk (S5778 assertThrows single-throwing-call x21 mostly tests, S7467 unnamed-pattern x14, S6878 record-pattern, S6916 pattern-match-guard, S1612 method-reference, S5976 parameterized-test, S6353 \w char-class, S1068 unused-field, S4276 UnaryOperator) — these align with the project's Java-25-features-encouraged standard. CARE ITEMS, not blind auto-fixes: S2589 always-false expression at DispatchStage.java:108 (a dead branch may be a real logic bug — investigate before deleting), S112 generic-exception at DispatchStage.java:228 (replace with a specific type, check callers), S125 commented-out code at DispatchStageTest.java:193 (confirm truly dead), S3776 (refactor behavior-preservingly under test). ~1/3 are pre-existing PLAN-02/03 code — this is a one-time debt-clear to set the zero baseline. Fix by default, but suppression-with-rationale is a sensible escape hatch (operator clarification 2026-07-21): the general goal is to FIX, and where a fix is genuinely not sensible (false positive, deliberate idiom, a rule fighting the design) an IN-CODE suppression carrying a documented rationale (// NOSONAR <why> or @SuppressWarnings("java:SXXXX") with a justifying comment) is an acceptable way to reach zero because it stays auditable in the repo next to the code — what is NOT acceptable is reaching zero by silent server-side won't-fix/false-positive marking in the Sonar UI; keep the org gate green and new coverage >= 80% throughout; if the sweep is too large for one PR split by package but land the 2 gate-failing bugs in the first PR so main goes green fast. (2) DOCUMENT THE COMPLIANCE POLICY (the doc/development/ tree exists since roadmap plan 10): a "Sonar / Quality Gate" section in CLAUDE.md and a contributor page under doc/development/ stating the cuioss-org SonarCloud gate is authoritative and blocking, zero new findings, never merge over a red gate or a stale green while analysis is pending, and a plan is NOT "done"/"all green" until the post-merge PROJECT gate is green (the completion report must reflect that, not a transient PR-scoped new-code green). REFERENCE the org gate as the source of truth — do NOT restate its thresholds, which would drift — and note the PR-new-code-vs-project-analysis nuance so future reports can be audited. The page must also state the fix-by-default / suppress-with-rationale rule: fixing is the goal, an in-code // NOSONAR or @SuppressWarnings with a documented rationale is the acceptable escape hatch where a fix is not sensible, and silent server-side won't-fix marking is not. AUDITABILITY (standing convention): carry the rationale into the tracked docs, not only the mechanical change. No feature change and no config-model change is expected — if one becomes necessary, stop and report it.
```

## Status Trail

- plan_marshall_plan_id: (set at launch)
- pr: (set when the PR opens)
- landing: (set when recorded at landings/PLAN-12.md)
```
