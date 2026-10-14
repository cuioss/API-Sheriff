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
package de.cuioss.sheriff.gateway.config;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import de.cuioss.sheriff.gateway.config.RouteTableBuilder.RouteTableException;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import org.jspecify.annotations.Nullable;

/**
 * One policy block of the gateway configuration — {@code auth}, {@code allowed_methods},
 * {@code security_filter}, {@code security_headers}, {@code upstream_defaults}, {@code forward} or
 * {@code websocket} — declared once, together with the one rule every block is resolved by.
 * <p>
 * A descriptor names the scopes the block is declarable at and, per scope, the accessor reading
 * the declaration there. The rule is the same for every block (ADR-0007): the scopes are ordered
 * {@code gateway → anchor → endpoint → route} ({@link Scope}), the <em>nearest declared</em> scope
 * wins, and the winning block replaces every broader one <em>wholesale</em> — no field of a
 * broader declaration is merged into it. A {@code null} value and an empty list both count as
 * <em>not declared</em> ({@link #isDeclared}). When no scope declares the block, the descriptor's
 * {@link Fallback} decides: a fixed value, {@code null}, or a boot error.
 * <p>
 * The seven descriptors ({@link #ALL}) are the single place the inheritance chains live: the route
 * table ({@link RouteTableBuilder}) and the boot validator
 * ({@code de.cuioss.sheriff.gateway.config.validation.ConfigValidator}) both resolve through them,
 * so the boot refusal and the runtime posture can never resolve a block differently. Two
 * <em>field-level</em> fallbacks are deliberately not part of this rule and stay with their
 * readers: {@code security_filter.profile} falling back to {@code security_defaults.profile}
 * ({@link RouteTableBuilder#effectiveProfile}), and a route's {@code upstream.retry.enabled} /
 * {@code upstream.not_modified.enabled} overriding the resolved {@code upstream_defaults} field by
 * field.
 * <p>
 * Usage:
 * {@snippet :
 * PolicyBlock.ScopeChain chain = PolicyBlock.ScopeChain.of(gateway, endpoint, route);
 * AuthConfig auth = PolicyBlock.AUTH.resolve(chain);              // boot error when undeclared
 * AuthConfig declared = PolicyBlock.AUTH.declared(chain);         // null when undeclared
 * Set<PolicyBlock.Scope> where = PolicyBlock.ALLOWED_METHODS.declaringScopes(chain);
 * }
 * <p>
 * The class carries no framework imports, so the offline configuration check can run it before
 * the framework starts (ADR-0061). Every descriptor is immutable and safe for concurrent use.
 *
 * @param <T>          the block's type; a nullable type argument for a block whose fallback is
 *                     {@code null}
 * @param yamlKey      the key the block is declared under in the configuration documents
 * @param accessors    per declarable scope, the accessor reading the block declared there (or
 *                     {@code null} / an empty list when that scope does not declare it); iterated in
 *                     {@link Scope} order
 * @param fallback     what the block resolves to when no scope declares it
 * @param overrideWarn whether replacing an anchor-declared block from a nearer scope is announced
 *                     as a boot WARN
 * @author API Sheriff Team
 * @since 1.0
 */
public record PolicyBlock<T extends @Nullable Object>(String yamlKey,
Map<Scope, Function<ScopeChain, @Nullable T>> accessors, Fallback<T> fallback, boolean overrideWarn) {

    /** Every standard HTTP method — the {@code allowed_methods} value when no scope declares one. */
    private static final List<HttpMethod> STANDARD_ALLOWED_METHODS = List.copyOf(EnumSet.allOf(HttpMethod.class));

    /** The scopes nearest first: the order {@link #declared} searches them in. */
    private static final List<Scope> NEAREST_FIRST = List.of(Scope.values()).reversed();

    /**
     * {@code auth}: declarable at anchor, endpoint and route. A route no scope supplies an auth
     * posture for is a boot error, never a default.
     */
    public static final PolicyBlock<AuthConfig> AUTH = new PolicyBlock<>("auth",
            Map.of(Scope.ANCHOR, atAnchor(AnchorConfig::auth),
                    Scope.ENDPOINT, atEndpoint(EndpointConfig::auth),
                    Scope.ROUTE, atRoute(RouteConfig::auth)),
            new Fallback.BootError<>("route '%s' has no effective auth (no route, endpoint, or anchor auth block)"),
            false);

    /**
     * {@code allowed_methods}: declarable at gateway, anchor and endpoint; an empty list is not a
     * declaration. When no scope declares one, every standard HTTP method is allowed.
     */
    public static final PolicyBlock<List<HttpMethod>> ALLOWED_METHODS = new PolicyBlock<>("allowed_methods",
            Map.of(Scope.GATEWAY, atGateway(GatewayConfig::allowedMethods),
                    Scope.ANCHOR, atAnchor(AnchorConfig::allowedMethods),
                    Scope.ENDPOINT, atEndpoint(EndpointConfig::allowedMethods)),
            new Fallback.Value<>(STANDARD_ALLOWED_METHODS),
            false);

    /**
     * {@code security_filter}: declarable at anchor and route; {@code null} when neither declares
     * one (the profile then comes from {@code security_defaults}).
     */
    public static final PolicyBlock<@Nullable SecurityFilterConfig> SECURITY_FILTER =
            new PolicyBlock<>("security_filter",
                    Map.of(Scope.ANCHOR, atAnchor(AnchorConfig::securityFilter),
                            Scope.ROUTE, atRoute(RouteConfig::securityFilter)),
                    new Fallback.None<>(),
                    false);

    /** {@code security_headers}: declarable at gateway and anchor; {@code null} when neither declares one. */
    public static final PolicyBlock<@Nullable SecurityHeadersConfig> SECURITY_HEADERS =
            new PolicyBlock<>("security_headers",
                    Map.of(Scope.GATEWAY, atGateway(GatewayConfig::securityHeaders),
                            Scope.ANCHOR, atAnchor(AnchorConfig::securityHeaders)),
                    new Fallback.None<>(),
                    false);

    /**
     * {@code upstream_defaults}: declarable at gateway and endpoint; {@link UpstreamDefaultsConfig#defaults()}
     * when neither declares one.
     */
    public static final PolicyBlock<UpstreamDefaultsConfig> UPSTREAM_DEFAULTS = new PolicyBlock<>("upstream_defaults",
            Map.of(Scope.GATEWAY, atGateway(GatewayConfig::upstreamDefaults),
                    Scope.ENDPOINT, atEndpoint(EndpointConfig::upstreamDefaults)),
            new Fallback.Value<>(UpstreamDefaultsConfig.defaults()),
            false);

    /**
     * {@code forward}: declarable at route only. When the route declares none, the all-absent block
     * applies, which is <em>forward-all</em> on both dimensions — not a nothing-crosses allowlist.
     */
    public static final PolicyBlock<ForwardConfig> FORWARD = new PolicyBlock<>("forward",
            Map.of(Scope.ROUTE, atRoute(RouteConfig::forward)),
            new Fallback.Value<>(ForwardConfig.builder().build()),
            false);

    /** {@code websocket}: declarable at route only; {@code null} when the route declares none. */
    public static final PolicyBlock<@Nullable WebSocketConfig> WEBSOCKET = new PolicyBlock<>("websocket",
            Map.of(Scope.ROUTE, atRoute(RouteConfig::websocket)),
            new Fallback.None<>(),
            false);

    /** Every policy-block descriptor, one per block. */
    public static final List<PolicyBlock<?>> ALL = List.of(AUTH, ALLOWED_METHODS, SECURITY_FILTER,
            SECURITY_HEADERS, UPSTREAM_DEFAULTS, FORWARD, WEBSOCKET);

    /**
     * Canonical constructor requiring every component and freezing the accessors in {@link Scope}
     * order.
     *
     * @throws IllegalArgumentException when no declarable scope is named
     */
    public PolicyBlock {
        Objects.requireNonNull(yamlKey, "yamlKey");
        Objects.requireNonNull(accessors, "accessors");
        Objects.requireNonNull(fallback, "fallback");
        if (accessors.isEmpty()) {
            throw new IllegalArgumentException("policy block '%s' declares no scope".formatted(yamlKey));
        }
        accessors = Collections.unmodifiableMap(new EnumMap<>(accessors));
    }

    /**
     * The scopes this block is declarable at, in {@link Scope} order.
     *
     * @return the declarable scopes, never empty
     */
    public Set<Scope> scopes() {
        return accessors.keySet();
    }

    /**
     * The block declared at the nearest scope of the chain, without the fallback.
     *
     * @param chain the route's scope chain
     * @return the nearest declared block, or {@code null} when no scope declares one
     */
    public @Nullable T declared(ScopeChain chain) {
        Objects.requireNonNull(chain, "chain");
        for (Scope scope : NEAREST_FIRST) {
            Function<ScopeChain, @Nullable T> accessor = accessors.get(scope);
            if (accessor != null) {
                T value = accessor.apply(chain);
                if (isDeclared(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Resolves the block for the chain: the nearest declared scope wins and replaces every broader
     * declaration wholesale; when no scope declares the block, the {@link Fallback} applies.
     *
     * @param chain the route's scope chain
     * @return the effective block; {@code null} only for a block whose fallback is {@link Fallback.None}
     * @throws RouteTableException when no scope declares the block and its fallback is a
     *                             {@link Fallback.BootError}
     */
    public T resolve(ScopeChain chain) {
        T declared = declared(chain);
        return declared != null ? declared : fallback.apply(chain);
    }

    /**
     * Every scope of the chain that declares this block — the input for announcing that a nearer
     * scope replaced a broader declaration.
     *
     * @param chain the route's scope chain
     * @return the declaring scopes in {@link Scope} order, empty when no scope declares the block
     */
    public Set<Scope> declaringScopes(ScopeChain chain) {
        Objects.requireNonNull(chain, "chain");
        Set<Scope> declaring = EnumSet.noneOf(Scope.class);
        accessors.forEach((scope, accessor) -> {
            if (isDeclared(accessor.apply(chain))) {
                declaring.add(scope);
            }
        });
        return Collections.unmodifiableSet(declaring);
    }

    /**
     * The one "is declared" test every block shares: a {@code null} value and an empty collection
     * are both <em>not declared</em>.
     *
     * @param value the value read at one scope
     * @return {@code true} when the value is a declaration
     */
    public static boolean isDeclared(@Nullable Object value) {
        return value != null && !(value instanceof Collection<?> collection && collection.isEmpty());
    }

    private static <T extends @Nullable Object> Function<ScopeChain, @Nullable T> atGateway(
            Function<GatewayConfig, @Nullable T> accessor) {
        return chain -> accessor.apply(chain.gateway());
    }

    private static <T extends @Nullable Object> Function<ScopeChain, @Nullable T> atAnchor(
            Function<AnchorConfig, @Nullable T> accessor) {
        return chain -> {
            AnchorConfig anchor = chain.anchor();
            return anchor == null ? null : accessor.apply(anchor);
        };
    }

    private static <T extends @Nullable Object> Function<ScopeChain, @Nullable T> atEndpoint(
            Function<EndpointConfig, @Nullable T> accessor) {
        return chain -> accessor.apply(chain.endpoint());
    }

    private static <T extends @Nullable Object> Function<ScopeChain, @Nullable T> atRoute(
            Function<RouteConfig, @Nullable T> accessor) {
        return chain -> accessor.apply(chain.route());
    }

    /**
     * The scopes a policy block can be declared at, broadest first. A later constant is a
     * <em>nearer</em> scope: its declaration replaces every earlier one.
     *
     * @since 1.0
     */
    public enum Scope {

        /** The gateway document ({@code gateway.yaml} root). */
        GATEWAY,

        /** The route's anchor ({@code gateway.yaml} {@code anchors.<name>}). */
        ANCHOR,

        /** The route's endpoint ({@code endpoints/*.yaml} {@code endpoint}). */
        ENDPOINT,

        /** The route itself ({@code endpoint.routes[]}). */
        ROUTE
    }

    /**
     * The four scopes one route is resolved against. The anchor is the route's own {@code anchor}
     * when it declares one, else its endpoint's; an anchor name the gateway does not declare
     * resolves to no anchor, exactly as an absent name does (the boot validator reports the unknown
     * name separately).
     *
     * @param gateway  the bound gateway document
     * @param anchor   the route's resolved anchor, {@code null} when the route is unanchored
     * @param endpoint the route's owning endpoint
     * @param route    the route
     * @since 1.0
     */
    public record ScopeChain(GatewayConfig gateway, @Nullable AnchorConfig anchor, EndpointConfig endpoint,
    RouteConfig route) {

        /**
         * Canonical constructor requiring the gateway, endpoint and route.
         */
        public ScopeChain {
            Objects.requireNonNull(gateway, "gateway");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(route, "route");
        }

        /**
         * Builds the chain of one route, resolving its anchor: the route's {@code anchor} when
         * declared, otherwise the endpoint's — the one place a route's anchor membership is
         * resolved for block resolution.
         *
         * @param gateway  the bound gateway document
         * @param endpoint the route's owning endpoint
         * @param route    the route
         * @return the route's scope chain
         */
        public static ScopeChain of(GatewayConfig gateway, EndpointConfig endpoint, RouteConfig route) {
            Objects.requireNonNull(gateway, "gateway");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(route, "route");
            String routeAnchor = route.anchor();
            String name = routeAnchor != null ? routeAnchor : endpoint.anchor();
            return new ScopeChain(gateway, name == null ? null : gateway.anchors().get(name), endpoint, route);
        }
    }

    /**
     * What a policy block resolves to when no scope declares it.
     *
     * @param <T> the block's type
     * @since 1.0
     */
    public sealed interface Fallback<T extends @Nullable Object> {

        /**
         * Applies the fallback for a chain no scope of which declares the block.
         *
         * @param chain the route's scope chain
         * @return the fallback value
         * @throws RouteTableException when the fallback is a {@link BootError}
         */
        T apply(ScopeChain chain);

        /**
         * A fixed value.
         *
         * @param value the value every undeclared chain resolves to
         * @param <T>   the block's type
         * @since 1.0
         */
        record Value<T extends @Nullable Object>(T value) implements Fallback<T> {

            @Override
            public T apply(ScopeChain chain) {
                return value;
            }
        }

        /**
         * No block: an undeclared chain resolves to {@code null}.
         *
         * @param <T> the block's (nullable) type
         * @since 1.0
         */
        record None<T extends @Nullable Object>() implements Fallback<T> {

            @Override
            public T apply(ScopeChain chain) {
                return null;
            }
        }

        /**
         * A boot error: an undeclared chain is a configuration error, not a defaultable omission.
         *
         * @param messageTemplate the {@link RouteTableException} message, with one {@code %s}
         *                        receiving the route id
         * @param <T>             the block's type
         * @since 1.0
         */
        record BootError<T extends @Nullable Object>(String messageTemplate) implements Fallback<T> {

            /**
             * Canonical constructor requiring the message template.
             */
            public BootError {
                Objects.requireNonNull(messageTemplate, "messageTemplate");
            }

            @Override
            public T apply(ScopeChain chain) {
                throw new RouteTableException(messageTemplate.formatted(chain.route().id()));
            }
        }
    }
}
