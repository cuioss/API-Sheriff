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
 * observe it with — and is stated on {@link #persist}. {@link #persistReissuingCookie} additionally
 * replaces the cookie value the browser holds, and is the write a step-up or a scope widening makes;
 * {@link #persist} is the refresh's write, and additionally names the record it was computed from, so
 * an implementation that can observe it reports a widening that landed in between as
 * {@link PersistResult.Superseded} instead of overwriting it. {@link #destroyIfCurrent} is the refused
 * refresh's destruction and names its basis the same way, so a widening that landed in between is not
 * destroyed either.
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
     * Updates a session that already exists with the new token material a refresh rotated in, without
     * extending its absolute lifetime, and <strong>without ever creating a session</strong> — the
     * refresh's write.
     * <p>
     * The caller resolved {@code basis} earlier and computed {@code updated} from it. Two things may
     * have happened to the session since, and the result says which:
     * <ul>
     *   <li><strong>The session may be gone</strong> — a logout or a back-channel logout destroyed it.
     *       The result is {@link PersistResult.Gone}: nothing was written, and the caller must treat the
     *       session as ended rather than hand out the new token material.</li>
     *   <li><strong>The session may have moved on</strong> — a widening or a step-up replaced its record
     *       through {@link #persistReissuingCookie}. The result is {@link PersistResult.Superseded}:
     *       nothing was written, so the later grant's scopes, refresh token, {@code acr} and
     *       {@code auth_time} stay as that write left them. The session lives on, and the caller must
     *       not end it.</li>
     * </ul>
     * Otherwise the result is {@link PersistResult.Written}.
     * <p>
     * <strong>Which of these an implementation can observe is a property of the mode.</strong> An
     * implementation that holds server-side state compares the stored record against {@code basis} and
     * writes in one atomic step. A stateless implementation holds nothing to compare against: it cannot
     * observe a destruction or a concurrent widening, ignores {@code basis}, always writes and always
     * reports {@link PersistResult.Written}. In that mode a refresh that re-seals after a concurrent
     * widening re-sealed hands the browser two cookies, and whichever the browser keeps last wins — the
     * stated residual of the stateless variant.
     * <p>
     * Thread-safe, like every operation of this seam.
     *
     * @param basis   the session as the caller resolved it and computed {@code updated} from
     * @param updated the session carrying the new token material; it keeps the identity and the
     *                absolute expiry of the session it updates
     * @param now     the reference instant
     * @return {@link PersistResult.Written} carrying the updated session and the {@code Set-Cookie}
     *         header value(s) the caller emits (an empty list for an implementation whose update is
     *         invisible to the browser); {@link PersistResult.Gone} when the implementation observed that
     *         the session no longer exists; {@link PersistResult.Superseded} when it observed that the
     *         session's record is no longer {@code basis}
     * @throws IllegalStateException when the binding cannot hold the updated session — for example a
     *         stateless implementation whose sealed representation exceeds the cookie size budget
     */
    PersistResult persist(SessionRecord basis, SessionRecord updated, Instant now);

    /**
     * Updates a session that already exists with the token material of a widening merge and
     * additionally <strong>re-issues the cookie value the browser holds</strong> — the write a step-up
     * or a scope widening makes.
     * <p>
     * Like {@link #persist} it never creates a session, never extends the absolute lifetime and reports
     * the session gone instead of writing when the implementation can observe that it was destroyed.
     * Unlike {@link #persist} it checks presence only and is never superseded: when a refresh and a
     * widening of one session overlap, the refresh is the side that gives way.
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
     * Destroys the given session (RP-initiated logout or a failed refresh) and hands back the record
     * that was destroyed. A no-op when the session is already gone.
     * <p>
     * <strong>The result is the session as it stood when it was destroyed</strong>, which need not be
     * {@code session}: the caller resolved the session earlier, and an updating write may have replaced
     * its token material since. An implementation that holds server-side state returns the record it
     * removed, read in the same atomic step as the removal, so a refresh token rotated in after the
     * caller's resolve is the one the result carries. A stateless implementation holds nothing to
     * remove and returns {@code session} itself — the record the request presented.
     * <p>
     * Thread-safe, like every operation of this seam.
     *
     * @param session the session to destroy, as the caller resolved it; its identity names the session
     * @return the destroyed session as it stood at its destruction; empty when the implementation
     *         observed that the session was already gone
     * @since 1.0
     */
    Optional<SessionRecord> destroy(SessionRecord session);

    /**
     * Destroys the session <strong>only while it is still the record the caller read</strong> — the
     * destruction a refused refresh makes.
     * <p>
     * The caller resolved {@code basis} earlier and decided from it that the session ends. Two things
     * may have happened to the session since, and the result says which:
     * <ul>
     *   <li><strong>The session may be gone</strong> — a logout or a back-channel logout destroyed it.
     *       The result is {@link DestroyResult#GONE}, and nothing was destroyed.</li>
     *   <li><strong>The session may have moved on</strong> — a widening or a step-up replaced its record
     *       through {@link #persistReissuingCookie}. The result is {@link DestroyResult#SUPERSEDED}:
     *       nothing was destroyed, the later write's record stays, and the caller must not treat the
     *       session as ended.</li>
     * </ul>
     * Otherwise the session is destroyed as {@link #destroy} destroys it and the result is
     * {@link DestroyResult#DESTROYED}.
     * <p>
     * <strong>Which of these an implementation can observe is a property of the mode</strong>, as on
     * {@link #persist}. An implementation that holds server-side state compares the stored record
     * against {@code basis} and removes it in one atomic step. A stateless implementation holds nothing
     * to compare against: it ignores {@code basis}, destroys as {@link #destroy} does and reports
     * {@link DestroyResult#DESTROYED}.
     * <p>
     * Thread-safe, like every operation of this seam.
     *
     * @param basis the session as the caller resolved it and decided the destruction on; its identity
     *              names the session
     * @return {@link DestroyResult#DESTROYED} when the session was destroyed; {@link DestroyResult#GONE}
     *         when the implementation observed that it no longer exists; {@link DestroyResult#SUPERSEDED}
     *         when it observed that the session's record is no longer {@code basis}
     * @since 1.0
     */
    DestroyResult destroyIfCurrent(SessionRecord basis);

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
     * The answer of {@link #destroyIfCurrent}: the session was destroyed, it was already gone, or its
     * record had been superseded by another updating write. Only {@link #DESTROYED} destroyed anything.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    enum DestroyResult {

        /** The session was the caller's basis and has been destroyed. */
        DESTROYED,

        /** The session no longer exists — a logout or a back-channel logout ended it; nothing was destroyed. */
        GONE,

        /**
         * The session lives on, but its record is no longer the one the caller decided the destruction on —
         * a widening or a step-up replaced it meanwhile; nothing was destroyed.
         */
        SUPERSEDED
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

    /**
     * The answer of {@link #persist}: the rotated session was written, the session was gone, or its
     * record had been superseded by another updating write. Only {@link Written} wrote anything.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    sealed interface PersistResult permits PersistResult.Written, PersistResult.Gone, PersistResult.Superseded {

        /**
         * @param bound the updated session and the {@code Set-Cookie} header value(s) the caller emits
         * @return the result of a write that landed
         */
        static PersistResult written(BoundSession bound) {
            return new Written(bound);
        }

        /**
         * @return the result of a write that found the session destroyed and wrote nothing
         */
        static PersistResult gone() {
            return Gone.INSTANCE;
        }

        /**
         * @return the result of a write that found the session's record replaced since the caller read
         *         it and wrote nothing
         */
        static PersistResult superseded() {
            return Superseded.INSTANCE;
        }

        /**
         * The rotated session was written.
         *
         * @param bound the updated session and the {@code Set-Cookie} header value(s) the caller emits
         * @author API Sheriff Team
         * @since 1.0
         */
        record Written(BoundSession bound) implements PersistResult {

            /**
             * Canonical constructor rejecting an absent bound session.
             */
            public Written {
                Objects.requireNonNull(bound, "bound");
            }
        }

        /**
         * The session no longer exists — a logout or a back-channel logout destroyed it after the caller
         * resolved it. Nothing was written.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        enum Gone implements PersistResult {
            /** The one instance. */
            INSTANCE
        }

        /**
         * The session lives on, but its record is no longer the one the caller computed its update from —
         * a widening or a step-up replaced it meanwhile. Nothing was written.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        enum Superseded implements PersistResult {
            /** The one instance. */
            INSTANCE
        }
    }
}
