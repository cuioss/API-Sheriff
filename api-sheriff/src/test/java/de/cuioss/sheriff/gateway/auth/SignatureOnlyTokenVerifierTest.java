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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;


import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.validation.IssuerConfig;
import de.cuioss.sheriff.token.validation.TokenType;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;
import de.cuioss.sheriff.token.validation.test.JwtTokenTamperingUtil;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.ClaimControlParameter;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link SignatureOnlyTokenVerifier} — the seam the BFF back-channel logout receiver is bound
 * to — over a <strong>really signed</strong> token, not a hand-built claim carrier.
 * <p>
 * The suite is built around one matched pair, because the change it guards is a seam swap and a seam
 * swap is invisible to a test of either side alone:
 * <ul>
 *   <li><strong>Positive.</strong> A spec-shaped back-channel logout token — signed, carrying
 *       {@code sid} but no {@code sub}, no {@code exp} and no {@code azp} — is verified here and its
 *       claims reach {@link LogoutTokenValidator}, which accepts it.</li>
 *   <li><strong>Negative control.</strong> The <em>same</em> token, put through
 *       {@link IdTokenValidationBridge#validateRefreshedIdToken(String)} — the seam this class
 *       replaced — is rejected. Without this arm the positive arm would pass just as happily if the
 *       old seam had never been a problem, and the whole fix would read as unnecessary.</li>
 * </ul>
 * The security properties that must <em>not</em> have been relaxed are asserted alongside: a tampered
 * signature, an unknown issuer and a malformed token are all still refused.
 * <p>
 * Note on {@code events}: {@link TestTokenHolder} carries a claim as a JSON <em>string</em>, so the
 * token built here exercises the string form of that claim. The object form a real identity provider
 * sends — which the engine surfaces in {@code Map#toString()} syntax — is covered at the unit level by
 * {@code LogoutTokenValidatorTest}.
 */
@EnableGeneratorController
@DisplayName("SignatureOnlyTokenVerifier — signature yes, token-type claim semantics no")
class SignatureOnlyTokenVerifierTest {

    private static final String SID = "idp-sid-9";
    private static final String EVENTS = "{\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\":{}}";

    /**
     * A back-channel logout token as
     * <a href="https://openid.net/specs/openid-connect-backchannel-1_0.html">the spec</a> defines it
     * and as {@code backchannel.logout.session.required=true} produces it: {@code sid} but no
     * {@code sub}, no {@code exp}, no {@code azp}.
     */
    private final TestTokenHolder logoutToken = new TestTokenHolder(TokenType.ID_TOKEN,
            ClaimControlParameter.builder()
                    .missingSubject(true)
                    .missingExpiration(true)
                    .missingAuthorizedParty(true)
                    .build())
            .withClaim("sid", ClaimValue.forPlainString(SID))
            .withClaim("events", ClaimValue.forPlainString(EVENTS));

    private final IssuerConfig issuerConfig = logoutToken.getIssuerConfig();

    private final TokenValidator tokenValidator = TokenValidator.builder().issuerConfig(issuerConfig).build();

    private final SignatureOnlyTokenVerifier verifier =
            new SignatureOnlyTokenVerifier(List.of(issuerConfig), tokenValidator.getSecurityEventCounter());

    @Nested
    @DisplayName("The seam swap, as a matched pair")
    class SeamSwap {

        @Test
        @DisplayName("Should verify a sid-only, exp-less, azp-less logout token and surface its claims")
        void shouldVerifySpecShapedLogoutToken() {
            TokenContent verified = verifier.verify(logoutToken.getRawToken());

            assertNotNull(verified);
            assertEquals(SID, verified.getClaims().get("sid").getOriginalString());
            assertTrue(verified.getSubject().isEmpty(),
                    "the token carries no sub — that is legal for a logout token and must not be invented");
        }

        @Test
        @DisplayName("Should be rejected by the ID-token pipeline this seam replaced (falsification control)")
        void shouldBeRejectedByTheIdTokenPipeline() {
            IdTokenValidationBridge idBridge = new IdTokenValidationBridge(tokenValidator);
            String raw = logoutToken.getRawToken();

            assertThrows(TokenValidationException.class, () -> idBridge.validateRefreshedIdToken(raw),
                    "if the ID-token pipeline accepted this token, binding the verifier away from it "
                            + "would be pointless — this arm is what makes the positive arm meaningful");
        }

        @Test
        @DisplayName("Should carry the verified claims into an accepted back-channel logout verdict")
        void shouldReachAnAcceptedLogoutVerdict() {
            LogoutTokenValidator claimResidual = new LogoutTokenValidator(logoutToken.getIssuer(),
                    logoutToken.getAudience().iterator().next(), Duration.ofMinutes(2));

            LogoutTokenValidator.Verdict verdict =
                    claimResidual.validate(verifier.verify(logoutToken.getRawToken()), Instant.now());

            LogoutTokenValidator.Verdict.Accepted accepted = assertInstanceOf(
                    LogoutTokenValidator.Verdict.Accepted.class, verdict,
                    "the documented sid-only path must be reachable end to end, not just past the seam");
            assertEquals(SID, accepted.subject().sid());
        }
    }

    @Nested
    @DisplayName("Nothing about the trust boundary was relaxed")
    class TrustBoundary {

        @Test
        @DisplayName("Should refuse a token whose signature was tampered with")
        void shouldRefuseTamperedSignature() {
            String tampered = JwtTokenTamperingUtil.applyTamperingStrategy(logoutToken.getRawToken(),
                    JwtTokenTamperingUtil.TamperingStrategy.MODIFY_SIGNATURE_LAST_CHAR);

            assertThrows(TokenValidationException.class, () -> verifier.verify(tampered));
        }

        @Test
        @DisplayName("Should refuse a token signed for an issuer this gateway does not configure")
        void shouldRefuseUnknownIssuer() {
            TestTokenHolder foreign = new TestTokenHolder(TokenType.ID_TOKEN,
                    ClaimControlParameter.defaultForTokenType(TokenType.ID_TOKEN));
            SignatureOnlyTokenVerifier emptyGateway =
                    new SignatureOnlyTokenVerifier(List.of(), tokenValidator.getSecurityEventCounter());
            String raw = foreign.getRawToken();

            assertThrows(TokenValidationException.class, () -> emptyGateway.verify(raw),
                    "an issuer the gateway does not configure has no key set to verify against");
        }

        @Test
        @DisplayName("Should refuse a token that is not a JWT at all")
        void shouldRefuseMalformedToken() {
            assertThrows(TokenValidationException.class, () -> verifier.verify("not-a-jwt"));
        }

        @Test
        @DisplayName("Should refuse a null token rather than treating it as absent")
        void shouldRefuseNullToken() {
            assertThrows(NullPointerException.class, () -> verifier.verify(null));
        }
    }
}
