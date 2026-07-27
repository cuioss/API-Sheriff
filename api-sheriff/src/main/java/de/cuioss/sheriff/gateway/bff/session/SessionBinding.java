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
 * The mode-neutral session-binding seam (D7) — the single session-state contract the whole BFF
 * foundation binds.
 * <p>
 * The contract deliberately mentions <strong>no store and no opaque id</strong>: it describes only
 * how a {@link SessionRecord} is bound to the browser, read back from a request, re-bound after a
 * rotation, and destroyed. That makes a stateless variant representable — a server-mode
 * implementation keeps the record in a {@link SessionStore} and hands the browser an opaque handle,
 * while a stateless implementation seals the record into the cookie itself. Login, CSRF, step-up,
 * scope enforcement, and logout orchestration stay single-sourced above this seam.
 * <p>
 * <strong>Session identity.</strong> Every implementation populates {@link SessionRecord#sessionId()}
 * with a stable per-session identity, so callers that need to key per-session work — notably the
 * single-flight coalescing in the refresh coordinator — use that component directly. The seam
 * therefore carries <em>no</em> identity accessor.
 * <p>
 * <strong>IdP-driven destruction.</strong> {@link #destroyBySid(String)} and
 * {@link #destroyBySub(String)} serve OIDC back-channel logout. A stateless implementation holds no
 * server-side index and cannot honour them; it declares that by reporting
 * {@link IdpDestruction#UNSUPPORTED} from {@link #idpDestruction()} so callers fail closed rather
 * than silently reporting a destruction that never happened.
 * <p>
 * Implementations are framework-agnostic (raw {@code Cookie} header values in, {@code Set-Cookie}
 * header values out — no JAX-RS/Vert.x coupling) and must be safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public interface SessionBinding {

    /**
     * Binds a freshly created session to the browser (post-login).
     *
     * @param session the session to bind
     * @param now     the reference instant
     * @return the bound session and the {@code Set-Cookie} header value(s) the caller emits
     * @throws IllegalStateException when the binding cannot bind the session at all — a stateful
     *         implementation at its capacity bound, or a stateless one whose sealed representation
     *         exceeds the browser-safe cookie size budget
     */
    BoundSession bind(SessionRecord session, Instant now);

    /**
     * Resolves the live session a request carries, enforcing the absolute TTL: an expired or
     * unreadable binding is reported as absent.
     *
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now          the reference instant for the TTL check
     * @return the live session; empty when the request carries none, or it is unreadable or expired
     */
    Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now);

    /**
     * Re-binds a session whose token material was rotated, without extending its absolute lifetime.
     *
     * @param rotated the session carrying the rotated token material
     * @param now     the reference instant
     * @return the re-bound session and the {@code Set-Cookie} header value(s) the caller emits
     *         (empty for an implementation whose re-bind is invisible to the browser)
     */
    BoundSession persist(SessionRecord rotated, Instant now);

    /**
     * Destroys the given session (RP-initiated logout or a failed refresh). A no-op when the
     * session is already gone.
     *
     * @param session the session to destroy
     */
    void destroy(SessionRecord session);

    /**
     * Destroys every session carrying the given IdP {@code sid} (back-channel logout).
     *
     * @param sid the IdP session id claim
     * @return the number of sessions destroyed; always {@code 0} when {@link #idpDestruction()}
     *         reports {@link IdpDestruction#UNSUPPORTED}
     */
    int destroyBySid(String sid);

    /**
     * Destroys every session for the given subject (back-channel logout without a {@code sid}).
     *
     * @param sub the subject claim
     * @return the number of sessions destroyed; always {@code 0} when {@link #idpDestruction()}
     *         reports {@link IdpDestruction#UNSUPPORTED}
     */
    int destroyBySub(String sub);

    /**
     * @return whether this binding can honour the IdP-driven {@code sid}/{@code sub} destruction
     */
    IdpDestruction idpDestruction();

    /**
     * Builds the {@code Set-Cookie} header value that clears the browser's session binding. Needed
     * on logout even when no live session resolved, so a stale binding cookie is cleared too.
     *
     * @return the clearing {@code Set-Cookie} header value
     */
    String clearingSetCookieHeader();

    /**
     * Whether a binding can honour the IdP-driven {@code sid}/{@code sub} destruction of OIDC
     * back-channel logout.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    enum IdpDestruction {

        /** The binding holds a server-side index and destroys the named sessions. */
        SUPPORTED,

        /** The binding is stateless — it holds no index and cannot reach another browser's cookie. */
        UNSUPPORTED
    }

    /**
     * The result of binding or re-binding a session: the session as bound, plus the
     * {@code Set-Cookie} header values the caller emits so the browser carries the new binding.
     * Token material never appears in the headers — a server-mode binding emits an opaque handle
     * and a stateless binding emits an authenticated-encrypted value.
     *
     * @param session          the session as bound
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit, possibly empty
     * @author API Sheriff Team
     * @since 1.0
     */
    record BoundSession(SessionRecord session, List<String> setCookieHeaders) {

        /**
         * Canonical constructor rejecting an absent session and defensively copying the cookies.
         */
        public BoundSession {
            Objects.requireNonNull(session, "session");
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }
    }
}
