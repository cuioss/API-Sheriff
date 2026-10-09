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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import de.cuioss.sheriff.gateway.bff.login.LoginFlow.LoginRedirect;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord.Widening;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SessionWidening}: the live-session widening requests the granted set united with the
 * route's needed scopes, records the attempt and the live identity on a widening pending record, never
 * redirects off-origin, emits the browser-binding cookie, and re-drives exactly the silent attempt as
 * one interactive attempt.
 * <p>
 * The engine authorization is driven through the {@link SessionWidening.AuthorizationWidening} seam
 * with a hand-built {@link AuthorizationCodeFlow.AuthorizationRedirect}, so the widening is exercised
 * without a live IdP or discovery round-trip.
 */
@EnableGeneratorController
@DisplayName("SessionWidening — prompt=none first, recorded on a widening pending record")
class SessionWideningTest {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String AUTHORIZATION_URL = "https://idp.example.com/authorize?client_id=api-sheriff";
    /** A configured {@code oidc.login.default_return_url} distinct from {@code /}. */
    private static final String CONFIGURED_DEFAULT = "/home";
    private static final Set<String> GRANTED = Set.of("openid", "profile");
    private static final Set<String> NEEDED = Set.of("openid", "orders:read");

    /** One widening-seam call: the scope set it was asked for and whether it was silent. */
    private record SeamCall(Collection<String> scopes, boolean silent) {
    }

    private PendingAuthorizationStore.InMemory pendingStore;
    private BindingCookieCodec bindingCodec;
    private FlowContext flowContext;
    private List<SeamCall> seamCalls;
    private SessionWidening.AuthorizationWidening authorization;
    private SessionWidening widening;
    private SessionRecord live;

    @BeforeEach
    void setUp() {
        pendingStore = new PendingAuthorizationStore.InMemory(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        flowContext = FlowContext.create(GATEWAY_ORIGIN + "/auth/callback");
        AuthorizationCodeFlow.AuthorizationRedirect redirect =
                new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
        seamCalls = new ArrayList<>();
        authorization = (scopes, silent) -> {
            seamCalls.add(new SeamCall(scopes, silent));
            return redirect;
        };
        widening = new SessionWidening(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT);
        live = liveSession(GRANTED);
    }

    private static SessionRecord liveSession(Set<String> granted) {
        return SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken(Generators.letterStrings(16, 32).next())
                .idToken(Generators.letterStrings(16, 32).next())
                .sub(Generators.letterStrings(8, 16).next())
                .expiresAt(T0.plusSeconds(3600))
                .activeScopes(granted)
                .grantedScopes(granted)
                .build();
    }

    private PendingAuthorizationRecord consumeBoundRecord(LoginRedirect result) {
        String cookieHeader = result.setCookieHeaders().getFirst().split(";", 2)[0];
        Optional<String> recordId = bindingCodec.readRecordId(cookieHeader);
        assertTrue(recordId.isPresent(), "the widening sets a binding cookie carrying the record id");
        return pendingStore.consume(recordId.get(), T0).orElseThrow();
    }

    @Nested
    @DisplayName("Requested scope set")
    class RequestedScopes {

        @Test
        @DisplayName("Should request the granted set united with the needed scopes, never the needed scopes alone")
        void shouldRequestGrantedUnionNeeded() {
            LoginRedirect result = widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, T0);

            Set<String> expected = Set.of("openid", "profile", "orders:read");
            assertEquals(1, seamCalls.size(), "exactly one authorization request is built");
            assertEquals(expected, Set.copyOf(seamCalls.getFirst().scopes()),
                    "a narrower request would silently narrow the live session on the callback");
            assertEquals(expected, consumeBoundRecord(result).requestedScopes(),
                    "the pending record carries the set the request asked for");
        }

        @Test
        @DisplayName("Should request the granted set unchanged when every needed scope is already granted")
        void shouldRequestGrantedWhenNeededIsCovered() {
            widening.initiate(live, "/orders", Set.of("profile"), Widening.Attempt.SILENT, T0);

            assertEquals(GRANTED, Set.copyOf(seamCalls.getFirst().scopes()));
        }
    }

    @Nested
    @DisplayName("Attempt recorded on the pending record")
    class AttemptRecorded {

        @Test
        @DisplayName("Should issue the silent attempt with prompt=none and record it with the live subject")
        void shouldRecordSilentAttempt() {
            LoginRedirect result = widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, T0);

            PendingAuthorizationRecord pending = consumeBoundRecord(result);
            assertTrue(seamCalls.getFirst().silent(), "a silent attempt asks the seam for prompt=none");
            assertNotNull(pending.widening(), "a widening leaves a widening record, never a login record");
            assertEquals(Widening.Attempt.SILENT, pending.widening().attempt());
            assertEquals(live.sub(), pending.widening().sub(), "the callback must land on the live identity");
            assertSame(flowContext, pending.flowContext(), "the record wraps the engine context");
            assertEquals(T0, pending.createdAt());
        }

        @Test
        @DisplayName("Should issue the interactive attempt without prompt=none and record it as interactive")
        void shouldRecordInteractiveAttempt() {
            LoginRedirect result = widening.initiate(live, "/orders", NEEDED, Widening.Attempt.INTERACTIVE, T0);

            PendingAuthorizationRecord pending = consumeBoundRecord(result);
            assertFalse(seamCalls.getFirst().silent(), "the interactive attempt lets the IdP interact");
            assertEquals(Widening.Attempt.INTERACTIVE, pending.widening().attempt());
        }
    }

    @Nested
    @DisplayName("Interactive re-drive")
    class InteractiveRedrive {

        @Test
        @DisplayName("Should re-drive a silent record as one interactive attempt with the same URL, scopes and subject")
        void shouldRedriveSilentAsInteractive() {
            PendingAuthorizationRecord silent = consumeBoundRecord(
                    widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, T0));

            LoginRedirect redrive = widening.redriveInteractive(silent, T0.plusSeconds(5));

            PendingAuthorizationRecord interactive = consumeBoundRecord(redrive);
            assertEquals(AUTHORIZATION_URL, redrive.authorizationUrl());
            assertEquals(2, seamCalls.size());
            assertFalse(seamCalls.get(1).silent(), "the re-drive is the interactive attempt");
            assertEquals(silent.requestedScopes(), Set.copyOf(seamCalls.get(1).scopes()),
                    "the re-drive asks for the same set as the silent attempt");
            assertEquals(Widening.Attempt.INTERACTIVE, interactive.widening().attempt());
            assertEquals(silent.widening().sub(), interactive.widening().sub());
            assertEquals(silent.returnUrl(), interactive.returnUrl());
            assertEquals(silent.requestedScopes(), interactive.requestedScopes());
            assertNotEquals(silent.id(), interactive.id(), "the re-drive persists a fresh single-use record");
        }

        @Test
        @DisplayName("Should refuse to re-drive an interactive record — the interactive attempt is the last")
        void shouldRefuseToRedriveInteractive() {
            PendingAuthorizationRecord interactive = consumeBoundRecord(
                    widening.initiate(live, "/orders", NEEDED, Widening.Attempt.INTERACTIVE, T0));

            assertThrows(IllegalArgumentException.class, () -> widening.redriveInteractive(interactive, T0));
            assertEquals(1, seamCalls.size(), "no second authorization request is built");
        }

        @Test
        @DisplayName("Should refuse to re-drive a plain login record")
        void shouldRefuseToRedriveLogin() {
            PendingAuthorizationRecord login = PendingAuthorizationRecord.create(flowContext, "/", NEEDED, T0);

            assertThrows(IllegalArgumentException.class, () -> widening.redriveInteractive(login, T0));
            assertTrue(seamCalls.isEmpty(), "the seam is never reached for a login record");
        }
    }

    @Nested
    @DisplayName("Same-origin return-URL guard (never an open redirect)")
    class ReturnUrlGuard {

        @ParameterizedTest(name = "same-origin return URL \"{0}\" is recorded verbatim")
        @ValueSource(strings = {"/orders", "/app/page?x=1&y=2", "https://gw.example.com/app"})
        @DisplayName("Should record a same-origin return URL verbatim")
        void shouldRecordSameOriginReturnUrl(String returnUrl) {
            LoginRedirect result = widening.initiate(live, returnUrl, NEEDED, Widening.Attempt.SILENT, T0);

            assertEquals(returnUrl, consumeBoundRecord(result).returnUrl());
        }

        @ParameterizedTest(name = "off-origin return URL \"{0}\" falls back to the configured default")
        @ValueSource(strings = {"https://evil.example.com/app", "//evil.example.com", "/\\evil.example.com",
                "/\t/evil.example.com", "javascript:alert(1)", " "})
        @DisplayName("Should fall back to the configured default for a cross-origin, smuggled or blank return URL")
        void shouldFallBackForOffOrigin(String returnUrl) {
            LoginRedirect result = widening.initiate(live, returnUrl, NEEDED, Widening.Attempt.SILENT, T0);

            assertEquals(CONFIGURED_DEFAULT, consumeBoundRecord(result).returnUrl());
        }

        @Test
        @DisplayName("Should fall back to the configured default when no return URL is supplied")
        void shouldFallBackForNull() {
            LoginRedirect result = widening.initiate(live, null, NEEDED, Widening.Attempt.SILENT, T0);

            assertEquals(CONFIGURED_DEFAULT, consumeBoundRecord(result).returnUrl());
        }
    }

    @Nested
    @DisplayName("Browser redirect")
    class BrowserRedirect {

        @Test
        @DisplayName("Should redirect to the engine authorization URL and set exactly the binding cookie")
        void shouldRedirectWithBindingCookie() {
            LoginRedirect result = widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, T0);

            assertEquals(AUTHORIZATION_URL, result.authorizationUrl(), "the URL comes from the seam, unchanged");
            assertEquals(1, result.setCookieHeaders().size(), "no session cookie is touched by the initiation");
            assertTrue(result.setCookieHeaders().getFirst().startsWith(BindingCookieCodec.COOKIE_NAME + "="));
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject absent arguments before reaching the seam")
        void shouldRejectNullArguments() {
            assertThrows(NullPointerException.class,
                    () -> widening.initiate(null, "/orders", NEEDED, Widening.Attempt.SILENT, T0));
            assertThrows(NullPointerException.class,
                    () -> widening.initiate(live, "/orders", null, Widening.Attempt.SILENT, T0));
            assertThrows(NullPointerException.class, () -> widening.initiate(live, "/orders", NEEDED, null, T0));
            assertThrows(NullPointerException.class,
                    () -> widening.initiate(live, "/orders", NEEDED, Widening.Attempt.SILENT, null));
            assertTrue(seamCalls.isEmpty(), "the seam is never reached with an absent argument");
        }

        @Test
        @DisplayName("Should reject an absent collaborator")
        void shouldRejectNullCollaborators() {
            assertThrows(NullPointerException.class,
                    () -> new SessionWidening(null, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT));
            assertThrows(NullPointerException.class,
                    () -> new SessionWidening(authorization, pendingStore, bindingCodec, GATEWAY_ORIGIN, null));
        }
    }
}
