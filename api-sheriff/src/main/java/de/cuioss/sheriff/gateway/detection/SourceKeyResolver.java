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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * Derives the {@link SourceKey} a request is tracked under from three plain texts the edge hands
 * over: the TCP peer address, the forwarded client address and the authority host.
 * <p>
 * <strong>The source.</strong> The forwarded client address is used when one is handed over,
 * otherwise the TCP peer address. The edge hands a forwarded address over only for a peer its
 * forwarded-trust gate trusts, resolved by the same resolver the forward stage regenerates
 * {@code X-Forwarded-For} from; this class does not judge trust, so a caller that passes an untrusted
 * peer's header would key on a value that caller chose. The chosen address is canonicalized:
 * <ul>
 *   <li>an IPv4 address is kept whole, in dotted-decimal form;</li>
 *   <li>an IPv6 address is reduced to its {@code /64} prefix, written {@code a:b:c:d::/64} in lower-case
 *       hexadecimal without leading zeros — one end site is routinely given a whole {@code /64}, so
 *       keying on the full address would let one host rotate through unbounded keys. An IPv4-mapped
 *       IPv6 address is an IPv4 address and is kept whole;</li>
 *   <li>an absent, blank or unparseable address folds to the one fixed
 *       {@link SourceKey#UNKNOWN_SOURCE}.</li>
 * </ul>
 * Surrounding whitespace, enclosing square brackets and an IPv6 zone identifier ({@code %eth0}) are
 * removed before parsing. The text is parsed as an address literal only
 * ({@link InetAddress#ofLiteral(String)}), so a host name is never resolved: a name is unparseable and
 * folds to {@link SourceKey#UNKNOWN_SOURCE}, and no lookup is ever made.
 * <p>
 * <strong>The host.</strong> The authority host is lower-cased ({@link Locale#ROOT}) and kept when it
 * equals one of the hosts handed in at construction — the {@code match.host} values the routes
 * declare — otherwise it folds to the fixed {@link SourceKey#UNDECLARED_HOST}. A caller rotating
 * {@code Host} therefore cannot multiply its keys.
 * <p>
 * Immutable and thread-safe: the declared host set is copied at construction.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SourceKeyResolver {

    private static final int IPV6_PREFIX_HEXTETS = 4;

    private final Set<String> declaredHosts;

    /**
     * @param declaredHosts the hosts the routes declare in {@code match.host}; compared
     *                      case-insensitively, empty when no route declares a host
     */
    public SourceKeyResolver(Collection<String> declaredHosts) {
        this.declaredHosts = Objects.requireNonNull(declaredHosts, "declaredHosts").stream()
                .map(host -> host.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Resolves the key a request is tracked under.
     *
     * @param peerAddress            the TCP peer address, {@code null} when the edge has none
     * @param forwardedClientAddress the client address resolved from a trusted forwarder's headers,
     *                               {@code null} when the peer is not trusted or sent none
     * @param authorityHost          the authority host without its port, {@code null} when absent
     * @return the key, never {@code null}
     */
    public SourceKey resolve(@Nullable String peerAddress, @Nullable String forwardedClientAddress,
            @Nullable String authorityHost) {
        String address = forwardedClientAddress != null ? forwardedClientAddress : peerAddress;
        return new SourceKey(canonicalSource(address), foldHost(authorityHost));
    }

    /**
     * Folds an authority host to the declared set.
     *
     * @param authorityHost the authority host without its port, {@code null} when absent
     * @return the lower-cased host when a route declares it, otherwise {@link SourceKey#UNDECLARED_HOST}
     */
    public String foldHost(@Nullable String authorityHost) {
        if (authorityHost == null) {
            return SourceKey.UNDECLARED_HOST;
        }
        String lowered = authorityHost.toLowerCase(Locale.ROOT);
        return declaredHosts.contains(lowered) ? lowered : SourceKey.UNDECLARED_HOST;
    }

    /**
     * Canonicalizes a source address text; see the class documentation for the rules.
     *
     * @param address the address text, {@code null} when absent
     * @return the canonical source text, {@link SourceKey#UNKNOWN_SOURCE} when absent or unparseable
     */
    public static String canonicalSource(@Nullable String address) {
        String literal = stripDecoration(address);
        if (literal.isEmpty()) {
            return SourceKey.UNKNOWN_SOURCE;
        }
        InetAddress parsed;
        try {
            parsed = InetAddress.ofLiteral(literal);
        } catch (IllegalArgumentException notALiteral) {
            return SourceKey.UNKNOWN_SOURCE;
        }
        if (parsed instanceof Inet4Address) {
            return parsed.getHostAddress();
        }
        return ipv6Prefix(parsed.getAddress());
    }

    private static String stripDecoration(@Nullable String address) {
        if (address == null) {
            return "";
        }
        String text = address.strip();
        if (text.length() >= 2 && text.charAt(0) == '[' && text.charAt(text.length() - 1) == ']') {
            text = text.substring(1, text.length() - 1);
        }
        int zone = text.indexOf('%');
        return zone >= 0 ? text.substring(0, zone) : text;
    }

    private static String ipv6Prefix(byte[] bytes) {
        StringBuilder prefix = new StringBuilder();
        for (int hextet = 0; hextet < IPV6_PREFIX_HEXTETS; hextet++) {
            int value = ((bytes[2 * hextet] & 0xff) << 8) | (bytes[2 * hextet + 1] & 0xff);
            prefix.append(Integer.toHexString(value)).append(':');
        }
        return prefix.append(":/64").toString();
    }
}
