envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:11Z

# A method was renamed and the test names around it kept the old word

Source: self-review finding c7398d (finalize phase, fixed in the run).

What happened: The codec method readSessionId was renamed readCookieHandle, because the cookie does not carry the session id. In SessionCookieCodecTest the three call sites were updated, but the display name, the nested test class name, the test method name and a literal still said "session id". The test then read as if it proved the opposite of the codec's contract. The pre-submission self-review caught it and the names were corrected.

Candidate rule: A rename that corrects a concept is not finished when the code compiles. Search the test classes for the old word in display names, nested class names, method names and literals, and rename those in the same commit.
