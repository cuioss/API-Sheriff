# plan-marshall bundle hand-offs — delivery index (`api-sheriff-0-2-0`)

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
> carriers so provenance is readable (`api-sheriff-0-2-0` round 1 item 3).

| Round | Delivered | Items | Carrier on disk |
|-------|-----------|------:|-----------------|
| _(none yet)_ | | | |

## Round 1 — not yet compiled

Two items were handed to this epic by the `api-sheriff-roadmap` close-out because that epic's round 8
had already been delivered and it has no round 9. **They are unfiled findings, not delivered ones** —
they belong in this epic's round 1.

- **(round 1 item 1) `plan-marshall:tools-integration-ci` — the main-branch `deploy-snapshot` check
  has NO sanctioned execution path. UNSENT.** Carried from `api-sheriff-roadmap` defect (16), which
  the close-out ledger sweep found had **never reached that epic's bundle index** across eight
  rounds — the one genuine miss in the sweep. The check is SKIPPED on every PR by design, so the
  watch requires a **by-merge-commit lookup**; `ci checks status` accepts only `--pr-number`/`--head`
  and refuses `main`. **The consequence is not theoretical**: the orchestrator had to discharge this
  check by hand after every single merge in the epic, via `gh run list --branch main` — i.e. by
  dropping out of the abstraction the rule says to use. **The ask: a sanctioned way to read a
  main-branch run by merge commit, or an explicit statement that this is out of scope so the
  fall-back stops being a silent rule violation.**
- **(round 1 item 2) `plan-marshall` — an agent's report that it fixed a config file is not evidence
  the file changed. UNSENT.** Carried from corpus lesson `2026-08-02-15-005` (PLAN-11): a dispatched
  agent reported having fixed an unregistered verify-step canonical in `.plan/marshal.json`, and the
  blocker was struck from the work list **on the strength of that report**. It had not been fixed.
  This is the same family as the predecessor's cluster-A findings — a confident result nobody
  computed — but at the *agent-report* seam rather than a tool seam, which makes it the one instance
  no tool fix addresses. **The ask: dispatched-agent claims that clear a blocker should require a
  disk-state assertion, not a report.**
- **(round 1 item 3) `plan-marshall:manage-lessons` / `finalize-step-lessons-capture` — a lesson
  written by `lessons-capture` used a header format the corpus reader cannot parse, and `list`
  reported it as present with EMPTY metadata rather than as malformed. UNSENT.** Found 2026-08-08 by
  the `api-sheriff-roadmap` close-out while draining the corpus to zero. Lesson `2026-08-08-11-001`
  (written by PLAN-51's own `lessons-capture` step) carried a **markdown-bullet header**
  (`- **id**: …`, `- **component**: …`) where every other lesson in the corpus carries `key=value`
  frontmatter. Two consequences, and the second is the dangerous one:
  1. `manage-lessons list` rendered the row with `component=""` and `category=""` — **a well-formed
     row with silently empty fields, not an error**. A triage that groups by component would have
     dropped it.
  2. `manage-lessons remove --lesson-id 2026-08-08-11-001` returned **`Lesson … not found`** for a
     file that demonstrably exists and that `list` had just displayed. **The two verbs disagree about
     whether the lesson exists**, so a corpus drain reports success while leaving it behind.
  The close-out had to hand-normalize the header before the sanctioned `remove` would resolve it.
  **This is the predecessor's cluster-A shape — a clean result over something never actually parsed —
  reaching the lessons corpus itself, i.e. the very mechanism that records such findings.** The ask:
  make the writer and the reader share one format, and make `list` report a malformed lesson as
  malformed rather than as one with empty fields.

## Round 9 — `api-sheriff-0-2-0`, accumulating

**Status: UNSENT.** Compiled from the four landings of 2026-08-09 (`PLAN-V02-02`, `-03`, `-16`,
`-17`) plus the two lessons the plans filed directly. **14 items.** Flip these to delivered in the
SAME ACTION as the send, per rule 2 above.

| Lesson | Component | Delivery |
|---|---|---|
| `2026-08-09-03-001` | plan-marshall:manage-architecture | UNSENT |
| `2026-08-09-03-002` | plan-marshall:phase-6-finalize | UNSENT |
| `2026-08-09-07-003` | plan-marshall:automatic-review | UNSENT |
| `2026-08-09-07-004` | plan-marshall:marshall-orchestrator | UNSENT |
| `2026-08-09-07-005` | plan-marshall:tools-script-executor | UNSENT |
| `2026-08-09-07-006` | pm-plugin-development:ext-self-review-plan-marshall | UNSENT |
| `2026-08-09-07-007` | pm-documents:ref-asciidoc | UNSENT |
| `2026-08-09-07-008` | plan-marshall:build-maven | UNSENT |
| `2026-08-09-07-009` | plan-marshall:build-server-client | UNSENT |
| `2026-08-09-18-006` | plan-marshall:phase-3-outline | UNSENT |
| `2026-08-09-18-007` | plan-marshall:automatic-review | UNSENT |
| `2026-08-09-18-008` | plan-marshall:tools-integration-ci | UNSENT |
| `2026-08-09-19-001` | plan-marshall:phase-5-execute | UNSENT |
| `2026-08-09-19-003` | plan-marshall:automatic-review | UNSENT |

**Three clusters worth triaging together rather than item by item:**

- **The clean result that was never computed — the predecessor's Cluster A, recurring.** A
  goal-prefix invocation that dies before the gate runs; a self-review surfacer returning `0` over a
  non-Python footprint; `asciidoc verify-links` reporting `0 broken` while parsing only half the link
  forms; an empty assessment store making outline validator 2.2 unevaluable. **Four more costumes for
  one bug**, and the predecessor delivered nine. It has not landed.
- **Automatic-review filters keyed on incidental text — three items, all new.** The Sourcery OSS
  branding footer in `ignore_patterns` (a whole-comment drop that makes Overall Comments
  *structurally* unfileable on any OSS repo); the start-anchored self-response filter refiling an
  agent's own replies as inbound findings; and a bot's "will not compile" verdict refuted by four
  green compile jobs on the same head. **All three share one shape: a filter or verdict keyed on text
  that is present for reasons unrelated to what it is trying to match.**
- **Argparse rejection under a loaded rule — two items, and the orchestrator contributed its own
  instances in the same sessions.** `persona-plan-marshall-agent` already forbids inventing
  subcommands and the rule was loaded both times. **Prose does not bind at call-construction time;
  only `--help` does.**
