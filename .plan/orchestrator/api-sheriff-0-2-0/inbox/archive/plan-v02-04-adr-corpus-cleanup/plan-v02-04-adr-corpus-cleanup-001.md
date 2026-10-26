envelope_version=1
sender_type=plan
sender_id=plan-v02-04-adr-corpus-cleanup
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-25T11:34:36Z
revision=2
amended=2026-10-25T22:34:19Z

# PLAN-V02-04 deferral: follow-up plan for the remaining ADR groups

PLAN-V02-04 (`plan-v02-04-adr-corpus-cleanup`) audited all 71 records and shipped six groups in one PR (53 files). These are C1 (0060, 0066), C2 (0018, 0019), C3 (0023, 0024, 0026, 0029), C4 (0054 absorbs 0056) and C6 (0040, 0041, 0045), plus the five relocations (0002, 0012, 0021, 0031, 0065). It also cut 0037 and 0058 to the ceiling. Nine groups, 53 records, are deferred whole. Please stage a follow-up plan for them.

- **Proposed slug:** `adr-corpus-cleanup-part-2` (the orchestrator assigns the ordinal).
- **Inputs it inherits:** the rubric, skeleton, writing rule and term list in `doc/development/adr-writing.adoc`, and the 500-line body ceiling `AdrRecordLengthContractTest` now enforces. The corpus is green under that test.
- **Verdict source:** the deliverable 2 verdict table of PLAN-V02-04 (copied into its PR body). The verdicts below are taken from it.

## Deferred groups, records and verdicts

Body lines are as of PLAN-V02-04's merge. "keep" means: rewrite to the skeleton and writing rule, then check the status against the shipped code.

### F1 — config neutrality and TLS edge
- 0011 — keep. The deferred-knobs table and amendment detail are candidates to move to `doc/configuration.adoc`.
- 0017 — keep. Check the status (Proposed) against the shipped front listener.
- 0025 — keep. Body 491, 9 lines under the ceiling. It joins two spec-named clusters (config neutrality, TLS edge).
- 0032 — keep. `doc/development/README.adoc` § Artifact Purity restates most of it; make one of the two link to the other.
- 0038 — keep. It is close to a build convention, so re-examine it beside the 0021 relocation.

### F2 — anchors, assets, policy resolution
- 0007, 0013, 0014, 0015, 0028, 0067 — keep. 0014 owes its skeleton rewrite. About 75 files reference this group.

### F3 — scopes and session routes
- 0048, 0055 — keep.
- 0049 — keep. Its session half is superseded by 0057; mark that cleanly instead of the 16-line note inside Status.
- 0057 — keep. Its status was already flipped to Accepted in PLAN-V02-04. Its three amendment notes still sit inside Status (a 58-line meta block).

### F4 — BFF identity-provider legs
- 0043, 0050, 0051, 0069 — keep.
- 0046 — keep. Move the amendment note inside Status into the body.
- 0058 — keep. **Ceiling cut already done in PLAN-V02-04** (body 780 → 497, status Accepted). The skeleton rewrite is still owed; it currently has no Alternatives section heading, and its alternatives are inline.

### F5 — token validation and JWKS
- 0022 — relocate → `doc/technical_aspects.adoc` (section on adopting upstream security-default changes). 13 files reference it, ADR-0011 A1 among them.
- 0027 — **not settled**; `PLAN-V02-09` re-examines it. PLAN-V02-04 left it unchanged.
- 0063 — keep.

### F6 — release and image
- 0034 — keep (superseded by 0035, marked).
- 0035 — keep. Flip Proposed → Accepted: the shipped `release.yml` implements it.
- 0039 — keep. PLAN-V02-04 only repointed its 0031 mention.
- 0042, 0070 — keep.

### F7 — testing and structure conventions
- 0005 — keep (superseded by 0062, marked). Remove the "superseded by" sentence inserted into the 0.1.0 snapshot section.
- 0030 — relocate → `doc/development/test-corpus-integrity.adoc` (fitness-function shape). Five ArchUnit tests cite it.
- 0033 — keep. It is close to a coding convention.
- 0061, 0062, 0071 — keep.
- 0068 — keep. It is close to a code-organisation convention; re-examine it.

### F8 — forward and header policy
- 0036 — keep. The 0047-into-0036 merge question belongs here; that merge alone repoints about 18 files.
- 0037 — keep. **Ceiling cut already done in PLAN-V02-04** (body 583 → 499). The skeleton rewrite and the 0036/0047 merge decision are still owed.
- 0047 — keep.

### F9 — transport and routing singletons (skeleton rewrite only)
- 0001, 0009, 0016, 0020, 0044, 0052, 0053, 0059, 0064 — keep.
- 0003 — keep. Add the missing Alternatives section.
- 0004 — keep. Fold A1/A2 into the decision; the A2 migration list moves out as implementation narrative.
- 0006 — keep. The competitor table is research evidence that may be trimmed.
- 0008 — keep. **Its timeout sentence was left alone** (kidicap `PLAN-29` D6); leave it unless that plan has landed.
- 0010 — keep. Check the status (Proposed) against `ConfigLoader`.

## Points the follow-up should know

- `.claude/skills/release/SKILL.md` says an ADR is immutable and never edited. That conflicts with the in-place rewrites both plans make; it is raised with the operator.
- Moving amendment notes out of Status into the body (0046, 0049, 0057) raises the body count. Measure it against the ceiling before the rewrite.
- No test pins the abortive HTTP/1.1 upstream close on the request-body idle-timeout trigger or the 25 s deadline trigger (ADR-0066). The gap is recorded in ADR-0066 and the GW-07 threat-model entry; the operator ruled to report it without a test.
- The `doc/user/tls-edge.adoc` egress section is already titled "Egress TLS" (anchor `_egress_tls`); PLAN-V02-04 renamed it in its code-review fix round, so nothing is owed there.
- Term-list residue PLAN-V02-04 left in records it rewrote, listed in its report for a later wording pass: "refuse" for a request stopped at runtime (0029:173, 0060:209, 0066:229), "routing" for route selection (0019, 0026, 0029, 0054), unqualified "warning" for the boot WARN (0023, 0024, 0054), and "outbound" beside "egress" (0040:59, 0040:181). Outside the records, "outbound TLS" remains in `doc/configuration.adoc`, `doc/security-threat-model.adoc` and two test sources.
- ADR-0060:331-334 still states that the dispatchGrpc watched-stream hand-off and the fixed five-minute value have no test; #520 (TerminalDispatchGrpcStreamIdleTest clientTrafficKeepsTheCallOpen, onBuildsTheFixedFiveMinuteBound) added both, so the sentence should name only the public one-line hand-over, as the threat model now does.
