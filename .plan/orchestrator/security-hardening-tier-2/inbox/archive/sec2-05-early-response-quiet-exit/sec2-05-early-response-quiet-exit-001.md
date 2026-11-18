envelope_version=1
sender_type=plan
sender_id=sec2-05-early-response-quiet-exit
epic=security-hardening-tier-2
kind=candidate-lesson
created=2026-11-18T05:29:01Z

# Candidate lesson: Vert.x HttpConnection#ping must be issued on the connection's event loop

**Signal source:** script-failure / CI flake during the run (CI-only intermittent 30 s timeout on JDK 25).

**Mechanism.** In Vert.x 4.5, `Http2ConnectionBase#ping` registers the PING-ack handler only after the PING frame write completes. An ack that arrives before the handler is registered is dropped (unawaited acks are discarded), so the returned future never completes and the caller times out. When `HttpConnection#ping` is called from a thread other than the connection's event loop, the write and the ack can race on that loop and the ack is lost intermittently - observed only on CI, not locally.

**Corrective rule.** Always dispatch `HttpConnection#ping` onto the connection's own event loop (e.g. via the connection's context `runOnContext`), never from a worker / test / foreign thread. Treat an intermittent PING-ack timeout as this race before suspecting the peer.

**Suggested component:** project-local (edge / HTTP/2 connection handling); possibly a project knowledge hint rather than a defect lesson.
