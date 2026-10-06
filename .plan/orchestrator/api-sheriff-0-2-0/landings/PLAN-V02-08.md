# Landing Analysis: PLAN-V02-08 — FAPI 2.0 Conformance for the Confidential-Client Flow

epic: api-sheriff-0-2-0
workstream: WS-04
pr: #377 (code, tests, integration stack, ADR-0058) and #378 (documentation)

> Landing record for one shipped plan. Written by the `analyze` verb on 2026-10-03 from the inbox
> message `plan-v02-08-fapi-2-0-conformance-010.md` (inbox-scan mode, `landing-check`
> `complete: true`), after verifying each material claim against ground truth.

## Deliverable Fidelity vs Spec

The plan reports 12 of 12 deliverables done. The spec carried six (D1–D6); the plan's outline
re-cut them into twelve. Checked against the two merge commits on `origin/main` and the PR state
read through the CI abstraction.

| Spec deliverable | Verdict | Evidence |
|------------------|---------|----------|
| D1 — route selection and the sender-constraint seam | shipped-as-specified | The DPoP route was taken (mTLS client authentication is foreclosed upstream). ADR-0058 records it: *the BFF pushes every authorization request and binds its tokens with DPoP and authenticates with `private_key_jwt` unless a client secret is configured*. |
| D2 — Pushed Authorization Requests | shipped-as-specified | PR #377 title `feat(bff)!: require PAR and DPoP, default to private_key_jwt`; ADR-0058 § PAR. |
| D3 — client authentication | shipped-as-specified | `private_key_jwt` by default; `client_secret_basic` when `oidc.client_secret` is set; a secret and a key file together are refused (ADR-0058). |
| D4 — sender constraint wiring and key lifecycle | shipped-as-specified | One DPoP key; a token response not bound to it is refused. Keys are provided (an unencrypted PKCS#8 PEM file named by path, carrying its `PUBLIC KEY` block too) or generated at startup (EC P-256). Rotation is by file replacement; no overlapping-key support. |
| D5 — trust and key-material plumbing, config surface, native registration | shipped-as-specified | ADR-0058 § 3 and the key-file format section; the client key is published at an unauthenticated JWKS endpoint, `oidc.client_authentication.jwks_path`, default `/auth/jwks`. |
| D6 — `cnf` forwarding contract and documentation reconciliation | shipped-modified | The documentation half landed separately as #378 (31 files), because a required reviewer refuses pull requests above 100 files. Both are merged. |

The decision record is **ADR-0058**; it was renumbered twice during the plan because sibling
records landed first. `doc/adr/` on `origin/main` has no duplicate ordinal.

## Metrics and Anomalies

- Tokens: 29,861,200 total.
- Duration: 39h08m wall (140,929 s), across two days and one overnight pause.
- Anomalies, as reported by the plan and consistent with the merge record:
  - The pre-submission self-review did not converge: six rounds, `may_close=no` on each, closed by
    operator decision at the loop-back ceiling. It was not repeated after the final merge with
    `main`.
  - `main` moved by seven commits during finalize; the branch conflicted on 24 files after the PR
    was opened and needed a re-integration with the session-widening change. The security audit was
    not repeated over that merge delta; it is covered by unit tests and one integration run.
  - The required reviewer's last review covers the commit before the head (a one-line schema
    description change it had asked for); the second required reviewer reviewed the head.
  - The merge queue landed #377 about seven minutes after the plan's 1800 s landing wait expired;
    cleanup ran on a fresh finalize entry.
- All eight process findings were filed as candidate lessons and are promoted (see Reconciliation).

## Routing and Merge Behavior

- Review: zero unresolved resolvable threads on #377 and on #378 (every unresolved row carries an
  empty `thread_id`).
- CI/merge: #377 — 34 checks, all green; #378 — 22 checks, all green. Both merged through the merge
  queue (#377 as `e8db85bf`, #378 as `3a1182e5`). No collision with a concurrently running plan of
  this epic; the conflicts came from the session-widening work on `main`, which is not in this
  epic's queue.
- Post-merge, checked by the orchestrator 2026-10-03:
  - PR-attached `Run Integration Benchmarks`: success on #377 (run 37052921870) and on #378 (run
    37063917290).
  - Main-branch push runs for `e8db85bf`: `Integration Tests`, `Demo Client E2E`, `Scorecard`
    success; **`Maven Build` (run 37052919689) failure — `build / deploy-snapshot` failed** with
    `Could not transfer artifact de.cuioss.sheriff.gateway:api-sheriff:jar:0.2.4-… from/to central
    (https://central.sonatype.com/repository/maven-snapshots/): HTTP Status: 401`. Every other job of
    that run succeeded.
  - Main-branch push runs for `3a1182e5`: all success; `deploy-snapshot` skipped (documentation-only).
  - **The 401 is not caused by this plan.** The same failure hit `8d7445c1` (run 37010757190) before
    #377 merged and `cd383c2e` (run 37086429426) after it; the last successful snapshot deploy was
    `e445e299`. It is a publishing-credential problem on `main`, recorded as an Open Defect.

## Reconciliation Actions

- [x] row `status` → `shipped`; `pr` stamped `377`; `landing` stamped `landings/PLAN-V02-08.md`;
      `plan_marshall_plan_id` already stamped `plan-v02-08-fapi-2-0-conformance`
- [x] inbox `plan-v02-08-fapi-2-0-conformance-010.md` archived (`reconciled`); the eight candidate
      lessons `-002` … `-009` promoted to `2026-10-03-06-001` … `-008` and archived
- [x] the held Watch "align the cookie sealing key with the file-based key model" retired by staging
      `PLAN-V02-20`
- [x] Open Defect added: snapshot deploy to Central fails with HTTP 401 on `main`
- [x] Watch added: the post-FAPI follow-ups the plan leaves (below)
- [x] dependent specs corrected in place: `PLAN-V02-12`, `PLAN-V02-09`, `PLAN-V02-15` no longer wait
      on this plan; `PLAN-V02-12` D4 gains the `/auth/jwks` reserved path
- [x] resume anchor updated; `queue-view.md` regenerated and committed with the row changes

## Follow-Ups

- **Staged:** `PLAN-V02-20` — the cookie sealing key provided the same way as the two signing keys.
- **Recorded as a Watch, no plan staged** (all already stated in the merged PRs or documents):
  - a rate limit in front of login initiation, which now drives one outbound pushed request per
    call (threat model `BFF-20`);
  - whether to refuse a configuration in which both key files name the same file (the published key
    would then also be the DPoP proof key);
  - re-measuring the cookie size with the `cnf` claim present;
  - the engine's nonce-retry gaps, and a refused token response discarding its refresh token
    instead of revoking it;
  - `oidc.login.path` and `oidc.user_info.path` still have no canonical-form check;
  - three imprecise executable texts (an exception message naming only "private key member", a
    DEBUG line saying "for the rest of the process", and "every failure refuses the login with 502");
  - two answers chosen conservatively at the merge: a refused push on the interactive widening
    re-drive answers `502` (alternative: the widening's terminal `403`), and an unbound token on a
    widening callback answers `400` with ApiSheriff-134;
  - no conformance-suite run was made, so no FAPI conformance or certification claim exists.
- **Operator action:** the remote branch `feature/plan-v02-08-fapi-2-0-conformance-docs` held the
  documentation during the split and is obsolete. Not deleted by the orchestrator.
- **Delivered but never read:** the orchestrator's mailbox finding
  `inbox/to/plan-v02-08-fapi-2-0-conformance/orchestrator-001.md` (the ADR ordinal collision) is
  still `unconsumed`; the plan resolved the collision on its own.
