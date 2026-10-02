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
 * Sessions are keyed by their opaque id in a primary map. Two secondary indexes — by IdP
 * {@code sid} and by {@code sub} — give O(1) back-channel logout destruction without scanning
 * the primary map. Every removal path (direct destroy, back-channel destroy, lazy TTL eviction,
 * at-capacity sweep, and the eviction of the record a write replaces) keeps the indexes consistent
 * through a single {@link #removeInternal} seam.
 * <p>
 * <strong>Two writes.</strong> {@link #create} stores a new session and is the login's write.
 * {@link #replaceIfPresent} updates a session that already exists — the write a refresh or a
 * widening makes — and stores nothing when the id is not held, so a write that was in flight while
 * the session was destroyed does not bring it back. The presence check and the replacement run
 * under the same monitor as {@link #destroyById}, {@link #destroyBySid} and {@link #destroyBySub},
 * so no destruction can take effect between them.
 * <p>
 * The absolute TTL is enforced two ways, with <strong>no per-session timer threads, no scheduler,
 * and no periodic task</strong>: lazily on {@link #resolve} (an expired session is evicted as it is
 * looked up) and opportunistically by {@link #sweepExpired}, whose one automatic trigger is a
 * <em>capacity-consuming</em> {@link #create} — one introducing a session id the store does not yet
 * hold — finding the store at its {@code maxSessions} bound. A write that replaces an already-stored
 * id — {@link #replaceIfPresent}, or a {@link #create} naming an id the store holds — replaces a
 * record already counted against the bound, so it consumes no capacity and never sweeps.
 * <p>
 * That trigger is what makes the bound a ceiling on <em>live</em> sessions rather than on
 * accumulated ones. An expired session that nothing has resolved since it lapsed still occupies its
 * slot, so without a reclaiming trigger a store could sit permanently full of dead sessions and
 * refuse every login. A capacity-consuming creation therefore sweeps once at the bound and re-tests
 * it; only a store still full of live sessions afterwards is refused, fail-closed.
 * <p>
 * <strong>Thread safety.</strong> Every operation is guarded by the instance monitor, so each one is
 * atomic with respect to every other.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class InMemorySessionStore implements SessionStore {

    private final int maxSessions;
    private final Map<String, SessionRecord> byId = new HashMap<>();
    private final Map<String, Set<String>> bySid = new HashMap<>();
    private final Map<String, Set<String>> bySub = new HashMap<>();

    /**
     * Creates a store bounded to {@code maxSessions} live sessions.
     *
     * @param maxSessions the hard capacity bound; must be positive
     * @throws IllegalArgumentException when {@code maxSessions} is not positive
     */
    public InMemorySessionStore(int maxSessions) {
        if (maxSessions <= 0) {
            throw new IllegalArgumentException("maxSessions must be positive, but was " + maxSessions);
        }
        this.maxSessions = maxSessions;
    }

    @Override
    public synchronized void create(SessionRecord session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        // An id the store already holds names a record already counted against the bound, so storing
        // it consumes no new capacity and is not refused at the ceiling. A rotated or widened session
        // does not take this path — it is written through replaceIfPresent, which never creates.
        if (!byId.containsKey(session.sessionId()) && byId.size() >= maxSessions) {
            // Sweep once, then re-test: expired sessions still hold their slots until something
            // reclaims them, and reaching the bound is the trigger. A store still at the bound after
            // the sweep is genuinely full of live sessions, and refusing it is the fail-closed guard.
            sweepExpired(now);
            if (byId.size() >= maxSessions) {
                throw new IllegalStateException("session store is at its max-session bound of " + maxSessions);
            }
        }
        store(session);
    }

    @Override
    public synchronized boolean replaceIfPresent(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        // The presence check and the replacement share this monitor with every destroy method, so a
        // logout or a back-channel logout either ran before the check — and nothing is written — or
        // runs after the replacement and removes it. There is no window in which a destroyed session
        // is written back.
        if (!byId.containsKey(session.sessionId())) {
            return false;
        }
        store(session);
        return true;
    }

    /**
     * Stores {@code session} under its id, replacing any record already held there, and brings both
     * secondary indexes in line with it. Callers hold the instance monitor.
     */
    private void store(SessionRecord session) {
        // The record may carry a different sub or sid than the one it replaces, and a deindex is
        // only ever keyed by the record's OWN sub/sid — so the previous record leaves through the
        // same removeInternal seam every other removal path uses. Left indexed, its stale sub/sid
        // would keep resolving to this id, and a later destroyBySub/destroyBySid on that stale key
        // would destroy the replacement and report a phantom deletion.
        removeInternal(session.sessionId());
        byId.put(session.sessionId(), session);
        index(bySub, session.sub(), session.sessionId());
        String sid = session.sid();
        if (sid != null) {
            index(bySid, sid, session.sessionId());
        }
    }

    @Override
    public synchronized Optional<SessionRecord> resolve(String sessionId, Instant now) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(now, "now");
        SessionRecord session = byId.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.isExpired(now)) {
            removeInternal(sessionId);
            return Optional.empty();
        }
        return Optional.of(session);
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
        for (Map.Entry<String, SessionRecord> entry : byId.entrySet()) {
            if (entry.getValue().isExpired(now)) {
                expired.add(entry.getKey());
            }
        }
        expired.forEach(this::removeInternal);
        return expired.size();
    }

    /**
     * @return the current number of live sessions
     */
    public synchronized int size() {
        return byId.size();
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
        SessionRecord session = byId.remove(sessionId);
        if (session == null) {
            return;
        }
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
}
