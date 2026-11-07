# PLAN-V02-19: Orphaned Guard Hygiene — ADR Ordinal Collision, Module-List Contract, S3398

epic: api-sheriff-0-2-0
workstream: WS-01

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Lives at `plans/PLAN-V02-19-orphaned-guard-hygiene.md` and is queued as one row file,
> `queue/PLAN-V02-19.json`. The orchestrator EMITS the command below; it never launches the plan
> inline. This spec is SELF-SUFFICIENT: the emitted command is a one-line pointer and carries no
> brief.
>
> **Source: the 2026-09-24 inbox drain.** Three unowned items handed over by the closing sibling
> epic `deployment-configurability` (inbox messages `-001`, `-003`, `-020`), each re-verified against
> `origin/main` at `05f6ee3` before staging. None had a home in this epic's corpus: no staged spec
> declares `UpstreamAssetSource.java`, and none declares the module-list or ADR-ordinal guards. They
> are batched into one plan because each is small and all three are the same kind of gap: a guard
> that is missing or a record that nobody owns.

## Objective

Repair the duplicate ADR ordinal now on `main`, and add the contract test whose absence let it land
unnoticed. Bind the `AGENTS.md` / `CLAUDE.md` reactor module lists to the root `pom.xml`
`<modules>` with a contract test. Resolve the standing Sonar `java:S3398` finding on
`UpstreamAssetSource.defaultSslContext()`. This is test and structure work with no production
behaviour change: deliverable 4 moves a method without changing what it does.

## Deliverables

1. **Resolve the duplicate ADR ordinal `0053`.** Two records share it on `main`:
   `0053-Portal_templates_render_on_a_standalone_Qute_engine_and_escape-bypass_constructs_are_refused_at_boot.adoc`
   (renumbered 0050→0053 by PR #348 to clear an earlier 0050 collision) and
   `0053-A_header_matchers_name_is_normalised_once_at_the_route-compile_seam_and_its_fields_compose_with_AND.adoc`
   (authored by PR #346, which merged **after** #348). Renumber the **header-matcher** record: it is
   the later claimant, and it carries fewer inbound references. The new ordinal is **the next free
   ordinal at implementation time**. Re-derive it from `doc/adr/` on the branch; do not assume a
   number from this spec. Repair every inbound `ADR-0053` / `link:…0053-A_header…` reference that
   means the header-matcher record (at least the one in `0054-…adoc`), and leave references meaning
   the portal record untouched. Released `0.2.3` shipped with the duplicate; that is history and is
   not rewritten.
2. **ADR ordinal-uniqueness contract test.** A JUnit test beside `DocumentedSetsContractTest` that
   lists `doc/adr/*.adoc`, extracts the four-digit ordinal prefix, and asserts there are no
   duplicates. It must report the offending ordinal and both filenames. Include a control that
   fails on a duplicate fixture, so the guard is shown not to be vacuous. This is the durable fix:
   the ordinal space has now collided **twice in one week** (0050, then 0053) across concurrently
   merging PRs from two epics. Nothing checks ordinal freshness at merge time, and the merge queue
   rebases no filename.
3. **Reactor module-list contract test.** A test that reads `<modules>` from the root `pom.xml` with
   the JDK XML parser and extracts the module bullets from `AGENTS.md`'s and `CLAUDE.md`'s
   module-list sections. It asserts set equality in both directions, and also a pre-dedup count, so
   a duplicated bullet cannot pass. `DocumentedSetsContractTest`'s own Javadoc states the policy
   this closes: a hardcoded list mirroring a set defined elsewhere is a defect unless it is derived
   from, or bound to, that source. If either document has drifted when the test is added, fix the
   document, never the assertion. `CLAUDE.md` once listed three modules where the reactor had six,
   and CodeRabbit raised this gap on PR #305 (finding `6da2fb`, operator Hold 2026-09-15).
4. **Sonar `java:S3398` on `UpstreamAssetSource.defaultSslContext()`.** The private static method is
   called only from the nested `HttpUpstreamFetcher` constructor, so move it into that nested class.
   Its behaviour is unchanged, and its Javadoc and `IllegalStateException` contract move with it.
   Fix by default per `CLAUDE.md` § Sonar. A `// NOSONAR java:S3398` is acceptable only with a
   stated reason why the move is wrong, and none is known.

## Claim Labels

- OBSERVED: `doc/adr/` on `origin/main` at `05f6ee3` holds exactly one duplicate ordinal, `0053`
  (`git ls-tree --name-only origin/main doc/adr/ | grep -oE '^[0-9]{4}' | sort | uniq -d` over the
  basenames). The earlier `0050` duplicate is gone.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: doc/adr basenames uniq -d returns only 0053
- OBSERVED: PR #348 (`070eda5`) renamed the portal ADR `0050` → `0053` (`R099` in its name-status).
  PR #346 (`f606758`) added the header-matcher `0053` and merged later.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: f6067588 (#346) merged after 070eda54 (#348 rename)
- OBSERVED: inbound references to the portal `0053` appear in `doc/architecture.adoc:148`,
  `doc/configuration.adoc:537`, `doc/security-threat-model.adoc` (1927, 2155, 2156, 2160) and
  `doc/user/portal.adoc:14`. The header-matcher `0053` is referenced from
  `doc/adr/0054-…adoc:134`.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: portal 0053 inbound refs unchanged: architecture:148 configuration:537 threat-model:1927/2155/2156/2160 user/portal:14
- HYPOTHESIS: `0054-…adoc:134` is the ONLY inbound reference to the header-matcher record. Confirm
  or refute with a whole-tree content search for its filename stem and for `ADR-0053` near
  header-matcher context (verify-at-outline). Run a control query per the epic's
  asserted-absence watch.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: whole-tree search: 0054-...adoc:134 is the sole inbound ref to the header-matcher record
- OBSERVED: `UpstreamAssetSource.defaultSslContext()` (`:376`) is `private static` and is called
  only at `:313`, inside the nested `static final class HttpUpstreamFetcher`.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: defaultSslContext private static :376, sole call :313 inside nested HttpUpstreamFetcher (:301)
- OBSERVED: `AGENTS.md` and `CLAUDE.md` each carry a six-bullet module list that matches the
  current reactor. No test binds either list: the only test naming `CLAUDE.md` is
  `BuildGateCoverageContractTest`, which binds a different section.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: pom <modules> 6; AGENTS.md and CLAUDE.md identical 6-bullet lists
- Verify-first clause: re-derive the next free ADR ordinal on the implementation branch. A sibling
  plan may have claimed one since staging, and a stale number would reproduce this spec's own
  defect.
  - verdict: corroborated | checked_at: 05f6ee3ebb5ae32fb75082b660e6abdb7617edb6 | by: api-sheriff-0-2-0/cleanup | rescoped: n/a | evidence: highest ordinal 0054 with 0053 duplicated; next free 0055

## Expected Surface

- OBSERVED: `doc/adr/0053-A_header_matchers_name_is_normalised_once_at_the_route-compile_seam_and_its_fields_compose_with_AND.adoc` — renamed to the next free ordinal
- OBSERVED: `doc/adr/0054-The_boot_validator_judges_header_matchers_exactly_as_the_runtime_evaluates_them_and_a_matcher_set_that_can_never_hold_is_refused.adoc` — inbound link repair
- OBSERVED: `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/config/` — the two new contract tests, beside `DocumentedSetsContractTest`
- OBSERVED: `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/asset/UpstreamAssetSource.java` — the S3398 move
- OBSERVED: `AGENTS.md` — read by the module-list test; edited only if it has drifted
- OBSERVED: `CLAUDE.md` — read by the module-list test; edited only if it has drifted

## Dependencies and Sequencing

- Depends on: none.
- Overlaps with: `PLAN-V02-04` (`doc/adr/**`). **Land this plan first.** V02-04 audits and
  renumbers the whole corpus and must start from a corpus with unique ordinals, and this plan's
  uniqueness test then guards V02-04's own merges. Overlaps with `PLAN-V02-18` (`CLAUDE.md`):
  sequence the two, because V02-18 edits `CLAUDE.md` and this plan's test reads it.
- Adjacent to: every ADR-authoring plan in this epic (V02-01, V02-06, V02-11, V02-12, V02-13). They
  do not overlap this plan's surface, but they gain the uniqueness guard once it lands, so landing
  it early is worth more than its size suggests.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-19-orphaned-guard-hygiene.md" plan_id=plan-v02-19-orphaned-guard-hygiene
```

## Write-Boundary

The plan implementing this spec touches only its own repository source and tests. It creates
and edits NO file under `.plan/orchestrator/` other than its own
`inbox/{sender}-{seq}` message — the orchestrator owns every other ledger write — and reports
its outcome through its PR and its inbox message. The inbox exception's qualifiers and the
sole sanctioned write mechanism are stated in
`persona-plan-orchestrator/standards/orchestration-model.md` § Ledger Write-Boundary.
