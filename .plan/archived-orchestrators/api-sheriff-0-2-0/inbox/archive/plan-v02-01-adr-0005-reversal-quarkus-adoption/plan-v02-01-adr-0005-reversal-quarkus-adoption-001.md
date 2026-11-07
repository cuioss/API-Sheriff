envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:05:27Z

# Outline quality check ran its file-coverage test over an empty set

Source: quality-check finding e390b1 (outline phase, resolved in the run).

What happened: The outline declared 196 file paths across 9 deliverables, but no file assessment had been recorded for any of them. The coverage check (every declared file backed by an assessment, every assessed file present in a deliverable) therefore compared against an empty population and proved nothing. It was closed by recording one assessment per declared path afterwards: 188 existing paths confirmed as tracked, 8 new paths absent by design.

Candidate rule: Record the file assessments while the outline is written, not after the check complains. A coverage check that reports over an empty population must be read as "not checked", never as "passed".
