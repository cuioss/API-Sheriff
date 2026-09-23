# Landing Analysis: PLAN-04 — JWKS Back-Channel Hostname Verification

epic: deployment-configurability
workstream: WS-03
pr: [#272](https://github.com/cuioss/API-Sheriff/pull/272) — merged as `054b3e4` (squash)

> **WS-03 is complete.** Third inbox-delivered landing, `complete: true` / `missing_keys[0]` — and the
> first arriving with `revision=1`, because the plan caught its own false green and amended the
> message until it passed. See Routing.

## Deliverable Fidelity vs Spec

| Deliverable | Verdict |
|---|---|
| 1. Reader for `egress_tls.jwks_verify_hostname` + token-sheriff 0.9.5 bump | shipped — the bump is the *mechanism*, not incidental |
| 2. ADR-0041 recording the settled mechanism | shipped — `0041-The_JWKS_hostname-verification_knob_is_the_librarys_own_passthrough_and_its_sslContext_collision_is_refused_at_boot.adoc` |
| 3. ADR-0040 amended in place | shipped |
| 4. Operator docs + threat model completed | shipped |

Footprint 14 files. ✅ **The spec's own key insight held**: `egress_tls.jwks_verify_hostname` parsed
before this plan and did nothing — the epic's standing rule that *a configuration key that parses is
not a key that acts* is what this deliverable closes.

### ⛔ The mechanism was wrong twice before it was right — and one of the wrong answers was mine

| Position | Held by | Verdict |
|---|---|---|
| Hand-roll a delegating `X509ExtendedTrustManager` | the original spec, option (b′) | superseded |
| cui-http 3.0's `HttpHandlerBuilder#verifyHostname` | **this orchestrator's 2026-09-07 re-scope** | ⛔ **real API, unreachable from this gateway** |
| No mechanism exists at the resolved dependency | refine's first refutation | ⛔ true at 0.9.4, wrong as a conclusion |
| **`verifyHostname(boolean)` on token-sheriff's OWN builders, from 0.9.5** | the plan | ✅ correct |

**Corroborated first-party at `054b3e4`**: `TokenValidatorProducer.java:29` imports
`HttpJwksLoaderConfig` from **`de.cuioss.sheriff.token.commons.transport`**; `:257` builds
`HttpJwksLoaderConfig.HttpJwksLoaderConfigBuilder`; `:266` calls `.verifyHostname(jwksVerifyHostname)`;
`:271` calls `builder.sslContext(...)`. `pom.xml:87` carries `0.9.5-SNAPSHOT`. The gateway never holds
a cui-http `HttpHandlerBuilder` at all.

⚠ **The epic's stamped verdict has been corrected** — claim 5 re-stamped `contradicted @ 054b3e4`
with the token-sheriff evidence replacing the cui-http evidence. The verdict was always right; the
reasoning under it would have sent a sibling plan to the wrong library. The message flagged this
rather than letting it stand, which is the correction working as designed.

✅ **What my re-scope got right and what carried:** the `sslContext` collision. token-sheriff
re-raises cui-http's guard on both builders, and the plan settled it as **option (i)** of the three
I recorded — refused at boot in `toHttpJwksLoaderConfig` with `GatewayException(CONFIG_INVALID)`,
ahead of the library's own throw. (ii) and (iii) are recorded as rejected in ADR-0041. The
hand-rolled trust manager stays retired and the TokenSheriff duplication stays dissolved.

## Metrics and Anomalies

7h29m wall, 4,027,207 tokens, one loop-back of five available. `adr-propose` and `lessons-capture`
both `skipped` — the first correctly (ADRs were deliverables), the second unexplained but immaterial.

## Routing and Merge Behavior

- CodeRabbit reviewed **twice** (`a29b388` → 4 findings, `c45994e` → 1) and the re-review went
  through **despite reporting "0 remain this hour"** — no quota wait, no PR recreation. ⚠ Worth
  noting against lesson `2026-09-02-22-004` (*a rate-limited required bot deadlocks the merge*): the
  rate-window reporting is not itself a block, and `#271` disabled `review_rate_window_await`.
- Sonar 0 new-code issues, confirmed. Sourcery rate-limited (optional, non-gating).
  `cuioss-review-bot` participated with no actionable finding — the token this ledger wrongly called
  broken, working normally for the second landing running.
- Merged as `054b3e4`; worktree removed.
- ⚠ **HEAD has already moved past it** — `cb60b24 chore: adopt cui-quarkus-parent 1.7.0` is a
  **parent migration**, landed after the merge and not exercised by any epic plan's gate.

### ✅ emit-landing worked — and the plan caught its own false green

`emit-landing` was marked **done before the message was actually emitted**; the plan caught it, wrote
the message, and `landing-check` **failed it on eight missing machine-readable keys**. It was amended
until it passed — the envelope records `revision=1`, `amended=2026-09-07T21:31:18Z`.

⛔ **This is the completeness check doing exactly the job it exists for.** Without it the message
would have shipped as a false green, and the drain would have reconciled against a partial payload
while reporting success. Channel record now: **3 delivered (PLAN-10, PLAN-15, PLAN-04), 3 skipped
(PLAN-01, PLAN-02, PLAN-03), 1 correct abstention (PLAN-13)** — still intermittent, still no
discriminator found.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` `272`, `landing`, `plan_marshall_plan_id` stamped
- [x] **Claim 5's verdict evidence corrected** — token-sheriff 0.9.5, not cui-http 3.0
- [x] Inbox drained: 1 scanned, 1 archived, 0 invalid; `complete: true`
- [x] Four defects opened (below)
- [x] **ADR ordinal is now `0042`** — PLAN-07 declares `doc/adr/` and must re-resolve

## Follow-Ups

1. ⛔ **A FIFTH unpinned outbound HTTPS leg, carrying the `client_secret`.**
   `BffRuntimeProducer:208` builds `ClientConfiguration` with **neither** `verifyHostname` nor
   `sslContext`, dialled by `DiscoveryResolver` / `TokenEndpointClient` / `RefreshFlow` — discovery,
   code exchange **and refresh**. Secure today only via the upstream `@Builder.Default true`, which is
   **the exact reliance `TokenValidatorProducer` refuses for the lower-value JWKS leg**. ⚠ This is the
   *second* fifth-leg-class finding in two landings (PLAN-03 found a fourth egress leg five docs
   denied); the egress inventory has been wrong twice. Code half is out of footprint — **wants its own
   plan**.
2. ⚠ **`RotationResult` widened 5→7 components in 0.9.5, exposing a `scopeDelta` nothing reads.** A
   NARROWED or BROADENED scope on refresh is currently **unobserved**. Fixture migrated, signal not
   adopted. ⚠ Directly relevant to running **PLAN-05**, whose whole subject is the refresh path.
3. ⛔ **`0.9.5-SNAPSHOT` is a mutable coordinate on the authentication path of every build cut from
   `main`.** Operator was shown twice and chose to proceed; mitigations are real (snapshot repo sets
   `<releases><enabled>false</enabled>`, pom carries an explicit REMOVAL CONDITION). ⛔ **Residual gap
   stated plainly: nothing in the build REFUSES a release that still resolves a SNAPSHOT — the
   condition is prose.** Filed by the security audit (`651d5b`) and CodeRabbit (`6cd491`, Major),
   dispositioned `accepted` / `taken_into_account`.
4. ⚠ **No deployment-activation guard** — the knob is proven at unit level only, by operator decision.
