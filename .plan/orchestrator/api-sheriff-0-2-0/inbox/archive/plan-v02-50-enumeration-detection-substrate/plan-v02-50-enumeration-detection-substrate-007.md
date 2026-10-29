envelope_version=1
sender_type=plan
sender_id=plan-v02-50-enumeration-detection-substrate
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-29T07:59:48Z

# Candidate lesson: rebasing over a docs split silently orphans edits to moved content

**Source signal:** finalize main-drift rebase.

**Observation:** While this plan was in flight, main landed a docs split (`architecture.adoc` broken out into `doc/architecture/`, PR #533). Rebasing onto it moved the content the branch had edited. A clean or trivially resolved rebase does not prove the branch's edits ended up in the new file that now holds that section.

**Corrective rule:** Before rebasing, check `HEAD..origin/main` for file moves/splits that touch any doc the branch edits. After the rebase, check that every doc edit the branch made is present in the file that now owns the section (search the new tree for the branch's new wording), and re-apply it there if it was dropped or left in a stub. Re-check cross-document anchors and `xref:` links that pointed at the old single file.

**Scope:** project git / documentation workflow (complements the existing finalize main-drift practice).
