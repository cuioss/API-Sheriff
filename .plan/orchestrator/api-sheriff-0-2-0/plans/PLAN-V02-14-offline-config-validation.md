# PLAN-V02-14: validate a gateway configuration without starting the runtime

epic: api-sheriff-0-2-0
workstream: WS-01

> **Owns GitHub issue [#175](https://github.com/cuioss/API-Sheriff/issues/175)**, filed 2026-08-06 by
> an adopter standing up a real deployment, routed here 2026-08-07. **No existing 0.2.0 plan covers
> it** — checked against all twelve specs before staging this one.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This spec holds; no correction is owed.**

**ASSERTED ABSENCE RE-CONFIRMED, WITH A CONTROL.** A tree-wide search for `validate-config` /
`validateConfig` / `--validate` outside `.plan/` returns **zero** hits. Control query passes:
48 files reference `ConfigValidator`, so the search reaches the right tree. **The feature does not
exist in any form.**

**CONFIRMED — the rule list is a lead, and the spec is right that it is incomplete.**
`ConfigValidator` does carry `checkForwardDimension` (:492), the 0.1.1 addition the spec names as
postdating the issue, alongside `checkRouteInsideDeclaredAnchorNamespace` (:699),
`checkRouteDeclaresContainingAnchor` (:713), `checkFamilyTrust` (:1063) and `checkCors` (:1147) —
and those are only the `check*`-named helpers. **The class is well over a thousand lines; enumerate
its actual rule set at outline rather than working from the issue's five names.**

**SEQUENCING NOTE, sharpened.** D3's cross-check against `PLAN-V02-01` is a **read, not a blocker** —
but V02-01 is the epic's load-bearing plan and *runs alone*, so if it is in flight this plan waits
regardless of the read. If V02-01 has landed, read its ADR verdict on platform mechanisms before
choosing between a native-runner flag and a standalone artifact: Quarkus command mode may be the
answer the reversal makes available.

**GATE COST.** `.github/workflows/**` is now gate-requiring (`build.map`, roadmap PLAN-51 / #196),
so D4's CI wiring pays a full quality gate. Intended trade.

**The tooling caution in Dependencies is live and was re-earned during this re-grounding** — an
unquoted `--include=*` glob in a `grep -r` was shell-expanded and silently dropped a real carrier
from the results. Quote your globs; run a control before trusting any asserted absence.


## Re-Grounded (2) 2026-08-09 at `95dd566` — after four landings

`PLAN-V02-02`, `-03`, `-16` and `-17` have shipped. **This section outranks the 2026-08-08
re-grounding above it wherever they conflict.**

**EPIC-WIDE, AND NO SPEC BELOW KNOWS IT: THE BUILD NOW FAILS ON ANY COMPILER WARNING.**
`PLAN-V02-02` turned on `<showDeprecation>true</showDeprecation>` **and**
`<failOnWarning>true</failOnWarning>` reactor-wide (`pom.xml`:163, :178), so javac runs with
`-Werror` across all six modules. A deprecated API or an unchecked cast is now a **build failure**,
not a log line. Two consequences bind every plan:

1. **Answer such a failure by migrating off the warned construct.** `CLAUDE.md` states it directly:
   a `@SuppressWarnings` added to get back to green *"hollows the gate out while leaving it reporting
   success"*, and it collides with the Pre-1.0 rule forbidding deprecated code at all.
2. **The failure reaches the executor as a `warnings[]` row plus a `-Werror` `errors[]` row.** Read
   both arrays — the line number lives on the warning row.

**ANCHORS HELD** at `95dd566`: the `validate-config` / `validateConfig` / `--validate` absence is
still real (control passes), and `ConfigValidator` still carries `checkForwardDimension` among its
rules.

**D5's DOCUMENTATION TARGET MOVED.** `doc/user/` is now **ten** pages after `PLAN-V02-03`'s
restructure, with `anchors.adoc` and `endpoint-routes.adoc` new. Write D5 against the landed layout.

**D3's cross-check on `PLAN-V02-01` is unchanged and still a READ, not a blocker** — but V02-01 has
not started, so if it is still unstarted at this plan's outline, record that the Quarkus-command-mode
option could not be read rather than assuming either answer.

## Objective

Let a `gateway.yaml` / `endpoints/` / `topology.properties` set be checked **without a running
gateway**, so a configuration change is gateable in CI and an operator can check a change before
restarting a live gateway.

## Why this is a real gap and not a convenience

**The machinery already exists and is good.** The boot is deliberately fail-closed and aggregates
every violation in one pass. That is exactly what makes it valuable *outside* the runtime too. What
is missing is a way to invoke it without a gateway.

**The workaround an adopter actually reached for does not cover the rules that matter.** The reporter
ran the shipped JSON schemas by hand:

```python
Draft202012Validator(json.load(open("gateway.schema.json"))).iter_errors(doc)
```

— **after resolving the `${VAR}` placeholders manually, because the schemas see resolved values
while the files carry placeholders.** That catches structural mistakes (unknown keys, wrong types,
missing required fields) and misses every cross-document rule, which is where the errors actually
are. Those live in `ConfigValidator`:

- anchor `path_prefix` pairwise disjointness — *and its segment-wise, not string-prefix, semantics*
- route-to-anchor namespace membership
- the access→auth matrix
- topology alias resolvability
- same-prefix route disjointness

**The reporter's design turned out correct — but they only knew that after pulling an image and
reading a boot log.** That is the cost being removed.

## Deliverables

1. **A validate-and-exit mode that runs the SAME validator the boot runs.**

   The issue's suggested shape: `--validate-config` on the runner, exiting non-zero and printing the
   same aggregated violation list.

   **The binding constraint is single-sourcing.** The value of this feature is that it gives the
   *same verdict* as the boot. A second validation path that drifts from `ConfigValidator` is worse
   than nothing, because it produces confident green on configurations the gateway will refuse.
   **State how the shared path is enforced** — a test that runs both and asserts identical
   aggregated output is the obvious mechanism; say so rather than relying on the code looking shared.

2. **Settle the placeholder question explicitly — it is the sharp edge, not a detail.**

   The schemas see resolved values while the files carry `${VAR}` placeholders. So a validator that
   resolves the environment behaves differently on a developer laptop than in the target deployment,
   and one that does not resolve cannot check anything downstream of a placeholder. **Neither answer
   is obviously right; pick one, write down which, and make the failure mode of the unchosen one
   visible in the output** (e.g. report which values were unresolved rather than silently treating a
   placeholder as valid).

3. **Decide the packaging: a flag on the native runner, or a small standalone validator artifact.**

   The issue offers both and prefers the flag, with the standalone artifact as the fallback *"if a
   flag on the native runner is awkward"*. It may well be awkward — the native image is a server, and
   a validate-and-exit path inside it has to avoid starting listeners, opening the IdP connection, or
   binding anything. **Evaluate against the native image's actual startup path rather than assuming
   the flag is cheap**, and record the verdict either way.

   > **Cross-check against PLAN-V02-01 (ADR-0005 reversal — Quarkus/Jakarta adoption) before
   > choosing.** Quarkus has first-class command-mode support; if V02-01 moves the project toward
   > platform mechanisms, a Quarkus command-mode entry point may be the answer rather than a
   > hand-rolled flag. Do not re-derive that decision here — read V02-01's verdict.

4. **Make it usable as a CI gate, which is the stated purpose.**

   Non-zero exit on any violation; the aggregated list on stdout or stderr with a stated choice;
   a documented invocation an adopter can paste into a pipeline. **Wire it into this repository's own
   CI against the shipped sample and integration-test configurations** — a validator nobody runs
   rots, and this project has three configuration sets it could be proving against continuously.

5. **Documentation** in `doc/user/` — where an adopter looks — not only in a development document.

## Claim Labels

- OBSERVED (2026-08-07, `b8dde22`): a tree-wide search for `validate-config` / `validateConfig` /
  `--validate` returns **zero** hits outside `.plan/`. The feature does not exist in any form.
  (The search was re-run without a pathspec after an earlier `**`-globbed pathspec returned a false
  zero on every query — see the note in Dependencies.)
- ASSERTED BY THE ISSUE, NOT RE-VERIFIED: the five cross-document rule names attributed to
  `ConfigValidator`. Read the class and enumerate its actual rules; the list is a lead and may be
  incomplete — `ConfigValidator` gained at least one rule in 0.1.1 (the forward `*_allow`/`*_deny`
  mutual exclusion, `checkForwardDimension`) that postdates the issue.

## Expected Surface

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/**` — D1
- the runner entry point / a new module — D3
- `.github/workflows/**` — D4
- `doc/user/**` — D5

## Dependencies and Sequencing

- **Read PLAN-V02-01's verdict before D3.** See the note in D3; this is a read, not a blocker.
- May run concurrently with PLAN-V02-13 and PLAN-V02-15 — no shared surface.
- **A tooling caution that cost time on 2026-08-07**: `git grep <pattern> origin/main -- 'path/**'`
  returned a **clean zero for every query** in this tree while the same searches without the pathspec
  returned hits. An asserted absence must be verified with a control query, exactly as the epic's
  `architecture search` and `search.maven.org` false negatives already require.

## Issue Closure

**CLOSE [#175](https://github.com/cuioss/API-Sheriff/issues/175) WHEN THIS PLAN LANDS** — comment
naming the PR and merge commit, state which deliverable discharged it, and close it. If the
standalone-artifact fallback was taken instead of the flag, say so on the issue: the reporter offered
both and is entitled to know which shipped.

**A PR body that merely mentions an issue does NOT link or close it** — use a closing keyword or
close explicitly after the merge. Issues #182/#183 sat open after their implementing PR landed for
exactly this reason.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-14-offline-config-validation.md" plan_id=plan-v02-14-offline-config-validation
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message.
