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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow.AuthorizationInitiation;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.reserved.LoginInitiationEndpoint.LoginInitiationOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link LoginInitiationEndpoint}: the login-initiation reserved endpoint. The
 * security-relevant contracts under test are that an unauthenticated caller is driven into the D5
 * auth-code flow (binding cookie set, engine authorization reached) and returns to the same-origin-
 * validated return URL; that an off-origin, schema-relative, or unparseable {@code returnUrl} can
 * never become the redirect location (never an open redirect) on either path; and — the explicitly
 * defined behaviour — that an already-authenticated caller short-circuits straight to the validated
 * return URL <em>without</em> a fresh auth-code flow (no engine authorization, no pending record, no
 * binding cookie).
 * <p>
 * The endpoint is framework-agnostic, so it is exercised with the real {@link LoginFlow} over a
 * hand-built {@link AuthorizationInitiation} seam (a counter proves whether the fresh flow ran), a
 * real {@link InMemorySessionStore}, and the real {@link SessionCookieCodec} — no container, no live
 * IdP, no test double framework.
 */
class LoginInitiationEndpointTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String AUTHORIZATION_URL = "https://idp.example.com/authorize?client_id=api-sheriff";
    private static final String SESSION_ID = "opaque-session-1";
    private static final String SUBJECT = "user-sub-1";

    private PendingAuthorizationStore.InMemory pendingStore;
    private BindingCookieCodec bindingCodec;
    private InMemorySessionStore sessionStore;
    private SessionCookieCodec sessionCodec;
    private AtomicInteger authorizeCalls;
    private LoginInitiationEndpoint endpoint;

    @BeforeEach
    void setUp() {
        pendingStore = new PendingAuthorizationStore.InMemory(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        FlowContext flowContext = FlowContext.create(GATEWAY_ORIGIN + "/auth/callback");
        AuthorizationCodeFlow.AuthorizationRedirect redirect =
                new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
        authorizeCalls = new AtomicInteger();
        AuthorizationInitiation authorization = () -> {
            authorizeCalls.incrementAndGet();
            return redirect;
        };
        LoginFlow loginFlow = new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN);

        sessionStore = new InMemorySessionStore(16);
        sessionCodec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL);
        endpoint = new LoginInitiationEndpoint(loginFlow, new ServerSessionBinding(sessionStore, sessionCodec),
                GATEWAY_ORIGIN);
    }

    /** Creates a live session and returns the request {@code Cookie} header that resolves it. */
    private String liveSessionCookie() {
        SessionRecord session = SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken("raw-access-token")
                .idToken("raw-id-token")
                .sub(SUBJECT)
                .expiresAt(T0.plus(SESSION_TTL))
                .acr(null)
                .authTime(null)
                .build();
        sessionStore.create(session, T0);
        return sessionCodec.toSetCookieHeader(SESSION_ID).split(";", 2)[0];
    }

    /** Consumes the pending record bound by the binding cookie the fresh flow set. */
    private PendingAuthorizationRecord consumeBoundRecord(LoginInitiationOutcome outcome) {
        String cookie = outcome.setCookieHeaders().getFirst().split(";", 2)[0];
        Optional<String> recordId = bindingCodec.readRecordId(cookie);
        assertTrue(recordId.isPresent(), "a fresh login sets a binding cookie carrying the record id");
        return pendingStore.consume(recordId.get(), T0).orElseThrow();
    }

    @Nested
    @DisplayName("Unauthenticated caller (drives the auth-code flow)")
    class Unauthenticated {

        @Test
        @DisplayName("Should redirect to the engine authorization URL and set the binding cookie")
        void shouldDriveAuthCodeFlow() {
            LoginInitiationOutcome outcome = endpoint.initiate("/dashboard", null, T0);

            assertEquals(302, outcome.status());
            assertTrue(outcome.isRedirect());
            assertEquals(AUTHORIZATION_URL, outcome.location(), "the URL comes from the engine, unchanged");
            assertEquals(1, authorizeCalls.get(), "the fresh auth-code flow was driven exactly once");
            assertEquals(1, outcome.setCookieHeaders().size());
            assertTrue(outcome.setCookieHeaders().getFirst().startsWith(BindingCookieCodec.COOKIE_NAME + "="));
        }

        @Test
        @DisplayName("Should return to the same-origin-validated return URL after the callback")
        void shouldRecordSameOriginReturnUrl() {
            LoginInitiationOutcome outcome = endpoint.initiate("/dashboard", null, T0);

            assertEquals("/dashboard", consumeBoundRecord(outcome).returnUrl());
        }

        @ParameterizedTest(name = "off-origin returnUrl \"{0}\" falls back to the default landing")
        @ValueSource(strings = {"https://evil.example.com/app", "//evil.example.com", "javascript:alert(1)"})
        @DisplayName("Should fall back to the default landing for an off-origin return URL (never an open redirect)")
        void shouldRejectOffOriginReturnUrl(String returnUrl) {
            LoginInitiationOutcome outcome = endpoint.initiate(returnUrl, null, T0);

            assertEquals(LoginFlow.DEFAULT_RETURN_URL, consumeBoundRecord(outcome).returnUrl());
        }

        @Test
        @DisplayName("Should treat an expired session as unauthenticated and drive a fresh flow")
        void shouldTreatExpiredSessionAsUnauthenticated() {
            String cookie = liveSessionCookie();

            LoginInitiationOutcome outcome = endpoint.initiate("/dashboard", cookie, T0.plus(SESSION_TTL).plusSeconds(1));

            assertEquals(AUTHORIZATION_URL, outcome.location(), "an expired session cannot short-circuit");
            assertEquals(1, authorizeCalls.get());
        }
    }

    @Nested
    @DisplayName("Already-authenticated caller (short-circuit, no fresh flow)")
    class AlreadyAuthenticated {

        @Test
        @DisplayName("Should redirect straight to the validated return URL without a fresh auth-code flow")
        void shouldShortCircuitToReturnUrl() {
            String cookie = liveSessionCookie();

            LoginInitiationOutcome outcome = endpoint.initiate("/dashboard", cookie, T0);

            assertEquals(302, outcome.status());
            assertEquals("/dashboard", outcome.location(), "a live session lands straight on the return URL");
            assertEquals(0, authorizeCalls.get(), "no fresh auth-code flow is driven for an authenticated caller");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "no binding cookie — there is no new login transaction");
        }

        @ParameterizedTest(name = "off-origin returnUrl \"{0}\" is refused on the short-circuit too")
        @ValueSource(strings = {"https://evil.example.com", "//evil.example.com", "not a uri"})
        @DisplayName("Should refuse an off-origin return URL on the short-circuit (never an open redirect)")
        void shouldRejectOffOriginReturnUrlOnShortCircuit(String returnUrl) {
            String cookie = liveSessionCookie();

            LoginInitiationOutcome outcome = endpoint.initiate(returnUrl, cookie, T0);

            assertEquals(LoginFlow.DEFAULT_RETURN_URL, outcome.location(), "an off-origin target can never be the location");
            assertEquals(0, authorizeCalls.get());
        }

        @Test
        @DisplayName("Should fall back to the default landing when no return URL is supplied")
        void shouldFallBackForNullReturnUrl() {
            String cookie = liveSessionCookie();

            LoginInitiationOutcome outcome = endpoint.initiate(null, cookie, T0);

            assertEquals(LoginFlow.DEFAULT_RETURN_URL, outcome.location());
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject a null reference instant")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> endpoint.initiate("/dashboard", null, null));
        }
    }
}
