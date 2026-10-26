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

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.ENDPOINT_ROUTES_POINTER;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.effectiveProtocol;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.renderForMessage;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import de.cuioss.sheriff.gateway.asset.DirectoryAssetSource;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RedirectConfig;
import de.cuioss.sheriff.gateway.config.model.ResolvedTopology;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.http.LocationPathReview;
import de.cuioss.tools.logging.CuiLogger;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s that judge a route's terminal action: whether it resolves
 * exactly one of upstream, asset or redirect, whether an asset action is well-sourced, whether a
 * redirect target survives the open-redirect review, and whether the terminal action relays the
 * upstream response headers a {@code keep_identity_headers} key would govern.
 * <p>
 * The terminal-action rule ({@link #validateTerminalAction}, ADR-0014 and its Amendment A1) holds
 * every route to exactly one of upstream, asset or redirect, holds the anchor type and the terminal
 * action to the consistency matrix, requires the source-specific field of a declared asset action,
 * and reviews a redirect's {@code location} for open-redirect spellings at boot — a redirect is
 * written verbatim at request time, so this review is the only place its target is ever judged. It
 * refuses {@code keep_query} together with {@code allow_external}, the pair that would hand the
 * inbound query to a foreign origin, and emits
 * {@link ConfigLogMessages.WARN#REDIRECT_EXTERNAL_TARGET_ALLOWED} for a route that opts into an
 * external target.
 * <p>
 * The asset file-name rule ({@link #validateAssetIndexAndFallback}, AS-12) confines
 * {@code asset.index} and {@code asset.fallback} to a {@code source: directory} action and to a single
 * file-name segment.
 * <p>
 * The identity-header target rule ({@link #validateKeepIdentityHeadersTarget}) refuses
 * {@code keep_identity_headers} on a redirect, a directory-asset and a WebSocket route, none of which
 * relays an upstream response header for the key to govern.
 * <p>
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never
 * fails fast. Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class TerminalActionRules {

    private static final CuiLogger LOGGER = new CuiLogger(TerminalActionRules.class);

    /** The only schemes an {@code allow_external} redirect location may carry. */
    private static final Set<String> EXTERNAL_REDIRECT_SCHEMES = Set.of("http", "https");

    /**
     * Rule: the terminal-action / anchor-type consistency matrix (ADR-0014 and its Amendment A1).
     * A route resolves exactly one terminal action — the endpoint upstream, an {@code asset}, or a
     * {@code redirect}.
     * <ul>
     *   <li><strong>Exclusivity.</strong> A {@code redirect} declared together with an
     *       {@code asset} or an {@code upstream} block is refused: either block would be silently
     *       ignored.</li>
     *   <li><strong>Anchor matrix.</strong> A route whose resolving anchor is {@code type: asset}
     *       must declare an {@code asset} or a {@code redirect} action; an {@code asset} block on a
     *       route under a {@code proxy} / {@code bff} anchor (or with no anchor) is refused. A
     *       {@code redirect} is admitted under every anchor type, since it serves nothing the anchor
     *       type governs.</li>
     *   <li><strong>Asset source.</strong> For a declared asset action, the source-specific field
     *       must be present and, for a {@code source: upstream} action, its topology alias must
     *       resolve.</li>
     *   <li><strong>Redirect.</strong> A declared redirect action must ride the {@code http}
     *       protocol and pass the open-redirect review of its {@code location} (see
     *       {@link #validateRedirectAction}).</li>
     * </ul>
     * Every violation collects into the shared list; the rule never fails fast.
     *
     * @param gateway   the bound gateway document, whose anchors resolve each route's anchor type
     * @param endpoints the enabled endpoints, whose every route's terminal action is judged
     * @param topology  the resolved topology a {@code source: upstream} asset alias must resolve in
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateTerminalAction(GatewayConfig gateway, List<EndpointConfig> endpoints,
            ResolvedTopology topology, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                validateRouteTerminalAction(gateway, endpoint, route, topology, errors);
            }
        }
    }

    /**
     * The per-route half of the terminal-action rule (ADR-0014 and its Amendment A1): redirect
     * exclusivity, the anchor-type / terminal-action consistency check, the source-field check for a
     * declared asset action, and the redirect review for a declared redirect action. Every violation
     * collects into the shared list; the check never fails fast.
     */
    private static void validateRouteTerminalAction(GatewayConfig gateway, EndpointConfig endpoint,
            RouteConfig route, ResolvedTopology topology, List<ConfigError> errors) {
        AnchorConfig anchor = ScopeChain.of(gateway, endpoint, route).anchor();
        boolean assetAnchor = anchor != null && anchor.type() == AnchorType.ASSET;
        AssetConfig asset = route.asset();
        RedirectConfig redirect = route.redirect();
        if (redirect != null) {
            validateRedirectExclusivity(endpoint, route, errors);
        }
        if (assetAnchor && asset == null && redirect == null) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' resolves to asset anchor '%s' but declares no asset terminal action and no redirect terminal action"
                            .formatted(route.id(), anchor.name())));
        } else if (!assetAnchor && asset != null) {
            String context = anchor == null
                    ? "the route is unanchored"
                    : "its anchor '%s' is type '%s'".formatted(anchor.name(),
                    anchor.type().name().toLowerCase(Locale.ROOT));
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' declares an asset terminal action but %s; an asset action requires an asset-type anchor"
                            .formatted(route.id(), context)));
        }
        if (asset != null) {
            validateAssetSource(endpoint, route, asset, topology, errors);
        }
        if (redirect != null) {
            validateRedirectAction(endpoint, route, redirect, errors);
        }
    }

    /**
     * Refuses a {@code redirect} action declared together with another terminal action. A route
     * resolves exactly one; accepting the pair would silently drop one of the two blocks.
     */
    private static void validateRedirectExclusivity(EndpointConfig endpoint, RouteConfig route,
            List<ConfigError> errors) {
        if (route.asset() != null) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' declares both a redirect and an asset terminal action; a route resolves exactly one of upstream, asset or redirect"
                            .formatted(route.id())));
        }
        if (route.upstream() != null) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' declares both a redirect terminal action and an upstream block; a redirect contacts no upstream, so the upstream settings would be silently ignored"
                            .formatted(route.id())));
        }
    }

    /**
     * The redirect half of the terminal-action rule (ADR-0014 Amendment A1).
     * <p>
     * <strong>Protocol.</strong> A redirect is answered with a plain HTTP status and
     * {@code Location}, which a gRPC, WebSocket or GraphQL client cannot follow — so a redirect on
     * any protocol other than {@code http} is refused rather than silently ignored.
     * <p>
     * <strong>Open-redirect review.</strong> {@code location} is written verbatim into the
     * {@code Location} header, so it is reviewed once, here, and never at request time. Every
     * refused spelling is one a browser or an intermediary resolves to a <em>different origin</em>
     * than a reader of the configuration would expect:
     * <ul>
     *   <li>whatever the form, the value must be non-empty printable ASCII — no whitespace, no
     *       control character (a CR/LF would forge a response header) — and must not carry a
     *       {@code #} when {@code keep_query} is set, since a query appended after a fragment never
     *       reaches the server;</li>
     *   <li>a <strong>gateway path</strong> must pass {@link #gatewayPathRefusal}, which delegates to
     *       the shared {@link LocationPathReview}: exactly one leading {@code /} ({@code //host} is
     *       scheme-relative and names another origin), no {@code \} (browsers read {@code /\host} as
     *       {@code //host}), no percent-encoded {@code /} or {@code \} ({@code %2F}, {@code %5C}, in
     *       either case), no dot-segment ({@code .} or {@code ..}, including their {@code %2E}
     *       spellings) in its path, and nothing the {@code cui-http} {@code URL_PATH} pipeline
     *       refuses — double encoding, escaping traversal, null bytes, control characters and the
     *       1024-character length cap;</li>
     *   <li>anything else is refused unless the route sets {@code allow_external: true}, which
     *       additionally admits an absolute {@code http}/{@code https} URI with a non-empty host and
     *       no user-info. Every other scheme ({@code javascript:}, {@code data:}, …) stays refused.
     *       Opting in emits {@link ConfigLogMessages.WARN#REDIRECT_EXTERNAL_TARGET_ALLOWED}, naming
     *       the route but never the location.</li>
     * </ul>
     * Refusal messages echo the value through {@link RuleSupport#renderForMessage}, so a hostile value cannot
     * forge boot log lines (CWE-117).
     * <p>
     * <strong>Query hand-off.</strong> {@code keep_query} together with {@code allow_external} is
     * refused as a pair. Each is defensible alone — {@code keep_query} carries the inbound query
     * across a <em>same-origin</em> move, {@code allow_external} points a fixed, reviewed target at
     * another origin — but together they opt every inbound query parameter out of the gateway
     * origin: a request to {@code /go?code=...&state=...} produces a {@code Location} that plants
     * those values in the third party's access log, {@code Referer} chain and page analytics
     * (CWE-201 / CWE-598). Unlike the target itself, the query is <em>attacker-supplied</em>, so no
     * boot review can vet it; the pair is therefore refused rather than warned about, and an
     * operator who wants one of the two drops the other. The refusal names the route and the two
     * keys, never the location.
     */
    private static void validateRedirectAction(EndpointConfig endpoint, RouteConfig route, RedirectConfig redirect,
            List<ConfigError> errors) {
        Protocol protocol = effectiveProtocol(route);
        if (protocol != Protocol.HTTP) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "route '%s' declares a redirect terminal action but its protocol is '%s'; a redirect is answered over http only"
                            .formatted(route.id(), protocol.name().toLowerCase(Locale.ROOT))));
        }
        if (redirect.keepQuery() && redirect.allowExternal()) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    ("route '%s' declares both keep_query and allow_external; that hands the inbound query "
                            + "string to a foreign origin, where it lands in the target's access log and Referer "
                            + "chain — drop keep_query to confine the query to this gateway, or drop "
                            + "allow_external to keep the redirect same-origin")
                            .formatted(route.id())));
        }
        redirectLocationRefusal(redirect).ifPresent(reason -> errors.add(
                new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                        "route '%s' redirect location '%s' is refused: %s"
                                .formatted(route.id(), renderForMessage(redirect.location()), reason))));
        if (redirect.allowExternal()) {
            LOGGER.warn(ConfigLogMessages.WARN.REDIRECT_EXTERNAL_TARGET_ALLOWED, route.id());
        }
    }

    /**
     * The open-redirect review of a redirect {@code location}; see {@link #validateRedirectAction}
     * for the rules and why each exists.
     *
     * @return the refusal reason, or empty when the location is admitted
     */
    private static Optional<String> redirectLocationRefusal(RedirectConfig redirect) {
        String location = redirect.location();
        if (location.isEmpty()) {
            return Optional.of("the location must not be empty");
        }
        if (!isPrintableAscii(location)) {
            return Optional.of("the location must be printable ASCII without whitespace or control characters");
        }
        if (redirect.keepQuery() && location.indexOf('#') >= 0) {
            return Optional.of("keep_query appends the request query, which a '#' fragment would swallow");
        }
        if (location.startsWith("/")) {
            return gatewayPathRefusal(location);
        }
        if (!redirect.allowExternal()) {
            return Optional.of("without allow_external the location must be a gateway path starting with exactly one '/'");
        }
        return externalLocationRefusal(location);
    }

    private static boolean isPrintableAscii(String value) {
        return value.chars().allMatch(character -> character > 0x20 && character < 0x7F);
    }

    /**
     * The gateway-path half of the location review. The refusal set itself is not this class's own:
     * the path portion is handed to {@link LocationPathReview#refusalReason(String)}, the single place
     * the gateway decides which {@code Location} path is dangerous, so this boot review and the
     * runtime {@code routing.LocationRewriter} mapping of an <em>upstream</em> {@code Location} cannot
     * disagree (GW-13). That review covers the scheme-relative {@code //} prefix, the percent-encoded
     * separators, the dot segments, and everything the {@code cui-http} {@code URL_PATH} pipeline owns
     * — double encoding above all, which no hand-written substring test here ever caught
     * ({@code /%252F%252Fevil.example}).
     * <p>
     * Two tests stay here rather than moving into that review, because a <em>configured</em> value is
     * scoped more widely than an upstream-supplied one. Both are applied to the whole location, query
     * and fragment included:
     * <ul>
     *   <li>a <strong>literal backslash</strong> anywhere. The shared review sees the path only, where
     *       the {@code cui-http} pipeline already refuses it as {@code INVALID_CHARACTER}; this arm is
     *       what extends the refusal past the {@code ?}.</li>
     *   <li>a <strong>percent-encoded separator</strong> anywhere, via
     *       {@link LocationPathReview#carriesEncodedSeparator(String)} — the same test the shared
     *       review applies to the path, reused rather than restated so the two cannot drift.</li>
     * </ul>
     * The wider scope is deliberate and costs a configured value nothing: an operator writing a fixed
     * redirect target has no reason to encode a separator in its query, whereas an upstream redirect
     * routinely carries one (which is why {@code LocationRewriter} scopes the same test to the path).
     * A separator after the {@code ?} cannot form an authority, so this is a tightening for
     * operator-authored values, not a security necessity.
     */
    private static Optional<String> gatewayPathRefusal(String location) {
        if (location.indexOf('\\') >= 0) {
            return Optional.of("a backslash is read as '/' by browsers");
        }
        if (LocationPathReview.carriesEncodedSeparator(location)) {
            return Optional.of("a percent-encoded '/' or '\\' is decoded by intermediaries");
        }
        return LocationPathReview.refusalReason(location.substring(0, firstIndexOf(location, '?', '#')));
    }

    /** The index of the first occurrence of either character, or the value's length when neither occurs. */
    private static int firstIndexOf(String value, char first, char second) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == first || current == second) {
                return index;
            }
        }
        return value.length();
    }

    /**
     * The external half of the location review, reached only under {@code allow_external}: an
     * absolute {@code http}/{@code https} URI with a non-empty host and no user-info.
     */
    private static Optional<String> externalLocationRefusal(String location) {
        URI uri;
        try {
            uri = new URI(location);
        } catch (URISyntaxException _) {
            return Optional.of("the location is neither a gateway path nor a well-formed absolute URI");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !EXTERNAL_REDIRECT_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return Optional.of("an external location must use the http or https scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return Optional.of("an external location must name a non-empty host");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of("an external location must not carry user-info, which disguises the real host");
        }
        return Optional.empty();
    }

    /**
     * The source-field half of the terminal-action rule (ADR-0014): a {@code source: directory}
     * action requires a {@code directory} root; a {@code source: upstream} action requires an
     * {@code upstream} topology alias that resolves.
     */
    // NOSONAR java:S6916 - the rule suggests replacing each case's if with a `when` guard, but
    // guards attach only to PATTERN labels; `case DIRECTORY when ...` on an enum CONSTANT label
    // is a compile error (verified against javac --release 25). Restructuring further would move
    // alias-resolvability error reporting out of this method, which ADR-9 forbids.
    @SuppressWarnings("java:S6916")
    private static void validateAssetSource(EndpointConfig endpoint, RouteConfig route, AssetConfig asset,
            ResolvedTopology topology, List<ConfigError> errors) {
        switch (asset.source()) {
            case DIRECTORY -> {
                if (asset.directory() == null) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "asset route '%s' declares source: directory but no directory root".formatted(route.id())));
                }
            }
            case UPSTREAM -> {
                String alias = asset.upstream();
                if (alias == null) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "asset route '%s' declares source: upstream but no upstream alias".formatted(route.id())));
                } else if (topology.lookup(alias).isEmpty()) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "asset route '%s' upstream alias '%s' does not resolve in the topology"
                                    .formatted(route.id(), alias)));
                }
            }
        }
    }

    /**
     * Rule (AS-12): {@code asset.index} and {@code asset.fallback} apply to a {@code source: directory}
     * action only, and each value must be a single file-name segment. On {@code source: upstream} the
     * keys would be silently ignored, so they are refused. A value carrying {@code /}, {@code \}, a
     * control character, or naming {@code .} / {@code ..} — or an empty value — is refused, because
     * the source resolves it beneath the requested directory (index) or the root (fallback), and a
     * path-shaped value would widen what the confinement is asked to serve. The segment predicate is
     * the one {@link DirectoryAssetSource} itself enforces, so boot and runtime cannot disagree. The
     * offending value is never echoed. Every violation collects into the shared list (ADR-0009).
     *
     * @param endpoints the enabled endpoints, whose every route's {@code asset} block is judged
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateAssetIndexAndFallback(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                AssetConfig asset = route.asset();
                if (asset != null) {
                    validateAssetFileName(endpoint, route, asset, "index", asset.index(), errors);
                    validateAssetFileName(endpoint, route, asset, "fallback", asset.fallback(), errors);
                }
            }
        }
    }

    /**
     * Rule: {@code keep_identity_headers} is refused, whatever its declared value, on a route whose
     * terminal action relays no upstream response header. The key governs the paths that do — the
     * HTTP relay, the gRPC relay's leading headers and trailers, and a {@code source: upstream} asset
     * action — and would be silently ignored on the three that do not:
     * <ul>
     *   <li>a <strong>redirect</strong> route, answered at the gateway without contacting any
     *       upstream;</li>
     *   <li>a <strong>{@code source: directory}</strong> asset route, served from the local
     *       filesystem, which proposes no upstream header;</li>
     *   <li>a <strong>{@code protocol: websocket}</strong> route, whose upgrade hands the connection to
     *       the opaque relay, which forwards no upstream response header.</li>
     * </ul>
     * A declared {@code false} is refused as well: it states an intent the route cannot act on. One
     * error is collected per offending route, naming the route; the rule never fails fast
     * (ADR-0009).
     *
     * @param endpoints the enabled endpoints, whose every route's {@code keep_identity_headers} is judged
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateKeepIdentityHeadersTarget(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (route.keepIdentityHeaders() != null) {
                    identityHeadersIgnoredReason(route).ifPresent(reason -> errors.add(
                            new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                                    "route '%s' declares keep_identity_headers but %s, which relays no upstream response header; the key would be silently ignored"
                                            .formatted(route.id(), reason))));
                }
            }
        }
    }

    /**
     * Why a route relays no upstream response header, or empty when it relays them.
     *
     * @return the route kind that makes {@code keep_identity_headers} meaningless, or empty
     */
    private static Optional<String> identityHeadersIgnoredReason(RouteConfig route) {
        if (route.redirect() != null) {
            return Optional.of("it is a redirect route");
        }
        AssetConfig asset = route.asset();
        if (asset != null && asset.source() == AssetConfig.Source.DIRECTORY) {
            return Optional.of("it is an asset route with source: directory");
        }
        if (effectiveProtocol(route) == Protocol.WEBSOCKET) {
            return Optional.of("it is a protocol: websocket route");
        }
        return Optional.empty();
    }

    private static void validateAssetFileName(EndpointConfig endpoint, RouteConfig route, AssetConfig asset,
            String key, @Nullable String value, List<ConfigError> errors) {
        if (value == null) {
            return;
        }
        if (asset.source() != AssetConfig.Source.DIRECTORY) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "asset route '%s' declares asset.%s but its source is 'upstream'; index and fallback apply to source: directory only"
                            .formatted(route.id(), key)));
        } else if (!DirectoryAssetSource.isSingleFileName(value)) {
            errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                    "asset route '%s' asset.%s must be a single file-name segment (not empty, no '/', no '\\', no '.' or '..', no control characters)"
                            .formatted(route.id(), key)));
        }
    }
}
