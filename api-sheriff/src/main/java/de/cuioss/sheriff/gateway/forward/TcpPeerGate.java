/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The gateway-side immediate-TCP-peer trust gate (ADR-0003) — API Sheriff code, deliberately not
 * delegated to the forwarded-header resolver.
 * <p>
 * The {@code forwarded.trusted_proxies} CIDR set is parsed once, at boot, into a matcher list; at
 * request time only the single immediate peer address is parsed (no per-request CIDR string
 * parsing). {@link #isTrustedPeer(String)} answers whether the immediate TCP peer sits inside the
 * trusted set, which the {@link ForwardPolicyStage} consults before it honours any inbound
 * forwarding header — a spoofed {@code X-Forwarded-For} from an untrusted peer is therefore ignored.
 * The gate fails closed: an empty trusted set trusts no peer, and an unparseable peer address is
 * never trusted.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class TcpPeerGate {

    private final List<Cidr> trustedRanges;

    /**
     * @param trustedProxyCidrs the boot-parsed {@code forwarded.trusted_proxies} CIDR entries
     */
    public TcpPeerGate(List<String> trustedProxyCidrs) {
        Objects.requireNonNull(trustedProxyCidrs, "trustedProxyCidrs");
        List<Cidr> ranges = new ArrayList<>();
        for (String cidr : trustedProxyCidrs) {
            ranges.add(Cidr.parse(cidr));
        }
        this.trustedRanges = List.copyOf(ranges);
    }

    /**
     * @param peerAddress the immediate TCP peer address, {@code null} when the edge supplied none
     * @return {@code true} when the peer falls inside a trusted CIDR range
     */
    public boolean isTrustedPeer(@Nullable String peerAddress) {
        if (peerAddress == null || trustedRanges.isEmpty()) {
            return false;
        }
        InetAddress peer;
        try {
            peer = InetAddress.ofLiteral(peerAddress.strip());
        } catch (IllegalArgumentException _) {
            return false;
        }
        for (Cidr range : trustedRanges) {
            if (range.contains(peer)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A boot-parsed CIDR range: the network prefix bytes and the significant-bit count. Matching is
     * address-family aware — an IPv4 candidate never matches an IPv6 range and vice versa.
     * <p>
     * The network address is held as an immutable {@link List} of bytes rather than a raw
     * {@code byte[]} so the record's generated {@code equals} / {@code hashCode} / {@code toString}
     * are content-aware (an array component would compare by identity, which is misleading).
     *
     * @param network      the network address bytes (4 for IPv4, 16 for IPv6)
     * @param prefixLength the number of significant leading bits
     */
    private record Cidr(List<Byte> network, int prefixLength) {

        static Cidr parse(String cidr) {
            Objects.requireNonNull(cidr, "cidr");
            int slash = cidr.indexOf('/');
            if (slash < 0) {
                throw new IllegalArgumentException("Not a CIDR range: " + cidr);
            }
            byte[] networkBytes = InetAddress.ofLiteral(cidr.substring(0, slash).strip()).getAddress();
            int prefixLength = Integer.parseInt(cidr.substring(slash + 1).strip());
            if (prefixLength < 0 || prefixLength > networkBytes.length * 8) {
                throw new IllegalArgumentException("CIDR prefix out of range: " + cidr);
            }
            return new Cidr(toImmutableByteList(networkBytes), prefixLength);
        }

        boolean contains(InetAddress candidate) {
            byte[] address = candidate.getAddress();
            if (address.length != network.size()) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network.get(i)) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (address[fullBytes] & mask) == (network.get(fullBytes) & mask);
        }

        private static List<Byte> toImmutableByteList(byte[] bytes) {
            List<Byte> list = new ArrayList<>(bytes.length);
            for (byte value : bytes) {
                list.add(value);
            }
            return List.copyOf(list);
        }
    }
}
