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
import java.util.Optional;

/**
 * The <strong>server-mode implementation detail</strong> behind {@link SessionBinding} (D3) — the
 * keyed store {@link ServerSessionBinding} delegates to.
 * <p>
 * No BFF collaborator binds this contract directly any more: the stage, the refresh coordinator,
 * and every reserved endpoint bind the mode-neutral {@link SessionBinding} seam, so a stateless
 * variant that keeps no server-side session state is representable. This interface therefore
 * describes only the server-mode storage semantics.
 * <p>
 * <strong>Two names for one session.</strong> A session is stored under its stable
 * {@link SessionRecord#sessionId() session id}, which never changes and never leaves the gateway. The
 * browser holds a separate opaque <em>cookie handle</em>, and the store keeps, beside each record, the
 * one handle that currently resolves to it. A handle can be re-issued while the session stays the
 * same session; the previous handle then resolves nothing.
 * <p>
 * A session is created after a successful IdP login, resolved by its cookie handle on every
 * subsequent request, replaced in place when a refresh or a widening gives it new token material,
 * and destroyed either directly (RP-initiated logout) or via the IdP's {@code sid}/{@code sub} on a
 * back-channel logout. The {@code sid}/{@code sub} destruction is O(1) through the implementation's
 * secondary index — a back-channel logout must not scan the whole store. {@code memory} is the only
 * implementation ({@link InMemorySessionStore}); a shared/external store is deliberately unsupported
 * (single-node / sticky-session deployments).
 * <p>
 * <strong>Three writes, and only one of them creates.</strong> {@link #create} is the write for a
 * <em>new</em> session. {@link #replaceIfPresent} and {@link #replaceAndReissueHandle} are the writes
 * for a session that already exists: neither ever creates, so a write that was in flight when the
 * session was destroyed cannot bring the session back.
 * <p>
 * <strong>Two deadlines.</strong> A session is expired at the earlier of its absolute lifetime
 * ({@link SessionRecord#expiresAt()}) and its idle deadline — the last access recorded through
 * {@link #recordAccess} plus the store's idle timeout. A new session's last access is the instant it
 * was created at.
 * <p>
 * Implementations must be safe for concurrent use, and every operation must be atomic with respect
 * to every other one.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public interface SessionStore {

    /**
     * Stores a <em>new</em> session under its session id and registers {@code cookieHandle} as the
     * handle that resolves to it — the write a login makes. The session's last access is {@code now}.
     * <p>
     * {@code now} is also the reference instant capacity is reclaimed against. When a new id arrives
     * while the store sits at its max-session bound, the implementation sweeps the sessions expired at
     * {@code now} and admits the session if that freed a slot. A store genuinely full of
     * <em>live</em> sessions still refuses fail-closed — the bound caps concurrent live sessions,
     * never accumulated dead ones.
     * <p>
     * A session that already exists is updated through {@link #replaceIfPresent} or
     * {@link #replaceAndReissueHandle}, never through this method. An id the store already holds is
     * nevertheless accepted here: the stored record is replaced in place, {@code cookieHandle} replaces
     * the handle it had, the secondary indexes follow the new record, and no capacity is consumed.
     *
     * @param session      the session to store
     * @param cookieHandle the opaque handle the browser will present for this session
     * @param now          the reference instant: the session's first access, and the instant expired
     *                     capacity is reclaimed against
     * @throws IllegalStateException when the store is at its max-session capacity bound and no
     *                               expired session could be reclaimed to admit this one, or when
     *                               {@code cookieHandle} already resolves to another session
     */
    void create(SessionRecord session, String cookieHandle, Instant now);

    /**
     * Replaces the record stored under {@code session}'s session id, <strong>only if a record for that
     * id is currently stored</strong>, and reports whether it did — the write a refresh makes to a
     * session that already exists.
     * <p>
     * When no record is stored under the id, nothing is written and the store is left exactly as it
     * was. That is what keeps a destroyed session destroyed: a writer that resolved the session, was
     * overtaken by {@link #destroyById}, {@link #destroyBySid} or {@link #destroyBySub}, and then
     * writes, replaces nothing.
     * <p>
     * The presence check and the replacement are <strong>one atomic step</strong>: no destruction can
     * take effect between them. A replacement may carry a different {@code sid} or {@code sub} than
     * the record it replaces; the secondary indexes then stop resolving the previous values to the
     * session and resolve the new ones instead.
     * <p>
     * <strong>The session keeps the cookie handle it has</strong>, and its last access. The handle is
     * the one the store holds for the id at the moment of the replacement — never one the caller read
     * earlier — so a replacement that was in flight across a {@link #replaceAndReissueHandle} leaves
     * the re-issued handle in place and cannot put the previous one back.
     * <p>
     * A replacement consumes no capacity — the replaced record is already counted against the
     * max-session bound — so it never sweeps and is never refused at the bound.
     * <p>
     * The check is on presence, not on liveness: a record whose lifetime has lapsed but that nothing
     * has evicted yet is replaced like any other, and {@link #resolve} goes on enforcing both deadlines.
     *
     * @param session the replacement record; its session id names the record to replace
     * @return {@code true} when a record was stored under the id and has been replaced; {@code false}
     *         when none was stored, in which case the store is unchanged
     * @since 1.0
     */
    boolean replaceIfPresent(SessionRecord session);

    /**
     * Replaces the record stored under {@code session}'s session id and swaps the cookie handle that
     * resolves to it for {@code newCookieHandle}, <strong>only if a record for that id is currently
     * stored</strong>, and reports whether it did — the write a step-up or a scope widening makes.
     * <p>
     * The presence check, the replacement of the record and the swap of the handle are <strong>one
     * atomic step</strong>. Afterwards {@code newCookieHandle} resolves the session and the handle it
     * had before resolves nothing. The session id, the absolute expiry the record carries and the last
     * access are unchanged; the secondary indexes follow the replacement record as they do for
     * {@link #replaceIfPresent}.
     * <p>
     * When no record is stored under the id, nothing is written: no record is stored and
     * {@code newCookieHandle} is not registered.
     * <p>
     * A re-issue consumes no capacity, so it never sweeps and is never refused at the bound.
     *
     * @param session         the replacement record; its session id names the record to replace
     * @param newCookieHandle the opaque handle the browser will present from now on
     * @return {@code true} when a record was stored under the id, has been replaced and now resolves
     *         from {@code newCookieHandle}; {@code false} when none was stored, in which case the store
     *         is unchanged
     * @throws IllegalStateException when {@code newCookieHandle} already resolves to another session
     * @since 1.0
     */
    boolean replaceAndReissueHandle(SessionRecord session, String newCookieHandle);

    /**
     * Records {@code now} as the last access of the session stored under {@code sessionId}, which
     * moves its idle deadline. A no-op when no session is stored under the id.
     * <p>
     * Only the instant is written — <strong>never the record</strong> — so an access recorded while a
     * refresh is replacing the record cannot overwrite the token material that refresh stored. The
     * last access only moves forward: an instant before the recorded one is ignored. The absolute
     * lifetime is not affected.
     *
     * @param sessionId the stable session id
     * @param now       the instant of the access
     * @since 1.0
     */
    void recordAccess(String sessionId, Instant now);

    /**
     * Resolves a live session by the cookie handle the browser presented, enforcing both deadlines
     * lazily: a session past its absolute lifetime or idle past the idle timeout is evicted and
     * reported as absent. Resolving does not count as an access and extends neither deadline.
     *
     * @param cookieHandle the opaque cookie handle (from the session cookie)
     * @param now          the reference instant for both deadline checks
     * @return the live session; empty when the handle resolves nothing or the session is expired
     */
    Optional<SessionRecord> resolve(String cookieHandle, Instant now);

    /**
     * Destroys the session with the given session id (RP-initiated logout) together with its cookie
     * handle. A no-op when absent.
     *
     * @param sessionId the stable session id
     */
    void destroyById(String sessionId);

    /**
     * Destroys every session carrying the given IdP {@code sid} (back-channel logout), O(1) via
     * the secondary index, each together with its cookie handle.
     *
     * @param sid the IdP session id claim
     * @return the number of sessions destroyed
     */
    int destroyBySid(String sid);

    /**
     * Destroys every session for the given subject (back-channel logout without a {@code sid}),
     * O(1) via the secondary index, each together with its cookie handle.
     *
     * @param sub the subject claim
     * @return the number of sessions destroyed
     */
    int destroyBySub(String sub);

    /**
     * Removes every session expired at {@code now} — past its absolute lifetime or idle past the idle
     * timeout — together with its cookie handle.
     * <p>
     * <strong>A periodic task drives this.</strong> The session runtime calls it at a fixed interval,
     * so an expired session leaves memory within that interval of expiring whether or not anything
     * looks it up. {@link #create} calls it as well when it reaches the max-session bound, which is
     * what lets a login reclaim a slot without waiting for the next run.
     * <p>
     * Expiry itself never depends on the sweep: {@link #resolve} evicts an expired session as it is
     * looked up, so an expired session is unresolvable whether or not it has been swept. The sweep
     * takes the same exclusion as every other operation, so the caller must not run it on a thread that
     * may not block.
     *
     * @param now the reference instant
     * @return the number of sessions swept
     */
    int sweepExpired(Instant now);
}
