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
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public interface PendingAuthorizationStore {

    /**
     * Persists a pending-authorization record under its {@link PendingAuthorizationRecord#id()}.
     *
     * @param pending the record to store
     */
    void store(PendingAuthorizationRecord pending);

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
