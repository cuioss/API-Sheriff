# Lessons archive — deployment-configurability

Lessons moved here from `.plan/local/lessons-learned/` during the **2026-09-11 lessons intake**, after
being verified against `origin/main` `428bbec` and incorporated into this epic. Each file is the
original, byte-identical (`cp -p`, `cmp`-checked). The originals were retired from the store through
`manage-lessons remove`, which left a tombstone per id under `.plan/local/lessons-learned/.tombstones/` —
except `2026-09-01-15-001`, which the store could not index (markdown-bullet metadata instead of `id=`
headers) and which was deleted directly after a `cmp`-verified copy, so it has no tombstone.

⚠ **Why not `inbox/archive/`.** Lesson filenames (`2026-07-16-09-002.md`) match the inbox message-name
pattern `^(?P<sender>.+?)-(?P<seq>\d{3,})\.md$`, so filing them there would inflate the derived
`inbox_archived` count that the START-HERE block renders. This sibling directory is invisible to the
inbox tooling by design.

The per-lesson disposition for all 27 lessons scanned — including the 16 relocated to plan-marshall's
`truthful-signals` inbox (the last 4 after an operator ruling) — is in `epic.md` § Decisions →
"Lessons intake — 2026-09-11". The table below covers only the files in this directory.

| Lesson | Cluster | Disposition | Where it now lives in the ledger |
|---|---|---|---|
| `2026-07-16-09-002` | C1 `-Ppre-commit` destructive rewrite | already-covered — RESOLVED by PR #242 | Open Defects: `pom.xml:201` stale comment (residue) |
| `2026-09-01-19-001` | C1 | already-covered — RESOLVED by PR #242 | same |
| `2026-07-16-15-001` | C16 benchmark JDK pin | stale — RESOLVED (`benchmark.yml:95` is `'25'`) | Decisions entry only |
| `2026-08-29-16-002` | C2 macOS loopback flake | clustered-into C2 — mechanism CLOSED by PLAN-13 | PLAN-23 deliverable 3 (re-grounded), Open Defects loopback entry |
| `2026-09-10-09-001` | C2 | clustered-into C2 — its "bind the control listener" remedy MISDESCRIBES the site | PLAN-23 deliverable 3 (corrected there) |
| `2026-09-01-14-002` | C2 | clustered-into C2 — attribution rule (check CI before blaming the branch) | PLAN-23 deliverable 3 note |
| `2026-08-29-16-001` | C15 guard outside CI trigger set | already-covered — gap STILL OPEN, org-owned | Open Defects: CI blind spot (re-verified) |
| `2026-09-02-22-001` | C14 root path `/` | standalone — STILL VALID | Open Defects: root-path normalisation (new) |
| `2026-09-01-14-003` | C12 tests that cannot fail | clustered-into C12 — original site fixed; one NEW site found | PLAN-23 deliverables 1 & 3 |
| `2026-09-01-15-001` | C12 | clustered-into C12 — `ContextPathDefaultsTest` already follows it | PLAN-23 methodology inputs |
| `2026-09-04-07-001` | C13 prose claims vs implementing source | clustered-into C13 — all named instances fixed | PLAN-09 and PLAN-19 authoring discipline |
