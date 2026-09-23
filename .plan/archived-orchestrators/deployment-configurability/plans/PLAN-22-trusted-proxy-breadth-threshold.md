# PLAN-22: Fix the trusted-proxy breadth threshold and write the ADR it owes

epic: deployment-configurability
workstream: WS-05

> ⛔ **SUPERSEDED 2026-09-15 by `PLAN-25-configuration-security-hardening.md`, which carries this plan's scope as its deliverables 7-8, 9-11.** Retained as
> the audit record of why it was retired; a superseded spec is never deleted. **Do not emit.**
> **Why:** operator decision at the 2026-09-15 corpus revisit (`origin/main` `a2969b9`) — up to 12
> deliverables per plan are authorized, and this spec collided on surface with the others merged
> into the successor, so they could only have run sequentially as separate PR cycles. Its queue row
> is `parked` because the queue status vocabulary has no `superseded` value.
>
> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.
> ⛔ **Staged 2026-09-10 from the epic coverage audit.** Both halves have sat unowned since
> PLAN-06/PLAN-15 landed — issue #256 marked *"a WS-05 successor candidate"* and the ADR marked
> *"epic decision owed"*. This plan is that successor and that decision.

## Objective

`ConfigValidator.checkFamilyTrust` warns only **below** `BROAD_PREFIX_IPV4 = 8`, so every IPv4 range
from `/9` to `/32` boots with no warning at all — including `172.16.0.0/12`, **Docker's entire
default bridge pool, 1,048,576 addresses**.

⛔ **PLAN-06 raised the severity of this without meaning to.** The value used to sit in a committed
`gateway.yaml` that a human reviewed. PLAN-06 deliberately moved it into an environment variable
**no code review ever sees** — leaving this boot warning as the *only remaining signal* on it. The
guard got weaker at exactly the moment the review disappeared.

And the threshold's reasoning was never written down: `adr-propose` scanned all 39 corpus ADRs during
PLAN-15 and found **no** coverage of `trusted_proxies` breadth or the warn-vs-reject decision.

## Deliverables

1. **Re-derive the threshold, and state what it is protecting against.** `BROAD_PREFIX_IPV4 = 8` is
   the current value; nothing records why 8. ⛔ **Do not simply lower it to catch `/12`** — that
   swaps one unexplained constant for another. Establish what a "too broad to be an operator-
   provisioned network" range actually is for both families, name the deployment shapes that
   legitimately need a wide range (container bridge pools are the obvious one, and the obvious
   counter-argument to a naive tightening), and derive the threshold from that.
   ⚠ The declared un-warned residual today is IPv4 `/16` and IPv6 `/48`; whatever this plan lands
   must state its own residual just as explicitly.
2. **Settle warn versus reject, and make the signal survive the environment-variable path.**
   ⛔ **This is the deliverable that addresses what PLAN-06 changed.** A warning is a reasonable
   signal when a human reviews the committed value; it is a much weaker one when the value arrives
   from an unreviewed environment variable. Decide whether the breadth check should refuse rather
   than warn above some second, wider threshold — and if it stays a warning, say what makes the
   warning sufficient now that the review step is gone.
3. **Write the ADR that PLAN-15 correctly declined to write.** ⛔ **The decline was right and must not
   be read as an oversight** — adding a file post-review would have re-staled both required bots on a
   PR whose review was already the long pole. But the decision it records is exactly what the ADR
   corpus is for: a security threshold moved on stated reasoning, with named rejected alternatives and
   a declared un-warned residual. **The full draft is preserved in PLAN-15's decision log — start from
   it, do not re-derive it from scratch.**
   ⚠ Draft title carried forward: *"The trusted_proxies breadth warning is thresholded at one
   operator-provisioned network, and its un-warned residual is declared"*. ⛔ **The ordinal has moved
   since that draft was written** — `doc/adr/` ran to `0039` then and ran to **`0042`** at HEAD `990aebf`
   and PLAN-17 then consumed **`0043`** on 2026-09-10 (the CRIME/BREACH ADR), so the next free ordinal
   is **`0044`** — not the `0040` the draft names, and no longer the `0043` this spec itself claimed
   when staged one day earlier. ⛔ **That is the THIRD stale-ordinal instance in this epic and the
   first where THIS spec was the one that went stale — within 24 hours of being written.** This epic has
   been bitten by a stale ADR ordinal before; re-check it at outline rather than trusting this line.
   ⚠ If PLAN-20 also lands an ADR, the two will contend for `0044` — coordinate at outline.
4. **Close issue #256 against the shipped behaviour, not against the intent.** Reference the issue in
   the change and state in the closing comment what a `/12` now does, with the observable that proves
   it — a boot record, a refusal, or a test.

## Claim Labels

- OBSERVED: `doc/adr/` runs to `0043` at HEAD `7fce677` (PLAN-17 added `0043`, the CRIME/BREACH ADR) and contains **no** ADR on `trusted_proxies`
  breadth or the warn-vs-reject threshold — re-verified during the 2026-09-10 coverage audit by
  listing the corpus and searching it for trust/proxy/forwarded titles. The three hits
  (`0003-forwarded-headers`, `0011-gatewayyaml_exposes_JWKS_trust_and_egress_as_neutral_names`,
  `0036-Forward_filtering_is_a_three-mode_policy`) cover other subjects.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: doc/adr/0044-Trusted-proxy_breadth adoc now exists, exactly the ADR this claim asserted did not exist
- OBSERVED: issue #256 is open, filed by PLAN-06 and corroborated through the CI abstraction.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: gh issue view confirms issue #256 is now closed, not open
- OBSERVED: PLAN-06 moved the value from a committed `gateway.yaml` into an environment variable.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: immutable historical fact, PLAN-06 moved the value to an env var
- ⛔ HYPOTHESIS: `BROAD_PREFIX_IPV4 = 8` and `checkFamilyTrust` still carry the shapes described —
  confirm at outline. These citations are inherited from PLAN-06's landing at `6ba8879`, **not**
  re-read at `990aebf`; this epic has twice been bitten by a citation authored pre-merge.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: ConfigValidator.java now sets BROAD_PREFIX_IPV4=16/IPV6=48, raised by PLAN-15, not 8/32
- ⛔ HYPOTHESIS: PLAN-15's decision log still holds the full ADR draft. Confirm before relying on it;
  if it does not, deliverable 3 is a fresh authoring and is materially larger.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: ADR-0044 successfully landed with the exact draft title carried forward
- Verify-first clause: re-read `ConfigValidator` at HEAD before editing.

## Expected Surface

- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/ConfigValidator.java`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/ConfigLogMessages.java`
- OBSERVED: `doc/adr/0044-trusted-proxies-breadth-threshold.adoc` — new file, ordinal confirmed at outline
- OBSERVED: `doc/configuration.adoc`
- OBSERVED: `doc/LogMessages.adoc`

⛔ **Named files only.** ⚠ The `ConfigValidatorTest` changes this implies are owned by **PLAN-23**,
which declares the whole `api-sheriff/src/test` tree — see Dependencies.

## Dependencies and Sequencing

- ⛔ **Contends with PLAN-23 on `api-sheriff/src/test/**`.** PLAN-23 declares that tree wholesale and
  this plan's threshold change requires test edits inside it. **They must not run concurrently**, and
  this is a directory-versus-file contention of exactly the class this epic has now measured three
  times — recorded here in advance rather than discovered at an emit round.
- ⚠ **Overlaps PLAN-20 and PLAN-21** on `doc/LogMessages.adoc`, and PLAN-09/PLAN-19/PLAN-20 on
  `doc/configuration.adoc`. Not concurrent with any of them.
- ⚠ **ADR ordinal contention with PLAN-20** if that plan also lands an ADR.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-22-trusted-proxy-breadth-threshold.md"
```
