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
 * A session is created after a successful IdP login, resolved by its opaque id on every
 * subsequent request, and destroyed either directly (RP-initiated logout) or via the IdP's
 * {@code sid}/{@code sub} on a back-channel logout. The {@code sid}/{@code sub} destruction is
 * O(1) through the implementation's secondary index — a back-channel logout must not scan the
 * whole store. {@code memory} is the only implementation ({@link InMemorySessionStore}); a
 * shared/external store is deliberately unsupported (single-node / sticky-session deployments).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public interface SessionStore {

    /**
     * Stores a session, upserting by its opaque id — re-creating an existing id replaces the record
     * in place, which is how a rotated session is persisted without a destroy-then-create window.
     * An upsert consumes no new capacity, so it is admitted even at the bound.
     * <p>
     * {@code now} is the reference instant capacity is reclaimed against. When a <em>new</em> id
     * arrives while the store sits at its max-session bound, the implementation sweeps the sessions
     * expired at {@code now} and admits the session if that freed a slot. A store genuinely full of
     * <em>live</em> sessions still refuses fail-closed — the bound caps concurrent live sessions,
     * never accumulated dead ones.
     *
     * @param session the session to store
     * @param now     the reference instant expired capacity is reclaimed against
     * @throws IllegalStateException when the store is at its max-session capacity bound and no
     *                               expired session could be reclaimed to admit this one
     */
    void create(SessionRecord session, Instant now);

    /**
     * Resolves a live session by its opaque id, enforcing the absolute TTL lazily: an expired
     * session is evicted and reported as absent.
     *
     * @param sessionId the opaque session id (from the session cookie)
     * @param now       the reference instant for the TTL check
     * @return the live session; empty when unknown or expired
     */
    Optional<SessionRecord> resolve(String sessionId, Instant now);

    /**
     * Destroys the session with the given opaque id (RP-initiated logout). A no-op when absent.
     *
     * @param sessionId the opaque session id
     */
    void destroyById(String sessionId);

    /**
     * Destroys every session carrying the given IdP {@code sid} (back-channel logout), O(1) via
     * the secondary index.
     *
     * @param sid the IdP session id claim
     * @return the number of sessions destroyed
     */
    int destroyBySid(String sid);

    /**
     * Destroys every session for the given subject (back-channel logout without a {@code sid}),
     * O(1) via the secondary index.
     *
     * @param sub the subject claim
     * @return the number of sessions destroyed
     */
    int destroyBySub(String sub);

    /**
     * Removes every session expired at {@code now}.
     * <p>
     * No scheduler, timer thread, or periodic task drives this. Its one automatic trigger is
     * {@link #create(SessionRecord, Instant)} reaching the max-session bound, which is what lets an
     * expired session release the slot it still occupies. Expiry itself never depends on the sweep:
     * {@link #resolve} evicts an expired session as it is looked up, so an expired session is
     * unresolvable whether or not it has been swept.
     *
     * @param now the reference instant
     * @return the number of sessions swept
     */
    int sweepExpired(Instant now);
}
