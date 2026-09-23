# PLAN-15: Broad-prefix trust threshold, plus the probe Javadoc correction

epic: deployment-configurability
workstream: WS-05

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> **Supersedes PLAN-14** (`PLAN-14-healthprobe-javadoc-port-claim.md`), which carried deliverable 4
> alone. PLAN-14's spec is retained as the audit record of why it was retired; it is not deleted.

## Objective

`forwarded.trusted_proxies` decides whose `X-Forwarded-For` this gateway believes, and its only
guard against an over-broad range is a boot warning that is silent for every range an operator is
actually likely to get wrong. PLAN-06 raised the stakes by moving that value into an environment
variable no code review sees, leaving the warning as the last signal on it. This plan settles the
breadth thresholds for both address families, implements and tests them, and — riding the same gate
run — corrects the `HealthProbe` Javadoc that still documents a remedy ADR-0039 has established
does not exist.

⚠ **Recorded impurity, deliberately accepted.** Deliverable 4 is a WS-01 concern (container health
check) carried by a WS-05 plan. It rides here because it is a one-line source correction whose
entire cost is the gate-and-ship cycle: as its own plan it paid roughly **40:1 machinery-to-work**
against a measured baseline of 2,338,588 tokens for PLAN-10's finalize phase alone — and PLAN-10 ran
no quality gate at all, while this footprint does. Batching it onto a plan that must run that gate
anyway is what makes it affordable. The alternative was leaving a live ADR/source contradiction
standing indefinitely, since WS-01 has no remaining staged work to batch it with.

## Deliverables

1. **Settle the broad-prefix thresholds for both families and record why.** `BROAD_PREFIX_IPV4 = 8`
   and `BROAD_PREFIX_IPV6 = 32` are the current values and the warning fires only *below* them.
   Decide the values that make the warning fire for the ranges operators actually supply — the
   motivating case is `172.16.0.0/12`, Docker's entire default bridge pool at 1,048,576 addresses —
   and record the reasoning, including what is deliberately left un-warned. ⛔ **This is a security
   judgement, not a constant edit**: too permissive and the guard stays decorative, too strict and it
   trains operators to ignore a routine warning. That trade-off is the deliverable.
2. **Implement the settled thresholds** in
   `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java`.
   ⛔ Do **not** change the trust-all refusal: `coversEntireSpace` already *rejects* a range covering
   the whole space, and that is an error, not a warning. This deliverable moves only the warning
   boundary between those two states.
3. **Test the boundary with matched controls.** A range just inside the new threshold must warn and a
   range just outside must not, for **both** families — the IPv6 arm is easy to leave untested
   because the motivating case is IPv4. Per the project's own rule, a threshold that parses is not a
   threshold that acts: if the constant were reverted, one of these tests must go red.
4. **Correct the `HealthProbe` class Javadoc** so its port-override sentence names an image rebuild
   as the only remedy, matching ADR-0039's corrected wording at both of its sites. Preserve the
   fail-closed framing, which was correct in both documents.

## Claim Labels

- OBSERVED: the thresholds and their comparison — read at `ConfigValidator.java` §
  `BROAD_PREFIX_IPV4` (`= 8`, line 129), § `BROAD_PREFIX_IPV6` (`= 32`, line 130) and
  § `checkFamilyTrust`, whose warning arm is `if (range.prefixLength() < broadPrefix)`. A `/12` is
  therefore not `< 8` and warns not at all, as does every IPv4 range from `/9` to `/32`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: ConfigValidator.java now sets BROAD_PREFIX_IPV4=16 and IPV6=48, raised by this plan's own deliverable 1
- OBSERVED: the trust-all case is a separate, stricter arm that already refuses rather than warns —
  read at `ConfigValidator.java` § `checkFamilyTrust` → `coversEntireSpace`, which appends a
  `ConfigError` and returns before the warning loop.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: coversEntireSpace still a separate stricter arm appending a ConfigError before the warning loop
- OBSERVED: the warning has a log record rather than a bare string — read at `ConfigValidator.java` §
  `checkFamilyTrust`, `LOGGER.warn(ConfigLogMessages.WARN.BROAD_TRUSTED_PROXY, …)`. ⚠ Changing the
  threshold may require re-wording that record and its `doc/LogMessages.adoc` row; verify at outline.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: LOGGER.warn(BROAD_TRUSTED_PROXY,...) still present at ConfigValidator.java:1532
- OBSERVED: the `HealthProbe` Javadoc carries the unreachable remedy verbatim — read at
  `HealthProbe.java`, class Javadoc: *"A deployment that overrides `quarkus.management.port` must
  override the image's `HEALTHCHECK` to match"*.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: HealthProbe.java's Javadoc no longer carries the unreachable override sentence - fixed as this plan's own deliverable 4
- OBSERVED: that remedy is unreachable because the probe port is a compile-time constant — read at
  `HealthProbe.java` § `PROBE_PORT` (`private static final int PROBE_PORT = 9000`, line 67) and
  § `probe()` (line 111, `socket.connect(new InetSocketAddress("127.0.0.1", PROBE_PORT), …)`), which
  takes no port argument and reads no configuration. `Dockerfile.native` ships no other executable.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: PROBE_PORT=9000 still a compile-time constant, Dockerfile.native still ships no other executable
- OBSERVED: `ConfigValidator.java` is unowned by any other spec in this corpus — PLAN-06 declared it
  as a HYPOTHESIS and refuted it by non-realization; `git show --stat 6ba8879` confirms it untouched.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: historical structural fact about spec-corpus ownership at time of authoring
- HYPOTHESIS: no OTHER site in the repository repeats the `HEALTHCHECK`-override claim —
  confirm/refute by a repo-wide search for `HEALTHCHECK` near `management.port` at outline
  (verify-at-outline). ⚠ An asserted absence, verified exactly as an asserted presence: a third site
  joins deliverable 4 rather than becoming another plan.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed no remaining site repeats the stale HEALTHCHECK-override claim
- HYPOTHESIS: `doc/configuration.adoc`'s `trusted_proxies` rows state the breadth-warning behaviour
  and will need updating — confirm/refute at `doc/configuration.adoc` § `trusted_proxies`
  (verify-at-outline). If they do not mention it, deliverable 1's reasoning still needs a home.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: confirmed realized: doc/configuration.adoc and doc/LogMessages.adoc rows updated for the new threshold
- Verify-first clause: re-read both source files at HEAD before scoping. PLAN-13 is in finalize and
  will move HEAD; it touches only the `api-sheriff` test tree, so neither file can change under it,
  but the line numbers cited above are `337af0d`-relative.

## Expected Surface

⛔ **CORRECTED 2026-09-05 against the realized footprint of `558a38b` (PR #267), per `analyze`
Step 4 item 5.** All five declared entries landed — a first for this epic — but the
`HEALTHCHECK`-claim hunt widened from 2 sites to 6, pulling in five undeclared files. The widening
was **sanctioned by this spec's own instruction** ("a third site joins deliverable 4 rather than
becoming another plan"), so it is a correctly-handled expansion rather than a mis-declaration; the
declaration is corrected regardless, because the corpus must never carry a surface measured wrong.

**REALIZED (10 files, +121/-26, `git show --stat 558a38b`):**

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — declared
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/HealthProbe.java` — declared
- `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidatorTest.java` — declared as its directory; ✅ resolved inside it, no drift
- `doc/configuration.adoc` — declared (HYPOTHESIS, confirmed)
- `doc/LogMessages.adoc` — declared (HYPOTHESIS, confirmed)
- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java` — ⛔ UNDECLARED; the warning record re-worded with the threshold
- `doc/user/container-image.adoc` — ⛔ UNDECLARED; `HEALTHCHECK` claim site
- `doc/user/context-path.adoc` — ⛔ UNDECLARED; `HEALTHCHECK` claim site
- `doc/user/environment-variable-overrides.adoc` — ⛔ UNDECLARED; `HEALTHCHECK` claim site, and where the self-contradicting paragraph was authored and fixed
- `doc/user/tls-edge.adoc` — ⛔ UNDECLARED; `HEALTHCHECK` claim site

### Pre-landing declaration (kept for the record — complete but understated)

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java` — the two constants and `checkFamilyTrust` — **realized**
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/HealthProbe.java` — the class Javadoc, one edit — **realized, but the claim proved to live at six sites, not one**
- HYPOTHESIS: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/validation/` — the matched threshold controls (verify-at-outline: exact class TBD) — **CONFIRMED, resolved to `ConfigValidatorTest.java` inside the declared directory**
- HYPOTHESIS: `doc/configuration.adoc` — the `trusted_proxies` breadth rows — **CONFIRMED**
- HYPOTHESIS: `doc/LogMessages.adoc` — only if `BROAD_TRUSTED_PROXY`'s wording changes — **CONFIRMED, the wording did change**

## Dependencies and Sequencing

- **Depends on: none.** ADR-0039 has landed (`337af0d`); PLAN-06 has landed (`6ba8879`) and refuted
  its claim on `ConfigValidator.java`, so the file is free.
- ✅ **Surface-disjoint from everything in flight.** PLAN-13 touches only the `api-sheriff` test tree
  plus `doc/development/`; PLAN-05 touches only `integration-tests/`.
- ⚠ **Overlaps with PLAN-03, PLAN-04, PLAN-07 and PLAN-08 on `doc/configuration.adoc`** if the
  HYPOTHESIS entry resolves. All four are staged, none launched; re-check at emit rather than
  assuming today's verdict holds.
- ⚠ **Gate-requiring, and that is the point of the batch.** Two `.java` files make the whole commit
  subject to the quality gate and full verify. Both deliverables pay that cost once, together.
- ⚠ **The gate run is exposed to the open edge/tls flake class**, which the ledger records as
  presumptively environmental on this workstation (~50% locally, 0/100 on CI). PLAN-13 lands the root
  fix; if it has shipped by the time this runs, that exposure is largely gone. A local red is checked
  against CI history before it is attributed to this branch.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-15-trusted-proxy-breadth-and-probe-doc.md"
```
