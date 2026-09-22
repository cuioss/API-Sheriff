# PLAN-V02-17: land the corpus lessons into repository source

epic: api-sheriff-0-2-0
workstream: WS-02

> **This plan is the destination of a close-out disposition, not a discovery.** The
> `api-sheriff-roadmap` close-out (2026-08-08) drained the repository-global lessons corpus. Twelve
> lessons were dispositioned **repository source** — they are standing rules about how *this code* is
> built, and a rule that lives only in a corpus nobody reads at the moment of the mistake is not a
> rule. Each is archived verbatim at
> `.plan/local/archived-orchestrators/api-sheriff-roadmap/archive/lessons-consumed/{id}.md`
> — **path corrected 2026-08-08; the epic was archived after this spec was written. See
> § Re-Grounded.**

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`, then a **full corpus audit** of all 25 archived lessons
(operator-requested, same day). **This section outranks any conflicting line below it.**

**⚠ THE SOURCE PATH IN THE HEADER WAS BROKEN AND IS NOW FIXED.** The header said the lessons were at
`.plan/local/orchestrator/api-sheriff-roadmap/archive/lessons-consumed/`. **Nothing exists there** —
`api-sheriff-roadmap` was closed *and archived*, which relocates the whole tree. The verified path is
`.plan/local/archived-orchestrators/api-sheriff-roadmap/archive/lessons-consumed/`, holding **25**
archived lesson files. A plan following the old path would have found an empty directory and no
source material at all.

**THE DELIVERABLES BELOW WERE RE-CLUSTERED 2026-08-08** on the audit's findings. The original D2
cluster was wrong on its central member, two lessons were near-duplicates, one lesson's prescription
is refuted by shipped code, two lessons contradict each other outright, and the plan was carrying a
thirteenth lesson that was dispositioned elsewhere. All six are resolved in the deliverables.

**SEQUENCING — the one real overlap.** This plan writes into `doc/development/**` (D5, D6) while
`PLAN-V02-03` restructures the documentation tree; V02-03's own re-grounding found
`doc/configuration.adoc` at 2539 lines and `doc/user/` at eight pages, so its split is *larger* than
staged. **Landing prose into documents V02-03 is about to split or relocate wastes both plans.**
Either run this plan first and let V02-03 carry the text along, or run it after V02-03 and write into
the settled layout — choose deliberately rather than letting queue order decide. Otherwise
surface-disjoint from V02-13, V02-14, V02-15 and V02-16.

## Objective

Put each lesson where the person about to make the mistake will actually read it — `CLAUDE.md`,
`doc/development/**`, or the relevant skill — and nowhere else.

## The binding constraint

**A lesson is not landed by being quoted.** The largest cluster below describes a mechanism that
produces a *confident false green* or a *silent revert*; restating those as prose in a document
nobody opens mid-task repeats the failure this whole disposition exists to fix. **Where a lesson can
be enforced rather than stated, enforce it** — a test, a gate, or a check — and where it genuinely
cannot, say so and put it in the narrowest document whose reader is the person at risk.

## Deliverables

### D1 — Triage to a destination before editing anything, and settle the three audit conflicts first

Record the destination per lesson. `CLAUDE.md` is the highest-traffic surface and therefore the
scarcest — a lesson goes there only if it applies to *most* work, not merely to important work.

**Three conflicts must be settled before any text is written. They are inputs to this deliverable,
not discoveries to be made during it.**

**(a) TWO ACTIVE LESSONS GIVE DIRECTLY OPPOSITE INSTRUCTIONS. Resolve, then land ONE rule.**

| Lesson | Says |
|---|---|
| `2026-07-27-09-001` | *"**Keep the formatter's own output.** When the gate rewrites unrelated files, commit that output as-is rather than reverting it; reverting only guarantees the same diff reappears on the next run."* |
| `2026-08-02-17-001` | the workaround every plan must apply is *"keep the rewrite only for files the branch itself authored, and **revert the rest wholesale** so the PR stays focused."* |

Same situation — the gate rewrote files the branch never touched — and opposite prescriptions. Both
were `status=active`; the original clustering put them in different groups, so the contradiction
would never have been seen at landing time.

**The evidence favours REVERT**, and the resolution should say so with its reasons rather than
picking silently: the operator's standing note is *"revert it, never commit it into a scoped PR"*,
and `2026-08-08-11-001` refers to the churn as something one would *"revert as noise"*. The
countervailing argument in `09-001` — that reverting guarantees the diff reappears — is **true and
not a reason to commit it**: the diff reappearing is the cost of `main` not being at the formatter's
fixed point, and paying it per-PR is cheaper than polluting every scoped PR with ~170 unrelated
files. **Land: revert unrelated churn, keep the rewrite for files the branch authored, and record
that bringing `main` to the fixed point is the durable fix nobody has done.** If the outline
disagrees, it must say why in the same words — do not land both lessons and leave the reader to
choose.

**(b) `2026-08-02-15-003`'s PRESCRIPTION IS REFUTED BY SHIPPED CODE. Land the diagnosis, not the
rule.** Its solution says *"Never bind teardown to `post-integration-test` behind a plugin that
aborts on failure."* The project does exactly that, deliberately, and documents it in three places:

- `demo-client/pom.xml`:144–159 binds the teardown to `post-integration-test`, with a 15-line
  rationale at :129–143 that names it *"a decision, not an omission"* — a failing local run is one
  the developer usually **wants** the containers left up for, and tearing down would destroy the logs
  of the failure being diagnosed.
- `.github/workflows/demo-client-e2e.yml`:83 carries the `if: always()` CI teardown, because an
  abandoned stack on a shared runner holds the fixed host ports.
- `demo-client/doc/playwright-suite.adoc`:269–276 documents the behaviour for developers.

The project chose a third option the lesson does not list. **Land the mechanism** (a
failure-aborting plugin means `post-integration-test` is never reached, so success-path-only teardown
is what that binding gives you) **and the documented CI/local split. Do not land "never bind" —
it would contradict three in-tree records.**

**(c) `2026-08-02-15-002` IS NOT ON THE CLOSE-OUT'S LIST FOR THIS PLAN — adopting it is a decision,
recorded here.** The corpus has **twelve** lessons dispositioned `repository source`; this plan
originally named **thirteen**. The extra one, `2026-08-02-15-002` (*BUILD SUCCESS is not evidence
that work happened*), is dispositioned **successor ledger** and is already standing rule (1) in this
epic's `epic.md`.

**Adopt it anyway, and here is why**: the epic ledger is orchestrator-facing — it governs how the
epic is *run* — while D2 below lands a concrete instance of exactly this class into `CLAUDE.md`,
which is implementer-facing. Landing the class statement without its parent rule would leave the
narrow case stated and the general one missing. **The double-disposition is therefore deliberate, not
accidental.** State the general rule once in `CLAUDE.md` as D2's opening line and reference it from
the specific cases; do not restate the ledger's copy.

### D2 — THE GATE REWRITES YOUR TREE AND STILL EXITS 0 — three lessons, one mechanism

**This is the re-clustering's central correction and the highest-value group in the corpus.**
`2026-08-02-17-001` was previously filed as a false-green lesson. **It is not one** — the gate
genuinely *passes*; the defect is that it **mutates the working tree while exiting 0**. Its real
siblings were sitting in two other places:

- `2026-08-02-17-001` — `verify -Ppre-commit` emits ~170 files of non-idempotent OpenRewrite
  import-group churn, pollutes every PR, and **poisons the change-ledger freshness stamp** (the
  ledger stamps `worktree_sha` at build *end*, so it records the churned tree; reverting then
  restores the pre-build sha and `pre-commit-verify-freshness` returns `stale` for a commit whose
  gate just passed).
- `2026-07-27-09-001` — the pre-commit formatter is **non-idempotent** (A→B and B→C, not B→B) and
  `main` is not its fixed point, so hand-editing toward "what the formatter wants" cannot converge.
- `2026-08-08-11-001` — OpenRewrite `SimplifyTestThrows` **silently reverts an accepted review-bot
  fix** and still exits 0. Verified still live at HEAD: `JUnit5BestPractices` is in the `pre-commit`
  recipe list (`pom.xml`:436) and `BuildGateCoverageContractTest`:142/:171 still read
  `throws Exception` — the gate won and the narrowing did not survive.

**State the class once**: *a gate that exits 0 can still have changed your files, and three different
mechanisms in this repo do.* Then the three consequences, which are what a reader actually needs:

1. **A review-bot suggestion is not verified by implementing it, only by surviving the gate.** Run
   the gate, then `git status --porcelain`.
2. **Attribute a post-gate dirty tree before reverting it.** The OpenRewrite log prints
   `Changes have been made to <file> by:` with the recipe chain — read that rather than assuming the
   familiar import churn. (And note `build-maven rewrite-log` returned `verdict: not_observed` for a
   build in which `rewrite:run` demonstrably ran, because the parser keys on a findings marker; do
   not read that verdict as evidence OpenRewrite was inactive.)
3. **Never hand-edit formatter-owned whitespace, wrapping or import order.** Two rounds of
   "reformat by hand, re-run, get a different result" is proof of non-idempotency — stop and revert
   the cosmetic edits.

**THIS CLUSTER IS ENFORCEABLE AND THAT IS WHY IT LEADS.** A single post-gate
`git status --porcelain` assertion covers all three mechanisms. **Prefer that to prose** — the
binding constraint above applies hardest here, since every one of these three failures is invisible
in the build result by construction. If a check is not feasible, say why.

Recipe-scoping trap worth one line: `rewrite:run` **without** `-Ppre-commit` reports
`Using active recipe(s) []` and changes nothing, so a scoped reproduction without the profile is a
false negative that appears to exonerate OpenRewrite.

### D3 — INCREMENTAL BUILDS SKIP THE THING YOU CHANGED — two near-duplicate lessons, land as ONE

`2026-08-02-17-002` and `2026-08-05-07-001` are **the same lesson twice**: same mechanism (Maven's
incremental `testCompile` compares *test* source timestamps against *test* classes, so a
`src/main/java`-only change leaves them "up to date" and compilation is skipped entirely), same rule,
same detection marker (`Nothing to compile - all classes are up to date`). They differ only in
instance:

| Lesson | Instance | `clean test-compile` then gave |
|---|---|---|
| `2026-08-02-17-002` | PLAN-36 TASK-8, production-only change | **142** compilation errors |
| `2026-08-05-07-001` | PLAN-49 D3, `SessionStore.create` signature change | **26** errors |

**Land one rule with both instances as evidence** — two anecdotes for one mechanism is exactly the
proliferation D1 exists to prevent. The rule: *a build whose output says it did nothing has verified
nothing.* After any production-only or signature change, `clean test-compile` (or `clean verify`);
`test` inherits the same defect because Surefire runs whatever is already in `target/test-classes`.

This is a specific case of D1(c)'s general rule, so **reference that line rather than restating it**.

### D4 — A CONFIG KEY THAT PARSES IS NOT A CONFIG KEY THAT ACTS — the one cluster that was already right

`2026-08-01-17-001` and `2026-08-01-17-002` are left **exactly as clustered**. They are the
best-formed pair in the corpus: they cross-reference each other, and 17-002's own closing text says
*"The two lessons are a pair… State 2 is the hazard in both directions — living in it, and leaving
it."*

- **`17-001`** — a parsed, schema-validated key with no production consumer is a silent security
  no-op. `TlsConfig.minVersion`/`alpn` had **zero** production readers, so the intended TLS floor and
  cipher-suite restriction applied to **no listener at all** while every visible signal said
  otherwise. Its discriminator is the line to land: **"if the key were deleted entirely, would any
  test go red?"** If no, the control it names is not in effect.
- **`17-002`** — wiring a previously-inert key is a **behavioural flip for every consumer**, and the
  failing test is a *sample* of the dependant set, not the whole of it. Updating the test closes the
  investigation and leaves the un-sampled dependants to fail later, further from the change.

**Both are verified still-true at HEAD** and both fixes shipped: `minVersion` now has the non-test
consumer `TlsServerCustomizer`; `SingleSourceTlsContractTest`, `CipherSuiteFixtureWiringTest` and
`ManagementPlainHttpActivationWiringTest` all exist; `max_authorization_header_value_length` is in
`gateway.schema.json`:160 and `SecurityDefaultsConfig`. **So this cluster lands as a rule with its
guards already in place to point at** — the strongest possible form, and the reason it should be
stated as *extend `SingleSourceTlsContractTest`*, never as *write a new contract test*.

Both are security-relevant. `doc/development/**` is the destination; `CLAUDE.md` gets at most the
one-line discriminator.

### D5 — SONAR FINDINGS VERSUS THE DECLARED FOOTPRINT — a new pairing

Two lessons that were never clustered together and answer the same question from opposite ends:
*what does a plan do about a Sonar finding that its own declared footprint does not cover?*

- **`2026-07-23-00-001`** — a cosmetic sweep **drags pre-existing uncovered lines into the PR
  new-code coverage gate**, because Sonar's new-code window is the diff, not semantics. A pure rename
  inside an already-uncovered catch block fails `new_coverage`. Preferred remedy: **do not let a
  cosmetic sweep touch lines inside uncovered branches.**
- **`2026-08-04-05-001`** — findings inside the scanned surface but **outside** a plan's declared
  write-boundary are dispositioned `taken_into_account` and **reported as named follow-ups**, never
  absorbed. Absorbing them silently widens the blast radius past the boundary the spec declared.

Together they say: *the plan's footprint governs, in both directions* — don't let a sweep pull
unrelated lines in, and don't let a finding pull your diff out.

`doc/development/sonar-quality-gate.adoc` is the destination for both.

> **Land the rules, NOT `08-04-05-001`'s evidence table.** Its four `file:line` anchors no longer
> resolve — `RouteTableBuilder:80` is now a javadoc close, and `BackchannelLogoutEndpoint:137` is now
> a comment stating the single-exit fold was **done**, which suggests `java:S135` was *fixed* rather
> than deferred. Re-derive the current deferral list from the live gate if one is wanted, or omit it.
> The decay is itself an instance of `2026-08-06-08-002`'s rule that line-number cross-references are
> unowned duplicated state — one lesson's evidence violating another lesson's rule.

### D6 — TEST-LANE FIDELITY — four lessons, and two of them are not documentation edits

- **`2026-07-25-15-001`** — an opt-in runtime feature needs a **deployment-activation** test, not
  only unit tests. Unit tests prove `config → behaviour`; they do not prove the deployment sets that
  config. The pattern to point at exists: `TlsEdgeActivationWiringTest` (verified present). Land as a
  standing rule; it is the epic's `ACTIVATE-IT-IN-AN-IT` clause and has already recurred once.
- **`2026-08-02-15-001`** — a RestAssured IT suite **cannot** verify browser-policy behaviour
  (`SameSite`, `Secure`, `__Host-` prefixes, cookie partitioning, CORS preflight, redirect-vs-form-post
  navigation context). Only a real browser can, so the demo-client Playwright suite is the **only**
  gate covering that class. Verified: `QueryResponseModeAuthorizationRequestBuilder` ships
  `response_mode=query` with the reasoning in its javadoc. Destination:
  `.claude/skills/run-integration-tests/` and the demo-client docs.
- **`2026-08-02-15-003`** — **re-scoped per D1(b).** Land the mechanism and the documented CI/local
  split; do **not** land "never bind".
- **`2026-08-02-15-004`** — **ALREADY LANDED. Disposition: already-covered.** Compose profiles can
  only ADD services; trim with explicit selection plus `--no-deps`. This is already in repository
  source at `demo-client/scripts/start-dev-environment.sh` — the reasoning at :31–37, the invocation
  at :221, and :218 already refers to *"the standing prohibition"*. **Do not re-land it.** The most
  this warrants is a cross-reference from `.claude/skills/run-integration-tests/SKILL.md` if one is
  missing — check first, and if the skill already covers it, record already-covered and move on.

### D7 — `2026-08-05-10-001` — DISCARD WITH EVIDENCE, and the discard is now settled

The original spec asked whether the refspec was already fixed, and said this lands as a *discard with
evidence* if so. **The audit settled it: the construct the lesson prescribes a fix for no longer
exists.** The release skill's tag check now uses `git fetch --tags --force` plus
`git ls-remote --exit-code --tags`, and the only surviving `merge-base` in the skill
(`SKILL.md`:340) is an ancestry assertion, not a resolution against a prunable remote-tracking ref.
There is nothing to apply the fully-qualified-refspec fix to.

**Do not land the title.** It still reads *"run the release pre-dispatch guard checks serially"*, and
that prescription was refuted by the lesson's own `CORRECTED 2026-08-05` banner: serial execution
merely *hid* the problem. A lesson whose diagnosis was refuted after it was written is worse than no
lesson, because it is consulted with confidence.

**Two halves are worth keeping and are the whole of what lands:**

1. **An ERROR from a guard is a statement about the guard's ability to evaluate, not about its
   subject.** Before treating it as a finding about the thing being checked, establish that the check
   actually ran — and before treating a *green* as evidence, establish the same. A check that
   silently could not run is the more dangerous half.
2. **A plausible root cause that explains the observation is not the root cause.** The concurrency
   story fit every fact available and was still wrong; it survived because nobody re-derived it
   against the implementing source until a release forced the question.

Destination: `doc/development/**`, beside D5's gate material. Record the discard and its evidence so
nobody re-opens it.

## Split-Guard Evaluation — 2026-08-08

**Seven deliverables — at the guard, evaluated, PROCEEDING UNSPLIT, rationale recorded as the guard
requires.** The count is misleading as a size signal: **D1 is triage** (it decides destinations and
settles three conflicts, and produces no repository text of its own), and **D7 is a discard** (its
output is two sentences plus a recorded verdict). The substantive landing work is D2–D6, which is
five, and three of those five (D4, D5, D6) are a handful of paragraphs into documents that already
exist.

The parts also do not ship independently in the way a split assumes: **D1's conflict resolutions are
inputs to D2, D5 and D6**, so any split that separates D1 from them ships a deliverable whose
governing decision lives in another PR.

**If it must split later, the line is D1+D2+D3 (the build/gate-discipline half, which is where the
only enforceable check lives) | D4+D5+D6+D7 (the documentation half).** Do not split between D1 and
D2 — D2 is the cluster D1(a) and D1(c) exist to settle.

## Appendix — corpus defects this plan cannot fix, recorded so they are not lost

**These live in `api-sheriff-roadmap`'s archived tree, which is a closed epic's frozen audit record
and outside every write boundary — the orchestrator's included.** They are recorded here because
this is the document whose reader will consult those lessons.

- **Three lessons have no body.** `2026-08-07-18-001`, `-002` and `-003` are a title plus a
  consumption banner and nothing else (638–732 bytes each), while each banner asserts *"Body below is
  verbatim from the corpus."* **That sentence is false for all three.** All three are dispositioned
  `discard` and all three discards are correct — `18-003`'s was verified: `build.map` now carries
  `.github/workflows/*`, `Dockerfile*`, `docker-compose*.yml`, `*.css` and `*.ts`. So the operational
  cost is nil and **no work is owed here.** The archival cost is real: `18-001` (*a narrowing
  enforced on one trigger path and merely assumed on the other is not a narrowing — and the guard
  must sit upstream of the irreversible publish*) and `18-002` (*retiring a guard and deleting the
  ref it inspects is ONE ordered change: remove every carrier of the check first, publish the
  replacement anchor, then delete*) are durable release-lane rules whose **reasoning is now
  unrecoverable**. If either is ever wanted, it must be re-derived from PLAN-50/PLAN-51's diffs, not
  from the corpus.
- **Every archived lesson still reads `status=active`**, including the four discards. Harmless while
  archived; misleading if the corpus is ever re-read as a live store.

## Expected Surface

- `CLAUDE.md` — D1(c)'s general rule and D2's class statement, at most a few lines each
- `doc/development/**` — D4, D5, D7
- `doc/development/sonar-quality-gate.adoc` — D5
- `.claude/skills/run-integration-tests/**` — D6
- `demo-client/doc/**` — D6's browser-policy rule, if the Playwright docs are the narrower reader
- tests / gates wherever D2 concludes a lesson is enforceable rather than merely statable — **the
  post-gate `git status --porcelain` assertion is the named candidate**
- OBSERVED (absence, asserted): **no `api-sheriff/src/main/java/**` change.** This plan lands rules
  and, at most, a gate. A production edit is a finding to report.
- OBSERVED (absence, enforced): **no file under `.plan/local/archived-orchestrators/**` is edited.**
  See the Appendix.

## Dependencies and Sequencing

- No dependency on other 0.2.0 plans; surface-disjoint from V02-13, V02-14, V02-15 and V02-16.
- **Overlaps `PLAN-V02-03` on `doc/development/**`** — sequence deliberately, see § Re-Grounded.

## Issue Closure

No GitHub issue tracks these; they came from the corpus. Nothing to close.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-17-lessons-into-source.md" plan_id=plan-v02-17-lessons-into-source
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates and
edits NO file under `.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message —
the orchestrator owns every other ledger write — and reports its outcome through its PR and its
inbox message.
