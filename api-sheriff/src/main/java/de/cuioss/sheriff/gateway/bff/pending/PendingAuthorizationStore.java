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
package de.cuioss.sheriff.gateway.bff.pending;

import java.time.Instant;
import java.util.Optional;

import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

/**
 * The storage seam for {@link PendingAuthorizationRecord}s (D2b).
 * <p>
 * The seam is deliberately pluggable: server mode stores the record here and carries only the
 * record id in the browser-binding cookie ({@link InMemoryPendingAuthorizationStore}), whereas the cookie variant
 * (PLAN-08) can supply an implementation where the sealed record travels in the cookie itself
 * and no server map exists. The two operations are the whole contract — persist a record, and
 * consume it exactly once.
 * <p>
 * <strong>Single-use is a store invariant, not a caller contract.</strong> {@link #consume}
 * removes the record as it returns it, so a second consumption of the same id resolves to
 * empty even if the record has not expired — this is the enforcement the engine's
 * {@link de.cuioss.sheriff.token.client.flow.FlowContext} deliberately leaves open.
 * <p>
 * <strong>Fairness is a store invariant too.</strong> Unauthenticated callers create these records,
 * so a bounded store must never let one requester displace another's live login. {@link #store}
 * therefore purges expired records first, lets each {@linkplain PendingAuthorizationRecord#quotaKey()
 * quota key} displace only its own oldest record once it holds its per-source share, and refuses a
 * net-new record when the store is full of live records instead of evicting any of them.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public interface PendingAuthorizationStore {

    /**
     * The {@code Retry-After} delay, in seconds, the edge answers a
     * {@link EventType#PENDING_AUTHORIZATION_SATURATED} refusal with. Records free up as callbacks
     * consume them or as their {@linkplain PendingAuthorizationRecord#FIXED_TTL fixed lifetime} lapses,
     * so a short fixed delay needs no clock (RFC 9110 §10.2.3, delay-seconds form).
     */
    int SATURATED_RETRY_AFTER_SECONDS = 30;

    /**
     * Persists a pending-authorization record under its {@link PendingAuthorizationRecord#id()}.
     * <p>
     * Before the record is admitted, every record expired at {@code now} is purged. When the record's
     * {@linkplain PendingAuthorizationRecord#quotaKey() quota key} already holds its per-source share of
     * live records, that key's own oldest record makes room. Otherwise, when the store holds its full
     * capacity of live records, the record is refused and no stored record is touched.
     *
     * @param pending the record to store
     * @param now     the reference instant for purging expired records — the same clock source
     *                {@link #consume} is given
     * @throws GatewayException with {@link EventType#PENDING_AUTHORIZATION_SATURATED} when the store is
     *                          full of live records and the record would need room another source's live
     *                          record occupies
     */
    void store(PendingAuthorizationRecord pending, Instant now);

    /**
     * Atomically resolves and removes the record for {@code recordId} (single-use). Returns
     * empty when no record is stored under that id, when it has already been consumed, or when
     * it has expired at {@code now}.
     *
     * @param recordId the record id (from the browser-binding cookie)
     * @param now      the reference instant for the TTL check
     * @return the live record, removed from the store; empty when absent/consumed/expired
     */
    Optional<PendingAuthorizationRecord> consume(String recordId, Instant now);
}
