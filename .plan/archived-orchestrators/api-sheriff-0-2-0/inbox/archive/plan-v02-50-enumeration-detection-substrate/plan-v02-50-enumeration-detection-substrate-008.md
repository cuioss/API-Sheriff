envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T08:00:02Z

# Candidate lesson: outline file lists miss consequence files (stale-claim residue, renumbered ADRs), so the scope-creep gate fires repeatedly

**Source signal:** Q-Gate findings in 3-outline (2, taken into account) and 5-execute (4 scope-creep warnings, all accepted).

**Observation:**
- At outline time a content sweep found that a behavior claim being corrected in one Javadoc also appeared in a log-message template, the `doc/LogMessages.adoc` catalogue row and `README.adoc`. None of them were in any deliverable until the Q-Gate flagged it. A success criterion also counted "six listed types" where only five existed.
- During execute, the scope-creep check fired four times on a growing residual set (6, 8, 9, then 11 files). All of them were legitimate consequences of in-scope work: stale wording in index/user READMEs and an older ADR, a topology table row, a test helper, a `package-info.java`, and two ADRs renumbered (planned 0074/0075, shipped 0075/0076) because a concurrent PR took ADR-0074 first. Each firing needed a separate accept.

**Corrective rule:**
- When a deliverable changes documented behavior, sweep the whole tree (code Javadoc, log-message templates, `doc/LogMessages.adoc`, READMEs, ADRs, topology docs) for the old claim at outline time, and list every hit in the affected files.
- Count criterion items against the affected-files list before finalizing the outline.
- Declare new test helpers and `package-info.java` files the deliverables will need.
- Re-check the next free ADR ordinal against `origin/main` right before committing ADRs, and update the outline's file list when ordinals shift, so the residual set stays empty.

**Scope:** project outline / planning practice.
