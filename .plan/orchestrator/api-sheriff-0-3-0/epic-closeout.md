# Close-out state — api-sheriff-0-3-0

**The mechanism lives in the `epic-closeout` skill** (`.claude/skills/epic-closeout/SKILL.md`). Run
it with this epic's slug; it disposes and never closes.

**This document is state only** — what *this* epic has pending, never how any of it is done.

> **Created 2026-08-08 by the `api-sheriff-roadmap` close-out (step 7).**

## Bundle round

- **Round numbering starts at 1 in this epic.** Do NOT continue any predecessor's sequence.
- `bundle-handoff-index.md` and `archive/bundle-handoffs/` were implanted 2026-08-08 with the two
  rules learned expensively two epics back: **the index is the durable dedup base, kept forever**,
  and **rows flip `UNSENT` → delivered in the same action as the send**.
- **No rows yet.**

## Ledger sweep candidates

None yet. When sweeping, prefer **one named class** in the carrier over N local-looking rows — the
`api-sheriff-roadmap` close-out's dominant finding was a single class (*a mechanism reporting a clean
result it never computed*) with nine instances that had each looked local.

## Lessons

**Corpus is global to the repository and was drained to zero on 2026-08-08.** This epic drains
whatever has accumulated by the time it closes — including anything `api-sheriff-0-2-0` leaves.

**The consume convention must be identical in every epic**: archive to
`archive/lessons-consumed/{lesson-id}.md`, **persist-then-remove**, and **archive discards with their
rationale**.

## Defects and watches inherited

**Received 2026-11-07 from the `api-sheriff-0-2-0` close-out** — all written into `epic.md`, each entry
naming its 0.2.0 source:

- `epic.md` § Open Defects — (1) the integration-lane assertion-strength work list (was 0.2.0 OD 4).
- `epic.md` § Watches — 13 entries, including the consolidated edge/HTTP2/IT flake family (the only
  cross-epic tracker for the framing-corpus flake; kidicap was handed text to repoint at it) and the
  security-notes pointer (by reference only).
- `epic.md` § Standing Rules — the 0.2.0 standing rules and the five `api-sheriff-roadmap` rules,
  verbatim; this is now their only copy.
- WS-04 and PLAN-V03-05 — ten consumed lessons whose rules belong in repository docs.
- The release-cut carry (FIRST REAL RELEASE checklist, steward run, APISIX lane, release-skill ADR line),
  the Release Pre-Flight Knowledge and the dry-run Deferred Item — moved here when the operator closed
  0.2.0 before its next release (2026-11-07). This epic now owns the `0.2.5` release.

The source-side record (every 0.2.0 entry with its disposition) is
`api-sheriff-0-2-0/epic-closeout.md` § "Close-out disposition — 2026-11-07".

## Memory

Project-scoped and shared. Re-read entries against ground truth rather than trusting summary lines.
