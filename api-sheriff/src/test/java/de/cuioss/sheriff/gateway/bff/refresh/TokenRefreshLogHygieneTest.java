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
package de.cuioss.sheriff.gateway.bff.refresh;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.LogRecord;


import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.sheriff.token.client.flow.CredentialRejectedException;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;

/**
 * No log record of a transparent refresh carries a credential: not a token the session held, not a
 * token the refresh returned, not the session cookie value and not the session identity. The logger
 * is captured from {@code DEBUG} upwards and the capture is proven before the absence is stated.
 */
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@ExtendWith(SheriffDebugCapture.class)
@EnableGeneratorController
@DisplayName("Transparent refresh — no credential reaches the log at any level")
class TokenRefreshLogHygieneTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration LEEWAY = Duration.ofSeconds(60);
    private static final Instant NEAR_EXPIRY = NOW.plusSeconds(30);
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String SUBJECT = "sub-1";

    private final String sessionId = SessionRecord.newSessionId();
    private final String handle = secret();
    private final String currentAccess = secret();
    private final String currentRefresh = secret();
    private final String currentId = secret();
    private final String rotatedAccess = secret();
    private final String rotatedRefresh = secret();
    private final String rotatedId = secret();
    private final String cookieHeader = SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + handle;

    private final InMemorySessionStore store = new InMemorySessionStore(16, SESSION_TTL, Integer.MAX_VALUE,
            ended -> { });
    private final SessionBinding binding = new ServerSessionBinding(store,
            new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
    private final List<String> revoked = new ArrayList<>();

    private static String secret() {
        return Generators.letterStrings(24, 32).next();
    }

    private SessionRecord storedSession() {
        SessionRecord live = SessionRecord.builder().sessionId(sessionId)
                .accessToken(currentAccess).refreshToken(currentRefresh).idToken(currentId)
                .sub(SUBJECT).expiresAt(NOW.plus(SESSION_TTL)).build();
        store.create(live, handle, NOW);
        return live;
    }

    private RotationResult rotation() {
        Map<String, ClaimValue> claims = new HashMap<>();
        claims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        return new RotationResult(new AccessTokenContent(claims, rotatedAccess), rotatedRefresh, rotatedId, 300L,
                true, null, RotationResult.ScopeDelta.UNDECLARED);
    }

    private Map<String, String> credentials() {
        return Map.of(
                "access token the session held", currentAccess,
                "refresh token the session held", currentRefresh,
                "ID token the session held", currentId,
                "access token the refresh returned", rotatedAccess,
                "refresh token the refresh returned", rotatedRefresh,
                "ID token the refresh returned", rotatedId,
                "session cookie value", handle,
                "session identity", sessionId);
    }

    private static void assertNowhereInTheLog(List<LogRecord> captured, Map<String, String> credentials) {
        SheriffDebugCapture.assertDebugIsCaptured(TokenRefreshCoordinator.class);
        List<String> lines = captured.stream().map(SheriffDebugCapture::rendered).toList();
        assertAll("no credential in a log record of any level", credentials.entrySet().stream()
                .map(credential -> (Executable) () -> assertEquals(0,
                        lines.stream().filter(line -> line.contains(credential.getValue())).count(),
                        "log records carrying the " + credential.getKey())));
    }

    @Test
    @DisplayName("a refresh that rotates the tokens logs none of them, nor the cookie value or the session identity")
    void successfulRefreshLogsNoCredential() {
        SessionRecord live = storedSession();
        TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR_EXPIRY,
                (presented, scopes) -> rotation(), binding, revoked::add, Runnable::run, EndedRefreshTokens.inert());

        coordinator.refresh(live, cookieHeader, NOW);

        assertEquals(Optional.of(rotatedAccess), binding.resolve(cookieHeader, NOW).map(SessionRecord::accessToken),
                "precondition: the refresh ran and the session holds the rotated token");
        assertNowhereInTheLog(SheriffDebugCapture.capturedRecords(), credentials());
    }

    @Test
    @DisplayName("a refresh the provider refuses ends the session and logs no token, cookie value or session identity")
    void refusedRefreshLogsNoCredential() {
        SessionRecord live = storedSession();
        TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR_EXPIRY,
                (presented, scopes) -> {
                    throw new CredentialRejectedException("Token endpoint rejected the credential with HTTP 400");
                }, binding, revoked::add, Runnable::run, EndedRefreshTokens.inert());

        coordinator.refresh(live, cookieHeader, NOW);

        List<LogRecord> captured = SheriffDebugCapture.capturedRecords();
        assertAll("precondition: the refusal ended the session and was recorded",
                () -> assertEquals(Optional.empty(), binding.resolve(cookieHeader, NOW)),
                () -> assertTrue(captured.stream().anyMatch(written ->
                                TokenRefreshCoordinator.class.getName().equals(written.getLoggerName())),
                        "the coordinator wrote a record for the refused refresh"));
        assertNowhereInTheLog(captured, credentials());
    }
}
