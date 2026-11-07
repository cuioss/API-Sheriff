envelope_version=1
sender_type=plan
sender_id=plan-v02-01-adr-0005-reversal-quarkus-adoption
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-09T13:06:06Z

# A flag was flipped on enum members and the class Javadoc statement about them was not re-checked

Source: self-review finding bdca5c (finalize phase, fixed in the run).

What happened: The plan changed one boolean on four members of the logout rejection enum. The class Javadoc made a statement about how often those members are reached, and after the change that statement no longer held. The pre-submission self-review caught it. On the operator's decision the code was hardened in the same PR so that the statement holds again, and the fix is landed.

Candidate rule: When a change flips a flag or constant, read every sentence in the surrounding Javadoc that describes the old value's consequences, and either prove it still holds or change code or text. A changed line next to an unchanged claim is the place to look.
