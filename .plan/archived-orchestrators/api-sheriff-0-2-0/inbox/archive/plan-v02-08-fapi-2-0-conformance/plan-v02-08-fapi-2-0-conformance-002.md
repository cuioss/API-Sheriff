envelope_version=1
sender_type=plan
sender_id=plan-v02-08-fapi-2-0-conformance
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-02T21:05:35Z

# Candidate lesson: pre-submission self-review does not converge on a large prose-heavy change

## Pattern

On a change of roughly 115 to 120 files in which every self-review candidate is in the prose-contract family (structural candidates: 0), the author/verifier loop never reached a close. Each round the verifier accepted the author's verdict on the candidates it was given and then answered the stop question with "no", on a ground that was new in that round and lay outside the candidate set: a class of wording seen by chance and never swept, a claim left unverified, a count in one document disagreeing with rows elsewhere. Fixing the round's ground produced a new commit, the new commit produced a new round, and the next round found a different ground.

## What the record shows

- Six verifier rounds between 09:46Z and 12:38Z on 2026-10-02, every one ending `may_close=no` (decision log cb30ae, 29d60f, f64382, 865194, 3076d4, e28658).
- The candidate surface was identical each time: 20 candidates, structural=0, prose_contract=20, 13 of about 118 files (fe87b4, 3161dc, 1c9c62).
- Extra sweeps were dispatched between rounds to close the ground just raised (stale unchanged-line sweep, over-wide wording sweep for docs and code, five full-read reviews); the following round still answered no on another ground.
- The loop-back ceiling (5) was reached; the request for iteration 6 was refused (work log 9a64ed).
- The operator closed it by decision: fix the latest findings, continue without further self-review. The record states this is not a converged close and that the final fix commit is covered by no review round (e06447).
- Cost: about three and a half hours of finalize wall time and roughly 2.4 million tokens across the recorded self-review rounds, before the sweeps.

## How to recognise it next time

- Candidate families are all prose-contract and the candidate count does not fall between rounds.
- The verifier's refusal ground changes every round and is always "seen by chance, never swept".
- Each round's fix commit is text-only.

By the second such round the loop is not going to close on its own.

## Suggested direction (for the orchestrator to classify)

- Treat "accepted verdict, new out-of-candidate ground" two rounds running as a non-convergence signal and put the close decision to the operator then, instead of at the ceiling.
- The verifier's stop question has no bound on the ground it may raise; a ground outside the candidate set could be filed as a finding for later triage rather than spending a loop-back iteration.
- The self-review consumed the whole loop-back budget of the finalize phase, which later steps then needed (see the separate candidate on the shared ceiling and the queue-landing wait).
