/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
 * periodic sweep) keeps the indexes consistent through a single {@link #removeInternal} seam.
 * <p>
 * The absolute TTL is enforced two ways with <strong>no per-session timer threads</strong>:
 * lazily on {@link #resolve} (an expired session is evicted as it is looked up) and by an
 * operator/scheduler-driven {@link #sweepExpired}. A documented {@code maxSessions} bound caps
 * the live-session count (a capacity ceiling for the operator's memory math); creating a session
 * beyond the bound is refused fail-closed. Every operation is guarded by the instance monitor.
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
    public synchronized void create(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        if (byId.size() >= maxSessions) {
            throw new IllegalStateException("session store is at its max-session bound of " + maxSessions);
        }
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
