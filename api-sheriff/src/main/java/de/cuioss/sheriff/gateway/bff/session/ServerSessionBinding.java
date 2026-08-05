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
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The server-mode {@link SessionBinding} ({@code session.mode: server}) — a thin adapter over the
 * unchanged {@link SessionStore} and {@link SessionCookieCodec}.
 * <p>
 * The token material stays server-side in the store and the browser carries only the opaque
 * {@link SessionRecord#sessionId()} in the hardened {@code __Host-} session cookie, exactly as
 * before the seam was extracted: {@link #bind} is a store {@code create} plus the opaque
 * {@code Set-Cookie}, {@link #resolve} reads the cookie and looks the session up (the store's lazy
 * TTL eviction applies), {@link #persist} is the store's documented upsert-by-id {@code create}
 * (no pre-destroy, so a concurrent resolve never misses a rotating session), and {@link #destroy}
 * is {@code destroyById}. The store's O(1) secondary indexes back the IdP-driven destruction, so
 * this binding reports {@link IdpDestruction#SUPPORTED}.
 * <p>
 * The adapter is behaviour-preserving by construction — it adds no policy of its own and holds no
 * state beyond its two collaborators.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ServerSessionBinding implements SessionBinding {

    private final SessionStore sessionStore;
    private final SessionCookieCodec sessionCookieCodec;

    /**
     * Assembles the server-mode binding over the session store and its opaque-cookie codec.
     *
     * @param sessionStore       the server-side session store holding the token material
     * @param sessionCookieCodec the opaque session-cookie codec reading and writing the handle
     */
    public ServerSessionBinding(SessionStore sessionStore, SessionCookieCodec sessionCookieCodec) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.sessionCookieCodec = Objects.requireNonNull(sessionCookieCodec, "sessionCookieCodec");
    }

    @Override
    public BoundSession bind(SessionRecord session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        sessionStore.create(session, now);
        return new BoundSession(session, List.of(sessionCookieCodec.toSetCookieHeader(session.sessionId())));
    }

    @Override
    public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");
        return sessionCookieCodec.readSessionId(cookieHeader)
                .flatMap(sessionId -> sessionStore.resolve(sessionId, now));
    }

    @Override
    public BoundSession persist(SessionRecord rotated, Instant now) {
        Objects.requireNonNull(rotated, "rotated");
        Objects.requireNonNull(now, "now");
        // create() upserts by session id (SessionStore contract; InMemorySessionStore is a keyed map
        // put), so no pre-destroy is needed. Destroying first would open a window where a concurrent
        // resolve() misses the rotating session. An upsert consumes no new capacity, so this call is
        // admitted even at the max-session bound. The opaque handle is unchanged, so the browser
        // needs no new Set-Cookie.
        sessionStore.create(rotated, now);
        return new BoundSession(rotated, List.of());
    }

    @Override
    public void destroy(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        sessionStore.destroyById(session.sessionId());
    }

    @Override
    public int destroyBySid(String sid) {
        Objects.requireNonNull(sid, "sid");
        return sessionStore.destroyBySid(sid);
    }

    @Override
    public int destroyBySub(String sub) {
        Objects.requireNonNull(sub, "sub");
        return sessionStore.destroyBySub(sub);
    }

    @Override
    public IdpDestruction idpDestruction() {
        return IdpDestruction.SUPPORTED;
    }

    @Override
    public String clearingSetCookieHeader() {
        return sessionCookieCodec.toClearingSetCookieHeader();
    }
}
