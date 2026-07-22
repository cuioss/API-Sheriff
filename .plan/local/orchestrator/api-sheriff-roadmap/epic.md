# Epic: API Sheriff Implementation Roadmap (Plans 02b-09)

slug: api-sheriff-roadmap

> Ledger document for one epic under `.plan/local/orchestrator/api-sheriff-roadmap/`. The layout and
> authority contract live in the central standard — see
> `persona-marshall-orchestrator/standards/orchestration-model.md`. `status.json` is the
> machine authority; any statement here that conflicts with it is stale prose.

## Vision

Drive the remaining API Sheriff implementation plans (`doc/plan/02b` through `09`) to completion,
one plan-marshall plan per roadmap plan, in the dependency order the roadmap's own sequencing
rationale defines. Plans 01 and 02 are already delivered. "Done" at the epic level is the 1.0.0
release cut (roadmap plan 09) with every prior plan landed, integration-tested, and benchmarked
per the roadmap conventions. The source of truth for each plan's content is its `doc/plan/*.adoc`
document — the design docs govern, the plans only sequence the work.

## START HERE

<!-- GENERATED BLOCK — never hand-write or hand-edit this section.
     Regenerate after every queue-touching state change via:
     python3 .plan/execute-script.py plan-marshall:marshall-orchestrator:orchestrator resume-summary --slug api-sheriff-roadmap
     Paste the returned block verbatim between the markers. -->

<!-- BEGIN GENERATED: resume-summary -->
**Resume anchor**: PLAN-14 SHIPPED 2026-07-22 (PRs #91+#92+#94 → 36541f6; landing landings/PLAN-14.md, every claim independently verified): gateway@0.1.0-SNAPSHOT live, guard dispatch-only, relocations 1.0.1 on repo1, deploy-skips working. DEPLOY-SNAPSHOT RESOLVED (root cause: test-module upload payload; fixed by the deploy-skip; org escalation retired). Residuals: stray immutable api-sheriff-relocations:1.0.1 (accepted); relocation-stubs branch NEVER merge (watch); 3 stale enriched.json old-GA refs (cleared by pending enrichment). NO plan in flight. NEXT: launch PLAN-13 (REFRESHED command in plans/PLAN-13-sonar-residual.md — A verify/close, B rebase #70 over rename first, C pin rationale only, D fresh Sonar query, old 34 baseline stale after 168-package rename). After PLAN-13: emit PLAN-05 — decide deferred-ADR fold + ADR-0010/0012 re-check. OPERATOR ACTIONS: (1) architecture enrichment — now fixes BOTH documentation.implementation AND stale enriched.json; (2) upstream verify-on-version: two daemon defects (false-green build verdicts, ci-pr-merge false-merged destructive) reportedly fixed in 0.1.1182 (landed #90) — verify at next build/plan contact, keep .plan/temp/plan-marshall-daemon-false-green.md until confirmed; credentials-key-prefix + lessons -002/-003 filings still open. LESSONS CORPUS lives at .plan/local/orchestrator/lessons-handling-26-07-22-01/lessons/ (17, orchestrator-owned, operator-designated home; plugin recreates .plan/local/lessons-learned at next phase-6 — sweep it each lessons run). STANDING SONAR POLICY + three-layer docs + decisions-in-plan-file stand. Open for PLAN-08: boot-TOCTOU, placeholder-coercion, PLAN-10 controls re-verify, PLAN-09 anti-patterns, readiness-disclosure; release cut = de.cuioss.sheriff.gateway 0.1.0 via manual dispatch. Queue: PLAN-13(re-emitted) -> PLAN-05 -> PLAN-06 -> PLAN-07 -> PLAN-11 -> PLAN-08.
**Phase**: orchestrating
**Queue** (staged, in order):
1. PLAN-13 (WS-02)
2. PLAN-05 (WS-02)
3. PLAN-06 (WS-03)
4. PLAN-07 (WS-03)
5. PLAN-11 (WS-03)
6. PLAN-08 (WS-04)
- PLAN-01 (WS-01) — plan=plan-02b-reconciliation — PR #72 — landing=landings/PLAN-01.md — status: shipped
- PLAN-02 (WS-02) — plan=plan-03-endpoint-anchors — PR #74 — landing=landings/PLAN-02.md — status: shipped
- PLAN-03 (WS-02) — plan=plan-04-request-pipeline — PR #76 — landing=landings/PLAN-03.md — status: shipped
- PLAN-09 (WS-02) — plan=plan-04b-comparative-benchmark — PR #82 — landing=landings/PLAN-09.md — status: shipped
- PLAN-10 (WS-02) — plan=anchor-types-assets — PR #84 — landing=landings/PLAN-10.md — status: shipped
- PLAN-12 (WS-02) — plan=sonar-zero-findings — PR #89 — landing=landings/PLAN-12.md — status: shipped
- PLAN-04 (WS-02) — plan=protocol-processors-ws-grpc — PR #88 — landing=landings/PLAN-04.md — status: shipped
- PLAN-14 (WS-04) — plan=groupid-relocation-release-guard — PR #91 #92 #94 — landing=landings/PLAN-14.md — status: shipped
<!-- END GENERATED: resume-summary -->

## Ordered Queue

| # | Plan | Workstream | Status | Surface (expected) | Notes |
|---|------|------------|--------|--------------------|-------|
| 1 | PLAN-01-reconciliation | WS-01 | shipped | doc/**, README.adoc, CLAUDE.md, ConfigValidator+schema+tests, integration-tests pom/scripts, benchmarks scripts+post-processor | Roadmap 02b. PR #72 merged 2026-07-18 (715f19e); landing: landings/PLAN-01.md |
| 2 | PLAN-02-endpoint-anchors | WS-02 | shipped | config loading/validation/schema, route table, ADR-0004/0007 docs, IT fixtures | Roadmap 03. PR #74 squash-merged 2026-07-18 (a9fd717); landing: landings/PLAN-02.md |
| 3 | PLAN-03-request-pipeline | WS-02 | shipped | new pipeline/dispatch/event/metrics packages, removes ProxyRoute, ITs, benchmarks | Roadmap 04. PR #76 squash-merged 2026-07-19 (9c47959); landing: landings/PLAN-03.md |
| 3b | PLAN-09-comparative-benchmark | WS-02 | shipped | benchmarks module reworked to k6 (scripts, image, post-processor, tests), integration-tests APISIX stack + apisix.yaml, doc/plan 04b + README convention | NEW 2026-07-19 (operator). FULL k6 replacement of wrk (decision 2026-07-19) + APISIX baseline, 6 HTTP-family aspects (unauth, bearer, h2, graphql, upload 1MB/50MB). BEFORE PLAN-04 — it needs this harness for ws/grpc benchmarks. Comparison on-demand, NOT CI. PR #82 squash-merged 2026-07-20 (fbf84e3); landing: landings/PLAN-09.md. **8/8 shipped; wrk removal verified complete (zero tracked wrk/lua files). The documentation-tree bootstrap did NOT ship — it was folded into the spec after the command was emitted, so it never executed; re-homed to PLAN-10** |
| 3c | PLAN-10-anchor-types-assets | WS-02 | shipped | config loading/validation/schema, route-table terminal-action, NEW asset-serving package, ADRs 0013/0014, threat-model GW-11/GW-05, doc/plan/10, NEW doc/user/ + doc/development/ trees | NEW 2026-07-20 (operator); restaged same day to two axes + asset rename + dual sources. PR #84 squash-merged 2026-07-21 (0f7f957); landing: landings/PLAN-10.md. **12/12 shipped (one PR, not the recommended split). D2a startup-path validation verified correct on main. Doc trees + three-layer convention now LIVE. Audit caught two required security controls that were narrative-only (symlink escape, mid-flight size cap); CI caught 3 defects local gates structurally couldn't (native reflection, topology aliases, stale fixture). Payoff realized: PLAN-04/05/06/07 now author against the final config model** |
| 3d | PLAN-12-sonar-hardening | WS-02 | shipped | ALL 59 open Sonar findings → zero (2 S3655 bugs + 57 smells across asset/config/edge/auth), CLAUDE.md + doc/development compliance policy | NEW 2026-07-21 (operator). Plan `sonar-zero-findings` (deep lane, 11 tasks / 7 deliverables incl. the README-badge D7 addendum). PR #89 squash-merged 2026-07-21 (5265ed6); landing: landings/PLAN-12.md. **Gate verified GREEN post-merge** (was red on the 2 S3655). Backlog NOT zero: 34 open post-merge (10 declared residual + 24 S5778/S7467 lag-or-unfixed — D4 sweep ran partially blind, live Sonar HTTPS-blocked locally) → literal-zero re-homed to **PLAN-13**. Compliance docs LIVE (CLAUDE.md + doc/development/sonar-quality-gate.adoc) |
| 4 | PLAN-04-protocol-processors | WS-02 | shipped | protocol processors, Vert.x upstream h2, gRPC echo upstream, ITs, k6 ws/grpc benchmarks + comparison aspects; +GatewayExceptionMapper removal, verify-green-while-errored probe | Roadmap 05. PR #88 squash-merged 2026-07-22-analysis (bdc8948, rebased onto 5265ed6 — second-merger obligation satisfied, 3 conflicts resolved, README D7 lines survived); landing: landings/PLAN-04.md. **14/14 shipped** incl. both folded items; 3 CI loop-back rounds fixed real defects (gRPC anchor-containment exemption, upstream.path materialization bug TASK-027). Verify-green probe closed: build-wrapper, not pom. Sonar new-code 0; backlog unchanged at 34 |
| 4b | PLAN-13-sonar-residual (Project Cleanup) | WS-02 | staged | pom.xml (quarkus pin), dependabot branch #70 (separate PR), deploy-snapshot diagnosis (workflows read-mostly), asset/config + test files (sonar sweep), 2× enriched.json ride-along | NEW 2026-07-21; **EXPANDED + RE-EMITTED 2026-07-22 (operator: "Project cleanup")** — folds three aspects AHEAD of the sonar sweep: **REFRESHED + RE-RE-EMITTED post-PLAN-14 (events overtook two packages)**: (A) deploy-snapshot → VERIFY/CLOSE only (resolved by PLAN-14's deploy-skip; record the root-cause finding); (B) fix PR #70 on its dependabot branch — REBASE over the 168-package gateway rename first; (C) quarkus pin → #69 already auto-merged (3.37.3 current); remaining: parent-managed-or-legitimate analysis + rationale pom comment only; (D) sonar literal zero from a FRESH query — the 34 baseline is STALE by construction after the package rename (findings re-anchor). Ride-along dropped (tree clean). Launch the NEWEST command from plans/PLAN-13-sonar-residual.md |
| 4c | PLAN-14-coordinates-relocation | WS-04 | shipped | release.yml (PR1), root+module poms GAV/versions/deploy-skips, .github/project.yml, README badge, doc/** sweep (PR2), old-GA relocation POMs on Central (off-cycle) | NEW 2026-07-22 (operator: groupId **de.cuioss.sheriff.gateway** chosen, "ensure no more accidental releases"). Three strictly-ordered stages: PR1 disarms release.yml (workflow_dispatch ONLY — the safety gate); PR2 restructures (new GA, 0.1.0-SNAPSHOT, project.yml 0.1.0/0.2.0-SNAPSHOT, test-module deploy-skip, badge+docs); off-cycle publishes relocation POMs 1.0.1 at old GA (parent + api-sheriff only). ~8 deliverables over the split guard — proceeding as one plan: atomic identity change, the PR split IS the safety structure (recorded rationale). **SHIPPED 2026-07-22**: PRs #91 (guard, c2636bd) + #92 (restructure, 99021b5) + #94 (javadoc/one-shot cleanup, 36541f6); landing: landings/PLAN-14.md. All outcomes independently verified (relocations live on repo1, guard + incident comment on main, deploy-skips working). **BONUS: deploy-snapshot 3-hang issue RESOLVED by the deploy-skip** (job green 1m40s; root cause = test-module upload payload). Grep proof resolves to deliberate exceptions (gRPC wire package, incident docs) + stale enriched.json (fixed by pending enrichment). Residuals: stray immutable api-sheriff-relocations:1.0.1 on Central (accepted, cosmetic); release/relocation-stubs branch retained, NEVER merge (watch) |
| 5 | PLAN-05-tls-edge | WS-02 | staged | accept path/TLS/L4 relay, compose stack (Toxiproxy), ITs | Roadmap 06. Independent of PLAN-04; shares Vert.x transport neighborhood |
| 6 | PLAN-06-bff-server-session | WS-03 | staged | BFF/session packages, reserved paths, token-sheriff-client wiring, Keycloak ITs | Roadmap 07. Largest/riskiest; multi-PR expected. TWO folds (operator 2026-07-20, both doc-first at launch): session/user-info reserved endpoint, AND a login-initiation reserved path (verified gap — D2 defines only four reserved paths and login is only ever implicit) |
| 7 | PLAN-07-bff-cookie | WS-03 | staged | cookie token-binding on BFF packages | Roadmap 08. Strictly after PLAN-06 (same surface) |
| 7b | PLAN-11-demo-client-e2e | WS-03 | staged | NEW top-level demo-client/ module (SPA + Playwright), root pom module list, integration-tests compose reuse, doc/** integration sample | NEW 2026-07-20 (operator). Simple JS/SPA demo proving the BFF end-to-end AND shipping as the integration sample. Both variants under one suite; functional + screenshots only (no a11y). First real consumer of PLAN-10 asset serving (`type: asset` + `access: public`). After PLAN-07, before PLAN-08 — the release cut must be able to reference and audit it. No api-sheriff/ production-source change expected |
| 8 | PLAN-08-release-readiness | WS-04 | staged | doc/** audit of user+development trees, hardening, benchmark baselines, release plumbing | Roadmap 09. Last; depends on all prior. **Doc role changed 2026-07-20**: reviews/completes the doc trees rather than authoring the operator guide, and attributes gaps to the plan that should have documented them |

## Decisions

- 2026-07-17 — Epic created from the `doc/plan/` roadmap at operator request ("pickup doc/plan
  and drive me through the next plans"). Plans 01 and 02 are delivered (per `doc/plan/README.adoc`);
  the epic queue starts at roadmap plan 02b, which the operator and the roadmap's sequencing
  rationale both identify as next.
- 2026-07-17 — Decomposition: 4 workstreams cut along the roadmap's own sequencing rationale
  (correction sweep / data plane / BFF track / release), 8 plans staged 1:1 with roadmap plans
  02b-09. Alternative considered: splitting roadmap 04 and 07 at staging time — rejected; the
  roadmap docs already define their internal PR splits, so the split decision is deferred to
  each plan's own lifecycle and re-evaluated at emit time (scope-bloat guard noted in both specs).
- 2026-07-17 — PLAN-01 (02b) deliverable count: the plan doc enumerates ~20 items but they form
  4 tightly-related correction groups with one PR expected by the doc itself; proceeding unsplit
  per the split guard's recorded-rationale path.
- 2026-07-18 — PLAN-01 landing analyzed (full ship): all 17 deliverables + 4 review-fix tasks
  corroborated against ground truth (0 hamcrest imports, whole-second rule gone, invalid-config
  script wired, merge commit 715f19e on main). No surface collisions observed. WS-01 closes.
- 2026-07-18 — Build-server verification (operator request): marshalld was up and the project
  enrolled, but with an empty notation allowlist — the daemon refused every submit
  (notation_not_allowlisted) and builds silently fell back in-process. Re-registered with
  `plan-marshall:build-maven:maven` + the `.plan/local/worktrees` container; end-to-end
  verified (canonical compile routed, job 560f57c2, exit 0). Worktree-build routing watched
  until PLAN-03 lands.
- 2026-07-18 — Merge-method watch retired: operator reports the merge-queue/pr_merge_strategy
  mismatch fixed (AskUserQuestion outcome: "I fixed"). Verify on the PLAN-02 PR merge — it
  should land as a single squash commit.
- 2026-07-18 — PLAN-02 landing analyzed (full ship): 6/6 deliverable groups corroborated
  (ADR-0007 Accepted, convention env lookups deleted, anchors in schema, ADR-0010 proposed
  on disk). Squash merge verified — a9fd717 has one parent, confirming the operator's
  merge-queue fix works; that thread is fully closed. Review loop-backs improved D3
  (per-route auth mandatoriness) and D5 (alias-bomb protection moved to a compose-only
  pre-pass after discovering setMaxAliasesForCollections is inert via Jackson).
- 2026-07-19 — New plan staged by operator direction: PLAN-09-comparative-benchmark — an
  APISIX single-node standalone reference stack (same endpoints, same Keycloak token
  validation) plus a target-neutral wrk harness swapped by compose stack, for on-demand
  development comparison runs (explicitly NOT CI-wired; BFF out of scope). Queued directly
  after PLAN-03: the comparison needs the real data plane's bearer route, and the
  benchmarks/ surface overlaps PLAN-03's benchmark deliverable. Alternatives considered:
  folding into PLAN-03 (rejected — PLAN-03 already trips the split guard at 13 items) or
  deferring to PLAN-08/release (rejected — operator wants numbers during development).
  The plan authors doc/plan/04b-comparative-benchmark.adoc doc-first, keeping the repo's
  plan corpus complete.
- 2026-07-19 — PLAN-09 expanded by operator to the full aspect matrix (unauthenticated,
  bearer, http2, graphql, websocket, grpc, upload-normal, upload-large; BFF excluded).
  Consequences: repositioned AFTER PLAN-04 — websocket/gRPC processors and their echo
  upstreams exist only from roadmap 05; wrk is HTTP/1.1-only, so the spec mandates a pinned
  second-tool decision (k6 unified vs h2load+ghz specialists) recorded doc-first in the 04b
  doc; upload sizes set to 1 MB (normal) and 50 MB (large — 10× the operator's 5 MB
  threshold, streaming territory, throughput+latency metrics, reduced concurrency). The
  runner gains an aspect-subset mode so HTTP-only partial comparisons are possible between
  PLAN-03 and PLAN-04 landings without the plan itself splitting.
- 2026-07-19 — Benchmark framework decision (AskUserQuestion, operator confirmed on re-ask):
  FULL k6 replacement of wrk, repo-wide. Decisive rationale: wrk is HTTP/1.1-only and cannot
  serve roadmap 05's convention-mandated ws/grpc benchmarks — a second tool was inevitable,
  and one toolchain beats two permanently; the 02b custom error gate becomes native k6
  thresholds; the raw-throughput trade-off is accepted because measurements are relative;
  one-time pages-baseline restart accepted. PLAN-09 restaged (k6 migration + APISIX + six
  HTTP-family aspects) and repositioned BEFORE PLAN-04, which now ships its ws/grpc
  benchmarks in k6 and extends the comparison matrix. PLAN-03 stays untouched (its wrk
  bearer benchmark joins the migration set).
- 2026-07-19 — Benchmark output-format decision: k6 binds into the cuioss generic format
  (de.cuioss.sheriff.token:benchmarking-common) via its designed seam — a new
  K6BenchmarkConverter fed by handleSummary() JSON; badges, history, trends, and the GitHub
  Pages HTML continue unchanged. k6-native reporting (k6-reporter, xk6-dashboard) is
  single-run only and supplements but never replaces the generic format; upstreaming the
  converter to benchmarking-common proposed in the 04b doc as cross-repo follow-up.
- 2026-07-19 — PLAN-03 landing analyzed (full ship): PR #76 squash-merged (9c47959), 12/12
  deliverables corroborated (pipeline packages on main, placeholder GatewayResource deleted,
  bearer wrk benchmark present, 23 ITs, Sonar 87.2%). The plan's own gates caught real
  defects in-flight (unwired meters/retry gate, dormant D4 logging, Content-Length
  hop-by-hop bug, smuggling guard from bot review). Build-server worktree watch retired
  VERIFIED — 62 daemon jobs served the plan's builds. New: readiness-disclosure defect,
  GatewayExceptionMapper cleanup watch (→ PLAN-04 emit). PLAN-09 emitted next.

- 2026-07-20 — BFF session/user-info endpoint folded into PLAN-06 (operator request +
  AskUserQuestion): the browser client needs an endpoint returning info from within the
  token; 401 without a valid/active session. Variant chosen: ONE reserved path with a
  curated default set (sub, name, roles + session expiry/auth_time/acr) and a claims/view
  parameter, every response filtered through a config-side claim allowlist with a secure
  default — the operator caps disclosure, never the client. Rejected: single "full"
  endpoint (maximal PII to browser JS, no operator control) and separate status+claims
  endpoints (more reserved-path surface, two round trips). Hard rules recorded in the
  spec: never raw tokens in any view; 401 problem+json without redirect (composes with
  D4 content negotiation); 403 reserved for CSRF/disallowed-claim; Cache-Control:
  no-store. Built above the session-resolution seam so PLAN-07 (cookie) inherits with
  minimal delta. Not yet in doc/plan/07 — the plan folds it doc-first (variant doc 02 +
  configuration.adoc + plan doc, same PR). Spec + hand-off command updated.

- 2026-07-20 — Anchor `type` + static serving staged as NEW PLAN-10 (operator +
  AskUserQuestion). Ground truth checked: anchors (ADR-0007, delivered by PLAN-02) carry
  `path_prefix` + policy blocks and have NO type field; every anchor implicitly terminates
  in a proxy, and static serving appears nowhere in the design corpus — so this is genuinely
  new design, staged doc-first. Operator chose the EXPLICIT type enum (`passthrough` |
  `authenticated` | `bff` | `static-public` | `static-authenticated`) as the anchor's primary
  discriminator, over the orthogonal action+auth axes I recommended. Decisive operator
  rationale (must survive into the ADR): the gateway must stay open to auth kinds beyond
  today's OAuth/OIDC — mTLS, API key, future schemes — so deriving type from the
  `bearer|session|none` enum would hard-wire the taxonomy to OAuth shapes; an explicit type
  keeps anchor intent stable while the mechanism underneath evolves. Operator added a hard
  requirement: boot validation enforces that every non-`passthrough` type resolves to at
  least one VALID, fully-backed auth config — not merely a non-`none` keyword. Static serves
  a mounted directory (SPA deploys independently of the gateway image); classpath-embedded
  resources out of scope. Staged as its own plan rather than folded into PLAN-06:
  `static-authenticated` needs `require: session` to exist, and PLAN-06 already absorbed the
  info endpoint. Noted as a new file-system trust boundary in a security gateway (traversal
  defence, dotfile deny, fixed content-type map, `no-store` for authenticated static).
- 2026-07-20 — CORRECTION + repositioning (operator). My PLAN-10 dependency claim was wrong:
  `static-authenticated` does NOT require a session — a bearer/OAuth token backs it just as
  legitimately, which is D1's own auth-kind-agnostic rationale applied consistently (the type
  declares intent, the auth block declares mechanism). Verified against ground truth: bearer
  enforcement shipped with PLAN-03 (roadmap 04 stage 4); `require: session` boot-rejects until
  PLAN-06. So the PLAN-06 dependency dissolves — only `type: bff` needs session, and D2
  validation legitimately failing a `type: bff` anchor until PLAN-06 lands IS the intended
  fail-closed behavior (assert it in a test, never work around it). PLAN-10 repositioned from
  after-PLAN-06 to after-PLAN-09 / before-PLAN-04 (AskUserQuestion, operator confirmed):
  the anchor taxonomy is a foundational config-model change and PLAN-04/05/06/07 all declare
  anchors, so landing `type` first means they author against the final model instead of
  retrofitting; the boot-TOCTOU defect also gets fixed sooner.
- 2026-07-20 — Demo SPA + Playwright E2E staged as NEW PLAN-11 (operator). Dual purpose is the
  decision: the client is simultaneously the **proof** the BFF works end-to-end against a real
  IdP and the **shipped integration sample** a downstream frontend copies — so its docs and code
  quality carry the same weight as its assertions. New top-level `demo-client/` module holding
  SPA + Playwright together (AskUserQuestion; alternatives were splitting across
  `integration-tests/` or burying it entirely there — rejected: a sample must be discoverable as
  a sample). Mirrors `TokenSheriff/.../e-2-e-playwright`: packaging pom, all Java plugins
  skipped, frontend-maven-plugin, opt-in `skipPlaywrightTests` profile. Functional + screenshots
  only; TokenSheriff's axe-core/WCAG suite excluded **by decision**, not oversight. One suite runs
  both `session.mode: server` and `cookie` — identical observable behaviour IS the assertion,
  since the browser-facing contract is meant to be variant-independent. Serving it through a
  `static-public` anchor makes it PLAN-10's first consumer outside its own tests. Positioned
  after PLAN-07 / before PLAN-08: a demo landing after the release cut proves nothing about the
  release, and PLAN-08's operator guide should reference the sample. npm devDeps +
  frontend-maven-plugin recorded as operator-approved named scope (CLAUDE.md dependency rule).
- 2026-07-20 — GAP CLOSED: login-initiation endpoint folded into PLAN-06 (operator). Staging
  PLAN-11 surfaced that `doc/plan/07-bff-server-session.adoc` D2 defines exactly FOUR reserved
  paths (callback, logout, logout-return, back-channel) — login is only ever triggered
  *implicitly* by D4 content negotiation on a navigation to a `require: session` route. A SPA
  served from a `static-public` anchor therefore gets a `401` from the info endpoint and has **no
  defined URL to navigate to**. Decision (AskUserQuestion): add a fifth reserved path
  (`/{oidc}/login?returnUrl=`) in PLAN-06 under the same D2 rules, reusing the D2b
  pending-authorization record — which already carries a same-origin-validated return URL and the
  browser-binding cookie — so this is a small delta, not new subsystem work. Duende BFF
  `/bff/login` is the prior art, pairing with the `/bff/user` already cited. Rejected: leaving it
  implicit (the sample would teach a workaround) and deferring to PLAN-11 (a BFF-surface change
  landing after both variants shipped and were documented). `configuration.adoc` enumerates four
  reserved paths today and must be updated.
- 2026-07-20 — VERIFIED, NO DEFECT: operator asked whether the info endpoint correctly "returns
  redirect to login". It must **not** redirect an XHR, and the plan is right as written.
  Redirecting a `fetch()` sends the browser chasing the IdP *inside* the fetch, so the SPA gets
  opaque HTML or a CORS failure instead of an actionable signal — D4's content negotiation exists
  to prevent exactly that. Contract stands: XHR → `401 problem+json`; top-level navigation
  (`Accept: text/html`) → `302` into the auth-code flow. PLAN-11 must assert **both halves** — it
  is the contract every real integrator depends on, and an assertion that the info endpoint 302s
  an XHR would encode the bug. Recorded so a future session does not "fix" correct behaviour.
- 2026-07-20 — PLAN-10 RESTAGED on two axes (operator). Operator questioned whether
  `static-public`/`static-authenticated` should be two types at all, since the implementation is
  one handler either way. Correct — but collapsing to a bare `static` loses the fail-closed
  cross-check: with intent derived from `auth`-block presence, a block lost to a bad merge
  silently serves a protected tree publicly, with no signal. The operator had also spotted the
  real wart: the enum was mixing *visibility* into the *action* name, and `passthrough` vs
  `authenticated` was already doing the same thing for proxying. Resolution: split the axes —
  `type: proxy | bff | asset` (action) plus a **required** `access: public | authenticated`
  (intent). Keeps the single asset type the operator wanted, keeps D2's validation target,
  and grows linearly instead of pairwise as new actions appear. Prior-art survey recorded in
  the spec for the ADR: infrastructure proxies (nginx, Caddy, Traefik, Kong, APISIX, Envoy)
  keep the axes orthogonal with no declared intent and all share the silent-go-public failure
  mode; application security frameworks (Spring Security, ASP.NET/Duende) require declared
  intent with deny-by-default. API Sheriff takes the latter. `static` rejected as the type name
  because `configuration.adoc`/`architecture.adoc` already use "static configuration"/"Static
  mode" for *not hot-reloadable* — `asset` avoids overloading a live term.
- 2026-07-20 — Asset sources: directory AND secondary server (operator instruction). Asset
  delivery must not be tied to a volume mount, so the `asset` block takes
  `source: directory | upstream`. The distinction from `type: proxy` is what makes this one
  terminal action rather than two: proxying is transparent mediation, asset serving is opaque
  content delivery where **the gateway owns the response envelope whatever the source** —
  content-type from the fixed extension map (never from the secondary server's `Content-Type`,
  or a sloppy origin gets script execution in the gateway's origin), `no-store` forced on
  authenticated assets regardless of upstream `Cache-Control`, upstream `Set-Cookie` stripped,
  GET/HEAD only. Path canonicalization runs **before** the source is touched, so traversal
  cannot escape the configured base path into another part of the secondary server. The
  upstream source reuses the existing upstream model (`base_url` + path + remainder, shared
  data-plane client, circuit breaker, `upstream_defaults`) and composes with the GW-05 SSRF
  controls — no parallel fetch stack.
- 2026-07-20 — FOUND while staging: the threat model's traversal entry rates itself
  **COVERED/ELIMINATED** on the assertion that "the residual surface is config-load input".
  Asset serving introduces request-path → filesystem-path (and upstream-URL) construction —
  exactly the cited evidence pattern (Tyk CVE-2021-23357, APIID → filesystem traversal) — so
  that rating becomes false the moment PLAN-10 lands. Reopening and updating the entry is now a
  PLAN-10 deliverable; a threat model that under-reports its own surface is worse than one that
  never claimed coverage. The entry's own recorded lesson governs the implementation: the
  Gravitee double-patch (CVE-2019-25075 → CVE-2022-38723, the same traversal patched twice)
  means close the whole class — canonicalize-then-contain plus a full traversal/encoding
  regression corpus, not one spelling.
- 2026-07-20 — **STANDING CONVENTION: every remaining plan documents its aspects in the user
  and developer docs, in its own PR** (operator instruction, applies epic-wide). Ground truth
  that shaped the implementation: `doc/README.adoc` declares its own scope as *design*
  documentation ("what will be built and how the parts fit together"), and the repo has **no
  operator guide and no developer guide at all** — the only user-facing documentation anywhere
  in the roadmap was PLAN-08's "operator configuration guide", landing *last*. That is a
  documentation big-bang: eight plans' worth of operator-facing behaviour reconstructed at
  release time from memory. So the instruction could not simply be appended to each hand-off —
  the target docs had to be created first.
  Structure (AskUserQuestion): `doc/user/` = task-oriented operator guide (getting started,
  deploy, configure, run, troubleshoot); `doc/development/` = contributor guide (build, quality
  gate, ITs, benchmarks, module layout, extension points), matching the sibling TokenSheriff
  repo's existing `doc/development/` convention. Rejected: a single `doc/guide/`
  tree (audiences interleave) and extending the existing files only (both are already large
  reference documents that read poorly as task-oriented guidance).
  **Amended same day, on operator challenge ("is adding configuration covered as well?") —
  it was not, and the first formulation was self-contradictory.** The clause only required
  *linking to* `configuration.adoc`; nothing obliged a plan to *update* it when adding keys.
  PLAN-10 happened to list that deliverable in its own spec, PLAN-04/05/06 did not — per-spec
  luck, not convention. Worse, the clause said "design-doc updates do not satisfy this" while
  `doc/README.adoc` files `configuration.adoc` under *Design Documents*, so the rule excluded
  the very update it should have demanded. Verified: `doc/plan/README.adoc`'s `== Conventions`
  section has exactly four bullets (integration tests, benchmarks, dependency approvals,
  pre-1.0 rules) and **no documentation convention at all**.
  The convention is therefore **three layers**, all in the same PR: **(1) Reference** — every
  new/changed configuration key, default, enum value and endpoint lands in
  `configuration.adoc` (structural changes in `architecture.adoc`), exhaustive and per-key,
  not optional; **(2) Operator guide** — `doc/user/`, task-oriented, linking to the reference
  rather than duplicating its key tables; **(3) Developer guide** — `doc/development/`. The
  anti-rubber-stamp rule now reads "plan-doc, variant-doc or ADR updates do not satisfy any
  layer", which no longer collides with the reference requirement.
  Classification fix folded into PLAN-09: `doc/README.adoc` states `configuration.adoc`'s dual
  role (design document *and* operator reference) in its index entry. The file is **not**
  moved — it is linked heavily across the variants, plan docs and threat model, and relocating
  it would churn every cross-reference to fix a labelling problem.
  Bootstrap: **PLAN-09** owns the scaffolding (AskUserQuestion) — it lands next, so nothing
  after it can miss the convention, and it has genuine developer-facing content already (how to
  run the k6 benchmarks and the APISIX comparison), making it a real first customer rather than
  synthetic scaffolding. It also records the convention in `doc/plan/README.adoc` beside the
  existing integration-test and benchmark conventions, so it binds every later plan at the
  source rather than only in this ledger.
  Anti-rubber-stamp clause, carried in every hand-off: design-doc updates do NOT satisfy the
  deliverable, and a plan that genuinely changes nothing an operator or contributor would do
  differently must **say so explicitly** rather than silently skip it.
  PLAN-08's role changes accordingly — from authoring the operator guide to **auditing and
  completing** both trees, with gaps recorded as findings *attributed to the plan that should
  have documented them*, so the convention's effectiveness stays visible.
  Applied 2026-07-20 to the hand-off commands of PLAN-09 (bootstrap + first customer),
  PLAN-10, PLAN-04, PLAN-05, PLAN-06, PLAN-07, PLAN-11 (standing clause) and PLAN-08 (audit
  variant). **Any plan staged after this date must carry the clause.**
  **Bootstrap re-homed to PLAN-10, 2026-07-20** (operator, AskUserQuestion). PLAN-09 shipped
  without it — the fold reached its spec *after* the hand-off command was emitted and the plan
  launched, so it never executed (see `landings/PLAN-09.md`). PLAN-10 is the genuine first
  customer anyway: anchor `type`/`access` and asset serving are pure operator surface. All
  seven downstream clauses re-pointed from "roadmap plan 04b" to "roadmap plan 10".
- 2026-07-20 — **ORCHESTRATION LESSON (mine, recorded so it does not recur): amending a staged
  spec after its hand-off command has been emitted changes nothing about what executes.** The
  documentation bootstrap was added to PLAN-09's spec while PLAN-09 was already in flight; the
  plan delivered 8/8 of the command it was actually given, faithfully. A post-emit amendment
  must either be re-emitted to the running plan or re-homed to a plan that has not launched —
  never left in a spec and assumed to bind. Check `status.json` for `launched` before amending.
- 2026-07-20 — **PLAN-09 archive analysed; lesson 2026-07-20-18-002 folded into PLAN-10 as D2a
  (highest-value item in the whole archive).** PLAN-09 shipped a fail-fast startup validator
  that silently never ran: a `@Observes StartupEvent` observer had the validator injected, but
  CDI injects a **lazy client proxy** whose `@PostConstruct` assembly runs only on the first
  method invocation — the observer merely held the reference. Unit tests passed because they
  call methods directly, forcing proxy resolution; the validation fired under test and was
  inert in production. Caught only by a review bot (CodeRabbit Critical `0f50e9`).
  This is load-bearing for PLAN-10 because **D2 is entirely a boot-validation feature** — the
  plan's whole fail-closed premise (required `access`, backed-auth check, `bff`+`public`
  rejection) dies silently if wired the same way, and per the lesson a lazy-proxy-defeated
  validator is *worse than none*: it manufactures the belief that misconfiguration aborts boot
  while the gateway starts with invalid configuration. D2a therefore requires the observer to
  actively invoke a method (or eager initialization), and at least one test asserting rejection
  **via the startup-event path** rather than by calling the validator directly.

- 2026-07-21 — RESUME RECONCILIATION: ground truth showed BOTH PLAN-12 and PLAN-04 launched
  and running concurrently, while the ledger recorded PLAN-04 as staged/emitted. PLAN-04
  (`protocol-processors-ws-grpc`) is in phase 5-execute on worktree branch
  `feature/protocol-processors-ws-grpc` (11 commits, base 5cf1408); PLAN-12
  (`sonar-zero-findings`) is in phase 1-init. The staged sequencing (PLAN-12 first, PLAN-04
  branching from its zero-findings baseline) was therefore NOT followed in practice — the
  operator launched PLAN-04 directly. Queue reconciled (PLAN-04 → launched, both
  plan_marshall_plan_ids recorded), collision watch opened (see Watches). Also cleared a stale
  anchor note: the uncommitted marshal.json + enriched.json hints landed via steward PR #87
  (d1545a3); working tree is clean.

- 2026-07-21 — PLAN-12 landing analyzed (full ship, landings/PLAN-12.md). Verified against
  ground truth: 5265ed6 single-parent squash on main; gate GREEN via SonarCloud API (was red);
  compliance docs live; README badge fix (D7) verified on main — note the addendum executed in
  PLAN-12, not PLAN-04 as the ledger had recorded from the operator's "passed to plan-4"
  (harmless ownership drift; watch PLAN-04's rebase for a README conflict-revert). Backlog is
  NOT zero (34 open: 10 declared residual + 24 S5778/S7467 lag-or-unfixed — the D4 sweep ran
  partially blind while live Sonar was HTTPS-blocked locally), so per the plan's own policy the
  epic-level closure moves to a NEW staged PLAN-13-sonar-residual: fresh live query, literal
  zero, emitted only after PLAN-04 lands (one re-query covers PLAN-04's new code too) and
  closure propagation settles. Deploy-snapshot on main hung ~30 min and was cancelled on BOTH
  attempts (all build/sonar jobs green) — the narrative's own escalation condition is met:
  org-admin scope (Sonatype incident or org-workflow timeout), not repo scope. Two tooling
  findings recorded: upstream plan-marshall credentials-key-prefix bug (worked around via
  ~/.plan-marshall credentials url), and empty skills_by_profile.documentation.implementation
  in marshal.json awaiting operator-run architecture enrichment.

- 2026-07-22 — PLAN-04 landing analyzed (full ship, landings/PLAN-04.md). Verified: bdc8948
  single-parent on 5265ed6 (rebase obligation met), README badge lines survived, mapper gone
  from main, worktree/plan archived. 14/14 deliverables — both folded items executed as named
  line items, a clean counter-example to the outlines-drop watch. Three in-flight additions
  were real fixes (gRPC containment exemption + 5 tests; upstream.path replace-semantics fix
  that also un-broke httpbin benchmark routes; security headers on WS handshake failures).
  Sonar backlog UNCHANGED at 34 with identical rule counts 24h post-5265ed6 → S5778/S7467 are
  unfixed remnants, not propagation lag; PLAN-13's both emit preconditions now met → command
  EMITTED. Deferred ADR (upstream.path replaces alias base-path, refining ADR-0004) recorded
  as a watch: operator ad-hoc PR or fold into PLAN-05 at emit. deploy-snapshot hung a THIRD
  consecutive time (bdc8948, all other jobs green) — systemic, org-admin escalation urgent.

## Open Defects

- ~~LIVE: SonarCloud Quality Gate is RED on main~~ — **RESOLVED by PLAN-12** (PR #89): both
  S3655 bugs fixed with regression tests; gate verified **OK** via SonarCloud API post-merge
  2026-07-21. The PLAN-10 report-vs-reality discrepancy stands corrected on main. (closed at
  landing PLAN-12)
- ~~Sonar provider tooling misconfigured~~ — **RESOLVED**: credentials/project key landed via
  steward PR #87 + a local url workaround during PLAN-12. Residual: UPSTREAM plan-marshall bug
  (workflow-integration-sonar scripts look up credentials by unprefixed skill name while
  credentials_config keys are bundle-prefixed → "HTTPS required") — tracked as a Watch, to be
  filed as a plan-marshall lesson/issue. (closed at landing PLAN-12)
- **STRICT SONAR ZERO-FINDINGS POLICY now in force** (operator 2026-07-21, see Decisions): gate
  must be GREEN before merge (hard stop, no merging over red or on a stale green); zero new
  findings; ratings all A; coverage >= 80%; completion reports must reflect the actual post-merge
  gate. **Fix-by-default, suppress-with-rationale** (operator refinement 2026-07-21): the goal is
  to FIX; where a fix is genuinely not sensible an in-code `// NOSONAR` / `@SuppressWarnings` with
  a documented rationale is an acceptable way to reach zero (auditable in-repo) — reaching zero by
  silent server-side won't-fix / false-positive marking is not. **Applies to ALL plans/tasks**, not
  just PLAN-12. Standing clause on every remaining hand-off. **Durable repo materialization
  DELIVERED by PLAN-12** (CLAUDE.md "Sonar / Quality Gate" section +
  doc/development/sonar-quality-gate.adoc, verified on main) — the policy itself remains
  standing and binds every plan. — source: operator 2026-07-21; materialized at landing PLAN-12
- **Gate + rules are cuioss-ORG-owned** (verified 2026-07-21): `.github/project.yml` declares
  only project-key/enabled/skip-on-dependabot; CI via `cuioss/cuioss-organization`
  reusable workflows; gate conditions + profiles live in the cuioss SonarCloud org, already a
  required check. A per-repo plan must NOT define/modify the gate — if the org gate itself
  should change (e.g. fail on smells), that is an operator/org-admin escalation. The policy is
  therefore materialized as **compliance documentation** referencing the org gate, plus a
  code-level zero-findings sweep — not a per-repo gate definition. — source: operator + config
  verification 2026-07-21
- **ACCIDENTAL, HALF-COMPLETED 1.0.0 RELEASE ON MAVEN CENTRAL (2026-07-12) — verified via
  repo1.maven.org + Actions API 2026-07-22** (operator noticed via the README Maven-Central
  badge showing v1.0.0). Facts: ALL FOUR modules are RELEASED at 1.0.0 on Central (published
  ~11:07Z 2026-07-12, signed): api-sheriff-parent (pom), **api-sheriff (full consumable set:
  jar + sources + javadoc + pom)**, and the test modules benchmarks + integration-tests
  (jar+pom — should never deploy at all; they also go out in every snapshot deploy).
  ROOT CAUSE: repo-side `release.yml` triggers on ANY merged PR touching
  `.github/project.yml`; the PLAN-01-era PR "Java 25 baseline, CI matrix 25/26" edited
  project.yml only for `maven-build.java-versions`, which fired the release workflow (run
  29189972818), which read the template-inherited `release.current-version: 1.0.0` (present
  since the initial commit) and released it. The run then FAILED at the "Push changes" step
  (branch protection on main) — so NO tag, NO GitHub release, NO version bump exist; the pom
  stayed 1.0.0-SNAPSHOT. Central's 1.0.0 is therefore a stale 2026-07-12 pre-roadmap build
  (pre-PLAN-01/03/04/09/10/12) presented as a final release; anyone resolving the artifact
  gets it as "latest release". The operator's expected 0.1-SNAPSHOT never existed — the pom
  has been 1.0.0-SNAPSHOT since the template's initial commit. Central releases are
  IMMUTABLE: 1.0.0 is burned. **STRATEGY DECIDED (operator 2026-07-22): do NOT follow
  1.0.0 — Maven RELOCATION + version restart at 0.1.x on NEW coordinates.** Analysis
  delivered (no changes): same-GA restart unviable (1.0.0 sorts above 0.x forever); clean
  path = relocation-only POM published as 1.0.1 at the old GA (parent + api-sheriff ONLY;
  benchmarks/integration-tests dead-end AND stop deploying — maven-deploy skip), pointing to
  the new GA at 0.1.0/0.1.0-SNAPSHOT. Caveats: relocation is per-version, Maven-resolver
  only (Gradle/dependabot ignore it), Central publishing requirements apply, and the one-off
  old-GA publish sits outside the org release workflow. GroupId alternatives proposed (must
  stay under verified de.cuioss.*): **A de.cuioss.sheriff.gateway — RECOMMENDED** (symmetric
  with live sibling de.cuioss.sheriff.token@0.9.2; artifactIds unchanged); B keep
  de.cuioss.sheriff.api + rename artifactIds; C de.cuioss.sheriff flat (umbrella squat);
  D de.cuioss.apisheriff. **RESOLVED 2026-07-22 by PLAN-14** (groupId A chosen; verified:
  guard dispatch-only on main with incident comment, gateway@0.1.0-SNAPSHOT live, both
  old GAs latest=1.0.1 with relocation elements on repo1, test modules deploy-skipped).
  Residuals: stray immutable `api-sheriff-relocations:1.0.1` under the abandoned GA
  (accepted, cosmetic — wrong skip property); stale old-GA refs in 3× enriched.json
  (cleared by the pending architecture-enrichment run).** CRITICAL SEQUENCING: the restructure PR must touch project.yml, which
  FIRES the still-armed release trigger → PR1 hardens release.yml (workflow_dispatch-only or
  org-convention-compatible; touches no project.yml), PR2 does the restructure (coordinates,
  0.1.0-SNAPSHOT, project.yml versions, badge, deploy-skips, docs sweep), then the one-off
  relocation publish. Upstream report still owed: org release workflow runs the irreversible
  Central publish BEFORE the failable repo push. — source: operator report + repo1.maven.org,
  Actions run 29189972818, git history, sibling verification 2026-07-22
- **Sonar backlog not yet literal zero — 34 open post-PLAN-12-merge** (was 59; SonarCloud API
  verified 2026-07-21 post-merge): S5778 ×17 + S7467 ×7 (propagation lag or unfixed remnants of
  the D4 sweep, which ran partially blind — indistinguishable until closure settles) + the
  declared residual S1612 ×3, S5976 ×3, S6916 ×3, S1068; narrative also expects S6878 ×2,
  S1130, 3× S1135 TODO to survive. 0 bugs, gate GREEN — this defect is policy-debt, not
  gate-debt. **UPDATE 2026-07-22 (PLAN-04 landing): count and rule distribution UNCHANGED
  24h later and across the PLAN-04 merge (which added 0) — the S5778/S7467 are confirmed
  unfixed remnants, not lag. Both PLAN-13 emit preconditions met; command EMITTED.**
  — source: SonarCloud API post-merge 2026-07-21, re-verified 2026-07-22


- ~~README benchmark badges 404 on click-through~~ — **RESOLVED, verified on main**: fixed as
  PLAN-12's D7 addendum (bf2aa66 in PR #89) — both `link=` targets now point to the live k6
  pages (`benchmarks/integration/index.html` / `trends.html`, previously curl-verified 200)
  and the WRK prose now says k6. Attribution stays with PLAN-09 (the k6 migration missed the
  README). NOTE: the ledger had recorded the addendum as passed to PLAN-04 ("passed to
  plan-4"); it actually executed in PLAN-12 — residual check moved to the Watches: PLAN-04's
  rebase over 5265ed6 must not conflict-revert these README lines. (closed at landing PLAN-12)
- **deploy-snapshot on main hangs and gets timeout-cancelled — now THREE consecutive times,
  SYSTEMIC** (verified via Actions API): 5265ed6 attempts 1+2, and bdc8948 attempt 1
  (23:00:04Z → cancelled 23:30:20Z) — every main push since 2026-07-21 evening; all
  build/test/sonar jobs green each time, only deploy-snapshot dead, main red on that job.
  **RESOLVED 2026-07-22, root cause repo-side after all**: PLAN-14's deploy-skip on
  benchmarks + integration-tests removed the heavyweight test-module artifacts from the
  upload, and deploy-snapshot immediately went green (1m40s on 36541f6; new-GA snapshots
  live, benchmarks correctly absent). Evidence: 3 consecutive ~30-min hangs before the skip,
  instant success after — the upload payload was the hang. Org escalation RETIRED.
  PLAN-13(A) reduced to verify/close: confirm the green streak holds and record the finding.
  — source: landings PLAN-12/PLAN-04/PLAN-14, Actions API 2026-07-21/22
- **marshal.json: `skills_by_profile.documentation.implementation` is empty** — operator
  action: run architecture enrichment to populate it (surfaced as a resolved-taken_into_account
  Q-Gate finding in PLAN-12; config changes were out of that plan's scope). — source: landing
  PLAN-12
- Boot-time TOCTOU between the YAML pre-pass read and the bind read (needs a size-bounded
  single-snapshot fix). Deferred with rationale by PLAN-02's security audit; candidate fold
  into a future config-touching plan — NOT PLAN-03 (data-plane surface). — source: landing PLAN-02
  — **STILL OPEN after PLAN-10**: PLAN-10 was the config-touching plan but the hand-off did NOT
  fold it in (the emit deferred the decision and I did not carry it into the command). Re-home
  to the next config-touching plan or to PLAN-08's hardening pass — decide at PLAN-08 emit.
- Readiness-message disclosure on the management port (advisory finding, PLAN-03 security
  audit). Natural owners: PLAN-05 (tls-edge) or the PLAN-08 full-surface security audit.
  — source: landing PLAN-03
- ~~`verify` reported success while 3 tests errored~~ — **CLOSED at the PLAN-04 landing with a
  root-cause finding**: the masking sits in the **build-wrapper layer, not the pom**
  (no testFailureIgnore/quarkus-jacoco defect; finding recorded doc-first, 39073ea). Repo-side
  build config is exonerated; the wrapper-layer behavior is plan-marshall-tooling territory —
  covered by the recorded lessons for the next lessons-handling epic. (source: landing PLAN-04)
- **Two required security controls shipped narrative-only in PLAN-10**, caught by the finalize
  audit, not the deliverable checks: the directory-source symlink escape was unimplemented
  (`PathConfinement` lexical-only) though explicitly required, and the upstream size cap
  buffered the full body before enforcing `max_bytes`. Both fixed pre-push. Not an open defect
  in the code (fixed) — an open defect in *trust*: deliverable prose claimed controls that did
  not exist. — Re-check: PLAN-08 security audit re-verifies both controls in the production path
  (source: landing PLAN-10)

## Watches

- ~~COLLISION WATCH: PLAN-04 second-merger obligations~~ — **DISCHARGED at the PLAN-04
  landing**: finalize rebased onto origin/main folding in PR #89 (3 conflicts resolved),
  whole-tree quality gate + CI green at 535244a, bdc8948 sits single-parent on 5265ed6, and
  the README D7 badge lines survived (verified on main). The recorded overlap (PLAN-12 sweep
  vs PLAN-04 edge/test surface) materialized exactly as predicted — 3 rebase conflicts —
  validating the sequencing concern for future pairings. (source: landing PLAN-04)
- **Deferred ADR from PLAN-04**: "Route upstream.path replaces the alias base-path in
  forward-URI reconstruction" — refines ADR-0004's concatenation wording to the
  replace-semantics TASK-027 implemented (full text in the adr-propose transcript /
  decision log of the archived plan). Disposition: operator lands it as a small ad-hoc doc
  PR, or it FOLDS into the PLAN-05 hand-off if still open at that emit (PLAN-05 shares the
  route/upstream neighborhood). — Re-check: at the PLAN-05 emit (source: landing PLAN-04)
- **Upstream plan-marshall bug to file**: workflow-integration-sonar scripts resolve
  credentials config by unprefixed skill name while credentials_config keys are
  bundle-prefixed → "HTTPS required" failures; PLAN-12 worked around via
  `~/.plan-marshall/credentials/workflow-integration-sonar.json` url. File as a lesson/issue
  in the plan-marshall repo (cross-repo; candidate for the next lessons-handling epic).
  PLAN-13 will hit the same path — its hand-off already carries the fallback instruction.
  — Re-check: when filed upstream, and at the PLAN-13 landing (source: landing PLAN-12)
- ~~Orphaned JAX-RS GatewayExceptionMapper (advisory, PLAN-03)~~ — DISCHARGED into PLAN-04 as a
  named deliverable at emit 2026-07-21 (operator confirmed the fold). (source: landing PLAN-03;
  closed at PLAN-04 emit)
- ~~PLAN-10's route-table terminal-action materialization may brush PLAN-04's pipeline
  surface~~ — DISCHARGED: PLAN-10 shipped, so the surfaces no longer overlap in time; PLAN-04
  now builds on the landed `ResolvedRoute` union rather than racing it. (source: staging
  2026-07-20; closed at landing PLAN-10)
- **Outlines drop named deliverables — now TWICE in this epic.** PLAN-09's doc bootstrap
  vanished (post-emit amendment, my error) and PLAN-10's terminal-action wiring was folded
  away by the phase-3 outline and only resurfaced as a TASK-015 infeasibility at execute time
  (recovered in-plan). Both were *named in the request*. Lesson for emit: when a hand-off names
  a concrete wiring/materialization deliverable, state it as its own line item, not inside a
  prose clause an outline can abstract away. — Re-check: apply at every future emit (source:
  landing PLAN-10, lesson 2026-07-21-04-002). **Positive signal at PLAN-04 (2026-07-22): both
  folded items were stated as named line items at emit and both shipped — the discipline
  works when applied.**
- **Source-mutating finalize steps fired freshness reconciliations again in PLAN-10**
  (security-audit mutated source post-build; two reconcile records, one correcting a
  mis-transcribed SHA). Same class as the PLAN-09 watch; CI verified the post-mutation tree
  both times, so late-verification not escape. — Re-check: at every landing, confirm CI
  verified the post-mutation tree when a reconciliation is reported (source: landings PLAN-09,
  PLAN-10)
- ADR-0010 (YAML expansion limits in a compose-only pre-pass) is Proposed and awaits
  operator review/acceptance. — Re-check: at next landing (source: landing PLAN-02)
- ADR-0012 (comparative benchmarking runs on-demand, segregated from the CI baseline) is
  **Proposed** and awaits operator review/acceptance — verified on main 2026-07-20. A proposed
  `k6-replaces-wrk` ADR was declined by the operator; that decision lives in the 04b plan doc.
  — Re-check: with ADR-0010 (source: landing PLAN-09)
- ADR-0011 (gateway.yaml exposes JWKS trust and egress as neutral names bound by the
  deployment) is **Accepted** on main but was never tracked in this ledger — noted for
  completeness; no action. (source: landing PLAN-09)
- PLAN-09's `finalize-step-simplify` reported **2 edits from 6 findings**. Archive analysed
  2026-07-20: the step ran **twice** and its findings are not itemised in the archive, so the
  4 unacted ones remain unattributable. Partially discharged — the four *anti-pattern* findings
  ARE itemised and all four were explicitly accepted with reasoning (below). — Re-check: only
  if benchmark-module quality issues surface later (source: archive analysis 2026-07-20)
- PLAN-09 accepted **four security anti-patterns**, all argued as benchmark/test-scoped:
  wholesale `insecureSkipTLSVerify: true` in every k6 aspect script (broader than needed — the
  mounted test CA could have been trusted instead); hardcoded default Keycloak client secret
  and password in `bearer_proxied.js`; k6 containers running as root (`user: 0:0`) because the
  results bind mount is host-owned; a test-only truststore password in `application.properties`
  under the `%it` profile. Each is defensible in isolation and none touches production code —
  but they are now *accepted precedents* in a security-focused gateway. — Re-check: PLAN-08's
  full-surface security audit must confirm they stayed benchmark-only and none leaked into a
  shipped default (source: archive analysis 2026-07-20)
- Source-mutating finalize steps leave a tree the passing build never verified. PLAN-09 hit
  this twice: an OpenRewrite `UseMapOf` recipe mutated production source *after* the phases it
  was gated behind had passed (green `-Ppre-commit`, broken next clean build), and
  `finalize-step-simplify` advanced the worktree past the last successful build so the push
  freshness gate was **reconciled rather than re-verified**. CI caught it downstream, so the
  risk materialised as late verification rather than an escape. — Re-check: at every landing;
  if a plan reports a freshness reconciliation, confirm CI verified the post-mutation tree
  (source: archive analysis 2026-07-20, lesson 2026-07-20-18-001)
- Destination-type-aware placeholder coercion in ConfigLoader.coerce() deferred as an
  architecture hint (design change); future planning picks it up automatically. — Re-check:
  when a config-touching plan is staged (source: landing PLAN-02) — **NOT folded into PLAN-10**
  (the emit did not carry it). Still open; next config-touching plan or PLAN-08. Decide at
  PLAN-08 emit alongside the boot-TOCTOU defect (same disposition)
- PLAN-11 must reuse the Keycloak compose stack that lives in `integration-tests/` rather than
  standing up a second one from the new `demo-client/` module — the reuse mechanism is
  undecided. — Re-check: at the PLAN-11 outline (source: staging 2026-07-20)
- PLAN-11 expects **no** `api-sheriff/` production-source change. If the demo forces one, that
  is a gap in PLAN-06/07/10 surfacing late — treat it as a finding to report, not a patch to
  apply in the demo plan. — Re-check: at the PLAN-11 landing (source: staging 2026-07-20)
- PLAN-10 is **over the split guard** (8 deliverable groups after the upstream asset source was
  added). Recommended split: (A) `type`+`access`+validation+docs — independently shippable and
  what PLAN-04/05/06/07 need to author against; (B) asset serving both sources + threat-model
  update + traversal corpus, where the security risk sits. — Re-check: at the PLAN-10 emit
  (source: restaging 2026-07-20)
- PLAN-10's upstream asset source touches the data-plane client neighbourhood, not just config
  — widen the PLAN-04 surface-disjointness check accordingly. — Re-check: at the PLAN-10 emit
  (source: restaging 2026-07-20)
- PLAN-06's two folds (info endpoint + login initiation) both add reserved gateway paths, and
  `configuration.adoc` enumerates the reserved-path list explicitly. Verify the doc list, the
  variant doc, and the implementation agree on all six paths at landing. — Re-check: at the
  PLAN-06 landing (source: staging 2026-07-20)
