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
package de.cuioss.sheriff.gateway.edge;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;

/**
 * Ends an HTTP/2 response answered while its request body is still open: <strong>only once no request
 * data can still be in flight, and then followed by a stream reset</strong>.
 * <p>
 * <strong>Why the end waits.</strong> The reset tells the client to stop sending the rest of the
 * request (RFC 9113 §8.1.1). It must come from the gateway and come first: a client that sees the
 * complete response on a stream it is still uploading on resets that stream itself, and enough client
 * resets on one connection trip the server's Rapid-Reset guard. But a reset written while the client
 * still has request data on the way is just as bad: each data frame that arrives after it is answered
 * by the transport with a {@code RST_STREAM(STREAM_CLOSED)}, and the transport counts its own resets
 * against the same limit. So the end of the response — its final frame, carrying {@code END_STREAM} —
 * and the reset go out together, at a moment no request data can still arrive.
 * <p>
 * <strong>How that moment is found.</strong> The caller has already stopped reading the body and
 * returning flow-control window for it (see {@code DispatchStage.InboundBody#resetByGateway}), so the
 * client can send no more than the window it already holds. The stream is looked at on its event loop
 * every {@value #POLL_MILLIS} ms through {@link InboundStreamWindow}, and the end goes ahead at the
 * first of:
 * <ol>
 *   <li>the client can send nothing more — the stream's or the connection's window is used up, or the
 *       client ended or lost the stream (see {@link InboundStreamWindow#sendableBytes()});</li>
 *   <li>what the client may send has not changed for {@value #QUIET_MILLIS} ms — it is sending nothing
 *       although it could, so nothing is on the way;</li>
 *   <li>{@value #MAX_WAIT_MILLIS} ms have passed since the wait began, whatever the stream does.</li>
 * </ol>
 * The last bound caps how long any response can be held back. The three values are constants of the
 * gateway, not configuration.
 * <p>
 * <strong>The reset goes out right behind the end.</strong> The end and the reset are written in the
 * same turn of the stream's event loop as the look that allowed them, so nothing the gateway does in
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
    /** How long an unmoved window means nothing is on the way. */
    static final long QUIET_MILLIS = 50L;
    /** The longest the end of a response is held back. */
    static final long MAX_WAIT_MILLIS = 1_000L;

    private EarlyResponseEnd() {
    }

    /**
     * Ends the response of {@code request} through {@code ending} once no request data can still be in
     * flight on its stream, then runs {@code reset} when the stream is still open after that end was
     * written.
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

    /** Why the wait ended, or that it goes on. */
    enum Outcome {
        /** Request data may still be on the way: look again. */
        WAIT,
        /** The client can send nothing more on the stream. */
        NOTHING_SENDABLE,
        /** The window has not moved for {@value EarlyResponseEnd#QUIET_MILLIS} ms. */
        QUIET,
        /** {@value EarlyResponseEnd#MAX_WAIT_MILLIS} ms have passed. */
        TIME_CAP
    }

    /**
     * Decides, from successive looks at one stream, when no request data can still be in flight. Pure:
     * it reads nothing but its arguments, so the rule is tested without a transport. Not thread-safe —
     * one instance serves one wait, confined to the stream's event loop.
     */
    static final class Quiescence {

        private final long startNanos;
        private long lastChangeNanos;
        private int lastSendable = -1;

        /** @param startNanos when the wait began, on the {@link System#nanoTime()} scale */
        Quiescence(long startNanos) {
            this.startNanos = startNanos;
            this.lastChangeNanos = startNanos;
        }

        /**
         * @param nowNanos   the time of this look, on the {@link System#nanoTime()} scale
         * @param remoteOpen whether the client may still send on the stream
         * @param sendable   the bytes the client may still send
         * @return {@link Outcome#WAIT} to look again, otherwise why the end may go ahead now
         */
        Outcome next(long nowNanos, boolean remoteOpen, int sendable) {
            if (!remoteOpen || sendable <= 0) {
                return Outcome.NOTHING_SENDABLE;
            }
            if (sendable != lastSendable) {
                lastSendable = sendable;
                lastChangeNanos = nowNanos;
            }
            if (nowNanos - lastChangeNanos >= TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS)) {
                return Outcome.QUIET;
            }
            if (nowNanos - startNanos >= TimeUnit.MILLISECONDS.toNanos(MAX_WAIT_MILLIS)) {
                return Outcome.TIME_CAP;
            }
            return Outcome.WAIT;
        }
    }

    /** One wait: looks at the stream until it is quiet, then ends the response and decides the reset. */
    private static final class Wait {

        private final InboundStreamWindow window;
        private final Supplier<Future<Void>> ending;
        private final Runnable reset;
        private final Promise<Void> written;
        private final Quiescence quiescence = new Quiescence(System.nanoTime());

        private Wait(InboundStreamWindow window, Supplier<Future<Void>> ending, Runnable reset,
                Promise<Void> written) {
            this.window = window;
            this.ending = ending;
            this.reset = reset;
            this.written = written;
        }

        private void look() {
            Outcome outcome = quiescence.next(System.nanoTime(), window.remoteOpen(), window.sendableBytes());
            if (outcome == Outcome.WAIT) {
                window.schedule(this::look, POLL_MILLIS);
                return;
            }
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
