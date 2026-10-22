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
package de.cuioss.sheriff.gateway.edge.admission;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.cuioss.sheriff.gateway.bff.session.SessionRelayRegistry;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.ext.web.RoutingContext;

/**
 * Admission into the edge and its drain on shutdown: the bounded
 * {@linkplain EdgeHardeningOptions#admissionCap() admission cap} every request is admitted under
 * before a virtual thread is dispatched, the
 * {@linkplain EdgeHardeningOptions#webSocketRelayCap() WebSocket relay sub-budget} an upgrade takes in
 * addition to it, the in-flight count, and the bounded drain that waits for in-flight requests on
 * {@code SIGTERM}.
 * <p>
 * Thread-safe: the permits are semaphores, the in-flight count is atomic, the draining flag is
 * volatile, and every per-request release is latched so it happens exactly once. The volatile flag
 * alone does not keep a request from being admitted after the drain has finished; that guarantee
 * comes from the order of the accesses — {@link #tryAdmit()} publishes its claim before it reads the
 * flag, and {@link #drain()} sets the flag before it reads the in-flight count.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EdgeAdmission {

    private static final CuiLogger LOGGER = new CuiLogger(EdgeAdmission.class);

    /** Holds the per-request admission-release CAS guard stashed by the edge root's {@code handle} so a
     * terminal path that runs <em>after</em> the virtual-thread hop — where only the
     * {@link RoutingContext} is in scope — can still release the permit exactly once. The WebSocket
     * relay is the one such path: a completed upgrade takes the connection over, so the HTTP end
     * handler never fires and {@code TerminalDispatch.dispatchWebSocket} must hand the relay its own
     * release callback. */
    public static final String ADMISSION_GUARD_KEY = "sheriff.admissionguard";
    /** Holds the per-request release guard for the WebSocket relay sub-permit. Present ONLY once
     * {@link #tryAcquireWebSocketRelay} has actually acquired that sub-permit, so its absence is how
     * every release site knows there is no sub-permit to return. */
    private static final String WEBSOCKET_RELAY_GUARD_KEY = "sheriff.wsrelayguard";
    /** Holds the {@link SessionRelayRegistry.Tracked} handle of a WebSocket upgrade a session was let
     * through with. Present ONLY once {@code TerminalDispatch.dispatchWebSocket} has tracked the relay;
     * every site that returns the admission permit stops the tracking through it (see
     * {@link #releaseAdmission}), so a tracked relay is released exactly where its permit is. */
    public static final String SESSION_RELAY_KEY = "sheriff.sessionrelay";
    private static final long DRAIN_POLL_INTERVAL_MILLIS = 50L;

    private final EdgeHardeningOptions hardening;
    private final Semaphore admission;
    /**
     * The WebSocket relay sub-budget: acquired <em>in addition to</em> {@link #admission}, never
     * instead of it. A relay holds its general admission permit for the connection's whole lifetime,
     * so without this second bound a handful of long-lived relays would consume the general pool and
     * starve ordinary HTTP traffic — trading the permit leak for a slow squeeze.
     */
    private final Semaphore webSocketRelayAdmission;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean draining;

    /**
     * @param hardening the edge transport / admission bounds the two permit pools and the drain
     *                  window are sized from
     */
    public EdgeAdmission(EdgeHardeningOptions hardening) {
        this.hardening = Objects.requireNonNull(hardening, "hardening");
        this.admission = new Semaphore(hardening.admissionCap());
        this.webSocketRelayAdmission = new Semaphore(hardening.webSocketRelayCap());
    }

    /**
     * Admits one request: refused while the edge drains or once the admission cap is exhausted, so a
     * flood is answered {@code 503} rather than spawning unbounded virtual threads. An admitted
     * request holds one general permit and counts as in flight until {@link #releaseAdmission}.
     * <p>
     * The claim is published before the draining flag is read: the permit is acquired and the
     * in-flight count incremented first, and only then is {@code draining} checked. When the flag is
     * set, both are undone and the request is refused. {@link #drain()} does the mirror image — it sets
     * the flag and then reads the in-flight count. Because every one of these accesses is volatile or
     * atomic, the two sides cannot miss each other: either this method sees the flag and withdraws its
     * claim, or the drain sees the claim and waits for its release. Reading the flag first, as a
     * pre-check, would reopen the window in which a request passes the check, the drain reads zero in
     * flight and returns, and the request is admitted after the drain has finished.
     * <p>
     * A request refused this way holds a permit and an in-flight count for the moment between the
     * increment and the undo, so a concurrent drain may wait one poll interval longer; nothing is left
     * behind.
     *
     * @return {@code true} when the request was admitted
     */
    public boolean tryAdmit() {
        if (!admission.tryAcquire()) {
            return false;
        }
        inFlight.incrementAndGet();
        if (draining) {
            inFlight.decrementAndGet();
            admission.release();
            return false;
        }
        return true;
    }

    /**
     * Acquires the WebSocket relay sub-permit for an upgrade, <em>in addition to</em> the general
     * permit the request already holds, and stashes its release guard under
     * {@link #WEBSOCKET_RELAY_GUARD_KEY} — only on the acquired path, since every release site keys
     * off that guard's presence to decide whether there is a sub-permit to return.
     *
     * @param ctx the upgrade request
     * @return {@code true} when the sub-permit was acquired; {@code false} when the relay sub-budget
     *         is exhausted
     */
    public boolean tryAcquireWebSocketRelay(RoutingContext ctx) {
        if (!webSocketRelayAdmission.tryAcquire()) {
            return false;
        }
        ctx.put(WEBSOCKET_RELAY_GUARD_KEY, new AtomicBoolean());
        return true;
    }

    /**
     * Releases this request's admission permits and decrements the in-flight counter exactly once,
     * guarded by {@code released} so the normal end-handler path, the executor-rejection rollback
     * path and the WebSocket relay-teardown callback (see {@code TerminalDispatch.dispatchWebSocket})
     * can all call it without double-counting.
     * <p>
     * The general permit and the WebSocket relay sub-permit are an ordered pair that must be returned
     * together: a request that acquired both would otherwise strand the sub-permit whenever the
     * general permit is released by a different site than the relay callback (the upstream-dial
     * failure ends the HTTP response, so the end handler gets there first). Both are therefore
     * released here, each behind its own idempotence latch.
     * <p>
     * A WebSocket relay tracked with its session stops being tracked here too, for the same reason: the
     * registry entry then leaves on every path the permit does — the relay's teardown, a failed client
     * upgrade, a failed upstream dial, a refused relay budget — and cannot outlive the relay.
     *
     * @param ctx      the admitted request
     * @param released the request's admission-release latch
     */
    public void releaseAdmission(RoutingContext ctx, AtomicBoolean released) {
        SessionRelayRegistry.Tracked sessionRelay = ctx.get(SESSION_RELAY_KEY);
        if (sessionRelay != null) {
            // Idempotent, and a no-op once the session itself has ended.
            sessionRelay.release();
        }
        releaseWebSocketRelayPermit(ctx);
        if (released.compareAndSet(false, true)) {
            admission.release();
            inFlight.decrementAndGet();
        }
    }

    /**
     * Stops admitting new requests and drains in-flight ones within the bounded
     * {@linkplain EdgeHardeningOptions#drainTimeoutMillis() drain window}, so the shutdown completes
     * cleanly within the Quarkus shutdown timeout. Returns early, with the interrupt flag restored,
     * when the waiting thread is interrupted.
     */
    public void drain() {
        draining = true;
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(hardening.drainTimeoutMillis());
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(DRAIN_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        LOGGER.debug("Edge drain finished; in-flight=%s", inFlight.get());
    }

    /**
     * Returns the WebSocket relay sub-permit exactly once, and only for a request that actually
     * acquired one — the guard is stashed on the context by {@link #tryAcquireWebSocketRelay} at
     * acquisition, so an absent guard means an HTTP/gRPC request (or a refused upgrade) with nothing
     * to return.
     */
    private void releaseWebSocketRelayPermit(RoutingContext ctx) {
        AtomicBoolean relayReleased = ctx.get(WEBSOCKET_RELAY_GUARD_KEY);
        if (relayReleased != null && relayReleased.compareAndSet(false, true)) {
            webSocketRelayAdmission.release();
        }
    }
}
