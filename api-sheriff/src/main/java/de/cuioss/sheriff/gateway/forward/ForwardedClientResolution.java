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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;

/**
 * The one derivation of a trusted forwarder's client address for the per-source consumers — the
 * edge's source key ({@code SourceKeys}) and the pending-authorization quota key
 * ({@code PendingQuotaKeys}) — and the one definition of the value a client-address header is judged
 * as.
 * <p>
 * <strong>Three outcomes.</strong> {@link #clientOf} resolves the request's headers once, through
 * cui-http's {@link ForwardedHeaderResolver}, and answers one of three {@link ForwardedClient}s:
 * <ul>
 *   <li>{@link Resolved} — the resolver named a client that is an address literal;</li>
 *   <li>{@link Unresolvable} — the request carries a client-address header ({@link #CLIENT_ADDRESS_HEADERS})
 *       with a non-blank value, yet the resolver named no client, or one that is no address literal
 *       ({@link SourceKeyResolver#canonicalSource} folds it to {@link SourceKey#UNKNOWN_SOURCE}, for
 *       example an RFC 7239 obfuscated identifier): a malformed chain, a chain whose
 *       joined value fails the resolver's validation, or a {@code Forwarded} chain that disagrees with
 *       the {@code X-Forwarded-For} chain (cui-http resolves no client for such a pair, pinned by
 *       {@code ForwardedClientResolutionTest});</li>
 *   <li>{@link Absent} — the request carries no client-address header with a non-blank value.</li>
 * </ul>
 * The two unresolved outcomes are kept apart because a client can produce the first and never the
 * second: a client can always add or break a client-address header, but it cannot take away the one
 * its trusted proxy appends. A consumer that gave both the same shared key would let any client move
 * itself onto the key a header-less proxy's requests share.
 * <p>
 * <strong>No second pass.</strong> When the two families disagree, the request gives no evidence which
 * of them the trusted proxy manages: a proxy that appends to {@code X-Forwarded-For} and a client that
 * adds {@code Forwarded} produce the same shape as a proxy that appends to {@code Forwarded} and a
 * client that adds {@code X-Forwarded-For}. Resolving either family alone would therefore let the
 * client choose the address in one of the two topologies, so a disagreeing pair stays
 * {@link Unresolvable}.
 * <p>
 * <strong>The judged value.</strong> The resolver reads every instance of a client-address header as
 * one value: it joins the instances with {@value #INSTANCE_SEPARATOR} and validates the joined value as
 * a whole. {@link #judgedValue} is that joined value, so the edge's header check can judge the same
 * text the resolver reads rather than each instance alone.
 * <p>
 * <strong>Precondition: a trusted peer.</strong> Call {@link #clientOf} only for a peer the
 * {@link TcpPeerGate} already trusts. It applies no trust judgement of its own, so handing it an
 * untrusted peer's headers would let that peer choose its own key.
 * <p>
 * Usage:
 * <pre>{@code
 * ForwardedClient client = ForwardedClientResolution.clientOf(resolver, name -> raw.headers().getAll(name));
 * String key = switch (client) {
 *     case ForwardedClientResolution.Resolved resolved -> resolved.address();
 *     case ForwardedClientResolution.Unresolvable unresolvable -> keyOfTheProxy;
 *     case ForwardedClientResolution.Absent absent -> sharedUnknownKey;
 * };
 * }</pre>
 * <p>
 * Stateless and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ForwardedClientResolution {

    /**
     * The headers the resolver reads a client address from, lower-cased: RFC 7239 {@code Forwarded}
     * and {@code X-Forwarded-For}.
     */
    public static final Set<String> CLIENT_ADDRESS_HEADERS = Set.of("forwarded", "x-forwarded-for");

    /** The separator the resolver joins the instances of one header with before it validates them. */
    public static final String INSTANCE_SEPARATOR = ", ";

    /**
     * What {@link #clientOf} found in a trusted forwarder's client-address headers.
     */
    public sealed interface ForwardedClient permits Resolved, Unresolvable, Absent {
    }

    /**
     * The resolver named a client.
     *
     * @param address the client address as the resolver returned it, not yet canonicalized
     */
    public record Resolved(String address) implements ForwardedClient {

        /**
         * @throws NullPointerException when {@code address} is {@code null}
         */
        public Resolved {
            Objects.requireNonNull(address, "address");
        }
    }

    /**
     * A client-address header carries a non-blank value, yet the resolver named no client.
     */
    public record Unresolvable() implements ForwardedClient {
    }

    /**
     * No client-address header carries a non-blank value.
     */
    public record Absent() implements ForwardedClient {
    }

    private ForwardedClientResolution() {
        // static derivation only
    }

    /**
     * The client a trusted forwarder reported; see the class documentation for the three outcomes.
     *
     * @param resolver the consumer's forwarded-header resolver, configured as the forward stage's
     * @param lookup   the request's header lookup, answering every value of a header and the empty list
     *                 for an absent one
     * @return the outcome, never {@code null}
     */
    public static ForwardedClient clientOf(ForwardedHeaderResolver resolver, Function<String, List<String>> lookup) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(lookup, "lookup");
        Optional<String> client = resolver.resolve(lookup).clientIp();
        if (client.isPresent() && !SourceKey.UNKNOWN_SOURCE.equals(SourceKeyResolver.canonicalSource(client.get()))) {
            return new Resolved(client.get());
        }
        return carriesClientAddressHeader(lookup) ? new Unresolvable() : new Absent();
    }

    /**
     * Whether {@code headerName} is one of the {@link #CLIENT_ADDRESS_HEADERS}.
     *
     * @param headerName a header name, in any letter case
     * @return {@code true} for {@code Forwarded} and {@code X-Forwarded-For}
     */
    public static boolean isClientAddressHeader(String headerName) {
        return CLIENT_ADDRESS_HEADERS.contains(Objects.requireNonNull(headerName, "headerName").toLowerCase(Locale.ROOT));
    }

    /**
     * The value the resolver validates for a header sent as {@code instances}: the instances joined
     * with {@value #INSTANCE_SEPARATOR}, in their inbound order.
     *
     * @param instances every value of one header, in inbound order
     * @return the joined value; the one instance itself when there is exactly one
     */
    public static String judgedValue(List<String> instances) {
        return String.join(INSTANCE_SEPARATOR, Objects.requireNonNull(instances, "instances"));
    }

    private static boolean carriesClientAddressHeader(Function<String, List<String>> lookup) {
        return CLIENT_ADDRESS_HEADERS.stream()
                .map(lookup)
                .anyMatch(values -> values.stream().anyMatch(value -> !value.isBlank()));
    }
}
