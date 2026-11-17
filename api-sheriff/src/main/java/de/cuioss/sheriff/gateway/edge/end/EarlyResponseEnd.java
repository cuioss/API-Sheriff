/*
 * Copyright © 2025-present CUI-OpenSource-Software (info@cuioss.de)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.cuioss.sheriff.gateway.edge.end;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;


import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;

/**
 * Ends an HTTP/2 response answered while its request body is still open: <strong>once request data stops
 * arriving or a time cap passes, and then followed by a stream reset</strong>.
 * <p>
 * <strong>Why the end waits.</strong> The reset tells the client to stop sending the rest of the
 * request (RFC 9113 §8.1). It must come from the gateway and come first: a client that sees the
 * complete response on a stream it is still uploading on resets that stream itself, and enough client
 * resets on one connection trip the server's Rapid-Reset guard. But a reset written while the client
 * still has request data on the way is just as bad: each data frame that arrives after it is answered
 * by the transport with a {@code RST_STREAM(STREAM_CLOSED)}, and the transport counts its own resets
 * against the same limit. So the end of the response — its final frame, carrying {@code END_STREAM} —
 * and the reset go out together, held back until request data has stopped arriving, for no longer than
 * a time cap.
 * <p>
 * <strong>How that moment is found.</strong> The caller has already stopped reading the body and
 * returning flow-control window for it (see {@code DispatchStage.InboundBody#resetByGateway}), so the
 * client can send no more than the window it already holds. The stream is looked at on its event loop
 * every {@value #POLL_MILLIS} ms through {@link InboundStreamWindow}, and the end goes ahead at the
 * first of:
 * <ol>
 *   <li>the client can send nothing more — the stream's or the connection's window is used up, or the
 *       client ended or lost the stream (see {@link InboundStreamWindow#sendableBytes()});</li>
 *   <li>what the client may send has not changed for {@value #QUIET_MILLIS} ms, and has still not
 *       changed when the client acknowledges the one HTTP/2 PING the gateway sends at that moment
 *       (through {@link InboundStreamWindow#ping()}). An unmoved window by itself shows only that no
 *       request data <em>arrived</em> for that long, not that none is on the way: across a link with
 *       latency, data a slow client has already sent is still in flight. A client acknowledges the
 *       PING only once it has seen it, so every frame it sent before then arrives ahead of the
 *       acknowledgement — a window still unmoved at the acknowledgement means the client sent nothing
 *       between its last frame and the PING. A window that moves before the acknowledgement restarts
 *       the {@value #QUIET_MILLIS} ms check. One PING is outstanding at a time, and a PING that fails or
 *       is never acknowledged ends nothing;</li>
 *   <li>{@value #MAX_WAIT_MILLIS} ms have passed since the wait began, whatever the stream does.</li>
 * </ol>
 * Only the first shows that no more request data can arrive; the second shows it for everything the
 * client sent before the PING reached it, and the cap ends the wait even while request data may still
 * be in flight. What remains exposed: a client that sends again after acknowledging the PING, or keeps
 * trickling data until the cap ends the wait, has that data arrive after the reset, and the transport
 * answers each such frame with a {@code RST_STREAM(STREAM_CLOSED)} counted against its reset limit. The
 * last bound caps how long any response can be held back. The three values are constants of the
 * gateway, not configuration.
 * <p>
 * <strong>The reset goes out right behind the end.</strong> The end and the reset are written in the
 * same turn of the stream's event loop as the look or the PING acknowledgement that allowed them, so
 * nothing the gateway does in
 * between — another stream on the connection consuming its body and returning connection window — can
 * let the client send on this stream before the reset reaches it. That holds as long as the end itself
 * reaches the transport at once. When HTTP/2 flow control holds the final frame back instead, a reset
 * written now would discard it; the reset then waits until that frame has been written. Either way a
 * stream the client has ended in the meantime is complete and is not reset.
 * <p>
 * <strong>Without a view of the stream there is no wait.</strong> When {@link InboundStreamWindow}
 * cannot attach, the response is ended at once and the stream reset once that end has been written.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class EarlyResponseEnd {

    private static final CuiLogger LOGGER = new CuiLogger(EarlyResponseEnd.class);

    /** How often the stream is looked at while the end waits. */
    static final long POLL_MILLIS = 10L;
    /** How long the window stays unmoved before one PING is sent to confirm that nothing is on the way. */
    static final long QUIET_MILLIS = 50L;
    /** The longest the end of a response is held back. */
    static final long MAX_WAIT_MILLIS = 1_000L;

    private EarlyResponseEnd() {
    }

    /**
     * Ends the response of {@code request} through {@code ending} once request data has stopped
     * arriving on its stream or the time cap has passed, then runs {@code reset} when the stream is
     * still open after that end was written.
     *
     * @param request the HTTP/2 request whose body is still open and no longer read
     * @param ending  the call that ends the response, run exactly once
     * @param reset   resets the stream; run on the stream's event loop, at most once
     * @return a future completing with the outcome of the end once the reset has been decided
     */
    static Future<Void> endWhenQuiet(HttpServerRequest request, Supplier<Future<Void>> ending, Runnable reset) {
        Optional<InboundStreamWindow> attached = InboundStreamWindow.of(request);
        if (attached.isEmpty()) {
            LOGGER.debug("No transport view of HTTP/2 stream %s — ending its response without waiting",
                    request.streamId());
            Future<Void> written = ending.get();
            written.onComplete(result -> reset.run());
            return written;
        }
        Promise<Void> written = Promise.promise();
        InboundStreamWindow window = attached.get();
        window.execute(new Wait(window, ending, reset, written)::look);
        return written.future();
    }

    /** Why the wait ended, or what it does next. */
    enum Outcome {
        /** Request data may still be on the way: look again. */
        WAIT,
        /**
         * The window has not moved for {@value EarlyResponseEnd#QUIET_MILLIS} ms: send one PING to confirm
         * that nothing is on the way, and look again.
         */
        PING,
        /** The client can send nothing more on the stream. */
        NOTHING_SENDABLE,
        /** The PING sent on an unmoved window was acknowledged with the window still unmoved. */
        QUIET,
        /** {@value EarlyResponseEnd#MAX_WAIT_MILLIS} ms have passed. */
        TIME_CAP
    }

    /**
     * Decides, from successive looks at one stream and the acknowledgement of the PING it asks for, when
     * the end may go ahead: the client can send nothing more, a PING sent once the window had not moved
     * for the quiet period was acknowledged with the window still unmoved, or the cap has passed. At most
     * one PING is outstanding; a window that moves restarts the quiet period, and a PING that is never
     * acknowledged leaves the wait to the cap. Pure: it reads nothing but its arguments, so the rule is
     * tested without a transport. Not thread-safe — one instance serves one wait, confined to the
     * stream's event loop.
     */
    static final class Quiescence {

        private final long startNanos;
        private long lastChangeNanos;
        private int lastSendable = -1;
        private boolean pingOutstanding;
        private int sendableAtPing;

        /** @param startNanos when the wait began, on the {@link System#nanoTime()} scale */
        Quiescence(long startNanos) {
            this.startNanos = startNanos;
            this.lastChangeNanos = startNanos;
        }

        /**
         * One look at the stream.
         *
         * @param nowNanos   the time of this look, on the {@link System#nanoTime()} scale
         * @param remoteOpen whether the client may still send on the stream
         * @param sendable   the bytes the client may still send
         * @return {@link Outcome#WAIT} to look again; {@link Outcome#PING} to send the confirming PING
         *         and look again; otherwise why the end may go ahead now — never {@link Outcome#QUIET},
         *         which only {@link #acknowledged} answers
         */
        Outcome next(long nowNanos, boolean remoteOpen, int sendable) {
            if (!remoteOpen || sendable <= 0) {
                return Outcome.NOTHING_SENDABLE;
            }
            observe(nowNanos, sendable);
            if (nowNanos - startNanos >= TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MILLIS)) {
                return Outcome.TIME_CAP;
            }
            if (!pingOutstanding && nowNanos - lastChangeNanos >= TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS)) {
                pingOutstanding = true;
                sendableAtPing = sendable;
                return Outcome.PING;
            }
            return Outcome.WAIT;
        }

        /**
         * The acknowledgement of the outstanding PING.
         *
         * @param nowNanos   the time the acknowledgement is handled, on the {@link System#nanoTime()} scale
         * @param remoteOpen whether the client may still send on the stream
         * @param sendable   the bytes the client may still send
         * @return {@link Outcome#QUIET} when the window has not moved since the PING was sent,
         *         {@link Outcome#NOTHING_SENDABLE} when the client can send nothing more, otherwise
         *         {@link Outcome#WAIT} — the quiet period restarts from the change — also for an
         *         acknowledgement no PING of this wait is outstanding for
         */
        Outcome acknowledged(long nowNanos, boolean remoteOpen, int sendable) {
            if (!remoteOpen || sendable <= 0) {
                return Outcome.NOTHING_SENDABLE;
            }
            if (!pingOutstanding) {
                return Outcome.WAIT;
            }
            pingOutstanding = false;
            if (sendable == sendableAtPing) {
                return Outcome.QUIET;
            }
            observe(nowNanos, sendable);
            return Outcome.WAIT;
        }

        /** Restarts the quiet period when what the client may send has changed. */
        private void observe(long nowNanos, int sendable) {
            if (sendable != lastSendable) {
                lastSendable = sendable;
                lastChangeNanos = nowNanos;
            }
        }
    }

    /**
     * One wait: looks at the stream until the end may go ahead, then ends the response and decides the
     * reset. Confined to the stream's event loop: the looks run there, and the PING acknowledgement is
     * handed there before it is read.
     */
    private static final class Wait {

        private final InboundStreamWindow window;
        private final Supplier<Future<Void>> ending;
        private final Runnable reset;
        private final Promise<Void> written;
        private final Quiescence quiescence = new Quiescence(System.nanoTime());
        private boolean ended;

        private Wait(InboundStreamWindow window, Supplier<Future<Void>> ending, Runnable reset,
                Promise<Void> written) {
            this.window = window;
            this.ending = ending;
            this.reset = reset;
            this.written = written;
        }

        private void look() {
            if (ended) {
                return;
            }
            Outcome outcome = quiescence.next(System.nanoTime(), window.remoteOpen(), window.sendableBytes());
            if (outcome == Outcome.PING) {
                window.ping().onComplete(acknowledgement -> window.execute(() -> acknowledged(acknowledgement)));
            } else if (outcome != Outcome.WAIT) {
                finish(outcome);
                return;
            }
            window.schedule(this::look, POLL_MILLIS);
        }

        private void acknowledged(AsyncResult<Void> acknowledgement) {
            if (ended) {
                return;
            }
            if (acknowledgement.failed()) {
                // The PING stays outstanding: the wait ends when nothing more is sendable or at the cap.
                LOGGER.debug(acknowledgement.cause(), "The PING confirming a quiet HTTP/2 stream failed");
                return;
            }
            Outcome outcome = quiescence.acknowledged(System.nanoTime(), window.remoteOpen(),
                    window.sendableBytes());
            if (outcome != Outcome.WAIT) {
                finish(outcome);
            }
        }

        private void finish(Outcome outcome) {
            ended = true;
            LOGGER.debug("Ending the early response of an open HTTP/2 stream: %s", outcome);
            end();
        }

        private void end() {
            Future<Void> ended;
            // Deliberately the broad RuntimeException: whatever the ending call throws, the caller waits
            // on the returned future and must learn of it. The failure is not swallowed — it fails that
            // future.
            // cui-rewrite:disable InvalidExceptionUsageRecipe
            try {
                ended = ending.get();
            } catch (RuntimeException endFailure) {
                written.fail(endFailure);
                return;
            }
            if (window.localEnded()) {
                // The final frame is in the transport's outbound buffer: the reset follows it at once.
                resetIfOpen();
                ended.onComplete(written);
                return;
            }
            // Flow control holds the final frame back; a reset now would discard it.
            ended.onComplete(result -> window.execute(() -> {
                resetIfOpen();
                written.handle(result);
            }));
        }

        private void resetIfOpen() {
            if (window.remoteOpen()) {
                reset.run();
            }
        }
    }
}
