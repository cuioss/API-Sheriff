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
package de.cuioss.sheriff.gateway.bff.logout;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver.BackchannelResult;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.sheriff.token.client.token.IdTokenValidationBridge;
import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.commons.transport.JwksType;
import de.cuioss.sheriff.token.commons.transport.LoaderStatus;
import de.cuioss.sheriff.token.validation.IssuerConfig;
import de.cuioss.sheriff.token.validation.TokenType;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.SignatureVerifiedToken;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;
import de.cuioss.sheriff.token.validation.jwks.JwksLoader;
import de.cuioss.sheriff.token.validation.jwks.key.KeyInfo;
import de.cuioss.sheriff.token.validation.test.JwtTokenTamperingUtil;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.ClaimControlParameter;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Covers the back-channel logout signature seam as the session runtime binds it — through
 * {@link BackchannelLogoutReceiver.LogoutTokenVerifier#engine}, the engine's signature-only entry point
 * {@link TokenValidator#verifySignature(String)} adapted by {@link VerifiedLogoutToken#of} — over a
 * <strong>really signed</strong> token, and the receiver's handling of that seam's refusals: a
 * configured issuer whose key set is not reported usable is recorded as {@code key-source-unavailable},
 * every other refusal as {@code signature-rejected}.
 * <p>
 * The suite is built around one matched pair, because the seam it guards is invisible to a test of
 * either side alone:
 * <ul>
 *   <li><strong>Positive.</strong> A spec-shaped back-channel logout token — signed, carrying
 *       {@code sid}, {@code exp} and {@code jti} but no {@code sub} and no {@code azp} — is verified and
 *       its claims reach {@link LogoutTokenValidator}, which accepts it.</li>
 *   <li><strong>Negative control.</strong> The <em>same</em> token, put through
 *       {@link IdTokenValidationBridge#validateRefreshedIdToken(String)} — the ID-token pipeline the
 *       seam must not be bound to — is rejected.</li>
 * </ul>
 * The trust boundary is asserted alongside: a tampered signature, an unknown issuer, an issuer whose
 * key set is not reported usable, a malformed token and an over-long token string are all refused, and
 * the unknown issuer's echoed {@code iss} cannot forge a gateway log line.
 * <p>
 * <strong>Accepted residual (operator decision, 2026-11-25).</strong> The library itself records a
 * {@code NO_ISSUER_CONFIG} WARN under its own logger that carries the unknown issuer's {@code iss}
 * unsanitised. The gateway has no switch for that record and does not try to suppress it; the
 * assertions here are scoped to the gateway's own loggers.
 * <p>
 * Note on {@code events}: {@link TestTokenHolder} carries a claim as a JSON <em>string</em>, so the
 * token built here exercises the string form of that claim — the form the library's own logout
 * validator refuses and the gateway's keeps accepting.
 */
@EnableGeneratorController
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@ExtendWith(SheriffDebugCapture.class)
@DisplayName("Back-channel logout signature seam — TokenValidator#verifySignature")
class BackchannelLogoutSignatureSeamTest {

    private static final String SID = "idp-sid-9";
    private static final String JTI = "logout-token-id-1";
    private static final String EVENTS = "{\"" + LogoutTokenValidator.BACKCHANNEL_LOGOUT_EVENT + "\":{}}";
    private static final Duration TTL = Duration.ofHours(8);
    /** The logger prefix every record the gateway itself writes carries. */
    private static final String GATEWAY_LOGGERS = "de.cuioss.sheriff.gateway";

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

    /**
     * The seam exactly as the session runtime binds it: through the engine factory, over the validator
     * and the configured logout issuer whose key source a refusal is checked against.
     */
    private static BackchannelLogoutReceiver.LogoutTokenVerifier productionSeam(TokenValidator validator,
            String issuer) {
        return BackchannelLogoutReceiver.LogoutTokenVerifier.engine(validator, issuer);
    }

    /** The production seam over {@code validator}, configured for the fixture token's issuer. */
    private BackchannelLogoutReceiver.LogoutTokenVerifier productionSeam(TokenValidator validator) {
        return productionSeam(validator, logoutToken.getIssuer());
    }

    private LogoutTokenValidator claimResidualFor(TestTokenHolder token) {
        return new LogoutTokenValidator(token.getIssuer(), token.getAudience().iterator().next(),
                Duration.ofMinutes(2));
    }

    private BackchannelLogoutReceiver receiverOver(TokenValidator validator) {
        InMemorySessionStore store = new InMemorySessionStore(16, TTL, Integer.MAX_VALUE, sessionId -> {
        });
        return new BackchannelLogoutReceiver(productionSeam(validator), claimResidualFor(logoutToken),
                new ServerSessionBinding(store, new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, TTL)),
                new LogoutTokenReplayGuard(16));
    }

    private static int signatureRejections() {
        return warningsFor(LogoutRejection.SIGNATURE_REJECTED);
    }

    private static int keySourceOutages() {
        return warningsFor(LogoutRejection.KEY_SOURCE_UNAVAILABLE);
    }

    private static int warningsFor(LogoutRejection reason) {
        return TestLoggerFactory.getTestHandler()
                .resolveLogMessagesContaining(TestLogLevel.WARN, reason.token()).size();
    }

    private static List<LogRecord> gatewayRecords() {
        return SheriffDebugCapture.capturedRecords().stream()
                .filter(captured -> String.valueOf(captured.getLoggerName()).startsWith(GATEWAY_LOGGERS))
                .toList();
    }

    @Nested
    @DisplayName("The seam, as a matched pair")
    class MatchedPair {

        @Test
        @DisplayName("Should verify a sid-only, azp-less logout token and surface its claims")
        void shouldVerifySpecShapedLogoutToken() {
            VerifiedLogoutToken verified = productionSeam(tokenValidator).verify(logoutToken.getRawToken());

            assertEquals(SID, verified.content().getClaims().get("sid").getOriginalString());
            assertTrue(verified.content().getSubject().isEmpty(),
                    "the token carries no sub — that is legal for a logout token and must not be invented");
        }

        @Test
        @DisplayName("Should be rejected by the ID-token pipeline the seam must not be bound to (falsification control)")
        void shouldBeRejectedByTheIdTokenPipeline() {
            IdTokenValidationBridge idBridge = new IdTokenValidationBridge(tokenValidator);
            String raw = logoutToken.getRawToken();

            assertThrows(TokenValidationException.class, () -> idBridge.validateRefreshedIdToken(raw),
                    "if the ID-token pipeline accepted this token, binding the seam away from it would be "
                            + "pointless — this arm is what makes the positive arm meaningful");
        }

        @Test
        @DisplayName("Should carry the verified claims into an accepted back-channel logout verdict")
        void shouldReachAnAcceptedLogoutVerdict() {
            LogoutTokenValidator.Verdict verdict = claimResidualFor(logoutToken)
                    .validate(productionSeam(tokenValidator).verify(logoutToken.getRawToken()), Instant.now());

            LogoutTokenValidator.Verdict.Accepted accepted = assertInstanceOf(
                    LogoutTokenValidator.Verdict.Accepted.class, verdict,
                    "the documented sid-only path must be reachable end to end, not just past the seam");
            assertEquals(SID, accepted.subject().sid());
            assertEquals(JTI, accepted.jti(), "the jti the token was signed with reaches the verdict");
        }

        @Test
        @DisplayName("Should accept the token through the receiver wired as the session runtime wires it")
        void shouldAcceptThroughTheReceiver() {
            BackchannelResult result = receiverOver(tokenValidator).receive(logoutToken.getRawToken(), Instant.now());

            assertTrue(result.accepted(), "a really signed, spec-shaped logout token is accepted end to end");
        }

        @Test
        @DisplayName("Should apply no claim stage: an exp-less token verifies and the claim residual refuses it")
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
            TokenValidator expirylessValidator =
                    TokenValidator.builder().issuerConfig(withoutExpiry.getIssuerConfig()).build();

            LogoutTokenValidator.Verdict verdict = claimResidualFor(withoutExpiry)
                    .validate(productionSeam(expirylessValidator, withoutExpiry.getIssuer())
                            .verify(withoutExpiry.getRawToken()), Instant.now());

            LogoutTokenValidator.Verdict.Rejected rejected = assertInstanceOf(
                    LogoutTokenValidator.Verdict.Rejected.class, verdict,
                    "the seam judges the signature only; the missing exp is the claim residual's refusal");
            assertEquals(LogoutRejection.EXPIRED, rejected.reason());
        }

        @Test
        @DisplayName("Should count each signature-only verification as SIGNATURE_ONLY_VERIFIED")
        void shouldCountSignatureOnlyVerification() {
            SecurityEventCounter counter = tokenValidator.getSecurityEventCounter();
            long before = counter.getCount(SecurityEventCounter.EventType.SIGNATURE_ONLY_VERIFIED);

            productionSeam(tokenValidator).verify(logoutToken.getRawToken());

            assertEquals(before + 1, counter.getCount(SecurityEventCounter.EventType.SIGNATURE_ONLY_VERIFIED),
                    "the logout path shares the bearer path's security-event counter");
        }
    }

    /**
     * The {@code typ} header reaches the claim residual as the token was signed with it, so the type
     * check judges a value the signature covers rather than one read from an unverified copy.
     */
    @Nested
    @DisplayName("The typ header of the signed token")
    class HeaderType {

        @Test
        @DisplayName("Should hand on the typ value of the token's protected header, absent as absent")
        void shouldHandOnTheSignedHeaderType() {
            String raw = logoutToken.getRawToken();
            String header = new String(Base64.getUrlDecoder().decode(raw.substring(0, raw.indexOf('.'))),
                    StandardCharsets.UTF_8);
            Matcher signedType = Pattern.compile("\"typ\"\\s*:\\s*\"([^\"]*)\"").matcher(header);

            String handedOn = productionSeam(tokenValidator).verify(raw).headerType();

            if (signedType.find()) {
                assertEquals(signedType.group(1), handedOn, "the typ of the signed header: " + header);
            } else {
                assertNull(handedOn, "a header without typ is handed on as absent: " + header);
            }
        }

        @Test
        @DisplayName("Should adapt the verified token's typ and claims into the claim carrier")
        void shouldAdaptTypAndClaims() {
            Map<String, ClaimValue> claims = Map.of("sid", ClaimValue.forPlainString(SID));
            SignatureVerifiedToken verified = new SignatureVerifiedToken(claims, Map.of("sid", SID), Set.of(),
                    LogoutTokenValidator.LOGOUT_TOKEN_TYPE, "raw.logout.token");

            VerifiedLogoutToken adapted = VerifiedLogoutToken.of(verified);

            assertAll("adapted logout token",
                    () -> assertEquals(LogoutTokenValidator.LOGOUT_TOKEN_TYPE, adapted.headerType(),
                            "the signed typ reaches the claim residual's type check"),
                    () -> assertEquals(SID, adapted.content().getClaims().get("sid").getOriginalString()),
                    () -> assertEquals("raw.logout.token", adapted.content().getRawToken()));
        }

        @Test
        @DisplayName("Should adapt an absent typ as absent, never as a default")
        void shouldAdaptAbsentTypAsAbsent() {
            SignatureVerifiedToken verified = new SignatureVerifiedToken(Map.of(), Map.of(), Set.of(), null,
                    "raw.logout.token");

            assertNull(VerifiedLogoutToken.of(verified).headerType());
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
            BackchannelLogoutReceiver.LogoutTokenVerifier seam = productionSeam(tokenValidator);

            TokenValidationException refusal = assertThrows(TokenValidationException.class, () -> seam.verify(tampered));
            assertFalse(receiverOver(tokenValidator).receive(tampered, Instant.now()).accepted(),
                    "the receiver refuses what the seam refuses");
            assertFalse(seam.keySourceUnavailable(refusal), "a bad signature is no key-source outage");
            assertEquals(1, signatureRejections(), "refused as SIGNATURE_REJECTED");
            assertEquals(0, keySourceOutages());
        }

        @Test
        @DisplayName("Should refuse a token that is not a JWT at all")
        void shouldRefuseMalformedToken() {
            BackchannelLogoutReceiver.LogoutTokenVerifier seam = productionSeam(tokenValidator);

            assertThrows(TokenValidationException.class, () -> seam.verify("not-a-jwt"));
        }

        @Test
        @DisplayName("Should refuse a signed token beyond the library's token-string limit")
        void shouldRefuseOverlongTokenString() {
            TestTokenHolder overlong = new TestTokenHolder(TokenType.ID_TOKEN,
                    ClaimControlParameter.builder().missingSubject(true).missingAuthorizedParty(true).build())
                    .withClaim("sid", ClaimValue.forPlainString(SID))
                    .withClaim("padding", ClaimValue.forPlainString("a".repeat(20_000)));
            TokenValidator validator = TokenValidator.builder().issuerConfig(overlong.getIssuerConfig()).build();
            BackchannelLogoutReceiver.LogoutTokenVerifier seam = productionSeam(validator, overlong.getIssuer());
            String raw = overlong.getRawToken();

            TokenValidationException refusal = assertThrows(TokenValidationException.class, () -> seam.verify(raw));

            assertEquals(SecurityEventCounter.EventType.TOKEN_SIZE_EXCEEDED, refusal.getEventType(),
                    "refused on the token-string bound, before any parse or key lookup");
        }
    }

    /**
     * A configured, enabled issuer whose loader still holds its keys but does not report its key set
     * usable must not verify a logout token from the keys it retained. Both arms verify the same signed
     * token against the same, really loaded key set, read through {@link StatusReportingLoader}: only the
     * status the loader reports differs, so the refusal can be attributed to that status alone.
     */
    @Nested
    @DisplayName("An issuer whose key set is not reported usable")
    class DegradedIssuer {

        @ParameterizedTest(name = "loader status {0}")
        @EnumSource(value = LoaderStatus.class, names = "OK", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("Should refuse a logout token from an issuer whose loader holds keys but does not report OK")
        void shouldRefuseTokenFromDegradedIssuer(LoaderStatus degradedStatus) {
            StatusReportingLoader loader = new StatusReportingLoader(issuerConfig.getJwksLoader(), degradedStatus);
            TokenValidator validator = validatorOver(loader);
            BackchannelLogoutReceiver.LogoutTokenVerifier seam = productionSeam(validator);
            String raw = logoutToken.getRawToken();
            assertFalse(loader.getKeyIds().isEmpty(), "precondition: the degraded loader still holds the issuer's keys");

            TokenValidationException refusal = assertThrows(TokenValidationException.class, () -> seam.verify(raw),
                    "a key set its loader does not report usable must not verify a logout token");
            BackchannelResult result = receiverOver(validator).receive(raw, Instant.now());

            assertAll("a logout token from an issuer whose loader reports " + degradedStatus,
                    () -> assertEquals(SecurityEventCounter.EventType.NO_ISSUER_CONFIG, refusal.getEventType(),
                            "precondition: the engine refuses a degraded issuer as it refuses an unknown one"),
                    () -> assertTrue(seam.keySourceUnavailable(refusal),
                            "the engine binding reads the refusal as a key-source outage"),
                    () -> assertFalse(result.accepted(), "the receiver refuses the token"),
                    () -> assertEquals(1, keySourceOutages(), "and records it as key-source-unavailable"),
                    () -> assertEquals(0, signatureRejections(), "not as a signature failure"));
        }

        @Test
        @DisplayName("Should verify the same token against the same keys when the loader reports OK (matched control)")
        void shouldVerifyTokenFromHealthyIssuer() {
            StatusReportingLoader loader = new StatusReportingLoader(issuerConfig.getJwksLoader(), LoaderStatus.OK);
            TokenValidator validator = validatorOver(loader);

            VerifiedLogoutToken verified = productionSeam(validator).verify(logoutToken.getRawToken());
            BackchannelResult result = receiverOver(validator).receive(logoutToken.getRawToken(), Instant.now());

            assertAll("the same token while the loader reports OK",
                    () -> assertEquals(SID, verified.content().getClaims().get("sid").getOriginalString(),
                            "only the reported status differs from the refusal arm, and with OK the token verifies"),
                    () -> assertTrue(result.accepted(), "and the receiver accepts it"),
                    () -> assertEquals(0, keySourceOutages(), "nothing is recorded as a key-source outage"));
        }

        /** A validator over one issuer that names the fixture token's {@code iss} and reads {@code loader}. */
        private TokenValidator validatorOver(JwksLoader loader) {
            IssuerConfig issuer = IssuerConfig.builder()
                    .issuerIdentifier(logoutToken.getIssuer())
                    .audienceValidationDisabled(true)
                    .jwksLoader(loader)
                    .build();
            return TokenValidator.builder().issuerConfig(issuer).build();
        }
    }

    /**
     * The library's refusal of an unconfigured issuer quotes the attacker-controlled {@code iss}
     * verbatim, and the back-channel logout path is unauthenticated. The receiver therefore sanitises
     * that message before its DEBUG record and attaches no throwable, so no gateway record can carry a
     * forged line (CWE-117). The token is refused on its {@code iss} before any key is consulted, so an
     * unsigned, hand-built JWT reaches that branch exactly as a signed foreign token does.
     */
    @Nested
    @DisplayName("An unknown issuer on the unauthenticated logout path")
    class UnknownIssuer {

        /*
         * The three invisible characters are built from their code points rather than written into a
         * literal, so a reader of this file sees which character each assertion names (Sonar
         * text:S6389 flags a raw bidi control in source text). JSON carries them raw; CR and LF are
         * JSON escapes the parser decodes.
         */
        private static final String LINE_SEPARATOR = Character.toString(0x2028);
        private static final String PARAGRAPH_SEPARATOR = Character.toString(0x2029);
        private static final String RIGHT_TO_LEFT_OVERRIDE = Character.toString(0x202E);

        @Test
        @DisplayName("Should fail closed and keep the hostile iss out of every gateway record in raw form")
        void shouldFailClosedWithoutForgingGatewayLogLines() {
            String marker = "forged-" + Generators.letterStrings(8, 16).next();
            String hostileIssJson = "evil\\r\\n" + marker + LINE_SEPARATOR + PARAGRAPH_SEPARATOR
                    + RIGHT_TO_LEFT_OVERRIDE;
            SheriffDebugCapture.assertDebugIsCaptured(BackchannelLogoutReceiver.class);

            BackchannelResult result = receiverOver(tokenValidator)
                    .receive(unsignedTokenWithIssuer(hostileIssJson), Instant.now());

            assertFalse(result.accepted(), "an issuer the gateway does not configure is refused");
            assertEquals(1, signatureRejections(), "refused as SIGNATURE_REJECTED");
            assertEquals(0, keySourceOutages(),
                    "the configured issuer's key source is healthy, so a foreign issuer is no key-source outage");
            List<LogRecord> gateway = gatewayRecords();
            assertAll("gateway records after an unknown-issuer logout token",
                    () -> assertTrue(gateway.stream().anyMatch(captured -> Level.FINE.equals(captured.getLevel())
                                    && SheriffDebugCapture.rendered(captured).contains("evil\\r\\n" + marker)),
                            "the refusal is recorded at DEBUG in its escaped form: " + gateway.size()),
                    () -> assertTrue(gateway.stream().noneMatch(captured -> captured.getThrown() != null),
                            "no gateway record attaches the library exception with its raw message"),
                    () -> assertTrue(gateway.stream().map(SheriffDebugCapture::rendered).noneMatch(
                                    rendered -> rendered.contains("\r") || rendered.contains("\n" + marker)
                                            || rendered.contains(LINE_SEPARATOR) || rendered.contains(PARAGRAPH_SEPARATOR)
                                            || rendered.contains(RIGHT_TO_LEFT_OVERRIDE)),
                            "no gateway record carries a raw line break or bidi control"),
                    () -> assertTrue(gateway.stream()
                                    .filter(captured -> captured.getLevel().intValue() > Level.FINE.intValue())
                                    .noneMatch(captured -> SheriffDebugCapture.rendered(captured).contains(marker)),
                            "no gateway record above DEBUG names the issuer at all"));
        }

        @Test
        @DisplayName("Should record a benign unknown iss at DEBUG unchanged (matched control)")
        void shouldRecordBenignIssuerUnchanged() {
            String benignIss = "benign-" + Generators.letterStrings(5, 40).next();
            SheriffDebugCapture.assertDebugIsCaptured(BackchannelLogoutReceiver.class);

            BackchannelResult result = receiverOver(tokenValidator)
                    .receive(unsignedTokenWithIssuer(benignIss), Instant.now());

            assertFalse(result.accepted());
            assertTrue(gatewayRecords().stream().anyMatch(captured -> Level.FINE.equals(captured.getLevel())
                            && SheriffDebugCapture.rendered(captured).contains(benignIss)),
                    "a value with nothing unsafe in it reaches the DEBUG record unchanged");
        }

        /**
         * Builds a compact JWT whose body carries only {@code iss}; the signature segment is a
         * placeholder because the issuer lookup refuses the token before any signature check.
         *
         * @param issJsonContent the {@code iss} value as JSON string content, escapes included
         */
        private static String unsignedTokenWithIssuer(String issJsonContent) {
            Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
            String header = encoder.encodeToString("{\"alg\":\"RS256\",\"typ\":\"logout+jwt\"}"
                    .getBytes(StandardCharsets.UTF_8));
            String body = encoder.encodeToString(("{\"iss\":\"" + issJsonContent + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
            String signature = encoder.encodeToString("placeholder".getBytes(StandardCharsets.UTF_8));
            return header + "." + body + "." + signature;
        }
    }

    /**
     * A hand-written loader that answers every key lookup from a real, loaded library loader and
     * reports the status the test chose — the shape of a stale-degraded delegate, which keeps answering
     * from its retained keys while it no longer reports them usable.
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
