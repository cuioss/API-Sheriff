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

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The server-mode {@link SessionBinding} ({@code session.mode: server}) — a thin adapter over the
 * {@link SessionStore} and the {@link SessionCookieCodec}.
 * <p>
 * The token material stays server-side in the store and the browser carries only an opaque
 * <em>cookie handle</em> in the hardened {@code __Host-} session cookie. The handle is minted here,
 * separately from {@link SessionRecord#sessionId()}: the session id is the stable internal identity
 * and never reaches the browser, and the handle is the one thing that can be re-issued without the
 * session becoming another session.
 * <ul>
 *   <li>{@link #bind} mints a handle, stores the session under its id with that handle beside it, and
 *       returns the {@code Set-Cookie} carrying the handle;</li>
 *   <li>{@link #resolve} reads the handle from the cookie and looks the session up by it — the
 *       store's lazy eviction applies, for the absolute lifetime and for the idle timeout;</li>
 *   <li>{@link #persist} is the store's conditional {@code replaceIfPresent} (no pre-destroy, so a
 *       concurrent resolve never misses a session being updated); the handle stays, so it returns no
 *       cookie;</li>
 *   <li>{@link #persistReissuingCookie} mints a new handle and makes the store replace the record and
 *       swap the handle in one atomic step; it returns the cookie for the new handle, whose
 *       {@code Max-Age} is the session's remaining absolute lifetime, and the previous cookie value
 *       resolves nothing from then on;</li>
 *   <li>{@link #recordAccess} moves the session's last access in the store and returns no cookie;</li>
 *   <li>{@link #destroy} is {@code destroyById}.</li>
 * </ul>
 * The store's O(1) secondary indexes back the IdP-driven destruction, so this binding reports
 * {@link IdpDestruction#SUPPORTED}. Those indexes are keyed on the session id, so a back-channel
 * logout ends a session whatever handle currently resolves to it.
 * <p>
 * <strong>A destroyed session stays destroyed.</strong> Neither updating write ever creates: when the
 * store no longer holds the session — {@link #destroy}, {@link #destroyBySid} or
 * {@link #destroyBySub} removed it after the caller resolved it — the update writes nothing and
 * reports the session gone. The store performs the check and the write as one atomic step, so a
 * logout cannot fall between them. {@link #bind} is the only creating write.
 * <p>
 * The adapter adds no policy of its own and holds no state beyond its two collaborators; it is
 * thread-safe because both of them are.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ServerSessionBinding implements SessionBinding {

    /** The parameter name the null checks of a session argument report. */
    private static final String SESSION_PARAMETER = "session";

    /** The cookie-handle width: 256 bits, the same entropy as {@link SessionRecord#newSessionId()}. */
    private static final int COOKIE_HANDLE_BYTES = 32;

    /**
     * Per instance, never {@code static}: the binding is assembled at runtime, so the generator is
     * seeded at runtime too, and no GraalVM runtime-initialization registration is needed for it.
     */
    private final SecureRandom secureRandom = new SecureRandom();
    private final SessionStore sessionStore;
    private final SessionCookieCodec sessionCookieCodec;

    /**
     * Assembles the server-mode binding over the session store and its opaque-cookie codec.
     *
     * @param sessionStore       the server-side session store holding the token material; it enforces
     *                           the absolute lifetime and the idle timeout
     * @param sessionCookieCodec the opaque session-cookie codec reading and writing the handle
     */
    public ServerSessionBinding(SessionStore sessionStore, SessionCookieCodec sessionCookieCodec) {
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
        this.sessionCookieCodec = Objects.requireNonNull(sessionCookieCodec, "sessionCookieCodec");
    }

    @Override
    public BoundSession bind(SessionRecord session, Instant now) {
        Objects.requireNonNull(session, SESSION_PARAMETER);
        Objects.requireNonNull(now, "now");
        String cookieHandle = newCookieHandle();
        sessionStore.create(session, cookieHandle, now);
        return new BoundSession(session, List.of(sessionCookieCodec.toSetCookieHeader(cookieHandle)));
    }

    @Override
    public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");
        return sessionCookieCodec.readCookieHandle(cookieHeader)
                .flatMap(cookieHandle -> sessionStore.resolve(cookieHandle, now));
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
        // The cookie handle is unchanged, so the browser needs no new Set-Cookie.
        return Optional.of(new BoundSession(updated, List.of()));
    }

    @Override
    public Optional<BoundSession> persistReissuingCookie(SessionRecord updated, Instant now) {
        Objects.requireNonNull(updated, "updated");
        Objects.requireNonNull(now, "now");
        String cookieHandle = newCookieHandle();
        // One store call: the presence check, the replacement and the handle swap are atomic with every
        // destroy, so a session destroyed since the caller resolved it is neither written back nor
        // given a handle. Splitting this into a replace and a separate swap would open exactly the
        // check-then-act window the store's single monitor exists to close.
        if (!sessionStore.replaceAndReissueHandle(updated, cookieHandle)) {
            return Optional.empty();
        }
        // The session is under way, so the cookie lives only as long as the session still does: its
        // Max-Age is the remaining absolute lifetime, not the full one the login cookie carries.
        Duration remainingLifetime = Duration.between(now, updated.expiresAt());
        return Optional.of(new BoundSession(updated,
                List.of(sessionCookieCodec.toSetCookieHeader(cookieHandle, remainingLifetime))));
    }

    @Override
    public List<String> recordAccess(SessionRecord session, @Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(session, SESSION_PARAMETER);
        Objects.requireNonNull(now, "now");
        // Keyed on the stable session id, so it reaches the session whatever handle the request carried.
        sessionStore.recordAccess(session.sessionId(), now);
        // The last access lives in the store, so the browser needs no cookie for it.
        return List.of();
    }

    @Override
    public void destroy(SessionRecord session) {
        Objects.requireNonNull(session, SESSION_PARAMETER);
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
    public List<String> clearingSetCookieHeaders() {
        return List.of(sessionCookieCodec.toClearingSetCookieHeader());
    }

    /**
     * Mints one cookie handle: {@link #COOKIE_HANDLE_BYTES} secure-random bytes, base64url-encoded
     * without padding so the value is a valid cookie value as it stands.
     */
    private String newCookieHandle() {
        byte[] bytes = new byte[COOKIE_HANDLE_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
