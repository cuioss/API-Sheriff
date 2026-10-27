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
 * <strong>The held-answer sub-budget.</strong> A held answer must not keep its general admission
 * permit for the length of its wait, or a caller producing nothing but held answers could fill the
 * general pool. Before it arms a timer, this primitive therefore enters the hold through the
 * {@link Hold} the edge root stashed on the request ({@link #HOLD_KEY}), which moves the request onto
 * the held-answer sub-budget. A refused hold — the sub-budget is full — is answered {@code 503} at
 * once, with no timer, and that answer is the same for every held response whatever its route or
 * caller.
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

    /**
     * The {@link Hold} of the request, stashed on the {@link RoutingContext} by the edge root when it
     * admits the request: how an answer about to wait enters the held-answer sub-budget. Carried on the
     * context because the writers that hold answers are built without the admission, and the
     * virtual-thread hop carries only the {@link RoutingContext}.
     */
    public static final String HOLD_KEY = "sheriff.hold";

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int SERVICE_UNAVAILABLE = 503;

    /**
     * Entry into the held-answer sub-budget for one admitted request — the edge root binds it to the
     * admission's {@code tryEnterHold} for that request.
     */
    @FunctionalInterface
    public interface Hold {

        /**
         * Moves the request from the general admission pool to the held-answer sub-budget.
         *
         * @return {@code true} when the request may wait; {@code false} when the sub-budget is full and
         *         nothing changed
         */
        boolean tryEnter();
    }

    private ResponseDelay() {
        // static helpers only
    }

    /**
     * Runs {@code answer} no earlier than {@code releaseAtNanos}. Three cases:
     * <ul>
     *   <li><strong>The release time has passed</strong> — the answer runs at once; no hold is
     *       entered.</li>
     *   <li><strong>The answer has to wait, and the hold is entered</strong> — the answer runs from a
     *       Vert.x timer scheduled for the remaining time, rounded up to the next whole millisecond so it
     *       is never written early. A request whose context carries no {@link Hold} waits the same way;
     *       the hold is then not entered.</li>
     *   <li><strong>The answer has to wait, and the hold is refused</strong> — the answer is replaced by
     *       a bare {@code 503} written at once through {@link ResponseEnds#reject}; no timer is
     *       armed.</li>
     * </ul>
     * A deferred answer leaves the calling thread free at once — it returns before the release — and is
     * dropped when its response has been ended by another writer or has closed by the time the timer
     * fires.
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
        Hold hold = ctx.get(HOLD_KEY);
        if (hold != null && !hold.tryEnter()) {
            // The marked connection is retired as the held answer would have retired it.
            ConnectionRetirement.retireMarkedConnection(ctx);
            ResponseEnds.reject(ctx, SERVICE_UNAVAILABLE);
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
