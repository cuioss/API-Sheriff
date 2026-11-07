envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:17Z

# One sentence of a Javadoc was reworded and the next sentence kept the old meaning

Source: self-review finding 58ddcb (finalize phase, fixed in the run).

What happened: In SessionRecord the wording "bearer session id" was changed to "internal session id", because the plan separated the session id from the cookie value. The toString Javadoc directly below still listed the session id among the credential fields, and its return tag still said so too. The same file then stated two different things about the same field. The pre-submission self-review caught it and the text was aligned.

Candidate rule: After rewording a term in one Javadoc block, read the whole class for other sentences that use the old meaning of that term, including the return and parameter tags.
