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

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import de.cuioss.sheriff.gateway.bff.login.LoginFlow.AuthorizationInitiation;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow.LoginRedirect;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.InMemoryPendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.testsupport.SettableClock;
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
    /** No time passes during a push unless a case moves it. */
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String AUTHORIZATION_URL = "https://idp.example.com/authorize?client_id=api-sheriff";
    /** A configured {@code oidc.login.default_return_url} distinct from {@code /}. */
    private static final String CONFIGURED_DEFAULT = "/home";
    /** The scope set a caller names for the login; the flow forwards it to the seam unchanged. */
    private static final List<String> SCOPES = List.of("openid", "profile", "orders:read");
    /** The requester source the login is counted under. */
    private static final String QUOTA_KEY = "192.0.2.10";
    private static final String OTHER_QUOTA_KEY = "198.51.100.20";

    private InMemoryPendingAuthorizationStore pendingStore;
    private BindingCookieCodec bindingCodec;
    private FlowContext flowContext;
    private AtomicReference<Collection<String>> requestedScopes;
    private AuthorizationInitiation authorization;
    private LoginFlow loginFlow;

    @BeforeEach
    void setUp() {
        pendingStore = new InMemoryPendingAuthorizationStore(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        flowContext = FlowContext.create(GATEWAY_ORIGIN + "/auth/callback");
        AuthorizationCodeFlow.AuthorizationRedirect redirect =
                new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
        requestedScopes = new AtomicReference<>();
        authorization = scopes -> {
            requestedScopes.set(scopes);
            return redirect;
        };
        loginFlow = new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT, CLOCK);
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
            LoginRedirect result = loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            assertEquals(AUTHORIZATION_URL, result.authorizationUrl(), "the URL comes from the engine, unchanged");
            assertEquals(1, result.setCookieHeaders().size());
            assertTrue(result.setCookieHeaders().getFirst().startsWith(BindingCookieCodec.COOKIE_NAME + "="));
        }

        @Test
        @DisplayName("Should persist the engine FlowContext as a single-use pending record bound to the browser")
        void shouldPersistPendingRecord() {
            LoginRedirect result = loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            PendingAuthorizationRecord pending = consumeBoundRecord(result);
            assertSame(flowContext, pending.flowContext(), "the record wraps the engine context, never re-invents it");
            assertEquals("/dashboard", pending.returnUrl());
            assertEquals(T0, pending.createdAt());
        }

        @Test
        @DisplayName("Should record the requester's quota key on the stored pending record")
        void shouldRecordQuotaKey() {
            LoginRedirect result = loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            assertEquals(QUOTA_KEY, consumeBoundRecord(result).quotaKey(),
                    "the store counts the record under the source that started the login");
        }

        @Test
        @DisplayName("Should forward the requested scope set to the authorization seam unchanged")
        void shouldForwardScopesToSeam() {
            loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            assertEquals(SCOPES, requestedScopes.get(), "the seam receives exactly the scope set the caller named");
        }
    }

    /**
     * The runtime binds the seam to the engine authorization followed by the push of the request
     * (ADR-0058), so a push that fails surfaces here as a throwing seam. The flow calls the seam
     * before it stores anything, which is what keeps a refused login from being left half-started.
     */
    @Nested
    @DisplayName("A failing authorization seam")
    class FailingSeam {

        @Test
        @DisplayName("Should propagate the seam's failure, store no pending record and yield no binding cookie")
        void shouldStoreNothingWhenTheSeamThrows() {
            GatewayException refusal =
                    new GatewayException(EventType.UPSTREAM_ERROR, "Pushed authorization request refused (push-failed)");
            LoginFlow failing = new LoginFlow(scopes -> {
                throw refusal;
            }, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT, CLOCK);

            GatewayException thrown =
                    assertThrows(GatewayException.class, () -> failing.initiate("/dashboard", SCOPES, QUOTA_KEY, T0));

            assertSame(refusal, thrown, "the refusal reaches the caller as it was raised — no redirect, so no Set-Cookie");
            assertEquals(0, pendingStore.size(), "nothing is stored for a request that was never pushed");
        }

        @Test
        @DisplayName("Should store exactly one pending record when the seam yields a redirect (matched control)")
        void shouldStoreOneRecordWhenTheSeamYieldsARedirect() {
            LoginRedirect result = loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            assertEquals(1, pendingStore.size(), "the store does count a record, so the zero above is observed");
            assertEquals(1, result.setCookieHeaders().size(), "and the redirect carries the binding cookie");
        }
    }

    @Nested
    @DisplayName("A saturated pending store")
    class SaturatedStore {

        @Test
        @DisplayName("Should propagate the store's refusal after the push and yield no binding cookie")
        void shouldPropagateSaturationRefusal() {
            InMemoryPendingAuthorizationStore saturated = new InMemoryPendingAuthorizationStore(1);
            PendingAuthorizationRecord held = PendingAuthorizationRecord.create(flowContext, "/held", SCOPES,
                    OTHER_QUOTA_KEY, T0);
            saturated.store(held, T0);
            LoginFlow flow = new LoginFlow(authorization, saturated, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT,
                    CLOCK);

            GatewayException thrown =
                    assertThrows(GatewayException.class, () -> flow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0));

            assertEquals(EventType.PENDING_AUTHORIZATION_SATURATED, thrown.getEventType(),
                    "the refusal reaches the caller instead of a redirect, so no Set-Cookie is returned");
            assertEquals(SCOPES, requestedScopes.get(), "the refusal follows the push, like the 502 path");
            assertEquals(1, saturated.size(), "no record was added");
            assertTrue(saturated.consume(held.id(), T0).isPresent(), "the other source's live record was not evicted");
        }

        /**
         * The store is full at the caller's {@code now}, but its one record expires while the push is in
         * flight: the clock is moved past that expiry inside the authorization seam. The flow must let
         * the store purge at the reading taken after the push, so the login is admitted — while the new
         * record's own lifetime still runs from the caller's {@code now}.
         */
        @Test
        @DisplayName("Should admit the login when the held record expired during the push, its TTL anchored at now")
        void shouldAdmitWhenTheHeldRecordExpiredDuringThePush() {
            InMemoryPendingAuthorizationStore full = new InMemoryPendingAuthorizationStore(1);
            Instant heldCreatedAt = T0.minus(PendingAuthorizationRecord.FIXED_TTL).plusSeconds(1);
            full.store(PendingAuthorizationRecord.create(flowContext, "/held", SCOPES, OTHER_QUOTA_KEY,
                    heldCreatedAt), heldCreatedAt);
            SettableClock clock = new SettableClock(T0);
            AuthorizationCodeFlow.AuthorizationRedirect redirect =
                    new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
            LoginFlow flow = new LoginFlow(scopes -> {
                clock.set(T0.plusSeconds(2));
                return redirect;
            }, full, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT, clock);

            LoginRedirect result = flow.initiate("/dashboard", SCOPES, QUOTA_KEY, T0);

            String recordId = bindingCodec.readRecordId(result.setCookieHeaders().getFirst().split(";", 2)[0])
                    .orElseThrow();
            PendingAuthorizationRecord stored = full.consume(recordId, T0).orElseThrow();
            assertAll("admitted at the post-push reading, anchored at the caller's now",
                    () -> assertEquals(T0, stored.createdAt(), "the push latency does not move the TTL anchor"),
                    () -> assertEquals(T0.plus(PendingAuthorizationRecord.FIXED_TTL), stored.expiresAt()));
        }
    }

    @Nested
    @DisplayName("Same-origin return-URL guard (never an open redirect)")
    class ReturnUrlGuard {

        @ParameterizedTest(name = "same-origin return URL \"{0}\" is recorded verbatim")
        @ValueSource(strings = {"/dashboard", "/app/page?x=1", "/x?tab=a&b", "/x?a=1&b=2&a=3&q=a%20b",
                "https://gw.example.com/app"})
        @DisplayName("Should record a same-origin return URL verbatim, query included")
        void shouldRecordSameOriginReturnUrl(String returnUrl) {
            LoginRedirect result = loginFlow.initiate(returnUrl, SCOPES, QUOTA_KEY, T0);

            assertEquals(returnUrl, consumeBoundRecord(result).returnUrl());
        }

        @ParameterizedTest(name = "off-origin return URL \"{0}\" falls back to the configured default")
        @ValueSource(strings = {"https://evil.example.com/app", "//evil.example.com", "javascript:alert(1)"})
        @DisplayName("Should fall back to the configured default for a cross-origin or unparseable return URL")
        void shouldFallBackForOffOrigin(String returnUrl) {
            LoginRedirect result = loginFlow.initiate(returnUrl, SCOPES, QUOTA_KEY, T0);

            assertEquals(CONFIGURED_DEFAULT, consumeBoundRecord(result).returnUrl());
        }

        @Test
        @DisplayName("Should fall back to the configured default when no return URL is supplied")
        void shouldFallBackForNull() {
            LoginRedirect result = loginFlow.initiate(null, SCOPES, QUOTA_KEY, T0);

            assertEquals(CONFIGURED_DEFAULT, consumeBoundRecord(result).returnUrl());
        }

        @Test
        @DisplayName("Should fall back to '/' when the flow is assembled with the unset default")
        void shouldFallBackToRootDefault() {
            LoginFlow rootDefault = new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, "/", CLOCK);

            LoginRedirect absent = rootDefault.initiate(null, SCOPES, QUOTA_KEY, T0);
            LoginRedirect offOrigin = rootDefault.initiate("https://evil.example.com/app", SCOPES, QUOTA_KEY, T0);

            assertEquals("/", consumeBoundRecord(absent).returnUrl(), "an absent target lands on '/'");
            assertEquals("/", consumeBoundRecord(offOrigin).returnUrl(), "an off-origin target lands on '/'");
        }

        @Test
        @DisplayName("Should expose the configured default return URL")
        void shouldExposeConfiguredDefault() {
            assertEquals(CONFIGURED_DEFAULT, loginFlow.defaultReturnUrl());
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject a null reference instant")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> loginFlow.initiate("/dashboard", SCOPES, QUOTA_KEY, null));
        }

        @Test
        @DisplayName("Should reject a null scope set before reaching the seam")
        void shouldRejectNullScopes() {
            assertThrows(NullPointerException.class, () -> loginFlow.initiate("/dashboard", null, QUOTA_KEY, T0));
            assertNull(requestedScopes.get(), "the seam is never reached without a scope set");
        }

        @Test
        @DisplayName("Should reject a null quota key before reaching the seam")
        void shouldRejectNullQuotaKey() {
            assertThrows(NullPointerException.class, () -> loginFlow.initiate("/dashboard", SCOPES, null, T0));
            assertNull(requestedScopes.get(), "the seam is never reached without a quota key");
        }

        @Test
        @DisplayName("Should reject a null default return URL")
        void shouldRejectNullDefaultReturnUrl() {
            assertThrows(NullPointerException.class,
                    () -> new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, null, CLOCK));
        }

        @Test
        @DisplayName("Should reject a null clock")
        void shouldRejectNullClock() {
            assertThrows(NullPointerException.class,
                    () -> new LoginFlow(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT,
                            null));
        }
    }
}
