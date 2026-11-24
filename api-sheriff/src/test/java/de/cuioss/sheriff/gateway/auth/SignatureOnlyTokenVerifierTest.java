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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.bff.logout.LogoutRejection;
import de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator;
import de.cuioss.sheriff.gateway.bff.logout.VerifiedLogoutToken;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.commons.error.ErrorTextSanitizer;
import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.commons.transport.JwksType;
import de.cuioss.sheriff.token.commons.transport.LoaderStatus;
import de.cuioss.sheriff.token.validation.IssuerConfig;
import de.cuioss.sheriff.token.validation.TokenType;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;
import de.cuioss.sheriff.token.validation.jwks.JwksLoader;
import de.cuioss.sheriff.token.validation.jwks.key.KeyInfo;
import de.cuioss.sheriff.token.validation.test.JwtTokenTamperingUtil;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.ClaimControlParameter;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Covers {@link SignatureOnlyTokenVerifier} — the seam the BFF back-channel logout receiver is bound
 * to — over a <strong>really signed</strong> token, not a hand-built claim carrier.
 * <p>
 * The suite is built around one matched pair, because the change it guards is a seam swap and a seam
 * swap is invisible to a test of either side alone:
 * <ul>
 *   <li><strong>Positive.</strong> A spec-shaped back-channel logout token — signed, carrying
 *       {@code sid}, {@code exp} and {@code jti} but no {@code sub} and no {@code azp} — is verified here and its
 *       claims reach {@link LogoutTokenValidator}, which accepts it.</li>
 *   <li><strong>Negative control.</strong> The <em>same</em> token, put through
 *       {@link IdTokenValidationBridge#validateRefreshedIdToken(String)} — the seam this class
 *       replaced — is rejected. Without this arm the positive arm would pass just as happily if the
 *       old seam had never been a problem, and the whole fix would read as unnecessary.</li>
 * </ul>
 * The security properties that must <em>not</em> have been relaxed are asserted alongside: a tampered
 * signature, an unknown issuer, an issuer whose key set is no longer reported usable and a malformed
 * token are all still refused, and the unknown issuer's echoed {@code iss} cannot forge log lines.
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
    private static final String JTI = "logout-token-id-1";
    private static final String EVENTS = "{\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\":{}}";

    /**
     * A back-channel logout token as
     * <a href="https://openid.net/specs/openid-connect-backchannel-1_0.html">the spec</a> defines it
     * and as {@code backchannel.logout.session.required=true} produces it: {@code sid}, {@code exp}
     * and {@code jti}, but no {@code sub} and no {@code azp}.
     */
    private final TestTokenHolder logoutToken = new TestTokenHolder(TokenType.ID_TOKEN,
            ClaimControlParameter.builder()
                    .missingSubject(true)
                    .missingAuthorizedParty(true)
                    .build())
            .withClaim("sid", ClaimValue.forPlainString(SID))
            .withClaim("jti", ClaimValue.forPlainString(JTI))
            .withClaim("events", ClaimValue.forPlainString(EVENTS));

    private final IssuerConfig issuerConfig = logoutToken.getIssuerConfig();

    private final TokenValidator tokenValidator = TokenValidator.builder().issuerConfig(issuerConfig).build();

    private final SignatureOnlyTokenVerifier verifier =
            new SignatureOnlyTokenVerifier(List.of(issuerConfig), tokenValidator.getSecurityEventCounter());

    @Nested
    @DisplayName("The seam swap, as a matched pair")
    class SeamSwap {

        @Test
        @DisplayName("Should verify a sid-only, azp-less logout token and surface its claims")
        void shouldVerifySpecShapedLogoutToken() {
            TokenContent verified = verifier.verify(logoutToken.getRawToken()).content();

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
                    claimResidual.validate(asLogoutToken(verifier.verify(logoutToken.getRawToken())), Instant.now());

            LogoutTokenValidator.Verdict.Accepted accepted = assertInstanceOf(
                    LogoutTokenValidator.Verdict.Accepted.class, verdict,
                    "the documented sid-only path must be reachable end to end, not just past the seam");
            assertEquals(SID, accepted.subject().sid());
            assertEquals(JTI, accepted.jti(), "the jti the token was signed with reaches the verdict");
        }

        @Test
        @DisplayName("Should verify an exp-less token at the seam and leave its refusal to the claim residual")
        void shouldLeaveMissingExpiryToTheClaimResidual() {
            TestTokenHolder withoutExpiry = new TestTokenHolder(TokenType.ID_TOKEN,
                    ClaimControlParameter.builder()
                            .missingSubject(true)
                            .missingExpiration(true)
                            .missingAuthorizedParty(true)
                            .build())
                    .withClaim("sid", ClaimValue.forPlainString(SID))
                    .withClaim("jti", ClaimValue.forPlainString(JTI))
                    .withClaim("events", ClaimValue.forPlainString(EVENTS));
            IssuerConfig expirylessIssuer = withoutExpiry.getIssuerConfig();
            // Building a validator over the issuer is what initialises its key loader.
            TokenValidator expirylessValidator = TokenValidator.builder().issuerConfig(expirylessIssuer).build();
            SignatureOnlyTokenVerifier seam = new SignatureOnlyTokenVerifier(List.of(expirylessIssuer),
                    expirylessValidator.getSecurityEventCounter());
            LogoutTokenValidator claimResidual = new LogoutTokenValidator(withoutExpiry.getIssuer(),
                    withoutExpiry.getAudience().iterator().next(), Duration.ofMinutes(2));

            LogoutTokenValidator.Verdict verdict =
                    claimResidual.validate(asLogoutToken(seam.verify(withoutExpiry.getRawToken())), Instant.now());

            LogoutTokenValidator.Verdict.Rejected rejected = assertInstanceOf(
                    LogoutTokenValidator.Verdict.Rejected.class, verdict,
                    "the seam judges the signature only; the missing exp is the claim residual's refusal");
            assertEquals(LogoutRejection.EXPIRED, rejected.reason());
        }
    }

    /**
     * The {@code typ} header reaches the caller as the token was signed with it, so the caller's type
     * check judges a value the signature covers rather than one read from an unverified copy.
     */
    @Nested
    @DisplayName("The typ header of the signed token")
    class HeaderType {

        @Test
        @DisplayName("Should hand on the typ value of the token's protected header")
        void shouldHandOnTheSignedHeaderType() {
            String raw = logoutToken.getRawToken();
            String header = new String(Base64.getUrlDecoder().decode(raw.substring(0, raw.indexOf('.'))),
                    StandardCharsets.UTF_8);
            Matcher signedType = Pattern.compile("\"typ\"\\s*:\\s*\"([^\"]*)\"").matcher(header);

            String handedOn = verifier.verify(raw).headerType();

            if (signedType.find()) {
                assertEquals(signedType.group(1), handedOn, "the typ of the signed header: " + header);
            } else {
                assertNull(handedOn, "a header without typ is handed on as absent: " + header);
            }
        }
    }

    /** What the session runtime does with a verified token before the claim residual judges it. */
    private static VerifiedLogoutToken asLogoutToken(SignatureOnlyTokenVerifier.VerifiedToken verified) {
        return new VerifiedLogoutToken(verified.content(), verified.headerType());
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

    /**
     * A configured, enabled issuer whose loader still holds its keys but no longer reports its key set
     * usable — what token-sheriff 0.9.9's {@code HttpJwksLoader} does once
     * {@code jwks.max_staleness_seconds} is exceeded — must not verify a logout token from the keys it
     * retained. The library's issuer cache refuses such an issuer's bearer tokens; the logout path
     * refuses its logout tokens the same way.
     * <p>
     * Both arms verify the same signed token against the same, really loaded key set, read through
     * {@link StatusReportingLoader}: only the status the loader reports differs, so the refusal can be
     * attributed to that status alone.
     */
    @Nested
    @DisplayName("An issuer whose key set is no longer reported usable")
    class DegradedIssuer {

        @ParameterizedTest(name = "loader status {0}")
        @EnumSource(value = LoaderStatus.class, names = "OK", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Should refuse a logout token from an issuer whose loader holds keys but does not report OK")
        void shouldRefuseTokenFromDegradedIssuer(LoaderStatus degradedStatus) {
            StatusReportingLoader loader = new StatusReportingLoader(issuerConfig.getJwksLoader(), degradedStatus);
            SignatureOnlyTokenVerifier seam = verifierOver(loader);
            String raw = logoutToken.getRawToken();
            assertFalse(loader.getKeyIds().isEmpty(), "precondition: the degraded loader still holds the issuer's keys");

            TokenValidationException refusal = assertThrows(TokenValidationException.class, () -> seam.verify(raw),
                    "a key set its loader no longer reports usable must not verify a logout token");

            assertAll("degraded-issuer refusal",
                    () -> assertEquals(SecurityEventCounter.EventType.NO_ISSUER_CONFIG, refusal.getEventType(),
                            "refused as the library's issuer cache refuses the same issuer's bearer tokens"),
                    () -> assertTrue(refusal.getMessage().contains(String.valueOf(degradedStatus)),
                            "the refusal names the reported status, so it is the degraded-issuer branch "
                                    + "and not the unconfigured-issuer one: " + refusal.getMessage()));
        }

        @Test
        @DisplayName("Should verify the same token against the same keys when the loader reports OK (matched control)")
        void shouldVerifyTokenFromHealthyIssuer() {
            StatusReportingLoader loader = new StatusReportingLoader(issuerConfig.getJwksLoader(), LoaderStatus.OK);
            SignatureOnlyTokenVerifier seam = verifierOver(loader);

            TokenContent verified = seam.verify(logoutToken.getRawToken()).content();

            assertEquals(SID, verified.getClaims().get("sid").getOriginalString(),
                    "only the reported status differs from the refusal arm, and with OK the token verifies");
        }

        /** A verifier over one issuer that names the fixture token's {@code iss} and reads {@code loader}. */
        private SignatureOnlyTokenVerifier verifierOver(JwksLoader loader) {
            IssuerConfig issuer = IssuerConfig.builder()
                    .issuerIdentifier(logoutToken.getIssuer())
                    .audienceValidationDisabled(true)
                    .jwksLoader(loader)
                    .build();
            return new SignatureOnlyTokenVerifier(List.of(issuer), tokenValidator.getSecurityEventCounter());
        }
    }

    /**
     * The refusal of an unconfigured issuer echoes the attacker-controlled {@code iss} into a message
     * that reaches an unauthenticated-path DEBUG log, so the echo must not let a caller forge log lines
     * (CWE-117). The token is refused on its {@code iss} before any key is consulted, so an unsigned,
     * hand-built JWT reaches that branch exactly as a signed foreign token does.
     */
    @Nested
    @DisplayName("The echoed iss of an unconfigured issuer")
    class RefusalEcho {

        private static final String MESSAGE_PREFIX = "No enabled issuer configuration for '";
        /*
         * The three invisible characters are built from their code points rather than written into a
         * literal, so a reader of this file sees which character each assertion names (Sonar
         * text:S6389 flags a raw bidi control in source text).
         */
        private static final String LINE_SEPARATOR = Character.toString(0x2028);
        private static final String PARAGRAPH_SEPARATOR = Character.toString(0x2029);
        private static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E);

        @Test
        @DisplayName("Should escape CR, LF, U+2028, U+2029 and bidi controls and truncate an over-long iss")
        void shouldEscapeUnsafeCharactersAndTruncate() {
            String filler = "a".repeat(300);
            // JSON escapes, decoded by the parser into the raw characters the attacker sent.
            String hostileIssJson = "evil\\r\\nforged\\u2028\\u2029\\u202E" + filler;
            int rawPrefixLength = ("evil\r\nforged" + LINE_SEPARATOR + PARAGRAPH_SEPARATOR + RIGHT_TO_LEFT_OVERRIDE)
                    .length();
            String expectedEcho = "evil\\r\\nforged\\u2028\\u2029\\u202e"
                    + "a".repeat(ErrorTextSanitizer.MAX_LENGTH - rawPrefixLength)
                    + ErrorTextSanitizer.TRUNCATION_MARKER;
            String raw = unsignedTokenWithIssuer(hostileIssJson);

            TokenValidationException refusal = assertThrows(TokenValidationException.class,
                    () -> verifier.verify(raw));

            String message = refusal.getMessage();
            assertAll("hostile iss echo",
                    () -> assertEquals(SecurityEventCounter.EventType.NO_ISSUER_CONFIG, refusal.getEventType(),
                            "the unconfigured-issuer branch is the one that echoes the iss"),
                    () -> assertEquals(MESSAGE_PREFIX + expectedEcho + "'", message,
                            "each unsafe character escaped, the value cut at the library bound and marked"),
                    () -> assertFalse(message.contains("\r"), "no raw CR"),
                    () -> assertFalse(message.contains("\n"), "no raw LF"),
                    () -> assertFalse(message.contains(LINE_SEPARATOR), "no raw LINE SEPARATOR"),
                    () -> assertFalse(message.contains(PARAGRAPH_SEPARATOR), "no raw PARAGRAPH SEPARATOR"),
                    () -> assertFalse(message.contains(RIGHT_TO_LEFT_OVERRIDE), "no raw RIGHT-TO-LEFT OVERRIDE"));
        }

        @Test
        @DisplayName("Should echo a benign unconfigured iss verbatim (matched control)")
        void shouldEchoBenignIssuerVerbatim() {
            String benignIss = Generators.letterStrings(5, 40).next();
            String raw = unsignedTokenWithIssuer(benignIss);

            TokenValidationException refusal = assertThrows(TokenValidationException.class,
                    () -> verifier.verify(raw));

            assertAll("benign iss echo",
                    () -> assertEquals(SecurityEventCounter.EventType.NO_ISSUER_CONFIG, refusal.getEventType()),
                    () -> assertEquals(MESSAGE_PREFIX + benignIss + "'", refusal.getMessage(),
                            "a value with nothing unsafe in it is echoed unchanged"));
        }

        /**
         * Builds a compact JWT whose body carries only {@code iss}; the signature segment is a
         * placeholder because the issuer lookup refuses the token before any signature check.
         *
         * @param issJsonContent the {@code iss} value as JSON string content, escapes included
         */
        private static String unsignedTokenWithIssuer(String issJsonContent) {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                    .getBytes(StandardCharsets.UTF_8));
            String body = encoder.encodeToString(("{\"iss\":\"" + issJsonContent + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            String signature = encoder.encodeToString("placeholder".getBytes(StandardCharsets.UTF_8));
            return header + "." + body + "." + signature;
        }
    }

    /**
     * A hand-written loader that answers every key lookup from a real, loaded library loader and
     * reports the status the test chose — the shape of a stale-degraded delegate, which keeps
     * answering from its retained keys while it no longer reports them usable.
     */
    private static final class StatusReportingLoader implements JwksLoader {

        /** The loaded library loader every key lookup is answered from. */
        private final JwksLoader keys;
        /** The status {@link #getLoaderStatus()} reports, whatever {@link #keys} holds. */
        private final LoaderStatus status;

        StatusReportingLoader(JwksLoader keys, LoaderStatus status) {
            this.keys = keys;
            this.status = status;
        }

        @Override
        public Optional<KeyInfo> getKeyInfo(String kid) {
            return keys.getKeyInfo(kid);
        }

        @Override
        public Set<String> getKeyIds() {
            return keys.getKeyIds();
        }

        @Override
        public JwksType getJwksType() {
            return keys.getJwksType();
        }

        @Override
        public Optional<String> getIssuerIdentifier() {
            return keys.getIssuerIdentifier();
        }

        @Override
        public CompletableFuture<LoaderStatus> initJWKSLoader(SecurityEventCounter securityEventCounter) {
            return CompletableFuture.completedFuture(status);
        }

        @Override
        public LoaderStatus getLoaderStatus() {
            return status;
        }
    }
}
