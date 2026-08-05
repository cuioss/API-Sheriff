---
name: epic-closeout
description: Run an orchestrator epic's close-out — everything that must be dispositioned before the epic is frozen. Takes the epic's slug. Delivers the final bundle hand-off round, sweeps the ledgers for unfiled plan-marshall material, consumes the global lessons corpus into the archive (archive, never delete), distributes open defects to successor epics with every re-homing verified rather than asserted, consolidates memory, and implants the mechanism into the successors. It disposes only — it NEVER calls `close`.
user-invocable: true
allowed-tools: Bash, Read, Edit, Write, Glob, Grep
---

# Epic close-out — API Sheriff orchestrator

Runs the close-out of **one orchestrator epic**, named by its **`slug`**. The close-out is everything
that must be dispositioned *before* the epic is frozen: the last bundle hand-off round, the ledger
sweep, the lessons corpus, the open-defect distribution, memory, and the implant into the successors.

Throughout, the epic under close-out is:

```text
EPIC=.plan/local/orchestrator/<slug>
```

That tree is the **default** root, and steps 1, 2 and 4 write only there. **Steps 3, 5, 6, 7 and 8
deliberately reach beyond it** — into repository source, project-scoped memory, and the successor
epics' trees — and each says so where it does. Read the write scope from the step, not from this
line.

**Nothing in this file names a particular epic, round number, defect id or lesson id** — see
*Mechanism vs state* below; that is the design, not an omission.

---

## THIS SKILL DOES NOT CLOSE THE EPIC

**It disposes. Closing is a separate, deliberate act.** Nothing here calls `close`, archives the
epic, or flips its status to a terminal phase. When every step below is done, **report and stop**;
the operator decides whether the epic is closed, exactly as the release cut is a separate decision
from the plan that prepared it.

A close-out that ran and a close that happened must never look alike.

## Not runnable by accident

Five preconditions, all of them before any write:

1. **The slug is given explicitly.** Never infer it from "the current epic", the most recently
   touched tree, or the only one that looks active. `ls .plan/local/orchestrator/` and confirm the
   slug exists as a directory.
2. **The epic's plans have all landed and been reconciled.** A close-out that runs while a plan is
   still in flight disposes of defects the plan is about to change, and sweeps a ledger that is
   about to gain rows. Read `$EPIC/status.json` and `$EPIC/epic.md`; check `$EPIC/plans/` against
   `$EPIC/archive/plans-shipped/` and `$EPIC/archive/plans-struck/`, and `$EPIC/inbox/` for an
   undrained OUTBOX. **Any plan not yet in a terminal state stops the close-out.**
3. **The operator has asked for it, on this epic.** Say which slug you are about to close out and
   what the sweep will touch — the successor epics' trees included — before touching anything.
4. **The epic's close-out state document exists and has been read** — conventionally
   `$EPIC/epic-closeout.md`. That is where this epic's *state* lives: which round is next, which
   defects owe verification, how many lessons are in the corpus. This skill supplies the *how*; that
   document supplies the *what*, and it is authoritative over any recollection.

   **It is required, not optional.** Without it the counts and the outstanding items have no
   authoritative source and every step below runs on recollection. If it is absent — a successor
   that never received one under step 7, or an epic predating the convention — **stop and compile it
   first**, from the epic's own artifacts and nothing else: the next round and its row count from
   `$EPIC/bundle-handoff-index.md`, the open defects and watches from the anchor, the corpus count
   from `manage-lessons list`. Then have the operator confirm it before step 1 writes anything.
5. **Only one close-out runs at a time.** Step 3 drains a corpus that is global to the repository, so
   two concurrent close-outs can each read the same lesson as unconsumed and dispose of it twice.
   Nothing locks it — the ordering in step 3 makes a *re-run* safe, not a concurrent run.

## Mechanism vs state — the whole design

| | Lives in | Example |
|---|---|---|
| **Mechanism** | this skill | *how* a lesson is consumed; *how* a bundle round is delivered |
| **State** | the epic's ledger | which round is next and how many rows it carries; which defects owe re-homing verification |

The same split as `status.json` (authority) versus a generated START-HERE block (rendering). **A
step in this file that hardcodes an epic-specific fact has broken it** — if you find yourself
wanting to record "round 8" or "defect (43)" here, that belongs in `$EPIC/epic-closeout.md`.

The reason this is a skill rather than a document copied into each epic: the close-out sequence was
first written inside the first epic that would close, and every later epic would then depend on a
mechanism document living in a closed-and-archived tree.

## Why this is orchestrator work, not a plan-marshall plan

The **Ledger Write-Boundary** forbids an executing plan from creating or editing *any* file under
`.plan/local/orchestrator/{epic}/`; its only channels back are its PR and its `inbox/` OUTBOX. Steps
1, 2 and 4 write this epic's ledger; steps 6, 7 and 8 write the successors' ledgers; step 5 is
outside the repository entirely. A plan-marshall call is **structurally incapable** of performing
any of them.

**The one genuine plan-shaped slice** is step 3's tail: where a consumed lesson must land in
*repository source* — documentation, an ADR, `CLAUDE.md`. That is spawned **from** the triage as its
own plan or direct brief; it never contains the triage.

> **This same boundary is why step 4 exists at all.** An orchestrator can record the *intent* to
> re-home a defect but cannot write it into another epic's tree, so the intent depends on a later
> session to enact it. Keep that in view: it is not a quirk of one epic, it is the mechanism's shape.

---

## Step 1 — Deliver the final bundle hand-off round

Compile and deliver the outstanding round of plan-marshall bundle findings for this epic.

- **Source of truth is `$EPIC/bundle-handoff-index.md`.** It is the **durable dedup base** — one
  line per delivered finding, kept forever. **Check it before compiling, never re-derive from
  memory.** The carrier itself is disposable; the index is not.
- **Flip `UNSENT` → sent in the SAME action as the send**, and relocate the carrier into
  `$EPIC/archive/bundle-handoffs/` in that same action. Nothing does this automatically. An index
  that is accurate right up until the moment it matters is worse than no index, because it is
  believed — a round has already been compiled twice because the flip was deferred.

  **This is a rule about not deferring the flip; it is not a claim of atomicity.** The send is
  external and the flip is a file edit, so an interruption between them can still leave a delivered
  round with unflipped rows, or the reverse. What catches that is the next round's dedup pass
  against this index — which is why the pass is mandatory before compiling, and why a suspected
  interruption is reconciled against the delivered carriers rather than assumed either way.
- **Round numbering restarts at 1 per epic**, and the epic's name distinguishes the carriers
  (`V02-round-1 item 3`, `V03-round-1 item 2`). A sequence shared across epics makes a carrier's
  provenance unreadable.
- **State permanent gaps explicitly, so they are not mistaken for completeness.** Where an early
  round's contents were not recorded, any finding older than that round needs manual dedup against
  the delivered carriers, and the carrier must say so.
- **Rows added since the last delivery have never been through a dedup pass** — run one rather than
  assuming. Watch particularly for a row that is a *recurrence* of a standing clause rather than a
  new finding; those belong in the carrier as a recurrence, which is a different and more useful
  claim.
- **Prefer a cover note about a class over N disconnected rows.** When several findings share a
  shape — the recurring example being *a mechanism reporting a clean result it never actually
  computed* — one named class is worth more to the bundle than five rows that each look local.

## Step 2 — Sweep the ledgers for unfiled plan-marshall material

Read, in full:

- the epic anchor's **Open Defects** and **Watches** (`$EPIC/status.json` `resume_anchor`, rendered
  into `$EPIC/epic.md`)
- every `$EPIC/landings/PLAN-*.md`
- `$EPIC/logs/decision.log`

Confirm that each defect attributable to a **plan-marshall mechanism** (as opposed to this
repository's own code) reached `bundle-handoff-index.md`. Anything that did not is filed now.

- **A defect seen in two or more plans is a recurrence, and says so in the carrier.** A two-plan
  recurrence is far stronger evidence of a mechanism defect than two separate one-off rows.
- The class cover-note rule from step 1 applies here too, and this sweep is usually where the class
  becomes visible.

## Step 3 — Consume the lessons corpus — the goal is zero

The corpus is **global to the repository, not per-epic**: `.plan/local/lessons-learned/`, listed with

```bash
python3 .plan/execute-script.py plan-marshall:manage-lessons:manage-lessons list
```

**Every epic's close-out drains whatever has accumulated since the last one.** That is precisely why
the archive convention below must be identical in every epic — otherwise a lesson consumed by one
epic is preserved and one consumed by another vanishes, and which lessons keep an audit record
becomes arbitrary.

Every lesson gets **exactly one recorded disposition**. Four destinations:

| Destination | For |
|---|---|
| **Bundle index** | plan-marshall mechanism defects |
| **Successor epic ledger** | standing rules that govern how the *next epic* is run |
| **Repository source** (docs / ADR / `CLAUDE.md`) | rules that govern how the *code* is built — **this is the plan-shaped slice**, spawned as its own plan |
| **Discard** | superseded, already covered, or scoped to work that has shipped |

> **The trap, stated because the word "consume" invites it.** A lesson that is a standing rule and
> is simply *deleted* is lost. Consumption means it lands somewhere that is actually read.
> **Removal is the last step of a disposition, never the disposition itself.**

### The consume mechanism — archive, never delete

A consumed lesson is **moved into the epic's archive**, so the corpus empties while the audit record
survives:

```text
$EPIC/archive/lessons-consumed/{lesson-id}.md
```

A fourth sibling alongside `archive/bundle-handoffs/`, `archive/plans-shipped/` and
`archive/plans-struck/`, following the same principle those three already embody — **this tree
relocates, it does not destroy.**

**Per lesson, in this order — persist, then remove:**

1. **Record the disposition at its destination**: the bundle index row, the successor anchor clause,
   the doc/ADR change, or an explicit discard rationale.
2. **Write the lesson body verbatim** to `$EPIC/archive/lessons-consumed/{lesson-id}.md`, with a
   header naming the disposition and where it went:

   ```markdown
   > **Consumed <YYYY-MM-DD> — disposition: <bundle index | successor ledger | repository source | discard>**
   > Landed at: <the index row, anchor clause, file+section, or the discard rationale>
   ```
3. **Only then** remove it from the corpus:

   ```bash
   python3 .plan/execute-script.py plan-marshall:manage-lessons:manage-lessons remove \
     --lesson-id <id> \
     --reason "<what happened to it>" \
     --coverage-verdict <completely_covered|redundant|superseded|obsolete> \
     --covering-clause "<required for completely_covered>" \
     --covering-input "<required for completely_covered>"
   ```

   `--reason` and `--coverage-verdict` are **required**; an unstated verdict is a rejection, not an
   assumption. `--force` skips the interactive confirmation — use it only in an unattended pass, and
   never as a way past a prompt you have not read.

**Persist-then-remove, never the reverse** — the same discipline the inbox drain uses, and for the
same reason: an interrupted pass loses at most the one lesson in flight, and a re-run over an
already-consumed lesson is a no-op rather than a second disposition. **A lesson removed before its
destination write is simply gone.**

**A discard is archived too, with its rationale.** "Superseded" and "already covered" are
dispositions *with reasons*, and the reason is exactly what a future reader needs when the same
observation recurs. A discarded lesson that leaves no trace gets re-learned and re-filed.

**Archive the corrected text, not the original.** Check each lesson for a diagnosis that has since
been refuted or superseded — a lesson corrected after the fact is common, and preserving the
superseded diagnosis as the record would re-mislead precisely the reader who trusts the archive.

## Step 4 — Distribute open defects and watches to the successors

Every open-defect and watch entry in the epic anchor takes exactly one of:

- **closed**, with the evidence that closed it;
- **re-homed** into a named successor epic;
- **kept as frozen history**, moving to `$EPIC/history.md` at close.

> ### AUDIT ITEM — an assertion of re-homing is not a re-homing
>
> Entries that already read *"RE-HOMED to X — track it there"* are **claims, not facts**. **Verify
> each one exists in the target epic's tree** before accepting it:
>
> ```bash
> grep -rn "<the defect's distinguishing phrase>" .plan/local/orchestrator/<target-slug>/
> ```
>
> **The failure is structural, not sloppiness.** The write boundary (above) forbids the orchestrator
> from writing into another epic's tree, so it can only record the intent and depend on a later
> session to enact it — and a re-homing has already sat asserted-but-unwritten for a day for exactly
> that reason. **You are that later session.** Where the entry is missing from the target, write it
> in now, then mark the source entry re-homed; where it is present, say so with the file and line
> you found it at.

## Step 5 — Consolidate memory

Project-scoped rather than epic-scoped, so it survives this epic and may run at any point in the
close-out.

Dedup the memory files, prune entries about defects that are now closed, and merge near-duplicates.
**Re-read each against current ground truth rather than trusting its summary line** — a memory whose
prescription was refuted after it was written is worse than no memory, because it is consulted with
confidence. Correct it or delete it; do not leave the stale prescription standing.

---

## For the successor epics

Steps 6 and 7 write into the successors' trees. They can start earlier than the rest, but **step 6
should follow step 1**, so the successors inherit a mechanism that has just been exercised end to
end.

## Step 6 — Implant the bundle hand-off mechanism

For each successor epic that lacks it:

- create `bundle-handoff-index.md` and `archive/bundle-handoffs/`;
- **numbering starts at round 1 in that epic**, with the epic's name distinguishing the carriers. Do
  not continue the predecessor's round numbering.
- carry across the two rules that were learned expensively: **the index is the durable dedup base,
  kept forever**, and **rows flip `UNSENT` → sent in the same action as the send**.

## Step 7 — Add a close-out state document to each successor

Same shape as the predecessor's, adapted: bundle round delivery, ledger sweep, lesson consumption,
defect distribution, memory consolidation — plus whatever this epic re-homed into it.

**Each successor's document carries state and points here for mechanism.** It does not re-state the
sequence; a copy of the mechanism in every epic is the duplication this skill exists to remove. It
*does* carry the consume convention by reference — `archive/lessons-consumed/{lesson-id}.md`,
persist-then-remove, discards archived with their rationale — because the corpus is global and the
convention must hold identically in every epic.

## Step 8 — Harvest what this epic learned for the successor that will do it next

Where this epic performed something a successor will perform again — a release cut, a migration, a
first-of-its-kind rehearsal — the pre-flight knowledge it produced typically exists **nowhere but
this epic's anchor** and would otherwise be re-learned at full price.

Move it into the successor that will do it next, in the successor's own ledger. Two rules about
where it lands:

- Knowledge about **how the repository's process works** belongs in that process's own documentation
  or skill, not in an epic ledger — file it as a step 3 *repository source* disposition instead.
- Knowledge that is **an instance-specific caution for the next run** — what was nearly missed last
  time, what a green result did not actually prove — belongs in the successor's ledger, where the
  next run will read it.

---

## Report and stop

Report, per step:

- the round delivered, its item count, and confirmation that the index rows were flipped **in the
  same action**;
- what the ledger sweep found unfiled, and any class named in the carrier rather than filed as rows;
- the lessons corpus count before and after, each disposition, and the archive path each landed at —
  **including the discards**;
- for **every** asserted re-homing: whether it was found in the target tree (with file and line) or
  written in now;
- what memory consolidation changed;
- which successors received the bundle mechanism, their close-out documents, and the harvested
  pre-flight knowledge.

Then **stop**. State plainly that the epic is dispositioned and **not closed**, and that `close` is
the operator's separate decision.

## Critical rules

- **This skill never calls `close`.** Disposition and closure are separate acts.
- **Never run it on an inferred slug**, and never while a plan is still in flight.
- **Consume means archive, never delete** — `archive/lessons-consumed/{lesson-id}.md`, and
  **persist-then-remove**: destination write, then archive copy, then `manage-lessons remove`.
- **A discard is a disposition with a reason, and is archived with it.**
- **Archive the corrected text of a superseded lesson, not the original.**
- **The lessons corpus is global to the repository**, so the archive convention must be identical in
  every epic or the audit record is arbitrary.
- **An assertion of re-homing is not a re-homing** — verify it in the target tree, or enact it.
- **The bundle index is the durable dedup base**, checked before compiling and **flipped in the same
  action as the send**; round numbering **restarts at 1 per epic**.
- **A class cover note beats N disconnected rows** when several findings share a shape.
- **Nothing epic-specific belongs in this file.** State lives in the epic's ledger.
- **Temporary files go under `.plan/temp/`.**

> **Activation latency (harness surface).** A newly authored or edited `.claude/skills/**` body is
> not discoverable as an invocable skill in the session that wrote it. Either start a fresh session
> or simply **follow this file as a document** — it is written to read correctly both ways.

## See also

- `$EPIC/epic-closeout.md` — this epic's *state*: what is outstanding, right now.
- `$EPIC/bundle-handoff-index.md` — the durable dedup base for step 1.
- `.plan/local/lessons-learned/` — the repository-wide corpus step 3 drains.
