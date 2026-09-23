# Settled narrative — deployment-configurability

> Relocated verbatim out of `epic.md` by the `cleanup` verb's compaction stage (Phase B). A section
> lands here only when its subject is **closed** — a shipped plan's residue, a resolved defect, a
> retraction, a do-not-re-derive note — never merely because it is old. Nothing here is deleted; each
> origin in `epic.md` carries a pointer naming its heading. Operator direction 2026-09-11: *"archive all
> handled open defects / watches"*. Every closing reason below was checked at `origin/main` `0515e15`.

## Open Defects — handled (relocated 2026-09-11)

59 entries moved from `epic.md` § Open Defects, in their original order; 31 live entries stayed behind.

### Why each entry is settled

| # | Entry (first line, abridged) | Why it is settled |
|---|---|---|
| O04 | ⛔ **BOTH RELEASES SHIPPED WITH `0.1.1`-STAMPED CONTENT — the defect this epic's own release-readiness | remediated on `main` by #299 (`64fe822`); the Known Limitations audit it left is PLAN-19 D1 |
| O05 | ✅ **RESOLVED 2026-09-11 — PR #290 (`6ee4a55`) FIXED THE GATE RATHER THAN REMOVING IT.** Measured | PR #290 (`6ee4a55`) fixed the alignment gate |
| O06 | ⛔ ~~**THE RELEASE RUNBOOK'S QUARKUS-ALIGNMENT GATE NOW ALWAYS RETURNS "CANNOT DETERMINE", AND THE | struck — superseded by the #290 fix above |
| O07 | ⛔ **`README.adoc`'s Known Limitations is stamped "Verified as still open at the 0.1.1 cut" and | re-stamped by #299; the re-verification is PLAN-19 D1 (re-grounded at `0515e15`) |
| O09 | ✅ **RETIRED 2026-09-11 — PLAN-17 SHIPPED THE FIX (PR #288 -> `6c1b6b6`).** Verified at HEAD | PLAN-17 (#288) shipped the fix |
| O10 | ⚠ ~~**The codec default (4096) sits ABOVE the browser-safe *value* budget (4019).** Shipped | struck — superseded by the PLAN-17 retirement |
| O11 | ✅ **RETIRED 2026-09-11 — PLAN-17 SHIPPED (PR #288).** The plan whose whole subject this was | PLAN-17 (#288) shipped |
| O12 | ⚠ ~~**Cookie-mode REFRESH responses are not covered by the budget assertion** (CodeRabbit's sixth | struck — superseded |
| O13 | ⛔ **CI CAN FAIL CLOSED ON A COVERING RUN THAT CAN NEVER APPEAR — a named, reusable signature | incident resolved by rebase; kept as a diagnostic signature |
| O15 | ✅ **THE "IS `gateway.yaml` THE RIGHT PLACE?" AUDIT IS DONE — and it reduces to ONE mechanical | audit complete; its one live consequence is the `tls.passthrough_sni` entry still in Open Defects |
| O17 | ✅ **`forwarded.trusted_proxies` is NOT the same problem — it is already solved, and the split it | explanatory do-not-re-derive note for the live `passthrough_sni` entry |
| O22 | ✅ **RETIRED 2026-09-11 — PLAN-08 (PR #286 -> `7fce677`) DELETED THEM AND SHIPPED A GUARD.** | PLAN-08 (#286) deleted the dead pairs and shipped a guard |
| O23 | ⚠ ~~**Issue #285 — ten dead `QUARKUS_TLS_DEFAULT_TRUST__STORE_*` pairs in `docker-compose.yml`, | struck — superseded (issue #285) |
| O26 | ✅ **RETIRED 2026-09-10 — `main` IS GREEN.** PLAN-16 (PR #284 → `990aebf`) cleared it. | `main` green since PLAN-16 (#284) |
| O27 | ⛔ ~~**`main` WAS RED, from PLAN-05's own merge — `Demo Client E2E`, persistent since | struck — superseded |
| O28 | ⛔ **The cookie ITs are structurally BLIND to deliverability, and the blindness PREDATES PLAN-05.** | closed by PLAN-16/17: `BffCookieRefreshIT:179` asserts `assertCookiesFitBrowserBudget`; Chromium E2E covers storage |
| O29 | ✅ **RETIRED 2026-09-11 — PLAN-17 (PR #288) bounded it.** `ConfigValidator` refuses a declared | PLAN-17 (#288) bounded `max_cookie_size` |
| O30 | ⚠ ~~**`oidc.session.max_cookie_size` admits a value no browser will keep.** The schema documents a | struck — superseded |
| O31 | ⛔ **`main`'s GREEN BADGE IS A STALE VERDICT AGAINST A MOVING SNAPSHOT — operator observation | no dependency on `main` resolves a SNAPSHOT since #289 (`version.token-sheriff` 0.9.5), so the stale-verdict gap has no current instance |
| O32 | ⛔ **UNCOMMITTED POM CHANGES SIT ON THE MAIN CHECKOUT WHILE PLAN-07 EXECUTES THERE (2026-09-08).** | transient shared-main hazard; PLAN-07 shipped (#283) |
| O33 | ⚠ **`version.quarkus` governance INVERTED and no epic plan has exercised the result.** `pom.xml:95` | changed ground absorbed: PLAN-07 shipped and #290 made the alignment check resolve the inherited `version.quarkus` |
| O35 | ✅ **RETIRED 2026-09-11 — RESOLVED OUT-OF-BAND BY PR #289 (`9692f91`), not by any epic plan.** | #289 moved token-sheriff to released 0.9.5 |
| O36 | ⛔ ~~**`0.9.5-SNAPSHOT` sits on the authentication path of every build cut from `main`, and nothing in | struck — superseded |
| O42 | ⛔ **THE 2026-09-10 READ-BACK WENT STALE THE SAME WEEK, AND IS NOW RE-DISCHARGED AT HEAD | read-back re-discharged at `7fce677`; the re-render rule moved into PLAN-09's Authoring Discipline |
| O43 | ✅ ~~**RETIRED 2026-09-10 — THE SVG READ-BACK IS DISCHARGED. Both themes rendered and inspected.**~~ | struck — superseded |
| O44 | ⚠ ~~**The topology SVG read-back is owed.**~~ PLAN-03 placed its geometry by coordinate arithmetic with | struck — superseded |
| O46 | ⛔ **`ci pr merge-queue` reports `enqueued: true` without corroborating that the PR entered the | relocated upstream 2026-09-11 to plan-marshall truthful-signals `api-sheriff-deployment-configurability-007` |
| O48 | 🔄 **OWNED AND DECOUPLED 2026-09-06 — PLAN-03 deliverable 6 now moves cui-http by an explicit | PLAN-03 shipped; removal condition MET — `cui-java-bom` 1.7.3 manages `version.cui.http` 3.0 and the local override is gone |
| O49 | 🔄 ~~**OWNED 2026-09-06 — folded into PLAN-03 as deliverable 6 by operator decision ("API updates are | struck — superseded |
| O50 | ⛔ ~~**cuioss-parent-pom 1.6.3 WILL BREAK THIS REPO, THE BREAK IS PRE-DIAGNOSED, AND THE FIX CANNOT BE | struck — superseded |
| O51 | ✅ **RETIRED 2026-09-06 — PLAN-13 MERGED as `3fc4c83` (squash, via the merge queue).** The stall | PLAN-13 merged (`3fc4c83`) |
| O52 | ⛔ ~~**PLAN-13 WAS GREEN, MERGEABLE, AND NOT MERGING — and it is the sole blocker on FOUR staged plans | struck — superseded |
| O56 | ✅ **RESOLVED 2026-09-04 — PLAN-13 ABSORBED THE HAZARD CORRECTLY; retired.** `git merge-base | PLAN-13 absorbed the rebase hazard |
| O57 | ⛔ ~~**PLAN-13 WILL GO RED ON REBASE — AND IT IS NOW IMMINENT, NOT HYPOTHETICAL (updated | struck — superseded |
| O58 | ⛔ **PLAN-06 reintroduced two bare `listen(0)` calls on `main` | owned and closed by PLAN-13 (all ephemeral binds on `LoopbackHost`, ArchUnit guard) |
| O59 | ⛔ **The compose sample's DEFAULT path does not boot until 0.2.0 ships.** PLAN-06 added the | 0.2.0/0.2.1 shipped and `.env:15` pins 0.2.1 (#299) |
| O60 | ✅ **RETIRED 2026-09-07 — `required_bots` WAS NEVER BROKEN, AND THIS ORCHESTRATOR WAS WRONG TO SAY | retraction — `required_bots` was never broken |
| O61 | ⛔ ~~**`required_bots` carries a broken token in PROJECT config and the fix is the operator's call.**~~ | struck — superseded by the retraction |
| O64 | ✅ **SOLVED 2026-09-11 — THE MISSING READ IS ONE COMMAND, AND IT WORKS TODAY.** | solved: `gh run list --commit` reads the main-branch runs |
| O65 | ⛔ ~~**RECURRENCE 2026-09-11, and now MEASURED.**~~ Corroborating PLAN-08 needed the main-branch run | struck — superseded |
| O66 | ⛔ **The epic cannot discharge its own post-merge verification obligation through the sanctioned | solved by the entry above; the upstream missing verb is a plan-marshall item |
| O69 | ⚠ **The two new `ManagementRootPathLabelIT` legs are compile-verified only.** CI is their first real | self-resolved — the legs have run in every integration run since PLAN-02 |
| O70 | ⛔ **The 30s-hang flake class is OPEN — NARROWED 2026-09-02 by PLAN-11 (PR #243 → `5948962`), not | narrowed by PLAN-11, then closed by PLAN-13's measured mechanism; residual sites are PLAN-23 D3 |
| O71 | ⛔ ~~**The 30s-hang flake class in the api-sheriff edge/tls suite is OPEN — the plan that attacked it | struck — superseded |
| O72 | ✅ **RETIRED 2026-09-02 — the follow-up brief durability risk is gone.** The investigation it carried | retired |
| O73 | ⚠ ~~**The follow-up brief is not durable.**~~ `.plan/temp/macos-loopback-hang-investigation.md` is the | struck — superseded |
| O74 | ✅ **RESOLVED 2026-08-31 by PLAN-01's landing — the text below is the ORIGINAL finding, kept for the | PLAN-01 shipped the HEALTHCHECK |
| O75 | ~~**The distroless main image has no `HEALTHCHECK`.**~~ `api-sheriff/src/main/docker/Dockerfile.native` | struck original finding |
| O76 | **Hostname verification is never configured on the upstream dial.** `DispatchStage:244` and | closed by PLAN-03 (#268) |
| O77 | **No test ever forces a BFF token refresh.** `BffSessionMediationIT`'s own comment concedes it: | closed by PLAN-05 (#282) |
| O78 | ✅ **RETIRED 2026-09-10 by the coverage audit — IT LANDED, AND THE ORDINAL MOVED.** Verified by | ADR landed |
| O79 | ⚠ ~~**ADR-0038 is drafted and rescued, but not landed.**~~ It records PLAN-01's pre-boot probe mechanism | struck — superseded |
| O80 | **The landing payload carried no `landing-facts` block.** `inbox landing-check` on | PLAN-01's landing, drained and reconciled from prose at the time |
| O84 | ✅ **RESOLVED 2026-09-03 by PLAN-06 (PR #254 → `6ba8879`) — retired.** `ConfigLoader` now carries a | PLAN-06 (#254) |
| O85 | ~~**The proxy allow-list is only half env-configurable.**~~ `forwarded.trusted_proxies` — the mandatory | struck — superseded |
| O86 | ✅ **RESOLVED 2026-09-03 by PLAN-06 (PR #254 → `6ba8879`) — retired.** The forwarded block is now | PLAN-06 (#254) |
| O87 | ~~**Nothing in the repository exercises the forwarded block.**~~ None of the six shipped `gateway.yaml` | struck — superseded |
| O88 | ✅ **RESOLVED 2026-09-03 by PLAN-02 (PR #248 → `b200bed`) — retired.** All three keys are now | PLAN-02 (#248) |
| O89 | ~~**No context-path configuration exists.**~~ Neither `quarkus.http.root-path` nor | struck — superseded |

### The entries, verbatim

- ⛔ **BOTH RELEASES SHIPPED WITH `0.1.1`-STAMPED CONTENT — the defect this epic's own release-readiness
  plan exists to close, shipped twice while that plan sat staged (2026-09-11).** `0.2.0` (PR #292) and
  `0.2.1` (PR #294) are cut and tagged. Verified at `origin/main` `428bbec`: `README.adoc:52` still
  reads *"0.1.1 is an ALPHA release"* and `README.adoc:146` still reads *"Verified as still open at
  the 0.1.1 cut"*. ⚠ **And three stamps were stale since `0.1.0`** — `doc/fapi_status.adoc:8`,
  `doc/fapi_next_steps.adoc:7`, `doc/features-analysis.adoc:220` — i.e. through **two** releases, with
  the repo's own audit already carrying it as finding `DOC-8`.
  ✅ **REMEDIATION IS OPEN, NOT MISSING: PR #299** (`chore/post-release-0.2.1`) corrects all of it —
  the three ALPHA callouts, the limitations stamp, the `.env` pin, the compose VERSION-SKEW block, the
  `docker pull` line, `build-parent/example/pom.xml`, and the three stale FAPI/features stamps.
  ⛔ **The timing is the lesson, not the content.** The Step 10 enumerator that finds these landed in
  PR #291 — **after** the `0.2.0` cut. A guard authored after the act it guards cannot catch that act.
  ✅ **The guard is nonetheless PROVEN**: pass 3 (stamp phrases, keyed on no version) is what surfaced
  the 0.1.0-era FAPI stamps that a `$PREV_VERSION` grep structurally cannot see, and #299 carries
  exactly those three files. **It worked on its first real use, one cut late.**
  ⚠ **#299 re-stamps; it does not re-run the verification `README.adoc:146` claims.** That audit is
  still owed and is PLAN-19 deliverable 1's remaining work.

- ✅ **RESOLVED 2026-09-11 — PR #290 (`6ee4a55`) FIXED THE GATE RATHER THAN REMOVING IT.** Measured
  before and after against the documented invocation (`--repo . --check-resolved`): **exit 2 before,
  exit 0 after**, reporting Quarkus `3.39.2` with every `io.smallrye.config` artifact at `3.17.2`,
  unsplit. The release path is unblocked.
  ⛔ **The proposal on the table was to DELETE the check** — the stack now comes from
  `cui-quarkus-parent`, so it looked obsolete. **Verification refuted that**, and the refutation is
  worth keeping: inheriting one `version.quarkus` made the *plugin-versus-platform* drift
  inexpressible, which is a DIFFERENT risk from the one this gate measures. `token-sheriff-bom`
  carries its own `version.quarkus`, and `pom.xml` already records that the two resolving equal is
  *"a coincidence of the moment, not a guarantee."*
  ✅ **TokenSheriff had hit the identical failure on its 0.9.5 cut and reached the opposite
  conclusion from deletion** — its commit `9d01a17f` states it outright: *"a gate that cannot run is
  the one that gets waved through — which is exactly the smallrye/Quarkus outage it exists to
  prevent."* That fix was ported here rather than re-derived.
  ⚠ **A residual obligation remains and is NOT discharged by this gate**: re-measure the
  `token-sheriff-bom` paragraph in `pom.xml` on every `${version.token-sheriff}` bump. That is
  dependency-bump-time work, now stated in the runbook. Original entry:

- ⛔ ~~**THE RELEASE RUNBOOK'S QUARKUS-ALIGNMENT GATE NOW ALWAYS RETURNS "CANNOT DETERMINE", AND THE
  RUNBOOK DEFINES THAT AS A STOP (2026-09-11, measured).**~~ Ran
  `.claude/skills/release/check-quarkus-alignment.py` at HEAD `7fce677`: **real exit code 2**, output
  *"CANNOT DETERMINE: property version.quarkus not declared anywhere … Treat this as blocking - an
  unresolvable check is not a pass."* The runbook's own table maps exit `2` to *"could not determine
  — also a stop. An unresolvable check is never a pass."*
  ✅ **The cause is documented in the tree and is not a regression**: `pom.xml:71` states
  *"version.quarkus is INHERITED from de.cuioss:cui-quarkus-parent - never declared"*. The script
  searches for a LOCAL declaration, so it cannot resolve an inherited property.
  ⛔ **This is the existing `version.quarkus` governance-inversion defect, now with a measured
  consequence**: that entry said the governance inverted and *"no epic plan has exercised the
  result."* Cutting a release exercises it, and the gate fails closed. **It blocks Path A and Path B
  alike**, because Step 2 precedes both.
  ⚠ **It is a TOOLING blocker, not a product one** — the fix is to resolve the property from the
  effective POM (or read it off `cui-quarkus-parent`) rather than from a local `<properties>` scan.
  Unowned; no staged plan declares `.claude/skills/release/`. — source: direct run at HEAD `7fce677`,
  exit code captured without a pipe.

- ⛔ **`README.adoc`'s Known Limitations is stamped "Verified as still open at the 0.1.1 cut" and
  carries at least two entries that are now FALSE (2026-09-11).** The SNAPSHOT-on-the-auth-path item
  was resolved by PR #289, and *"cookie mode and refresh do not work together"* was superseded by
  PLAN-17 (2700 bytes against 4019, 32.8 % spare, Chromium-confirmed). ⛔ **The section is cited by
  `doc/README.adoc:24` and `doc/user/README.adoc:25` as "the verified limitations at this cut", so it
  is a claim of verification that a 0.2.0 cut would carry forward untrue.** Owned by PLAN-19
  deliverable 1, which is staged and runs last. ⚠ **A 0.2.0 release before PLAN-19 ships a stale
  verification claim** — that is a release-sequencing decision, not a defect in the plan.

- ✅ **RETIRED 2026-09-11 — PLAN-17 SHIPPED THE FIX (PR #288 -> `6c1b6b6`).** Verified at HEAD
  `7fce677`: `SealedSessionCookieCodec` now carries `COOKIE_VALUE_BUDGET_FLOOR`, `_CEILING` and
  `DEFAULT_COOKIE_VALUE_BUDGET`, and `ConfigValidator` bounds the declared value against them. The
  commit subject settles the target explicitly: *"settle cookie-mode refresh viability at 4019"*.
  Original text follows.

- ⚠ ~~**The codec default (4096) sits ABOVE the browser-safe *value* budget (4019).** Shipped
  documented rather than hidden. The gap is the `Set-Cookie` header's own overhead — name, `__Host-`
  prefix, `Secure`, `HttpOnly`, `SameSite`, `Path`, `Max-Age`. Needs `gateway.schema.json`. Unowned.
  ⚠ Note this vindicates a hypothesis PLAN-17 already carries: *"the browser limit applies to the
  whole `Set-Cookie` line rather than the value alone… if true, the usable budget is below 4096 and
  `DEFAULT_COOKIE_VALUE_BUDGET = 4096` is itself optimistic."* **It was true.**

- ✅ **RETIRED 2026-09-11 — PLAN-17 SHIPPED (PR #288).** The plan whose whole subject this was
  landed a compression stage (`Deflater` at `SealedSessionCookieCodec.java:515`, before the seal, at
  the corrected pipeline order) plus a **browser-level** control that no deliverable asked for:
  `demo-client/tests/05-cookie-size-budget.spec.js` (+231). Every prior control in this lane measured
  the emitted header server-side; this is the first that measures what the argument was about.
  Original text follows.

- ⚠ ~~**Cookie-mode REFRESH responses are not covered by the budget assertion** (CodeRabbit's sixth
  finding) — a real gap needing its own integration instance. ✅ Correctly **replied to on the thread
  rather than closed**. ⛔ **It belongs to PLAN-17**, whose whole subject is making cookie mode work
  *with* refresh — a budget assertion that skips the refresh path skips exactly the case PLAN-17 must
  prove. Recorded here and carried into that spec.

- ⛔ **CI CAN FAIL CLOSED ON A COVERING RUN THAT CAN NEVER APPEAR — a named, reusable signature
  (2026-09-10).** PLAN-07 landing as #283 made PLAN-16's PR **conflicted**, and **GitHub silently
  refuses to run `pull_request` workflows on a conflicted PR**. So the barrier waited for a run that
  could not exist. **Rebasing fixed it instantly.**
  ✅ **The diagnosis is cheap and worth knowing**: *CI stuck with **no run at all**, and the PR shows
  conflicted → rebase, do not investigate.* ⚠ Distinguish it from the `ci pr merge-queue` defect
  already recorded — that one reports `enqueued: true` falsely; this one reports nothing at all
  because nothing ran. Unowned. — source: PLAN-16 landing paste.

- ✅ **THE "IS `gateway.yaml` THE RIGHT PLACE?" AUDIT IS DONE — and it reduces to ONE mechanical
  question (2026-09-10, operator-requested over all keys).** The whole schema was walked at HEAD
  `a30fe6f`: **30 string, 17 array, 16 boolean, 14 integer and 26 object nodes.**

  ⛔ **The question is not per-key taste. It is: "can this value come from the environment?" — and
  that reduces to "is it a map?"**, because `ConfigLoader` has substituted **scalars** since before
  PLAN-06 and **arrays** since PLAN-06 (#254). Everything else is already reachable.

  **Exactly TWO map-shaped keys exist in the entire schema:**

  | Key | Shape | Deployment-varying? |
  |---|---|---|
  | `tls.passthrough_sni` | `map<string,string>` | ⛔ **YES** — SNI hostname → backend alias is per-environment topology, and the schema itself calls the value a *"topology alias"* |
  | `asset_defaults.content_types` | `map<string,string>` | ✅ **No** — extension → MIME type is static policy, identical in every deployment |

  ⛔ **So the gap is ONE key, not a class.** `passthrough_sni` is the only key in the gateway that is
  both map-shaped and deployment-varying.

  ✅ **The three keys raised by name are all already answered, and none needs to move:**
  - `oidc.final_redirect` — `string`; scalars have been `${VAR}`-substitutable throughout
  - `oidc.session.csrf.trusted_origins` — `array<string>`; supplyable as a whole list since PLAN-06,
    exactly like `forwarded.trusted_proxies`
  - `forwarded.trusted_proxies` — `array<string>`; solved by PLAN-06

  ✅ **The split that has emerged in practice is the right one and should be stated as the rule**:
  **the DECLARATION lives in `gateway.yaml`, where the schema and `ConfigValidator` enforce its shape
  and constraints; the VALUES come from the deployment.** That satisfies ADR-0025's classification
  without moving any key out of the document, and it is why 46 of 47 leaf keys need no change.
  ⚠ **The one-line consequence**: give `coerce` an `object`/map case, as PLAN-06 gave it an `array`
  case. Unowned. — source: full schema walk at HEAD `a30fe6f`.

- ✅ **`forwarded.trusted_proxies` is NOT the same problem — it is already solved, and the split it
  uses is the right one.** PLAN-06 (#254) made one environment variable able to supply the whole CIDR
  list. The **shape** is policy and belongs in `gateway.yaml` where the schema and `ConfigValidator`
  enforce it; the **values** are topology and come from the deployment. ⛔ **Recorded as a defect entry
  deliberately so it is not re-opened**: the question "should this move out of gateway.yaml" has an
  answer, and the answer is that it already effectively has.

- ✅ **RETIRED 2026-09-11 — PLAN-08 (PR #286 -> `7fce677`) DELETED THEM AND SHIPPED A GUARD.**
  Verified at HEAD: `grep -c TRUST__STORE integration-tests/docker-compose.yml` returns **0**, and
  `EnvironmentKeySpellingGuardTest.java` (+406) now refuses the spelling.
  ⛔ **THE GUARD CAUGHT THE ELEVENTH INSTANCE THE DAY IT SHIPPED** — a sibling PR added the same
  broken spelling to a new service, copied from a neighbour; the merge queue went red and the guard
  named it before it landed. **This is the strongest evidence this epic has produced for its own
  central thesis**: the stated-rule-without-a-mechanism class is not closed by attention, only by a
  mechanism. Seven instances were closed by argument; this one was closed by construction and then
  demonstrated under real conditions against an author who was not looking for it. Original text follows.

- ⚠ ~~**Issue #285 — ten dead `QUARKUS_TLS_DEFAULT_TRUST__STORE_*` pairs in `docker-compose.yml`,
  pre-existing on `main`.** ✅ **Correctly declined inside PLAN-07 and routed out** on four independent
  grounds rather than fixed in an unrelated PR — the right call, recorded so it is not read as an
  oversight. Unowned. — source: PLAN-07 landing paste.

- ✅ **RETIRED 2026-09-10 — `main` IS GREEN.** PLAN-16 (PR #284 → `990aebf`) cleared it.
  `Demo Client E2E` — the only lane that catches this class — came back **success**, and all four
  post-merge runs on `main` passed. ✅ **And it was proven rather than asserted**: with the overlay
  reverted, all nine previously-blind cookie tests go red on the new assertion
  (`__Host-sheriff-session`, 5200 bytes), so the blindness is demonstrated closed, not just fixed.
  Original entry:

- ⛔ ~~**`main` WAS RED, from PLAN-05's own merge — `Demo Client E2E`, persistent since
  `b5369ca` (2026-09-09).** Reported by the plan itself in `…-007.md`, which **supersedes its own
  clean-landing claim** in `…-006.md` on this one point. Green on the five `main` commits before the
  merge, failing on every commit since — `673e3a7` and `481b05f` included — so persistent, not
  transient. `Maven Build`, `Integration Tests` and `Scorecard` stay green; **only the lane that
  drives a real browser fails**, all 7 `[session-cookie]` tests, while every `[session-server]` test
  passes.

  **Mechanism, corroborated first-party.** Seeding the refresh token into the session — the fix the
  plan shipped — grows the cookie-mode sealed session, and in cookie mode *the session IS the
  cookie*. Access + id + refresh seal to **5123 bytes** against RFC 6265's ~4096 guarantee, so
  Chromium drops the `Set-Cookie` **silently**: the SPA stays anonymous and every cookie test fails
  from login onward. ⛔ **The gateway did not refuse the seal**, because the same plan raised
  `max_cookie_size` to `8192` on the cookie overlay to get nine cookie-mode ITs green — converting a
  loud fail-closed 500 (`ApiSheriff-114`) into a silent browser-side drop. Verified at
  `integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml:171-172`.

  **D1 — the fix that returns `main` to green**: set `refresh.enabled: false` and remove the
  `max_cookie_size: 8192` line so the browser-safe 4096 default applies. The plan checked that no
  cookie IT depends on refresh (only a comment at `BffCookieSessionIT:154`), so it costs no coverage.
  ⚠ **Owed, not started.** — source: message `…-007.md`, corroborated against the overlay and the
  schema at HEAD `b5369ca`.

- ⛔ **The cookie ITs are structurally BLIND to deliverability, and the blindness PREDATES PLAN-05.**
  No cookie-mode IT asserts the sealed value's size — zero hits for `length` / `4096` / `budget`
  across `BffCookieSessionIT` and `BffCookieStatelessnessIT` — and they drive **RestAssured, not a
  browser**, which stores and replays a 5 KB cookie without complaint. The only size test is the
  unit-level `SealedSessionCookieCodecTest`, which pins the codec against *whatever budget is
  configured*, never against the browser's real limit. ⛔ **So nine ITs prove sealing,
  tamper-rejection, no-readable-token, statelessness and peer-unsealing — all real — on a
  configuration no browser can carry.** ⚠ **The accident that hid it**: the cookie previously sat
  under 4096 only because the refresh token was not being stored — *the very bug PLAN-05 fixed was
  what kept the suite accidentally honest*. ⛔ **The only gate that catches this runs post-merge on
  `main`, never on the PR.** — source: message `…-007.md`.

- ✅ **RETIRED 2026-09-11 — PLAN-17 (PR #288) bounded it.** `ConfigValidator` refuses a declared
  value outside `COOKIE_VALUE_BUDGET_FLOOR`..`_CEILING` and warns through
  `COOKIE_BUDGET_EXCEEDS_BROWSER_GUARANTEE` on the **effective** budget, computed from the resolved
  cookie name and TTL. ⚠ **The schema's own declared range may still be wider than the validator
  accepts** — PLAN-21 carries that as its remaining residual, not this entry. Original text follows.

- ⚠ ~~**`oidc.session.max_cookie_size` admits a value no browser will keep.** The schema documents a
  valid range of **40..8192** (`gateway.schema.json:345-347`), so the ceiling sits *above* RFC 6265's
  ~4096 guarantee — which is what made PLAN-05's `8192` look legitimate at review. Third contributor
  to the regression above, and the one that will let it recur. — source: schema read at HEAD
  `b5369ca`.

- ⛔ **`main`'s GREEN BADGE IS A STALE VERDICT AGAINST A MOVING SNAPSHOT — operator observation
  2026-09-09, corroborated.** `.github/workflows/maven.yml` triggers on `push`, `pull_request`,
  `merge_group` and `workflow_dispatch` — **`grep -c 'schedule:'` returns 0**. With
  `version.token-sheriff` a SNAPSHOT, the green check on `main` is a point-in-time result against
  whatever that snapshot held at the last push, and **is never re-evaluated**. The repository can
  become unbuildable through an upstream change alone: no local commit, no red signal, badge green.

  **Observed instance (2026-09-09)**: `AuthorizationCodeFlow.AuthenticationResult` gained a third
  record component upstream and two test call sites stopped compiling. Zero API-Sheriff commits fell
  between the last green `main` run and the first failure; two PRs with **disjoint, non-Java** file
  sets failed identically, placing the cause outside both branches. ✅ **Already repaired in the
  tree** — the call sites now pass three arguments — but the *gap* is untouched.

  ⛔ **Two tempting explanations are FALSE and are recorded so they are not re-derived**: the
  reporting job is *not* non-gating (`rewrite-report` and `supply-chain-scan` carry no
  `continue-on-error` and no `if:` guard — only the DIRTY VERDICT is non-gating), and it does *not*
  run on pull requests only (it runs on `push` to `main` too). **The gap is staleness, not
  suppression: the signalling works, nothing re-triggers it.**

  ⚠ **The recurring tax is MISATTRIBUTION, not the break** — the first person to feel it is whoever
  opens the next unrelated PR and must disprove that their own change caused it. ⚠ This epic has
  already paid that tax in a different currency (the shared-main uncommitted poms). Direction, not a
  prescribed fix: anything re-evaluating `main` against current snapshots on a cadence closes it —
  and it is worth deciding alongside whether a consumed SNAPSHOT belongs on `main` at all, since
  `pom.xml` already carries a REMOVAL CONDITION naming the dependency as temporary. ⚠ **Not unique to
  this repository** — the same shape applies to any cuioss repo consuming a SNAPSHOT. — source:
  operator observation, corroborated against `maven.yml` triggers, `pom.xml` and the repaired call
  sites at HEAD `b5369ca`.

- ⛔ **UNCOMMITTED POM CHANGES SIT ON THE MAIN CHECKOUT WHILE PLAN-07 EXECUTES THERE (2026-09-08).**
  `git status --porcelain` reports three modified files — `pom.xml`, `api-sheriff/pom.xml`,
  `demo-client/pom.xml` — and `manage-status list` puts PLAN-07 at `2-refine` with
  **`location: current`**, i.e. on that same working tree.

  ⛔ **They are not PLAN-07's.** The diff is a parent bump **`cui-quarkus-parent` 1.7.0 → 1.7.1** plus
  module cleanups adopting parent-supplied `frontend.node.version` / `frontend.npm.version` and
  dropping locally-pinned `node.version` / `npm.version` / `version.frontend-maven-plugin`. PLAN-07 is
  at *refine* — research, no implementation — and its subject is TLS audits, not the Node toolchain.

  **Two consequences, and they differ:**
  1. ⚠ **Any build PLAN-07 runs on main right now resolves against 1.7.1**, a parent no epic plan's
     gate has exercised — one bump beyond the 1.7.0 that itself arrived unexercised.
  2. ⛔ **Any footprint check PLAN-07 derives from `git status` will attribute these three files to
     itself.** That is the concrete form of the shared-main hazard: not code collision, but a plan
     mistaking foreign churn for its own change set.

  ✅ **The exposure ends when PLAN-07's worktree is cut** — `git worktree add` takes a clean checkout
  of the branch, so uncommitted main-tree changes do not follow it. Until then, re-check `git status`
  before trusting any footprint or gate result. — source: `git status --porcelain`, `git diff`, and
  `manage-status list` at HEAD `4863c61`.

- ⚠ **`version.quarkus` governance INVERTED and no epic plan has exercised the result.** `pom.xml:95`
  now records it as **INHERITED from `cui-quarkus-parent`, never declared locally**; the earlier root
  pom called it a *project-owned pin* precisely because the parent chain declared no Quarkus version
  at all. ⛔ **Not a refutation of anything staged** — but it moves the Quarkus platform from a value
  this repository controlled to one a parent supplies, and **PLAN-07 is the plan whose entire subject
  is Quarkus TLS configuration**. Read it as changed ground, not as a defect. — source: `pom.xml:95`
  at HEAD `4863c61`.

- ✅ **RETIRED 2026-09-11 — RESOLVED OUT-OF-BAND BY PR #289 (`9692f91`), not by any epic plan.**
  *"chore(deps): move token-sheriff to released 0.9.5, drop snapshot repository"* — `pom.xml` only,
  +14/-32. ⚠ **This epic did not observe it happen**: it landed on `main` on 2026-09-10 and was found
  by `analyze` on 2026-09-11 only because corroborating PLAN-08 required fetching `main`.
  ⛔ **PLAN-19's deliverable 1 must NOT carry it as a limitation** — it is no longer true, and that
  spec's candidate list is stale in exactly the way its own preamble warns about. Original text follows.

- ⛔ ~~**`0.9.5-SNAPSHOT` sits on the authentication path of every build cut from `main`, and nothing in
  the build refuses it.** Operator was shown the risk twice and chose to proceed; the mitigations are
  real — the snapshot repository sets `<releases><enabled>false</enabled>` and `pom.xml` carries an
  explicit REMOVAL CONDITION at `:68`. ⛔ **The residual is stated plainly and is the part that
  matters: nothing in the build REFUSES a release that still resolves a SNAPSHOT — the condition is
  prose, not a gate.** ⚠ Note the shape: this is the third instance in this epic of *a stated rule
  with no mechanism behind it* (issue #269's inventory claiming a check; lesson `2026-09-02-22-002`'s
  comment naming its own guard). Filed by the security audit (`651d5b`) and CodeRabbit (`6cd491`,
  Major), dispositioned `accepted` / `taken_into_account`. — source: PLAN-04 landing message,
  corroborated at `pom.xml:62-87`.

- ⛔ **THE 2026-09-10 READ-BACK WENT STALE THE SAME WEEK, AND IS NOW RE-DISCHARGED AT HEAD
  (2026-09-11).** The operator reported the two-theme render as never read back. **Both they and the
  entry below are right**, and the reconciliation is the point: the read-back below was performed
  against a **10-instance** diagram; `6c1b6b6` (PLAN-17) then **redrew the SVG**, which now carries
  **12** instances including `api-sheriff-no-certificate 10453 · 10454 · 19010` and
  `api-sheriff-cookie-refresh 10455 · 19011`. Established with
  `git log -- doc/resources/diagrams/integration-test-topology.svg`.
  ⛔ **A verification verdict outlived the artifact it verified** — the same shape as the stale ADR
  ordinal, the stale line citations, and the *"green badge is a stale verdict against a moving
  snapshot"* defect already recorded here. ⚠ **The document's own rule predicted it**: *"when they
  change, the diagram is stale and gets redrawn rather than annotated."* Nothing re-ran the check
  when it was redrawn.
  ✅ **RE-VERIFIED AT HEAD `7fce677` by the orchestrator, not asserted**: `rsvg-convert -b "#ffffff"`
  and `-b "#0d1117"` at 1200px, **both PNGs rendered and actually viewed**. No element overlap on
  either theme; every label legible; theme handling correct — nothing goes invisible on either
  background. Variant count reads `variant instances (11)` + the primary = 12, matching the source.
  ⚠ **The same cosmetic nit persists and is unchanged**: `mismatched-tls-backend`'s italic subtitle
  *"cert names upstream-mismatch"* reaches its box's right edge on both themes. It touches; it does
  not overflow the canvas.
  ⛔ **The standing gap is not the render, it is that nothing re-runs it.** Two commands and a look,
  with `rsvg-convert` on this machine — and it has now gone stale once. **A redrawn diagram with no
  re-render is the defect class; a guard belongs with whoever next owns that file.** Prior entry:

- ✅ ~~**RETIRED 2026-09-10 — THE SVG READ-BACK IS DISCHARGED. Both themes rendered and inspected.**~~
  `rsvg-convert -b "#ffffff"` and `-b "#0d1117"` at 1200px, per `pm-documents:ref-svg-diagrams`
  Step 4, then both PNGs viewed. **`integration-test-topology.svg` is correct on both themes**: no
  element overlap, all labels legible, theme handling working — nothing goes invisible on either
  background. The variant count is right too (9 variants + the primary = the tenth instance PLAN-05
  added, and `api-sheriff-refresh 10452 · 19009` is present).
  ⚠ **One cosmetic nit, not a defect**: `mismatched-tls-backend`'s italic subtitle *"cert names
  upstream-mismatch"* reaches its box's right edge on both themes. It touches, it does not overflow
  the canvas.
  ⛔ **The WARNING block in the document should now be REMOVED** — it says the render is unverified,
  and it no longer is. That is a one-line documentation edit owed to whoever next touches that file.
  ⛔ **And the reason this sat open is worth keeping**: PLAN-03 recorded it as blocked for want of a
  renderer. **`rsvg-convert` is installed on this machine** — the exact tool the standard's recipe
  names. The check was two commands and a look. Original entry:

- ⚠ ~~**The topology SVG read-back is owed.**~~ PLAN-03 placed its geometry by coordinate arithmetic with
  **no renderer available**, so element overlap and both light/dark themes are unverified. ✅ An
  explicit WARNING block sits in the document until someone renders it — disclosed in place rather
  than claimed, which is the correct handling. Closing it needs a human to open the file in a
  renderer; no plan is owed. — source: PLAN-03 landing paste.

- ⛔ **`ci pr merge-queue` reports `enqueued: true` without corroborating that the PR entered the
  queue.** Its corroboration string attests that the **branch has a queue rule**, not that **this PR
  entered** — precisely the case the corroboration exists to catch, so the check passes exactly when
  it should fail. Cost on PLAN-13: ~30 minutes with the PR sitting open and apparently enqueued.
  ✅ **`ci pr auto-merge` is the working path** and enqueued it correctly. Filed by the plan as lesson
  `2026-09-06-01-001` with a reproduction and a suggested fix (read back `mergeQueue.entries` and
  match the PR number). ⚠ Upstream `plan-marshall:tools-integration-ci` defect; the cost is paid here
  on every merge. — source: PLAN-13 landing paste; the lesson is present in this repo's store,
  verified via `manage-lessons list`.

- 🔄 **OWNED AND DECOUPLED 2026-09-06 — PLAN-03 deliverable 6 now moves cui-http by an explicit
  property override, NOT by the parent bump.** Operator's improvement on the first fold, and it is
  strictly better on three counts verified here: **(a) it is available today** — `cui-http` `3.0` is
  present in `~/.m2/repository/de/cuioss/cui-http/`, while `cui-java-parent` 1.6.3 is still
  unreleased, so the availability gate the first version needed is **gone**; **(b) the seam exists** —
  `cui-java-bom-1.6.2.pom:24` declares `<version.cui.http>2.2</version.cui.http>` and `:73-74` manages
  the artifact through it, so a one-line property override in this project's `<properties>` moves it;
  **(c) it is idiomatic here** — `pom.xml` already carries `version.quarkus`, `version.token-sheriff`
  and `version.json-schema-validator`. Blast radius shrinks to the one artifact whose API changed,
  instead of everything 1.6.3 carries.
  ⛔ **It is an OVERRIDE, not a pin, and the spec records the difference and the REMOVAL CONDITION.**
  The root pom's own comment already draws that line: `version.quarkus` is a pin because the parent
  declares nothing; `version.cui.http` is an override because the parent declares `2.2`. **Delete it
  once the resolved parent manages cui-http at ≥ 3.0** — left in place it silently holds the artifact
  at 3.0 past a future 3.1, which is how a dependency quietly stops tracking.
  ⚠ **The 24 → 26 count remains UNVERIFIED here** — relayed from another repo's failure, never read
  against a 3.0 artifact in this checkout. The spec says read `getRecordComponents()` at 3.0 and use
  what it says. Original fold and diagnosis:

- 🔄 ~~**OWNED 2026-09-06 — folded into PLAN-03 as deliverable 6 by operator decision ("API updates are
  part of it"). The diagnosis below stands as the brief; it is no longer unowned.** PLAN-03 was
  already `launched` and not yet started, so re-scoping it was permitted (the running-row exclusion
  binds `running`, not `launched`). The fold added `pom.xml`, `SecurityConfigurations.java` and
  `GatewayEdgeRouteTest.java` to its Expected Surface **in the same act**, taking it from 9 to 12
  resolved entries — verified through `corpus surfaces`, not assumed. ⚠ **The first attempt at that
  declaration was silently invisible**: the three entries were authored under a `###` subheading and
  the parser, which stops at the first one, still reported 9. Moving them into the main list fixed it.
  ⛔ **A fifth instance of this epic's recurring defect, and the first caused by AUTHORING POSITION
  rather than wrong content** — a correct declaration in the wrong place gates nothing.
  ⚠ **Deliverable 6 is GATED ON AVAILABILITY: 1.6.3 does not exist yet.** `~/.m2` tops out at `1.6.2`
  and `/Users/oliver/git/cuioss-parent-pom` is `1.6-SNAPSHOT` with `6907211` unreleased on main. If it
  is still unresolvable at outline, PLAN-03 drops deliverable 6 and ships 1-5 — for availability, not
  for scope. ⚠ **The component count 26 is SECOND-HAND** — relayed from another repo's build failure,
  never read here against a 3.0 artifact. It is the number to verify, not to code against.
  ✅ **Consequence for PLAN-04**: PLAN-03 now settles the version its claim 11 is pinned to. Original
  diagnosis:

- ⛔ ~~**cuioss-parent-pom 1.6.3 WILL BREAK THIS REPO, THE BREAK IS PRE-DIAGNOSED, AND THE FIX CANNOT BE
  APPLIED IN ADVANCE (cross-repo data point, 2026-09-05).** Corroborated first-party at HEAD
  `558a38b`, every element of it:

  | Claim | Verified |
  |---|---|
  | The tripwire exists | `GatewayEdgeRouteTest.java:513` `tripwiresOnSecurityConfigurationComponentDrift` |
  | It hard-codes 24 | `:515` `int copiedByBuilderSeededFrom = 24;` |
  | It compares against the live record | `:518` `SecurityConfiguration.class.getRecordComponents().length` |
  | The copy really does copy 24 | `SecurityConfigurations.java:55-79`, 24 `.component(preset.component())` calls |
  | This repo resolves cui-http **2.2** today | `dependency:tree` → `de.cuioss:cui-http:jar:2.2:compile` and `:generators:2.2:test` |
  | Parent is **1.6.2** | `pom.xml:7` |

  So the other repo watched **this repository's own tripwire fire**, and it is doing precisely the job
  it was written for: cui-http 2.2 → 3.0 grows `SecurityConfiguration` from 24 components to 26, and
  the guard refuses the silent posture regression that a dropped component would cause (an omitted
  component reverts to the `defaults()` policy the builder starts from — a real security downgrade
  with no other failing test).

  ⛔ **THE FIX MUST LAND ATOMICALLY WITH THE BUMP — pre-fixing is IMPOSSIBLE, not merely inadvisable.**
  The assertion is an equality against the live record. Raising the constant to 26 while cui-http is
  still 2.2 turns the test RED IMMEDIATELY. There is therefore no "fix it ahead of time" option, and
  any plan staged to do so would break `main` on its own merge. The three edits below belong in the
  **same commit as the version bump**:

  1. `SecurityConfigurations.java:55-79` — add the two new `.component(preset.component())` calls
  2. `GatewayEdgeRouteTest.java:515` — `24` → `26`
  3. `SecurityConfigurations.java:47` — the Javadoc says *"leaves the other twenty-three"*; it becomes
     twenty-five

  ⚠ **Two live collisions if this is ever staged as work.** `SecurityConfigurations.java` sits inside
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/config/`, which **PLAN-03 declares as a
  directory**; and `GatewayEdgeRouteTest.java` is in **PLAN-13's** surface, currently open and green
  at PR #255. Neither is a reason to defer, but a bump PR touching those files while either is in
  flight will rebase over them.

  ✅ **Expected arrival: an automated PR.** Nine dependency/steward PRs landed here in one window on
  2026-09-04, so 1.6.3 will most likely arrive the same way and go **red on this one test**. The value
  of this entry is that whoever sees that red already has the diagnosis and the three-line remedy.
  — source: file reads plus `dependency:tree` at HEAD `558a38b`; the originating observation is a
  cross-repo paste and was treated as a lead, not a fact.

- ✅ **RETIRED 2026-09-06 — PLAN-13 MERGED as `3fc4c83` (squash, via the merge queue).** The stall
  had a named cause after all: `ci pr merge-queue` returned `enqueued: true` while the queue stayed
  empty, so the PR sat open ~30 minutes; `ci pr auto-merge` enqueued it correctly. Tracked as its own
  defect below. Original entry:

- ⛔ ~~**PLAN-13 WAS GREEN, MERGEABLE, AND NOT MERGING — and it is the sole blocker on FOUR staged plans
  (2026-09-05).** PR **#255** is `state: open`, `mergeable: mergeable`, `merge_state: clean`,
  `is_draft: false`, with **all 29 checks `SUCCESS`**. Its plan record has sat at `6-finalize` since
  `2026-09-04T23:42Z`, and the branch is **35 commits ahead of main** and not yet rebased onto
  `558a38b`. ⛔ **The cost of the stall is not PLAN-13's own** — it is the four plans behind it:
  PLAN-03 and PLAN-07/PLAN-08 are blocked only by gate blind spots *against PLAN-13*, and PLAN-04 and
  PLAN-09 sit behind those two in hard-dependency chains. **One merge unblocks the entire remaining
  queue.** ⚠ `review_decision: none` — worth checking whether a required-bot participation barrier is
  what is holding it, since that is the failure this epic has now paid for three landings in a row.
  — source: `ci pr view --pr-number 255`, `ci checks status --pr-number 255`, plan `status.json` read
  and `git rev-list --count` at HEAD `558a38b`.

- ✅ **RESOLVED 2026-09-04 — PLAN-13 ABSORBED THE HAZARD CORRECTLY; retired.** `git merge-base
  --is-ancestor 6ba8879 feature/loopback-stall-fix-and-instrumentation` now succeeds — PLAN-13 has
  rebased past PLAN-06's merge — and its branch copy of `GatewayEdgeRouteTest.java` carries **zero
  bare `listen(0)` calls and six loopback-bound ones**, so it converted PLAN-06's two new sites along
  with its own four. The guard will pass. ⚠ **The prediction was right and the outcome was fine** —
  recorded that way deliberately: the hazard was real, was caught before it fired, and the plan
  handled it without being told. Original entry:

- ⛔ ~~**PLAN-13 WILL GO RED ON REBASE — AND IT IS NOW IMMINENT, NOT HYPOTHETICAL (updated
  2026-09-03 ~19:30).** PLAN-13 has moved to **`6-finalize` with 5 of 5 tasks `done`** (tip
  `81e4952`), and `git merge-base --is-ancestor 6ba8879 feature/loopback-stall-fix-and-instrumentation`
  reports it has **NOT yet rebased onto `6ba8879`**. Its `sync-baseline` finalize step is therefore
  still ahead of it, and that step is precisely where this fires — the failure will land at
  `pre-push-quality-gate`. The two offending sites are on `main` right now at
  `GatewayEdgeRouteTest.java:851` and `:856`; the file carries six `listen(0)` calls in total, of
  which PLAN-13's branch converts the four pre-existing ones (`:260`, `:293`, `:299`, `:343`) and has
  never seen these two. Original entry:

- ⛔ **PLAN-06 reintroduced two bare `listen(0)` calls on `main`
  (2026-09-03).** Deliverable 2's new `ForwardedTrustFromEnvironment` block in
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteTest.java` binds a stub
  upstream and a front server with the single-int overload. PLAN-13's `LoopbackEphemeralBindArchTest`
  imports `BASE_PACKAGE = "de.cuioss.sheriff.gateway"` under
  `ImportOption.Predefined.ONLY_INCLUDE_TESTS` and carves out exactly one fixture
  (`tls.TlsEdgeProducerTest`), so `GatewayEdgeRouteTest` is inside the guarded selection and the rule
  forbids precisely this overload. ⚠ **The rebase itself is CLEAN** — `git merge-tree --write-tree`
  against `main` returns a tree with no conflict, because PLAN-06 appended at old line 749 while
  PLAN-13 edits 751/776/792. **So the collision surfaces at BUILD time, not at rebase time**, and a
  clean rebase must NOT be read as the hazard having passed. Owned by PLAN-13 — inside its existing
  charter, its spec already declares that file, no new plan owed. — source: merged diff of `6ba8879`,
  read of the guard's own constants in PLAN-13's worktree, and a `merge-tree` dry run at HEAD `6ba8879`.

- ⛔ **The compose sample's DEFAULT path does not boot until 0.2.0 ships.** PLAN-06 added the
  `forwarded:` block plus `SHERIFF_TRUSTED_PROXIES` wiring to `deployment/compose-sample/`, but
  `.env:15` pins `ghcr.io/cuioss/api-sheriff:0.1.1`, whose loader predates the array arm: the
  placeholder resolves to a single string, hands a string to a key the schema declares an `array`,
  and fails the boot. ✅ **Disclosed, not hidden** — the skew, its cause and the
  `API_SHERIFF_IMAGE=api-sheriff:distroless ./scripts/start-sample.sh` workaround are documented at
  `docker-compose.yml:159-170`, and the `.env` pin is deliberately not bumped ahead of the release
  because that file's own rule is that it names an image that exists. CodeRabbit raised it as Major
  and was right; the plan accepted it rather than deferring. ⛔ **Self-resolving at the 0.2.0 release
  and therefore easy to ship past** — recorded so the release is not cut without bumping the pin.
  Clean revert if the operator prefers: deliverable 3's `gateway.yaml` hunk. — source: read of
  `.env:15` and `docker-compose.yml:150-170` at HEAD `6ba8879`.

- ✅ **RETIRED 2026-09-07 — `required_bots` WAS NEVER BROKEN, AND THIS ORCHESTRATOR WAS WRONG TO SAY
  IT WAS.** Verified first-party in the plan-marshall marketplace:
  `automatic-review/standards/cuioss-review-bot.md:56` declares **`bot_kind: cuioss-review-bot`**, and
  upstream commit `cc5ea40a1` (**2026-09-03**) *"feat(automatic-review): flag unknown bot-kind tokens;
  **rename pr-agent**"* renamed `pr-agent.md` → `cuioss-review-bot.md`. There is no `pr-agent.md` in
  the registry any more.

  **The timeline exonerates the config outright:**

  | Date | Event |
  |---|---|
  | 2026-09-02 | API-Sheriff `1c7308c` renames the token to `cuioss-review-bot` (#249) — **ahead of upstream by a day** |
  | 2026-09-03 | plan-marshall `cc5ea40a1` renames the registry doc to match |
  | 2026-09-04 | PLAN-10 reports *"no `cuioss-review-bot.md` registry doc exists"* — **already false when written** |

  ⛔ **Root cause on the orchestrator's side, recorded because it is the more useful half.** PLAN-10's
  candidate 003 asserted an **absence**, and this ledger recorded it **without verifying the
  registry** — then repeated it across four landings and put an "operator decision owed" in front of
  the operator three times. The epic's own [Verify-First Contract] says an asserted absence carries
  the *same* obligation as an asserted presence and is the higher-risk half. PLAN-03's landing, in
  this same session, is the identical failure (five doc sites asserting a fourth egress leg did not
  exist) — written up by this orchestrator while it was itself propagating one.
  ⚠ **The corollary is the durable lesson: a claim relayed from a plan's own inbox message is a LEAD,
  not a fact — including when it is precise, mechanism-level, and internally coherent.** This one was
  all three and still wrong.
  ⚠ CLAUDE.md line 221 was updated to `coderabbit,cuioss-review-bot`; that is the **correct**
  direction and reconciles the doc with a config that was already right. Original entry, now known
  false:

- ⛔ ~~**`required_bots` carries a broken token in PROJECT config and the fix is the operator's call.**~~
  `.plan/marshal.json:115` reads `"required_bots": "coderabbit,cuioss-review-bot"`.
  `cuioss-review-bot` is pr-agent's `author_login`, not a registry `bot_kind`, so participation
  resolves ABSENT for a bot that did participate — it would have blocked PR #254's merge and will
  block every future plan in this epic the same way. PLAN-06 fixed it plan-locally only, which is the
  correct boundary: commit `1c7308c` made that rename deliberately, so changing project config is an
  operator decision. Durable fix: `coderabbit,pr-agent`. ⚠ **Operator decision owed — this is the one
  item here that silently taxes every remaining plan.** — source: read of `.plan/marshal.json:115` at
  HEAD `6ba8879`.
  🔄 **RECURRENCE 2026-09-04 via inbox `adr-preboot-health-probe-003.md` (folded, not duplicated) —
  the mechanism is now fully named and it is WORSE than "a false block".** The registry declares
  `bot_kind: pr-agent` with `author_login: cuioss-review-bot` in
  `automatic-review/standards/pr-agent.md`, and **no `cuioss-review-bot.md` registry doc exists at
  all**. A token matching no `bot_kind` can never resolve, so the reviewer is classified `absent`
  **forever** and the participation quorum is **structurally unconvergeable by awaiting** — the
  loop-back ceiling burns and the run dead-ends at the barrier rather than failing with a
  diagnosable cause. ⛔ **The two config surfaces disagree and the documented one is right**: project
  `CLAUDE.md` still carries `coderabbit,pr-agent`; `.plan/marshal.json` is the side that drifted at
  commit `1c7308c`. PLAN-10 patched it plan-locally only, again. Two plans have now paid for this.
  Candidate remedies in increasing strength, recorded from the message: (a) fix `marshal.json`;
  (b) validate `required_bots` tokens against the live registry `bot_kind` set at config-write or
  step entry, so an unresolvable token is rejected where it is written rather than at an exhausted
  barrier; (c) have the barrier distinguish "configured bot resolves to no registry entry" from
  "configured bot did not review" — only the second is worth awaiting.

- ✅ **SOLVED 2026-09-11 — THE MISSING READ IS ONE COMMAND, AND IT WORKS TODAY.**
  `gh run list --repo cuioss/API-Sheriff --commit <merge-sha> --json workflowName,status,conclusion,event`
  returns the main-branch (`event: push`) runs keyed by MERGE COMMIT — exactly the lookup the CI
  abstraction cannot express. Used it to discharge **both** outstanding post-merge checks in one pass:
  `7fce677` (PLAN-08) — Maven Build, Integration Tests, Demo Client E2E and Scorecard **all success**,
  closing the gap this entry recorded as unverifiable; and `6ee4a55` (PR #290) — the same four, all
  success.
  ⛔ **So the defect is NOT structural, it is a missing verb**: `ci checks status` takes
  `--pr-number` / `--head` and resolves both to a PR. **The fix is a commit-addressed read in
  `tools-integration-ci`**, and it belongs upstream in plan-marshall. Until it lands, the sanctioned
  route for a post-merge check is `gh run list --commit`, which CLAUDE.md already permits
  (*"Allways use gh tool to access github"*). Prior entry:

- ⛔ ~~**RECURRENCE 2026-09-11, and now MEASURED.**~~ Corroborating PLAN-08 needed the main-branch run
  for merge commit `7fce677`. `ci checks status` takes `--pr-number` or `--head`, and **both resolve
  a branch to a PR** — `--head 7fce677` returns *"no pull requests found for branch 7fce677"*. There
  is no read verb in the abstraction that takes a merge commit, so the main-branch half of the
  obligation is **unreachable**, exactly as this entry predicted. ✅ The PR-attached half DID resolve
  (`Run Integration Benchmarks` SUCCESS on PR #286), so the two halves now have different, verified
  statuses rather than one unknown.

- ⛔ **The epic cannot discharge its own post-merge verification obligation through the sanctioned
  tooling.** CLAUDE.md § Git Workflow step 8 assigns the orchestrator the main-branch run for the
  merge commit — the only place `deploy-snapshot` ever runs, since it is skipped on pull requests.
  But every `plan-marshall:tools-integration-ci:ci` verb is PR-keyed: `checks status` accepts only
  `--pr-number` / `--head`, `--head main` returns `no pull requests found for branch "main"`, and no
  verb reads a push-triggered run by commit. The small-ops carve-out forbids reaching for `gh`
  directly, so the lookup is **structurally unperformable from this seat** and PR #254's snapshot
  deployment is recorded as an UNVERIFIED LEAD rather than as green. ⚠ This is not specific to
  PLAN-06 — it applies to every landing this epic will ever analyze. Unowned; a tooling-level gap. —
  source: `ci checks status --head main`, `ci --help` verb enumeration at HEAD `6ba8879`.

- ⚠ **The two new `ManagementRootPathLabelIT` legs are compile-verified only.** CI is their first real
  execution. File confirmed present at
  `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/ManagementRootPathLabelIT.java`.
  Self-resolving on the next integration run; recorded so a green build is not mistaken for a green
  *first* run. Owned by PLAN-02's landing, no follow-up plan staged. — source: file read at HEAD `1c7308c`.

- ⛔ **The 30s-hang flake class is OPEN — NARROWED 2026-09-02 by PLAN-11 (PR #243 → `5948962`), not
  closed.** The investigation measured 31 pre-registered runs: 8/31 stalls, ten methods across five
  classes, every elapsed within 10 ms of the 30 s ceiling, thread dumps showing the acceptor and all
  three event loops idle in `KQueue.poll`. ✅ **The TIME_WAIT/port-reuse hypothesis is contradicted for
  the HELD-LISTENER form** — the stall reproduces against ports held for the whole fixture lifetime —
  which killed the leading fix candidate before it became a third unmeasured fix. ⛔ **Read that scope
  literally**: the accepted-socket and client-socket forms were never tested, so port reuse is not
  refuted as a class; and zero reproductions of the symptom the budget was sized against is
  *inconclusive-with-power*, never an all-clear. The mechanism itself — an inferred loopback readiness
  event the macOS kqueue selector never delivers — remains **uninstrumented**. Full record:
  `landings/PLAN-11.md`. — source: operator paste, corroborated against git, the CI abstraction and
  the lessons corpus.

- ⛔ ~~**The 30s-hang flake class in the api-sheriff edge/tls suite is OPEN — the plan that attacked it
  shipped its mechanism and MISSED its goal, 2026-09-01.**~~ *(superseded by the narrowed entry above;
  kept as the state PLAN-11 was commissioned against.)* PR #241 → `0937922` landed 6/6 deliverables
  (tiered instrumented ceilings, a mutation-proven falsifiable control, ~114 fixed awaits converted
  across 11 classes, a bind-refusal redesign with a matched positive control), but deliverable 6 — an
  N=10 acceptance soak against a criterion of ten consecutive green — **ran and FAILED 5 RED / 5
  GREEN**, and reproduced again the same day including in isolation at 1 in 9 single-class runs.
  ⚠ **Do not read PR #241 as closing this.** The mechanism is better and the locus is pinned below all
  production code; the flake is not closed. Its originating lesson `2026-08-29-16-002` was
  deliberately restored to the ACTIVE corpus rather than archived with the plan, and now records that
  the `-T1` pin *reduced but did not close* it — so the mitigation this epic's watch relied on is
  known insufficient. Follow-up brief drafted at `.plan/temp/macos-loopback-hang-investigation.md`
  (251 lines). — source: operator landing paste, corroborated against `git log`, the CI abstraction
  (`state: merged`, `merge_commit_sha: 0937922a20…`) and the live lessons corpus.

- ✅ **RETIRED 2026-09-02 — the follow-up brief durability risk is gone.** The investigation it carried
  ran as PLAN-11 and landed as PR #243; its findings now live in `doc/development/build-gate-discipline.adoc`
  (+271 lines), in three lessons, and in `landings/PLAN-11.md`. The `.plan/temp/` copy is no longer the
  sole carrier.

- ⚠ ~~**The follow-up brief is not durable.**~~ `.plan/temp/macos-loopback-hang-investigation.md` is the
  only carrier of the macOS-loopback investigation, and CLAUDE.md designates `.plan/temp/` for
  temporary and generated files — the same class of durability risk the ADR-0038 draft carried before
  PLAN-10 was staged for it. It survives a session but is not tracked. Either land it through a plan
  or accept that a `.plan/` wipe takes it. **Deliberately NOT staged into this epic** — see the
  scope decision in Watches. — source: file read at HEAD `0937922`.

- ✅ **RESOLVED 2026-08-31 by PLAN-01's landing — the text below is the ORIGINAL finding, kept for the
  record, and no longer describes HEAD.** At `c170779` `Dockerfile.native:56` carries a `HEALTHCHECK`
  invoking `/app/application --health-probe`, its header at `:10` states the image reports its own
  health, and the probe logic lives in the separately-testable
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/HealthProbe.java` with `HealthProbeTest.java`
  beside it. ⚠ The mechanism is shipped but **undocumented** — that is what PLAN-10 lands as ADR-0038.
  Original finding:

- ~~**The distroless main image has no `HEALTHCHECK`.**~~ `api-sheriff/src/main/docker/Dockerfile.native`
  ends at `EXPOSE 8443 9000` / `USER nonroot` / `ENTRYPOINT` with no `HEALTHCHECK`, while
  `Dockerfile.native.jfr` — on UBI micro, whose header comment says *"has shell, needed for
  HEALTHCHECK and JFR output"* — carries one. `integration-tests/docker-compose.yml` states the
  consequence outright: *"no in-container healthcheck (the distroless image ships neither a shell
  nor curl)"*, and gates readiness host-side from `start-integration-container.sh` instead. Owned by
  PLAN-01. — source: read of both Dockerfiles and the compose file at HEAD.

- **Hostname verification is never configured on the upstream dial.** `DispatchStage:244` and
  `WebSocketRelayStage:148` call `.setSsl(...)` on the upstream request options, and
  `GatewayEdgeRoute:1295-1297` builds `new HttpClientOptions().setProtocolVersion(HTTP_2)` then
  `.setSsl(true).setUseAlpn(true)` — **`setVerifyHost` is called nowhere in the codebase**, so the
  Vert.x default stands and cannot be changed by any configuration. Owned by PLAN-03. — source:
  repo-wide grep at HEAD.

- **No test ever forces a BFF token refresh.** `BffSessionMediationIT`'s own comment concedes it:
  *"the integration realm's 900s access-token lifespan keeps the token well inside its validity for
  both requests, so this asserts the always-available continuity path rather than forcing a
  refresh"*, adding that a forced-refresh trigger *"needs a short access-token-lifespan realm client
  and is validated by the native+Docker IT run"*. **No file in the repository sets a short
  access-token lifespan** — all three realm JSONs use 900s, 900s and 300s at realm level with no
  client-level override — so that second claim is unsupported and the refresh path is untested.
  Owned by PLAN-05. — source: read of the IT and all three realm imports at HEAD.

- ✅ **RETIRED 2026-09-10 by the coverage audit — IT LANDED, AND THE ORDINAL MOVED.** Verified by
  listing `doc/adr/` at HEAD `990aebf`: PLAN-01's pre-boot probe ADR is
  `0039-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc`,
  the exact title the draft names. ⛔ **`0038` is a DIFFERENT ADR** — PLAN-02's
  `A_build-time_key_the_shipped_artifact_declares_is_made_configurable_through_a_carrier_key_in_the_build_tools_own_namespace`.
  The bullet below was written when `doc/adr/` topped out at `0037` and was never re-grounded after
  PLAN-02 consumed `0038`; it has been reporting an owed deliverable that shipped. ⚠ **This is the
  stale-ADR-ordinal class this epic has now hit twice** — PLAN-10's ordinal risk was the first, and
  PLAN-22's spec carries an explicit warning about it for the same reason. Original text follows.

- ⚠ ~~**ADR-0038 is drafted and rescued, but not landed.**~~ It records PLAN-01's pre-boot probe mechanism
  and its three refuted alternatives, and was deferred out of PR #230 to avoid re-gating a
  merged-ready branch. ✅ **Loss risk removed 2026-08-29**: both artifacts were copied byte-identical
  out of the ephemeral session scratchpad into this epic tree —
  `landings/PLAN-01-adr-0038-draft.adoc` (218 lines) and
  `landings/PLAN-01-adr-0038-readme-row.patch`. ⛔ **Still not durable in the git sense**: `.gitignore`
  line 20 excludes `.plan/*`, so this tree is untracked. The real fix is landing it at
  `doc/adr/0038-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc`
  — the filename the patch itself names, and `0038` is confirmed the next free ordinal (`doc/adr/`
  tops out at `0037`). That is a repository-source write and belongs to the plan lifecycle. Operator
  decision owed: a documentation-only PR, which per CLAUDE.md skips both gates.

- **The landing payload carried no `landing-facts` block.** `inbox landing-check` on
  `distroless-health-check-001.md` returned `complete: false` with the **whole required set** missing
  (`schema`, `plan_id`, `pr`, `merge_state`, `deliverables_total`, `deliverables_done`,
  `total_tokens`, `steps`). The landing was still drained and reconciled — every fact was recovered
  from the prose and corroborated against git and the CI abstraction — but a manual paste from that
  plan may still surface something the inbox did not. — source: `inbox landing-check` at HEAD.

- ✅ **RESOLVED 2026-09-03 by PLAN-06 (PR #254 → `6ba8879`) — retired.** `ConfigLoader` now carries a
  list-valued `${VAR}` arm that types a placeholder from its destination's declared `array` type and
  splits it into the whole list, with an empty-resolution refusal; `ConfigLoaderTest` (+143) covers
  it and `edge/GatewayEdgeRouteTest`'s new `ForwardedTrustFromEnvironment` block proves the
  env-supplied set reaches the inbound forwarding decision through a full boot. ⚠ Two residues, both
  filed below as their own defects: the compose sample's DEFAULT path does not boot until 0.2.0, and
  `BROAD_PREFIX_IPV4 = 8` (issue #256) is now the only review surface a `/12` ever gets. Original
  finding: 

- ~~**The proxy allow-list is only half env-configurable.**~~ `forwarded.trusted_proxies` — the mandatory
  CIDR set ADR-0003 requires before any `Forwarded` / `X-Forwarded-*` header is believed — lives only
  in `gateway.yaml`. Each *entry* is `${VAR}`-substitutable (the loader's walk descends into arrays,
  `ConfigLoader:425-431`), but `coerce` has no `array` case (`:474-481`), so **one variable can never
  expand into the list** and the schema's `"type": "array"` refuses the attempt at boot. Nor can a
  declared slot be switched off: `${VAR:-}` fails CIDR validation
  (`ConfigValidator:1040-1053`) and an unset bare `${VAR}` fails the boot. **The cardinality is baked
  into the mounted file** — an operator cannot go from three trusted proxies to four without editing
  YAML. Owned by PLAN-06. — source: read of the loader, the schema and the validator at HEAD.

- ✅ **RESOLVED 2026-09-03 by PLAN-06 (PR #254 → `6ba8879`) — retired.** The forwarded block is now
  exercised at three levels: array-element substitution in `ConfigLoaderTest`, the full-boot effect
  test in `edge/GatewayEdgeRouteTest`, and `ComposeSampleForwardedTrustWiringTest` (+312) guarding
  the shipped sample's wiring without Docker. The shipped `deployment/compose-sample/docker/
  sheriff-config/gateway.yaml` now declares `forwarded:` for real. Original finding:

- ~~**Nothing in the repository exercises the forwarded block.**~~ None of the six shipped `gateway.yaml`
  files declares `forwarded:` at all, and `trusted_proxies` appears outside the schema/validator/model
  and docs only in `api-sheriff/src/test/resources/config/valid/gateway.yaml:24`. Separately,
  **array-element substitution is untested** — `ConfigLoaderTest` covers substituted scalars only
  (string `:766`, boolean `:820-840`, integer `:858`) and every `trusted_proxies` test uses a literal.
  Per the project's own rule, a key that parses is not a key that acts. Owned by PLAN-06. — source:
  repo-wide grep at HEAD (asserted absence).

- ✅ **RESOLVED 2026-09-03 by PLAN-02 (PR #248 → `b200bed`) — retired.** All three keys are now
  declared at `application.properties:60-62` and made configurable through a carrier-key indirection
  recorded as **ADR-0038**, with the Maven build seam in `api-sheriff/pom.xml` as the operator-facing
  override. ⚠ The limitation is documented rather than faked: all three are build-time keys, so the
  matching environment variables are inert against an already-built image and a change always costs a
  rebuild. Original finding:

- ~~**No context-path configuration exists.**~~ Neither `quarkus.http.root-path` nor
  `quarkus.http.non-application-root-path` appears anywhere in the repository. Owned by PLAN-02. —
  source: repo-wide grep at HEAD (asserted absence).

## Watches — handled (relocated 2026-09-11)

38 entries moved from `epic.md` § Watches, in their original order; 9 live entries stayed behind.

### Why each entry is settled

| # | Entry (first line, abridged) | Why it is settled |
|---|---|---|
| W01 | ✅ **POST-MERGE OBLIGATION DISCHARGED FOR `6c1b6b6` — BY THE OPERATOR, BECAUSE THE ORCHESTRATOR | post-merge discharged for `6c1b6b6` |
| W03 | ⛔ **THE MERGE WENT AHEAD WITHOUT A CODERABBIT REVIEW OF THE FINAL TREE, on operator authorization | authorized deviation recorded; PLAN-17 shipped; the channel family went upstream (truthful-signals `-002`) |
| W04 | ⚠ **A PRUNE REPORTED FAILURE FOR AN ALREADY-COMPLETED DELETION, leaving a stale remote-tracking | operator removed the stale ref |
| W05 | ✅ **RETRACTED 2026-09-11, SAME DAY — THE INSTANCE EXISTS AND I WAS WRONG.** | retraction |
| W06 | ⚠ ~~**THE PLAIN-HTTP INTEGRATION INSTANCE WAS NOT CREATED, and the landing is otherwise faithful | struck — superseded |
| W08 | ⛔ **PLAN-08 ∥ PLAN-17 WAS A CORRECT PAIRING, and it is the first concurrent pair in this epic to | both plans shipped; pairing record |
| W09 | ⚠ **A ~90-MINUTE LOSS TO CHANNEL MISREAD, same family as the two above (2026-09-11).** PLAN-08 | the silent-channel family went upstream (truthful-signals `-002`/`-003`) |
| W11 | ⛔ **PLAN-03 ∥ PLAN-05 IS NOT SAFE, AND THE GATE SAYS OTHERWISE — a demonstrated blind spot, not a | both plans shipped; the gate has since moved to the single `epic_spec_parser` reader |
| W12 | ⛔ **cui-http 3.0 MAY REFUTE PLAN-04'S WHOLE MECHANISM — re-ground claim 11 BEFORE PLAN-04 is | PLAN-04 shipped on cui-http 3.0's `verifyHostname` |
| W13 | ⚠ **DISCARDED CANDIDATE, recorded so it is not re-derived — inbox | discarded candidate — do not re-derive |
| W14 | ✅ **RETIRED 2026-09-05 — the parent-POM bump is DISCHARGED, not merely stale.** PLAN-15's | retired |
| W15 | ⚠ ~~**PARENT POM BUMPED UNDER THE WHOLE CORPUS — `cui-java-parent` 1.5.11 → 1.6.1 (PR #258, | struck — superseded |
| W16 | 📥 **FIRST COMPLETE INBOX LANDING — the "landings arrive only by paste" defect is empirically | historical first-landing record |
| W17 | ⚠ **DISCARDED CANDIDATE, recorded so it is not re-derived — inbox `adr-preboot-health-probe-001.md` | discarded candidate — do not re-derive |
| W18 | 🔄 **RESOLVED-AND-REPLACED 2026-09-03 — the PLAN-06 ↔ PLAN-13 collision REALIZED, but not in the | both plans shipped |
| W19 | ✅ **RETIRED 2026-09-03 — PLAN-10's ADR-ordinal risk is CLEARED.** This section previously held | retired |
| W20 | ⚠ **The gate's THIRD blindness class, and the only one that is not a matcher limitation.** PLAN-06 | spec-authoring lesson absorbed; PLAN-06's surface corrected at the time |
| W21 | ⛔ **LIVE COLLISION BETWEEN THE TWO RUNNING PLANS — the gate caught this one (2026-09-03).** | both plans shipped |
| W23 | ⚠ **Two agents now run concurrently in ONE checkout (2026-09-03).** PLAN-06 runs through the | transient; both plans shipped |
| W24 | ✅ **PLAN-06 ↔ PLAN-12 verified disjoint THROUGH the shared test infrastructure, not merely by | verified disjoint; both shipped |
| W25 | ⛔ **UNDECLARED COUPLING, carry it to the PLAN-07/PLAN-08 emit decision.** The same check found | PLAN-07 and PLAN-08 both shipped |
| W26 | ⚠ **`api-sheriff/src/test/resources/junit-platform.properties` does not exist at HEAD.** PLAN-12 | moot — PLAN-12 landed and the file still does not exist at `0515e15` |
| W27 | 🔑 **The single most consequential finding of 2026-09-01: the edge/tls flake is a MACHINE property, | superseded by PLAN-13's measured mechanism |
| W28 | 🔄 **SCOPE DECISION REVERSED 2026-09-01, by operator instruction — the macOS-loopback investigation | settled scope decision |
| W29 | ▶ **PLAN-02 RESUMING 2026-09-01 by operator decision** — it keeps the only slot and no transition | PLAN-02 shipped |
| W30 | ✅ **RETIRED 2026-09-01 — PLAN-02 is moving again: 4 of 14 tasks done, up from 1.** The idle-hold | retired |
| W31 | ⚠ ~~**PLAN-02 was idle ~24h at 1 of 14 tasks.**~~ `TASK-001` (*declare context-path keys and Maven | struck — superseded |
| W32 | ⚠ **Three judgment calls from the PR #241 plan are recorded as live process signals, not as | settled judgement calls; (2) went upstream as truthful-signals `-006` |
| W37 | The reported refresh exception has no error text, stack trace or log identifier. If any detail | PLAN-05 shipped |
| W38 | **Four PLAN-01 lessons bypassed epic disposition.** `2026-08-29-16-001` (a contract test guarding | all four lessons dispositioned by the 2026-09-11 lessons intake |
| W39 | ⚠ **Every re-grounding verdict is now stale.** All 40 stamped claims carry `checked_at: cea163c`; | superseded by the re-grounding passes at `990aebf` and `0515e15` |
| W40 | **Upstream: `cuioss/cui-http#165`** (OPEN, filed 2026-08-27) asks for a first-class | `cuioss/cui-http#165` CLOSED as COMPLETED 2026-08-27; `TokenValidatorProducer:266` uses the upstream `verifyHostname` |
| W41 | **Cross-repo `cui-http` coordination (PLAN-04) — RE-AIMED 2026-08-27, not closed.** Option (a) (an | resolved upstream by #165; no hand-rolled trust manager shipped |
| W42 | ⛔ **JDK-version bound on the mechanism settlement.** The refutation of (a) was re-verified at | moot — the mechanism settled on upstream `verifyHostname`, not option (a) |
| W43 | ✅ **Cross-repo refresh gate SATISFIED 2026-08-31.** TokenSheriff `deployment-and-refresh-gaps` | satisfied |
| W44 | ⛔ **Residual 4 is the live lead: discovery-resolved metadata.** Their tests bypass discovery by | PLAN-05 shipped |
| W45 | ✅ **DPoP `cnf`-binding failure shape is NOT reachable here — do not re-derive.** The verdict flags a | do-not-re-derive note |
| W46 | **`TokenLifecycleManager` is not on this gateway's refresh path** — it and `RefreshScheduler` appear | do-not-re-derive note |

### The entries, verbatim

- ✅ **POST-MERGE OBLIGATION DISCHARGED FOR `6c1b6b6` — BY THE OPERATOR, BECAUSE THE ORCHESTRATOR
  CANNOT REACH IT (2026-09-11).** All four main-branch runs complete and successful: Maven Build
  (including `deploy-snapshot`, which is skipped on PRs and runs only on the push to `main`),
  Integration Tests, **Demo Client E2E 28/28**, and Scorecard supply-chain.
  ⛔ **This is the exact half `analyze` reported unreachable a day earlier** — `ci checks status`
  resolves `--head` to a PR, so a merge commit cannot be looked up. **The obligation IS dischargeable;
  it is simply not dischargeable through the sanctioned surface.** That sharpens the standing Open
  Defect from *"we cannot verify"* to *"the abstraction lacks one read verb"*, which is a fixable
  statement rather than a structural one.
  ✅ **Demo Client E2E is the lane PLAN-16's landing identified as the only one that catches the
  cookie-deliverability class**, and it runs post-merge where it cannot gate the PR. 28/28 green.

- ⛔ **THE MERGE WENT AHEAD WITHOUT A CODERABBIT REVIEW OF THE FINAL TREE, on operator authorization
  (2026-09-11).** CodeRabbit delivered one review, then **acknowledged three further requests across
  two PRs without servicing any**. PR **#287 was closed unmerged and reopened as #288** — the same
  branch at the same commit — which is the recorded remedy for this exact failure, and it **did not
  work the second time**. ⚠ **`.plan/marshal.json` lists `coderabbit` in `required_bots`**, so the
  merge is a deliberate, authorized deviation from the project's own gate, not an oversight.
  ⛔ **Record the consequence precisely**: the final tree of #288 — a cryptographic packaging change
  carrying a compression stage and an AEAD boundary — **shipped without a bot review of what
  actually merged**. The ADR and the tests are the compensating controls. **This is the second
  recorded case of a review channel that acknowledges and then does nothing**, and it belongs with
  the `emit-landing` and `ci pr merge-queue` findings as one family: *a channel that cannot
  distinguish "nothing to report" from "did not run".*

- ⚠ **A PRUNE REPORTED FAILURE FOR AN ALREADY-COMPLETED DELETION, leaving a stale remote-tracking
  ref (2026-09-11).** Removed by the operator after verifying all three ref states directly.
  ⛔ **Same family as the entry above, with the sign flipped**: that channel reports success it did
  not achieve; this one reports failure for work already done. **Both make the return value
  unusable as evidence, which is why every one of these has cost this epic real time.**

- ✅ **RETRACTED 2026-09-11, SAME DAY — THE INSTANCE EXISTS AND I WAS WRONG.**
  `api-sheriff-no-certificate` is live at `integration-tests/docker-compose.yml:1032`, publishing
  `10453:8080` **plain HTTP**, a deliberately dead `10454:8443`, and `19010:9000` management, with
  the required `de.cuioss.sheriff.management-scheme` label and a comment naming it *"THE
  CONFIGURATION UNDER TEST, expressed as an absence."* ⛔ **Added by `a30fe6f` (PR #283, PLAN-07)**,
  established with `git log -S` — so it predates PLAN-08, and PLAN-08 correctly **extended** it per
  its own re-scope header rather than building a second. **PLAN-08 shipped all five deliverables;
  there is no fidelity gap.**
  ⛔ **The error was mine and it is this epic's signature class turned on the orchestrator**: I
  compared the diff against the spec's ORIGINAL deliverable-4 text instead of against HEAD, when the
  re-scope header superseding it was in the same document I had read. Fourth instance of
  stale-premise reasoning in this epic, and the first committed by the ledger rather than by a plan.
  Original entry:

- ⚠ ~~**THE PLAIN-HTTP INTEGRATION INSTANCE WAS NOT CREATED, and the landing is otherwise faithful
  (2026-09-11).**~~ PLAN-08's deliverable 4 specified *"a `sheriff-config-*` directory, a
  `de.cuioss.sheriff.management-scheme` label, and host-side readiness through the same
  Compose-derived loop, with no branch on the service name anywhere."* Verified against the diff of
  `7fce677`: **no `integration-tests/src/main/docker/sheriff-config-plain-http/` exists.** The
  end-to-end proof landed on the **existing** surface instead (`NoCertificatePlainHttpOptInIT.java`
  +500) and passes. ✅ **Recorded as a Watch rather than an Open Defect**: the mode IS proven end to
  end, so what is unestablished is narrower — *"the mode has its own instance in the integration
  stack"*. ⚠ **PLAN-09's scenario 7 is the natural place to decide whether the documented scenario
  needs a running instance behind it**, and that scenario is now unblocked. — source: diff read at
  HEAD `7fce677` against the staged spec's deliverable 4.

- ⛔ **PLAN-08 ∥ PLAN-17 WAS A CORRECT PAIRING, and it is the first concurrent pair in this epic to
  land with no observed collision (2026-09-11).** They merged 1h45m apart (`6c1b6b6` 20:38,
  `7fce677` 22:23) with disjoint surfaces holding in practice. ⚠ **Recorded because the gate's
  failures are well documented here and its successes are not** — three blind spots are logged above
  and no counter-example was. This is the counter-example. — source: merge timestamps and both diffs.

- ⚠ **A ~90-MINUTE LOSS TO CHANNEL MISREAD, same family as the two above (2026-09-11).** PLAN-08
  read *zero comments on an incremental CodeRabbit re-review* as **never ran** when it meant
  **nothing to report** — the coverage evidence was in the comment body. Recorded by the plan as a
  lesson. ⛔ **Note the shape**: this epic now carries three distinct defects that are all *a silent
  channel misread as a verdict* — `emit-landing`'s empty drain, `ci pr merge-queue`'s false
  `enqueued: true`, and this. **The common fix is that a channel must distinguish "nothing happened"
  from "nothing was reported", and none of the three does.** — source: PLAN-08 landing narrative
  (operator's own, trusted).

- ⛔ **PLAN-03 ∥ PLAN-05 IS NOT SAFE, AND THE GATE SAYS OTHERWISE — a demonstrated blind spot, not a
  suspicion (2026-09-07).** `corpus cross-check` produces **no** PLAN-03 × PLAN-05 row, and the same
  output shows why that is wrong: PLAN-03 collides with PLAN-01, PLAN-02 and PLAN-08 on the exact
  string `integration-tests/src/test/java/de/cuioss/sheriff/gateway/integration/`, because those
  specs declare that **directory**. PLAN-05 declares **three files inside it**
  (`BffSessionMediationIT.java`, `BffRefreshSpecIT.java`, `BffKeycloakLoginFlow.java`), so equality
  fails and no row appears. ⚠ **The collision and the blindness are visible in one payload** — this
  is the directory-vs-file class, third occurrence, and the first time it can be read off a single
  output rather than argued.

  Two further reasons, independent of the matcher:
  1. **A shared-fixture change.** PLAN-05 edits
     `integration-tests/src/main/docker/keycloak/integration-realm.json` — the realm the whole
     integration stack imports. PLAN-03's deliverable 4 runs a matched control pair against that same
     booted stack. A realm edit is not file-local.
  2. **Verify-budget contention, now measured in this epic.** PLAN-13 disclosed two pushes whose
     local runs were clipped at their wall-clock budget by a concurrent plan-marshall run driving load
     to 150–200. Both these plans run the Docker-based `-Pintegration-tests` suite; running them
     together is the worst case for that defect — and this epic's own WS-07 history is about port
     collisions under load.

  ✅ **Recommendation: sequence PLAN-05 after PLAN-03 lands.** Nothing is lost — PLAN-07 and PLAN-08
  are equally unemittable while PLAN-03 runs (they collide with it on `doc/configuration.adoc` and
  `doc/security-threat-model.adoc`), so the slot cannot be filled by anything else either.
  — source: `corpus cross-check` at HEAD `3fc4c83`, spec reads, and PLAN-13's landing disclosure.

- ⛔ **cui-http 3.0 MAY REFUTE PLAN-04'S WHOLE MECHANISM — re-ground claim 11 BEFORE PLAN-04 is
  emitted, and bind that check to the parent bump rather than to PLAN-04's outline (2026-09-06).**
  Raised by the question *"does PLAN-03 incorporate the cui-http 3.0 update?"* — it does not (PLAN-03
  declares no `pom.xml`, mentions cui-http nowhere, and uses Vert.x `setVerifyHost`, an unrelated
  mechanism), but PLAN-04 is a different story.

  PLAN-04's claim 11 — *"the resolved `cui-http` `HttpHandlerBuilder` exposes no hostname-verification
  method"* — carries `verdict: corroborated | checked_at: c6e6f52`, and its own evidence line states
  the reading is **version-pinned**, taken against the resolved **2.1.0** artifact. That claim is what
  selects mechanism **(b′)**: hand-roll a delegating `X509ExtendedTrustManager`, because
  *"no `cui-http` release is required"*. ⛔ **A major-version bump is precisely the event that can
  move a version-pinned claim**, and `cuioss/cui-http#165` requests exactly the knob whose absence
  claim 11 asserts. If 3.0 carries it, PLAN-04 should migrate to it and **delete the local trust
  manager** — and the cross-repo coordination with TokenSheriff `deployment-and-refresh-gaps` PLAN-03
  dissolves with it, since that coordination exists only because both sides would otherwise hand-roll
  the same security-sensitive class.

  **What was checked here, and what could not be**: the local `/Users/oliver/git/cui-http` checkout is
  at `2.3-SNAPSHOT` (main, `6c09375`) and neither `verifyHostname` nor
  `setEndpointIdentificationAlgorithm` appears anywhere in its main source — so **#165 has not landed
  as of 2.3**. ⛔ **3.0 is NOT in that checkout and its API is unverified from here.** That is an
  unchecked negative, not a checked one: do not read "absent at 2.3-SNAPSHOT" as "absent at 3.0".

  ⚠ **The claim is NOT re-stamped, deliberately.** At HEAD the resolved version is still 2.2, so
  claim 11 is *true right now* — stamping `contradicted` would manufacture a refutation, and
  `unverifiable` would misdescribe a check that simply is not due yet. The correct trigger is the
  bump: **when the 1.6.3 / cui-http 3.0 PR arrives, re-read `HttpHandlerBuilder` before PLAN-04 is
  emitted.** — source: PLAN-04 spec claim 11 and its verdict line; `git -C /Users/oliver/git/cui-http`
  log and source grep; PLAN-03 spec grep at HEAD `3fc4c83`.

- ⚠ **DISCARDED CANDIDATE, recorded so it is not re-derived — inbox
  `trusted-proxy-breadth-and-probe-doc-003.md` (the light-lane envelope's unmet handoff
  obligations).** PLAN-15 routed `planning_lane: light`, and that envelope left **two** obligations
  unmet, both reconciled by hand: `metadata.pr_title` was never authored (the orchestrator supplied
  it manually at PR-creation time), and **`2-refine` was left `in_progress` while `3-outline` was
  transitioned**, so the phase array carried two non-terminal phases — a state the sequential model
  does not admit, which made `progress` under-report the plan's completion for the rest of the run.
  ⛔ **The second is the serious one because it is SILENT**: a missing `pr_title` announces itself; a
  phase left open behind an advanced `current_phase` fails nothing immediately and quietly corrupts
  the record every later resume and retrospective reads. The generalisable shape the message offers:
  *when a lane elides a phase's substantive work, the phase's terminal bookkeeping is not part of
  what it may elide.* ⛔ **Discarded on the store-boundary ground, applied evenly with PLAN-10's
  candidate 001** — the defect and its remedy live wholly inside plan-marshall's light-lane envelope,
  a bundle this repository does not own, and the local residue is not a durable rule but "plan-marshall
  has a bug". ⚠ **Distinguished from candidate 002, which WAS promoted**: that one carries a local
  *reading* rule that survives independently of the upstream fix; this one does not. ⚠ Light routing
  is the expected default for this epic's bounded changes, so every light-routed plan reproduces it
  and costs the same manual reconciliation. **Worth reporting upstream.**

- ✅ **RETIRED 2026-09-05 — the parent-POM bump is DISCHARGED, not merely stale.** PLAN-15's
  `pre-push-quality-gate` ran **green on `cui-java-parent` 1.6.2** (its `sync-baseline` rebased over
  `3fca05c`, which bumped 1.6.1 → 1.6.2), executing **202 + 1926 tests**. So the `-Werror` risk
  across 1.5.11 → 1.6.1 → 1.6.2 was exercised and passed — the concern below is closed by evidence,
  not by time. ⚠ **Do not re-open it for the NEXT parent bump**: the discharge is specific to 1.6.2.
  Original entry:

- ⚠ ~~**PARENT POM BUMPED UNDER THE WHOLE CORPUS — `cui-java-parent` 1.5.11 → 1.6.1 (PR #258,
  `bb1ac5c`, 2026-09-04).** This is a **build-input** change affecting every remaining plan's gate
  run, and it landed while no epic plan was executing, so nothing has exercised it yet. ⛔ Per
  CLAUDE.md the reactor compiles with `-Werror` (`failOnWarning` + `showDeprecation`), so a parent
  bump that introduces a newly-deprecated API turns into a **build failure**, not a warning — and the
  first plan to run the gate discovers it. PLAN-15 is that plan. ⚠ **A gate red on PLAN-15 must be
  attributed against this bump before it is attributed to PLAN-15's own two-file change** — the
  standing "a gate red on one machine is not a branch defect" lesson (`2026-09-01-14-002`) has a
  sibling here: a gate red on a fresh parent is not necessarily a branch defect either.
  ⚠ Eight further commits landed in the same window (`337af0d..eedfda6`): seven dependency bumps
  including two Docker base-image digests, plus a chore. **None is an epic plan** — PLAN-13 has still
  not shipped.

- 📥 **FIRST COMPLETE INBOX LANDING — the "landings arrive only by paste" defect is empirically
  closed (2026-09-04).** PLAN-10's `emit-landing` reached this epic: 5 messages drained
  (4 `candidate-lesson`, 1 `landing`), all valid, and `inbox landing-check` returned
  **`complete: true` with `missing_keys[0]`** — every required fact key supplied, the first time this
  epic has had one. ⛔ **Do NOT read this as the bundle defect being fixed.** PLAN-01 and PLAN-02 both
  skipped with `emit-landing … skipped -- not orchestrated`; one success does not refute two failures,
  and nothing was changed in between that this epic can point to. Treat the channel as working-but-
  unproven: keep verifying an empty drain against the plan's own report rather than concluding
  nothing was sent.

- ⚠ **DISCARDED CANDIDATE, recorded so it is not re-derived — inbox `adr-preboot-health-probe-001.md`
  (scope classification vs the scope sensor).** `references.scope_estimate` was persisted `surgical`
  while the sensor banded the same request body `multi_module`; the two read different inputs
  (`module_mapping` vs glob/pattern markers in the request prose), so they disagree whenever a request
  cites patterns as **exclusions or evidence** rather than as deliverable fan-out. Here the `surgical`
  estimate was correct — `module_mapping` carried exactly two `documentation` paths — and the finding
  was resolved `taken_into_account`. ⛔ **Discarded rather than promoted for a store-boundary reason,
  not a merit one**: the remedy is a change to `plan-marshall`'s sensor, a bundle this repository does
  not own, and filing it in API-Sheriff's lessons store would put a plan-marshall lesson in the wrong
  store. The local residue — *a scope-band finding can be a false positive; read `module_mapping`
  before re-adjudicating* — is already covered by the `findings-triage` discipline in the corpus.
  ⚠ The message notes the guarded risk (a narrow band suppressing S3/S4 escalation) was unreachable
  here **only** because the plan was independently on `planning_lane=deep` / `track=complex`; a run
  without that cover would not be protected.

- 🔄 **RESOLVED-AND-REPLACED 2026-09-03 — the PLAN-06 ↔ PLAN-13 collision REALIZED, but not in the
  form predicted.** PLAN-06 shipped as PR #254 → `6ba8879` and it DID touch
  `edge/GatewayEdgeRouteTest.java` (+132), exactly the file this watch named. ⛔ **The predicted
  rebase conflict does NOT exist**: `git merge-tree --write-tree main
  feature/loopback-stall-fix-and-instrumentation` returns a clean tree — PLAN-06 appended at old line
  749, PLAN-13 edits 751/776/792, adjacent and non-overlapping. What is real is a **build-guard
  violation**: PLAN-06's new block introduced two bare `listen(0)` calls that PLAN-13's own ArchUnit
  fitness function forbids. ⚠ **Read this precisely — the substance was right and the FORM was
  wrong**, and the form is what a resuming session would have acted on: had the ledger carried only
  "expect a conflict", the clean rebase would have read as the hazard having passed, straight into a
  red build. Tracked as the first Open Defect above; owned by PLAN-13.

- ✅ **RETIRED 2026-09-03 — PLAN-10's ADR-ordinal risk is CLEARED.** This section previously held
  PLAN-10 back because PLAN-06's spec declared `doc/adr/` as a HYPOTHESIS mechanism ADR that would
  have taken ordinal `0039`. PLAN-06 landed and **did not touch `doc/adr/` at all** (`git show --stat
  6ba8879`); the directory tops out at `0038` (PLAN-02's carrier-key ADR). `0039` is free and PLAN-10
  no longer waits on anything from PLAN-06.

- ⚠ **The gate's THIRD blindness class, and the only one that is not a matcher limitation.** PLAN-06
  declared deliverable 2's effect test at `test/…/forward/` with the hedge *"exact class TBD"*; it
  landed in `edge/GatewayEdgeRouteTest.java`. The directory-vs-file and production/test-pair classes
  already recorded here are matcher limitations — a better matcher would catch them. This one is a
  **wrong declaration**: a correct matcher comparing `forward/` against PLAN-13's `edge/` files would
  have been equally blind. ⛔ **The lesson is about spec authoring, not tooling**: an unresolved
  location declared as a directory the plan then does not use is worse than declaring nothing,
  because it buys a confident disjointness verdict over the wrong path. PLAN-06's spec surface has
  been corrected to its realized footprint so the corpus no longer carries the false declaration.

- ⛔ **LIVE COLLISION BETWEEN THE TWO RUNNING PLANS — the gate caught this one (2026-09-03).**
  PLAN-06 and PLAN-13 both touch
  `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/edge/GatewayEdgeRouteTest.java`. It is one of
  PLAN-13's 20 `listen(0)` call sites, and it is entry 3 of PLAN-06's own 8-file
  `references.affected_files` — read from the worktree copy, since the lifecycle relocated PLAN-06's
  plan directory into `.plan/local/worktrees/forwarded-trust-env-configurability/`. This is a
  **realized-side** collision the declared-surface gate could not have predicted: PLAN-06's spec
  declares no `edge/` path at all. Not a live-corruption risk — PLAN-06 works on
  `feature/forwarded-trust-env-configurability` while PLAN-13 works in the main checkout — but
  **whichever lands second rebases over the other's edit to that file**, and the conflict is
  guaranteed rather than possible.
  🔄 **UPDATE 2026-09-03 ~19:00 — PLAN-13 now has its OWN worktree; the collision is UNCHANGED, only
  its isolation improved.** `git worktree list` and `manage-status list` both report PLAN-13 at
  `.plan/local/worktrees/loopback-stall-fix-and-instrumentation` on
  `feature/loopback-stall-fix-and-instrumentation` (`b004b40`, clean), so the "PLAN-13 works in the
  main checkout" clause above is retired — no lifecycle plan executes on the main checkout now. The
  guaranteed rebase conflict on `GatewayEdgeRouteTest.java` stands exactly as written: it is a
  branch-content collision, not a checkout-sharing one, and two worktrees do not resolve it.
  ⚠ **PLAN-06 is the one that will land first on current evidence** — 6 of 6 tasks `done`, 4 commits
  ahead of `main` (tip `aa1c632`), still phase `5-execute` with no PR open — so plan for PLAN-13 to be
  the plan that rebases. — source: `git worktree list`, `git log main..`, task-file read and
  `ci pr list` at HEAD `1f4fc24`.

- ⚠ **Two agents now run concurrently in ONE checkout (2026-09-03).** PLAN-06 runs through the
  plan-marshall lifecycle (`plan_marshall_plan_id=forwarded-trust-env-configurability`, phase
  `2-refine`, `use_worktree: true`); PLAN-12 runs as a plain Claude Code command from
  `.plan/temp/kqueue-readiness-instrumentation.md`. **PLAN-06 has no worktree yet** — `use_worktree`
  is declared but the worktree is created later in the lifecycle, so both are currently operating on
  `/Users/oliver/git/API-Sheriff` at `main`/`1c7308c`. PLAN-12 will dirty `main`; when PLAN-06's
  worktree is finally cut, `git worktree add` takes a clean checkout of the branch, so PLAN-12's
  uncommitted changes do NOT leak into it. ⛔ PLAN-12 is a plain command, so nothing in the lifecycle
  will branch for it — per CLAUDE.md its changes need a feature branch before any commit.

- ✅ **PLAN-06 ↔ PLAN-12 verified disjoint THROUGH the shared test infrastructure, not merely by
  declared paths.** The declared-path gate reports no overlap, but that gate cannot see shared test
  infra, so it was checked by hand at HEAD: `Awaits` has 13 consumer files — 7 in `edge/`, 3 in
  `tls/`, 2 in `testsupport/`, 1 in `bff/refresh/` — and **none in `config/` or `forward/`**, which
  is PLAN-06's declared test surface. The pair is safe to run concurrently.

- ⛔ **UNDECLARED COUPLING, carry it to the PLAN-07/PLAN-08 emit decision.** The same check found
  `Awaits` consumed by **3 files under `api-sheriff/src/test/java/de/cuioss/sheriff/gateway/tls/`**,
  which is exactly the declared test surface of PLAN-07 *and* PLAN-08. PLAN-12 is mutating `Awaits`.
  Neither PLAN-07 nor PLAN-08 declares `testsupport/`, so the gate will report them disjoint from
  PLAN-12 and be wrong in the same way it is wrong about PLAN-03. Re-check this by hand before
  emitting either while PLAN-12 runs.

- ⚠ **`api-sheriff/src/test/resources/junit-platform.properties` does not exist at HEAD.** PLAN-12
  declares it only as a HYPOTHESIS ("touched only if the measurement needs a platform setting"). If
  PLAN-12 creates it, it is a **module-wide** JUnit configuration — parallelism and execution
  semantics for every test in `api-sheriff`, including tests PLAN-06 is authoring right now. Treat
  its appearance as an epic-wide event, not a PLAN-12-local one.

- 🔑 **The single most consequential finding of 2026-09-01: the edge/tls flake is a MACHINE property,
  not a repository defect.** Zero occurrences across 100 CI runs and three green CI runs on PR #241,
  against roughly 50% locally on this workstation. ⛔ **This inverts how a local red must be read for
  every remaining plan in this epic** — PLAN-02, PLAN-03, PLAN-05 and PLAN-08 all touch the
  integration or edge surface, and a local gate red on any of them is now presumptively environmental
  until CI history says otherwise. It is captured as lesson `2026-09-01-14-002` (*"A gate red on one
  machine is not a branch defect — check CI history before attributing it"*). The shared gate was
  never unreliable; one machine is.

- 🔄 **SCOPE DECISION REVERSED 2026-09-01, by operator instruction — the macOS-loopback investigation
  IS now tracked in this epic, as PLAN-11 under the new WS-07.** The earlier call kept it out on the
  grounds that a developer-workstation hang shares no surface, no workstream and no operator-facing
  outcome with the six deployment workstreams. That argument was about the epic's CHARTER; the
  operator's instruction is about TRACKING, and the two are separable — a workstream of its own
  (WS-07, Test-Suite Reliability and Local Environment) carries the work without widening the
  deployment charter into it. ⛔ The original reasoning is retained, not deleted: WS-07 is a
  reliability workstream, and nothing in it may be read as a deployment-configurability deliverable.
  ⚠ `parallelization_scope` was raised **1 → 2** in the same act, because the ledger would otherwise
  carry two `running` plans against a knob of 1 — an internally inconsistent state. The raise records
  reality; it does not authorise a third.

- ▶ **PLAN-02 RESUMING 2026-09-01 by operator decision** — it keeps the only slot and no transition
  was recorded. The decision turned on this section's flake finding: a local red on its integration
  tasks is presumptively environmental, CI is the authority and CI is green, and the plan has already
  paid for phases 1-init through 4-plan with worktree and branch intact. Declined: parking it for
  PLAN-10 (doc-only, flake-immune), for PLAN-05 (which inherits the same local exposure), and raising
  `parallelization_scope` to 2. The idle-hold record below is kept as the state it resumes FROM.

- ✅ **RETIRED 2026-09-01 — PLAN-02 is moving again: 4 of 14 tasks done, up from 1.** The idle-hold
  concern below is spent and is kept only as the state the resume decision was made against.

- ⚠ ~~**PLAN-02 was idle ~24h at 1 of 14 tasks.**~~ `TASK-001` (*declare context-path keys and Maven
  build seam*) is `done`; `TASK-002` through `TASK-014` are all `pending`. Phase `5-execute`,
  `status.json` last written `2026-08-31T14:12:10Z`, worktree present at
  `.plan/local/worktrees/configurable-context-path` on `feature/configurable-context-path`, whose
  branch tip is still `c170779` — **no work is committed yet**. It holds the epic's only slot
  (`parallelization_scope = 1`), so nothing else can be emitted while it sits. ⚠ The hold is plausibly
  a CONSEQUENCE of the flake above — PLAN-02's remaining tasks are integration-test-heavy and the
  suite was unreliable locally — which is exactly why the machine-not-repository finding matters to
  the decision to resume it. — source: task-file read and `git log` at HEAD `0937922`.

- ⚠ **Three judgment calls from the PR #241 plan are recorded as live process signals, not as
  settled practice.** (1) `pre-push-quality-gate` was marked done over three local reds, deferring to
  CI authority — defensible given the finding above, and the exact reasoning the new lesson encodes.
  (2) A structurally-unsatisfiable freshness gate was pushed past, now filed as lesson
  `2026-09-01-14-001` (*"Freshness ledger cannot certify a tree whose gate-owned churn was correctly
  reverted"*) — ⚠ **this is the `-Ppre-commit` formatter-churn interaction this repo already knows
  about**, and it will recur on every plan in this epic that runs the gate. (3) A preference pattern
  that cleared its promotion threshold 372× was declined, because it would have taught the system
  that "build errors are accepted" from what was one operator decision batch-applied. ✅ That
  declination is correct and should not be revisited by a later pass reading only the count.

- The reported refresh exception has no error text, stack trace or log identifier. If any detail
  surfaces it materially sharpens PLAN-05. Re-check before emitting PLAN-05.

- **Four PLAN-01 lessons bypassed epic disposition.** `2026-08-29-16-001` (a contract test guarding
  files outside the build-trigger set never runs on CI), `-002` (`-T1C` flakiness; `.mvn/maven.config`
  pins `-T1`), `-003` (executor `tests_run` counts only the last module, so a zero exit can hide
  `status: error`), `-004` (derive a commit delta with `git log A..B`). All four are **live in this
  repo's lessons store and none is lost** — they simply skipped the epic inbox, so this orchestrator
  never dispositioned them. ✅ **The cause was a hand-supplied value, not a broken seam**: running
  `inbox detect` on PLAN-01's real `source_id` returns `orchestrated: true` /
  `detection: orchestrated`. **No bundle fix is owed for this item** — do not re-derive it.
  🔄 **UPDATE 2026-09-01 — `-002` was actioned and its title now carries a sharper finding.** A plan
  spawned directly FROM it (`source: lesson`, `source_id: 2026-08-29-16-002`) shipped as PR #241
  → `0937922`. The lesson was deliberately **restored to the active corpus rather than archived with
  the plan**, and now reads *"the `-T1` pin reduced but did NOT close it (3/3 red with `-T1` in
  effect, reproduced on pristine main)"* — so the `.mvn/maven.config` mitigation recorded above is
  **known insufficient**. The other three remain undispositioned and unchanged.
  ⛔ **CORRECTION 2026-09-03 — the "no bundle fix is owed" conclusion above is WITHDRAWN.** PLAN-02
  shipped with `emit-landing … skipped -- not orchestrated`, and it **is** orchestrated: its archived
  `request.md` carries the unchanged orchestrator `source_id`, and `inbox detect` on that exact value
  returns `orchestrated: true` / `detection: orchestrated`. Two occurrences now share the shape, and in
  BOTH the persisted pointer detects correctly — so the defect is **not** a hand-supplied value and
  **not** `inbox detect`. It is in whatever the finalize step actually consults, which is demonstrably
  not that seam. Tracked as its own Open Defect below. ⛔ Do not re-close this on the strength of
  another `inbox detect` run: that seam has now been shown correct twice while the skip happened anyway.
  ⛔ **That plan owed this epic no inbox message and its absence is NOT a second bypass.** It is
  lesson-sourced, not orchestrator-sourced: `inbox detect` on its `source_id` returns
  `orchestrated: false` / `detection: not_orchestrator_pointer`. Do not re-derive this as an inbox
  defect — the empty queue at that drain was the EMPTY zero, correctly.

- ⚠ **Every re-grounding verdict is now stale.** All 40 stamped claims carry `checked_at: cea163c`;
  HEAD has since moved through `909d43f` to `843a038`. Per the standard a stale verdict is **reported,
  never promoted** — it does not block emission. But PLAN-01's landing changed the container and
  compose surface materially, so **PLAN-02's claims are the ones most likely to have actually moved**
  and should be re-grounded before it is emitted.

- **Upstream: `cuioss/cui-http#165`** (OPEN, filed 2026-08-27) asks for a first-class
  `HttpHandlerBuilder.verifyHostname(false)`. **Enhancement, not a dependency** — PLAN-04 is not
  blocked on it. Every load-bearing claim in that issue was re-verified here against the implementing
  source and holds. If it lands, migrate to it and delete the local trust manager.

- **Cross-repo `cui-http` coordination (PLAN-04) — RE-AIMED 2026-08-27, not closed.** Option (a) (an
  upstream `SSLParameters` knob) is now **refuted**, so the shared change is no longer a `cui-http`
  release — it is the **delegating `X509ExtendedTrustManager` itself**. API-Sheriff PLAN-04 and
  TokenSheriff `deployment-and-refresh-gaps` PLAN-03 both land on (b′), so both will otherwise
  hand-roll the same security-sensitive class independently. That is exactly the duplication #165
  exists to end. Coordinate the implementation; whichever lands first is what upstream absorbs.
  ⛔ `corpus cross-check` cannot see this: it scans only this repository's store and reports
  `epics_scanned: 0`. The duplication is carried in prose or it is not carried at all.

- ⛔ **JDK-version bound on the mechanism settlement.** The refutation of (a) was re-verified at
  **JDK 25.0.1** (this project's compile+runtime target) after being originally read at 24.0.2 — it
  holds, at `AbstractAsyncSSLConnection:138-139`. **JDK 26 is not installed locally and is
  unverified**, and the CI matrix runs it. Re-check there before the ADR is called final.

- ✅ **Cross-repo refresh gate SATISFIED 2026-08-31.** TokenSheriff `deployment-and-refresh-gaps`
  PLAN-01 shipped (PR #672, `cd1c0365`) and its verdict is drained. Case 2a — the 2-arg
  `RefreshFlow.refresh`, the only shared surface — is **`not_reproduced`** across 12 enumerated
  conditions. ⛔ Bounded elimination, not a clean bill of health. **H4, this repository's BFF wiring,
  is now the live hypothesis** and PLAN-05 is unblocked and sharpened.

- ⛔ **Residual 4 is the live lead: discovery-resolved metadata.** Their tests bypass discovery by
  hand-assembling `ProviderMetadata`; this gateway resolves it for real at
  `BffRuntimeProducer.java:214`. The elimination does **not** cover our metadata path — it is where
  PLAN-05 should start. (Also un-eliminated and relevant: Keycloak 26.5.7 here vs 26.4.0 there, and
  server-enforced single-use refresh tokens, which neither side exercises.)

- ✅ **DPoP `cnf`-binding failure shape is NOT reachable here — do not re-derive.** The verdict flags a
  sender-constrained refresh leg failing closed with `IllegalStateException`. DPoP is not in use in
  this gateway (`BffRuntimeProducer.java:227` says so outright; the only other mention is javadoc in
  `AuthenticationStage`), so that shape cannot be the reported defect. Deprioritised unless a
  deployment enables DPoP.

- **`TokenLifecycleManager` is not on this gateway's refresh path** — it and `RefreshScheduler` appear
  nowhere in this repository. If TokenSheriff PLAN-01 tests only through that layer, its result does
  not transfer here. Watch for it in that plan's landing and push back if the direct
  `RefreshFlow.refresh(metadata, refreshToken)` case is missing.

## Resume anchor history (relocated 2026-09-11)

The `resume_anchor` field had accumulated every prior session's anchor as a chain of `PRIOR:` blocks (83572 characters). The `cleanup` pass of 2026-09-11 replaced it with a short current anchor and moved the full chain here, verbatim and unedited, so no narrative is lost. Newest first, exactly as it stood.

```text
LESSONS INTAKE COMPLETE 2026-09-11 - store EMPTY, all 27 handled (16 relocated to truthful-signals -001..009, 11 incorporated + lessons-archive/). Operator ruled: the 4 held generic lessons -> truthful-signals -009; cuioss-organization check-changes issue DRAFTED not filed (drafts/); cui-java-parent 1.7.3 -Ppre-commit still activates UpgradeToJava21 -> RenameUnderscoreIdentifier for every Java 22+ consumer without an override - cross-repo Open Defect, filing is the operator's call. 27 lessons in .plan/local/lessons-learned re-verified (API-Sheriff 428bbec, plan-marshall 356973d80), 16 clusters. 12 plan-marshall lessons RELOCATED to plan-marshall truthful-signals inbox as api-sheriff-deployment-configurability-001..008 (validated, originals verbatim) and retired here. 11 API-Sheriff lessons INCORPORATED and moved to lessons-archive/ (NOT inbox/archive - lesson filenames match the inbox message regex and would inflate inbox_archived): PLAN-23 D3 re-grounded (the lesson's 'control listener' does not exist - fix the wildcard allocation socket TlsEdgeProducerTest:282, keep the 3 collision holders wildcard; SniFrontListenerTest goes through production SniFrontListener.java:98, undeclared - stop and record if needed) + new D5 (dead assert TlsEdgeProducerTest:343-344, the only one left); PLAN-09 and PLAN-19 gained Authoring Discipline sections; Open Defects gained root-path '/' normalisation (7 ad-hoc normalisers, latent), CLAUDE.md/AGENTS.md targeted-test example without -am, stale pom.xml:201 comment; loopback entry now OWNED by PLAN-23; CI blind-spot re-verified (org filter '!.plan/**'). Corpus re-parsed clean 23/23, 0 indeterminate, 0 blocking. Memory precommit-formatter-churn corrected (resolved by #242). NEXT: operator decides whether to file the drafted org issue and a cui-java-parent issue; the standing epic-level judgement (close vs continue) below still applies. PRIOR: RELEASED AND RESTART-READY. 2026-09-11. RESTART VERDICT: ready - FIRST ready verdict this epic, 5 of 6 signals scored all ready (phase orchestrating, NO running plans, corpus 23/23, inbox 0 queued/22 archived, worktree clean), registry_parity not_available and excluded. 0.2.0 AND 0.2.1 ARE CUT AND TAGGED - the epic's deployment goal is SHIPPED. Sequence on main: 0b93499 declare 0.2.0 (#292) -> 733a4cb prepare release 0.2.0 -> e42a080 repair build-parent so the example contract survives a release tag (#293) -> e85b551 declare 0.2.1 (#294) -> a2ce218 prepare release 0.2.1 -> 428bbec next dev iteration. 0.2.1 EXISTS BECAUSE 0.2.0 NEEDED THE BUILD-PARENT REPAIR - read #293 before treating 0.2.0 as the reference cut. QUEUE 23: 14 shipped, 1 landed, 7 staged, 1 superseded. NOTHING RUNNING, three slots free. ⛔ SESSION IS NOT ON MAIN. HEAD is cc81dfd on branch chore/post-release-0.2.1, which is OPEN PR #299 - the post-release content remediation. FIVE PRs OPEN: #299, #295 (org workflows v0.26.0), dependabot #296/#297/#298. The release runbook's Step 2 requires ZERO open PRs on the dispatch path, so the tree is NOT cuttable again until these drain. Not a defect, a precondition. ⛔ BOTH RELEASES SHIPPED WITH 0.1.1-STAMPED CONTENT - the exact defect PLAN-19 exists to close, shipped twice while PLAN-19 sat staged. Verified at origin/main 428bbec: README.adoc:52 still says '0.1.1 is an ALPHA release' and :146 still says 'Verified as still open at the 0.1.1 cut'. Three further stamps were stale since 0.1.0 - fapi_status.adoc:8, fapi_next_steps.adoc:7, features-analysis.adoc:220 - i.e. through TWO releases, already recorded by the repo's own audit as DOC-8. PR #299 REMEDIATES ALL OF IT (three alpha callouts, limitations stamp, .env pin, compose VERSION-SKEW block, docker pull line, build-parent/example/pom.xml, the three stale stamps). ✅ THE GUARD IS PROVEN, ONE CUT LATE. The Step 10 enumerator rebuilt in PR #291 has a pass 3 keyed on stamp PHRASES rather than on any version, precisely because a $PREV_VERSION grep cannot see a doc that ALREADY fell behind. Pass 3 is what surfaced the 0.1.0-era FAPI stamps, and #299 carries exactly those three files. It worked on first real use - but it landed AFTER the 0.2.0 cut, and a guard authored after the act it guards cannot catch that act. RELEASE-SKILL WORK LANDED THIS SESSION: PR #290 (6ee4a55) fixed check-quarkus-alignment.py to resolve the INHERITED version.quarkus - it had returned exit 2 CANNOT DETERMINE on every run, blocking every release; verified exit 2 -> exit 0, Quarkus 3.39.2 with all io.smallrye.config at 3.17.2 unsplit. The proposal was to DELETE that check as obsolete; verification REFUTED it (cui-quarkus-parent makes plugin-vs-platform drift inexpressible, a DIFFERENT risk from the smallrye family split, and token-sheriff-bom carries a second independent version.quarkus). PR #291 rebuilt Step 10. SPECS RE-GROUNDED AGAINST origin/main THIS PASS: PLAN-19 RE-SCOPED HARD - it was written to run BEFORE a cut and two cuts happened, so its D1 is being discharged outside it by #299 and collapses to VERIFYING once #299 lands; its real remaining D1 work is the Known Limitations AUDIT, which #299 does NOT do (it re-stamps, it does not re-run the verification the section claims). D2 badges and D3 sweep unchanged and now MORE valuable. PLAN-23 census re-derived 44/207 -> 45/216 in one day, third measurement third number - do not plan against any figure in that spec. PLAN-20 premise HOLDS (BffRuntimeProducer:229 still builds ClientConfiguration with no verifyHostname/sslContext). PLAN-21 premise HOLDS (cookie_name still unvalidated at main). PLAN-22 HOLDS (doc/adr ends at 0043, next free 0044). CORPUS CLEAN: 23 rows / 23 specs, all declarative, admits_disjointness_check true, 0 indeterminate, 0 blocking verdicts. Compaction idempotent - both generated blocks unchanged, all three invariants ok, 5 sections preserved verbatim, 0 unreachable. ⚠ STILL DEFERRED: settled-narrative relocation to settled.md was PROPOSED to the operator this pass but not applied - epic.md is large and several sections' subjects are closed. Archive drain REFUSED by permanent default (no epic-wide quiescence signal exists; closed_senders is per-sender and the sender population is open). ⚠ EPIC-LEVEL JUDGEMENT OWED: the vision is shipped and released. Consider whether this epic should CLOSE, with the 7 staged specs either carried to a successor epic or dropped. That is an operator decision, not a queue mechanic. NEXT: nothing running, three slots free, restart-safe. Run corpus surfaces + cross-check before emitting. PRIOR: POST-MERGE CLEAN AT 6c1b6b6 AND ONE OF MY OWN FINDINGS RETRACTED. 2026-09-11, HEAD 7fce677, R=0 of N=3, 7 staged, nothing running, worktree clean. POST-MERGE DISCHARGED for 6c1b6b6: all four main-branch runs green - Maven Build incl deploy-snapshot, Integration Tests, DEMO CLIENT E2E 28/28, Scorecard. Operator supplied it because I CANNOT reach it: ci checks status resolves --head to a PR so a merge commit cannot be looked up. That sharpens the standing Open Defect from 'we cannot verify' to 'the abstraction lacks ONE read verb' - fixable rather than structural. Demo Client E2E is the lane PLAN-16 identified as the only one catching the cookie-deliverability class. SELF-CORRECTION, SAME DAY: MY PLAN-08 FIDELITY GAP WAS WRONG AND IS RETRACTED. api-sheriff-no-certificate is live at integration-tests/docker-compose.yml:1032 serving PLAIN HTTP on 10453 with a deliberately dead 10454 and the required management-scheme label; git log -S proves it was added by a30fe6f (PR #283, PLAN-07), so it PREDATES PLAN-08, which correctly EXTENDED it exactly as its own re-scope header instructed. PLAN-08 SHIPPED 5/5 WITH NO GAP. I had compared the diff against the spec's ORIGINAL deliverable-4 text instead of against HEAD, with the superseding re-scope header in the same document I had already read - the epic's own stale-premise class, committed by the LEDGER this time rather than by a plan. Fourth instance overall, first mine. SVG READ-BACK - BOTH THE OPERATOR AND THE LEDGER WERE RIGHT: the 2026-09-10 read-back was performed against a 10-INSTANCE diagram; 6c1b6b6 REDREW the SVG to 12 instances (adding api-sheriff-no-certificate and api-sheriff-cookie-refresh), so the verdict outlived the artifact it verified. RE-DISCHARGED AT HEAD BY ACTUAL RENDER: rsvg-convert at #ffffff and #0d1117, 1200px, BOTH PNGs viewed - no element overlap, all labels legible, theme handling correct, variant count 11+primary=12 matching source. The SAME cosmetic nit persists (mismatched-tls-backend italic subtitle touches its box's right edge, both themes). THE STANDING GAP IS NOT THE RENDER, IT IS THAT NOTHING RE-RUNS IT when the diagram is redrawn - the document's own rule predicted this. A guard belongs with whoever next owns that file. TWO DEVIATIONS ON THE RECORD. (1) #288 MERGED WITHOUT A CODERABBIT REVIEW OF THE FINAL TREE on operator authorization: one review delivered, then THREE requests acknowledged and unserviced across two PRs; #287 was closed unmerged and reopened as #288, which is the RECORDED REMEDY for this exact failure, AND IT DID NOT WORK THE SECOND TIME. coderabbit is in required_bots, so this is an authorized deviation from the project's own gate, and the final tree of a cryptographic packaging change shipped unreviewed by a bot - ADR-0043 and the tests are the compensating controls. (2) A PRUNE REPORTED FAILURE FOR AN ALREADY-COMPLETED DELETION, leaving a stale remote-tracking ref. BOTH BELONG TO ONE FAMILY WITH emit-landing AND ci pr merge-queue: a channel that cannot distinguish 'nothing to report' from 'did not run' - one reports success it did not achieve, one reports failure for work already done, and every one has cost this epic real time. FOUR INSTANCES NOW. CAPABILITY NUMBER IS HARD AND VERIFIED: three tokens seal to 2700 BYTES AGAINST 4019 - 32.8% SPARE - corroborated in PR #288's own body, and a REAL CHROMIUM confirmed the cookie is stored. That browser check is precisely the control whose absence let nine integration tests pass against a session no browser could hold. PLAN-19 D1 updated with these numbers and told to re-derive at HEAD. session.cookie_name STILL HAS NO __Host- GUARD - operator filed it as a follow-up, independently confirming PLAN-21's D1, which is now that plan's whole centre of gravity. NEXT: three slots free. PLAN-09 (scenario 7 unblocked by PLAN-08), PLAN-18 (dep discharged; disjoint from PLAN-23 and the natural pair), PLAN-20, PLAN-21 (launchable, re-scoped), PLAN-22 (ADR ordinal now 0044), PLAN-23. PLAN-19 RUNS LAST. Run corpus surfaces + cross-check before emitting; PLAN-20/22 contend with PLAN-23 on api-sheriff/src/test as a directory-vs-file collision the matcher reports as NOTHING. PRIOR: TWO PLANS SHIPPED, ONE OF THEM UNREPORTED. 2026-09-11 at HEAD 7fce677. PLAN-08 landed (PR #286 -> 7fce677, landings/PLAN-08.md) and PLAN-17 landed (PR #288 -> 6c1b6b6, landings/PLAN-17.md). R=0 of N=3 - NOTHING IS RUNNING. Queue 23: 14 shipped, 1 landed, 7 staged, 1 superseded. CRITICAL PROCESS FINDING: PLAN-17's LANDING WAS INVISIBLE FOR A DAY. emit-landing ran and returned outcome=skipped for BOTH plans (verified in each archived logs/work.log), so the inbox stayed at count 0, and the operator's paste covered PLAN-08 only. PLAN-17 was found ONLY because corroborating PLAN-08 required fetching main. Channel record is now 3 delivered / 5 skipped-at-runtime / 1 absent-from-manifest / 1 correct abstention. PLAN-08 reported 19/19 finalize steps while the channel delivered nothing. A THIRD commit also landed unobserved: PR #289 (9692f91) moved token-sheriff to released 0.9.5 and dropped the snapshot repository. THE GUARD CAUGHT THE ELEVENTH INSTANCE THE DAY IT SHIPPED - a sibling PR copied the broken QUARKUS __ spelling into a new service, merge queue went red, guard named it pre-merge. Strongest evidence this epic has produced that the stated-rule-without-a-mechanism class is closed only by a mechanism. Issue #285 RETIRED: grep -c TRUST__STORE now 0. FOUR OPEN DEFECTS RETIRED THIS PASS: issue #285; codec 4096-vs-4019; cookie-mode refresh uncovered; max_cookie_size unbounded; and the 0.9.5-SNAPSHOT auth-path defect (resolved out-of-band by #289, not by any epic plan). THREE STAGED SPECS RE-GROUNDED IN THE SAME ACT: PLAN-21 RE-SCOPED - its D2 is SUBSTANTIALLY DONE (ConfigValidator now bounds against COOKIE_VALUE_BUDGET_FLOOR/CEILING and warns on the effective budget), and its D1 is now the whole plan - verified by direct read that ConfigValidator.resolvedCookieName():1327-1332 folds blank onto default and checks NOTHING else, javadoc conceding cookie_name is an unrestricted string. FOURTH overtake of a downstream spec's premise in this epic. PLAN-21's hard dependency on PLAN-17 is DISCHARGED, so it is launchable. PLAN-22 ADR ORDINAL CORRECTED 0043 -> 0044 because PLAN-17 consumed 0043 (the CRIME/BREACH ADR) - THIRD stale-ordinal instance and the first where MY OWN spec went stale, within 24 hours of writing it, despite that spec carrying an explicit warning about this exact class. PLAN-19's D1 candidate list had TWO stale entries reconciled in place (SNAPSHOT resolved; cookie-mode-and-refresh superseded - and the replacement is NOT 'it works', it is the conditioned statement ADR-0043 and bff-cookie.adoc now support). ONE FIDELITY GAP, recorded as a Watch not a defect: PLAN-08 deliverable 4 specified a NEW integration-tests/src/main/docker/sheriff-config-plain-http/ instance; the diff creates NO such directory. The end-to-end proof landed on the EXISTING surface (NoCertificatePlainHttpOptInIT +500) and passes, so the mode is proven but has no instance of its own. PLAN-09 scenario 7 is the place to decide whether it needs one - and scenario 7 is now UNBLOCKED. TWO UNDECLARED LIVE SURFACES: doc/user/compose-sample.adoc (new, +125) and demo-client/ (PLAN-17's Playwright budget test). PLAN-09 and PLAN-19 both sweep doc/user/ and neither declares the new file - directory-vs-file blind spot arriving from the LANDING side this time. Decide ownership before the next emit. POST-MERGE: PR #286 29 checks all green; PR-attached benchmark run SUCCESS. The MAIN-BRANCH run for 7fce677 is UNREACHABLE through the sanctioned surface - ci checks status takes --pr-number or --head and BOTH resolve a branch to a PR, so --head 7fce677 returns 'no pull requests found'. Recurrence of the existing Open Defect, now measured rather than asserted. NEXT: three slots free, nothing running. PLAN-09 (scenario 7 unblocked), PLAN-18 (PLAN-16 landed so its hard dep is discharged; disjoint from PLAN-23 and they are the natural pair), PLAN-20, PLAN-21 (newly launchable), PLAN-22, PLAN-23 all staged. PLAN-19 STILL RUNS LAST. Run corpus surfaces + cross-check before emitting - PLAN-20/21/22 contend on doc/LogMessages.adoc and doc/configuration.adoc, and PLAN-20/22 contend with PLAN-23 on api-sheriff/src/test as a directory-vs-file collision the matcher reports as NOTHING. PRIOR: FOUR PLANS STAGED 2026-09-10 FROM A COVERAGE AUDIT, queue 19 -> 23. Operator asked whether all open work was incorporated into the remaining plans; audited 47 live Open Defects + 29 live Watches against the five live specs. ANSWER WAS NO. ~13 owned, ~6 documented-only by PLAN-19, ~20 unowned. NEW: PLAN-20 (WS-03, egress leg pinning + mechanical egress re-sweep + issue #269 array-key guard), PLAN-21 (WS-04, session.cookie_name + max_cookie_size validation), PLAN-22 (WS-05, trusted_proxies breadth threshold + the ADR owed since PLAN-15, ordinal 0043), PLAN-23 (WS-07, unit-lane vacuity audit + loopback residuals). FOLDED rather than staged: key-material diagram -> PLAN-09 D6; benchmarks WRK/k6 metadata drift + JFR chmod 777 + compose-sample/.env stale comment -> PLAN-19 D4/D5. CORPUS CLEAN: 23 rows / 23 specs, all declarative, admits_disjointness_check true, 0 indeterminate. VERIFY-FIRST PAID OFF FOUR TIMES THIS PASS: (1) the '29 files / 87 markers' unit-lane backlog is VACUITY-SHAPE markers per test-corpus-integrity.adoc, NOT OpenRewrite markers (0 of those in api-sheriff/src/test), and at HEAD the census is 44 files / 207 occurrences - LARGER than the ledger's 497c592 figure, recorded in PLAN-23; (2) 'ADR-0038 drafted but not landed' is FALSE - PLAN-01's preboot-probe ADR landed as 0039, 0038 is PLAN-02's carrier key; RETIRE that Open Defect; (3) ConfigValidator.java is at config/validation/ NOT config/, and gateway.schema.json is at resources/schema/ NOT resources/ - both wrong in my first draft of PLAN-21/22 and caught by corpus surfaces, the exact path-mismatch class this epic keeps hitting; (4) deployment/compose-sample/.env IS git-tracked, so the ledger's 'tooling prohibits committing .env' does not describe this repo. GATE POSTURE CHANGED FOR TWO SPECS: PLAN-09 and PLAN-19 were documentation-only and are NOT any more - PLAN-09 D6 adds an .svg (not in CLAUDE.md's doc-only enumeration) and PLAN-19 D5 touches Dockerfile.native.jfr (named gate-requiring). BOTH GATES RUN on those commits; both specs say so in place. STILL UNOWNED BY DESIGN: ~14 plan-marshall tooling/process items (emit-landing three failure modes, self-review-java vacuous green, ci pr merge-queue false enqueued, marshal.json CI blind spot) belong UPSTREAM, not in this epic. Also unowned and deliberately not staged: version.quarkus inversion unexercised, WebSocketRelayStageTest CI flake (routed into PLAN-23 as a confirm-then-route hypothesis). NOTHING EMITTED THIS PASS - the four new plans are STAGED only. R=2 of N=3 unchanged: PLAN-08 and PLAN-17 still running (both at 2-refine; PLAN-17 looped 3-outline -> 2-refine). SEQUENCING WRITTEN IN ADVANCE: PLAN-21 hard-depends on PLAN-17; PLAN-22 and PLAN-20 contend with PLAN-23 on api-sheriff/src/test (directory-vs-file, matcher will report NOTHING); PLAN-23 is disjoint from PLAN-18 and they are the natural concurrent pairing. PLAN-19 STILL RUNS LAST. PRIOR: CLEANUP DONE AND RESTART-SAFE 2026-09-10 at HEAD 990aebf, MAIN GREEN, worktree clean. RESTART VERDICT not_ready on ONE signal only - running_plans naming PLAN-08 and PLAN-17, both started this session DELIBERATELY; every other scored signal ready (phase orchestrating, corpus 19/19, inbox 0 queued/22 archived, worktree clean), registry_parity not_available and excluded, 5 of 6 scored. THIS IS THE SAME SHAPE EVERY PRIOR CLEANUP REPORTED - it is not a blocker. IN FLIGHT R=2 of N=3: PLAN-08 running (plain-HTTP instance + issue #285 fold), PLAN-17 running (cookie-mode refresh viability). Their verify-first was discharged BEFORE the transitions, which is what made the running-row exclusion harmless this pass. ONE SLOT FREE AND DELIBERATELY UNFILLED: PLAN-18 is blocked by the HIDDEN directory-vs-file collision with running PLAN-08 (ten named files inside integration-tests/.../integration/ against PLAN-08's directory declaration - cross-check reports NOTHING); PLAN-09 collides with PLAN-08 on doc/configuration.adoc + doc/user/environment-variable-overrides.adoc; PLAN-19 is HELD on a non-disjointness ground - its deliverable 3 sweeps README and doc/ for unsupported claims while PLAN-08 edits doc/, so it would review a tree that is not the one being released. PLAN-19 RUNS LAST, after the doc-changing plans land. A1 FINDINGS TO CARRY: PLAN-09's catalogue is now understated THREE ways (egress_tls; PLAN-07's plain-HTTP and outbound-trust sections; ApiSheriff-124 and the 4019-vs-4096 distinction) - re-enumerate against HEAD not against the staged catalogue. PLAN-18 must re-read the test-corpus note because PLAN-16's deliverable 5 ADDED SHAPE (e) while that spec names only (c) and (d). PLAN-19's limitations list gains two candidates from PLAN-16: session.cookie_name entirely unvalidated (dropping __Host- silently loses the no-Domain guarantee) and the codec default 4096 sitting above the browser-safe VALUE budget of 4019. ⚠ STILL DEFERRED, unchanged: settled-narrative relocation was not put to the operator this pass; the archive drain is REFUSED by permanent default (no epic-wide quiescence signal exists). PRIOR: PLAN-16 SHIPPED AND MAIN IS GREEN 2026-09-10 (PR #284 -> 990aebf, landings/PLAN-16.md). Demo Client E2E - the only lane that catches this class and which runs post-merge, unable to gate the PR - came back SUCCESS, and all four post-merge runs passed. The red PR #282 deliberately left is CLEARED. ✅ PROVEN NOT ASSERTED: with the overlay reverted, all NINE previously-blind cookie tests go RED on the new assertion (__Host-sheriff-session, 5200 bytes). The blindness is demonstrated closed, not merely fixed. assertCookiesFitBrowserBudget verified at BffKeycloakLoginFlow.java:317, measuring the EMITTED HEADER as specified. ⛔ DELIVERABLE 4 TOOK THE BRANCH I ARGUED AGAINST AND WAS RIGHT TO: it KEPT the range at 40..8192 and added ApiSheriff-124 rather than capping at 4096. PLAN-07 shipped before finalize so the doc/LogMessages.adoc collision I was fencing against evaporated, and capping would have removed a range some deployment may want in order to fix a DOCUMENTATION defect. ⛔ TWO OF THE 24 PRE-MERGE DEFECTS ARE THIS EPIC'S OWN THEME, SELF-INFLICTED: ApiSheriff-124 would have shipped comparing a VALUE budget against RFC 6265's HEADER guarantee - a stated guarantee with no mechanism, INTRODUCED BY THE DELIVERABLE WRITTEN TO CLOSE EXACTLY THAT CLASS, then hardened twice more by CodeRabbit which showed the fixed 4019 threshold still failed for non-default cookie names and TTLs; and toSetCookieHeader bounded Max-Age below but not above while its own javadoc already claimed the cap. Seventh and eighth instances, and the first two authored BY the fix for the sixth. The lesson is that this class is not eliminated by attention, only by a mechanism - which is what the reversion control provides and what these two lacked when written. ⛔ NEW REUSABLE SIGNATURE: CI CAN FAIL CLOSED ON A COVERING RUN THAT CAN NEVER APPEAR. PLAN-07 landing as #283 made PLAN-16's PR CONFLICTED, and GitHub SILENTLY REFUSES to run pull_request workflows on a conflicted PR, so the barrier waited for a run that could not exist. Rebasing fixed it instantly. DIAGNOSIS: CI stuck with NO RUN AT ALL plus PR conflicted -> REBASE, do not investigate. Distinct from the ci pr merge-queue defect (that one reports enqueued:true falsely; this reports nothing because nothing ran). EMIT ROUND: nothing running, three slots, TWO emitted - PLAN-08 and PLAN-17 (queue order, disjoint). PLAN-18 EXCLUDED by the hidden directory-vs-file collision with PLAN-08 (it declares ten named files inside integration-tests/.../integration/ while PLAN-08 declares that DIRECTORY, so cross-check reports nothing) - it goes next, after PLAN-08 lands. PLAN-19 HELD DESPITE QUALIFYING, and not for disjointness: its deliverable 3 sweeps README and doc/ for unsupported claims while PLAN-08 and PLAN-09 are actively editing doc/, so it would review a tree that is not the one being released. It runs LAST. ⚠ FOUR RESIDUALS OPENED, the sharpest first: session.cookie_name is ENTIRELY UNVALIDATED (finding 45e0ba) so dropping the __Host- prefix SILENTLY loses the no-Domain guarantee - a security property removable by a typo; the codec default 4096 sits ABOVE the browser-safe VALUE budget of 4019 (which CONFIRMS PLAN-17's own hypothesis that the limit applies to the whole Set-Cookie line - the real target is 4019, not the round number); cookie-mode REFRESH responses are not covered by the new budget assertion (CodeRabbit's sixth finding, correctly replied-to not closed - carried into PLAN-17, whose whole subject it is); and TlsEdgeProducerTest flaked three times locally never in CI (lesson 2026-09-10-09-001, fifth sighting, and the first where a guard - LoopbackEphemeralBindArchTest from #283 - already exists but does not reach the offending production fixture). PRIOR: ISSUE #285 FOLDED INTO PLAN-08 DELIVERABLE 4 2026-09-10 (operator direction), surface updated in the same act 9 -> 10 entries and verified through the parser. PLAN-08 is the only LIVE spec declaring integration-tests/docker-compose.yml. Count verified first-party: 20 lines / 10 pairs. ⛔ THE DOCUMENTATION HALF NEEDS NO REMOVAL - IT IS THE HALF THAT IS RIGHT. doc/user/environment-variable-overrides.adoc:161-166 is a WARNING block naming this exact mistake ('writing __ where a dash belongs produces a variable that resolves to nothing... silently absent rather than rejected'). THE COMPOSE FILE DOES PRECISELY WHAT THE REPO'S OWN DOCS FORBID, TEN TIMES OVER. ⛔ SETTLE DELETE-VERSUS-CORRECT FIRST: surplus -> delete; BROKEN -> the anchor was needed and has been silently absent, so CORRECT the spelling because deleting makes a real gap permanent. The doc supplies the test - the failure surfaces as an opaque handshake error against an endpoint whose anchor was never loaded; if no such error exists today they are surplus. ⛔ THIRD APPEARANCE OF THIS CONVENTION ERROR, hence the case for a GUARD not a third manual removal: the ten pairs, the gate accepting KEY__STORE (CodeRabbit, from reading SmallRye 3.17.2 sources), and PLAN-07's security-audit step INTRODUCING it into the docs before correcting it. The fold asks for a contract test or build grep refusing __ in QUARKUS_* except for quoted profile names, decision recorded either way. ⚠ SETTLE AT PLAN-16's LANDING: cross-check returns a live_plan row for PLAN-08 against running PLAN-16 on doc/configuration.adoc - the file PLAN-16's spec deliberately EXCLUDED with a stop-and-sequence instruction. Not a violation yet (references.affected_files can be predicted, and the fence's reason is gone since PLAN-07 shipped) but verify whether it was actually touched. PRIOR: PLAN-07 SHIPPED 2026-09-10 (PR #283 -> a30fe6f, landings/PLAN-07.md). WS-06 half done. ADR ordinal is now 0043 (doc/adr/ ends at 0042). IN FLIGHT R=1 of N=3: PLAN-16 running (the red-main fix). ⛔ THE BIGGEST FINDING IS SYSTEMIC AND RETROACTIVE: pre-submission-self-review RETURNS GREEN ON JAVA WHILE CHECKING NOTHING - no ext-self-review-java surfacer exists and its detectors read Python and skill docs. EVERY JAVA PLAN IN THIS EPIC PASSED THAT STEP AND EVERY ONE OF THOSE GREENS WAS VACUOUS; PLAN-07 is just where it was noticed. It retroactively weakens every 'self-review clean' line in this epic's landing records - they mean the step RAN, not that anything was checked. Belongs upstream in plan-marshall. FIFTH instance of a stated rule with no mechanism behind it. ⛔ emit-landing DID NOT RUN AT ALL for PLAN-07 - a THIRD failure mode: grep -c over the archived work.log returns 0 while every neighbouring step logs its completion line, so the step was ABSENT FROM THE COMPOSED MANIFEST rather than skipped at runtime, and the request.md carries a valid orchestrator source_id. Channel record: 3 delivered, 3 skipped-at-runtime, 1 absent-from-manifest, 1 correct abstention. The existing diagnosis (fault downstream of inbox detect) CANNOT explain a step that never entered the step list. ✅ DELIVERABLE 3 WAS INVERTED MID-FINALIZE on operator ruling and is a BREAKING CHANGE (feat(tls)!): it no longer INFERS plain-HTTP intent from a missing certificate, it REFUSES the boot unless insecure-requests=enabled is explicitly declared. That matches this epic's own repeated finding - inference from absence is how the fourth egress leg went undeclared, how the cookie ceiling looked legitimate, and how five doc sites asserted a leg that existed. ⚠ THE REVIEW CHAIN CAUGHT THREE THINGS 2029 LOCAL TESTS DID NOT, all the same family (a check passing while measuring the wrong thing): both TLS audits read only certificate.files, producing a FALSE-POSITIVE plain-HTTP warning on key-files/key-store-file deployments; the new gate accepted KEY__STORE, a spelling SmallRye does not resolve - a verdict from READING SmallRye 3.17.2 SOURCES that INVALIDATED OUR OWN EARLIER FIX, since the security-audit step had put the same wrong convention into the docs; and a test passing VACUOUSLY where an unrelated startup crash was indistinguishable from a real refusal. That third one is the same class PLAN-16 deliverable 5 was commissioned to hunt, found independently in another lane the same day. PLAN-18 STAGED from PLAN-16's deliverable-5 audit: nine pro-forma methods across eight files, exhaustive over all 56 integration-test files at 497c592. Hard-depends on PLAN-16; declares TEN NAMED FILES against PLAN-08's DIRECTORY so the matcher will report NO collision - do not run them concurrently. ⚠ The remaining corpus backlog is 29 files / 87 markers ENTIRELY inside api-sheriff/src/test/** - the integration lane is done, the unit lane is untouched. ⚠ Also open: issue #285 (ten dead QUARKUS_TLS_DEFAULT_TRUST__STORE_* pairs, correctly declined and routed out); NO scope-creep verdict for PLAN-07 - ABSENT not passed, no plan_creation_sha, SECOND occurrence after PLAN-10; marshal.json STALE 0.1.1617 vs 0.1.1632 - run /marshall-steward BETWEEN plans, since the last sync needed re-applying mid-flow; and machine-local port-collision flakes cost two full-suite re-runs, the FOURTH sighting of that class and NOT a refutation of PLAN-13 (foreign port holders, not wildcard binds). PRIOR: PLAN-16 STARTED 2026-09-09, row running (1-init, location: current, main checkout). HEAD IS 481b05f - main advanced past b5369ca by two commits (#280-ish and #281 docs). IN FLIGHT R=2 of N=3: PLAN-16 running (clears the red main), PLAN-07 running (5-execute, own worktree - the two-plans-on-main hazard is NOT live, only PLAN-16 is on main and the sole dirty file is .plan/marshal.json). ✅ VERIFY-FIRST CAUGHT A FALSE PREMISE IN THE SOURCE FINDING, NOT THE CODE: PLAN-05's message asserted 'zero hits for length/4096/budget' across the two cookie ITs; at 481b05f that grep returns 2 hits in EACH, all noise (a base64-padding javadoc at BffCookieSessionIT:278, an array index in a tamper test at :289). The FINDING is right - no cookie IT asserts the sealed value's size - but the SEARCH is not. Claim promoted HYPOTHESIS -> OBSERVED and rewritten to say look for an assertion on the emitted Set-Cookie value's SIZE, with an explicit 'do not run the bare grep'. Left alone it would have cost the plan an outline detour disproving its own brief. ⚠ STILL TRUE AND STILL GOVERNING FOR PLAN-16: deliverable 2 ALONE clears the red and nothing may delay it; deliverable 5 (the suite-wide pro-forma audit) is open-ended and the spec says SPLIT IT OUT if it grows, BEFORE it can hold up deliverable 2; doc/configuration.adoc is deliberately EXCLUDED from the surface because running PLAN-07 declares it, and if deliverable 4 needs it the instruction is to stop and sequence rather than widen; and deliverable 4's cap-at-4096 branch makes ApiSheriff-114's existing text true as written, avoiding a doc/LogMessages.adoc edit that would collide with PLAN-07. PRIOR: PLAN-17 D4 REWRITTEN as a PIPELINE + THREAT-MODEL task 2026-09-09 (operator direction). The sketched order cookie(s) -> serialize -> base64 -> compress -> encrypt has TWO MISPLACED STAGES; corrected order recorded in the spec with a reason per stage: serialize -> COMPRESS -> ENCRYPT -> base64url -> SPLIT. Compression must PRECEDE encryption (ciphertext is high-entropy, compressing after buys ~0%); base64 must FOLLOW it (transport encoding - base64 before compression inflates ~33% then compresses that inflation back out); split last over the final encoded string. ✅ INSERTION POINT LOCATED: SealedSessionPayload.java:103-105 shows today's pipeline is serialize -> seal, so compression goes BETWEEN encode() and the seal - an INSERTION, not a re-ordering. ⚠ Open claim: the field-level Base64 at :109 may sit INSIDE the sealed plaintext, inflating what compression works on. ⛔ THREAT MODEL SCOPED, NOT GESTURED AT: compress-then-encrypt is the CRIME/BREACH shape and the spec names the DISCRIMINATOR - those attacks need MANY samples with attacker-varied input, while this cookie is sealed ONCE AT LOGIN (and on refresh), not per request, so the oracle is far weaker. The spec demands stating whether that makes the risk ACCEPTABLE or merely UNLIKELY (different answers) and recording the verdict as an ADR rather than assuming it. Deliverable count held at FIVE by folding this into the packaging deliverable; a sixth would have tripped the split guard. PRIOR: PLAN-17 STAGED 2026-09-09 (WS-04) - make cookie mode viable WITH refresh, because PLAN-16 DOES NOT: its D2 sets refresh.enabled:false on the cookie overlay, which returns main to green as a DELIBERATE RETREAT, and its D4 will make the gateway REFUSE the seal rather than emit it. After PLAN-16 the honest capability statement is 'cookie mode and refresh do not work together'. FOUR ROUTES in the operator's binding order: enlarge (highly preferred, most likely refused - the limit is the browser's); SPLIT across multiple cookies (operator-raised as potentially the most elegant, circumventing rather than fighting the limit); reduce via fewer Keycloak claims (preferred reduction); package/compress (last). NOT mutually exclusive - report the combination. TWO STAGING FINDINGS: the payload is THREE JWTs with NO compression stage at all, and the ID TOKEN RIDES EVERY REQUEST FOR A LOGOUT-ONLY PURPOSE (SealedSessionPayload.java:51) - possibly a bigger win than trimming claims. ON SPLITTING: SealedSessionCookieCodec.java:70-72 calls it a deliberate non-goal, but that is a SIMPLICITY argument NOT a safety one, so reopening it is a product decision. NEW ARGUMENT the original did not weigh: the seal makes splitting FAIL-CLOSED BY CONSTRUCTION - the AEAD tag covers the whole plaintext so a missing chunk yields a failed tag, never a half-session (verify against the actual construction first). THE TRAP: every request carries all chunks, so the Cookie header grows into the gateway's pre-route header-value cap - the same declared number that broke cookie mode once before when two constants disagreed. SEQUENCED BEHIND PLAN-16 on a HARD dependency on its D3: without the deliverability assertion, a fix that still overflows is INDISTINGUISHABLE from one that does not - the exact blindness that produced this. NOT urgent; a negative verdict (cookie mode does not support refresh, server mode is the path) is a legitimate outcome. Corpus 17/17 both ways, blocking_count 0. PRIOR: PLAN-16 EXPANDED TO FIVE DELIVERABLES 2026-09-09 on operator direction (still launched, not started - re-scoping permitted). NEW D1: analyse why the cookie ITs PASSED while the cookie was being dropped - the question is not why a test failed but why NINE reported success on a session no real client could hold; confirm RestAssured's absent per-cookie limit is the WHOLE mechanism or find the rest, and record that the tests were CORRECT before PLAN-05 (cookie under 4096) and became blind only when the payload grew. NEW D5: audit EVERY integration test for PRO-FORMA tests - five named classes, method is the epic's own rule turned on tests (if the production behaviour were reverted, would this test go red?), REPORT findings outside the cookie lane rather than fixing them. ⛔ SCOPE GUARD: five deliverables is one short of the split guard and D5 is open-ended - SPLIT IT OUT if it grows, and split BEFORE it can hold up D2. DELIVERABLE 2 ALONE CLEARS THE RED MAIN and nothing may delay it. ⛔ ALSO FOLDED INTO D4 - THE ApiSheriff-114 MISMATCH, and it answers the operator's question directly: the gateway does NOT silently drop, it has a real fail-closed guard - but the guard fires at the CONFIGURED budget, not the browser's, so at max_cookie_size 8192 it EMITS a 5123-byte cookie, logs nothing, believes it succeeded, and the BROWSER drops it silently. That is the state main is in now. doc/LogMessages.adoc:70 describes ApiSheriff-114 as firing at 'the browser-safe ~4 KB budget' and refusing 'rather than emitting a value the browser would silently drop' - the log record claims a guarantee the code does not provide; the codec's own Javadoc correctly says 'configured'. DECISION INPUT: capping the validated max at 4096 makes that message TRUE AS WRITTEN with no doc edit and no collision, while the acknowledge-plus-WARN branch requires a doc/LogMessages.adoc fix that must sequence behind running PLAN-07. PRIOR: PLAN-16 STAGED AND EMITTED 2026-09-09 TO CLEAR THE RED MAIN - it carries PLAN-05's own D1/D2/D3 and OUTRANKS the queue. DELIVERABLE 1 ALONE RETURNS MAIN TO GREEN and is config-plus-docs only: set refresh.enabled: false and REMOVE max_cookie_size: 8192 from integration-tests/src/main/docker/sheriff-config-cookie/gateway.yaml, then drop the dead oidc.session.max_cookie_size row from doc/development/declared-limit-assertion-coverage.adoc and RE-CHECK THE COUNTS it disturbs (that document has already produced two stale-count review comments this cycle). Gate: -Pintegration-tests stays 130/130 AND Demo Client E2E goes green. ✅ DISJOINTNESS VERIFIED not assumed: no live_plan row against running PLAN-07, and doc/configuration.adoc was DELIBERATELY EXCLUDED from PLAN-16's surface even though max_cookie_size is documented there, because PLAN-07 declares it - the spec says stop and sequence behind PLAN-07 if deliverable 3 needs it, rather than widening mid-flight. ⚠ PLAN-16's own verification depends on a gate it CANNOT run before merging - Demo Client E2E is post-merge on main only - so it ships on the same blind spot it closes; that is what deliverable 2 fixes. ✅ PLAN-05 SHIPPED WITH REGRESSION (PR #282 -> b5369ca, landings/PLAN-05.md written). ITS REPRODUCTION SUCCEEDED and the root cause was upstream of the leeway arithmetic entirely: CallbackEndpoint never stored the refresh token, so TokenRefreshCoordinator.java:127 returned at its FIRST GUARD BEFORE ANY LOGGING - which explains BOTH halves of the original report, the silence and the IdP-revoked session still answering 200. ⚠ TokenSheriff's H4 was RIGHT and Residual 4 (discovery-resolved metadata) was NOT where it lived, though this epic carried it as the highest-priority lead. IN FLIGHT R=2 of N=3: PLAN-07 running (2-refine, main checkout), PLAN-16 launched awaiting start. PLAN-08 and PLAN-09 are unblocked by PLAN-05's ship but still collide with PLAN-07 on doc/configuration.adoc + doc/security-threat-model.adoc, and PLAN-09 hard-depends on PLAN-07. PRIOR: ⛔⛔ MAIN IS RED RIGHT NOW - FIRST ACTION, ahead of everything else (2026-09-09). Demo Client E2E has failed on EVERY main commit since b5369ca (PLAN-05's merge, PR #282) - 673e3a7 and 481b05f included - so persistent, not transient. Maven Build, Integration Tests and Scorecard are GREEN; only the browser-driving lane fails, all 7 [session-cookie] tests, while every [session-server] test passes. MECHANISM: seeding the refresh token into the session grows the cookie-mode sealed session and in cookie mode THE SESSION IS THE COOKIE - access+id+refresh seal to 5123 bytes against RFC 6265's ~4096, so Chromium DROPS the Set-Cookie SILENTLY and the SPA stays anonymous. The gateway did NOT refuse the seal because the same plan raised max_cookie_size to 8192 on the cookie overlay to get nine cookie ITs green, converting a loud fail-closed 500 (ApiSheriff-114) into a silent browser-side drop. Corroborated at sheriff-config-cookie/gateway.yaml:171-172. D1, THE FIX THAT RETURNS MAIN TO GREEN: set refresh.enabled: false and REMOVE the max_cookie_size: 8192 line so the browser-safe 4096 default applies. No cookie IT depends on refresh (only a comment at BffCookieSessionIT:154), so it costs no coverage. OWED, NOT STARTED - no plan is staged for it. ⛔ THE COOKIE ITs ARE STRUCTURALLY BLIND TO DELIVERABILITY AND THE BLINDNESS PREDATES PLAN-05: no cookie IT asserts the sealed size (zero hits for length/4096/budget), they drive RestAssured NOT a browser, and the only size test pins the codec against whatever budget is CONFIGURED. The cookie previously sat under 4096 only because the refresh token was not stored - the very bug PLAN-05 fixed was what kept the suite accidentally honest. The only gate that catches this runs POST-MERGE on main, never on the PR. Third contributor: gateway.schema.json:345-347 documents a 40..8192 range, so the ceiling sits ABOVE the browser guarantee and made 8192 look legitimate at review. ⛔ SEPARATE AND ALSO OPEN - MAIN'S GREEN BADGE IS A STALE VERDICT AGAINST A MOVING SNAPSHOT (operator observation, corroborated): maven.yml triggers on push/pull_request/merge_group/workflow_dispatch with NO schedule (grep -c 'schedule:' = 0), and version.token-sheriff is a SNAPSHOT, so main's green is never re-evaluated and the repo can become unbuildable through an upstream change alone. Observed 2026-09-09: AuthorizationCodeFlow.AuthenticationResult gained a third component upstream and two test call sites stopped compiling with ZERO API-Sheriff commits in the window; two PRs with disjoint non-Java file sets failed identically. ALREADY REPAIRED in the tree (call sites now 3-arg) but the GAP is untouched. ⛔ TWO FALSE EXPLANATIONS, recorded so they are not re-derived: the reporting job is NOT non-gating (no continue-on-error, no if: guard - only the DIRTY VERDICT is non-gating) and it does NOT run on PRs only (it runs on push to main). The gap is STALENESS, not suppression. The recurring tax is MISATTRIBUTION - the next unrelated PR author must disprove their own change. Not unique to this repo. PLAN-05 SHIPPED (PR #282 -> b5369ca, 6/6, landings/PLAN-05.md) but SHIPPED-WITH-REGRESSION: its own message 007 SUPERSEDES its clean-landing claim in 006 on that one point - the plan reported its own post-merge breakage, which is the inbox channel working as designed. Drain closed: 7 scanned, 7 archived, 0 invalid. Five candidate lessons DEFERRED as a batch behind the main-is-red work; two of them (002 'a green suite is not coverage' and 004 'fixing a vacuous-green selector obliges sweeping sibling lanes') are the direct generalisation of this very regression and should cite it when promoted. IN FLIGHT R=1 of N=3: PLAN-07 running (2-refine, main checkout). ⚠ PLAN-08 AND PLAN-09 ARE NOW BOTH UNBLOCKED BY PLAN-05's SHIP but still collide with running PLAN-07 on doc/configuration.adoc plus doc/security-threat-model.adoc, and PLAN-09 additionally hard-depends on PLAN-07 - so nothing is emittable, and the D1 fix has no plan either. PRIOR: PLAN-07 STARTED 2026-09-08, row running (2-refine, location: current). IN FLIGHT R=3 of N=3, SLOTS FULL: PLAN-05 running (5-execute, own worktree), PLAN-07 running (main checkout), and no third - PLAN-08 is blocked by a real machine-caught collision with PLAN-05 on integration-tests/docker-compose.yml and PLAN-09 by its PLAN-07 dependency plus a doc/configuration.adoc collision with it. NOTHING IS EMITTABLE. ⛔ FIRST ACTION ON RESTART - THREE UNCOMMITTED POMS SIT ON THE MAIN CHECKOUT WHILE PLAN-07 EXECUTES THERE: git status reports pom.xml, api-sheriff/pom.xml and demo-client/pom.xml modified, and the diff is a parent bump cui-quarkus-parent 1.7.0 -> 1.7.1 plus module cleanups adopting parent-supplied frontend.node.version / frontend.npm.version. THEY ARE NOT PLAN-07'S - it is at REFINE, no implementation, and its subject is TLS audits not the Node toolchain. TWO DIFFERENT CONSEQUENCES: (a) any build PLAN-07 runs on main resolves against 1.7.1, a parent NO epic plan's gate has exercised, one bump beyond the already-unexercised 1.7.0; (b) any footprint check PLAN-07 derives from git status will ATTRIBUTE THESE THREE FILES TO ITSELF - the concrete form of the shared-main hazard, a plan mistaking foreign churn for its own change set. The exposure ends when PLAN-07's worktree is cut, since git worktree add takes a clean checkout; until then re-check git status before trusting any footprint or gate result. ⚠ version.quarkus GOVERNANCE INVERTED: pom.xml:95 now records it INHERITED from cui-quarkus-parent and never declared locally, where the earlier root pom called it a project-owned pin because the parent chain declared no Quarkus version at all. Not a refutation - but the platform moved from a value this repo controlled to one a parent supplies, and PLAN-07 is the plan whose whole subject is Quarkus TLS configuration. Changed ground, read it before scoping. PRIOR: CLEANUP COMPLETE 2026-09-08 at HEAD 4863c61 (cui-quarkus-parent 1.7.0 via #274). RESTART VERDICT not_ready on ONE signal only - running_plans naming PLAN-05, running deliberately; every other scored signal ready, registry_parity not_available and excluded (5 of 6 scored). Corpus 15/15 both ways, indeterminate_count 0, blocking_count 0 over 89 claims. Compaction was a NO-OP (epic_changed false, both blocks unchanged, all three invariants ok, 5 sections preserved_verbatim, 0 unreachable). Archive drain REFUSED per the permanent default. Settled-narrative relocation DEFERRED again - not put to the operator this pass. ⛔ THE CLEANUP'S SUBSTANTIVE FINDING: TWO STAGED SPECS WERE OUTDATED BY THIS EPIC'S OWN LANDINGS. PLAN-07's premise is PARTIALLY REFUTED - 'nothing in the product says trust config REPLACES the platform CA bundle' is closed by ApiSheriff-119 (LogMessages.adoc:75) for the proxy/gRPC/WebSocket clients and ApiSheriff-120 for JWKS; its tls-package enumeration is stale (omits EgressTrustProfileResolver.java). SURVIVES: the MAIN-LISTENER audit, still absent - ManagementPlainHttpAudit remains the only *Audit class - so deliverable 1 is now PLAN-07's centre and deliverables 2/4 shrink to the uncovered remainder, chiefly the fifth unpinned BffRuntimeProducer:208 leg. Stamped contradicted|4863c61|rescoped:yes and re-scoped in place. ⚠ PLAN-09 IS UNDERSTATED: it mentions egress_tls ZERO times, staged before that surface existed, while two ADRs and three boot records landed since. Its deliverable 1 already licenses re-enumeration so it took a note, not a re-scope; the fold adds NO file surface (new scenarios land in doc/user/, already declared). ⚠ NO REGROUPING APPLIED - the 2026-09-04 arithmetic still governs: PLAN-07/08/09 carry five deliverables each, any merge yields ten against a guard firing at six. Do not redo that review. PRIOR: PLAN-04 SHIPPED AND DRAINED 2026-09-08 (PR #272 -> 054b3e4, landings/PLAN-04.md). WS-03 IS COMPLETE. ⚠ HEAD IS ALREADY PAST IT at cb60b24 'chore: adopt cui-quarkus-parent 1.7.0' - a PARENT MIGRATION no epic plan's gate has exercised; treat a gate red on the next plan as suspect against that bump first. ⛔ I CORRECTED MY OWN STAMP: claim 5's verdict contradicted STANDS, its evidence did not. My 2026-09-07 re-scope credited cui-http 3.0's HttpHandlerBuilder#verifyHostname - REAL but UNREACHABLE from this gateway, which never holds such a builder. The operative mechanism is TOKEN-SHERIFF's OWN passthrough from 0.9.5. Verified first-party: TokenValidatorProducer.java:29 imports HttpJwksLoaderConfig from de.cuioss.sheriff.token.commons.transport, :266 calls .verifyHostname(...), :271 calls sslContext(...), pom.xml:87 is 0.9.5-SNAPSHOT. Re-stamped at 054b3e4 - a sibling plan reasoning from the old stamp would have gone to the wrong library. WHAT SURVIVED: the sslContext collision was mine and was right; PLAN-04 settled it as option (i), refused at boot with GatewayException(CONFIG_INVALID); ADR-0041 records (ii) and (iii) rejected. IN FLIGHT R=2 of N=3: PLAN-05 running (refresh coverage), PLAN-07 EMITTED and auto-marked launched. ⚠ PLAN-07's ADR ordinal is 0042 and its subject sits beside the new fifth-egress-leg defect - read that first. ONE SLOT IS DELIBERATELY UNFILLED: PLAN-08 is blocked by a REAL machine-caught live_plan collision with running PLAN-05 on integration-tests/docker-compose.yml (a checked collision, notable after three blind ones), and PLAN-09 is blocked by its PLAN-07 dependency AND a doc/configuration.adoc collision with it. ⛔ TWO SERIOUS NEW DEFECTS: (1) A FIFTH unpinned outbound https leg - BffRuntimeProducer:208 builds ClientConfiguration with neither verifyHostname nor sslContext, dialled by DiscoveryResolver/TokenEndpointClient/RefreshFlow and carrying the client_secret, secure only by an upstream default - the EXACT reliance TokenValidatorProducer refuses for the lower-value JWKS leg. This is the SECOND fifth-leg finding in two landings, so the egress inventory has been wrong twice running: stop accepting 'the egress paths are enumerated' without a fresh sweep. Wants its own WS-03 successor plan. (2) 0.9.5-SNAPSHOT sits on the authentication path of every build from main and NOTHING IN THE BUILD REFUSES a release that still resolves a SNAPSHOT - the condition is prose. Third instance in this epic of a stated rule with no mechanism behind it. ⚠ Also: RotationResult widened 5->7 in 0.9.5 exposing a scopeDelta nothing reads, so a narrowed scope on refresh is UNOBSERVED - directly relevant to RUNNING PLAN-05; and the JWKS knob has no deployment-activation guard, unit-proven only by operator decision. ✅ emit-landing WORKED and the plan CAUGHT ITS OWN FALSE GREEN - marked done before emitting, caught it, then landing-check failed the message on EIGHT missing keys until amended (revision=1). Channel record 3 delivered / 3 skipped / 1 correct abstention - still intermittent, still no discriminator. PRIOR: PLAN-05 STARTED 2026-09-07 - THE PHANTOM IS OVER, closed by a real start rather than a release. IN FLIGHT R=3 of N=3, SLOTS FULL: PLAN-04 running (jwks-hostname-verification, 1-init), PLAN-05 running. Verify-first discharged before the transition: accessTokenLifespan 900 confirmed in integration-realm.json:6 AND benchmark-realm.json:6, the conceding comment intact at BffSessionMediationIT.java:103-105, TokenRefreshCoordinator at bff/refresh/...:73. ⚠ ONE CITATION WAS STALE AND WAS CORRECTED IN THE SAME ACT - the discovery seam is BffRuntimeProducer.java:213 not :214, which is the line MY OWN prior-art fold wrote hours earlier; fixed in two places. Second time in two days a citation I authored was born stale. ⛔ FIRST ACTION ON RESTART - TWO PLANS ARE EXECUTING ON THE MAIN CHECKOUT AT ONCE, the recorded 2026-09-03 hazard returning: manage-status list shows PLAN-04 at 1-init with location: current and PLAN-05 starting into the same checkout, neither worktree cut yet because the lifecycle creates them later. Their DECLARED work is disjoint (auth/ vs integration-tests/) so the risk is NOT code collision - it is .plan/ state and uncommitted tree churn while they share one working directory. RE-CHECK git status after any tool run touching .plan/. Also expect verify-budget contention: this epic measured local runs clipped at wall-clock under a concurrent plan at load 150-200, and PLAN-05 runs the Docker -Pintegration-tests suite, the heaviest thing in the repo. NOTHING IS EMITTABLE: slots are full, and the three staged plans (PLAN-07, PLAN-08, PLAN-09) all collide with running PLAN-04 on doc/configuration.adoc plus doc/security-threat-model.adoc, and PLAN-09 additionally hard-depends on PLAN-07. PRIOR: PLAN-04 STARTED and PLAN-05 RE-EMITTED 2026-09-07. HEAD IS 5467a80 (#270 steward reconcile, #271 review_rate_window_await disabled - both config-only). IN FLIGHT R=2 of N=3: PLAN-04 running, PLAN-05 launched awaiting an operator start. ✅ REQUIRED_BOTS WAS NEVER BROKEN AND THIS LEDGER WAS WRONG FOR FOUR LANDINGS - DEFECT RETIRED, DO NOT RE-OPEN. automatic-review/standards/cuioss-review-bot.md:56 declares bot_kind: cuioss-review-bot; upstream cc5ea40a1 (2026-09-03) renamed pr-agent.md to cuioss-review-bot.md and no pr-agent.md remains. API-Sheriff's 1c7308c (2026-09-02) tracked that rename A DAY EARLY, so the config was correct from 09-03 onward and PLAN-10's 2026-09-04 claim that 'no cuioss-review-bot.md registry doc exists' was ALREADY FALSE WHEN WRITTEN. CLAUDE.md line 221 aligning to coderabbit,cuioss-review-bot is the CORRECT direction. ⛔ ROOT CAUSE ON THE ORCHESTRATOR SIDE, the durable half: the claim was an asserted ABSENCE relayed from a plan's inbox message and recorded WITHOUT opening the registry, then repeated across four landings and put to the operator as a decision three times. The epic's own verify-first contract says an asserted absence carries the same obligation as a presence and is the higher-risk half - and PLAN-03's landing, written up in the same session, is the identical failure (five doc sites asserting a fourth egress leg did not exist). RULE: a claim relayed from a plan's message is a LEAD, not a fact, INCLUDING when it is precise, mechanism-level and internally coherent - this one was all three. Lesson 2026-09-02-22-003 corrected accordingly; its re_review_on_loopback and pr-agent.yml halves STAND. PRIOR: PLAN-03 SHIPPED 2026-09-07 (PR #268 -> a8c9834, landings/PLAN-03.md). HEAD IS a8c9834, main clean, no worktrees. WS-03 half done. ⛔ THE BIGGEST CONSEQUENCE IS DOWNSTREAM: PLAN-04'S MECHANISM IS REFUTED AND THE SPEC IS RE-SCOPED. PLAN-03 carried <version.cui.http>3.0</version.cui.http> (deliverable 7, the fold, shipped exactly as specified), and cui-http 3.0's sources carry HttpHandlerBuilder#verifyHostname(boolean) default true - read at ~/.m2/repository/de/cuioss/cui-http/3.0/cui-http-3.0-sources.jar, HttpHandler.java 'Hostname verification' - skipping ONLY the SAN/CN match while chain trust, validity and algorithm constraints stay enforced, WARN HTTP-116 per relaxed handler. cuioss/cui-http#165 LANDED. Claim 5 stamped contradicted | a8c9834 | rescoped: yes. ✅ THE HAND-ROLLED X509ExtendedTrustManager IS RETIRED and the TokenSheriff coordination is DISSOLVED - neither repo needs that class now. Do not re-open it, do not wait on it. ⛔ THE SAME READ FOUND PLAN-04'S NEW CENTRAL QUESTION: combining verifyHostname(false) with a CALLER-SUPPLIED sslContext is REJECTED at build() with IllegalArgumentException, and JwksTrustProfileResolver.resolve(:119) returns exactly such a context for any issuer declaring jwks.tls_profile. The two features are MUTUALLY EXCLUSIVE as the API stands, and the corporate-internal-CA deployment most likely to need relaxation is precisely the one that supplies a profile. Three candidate resolutions are recorded in the spec's new opening block - settle at outline. PLAN-04 EMITTED and auto-marked launched. ⚠ ADR ordinal is 0041 (PLAN-03 took 0040); PLAN-07 also declares doc/adr/ and must re-resolve too. ✅ PLAN-05 IS NOW CLEAR TO RUN - the blocker I named was PLAN-03 and it has landed; it is disjoint from PLAN-04 on every dimension including the hidden one (PLAN-04 declares test/.../auth/, nowhere near integration-tests/). ⛔ BUT IT HAS HAD NO PLAN RECORD SINCE 2026-09-04, three days - the phantom watch's SECOND occurrence. Start it or release it. ⛔ emit-landing SKIPPED AGAIN - THIRD OCCURRENCE - and the two successes did NOT mean it was fixed. work.log:457 records outcome=skipped while request.md:7 carries a valid orchestrator source_id that inbox detect classifies orchestrated:true. NEW EVIDENCE: all four orchestrated plans have BYTE-IDENTICAL pointer shapes and two succeeded (PLAN-10, PLAN-15) while two skipped (PLAN-02, PLAN-03), so the source_id is NOT the discriminator and the fault is non-deterministic w.r.t. everything in the plan record. The skip-success-success-skip ordering also admits a REGRESSION between 2026-09-05 and 2026-09-07, the plan-marshall upgrade window - look there first. RULE UPGRADED FROM CAUTION TO FACT: an empty drain means nothing was SENT, and the channel is demonstrably INTERMITTENT, so a delivered landing is no evidence the next will arrive. ⚠ PLAN-03's security audit REFUTED AN ASSERTED ABSENCE: a FOURTH https egress leg (UpstreamAssetSource's JDK client) that FIVE documentation sites claimed did not exist. ADR-0040 records the SSLParameters seam as out of scope. ⚠ CodeRabbit caught the control proving NOTHING - the TLS backends' healthcheck ran only nginx -t, so a REFUSED connection would have satisfied the verify-on leg's expected 502 for the wrong reason; fixed with a real TCP probe. ⚠ Also open: issue #269 (configuration.adoc's array-key inventory claims a guard it does not have - lesson 2026-09-02-22-002 recurring in DOCS), the topology SVG read-back (geometry placed by coordinate arithmetic with no renderer; WARNING block sits in the doc), and TlsEdgeProducerTest + SniFrontListenerTest as RESIDUAL loopback sites (each failed once locally on port-collision preconditions under load 119, passed on re-run and in CI - the contended-verify-budget defect meeting the loopback defect, NOT a refutation of PLAN-13). PRIOR: PLAN-03 STARTED (operator-confirmed) 2026-09-06, row launched -> running. No plan record at the transition (only NO_PLAN) - expected pre-phase-1-init, same shape PLAN-10 and PLAN-15 showed. ✅ VERIFY-FIRST DISCHARGED BEFORE THE TRANSITION AND IT CAUGHT FOUR STALE CITATIONS, corrected in the same act because `running` bars re-scoping: DispatchStage :244->:243, WebSocketRelayStage :148->:147, GatewayEdgeRoute :1295-1297->:1294-1296, and the tripwire trio :513/:515/:518 -> :519/:521/:524. ⛔ EVERY CLAIM'S SUBSTANCE HELD - setVerifyHost still absent repo-wide, all three binding sites present with identical code, tripwire intact, builderSeededFrom still 24 .component(preset.component()) calls COUNTED AT HEAD not carried over, cui-http 2.2 resolved and 3.0 present in ~/.m2. Five claims stamped corroborated at 3fc4c83; blocking_count 0. ⚠ THE INSTRUCTIVE ONE: PLAN-13's rewrite of GatewayEdgeRouteTest.java displaced a citation that deliverable 6 - authored AFTER PLAN-13 merged but from a read taken BEFORE it - still pointed at. A citation from a pre-merge read is stale the moment that merge lands, even when the authoring session felt current. IN FLIGHT R=2 of N=3: PLAN-03 running, PLAN-05 launched. ⛔ PLAN-05'S PHANTOM WATCH IS NOW OVERDUE - re-emitted 2026-09-04, still NO plan record two days later, which is the exact recurrence the watch was written to catch. RELEASE IT to staged rather than letting it hold a third of capacity again. ONE SLOT FREE and the emittable set is PLAN-07 or PLAN-08 (both unblocked by PLAN-13, both prep-ready) - but they collide with each other on tls/ + application.properties + both docs, and BOTH collide with running PLAN-03 on doc/configuration.adoc + doc/security-threat-model.adoc, so NOTHING is emittable while PLAN-03 runs. PRIOR: CUI-HTTP 3.0 FOLDED INTO PLAN-03 AS DELIVERABLE 6 (operator decision 2026-09-06, 'API updates are part of it'), then RE-SCOPED the same day on the operator's improvement to DECOUPLE FROM THE PARENT: deliverable 6 now sets <version.cui.http>3.0</version.cui.http> in this project's pom properties instead of bumping cui-java-parent to 1.6.3. ⛔ THE AVAILABILITY GATE IS GONE - cui-http 3.0 is ALREADY in ~/.m2/repository/de/cuioss/cui-http/ so it resolves today, while 1.6.3 stays unreleased. The seam: cui-java-bom-1.6.2.pom:24 declares version.cui.http=2.2 and :73-74 manages the artifact through it. Idiomatic here - pom.xml already carries version.quarkus, version.token-sheriff, version.json-schema-validator. Blast radius is now ONE artifact instead of everything 1.6.3 carries. ⚠ IT IS AN OVERRIDE, NOT A PIN, AND IT HAS A REMOVAL CONDITION: the root pom's comment distinguishes a pin (parent declares nothing) from an override (parent declares a value); version.cui.http is the latter. DELETE IT once the resolved parent manages cui-http at >= 3.0, or it silently holds the artifact at 3.0 past a future 3.1. PLAN-03 was launched-not-running so re-scoping was permitted. Surface updated IN THE SAME ACT and VERIFIED: 9 -> 12 resolved entries (+pom.xml, +SecurityConfigurations.java, +GatewayEdgeRouteTest.java); no live collision - the only cross-check rows name PLAN-06 and PLAN-13, both terminal. ⛔ THE FIRST DECLARATION ATTEMPT WAS SILENTLY INVISIBLE: entries authored under a ### subheading, parser stops at the first one, row still read 9. Moved into the main bullet list. FIFTH instance of this epic's surface defect and the FIRST caused by AUTHORING POSITION rather than wrong content - a correct declaration in the wrong place gates nothing. Never author surface entries below a ### in a spec. ⚠ TWO GATES ON DELIVERABLE 6, both written into the spec: (a) AVAILABILITY - 1.6.3 DOES NOT EXIST (~/.m2 tops at 1.6.2; cuioss-parent-pom is 1.6-SNAPSHOT with 6907211 'chore: update cui-http from 2.2 to 3.0 (#1433)' unreleased on main). If still unresolvable at outline, DROP deliverable 6 and ship 1-5, recording availability not scope. (b) The component count 26 is SECOND-HAND, relayed from another repo's build failure and never read here against a 3.0 artifact - verify it, do not code against it; if growth is not two components, SPLIT rather than absorb. PLAN-03 now carries SIX deliverables, tripping the split guard; proceeding unsplit is deliberate with the rationale recorded in the spec (operator decision; cannot be split out because the edits must be atomic with the bump - the tripwire asserts equality so no ordering is green in between; and it adds one bounded mechanical deliverable). ✅ PLAN-03 NOW SETTLES THE VERSION PLAN-04'S CLAIM 11 IS PINNED TO, so the re-grounding trigger MOVES from 'when the 1.6.3 bump arrives' to 'when PLAN-03 lands' - re-read HttpHandlerBuilder before PLAN-04 is emitted; if 3.0 carries cui-http#165's knob, PLAN-04 deletes its hand-rolled X509ExtendedTrustManager and the TokenSheriff coordination dissolves. The pre-diagnosed 1.6.3 defect is OWNED now, not unowned. PRIOR: ⛔ NEW, HIGH-VALUE (2026-09-06): cui-http 3.0 MAY REFUTE PLAN-04'S MECHANISM - re-ground its claim 11 WHEN THE 1.6.3 BUMP ARRIVES, before PLAN-04 is emitted. PLAN-03 does NOT incorporate the cui-http update (declares no pom.xml, mentions cui-http nowhere, uses Vert.x setVerifyHost - an unrelated mechanism), but PLAN-04's claim 11 ('the resolved cui-http HttpHandlerBuilder exposes no hostname-verification method') is corroborated at checked_at c6e6f52 with an evidence line that states the reading is VERSION-PINNED to the resolved 2.1.0 artifact. That claim is what selects mechanism (b') - hand-roll a delegating X509ExtendedTrustManager because 'no cui-http release is required'. cuioss/cui-http#165 requests exactly the knob whose absence claim 11 asserts; if 3.0 carries it, PLAN-04 migrates to it, DELETES the local trust manager, and the TokenSheriff coordination dissolves. CHECKED: local /Users/oliver/git/cui-http is 2.3-SNAPSHOT (main 6c09375) and neither verifyHostname nor setEndpointIdentificationAlgorithm appears in its main source, so #165 has NOT landed as of 2.3. NOT CHECKED: 3.0 is not in that checkout and its API is UNVERIFIED from here - an unchecked negative, do NOT read 'absent at 2.3-SNAPSHOT' as 'absent at 3.0'. The claim is deliberately NOT re-stamped: at HEAD the resolved version is still 2.2 so claim 11 is true right now; contradicted would manufacture a refutation and unverifiable would misdescribe a check that is not due yet. The trigger is the BUMP, not PLAN-04's outline. PRIOR: PLAN-13 SHIPPED 2026-09-06 (PR #255 -> 3fc4c83, squash via merge queue, landings/PLAN-13.md). HEAD IS 3fc4c83, main clean, no worktrees. WS-07 IS NOW COMPLETE. ⭐ THE POST-MERGE MAIN-RUN OBLIGATION WAS DISCHARGED FOR THE FIRST TIME - all three runs completed success - BY THE OPERATOR, NOT BY NEW TOOLING. The ci abstraction gap is UNCHANGED (every verb PR-keyed, no verb reads a push run by commit) and #254 and #257 remain unverified leads. ✅ THE EMPTY INBOX IS THE CORRECT EMPTY AND IT IS PROVEN, NOT ASSUMED: PLAN-13's archived request.md carries source: description / source_id: none and inbox detect returns orchestrated:false / not_orchestrator_pointer, so it OWED this epic no message - same shape as PLAN-11. This is NOT evidence the emit-landing bundle defect is fixed; the channel's record is two skips (PLAN-01/02), two successes (PLAN-10/15), one correct abstention (PLAN-13). ⛔ THE EPIC'S REAL BOTTLENECK IS NOW MEASURED AND IT IS NOT PLAN-13: its merge unblocked THREE plans (PLAN-03/07/08, each blocked solely by a gate blind spot against it) and freed a slot leaving N-R=2, but ONLY ONE could be emitted because all three collide PAIRWISE - PLAN-03 x PLAN-07 on doc/configuration.adoc + doc/security-threat-model.adoc, PLAN-03 x PLAN-08 on those plus integration-tests/.../integration/, PLAN-07 x PLAN-08 on tls/ + application.properties + both docs. doc/configuration.adoc is declared by NINE of fifteen specs and serializes almost the whole remaining epic - a DOCUMENTATION file. ⛔ RAISING parallelization_scope CANNOT HELP: the knob caps concurrency, disjointness decides eligibility, and a slot goes unfilled rather than filled with a collider. Do not re-diagnose an idle slot as a knob problem. PLAN-03 EMITTED and auto-marked launched - queue order, and also the highest-value pick since it is the chain head PLAN-04 hard-depends on. ⚠ CARRY THE cui-http HAZARD INTO IT: PLAN-03 declares config/ as a DIRECTORY, which contains SecurityConfigurations.java - the file the pre-diagnosed parent-pom 1.6.3 break must edit; an automated 1.6.3 bump arriving mid-run collides there. IN FLIGHT R=2 of N=3: PLAN-05 launched (STILL no plan record - the phantom watch is now overdue, consider releasing it again), PLAN-03 launched. ⛔ TWO NEW DEFECTS: (1) ci pr merge-queue returns enqueued:true while the queue stays empty - its corroboration attests the BRANCH has a queue rule, not that THIS PR entered, so it passes exactly when it should fail; cost 30 minutes on PLAN-13; ci pr auto-merge is the working path; filed as lesson 2026-09-06-01-001. (2) Two commits pushed without a COMPLETE local verify - runs clipped at wall-clock budget by a concurrent plan-marshall run driving load to 150-200, NOT stalled (zero errors, changed tests green at the cut); -Ppre-commit completed green on the final round and CI gated on JDK 25 AND 26 so nothing shipped unverified, but the local half of the documented process was not completed and the cause is STRUCTURAL: parallelization_scope=3 makes contended verify budgets the design, not an accident. 🔄 THIS ORCHESTRATOR'S STORE-BOUNDARY RULE WAS TOO STRICT AND IS CORRECTED: PLAN-13 filed 2026-09-06-01-001 against the FOREIGN bundle plan-marshall:tools-integration-ci in THIS store - the exact filing I refused twice. The plan was right; the deciding question is whether THIS repo repeatedly pays the cost, not which bundle owns the remedy, because the store is CWD-keyed and a lesson filed elsewhere is invisible to the plans that keep hitting it. The light-lane candidate I discarded 2026-09-05 is PROMOTED RETROACTIVELY as 2026-09-06-07-001 carrying its own provenance. Narrower surviving rule: discard a foreign observation only when it carries NO local cost AND no local actionable - PLAN-10's scope-sensor candidate still meets that and stays discarded. ✅ TWO GATE BLIND SPOTS RETIRED AS LIVE HAZARDS (tls/ directory-vs-file, production/test-pair) - both were against PLAN-13, now terminal. RETIRED AS HAZARDS, NOT AS KNOWLEDGE: both remain true of the matcher and recur against the next running plan. PRIOR: CROSS-REPO DATA POINT ABSORBED 2026-09-05 (paste, observation granularity - no ship semantics, no queue transition). ⛔ HIGHEST-VALUE ITEM: cuioss-parent-pom 1.6.3 WILL BREAK THIS REPO AND THE FIX CANNOT BE PRE-APPLIED. Corroborated first-party at 558a38b, every element: the tripwire is GatewayEdgeRouteTest.java:513 tripwiresOnSecurityConfigurationComponentDrift, it hard-codes copiedByBuilderSeededFrom=24 at :515 and compares it to SecurityConfiguration.class.getRecordComponents().length at :518; SecurityConfigurations.java:55-79 really does make 24 .component(preset.component()) calls; dependency:tree resolves de.cuioss:cui-http:jar:2.2 (compile AND generators), so it passes today; pom.xml:7 is cui-java-parent 1.6.2. 1.6.3 pulls cui-http 3.0, which grows the record 24 -> 26. ⛔ THE ASSERTION IS AN EQUALITY, SO RAISING THE CONSTANT TO 26 NOW GOES RED IMMEDIATELY AT 2.2 - there is NO 'fix it ahead' option and any plan staged to do so breaks main on its own merge. The three edits belong in the SAME COMMIT as the version bump: add two .component() calls at SecurityConfigurations.java:55-79, change 24 -> 26 at GatewayEdgeRouteTest.java:515, and fix the 'other twenty-three' Javadoc at SecurityConfigurations.java:47. ⚠ TWO COLLISIONS IF EVER STAGED: SecurityConfigurations.java sits inside config/, which PLAN-03 declares as a DIRECTORY; GatewayEdgeRouteTest.java is PLAN-13's surface, open and green at PR #255. ✅ EXPECTED ARRIVAL IS AN AUTOMATED PR that goes red on this one test - nine dependency/steward PRs landed here in one window on 2026-09-04. The tripwire is DOING ITS JOB (a dropped component silently reverts to the defaults() policy - a real posture regression with no other failing test); do not 'fix' it by deriving the count, which would defeat the guard. ✅ THIS IS EXACTLY WHY THE PARENT-POM DISCHARGE WAS SCOPED TO 1.6.2 - the scoping was written one message before this data point arrived and it held. ⚠ Second finding FOLDED into the existing WebSocketRelayStageTest defect as a recurrence, not filed twice: a cross-repo run saw 5023ms against a 5000ms ceiling under parallel-build load - a DIFFERENT symptom (fixed-await overshoot, the PLAN-11 class) on the same file, still not fixed by PLAN-13's listen(0) work. Evidence now spans two repos and two symptoms. PRIOR: PLAN-15 SHIPPED AND ITS INBOX DRAINED 2026-09-05 (PR #267 -> 558a38b, landings/PLAN-15.md). HEAD IS 558a38b, main clean. Second consecutive inbox-delivered landing, second complete:true. Drain closed: 5 scanned, 5 archived, 0 invalid; inbox is the EMPTY zero. ⛔ FIRST ACTION ON RESTART - PLAN-13 IS GREEN, MERGEABLE AND NOT MERGING, AND IT BLOCKS FOUR PLANS: PR #255 is open / mergeable / merge_state clean / not draft with ALL 29 CHECKS SUCCESS, plan record stuck at 6-finalize since 2026-09-04T23:42Z, branch 35 commits ahead and not rebased onto 558a38b. review_decision is none - CHECK WHETHER A REQUIRED-BOT PARTICIPATION BARRIER IS HOLDING IT, that being the failure this epic has paid for three landings running. ONE MERGE UNBLOCKS THE WHOLE REMAINING QUEUE (PLAN-03/07/08 are blocked only by gate blind spots AGAINST PLAN-13; PLAN-04 and PLAN-09 sit behind those in hard-dependency chains). IN FLIGHT R=2 of N=3, ONE SLOT FREE AND NOTHING ELIGIBLE TO FILL IT: PLAN-05 launched (watch the phantom recurrence - still no plan record), PLAN-13 running. ⛔ AN EPIC DECISION IS OWED: PLAN-15's adr-propose scanned all 39 corpus ADRs and found NO coverage of trusted_proxies breadth or the warn-vs-reject threshold; it declined the ADR for that PR and the decline was CORRECT (declared scope was met, and adding a file post-review would have re-staled both required bots on a PR whose review was already the long pole). The draft is preserved in that plan's decision log. Ordinal 0040 is free. A doc-only ADR plan has NO dependencies and would be immediately emittable into the free slot - but it is the same one-deliverable shape the operator questioned for PLAN-14, so ASK before staging it. ✅ THE PARENT-POM WATCH IS DISCHARGED BY EVIDENCE, not stale: PLAN-15's gate ran GREEN on cui-java-parent 1.6.2 (sync-baseline rebased over 3fca05c) executing 202 + 1926 tests, so the -Werror risk across 1.5.11 -> 1.6.1 -> 1.6.2 was exercised and passed. Discharge is SPECIFIC TO 1.6.2 - do not re-open for the next bump, do not extend it to one. ✅ PLAN-15 SHIPPED THE THRESHOLDS AND ERADICATED THE BAD CLAIM: BROAD_PREFIX_IPV4 8->16 and IPV6 32->48 verified by direct read at ConfigValidator.java:129-130, so 172.16.0.0/12, 10.0.0.0/8 and 2001:db8::/32 now warn; IPv4 /16 and IPv6 /48 stay un-warned as a DECLARED reasoned residual; five parameterized controls pin both constants. A repo-wide grep for 'must override the image' across api-sheriff/src and doc/ returns ZERO - the unreachable HEALTHCHECK remedy is eradicated at all six sites, not merely reduced. ⚠ ISSUE #256 IS RESOLVED AND SHOULD BE CLOSED - deliberately NOT actioned because closing an issue is outward-facing and was not asked for. ⚠ FOURTH UNDER-DECLARATION EVENT, BUT THE FIRST THE SPEC HANDLED ITSELF: PLAN-15 declared the HEALTHCHECK claim at 2 sites with a HYPOTHESIS of a third; verification found SIX. The spec's OWN instruction ('a third site joins deliverable 4 rather than becoming another plan') fired and was followed - all six fixed, nothing orphaned. All FIVE declared entries realized, a first for this epic. Surface corrected to the realized 10 files anyway. The directory-entry warning I wrote into that spec PAID OFF: the test/.../config/validation/ entry resolved INSIDE the declared directory, no drift, unlike PLAN-06. ⚠ Lessons this drain wrote: NEW 2026-09-05-07-001 (findings-triage) 'an execution-log error row can name the wrong culprit - a zero-token zero-tool row with an impossible duration is a lost return path'; FOLDED into 2026-09-04-07-001 the self-contradicting-paragraph recurrence; FOLDED into 2026-09-02-22-003 the THIRD review-bot occurrence (this one LOCAL config: re_review_on_loopback:false at marshal.json:108 plus pr-agent.yml's trigger set, so both required bots are silent after a fix push for two different legitimate reasons and both need separate manual acts). Candidate 003 (light-lane envelope skipped pr_title and left 2-refine in_progress) was DISCARDED on the store-boundary ground applied evenly with PLAN-10's candidate 001, preserved in Watches, and is WORTH REPORTING UPSTREAM. ⛔ required_bots STILL BROKEN at .plan/marshal.json:115 - THREE landings have now paid for it. PRIOR: PLAN-15 STARTED (operator-confirmed) 2026-09-04, row launched -> running. ⚠ No plan record observable at the transition (only NO_PLAN and PLAN-13) - expected pre-phase-1-init, same shape PLAN-10 showed before registering normally; discriminator is elapsed time, re-verify next check. ⛔ HEAD HAS MOVED NINE COMMITS since PLAN-10: 337af0d -> eedfda6 (#258 cui-java-parent 1.5.11->1.6.1, #234/#235/#260/#261/#262/#263/#264 dependency and Docker base-image digest bumps, #265 chore). NONE is an epic plan - PLAN-13 has STILL not shipped, still 6-finalize in its worktree (tip e47779b) and NOT rebased onto eedfda6. ✅ PLAN-15's verify-first clause was DISCHARGED BEFORE the start, not deferred: claims 0, 3 and 4 re-read at eedfda6 and stamped corroborated via corpus set-verdict. ONE was genuinely at risk and was re-read rather than assumed - PR #262 bumped quarkus-distroless-image 5d8bc90 -> ae97db1 directly under the 'image ships no other executable' half of claim 4; re-read at Dockerfile.native:26/:49/:61 and the claim holds (a distroless digest bump adds no shell). BROAD_PREFIX_IPV4=8 / IPV6=32 unchanged at ConfigValidator.java:129/:130; HealthProbe.java:37 still carries the wrong claim. ⚠ NEW WATCH - THE PARENT POM BUMP IS UNEXERCISED: cui-java-parent 1.6.1 landed while no epic plan was executing, the reactor compiles with -Werror (failOnWarning + showDeprecation), so a newly-deprecated API becomes a BUILD FAILURE and the first plan to run the gate discovers it. PLAN-15 is that plan. A gate red on PLAN-15 must be attributed against this bump BEFORE its own two-file change. PRIOR: PLAN-10 SHIPPED AND ITS INBOX DRAINED 2026-09-04 ~07:30 (PR #257 -> 337af0d, landings/PLAN-10.md). HEAD IS 337af0d, main clean. ⭐ FIRST INBOX-DELIVERED LANDING THIS EPIC HAS EVER HAD, and the first to pass inbox landing-check with complete:true / missing_keys[0]. Drain closed cleanly: 5 scanned, 5 archived, 0 invalid, 0 archive-failed; inbox is now the EMPTY zero (present, 0 queued, 0 live, 0 invalid, no sender closed). ⛔ DO NOT CONCLUDE THE emit-landing BUNDLE DEFECT IS FIXED: PLAN-01 and PLAN-02 both skipped with 'not orchestrated' and nothing observable changed in between, so ONE success does not refute TWO failures. Channel is working-but-unproven; keep checking an empty drain against the plan's own report. IN FLIGHT R=3 of N=3, SLOTS FULL: PLAN-05 RELEASED AND RE-EMITTED this session by operator decision (launched -> staged -> launched): the old launched was a PHANTOM stamped days ago with no plan record ever appearing, holding a third of capacity on an unsubstantiated in-flight claim. Released to make the queue honest, it re-entered the rotation and qualified on merit (declarative, 5 paths, ZERO overlap rows of any kind, blocking_count 0) and took the slot ahead of PLAN-07/PLAN-08 purely by queue order. ⚠ WATCH FOR THE RECURRENCE: an emit is only real when the operator RUNS the command and auto_emit stamps launched without observing a start - if no plan record appears for PLAN-05 within a session or two, RELEASE IT AGAIN rather than letting a phantom re-accumulate. Its start point is sharpened: H4 is the live hypothesis, Residual 4 (discovery-resolved metadata, BffRuntimeProducer.java:214) is where it begins, PLAN-13 running (6-finalize, own worktree, tip c83b613), PLAN-15 EMITTED and auto-marked launched (auto_emit TRUE), SUPERSEDING PLAN-14 which is now status=superseded. ⛔ CORPUS CONSOLIDATION REVIEW DONE 2026-09-04 (operator-requested) - DO NOT REDO IT, the answer is arithmetic: six of seven live specs (PLAN-03/04/05/07/08/09) carry EXACTLY FIVE deliverables each against a split guard that fires at ~six, so ANY merge among them yields ten and is refused before taste enters. PLAN-14 at ONE deliverable was the sole outlier and the only merge available. PLAN-03+PLAN-04 is refused on TWO independent grounds (the arithmetic, plus the standing recorded decision that they stay split because their MECHANISMS differ - Vert.x setVerifyHost exists, cui-http has no such lever - while the shared contract is decided once in PLAN-03 deliverable 2); a strictly sequential pair is NOT evidence they are one plan. PLAN-09 must NOT be merged into PLAN-07/08 and the reason INVERTS the usual argument: it is documentation-only and skips both gates, so folding it into a gate-requiring plan FORCES it through a gate it is exempt from - batching saves only when riders share the heavier footprint class. PLAN-15 (WS-05) carries issue #256's broad-prefix threshold (design-bearing: BROAD_PREFIX_IPV4=8 warns only BELOW 8, so a /12 and everything from /9 to /32 boots silent; coversEntireSpace already REFUSES trust-all, so only the warning boundary moves) plus PLAN-14's HealthProbe Javadoc as deliverable 4, sharing one gate run. ⚠ RECORDED IMPURITY: deliverable 4 is a WS-01 concern in a WS-05 plan, accepted because WS-01 has no remaining staged work and the ADR/source contradiction is live. ⚠ PLAN-15's cross-check shows an overlap with PLAN-14 on HealthProbe.java - that is the SUPERSEDED spec still declaring the file it handed over, permanent and expected, NOT a collision. ⚠ PLAN-15's third surface entry is a DIRECTORY with 'exact class TBD' - the exact shape that made PLAN-06 gate-blind; resolve it to a named file at outline and correct the declaration in the same act. ✅ THE listen(0) GUARD HAZARD IS RESOLVED, NOT PENDING - retired after verification, do not re-open: PLAN-13 has rebased past 6ba8879 (merge-base --is-ancestor succeeds) and its branch copy of GatewayEdgeRouteTest.java carries ZERO bare listen(0) and SIX loopback-bound calls, so it converted PLAN-06's two new sites along with its own four, unprompted. ⛔ PLAN-14 IS NEWLY STAGED AND LAUNCHED (WS-01, one file: api-sheriff/src/main/java/de/cuioss/sheriff/gateway/HealthProbe.java): the class Javadoc still tells a deployment to 'override the image's HEALTHCHECK to match', the exact unreachable remedy ADR-0039 just corrected - PROBE_PORT is a compiled-in 9000 at :67, probe() takes no port argument, :111 dials a hardcoded 127.0.0.1, and Dockerfile.native ships no other executable. The ADR is now right and the source comment is not, which is the worse disagreement: a reader trusting the comment gets an instruction that cannot work. It could not ride PR #257 because one .java file makes the whole commit gate-requiring. ⚠ Its spec records that BATCHING was preferred to solo emission; it was emitted solo because it was the ONLY eligible candidate and the defect must not sit indefinitely. ⛔ WHY NOTHING ELSE COULD BE EMITTED, all derived: PLAN-03 production/test-pair blind spot vs running PLAN-13 (declares edge/WebSocketRelayStage.java vs PLAN-13's edge/WebSocketRelayStageTest.java, matcher returns no row); PLAN-04 hard dependency on PLAN-03 landing; PLAN-07 and PLAN-08 blocked ONLY by the tls/ directory-vs-file blind spot vs PLAN-13 - BOTH UNBLOCK THE MOMENT PLAN-13 LANDS and are the two best-prepared candidates; PLAN-09 hard-depends on PLAN-07 for scenario 6 only, with a recorded legitimate split (scenarios 1-5 ship today). ⛔ OPERATOR DECISION STILL OWED AND NOW PAID FOR TWICE: .plan/marshal.json:115 still reads required_bots coderabbit,cuioss-review-bot. The mechanism is now fully named - no cuioss-review-bot.md registry doc EXISTS, so the token resolves to nothing, the bot is classified absent FOREVER, and the participation quorum is STRUCTURALLY UNCONVERGEABLE by awaiting: the loop-back ceiling burns and the run dead-ends at the barrier with no diagnosable cause. PLAN-10 burned its 3/3 ceiling and needed a hand-verified rereview-timeout-override to merge. CLAUDE.md carries the correct value (coderabbit,pr-agent); marshal.json is the side that drifted at 1c7308c. ⚠ A SECOND, DISTINCT REVIEW DEFECT rides with it (folded into lesson 2026-09-02-22-003, upstream plan-marshall): _references_head_sha inspects only the review signal, so pr-agent's issue_comment-published re-review reports head_sha_verified=false and escalates as a false DECLINE even when the body names the reviewed commit; github_pr fetch_findings resolves the SAME comment correctly, so two resolvers disagree and only one is right. ⚠ PLAN-10's scope-creep guard COULD NOT RUN during execute (references.json had no plan_creation_sha) and was hand-verified; the two-.adoc footprint conclusion holds on independent git evidence, but the automatic fence did not produce it. ⚠ doc/README.adoc's NOTE enumeration will drift again at ADR-0040 - carried from the plan's report, NOT independently verified here; PLAN-04 and PLAN-07 both still declare doc/adr/ and whichever lands 0040 inherits it. ⚠ Lessons this drain wrote: NEW 2026-09-04-07-001 (documentation, anti-pattern) 'a documentation-only footprint removes the gate that would catch a false claim - trace every runtime assertion to the implementing symbol'; FOLDED into 2026-09-02-22-003 the symmetric review-credit failure. Candidate 001 (scope sensor vs persisted estimate) was DISCARDED on a store-boundary ground - it targets a plan-marshall bundle this repo does not own - and is preserved verbatim in Watches so it is not re-derived. CARRIED UNCHANGED AND STILL GOVERNING: the compose sample's DEFAULT path does not boot until 0.2.0 (.env:15 pins 0.1.1; documented at docker-compose.yml:159-170; do NOT cut 0.2.0 without bumping the pin); the post-merge main-branch/deploy-snapshot run is UNVERIFIABLE through the ci abstraction (every verb is PR-keyed, --head main returns no-PR, no verb reads a push run by commit) so it is an unverified lead for BOTH #254 and #257, not green; WebSocketRelayStageTest.preservesSecurityHeadersOnHandshakeFailure is CI-flaky and is NOT fixed by PLAN-13's listen(0) work; issue #256 (BROAD_PREFIX_IPV4=8 lets a /12 boot silently) had its severity raised by PLAN-06; the gate's THIRD blindness class is a WRONG DECLARATION, not a matcher limit (PLAN-06 declared forward/, landed edge/) and PLAN-06's spec surface was corrected; WS-07 is settled and PLAN-12's kqueue hypothesis is REFUTED; the 30s-hang defect is NARROWED, NOT CLOSED; the build.map drift gate is answered NO DELIBERATELY; --status on the queue verb is FREE-FORM with no enum validation; settled-narrative relocation DEFERRED again. CORPUS at 337af0d: 14 rows / 14 specs both ways, all declarative, indeterminate_count 0, blocking_count 0, 14/14 claim sections parsed. Every re-grounding verdict is stale at this HEAD; staleness is reported-not-promoted and nothing is gated.
```

## Open Defects — routed to plan-marshall (relocated 2026-09-15)

Six entries whose remedy lives in the `plan-marshall` bundle, not in this repository. Operator direction
2026-09-15: *"root the plan-marshall defects to `.plan/local/orchestrator/truthful-signals/inbox`"*. Each was
re-verified read-only against plan-marshall `origin/main` `7a028157e` before filing, then written to that
epic's inbox as a `finding` message carrying the entry verbatim, and retired from `epic.md` in the same act.
The four messages are `api-sheriff-deployment-configurability-010` … `-013`.

⚠ **`-013` also covers the group already settled above** — "Open Defects — handled (relocated 2026-09-11)",
the `gh run list --commit` entry (O64/O65/O66). That group is settled HERE because a workaround exists; the
missing commit-addressed verb in `tools-integration-ci` is what `-013` reports upstream, and it was the only
defect in the batch that nothing upstream tracked.

### Where each entry went, and what the verification found

| Message | Entry (first line, abridged) | Disposition |
|---|---|---|
| `-011` | ⛔ **`pre-submission-self-review` RETURNS GREEN ON JAVA WHILE CHECKING NOTHING — no | routed 2026-09-15 — PARTIALLY FIXED upstream: the vacuous-green half shipped (PLAN-TRUTH-126 Instance 4, PR #1397); what remains is that `ext-self-review-plan-marshall` is still the only implementor, so a Java footprint gets no surfacer at all |
| `-010` | ⛔ **`emit-landing` DID NOT RUN AT ALL for PLAN-07 — a THIRD failure mode, distinct from the two | routed 2026-09-15 — cause found upstream: the compose gate drops `emit-landing` on every negative verdict incl. `ImportError` and an unreadable `request.md`, logging the drop to the DECISION log, never the work log |
| `-012` | ⚠ **No scope-creep verdict exists for PLAN-07 — the check was ABSENT, not passed.** | routed 2026-09-15 — already tracked upstream by PLAN-TRUTH-145 D4/D5 (staged); filed as a consumer-repo sighting only, explicitly not a second work item |
| `-010` | ⛔ **RECURRENCE 2026-09-11 — OCCURRENCES FOUR AND FIVE, BOTH SKIPPED-AT-RUNTIME.** PLAN-08 and | routed 2026-09-15 with the rest of the channel record |
| `-010` | ⛔ **`emit-landing` SKIPPED FOR AN ORCHESTRATED PLAN — THIRD OCCURRENCE, and the two successes did | routed 2026-09-15 — the non-determinism is explained: the `orchestrated` verdict is resolved inside the lessons-capture gate and carried in-context with no persisted fallback, so an empty `epic` input reads as 'not orchestrated' |
| `-010` | ⛔ **`emit-landing` skips orchestrated plans, and the epic learns nothing from the drain.** PLAN-02's | routed 2026-09-15 — first observation of the same channel defect |

### The entries, verbatim

- ⛔ **`pre-submission-self-review` RETURNS GREEN ON JAVA WHILE CHECKING NOTHING — no
  `ext-self-review-java` surfacer exists (2026-09-10).** Its detectors read **Python and skill docs**,
  so on a Java footprint it reports zero findings **because it looked at nothing**, not because
  nothing is there. ⛔ **This is not one plan's problem — every Java plan in this epic has passed that
  step, and every one of those greens was vacuous.** PLAN-07 is simply where it was noticed.
  ⚠ **It is the same shape the epic has now recorded five times** — a stated rule with no mechanism:
  issue #269's inventory claiming a check, the SNAPSHOT REMOVAL CONDITION carried as prose,
  `ApiSheriff-114` promising a browser-safe guarantee, `DescriptorInventoryWiringTest`'s unasserted
  count, and now a review step whose green is structurally uninformative. ⛔ **It belongs in the
  plan-marshall repository**, not here. Unowned locally. — source: PLAN-07 landing paste.

- ⛔ **`emit-landing` DID NOT RUN AT ALL for PLAN-07 — a THIRD failure mode, distinct from the two
  already recorded.** `grep -c 'emit-landing'` over the archived `work.log` returns **0**, while
  every neighbouring step logs its `Completed step: … (outcome=…)` line. So the step was **absent
  from the composed manifest**, not merely skipped at runtime — and PLAN-07's `request.md` carries a
  valid orchestrator `source_id`. The channel's record is now: **3 delivered, 3 skipped-at-runtime,
  1 absent-from-manifest, 1 correct abstention.** ⚠ That third mode matters because the existing
  diagnosis ("the fault is downstream of `inbox detect`, in whatever the finalize step reads") cannot
  explain a step that never entered the step list. Unowned; a bundle-level fix. — source: archived
  `work.log` and `request.md` at HEAD `a30fe6f`.

- ⚠ **No scope-creep verdict exists for PLAN-07 — the check was ABSENT, not passed.**
  `references.json` never carried a `plan_creation_sha`. ⚠ **Second occurrence** — PLAN-10 had the
  same gap on 2026-09-04, hand-verified then. Two of this epic's plans have now shipped with that
  fence unobservable, which makes it a pattern rather than an incident. — source: PLAN-07 landing paste.

- ⛔ **RECURRENCE 2026-09-11 — OCCURRENCES FOUR AND FIVE, BOTH SKIPPED-AT-RUNTIME.** PLAN-08 and
  PLAN-17 both ran `emit-landing` and both got `outcome=skipped` — verified in each archived
  `logs/work.log` (`2026-09-10T22:50:21Z` and `2026-09-10T21:09:57Z`). Channel record is now
  **3 delivered / 5 skipped-at-runtime / 1 absent-from-manifest / 1 correct abstention**.
  ⛔ **PLAN-08 reported "Finalize steps (19/19)" and the channel still delivered nothing** — the
  epic's own rule that a green step count is not evidence of work, demonstrated on the one step whose
  entire job is evidence. ⛔ **PLAN-17's landing was invisible for a full day as a result**: no inbox
  message and no paste, found only because `analyze` fetched `main` while corroborating PLAN-08.
  **A silent channel plus an unmentioned plan is how a shipped plan goes unrecorded.**

- ⛔ **`emit-landing` SKIPPED FOR AN ORCHESTRATED PLAN — THIRD OCCURRENCE, and the two successes did
  NOT mean it was fixed (2026-09-07).** PLAN-03's `work.log:457` records
  `Completed step: emit-landing (outcome=skipped)`, while its archived `request.md:7` carries
  `source_id: .plan/local/orchestrator/deployment-configurability/plans/PLAN-03-upstream-hostname-verification.md`
  and `inbox detect` on that exact value returns `orchestrated: true` / `detection: orchestrated`.
  The inbox is empty and no `upstream-hostname-verification` sender directory exists under
  `inbox/archive/`, so nothing was sent — this landing reached the epic only by operator paste.

  ⛔ **NEW EVIDENCE THAT NARROWS IT — the source_id shape is NOT the discriminator.** All four
  orchestrated plans carry byte-identical pointer shapes (`source: description`, the same
  `.plan/local/orchestrator/deployment-configurability/plans/PLAN-NN-slug.md` form), and **two
  succeeded while two skipped**:

  | Plan | Landed | `emit-landing` |
  |---|---|---|
  | PLAN-02 | 2026-09-02 | ⛔ skipped |
  | PLAN-10 | 2026-09-04 | ✅ delivered |
  | PLAN-15 | 2026-09-05 | ✅ delivered |
  | PLAN-03 | 2026-09-07 | ⛔ skipped |

  So the fault is not the pointer, not `inbox detect`, and not the persisted metadata — **it is
  non-deterministic with respect to everything observable from the plan record**. The
  skip-success-success-skip ordering also admits a **regression between 2026-09-05 and 2026-09-07**
  as readily as intermittency; that window is where to look first, and it is the window in which
  plan-marshall tooling was being upgraded.

  ⛔ **The standing operational rule is now upgraded from caution to fact**: an empty inbox drain
  means *nothing was sent*, never *nothing happened* — **and the channel is demonstrably
  intermittent, so a delivered landing is no evidence the next one will arrive.** Always reconcile an
  empty drain against the plan's own report. Unowned; a bundle-level fix. — source: archived
  `work.log`, `request.md`, `inbox detect`, and `inbox/archive/` enumeration at HEAD `a8c9834`.

- ⛔ **`emit-landing` skips orchestrated plans, and the epic learns nothing from the drain.** PLAN-02's
  finalize recorded `emit-landing … skipped -- not orchestrated` while the plan **is** orchestrated —
  archived `source_id` unchanged, `inbox detect` returns `orchestrated: true` /
  `detection: orchestrated`. Same shape as PLAN-01's four bypassed lessons. **Two occurrences, and in
  both the persisted pointer detects correctly**, so the fault is downstream of `inbox detect`, in
  whatever the finalize step actually reads. ⚠ **Operational consequence right now**: no epic landing
  arrives by inbox, so every landing must be pasted or the ledger silently misses it — the inbox
  drain's `count: 0` means "nothing was sent", never "nothing happened". Unowned; a bundle-level fix.
  — source: archived `request.md` read plus `inbox detect` at HEAD `1c7308c`.

## Resume anchor history (relocated 2026-09-15)

Replaced by a short current anchor when the 2026-09-15 corpus regroup superseded its queue description ("7 staged (PLAN-09, 18-23)", "re-ground before emitting"). Moved here verbatim and unedited; the older chain is in "Resume anchor history (relocated 2026-09-11)" above.

```text
STATUS CHECK 2026-09-15: origin/main is now a2969b9 (past bd39ae7: #302 d35daf6 release-skill alignment check, .claude/skills/release only; #303 a2969b9 steward upgrade, 1-line CLAUDE.md Temporary Files wording - CLAUDE.md is in PLAN-19 declared surface, trivial). No open PRs, inbox empty (22 archived), nothing running. Re-ground against a2969b9, not bd39ae7. PLAN-MARSHALL DEFECTS ROUTED 2026-09-15. Six live Open Defects entries filed to plan-marshall truthful-signals as findings api-sheriff-deployment-configurability-010..013 (verified at plan-marshall 7a028157e) and relocated to settled.md; live ledger now 25 open defects / 9 watches. -013 (no commit-addressed CI read) is the ONLY untracked upstream item; -012 is a sighting for already-staged PLAN-TRUTH-145; -010 names the emit-landing cause (verdict has no persisted carrier); -011 notes no non-plan-marshall self-review implementor exists. Prior batch -001..-009 confirmed drained upstream and folded into PLAN-TRUTH-105/117/118/122/132/141. API-SHERIFF main has MOVED to bd39ae7 (#301 org workflows v0.27.0 merged; #295 and #300 from the 09-11 note are resolved) - the 09-11 corpus re-grounding was against 0515e15, so RE-GROUND BEFORE EMITTING. PRIOR: CLEANED 2026-09-11 at origin/main 0515e15 (checkout on main). Nothing running, 7 staged (PLAN-09, 18-23), N=3. #299 MERGED (64fe822) - version-bearing content now says 0.2.1; PLAN-19 re-grounded (7 verdicts at 0515e15): D1 is now the Known Limitations AUDIT only (#299 re-stamped the section without re-verifying it), D3 gains two stamps #299 missed (doc/fapi_status.adoc:1 '(0.1.0 Alpha)', doc/features-analysis.adoc:219 - both outside PLAN-19's declared surface). Ledger compacted: 59 handled Open Defects + 38 handled Watches relocated verbatim to settled.md with reasons; 31 live defects / 9 live watches remain. Full prior anchor chain relocated verbatim to settled.md 'Resume anchor history (relocated 2026-09-11)'. Lessons store empty (intake done same day; 16 lessons in plan-marshall truthful-signals -001..009). OPEN PRs: #295 (org workflows v0.26.0), #300 (cui-quarkus-parent) - not cuttable until they drain. OPERATOR DECISION PENDING: close this epic vs continue - the vision is shipped and released. (Decided 2026-09-15: check-changes force-include draft NOT filed - build.map is plan-marshall-only; cui-java-parent UpgradeToJava21 NOT raised - other consumers are Java 21 baseline, no-op.) NEXT if continuing: /plan-marshall:plan-orchestrator next slug=deployment-configurability.
```
