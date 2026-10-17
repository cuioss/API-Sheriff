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
package de.cuioss.sheriff.gateway.config.validation.rule;

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.GATEWAY_FILE;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.tools.logging.CuiLogger;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s that judge the gateway's network edge: which peers may
 * supply forwarded headers, and how the TLS passthrough hosts relate to the routed traffic.
 * <p>
 * The forwarded-trust rule ({@link #validateForwardedTrust}, ADR-0044) requires every
 * {@code forwarded.trusted_proxies} entry to be a well-formed CIDR, refuses a set of ranges that
 * covers an entire address family, and emits {@link ConfigLogMessages.WARN#BROAD_TRUSTED_PROXY} for an
 * individually very broad range.
 * <p>
 * The passthrough host-collision rule ({@link #validatePassthroughHostCollision}) refuses a route whose
 * {@code match.host} is a {@code tls.passthrough_sni} host, since such a host is relayed at L4 and the
 * route would never be selected. The passthrough alias rule
 * ({@link #validatePassthroughAliasResolvable}) requires every {@code passthrough_sni} alias to resolve
 * in the topology to an origin carrying no base path.
 * <p>
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never
 * fails fast. Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class EdgeRules {

    private static final CuiLogger LOGGER = new CuiLogger(EdgeRules.class);

    private static final String PASSTHROUGH_SNI_POINTER = "/tls/passthrough_sni";
    private static final String FORWARDED_TRUSTED_POINTER = "/forwarded/trusted_proxies";
    private static final int IPV4_BITS = 32;
    private static final int IPV6_BITS = 128;
    private static final int BROAD_PREFIX_IPV4 = 16;
    private static final int BROAD_PREFIX_IPV6 = 48;

    /**
     * Rule: every {@code trusted_proxies} entry is a well-formed CIDR; the union of
     * the parsed ranges per address family must not cover the entire IPv4 or IPv6
     * space — catching a single full-space CIDR <em>and</em> complementary
     * combinations such as {@code 0.0.0.0/1} + {@code 128.0.0.0/1}. Individually very
     * broad — but not total — prefixes (shorter than {@code /16} for IPv4 or
     * {@code /48} for IPv6) are surfaced as the boot WARN
     * {@link ConfigLogMessages.WARN#BROAD_TRUSTED_PROXY}. The trust-all refusal is the only breadth
     * refusal: every range narrower than the whole address space boots.
     * <p>
     * Both thresholds encode one principle: <em>warn when a single entry spans more than one
     * operator-provisioned network.</em> A range matching exactly one network an operator
     * deliberately provisioned is a considered trust decision; anything broader is far more
     * likely to be an RFC 1918 block pasted in whole. {@code /16} is the widest single IPv4
     * network in routine use — an AWS VPC's maximum size, Docker's default bridge shape
     * {@code 172.17.0.0/16}, the RFC 1918 {@code 192.168.0.0/16} block — so the warning starts
     * one bit broader, at {@code /15}, and catches {@code 172.16.0.0/12} and {@code 10.0.0.0/8}.
     * {@code /48} is the canonical IPv6 site allocation and the direct analogue, with
     * {@code /64} the standard single subnet.
     * <p>
     * Left un-warned deliberately: an IPv4 {@code /16} (65,536 addresses) and an IPv6
     * {@code /48}. Every host inside a trusted range that can reach the gateway directly can
     * spoof forwarded headers, so a {@code /16} is still a real exposure — un-warned because it
     * is a <em>legible</em> one an operator chose, not because it is safe. Warning on a routine,
     * correct configuration is how a guard gets trained into background noise.
     * <p>
     * <strong>The value may arrive from an environment variable no code review sees.</strong> A
     * {@code ${VAR}} placeholder is substituted before this rule runs, so a range the deployment
     * environment supplies reaches it indistinguishable from one written literally into
     * {@code gateway.yaml} — except that it never passed through a reviewed change. Such a range keeps
     * exactly the boot WARN a literal one gets and meets no second, stricter refusal threshold. An
     * environment-only refusal is not expressible here, because the rule cannot tell the two sources
     * apart; a source-blind refusal at a wider threshold would refuse legitimate pod-network trust
     * such as {@code 10.0.0.0/8} while catching nothing the WARN and the trust-all refusal do not
     * already surface. The un-warned residual applies unchanged: an environment-supplied IPv4
     * {@code /16} or IPv6 {@code /48} boots with no signal at all, the same chosen exposure with no
     * review behind it. The decision and its rejected alternatives are recorded in ADR-0044.
     */
    public static void validateForwardedTrust(GatewayConfig gateway, List<ConfigError> errors) {
        ForwardedConfig forwarded = gateway.forwarded();
        if (forwarded == null) {
            return;
        }
        List<CidrRange> ipv4 = new ArrayList<>();
        List<CidrRange> ipv6 = new ArrayList<>();
        for (String cidr : forwarded.trustedProxies()) {
            Optional<CidrRange> parsed = parseCidr(cidr);
            if (parsed.isEmpty()) {
                errors.add(new ConfigError(GATEWAY_FILE, FORWARDED_TRUSTED_POINTER,
                        "malformed trusted_proxies CIDR: " + cidr));
                continue;
            }
            CidrRange range = parsed.get();
            (range.bits() == IPV4_BITS ? ipv4 : ipv6).add(range);
        }
        checkFamilyTrust(ipv4, IPV4_BITS, BROAD_PREFIX_IPV4, "IPv4", errors);
        checkFamilyTrust(ipv6, IPV6_BITS, BROAD_PREFIX_IPV6, "IPv6", errors);
    }

    private static void checkFamilyTrust(List<CidrRange> ranges, int bits, int broadPrefix, String family,
            List<ConfigError> errors) {
        if (ranges.isEmpty()) {
            return;
        }
        if (coversEntireSpace(ranges, bits)) {
            errors.add(new ConfigError(GATEWAY_FILE, FORWARDED_TRUSTED_POINTER,
                    "trusted_proxies cover the entire %s address space; a trust-all range is not permitted"
                            .formatted(family)));
            return;
        }
        for (CidrRange range : ranges) {
            if (range.prefixLength() < broadPrefix) {
                LOGGER.warn(ConfigLogMessages.WARN.BROAD_TRUSTED_PROXY, range.cidr(),
                        Integer.toString(range.prefixLength()));
            }
        }
    }

    /**
     * Whether the union of the supplied ranges covers the whole {@code [0, 2^bits-1]}
     * address space, merging contiguous or overlapping ranges (so complementary
     * halves are caught).
     */
    private static boolean coversEntireSpace(List<CidrRange> ranges, int bits) {
        List<CidrRange> sorted = new ArrayList<>(ranges);
        sorted.sort((first, second) -> first.start().compareTo(second.start()));
        BigInteger max = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
        BigInteger nextExpected = BigInteger.ZERO;
        for (CidrRange range : sorted) {
            if (range.start().compareTo(nextExpected) > 0) {
                return false;
            }
            BigInteger endPlusOne = range.end().add(BigInteger.ONE);
            if (endPlusOne.compareTo(nextExpected) > 0) {
                nextExpected = endPlusOne;
            }
        }
        return nextExpected.compareTo(max) > 0;
    }

    /**
     * Parses a {@code address/prefix} CIDR into its inclusive address range, or an
     * empty optional when the entry is malformed (not exactly one {@code /}, a
     * non-numeric or out-of-range prefix, or an address that is not an IP literal).
     */
    private static Optional<CidrRange> parseCidr(String cidr) {
        int slash = cidr.indexOf('/');
        if (slash < 0 || cidr.indexOf('/', slash + 1) >= 0) {
            return Optional.empty();
        }
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException _) {
            return Optional.empty();
        }
        byte[] bytes;
        try {
            bytes = InetAddress.ofLiteral(cidr.substring(0, slash)).getAddress();
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        int bits = bytes.length * 8;
        if (prefixLength < 0 || prefixLength > bits) {
            return Optional.empty();
        }
        BigInteger value = new BigInteger(1, bytes);
        int hostBits = bits - prefixLength;
        BigInteger start = value.shiftRight(hostBits).shiftLeft(hostBits);
        BigInteger end = start.add(BigInteger.ONE.shiftLeft(hostBits)).subtract(BigInteger.ONE);
        return Optional.of(new CidrRange(cidr, bits, prefixLength, start, end));
    }

    private record CidrRange(String cidr, int bits, int prefixLength, BigInteger start, BigInteger end) {
    }

    /**
     * Rule: a route whose {@code match.host} equals a {@code tls.passthrough_sni} host is refused,
     * because a connection naming that host in its SNI is relayed at L4 to the passthrough origin
     * and never reaches the route table, so the route is never routed. The comparison ignores
     * letter case, and a route that matches no host is not judged. One violation is collected per
     * colliding route and host; the rule never fails fast.
     */
    public static void validatePassthroughHostCollision(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        Map<String, String> passthrough = passthroughSni(gateway);
        if (passthrough.isEmpty()) {
            return;
        }
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                String host = routeHost(route);
                if (host == null) {
                    continue;
                }
                for (String sniHost : passthrough.keySet()) {
                    if (sniHost.equalsIgnoreCase(host)) {
                        errors.add(new ConfigError(GATEWAY_FILE, PASSTHROUGH_SNI_POINTER,
                                "route '%s' matches host '%s', which is relayed at L4 by passthrough_sni and is never routed"
                                        .formatted(route.id(), sniHost)));
                    }
                }
            }
        }
    }

    /**
     * Rule: every {@code tls.passthrough_sni} alias must resolve in the topology, and must resolve
     * to an origin with no base path — a resolved base path other than empty or {@code /} is
     * refused. An unresolved alias and a resolved alias carrying a base path are each collected as
     * a violation; the rule never fails fast.
     */
    public static void validatePassthroughAliasResolvable(GatewayConfig gateway, ResolvedTopology topology,
            List<ConfigError> errors) {
        for (Map.Entry<String, String> entry : passthroughSni(gateway).entrySet()) {
            String alias = entry.getValue();
            Optional<ResolvedUpstream> upstream = topology.lookup(alias);
            if (upstream.isEmpty()) {
                errors.add(new ConfigError(GATEWAY_FILE, PASSTHROUGH_SNI_POINTER,
                        "unresolved topology alias '%s' referenced by passthrough_sni host '%s'"
                                .formatted(alias, entry.getKey())));
                continue;
            }
            String basePath = upstream.get().basePath();
            if (!basePath.isEmpty() && !"/".equals(basePath)) {
                errors.add(new ConfigError(GATEWAY_FILE, PASSTHROUGH_SNI_POINTER,
                        "passthrough_sni alias '%s' must resolve to an origin without a base path, but resolved to '%s'"
                                .formatted(alias, basePath)));
            }
        }
    }

    private static Map<String, String> passthroughSni(GatewayConfig gateway) {
        TlsConfig tls = gateway.tls();
        return tls == null ? Map.of() : tls.passthroughSni();
    }

    private static @Nullable String routeHost(RouteConfig route) {
        String host = route.match().host();
        if (host == null) {
            return null;
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
