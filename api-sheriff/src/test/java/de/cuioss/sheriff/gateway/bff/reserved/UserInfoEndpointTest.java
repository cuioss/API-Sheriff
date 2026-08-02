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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint.ClaimSource;
import de.cuioss.sheriff.gateway.bff.reserved.UserInfoEndpoint.UserInfoOutcome;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UserInfoEndpoint}: the session/user-info reserved endpoint. The security-relevant
 * contracts under test are the 401-not-redirect XHR probe, the curated default-view disclosure, the
 * operator-allowlist cap (a claim outside the allowlist is absent from a default view and rejected
 * {@code 403} when explicitly requested), the absence of any raw token material in the response, and
 * the {@code Cache-Control: no-store} header on every outcome.
 * <p>
 * The endpoint is framework-agnostic, so it is exercised with a real
 * {@link de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding} over an
 * {@link InMemorySessionStore} and the real {@link SessionCookieCodec}, plus a hand-built
 * {@link ClaimSource} lambda — no container, no live IdP, no test double framework.
 */
class UserInfoEndpointTest {

    private static final Instant T0 = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration TTL = Duration.ofHours(8);
    private static final String SESSION_ID = "opaque-session-1";
    private static final String SUBJECT = "user-sub-1";
    private static final String NAME = "Alice Example";
    private static final String EMAIL = "alice@example.com";
    private static final String ACR = "urn:mace:incommon:iap:silver";
    private static final Instant AUTH_TIME = Instant.ofEpochSecond(1_721_730_000L);
    private static final String RAW_ACCESS_TOKEN = "raw-access-token-SECRET-material";
    private static final String RAW_ID_TOKEN = "raw-id-token-SECRET-material";

    private InMemorySessionStore sessionStore;
    private SessionCookieCodec sessionCodec;
    private ClaimAllowlistFilter claimFilter;
    private UserInfoEndpoint endpoint;
    private String cookieHeader;

    @BeforeEach
    void setUp() {
        sessionStore = new InMemorySessionStore(16);
        sessionCodec = new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, TTL);
        claimFilter = new ClaimAllowlistFilter(List.of("sub", "name", "roles"), List.of("sub", "name", "roles"));
        endpoint = new UserInfoEndpoint(new ServerSessionBinding(sessionStore, sessionCodec), claimFilter,
                validatedClaims());

        SessionRecord session = SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken(RAW_ACCESS_TOKEN)
                .idToken(RAW_ID_TOKEN)
                .sub(SUBJECT)
                .expiresAt(T0.plus(TTL))
                .acr(ACR)
                .authTime(AUTH_TIME)
                .build();
        sessionStore.create(session);
        cookieHeader = sessionCodec.toSetCookieHeader(SESSION_ID).split(";", 2)[0];
    }

    /** A validated-ID-token claim map carrying an allowlisted subset plus a non-allowlisted email. */
    private static ClaimSource validatedClaims() {
        Map<String, Object> claims = Map.of(
                "sub", SUBJECT,
                "name", NAME,
                "roles", List.of("admin", "user"),
                "email", EMAIL);
        return session -> claims;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> claimsOf(UserInfoOutcome outcome) {
        return (Map<String, Object>) outcome.body().get("claims");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> sessionOf(UserInfoOutcome outcome) {
        return (Map<String, Object>) outcome.body().get("session");
    }

    @Nested
    @DisplayName("Unauthenticated probe (401, never a redirect)")
    class Unauthenticated {

        @Test
        @DisplayName("No session cookie yields 401 application/problem+json — never a 302 redirect")
        void shouldChallenge401NotRedirect() {
            UserInfoOutcome outcome = endpoint.handle(null, null, T0);

            assertEquals(401, outcome.status(), "an XHR probe with no session is challenged 401");
            assertNotEquals(302, outcome.status(), "the user-info probe is never a redirect");
            assertFalse(outcome.isDisclosed());
            assertEquals("application/problem+json", outcome.headers().get("Content-Type"));
            assertFalse(outcome.headers().containsKey("Location"), "no redirect location is ever emitted");
        }

        @Test
        @DisplayName("An expired session is treated as unauthenticated (401)")
        void shouldChallengeExpiredSession() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, null, T0.plus(TTL).plusSeconds(1));

            assertEquals(401, outcome.status());
            assertFalse(outcome.isDisclosed());
        }
    }

    @Nested
    @DisplayName("Default-view disclosure")
    class DefaultView {

        @Test
        @DisplayName("The default view discloses the curated allowlisted claim subset")
        void shouldDiscloseCuratedDefaultView() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, null, T0);

            assertEquals(200, outcome.status());
            assertTrue(outcome.isDisclosed());
            Map<String, Object> claims = claimsOf(outcome);
            assertIterableEquals(List.of("sub", "name", "roles"), claims.keySet());
            assertEquals(NAME, claims.get("name"));
        }

        @Test
        @DisplayName("Session metadata (expiry, auth_time, acr) accompanies the claims")
        void shouldDiscloseSessionMetadata() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, null, T0);

            Map<String, Object> session = sessionOf(outcome);
            assertEquals(T0.plus(TTL).toString(), session.get("expires_at"));
            assertEquals(AUTH_TIME.toString(), session.get("auth_time"));
            assertEquals(ACR, session.get("acr"));
        }
    }

    @Nested
    @DisplayName("Allowlist enforcement")
    class AllowlistEnforcement {

        @Test
        @DisplayName("A claim present in the token but outside the allowlist is absent from the default view")
        void shouldOmitNonAllowlistedClaim() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, null, T0);

            assertFalse(claimsOf(outcome).containsKey("email"), "email is not on the operator allowlist");
        }

        @Test
        @DisplayName("An explicitly-requested disallowed claim is rejected 403 before any disclosure")
        void shouldReject403DisallowedRequestedClaim() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, "email", T0);

            assertEquals(403, outcome.status(), "a disallowed-claim request is 403");
            assertFalse(outcome.isDisclosed());
            assertEquals("application/problem+json", outcome.headers().get("Content-Type"));
        }

        @Test
        @DisplayName("A specific allowlisted claim selection discloses only that claim")
        void shouldDiscloseRequestedAllowlistedSubset() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, "name", T0);

            assertIterableEquals(List.of("name"), claimsOf(outcome).keySet());
        }

        @Test
        @DisplayName("The full view discloses every allowlisted claim present, never a disallowed one")
        void shouldDiscloseFullAllowlistedView() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, UserInfoEndpoint.FULL_VIEW, T0);

            Map<String, Object> claims = claimsOf(outcome);
            assertTrue(claims.keySet().containsAll(List.of("sub", "name", "roles")));
            assertFalse(claims.containsKey("email"), "the full view is still capped to the allowlist");
        }
    }

    @Nested
    @DisplayName("No token material and uncacheability")
    class SecurityInvariants {

        @Test
        @DisplayName("No raw token material ever appears in the response body")
        void shouldNeverDiscloseTokenMaterial() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, UserInfoEndpoint.FULL_VIEW, T0);

            String rendered = outcome.body().toString();
            assertFalse(rendered.contains(RAW_ACCESS_TOKEN), "the mediated access token never leaves the server");
            assertFalse(rendered.contains(RAW_ID_TOKEN), "the raw ID token never leaves the server");
        }

        @Test
        @DisplayName("Cache-Control: no-store is set on a disclosed view")
        void shouldSetNoStoreOnDisclosure() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, null, T0);

            assertEquals("no-store", outcome.headers().get("Cache-Control"));
        }

        @Test
        @DisplayName("Cache-Control: no-store is set on a 401 challenge")
        void shouldSetNoStoreOnChallenge() {
            UserInfoOutcome outcome = endpoint.handle(null, null, T0);

            assertEquals("no-store", outcome.headers().get("Cache-Control"));
        }

        @Test
        @DisplayName("Cache-Control: no-store is set on a 403 rejection")
        void shouldSetNoStoreOnForbidden() {
            UserInfoOutcome outcome = endpoint.handle(cookieHeader, "email", T0);

            assertEquals("no-store", outcome.headers().get("Cache-Control"));
        }
    }

    @Nested
    @DisplayName("Argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("A null reference instant is rejected")
        void shouldRejectNullNow() {
            assertThrows(NullPointerException.class, () -> endpoint.handle(cookieHeader, null, null));
        }
    }
}
