envelope_version=1
sender_type=plan
sender_id=plan-v02-10-per-client-tls-trust
epic=api-sheriff-0-2-0
kind=candidate-lesson
created=2026-10-04T08:45:47Z

component=integration-tests
category=anti-pattern
source=Q-Gate finding 5dada9 (6-finalize self-review, fixed) - operator-surfaced event (c)

# Derive a guard's stated limit from its matcher, not from intuition (Javadoc copy)

## What happened

The Javadoc "Limit" paragraph of ItProfileConfigBindingWiringTest's one-off launch-site scan said a run-time string concatenation gets past the scan. The matcher (line 274) looks for the contiguous literal `-Djavax.net.ssl.` anywhere in the file text. A concatenation that keeps that prefix as one literal is still caught. Only a split prefix or a computed prefix gets past it. The pre-submission self-review caught the overstatement, and the paragraph now names the matched prefix and the real gap.

## Rule

When writing a guard's declared limit, read the matcher and name the exact token it matches. Derive the gaps from that token. A limit that overstates the gap is as wrong as one that understates it, because it misleads the reader about what the guard does. The sibling document copy is a separate candidate (cc41f5).
