# Close-out state — api-sheriff-0-2-0

**The mechanism lives in the `epic-closeout` skill** (`.claude/skills/epic-closeout/SKILL.md`). Run
it with this epic's slug; it disposes and never closes.

**This document is state only** — what *this* epic has pending, never how any of it is done. Do not
reintroduce mechanism here; the predecessor let it drift into two copies once and it was promoted
out precisely so a closed-and-archived tree would not own it.

> **Created 2026-08-08 by the `api-sheriff-roadmap` close-out (step 7).** That epic is dispositioned;
> whether it is closed is a separate operator decision.

## Bundle round

- **Round numbering starts at 1 in this epic.** Do NOT continue the predecessor's sequence — it
  reached round 8 and its carriers are named for it.
- `bundle-handoff-index.md` and `archive/bundle-handoffs/` were implanted 2026-08-08 and carry the
  two rules learned expensively next door: **the index is the durable dedup base, kept forever**, and
  **rows flip `UNSENT` → delivered in the same action as the send**.
- **Round 1 already carries three rows, all UNSENT**, handed over because the predecessor's round 8
  had shipped and it has no round 9:
  1. `tools-integration-ci` — the main-branch `deploy-snapshot` check has no sanctioned execution
     path. **This was the ledger sweep's one genuine miss across eight rounds** — it had never
     reached the predecessor's index at all.
  2. `plan-marshall` — an agent's report that it fixed a config file is not evidence the file changed.
  3. `manage-lessons` / `lessons-capture` — a lesson written in an unparseable header format that
     `list` reported with empty fields and `remove` could not find.
- **Read the row count from `bundle-handoff-index.md`, never from here.** A second copy of a count is
  a second thing to go stale — the predecessor's document said 26 while its index carried 27.

## Ledger sweep candidates

Nothing accumulated yet. When sweeping, note that the predecessor's dominant finding was a **class**,
not a list: *a mechanism reporting a clean result it never computed* — nine instances in one round,
across search, gates, assessments and the lessons corpus itself. **Prefer one named class in the
carrier over N local-looking rows.**

## Lessons

**Corpus is at zero as of 2026-08-08**, drained by the predecessor's close-out (25 consumed). The
corpus is **global to the repository**, so this epic drains whatever accumulates from here.

**The consume convention is identical in every epic and must stay so**, or which lessons keep an
audit record becomes arbitrary: archive to `archive/lessons-consumed/{lesson-id}.md`,
**persist-then-remove** (destination write → archive copy → `manage-lessons remove`), and **archive
discards too, with their rationale**. Twelve of the predecessor's lessons were dispositioned into
`PLAN-V02-17-lessons-into-source.md` in this epic — that plan is the open end of that disposition.

## Defects and watches inherited

- **(6)**, **(7)**, **(8)** — re-homed from the predecessor on 2026-08-04 and **verified present in
  this tree** by the 2026-08-08 audit.
- **Open Defect (53)** → `PLAN-V02-05` deliverable 5 (forward-all / `not_modified` asymmetry).
- **Open Defect (54)** → `PLAN-V02-16` (image metadata fidelity).
- **Five standing rules** inherited into the anchor's `STANDING RULES INHERITED` block.
- **The 0.1.x pre-flight knowledge** is in the anchor's `HARVESTED PRE-FLIGHT KNOWLEDGE` block. **Read
  it before the 0.2.0 cut** — it is the only copy, and it includes the one still-owed item: reading
  the pinned reusable workflow's checkout depth, which is what actually settles whether the release
  guard works.

## Memory

**21 files** at 2026-08-08 after the predecessor's consolidation. Re-read each against ground truth
rather than trusting its summary line — that pass found a memory whose *index line* still carried a
prescription the *body* had already refuted.

## Pending state — refreshed 2026-08-09 (4 of 17 plans shipped)

**Nothing here is close-out-ready.** The epic is mid-flight; this section is the running list of what
close-out will have to dispose when the time comes.

- **Open Defects with homes** — (1) → V02-05, (10) → **successor epic, becomes actionable at the 1.0
  cut and 0.2.0 CANNOT close it**, (12) → V02-01 (conditional), (13) → folded into V02-09, (14) →
  V02-11. **(2), (3), (4), (6), (7), (8), (9), (11) still have no owning plan** and are the
  close-out's largest disposition batch.
- **(8) needs an OPERATOR live-gate check**, not plan work, and has needed one since 2026-08-04.
- **A `/marshall-steward` run is owed by two independent routes** — Watch (31)'s config pairing, and
  the stale architecture-inventory description left by V02-03 D3. Neither is fixable by any plan or
  by the orchestrator.
- **Bundle round 9 is UNSENT** — 14 items in `bundle-handoff-index.md`. Flip to delivered in the same
  action as the send.
- **The post-merge `deploy-snapshot` axis is OWED for four merge commits** — `89a3cfe`, `e343404`,
  `aeb80c5`, `95dd566`. Unreachable through the CI abstraction; re-verified by execution 2026-08-09.
  It will still be owed at close-out unless the tooling changes.
- **`cuioss/TokenSheriff#641` is open upstream** and its resolution would materially shrink V02-09.
  Check it at that plan's outline, **against the resolved jar rather than the issue thread**.
- **The release dry-run brief is still a direct-session item, not a queue item** — see epic.md
  § Deferred Item. Its hard gate has opened; the brief itself is unstarted.
