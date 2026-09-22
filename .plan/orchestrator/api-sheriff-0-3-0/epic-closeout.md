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

**None directly.** This epic sits behind `api-sheriff-0-2-0`; anything it should own arrives through
that epic's close-out, not from `api-sheriff-roadmap`.

## Memory

Project-scoped and shared. Re-read entries against ground truth rather than trusting summary lines.
