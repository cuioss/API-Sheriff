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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.cuioss.sheriff.gateway.bff.session.SessionRelayRegistry;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.ext.web.RoutingContext;

/**
 * Admission into the edge and its drain on shutdown: three permit pools, the in-flight count, and the
 * bounded drain that waits for in-flight requests on {@code SIGTERM}.
 * <ol>
 *   <li>The general pool, sized by the {@linkplain EdgeHardeningOptions#admissionCap() admission cap}:
 *       every request is admitted under one of its permits before a virtual thread is dispatched.</li>
 *   <li>The {@linkplain EdgeHardeningOptions#webSocketRelayCap() WebSocket relay sub-budget}: an
 *       upgrade takes one of its permits <em>in addition to</em> its general permit and holds both for
 *       the relay's lifetime.</li>
 *   <li>The {@linkplain EdgeHardeningOptions#heldAnswerCap() held-answer sub-budget}: an answer held
 *       back on a timer takes one of its permits <em>instead of</em> its general permit
 *       ({@link #tryEnterHold}), so held answers cannot occupy the general pool.</li>
 * </ol>
 * A request that hands its general permit back on entering a hold stays counted in flight until its
 * response ends, so the drain still waits for its held answer.
 * <p>
 * <strong>One source's share of the held-answer sub-budget.</strong> An answer the {@code delay} action
 * holds for a tripped source carries that source on its context ({@link #HELD_SOURCE_KEY}); entering
 * its hold also takes one unit of the source's
 * {@linkplain EdgeHardeningOptions#heldAnswerSourceShare() share}, and the unit is returned exactly
 * once, together with the held permit. A source whose share is used up is refused the hold, and
 * {@link #heldShareExhausted} lets the source gate refuse its further requests before admission. The
 * per-source counts live in a map that holds a source only while at least one of its held answers
 * waits, so it never holds more entries than the sub-budget has permits.
 * <p>
 * Thread-safe: the permits are semaphores, the in-flight count is atomic, the per-source counts are
 * updated atomically per key, the draining flag is volatile, and every per-request release is latched
 * so it happens exactly once — the general permit, the in-flight count and each sub-budget permit
 * behind a latch of its own. The volatile flag alone
 * does not keep a request from being admitted after the drain has finished; that guarantee comes from
 * the order of the accesses — {@link #tryAdmit()} publishes its claim before it reads the flag, and
 * {@link #drain()} sets the flag before it reads the in-flight count.
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
    /** Holds the per-request latch of the general admission permit, stashed with
     * {@link #ADMISSION_GUARD_KEY} by {@link #armReleaseGuards}. It is separate from that guard, which
     * latches the in-flight count, because a held answer returns its general permit at hold entry
     * ({@link #tryEnterHold}) while the request stays in flight until its response ends. */
    private static final String GENERAL_PERMIT_GUARD_KEY = "sheriff.generalpermitguard";
    /** Holds the per-request release guard for the held-answer sub-permit. Present ONLY once
     * {@link #tryEnterHold} has actually acquired that sub-permit, so its absence is how
     * {@link #releaseAdmission} knows there is no held permit to return. */
    private static final String HELD_ANSWER_GUARD_KEY = "sheriff.heldanswerguard";
    /** Holds the source an answer held by the {@code delay} action is held for, stashed by
     * {@link SourceGate} before the answer is armed. A hold entered for a request carrying it counts
     * against that source's share of the held-answer sub-budget; a request without it — the uniform
     * {@code 404}'s floor alone — counts against no share. */
    public static final String HELD_SOURCE_KEY = "sheriff.heldsource";
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
    /**
     * The held-answer sub-budget: acquired <em>instead of</em> {@link #admission} by an answer that
     * waits on a timer. Without it every held answer would keep its general permit for the whole wait,
     * so a scan whose answers are all held could fill the general pool with answers that do no work.
     */
    private final Semaphore heldAnswerAdmission;
    /** How many answers held for one tripped source may wait at once. */
    private final int heldAnswerSourceShare;
    /** The held answers waiting per source; a source is present only while its count is positive. */
    private final ConcurrentMap<Object, Integer> heldPerSource = new ConcurrentHashMap<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean draining;

    /**
     * @param hardening the edge transport / admission bounds the three permit pools, the per-source
     *                  share of the held-answer sub-budget and the drain window are sized from
     */
    public EdgeAdmission(EdgeHardeningOptions hardening) {
        this.hardening = Objects.requireNonNull(hardening, "hardening");
        this.admission = new Semaphore(hardening.admissionCap());
        this.webSocketRelayAdmission = new Semaphore(hardening.webSocketRelayCap());
        this.heldAnswerAdmission = new Semaphore(hardening.heldAnswerCap());
        this.heldAnswerSourceShare = hardening.heldAnswerSourceShare();
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
     * Stashes the release guards of a request {@link #tryAdmit()} has just admitted: the in-flight
     * guard under {@link #ADMISSION_GUARD_KEY}, which every release site hands to
     * {@link #releaseAdmission}, and the general-permit latch beside it, which {@link #tryEnterHold}
     * and {@link #releaseAdmission} share so the general permit is returned exactly once whichever of
     * them gets there first.
     *
     * @param ctx the admitted request
     * @return the in-flight guard, as stashed under {@link #ADMISSION_GUARD_KEY}
     */
    public AtomicBoolean armReleaseGuards(RoutingContext ctx) {
        AtomicBoolean released = new AtomicBoolean();
        ctx.put(ADMISSION_GUARD_KEY, released);
        ctx.put(GENERAL_PERMIT_GUARD_KEY, new AtomicBoolean());
        return released;
    }

    /**
     * Moves an admitted request whose answer is about to be held back on a timer from the general pool
     * to the held-answer sub-budget: one held permit is taken with a single atomic
     * {@link Semaphore#tryAcquire()}, its release guard is stashed under
     * {@link #HELD_ANSWER_GUARD_KEY} — only on this acquired path — and the request's general permit
     * is returned at once, behind the general-permit latch {@link #armReleaseGuards} stashed, so the end
     * handler cannot return it a second time. The request stays counted in flight until its response
     * ends, so {@link #drain()} still waits for the held answer.
     * <p>
     * A request carrying a {@link #HELD_SOURCE_KEY} also takes one unit of that source's share first;
     * the unit is returned with the held permit, by the same latch.
     * <p>
     * When the sub-budget, or the source's share of it, is full nothing changes and {@code false} is
     * returned: the caller refuses the hold instead of holding, and the request keeps its general
     * permit.
     * <p>
     * A response that has already been released — its end handler ran before the hold was entered —
     * gives the held permit straight back, so a hold entered too late leaves nothing behind.
     *
     * @param ctx the admitted request whose answer is to be held
     * @return {@code true} when the held permit was taken and the general permit handed back;
     *         {@code false} when the held-answer sub-budget or the source's share of it is full
     * @throws IllegalStateException when the request carries no release guards — it was not admitted
     *                               through {@link #armReleaseGuards}
     */
    public boolean tryEnterHold(RoutingContext ctx) {
        AtomicBoolean released = ctx.get(ADMISSION_GUARD_KEY);
        AtomicBoolean generalPermitReturned = ctx.get(GENERAL_PERMIT_GUARD_KEY);
        if (released == null || generalPermitReturned == null) {
            throw new IllegalStateException("A hold can only be entered by a request whose release guards were armed");
        }
        Object source = ctx.get(HELD_SOURCE_KEY);
        if (source != null && !tryTakeShare(source)) {
            return false;
        }
        if (!heldAnswerAdmission.tryAcquire()) {
            if (source != null) {
                returnShare(source);
            }
            return false;
        }
        ctx.put(HELD_ANSWER_GUARD_KEY, new AtomicBoolean());
        returnGeneralPermit(generalPermitReturned);
        if (released.get()) {
            // The response ended before the hold was entered; its release already ran and saw no held
            // permit, so the one taken here is returned now.
            releaseHeldAnswerPermit(ctx);
        }
        return true;
    }

    /**
     * Whether a source has used up its {@linkplain EdgeHardeningOptions#heldAnswerSourceShare() share}
     * of the held-answer sub-budget: as many of its answers as the share allows are waiting now. Read on
     * the event loop by the source gate before admission, so a tripped source's further requests are
     * refused there rather than processed and then refused a hold.
     *
     * @param source the source, as stashed under {@link #HELD_SOURCE_KEY}
     * @return {@code true} when no further answer held for {@code source} may wait now
     */
    public boolean heldShareExhausted(Object source) {
        Integer held = heldPerSource.get(source);
        return held != null && held >= heldAnswerSourceShare;
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
     * <p>
     * A request whose answer was held returned its general permit at hold entry; the general-permit
     * latch it shares with {@link #tryEnterHold} keeps this method from returning it again, and the held
     * permit is returned here instead, behind its own latch. The held permit is returned after
     * {@code released} is set, so a hold entered concurrently either is seen here or sees the release
     * itself and returns its permit (see {@link #tryEnterHold}).
     *
     * @param ctx      the admitted request
     * @param released the request's in-flight latch, stashed under {@link #ADMISSION_GUARD_KEY}
     */
    public void releaseAdmission(RoutingContext ctx, AtomicBoolean released) {
        SessionRelayRegistry.Tracked sessionRelay = ctx.get(SESSION_RELAY_KEY);
        if (sessionRelay != null) {
            // Idempotent, and a no-op once the session itself has ended.
            sessionRelay.release();
        }
        releaseWebSocketRelayPermit(ctx);
        if (released.compareAndSet(false, true)) {
            AtomicBoolean generalPermitReturned = ctx.get(GENERAL_PERMIT_GUARD_KEY);
            if (generalPermitReturned != null) {
                returnGeneralPermit(generalPermitReturned);
            } else {
                // No separate latch was armed, so no hold can have returned the permit: the in-flight
                // latch alone makes this the one return.
                admission.release();
            }
            inFlight.decrementAndGet();
        }
        releaseHeldAnswerPermit(ctx);
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

    /**
     * Returns the held-answer sub-permit exactly once, and only for a request that actually acquired
     * one — the guard is stashed by {@link #tryEnterHold} on its acquired path, so an absent guard means
     * an answer that was never held, or a hold that was refused, with nothing to return. A request that
     * carries a {@link #HELD_SOURCE_KEY} took a unit of that source's share with the permit, and the
     * unit is returned behind the same latch.
     */
    private void releaseHeldAnswerPermit(RoutingContext ctx) {
        AtomicBoolean heldReleased = ctx.get(HELD_ANSWER_GUARD_KEY);
        if (heldReleased != null && heldReleased.compareAndSet(false, true)) {
            heldAnswerAdmission.release();
            Object source = ctx.get(HELD_SOURCE_KEY);
            if (source != null) {
                returnShare(source);
            }
        }
    }

    /**
     * Takes one unit of {@code source}'s share, atomically for that source.
     *
     * @return {@code true} when the unit was taken; {@code false} when the share is used up
     */
    private boolean tryTakeShare(Object source) {
        AtomicBoolean taken = new AtomicBoolean();
        heldPerSource.compute(source, (key, held) -> {
            int count = held == null ? 0 : held;
            if (count >= heldAnswerSourceShare) {
                return held;
            }
            taken.set(true);
            return count + 1;
        });
        return taken.get();
    }

    /** Returns one unit of {@code source}'s share; the source leaves the map with its last unit. */
    private void returnShare(Object source) {
        heldPerSource.computeIfPresent(source, (key, held) -> held > 1 ? held - 1 : null);
    }

    /** Returns the general admission permit exactly once, behind the request's general-permit latch. */
    private void returnGeneralPermit(AtomicBoolean generalPermitReturned) {
        if (generalPermitReturned.compareAndSet(false, true)) {
            admission.release();
        }
    }
}
