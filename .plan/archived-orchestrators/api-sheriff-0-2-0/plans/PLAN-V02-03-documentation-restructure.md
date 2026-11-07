# PLAN-V02-03: documentation restructure — split the monolith, purge the archive, retire `doc/plan`

epic: api-sheriff-0-2-0
workstream: WS-02
track: **POST-0.1.0** — gated behind the release cut. **Two former deliverables were pulled forward
into PLAN-08B and ship with 0.1.0; see § Deliverables.**

> Staged plan spec — one shippable unit of work, ready for `/plan-marshall` hand-off.
> Source: operator code review, 2026-08-01. Ground truth read first-party at `b903526`.
> The orchestrator EMITS the command below; it never launches the plan inline.

> **Renumbered 2026-08-04.** This spec was `PLAN-40-documentation-restructure.md` in the retired `api-sheriff-next`
> backlog epic. In-body references to other `PLAN-NN` numbers were deliberately **not**
> rewritten: many point at `api-sheriff-roadmap` plans that keep their numbers. Resolve any
> such reference through the renumbering map in this epic's `epic.md`.

## Re-Grounded 2026-08-08 at `963e422` (== `origin/main`, clean tree)

Epic-wide re-grounding pass at `decompose`. **This section outranks any conflicting line below it.**

**D2 IS MATERIALLY BIGGER THAN SCOPED.** `doc/configuration.adoc` is **2539 lines, not 1840** — it
grew ~38% while this spec sat staged. Re-estimate the split before scoping it.

**D2 HAS MORE DESTINATIONS THAN THE SPEC KNOWS.** `doc/user/` now holds **eight** files, not five:
the spec's `tls-edge.adoc`, `bff-cookie.adoc`, `bff-session.adoc`, `protocol-routes.adoc` and
`README.adoc`, **plus `compose-sample.adoc`, `container-image.adoc` and
`environment-variable-overrides.adoc`**. Three more subjects already have an owning page, so fewer
new pages are needed than the spec assumes — and `environment-variable-overrides.adoc` is the file
Open Defect (5) is about (below).

**D1 — bigger inventory than the spec's "three entries".** `doc/archive/` is still 108 KB, but holds
**10 files**: `README.adoc`, `manifest.adoc`, and `others/` carrying eight competitor analyses
(`apache-apisix`, `excluded`, `gravitee`, `kong`, `krakend`, `traefik`, `tyk`, `README`).

**D3 — CONFIRMED unchanged.** `doc/plan/` still holds exactly the 12 files the spec lists.

**DISCHARGED — the PLAN-08B D1 purge landed.** A tree-wide search finds **zero** `doc/plan/`
references anywhere in `doc/` outside `doc/plan/` itself, and none in root `README.adoc` or
`CLAUDE.md`. The spec's expected "residue from OUTSIDE `doc/`" is currently **empty** — still
enumerate at outline rather than trusting this line, but do not budget for a cleanup that is done.

**OPEN DEFECT (5) IS LIVE — DO NOT STRIKE IT.** The epic ledger says *"IF PLAN-08B D1 CLOSES IT
BEFORE THE CUT, STRIKE THIS; verify rather than assume."* Verified: it did **not**.
`doc/user/environment-variable-overrides.adoc` carries 18 `SHERIFF_`/`QUARKUS_` entries and **neither
`SHERIFF_CLIENT_SECRET` nor `SHERIFF_SESSION_KEY`** (control query passes — the grep works on that
file). Both names *are* carried in five other files (`doc/configuration.adoc`,
`doc/user/bff-cookie.adoc`, `doc/user/bff-session.adoc`, `doc/variants/02-bff-session.adoc`,
`doc/variants/03-bff-cookie.adoc`), so the fix is to make the *enumerating* page agree with them.
**0.1.0 and 0.1.1 both shipped with this gap.** Preserve the distinction the fix must not lose:
fixed runtime keys (`QUARKUS_*`) versus author-chosen placeholders — `SHERIFF_CLIENT_SECRET` is a
convention shown in examples, not a fixed name. And do **not** "fix" the page's deliberate exclusion
of issuer identity, audience and JWKS location: that is policy, not a deployment-bound value.

**COLLISIONS EXPIRED.** PLAN-31B and PLAN-37 have both shipped in `api-sheriff-roadmap`. They are
landed diffs to read, not concurrent editors to sequence against. The `⚠ COLLIDES WITH … RUNNING NOW`
warnings below are historical.

**DO NOT SWEEP** the two FAPI documents (`doc/fapi_status.adoc`, `doc/fapi_next_steps.adoc`) — they
are `PLAN-V02-08` D6(b)'s subject. `doc/features-analysis.adoc` likewise.

## Objective

The documentation tree carries two structural defects: a 1840-line monolithic configuration
reference, and a 108 KB archive of superseded material — plus a `doc/plan/` tree superseded by the
orchestrator ledger. **The two hygiene defects that were also here — migration notes in a product
that has never been released, and user docs cross-referencing internal planning — moved to PLAN-08B
and ship with 0.1.0.**

## Deliverables

**Three deliverables** — structural documentation work that is deliberately post-cut, because
restructuring docs while the release is being audited fights PLAN-08B.

**Scope boundary, not history**: the migration-note and planning-reference purges are **PLAN-08B D1's
work**, shipping with 0.1.0. This plan does not re-do them. On start, verify they landed — a partial
or missing purge is a finding to report, and the residue returns here.

1. **Delete `doc/archive/` entirely.**
   **OBSERVED**: 108 KB — `README.adoc`, `manifest.adoc`, and an `others/` subtree holding competitor
   analyses (e.g. `others/kong.adoc`). Operator instruction is unqualified: *"Completly remove"*.
   **Enumerate inbound links before deleting** and fix every one — a dangling xref is a broken build
   in AsciiDoc, not just a bad link. **Report anything found that looks load-bearing rather than
   silently deleting it**; the instruction is to remove the archive, not to lose a document that
   turns out to be referenced from a live page.

2. **Split `doc/configuration.adoc` into per-aspect documents; keep it as the entry point.**
   **OBSERVED**: 1840 lines. **OBSERVED**: `doc/user/` already holds the correct destinations —
   `tls-edge.adoc`, `bff-cookie.adoc`, `bff-session.adoc`, `protocol-routes.adoc`.
   Move each configuration aspect **to the document that already owns that subject**, leaving
   `configuration.adoc` as a navigational index. **This is by design, per the operator — the monolith
   is being deliberately broken up, not merely reorganised.**
   **The deduplication rule is the load-bearing half**: where the split creates duplication between
   the monolith and a destination page, **the duplicate is replaced by a link — never left as a
   second copy.** Two copies of a config table drift, and the reader cannot tell which is current.
   Create new `doc/user/` pages where no destination exists yet.

3. **Verify `doc/plan/` is fully superseded by the orchestrator ledger, then delete it.**
   **The verification gates the deletion and must be reported per file — a bulk "verified" is not
   acceptable for a delete of this size.**
   **OBSERVED**: `doc/plan/` holds 12 files — `01-base-implementation`, `02b-reconciliation`,
   `03-endpoint-anchors`, `04-request-pipeline`, `04b-comparative-benchmark`, `05-protocol-processors`,
   `06-tls-edge`, `07-bff-server-session`, `08-bff-cookie`, `09-release-readiness`,
   `10-anchor-types-assets`, `README`.
   **HYPOTHESIS — the mapping to the orchestrator epic**, which the plan must confirm per row:
   `02b→PLAN-01`, `03→PLAN-02`, `04→PLAN-03`, `04b→PLAN-09`, `05→PLAN-04`, `06→PLAN-05`,
   `07→PLAN-06`, `08→PLAN-07A/07B`, `09→PLAN-08A/08B`, `10→PLAN-10`.
   **⚠ The numbering deliberately disagrees** — this is the known epic renumbering that already
   caused four status rows to carry prefixes disagreeing with their queue numbers. **Do not resolve
   the mapping by matching numbers; match by content**, against
   `.plan/local/archived-orchestrators/api-sheriff-roadmap/landings/` and the staged specs.
   `01-base-implementation` has **no obvious counterpart** and is the most likely to hold unsuperseded
   content — **check it first and hardest**.
   **If any file is NOT superseded, do not delete it — report it.** Then remove the directory and
   every inbound reference. **Note PLAN-08B already removed the planning references from `doc/`** —
   so what remains here is references from OUTSIDE `doc/` (root `README.adoc`, `CLAUDE.md`, spec
   files). Enumerate rather than assuming 08B caught them all.

## Claim Labels

- **OBSERVED** (`b903526`, first-party): `doc/archive/` size (108 K) and its three entries;
  `doc/plan/`'s 12 filenames; `doc/configuration.adoc` at 1840 lines; `doc/user/`'s five files;
  the `doc/user/README.adoc` seed lines at `:14`, `:148`, `:163`, `:165`, `:170`, `:180`;
  `doc/development/integration-test-topology.adoc:16`; `doc/development/release-process.adoc:30`.
- **HYPOTHESIS (verify-at-outline)**: the `doc/plan` → orchestrator mapping in D3.
  **Confirm/refute artifact**: each landing report under
  `.plan/local/archived-orchestrators/api-sheriff-roadmap/landings/` compared to the plan document's content.
  **This is an asserted-supersession claim gating a delete — the highest-risk claim class in this
  spec, and the verify-first contract's absence rule binds hardest here.**
- **HYPOTHESIS (verify-at-outline)**: that `doc/archive/` has no load-bearing inbound link.
  **Confirm/refute artifact**: an enumeration of xrefs targeting `doc/archive/**` before the delete.
- **OBSERVED (absence)**: the orchestrator did **not** read any `doc/plan` file's contents, did
  **not** verify a single supersession claim, did **not** enumerate inbound links to `doc/archive/`,
  and did **not** determine how `configuration.adoc`'s 1840 lines partition across destinations.

## Open question routed to this plan — not a deliverable

The operator asked, of `configuration.adoc` § *7. Endpoint Configuration Files (endpoints/*.yaml)*:
*"`host: api.example.com` — isn't that a topology reference needed here?"*
**Answer it during D2** rather than moving the section unchanged. **ADR-0004 is
`topology-indirection`** (OBSERVED, `doc/adr/0004-topology-indirection.adoc`), so a literal hostname
in an endpoint example may well contradict the project's own accepted indirection model. **If it
does, the example is wrong and fixing it is in scope; if the literal is correct at that layer, say
why.** Do not silently relocate a possibly-wrong example.

## Expected Surface

- OBSERVED: `doc/archive/**` — deleted, D1
- OBSERVED: `doc/plan/**` — deleted after verification, D3
- OBSERVED: `doc/configuration.adoc` — reduced to an index, D2
- OBSERVED: `doc/user/**` — expanded destinations, D2
- OBSERVED (absence, deliberate): `doc/development/**` — the purge that touched it moved to PLAN-08B
- HYPOTHESIS: `README.adoc` at repo root and any other inbound linker — enumerate
- OBSERVED (absence, deliberate): **no `doc/adr/**` content rewrite.** ADRs record superseded
  decisions by design. **No code change of any kind.**

## Dependencies and Sequencing

- **HARD GATE: `api-sheriff-roadmap` must be CLOSED** — but see the pull-forward note below.
- **⚠ COLLIDES WITH PLAN-31B, RUNNING NOW** — 31B edits `doc/configuration.adoc`,
  `doc/architecture.adoc`, `doc/user/` and `doc/LogMessages.adoc`. A wholesale split of
  `configuration.adoc` against a concurrent editor is the worst possible pairing. **Strictly after.**
- **⚠ COLLIDES WITH PLAN-37** — 37 rewrites `doc/user/README.adoc` and `doc/configuration.adoc` for
  the `none`→`minimal` rename, and **37 owns that rename's documentation**. Sequence after 37, and
  **do not re-do 37's rename here**.
- **⚠ OVERLAPS PLAN-08B D1** (three-layer documentation audit). **Boundary**: 08B audits *content
  correctness* against what shipped; this plan changes *structure and location*. If 08B has not
  landed, this plan does not start.
- **The migration-note and planning-reference purges belong to PLAN-08B D1**, not here — see the
  scope boundary above. Verify they landed on start.

## Standing Epic Clauses

- **SONAR ZERO-FINDINGS** — red is a HARD STOP (docs-only, but the gate still runs).
- **NAMED LINE ITEMS** — three named deliverables; D3's per-file verification is **not** foldable into
  its delete.
- **STAMP THE HEAD** — premises carry HEAD `b903526`; **line numbers in D2/D3 will drift** as PLAN-31B
  and PLAN-37 edit these same files. Re-anchor by content, not by line.

## Finalize Boundary — the plan STOPS at the merge

**Operator ruling, 2026-07-30.** The plan owns everything through the merge, then REPORTS AND STOPS.

## Hand-Off Command

```text
/plan-marshall task="implement .plan/local/orchestrator/api-sheriff-0-2-0/plans/PLAN-V02-03-documentation-restructure.md" plan_id=plan-v02-03-documentation-restructure
```

**The explicit `plan_id` is load-bearing — do not drop it.**

## Write-Boundary

The executing plan MUST NOT create or edit any file under
`.plan/local/orchestrator/api-sheriff-0-2-0/`. Its two channels back to the epic are its PR and its
`inbox/` OUTBOX.
