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
import java.util.concurrent.atomic.AtomicInteger;


import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.impl.ConnectionBase;

/**
 * One inbound HTTP/2 stream as the transport sees it: whether the client may still send on it, how many
 * request-body bytes it may still send, and whether the gateway's side of the stream has ended — and a
 * PING on its connection that tells when everything the client sent before it has arrived.
 * <p>
 * <strong>What the client may still send.</strong> A client may send DATA on a stream only within both
 * flow-control windows the gateway has granted and not yet received: the stream's own and the
 * connection's, which every stream on the connection draws from. The smaller of the two bounds what it
 * can send — and what it can have on the way — and when either is used up it can send nothing at all.
 * Both windows count the {@code WINDOW_UPDATE} frames the gateway has already written, so a window that
 * reads {@code 0} stays {@code 0} until the gateway itself grants more.
 * <p>
 * <strong>Why this reaches below Vert.x — the one place in the gateway that does.</strong> Vert.x
 * exposes no per-stream flow-control state: {@code HttpConnection#getWindowSize()} is the window of the
 * whole connection, and a paused request buffers the data that still arrives without telling anyone.
 * Only the Netty HTTP/2 codec under the Vert.x connection knows how much a client may still send on a
 * stream. This class reaches it through exactly one Vert.x internal,
 * {@link ConnectionBase#channelHandlerContext()} — for an HTTP/2 connection the context of the Netty
 * {@link Http2ConnectionHandler} Vert.x installs. Everything read from there is public Netty API:
 * {@link Http2ConnectionHandler#connection()}, {@link Http2Stream#state()} and the decoder's
 * {@code Http2LocalFlowController#windowSize}. No frame is written through it; the one thing kept on the
 * connection's channel is the count of this class's own unacknowledged PINGs. When the internal is not
 * what this class expects — a Vert.x upgrade changed it — {@link #of} answers empty and the caller does
 * without the wait it would have used this for.
 * <p>
 * <strong>The one frame it writes goes through public Vert.x API.</strong> {@link #ping()} sends an
 * HTTP/2 PING on the request's own connection through {@code HttpConnection#ping}, not through the
 * Netty codec, and reports the client's acknowledgement. A client acknowledges a PING only once it has
 * received it, so every frame it sent before then has reached the gateway by the time the
 * acknowledgement does: what the windows read at that moment already counts it. Counted is not the same
 * as visible: the windows also rise while the request is paused — the connection's when the gateway
 * returns credit for another stream's body, the stream's own once the transport has returned enough of
 * the padding of its DATA frames, which it returns at once — so a reading equal to an earlier one does
 * not show that no DATA arrived in between.
 * <p>
 * <strong>Unacknowledged PINGs are bounded per connection.</strong> Vert.x keeps the future of every
 * PING it has written until the client acknowledges it, and does not release it when the connection
 * stays open without an acknowledgement. A client that never acknowledges would otherwise make every
 * early answer on a long-lived connection leave one more pending future behind. The connection's count
 * holds the PINGs sent through this class whose future has not completed: {@link #ping()} adds one when
 * it sends a PING and takes it away when that PING's future completes — on the client's
 * acknowledgement, or on a failed write. The count follows the PING, not the wait that sent it, so the
 * PING of a wait that has already ended stays counted until its acknowledgement arrives. At most
 * {@value #MAX_OUTSTANDING_PINGS} PINGs are counted on one connection at a time — the most concurrent
 * streams the edge admits on one connection. Past it, {@link #ping()} sends nothing and fails at once;
 * the caller takes that like any failed PING: the PING ends nothing, the quiet period restarts, and the
 * caller sends a new PING once the window has stood still for it again. An acknowledgement frees a place.
 * <p>
 * <strong>Thread confinement.</strong> Netty's HTTP/2 state belongs to the connection's event loop.
 * {@link #remoteOpen()}, {@link #sendableBytes()}, {@link #localEnded()} and {@link #ping()} must be
 * called there; {@link #execute} and {@link #schedule} hand work to it. {@link #ping()} refuses a call
 * from any other thread, for the reason its documentation gives. The future it returns may complete off
 * that event loop: a caller that reads the windows on the acknowledgement hands that read to it through
 * {@link #execute}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class InboundStreamWindow {

    /** The PING payload length HTTP/2 prescribes (RFC 9113 §6.7) and Vert.x enforces. */
    private static final int PING_PAYLOAD_BYTES = 8;

    /**
     * The most PINGs sent through this class whose future has not completed that may be counted on one
     * connection at once: the most concurrent streams the edge admits on one connection, 100 (the
     * listener bound {@code EdgeHardeningOptions} applies). A PING is counted from the moment
     * {@link #ping()} sends it until its future completes — on the acknowledgement or on a failed write —
     * so the PING of an early answer that has already ended stays counted until it is acknowledged, and a
     * client that never acknowledges leaves at most this many pending. {@code InboundStreamWindowTest}
     * holds it to the edge's stream bound.
     */
    static final int MAX_OUTSTANDING_PINGS = 100;

    /** The failure {@link #ping()} answers with once {@value #MAX_OUTSTANDING_PINGS} PINGs are unacknowledged. */
    private static final String PINGS_OUTSTANDING = "Too many unacknowledged PINGs on the connection";

    /** The failure {@link #ping()} answers with when called off the connection's event loop. */
    private static final String PING_OFF_EVENT_LOOP = "A PING must be sent on the connection's event loop";

    /** The connection-wide count of unacknowledged PINGs sent through this class, kept on the channel. */
    private static final AttributeKey<AtomicInteger> OUTSTANDING_PINGS = AttributeKey
            .valueOf(InboundStreamWindow.class, "outstandingPings");

    private final HttpConnection connection;
    private final ChannelHandlerContext channel;
    private final Http2ConnectionHandler codec;
    private final int streamId;
    private final AtomicInteger outstandingPings;

    private InboundStreamWindow(HttpConnection connection, ChannelHandlerContext channel,
            Http2ConnectionHandler codec, int streamId) {
        this.connection = connection;
        this.channel = channel;
        this.codec = codec;
        this.streamId = streamId;
        this.outstandingPings = outstandingPingsOf(channel);
    }

    /** @return the count of unacknowledged PINGs of {@code channel}'s connection, created on first use */
    private static AtomicInteger outstandingPingsOf(ChannelHandlerContext channel) {
        Attribute<AtomicInteger> attribute = channel.channel().attr(OUTSTANDING_PINGS);
        AtomicInteger fresh = new AtomicInteger();
        AtomicInteger present = attribute.setIfAbsent(fresh);
        return present == null ? fresh : present;
    }

    /**
     * Attaches to the stream of an HTTP/2 request.
     *
     * @param request the inbound request
     * @return the stream's receive side, or empty for a request that is not an HTTP/2 stream the
     *         transport can be read for
     */
    static Optional<InboundStreamWindow> of(HttpServerRequest request) {
        int streamId = request.streamId();
        HttpConnection connection = request.connection();
        if (streamId <= 0 || !(connection instanceof ConnectionBase transport)) {
            return Optional.empty();
        }
        ChannelHandlerContext channel = transport.channelHandlerContext();
        if (channel == null || !(channel.handler() instanceof Http2ConnectionHandler codec)) {
            return Optional.empty();
        }
        return Optional.of(new InboundStreamWindow(connection, channel, codec, streamId));
    }

    /**
     * Sends one HTTP/2 PING on the request's connection. The payload is fixed: an acknowledgement is
     * matched to its PING by order, not by content.
     * <p>
     * Must be called on the connection's event loop. Vert.x 4.5 registers the acknowledgement handler
     * only once the PING write has completed and drops an acknowledgement no handler awaits, so a PING
     * written from another thread can have its acknowledgement read — and lost — before the handler is
     * registered. On the event loop the PING is written and the handler registered before control
     * returns to the loop, ahead of any frame it reads next. A call from any other thread is therefore
     * refused at once: nothing is sent and the PING takes no place under the per-connection bound.
     *
     * @return a future completing once the client has acknowledged the PING and failing when the PING
     *         could not be written, a closed connection among the causes, or was not sent: because the
     *         call came from off the connection's event loop (failing with an
     *         {@link IllegalStateException}) or because {@value #MAX_OUTSTANDING_PINGS} PINGs are already
     *         unacknowledged on the connection. A PING written on a connection that closes before the
     *         acknowledgement arrives is never completed: Vert.x does not fail it, so a caller must not
     *         depend on the future settling
     */
    Future<Void> ping() {
        if (!channel.executor().inEventLoop()) {
            return Future.failedFuture(new IllegalStateException(PING_OFF_EVENT_LOOP));
        }
        if (outstandingPings.incrementAndGet() > MAX_OUTSTANDING_PINGS) {
            outstandingPings.decrementAndGet();
            return Future.failedFuture(PINGS_OUTSTANDING);
        }
        Future<Void> acknowledgement = connection.ping(Buffer.buffer(new byte[PING_PAYLOAD_BYTES])).mapEmpty();
        acknowledgement.onComplete(settled -> outstandingPings.decrementAndGet());
        return acknowledgement;
    }

    /**
     * Must be called on the connection's event loop.
     *
     * @return {@code true} while the client may still send data on the stream: the stream is open and
     *         the client has not ended its side of it
     */
    boolean remoteOpen() {
        Http2Stream stream = codec.connection().stream(streamId);
        return stream != null && stream.state().remoteSideOpen();
    }

    /**
     * Must be called on the connection's event loop.
     *
     * @return the request-body bytes the client may still send on the stream: the smaller of the
     *         stream's and the connection's receive window, {@code 0} once the client may send nothing
     *         more
     */
    int sendableBytes() {
        Http2Stream stream = codec.connection().stream(streamId);
        if (stream == null || !stream.state().remoteSideOpen()) {
            return 0;
        }
        Http2LocalFlowController flowControl = codec.decoder().flowController();
        int streamWindow = flowControl.windowSize(stream);
        int connectionWindow = flowControl.windowSize(codec.connection().connectionStream());
        // The smaller window, floored at zero. Clamped as one value rather than as the stream window
        // between 0 and the connection window: Math.clamp rejects a range whose upper end is negative.
        return Math.clamp(Math.min(streamWindow, connectionWindow), 0, Integer.MAX_VALUE);
    }

    /**
     * Must be called on the connection's event loop.
     *
     * @return {@code true} once the gateway's side of the stream has ended — the frame carrying
     *         {@code END_STREAM} has been handed to the transport's outbound buffer, not held back by
     *         flow control — or the stream is gone
     */
    boolean localEnded() {
        Http2Stream stream = codec.connection().stream(streamId);
        return stream == null || !stream.state().localSideOpen();
    }

    /** Runs {@code task} on the connection's event loop — at once when called there. */
    void execute(Runnable task) {
        if (channel.executor().inEventLoop()) {
            task.run();
        } else {
            channel.executor().execute(task);
        }
    }

    /** Runs {@code task} on the connection's event loop after {@code delayMillis}. */
    void schedule(Runnable task, long delayMillis) {
        channel.executor().schedule(task, delayMillis, TimeUnit.MILLISECONDS);
    }
}
