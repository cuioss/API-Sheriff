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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestOptions;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.flow.ParClient;
import de.cuioss.sheriff.token.client.flow.ParResponse;
import de.cuioss.sheriff.token.client.flow.Prompt;
import de.cuioss.sheriff.token.client.flow.PushedAuthorizationEndpointMissingException;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
import de.cuioss.sheriff.token.client.flow.ResponseMode;
import de.cuioss.sheriff.token.client.flow.StepUpChallengeParser.StepUpChallenge;
import de.cuioss.sheriff.token.client.flow.StepUpHandler;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The gateway-side per-request scope and pushed-request seam over the {@code token-sheriff-client}
 * engine (ADR-0083, ADR-0058).
 * <p>
 * <strong>One flow, the scope set named per call.</strong> The engine takes the scope set, the
 * {@code response_mode} and the {@code prompt} of an authorization request as per-call
 * {@link AuthorizationRequestOptions}, and the scope set of a refresh grant as a per-call argument of
 * {@link RefreshFlow#refresh(ProviderMetadata, String, List)}. This seam therefore drives one
 * {@link AuthorizationCodeFlow} and one {@link RefreshFlow}, both built by the runtime over its one
 * back-channel configuration, and names the scope set on every call. Nothing is built per scope set
 * and nothing is cached, so the login leg, the widening leg and the refresh leg are bounded by nothing
 * but the request itself. The engine never changes the shared configuration for a call, so two
 * concurrent calls with different scope sets do not interfere.
 * <p>
 * <strong>The scope set is the canonical one.</strong> Every leg sends {@link #canonical} of the set
 * it is handed: duplicates removed, then sorted. That is gateway policy, not the engine's: two
 * requests naming the same scopes in a different order send the same {@code scope}. The base
 * configuration the runtime builds carries no scope of its own, so an empty canonical set is sent as
 * no scope override at all and the request carries no {@code scope} parameter — the request a
 * configuration built for the empty set sent before. The engine refuses an empty override, which is
 * why the empty set is passed as "no override" rather than as an empty list.
 * <p>
 * <strong>{@code response_mode=query} on every authorization request.</strong> The engine defaults to
 * {@code response_mode=form_post}, so after a successful identity-provider login the browser performs
 * a <em>cross-site POST</em> to the {@code redirect_uri}. The short-lived browser-binding cookie
 * {@link BindingCookieCodec} mints carries {@code SameSite=Lax}, which a browser sends on a top-level
 * GET navigation and <em>not</em> on a cross-site POST, so a {@code form_post} callback dead-ends on
 * the "no browser-binding cookie" {@code 403}. Every request this seam drives opts into
 * {@link ResponseMode#QUERY}, so the callback is a {@code 302} top-level GET navigation and the Lax
 * cookie is sent. The rejected alternative, {@code SameSite=None} on the binding cookie, would have
 * weakened the cross-site binding control the cookie exists to provide.
 * <p>
 * <strong>Accepted tradeoff — the authorization code travels in the URL query string.</strong>
 * {@code response_mode=query} places {@code code}, {@code state} and {@code iss} in the callback URL,
 * which exposes them to the {@code Referer} header, to proxy and server access logs and to browser
 * history ({@code T-URL-LEAK}). It is accepted deliberately, by operator decision, because it is what
 * makes the browser-facing flow work at all. The mitigations: PKCE {@code S256} is always in force and
 * the engine refuses to start a flow without it, so a leaked code is not redeemable without the
 * verifier, which never leaves the gateway; the code is single-use and short-lived; and the callback
 * resolves the pending record by the unguessable id in the binding cookie <em>and</em>
 * constant-time-compares the returned {@code state}, so a code replayed from another browser is
 * refused {@code 403}. The exposure of the code to intermediaries is real and is bounded by those
 * controls, not removed by them.
 * <p>
 * <strong>Every authorization request is pushed (ADR-0058).</strong> The login and the widening are
 * started through {@link AuthorizationCodeFlow#authorizePushed}: the engine pushes the parameters to
 * the identity provider's pushed-authorization-request endpoint (RFC 9126) over the authenticated back
 * channel, presenting the runtime's one {@link ClientAuthentication}, and returns a redirect that
 * carries {@code client_id} and {@code request_uri} and nothing else. The scope set, {@code state},
 * {@code nonce}, the PKCE challenge, {@code response_mode=query} and a silent widening's
 * {@code prompt=none} travel in the pushed request. No {@code dpop_jkt} is pushed: FAPI 2.0 does not
 * require the authorization code to be bound to the DPoP key, and the engine adds none.
 * <p>
 * <strong>The RFC 9470 step-up re-drive is pushed by this seam (kept back from 0.9.10).</strong> The
 * engine's {@link StepUpHandler} mints the step-up transaction — a fresh {@link FlowContext} carrying the
 * challenge's {@code acr_values} and {@code max_age} — and offers no pushed entry point, and
 * {@link AuthorizationCodeFlow#authorizePushed} mints a context without the challenge's parameters. So
 * {@link #stepUp} lets {@link StepUpHandler} mint the context, reads the request's parameters from the
 * engine's {@link AuthorizationRequestBuilder#parameters}, pushes them through an engine
 * {@link ParClient} over the same back-channel configuration, and composes the {@code client_id} +
 * {@code request_uri} redirect itself. That is the one push leg the gateway still drives.
 * <p>
 * <strong>Every failure refuses the request with {@code 502}.</strong> A provider that advertises no
 * (or a blank) {@code pushed_authorization_request_endpoint} is refused without a network call; a
 * request the engine cannot form — the authorization endpoint missing or not a TLS URL, PKCE
 * {@code S256} not advertised, a scope the engine's scope-token rule refuses — is refused without a
 * network call; and every failure of the push itself — a transport failure, a timeout, a non-success
 * answer, an unparsable answer, an answer without a {@code request_uri} — is translated into the same
 * {@link GatewayException} with {@link EventType#UPSTREAM_ERROR}. There is no fall-back to a
 * front-channel request. The callers call this seam before they store a pending record or set a cookie,
 * so a refusal leaves no login, widening or step-up half-started.
 * <p>
 * <strong>Latched refusal record (ADR-0051).</strong> Login initiation is reachable without a
 * credential, so a refusal is recorded as {@code ApiSheriff-132} only on the first occurrence of each
 * reason; every repeat is a {@code DEBUG} line. The widening and the step-up re-drive push through the
 * same instance and share the latch; the runtime holds one instance. Neither the record nor the refusal
 * carries the authorization URL, a parameter value or the {@code request_uri}.
 * <p>
 * <strong>One sender constraint, one token-endpoint client.</strong> The runtime builds the flow and
 * the refresh flow with its one DPoP sender constraint and its one token-endpoint client, so a login,
 * a widening and a refresh obtain access tokens bound to the same key through the same client.
 * <p>
 * The seam logs no token, no URL and no parameter value. Framework-agnostic: no Quarkus, Vert.x or
 * JAX-RS type is involved.
 * <p>
 * <strong>Thread safety.</strong> Immutable apart from the refusal latch, which is a concurrent set;
 * the engine collaborators are safe for concurrent use. One instance serves every request.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ScopedEngineFlows {

    private static final CuiLogger LOGGER = new CuiLogger(ScopedEngineFlows.class);

    private static final String PARAM_CLIENT_ID = "client_id";
    private static final String PARAM_REQUEST_URI = "request_uri";
    private static final char QUERY_START = '?';
    private static final char PAIR_SEPARATOR = '&';
    private static final char NAME_VALUE_SEPARATOR = '=';

    /** The argument name a {@code null} provider metadata is reported under. */
    private static final String METADATA_ARGUMENT = "metadata";

    private final ClientConfiguration configuration;
    private final AuthorizationCodeFlow authorizationCodeFlow;
    private final RefreshFlow refreshFlow;
    private final ClientAuthentication clientAuthentication;
    private final StepUpHandler stepUpHandler = new StepUpHandler();
    private final AuthorizationRequestBuilder authorizationRequestBuilder = new AuthorizationRequestBuilder();
    private final ParClient stepUpParClient;
    private final Set<Reason> reported = ConcurrentHashMap.newKeySet();

    /**
     * Assembles the seam over the runtime's engine flows.
     *
     * @param configuration         the runtime's back-channel configuration, carrying no scope of its own;
     *                              the step-up request is formed and pushed over it
     * @param authorizationCodeFlow the runtime's one authorization-code flow, built over
     *                              {@code configuration}; it starts the login and the widening
     * @param refreshFlow           the runtime's one refresh flow, built over {@code configuration}
     * @param clientAuthentication  the confidential-client authentication every push presents
     */
    public ScopedEngineFlows(ClientConfiguration configuration, AuthorizationCodeFlow authorizationCodeFlow,
            RefreshFlow refreshFlow, ClientAuthentication clientAuthentication) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.authorizationCodeFlow = Objects.requireNonNull(authorizationCodeFlow, "authorizationCodeFlow");
        this.refreshFlow = Objects.requireNonNull(refreshFlow, "refreshFlow");
        this.clientAuthentication = Objects.requireNonNull(clientAuthentication, "clientAuthentication");
        this.stepUpParClient = new ParClient(configuration);
    }

    /**
     * Pushes the authorization request of a login requesting exactly {@code scopes}.
     *
     * @param metadata the resolved provider metadata
     * @param scopes   the scope set the login requests; order and duplicates are irrelevant
     * @return the engine's authorization redirect: a URL carrying {@code client_id} and
     *         {@code request_uri} only, and the transaction context to persist
     * @throws GatewayException with {@link EventType#UPSTREAM_ERROR} when the request is not pushed
     */
    public AuthorizationCodeFlow.AuthorizationRedirect authorize(ProviderMetadata metadata,
            Collection<String> scopes) {
        Objects.requireNonNull(metadata, METADATA_ARGUMENT);
        return pushed(metadata, () -> authorizationCodeFlow.authorizePushed(metadata, clientAuthentication,
                options(scopes, null)));
    }

    /**
     * Pushes the authorization request of a widening requesting exactly {@code scopes}.
     * <p>
     * When {@code silent} is {@code true} the pushed request carries exactly one
     * {@code prompt=none}; the interactive attempt carries no {@code prompt} parameter.
     *
     * @param metadata the resolved provider metadata
     * @param scopes   the scope set the widening requests; order and duplicates are irrelevant
     * @param silent   {@code true} for the {@code prompt=none} attempt, {@code false} for the
     *                 interactive attempt
     * @return the engine's authorization redirect: a URL carrying {@code client_id} and
     *         {@code request_uri} only, and the transaction context to persist
     * @throws GatewayException with {@link EventType#UPSTREAM_ERROR} when the request is not pushed
     */
    public AuthorizationCodeFlow.AuthorizationRedirect widen(ProviderMetadata metadata, Collection<String> scopes,
            boolean silent) {
        Objects.requireNonNull(metadata, METADATA_ARGUMENT);
        return pushed(metadata, () -> authorizationCodeFlow.authorizePushed(metadata, clientAuthentication,
                options(scopes, silent ? Prompt.NONE : null)));
    }

    /**
     * Pushes the RFC 9470 step-up authorization request for {@code challenge}, requesting exactly
     * {@code scopes}.
     *
     * @param metadata  the resolved provider metadata
     * @param challenge the parsed step-up challenge whose {@code acr_values} / {@code max_age} the
     *                  request carries
     * @param scopes    the scope set the step-up requests; order and duplicates are irrelevant
     * @return the step-up request: a URL carrying {@code client_id} and {@code request_uri} only, and
     *         the fresh transaction context to persist
     * @throws GatewayException with {@link EventType#UPSTREAM_ERROR} when the request is not pushed
     */
    public StepUpHandler.StepUpRequest stepUp(ProviderMetadata metadata, StepUpChallenge challenge,
            Collection<String> scopes) {
        Objects.requireNonNull(metadata, METADATA_ARGUMENT);
        Objects.requireNonNull(challenge, "challenge");
        return pushed(metadata, () -> {
            AuthorizationRequestOptions options = options(scopes, null);
            // The handler mints the context carrying the challenge's acr_values and max_age; its
            // front-channel URL is discarded, the parameters of the same request are pushed instead.
            FlowContext context = stepUpHandler.initiate(configuration, metadata, challenge, options).context();
            Map<String, String> parameters = authorizationRequestBuilder.parameters(configuration, metadata, context,
                    options);
            String parEndpoint = metadata.getPushedAuthorizationRequestEndpoint()
                    .orElseThrow(() -> new PushedAuthorizationEndpointMissingException(
                            "provider metadata is missing the pushed authorization request endpoint"));
            // parameters(...) has refused metadata without an authorization endpoint already.
            String authorizationEndpoint = metadata.getAuthorizationEndpoint()
                    .orElseThrow(() -> new ClientProtocolException(
                            "provider metadata is missing the authorization endpoint"));
            ParResponse pushedRequest = stepUpParClient.pushAuthorizationRequest(parEndpoint, parameters,
                    clientAuthentication);
            return new StepUpHandler.StepUpRequest(pushedRedirect(authorizationEndpoint, pushedRequest.requestUri),
                    context);
        });
    }

    /**
     * Redeems {@code refreshToken} with a refresh grant whose {@code scope} is exactly the canonical
     * form of {@code scopes}; an empty set sends no {@code scope} parameter.
     *
     * @param metadata     the resolved provider metadata
     * @param refreshToken the refresh token to redeem
     * @param scopes       the scope set the grant requests — the session's active scope set, plus the
     *                     missing scopes on a scope-driven refresh
     * @return the engine's rotation result
     */
    public RotationResult refresh(ProviderMetadata metadata, String refreshToken, Collection<String> scopes) {
        Objects.requireNonNull(metadata, METADATA_ARGUMENT);
        Objects.requireNonNull(refreshToken, "refreshToken");
        List<String> canonicalScopes = canonical(scopes);
        if (canonicalScopes.isEmpty()) {
            // The configuration carries no scope, so the default grant sends none.
            return refreshFlow.refresh(metadata, refreshToken);
        }
        return refreshFlow.refresh(metadata, refreshToken, canonicalScopes);
    }

    /**
     * The per-call options of an authorization request: the canonical scope set (no override for the
     * empty set), {@code response_mode=query} and the given prompt.
     */
    private static AuthorizationRequestOptions options(Collection<String> scopes, @Nullable Prompt prompt) {
        List<String> canonicalScopes = canonical(scopes);
        return AuthorizationRequestOptions.builder()
                .scopes(canonicalScopes.isEmpty() ? null : canonicalScopes)
                .responseMode(ResponseMode.QUERY)
                .prompt(prompt)
                .build();
    }

    /**
     * Runs one push and translates each engine refusal into the gateway's {@code 502} refusal. A blank
     * pushed-authorization-request endpoint is refused here, before the engine is asked, exactly as an
     * absent one.
     */
    private <T> T pushed(ProviderMetadata metadata, Supplier<T> push) {
        if (metadata.getPushedAuthorizationRequestEndpoint().filter(endpoint -> !endpoint.isBlank()).isEmpty()) {
            throw refusal(Reason.NO_PAR_ENDPOINT, null);
        }
        try {
            return push.get();
        } catch (PushedAuthorizationEndpointMissingException _) {
            throw refusal(Reason.NO_PAR_ENDPOINT, null);
        } catch (TransportException failed) {
            throw refusal(Reason.PUSH_FAILED, failed);
        } catch (ClientProtocolException | IllegalArgumentException _) {
            // The engine's message can name a scope value or an endpoint — name the reason, never chain it.
            throw refusal(Reason.INVALID_REQUEST, null);
        }
    }

    /**
     * The one emission point of a refusal: records {@code ApiSheriff-132} under the latch and builds
     * the {@code 502} failure, whose message names the reason token and nothing else.
     *
     * @param reason why the request was not pushed
     * @param cause  the engine failure behind a failed push, {@code null} when nothing was sent
     * @return the failure to throw
     */
    private GatewayException refusal(Reason reason, @Nullable Throwable cause) {
        if (reported.add(reason)) {
            LOGGER.warn(BffLogMessages.WARN.AUTHORIZATION_PUSH_REFUSED, reason.token);
        } else {
            LOGGER.debug("Pushed authorization request refused again (%s) — this reason has already been "
                    + "reported once and stays at DEBUG for the rest of the process", reason.token);
        }
        return new GatewayException(EventType.UPSTREAM_ERROR,
                "Pushed authorization request refused (" + reason.token + ")", cause);
    }

    /**
     * The step-up redirect: the authorization endpoint with exactly two form-encoded query parameters
     * appended, {@code client_id} and {@code request_uri}.
     */
    private String pushedRedirect(String authorizationEndpoint, String requestUri) {
        return authorizationEndpoint + (authorizationEndpoint.indexOf(QUERY_START) < 0 ? QUERY_START : PAIR_SEPARATOR)
                + PARAM_CLIENT_ID + NAME_VALUE_SEPARATOR + encode(configuration.getClientId())
                + PAIR_SEPARATOR + PARAM_REQUEST_URI + NAME_VALUE_SEPARATOR + encode(requestUri);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * The canonical form of a scope set: duplicates removed, then sorted, as an immutable list. Two
     * sets naming the same scopes in a different order canonicalize to the same list.
     *
     * @param scopes the scope set, never {@code null}
     * @return the canonical scope list
     */
    static List<String> canonical(Collection<String> scopes) {
        Objects.requireNonNull(scopes, "scopes");
        return scopes.stream().map(scope -> Objects.requireNonNull(scope, "scope")).distinct().sorted().toList();
    }

    /**
     * Why an authorization request was not pushed, as the refusal record and the failure name it.
     */
    private enum Reason {

        /** The provider metadata advertises no pushed-authorization-request endpoint. */
        NO_PAR_ENDPOINT("no-par-endpoint"),

        /** The engine could not form the authorization request from the metadata and the options. */
        INVALID_REQUEST("invalid-request"),

        /** The push itself failed, at the transport or in the identity provider's answer. */
        PUSH_FAILED("push-failed");

        private final String token;

        Reason(String token) {
            this.token = token;
        }
    }
}
