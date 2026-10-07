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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.bff.login.ReturnTargetScopes;
import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord.Widening;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.reserved.StepUpEndpoint.StepUpOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
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
 * Tests for {@link StepUpEndpoint}: the reserved step-up path. The security-relevant contracts under
 * test are that the endpoint serves a live session only (a request without one is answered {@code 401}
 * with no IdP redirect and no pending record), that an off-origin, schema-relative, backslash or
 * control-character return URL falls back to the configured default and so can never become a redirect
 * location, and that the decision between a direct redirect and a widening is taken against the
 * session's granted-scope set {@code S} — a direct {@code 302} when the target route's needed scopes lie
 * inside it, a silent widening for {@code S ∪ needed} otherwise.
 * <p>
 * The endpoint is exercised with the real {@link SessionWidening} over a hand-built authorization seam
 * (a call list proves whether and how the widening ran), a real {@link InMemorySessionStore} and the
 * real {@link SessionCookieCodec} — no container, no live IdP, no test double framework.
 */
@EnableGeneratorController
@DisplayName("StepUpEndpoint — widens a live session for the route behind a return URL")
class StepUpEndpointTest {

    private static final Instant T0 = Instant.parse("2026-09-25T10:00:00Z");
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String GATEWAY_ORIGIN = "https://gw.example.com";
    private static final String AUTHORIZATION_URL = "https://idp.example.com/authorize?client_id=api-sheriff";
    /** A configured {@code oidc.login.default_return_url} distinct from {@code /}. */
    private static final String CONFIGURED_DEFAULT = "/home";
    /** The configured {@code oidc.scopes} — what an unrouted target needs. */
    private static final Set<String> OIDC_SCOPES = Set.of("openid", "profile");
    /** The {@code neededScopes} of the session route under {@code /dashboard}. */
    private static final Set<String> DASHBOARD_SCOPES = Set.of("openid", "profile", "dashboard:read");

    /** One widening-seam call: the scope set it was asked for and whether it was silent. */
    private record SeamCall(Collection<String> scopes, boolean silent) {
    }

    private PendingAuthorizationStore.InMemory pendingStore;
    private BindingCookieCodec bindingCodec;
    private InMemorySessionStore sessionStore;
    private SessionCookieCodec sessionCodec;
    private ServerSessionBinding sessionBinding;
    private List<SeamCall> seamCalls;
    private SessionWidening sessionWidening;
    private StepUpEndpoint endpoint;

    @BeforeEach
    void setUp() {
        pendingStore = new PendingAuthorizationStore.InMemory(8);
        bindingCodec = new BindingCookieCodec(PendingAuthorizationRecord.FIXED_TTL);
        FlowContext flowContext = FlowContext.create(GATEWAY_ORIGIN + "/auth/callback");
        AuthorizationCodeFlow.AuthorizationRedirect redirect =
                new AuthorizationCodeFlow.AuthorizationRedirect(AUTHORIZATION_URL, flowContext);
        seamCalls = new ArrayList<>();
        sessionWidening = new SessionWidening((scopes, silent) -> {
            seamCalls.add(new SeamCall(scopes, silent));
            return redirect;
        }, pendingStore, bindingCodec, GATEWAY_ORIGIN, CONFIGURED_DEFAULT);
        // The idle timeout equals the absolute lifetime, so it is not in play in these cases.
        sessionStore = new InMemorySessionStore(16, SESSION_TTL);
        sessionCodec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL);
        sessionBinding = new ServerSessionBinding(sessionStore, sessionCodec);
        endpoint = new StepUpEndpoint(sessionWidening, sessionBinding, returnTargetScopes(), GATEWAY_ORIGIN,
                CONFIGURED_DEFAULT);
    }

    /** A route table with one {@code require: session} route under {@code /dashboard}. */
    private static ReturnTargetScopes returnTargetScopes() {
        ResolvedRoute dashboard = ResolvedRoute.builder()
                .id("dashboard")
                .match(MatchConfig.builder().pathPrefix("/dashboard").build())
                .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                .effectiveAllowedMethods(List.of(HttpMethod.GET))
                .upstream(new ResolvedUpstream("https", "dashboard.example", 443, ""))
                .neededScopes(DASHBOARD_SCOPES)
                .build();
        return new ReturnTargetScopes(new RouteTable(List.of(dashboard)), GATEWAY_ORIGIN, OIDC_SCOPES);
    }

    /**
     * Creates a live session with the given active and granted sets and returns the request
     * {@code Cookie} header that resolves it.
     */
    private String liveSessionCookie(Set<String> active, Set<String> granted) {
        String sessionId = SessionRecord.newSessionId();
        SessionRecord session = SessionRecord.builder()
                .sessionId(sessionId)
                .accessToken(Generators.letterStrings(16, 32).next())
                .idToken(Generators.letterStrings(16, 32).next())
                .sub(Generators.letterStrings(8, 16).next())
                .expiresAt(T0.plus(SESSION_TTL))
                .activeScopes(active)
                .grantedScopes(granted)
                .build();
        // The cookie carries an opaque handle of its own, never the session id.
        String cookieHandle = Generators.letterStrings(32, 43).next();
        sessionStore.create(session, cookieHandle, T0);
        return sessionCodec.toSetCookieHeader(cookieHandle).split(";", 2)[0];
    }

    private String liveSessionCookie(Set<String> granted) {
        return liveSessionCookie(granted, granted);
    }

    /** Consumes the pending record bound by the binding cookie the widening set. */
    private PendingAuthorizationRecord consumeBoundRecord(StepUpOutcome outcome) {
        String cookie = outcome.setCookieHeaders().getFirst().split(";", 2)[0];
        Optional<String> recordId = bindingCodec.readRecordId(cookie);
        assertTrue(recordId.isPresent(), "a widening sets a binding cookie carrying the record id");
        return pendingStore.consume(recordId.get(), T0).orElseThrow();
    }

    @Nested
    @DisplayName("Live-session guard")
    class LiveSessionGuard {

        @Test
        @DisplayName("Should answer 401 problem+json without an IdP redirect when no session cookie is sent")
        void shouldAnswer401WithoutSession() {
            StepUpOutcome outcome = endpoint.handle("/dashboard", null, T0);

            assertAll("the no-session answer",
                    () -> assertEquals(401, outcome.status()),
                    () -> assertFalse(outcome.isRedirect(), "never a redirect — there is nothing to widen"),
                    () -> assertNull(outcome.location()),
                    () -> assertEquals("application/problem+json", outcome.headers().get("Content-Type")),
                    () -> assertEquals("no-store", outcome.headers().get("Cache-Control")),
                    () -> assertEquals(401, outcome.body().get("status")),
                    () -> assertTrue(outcome.setCookieHeaders().isEmpty(), "no binding cookie is minted"),
                    () -> assertTrue(seamCalls.isEmpty(),
                            "no authorization request is built, so no pending record is persisted"));
        }

        @Test
        @DisplayName("Should answer 401 for a cookie that resolves to no live session")
        void shouldAnswer401ForUnknownSession() {
            String unknown = sessionCodec.toSetCookieHeader(SessionRecord.newSessionId()).split(";", 2)[0];

            StepUpOutcome outcome = endpoint.handle("/dashboard", unknown, T0);

            assertEquals(401, outcome.status());
            assertTrue(seamCalls.isEmpty());
        }

        @Test
        @DisplayName("Should answer 401 for an expired session")
        void shouldAnswer401ForExpiredSession() {
            String cookie = liveSessionCookie(OIDC_SCOPES);

            StepUpOutcome outcome = endpoint.handle("/dashboard", cookie, T0.plus(SESSION_TTL).plusSeconds(1));

            assertEquals(401, outcome.status(), "an expired session cannot be widened");
            assertTrue(seamCalls.isEmpty());
        }
    }

    @Nested
    @DisplayName("Return-URL guard (never an open redirect)")
    class ReturnUrlGuard {

        @ParameterizedTest(name = "off-origin return URL \"{0}\" falls back to the configured default")
        @ValueSource(strings = {"https://evil.example.com/dashboard", "//evil.example.com/dashboard",
                "/\\evil.example.com/dashboard", "/\t/evil.example.com/dashboard", "javascript:alert(1)", " "})
        @DisplayName("Should fall back to the configured default for a cross-origin, smuggled or blank target")
        void shouldFallBackForOffOrigin(String returnUrl) {
            String cookie = liveSessionCookie(OIDC_SCOPES);

            StepUpOutcome outcome = endpoint.handle(returnUrl, cookie, T0);

            assertEquals(302, outcome.status());
            assertEquals(CONFIGURED_DEFAULT, outcome.location(),
                    "an off-origin target can never become the location, and the default needs no widening");
            assertTrue(seamCalls.isEmpty(), "the off-origin target's route is never widened for");
        }

        @Test
        @DisplayName("Should fall back to the configured default when no return URL is supplied")
        void shouldFallBackForNull() {
            String cookie = liveSessionCookie(OIDC_SCOPES);

            StepUpOutcome outcome = endpoint.handle(null, cookie, T0);

            assertEquals(CONFIGURED_DEFAULT, outcome.location());
        }

        @Test
        @DisplayName("Should record the same-origin return URL verbatim on the widening pending record")
        void shouldRecordSameOriginTargetOnWidening() {
            String cookie = liveSessionCookie(OIDC_SCOPES);

            StepUpOutcome outcome = endpoint.handle("https://gw.example.com/dashboard?tab=a", cookie, T0);

            assertEquals("https://gw.example.com/dashboard?tab=a", consumeBoundRecord(outcome).returnUrl());
        }
    }

    @Nested
    @DisplayName("Needed scopes inside the granted set — straight back")
    class NeededInsideGranted {

        @Test
        @DisplayName("Should redirect straight to the target when its needed scopes are already granted")
        void shouldRedirectStraightBack() {
            String cookie = liveSessionCookie(DASHBOARD_SCOPES);

            StepUpOutcome outcome = endpoint.handle("/dashboard/reports", cookie, T0);

            assertAll("a direct 302, no widening",
                    () -> assertEquals(302, outcome.status()),
                    () -> assertEquals("/dashboard/reports", outcome.location()),
                    () -> assertTrue(outcome.setCookieHeaders().isEmpty(), "no binding cookie — no IdP round trip"),
                    () -> assertTrue(outcome.body().isEmpty()),
                    () -> assertTrue(seamCalls.isEmpty()));
        }

        @Test
        @DisplayName("Should decide on the granted set S, not the active set A — the route refreshes inside S")
        void shouldDecideOnGrantedNotActive() {
            String cookie = liveSessionCookie(OIDC_SCOPES, DASHBOARD_SCOPES);

            StepUpOutcome outcome = endpoint.handle("/dashboard", cookie, T0);

            assertEquals("/dashboard", outcome.location(),
                    "a scope inside S but outside A is obtained by the route's own refresh, never by a widening");
            assertTrue(seamCalls.isEmpty());
        }
    }

    @Nested
    @DisplayName("Needed scopes outside the granted set — silent widening")
    class NeededOutsideGranted {

        @Test
        @DisplayName("Should start a silent widening for the granted set united with the needed scopes")
        void shouldStartSilentWidening() {
            String cookie = liveSessionCookie(OIDC_SCOPES);

            StepUpOutcome outcome = endpoint.handle("/dashboard", cookie, T0);

            PendingAuthorizationRecord pending = consumeBoundRecord(outcome);
            Widening widening = pending.widening();
            assertAll("the widening redirect",
                    () -> assertEquals(302, outcome.status()),
                    () -> assertEquals(AUTHORIZATION_URL, outcome.location(), "the URL comes from the seam"),
                    () -> assertEquals(1, outcome.setCookieHeaders().size()),
                    () -> assertTrue(outcome.setCookieHeaders().getFirst()
                            .startsWith(BindingCookieCodec.COOKIE_NAME + "=")),
                    () -> assertEquals(1, seamCalls.size(), "exactly one authorization request"),
                    () -> assertTrue(seamCalls.getFirst().silent(), "the first attempt carries prompt=none"),
                    () -> assertEquals(DASHBOARD_SCOPES, Set.copyOf(seamCalls.getFirst().scopes()),
                            "S ∪ needed"),
                    () -> assertNotNull(widening, "a widening record, never a login record"),
                    () -> assertEquals(Widening.Attempt.SILENT, widening.attempt()),
                    () -> assertEquals("/dashboard", pending.returnUrl()));
        }

        @Test
        @DisplayName("Should keep every granted scope in the widening request, even one the route does not need")
        void shouldKeepGrantedScopesOutsideNeeded() {
            Set<String> granted = Set.of("openid", "profile", "email");
            String cookie = liveSessionCookie(granted);

            endpoint.handle("/dashboard", cookie, T0);

            assertEquals(Set.of("openid", "profile", "email", "dashboard:read"),
                    Set.copyOf(seamCalls.getFirst().scopes()),
                    "a request for less than S would narrow the live session on the callback");
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("Should reject a null reference instant")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> endpoint.handle("/dashboard", null, null));
        }

        @Test
        @DisplayName("Should reject an absent collaborator")
        void shouldRejectNullCollaborators() {
            ReturnTargetScopes scopes = returnTargetScopes();

            assertAll(
                    () -> assertThrows(NullPointerException.class, () -> new StepUpEndpoint(null, sessionBinding,
                            scopes, GATEWAY_ORIGIN, CONFIGURED_DEFAULT)),
                    () -> assertThrows(NullPointerException.class, () -> new StepUpEndpoint(sessionWidening, null,
                            scopes, GATEWAY_ORIGIN, CONFIGURED_DEFAULT)),
                    () -> assertThrows(NullPointerException.class, () -> new StepUpEndpoint(sessionWidening,
                            sessionBinding, null, GATEWAY_ORIGIN, CONFIGURED_DEFAULT)),
                    () -> assertThrows(NullPointerException.class, () -> new StepUpEndpoint(sessionWidening,
                            sessionBinding, scopes, null, CONFIGURED_DEFAULT)),
                    () -> assertThrows(NullPointerException.class, () -> new StepUpEndpoint(sessionWidening,
                            sessionBinding, scopes, GATEWAY_ORIGIN, null)));
        }
    }
}
