# PLAN-10: Land ADR-0038 — the pre-boot health probe (documentation-only)

epic: deployment-configurability
workstream: WS-01

> Staged plan spec — the emitted command is a one-line pointer, so this spec is the whole brief.

## Objective

PLAN-01 shipped a health-check mechanism that no ADR records. The distroless image carries no
executable other than the application binary, so its `HEALTHCHECK` invokes that binary with a probe
token which the entry point answers **before the framework boots**, by a bare TCP accept against the
compiled-in management port on loopback. That is a genuine architectural decision with three refuted
alternatives behind it, and it was deferred out of PR #230 to avoid re-gating a merge-ready branch.

The ADR was drafted and survives only inside this epic's ledger tree, which `.gitignore` line 20
excludes from the repository. Land it: write the ADR at its own ordinal and add its index row, so the
decision lives where every other ADR lives rather than in an untracked directory.

⛔ **This plan writes no code and changes no behaviour.** The mechanism it documents is already
merged and live at HEAD. A deliverable that touches a `.java`, a `Dockerfile`, or a compose file has
left this plan's scope — the documentation-only footprint is what makes it cheap, and one non-doc
file makes the whole commit gate-requiring.

## Deliverables

1. **Land the ADR** at `doc/adr/{NNNN}-The_distroless_images_health_check_is_answered_by_the_application_binary_before_boot.adoc`.
   ⛔ **RE-SCOPED 2026-09-02, and now SETTLED 2026-09-03: the ordinal is `0039`, not `0038`.**
   PLAN-02 shipped as PR #248 → `b200bed` and its
   `0038-A_build-time_key…carrier_key…adoc` is on `main`, which now tops at `0038`. ⚠ **Still
   re-verify at outline** — PLAN-04, PLAN-06 and PLAN-07 all declare `doc/adr/` and any of them
   landing first moves the number again. **The drafted patch and draft still say `ADR-0038` in the
   filename, the link and the label; all three must be changed to the resolved ordinal.** The
   drafted source is `.plan/local/orchestrator/deployment-configurability/landings/PLAN-01-adr-0038-draft.adoc`
   (218 lines, complete with `// adr-metadata` block, Context, Decision, Consequences and the refuted
   alternatives). ⛔ **Copy it as a file read, never by retyping or reconstruction** — the draft is the
   artifact, and a reconstructed ADR is a different document wearing its number.
2. **Add the index row to `doc/README.adoc`.** The drafted patch
   (`landings/PLAN-01-adr-0038-readme-row.patch`) applies cleanly at HEAD and its `index 53da5d8..`
   line matches the current blob exactly. ⚠ **The row goes in `doc/README.adoc`, not
   `doc/adr/README.adoc`** — the latter does not exist; the epic's Open Defect prose named it wrongly
   and that error must not be carried into the implementation.
3. **Settle the ADR's status against what actually shipped.** The draft says `Proposed` and the index
   row says `*(Status: proposed.)*`, but the mechanism is merged and live at HEAD — `Dockerfile.native:56`
   runs it and `HealthProbe.java` implements it. Decide whether `Accepted` is the honest status,
   change both sites together if so, and state the reason. ⛔ The two sites must agree; a `Proposed`
   ADR indexed as accepted (or the reverse) is exactly the drift an index exists to prevent.

## Claim Labels

- ⛔ **RE-SCOPED 2026-09-02, SETTLED 2026-09-03 — was: "`0038` is the next free ordinal".** That was
  correct at `c170779` and became false when PLAN-02 landed `doc/adr/0038-A_build-time_key…carrier_key…adoc`
  (PR #248 → `b200bed`). `main` now tops at `0038`, so **`0039` is the next free ordinal** — still to
  be re-verified at outline, since PLAN-04/06/07 all declare `doc/adr/`. Carry the resolved number
  into the filename, the index-row link and the index-row label. The under-declaration that hid this collision from the gate is recorded in PLAN-02's
  corrected Expected Surface.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: yes | evidence: reaffirmed: doc/adr/0039 exists exactly as this plan resolved (ordinal 0039, not 0038), confirming the correction stuck
- OBSERVED: the drafted README patch **applies cleanly at HEAD** — `git apply --check` succeeds at
  `c170779`, and the patch's `index 53da5d8..ad16c50` line matches `git rev-parse HEAD:doc/README.adoc`
  = `53da5d8` exactly. The insertion point is `doc/README.adoc:281`, immediately after the ADR-0037 row
  and before the closing `|===`.
  - verdict: contradicted | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: no | evidence: git apply --check on the landing patch now fails because the row it inserts is already present in doc/README.adoc:285
- OBSERVED: **`doc/adr/README.adoc` does not exist.** The ADR index lives in `doc/README.adoc`, which is
  what the patch targets. Asserted absence, verified at HEAD.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: doc/adr/README.adoc still does not exist
- OBSERVED: the mechanism the ADR records **is shipped and live** — `api-sheriff/src/main/docker/Dockerfile.native:56`
  carries a `HEALTHCHECK` invoking `/app/application --health-probe`, its header comment at `:10`
  states the binary answers its own health, and the probe logic sits in the separately-testable
  `api-sheriff/src/main/java/de/cuioss/sheriff/gateway/HealthProbe.java` with
  `HealthProbeTest.java` beside it. This is why deliverable 3 exists.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: Dockerfile.native still carries the HEALTHCHECK invoking --health-probe; HealthProbe.java/Test both still present
- OBSERVED: both drafted artifacts are present in this epic's tree and are byte-identical copies
  rescued from the ephemeral PLAN-01 session — `landings/PLAN-01-adr-0038-draft.adoc` (218 lines) and
  `landings/PLAN-01-adr-0038-readme-row.patch`. ⛔ They are **untracked**: `.gitignore:20` excludes
  `.plan/*`, so they are safe from session loss but not durable in the git sense. Landing them is the
  only fix.
  - verdict: corroborated | checked_at: cc10ce2a2c7840162bbc087242d42cbbc3cd14c3 | by: deployment-configurability/cleanup | rescoped: n/a | evidence: both landing draft and patch files still present under the ledger's landings/ directory
- Verify-first clause: re-run `git apply --check` on the patch before relying on it. It applies at
  `c170779`; any commit touching `doc/README.adoc` between staging and outline invalidates the blob
  hash, and the row must then be placed by reading the file rather than by forcing the patch.

## Expected Surface

- OBSERVED: `doc/adr/` — the new ADR, at the next free ordinal resolved at outline (⛔ NOT `0038`, which PLAN-02 has taken)
- OBSERVED: `doc/README.adoc`:281 — the ADR index row, inserted after ADR-0037

## Dependencies and Sequencing

- **Depends on: none.** PLAN-01 has shipped (PR #230); this plan records a decision already made, so
  nothing gates it.
- ✅ **Documentation-only footprint.** Per CLAUDE.md a commit whose entire footprint is `*.adoc` under
  `doc/**` skips both the quality gate and full verify. Confirm the footprint really is
  documentation-only before relying on that: one non-doc file makes the whole commit gate-requiring.
- ⚠ **Overlap REALIZED, not nominal — and by the plan nobody predicted.** This spec anticipated that
  PLAN-04, PLAN-06 or PLAN-07 might claim `0038` first, since all three declare `doc/adr/`. The plan
  that actually took it was **PLAN-02**, which declared no `doc/adr/` path at all — so the gate saw no
  collision and could not have. The contingency written here was correct and fired; only the predicted
  source was wrong. Those three remain genuine `doc/adr/` overlaps for the same reason, and the
  ordinal must be re-resolved at outline against whatever has landed by then, never against this file.
- ✅ `doc/README.adoc` is NOT touched by PLAN-02's branch, so the index-row insertion point is clean;
  the drafted patch was re-verified at HEAD `5948962` and still applies (`index 53da5d8` matches).
- Adjacent to: `doc/adr/0025` (management-TLS neutrality) and `doc/adr/0031` (host-side readiness),
  both cross-referenced from the drafted row. **Linked, never restated.**

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/deployment-configurability/plans/PLAN-10-adr-0038-preboot-health-probe.md"
```

## Write-Boundary

Touches only repository source and tests. Creates and edits NO file under
`.plan/local/orchestrator/` other than its own `inbox/{sender}-{seq}` message; reports outcome
through its PR and that message. See `orchestration-model.md` § Ledger Write-Boundary.

⛔ Note the asymmetry this plan sits on: its two SOURCE artifacts live under
`landings/`, which it READS. Reading the ledger for source material is fine; writing back into it is
not. The ADR lands in `doc/adr/`, and the drafts stay where they are.
