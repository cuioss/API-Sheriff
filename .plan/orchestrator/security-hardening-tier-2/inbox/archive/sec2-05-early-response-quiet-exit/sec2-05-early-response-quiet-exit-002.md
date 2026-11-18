envelope_version=1
sender_type=plan
sender_id=sec2-05-early-response-quiet-exit
epic=security-hardening-tier-2
kind=candidate-lesson
created=2026-11-18T05:29:09Z

# Candidate lesson: a stream's receive-window equality cannot prove an HTTP/2 client stopped sending

**Signal source:** Q-Gate / design finding during the run.

**Mechanism.** Netty's local flow controller returns padding bytes as consumed immediately, even while the stream is paused (auto-read off / no demand). The stream's own receive window can therefore rise during a pause. A check of the form "window unchanged across an interval => peer sent nothing" is unsound: padded DATA frames move the window, and window arithmetic alone does not distinguish "no frames" from "frames whose bytes were returned".

**Corrective rule.** Do not use receive-window equality as evidence of client quiescence. Use an explicit liveness/ordering probe (e.g. an HTTP/2 PING round-trip, whose ack is ordered after previously sent frames) or observe frame arrival directly.

**Suggested component:** project-local (edge / HTTP/2 flow control).
