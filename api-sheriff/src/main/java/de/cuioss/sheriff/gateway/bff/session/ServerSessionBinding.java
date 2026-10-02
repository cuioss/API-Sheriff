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
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import org.jspecify.annotations.Nullable;

/**
 * The server-mode {@link SessionBinding} ({@code session.mode: server}) — a thin adapter over the
 * unchanged {@link SessionStore} and {@link SessionCookieCodec}.
 * <p>
 * The token material stays server-side in the store and the browser carries only the opaque
 * {@link SessionRecord#sessionId()} in the hardened {@code __Host-} session cookie:
 * {@link #bind} is a store {@code create} plus the opaque
 * {@code Set-Cookie}, {@link #resolve} reads the cookie and looks the session up (the store's lazy
 * TTL eviction applies), {@link #persist} is the store's conditional {@code replaceIfPresent} (no
 * pre-destroy, so a concurrent resolve never misses a session being updated), and {@link #destroy}
 * is {@code destroyById}. The store's O(1) secondary indexes back the IdP-driven destruction, so
 * this binding reports {@link IdpDestruction#SUPPORTED}.
 * <p>
 * <strong>A destroyed session stays destroyed.</strong> {@link #persist} never creates: when the
 * store no longer holds the session — {@link #destroy}, {@link #destroyBySid} or
 * {@link #destroyBySub} removed it after the caller resolved it — the update writes nothing and
 * reports the session gone. The store performs the check and the replacement as one atomic step, so
 * a logout cannot fall between them. {@link #bind} is the only creating write.
 * <p>
 * The adapter adds no policy of its own and holds no state beyond its two collaborators; it is
 * thread-safe because both of them are.
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
    public Optional<BoundSession> persist(SessionRecord updated, Instant now) {
        Objects.requireNonNull(updated, "updated");
        Objects.requireNonNull(now, "now");
        // The store replaces the record only if it still holds the session, as one atomic step, so a
        // session a logout or a back-channel logout destroyed since the caller resolved it is not
        // written back. No pre-destroy is needed either way: destroying first would open a window
        // where a concurrent resolve() misses the session being updated. A replacement consumes no
        // capacity, so it is admitted even at the max-session bound.
        if (!sessionStore.replaceIfPresent(updated)) {
            return Optional.empty();
        }
        // The opaque handle is unchanged, so the browser needs no new Set-Cookie.
        return Optional.of(new BoundSession(updated, List.of()));
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
