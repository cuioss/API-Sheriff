# Landing Analysis: PLAN-SEC2-08 — Open watches and leftovers

epic: security-hardening-tier-2
workstream: WS-04
pr: #651 (https://github.com/cuioss/API-Sheriff/pull/651), merged through the merge queue as `a2ff139e`

> Landing record for one shipped plan, the last of the epic. Written by the `analyze` verb after verifying the landing
> message against ground truth (git, the PR through the CI abstraction, the merged code). Disclosure is
> mechanism-level: the three-layer read found no possible weakness in the changed production code, so nothing
> was held back from this file for that reason.

## Deliverable Fidelity vs Spec

Landing facts: `deliverables_total=4`, `deliverables_done=4`; the spec names 4 deliverables and the lane dropped none. The landing
facts block is **complete** (`landing-check`: `complete: true`), `effort=medium` and self-reported by the lane (not verified).

| Deliverable (spec) | Verdict | Evidence |
|--------------------|---------|----------|
| 1. A test of the `AcknowledgementRelay` release wiring through the real wait | shipped-modified | The test is `InboundStreamWindowTest.endedWaitLetsGoOfItsEndingCallWhileItsPingIsStillPending` (`:394-427`), not a case of `EarlyResponseEndTest` (untouched). It drives the real `EarlyResponseEnd.endWhenQuiet` (helper `:507-515`) on a PING-holding client and asserts, while the PING is still pending (`pingsHeld() == 1`), that a `WeakReference` to the ending call becomes collectable (`awaitCollectable`, `:521-526`). It does **not** hand a second acknowledgement to the relay. No seam was added (`EarlyResponseEnd.java` is not in the diff). The shape differs from the spec because the spec's observable cannot exist: see Layer 1. |
| 2. Three restatements the sweep of PLAN-SEC2-07 did not reach | shipped-as-specified | `docker-compose.yml:425-429` and `sheriff-config/gateway.yaml:78-82` now say the listener is bound on all interfaces and fed on `localhost:8444`, matching `Dockerfile.native` (`ENTRYPOINT [..., "-Dquarkus.http.host=0.0.0.0"]`), `TlsEdgeProducer.java:114` and `doc/development/tls-edge.adoc:366-381`; the `PendingAuthorizationStore` Javadoc and `package-info.java:27-31` say one in-memory server-side store serves both modes, matching `BffRuntimeProducer.java:597`, and `consume()` removes the record. Re-read at `a2ff139e`: no disagreement. One sibling sentence remains, see Residue. |
| 3. The boot-rule template and its test gap | shipped-modified | The record is `ApiSheriff-152`, still one identifier. Its template (`ConfigLogMessages.java:722`) now takes two fixed `%s` arguments, a combination token and that combination's own consequence-and-remedy; the prose sits in two constants of `EdgeRules` (`:95-111`), passed at the two `warn` calls (`:379-385`). `doc/LogMessages.adoc:114` carries the same template character for character (re-read). The loopback consequence was made conditional on the range holding the relay leg's own address after a CodeRabbit finding. `EdgeRulesTest` asserts each record's own consequence, covering ranges (`0.0.0.0/1`, `::/1`) and a malformed entry. The spec put the new wording in the template; the lane moved it into the call site, which is why `EdgeRules.java` is on the diff. |
| 4. Pin that the connection cap counts after the handshake (optional) | shipped, hypothesis not refuted | `MainListenerConnectionCapIT.countsAConnectionOnlyOnceItsHandshakeCompletes` (`:233-265`) opens CAP+1 raw sockets that never write, serves one real handshake (`Outcome.SERVED`) and asserts every raw socket is still open. No refutation-arm file was touched; `ConnectionCapAudit.java:39` still says a connection in its handshake "is not counted yet". The case was not observed running (see Layer 2). |
| Added by the lane | added-unplanned | `integration-tests/src/main/docker/sheriff-config-passthrough-empty/gateway.yaml` (one comment, the same "loopback relocation" wording as deliverable 2) and `doc/configuration/security-and-edge-hardening.adoc` (the deliverable 3 sentence, plus the per-Host qualifier a CodeRabbit finding asked for). |

Surface delta (declared 15 entries, 6 of them conditional on deliverable 4's refutation arm and 1 on a seam; realized 12 files): 8 realized
files were declared (`PendingAuthorizationStore.java`, `ConfigLogMessages.java`, `doc/LogMessages.adoc`, `EdgeRulesTest.java`,
`MainListenerConnectionCapIT.java`, `docker-compose.yml`, `sheriff-config/gateway.yaml`, and the conditional
`security-and-edge-hardening.adoc`, touched for deliverable 3 and not for the refutation arm). **4 realized files were never
declared**: `EdgeRules.java` (deliverable 3's call sites), `InboundStreamWindowTest.java` (deliverable 1), `package-info.java`
(deliverable 2, the same sentence as the Javadoc) and `sheriff-config-passthrough-empty/gateway.yaml`. Each is explained by a
deliverable. **7 declared files were untouched**: `EarlyResponseEndTest.java`, and six conditional ones, namely
`EarlyResponseEnd.java` (no seam needed), `application.properties`, `doc/user/edge-security.adoc`, `doc/security-threat-model.adoc`,
`doc/architecture/tls-and-inbound-security.adoc` and `ConnectionCapAudit.java` (the refutation arm that did not fire).

## Three-Layer Analysis

One read-only leaf (`execution-context-level-3`, the `orchestrator.analyze` surface) read the merge commit `a2ff139e` against the spec and the
code. Reason for a leaf of its own: it reads merged code in its own context, which is the depth the dispatch rule exists for. The
orchestrator re-read the points marked "re-read" below at their `file:line` on `main` at `a2ff139e` before recording them; the leaf
wrote nothing.

### Layer 1 — Specified

**Verdict: partly sound.** One premise of deliverable 1 cannot be met as worded; the other deliverables' premises hold.

- **L1-1 (major, re-read).** Deliverable 1 asks to hand the real wait "a second acknowledgement" and assert the response "is not ended twice", and to show the test red when `relay.release()` is removed. `Wait.acknowledged` returns first on `if (ended)` (`EarlyResponseEnd.java:316-319`), and `finish` sets `ended = true` at `:334` before `relay.release()` at `:335`. A second acknowledgement is therefore a no-op whether or not `release()` is there, so that assertion cannot go red against the variant the Done-when names. The only observable `release()` has is reachability: it lets go of the relay, and with it the wait and the ending call, while a never-acknowledged PING future stays pending in the transport (`:305-314`). The lane's reachability design is the only one that satisfies the spec's own Done-when.
- The persisted verdicts (corpus vocabulary, producer `security-hardening-tier-2/analyze`, checked at `a2ff139e`): claim 0 (`Wait.finish` calls `relay.release()`, the relay test re-implements the ending) **corroborated**, with the guard recorded in its evidence; claim 1 (the real wait is reachable with no production change) **corroborated**, recorded with the location difference (reachable from the package through `endWhenQuiet`, from `InboundStreamWindowTest`, not from `EarlyResponseEndTest`, which has no transport); claim 7 (handshake-pending connections do not occupy the cap) **unverifiable** (neither shown by reading nor observed in a run; see Layer 2). The OBSERVED claims were not persisted, as for the earlier landings.
- Premise of `ApiSheriff-152` (re-read): `SourceKeys.resolve` keys on the TCP peer (`SourceKeys.java:140-152`) and folds the authority host into the key (`:70`, `:145-150`), so the loopback-peer statement is true for detection, and the key is per address *and host*.

### Layer 2 — Implemented

**Verdict: deliverables 2 and 3 trace to code and to tests; deliverable 1 shipped in another form; weak points in 1, 3 and 4.**

- **L2-1 (minor, re-read).** `EarlyResponseEndTest.lateAcknowledgementDoesNotEndTheWaitASecondTime` is unchanged (`:401-429`). Its Javadoc still says the response "is not ended a second time", yet the test re-implements the ending in a lambda and calls `relay.release()` itself (`:413-420`), so it stays green if `Wait.finish` drops the call. The new test covers the wiring; the old one is a sibling that reads as if it did.
- **L2-2 (minor).** In the new test the assertions "the response is ended once" and "reset once" (`InboundStreamWindowTest.java:418-426`) hold with or without `release()`; only the `awaitCollectable` timeout can fail. The commit message's "red with `relay.release()` removed" is a run result and cannot be confirmed by reading. GC-based reachability: `System.gc()` carries a `NOSONAR java:S1215` with rationale, the ceiling is fixed, and a failure is a red timeout, never a false green.
- **L2-3 (minor, re-read).** `EdgeRulesTest.assertStatesOnlyItsOwnConsequence` (`:120-135`): the `noneMatch(other)` half checks fragments of the *new* constants, so it would stay green if the old cross-contaminating wording came back in other words; each test would go red at `434193b0` only through the `allMatch(own)` half. Whether the malformed-entry test is vacuous depends on whether `ConfigValidator` reaches the collapse rule after an error: not read.
- **L2-4 (minor, re-read).** The deliverable 4 case does not repeat the dead-port start check its class Javadoc names (`assertListenerAnswers` is called by the cap case, not by this one), and its `isStillOpen` probe (`:332-341`) counts any received byte, a TLS alert included, as open. Neither shows a false green; a dead port would still go red, under a misleading message.
- **The deliverable 4 case was not observed running.** The PR's Integration Tests log carries 41 tests and names no cap case by display name, so "hypothesis shown" rests on the case being written and green by omission, not on a read result. Recorded as `unverifiable` (claim 7).
- **L2-5 (minor).** The lane's open advisories are confirmed as still true on `main`: `EdgeRulesTest` pins consequences, not remedy text; the once-per-combination test uses a single loopback range.

### Layer 3 — Correct

**Verdict: no defect found in the changed production code.**

- The production change is the two constants and a second `%s` argument at two call sites (`EdgeRules.java:99-111`, `:379-385`); the gating conditions and `holdsLoopback`/`overlaps` bodies are unchanged (re-read). By reading `parseCidr`, `holdsLoopback` answers true for `127.0.0.0/8`, `127.0.0.2/32`, `::1/128`, `0.0.0.0/1` and `::/1`, and false for a malformed entry. IPv4-mapped IPv6 ranges are not shown by reading.
- **L3-1 (minor design note, re-read).** The operator-readable consequence and remedy now live in `EdgeRules` constants, not in the `LogRecord` template, so the template column of `doc/LogMessages.adoc` no longer shows what the operator reads, and the text sits outside the one home the log-documentation rule assumes. Both arguments are fixed constants, never a configured range or address, so there is no injection or data-exposure risk. Two records would have kept text and template together.
- No weakness-class read: nothing in the read needed the operator-only channel.

### Conformance to the Project's Own Rules

| Rule source | Rule | Verdict | Evidence |
|-------------|------|---------|----------|
| CLAUDE.md | CuiLogger, `%s` only, exception first, `LogRecord` for INFO/WARN/ERROR | conforms | `EdgeRules.java:379-385`; template `ConfigLogMessages.java:722` |
| CLAUDE.md | `LogRecord` ranges WARN 100-199 | conforms | `identifier(152)` inside `WARN` |
| CLAUDE.md | Document every record in `doc/LogMessages.adoc` | conforms | row `:114` equals the template |
| CLAUDE.md | Never expose sensitive data in logs | conforms | fixed constant arguments; tests assert no range or address in the record |
| CLAUDE.md | Javadoc with `@since` and thread-safety on public/protected | conforms | `PendingAuthorizationStore` keeps `@since 1.0`; the interface lacks a thread-safety note, unchanged by the diff |
| CLAUDE.md | JUnit 5, AAA, no Mockito/PowerMock/Hamcrest | conforms | no such import in the diff |
| CLAUDE.md | `-Werror`, no `@SuppressWarnings` to get green; in-code suppression only with rationale | conforms | `System.gc()` carries `NOSONAR java:S1215` with rationale |
| `declared-limit-assertion-coverage` | a key that parses is not a key that acts | not shown by reading | the new IT case is the delete-the-key counterpart for the connection cap; its run was not observed |

## Metrics and Anomalies

- Tokens `35702217` (mixed populations; the lane says the finalize figure is overstated by about 24k), wall `43890 s` (about 12 h; the PR was open 4 h 25 min), harness `claude`, model `claude-sonnet-5-5`, `effort=medium` (self-reported, not verified), plugin `0.1.2173`. Fifteen finalize steps recorded `done`; `emit-landing` and `archive-plan` had not recorded an outcome when the message was written, and the message says so.
- Eight commits, three finalize loops, two review-driven correction rounds on the `ApiSheriff-152` wording and one inline documentation fix.
- Process deviations the lane reports (a `git grep` through the shell by one review author, a verdict file edited with a shell one-liner, the commit verb's refusal of an inline commit without a step): recorded as the lane's own account, not independently checked.

## Routing and Merge Behavior

- Merged through the merge queue as `a2ff139e`; no rebase conflict recorded; parallelization: the plan ran alone, no overlap observed.
- **Review numbers (re-read through `review-gate` and the PR comment listing, at 2026-11-22).** Unresolved threads: lane `0`, provider `0`: **agree**. Review-body findings: lane total `4`, handled `4`; provider total `2`, handled `0` (the two inline findings, each with a reply and resolved). Every record the provider returns predates the merge (`mergedAt` 2026-11-22T03:20:09Z); none was created after it, so the difference is **`undecidable`** under the classification rule (the gate-read time was not retained), not a false claim and not a post-merge change. `review_body_findings_handled` is not re-verified: the run's progress file was not read.
- **Sonar:** the lane reports `sonar_open_new_issues=0`; the `SonarCloud` check is green. The count itself was not re-read from Sonar: **`undecidable`**, as for the earlier landings.
- **Checks on #651 (25):** all green or skipped, `build / conclusion`, `integration-tests / conclusion`, `SonarCloud`, `CodeRabbit`, `Sourcery review`. The PR-attached post-merge `Run Integration Benchmarks` run (44980150829), still open when the lane wrote its message, finished **green** (1141 s).
- **The main-branch runs for `a2ff139e`** were not read at analysis (the CI abstraction has no lookup by merge commit). **Addendum 2026-11-22**, read on the operator's word through the check runs of the merge commit: `build / conclusion`, `build / deploy-snapshot` (the snapshot deployment) and `integration-tests / conclusion` are green.

## Reconciliation Actions

- Row `PLAN-SEC2-08` transitioned `running` → `shipped`; `pr` 651, `landing` `landings/PLAN-SEC2-08.md`, `plan_marshall_plan_id` `sec2-08-open-watches-and-leftovers`.
- Layer-1 verdicts persisted on the spec under claims 0, 1 and 7 (see Layer 1).
- Inbox message `sec2-08-open-watches-and-leftovers-001` drained and archived after the above was persisted.
- Watches settled by this landing and retired in `epic.md`: the `AcknowledgementRelay` release-wiring Watch (deliverable 1), and leftovers (a) and (b) (deliverable 2) and (c) and (d) (deliverable 3) of the PLAN-SEC2-07 Watch, each with the qualifiers recorded there.
- No staged spec remains, so no fold and no surface edit applies to the findings above.

## Follow-Ups

None is staged: the epic has no plan left to carry one, and each item is small. They are carried to `history.md` by `close` as owed leads, or dropped on the operator's word.

1. **Per-Host wording in the record itself.** The doc sentence now says terminated requests to the same *Host* are one source, but `ACTING_ACTION_CONSEQUENCE` (`EdgeRules.java:99-103`) still says "every terminated client is keyed on that one source … acts on all of them at once", and the `ConfigLogMessages` Javadoc and `doc/LogMessages.adoc` carry the same wording, while the key folds the Host (`SourceKeys.java:145-150`). The lane lists this as an open advisory; it is confirmed.
2. **`docker-compose.yml:944`** still says "The five other gateway instances escape this only because each overlays `gateway.yaml` with a variant declaring no `passthrough_sni`"; the file defines twelve `api-sheriff*` services. Whether five is the right count of the overlaying ones was not established: a lead, not a finding.
3. **The old lambda test** (`EarlyResponseEndTest.lateAcknowledgementDoesNotEndTheWaitASecondTime`) and its Javadoc still read as a release-wiring guard (L2-1).
4. **The cap IT case** was not observed in the PR's Integration Tests log; running it once against the native image (`-Pintegration-tests`) would turn claim 7 from `unverifiable` into a settled verdict.
