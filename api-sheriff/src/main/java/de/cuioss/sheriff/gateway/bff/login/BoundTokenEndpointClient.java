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

import java.io.IOException;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.dpop.SenderConstraint;
import de.cuioss.sheriff.token.client.flow.RedeemedResponseException;
import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import de.cuioss.sheriff.token.client.token.TokenResponse;
import de.cuioss.sheriff.token.commons.transport.ParserConfig;
import de.cuioss.sheriff.token.validation.json.MapRepresentation;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The gateway's token-endpoint client: the engine's {@link TokenEndpointClient} plus the refusal of
 * every token response that is not bound to the gateway's DPoP proof key (ADR-0057).
 * <p>
 * <strong>Why one seam, and why this one.</strong> The engine's code exchange and its refresh grant
 * both obtain their {@link TokenResponse} from the four-argument
 * {@link TokenEndpointClient#requestToken(String, Map, Map, SenderConstraint)}, the three-argument
 * overload delegates to it, and the runtime shares one client instance between the base flow, the
 * per-scope flows and the refresh flow. The token type exists on that response only: neither the
 * engine's {@code AuthenticationResult} nor its {@code RotationResult} carries it. One override of
 * that method therefore sees the token type and the access token of every token response the gateway
 * obtains, and no flow, no call site and no session seam is given a copy of the check.
 * <p>
 * <strong>What it does.</strong> It calls the engine method unchanged, so the DPoP proof, the
 * {@code DPoP-Nonce} retry and every engine refusal of a non-success answer stay the engine's. It
 * judges only a response the engine returned: the {@code token_type} must be {@code DPoP}, compared
 * without regard to case (RFC 6749 §5.1), and the access token must carry a {@code cnf} object whose
 * {@code jkt} string equals the expected thumbprint. A response that passes is returned as it is.
 * <p>
 * <strong>How {@code cnf} is read.</strong> From the payload segment of the access token,
 * base64url-decoded and parsed with the token library's bounded parser
 * ({@link MapRepresentation#fromJson} over the default {@link ParserConfig}) — the read
 * {@code IdTokenClaimProjection} performs on the ID token. The validated token content is not a
 * source: the library has no {@code cnf} claim type and renders an object-valued claim there as a
 * Java {@code toString()}. No trust decision rests on the unverified payload: the engine validates
 * the signature over exactly these bytes directly after this method returns, nothing is accepted
 * unless both pass, and a refusal on an unverified payload only refuses.
 * <p>
 * <strong>An access token that is not a JWT.</strong> A token that is not a three-segment compact
 * JWS, or whose payload is not base64url-encoded JSON within the parser limits, has no readable
 * {@code cnf} and is refused under its own reason. This narrows nothing a deployment could rely on:
 * the engine validates the access token as a JWT at the code exchange and at every refresh, and the
 * gateway reads its expiry the same way at every refresh decision, so an identity provider that
 * issues opaque access tokens has never completed a login, and the gateway configures no JWE
 * decryption.
 * <p>
 * <strong>Refusal.</strong> One private emission point records {@code ApiSheriff-131} with two
 * bounded tokens — the leg ({@code code-exchange}, {@code refresh} or {@code other}, mapped from the
 * request's {@code grant_type} by an allow-list) and the reason ({@code token-type},
 * {@code unreadable-access-token}, {@code cnf-absent}, {@code cnf-mismatch}) — and throws the
 * engine's {@link RedeemedResponseException} with a fixed message that names the reason token and
 * nothing else. Neither the record nor the exception carries the access token, the refresh token,
 * the ID token, a claim value, the received {@code jkt} or the received token type.
 * <p>
 * <strong>The exception type is the contract with the two callers, and the reason neither of them
 * changes.</strong> It is a {@code TokenSheriffException}, which {@code CallbackEndpoint} answers
 * with {@code 400}, no session and no session cookie. And {@code RefreshFlow.classify} reads it as
 * a grant the identity provider redeemed, which {@code TokenRefreshCoordinator} disposes by
 * destroying the session under the reason {@code redeemed-response-refused}, after which the session
 * stage applies {@code oidc.session.refresh.on_failure} (ADR-0046). A plain
 * {@code TransportException} would be read as a grant the provider never processed and would keep a
 * session whose refresh token the provider has already consumed.
 * <p>
 * <strong>The record is not latched.</strong> It is emitted on every occurrence, because the refusal
 * is reached only on a success answer of the token endpoint, which needs an authorization code or a
 * refresh token the identity provider issued — a caller without a credential cannot reach it
 * (ADR-0051).
 * <p>
 * <strong>The check holds in both client-authentication modes.</strong> It reads neither the mode
 * nor the client credential. A call made without a sender constraint is refused the same way: it
 * carries no DPoP proof, so its response cannot be bound.
 * <p>
 * <strong>Token responses only.</strong> The check judges a token response when it arrives. A
 * session whose token is bound to an earlier key is never re-checked, so replacing the key does not
 * by itself end a session.
 * <p>
 * Thread-safety: immutable apart from what the engine client holds, and safe for concurrent use; one
 * instance serves every flow.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BoundTokenEndpointClient extends TokenEndpointClient {

    private static final CuiLogger LOGGER = new CuiLogger(BoundTokenEndpointClient.class);

    /** The library's default parser limits, shared because the configuration is immutable. */
    private static final ParserConfig PARSER_CONFIG = ParserConfig.builder().build();

    private static final String PARAM_GRANT_TYPE = "grant_type";
    private static final String CLAIM_CNF = "cnf";
    private static final String MEMBER_JKT = "jkt";

    /** Header, payload and signature — the segment count of a compact JWS. */
    private static final int COMPACT_JWS_SEGMENTS = 3;

    private static final int PAYLOAD_SEGMENT = 1;

    private final String expectedThumbprint;

    /**
     * @param configuration      the back-channel client configuration carrying the TLS posture of
     *                           the token-endpoint leg
     * @param expectedThumbprint the RFC 7638 thumbprint of the gateway's DPoP proof key — the
     *                           {@code cnf.jkt} every accepted access token must carry
     * @throws IllegalArgumentException when {@code expectedThumbprint} is blank, because a blank
     *                                  comparand would accept a token whose {@code jkt} is blank
     */
    public BoundTokenEndpointClient(ClientConfiguration configuration, String expectedThumbprint) {
        super(configuration);
        Objects.requireNonNull(expectedThumbprint, "expectedThumbprint");
        if (expectedThumbprint.isBlank()) {
            throw new IllegalArgumentException("expectedThumbprint must not be blank");
        }
        this.expectedThumbprint = expectedThumbprint;
    }

    /**
     * Posts the token request through the engine and returns its response only when the token is
     * bound to the gateway's DPoP proof key.
     *
     * @param tokenEndpoint    the absolute token endpoint URL
     * @param formParameters   the form-encoded request body parameters; {@code grant_type} selects
     *                         the leg a refusal is recorded under
     * @param requestHeaders   additional request headers
     * @param senderConstraint the DPoP sender constraint to apply; a call without one is refused,
     *                         because its response cannot be bound
     * @return the engine's token response, unchanged
     * @throws RedeemedResponseException when the response is not of type {@code DPoP} or its access
     *                                   token does not carry the expected {@code cnf.jkt}; the
     *                                   engine's own failures propagate unchanged
     */
    @Override
    public TokenResponse requestToken(String tokenEndpoint, Map<String, String> formParameters,
            Map<String, String> requestHeaders, @Nullable SenderConstraint senderConstraint) {
        TokenResponse response = super.requestToken(tokenEndpoint, formParameters, requestHeaders,
                senderConstraint);
        Leg leg = Leg.of(formParameters.get(PARAM_GRANT_TYPE));
        if (!TokenResponse.TOKEN_TYPE_DPOP.equalsIgnoreCase(response.tokenType)) {
            throw refusal(leg, Reason.TOKEN_TYPE);
        }
        Optional<Reason> defect = bindingDefect(response.accessToken);
        if (defect.isPresent()) {
            throw refusal(leg, defect.get());
        }
        return response;
    }

    /**
     * Reads {@code cnf.jkt} from the unverified payload of the access token and compares it with the
     * expected thumbprint.
     *
     * @param accessToken the access token of a response the engine returned
     * @return the reason the token is not bound to the proof key, empty when it is
     */
    private Optional<Reason> bindingDefect(String accessToken) {
        String[] segments = accessToken.split("\\.", -1);
        if (segments.length != COMPACT_JWS_SEGMENTS) {
            return Optional.of(Reason.UNREADABLE_ACCESS_TOKEN);
        }
        byte[] payload;
        try {
            payload = Base64.getUrlDecoder().decode(segments[PAYLOAD_SEGMENT]);
        } catch (IllegalArgumentException _) {
            return Optional.of(Reason.UNREADABLE_ACCESS_TOKEN);
        }
        if (payload.length == 0 || payload.length > PARSER_CONFIG.getMaxPayloadSize()) {
            return Optional.of(Reason.UNREADABLE_ACCESS_TOKEN);
        }
        MapRepresentation claims;
        try {
            claims = MapRepresentation.fromJson(PARSER_CONFIG.getDslJson(), payload);
        } catch (IOException _) {
            // The parser's message can echo a fragment of the token payload — it is not logged.
            return Optional.of(Reason.UNREADABLE_ACCESS_TOKEN);
        }
        Optional<String> thumbprint = claims.getNestedMap(CLAIM_CNF).flatMap(cnf -> cnf.getString(MEMBER_JKT));
        if (thumbprint.isEmpty()) {
            return Optional.of(Reason.CNF_ABSENT);
        }
        return expectedThumbprint.equals(thumbprint.get()) ? Optional.empty() : Optional.of(Reason.CNF_MISMATCH);
    }

    /**
     * The one emission point of a refusal: records {@code ApiSheriff-131} with the two bounded
     * tokens and builds the exception that carries the reason token and nothing else.
     *
     * @param leg    the leg the refused response belongs to
     * @param reason why the response is not bound to the proof key
     * @return the exception to throw
     */
    private static RedeemedResponseException refusal(Leg leg, Reason reason) {
        LOGGER.warn(BffLogMessages.WARN.TOKEN_RESPONSE_NOT_BOUND, leg.label, reason.token);
        return new RedeemedResponseException(
                "Token response refused: not bound to the DPoP proof key (" + reason.token + ")");
    }

    /**
     * The leg a token response belongs to, as the refusal record names it.
     */
    private enum Leg {

        CODE_EXCHANGE("code-exchange"),

        REFRESH("refresh"),

        OTHER("other");

        private static final String GRANT_AUTHORIZATION_CODE = "authorization_code";
        private static final String GRANT_REFRESH_TOKEN = "refresh_token";

        private final String label;

        Leg(String label) {
            this.label = label;
        }

        /**
         * Maps a {@code grant_type} to its leg by an allow-list, so a request value never reaches the
         * log: every grant type the list does not name, and an absent one, is {@link #OTHER}.
         */
        static Leg of(@Nullable String grantType) {
            if (GRANT_AUTHORIZATION_CODE.equals(grantType)) {
                return CODE_EXCHANGE;
            }
            if (GRANT_REFRESH_TOKEN.equals(grantType)) {
                return REFRESH;
            }
            return OTHER;
        }
    }

    /**
     * Why a token response is not bound to the proof key, as the refusal record and the exception
     * name it.
     */
    private enum Reason {

        /** The response's {@code token_type} is not {@code DPoP}. */
        TOKEN_TYPE("token-type"),

        /** The access token is not a compact JWS whose payload is JSON within the parser limits. */
        UNREADABLE_ACCESS_TOKEN("unreadable-access-token"),

        /** The access token carries no {@code cnf} object holding a {@code jkt} string. */
        CNF_ABSENT("cnf-absent"),

        /** The access token's {@code cnf.jkt} names another key. */
        CNF_MISMATCH("cnf-mismatch");

        private final String token;

        Reason(String token) {
            this.token = token;
        }
    }
}
