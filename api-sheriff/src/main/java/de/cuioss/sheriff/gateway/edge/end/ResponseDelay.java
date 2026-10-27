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

import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

/**
 * The edge's one delay primitive: holds a response back until a release time has passed, without
 * holding a thread while it waits. {@link #endNoEarlierThan} runs the answer at once when its release
 * time has already passed, and otherwise schedules it with a Vert.x timer
 * ({@link io.vertx.core.Vertx#setTimer}); neither the event loop nor a virtual thread is parked in the
 * meantime, so a held answer costs a timer entry and nothing else.
 * <p>
 * The delayed answer is the writer's own write — status, headers and the end — and its end still
 * passes the one end rule ({@link ResponseEnds#endResponse}): this primitive decides only <em>when</em>
 * the answer is written, never <em>how</em> it is ended.
 * <p>
 * <strong>Ended and closed responses.</strong> A response another writer ended while the timer was
 * pending is not written again, and a response whose connection or stream closed in the meantime is
 * not written at all. The timer always fires once its delay has passed and then releases the answer it
 * held, so a closed connection leaves nothing behind.
 * <p>
 * Two callers write through here. The uniform {@code 404}'s response-timing floor is the first (see
 * {@code edge.error.UniformNotFound}). The per-source {@code delay} action of
 * {@code enumeration_detection} is the second: a rejection answer to a source that has tripped under
 * that action is held until admission plus {@code delay_millis}, or until the uniform {@code 404}'s
 * release time when that is later (see {@code edge.error.ErrorResponseWriter}), rather than through a
 * delay of its own. This primitive applies no policy of its own: the caller computes the release time,
 * and the uniform {@code 404}'s caller computes one that is never in the past at the moment it is
 * computed.
 * <p>
 * Stateless; {@link #endNoEarlierThan} runs on the event loop of the response it is handed.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ResponseDelay {

    /**
     * The {@link System#nanoTime()} reading the edge took when it admitted the request, stashed on the
     * {@link RoutingContext} by the edge root: the origin a release time is measured from. The
     * virtual-thread hop carries only the {@link RoutingContext}, so the reading rides on it to the
     * writer.
     */
    public static final String ADMITTED_AT_KEY = "sheriff.admittedat";
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private ResponseDelay() {
        // static helpers only
    }

    /**
     * Runs {@code answer} no earlier than {@code releaseAtNanos}: at once when that time has passed,
     * otherwise from a Vert.x timer scheduled for the remaining time, rounded up to the next whole
     * millisecond so the answer is never written early. A deferred answer leaves the calling thread
     * free at once — it returns before the release — and is dropped when its response has been ended
     * by another writer or has closed by the time the timer fires.
     *
     * @param ctx            the request whose answer is held back
     * @param releaseAtNanos the {@link System#nanoTime()} value before which the answer is not written
     * @param answer         the writer's whole answer, ending the response through
     *                       {@link ResponseEnds#endResponse}
     */
    public static void endNoEarlierThan(RoutingContext ctx, long releaseAtNanos, Runnable answer) {
        long remainingNanos = releaseAtNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            answer.run();
            return;
        }
        long delayMillis = Math.ceilDiv(remainingNanos, NANOS_PER_MILLI);
        HttpServerResponse response = ctx.response();
        ctx.vertx().setTimer(delayMillis, timerId -> {
            if (!response.ended() && !response.closed()) {
                answer.run();
            }
        });
    }
}
