/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
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
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CallbackEndpoint}: the OIDC auth-code callback orchestration — the BFF-13
 * duplicate-parameter rejection (via {@code parse(rawQuery)}), the browser-binding checks (D2b),
 * and the success path that drives the exchange seam, creates the session, and redirects.
 * <p>
 * <strong>Every case below is a {@code response_mode=query} callback.</strong> The gateway drives
 * the authorization request with {@code response_mode=query}, so the live callback is a top-level
 * GET whose {@code code}/{@code state} arrive in the query string, and the string each test hands
 * {@code handle(..)} IS that raw query. The endpoint itself stays source-neutral by design — it
 * parses whatever raw parameter string it is given — which is exactly why the assertion that the
 * <em>runtime</em> hands it the uncollapsed query lives one layer out, in
 * {@code GatewayEdgeRouteBffWiringTest}'s query-mode dispatch coverage rather than here.
 * <p>
 * The engine exchange is driven through the {@link CodeExchange} seam, so the success and
 * exchange-failure paths are exercised with a hand-built {@link AuthorizationCodeFlow.AuthenticationResult}
 * — no live token endpoint, no signed tokens, no test double framework.
 */
class CallbackEndpointTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String RETURN_URL = "/dashboard";
    private static final String SUBJECT = "user-sub-1";
    private static final String RAW_ACCESS_TOKEN = "raw-access-token";
    private static final String RAW_ID_TOKEN = "raw-id-token";
    private static final String IDP_SID = "idp-sid-9";

    private PendingAuthorizationStore.InMemory pendingStore;
    private BindingCookieCodec bindingCodec;
    private InMemorySessionStore sessionStore;
    private SessionCookieCodec sessionCodec;
    private SessionBinding sessionBinding;
    private CallbackEndpoint endpoint;

    private String state;
    private String recordId;
    private String bindingCookieHeader;

    @BeforeEach
    void setUp() {
        pendingStore = new PendingAuthorizationStore.InMemory(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        sessionStore = new InMemorySessionStore(16);
        sessionCodec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL);
        sessionBinding = new ServerSessionBinding(sessionStore, sessionCodec);
        endpoint = new CallbackEndpoint(successfulExchange(), pendingStore, bindingCodec, sessionBinding, SESSION_TTL);

        FlowContext flow = FlowContext.create("https://gw.example.com/auth/callback");
        state = flow.state();
        PendingAuthorizationRecord pending = PendingAuthorizationRecord.create(flow, RETURN_URL, T0);
        pendingStore.store(pending);
        recordId = pending.id();
        bindingCookieHeader = bindingCodec.toSetCookieHeader(recordId).split(";", 2)[0];
    }

    private static CodeExchange successfulExchange() {
        Map<String, ClaimValue> accessClaims = new HashMap<>();
        accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        AccessTokenContent access = new AccessTokenContent(accessClaims, RAW_ACCESS_TOKEN);

        Map<String, ClaimValue> idClaims = new HashMap<>(Map.of(
                ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT),
                "sid", ClaimValue.forPlainString(IDP_SID),
                "acr", ClaimValue.forPlainString("urn:mace:incommon:iap:silver"),
                "auth_time", ClaimValue.forPlainString("1721730000")));
        IdTokenContent id = new IdTokenContent(idClaims, RAW_ID_TOKEN);

        AuthorizationCodeFlow.AuthenticationResult result = new AuthorizationCodeFlow.AuthenticationResult(access, id);
        return (context, params) -> result;
    }

    /**
     * An exchange whose validated ID token is far larger than the browser-safe cookie budget — the
     * realistic driver of the documented {@code bind()} failure in stateless cookie mode.
     */
    private static CodeExchange oversizedExchange() {
        Map<String, ClaimValue> accessClaims = new HashMap<>();
        accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        AccessTokenContent access = new AccessTokenContent(accessClaims, RAW_ACCESS_TOKEN);

        Map<String, ClaimValue> idClaims = new HashMap<>();
        idClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
        IdTokenContent id = new IdTokenContent(idClaims, "x".repeat(16_384));

        AuthorizationCodeFlow.AuthenticationResult result = new AuthorizationCodeFlow.AuthenticationResult(access, id);
        return (context, params) -> result;
    }

    /** The stateless cookie binding — {@code bind()} refuses a session past the cookie-size budget. */
    private static SessionBinding cookieBinding() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x11);
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 0x22);
        return new CookieSessionBinding(
                new SealedSessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL,
                        SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, new SecretKeySpec(key, "AES"), (byte) 1),
                salt);
    }

    /**
     * The BFF-13 duplicate-parameter defence — the Keycloak CVE-2026-9689 class — asserted on the
     * raw query, which under {@code response_mode=query} is the live callback shape.
     * <p>
     * The defence is structural: the endpoint parses the raw string with
     * {@code CallbackParameters.parse(String)} and never {@code CallbackParameters.of(Map)}, because
     * only the raw parse can still SEE a duplicated {@code code} or {@code state}. A collapsed map
     * has already silently chosen one occurrence by the time the endpoint runs.
     */
    @Nested
    @DisplayName("BFF-13 duplicate-parameter rejection on the raw query")
    class DuplicateParameterRejection {

        @Test
        @DisplayName("Should reject a duplicate-code callback 400 without consuming the pending record")
        void shouldRejectDuplicateCode() {
            CallbackOutcome outcome = endpoint.handle("code=first&code=second&state=" + state, bindingCookieHeader, T0);

            assertEquals(400, outcome.status(), "a duplicated code is rejected by parse(rawQuery)");
            assertFalse(outcome.isRedirect());
            assertTrue(pendingStore.consume(recordId, T0).isPresent(),
                    "the record is untouched — parse fails before binding resolution");
        }

        @Test
        @DisplayName("Should reject a duplicate-state callback 400 without consuming the pending record")
        void shouldRejectDuplicateState() {
            CallbackOutcome outcome = endpoint.handle("code=abc&state=" + state + "&state=other", bindingCookieHeader,
                    T0);

            assertEquals(400, outcome.status(),
                    "state is the parameter the binding check compares, so a duplicate must be refused "
                            + "outright rather than resolved to whichever occurrence a parser happened to pick");
            assertTrue(pendingStore.consume(recordId, T0).isPresent(),
                    "the record is untouched — parse fails before binding resolution");
        }
    }

    @Nested
    @DisplayName("Browser-binding checks (D2b)")
    class BrowserBinding {

        @Test
        @DisplayName("Should reject a callback that carries no binding cookie 403 (cross-browser replay)")
        void shouldRejectWithoutBindingCookie() {
            CallbackOutcome outcome = endpoint.handle("code=abc&state=" + state, null, T0);

            assertEquals(403, outcome.status());
            assertTrue(pendingStore.consume(recordId, T0).isPresent(), "the record was never resolved");
        }

        @Test
        @DisplayName("Should reject a binding cookie that resolves no live record 403")
        void shouldRejectUnknownRecord() {
            String foreignCookie = bindingCodec.toSetCookieHeader("nonexistent-id").split(";", 2)[0];

            CallbackOutcome outcome = endpoint.handle("code=abc&state=" + state, foreignCookie, T0);

            assertEquals(403, outcome.status());
        }

        @Test
        @DisplayName("Should reject a returned state that does not match the bound record 403")
        void shouldRejectStateMismatch() {
            CallbackOutcome outcome = endpoint.handle("code=abc&state=not-the-bound-state", bindingCookieHeader, T0);

            assertEquals(403, outcome.status());
        }

        @Test
        @DisplayName("Should reject an expired pending record 403")
        void shouldRejectExpiredRecord() {
            Instant afterTtl = T0.plus(PendingAuthorizationRecord.FIXED_TTL).plusSeconds(1);

            CallbackOutcome outcome = endpoint.handle("code=abc&state=" + state, bindingCookieHeader, afterTtl);

            assertEquals(403, outcome.status());
        }
    }

    @Nested
    @DisplayName("IdP error and exchange failure")
    class FailurePaths {

        @Test
        @DisplayName("Should reject an IdP error response 400")
        void shouldRejectIdpError() {
            CallbackOutcome outcome = endpoint.handle("error=access_denied&error_description=nope&state=" + state,
                    bindingCookieHeader, T0);

            assertEquals(400, outcome.status());
        }

        @Test
        @DisplayName("Should reject a missing state parameter 400")
        void shouldRejectMissingState() {
            CallbackOutcome outcome = endpoint.handle("code=abc", bindingCookieHeader, T0);

            assertEquals(400, outcome.status());
        }

        @Test
        @DisplayName("Should map an engine exchange failure to 400")
        void shouldMapExchangeFailure() {
            CodeExchange failing = (context, params) -> {
                throw new ClientProtocolException("token endpoint rejected the code");
            };
            CallbackEndpoint failingEndpoint = new CallbackEndpoint(failing, pendingStore, bindingCodec, sessionBinding,
                    SESSION_TTL);

            CallbackOutcome outcome = failingEndpoint.handle("code=abc&state=" + state, bindingCookieHeader, T0);

            assertEquals(400, outcome.status());
        }

        @Test
        @DisplayName("Should map a binding failure to 500 rather than letting IllegalStateException escape handle()")
        void shouldMapBindFailureTo500() {
            CallbackEndpoint bindFailingEndpoint = new CallbackEndpoint(oversizedExchange(), pendingStore, bindingCodec,
                    cookieBinding(), SESSION_TTL);

            CallbackOutcome outcome = assertDoesNotThrow(
                    () -> bindFailingEndpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0),
                    "a session the binding cannot hold is a clean outcome, never an escaping IllegalStateException");

            assertEquals(500, outcome.status(),
                    "the exchange succeeded and the caller did nothing wrong — an unbindable session is a "
                            + "gateway-side 500, not a 4xx");
            assertFalse(outcome.isRedirect());
            assertTrue(outcome.setCookieHeaders().isEmpty(), "a failed binding emits no session cookie");
        }
    }

    @Nested
    @DisplayName("Successful login")
    class SuccessfulLogin {

        @Test
        @DisplayName("Should create a session, set the cookies, and redirect to the return URL")
        void shouldCompleteLogin() {
            CallbackOutcome outcome = endpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);

            assertTrue(outcome.isRedirect(), "a successful login is a 302 redirect");
            assertEquals(302, outcome.status());
            assertEquals(Optional.of(RETURN_URL), outcome.location());
            assertEquals(2, outcome.setCookieHeaders().size(), "one session cookie + one binding-clearing cookie");

            String sessionSetCookie = outcome.setCookieHeaders().getFirst();
            assertTrue(sessionSetCookie.startsWith(SessionCookieCodec.DEFAULT_COOKIE_NAME + "="), sessionSetCookie);
            String bindingClear = outcome.setCookieHeaders().get(1);
            assertTrue(bindingClear.contains(BindingCookieCodec.COOKIE_NAME + "="), bindingClear);
            assertTrue(bindingClear.contains("Max-Age=0"), "the single-use binding cookie is cleared");
        }

        @Test
        @DisplayName("Should store the mediated token material and identity claims under the session id")
        void shouldStoreSession() {
            CallbackOutcome outcome = endpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);

            String sessionId = sessionCodec.readSessionId(outcome.setCookieHeaders().getFirst()).orElseThrow();
            Optional<SessionRecord> session = sessionStore.resolve(sessionId, T0);

            assertTrue(session.isPresent(), "the session was created under the opaque id from the cookie");
            SessionRecord sessionRecord = session.get();
            assertEquals(RAW_ACCESS_TOKEN, sessionRecord.accessToken());
            assertEquals(RAW_ID_TOKEN, sessionRecord.idToken());
            assertEquals(SUBJECT, sessionRecord.sub());
            assertEquals(Optional.of(IDP_SID), sessionRecord.sid());
            assertEquals(T0.plus(SESSION_TTL), sessionRecord.expiresAt(), "the session TTL is absolute from login");
        }

        @Test
        @DisplayName("Should consume the pending record exactly once (single-use)")
        void shouldConsumePendingRecordOnce() {
            endpoint.handle("code=auth-code&state=" + state, bindingCookieHeader, T0);

            assertTrue(pendingStore.consume(recordId, T0).isEmpty(), "the pending record was consumed by the callback");
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject a null raw query")
        void shouldRejectNullRawQuery() {
            assertThrows(NullPointerException.class, () -> endpoint.handle(null, bindingCookieHeader, T0));
        }

        @Test
        @DisplayName("Should reject a null reference instant")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> endpoint.handle("code=abc&state=" + state, bindingCookieHeader,
                    null));
        }
    }
}
