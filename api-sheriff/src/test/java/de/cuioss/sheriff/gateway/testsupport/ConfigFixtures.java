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
package de.cuioss.sheriff.gateway.testsupport;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.config.model.UpstreamDefaultsConfig;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.test.generator.Generators;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * Pre-seeded fixtures for the configuration this module's tests build again and again.
 * <p>
 * Two families live here, and the return type says which one a method belongs to. A <em>seed</em>
 * ({@link #gateway()}, {@link #route(String, String)}, {@link #validGateway()}) returns a builder, never a
 * finished record: it holds only what every caller would otherwise write identically, so a test still
 * states, on its own line, the one field its behaviour depends on. A <em>leaf value</em> (every other
 * method) returns a finished record whose few inputs are all parameters, because the test reads it inside
 * another builder call or passes it straight to the validator:
 * {@snippet :
 * GatewayConfig config = ConfigFixtures.gateway().oidc(oidc).build();
 * Route route = ConfigFixtures.route("api", "/api").upstream(upstream).build();
 * EndpointConfig endpoint = ConfigFixtures.endpoint("orders", "ORDERS", List.of(), routeConfig("r", HttpMethod.GET));
 * }
 * <p>
 * A test that uses the fixtures unqualified imports them with a star static import, because a named static
 * import of an {@code @UtilityClass} member does not resolve under javac.
 * <p>
 * Thread-safe: the class is stateless and every call returns a fresh builder or an immutable record.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ConfigFixtures {

    /**
     * The gateway document every edge fixture starts from.
     * <p>
     * It declares {@code enumeration_detection.enabled: false}. The edge tests built on it send many
     * rejected requests from one loopback source; under the shipped default {@code observe} those would
     * change no answer, but they would trip the source and raise its WARN and event-counter side effects
     * in tests about something else. A test about detection overrides the block, or sets it to
     * {@code null} to run the omitted-block default.
     *
     * @return a builder carrying {@code version: 1} and the disabled detection block, nothing else
     */
    public GatewayConfig.GatewayConfigBuilder gateway() {
        return GatewayConfig.builder().version(1)
                .enumerationDetection(EnumerationDetectionConfig.builder().enabled(false).build());
    }

    /**
     * The gateway document a boot-validation test starts from: the version every document must declare
     * and nothing else, so every rule reads an omitted block exactly as an operator's document omits it.
     *
     * @return a builder carrying {@code version: 1} only
     */
    public GatewayConfig.GatewayConfigBuilder validGateway() {
        return GatewayConfig.builder().version(1);
    }

    /**
     * A seed route: an HTTP route under a path prefix that requires no authentication and admits only
     * {@code GET}.
     * <p>
     * The seed is not what route-table assembly resolves for a route that declares neither field; it
     * differs in two ways. A route no scope declares {@code allowed_methods} for resolves to every
     * standard HTTP method, not to {@code GET} alone ({@code PolicyBlock.ALLOWED_METHODS}). A route no
     * scope gives an {@code auth} block is refused at boot, not resolved to {@code require: none}
     * ({@code PolicyBlock.AUTH}).
     * <p>
     * The caller adds what the case is about — typically the upstream, and any other method, posture
     * or block — and may override every seeded field.
     *
     * @param id         the route id
     * @param pathPrefix the {@code match.path_prefix}
     * @return the seeded builder
     */
    public Route.RouteBuilder route(String id, String pathPrefix) {
        return Route.builder()
                .id(id)
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET));
    }

    /**
     * A seed route under {@code /api} with a generated id, for a case about the {@link Route} record itself.
     * It carries no terminal action, so the caller adds the one its case needs.
     *
     * @return the seeded builder
     */
    public Route.RouteBuilder baseRoute() {
        return route(Generators.letterStrings(3, 12).next(), "/api");
    }

    /**
     * The upstream of the location-rewriter cases: plain HTTP on a non-default port with an empty base path.
     *
     * @return the upstream {@code http://backend:8080}
     */
    public ResolvedUpstream backendUpstream() {
        return new ResolvedUpstream("http", "backend", 8080, "");
    }

    /**
     * A seed anchor: a {@code proxy} anchor at {@code /<name>} with {@code access: authenticated} and no
     * policy block, so the caller states the one block its case resolves. Unlike {@link #anchor}, it is
     * not kept consistent with the access-to-auth matrix, because route-table assembly and policy
     * resolution do not read that matrix.
     *
     * @param name the anchor name
     * @return the seeded builder
     */
    public AnchorConfig.AnchorConfigBuilder anchorSeed(String name) {
        return AnchorConfig.builder().name(name).pathPrefix("/" + name).type(AnchorType.PROXY)
                .access(AccessLevel.AUTHENTICATED);
    }

    /**
     * A seed endpoint: enabled, and nothing else.
     *
     * @param id the endpoint id
     * @return the seeded builder
     */
    public EndpointConfig.EndpointConfigBuilder endpointSeed(String id) {
        return EndpointConfig.builder().id(id).enabled(true);
    }

    /**
     * A seed endpoint that declares its own {@code require: none} posture.
     *
     * @param id    the endpoint id
     * @param alias the {@code base_url} topology alias, or {@code null} for an endpoint without proxy routes
     * @return the seeded builder
     */
    public EndpointConfig.EndpointConfigBuilder openEndpointSeed(String id, @Nullable String alias) {
        return endpointSeed(id).baseUrl(alias).auth(auth(Require.NONE));
    }

    /**
     * A seed endpoint on an anchor that declares no {@code auth} block, so the anchor's floor governs it.
     *
     * @param id         the endpoint id
     * @param alias      the {@code base_url} topology alias
     * @param anchorName the anchor
     * @return the seeded builder
     */
    public EndpointConfig.EndpointConfigBuilder anchoredEndpointSeed(String id, String alias, String anchorName) {
        return endpointSeed(id).baseUrl(alias).anchor(anchorName);
    }

    /**
     * A seed route declaration under the prefix {@code /<id>} that matches every method.
     *
     * @param id the route id
     * @return the seeded builder
     */
    public RouteConfig.RouteConfigBuilder routeConfigSeed(String id) {
        return RouteConfig.builder().id(id).match(MatchConfig.builder().pathPrefix("/" + id).build());
    }

    /**
     * A route declaration under a stated prefix.
     *
     * @param id      the route id
     * @param prefix  the {@code match.path_prefix}
     * @param methods the {@code match.methods}
     * @return the declaration
     */
    public RouteConfig prefixRoute(String id, String prefix, HttpMethod... methods) {
        return RouteConfig.builder().id(id).match(match(prefix, methods)).build();
    }

    /**
     * A {@code security_filter} block declaring only its profile.
     *
     * @param profile the profile
     * @return the block
     */
    public SecurityFilterConfig filter(String profile) {
        return SecurityFilterConfig.builder().profile(profile).build();
    }

    /**
     * A {@code security_headers} block declaring only {@code frame_deny}.
     *
     * @return the block
     */
    public SecurityHeadersConfig headers() {
        return SecurityHeadersConfig.builder().frameDeny(true).build();
    }

    /**
     * A {@code cookies} block listing one cookie.
     *
     * @param cookieName the listed cookie
     * @return the block
     */
    public CookiesConfig cookies(String cookieName) {
        return new CookiesConfig(new CookieAllow.Named(List.of(cookieName)), null, null);
    }

    /**
     * An {@code upstream_defaults} block declaring both toggles.
     *
     * @param retryEnabled       the retry toggle
     * @param notModifiedEnabled the not-modified toggle
     * @return the block
     */
    public UpstreamDefaultsConfig upstreamDefaults(boolean retryEnabled, boolean notModifiedEnabled) {
        return UpstreamDefaultsConfig.builder()
                .retryEnabled(retryEnabled)
                .notModifiedEnabled(notModifiedEnabled)
                .build();
    }

    /**
     * An {@code auth} block declaring only its posture.
     *
     * @param require the posture
     * @return the block
     */
    public AuthConfig auth(Require require) {
        return AuthConfig.builder().require(require).build();
    }

    /**
     * A topology in which each alias resolves to {@code https://<alias>.internal:443}.
     *
     * @param aliases the aliases; none yields the empty topology
     * @return the topology
     */
    public ResolvedTopology topologyWith(String... aliases) {
        Map<String, ResolvedUpstream> map = new HashMap<>();
        for (String alias : aliases) {
            map.put(alias, new ResolvedUpstream("https", alias.toLowerCase(Locale.ROOT) + ".internal", 443, ""));
        }
        return new ResolvedTopology(map);
    }

    /**
     * A prefix matcher.
     *
     * @param pathPrefix the {@code path_prefix}
     * @param methods    the {@code methods}; none matches every method
     * @return the matcher
     */
    public MatchConfig match(String pathPrefix, HttpMethod... methods) {
        return MatchConfig.builder().pathPrefix(pathPrefix).methods(List.of(methods)).build();
    }

    /**
     * A route declaration under the prefix {@code /<id>}.
     *
     * @param id      the route id
     * @param methods the {@code match.methods}
     * @return the declaration
     */
    public RouteConfig routeConfig(String id, HttpMethod... methods) {
        return RouteConfig.builder().id(id).match(match("/" + id, methods)).build();
    }

    /**
     * An enabled {@code require: none} endpoint on no anchor.
     *
     * @param id             the endpoint id
     * @param alias          the {@code base_url} topology alias
     * @param allowedMethods the {@code allowed_methods}
     * @param routes         the routes
     * @return the endpoint
     */
    public EndpointConfig endpoint(String id, String alias, List<HttpMethod> allowedMethods, RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .auth(auth(Require.NONE))
                .allowedMethods(allowedMethods)
                .routes(List.of(routes))
                .build();
    }

    /**
     * A {@code proxy} anchor that stays consistent with the ADR-0013 access-to-auth matrix by
     * construction, because the ADR-0007 anchor rules are orthogonal to it: an anchor with no auth floor
     * is {@code access: public}, while an anchor carrying a floor is {@code access: authenticated}.
     *
     * @param name    the anchor name
     * @param prefix  the {@code path_prefix}
     * @param require the auth floor, or {@code null} for none
     * @return the anchor
     */
    public AnchorConfig anchor(String name, String prefix, @Nullable Require require) {
        return matrixAnchor(name, prefix, AnchorType.PROXY,
                require == null ? AccessLevel.PUBLIC : AccessLevel.AUTHENTICATED, require);
    }

    /**
     * An anchor with every matrix input stated, for a case about the access-to-auth matrix itself.
     *
     * @param name    the anchor name
     * @param prefix  the {@code path_prefix}
     * @param type    the anchor type
     * @param access  the access level
     * @param require the auth floor, or {@code null} for none
     * @return the anchor
     */
    public AnchorConfig matrixAnchor(String name, String prefix, AnchorType type, AccessLevel access,
            @Nullable Require require) {
        return AnchorConfig.builder()
                .name(name)
                .pathPrefix(prefix)
                .type(type)
                .access(access)
                .auth(require == null ? null : auth(require))
                .build();
    }

    /**
     * A gateway declaring one anchor and the one token-validation issuer a bearer floor needs.
     *
     * @param anchorConfig the anchor
     * @return the gateway
     */
    public GatewayConfig gatewayWithAnchorAndIssuer(AnchorConfig anchorConfig) {
        return validGateway()
                .anchors(Map.of(anchorConfig.name(), anchorConfig))
                .tokenValidation(new TokenValidationConfig(List.of(
                        IssuerConfig.builder().name("main").issuer("https://idp.example").build())))
                .build();
    }

    /**
     * A gateway declaring these anchors.
     *
     * @param anchors the anchors by name
     * @return the gateway
     */
    public GatewayConfig gatewayWithAnchors(Map<String, AnchorConfig> anchors) {
        return validGateway().anchors(anchors).build();
    }

    /**
     * An enabled endpoint on an anchor.
     *
     * @param id         the endpoint id
     * @param alias      the {@code base_url} topology alias
     * @param anchorName the anchor, or {@code null} for none
     * @param auth       the endpoint {@code auth} block, or {@code null} for none
     * @param routes     the routes
     * @return the endpoint
     */
    public EndpointConfig anchoredEndpoint(String id, String alias, @Nullable String anchorName,
            @Nullable AuthConfig auth, RouteConfig... routes) {
        return EndpointConfig.builder()
                .id(id)
                .enabled(true)
                .baseUrl(alias)
                .anchor(anchorName)
                .auth(auth)
                .routes(List.of(routes))
                .build();
    }

    /**
     * A route declaration on an anchor.
     *
     * @param id         the route id
     * @param prefix     the {@code match.path_prefix}
     * @param anchorName the anchor, or {@code null} for none
     * @param methods    the {@code match.methods}
     * @return the declaration
     */
    public RouteConfig anchoredRoute(String id, String prefix, @Nullable String anchorName, HttpMethod... methods) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName)
                .match(match(prefix, methods))
                .build();
    }

    /**
     * A route declaration whose terminal action is an asset.
     *
     * @param id         the route id
     * @param prefix     the {@code match.path_prefix}
     * @param anchorName the anchor, or {@code null} for none
     * @param asset      the asset block
     * @param methods    the {@code match.methods}
     * @return the declaration
     */
    public RouteConfig assetRoute(String id, String prefix, @Nullable String anchorName, AssetConfig asset,
            HttpMethod... methods) {
        return RouteConfig.builder()
                .id(id)
                .anchor(anchorName)
                .match(match(prefix, methods))
                .asset(asset)
                .build();
    }

    /**
     * An asset block serving a directory.
     *
     * @param root the directory
     * @return the asset block
     */
    public AssetConfig directoryAsset(String root) {
        return AssetConfig.builder().source(AssetConfig.Source.DIRECTORY).directory(root).build();
    }

    /**
     * A token-validation issuer named after itself.
     *
     * @param name the issuer name, also the host of its {@code issuer} URL
     * @param jwks the JWKS block
     * @return the issuer
     */
    public IssuerConfig issuer(String name, IssuerConfig.Jwks jwks) {
        return IssuerConfig.builder().name(name).issuer("https://" + name + ".example").jwks(jwks).build();
    }

    /**
     * A generated issuer name, usable as a host label.
     *
     * @return the name
     */
    public String issuerName() {
        return Generators.letterStrings(4, 10).next().toLowerCase(Locale.ROOT);
    }
}
