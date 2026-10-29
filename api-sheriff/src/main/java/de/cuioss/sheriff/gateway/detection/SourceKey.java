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
package de.cuioss.sheriff.gateway.detection;

import java.util.Objects;

/**
 * The key a {@link SourceLedger} tracks one bucket under: where a request comes from, and which of
 * the declared hosts it addressed.
 * <p>
 * <strong>Equality.</strong> Two keys are equal exactly when both texts are equal, character for
 * character. Both texts are canonical by construction — {@link SourceKeyResolver} is the only
 * producer the edge uses — so equal keys mean the same source on the same host:
 * <ul>
 *   <li>{@code source} is the canonical address text: an IPv4 address whole in dotted-decimal form,
 *       an IPv6 address reduced to its {@code /64} prefix in the form {@code a:b:c:d::/64}, or the fixed
 *       {@link #UNKNOWN_SOURCE} for an absent or unparseable address;</li>
 *   <li>{@code host} is the lower-cased authority host when it equals a host some route declares in
 *       {@code match.host}, otherwise the fixed {@link #UNDECLARED_HOST}.</li>
 * </ul>
 * <p>
 * <strong>Cardinality.</strong> The host text is drawn from a set fixed at boot plus one sentinel, so
 * a caller rotating {@code Host} cannot multiply its keys: the number of keys one source can occupy is
 * at most the number of declared hosts plus one. The number of sources is bounded by the ledger
 * ({@link SourceLedger#MAX_TRACKED_SOURCES}), not by this type.
 * <p>
 * Immutable and thread-safe.
 *
 * @param source the canonical source address text
 * @param host   the folded authority host
 * @author API Sheriff Team
 * @since 1.0
 */
public record SourceKey(String source, String host) {

    /** The source every absent or unparseable address folds to. */
    public static final String UNKNOWN_SOURCE = "unknown";

    /** The host every authority host that no route declares folds to. */
    public static final String UNDECLARED_HOST = "*";

    /**
     * Canonical constructor rejecting an absent component.
     *
     * @throws NullPointerException when {@code source} or {@code host} is {@code null}
     */
    public SourceKey {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(host, "host");
    }
}
