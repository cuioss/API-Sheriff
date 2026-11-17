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
package de.cuioss.sheriff.gateway.forward;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import lombok.experimental.UtilityClass;

/**
 * The one derivation of a trusted forwarder's client address for the per-source consumers — the
 * edge's source key ({@code SourceKeys}) and the pending-authorization quota key
 * ({@code PendingQuotaKeys}) — with a single {@code X-Forwarded-For} fallback.
 * <p>
 * <strong>Why a fallback.</strong> cui-http's {@link ForwardedHeaderResolver} resolves no client when
 * a trusted peer sends an RFC 7239 {@code Forwarded} chain that disagrees with its
 * {@code X-Forwarded-For} chain (pinned by {@code ForwardedClientResolutionTest}). Both consumers key
 * such a request on the shared {@code unknown} source, so every contested request through that proxy
 * would collapse onto one key. {@link #clientOf} therefore resolves the combined headers first and,
 * only when that yields no client, resolves again through the <em>same</em> resolver with the
 * {@code Forwarded} header masked out of the lookup — the {@code X-Forwarded-For}-family result under
 * the same trusted-proxy set and the same baseline. When that is empty too, the client stays absent.
 * <p>
 * <strong>Precondition: a trusted peer.</strong> Call it only for a peer the {@link TcpPeerGate}
 * already trusts. It applies no trust judgement of its own, so handing it an untrusted peer's headers
 * would let that peer choose its own key.
 * <p>
 * <strong>Counting.</strong> A malformed forwarding header seen by both passes may be recorded twice
 * on the resolver's security-event counter. Each consumer owns a counter of its own for exactly this
 * resolver, so the double count stays on that consumer's counter and never reaches the forward
 * stage's.
 * <p>
 * Usage:
 * <pre>{@code
 * Optional<String> client = ForwardedClientResolution.clientOf(resolver, name -> raw.headers().getAll(name));
 * }</pre>
 * <p>
 * Stateless and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ForwardedClientResolution {

    /** The RFC 7239 header masked out of the lookup on the fallback pass. */
    private static final String FORWARDED_HEADER = "Forwarded";

    /**
     * The client address a trusted forwarder reported: the combined {@code Forwarded} /
     * {@code X-Forwarded-For} result, else the {@code X-Forwarded-For} result with {@code Forwarded}
     * masked, else empty.
     *
     * @param resolver the consumer's forwarded-header resolver, configured as the forward stage's
     * @param lookup   the request's header lookup, answering every value of a header and the empty list
     *                 for an absent one
     * @return the client address, empty when neither pass resolves one
     */
    public static Optional<String> clientOf(ForwardedHeaderResolver resolver, Function<String, List<String>> lookup) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(lookup, "lookup");
        Optional<String> combined = resolver.resolve(lookup).clientIp();
        if (combined.isPresent()) {
            return combined;
        }
        return resolver.resolve(name -> FORWARDED_HEADER.equalsIgnoreCase(name) ? List.of() : lookup.apply(name))
                .clientIp();
    }
}
