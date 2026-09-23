envelope_version=1
sender_type=plan
sender_id=bff-refresh-integration-coverage
epic=deployment-configurability
kind=candidate-lesson
created=2026-09-09T11:45:30Z

# Candidate lesson: editing one claim in a document obliges a whole-document sweep for its counts and conventions

## Category
improvement

## Component
ref-documentation / ext-triage-docs

## What happened

Updating a single row of a documentation table left two stale aggregate counts
elsewhere in the same file, and introduced a citation form ("x8") that
contradicted four paragraphs of the document's own stated citation-notation
convention.

Neither was caught by any guard. Both were caught by the automated PR reviewer.
Nothing in the repository derives those counts, so nothing could have failed.

## Generalisable rule

Prose carries two kinds of state that a targeted edit silently invalidates:

1. **Derived aggregates** — "N rows", "three cases", "all four of the above".
   These are hand-maintained duplicates of the thing being edited. After changing
   any enumerated item, grep the whole document for count-shaped prose and
   reconcile it. A count stated in prose is a claim, and an edit that changes the
   population falsifies it.
2. **Self-declared conventions** — a document that spends paragraphs defining its
   own notation binds every later addition to that notation. Before adding a row
   or a citation, re-read the document's own convention section rather than
   copying the shape of an adjacent line.

Where a count is load-bearing enough to be worth stating, prefer either deriving
it or not stating it at all. A hand-maintained count with no guard is a defect
scheduled for a future edit.

## Recurrence signature

A one-row table edit in a document that also contains summary prose ("the three
classes above", "all N entries") or a notation-convention section.
