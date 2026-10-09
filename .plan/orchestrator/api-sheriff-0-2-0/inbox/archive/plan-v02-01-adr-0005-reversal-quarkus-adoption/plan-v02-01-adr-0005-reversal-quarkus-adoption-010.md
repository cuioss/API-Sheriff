envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:22Z

# Self-review round was accepted although its author named two unchecked items

Source: self-review finding ca38a9 (finalize phase, fixed in the run).

What happened: The second self-review round stayed within the 68 candidates the tool had surfaced and was therefore accepted. In the same report the author named a stale parameter description in BackchannelLogoutReceiver that lay outside the surfaced set, and said that a "cases 1-13" count had not been compared with the script it describes. A further round was owed for those two items. The stale parameter description was fixed in a follow-up commit.

Candidate rule: A review round is closed only when the reviewer's own list of "not checked" and "outside the surface" items is empty. Items the reviewer names but does not check are findings, even when the surfaced candidates are all clean.
