envelope_version=1
sender_type=plan
sender_id=plan-v02-45-request-body-and-h2-buffer-bounds
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-21T22:22:06Z

# Count-bearing Javadoc drifted when a fix task added a caller and a writer; late wording advisories kept re-opening reviews

- Source: Q-Gate finding 0b6ea7 (phase 6-finalize, code-review advisories F1-F3, inline correction 24ca7e1a); decision-log entries 7ac05b, 1ef4e9, c22e90, 15ab79
- Component: api-sheriff ConnectionRetirement Javadoc; phase-6 review loop

## Observation

TASK-15 made TerminalDispatch.retireFailedRelayConnection a third caller of
retireConnectionAfterResponse and made recordRetirement a second writer of retiredConnections.
The Javadoc still said "two callers" and named framing-gate rejections as the only writer, and
markFramingRejection's Javadoc omitted the request-body deadline refusal that also marks the
connection. These were Javadoc-only advisories (no behaviour change).

Process observation from the same run: after TASK-15, simplify, security-audit,
functional-review and code-review all re-fired on the new head (decision 7ac05b), and the
code-review round again produced wording-only advisories. Across finalize, five rounds of late
Javadoc/doc wording corrections (findings ad7b99, 5ec4bc, a72844, 677474, 0b6ea7) each moved the
head, and each head move made the head-dependent review verdicts stale again. The run stopped
the loop by applying the last advisories as a Javadoc-only inline correction and recording that
the pushed tree differs from the reviewed tree by that commit alone.

## Outcome

Javadoc corrected in 24ca7e1a; no further review round run for it, by recorded decision.

## Candidate rules

- Avoid counts ("two callers", "the only writer") in Javadoc; name the role instead, or list the
  callers in one place that a caller-adding change has to touch.
- A fix task that adds a caller or a writer greps the Javadoc of the callee and the field for
  count and exclusivity words before closing.
- For the review loop: once a round yields only wording-level advisories, apply them as one
  inline correction and record the residual difference, rather than re-running every
  head-dependent review on a docs-only head move.
