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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import de.cuioss.tools.logging.CuiLogger;

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
 * <strong>A bound per subject.</strong> One subject holds at most {@code maxSessionsPerSubject} live
 * sessions. A {@link #create} that introduces a new session id for a subject already at that bound
 * ends the subject's <em>oldest</em> session first — the one created earliest in this store, whatever
 * its last access — and repeats until the new session fits. Expired sessions of the subject are
 * dropped before the count is taken, so they neither count nor shield a live one. The subject is the
 * record's {@code sub}; the store serves one issuer, so {@code sub} alone names it. This bound is
 * applied before the store-wide one: ending a session of the same subject frees the slot the new
 * session takes, so a subject at its own bound is never refused by a full store. Only a creation
 * applies the bound; an updating write never ends another session.
 * <p>
 * <strong>Ended sessions are reported.</strong> Every path on which a session <em>ends</em> — the three
 * destroy methods, the lazy eviction in {@link #resolve}, the sweep, and the per-subject eviction —
 * reports the session's identity to the {@link SessionEndListener}, so that whatever was opened with
 * the session can be closed with it. A write that replaces a session's record or re-issues its cookie
 * handle reports nothing: the session lives on. The listener is called only after the monitor has been
 * left, on the thread whose operation ended the session.
 * <p>
 * <strong>Thread safety.</strong> Every operation is guarded by the instance monitor, so each one is
 * atomic with respect to every other. No operation is split across two calls.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class InMemorySessionStore implements SessionStore {

    private static final CuiLogger LOGGER = new CuiLogger(InMemorySessionStore.class);

    private final int maxSessions;
    private final Duration idleTimeout;
    private final int maxSessionsPerSubject;
    private final Map<String, HeldSession> byId = new HashMap<>();
    private final Map<String, String> idByHandle = new HashMap<>();
    private final Map<String, Set<String>> bySid = new HashMap<>();
    private final Map<String, Set<String>> bySub = new HashMap<>();
    /** The creation order of the next new session; read and advanced under the instance monitor. */
    private long nextCreationOrder;
    private final SessionEndListener sessionEndListener;
    /**
     * The sessions the operation in progress has ended and not yet reported; filled and drained under
     * the instance monitor, so it is empty whenever the monitor is free.
     */
    private final List<String> endedSessions = new ArrayList<>();

    /**
     * Creates a store bounded to {@code maxSessions} live sessions and {@code maxSessionsPerSubject}
     * live sessions of one subject, each session ending after {@code idleTimeout} without a recorded
     * access.
     *
     * @param maxSessions           the hard capacity bound; must be positive
     * @param idleTimeout           how long a session may go without a recorded access before it is
     *                              expired; must be positive
     * @param maxSessionsPerSubject how many live sessions one subject may hold before a new one ends
     *                              its oldest; must be positive
     * @param sessionEndListener    told of every session this store ends, after the store has left its
     *                              monitor; it must not call back into this store and must not throw
     * @throws IllegalArgumentException when {@code maxSessions}, {@code idleTimeout} or
     *                                  {@code maxSessionsPerSubject} is not positive
     */
    public InMemorySessionStore(int maxSessions, Duration idleTimeout, int maxSessionsPerSubject,
            SessionEndListener sessionEndListener) {
        this.sessionEndListener = Objects.requireNonNull(sessionEndListener, "sessionEndListener");
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive, but was " + maxSessions);
        }
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive, but was " + idleTimeout);
        }
        if (maxSessionsPerSubject <= 0) {
            throw new IllegalArgumentException(
                    "maxSessionsPerSubject must be positive, but was " + maxSessionsPerSubject);
        }
        this.maxSessions = maxSessions;
        this.idleTimeout = idleTimeout;
        this.maxSessionsPerSubject = maxSessionsPerSubject;
    }

    @Override
    public void create(SessionRecord session, String cookieHandle, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(cookieHandle, "cookieHandle");
        Objects.requireNonNull(now, "now");
        // A refused creation may already have ended sessions on its way to the bound; they are
        // announced whether or not it throws.
        announcingEnds(() -> {
            createLocked(session, cookieHandle, now);
            return Boolean.TRUE;
        });
    }

    /** The creating write itself. Callers hold the instance monitor. */
    private void createLocked(SessionRecord session, String cookieHandle, Instant now) {
        HeldSession held = byId.get(session.sessionId());
        if (held != null) {
            // An id the store already holds names a record already counted against both bounds, so
            // storing it consumes no new capacity, ends no other session and is not refused at the
            // ceiling. A rotated or widened session does not take this path — it is written through
            // an updating write, which never creates.
            store(session, cookieHandle, now, held.creationOrder);
            return;
        }
        // The subject's own bound first: a session of the same subject that is ended here frees the
        // slot the new one takes, so the store-wide test below cannot refuse a subject at its bound.
        endOldestSessionsOf(session.sub(), now);
        if (byId.size() >= maxSessions) {
            // Sweep once, then re-test: expired sessions still hold their slots until something
            // reclaims them, and reaching the bound is the trigger. A store still at the bound after
            // the sweep is genuinely full of live sessions, and refusing it is the fail-closed guard.
            sweepExpiredLocked(now);
            if (byId.size() >= maxSessions) {
                throw new IllegalStateException("session store is at its max-session bound of " + maxSessions);
            }
        }
        store(session, cookieHandle, now, nextCreationOrder++);
    }

    /**
     * Makes room for one more session of {@code sub}: drops the subject's expired sessions, then ends
     * its oldest live session — the one created earliest — until the subject holds fewer than
     * {@code maxSessionsPerSubject}. Callers hold the instance monitor.
     */
    private void endOldestSessionsOf(String sub, Instant now) {
        Set<String> sessionIds = bySub.get(sub);
        if (sessionIds == null || sessionIds.size() < maxSessionsPerSubject) {
            return;
        }
        List<HeldSession> live = new ArrayList<>();
        for (String sessionId : List.copyOf(sessionIds)) {
            HeldSession held = byId.get(sessionId);
            if (held == null) {
                continue;
            }
            if (isExpired(held, now)) {
                endInternal(sessionId);
            } else {
                live.add(held);
            }
        }
        live.sort(Comparator.comparingLong(held -> held.creationOrder));
        int surplus = live.size() - maxSessionsPerSubject + 1;
        for (int index = 0; index < surplus; index++) {
            endInternal(live.get(index).session.sessionId());
        }
        if (surplus > 0) {
            LOGGER.debug("Ended %s oldest session(s) of a subject at its per-subject bound of %s", surplus,
                    maxSessionsPerSubject);
        }
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
        store(session, held.cookieHandle, held.lastAccess, held.creationOrder);
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
        store(session, newCookieHandle, held.lastAccess, held.creationOrder);
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
     * Stores {@code session} under its id with {@code cookieHandle}, {@code lastAccess} and
     * {@code creationOrder} beside it, replacing any record — and dropping any handle — already held
     * there, and brings both secondary indexes in line with it. Callers hold the instance monitor.
     *
     * @throws IllegalStateException when {@code cookieHandle} resolves to a different session
     */
    private void store(SessionRecord session, String cookieHandle, Instant lastAccess, long creationOrder) {
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
        byId.put(sessionId, new HeldSession(session, cookieHandle, lastAccess, creationOrder));
        idByHandle.put(cookieHandle, sessionId);
        index(bySub, session.sub(), sessionId);
        String sid = session.sid();
        if (sid != null) {
            index(bySid, sid, sessionId);
        }
    }

    @Override
    public Optional<SessionRecord> resolve(String cookieHandle, Instant now) {
        Objects.requireNonNull(cookieHandle, "cookieHandle");
        Objects.requireNonNull(now, "now");
        return announcingEnds(() -> resolveLocked(cookieHandle, now));
    }

    /** The lookup itself, evicting an expired session. Callers hold the instance monitor. */
    private Optional<SessionRecord> resolveLocked(String cookieHandle, Instant now) {
        String sessionId = idByHandle.get(cookieHandle);
        if (sessionId == null) {
            return Optional.empty();
        }
        HeldSession held = byId.get(sessionId);
        if (held == null) {
            return Optional.empty();
        }
        if (isExpired(held, now)) {
            endInternal(sessionId);
            return Optional.empty();
        }
        return Optional.of(held.session);
    }

    @Override
    public void destroyById(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        announcingEnds(() -> {
            endInternal(sessionId);
            return Boolean.TRUE;
        });
    }

    @Override
    public int destroyBySid(String sid) {
        Objects.requireNonNull(sid, "sid");
        return announcingEnds(() -> removeAll(bySid.get(sid)));
    }

    @Override
    public int destroyBySub(String sub) {
        Objects.requireNonNull(sub, "sub");
        return announcingEnds(() -> removeAll(bySub.get(sub)));
    }

    @Override
    public int sweepExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        return announcingEnds(() -> sweepExpiredLocked(now));
    }

    /** The sweep itself. Callers hold the instance monitor. */
    private int sweepExpiredLocked(Instant now) {
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, HeldSession> entry : byId.entrySet()) {
            if (isExpired(entry.getValue(), now)) {
                expired.add(entry.getKey());
            }
        }
        expired.forEach(this::endInternal);
        return expired.size();
    }

    /**
     * @return the current number of stored sessions
     */
    public synchronized int size() {
        return byId.size();
    }

    /**
     * Whether the store holds a session under {@code sessionId} at this moment. It reads the primary
     * map only: an expired session that nothing has evicted yet is still held, and its eviction is
     * reported to the {@link SessionEndListener} when it happens.
     *
     * @param sessionId the stable session identity
     * @return {@code true} while the session is held
     */
    public synchronized boolean isHeld(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return byId.containsKey(sessionId);
    }

    /**
     * Runs {@code underMonitor} under the instance monitor and, after the monitor has been left,
     * reports every session that operation ended to the {@link SessionEndListener} — also when the
     * operation throws. The listener is never called with the monitor held, so nothing it does can
     * block a store operation or deadlock with one.
     */
    private <T> T announcingEnds(Supplier<T> underMonitor) {
        List<String> ended = new ArrayList<>();
        try {
            synchronized (this) {
                try {
                    return underMonitor.get();
                } finally {
                    ended.addAll(endedSessions);
                    endedSessions.clear();
                }
            }
        } finally {
            ended.forEach(sessionEndListener::sessionEnded);
        }
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
        snapshot.forEach(this::endInternal);
        return snapshot.size();
    }

    /**
     * Removes a session that has <em>ended</em> — destroyed, expired, or ended under a bound — and
     * notes it for the {@link SessionEndListener}. A session the store does not hold is ignored.
     * Callers hold the instance monitor; the listener is told by {@link #announcingEnds} afterwards.
     */
    private void endInternal(String sessionId) {
        if (byId.containsKey(sessionId)) {
            removeInternal(sessionId);
            endedSessions.add(sessionId);
        }
    }

    /**
     * Removes a session's record, handle and index entries <em>without</em> noting an end. This is
     * the seam {@link #store} uses for the record it replaces: the session lives on under the same
     * identity — after a refresh, or with a re-issued cookie handle — so nothing has ended.
     */
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
     * One stored session: the record, plus the pieces of store-side state that are deliberately not
     * components of it — the cookie handle, the last access, and the order in which the session was
     * created in this store, which every later write to the session keeps. Read and written only
     * under the store's monitor.
     */
    private static final class HeldSession {

        private final SessionRecord session;
        private final String cookieHandle;
        private final long creationOrder;
        private Instant lastAccess;

        HeldSession(SessionRecord session, String cookieHandle, Instant lastAccess, long creationOrder) {
            this.session = session;
            this.cookieHandle = cookieHandle;
            this.lastAccess = lastAccess;
            this.creationOrder = creationOrder;
        }
    }
}
