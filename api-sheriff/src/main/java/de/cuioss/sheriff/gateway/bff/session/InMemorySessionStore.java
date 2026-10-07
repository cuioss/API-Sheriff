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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The single-node in-memory {@link SessionStore} — the only supported store for
 * {@code mode: server} (D3).
 * <p>
 * Sessions are keyed by their stable session id in a primary map. Beside each record the store holds
 * the session's current cookie handle and its last-access instant; a second map resolves a cookie
 * handle to the session id. Two secondary indexes — by IdP {@code sid} and by {@code sub} — give O(1)
 * back-channel logout destruction without scanning the primary map. Every removal path (direct
 * destroy, back-channel destroy, lazy eviction on resolve, the sweep, and the eviction of the record a
 * write replaces) keeps the handle map and the indexes consistent through a single
 * {@link #removeInternal} seam, so a removed session never leaves a handle behind.
 * <p>
 * <strong>Three writes.</strong> {@link #create} stores a new session and is the login's write.
 * {@link #replaceIfPresent} updates a session that already exists — the write a refresh makes — and
 * keeps the handle the store holds for it. {@link #replaceAndReissueHandle} updates it and swaps the
 * handle — the write a step-up or a widening makes. Neither updating write stores anything when the id
 * is not held, so a write that was in flight while the session was destroyed does not bring it back.
 * In each of them the presence check and the write run under the same monitor as
 * {@link #destroyById}, {@link #destroyBySid} and {@link #destroyBySub}, so no destruction can take
 * effect between them.
 * <p>
 * <strong>The handle is store-side state, not a component of the record.</strong> A refresh that
 * resolved the session before a re-issue and replaces the record after it writes the record only: the
 * handle it leaves in place is the one read under the monitor at that moment, which is the re-issued
 * one. It cannot put the previous handle back.
 * <p>
 * <strong>Two deadlines.</strong> A session is expired at the earlier of its absolute lifetime and its
 * idle deadline, the last access plus the idle timeout. {@link #recordAccess} moves the last access
 * and writes that instant alone, never the record, so it cannot overwrite token material a concurrent
 * refresh has just stored. Expiry is enforced two ways: lazily on {@link #resolve} (an expired session
 * is evicted as it is looked up) and by {@link #sweepExpired}, which the session runtime's periodic
 * task calls and which a <em>capacity-consuming</em> {@link #create} — one introducing a session id
 * the store does not yet hold — calls on finding the store at its {@code maxSessions} bound. This
 * store itself starts no thread and no timer. A write that replaces an already-stored id — either
 * updating write, or a {@link #create} naming an id the store holds — replaces a record already
 * counted against the bound, so it consumes no capacity and never sweeps.
 * <p>
 * The sweep at the bound is what makes the bound a ceiling on <em>live</em> sessions rather than on
 * accumulated ones: a capacity-consuming creation sweeps once at the bound and re-tests it; only a
 * store still full of live sessions afterwards is refused, fail-closed.
 * <p>
 * <strong>Thread safety.</strong> Every operation is guarded by the instance monitor, so each one is
 * atomic with respect to every other. No operation is split across two calls.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class InMemorySessionStore implements SessionStore {

    private final int maxSessions;
    private final Duration idleTimeout;
    private final Map<String, HeldSession> byId = new HashMap<>();
    private final Map<String, String> idByHandle = new HashMap<>();
    private final Map<String, Set<String>> bySid = new HashMap<>();
    private final Map<String, Set<String>> bySub = new HashMap<>();

    /**
     * Creates a store bounded to {@code maxSessions} live sessions, each ending after
     * {@code idleTimeout} without a recorded access.
     *
     * @param maxSessions the hard capacity bound; must be positive
     * @param idleTimeout how long a session may go without a recorded access before it is expired;
     *                    must be positive
     * @throws IllegalArgumentException when {@code maxSessions} or {@code idleTimeout} is not positive
     */
    public InMemorySessionStore(int maxSessions, Duration idleTimeout) {
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive, but was " + maxSessions);
        }
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive, but was " + idleTimeout);
        }
        this.maxSessions = maxSessions;
        this.idleTimeout = idleTimeout;
    }

    @Override
    public synchronized void create(SessionRecord session, String cookieHandle, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(cookieHandle, "cookieHandle");
        Objects.requireNonNull(now, "now");
        // An id the store already holds names a record already counted against the bound, so storing
        // it consumes no new capacity and is not refused at the ceiling. A rotated or widened session
        // does not take this path — it is written through an updating write, which never creates.
        if (!byId.containsKey(session.sessionId()) && byId.size() >= maxSessions) {
            // Sweep once, then re-test: expired sessions still hold their slots until something
            // reclaims them, and reaching the bound is the trigger. A store still at the bound after
            // the sweep is genuinely full of live sessions, and refusing it is the fail-closed guard.
            sweepExpired(now);
            if (byId.size() >= maxSessions) {
                throw new IllegalStateException("session store is at its max-session bound of " + maxSessions);
            }
        }
        store(session, cookieHandle, now);
    }

    @Override
    public synchronized boolean replaceIfPresent(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        // The presence check and the replacement share this monitor with every destroy method, so a
        // logout or a back-channel logout either ran before the check — and nothing is written — or
        // runs after the replacement and removes it. There is no window in which a destroyed session
        // is written back.
        HeldSession held = byId.get(session.sessionId());
        if (held == null) {
            return false;
        }
        // The handle is the one held NOW, read under the monitor: a re-issue that ran since the caller
        // resolved the session stays in force.
        store(session, held.cookieHandle, held.lastAccess);
        return true;
    }

    @Override
    public synchronized boolean replaceAndReissueHandle(SessionRecord session, String newCookieHandle) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(newCookieHandle, "newCookieHandle");
        // One atomic step with every destroy method, exactly as replaceIfPresent: a session destroyed
        // before the check is not written back and its new handle is never registered.
        HeldSession held = byId.get(session.sessionId());
        if (held == null) {
            return false;
        }
        // store() drops the previous handle through removeInternal before it registers the new one, so
        // the previous cookie value resolves nothing from here on. Last access is carried over: a
        // re-issue is not an access.
        store(session, newCookieHandle, held.lastAccess);
        return true;
    }

    @Override
    public synchronized void recordAccess(String sessionId, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(now, "now");
        HeldSession held = byId.get(sessionId);
        // Only the instant is written, never the record: the record a concurrent refresh stored under
        // this monitor a moment ago stays exactly as that refresh left it.
        if (held != null && now.isAfter(held.lastAccess)) {
            held.lastAccess = now;
        }
    }

    /**
     * Stores {@code session} under its id with {@code cookieHandle} and {@code lastAccess} beside it,
     * replacing any record — and dropping any handle — already held there, and brings both secondary
     * indexes in line with it. Callers hold the instance monitor.
     *
     * @throws IllegalStateException when {@code cookieHandle} resolves to a different session
     */
    private void store(SessionRecord session, String cookieHandle, Instant lastAccess) {
        String sessionId = session.sessionId();
        String owner = idByHandle.get(cookieHandle);
        if (owner != null && !owner.equals(sessionId)) {
            // Handles are 256 random bits, so this is not reachable by chance. Refusing it keeps a
            // caller that passed a handle twice from re-pointing another session's cookie.
            throw new IllegalStateException("cookie handle already resolves to another session");
        }
        // The record may carry a different sub or sid than the one it replaces, and a deindex is
        // only ever keyed by the record's OWN sub/sid — so the previous record leaves through the
        // same removeInternal seam every other removal path uses. Left indexed, its stale sub/sid
        // would keep resolving to this id, and a later destroyBySub/destroyBySid on that stale key
        // would destroy the replacement and report a phantom deletion. The same seam drops the
        // previous handle.
        removeInternal(sessionId);
        byId.put(sessionId, new HeldSession(session, cookieHandle, lastAccess));
        idByHandle.put(cookieHandle, sessionId);
        index(bySub, session.sub(), sessionId);
        String sid = session.sid();
        if (sid != null) {
            index(bySid, sid, sessionId);
        }
    }

    @Override
    public synchronized Optional<SessionRecord> resolve(String cookieHandle, Instant now) {
        Objects.requireNonNull(cookieHandle, "cookieHandle");
        Objects.requireNonNull(now, "now");
        String sessionId = idByHandle.get(cookieHandle);
        if (sessionId == null) {
            return Optional.empty();
        }
        HeldSession held = byId.get(sessionId);
        if (held == null) {
            return Optional.empty();
        }
        if (isExpired(held, now)) {
            removeInternal(sessionId);
            return Optional.empty();
        }
        return Optional.of(held.session);
    }

    @Override
    public synchronized void destroyById(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        removeInternal(sessionId);
    }

    @Override
    public synchronized int destroyBySid(String sid) {
        Objects.requireNonNull(sid, "sid");
        return removeAll(bySid.get(sid));
    }

    @Override
    public synchronized int destroyBySub(String sub) {
        Objects.requireNonNull(sub, "sub");
        return removeAll(bySub.get(sub));
    }

    @Override
    public synchronized int sweepExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, HeldSession> entry : byId.entrySet()) {
            if (isExpired(entry.getValue(), now)) {
                expired.add(entry.getKey());
            }
        }
        expired.forEach(this::removeInternal);
        return expired.size();
    }

    /**
     * @return the current number of stored sessions
     */
    public synchronized int size() {
        return byId.size();
    }

    /**
     * Whether a held session is expired at {@code now}: past its absolute lifetime, or idle for the
     * idle timeout or longer — the earlier of the two deadlines. Both are inclusive of the boundary.
     */
    private boolean isExpired(HeldSession held, Instant now) {
        return held.session.isExpired(now) || !now.isBefore(held.lastAccess.plus(idleTimeout));
    }

    // java:S2589 — bySub/bySid.get() returns null for an unknown sub/sid, so the null guard is
    // load-bearing (removeAll is called with the raw Map.get result); the analyzer misjudges it.
    @SuppressWarnings("java:S2589")
    private int removeAll(Set<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return 0;
        }
        List<String> snapshot = new ArrayList<>(sessionIds);
        snapshot.forEach(this::removeInternal);
        return snapshot.size();
    }

    private void removeInternal(String sessionId) {
        HeldSession held = byId.remove(sessionId);
        if (held == null) {
            return;
        }
        idByHandle.remove(held.cookieHandle);
        SessionRecord session = held.session;
        deindex(bySub, session.sub(), sessionId);
        String sid = session.sid();
        if (sid != null) {
            deindex(bySid, sid, sessionId);
        }
    }

    private static void index(Map<String, Set<String>> map, String key, String sessionId) {
        map.computeIfAbsent(key, unused -> new HashSet<>()).add(sessionId);
    }

    private static void deindex(Map<String, Set<String>> map, String key, String sessionId) {
        Set<String> sessionIds = map.get(key);
        if (sessionIds == null) {
            return;
        }
        sessionIds.remove(sessionId);
        if (sessionIds.isEmpty()) {
            map.remove(key);
        }
    }

    /**
     * One stored session: the record, plus the two pieces of store-side state that are deliberately
     * not components of it. Read and written only under the store's monitor.
     */
    private static final class HeldSession {

        private final SessionRecord session;
        private final String cookieHandle;
        private Instant lastAccess;

        HeldSession(SessionRecord session, String cookieHandle, Instant lastAccess) {
            this.session = session;
            this.cookieHandle = cookieHandle;
            this.lastAccess = lastAccess;
        }
    }
}
