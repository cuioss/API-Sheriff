envelope_version=1
sender_type=plan
sender_id=fix-four-audit-findings-and-bff-flaky-test
epic=api-sheriff-0-2-0
kind=finding
created=2026-10-05T14:17:19Z

## Follow-up to PLAN-V02-13 landed: #385 (merged, squash 11f9c38a)

Plan `fix-four-audit-findings-and-bff-flaky-test` (not launched from this epic) closed the open items PLAN-V02-13 (#383) left behind. Its six deliverables merged in #385:

- **`status_family` metric label** on `sheriff_requests_total` is now bounded to `1xx`..`5xx` plus `other`.
- **Bodyless-method hardening:** a GET or HEAD with no declared `Content-Length` is sent upstream with no body on every protocol, and a body byte is refused. A request that declares a length is forwarded framed by exactly that length, never re-framed as chunked. The `security_defaults.allow_get_with_content_length_body` opt-in keeps its behaviour. Threat-model row gw-02 updated.
- **Abort attribution:** a client disconnect after an upstream failure no longer relabels that failure as a client abort. Circuit-breaker accounting itself is unchanged by operator decision; the docs now state how client-ended calls are counted and why.
- **Reserved passthrough host 404:** pinned by tests with no production change; architecture.adoc, ADR-0059 and the threat model now scope the "identical 404" claim to unrouted addresses.
- **Flaky `BffRuntimeProducerTest`** (the residue item from the PLAN-V02-13 landing): fixed. The tests read a one-step copy of the captured log records instead of the live list.
- **Docs:** new ADR-0060 (status **Proposed**), plus architecture.adoc, ADR-0059 and security-threat-model.adoc.

Checks at merge: local quality gate green (4,671 api-sheriff tests); CI green on every push and re-run by the merge queue; SonarCloud gate passed (93.7% new-code coverage); CodeRabbit and the cuioss review bot reviewed the final commit.

## For the orchestrator

- **ADR-0060 is Proposed** and needs an acceptance decision.
- **Post-merge verification is open:** the Maven Build on `main` for 11f9c38a (including the snapshot deploy) and the PR-attached post-merge benchmark run.
- **One simplification finding** in `DispatchStage`'s inbound-body handling was left for an operator decision rather than changed in this plan.
- **Process gap observed:** the per-module build gate mapped the changed files to zero modules for this repository's layout, so the run widened it by hand to the whole-tree quality gate plus the api-sheriff module tests.
