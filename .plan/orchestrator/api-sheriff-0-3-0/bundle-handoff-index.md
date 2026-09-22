# plan-marshall bundle hand-offs — delivery index (`api-sheriff-0-3-0`)

**Purpose**: the durable dedup base for this epic's rounds. One line per delivered finding, forever.

> **Implanted 2026-08-08 by the `api-sheriff-roadmap` close-out**, which delivered eight rounds and
> 100+ findings through this mechanism. Two rules were learned expensively there and are carried here
> rather than re-learned:
>
> 1. **The index is the durable dedup base and is kept forever.** Rounds 1 and 2 of the predecessor
>    had their carrier documents deleted after hand-off; that destroyed the only record of what had
>    been delivered, and a later round could not be mechanically deduplicated against eighteen items.
>    **The carrier is disposable; this file is not.**
> 2. **Flip `UNSENT` → delivered in the SAME ACTION as the send**, and relocate the carrier into
>    `archive/bundle-handoffs/` in that same action. Nothing does this automatically. An index that
>    is accurate right up until the moment it matters is worse than none, because it is believed —
>    the predecessor carried a stale row for two days and compiled one round twice.
>
> **Round numbering starts at 1 in this epic.** Do NOT continue the predecessor's sequence: name
> carriers so provenance is readable (`api-sheriff-0-3-0` round 1 item 3).

| Round | Delivered | Items | Carrier on disk |
|-------|-----------|------:|-----------------|
| _(none yet)_ | | | |

## Round 1 — not yet compiled
