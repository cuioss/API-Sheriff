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
 * The mode-neutral session-binding seam (D7) — the single session-state contract the whole BFF
 * foundation binds.
 * <p>
 * The contract deliberately mentions <strong>no store and no opaque id</strong>: it describes only
 * how a {@link SessionRecord} is bound to the browser, read back from a request, updated after its
 * token material changed, and destroyed. That makes a stateless variant representable — a
 * server-mode implementation keeps the record in a {@link SessionStore} and hands the browser an
 * opaque handle, while a stateless implementation seals the record into the cookie itself. Login,
 * CSRF, step-up, scope enforcement, and logout orchestration stay single-sourced above this seam.
 * <p>
 * <strong>One creating write, two updating writes.</strong> {@link #bind} creates a session and is the
 * login's write. {@link #persist} and {@link #persistReissuingCookie} update a session that already
 * exists and <em>never create one</em>: an implementation that can observe that the session was
 * destroyed since the caller resolved it reports that instead of writing, so a refresh or a widening
 * that was in flight during a logout cannot bring the session back. Whether an implementation can
 * observe it is a property of the mode — a server-mode binding can, a stateless one holds nothing to
 * observe it with — and is stated on {@link #persist}. The two updating writes differ in one point
 * only: {@link #persistReissuingCookie} additionally replaces the cookie value the browser holds, and
 * is the write a step-up or a scope widening makes; {@link #persist} is the refresh's write.
 * <p>
 * <strong>Session identity.</strong> Every implementation populates {@link SessionRecord#sessionId()}
 * with a stable per-session identity, so callers that need to key per-session work — notably the
 * single-flight coalescing in the refresh coordinator — use that component directly. The seam
 * therefore carries <em>no</em> identity accessor. <strong>The session identity is never the cookie
 * value, in either mode.</strong> A server-mode binding hands the browser an opaque handle that
 * resolves to the session and can be re-issued while the identity stays; a stateless binding derives
 * the identity from the sealed payload and never emits it.
 * <p>
 * <strong>Two deadlines.</strong> A session ends at its absolute lifetime
 * ({@link SessionRecord#expiresAt()}) and, earlier, when it has not been accessed for the idle
 * timeout. {@link #resolve} enforces both and extends neither. Only {@link #recordAccess} moves the
 * idle deadline, and the one caller that may invoke it is the session stage, for a request it lets
 * through to a session-protected route.
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
     * Resolves the live session a request carries, enforcing the absolute TTL and the idle timeout: a
     * binding that is unreadable, past its absolute lifetime or idle for longer than the idle timeout
     * is reported as absent.
     * <p>
     * Resolving <strong>never extends</strong> either deadline. A caller that only reads the session —
     * a reserved endpoint, the portal identity — therefore does not keep it alive; see
     * {@link #recordAccess}.
     *
     * @param cookieHeader the raw request {@code Cookie} header value, may be absent
     * @param now          the reference instant for both deadline checks
     * @return the live session; empty when the request carries none, or it is unreadable, expired or
     *         idle past the idle timeout
     */
    Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now);

    /**
     * Updates a session that already exists with new token material — after a refresh or a widening
     * merge — without extending its absolute lifetime, and <strong>without ever creating a
     * session</strong>.
     * <p>
     * <strong>The session may be gone.</strong> The caller resolved the session earlier; a logout or a
     * back-channel logout may have destroyed it since. An implementation that holds server-side state
     * checks for the session and writes in one atomic step, and returns an empty result when the
     * session is no longer there: nothing was written, and the caller must treat the session as ended
     * rather than hand out the new token material. A stateless implementation holds nothing a
     * destruction could have removed, cannot observe one, and therefore always writes and never
     * returns empty.
     * <p>
     * Thread-safe, like every operation of this seam.
     *
     * @param updated the session carrying the new token material; it keeps the identity and the
     *                absolute expiry of the session it updates
     * @param now     the reference instant
     * @return the updated session and the {@code Set-Cookie} header value(s) the caller emits (an
     *         empty list for an implementation whose update is invisible to the browser); empty when
     *         the implementation observed that the session no longer exists
     * @throws IllegalStateException when the binding cannot hold the updated session — for example a
     *         stateless implementation whose sealed representation exceeds the cookie size budget
     */
    Optional<BoundSession> persist(SessionRecord updated, Instant now);

    /**
     * Updates a session that already exists exactly as {@link #persist} does and additionally
     * <strong>re-issues the cookie value the browser holds</strong> — the write a step-up or a scope
     * widening makes.
     * <p>
     * Like {@link #persist} it never creates a session, never extends the absolute lifetime and reports
     * the session gone instead of writing when the implementation can observe that it was destroyed.
     * The session keeps its identity ({@link SessionRecord#sessionId()}): only the browser-facing value
     * changes.
     * <p>
     * How the value is re-issued is a property of the mode and is stated on each implementation.
     * <p>
     * Thread-safe, like every operation of this seam.
     *
     * @param updated the session carrying the new token material; it keeps the identity and the
     *                absolute expiry of the session it updates
     * @param now     the reference instant
     * @return the updated session and the {@code Set-Cookie} header value(s) carrying the re-issued
     *         cookie; empty when the implementation observed that the session no longer exists
     * @throws IllegalStateException when the binding cannot hold the updated session — for example a
     *         stateless implementation whose sealed representation exceeds the cookie size budget
     */
    Optional<BoundSession> persistReissuingCookie(SessionRecord updated, Instant now);

    /**
     * Records that a request carrying {@code session} was let through to a session-protected route —
     * the one event that moves the session's idle deadline.
     * <p>
     * The caller is the session stage, once per request it lets through. A request that is refused,
     * redirected into a login or a widening, or left without a token by a refresh is not an access and
     * must not be reported here, and no reserved endpoint reports one.
     * <p>
     * The absolute lifetime is not affected.
     *
     * @param session      the live session the request was let through with
     * @param cookieHeader the raw request {@code Cookie} header value the session was resolved from,
     *                     may be absent
     * @param now          the reference instant, recorded as the access
     * @return the {@code Set-Cookie} header value(s) the binding needs the caller to emit so the access
     *         is remembered, possibly none; never a cookie carrying token material
     */
    List<String> recordAccess(SessionRecord session, @Nullable String cookieHeader, Instant now);

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
     * Builds every {@code Set-Cookie} header value that clears a cookie this binding sets. Needed on
     * logout even when no live session resolved, so a stale cookie is cleared too. A binding that sets
     * more than one cookie returns one clearing value per cookie, and the caller emits all of them.
     *
     * @return the clearing {@code Set-Cookie} header values, never empty
     */
    List<String> clearingSetCookieHeaders();

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
     * The result of binding or updating a session: the session as bound, plus the
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
