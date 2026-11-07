envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=landing
created=2026-10-02T21:10:25Z

## What landed

plan-v02-08-fapi-2-0-conformance shipped as #377 (merged): the BFF pushes every authorization request (PAR), binds its access tokens with DPoP and authenticates with `private_key_jwt` unless `oidc.client_secret` is configured. Recorded in ADR-0058.

```landing-facts
schema=landing-facts/1
plan_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
pr=#377
merge_state=merged
cleanup_owed=false
deliverables_total=12
deliverables_done=12
total_tokens=29861200
total_wall_seconds=140929.0
steps=finalize-step-sync-baseline:done,finalize-step-simplify:done,finalize-step-security-audit:done,pre-submission-self-review:done,architecture-refresh:done,pre-push-quality-gate:done,push:done,create-pr:done,ci-verify:done,automatic-review:done,sonar-roundtrip:done,adr-propose:done,branch-cleanup:done,lessons-capture:done,finalize-step-preference-emitter:done,record-metrics:done,finalize-step-print-phase-breakdown:done
step.branch-cleanup.merge_mechanism=merge_queue
step.pre-submission-self-review.may_close=no
step.sonar-roundtrip.new_code_issue_count=0
```

## Residue

- **The plan landed as two pull requests.** A required reviewer refuses pull requests above 100 files, so the operator had the 117-file change split: #377 carries the code, the tests, the integration stack, the log catalogue and ADR-0058 (landing commit e8db85bf); the other 31 documentation files landed afterwards as #378 (landing commit 3a1182e5), opened outside the plan's recorded steps. `pr=` above names #377 only. Both are merged; the documentation on `main` matches the code again.
- **The pre-submission self-review did not converge.** Six rounds, `may_close=no` on each; closed by operator decision at its ceiling, not by a converged result. It was not repeated after the merge with `main`.
- **`main` moved during finalize.** The branch was merged with `main` after the session-widening change landed there; the widening and step-up seams were brought under PAR and DPoP, and this plan's decision record and log ids were renumbered (ADR-0058; ApiSheriff-21, -132, -133, -134). The security audit was not repeated over that merge delta; the delta is covered by unit tests and one integration run.
- **Review coverage of the last commit of #377.** The required reviewer's review covers the commit before the head; the head commit was a one-line schema description change that reviewer had asked for, and it declined to re-review it on its hourly limit. The participation check counted the earlier review. The second required reviewer reviewed the head.
- **Cleanup path.** The merge queue landed #377 about seven minutes after the 1800 s landing wait expired; the loop-back for the deferred cleanup was refused at the spent ceiling, and cleanup ran on a fresh finalize entry, at the operator's request only after #378 had merged.
- **Two answers chosen conservatively at the merge, open to review:** a refused push on the interactive widening re-drive answers `502` (the alternative is the widening's terminal `403`), and an unbound token on a widening callback answers `400` with ApiSheriff-134.
- **Follow-ups this plan leaves, all already stated in the merged pull requests or documents:**
  - the cookie-configuration alignment handed over earlier in this plan's first inbox message;
  - a rate limit in front of login initiation, which now drives one outbound pushed request per call (threat model BFF-20);
  - whether to refuse a configuration in which both key files name the same file (the published key is then also the DPoP proof key);
  - re-measuring the cookie size with the `cnf` claim present;
  - the engine's nonce-retry gaps, and a refused token response discarding its refresh token instead of revoking it;
  - `oidc.login.path` and `oidc.user_info.path` keeping their lack of a canonical-form check;
  - three imprecise executable texts left as they are (an exception message naming only "private key member", a DEBUG line saying "for the rest of the process", and "every failure refuses the login with 502");
  - no conformance-suite run was made, so no conformance or certification claim exists.
- **Remote leftover.** The branch `feature/plan-v02-08-fapi-2-0-conformance-docs` on the remote held the documentation during the split and is now obsolete.
