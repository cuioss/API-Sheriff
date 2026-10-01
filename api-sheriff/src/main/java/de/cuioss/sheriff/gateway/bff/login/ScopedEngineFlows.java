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

import java.util.ArrayList;
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
 * because the scope set a session refreshes with comes from the identity provider's grant and
 * is therefore not bounded by the configuration. Building the flow is cheap (it holds references
 * only); the network cost is the refresh grant itself.
 * <p>
 * <strong>Widening leg — never cached.</strong> {@link #widen} builds an {@link AuthorizationCodeFlow}
 * per call for the same reason: the set a widening requests is the session's granted-scope set plus
 * the route's needed scopes, and the granted set is IdP-derived, so caching it would break the
 * "bounded by boot configuration" property of the login-flow cache. A silent widening additionally
 * carries {@code prompt=none}, added by a parameter-aware rewrite of the rendered URL in the same
 * style as {@link QueryResponseModeAuthorizationRequestBuilder#withQueryResponseMode}.
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

    /** The authorization-request parameter a silent widening sets. */
    private static final String PARAM_PROMPT = "prompt";

    /** The OIDC prompt value that forbids the IdP from showing any interaction. */
    private static final String PROMPT_NONE_PAIR = PARAM_PROMPT + "=none";
    private static final char QUERY_START = '?';
    private static final String PAIR_SEPARATOR = "&";
    private static final char NAME_VALUE_SEPARATOR = '=';

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
     * Builds the authorization URL and transaction context for a widening requesting exactly
     * {@code scopes}, over an {@link AuthorizationCodeFlow} built for this call alone.
     * <p>
     * The flow is never cached: the widening set contains the session's IdP-derived granted scopes,
     * so it is not bounded by the boot configuration the login-flow cache relies on. When
     * {@code silent} is {@code true} the rendered URL additionally carries exactly one
     * {@code prompt=none}; every other parameter is copied through byte for byte. The URL is never
     * logged.
     *
     * @param metadata the resolved provider metadata
     * @param scopes   the scope set the widening requests; order and duplicates are irrelevant
     * @param silent   {@code true} for the {@code prompt=none} attempt, {@code false} for the
     *                 interactive attempt, which carries no {@code prompt} parameter of its own
     * @return the engine's authorization redirect, whose URL carries {@code scope} equal to the
     *         canonical form of {@code scopes}
     */
    public AuthorizationCodeFlow.AuthorizationRedirect widen(ProviderMetadata metadata, Collection<String> scopes,
            boolean silent) {
        Objects.requireNonNull(metadata, "metadata");
        AuthorizationCodeFlow.AuthorizationRedirect redirect = newAuthorizationFlow(canonical(scopes))
                .authorize(metadata);
        if (!silent) {
            return redirect;
        }
        return new AuthorizationCodeFlow.AuthorizationRedirect(withPromptNone(redirect.authorizationUrl()),
                redirect.context());
    }

    /**
     * Redeems {@code refreshToken} with a refresh grant whose {@code scope} is exactly {@code scopes}.
     *
     * @param metadata     the resolved provider metadata
     * @param refreshToken the refresh token to redeem
     * @param scopes       the scope set the grant requests — the session's active scope set, plus the
     *                     missing scopes on a scope-driven refresh
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
        return authorizationFlows.computeIfAbsent(canonical(scopes), this::newAuthorizationFlow);
    }

    /**
     * @return the number of cached login flows. Package-private so a test can observe that the
     *         widening leg never grows the cache.
     */
    int cachedAuthorizationFlowCount() {
        return authorizationFlows.size();
    }

    private AuthorizationCodeFlow newAuthorizationFlow(List<String> canonicalScopes) {
        return new AuthorizationCodeFlow(configurationFactory.apply(canonicalScopes), tokenEndpointClient,
                tokenBridge, idBridge, new IssValidator(), authorizationRequestBuilder, new CallbackHandler(), null);
    }

    /**
     * Sets the {@code prompt} parameter of an authorization URL to {@code none}, leaving every other
     * parameter untouched.
     * <p>
     * The rewrite is parameter-aware: it splits the query into its {@code name=value} pairs and
     * compares the literal parameter name, never a substring. Untouched pairs are copied through
     * exactly as the engine emitted them, so no decode/re-encode round-trip can corrupt an encoded
     * {@code redirect_uri} or {@code scope}. An existing {@code prompt} pair is replaced in place and
     * any further {@code prompt} pair dropped, so the result carries exactly one {@code prompt=none};
     * a URL without one gains it at the end. The URL is never logged: it carries {@code state},
     * {@code nonce} and the PKCE {@code code_challenge}.
     *
     * @param authorizationUrl the engine-built authorization URL
     * @return the same URL carrying exactly one {@code prompt=none}
     */
    static String withPromptNone(String authorizationUrl) {
        Objects.requireNonNull(authorizationUrl, "authorizationUrl");
        int queryStart = authorizationUrl.indexOf(QUERY_START);
        if (queryStart < 0) {
            return authorizationUrl + QUERY_START + PROMPT_NONE_PAIR;
        }
        String prefix = authorizationUrl.substring(0, queryStart + 1);
        String query = authorizationUrl.substring(queryStart + 1);
        if (query.isEmpty()) {
            return prefix + PROMPT_NONE_PAIR;
        }
        List<String> pairs = new ArrayList<>();
        boolean written = false;
        for (String pair : query.split(PAIR_SEPARATOR, -1)) {
            if (!PARAM_PROMPT.equals(nameOf(pair))) {
                pairs.add(pair);
            } else if (!written) {
                pairs.add(PROMPT_NONE_PAIR);
                written = true;
            }
        }
        if (!written) {
            pairs.add(PROMPT_NONE_PAIR);
        }
        return prefix + String.join(PAIR_SEPARATOR, pairs);
    }

    private static String nameOf(String pair) {
        int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
        return separator < 0 ? pair : pair.substring(0, separator);
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
