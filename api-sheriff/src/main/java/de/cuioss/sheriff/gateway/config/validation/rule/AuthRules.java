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
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.lacksConfiguredIssuer;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.requireOf;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.PolicyBlock;
import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s that judge the {@code auth} posture a route resolves and the
 * blocks that posture needs behind it.
 * <p>
 * The route-auth resolvability rule ({@link #validateRouteAuthResolvable}, ADR-0007) requires every
 * enabled route to resolve an auth posture through the {@code route → endpoint → declared-anchor} chain
 * the route table uses, so a route no scope gives an {@code auth} block is refused here rather than
 * escaping to route-table assembly. The effective-auth rule ({@link #validateEffectiveAuth}) holds the
 * resolved postures to their backing blocks: an effective {@code require: bearer} route needs a
 * {@code token_validation} issuer, an effective {@code require: session} route an {@code oidc} block.
 * <p>
 * The session-fallback rule ({@link #validateSessionFallback}, ADR-0055) refuses every declared
 * {@code auth} block — anchor, endpoint or route — that sets {@code session_fallback: true} on a posture
 * other than {@code require: bearer}, or on a gateway that declares no {@code oidc} block. The
 * endpoint-scopes rule ({@link #validateEndpointScopesNeedAuthentication}) refuses an endpoint whose
 * {@code scopes} would act on no route, because every one of its routes resolves {@code require: none}.
 * <p>
 * The postures are resolved through {@link PolicyBlock#AUTH}, the descriptor the route table resolves
 * them with, so validation and runtime cannot disagree about which {@code auth} block a route carries.
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never fails
 * fast (ADR-0009). Stateless, and free of framework dependencies as the pre-boot rule requires of this
 * package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class AuthRules {

    private static final String ENDPOINT_AUTH_POINTER = "/endpoint/auth";
    private static final String ENDPOINT_SCOPES_POINTER = "/endpoint/scopes";

    /**
     * The fixed detail of the refusal of {@code session_fallback: true} on a posture other than
     * {@code require: bearer}. Fixed text, so the boot log never echoes a configured scalar.
     */
    private static final String SESSION_FALLBACK_NOT_BEARER_DETAIL =
            "declares auth.session_fallback: true on a posture other than require: bearer; session_fallback only "
                    + "adds a session branch to a bearer posture for requests without an Authorization header — "
                    + "declare require: bearer, or drop session_fallback";

    /**
     * The fixed detail of the refusal of {@code session_fallback: true} when the gateway declares no
     * {@code oidc} block. Fixed text, so the boot log never echoes a configured scalar.
     */
    private static final String SESSION_FALLBACK_NO_OIDC_DETAIL =
            "declares auth.session_fallback: true but the gateway declares no oidc block; the session branch "
                    + "authenticates through the BFF runtime exactly as a require: session route does — declare "
                    + "an oidc block, or drop session_fallback";

    /**
     * Rule: every enabled route must resolve an auth posture through the same
     * {@code route → endpoint → declared-anchor} chain the route table uses
     * ({@link PolicyBlock#AUTH}); a route for which none of the three supplies an
     * {@code auth} block fails the boot (ADR-0007). Checking per route — rather than
     * once per endpoint — closes two gaps of a per-endpoint anchor check: it no longer
     * falsely rejects an endpoint whose every route supplies its own auth, and it
     * catches a route that overrides to a different, auth-less anchor via
     * {@code route.anchor} (or omits auth entirely) even when the endpoint-level anchor
     * would have satisfied a per-endpoint check. This surfaces the failure here, in the
     * all-violations pass (ADR-0009), instead of letting it escape to a
     * {@code RouteTableException} thrown during route-table assembly.
     *
     * @param gateway   the bound gateway document, whose declared anchors complete the chain
     * @param endpoints the enabled endpoints whose routes are judged
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateRouteAuthResolvable(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (PolicyBlock.AUTH.declared(ScopeChain.of(gateway, endpoint, route)) == null) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            "route '%s' has no resolvable auth: neither the route, its endpoint '%s', nor a declared anchor provides an auth posture"
                                    .formatted(route.id(), endpoint.id())));
                }
            }
        }
    }

    /**
     * Rule: every effective auth posture must have its backing block. An effective
     * {@code require: bearer} route needs a {@code token_validation} block declaring at least one issuer
     * ({@link RuleSupport#lacksConfiguredIssuer}); an effective {@code require: session} route needs an
     * {@code oidc} block.
     * <p>
     * The posture is resolved per enabled route through {@link PolicyBlock#AUTH}, the anchor-aware
     * cascade the route table uses, so a posture an anchor default supplies is held to its backing
     * block exactly as a declared one is. Each missing block is reported once, on its own pointer in
     * {@code gateway.yaml}, however many routes resolve the posture. Every violation collects into the
     * shared list; the rule never fails fast (ADR-0009).
     *
     * @param gateway   the bound gateway document, whose backing blocks are checked
     * @param endpoints the enabled endpoints whose routes' effective postures are collected
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateEffectiveAuth(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        Set<Require> requires = EnumSet.noneOf(Require.class);
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                requires.add(requireOf(ScopeChain.of(gateway, endpoint, route)));
            }
        }
        if (requires.contains(Require.BEARER) && lacksConfiguredIssuer(gateway)) {
            errors.add(new ConfigError(GATEWAY_FILE, "/token_validation",
                    "effective auth 'bearer' requires token_validation with at least one issuer"));
        }
        if (requires.contains(Require.SESSION) && gateway.oidc() == null) {
            errors.add(new ConfigError(GATEWAY_FILE, "/oidc", "effective auth 'session' requires an oidc block"));
        }
    }

    /**
     * Rule: every declared {@code auth} block that sets {@code session_fallback: true} — on an anchor,
     * on an enabled endpoint, or on a route of an enabled endpoint — must declare
     * {@code require: bearer}, and the gateway must declare an {@code oidc} block.
     * <p>
     * The key splits a bearer posture into two branches selected by {@code Authorization}-header
     * presence; on {@code require: none} or {@code require: session} there is no bearer branch to
     * split, so it would parse and act nowhere. Its session branch authenticates through the BFF
     * runtime, which exists only when {@code oidc} is declared. The two refusals are independent — a
     * block violating both yields both — and each names the owning anchor, endpoint or route with
     * its JSON pointer and a fixed detail text, never a configured scalar value.
     * <p>
     * Each declared block is judged where it is declared rather than through the effective-auth
     * cascade, so an anchor default that no route inherits is still refused. The anchor floor
     * ({@link AnchorRules#validateAnchorAuthFloor}) compares {@code require} only and is not consulted here.
     * Every violation collects into the shared list; the rule never fails fast (ADR-0009).
     *
     * @param gateway   the bound gateway document, whose anchors and {@code oidc} block are read
     * @param endpoints the enabled endpoints whose own and route {@code auth} blocks are judged
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateSessionFallback(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        boolean oidcDeclared = gateway.oidc() != null;
        for (AnchorConfig anchor : gateway.anchors().values()) {
            checkSessionFallbackBlock(anchor.auth(), oidcDeclared, GATEWAY_FILE,
                    ANCHORS_POINTER + "/" + anchor.name() + "/auth", "anchor '%s'".formatted(anchor.name()), errors);
        }
        for (EndpointConfig endpoint : endpoints) {
            checkSessionFallbackBlock(endpoint.auth(), oidcDeclared, endpointFile(endpoint), ENDPOINT_AUTH_POINTER,
                    "endpoint '%s'".formatted(endpoint.id()), errors);
            for (RouteConfig route : endpoint.routes()) {
                checkSessionFallbackBlock(route.auth(), oidcDeclared, endpointFile(endpoint),
                        ENDPOINT_ROUTES_POINTER, "route '%s'".formatted(route.id()), errors);
            }
        }
    }

    /**
     * Judges one declared {@code auth} block for {@link #validateSessionFallback}: a no-op when the
     * block is absent or does not set {@code session_fallback: true}.
     */
    private static void checkSessionFallbackBlock(@Nullable AuthConfig auth, boolean oidcDeclared, String file,
            String pointer, String owner, List<ConfigError> errors) {
        if (auth == null || !auth.effectiveSessionFallback()) {
            return;
        }
        if (auth.require() != Require.BEARER) {
            errors.add(new ConfigError(file, pointer, owner + " " + SESSION_FALLBACK_NOT_BEARER_DETAIL));
        }
        if (!oidcDeclared) {
            errors.add(new ConfigError(file, pointer, owner + " " + SESSION_FALLBACK_NO_OIDC_DETAIL));
        }
    }

    /**
     * Rule: an endpoint declaring a non-empty {@code scopes} list must have at least one route whose
     * effective auth is not {@code require: none}.
     * <p>
     * {@code endpoint.scopes} is consumed only by authenticated routes. On an endpoint whose every route
     * resolves {@code require: none} the key would parse and act nowhere, so an operator reading it
     * would believe a scope requirement is in force that no request ever meets. The boot refuses it
     * rather than letting the declaration stand inert. Only enabled endpoints reach this rule. Every
     * violation collects into the shared list; the rule never fails fast (ADR-0009).
     *
     * @param gateway   the bound gateway document, whose declared anchors complete each route's chain
     * @param endpoints the enabled endpoints whose {@code scopes} are judged
     * @param errors    the shared violation list every refusal is appended to
     */
    public static void validateEndpointScopesNeedAuthentication(GatewayConfig gateway,
            List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            if (endpoint.scopes().isEmpty()) {
                continue;
            }
            boolean allUnauthenticated = endpoint.routes().stream()
                    .allMatch(route -> requireOf(ScopeChain.of(gateway, endpoint, route)) == Require.NONE);
            if (allUnauthenticated) {
                errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_SCOPES_POINTER,
                        ("endpoint '%s' declares scopes but every one of its routes resolves require: none; "
                                + "scopes are consumed only by authenticated routes, so on an unauthenticated "
                                + "endpoint they would never act — remove scopes, or give a route of this "
                                + "endpoint a bearer or session posture")
                                .formatted(endpoint.id())));
            }
        }
    }
}
