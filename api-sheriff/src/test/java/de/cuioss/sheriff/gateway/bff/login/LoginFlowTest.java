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
package de.cuioss.sheriff.gateway.bff.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.login.LoginFlow.AuthorizationInitiation;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow.LoginRedirect;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link LoginFlow}: the login-initiation orchestration — engine-driven authorization,
 * pending-record persistence, the browser-binding cookie, and the same-origin return-URL guard.
 * <p>
 * The engine authorization is driven through the {@link AuthorizationInitiation} seam with a
 * hand-built {@link AuthorizationCodeFlow.AuthorizationRedirect}, so the flow is exercised without a
 * live IdP or discovery round-trip.
 */
class LoginFlowTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String AUTHORIZATION_URL = "https://idp.example.com/authorize?client_id=api-sheriff";

    private PendingAuthorizationStore.InMemory pendingStore;
    private BindingCookieCodec bindingCodec;
    private FlowContext flowContext;
    private LoginFlow loginFlow;

    @BeforeEach
    void setUp() {
        pendingStore = new PendingAuthorizationStore.InMemory(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        flowContext = FlowContext.create(GATEWAY_ORIGIN + "/auth/callback");
        AuthorizationCodeFlow.AuthorizationRedirect redirect =
                new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
        AuthorizationInitiation authorization = () -> redirect;
        loginFlow = new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN);
    }

    private PendingAuthorizationRecord consumeBoundRecord(LoginRedirect result) {
        String cookieHeader = result.setCookieHeaders().getFirst().split(";", 2)[0];
        Optional<String> recordId = bindingCodec.readRecordId(cookieHeader);
        assertTrue(recordId.isPresent(), "the login sets a binding cookie carrying the record id");
        return pendingStore.consume(recordId.get(), T0).orElseThrow();
    }

    @Nested
    @DisplayName("Engine-driven redirect")
    class Redirect {

        @Test
        @DisplayName("Should redirect to the engine authorization URL and set the binding cookie")
        void shouldRedirectToAuthorizationUrl() {
            LoginRedirect result = loginFlow.initiate("/dashboard", T0);

            assertEquals(AUTHORIZATION_URL, result.authorizationUrl(), "the URL comes from the engine, unchanged");
            assertEquals(1, result.setCookieHeaders().size());
            assertTrue(result.setCookieHeaders().getFirst().startsWith(BindingCookieCodec.COOKIE_NAME + "="));
        }

        @Test
        @DisplayName("Should persist the engine FlowContext as a single-use pending record bound to the browser")
        void shouldPersistPendingRecord() {
            LoginRedirect result = loginFlow.initiate("/dashboard", T0);

            PendingAuthorizationRecord pending = consumeBoundRecord(result);
            assertSame(flowContext, pending.flowContext(), "the record wraps the engine context, never re-invents it");
            assertEquals("/dashboard", pending.returnUrl());
            assertEquals(T0, pending.createdAt());
        }
    }

    @Nested
    @DisplayName("Same-origin return-URL guard (never an open redirect)")
    class ReturnUrlGuard {

        @ParameterizedTest(name = "same-origin return URL \"{0}\" is recorded verbatim")
        @ValueSource(strings = {"/dashboard", "/app/page?x=1", "https://gw.example.com/app"})
        @DisplayName("Should record a same-origin return URL verbatim")
        void shouldRecordSameOriginReturnUrl(String returnUrl) {
            LoginRedirect result = loginFlow.initiate(returnUrl, T0);

            assertEquals(returnUrl, consumeBoundRecord(result).returnUrl());
        }

        @ParameterizedTest(name = "off-origin return URL \"{0}\" falls back to the default landing")
        @ValueSource(strings = {"https://evil.example.com/app", "//evil.example.com", "javascript:alert(1)"})
        @DisplayName("Should fall back to the default landing for a cross-origin or unparseable return URL")
        void shouldFallBackForOffOrigin(String returnUrl) {
            LoginRedirect result = loginFlow.initiate(returnUrl, T0);

            assertEquals(LoginFlow.DEFAULT_RETURN_URL, consumeBoundRecord(result).returnUrl());
        }

        @Test
        @DisplayName("Should fall back to the default landing when no return URL is supplied")
        void shouldFallBackForNull() {
            LoginRedirect result = loginFlow.initiate(null, T0);

            assertEquals(LoginFlow.DEFAULT_RETURN_URL, consumeBoundRecord(result).returnUrl());
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject a null reference instant")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> loginFlow.initiate("/dashboard", null));
        }
    }
}
