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
package de.cuioss.sheriff.gateway.auth;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.validation.IssuerConfig;
import de.cuioss.sheriff.token.validation.domain.context.IdTokenRequest;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;
import de.cuioss.sheriff.token.validation.pipeline.DecodedJwt;
import de.cuioss.sheriff.token.validation.pipeline.NonValidatingJwtParser;
import de.cuioss.sheriff.token.validation.pipeline.SignatureTemplateManager;
import de.cuioss.sheriff.token.validation.pipeline.TokenBuilder;
import de.cuioss.sheriff.token.validation.pipeline.validator.TokenHeaderValidator;
import de.cuioss.sheriff.token.validation.pipeline.validator.TokenSignatureValidator;
import de.cuioss.tools.logging.CuiLogger;

/**
 * Verifies that a JWT was <strong>signed by a configured issuer</strong>, and stops there — it applies
 * no claim semantics of any token type.
 * <p>
 * <strong>Why this exists.</strong> The engine's public surface offers one validation entry point per
 * token <em>type</em> ({@code createAccessToken} / {@code createIdToken} / {@code createRefreshToken}),
 * and each imposes that type's mandatory-claim set. A JWT that is neither an access token nor an ID
 * token therefore has no usable entry point: driving it through the ID-token pipeline rejects it on
 * claims its own specification never required. The OIDC back-channel <em>logout token</em> is exactly
 * such a JWT — per
 * <a href="https://openid.net/specs/openid-connect-backchannel-1_0.html">OpenID Connect Back-Channel
 * Logout</a> §2.4 it carries {@code iss}, {@code aud}, {@code iat}, {@code jti} and {@code events}
 * plus {@code sub} <em>and/or</em> {@code sid}; it has no {@code exp}, need not carry {@code sub}, and
 * {@code azp} is not part of its claim set. Validating it as an ID token rejected every spec-valid
 * {@code sid}-only logout token before the gateway's own claim residual was ever reached (BFF-11).
 * <p>
 * <strong>What it runs.</strong> Precisely the engine's own ID-token pipeline
 * ({@code IdTokenValidationPipeline}) <em>minus its final claim-validation stage</em>, over the same
 * per-issuer collaborators:
 * <ol>
 *   <li>decode the token — size, structure and base64 bounds are enforced by the parser;</li>
 *   <li>resolve the configured {@link IssuerConfig} by the token's {@code iss} — an unknown or
 *       disabled issuer is refused;</li>
 *   <li>{@link TokenHeaderValidator} — algorithm posture and issuer agreement in the JWT header;</li>
 *   <li>{@link TokenSignatureValidator} — the cryptographic check against that issuer's JWKS;</li>
 *   <li>{@link TokenBuilder} — map the verified body into claims. The builder performs no validation;
 *       it only maps.</li>
 * </ol>
 * Everything the omitted stage would have enforced — issuer equality, audience, freshness, and the
 * token's own required claims — is the caller's responsibility, deliberately, so that the caller
 * remains the single authority over its token type's claim set. For the back-channel logout path that
 * caller is {@code LogoutTokenValidator}.
 * <p>
 * <strong>This is not a weakening of the trust boundary.</strong> A token that fails signature
 * verification, names an issuer this gateway does not configure, or carries an algorithm the issuer's
 * key does not support is refused here exactly as it would be on the ID-token path. The only checks
 * dropped are the ones that encode <em>ID-token</em> claim semantics.
 * <p>
 * Thread-safe and immutable: the per-issuer collaborators are resolved once at construction.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SignatureOnlyTokenVerifier {

    private static final CuiLogger LOGGER = new CuiLogger(SignatureOnlyTokenVerifier.class);

    private final NonValidatingJwtParser parser;
    private final Map<String, IssuerVerification> issuers;
    private final SecurityEventCounter securityEventCounter;

    /**
     * Assembles the verifier over the gateway's configured issuers.
     *
     * @param configuredIssuers    the issuers the gateway validates against, in configuration order;
     *                             a disabled issuer is carried but refuses every token
     * @param securityEventCounter the shared counter the engine's validators increment, so a refusal
     *                             on this path is counted exactly as one on the bearer path
     */
    public SignatureOnlyTokenVerifier(Collection<IssuerConfig> configuredIssuers,
            SecurityEventCounter securityEventCounter) {
        Objects.requireNonNull(configuredIssuers, "configuredIssuers");
        this.securityEventCounter = Objects.requireNonNull(securityEventCounter, "securityEventCounter");
        this.parser = NonValidatingJwtParser.builder().securityEventCounter(securityEventCounter).build();
        Map<String, IssuerVerification> resolved = new LinkedHashMap<>();
        for (IssuerConfig issuer : configuredIssuers) {
            resolved.put(issuer.getIssuerIdentifier(), new IssuerVerification(issuer,
                    new TokenHeaderValidator(issuer, securityEventCounter),
                    new TokenSignatureValidator(issuer.getJwksLoader(), securityEventCounter,
                            new SignatureTemplateManager(issuer.getAlgorithmPreferences())),
                    new TokenBuilder(issuer)));
        }
        this.issuers = Map.copyOf(resolved);
    }

    /**
     * Signature-verifies a raw JWT against its configured issuer and returns its claims unvalidated.
     *
     * @param rawToken the raw compact-serialized JWT
     * @return the signature-verified token content, carrying the mapped claims and no claim verdict
     * @throws TokenValidationException when the token cannot be decoded, names no {@code iss}, names an
     *                                  issuer this gateway does not configure or has disabled, carries
     *                                  an unacceptable header, fails signature verification, or has an
     *                                  empty body
     */
    public TokenContent verify(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken");
        DecodedJwt decoded = parser.decode(rawToken);
        String issuerIdentifier = decoded.getIssuer().orElseThrow(() -> reject(
                SecurityEventCounter.EventType.MISSING_CLAIM,
                "Token carries no issuer (iss) claim — signature verification cannot select a key set"));
        IssuerVerification issuer = issuers.get(issuerIdentifier);
        if (issuer == null || !issuer.config().isEnabled()) {
            // The identifier is echoed because it is attacker-controlled but bounded to what the
            // caller already sent, and without it the operator cannot tell a typo'd issuer in the
            // gateway configuration from a token genuinely minted elsewhere.
            throw reject(SecurityEventCounter.EventType.NO_ISSUER_CONFIG,
                    "No enabled issuer configuration for '" + issuerIdentifier + "'");
        }
        issuer.headerValidator().validate(decoded, IdTokenRequest.of(rawToken));
        issuer.signatureValidator().validateSignature(decoded);
        TokenContent verified = issuer.tokenBuilder().createIdToken(decoded).orElseThrow(() -> reject(
                SecurityEventCounter.EventType.MISSING_CLAIM,
                "Signature-verified token carries an empty body"));
        LOGGER.debug("Token signature verified against issuer %s — claim validation is the caller's",
                issuerIdentifier);
        return verified;
    }

    private TokenValidationException reject(SecurityEventCounter.EventType eventType, String detail) {
        securityEventCounter.increment(eventType);
        return new TokenValidationException(eventType, detail);
    }

    /**
     * The per-issuer collaborators, resolved once so no token verification allocates them.
     *
     * @param config             the configured issuer
     * @param headerValidator    the header posture check for that issuer
     * @param signatureValidator the JWKS-backed signature check for that issuer
     * @param tokenBuilder       the claim mapper for that issuer (maps only, validates nothing)
     */
    private record IssuerVerification(IssuerConfig config, TokenHeaderValidator headerValidator,
    TokenSignatureValidator signatureValidator, TokenBuilder tokenBuilder) {
    }
}
