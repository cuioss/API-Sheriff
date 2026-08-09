/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.auth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.context.AccessTokenRequest;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;

import jakarta.inject.Provider;
import org.jspecify.annotations.Nullable;

/**
 * Stage 4 — offline bearer-token validation, run after the per-route thorough checks.
 * <p>
 * The stage enforces the selected route's effective auth posture:
 * <ul>
 *   <li>{@code require: none} — pass through untouched;</li>
 *   <li>{@code require: bearer} — a {@code Bearer} token is extracted from {@code Authorization} and
 *       validated <strong>offline</strong> through the shared {@link TokenValidator} using a
 *       four-component {@link AccessTokenRequest} (token, headers, URI, method — URI and method are
 *       mandatory for DPoP binding). A missing token is 401 {@link EventType#TOKEN_MISSING}; an
 *       invalid / expired / tampered token is 401 {@link EventType#TOKEN_INVALID}; both carry
 *       {@code WWW-Authenticate: Bearer}. A valid token lacking a required scope is 403
 *       {@link EventType#SCOPE_MISSING};</li>
 *   <li>{@code require: session} — dispatched to the {@link SessionAuthenticationStage} stage-4
 *       runtime (D4): the opaque session cookie is resolved to a live session, the mediated token is
 *       injected as the upstream {@code Authorization: Bearer}, {@code required_scopes} are enforced
 *       against the mediated token (403 {@link EventType#SCOPE_MISSING}), and an unauthenticated
 *       request is redirected into the auth-code flow (navigation) or challenged 401
 *       {@code application/problem+json} (everything else). Bearer and session stay separate
 *       mechanisms — the bearer-validation logic above is untouched.</li>
 * </ul>
 * The upstream is never contacted on any authentication or authorization rejection.
 * <p>
 * The session runtime is an optional collaborator: it is wired only when the gateway serves a BFF
 * variant. A {@code require: session} route reaching this stage without a wired session runtime is a
 * boot-configuration error surfaced as an {@link IllegalStateException} rather than served.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class AuthenticationStage {

    private static final String BEARER_PREFIX = "Bearer ";

    private final Provider<TokenValidator> tokenValidator;
    private final @Nullable SessionAuthenticationStage sessionStage;

    /**
     * Assembles the stage for a gateway serving no BFF variant — {@code require: session} is
     * unsupported (a session route reaching this stage is a boot-configuration error).
     *
     * @param tokenValidator a lazy provider of the single shared validator built from the
     *                       {@code token_validation} config. The validator is resolved via
     *                       {@link Provider#get()} only at the first actual bearer validation, so a
     *                       gateway serving only {@code require: none} routes (with no
     *                       {@code token_validation} block) never triggers the validator producer and
     *                       therefore never fails boot for a missing validation config.
     */
    public AuthenticationStage(Provider<TokenValidator> tokenValidator) {
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
        this.sessionStage = null;
    }

    /**
     * Assembles the stage with the {@code require: session} stage-4 runtime (D4) wired for a gateway
     * serving a BFF variant.
     *
     * @param tokenValidator the lazy bearer-validator provider (see the single-argument constructor)
     * @param sessionStage   the session runtime a {@code require: session} route is dispatched to
     */
    public AuthenticationStage(Provider<TokenValidator> tokenValidator, SessionAuthenticationStage sessionStage) {
        this.tokenValidator = Objects.requireNonNull(tokenValidator, "tokenValidator");
        this.sessionStage = Objects.requireNonNull(sessionStage, "sessionStage");
    }

    /**
     * Enforces the selected route's auth posture.
     *
     * @param request the in-flight request context; its route must be selected (stage 2)
     * @throws GatewayException on a missing / invalid token (401) or a missing scope (403)
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        RouteRuntime route = requireSelectedRoute(request);
        AuthConfig auth = route.getEffectiveAuth();
        // The `case null` label is load-bearing, not defensive: it makes this an ENHANCED switch,
        // which javac is required to check for exhaustiveness. Without it a constant-only switch
        // statement is a legacy switch — a fourth Require constant would compile clean and fall
        // through silently, leaving the posture unenforced while the route still reports itself
        // AUTHENTICATED. `require` is non-null by AuthConfig's canonical constructor, so this arm
        // is unreachable; its job is to make the omission a compile error rather than a bypass.
        switch (auth.require()) {
            case NONE -> {
                // Anonymous surface: nothing to enforce.
            }
            case BEARER -> validateBearer(request, auth, route);
            case SESSION -> requireSessionStage(route).process(request);
            case null -> throw new IllegalStateException(
                    "Route " + route.getId() + " reached authentication with a null auth posture");
        }
    }

    private SessionAuthenticationStage requireSessionStage(RouteRuntime route) {
        if (sessionStage == null) {
            throw new IllegalStateException("Route " + route.getId()
                    + " requires session authentication but no session runtime is wired");
        }
        return sessionStage;
    }

    private void validateBearer(PipelineRequest request, AuthConfig auth, RouteRuntime route) {
        String token = extractBearerToken(request)
                .orElseThrow(() -> unauthorized(request, EventType.TOKEN_MISSING, "No bearer token presented"));

        AccessTokenContent content;
        try {
            content = tokenValidator.get().createAccessToken(new AccessTokenRequest(
                    token, request.headers(), requireCanonicalPath(request), request.method().name()));
        } catch (TokenValidationException validationFailure) {
            throw unauthorizedFromValidation(request, validationFailure);
        }

        List<String> requiredScopes = auth.requiredScopes();
        if (!requiredScopes.isEmpty() && !content.providesScopes(requiredScopes)) {
            throw new GatewayException(EventType.SCOPE_MISSING,
                    "Token missing a required scope for route " + route.getId());
        }
    }

    private static Optional<String> extractBearerToken(PipelineRequest request) {
        return request.firstHeader("Authorization")
                .filter(value -> value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length()))
                .map(value -> value.substring(BEARER_PREFIX.length()).strip())
                .filter(token -> !token.isEmpty());
    }

    private static GatewayException unauthorized(PipelineRequest request, EventType eventType, String detail) {
        request.responseHeaders().put("WWW-Authenticate", "Bearer");
        return new GatewayException(eventType, detail);
    }

    private static GatewayException unauthorizedFromValidation(PipelineRequest request,
            TokenValidationException validationFailure) {
        request.responseHeaders().put("WWW-Authenticate", "Bearer");
        return new GatewayException(EventType.TOKEN_INVALID, "Bearer token rejected by validation", validationFailure);
    }

    private static RouteRuntime requireSelectedRoute(PipelineRequest request) {
        RouteRuntime route = request.selectedRoute();
        if (route == null) {
            throw new IllegalStateException("Authentication requires the route selected at stage 2");
        }
        return route;
    }

    private static String requireCanonicalPath(PipelineRequest request) {
        String canonicalPath = request.canonicalPath();
        if (canonicalPath == null) {
            throw new IllegalStateException("Authentication requires the canonical path resolved at stage 1");
        }
        return canonicalPath;
    }
}
