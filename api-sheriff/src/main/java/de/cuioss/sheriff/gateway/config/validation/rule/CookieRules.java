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

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.ANCHORS_POINTER;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.ENDPOINT_ROUTES_POINTER;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.GATEWAY_FILE;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.renderForMessage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.config.PolicyBlock;
import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.WebSocketConfig;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import lombok.experimental.UtilityClass;

/**
 * The boot-time {@link ValidationRule}s that judge the {@code cookies} block: which browser cookies a
 * route forwards, which upstream {@code Set-Cookie} lines it relays, and the same-origin guard of a
 * cookie-forwarding route.
 * <p>
 * Seven rules, one per refusal:
 * <ul>
 *   <li>{@link #validateGatewayAllowAll} — {@code allow: all} is refused at the gateway scope;</li>
 *   <li>{@link #validateCookieNames} — a listed name must be an RFC 6265 cookie name;</li>
 *   <li>{@link #validateGatewayCookieNotListed} — a listed name must not be one of the gateway's own
 *       cookies;</li>
 *   <li>{@link #validateNoInertKeysOnNone} — {@code same_origin_check} and {@code additional_origins}
 *       are refused on a block whose {@code allow} is {@code none};</li>
 *   <li>{@link #validateAdditionalOrigins} — every {@code additional_origins} entry must be a serialized
 *       origin;</li>
 *   <li>{@link #validateNoCookiesOnTerminalRoute} — a route-scope block is refused on an asset or a
 *       redirect route;</li>
 *   <li>{@link #validateWebSocketCookieOrigins} — a {@code protocol: websocket} route whose effective
 *       block forwards any cookie must declare a non-empty {@code websocket.allowed_origins}.</li>
 * </ul>
 * A declared {@code allow: []} is refused by the bundled schema ({@code minItems: 1}), not here. Every
 * rule that judges the content of a block checks it at every scope {@link PolicyBlock#COOKIES}
 * declares — the gateway, each anchor, each enabled endpoint and each of its routes — so a block
 * declared at a scope a rule did not read cannot reach the runtime unrefused. Every rule appends file-
 * and pointer-annotated {@link ConfigError}s to the shared list and never fails fast (ADR-0009); a
 * configured value echoed into a message goes through {@link RuleSupport#renderForMessage}. Stateless,
 * and therefore safe for concurrent use. Free of framework dependencies as the pre-boot rule requires
 * of this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class CookieRules {

    private static final String COOKIES_KEY = "/" + PolicyBlock.COOKIES.yamlKey();

    private static final String ALLOW_KEY = "/allow";

    private static final String ADDITIONAL_ORIGINS_KEY = "/additional_origins";

    /**
     * A whole RFC 6265 cookie name: a non-empty RFC 9110 {@code token}, the character set RFC 6265
     * draws a cookie name from. It is the shared {@link RuleSupport#MEDIA_TYPE_TOKEN} class, reused
     * rather than restated.
     */
    private static final Pattern COOKIE_NAME_SHAPE = Pattern.compile(RuleSupport.MEDIA_TYPE_TOKEN);

    /** The schemes a serialized origin of a browser request carries. */
    private static final Set<String> ORIGIN_SCHEMES = Set.of("http", "https");

    private static final int MAX_PORT = 65_535;

    /**
     * Rule: a gateway-scope {@code cookies} block must not declare {@code allow: all}.
     * <p>
     * A gateway-scope block reaches every route of the gateway that declares none nearer, so
     * {@code all} there would hand every browser cookie of the gateway's origin to every upstream it
     * proxies to — the one posture the block exists to make a deliberate, per-upstream decision. The
     * same mode is accepted on an anchor, an endpoint and a route.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared list every violation is appended to
     */
    public static void validateGatewayAllowAll(GatewayConfig gateway, List<ConfigError> errors) {
        CookiesConfig cookies = gateway.cookies();
        if (cookies != null && cookies.allow() instanceof CookieAllow.All) {
            errors.add(new ConfigError(GATEWAY_FILE, COOKIES_KEY + ALLOW_KEY,
                    "the gateway-scope cookies block declares allow: all, which would forward every browser "
                            + "cookie to every upstream; declare allow: all on the anchor, endpoint or route "
                            + "whose upstream needs it, or list the cookie names here"));
        }
    }

    /**
     * Rule: every name a {@code cookies.allow} list declares must be an RFC 6265 cookie name — a
     * non-empty token, so no whitespace, no separator such as {@code ;}, {@code =} or {@code ,}, and no
     * control character.
     * <p>
     * A name outside that shape can never be a cookie a browser sends, so listing it admits nothing
     * while reading as if it admitted something.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateCookieNames(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (Declaration declaration : declarations(gateway, endpoints)) {
            for (String name : listedNames(declaration.cookies())) {
                if (!COOKIE_NAME_SHAPE.matcher(name).matches()) {
                    errors.add(new ConfigError(declaration.file(), declaration.pointer() + ALLOW_KEY,
                            ("%s lists the cookie name '%s' in cookies.allow, which is not an RFC 6265 cookie "
                                    + "name (a non-empty token without whitespace, separators or control "
                                    + "characters)").formatted(declaration.subject(), renderForMessage(name))));
                }
            }
        }
    }

    /**
     * Rule: no {@code cookies.allow} list may name one of the gateway's own cookies.
     * <p>
     * The gateway's own cookies — every name under {@value GatewayCookies#RESERVED_PREFIX} and the
     * configured session cookie, with its activity cookie in cookie mode ({@link GatewayCookies}) —
     * never reach an upstream under any mode. Listing one therefore admits nothing, and the boot refuses
     * it rather than let the block suggest that a session credential is handed on. The refusal names
     * the listed name.
     *
     * @param gateway   the bound gateway document, from which the gateway's own cookies are derived
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateGatewayCookieNotListed(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        GatewayCookies gatewayCookies = GatewayCookies.of(gateway);
        for (Declaration declaration : declarations(gateway, endpoints)) {
            for (String name : listedNames(declaration.cookies())) {
                if (gatewayCookies.matchesName(name)) {
                    errors.add(new ConfigError(declaration.file(), declaration.pointer() + ALLOW_KEY,
                            ("%s lists '%s' in cookies.allow, which is one of the gateway's own cookies; the "
                                    + "gateway's own cookies never reach an upstream, so remove it from the list")
                                    .formatted(declaration.subject(), renderForMessage(name))));
                }
            }
        }
    }

    /**
     * Rule: a {@code cookies} block whose {@code allow} is {@code none} must declare neither
     * {@code same_origin_check} nor {@code additional_origins}.
     * <p>
     * Both keys govern the same-origin guard of a route that forwards cookies. Under {@code none} no
     * cookie is forwarded, so the guard never runs and either key would parse and then act on nothing.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateNoInertKeysOnNone(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (Declaration declaration : declarations(gateway, endpoints)) {
            CookiesConfig cookies = declaration.cookies();
            if (cookies.forwardsAny()) {
                continue;
            }
            if (cookies.sameOriginCheck() != null) {
                errors.add(inertKey(declaration, "same_origin_check"));
            }
            if (cookies.additionalOrigins() != null) {
                errors.add(inertKey(declaration, "additional_origins"));
            }
        }
    }

    private static ConfigError inertKey(Declaration declaration, String key) {
        return new ConfigError(declaration.file(), declaration.pointer() + "/" + key,
                ("%s declares cookies.%s on a block whose allow is none; no cookie is forwarded under none, "
                        + "so the same-origin guard it configures never runs — remove the key, or forward "
                        + "cookies").formatted(declaration.subject(), key));
    }

    /**
     * Rule: every {@code cookies.additional_origins} entry must be a serialized origin — an
     * {@code http} or {@code https} scheme, a host and an optional port, and nothing else: no path
     * (not even {@code /}), no query, no fragment and no user-info.
     * <p>
     * An entry is compared exactly against the {@code Origin} a browser sends, which is always in that
     * serialized form, so any other shape could never match and would leave the operator believing an
     * origin is admitted when none is.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateAdditionalOrigins(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (Declaration declaration : declarations(gateway, endpoints)) {
            List<String> origins = declaration.cookies().additionalOrigins();
            if (origins == null) {
                continue;
            }
            for (String origin : origins) {
                if (!isSerializedOrigin(origin)) {
                    errors.add(new ConfigError(declaration.file(), declaration.pointer() + ADDITIONAL_ORIGINS_KEY,
                            ("%s lists '%s' in cookies.additional_origins, which is not a serialized origin; "
                                    + "write scheme, host and optional port only, e.g. https://app.example.com")
                                    .formatted(declaration.subject(), renderForMessage(origin))));
                }
            }
        }
    }

    /**
     * Whether a value is a serialized origin: it parses as a URI whose scheme is {@code http} or
     * {@code https}, which carries a host and at most a port in the range {@code 1..65535}, and which
     * re-serializes from those parts to exactly the value written — so a trailing {@code /}, a dangling
     * {@code :}, user-info, a path, a query or a fragment all fail.
     */
    private static boolean isSerializedOrigin(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException _) {
            return false;
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (scheme == null || !ORIGIN_SCHEMES.contains(scheme) || host == null || host.isEmpty()
                || port == 0 || port > MAX_PORT) {
            return false;
        }
        String serialized = scheme + "://" + host + (port < 0 ? "" : ":" + port);
        return serialized.equals(value);
    }

    /**
     * Rule: a route-scope {@code cookies} block is refused on an asset or a redirect route.
     * <p>
     * Such a route has no upstream to hand a cookie to or relay a {@code Set-Cookie} from, and it
     * always resolves {@code allow: none} whatever any scope declares, so a block declared on the route
     * itself would parse and then act on nothing. A broader block — endpoint, anchor or gateway — stays
     * accepted: it governs that scope's proxy routes.
     *
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateNoCookiesOnTerminalRoute(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            List<RouteConfig> routes = endpoint.routes();
            for (int index = 0; index < routes.size(); index++) {
                RouteConfig route = routes.get(index);
                if (route.cookies() != null && !route.isProxyRoute()) {
                    errors.add(new ConfigError(endpointFile(endpoint), routePointer(index),
                            ("route '%s' declares a cookies block but serves an asset or a redirect, which has "
                                    + "no upstream to forward a cookie to — remove the block")
                                    .formatted(route.id())));
                }
            }
        }
    }

    /**
     * Rule: a {@code protocol: websocket} route whose effective {@code cookies} block forwards any
     * cookie must declare a non-empty {@code websocket.allowed_origins}.
     * <p>
     * A WebSocket route carries no per-route same-origin check of its own: the upgrade is gated by the
     * exact-match {@code allowed_origins} allowlist instead, which is stricter (an absent
     * {@code Origin} is always refused). That gate enforces nothing while the list is empty, which only
     * a {@code require: none} route may boot with, so without this rule a cross-site page could open a
     * socket carrying the browser's forwarded cookies to the upstream. {@code same_origin_check} and
     * {@code additional_origins} do not act on a WebSocket route.
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateWebSocketCookieOrigins(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            List<RouteConfig> routes = endpoint.routes();
            for (int index = 0; index < routes.size(); index++) {
                RouteConfig route = routes.get(index);
                if (route.protocol() != Protocol.WEBSOCKET || !route.isProxyRoute()
                        || !PolicyBlock.COOKIES.resolve(ScopeChain.of(gateway, endpoint, route)).forwardsAny()) {
                    continue;
                }
                WebSocketConfig websocket = route.websocket();
                if (websocket == null || websocket.allowedOrigins().isEmpty()) {
                    errors.add(new ConfigError(endpointFile(endpoint),
                            ENDPOINT_ROUTES_POINTER + "/" + index + "/websocket",
                            ("websocket route '%s' forwards browser cookies to its upstream but declares no "
                                    + "websocket.allowed_origins; a cookie-forwarding upgrade is gated by that "
                                    + "allowlist alone, so declare the origins allowed to open the socket")
                                    .formatted(route.id())));
                }
            }
        }
    }

    private static List<String> listedNames(CookiesConfig cookies) {
        return cookies.allow() instanceof CookieAllow.Named(var names) ? names : List.of();
    }

    /**
     * Every declared {@code cookies} block, at every scope {@link PolicyBlock#COOKIES} declares, in
     * document order: the gateway's, each anchor's, then per enabled endpoint the endpoint's and each
     * route's.
     */
    private static List<Declaration> declarations(GatewayConfig gateway, List<EndpointConfig> endpoints) {
        List<Declaration> declarations = new ArrayList<>();
        CookiesConfig gatewayCookies = gateway.cookies();
        if (gatewayCookies != null) {
            declarations.add(new Declaration(GATEWAY_FILE, COOKIES_KEY, "the gateway", gatewayCookies));
        }
        for (AnchorConfig anchor : gateway.anchors().values()) {
            CookiesConfig anchorCookies = anchor.cookies();
            if (anchorCookies != null) {
                declarations.add(new Declaration(GATEWAY_FILE, ANCHORS_POINTER + "/" + anchor.name() + COOKIES_KEY,
                        "anchor '%s'".formatted(anchor.name()), anchorCookies));
            }
        }
        for (EndpointConfig endpoint : endpoints) {
            String file = endpointFile(endpoint);
            CookiesConfig endpointCookies = endpoint.cookies();
            if (endpointCookies != null) {
                declarations.add(new Declaration(file, "/endpoint" + COOKIES_KEY,
                        "endpoint '%s'".formatted(endpoint.id()), endpointCookies));
            }
            List<RouteConfig> routes = endpoint.routes();
            for (int index = 0; index < routes.size(); index++) {
                RouteConfig route = routes.get(index);
                CookiesConfig routeCookies = route.cookies();
                if (routeCookies != null) {
                    declarations.add(new Declaration(file, routePointer(index),
                            "route '%s'".formatted(route.id()), routeCookies));
                }
            }
        }
        return declarations;
    }

    private static String routePointer(int index) {
        return ENDPOINT_ROUTES_POINTER + "/" + index + COOKIES_KEY;
    }

    /**
     * One declared {@code cookies} block and where it was declared.
     *
     * @param file    the file a refusal is reported against
     * @param pointer the JSON pointer of the block
     * @param subject how a refusal names the declaring scope
     * @param cookies the declared block
     */
    private record Declaration(String file, String pointer, String subject, CookiesConfig cookies) {
    }
}
