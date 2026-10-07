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
package de.cuioss.sheriff.gateway.bff.reserved;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.LogRecord;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint.CallbackOutcome;
import de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint.CodeExchange;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * No log line of the login callback or of the logout carries a credential: not a session cookie value,
 * a session identity, a token, an authorization code, a {@code state} value or the login-binding
 * record id. The logger is captured from {@code DEBUG} upwards, and each case first proves that a
 * {@code DEBUG} line of the code under test is captured, so an empty log cannot pass for a clean one.
 */
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@ExtendWith(SheriffDebugCapture.class)
@EnableGeneratorController
@DisplayName("Login callback and logout — no credential reaches the log at any level")
class ReservedEndpointLogHygieneTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String ORIGIN = "https://gw.example.com";
    private static final String CALLBACK_URI = ORIGIN + "/auth/callback";
    private static final String SUBJECT = "user-sub-1";

    private final String accessToken = secret();
    private final String idToken = secret();
    private final String refreshToken = secret();
    private final String code = secret();

    private PendingAuthorizationStore.InMemory pendingStore;
    private BindingCookieCodec bindingCodec;
    private SessionBinding sessionBinding;
    private String state;
    private String recordId;
    private String bindingCookie;

    private static String secret() {
        return Generators.letterStrings(24, 32).next();
    }

    @BeforeEach
    void setUp() {
        pendingStore = new PendingAuthorizationStore.InMemory(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        sessionBinding = new ServerSessionBinding(
                new InMemorySessionStore(16, SESSION_TTL, Integer.MAX_VALUE, sessionId -> { }),
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
        FlowContext flow = FlowContext.create(CALLBACK_URI);
        state = flow.state();
        PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow, "/dashboard", List.of("openid"),
                NOW);
        pendingStore.store(pending);
        recordId = pending.id();
        bindingCookie = pair(bindingCodec.toSetCookieHeader(recordId));
    }

    private CallbackEndpoint callback(CodeExchange exchange) {
        SessionWidening widening = new SessionWidening((scopes, silent) -> {
            throw new AssertionError("no widening is driven here");
        }, pendingStore, bindingCodec, ORIGIN, "/dashboard");
        return new CallbackEndpoint(exchange, pendingStore, bindingCodec, sessionBinding, SESSION_TTL, widening);
    }

    private CodeExchange successfulExchange() {
        Map<String, ClaimValue> accessClaims = new HashMap<>();
        accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        Map<String, ClaimValue> idClaims = new HashMap<>();
        idClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        AuthorizationCodeFlow.AuthenticationResult result = new AuthorizationCodeFlow.AuthenticationResult(
                new AccessTokenContent(accessClaims, accessToken), new IdTokenContent(idClaims, idToken),
                refreshToken);
        return (context, params) -> result;
    }

    private static String pair(String setCookie) {
        return setCookie.split(";", 2)[0];
    }

    private static String valueOf(String cookiePair) {
        return cookiePair.substring(cookiePair.indexOf('=') + 1);
    }

    /** Binds an earlier session and returns its request-cookie pair. */
    private String earlierSession() {
        return pair(sessionBinding.bind(SessionRecord.builder().sessionId(SessionRecord.newSessionId())
                .accessToken(secret()).idToken(secret()).sub(SUBJECT)
                .expiresAt(NOW.plus(SESSION_TTL)).build(), NOW).setCookieHeaders().getFirst());
    }

    /**
     * Asserts that the code under test wrote at least one record through the logger of
     * {@code wroteDebug}, that {@code DEBUG} records of the reserved endpoints are captured at all, and
     * that no captured record — its message or the message of a throwable it chains — carries one of
     * {@code credentials}.
     */
    private static void assertNowhereInTheLog(Class<?> wroteDebug, Map<String, String> credentials) {
        List<LogRecord> captured = SheriffDebugCapture.capturedRecords();
        assertTrue(captured.stream().anyMatch(written -> wroteDebug.getName().equals(written.getLoggerName())),
                "the case wrote no record through " + wroteDebug.getSimpleName()
                        + ", so it would say nothing about what that class logs");
        SheriffDebugCapture.assertDebugIsCaptured(CallbackEndpoint.class, LogoutEndpoint.class,
                RpInitiatedLogout.class);
        List<String> lines = captured.stream().map(SheriffDebugCapture::rendered).toList();
        assertAll("no credential in a log record of any level", credentials.entrySet().stream()
                .map(credential -> (Executable) () -> assertEquals(0,
                        lines.stream().filter(line -> line.contains(credential.getValue())).count(),
                        "log records carrying the " + credential.getKey())));
    }

    @Test
    @DisplayName("a completed login logs neither the code, the state, a cookie value, the session identity nor a token")
    void completedLoginLogsNoCredential() {
        String earlier = earlierSession();

        CallbackOutcome outcome = callback(successfulExchange())
                .handle("code=" + code + "&state=" + state, bindingCookie + "; " + earlier, NOW);

        assertEquals(302, outcome.status(), "precondition: the login completed");
        String newCookie = pair(outcome.setCookieHeaders().getFirst());
        String sessionId = sessionBinding.resolve(newCookie, NOW).orElseThrow().sessionId();
        // A login that ends the session the browser presented writes one DEBUG line.
        assertNowhereInTheLog(CallbackEndpoint.class, Map.of(
                "authorization code", code,
                "state", state,
                "login-binding record id", recordId,
                "new session cookie value", valueOf(newCookie),
                "earlier session cookie value", valueOf(earlier),
                "session identity", sessionId,
                "access token", accessToken,
                "ID token", idToken,
                "refresh token", refreshToken));
    }

    static Stream<Arguments> refusedCallbacks() {
        return Stream.of(
                Arguments.of("a duplicated code", "code=%1$s&code=%1$s&state=%2$s", true),
                Arguments.of("a missing state", "code=%1$s", true),
                Arguments.of("no binding cookie", "code=%1$s&state=%2$s", false),
                Arguments.of("a state that does not match", "code=%1$s&state=%3$s", true),
                Arguments.of("an identity-provider error", "error=access_denied&error_description=%3$s&state=%2$s",
                        true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusedCallbacks")
    @DisplayName("a refused callback logs neither the code, a state value, a cookie value nor the provider's text")
    void refusedCallbackLogsNoCredential(String what, String query, boolean withBindingCookie) {
        String foreign = secret();
        String earlier = earlierSession();
        String cookies = withBindingCookie ? bindingCookie + "; " + earlier : earlier;

        CallbackOutcome outcome = callback(successfulExchange()).handle(query.formatted(code, state, foreign), cookies,
                NOW);

        assertEquals(4, outcome.status() / 100, "precondition: the callback is refused for " + what);
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG, "OIDC callback");
        assertNowhereInTheLog(CallbackEndpoint.class, Map.of(
                "authorization code", code,
                "state", state,
                "request-supplied text", foreign,
                "login-binding record id", recordId,
                "session cookie value", valueOf(earlier)));
    }

    @Test
    @DisplayName("a failed code exchange logs neither the code, the state nor a cookie value")
    void failedExchangeLogsNoCredential() {
        String earlier = earlierSession();
        CodeExchange failing = (context, params) -> {
            throw new ClientProtocolException("token endpoint rejected the code");
        };

        CallbackOutcome outcome = callback(failing).handle("code=" + code + "&state=" + state,
                bindingCookie + "; " + earlier, NOW);

        assertEquals(400, outcome.status(), "precondition: the exchange failure is answered 400");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG, "OIDC callback code exchange");
        assertNowhereInTheLog(CallbackEndpoint.class, Map.of(
                "authorization code", code,
                "state", state,
                "login-binding record id", recordId,
                "session cookie value", valueOf(earlier)));
    }

    @Test
    @DisplayName("a logout logs neither the ID token, the session cookie value, the session identity nor the logout state")
    void logoutLogsNoCredential() {
        SessionRecord session = SessionRecord.builder().sessionId(SessionRecord.newSessionId())
                .accessToken(accessToken).idToken(idToken).refreshToken(refreshToken).sub(SUBJECT)
                .expiresAt(NOW.plus(SESSION_TTL)).build();
        String cookie = pair(sessionBinding.bind(session, NOW).setCookieHeaders().getFirst());
        EndSessionFlow endSessionFlow = new EndSessionFlow(
                new PostLogoutRedirectValidator(Set.of(ORIGIN + "/auth/logout/return")));
        LogoutEndpoint withEndpoint = new LogoutEndpoint(new RpInitiatedLogout(endSessionFlow, ended -> { },
                () -> Optional.of("https://idp.example.com/logout"), ORIGIN + "/auth/logout/return", "/",
                Duration.ofMinutes(1)), sessionBinding);
        LogoutEndpoint withoutEndpoint = new LogoutEndpoint(new RpInitiatedLogout(endSessionFlow, ended -> { },
                Optional::empty, ORIGIN + "/auth/logout/return", "/", Duration.ofMinutes(1)), sessionBinding);

        LogoutEndpoint.LogoutOutcome toProvider = withEndpoint.logout(cookie, NOW);
        LogoutEndpoint.LogoutOutcome local = withoutEndpoint.logout(
                pair(sessionBinding.bind(session, NOW).setCookieHeaders().getFirst()), NOW);

        assertEquals(List.of(302, 302), List.of(toProvider.status(), local.status()),
                "precondition: both logouts were answered");
        // The record a logout writes when the provider publishes no end-session endpoint.
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, "135");
        Map<String, String> credentials = new HashMap<>(Map.of(
                "ID token", idToken,
                "access token", accessToken,
                "refresh token", refreshToken,
                "session cookie value", valueOf(cookie),
                "session identity", session.sessionId()));
        toProvider.setCookieHeaders().stream().map(ReservedEndpointLogHygieneTest::pair)
                .filter(line -> !valueOf(line).isEmpty())
                .forEach(line -> credentials.put("cookie " + line.substring(0, line.indexOf('=')), valueOf(line)));
        assertNowhereInTheLog(RpInitiatedLogout.class, credentials);
    }
}
