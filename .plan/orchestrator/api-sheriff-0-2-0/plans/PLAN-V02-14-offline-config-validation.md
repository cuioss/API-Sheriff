# PLAN-V02-14: validate a gateway configuration without starting the runtime

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> The orchestrator EMITS the command below; it never launches the plan inline.
>
> **Owns GitHub issue [#175](https://github.com/cuioss/API-Sheriff/issues/175)**, filed by an
> adopter standing up a real deployment. No other plan in this epic covers it.

## Objective

Let a `gateway.yaml` / `endpoints/` / `topology.properties` set be checked **without a running
gateway**, so a configuration change is gateable in CI and an operator can check a change before
restarting a live gateway.

## Why this is a real gap and not a convenience

**The machinery already exists and is good.** The boot is deliberately fail-closed and aggregates
every violation in one pass. That is exactly what makes it valuable *outside* the runtime too. What
is missing is a way to invoke it without a gateway. No validate-and-exit entry point exists in any
form today.

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

**That list comes from the issue and is incomplete.** `ConfigValidator` is roughly 2,600 lines and
carries more rules than the five above — among them `checkForwardDimension`,
`checkRouteInsideDeclaredAnchorNamespace`, `checkRouteDeclaresContainingAnchor`, `checkFamilyTrust`
and `checkCors`, and those are only the `check*`-named helpers. Enumerate its actual rule set at
outline rather than working from the issue's names.

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
   > hand-rolled flag. Do not re-derive that decision here — read V02-01's ADR verdict. If V02-01
   > has not landed when this plan reaches outline, record that the command-mode option could not
   > be read rather than assuming either answer.

4. **Make it usable as a CI gate, which is the stated purpose.**

   Non-zero exit on any violation; the aggregated list on stdout or stderr with a stated choice;
   a documented invocation an adopter can paste into a pipeline. **Wire it into this repository's own
   CI against the shipped sample and integration-test configurations** — a validator nobody runs
   rots, and this project has three configuration sets it could be proving against continuously.
   `.github/workflows/**` is a gate-requiring path, so this wiring runs the full pre-commit process.

5. **Documentation** in `doc/user/` — where an adopter looks — not only in a development document.
   Write it against the landed layout of that directory, which includes `anchors.adoc` and
   `endpoint-routes.adoc`.

## Claim Labels

- OBSERVED: a tree-wide search for `validate-config` / `validateConfig` /
  `--validate` returns **zero** hits outside `.plan/`. The feature does not exist in any form.
  (Run the search without a pathspec: a `**`-globbed pathspec returns a false zero on every query
  in this tree — see the note in Dependencies.)
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: no validate-config/--validate entry point outside .plan/ (control: ConfigValidator hits 32 files)
- ASSERTED BY THE ISSUE, NOT RE-VERIFIED: the five cross-document rule names attributed to
  `ConfigValidator`. Read the class and enumerate its actual rules; the list is a lead and may be
  incomplete — `ConfigValidator` gained at least one rule in 0.1.1 (the forward `*_allow`/`*_deny`
  mutual exclusion, `checkForwardDimension`) that postdates the issue.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: ConfigValidator (2586 lines) checkForwardDimension:603 checkRouteInsideDeclaredAnchorNamespace:1167 checkRouteDeclaresContainingAnchor:1181 checkFamilyTrust:1867 checkCors:2031

## Expected Surface

- `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/validation/**` — D1
- the runner entry point / a new module — D3
- `.github/workflows/**` — D4
- `doc/user/**` — D5

## Dependencies and Sequencing

- **Read PLAN-V02-01's verdict before D3.** See the note in D3; this is a read, not a blocker.
  PLAN-V02-01 runs alone, so if it is in flight this plan waits regardless of the read.
- Overlaps with: any plan declaring `.github/workflows/**`, `doc/user/**` or
  `config/validation/**` (`PLAN-V02-13` declares `ConfigValidator.java`; `PLAN-V02-15` declares
  `.github/workflows/**` and `doc/user/compose-sample.adoc`). The disjointness gate decides at emit
  time.
- **Tooling caution for every asserted absence in this plan.** `git grep <pattern> origin/main --
  'path/**'` has returned a clean zero for every query in this tree while the same search without
  the pathspec returned hits, and an unquoted `--include=*` in `grep -r` is shell-expanded and
  silently drops carriers. Quote globs, and run a control query before trusting a zero.

## Issue Closure

**CLOSE [#175](https://github.com/cuioss/API-Sheriff/issues/175) WHEN THIS PLAN LANDS** — comment
naming the PR and merge commit, state which deliverable discharged it, and close it. If the
standalone-artifact fallback was taken instead of the flag, say so on the issue: the reporter offered
both and is entitled to know which shipped.

**A PR body that merely mentions an issue does NOT link or close it** — use a closing keyword or
close explicitly after the merge.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-14-offline-config-validation.md" plan_id=plan-v02-14-offline-config-validation
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/orchestrator/` other than its own `inbox/{sender}-{seq}` message, and
reports its outcome through its PR and that message.
