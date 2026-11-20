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
package de.cuioss.sheriff.gateway.edge.assembly;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The request bodies one upstream target's shared client is sending: each one {@linkplain #open() opened} the
 * moment a dispatch hands it to an upstream request, and {@linkplain OpenBody#end() ended} exactly once, the
 * moment it stops arriving there — its end arrived, it was refused, aborted or reset, the upstream request it fed
 * failed and its consumer stopped forwarding it, its attempt is no longer waited for, or the stream that was to
 * forward it could not be built. Each open body records
 * when it last forwarded a chunk; a chunk that arrives after the body ended is not recorded.
 * <p>
 * It answers one question: whether, at a given moment, some body on this client is still arriving and forwarded
 * a chunk within a bound ({@link #arrivingWithin}). A body that has ended answers nothing, however recently it
 * forwarded its last chunk, so a complete request body never stands for one still arriving.
 * <p>
 * One instance per upstream target, built with the target's client by {@link UpstreamClients} and shared by
 * every dispatch on that client — the HTTP and the gRPC dispatch alike. Its size is the number of bodies in
 * flight on the client.
 * <p>
 * Thread-safe: bodies are opened on the upstream request's event loop, chunks are recorded on the inbound
 * connection's, and the question is asked from the dispatching thread.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UpstreamBodyTraffic {

    private final Set<OpenBody> open = ConcurrentHashMap.newKeySet();

    /**
     * Records a body handed to an upstream request on this client: it counts as in flight until
     * {@link OpenBody#end()} is called on the returned handle.
     *
     * @return the handle the body's chunks and its end are recorded through
     */
    public OpenBody open() {
        OpenBody body = new OpenBody();
        open.add(body);
        return body;
    }

    /**
     * Whether a request body is still arriving on this client: some body opened here and not yet ended
     * forwarded a chunk within {@code boundNanos} before {@code nowNanos}.
     *
     * @param nowNanos   a {@link System#nanoTime()} reading
     * @param boundNanos the bound, in nanoseconds
     * @return {@code true} when some body still in flight on this client forwarded a chunk less than
     *         {@code boundNanos} before {@code nowNanos}; a body that has ended, or one that forwarded no chunk
     *         yet, never counts
     */
    public boolean arrivingWithin(long nowNanos, long boundNanos) {
        for (OpenBody body : open) {
            if (body.forwardedWithin(nowNanos, boundNanos)) {
                return true;
            }
        }
        return false;
    }

    /** @return how many bodies are in flight on this client now: opened and not yet ended */
    public int openBodies() {
        return open.size();
    }

    /**
     * One body in flight on the client: when it last forwarded a chunk, and its end.
     * <p>
     * Thread-safe: the last chunk is published through a volatile flag written after its reading.
     *
     * @since 1.0
     */
    public final class OpenBody {

        private volatile long lastForwardedNanos;
        private volatile boolean forwarded;

        private OpenBody() {
        }

        /**
         * Records that the body forwarded a chunk.
         *
         * @param nowNanos the {@link System#nanoTime()} reading of the chunk
         */
        public void forwarded(long nowNanos) {
            lastForwardedNanos = nowNanos;
            forwarded = true;
        }

        /**
         * Ends the body: it no longer counts as in flight. Only the first call ends it; a later one is a no-op,
         * so every end path may call it.
         */
        public void end() {
            open.remove(this);
        }

        private boolean forwardedWithin(long nowNanos, long boundNanos) {
            // Compared as a difference, never as absolute readings: System.nanoTime() may wrap.
            return forwarded && nowNanos - lastForwardedNanos < boundNanos;
        }
    }
}
