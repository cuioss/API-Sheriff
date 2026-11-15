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
package de.cuioss.sheriff.gateway.bff.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import org.jspecify.annotations.Nullable;

/**
 * The long-lived relays that were opened with a session — WebSocket relays — so that a relay does
 * not outlive the session it was admitted with.
 * <p>
 * A relay is tracked under the <strong>stable session identity</strong>
 * ({@link SessionRecord#sessionId()}), never under a cookie handle: a step-up or a scope widening
 * re-issues the handle while the session lives on, and a relay opened before it stays open.
 * <p>
 * <strong>Two ways a tracked relay is ended.</strong>
 * <ul>
 *   <li>{@link #sessionEnded} — the registry is the {@link SessionEndListener} of a server-mode
 *       store, which reports every session it destroys, evicts on expiry or ends under a bound. Every
 *       relay tracked under that identity is told to close.</li>
 *   <li>{@link Tracked#untilAbsoluteExpiry()} — the time left until the session's absolute lifetime
 *       ends, for the relay to arm a timer of its own. This is the only end a stateless (cookie-mode)
 *       session has: nothing server-side observes its logout, so nothing reports one here.</li>
 * </ul>
 * <strong>Bound.</strong> At most {@code capacity} relays are tracked at once. A relay beyond it is
 * refused by {@link #track} — the caller does not open it — rather than opened untracked. An entry
 * leaves on {@link Tracked#release()}, which the relay's owner calls on every teardown, and on
 * {@link #sessionEnded}.
 * <p>
 * <strong>Thread safety and lock discipline.</strong> Safe for concurrent use. The registry's own lock
 * guards its map only and is never held while a relay's close action runs, and the registry never
 * calls a session store. A close action is run on the thread that reported the end — a logout, a
 * login, the sweep — so it must only hand the close over to the relay's own thread, never perform it.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SessionRelayRegistry implements SessionEndListener {

    private static final Tracked UNTRACKED = new Tracked(null, "", null);

    private final int capacity;
    private final Clock clock;
    private final Object lock = new Object();
    /** The tracked relays by session identity; read and written under {@link #lock}. */
    private final Map<String, Set<Tracked>> bySession = new HashMap<>();
    /** The number of tracked relays across all sessions; read and written under {@link #lock}. */
    private int tracked;

    /**
     * Creates a registry tracking at most {@code capacity} relays.
     *
     * @param capacity the most relays tracked at once; must be positive
     * @param clock    the clock the remaining absolute lifetime is measured against
     * @throws IllegalArgumentException when {@code capacity} is not positive
     */
    public SessionRelayRegistry(int capacity, Clock clock) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive, but was " + capacity);
        }
        this.capacity = capacity;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The handle of a relay that was opened without a session and is therefore tracked nowhere: it is
     * never told to close, has no absolute expiry, and its {@link Tracked#release()} does nothing.
     *
     * @return the shared no-session handle
     */
    public static Tracked untracked() {
        return UNTRACKED;
    }

    /**
     * Starts tracking one relay about to be opened with the given session.
     *
     * @param sessionId the stable identity of the session the relay is opened with
     * @param expiresAt the session's absolute expiry
     * @return the relay's handle; empty when the registry is at its capacity, in which case the caller
     *         must not open the relay
     */
    public Optional<Tracked> track(String sessionId, Instant expiresAt) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Tracked handle = new Tracked(this, sessionId, expiresAt);
        synchronized (lock) {
            if (tracked >= capacity) {
                return Optional.empty();
            }
            bySession.computeIfAbsent(sessionId, unused -> new HashSet<>()).add(handle);
            tracked++;
        }
        return Optional.of(handle);
    }

    /**
     * Tells every relay tracked under {@code sessionId} to close and stops tracking them. A session
     * with no tracked relay is ignored.
     *
     * @param sessionId the stable identity of the session that ended
     */
    @Override
    public void sessionEnded(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        List<Tracked> ended;
        synchronized (lock) {
            Set<Tracked> relays = bySession.remove(sessionId);
            if (relays == null) {
                return;
            }
            tracked -= relays.size();
            ended = List.copyOf(relays);
        }
        // Outside the lock: a close action hands over to the relay's own thread and must not be able
        // to block, or be blocked by, a concurrent track or release.
        ended.forEach(Tracked::end);
    }

    /**
     * @return the number of relays tracked at this moment
     */
    public int size() {
        synchronized (lock) {
            return tracked;
        }
    }

    private void release(Tracked handle) {
        synchronized (lock) {
            Set<Tracked> relays = bySession.get(handle.sessionId);
            if (relays == null || !relays.remove(handle)) {
                return;
            }
            tracked--;
            if (relays.isEmpty()) {
                bySession.remove(handle.sessionId);
            }
        }
    }

    /**
     * One tracked relay. Identity-compared: two relays of one session are two handles.
     * <p>
     * The handle is created before the relay exists, so the session can end before the relay has a
     * close action to offer. {@link #onSessionEnd} covers both orders: an end that came first runs the
     * action at once, an end that comes later runs it then. The action runs at most once.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public static final class Tracked {

        private final @Nullable SessionRelayRegistry registry;
        private final String sessionId;
        private final @Nullable Instant expiresAt;
        /** Whether the session has ended; read and written under this handle's monitor. */
        private boolean ended;
        /** The relay's close action once it has one; read and written under this handle's monitor. */
        private @Nullable Runnable closeAction;

        private Tracked(@Nullable SessionRelayRegistry registry, String sessionId, @Nullable Instant expiresAt) {
            this.registry = registry;
            this.sessionId = sessionId;
            this.expiresAt = expiresAt;
        }

        /**
         * Registers the action that closes the relay because its session ended. When the session has
         * already ended the action runs before this method returns, on the calling thread. On the
         * {@linkplain SessionRelayRegistry#untracked() no-session handle} the action is never run.
         *
         * @param closeAction closes the relay; it must be safe to run on any thread
         */
        public void onSessionEnd(Runnable closeAction) {
            Objects.requireNonNull(closeAction, "closeAction");
            if (registry == null) {
                // The shared no-session handle: nothing ever ends it, so it keeps no action.
                return;
            }
            synchronized (this) {
                if (!ended) {
                    this.closeAction = closeAction;
                    return;
                }
            }
            closeAction.run();
        }

        /**
         * @return {@code true} once the session this relay is tracked under has ended; always
         *         {@code false} for a relay opened without a session
         */
        public synchronized boolean sessionEnded() {
            return ended;
        }

        /**
         * The time left until the session's absolute lifetime ends, for the relay's own expiry timer.
         *
         * @return the remaining lifetime, zero or negative when it has already ended; empty for a relay
         *         opened without a session
         */
        public Optional<Duration> untilAbsoluteExpiry() {
            if (registry == null || expiresAt == null) {
                return Optional.empty();
            }
            return Optional.of(Duration.between(registry.clock.instant(), expiresAt));
        }

        /**
         * Stops tracking the relay. Called by the relay's owner on every teardown; a second call, and a
         * call after the session ended, does nothing.
         */
        public void release() {
            if (registry != null) {
                registry.release(this);
            }
        }

        private void end() {
            Runnable action;
            synchronized (this) {
                ended = true;
                action = closeAction;
                closeAction = null;
            }
            if (action != null) {
                action.run();
            }
        }

        /**
         * Overridden so the session identity is never printed.
         *
         * @return a fixed text naming only whether the relay is tracked
         */
        @Override
        public String toString() {
            return registry == null ? "Tracked[no session]" : "Tracked[session relay]";
        }
    }
}
