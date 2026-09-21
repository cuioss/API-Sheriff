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
package de.cuioss.sheriff.gateway.bff.login;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;


import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.client.flow.CallbackHandler;
import de.cuioss.sheriff.token.client.flow.IssValidator;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.client.token.TokenValidationBridge;

/**
 * The gateway-side per-request scope seam over the unchanged {@code token-sheriff-client} engine
 * (ADR-0048).
 * <p>
 * The engine reads the OAuth {@code scope} parameter from exactly one place on both legs it drives:
 * {@link ClientConfiguration#getScopes()}. The authorization request builder reads it when it
 * renders the authorization URL, and {@link RefreshFlow} reads it when it posts the refresh grant.
 * There is no per-call scope argument. This seam therefore supplies the scope per request the only
 * way the engine admits: it asks the injected configuration factory for a
 * {@link ClientConfiguration} carrying exactly the requested scope list and drives a flow built over
 * that configuration. Every other collaborator — the shared {@link TokenEndpointClient}, the
 * validation bridges, the gateway's response-mode-corrected {@link AuthorizationRequestBuilder} and
 * the {@link ClientAuthentication} — is the one the base configuration uses, so the only thing that
 * varies between two scoped flows is the scope list.
 * <p>
 * <strong>The factory owns the pinned back-channel posture.</strong> The configuration factory is the
 * producer's {@code backChannelConfiguration(oidc, scopes)}, which applies the ADR-0045 hostname and
 * trust-profile pinning to every configuration it builds. This seam never builds a
 * {@link ClientConfiguration} itself, so a scoped variant cannot carry a posture the base
 * configuration does not.
 * <p>
 * <strong>Login leg — cached per canonical scope set.</strong> {@link #authorize} drives an
 * {@link AuthorizationCodeFlow} cached under the canonical form of the requested set (deduplicated,
 * then sorted), so two requests for the same set in a different order share one flow. The cache is
 * bounded by construction: a login scope set is always a route's boot-derived {@code neededScopes} or
 * {@code oidc.scopes}, never a value a caller supplies, so there are at most as many entries as there
 * are distinct route scope sets.
 * <p>
 * <strong>Refresh leg — never cached.</strong> {@link #refresh} builds a {@link RefreshFlow} per call,
 * because the active scope set a session refreshes with comes from the identity provider's grant and
 * is therefore not bounded by the configuration. Building the flow is cheap (it holds references
 * only); the network cost is the refresh grant itself.
 * <p>
 * The seam performs no I/O of its own and logs nothing — in particular no token. The authorization
 * leg is local (the engine only renders a URL); the refresh leg's network call is the engine's.
 * <p>
 * <strong>Thread safety.</strong> Immutable apart from the login-flow cache, which is a
 * {@link ConcurrentMap} populated through {@link ConcurrentMap#computeIfAbsent}; the engine flows are
 * themselves safe for concurrent use. One instance serves every request.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ScopedEngineFlows {

    private final Function<List<String>, ClientConfiguration> configurationFactory;
    private final TokenEndpointClient tokenEndpointClient;
    private final TokenValidationBridge tokenBridge;
    private final IdTokenValidationBridge idBridge;
    private final AuthorizationRequestBuilder authorizationRequestBuilder;
    private final ClientAuthentication clientAuthentication;
    private final ConcurrentMap<List<String>, AuthorizationCodeFlow> authorizationFlows = new ConcurrentHashMap<>();

    /**
     * Assembles the seam over the shared engine collaborators.
     *
     * @param configurationFactory        builds the pinned back-channel {@link ClientConfiguration}
     *                                    for a given canonical scope list; never returns {@code null}
     * @param tokenEndpointClient         the shared token-endpoint client every flow posts through
     * @param tokenBridge                 the access-token validation bridge
     * @param idBridge                    the ID-token validation bridge
     * @param authorizationRequestBuilder the gateway's {@code response_mode=query} request builder
     * @param clientAuthentication        the confidential-client authentication the refresh grant presents
     */
    public ScopedEngineFlows(Function<List<String>, ClientConfiguration> configurationFactory,
            TokenEndpointClient tokenEndpointClient, TokenValidationBridge tokenBridge,
            IdTokenValidationBridge idBridge, AuthorizationRequestBuilder authorizationRequestBuilder,
            ClientAuthentication clientAuthentication) {
        this.configurationFactory = Objects.requireNonNull(configurationFactory, "configurationFactory");
        this.tokenEndpointClient = Objects.requireNonNull(tokenEndpointClient, "tokenEndpointClient");
        this.tokenBridge = Objects.requireNonNull(tokenBridge, "tokenBridge");
        this.idBridge = Objects.requireNonNull(idBridge, "idBridge");
        this.authorizationRequestBuilder = Objects.requireNonNull(authorizationRequestBuilder,
                "authorizationRequestBuilder");
        this.clientAuthentication = Objects.requireNonNull(clientAuthentication, "clientAuthentication");
    }

    /**
     * Builds the authorization URL and transaction context for a login requesting exactly
     * {@code scopes}.
     *
     * @param metadata the resolved provider metadata
     * @param scopes   the scope set the login requests; order and duplicates are irrelevant
     * @return the engine's authorization redirect, whose URL carries {@code scope} equal to the
     *         canonical form of {@code scopes}
     */
    public AuthorizationCodeFlow.AuthorizationRedirect authorize(ProviderMetadata metadata,
            Collection<String> scopes) {
        Objects.requireNonNull(metadata, "metadata");
        return authorizationFlow(scopes).authorize(metadata);
    }

    /**
     * Redeems {@code refreshToken} with a refresh grant whose {@code scope} is exactly {@code scopes}.
     *
     * @param metadata     the resolved provider metadata
     * @param refreshToken the refresh token to redeem
     * @param scopes       the scope set the grant requests — the session's active scope set
     * @return the engine's rotation result
     */
    public RotationResult refresh(ProviderMetadata metadata, String refreshToken, Collection<String> scopes) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(refreshToken, "refreshToken");
        RefreshFlow refreshFlow = new RefreshFlow(configurationFactory.apply(canonical(scopes)),
                tokenEndpointClient, tokenBridge, clientAuthentication);
        return refreshFlow.refresh(metadata, refreshToken);
    }

    /**
     * Returns the cached login flow for the canonical form of {@code scopes}, building it on first use.
     * Package-private so the reuse of one flow per scope set is observable from a test.
     *
     * @param scopes the requested scope set
     * @return the flow serving that set
     */
    AuthorizationCodeFlow authorizationFlow(Collection<String> scopes) {
        return authorizationFlows.computeIfAbsent(canonical(scopes), canonicalScopes -> new AuthorizationCodeFlow(
                configurationFactory.apply(canonicalScopes), tokenEndpointClient, tokenBridge, idBridge,
                new IssValidator(), authorizationRequestBuilder, new CallbackHandler(), null));
    }

    /**
     * The canonical form of a scope set: duplicates removed, then sorted, as an immutable list. Two
     * sets naming the same scopes in a different order canonicalize to the same key.
     *
     * @param scopes the scope set, never {@code null}
     * @return the canonical scope list
     */
    static List<String> canonical(Collection<String> scopes) {
        Objects.requireNonNull(scopes, "scopes");
        return scopes.stream().map(scope -> Objects.requireNonNull(scope, "scope")).distinct().sorted().toList();
    }
}
