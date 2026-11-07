# PLAN-V02-02: Java idiom sweep — switch expressions, deprecation warnings, Lombok, Java 25 modernization

epic: api-sheriff-0-2-0
workstream: WS-01
track: **POST-0.1.0** — gated behind the release cut. Non-breaking by construction.

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Source: operator code review, 2026-08-01. Ground truth read first-party at `b903526`.
> The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-39-java-idiom-sweep.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**MOVED — D1 seed.** The `AuthenticationStage` if-chain is at **:116–128**, not `:112-127`; its three
constants are at `:69-71`. The shape is unchanged and the deliverable stands.

**MOVED, AND THE SEED IS NOT ALONE — D2.** `GatewayEdgeRoute`'s deprecated-`host()` fallback has
moved from `:420` to **:460**, and there is a **SECOND site with the identical shape at :1146**
(`.host(raw.authority() != null ? raw.authority().host() : raw.host())`). The spec presents one seed;
there are two carriers. Fix both or the deprecation survives the sweep.

**CONFIRMED — the mechanism gap.** Neither `pom.xml` nor `api-sheriff/pom.xml` configures
`showDeprecation`, `-Xlint`, `failOnWarning` or `-Werror`. Control query run: `maven-compiler-plugin`
IS configured in both (`pom.xml`:160, `api-sheriff/pom.xml`:256), so the absence is real and not a
failed search. D4(a) stands as written. Note `pom.xml`:363 documents something rewriting the compiler
`release` down from 25 to 21 — read it before touching compiler config.

**THE CARRIER LIST IS INCOMPLETE — D2, `quarkus.log.file.enable`.** The spec says *"FOUR carriers
plus an environment-variable spelling"*. Mechanical enumeration at HEAD finds **six carrier files /
19 occurrences**:

| Carrier | Occurrences | In the spec? |
|---|---|---|
| `integration-tests/docker-compose.yml` | 7 (one per gateway service) | named, uncounted |
| `doc/development/README.adoc` :243, :286, :287, :288 | 4 | **NO — missing entirely** |
| `api-sheriff/src/test/…/ShippedApplicationPropertiesTest.java` :113, :119, :124 | 3 | yes |
| `api-sheriff/src/main/resources/application.properties` :238 (comment), :246 (property) | 2 | :246 only |
| `integration-tests/…/ItProfileConfigBindingWiringTest.java` :83, :107 | 2 | named as the guard |
| `.claude/skills/run-integration-tests/SKILL.md` :56 | 1 | yes |

**Enumerate mechanically before editing — do not work from this table either; re-run the search.**

> **A tooling caution earned during this very re-grounding.** An unquoted `--include=*` in a
> `grep -r` invocation was expanded by the shell into a filename list, which silently dropped
> `.claude/skills/run-integration-tests/SKILL.md` from the results and made a live carrier look
> retired. Only a control query caught it. This is the same false-negative family the epic already
> tracks for `git grep … -- 'path/**'` and `architecture search --content`. **Quote your globs, and
> run a control before trusting any asserted absence.**

**COST NOTE, new since this spec was written.** `.github/workflows/**`, `Dockerfile*` and
`docker-compose*.yml` are now **gate-requiring** — `build.map` gained the globs in
`api-sheriff-roadmap` PLAN-51 (#196), pinned by `BuildGateCoverageContractTest`. If D2's property
rename reaches the env spelling in `integration-tests/docker-compose.yml`, this plan pays a full
quality gate. Intended trade, not a surprise.

**RENUMBERING.** "Never concurrent with PLAN-38" means **PLAN-V02-01** — still binding. "Prefer after
PLAN-36" and "Overlaps PLAN-34's D6/D7" refer to shipped `api-sheriff-roadmap` plans: read the landed
diffs, do not wait. `ClientHelloSniParser` is no longer reserved to PLAN-34 D7 — that plan shipped.

## Objective

Four idiom-level corrections, plus **the mechanism that makes one of them stick**. Grouped because
each is individually small, all four sweep the same corpus, and running them as four plans would
sweep `api-sheriff/src/main/java/**` four times.

## Why the deprecation warning shipped — the honest analysis

The operator asked directly: *"There is an instruction for fixing all build-warnings. Why did you
commit at all? Wasn't that defined? Did you ignore it?"*

**The instruction exists. Nothing enforces it. It was not ignored — it was unenforceable.**

- **OBSERVED**: `CLAUDE.md` § Pre-Commit Process says both gates *"must pass with zero
  errors/warnings"*. So the rule is written down.
- **OBSERVED**: neither the root `pom.xml` nor `api-sheriff/pom.xml` configures
  `showDeprecation`, `-Xlint`, `failOnWarning`, or `-Werror`. The build **does not fail on a
  warning**, and without `-Xlint:deprecation` javac emits only the summary note, not the per-site
  warning.
- **OBSERVED**: the Maven executor returns a TOON result whose failure channel is
  `errors[N]{file,line,message,category}`. A deprecation warning is **not an error**, so a clean
  `status` with an empty `errors[]` is exactly what a warning-carrying build reports.

**The result**: "zero warnings" was asserted in prose, invisible in the tool output, and unenforced by
the build. That is a **mechanism gap, not a discipline failure** — and deliverable 4 closes it,
because a prose rule that no gate enforces will be re-broken by the next contributor.

## Deliverables

**Four deliverables.**

1. **Replace `if`/`else`-on-constant chains with `switch`.**
   **OBSERVED seed** — `auth/AuthenticationStage.java:112-127`:
   ```java
   if (REQUIRE_NONE.equals(require)) { return; }
   if (REQUIRE_BEARER.equals(require)) { validateBearer(request, auth, route); return; }
   if (REQUIRE_SESSION.equals(require)) { requireSessionStage(route).process(request); return; }
   throw new IllegalStateException("… unsupported require '" + require + "'");
   ```
   A three-branch dispatch over a closed constant set, ending in a throw for the unhandled case —
   textbook switch, and the trailing `throw` is exactly what an exhaustive switch makes unnecessary.
   **Sweep for the same shape corpus-wide, not just this site.**
   **The stronger fix is worth evaluating**: `require` is a `String` compared against three
   constants. If the value range is genuinely closed, **an enum makes the switch exhaustive and
   deletes the throw** — decide per site and record the reasoning; do not mechanically convert a
   string chain into a string switch and call it done.

2. **Fix every fixable deprecation warning.**
   **OBSERVED seed** — `edge/GatewayEdgeRoute.java:420`:
   ```java
   String host = ctx.request().authority() != null ? ctx.request().authority().host() : ctx.request().host();
   ```
   Note the site is **already half-migrated** — `authority()` is the replacement API and is used
   first; the deprecated `ctx.request().host()` survives only as the null fallback, which is why the
   warning persists. **Determine whether the fallback is reachable at all**; if `authority()` is
   non-null for every code path that reaches here, the fallback is dead and deletes cleanly.
   Then enumerate the rest: **turn on the lint first (deliverable 4), then fix what it reports** —
   enumerating deprecations by grep before the compiler can list them is guesswork.
   **Where a deprecation is NOT fixable** (no replacement, or the replacement is not in the shipped
   platform version), suppress it **with a rationale at the site**, never silently.

   **WIDEN THIS DELIVERABLE TO CONFIGURATION-PROPERTY DEPRECATIONS, NOT ONLY JAVA API ONES.** The
   Java lint of deliverable 4 will never report them, so a deliverable scoped to what the compiler
   emits would close while leaving a deprecation warning in the shipped boot log.

   **GitHub issue [#178](https://github.com/cuioss/API-Sheriff/issues/178), routed here 2026-08-07.**
   The published image logs, as the **second line an operator sees**:

   ```
   WARN [io.quarkus.config] (main) The "quarkus.log.file.enable" config property is deprecated and
   should not be used anymore.
   ```

   Cosmetic today; a future Quarkus upgrade that removes the property turns it into a **boot
   failure**, which is why it belongs in a sweep rather than in a backlog.

   **The property has FOUR carriers plus an environment-variable spelling — enumerate before
   editing, this is not a one-line change.** Verified at `origin/main` (`b8dde22`):
   - `api-sheriff/src/main/resources/application.properties`:246 — `quarkus.log.file.enable=false`
     (**note: the roadmap ledger recorded this at :221-225; it has MOVED — re-anchor on content**)
   - `api-sheriff/src/test/java/…/quarkus/ShippedApplicationPropertiesTest.java`:113, :119, :124 —
     three sites, one of which is a `%dev.` profile-prefixed spelling
   - `.claude/skills/run-integration-tests/SKILL.md`:56 — documents the pairing
   - the integration-test services set `QUARKUS_LOG_FILE_ENABLE=true` plus `LOG_FILE_PATH`; the
     env-var form must move in lockstep or file logging silently stops and `ManagementPlainHttpOptOutIT`,
     which reads one of those files, fails on a symptom that looks unrelated to a property rename.
     `ItProfileConfigBindingWiringTest` guards the enable/path pairing — check it still guards the
     replacement.

   **Sequencing note**: `application.properties`:246 is near the token-sheriff exclusion block at
   :203 that PLAN-V02-09 also edits. Not a conflict at these line numbers, but sequence the two
   deliberately rather than assuming.

   **CLOSE [#178](https://github.com/cuioss/API-Sheriff/issues/178) WHEN THIS DELIVERABLE LANDS** —
   comment naming the PR and merge commit, then close it. A PR body that merely *mentions* an issue
   does not link or close it; issues #182/#183 sat open after their implementing PR landed for
   exactly that reason.

3. **Apply Lombok where the standards call for it.**
   The operator's framing is precise and must be respected: *"It is not about all with lombok
   (records are more sensible) but use it where demanded."*
   **OBSERVED**: `pm-dev-java:java-lombok` states *"Immutable data carrier → Java record (not
   `@Value`)"* — so the record-heavy config model is **already compliant** and must not be converted.
   The gap is elsewhere: mutable builders, `@NonNull` parameter contracts, `@UtilityClass` on
   static-only holders. **Enumerate against the skill's actual decision table and change only what it
   demands** — a mass Lombok application would contradict both the skill and the operator.

4. **Make the warning gate real, and research Java 25 automation.**
   Two halves, both named:
   - **(a) Enforce it.** Add deprecation/lint reporting to the build so a warning is *visible* in the
     executor's output, and decide with the operator whether it becomes **fail-on-warning**. Without
     this, deliverable 2 regresses silently.
   - **(b) Research OpenRewrite for Java 25.** The operator recalls Sonar findings about **unnamed
     variables** (`_`). Establish whether an OpenRewrite recipe exists for that and for other Java 25
     modernizations, and whether it can join the existing `cui-rewrite` setup this repo already runs.
     **Research and report is a complete outcome** — "no suitable recipe exists" is a valid finding.
     **Do not add a plugin or dependency without explicit operator approval.**

## Claim Labels

- **OBSERVED** (`b903526`, first-party): the `AuthenticationStage` if-chain verbatim at `:112-127`;
  the `GatewayEdgeRoute:420` line verbatim including the `authority()`-first fallback shape;
  the absence of `showDeprecation` / `-Xlint` / `failOnWarning` / `-Werror` from both POMs;
  `CLAUDE.md`'s zero-warnings sentence; the executor's `errors[N]` result shape;
  the java-lombok skill's "record (not `@Value`)" decision row.
- **HYPOTHESIS (verify-at-outline)**: that `GatewayEdgeRoute:420` is the *only* deprecation warning,
  or even that it is representative. **Confirm/refute artifact**: a build run with deprecation lint
  enabled. **The orchestrator did not run a build — the count is unknown and could be large.**
- **HYPOTHESIS (verify-at-outline)**: that an OpenRewrite recipe exists for unnamed variables.
  **Confirm/refute artifact**: the OpenRewrite recipe catalogue. Refutation is a valid outcome.
- **HYPOTHESIS (verify-at-outline)**: that the `AuthenticationStage` chain is not unique.
  **Confirm/refute artifact**: a corpus sweep for `if (CONST.equals(x))` chains.
- **OBSERVED (absence)**: the orchestrator did **not** run any build, did **not** enumerate
  deprecations beyond the operator's seed, and did **not** enumerate Lombok-eligible sites.

## Expected Surface

- OBSERVED: `.../auth/AuthenticationStage.java` — D1 seed
- OBSERVED: `.../edge/GatewayEdgeRoute.java` — D2 seed
- HYPOTHESIS: `api-sheriff/src/main/java/**` broadly for all three sweeps — bounded by each
  deliverable's enumeration, not by this list
- OBSERVED: `pom.xml` and/or `api-sheriff/pom.xml` — D4(a), compiler configuration only
- HYPOTHESIS: `CLAUDE.md` — **only if** D4(a) changes what the pre-commit gate actually enforces;
  the prose and the mechanism must agree afterwards
- OBSERVED (absence, deliberate): **no behavioural change and no config-surface change.** A switch
  that changes which branch runs is a bug introduced by this plan.

## Dependencies and Sequencing

- **HARD GATE: `api-sheriff-roadmap` must be CLOSED.**
- **Never concurrent with PLAN-38** — both sweep `api-sheriff/src/main/java/**` broadly.
- **Prefer after PLAN-36** — 36 rewrites signatures across the same corpus; sweeping idioms first
  means re-sweeping after 36 lands.
- **D4(a) is worth doing FIRST inside this plan**, out of deliverable order: with the lint on, D2's
  enumeration comes from the compiler instead of from grep.
- **Overlaps PLAN-34's D6/D7** (`api-sheriff/src/test/**`, and `ClientHelloSniParser`'s Sonar
  findings). If PLAN-34 has not landed, **do not touch `ClientHelloSniParser` here** — it is D7's.

## Standing Epic Clauses

- **SONAR ZERO-FINDINGS** — red is a HARD STOP.
- **NAMED LINE ITEMS** — four named deliverables; D4's two halves (a) and (b) are **separately
  named** and an outline that drops the research half is a finding.
- **NEVER ADD DEPENDENCIES OR PLUGINS WITHOUT EXPLICIT USER APPROVAL** — binds D4(b).
- **A GREEN SUITE IS NOT EVIDENCE** (clause 12).
- **STAMP THE HEAD** — premises carry HEAD `b903526`; re-ground at outline.

## Finalize Boundary — the plan STOPS at the merge

**Operator ruling, 2026-07-30.** The plan owns everything through the merge, then REPORTS AND STOPS.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-02-java-idiom-sweep.md" plan_id=plan-v02-02-java-idiom-sweep
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The executing plan MUST NOT create or edit any file under
`.plan/local/orchestrator/api-sheriff-0-2-0/`. Its two channels back to the epic are its PR and its
`inbox/` OUTBOX.
