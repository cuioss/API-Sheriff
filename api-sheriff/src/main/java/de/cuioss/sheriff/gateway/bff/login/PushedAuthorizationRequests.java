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

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.ParClient;
import de.cuioss.sheriff.token.client.flow.ParResponse;
import de.cuioss.sheriff.token.commons.error.TokenSheriffException;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * Pushes an engine-built authorization request to the identity provider (RFC 9126) and yields the
 * URL the browser is sent to instead: the authorization endpoint with {@code client_id} and
 * {@code request_uri}, and nothing else (ADR-0058).
 * <p>
 * This class composes no authorization parameter itself. What is pushed is exactly what the
 * authorization URL it is handed carries — {@code response_type}, {@code client_id},
 * {@code redirect_uri}, {@code scope}, {@code state}, {@code nonce}, the PKCE challenge,
 * {@code response_mode=query}, on the RFC 9470 step-up leg {@code acr_values} and {@code max_age},
 * and on the silent attempt of a session widening the {@code prompt=none} that
 * {@link ScopedEngineFlows#widen} set on the engine-built URL. The
 * URL is the parameter source and is never sent to the browser. No {@code dpop_jkt} is added: FAPI
 * 2.0 does not require a client to bind the authorization code to its DPoP key, and the engine's
 * pushed-request transport attaches no proof.
 * <p>
 * This is an adapter over the engine's {@link ParClient} and the runtime's shared
 * {@link ClientAuthentication}. Both are handed to the constructor; the class constructs neither,
 * so the push dials the identity provider under the same pinned TLS posture and presents the same
 * client credential as every other authenticated back-channel leg.
 * <p>
 * <strong>Every failure refuses the login, the widening or the step-up re-drive with
 * {@code 502}.</strong> A provider that advertises no
 * {@code pushed_authorization_request_endpoint} is refused without a network call; an authorization
 * URL that cannot be split into its parameters, or that names one parameter twice, is refused
 * without a network call; and every failure of the push itself — a transport failure, a timeout, a
 * non-success answer, an unparsable answer, an answer without a {@code request_uri} — is translated
 * into the same refusal. There is no fall-back to an authorization URL that carries the parameters
 * through the browser. The refusal is raised before the caller stores its pending authorization or
 * sets its cookie, so no login is left half-started.
 * <p>
 * <strong>Latched refusal record (ADR-0051).</strong> Login initiation is reachable without a
 * credential, so a refusal is recorded as {@code ApiSheriff-132} only on the first occurrence of
 * each reason; every repeat is a {@code DEBUG} line. A session widening needs a live session, but it
 * pushes through this same instance and so shares the latch. The latch is held per instance, and the runtime
 * holds one instance. Neither the record nor the refusal carries the authorization URL, a parameter
 * value or the {@code request_uri}.
 * <p>
 * Framework-agnostic: no Quarkus, Vert.x or JAX-RS type is involved. Thread-safe: the latch is a
 * concurrent set and the collaborators are safe for concurrent use; one instance serves every
 * request.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PushedAuthorizationRequests {

    private static final CuiLogger LOGGER = new CuiLogger(PushedAuthorizationRequests.class);

    private static final String PARAM_CLIENT_ID = "client_id";
    private static final String PARAM_REQUEST_URI = "request_uri";
    private static final char QUERY_START = '?';
    private static final char PAIR_SEPARATOR = '&';
    private static final char NAME_VALUE_SEPARATOR = '=';

    private final ParClient parClient;
    private final ClientAuthentication clientAuthentication;
    private final Set<Reason> reported = ConcurrentHashMap.newKeySet();

    /**
     * @param parClient            the engine's pushed-authorization-request client, built over the
     *                             runtime's back-channel configuration
     * @param clientAuthentication the confidential-client authentication every authenticated
     *                             back-channel leg presents
     */
    public PushedAuthorizationRequests(ParClient parClient, ClientAuthentication clientAuthentication) {
        this.parClient = Objects.requireNonNull(parClient, "parClient");
        this.clientAuthentication = Objects.requireNonNull(clientAuthentication, "clientAuthentication");
    }

    /**
     * Pushes the parameters of an engine-built authorization URL and returns the URL the browser is
     * redirected to.
     *
     * @param metadata         the resolved provider metadata naming the authorization endpoint and
     *                         the pushed-authorization-request endpoint
     * @param clientId         the OAuth 2.0 client id the redirect carries
     * @param authorizationUrl the authorization URL the engine built on the provider's authorization
     *                         endpoint, already rewritten to {@code response_mode=query}
     * @return the authorization endpoint with exactly two form-encoded query parameters appended,
     *         {@code client_id} and {@code request_uri}
     * @throws GatewayException with {@link EventType#UPSTREAM_ERROR} when the provider advertises no
     *                          pushed-authorization-request endpoint, when the authorization URL
     *                          cannot be split into uniquely named parameters, or when the push fails
     */
    public String push(ProviderMetadata metadata, String clientId, String authorizationUrl) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(authorizationUrl, "authorizationUrl");

        String parEndpoint = metadata.getPushedAuthorizationRequestEndpoint()
                .filter(endpoint -> !endpoint.isBlank())
                .orElseThrow(() -> refusal(Reason.NO_PAR_ENDPOINT, null));
        String authorizationEndpoint = metadata.getAuthorizationEndpoint()
                .filter(authorizationUrl::startsWith)
                .orElseThrow(() -> refusal(Reason.INVALID_REQUEST, null));
        Map<String, String> parameters = parametersOf(authorizationUrl, authorizationEndpoint);

        ParResponse pushed;
        try {
            pushed = parClient.pushAuthorizationRequest(parEndpoint, parameters, clientAuthentication);
        } catch (TokenSheriffException failed) {
            throw refusal(Reason.PUSH_FAILED, failed);
        }
        // The engine returns only a response that carries a request_uri; anything else is one of the
        // failures translated above.
        return authorizationEndpoint + (authorizationEndpoint.indexOf(QUERY_START) < 0 ? QUERY_START : PAIR_SEPARATOR)
                + PARAM_CLIENT_ID + NAME_VALUE_SEPARATOR + encode(clientId)
                + PAIR_SEPARATOR + PARAM_REQUEST_URI + NAME_VALUE_SEPARATOR + encode(pushed.requestUri);
    }

    /**
     * Splits the query the engine appended to the authorization endpoint into its decoded parameters.
     * The engine appends one form-encoded query after {@code ?}, or after {@code &} when the
     * endpoint carries a query of its own, so everything after the endpoint and that one separator
     * is the parameter set.
     *
     * @throws GatewayException when no parameter follows the endpoint, a pair is undecodable or
     *                          carries no name, or a parameter name occurs twice
     */
    private Map<String, String> parametersOf(String authorizationUrl, String authorizationEndpoint) {
        int queryStart = authorizationEndpoint.length();
        if (authorizationUrl.length() <= queryStart + 1
                || (authorizationUrl.charAt(queryStart) != QUERY_START
                && authorizationUrl.charAt(queryStart) != PAIR_SEPARATOR)) {
            throw refusal(Reason.INVALID_REQUEST, null);
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : authorizationUrl.substring(queryStart + 1).split(String.valueOf(PAIR_SEPARATOR), -1)) {
            int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
            String name;
            String value;
            try {
                name = decode(separator < 0 ? pair : pair.substring(0, separator));
                value = separator < 0 ? "" : decode(pair.substring(separator + 1));
            } catch (IllegalArgumentException _) {
                // The decoder's message echoes the offending pair — name the reason, never chain it.
                throw refusal(Reason.INVALID_REQUEST, null);
            }
            if (name.isEmpty() || parameters.putIfAbsent(name, value) != null) {
                throw refusal(Reason.INVALID_REQUEST, null);
            }
        }
        return parameters;
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

    private static String decode(String formEncoded) {
        return URLDecoder.decode(formEncoded, StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Why an authorization request was not pushed, as the refusal record and the failure name it.
     */
    private enum Reason {

        /** The provider metadata advertises no pushed-authorization-request endpoint. */
        NO_PAR_ENDPOINT("no-par-endpoint"),

        /** The authorization URL is not a uniquely named parameter set on the authorization endpoint. */
        INVALID_REQUEST("invalid-request"),

        /** The push itself failed, at the transport or in the identity provider's answer. */
        PUSH_FAILED("push-failed");

        private final String token;

        Reason(String token) {
            this.token = token;
        }
    }
}
