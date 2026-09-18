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
package de.cuioss.sheriff.gateway.auth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The per-issuer JWKS key-set state of the gateway's bearer-token validator, as a small,
 * <strong>non-fetching</strong> view for readiness.
 * <p>
 * Each configured issuer's logical {@code name} maps to a reader of its current
 * {@link KeySetState}. In production every reader is a {@link RetryingJwksLoader}'s pure state read,
 * so nothing on this type ever issues a JWKS request: a readiness probe polling it at any rate
 * causes zero network activity. The view deliberately exposes counts and a verdict only — no issuer
 * URL, hostname, key id or key material — so a readiness payload built from it cannot disclose
 * where the gateway fetches its keys.
 * <p>
 * The set of issuers is fixed at construction; only the state behind each reader moves.
 * <p>
 * Thread-safety: immutable apart from the states it reads, each of which is published atomically by
 * its loader; safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class IssuerKeySetStatus {

    /**
     * Whether an issuer's JWKS key set is available to validate tokens.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public enum KeySetState {
        /** A key set has been loaded; tokens of this issuer can be validated. */
        LOADED,
        /** No load attempt has completed yet. */
        NOT_LOADED,
        /** The most recent load attempt completed without a key set. */
        FAILED
    }

    private final Map<String, Supplier<KeySetState>> issuers;

    /**
     * @param issuers each configured issuer's logical name mapped to a non-fetching reader of its
     *                current key-set state, in configuration order
     */
    public IssuerKeySetStatus(Map<String, Supplier<KeySetState>> issuers) {
        Objects.requireNonNull(issuers, "issuers");
        this.issuers = Collections.unmodifiableMap(new LinkedHashMap<>(issuers));
    }

    /**
     * A view over fixed states — the shape a caller that has no live loader (a test, a fixture) uses.
     *
     * @param states each issuer's logical name mapped to its fixed state
     * @return the view
     */
    public static IssuerKeySetStatus of(Map<String, KeySetState> states) {
        Objects.requireNonNull(states, "states");
        Map<String, Supplier<KeySetState>> readers = new LinkedHashMap<>();
        states.forEach((name, state) -> {
            Objects.requireNonNull(state, "state");
            readers.put(name, () -> state);
        });
        return new IssuerKeySetStatus(readers);
    }

    /**
     * @return {@code true} when every configured issuer has a loaded key set (vacuously {@code true}
     *         when none is configured)
     */
    public boolean allLoaded() {
        return loadedCount() == configuredCount();
    }

    /**
     * @return the number of configured issuers whose key set is loaded
     */
    public int loadedCount() {
        return count(KeySetState.LOADED);
    }

    /**
     * @return the number of configured issuers whose most recent load attempt failed
     */
    public int failedCount() {
        return count(KeySetState.FAILED);
    }

    /**
     * @return the number of configured issuers
     */
    public int configuredCount() {
        return issuers.size();
    }

    private int count(KeySetState wanted) {
        int count = 0;
        for (Supplier<KeySetState> reader : issuers.values()) {
            if (reader.get() == wanted) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        // Counts only — never an issuer URL or key material.
        return "IssuerKeySetStatus[loaded=" + loadedCount() + ", configured=" + configuredCount() + "]";
    }
}
