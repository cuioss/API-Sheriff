# Landing Analysis: PLAN-08 — Plain-HTTP Termination Mode (Ingress / Sidecar Deployments)

epic: deployment-configurability
workstream: WS-06
pr: [#286](https://github.com/cuioss/API-Sheriff/pull/286) — merged as `7fce677`, 2026-09-10 22:23 UTC

> Landing record written by `analyze` on 2026-09-11 from the operator's paste, **corroborated
> against ground truth** — the real merge commit, the real diff, the real CI check set and the
> real files at HEAD. ⛔ **The paste arrived by hand, not through the channel**: `emit-landing`
> ran and **skipped** (see Routing), so nothing reached `inbox/`.

## Deliverable Fidelity vs Spec

The staged spec had **re-scoped itself** before launch: PLAN-07 (#283) had already shipped
deliverables 1, 2 and 5, which the spec retired with *"do not re-ship them."* What landed is the
re-scoped plan, and the numbering in the paste is the **re-scoped** numbering, not the original
spec's. Both are recorded so a later reader does not mis-join them.

| Landed deliverable (paste) | Maps to spec | Verdict | Evidence checked at `7fce677` |
|---|---|---|---|
| 1. Three incoherent combinations refuse at boot (certificates, mTLS, SNI passthrough) | D2 remainder + D3 | shipped-as-specified | `ServerTlsDeclarationGate.java` +199, `ServerTlsDeclarationGateTest.java` +337 — the spec named exactly these three refusals |
| 2. Four interaction verdicts recorded; two false claims corrected | D3 | shipped-as-specified | `doc/user/tls-edge.adoc` +85, `doc/security-threat-model.adoc` +35 |
| 3. End-to-end proof on the existing integration surface | D4 | ✅ **shipped-as-specified** (corrected 2026-09-11 — see below) | `NoCertificatePlainHttpOptInIT.java` +500 on the **existing** surface. ⚠ The spec asked for a **new** `integration-tests/src/main/docker/sheriff-config-plain-http/` instance; that directory was **not** created. The proof landed without a new instance |
| 4. Issue #285 — ten dead trust-store pairs deleted, spelling guard shipped | D4 fold | shipped-as-specified, **exceeded** | `integration-tests/docker-compose.yml` −22 lines; `grep -c TRUST__STORE` now returns **0**. `EnvironmentKeySpellingGuardTest.java` +406 — the spec only *"considered"* a guard and asked for the decision either way; it built one |
| 5. Runnable compose sample with a TLS-terminating hop | D4 (compose half) | shipped-as-specified | `docker-compose.plain-http.yml` +156, `docker/nginx/tls-terminator.conf` +64, `doc/user/compose-sample.adoc` +125 |

## ⛔ CORRECTION 2026-09-11 — THE DIVERGENCE BELOW IS RETRACTED. I WAS WRONG.

The paragraph that follows claimed PLAN-08 failed to create a plain-HTTP integration instance.
**It did not need to: the instance already existed, and the spec's own re-scope header told the plan
to extend rather than restate.** Verified at HEAD `7fce677`:

- `api-sheriff-no-certificate` is a live service in `integration-tests/docker-compose.yml:1032`,
  publishing **`10453:8080` plain HTTP** (*"served by the declared opt-in"*), `10454:8443` as a
  **deliberately dead** terminated HTTPS port, and `19010:9000` management. It carries the
  `de.cuioss.sheriff.management-scheme: "https"` label the spec required, and its own comment names
  it *"THE CONFIGURATION UNDER TEST, expressed as an absence."*
- ⛔ **It was added by `a30fe6f` — PR #283, PLAN-07** — established with `git log -S`. So it predates
  PLAN-08 entirely.
- It is drawn in the topology diagram at `api-sheriff-no-certificate  10453 · 10454 · 19010`.

**What I did was compare the landed diff against the spec's ORIGINAL deliverable-4 text** — *"a
`sheriff-config-*` directory … no plain-HTTP instance and no end-to-end proof"* — which was written
**before PLAN-07 landed**, and which the spec's own re-scope header had already superseded with
*"Extend what is there; do not restate it."* PLAN-08 added the end-to-end proof
(`NoCertificatePlainHttpOptInIT` +500) against the existing instance, plus a separate
**deployment** artifact (`deployment/compose-sample/docker-compose.plain-http.yml`) — a different
thing from an integration instance, which is why the file counts looked like a gap.

✅ **PLAN-08 shipped all five deliverables as re-scoped. There is no fidelity gap.** The Watch this
paragraph opened is retracted in `epic.md`.

⚠ **The lesson is mine, and it is this epic's own signature class turned on the orchestrator**: I
verified the diff against a *stale premise* rather than against HEAD, which is precisely the failure
this epic has recorded against plan specs four times. The re-scope header was in the document I
read. Original text follows.

⛔ ~~**Deliverable 3 is the one divergence, and it is a real one.**~~ The spec's D4 required *"a
`sheriff-config-*` directory, a `de.cuioss.sheriff.management-scheme` label, and host-side readiness
through the same Compose-derived loop, with **no branch on the service name** anywhere."* The diff
creates no such directory. The end-to-end proof exists and passes, but it rides the existing
surface rather than adding the dedicated instance — so **"the mode has an instance in the
integration stack" is not established by this landing.** Recorded as a Watch, not an Open Defect:
the mode *is* proven end-to-end, and whether it needs its own instance is a judgement the next
WS-06 plan should make rather than one this record should assert.

## ✅ The guard caught the eleventh instance the day it shipped

The spec argued for a **mechanism** over a third manual removal, on the strength of three prior
occurrences. Within hours a sibling PR added the same broken spelling to a new service, copied
from a neighbour; the merge queue went red and the guard named it before it landed.

⛔ **This is the strongest evidence this epic has produced for its own central thesis** — that the
*stated-rule-without-a-mechanism* class is not closed by attention, only by a mechanism. Seven
instances had been recorded by argument. This one was closed by construction and then immediately
demonstrated, under real conditions, against an author who was not looking for it.

## Metrics and Anomalies

- Tokens: 4.4M · Worked 3h32m · Wall 13h27m — wall/worked ratio **3.8×**, the widest in the epic
- Diff: 15 files, +2607/−137
- Review: CodeRabbit 2 reviews / 12 findings, all handled. Sonar 0 findings
- Gates: quality gate 202, api-sheriff verify 2087, integration 141 — all green
- ⚠ **~90 minutes lost to a CodeRabbit misread**: zero comments on an incremental re-review was read
  as *never ran* when it meant *nothing to report* — the coverage evidence was in the comment body.
  Recorded by the plan as a lesson; noted here because it is a **channel-interpretation** error, the
  same family as this epic's `emit-landing` and `ci pr merge-queue` findings

## Routing and Merge Behavior

- CI: **29 checks, overall `success`**, verified through the CI abstraction against PR #286
- Post-merge, PR-attached: `Run Integration Benchmarks` / Performance Benchmark **SUCCESS**
- ⛔ **The main-branch run for `7fce677` could NOT be reached through the sanctioned surface.**
  `ci checks status` addresses PRs only (`--pr-number` / `--head` both resolve a branch to a PR);
  there is no read verb that takes a merge commit. **This is a recurrence of the existing Open
  Defect**, not a new one — folded there rather than duplicated
- ⛔ **`emit-landing` ran and returned `outcome=skipped`** — verified in the archived
  `logs/work.log` at `2026-09-10T22:50:21Z`. The step was composed into the manifest and executed;
  it declined to deliver. **"Finalize steps 19/19" is literally true and the channel still
  delivered nothing** — the epic's own rule that a green step count is not evidence of work,
  demonstrated on the step whose entire job is evidence

## Reconciliation Actions

- [x] row `status` → `shipped`
- [x] row `pr` → `286`
- [x] row `landing` → `landings/PLAN-08.md`
- [x] row `plan_marshall_plan_id` → `plain-http-termination-mode`
- [x] Issue #285 Open Defect **retired** — 0 occurrences remain and a guard now prevents recurrence
- [x] `emit-landing` skipped-at-runtime Open Defect — **recurrence recorded** (occurrence 4)
- [x] post-merge-verification Open Defect — **recurrence recorded**
- [x] Watch opened — the plain-HTTP integration instance was not created
- [x] START-HERE and Ordered Queue blocks regenerated

## Follow-Ups

- **The missing `sheriff-config-plain-http/` instance** → Watch. No plan staged; PLAN-09's
  scenario 7 (*"Terminate TLS upstream, serve plain HTTP"*) is the natural place to decide whether
  the documented scenario needs a running instance behind it.
- **PLAN-09 scenario 7 is now unblocked** — its catalogue row said *"no — PLAN-08 builds it"*, and
  PLAN-08 has. The doc surface it must describe (`tls-edge.adoc`, `compose-sample.adoc`) exists.
- **`doc/user/compose-sample.adoc` is a NEW file** not declared in any staged spec's Expected
  Surface. PLAN-09 and PLAN-19 both sweep `doc/user/`; neither declares this file.
