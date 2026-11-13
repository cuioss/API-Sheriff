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

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2LocalFlowController;
import io.netty.handler.codec.http2.Http2Stream;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.impl.ConnectionBase;

/**
 * One inbound HTTP/2 stream as the transport sees it: whether the client may still send on it, how many
 * request-body bytes it may still send, and whether the gateway's side of the stream has ended.
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
 * {@code Http2LocalFlowController#windowSize}. Nothing is written. When the internal is not what this
 * class expects — a Vert.x upgrade changed it — {@link #of} answers empty and the caller does without
 * the wait it would have used this for.
 * <p>
 * <strong>Thread confinement.</strong> Netty's HTTP/2 state belongs to the connection's event loop.
 * {@link #remoteOpen()}, {@link #sendableBytes()} and {@link #localEnded()} must be called there;
 * {@link #execute} and {@link #schedule} hand work to it.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class InboundStreamWindow {

    private final ChannelHandlerContext channel;
    private final Http2ConnectionHandler codec;
    private final int streamId;

    private InboundStreamWindow(ChannelHandlerContext channel, Http2ConnectionHandler codec, int streamId) {
        this.channel = channel;
        this.codec = codec;
        this.streamId = streamId;
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
        if (streamId <= 0 || !(request.connection() instanceof ConnectionBase connection)) {
            return Optional.empty();
        }
        ChannelHandlerContext channel = connection.channelHandlerContext();
        if (channel == null || !(channel.handler() instanceof Http2ConnectionHandler codec)) {
            return Optional.empty();
        }
        return Optional.of(new InboundStreamWindow(channel, codec, streamId));
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
