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
package de.cuioss.sheriff.gateway.bff.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.model.ForwardedConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;

/**
 * The one derivation of the gateway's forwarded-header trust from the bound configuration: the
 * {@code forwarded.trusted_proxies} set, the gateway-wide baseline {@link SecurityConfiguration} a
 * forwarding header is validated under, and the {@link ForwardedResolverConfig} built from both.
 * <p>
 * Every consumer that judges a forwarding header builds its {@link TcpPeerGate} and its
 * {@link ForwardedHeaderResolver} here: the edge's forward stage, which regenerates
 * {@code X-Forwarded-For} from the resolved client, and the pending-authorization quota key
 * ({@link PendingQuotaKeys}), which counts a login under that same client. A second, hand-written
 * derivation could drift from the first — a different trusted set, or a different baseline rejecting a
 * header the other accepts — and the two would then name different clients for one request.
 * <p>
 * <strong>Why it lives here.</strong> It sits beside {@link PendingQuotaKeys}, the consumer it was
 * extracted for, and is reachable from both callers: the edge entry point and the session-runtime
 * producer already depend on this package.
 * <p>
 * <strong>The baseline.</strong> The resolved {@code security_defaults} profile
 * ({@link RouteTableBuilder#globalProfile}), limited by itself, with the declared
 * {@code security_defaults.allow_extended_ascii} override applied
 * ({@link SecurityConfigurations#withDeclaredCharacterPolicy}). The edge seeds every pre-route check
 * from the same value, so it is exposed as {@link #baseline()}.
 * <p>
 * Usage:
 * <pre>{@code
 * ForwardedTrust trust = ForwardedTrust.of(gatewayConfig);
 * TcpPeerGate peerGate = trust.peerGate();
 * ForwardedHeaderResolver resolver = trust.resolver(new SecurityEventCounter());
 * }</pre>
 * <p>
 * <strong>Thread safety.</strong> Immutable. Each call of {@link #peerGate()} and {@link #resolver}
 * builds a new collaborator, both of which are safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ForwardedTrust {

    private final List<String> trustedProxies;
    private final SecurityConfiguration baseline;
    private final ForwardedResolverConfig resolverConfig;

    private ForwardedTrust(List<String> trustedProxies, SecurityConfiguration baseline) {
        this.trustedProxies = List.copyOf(trustedProxies);
        this.baseline = baseline;
        this.resolverConfig = ForwardedResolverConfig.builder()
                .trustedProxies(Set.copyOf(trustedProxies))
                .securityConfig(baseline)
                .build();
    }

    /**
     * Derives the forwarded-header trust of {@code gatewayConfig}.
     *
     * @param gatewayConfig the bound global gateway document
     * @return the derivation; an absent {@code forwarded} block trusts no proxy
     */
    public static ForwardedTrust of(GatewayConfig gatewayConfig) {
        Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        ForwardedConfig forwardedConfig = gatewayConfig.forwarded();
        List<String> trustedProxies = forwardedConfig == null ? List.of() : forwardedConfig.trustedProxies();
        SecurityProfile globalProfile = RouteTableBuilder.globalProfile(gatewayConfig);
        SecurityDefaultsConfig securityDefaults = gatewayConfig.securityDefaults();
        Boolean allowExtendedAscii = securityDefaults == null ? null : securityDefaults.allowExtendedAscii();
        SecurityConfiguration baseline = SecurityConfigurations.withDeclaredCharacterPolicy(
                SecurityProfile.limitsProfile(globalProfile, globalProfile).preset(), allowExtendedAscii);
        return new ForwardedTrust(trustedProxies, baseline);
    }

    /**
     * @return the gateway-wide baseline security configuration, seeded from the resolved
     *         {@code security_defaults} profile and its declared character policy
     */
    public SecurityConfiguration baseline() {
        return baseline;
    }

    /**
     * @return the resolver configuration: the trusted proxies and the {@linkplain #baseline() baseline}
     */
    public ForwardedResolverConfig resolverConfig() {
        return resolverConfig;
    }

    /**
     * @return a new immediate-TCP-peer trust gate over the trusted proxies
     */
    public TcpPeerGate peerGate() {
        return new TcpPeerGate(trustedProxies);
    }

    /**
     * Builds a forwarded-header resolver over {@link #resolverConfig()}.
     *
     * @param securityEventCounter the counter a malformed forwarding header is recorded on; a consumer
     *                             that must not count a header twice passes a counter of its own
     * @return a new resolver
     */
    public ForwardedHeaderResolver resolver(SecurityEventCounter securityEventCounter) {
        return new ForwardedHeaderResolver(resolverConfig,
                Objects.requireNonNull(securityEventCounter, "securityEventCounter"));
    }
}
