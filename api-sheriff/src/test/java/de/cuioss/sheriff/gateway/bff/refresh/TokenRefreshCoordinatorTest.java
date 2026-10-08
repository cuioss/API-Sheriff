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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.AccessTokenExpiry;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshExchange;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshOutcome;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator.RefreshTokenRevocation;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.token.client.flow.CredentialRejectedException;
import de.cuioss.sheriff.token.client.flow.RedeemedResponseException;
import de.cuioss.sheriff.token.client.flow.RedeemedScopeRefusalException;
import de.cuioss.sheriff.token.client.flow.RefreshRedemption;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link TokenRefreshCoordinator}: the single-flight, per-session transparent refresh and the
 * per-kind disposition of a refused refresh.
 * <p>
 * The engine refresh is driven through the {@link RefreshExchange} seam, the near-expiry decision
 * through the {@link AccessTokenExpiry} seam and revocation through a recording
 * {@link RefreshTokenRevocation}, so every path — not-needed, refreshed, each failure kind, the
 * post-exchange persist failure, a session terminated while its refresh is in flight, the pre-redemption
 * back-off and the concurrent single-flight coalesce
 * — is exercised with the engine's own exception types and no test-double framework. The engine's
 * {@code RefreshFlow.classify} is NOT stubbed: each test throws the exception the engine actually raises
 * for that situation, so a change in the classification is observed here. The downstream content
 * negotiation belongs to the session runtime stage and is tested there; this suite covers the
 * coordinator's own contract: which {@link RefreshOutcome} it returns, its session-binding side effects,
 * which refresh token it revokes, and which record it logs.
 */
@EnableTestLogger
class TokenRefreshCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final Duration LEEWAY = Duration.ofSeconds(60);
    private static final Instant NOT_NEAR = NOW.plusSeconds(600);
    private static final Instant NEAR = NOW.plusSeconds(30);
    private static final Instant EXPIRED = NOW.minusSeconds(1);
    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final String SESSION_ID = "session-1";
    private static final String CURRENT_REFRESH = "refresh-current";
    private static final String ROTATED_ACCESS = "rotated-access-token";
    private static final String ROTATED_REFRESH = "rotated-refresh-token";
    private static final String ROTATED_ID = "rotated-id-token";
    /** The session's active scope set A: the static oidc.scopes united with an endpoint scope. */
    private static final Set<String> ACTIVE_SCOPES = Set.of("openid", "profile", "email", "orders:read");
    /** The session's granted scope set S: A plus a scope a widening added and a refresh narrowed away. */
    private static final Set<String> GRANTED_SCOPES = Set.of("openid", "profile", "email", "orders:read",
            "orders:write");

    private static final String COOKIE_HEADER = cookieHeaderFor(SESSION_ID);

    /** The cookie handle a session of these cases is stored under — derived from, never equal to, its id. */
    private static String handleOf(String sessionId) {
        return "handle-of-" + sessionId;
    }

    /** The request {@code Cookie} header carrying the handle of the session with the given id. */
    private static String cookieHeaderFor(String sessionId) {
        return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + handleOf(sessionId);
    }

    private static void create(InMemorySessionStore target, SessionRecord session, Instant now) {
        target.create(session, handleOf(session.sessionId()), now);
    }

    /** Resolves the session with the given id through the handle it was stored under. */
    private static Optional<SessionRecord> resolveById(InMemorySessionStore target, String sessionId, Instant at) {
        return target.resolve(handleOf(sessionId), at);
    }

    /** The activity-cookie codec of the cookie-mode bindings; none of these cases reads the activity cookie. */
    private static SessionActivityCookieCodec activityCodec() {
        byte[] keyMaterial = new byte[32];
        Arrays.fill(keyMaterial, (byte) 0x44);
        return new SessionActivityCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME,
                new SecretKeySpec(keyMaterial, "AES"), (byte) 2);
    }

    /**
     * Runs a dispatched revocation on the dispatching thread, so every test that only asserts WHICH token
     * was revoked observes it synchronously. The ordering tests in {@link RevocationOrdering} bind a real
     * asynchronous executor instead.
     */
    private static final Executor DIRECT = Runnable::run;

    private static final String REFRESH_FAILED_ID = BffLogMessages.WARN.SESSION_REFRESH_FAILED.resolveIdentifierString();
    private static final String REFRESH_DEFERRED_ID =
            BffLogMessages.WARN.SESSION_REFRESH_DEFERRED.resolveIdentifierString();

    private InMemorySessionStore store;
    private SessionBinding binding;
    private List<String> revoked;

    @BeforeEach
    void setUp() {
        // The idle timeout equals the absolute lifetime, so it is not in play in these cases.
        store = new InMemorySessionStore(16, SESSION_TTL, Integer.MAX_VALUE, sessionId -> {
        });
        binding = new ServerSessionBinding(store,
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
        revoked = new CopyOnWriteArrayList<>();
    }

    private static SessionRecord session(@Nullable String refreshToken) {
        return SessionRecord.builder()
                .sessionId(SESSION_ID)
                .accessToken("access-current")
                .refreshToken(refreshToken)
                .idToken("id-current")
                .sub("sub-1")
                .sid(null)
                .expiresAt(NOW.plus(SESSION_TTL))
                .acr(null)
                .authTime(null)
                .activeScopes(ACTIVE_SCOPES)
                .grantedScopes(GRANTED_SCOPES)
                .build();
    }

    private static RotationResult rotation() {
        // The "IdP declared no scope on the refresh response" shape — a null grantedScope with
        // UNDECLARED — is the neutral value for the scheduling, single-flight, rebinding and
        // failure-disposition cases.
        return rotation(null, RotationResult.ScopeDelta.UNDECLARED);
    }

    private static RotationResult rotation(@Nullable String grantedScope, RotationResult.ScopeDelta delta) {
        Map<String, ClaimValue> claims = new HashMap<>();
        claims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString("sub-1"));
        AccessTokenContent rotatedAccess = new AccessTokenContent(claims, ROTATED_ACCESS);
        return new RotationResult(rotatedAccess, ROTATED_REFRESH, ROTATED_ID, 300L, true, grantedScope, delta);
    }

    private static void awaitRelease(CountDownLatch latch) {
        try {
            Awaits.connect(latch, "the release latch to reach zero");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the release latch");
        } catch (TimeoutException e) {
            // Previously the boolean return was discarded, so an expiry let the test continue
            // silently. The ceiling reaching zero now fails the test instead.
            throw new AssertionError("the release latch never reached zero", e);
        }
    }

    private TokenRefreshCoordinator coordinator(Instant accessExpiry, RefreshExchange exchange) {
        return new TokenRefreshCoordinator(LEEWAY, unused -> accessExpiry, exchange, binding, revoked::add, DIRECT,
                EndedRefreshTokens.inert());
    }

    private SessionRecord storedSession() {
        SessionRecord live = session(CURRENT_REFRESH);
        create(store, live, NOW);
        return live;
    }

    private boolean sessionResolvable(Instant at) {
        return resolveById(store, SESSION_ID, at).isPresent();
    }

    private static RefreshExchange throwing(RuntimeException failure) {
        return (presented, _) -> {
            throw failure;
        };
    }

    private static CredentialRejectedException credentialRejected() {
        return new CredentialRejectedException("Token endpoint rejected the credential with HTTP 400");
    }

    @Nested
    @DisplayName("No refresh needed")
    class NoRefresh {

        @Test
        @DisplayName("Should return the current session unchanged when not within the expiry leeway")
        void shouldReturnCurrentWhenNotNearExpiry() {
            AtomicInteger calls = new AtomicInteger();
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NOT_NEAR, (rt, _) -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.CURRENT, outcome.kind());
            assertSame(live, outcome.session(), "the same session is returned unchanged");
            assertEquals(0, calls.get(), "no engine refresh is attempted when the token is not near expiry");
        }

        @Test
        @DisplayName("Should return the current session when near expiry but the session carries no refresh token")
        void shouldReturnCurrentWhenNoRefreshToken() {
            AtomicInteger calls = new AtomicInteger();
            SessionRecord live = session(null);
            create(store, live, NOW);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.CURRENT, outcome.kind());
            assertEquals(0, calls.get(), "a session without a refresh token cannot be refreshed");
        }
    }

    @Nested
    @DisplayName("Successful refresh")
    class SuccessfulRefresh {

        @Test
        @DisplayName("Should refresh through the engine and persist the rotated token material")
        void shouldRefreshAndPersist() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> rotation());

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            SessionRecord rotated = outcome.session();
            assertNotNull(rotated);
            assertEquals(ROTATED_ACCESS, rotated.accessToken());
            assertEquals(ROTATED_REFRESH, rotated.refreshToken());
            assertEquals(ROTATED_ID, rotated.idToken());
            assertEquals(live.expiresAt(), rotated.expiresAt(), "the absolute session cap is unchanged by a refresh");
            assertTrue(revoked.isEmpty(), "a successful refresh revokes nothing");
        }

        @Test
        @DisplayName("Should pass the session's current refresh token to the engine")
        void shouldPresentCurrentRefreshToken() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (presented, _) -> {
                calls.incrementAndGet();
                assertEquals(CURRENT_REFRESH, presented, "the coordinator presents the stored refresh token");
                return rotation();
            });

            coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(1, calls.get());
            SessionRecord persisted = resolveById(store, SESSION_ID, NOW).orElseThrow();
            assertEquals(ROTATED_ACCESS, persisted.accessToken(), "the store now serves the rotated token");
        }
    }

    /**
     * The active scope set {@code A} on the near-expiry leg: the refresh grant requests it, and the rotated
     * session's {@code A} follows the response's {@code scope}, or stays unchanged when the response omits it.
     */
    @Nested
    @DisplayName("Active scope set A across a near-expiry refresh")
    class ActiveScopeSet {

        private RefreshOutcome refreshReturning(RotationResult rotation, List<Set<String>> requested) {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (_, activeScopes) -> {
                requested.add(activeScopes);
                return rotation;
            });
            return coordinator.refresh(live, COOKIE_HEADER, NOW);
        }

        @Test
        @DisplayName("Should request the session's active scope set on the refresh grant")
        void shouldRequestActiveScopes() {
            List<Set<String>> requested = new ArrayList<>();

            refreshReturning(rotation(), requested);

            assertEquals(List.of(ACTIVE_SCOPES), requested,
                    "the grant sends A, never the static oidc.scopes, so an endpoint scope survives the refresh");
        }

        @Test
        @DisplayName("Should set the new A to the response scope")
        void shouldTakeResponseScope() {
            RefreshOutcome outcome = refreshReturning(
                    rotation("openid profile email orders:read", RotationResult.ScopeDelta.EQUAL), new ArrayList<>());

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            assertEquals(ACTIVE_SCOPES, rotatedScopes(outcome));
            assertEquals(ACTIVE_SCOPES, resolveById(store, SESSION_ID, NOW).orElseThrow().activeScopes(),
                    "the persisted session carries the new A to the next request");
        }

        @Test
        @DisplayName("Should keep A unchanged when the response omits scope")
        void shouldKeepScopesWhenOmitted() {
            RefreshOutcome outcome = refreshReturning(rotation(), new ArrayList<>());

            assertEquals(ACTIVE_SCOPES, rotatedScopes(outcome),
                    "an omitted scope means identical to the one requested (RFC 6749 §5.1)");
        }

        @Test
        @DisplayName("Should keep A unchanged when the response carries a blank scope")
        void shouldKeepScopesWhenBlank() {
            RefreshOutcome outcome = refreshReturning(rotation("  ", RotationResult.ScopeDelta.UNDECLARED),
                    new ArrayList<>());

            assertEquals(ACTIVE_SCOPES, rotatedScopes(outcome));
        }

        @Test
        @DisplayName("Should narrow A when the response narrows the scope")
        void shouldNarrowScopes() {
            List<Set<String>> requested = new ArrayList<>();
            RefreshOutcome outcome = refreshReturning(
                    rotation("openid  profile", RotationResult.ScopeDelta.NARROWED), requested);

            assertEquals(Set.of("openid", "profile"), rotatedScopes(outcome),
                    "the identity provider's grant is authoritative; whitespace runs delimit the names");
        }

        @Test
        @DisplayName("Should request the narrowed A on the next refresh")
        void shouldRequestNarrowedScopesNext() {
            SessionRecord live = storedSession();
            List<Set<String>> requested = new ArrayList<>();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (_, activeScopes) -> {
                requested.add(activeScopes);
                return rotation("openid", RotationResult.ScopeDelta.NARROWED);
            });

            coordinator.refresh(live, COOKIE_HEADER, NOW);
            SessionRecord rotated = resolveById(store, SESSION_ID, NOW).orElseThrow();
            coordinator.refresh(rotated, COOKIE_HEADER, NOW);

            assertEquals(List.of(ACTIVE_SCOPES, Set.of("openid")), requested,
                    "each refresh sends the A the previous one left on the session");
        }

        private static Set<String> rotatedScopes(RefreshOutcome outcome) {
            SessionRecord rotated = outcome.session();
            assertNotNull(rotated, "a refreshed outcome carries the rotated session");
            return rotated.activeScopes();
        }
    }

    /**
     * The granted scope set {@code S} on the near-expiry leg: that leg never changes it — whatever the
     * response does to {@code A}. Only a widening obtains a scope the session was not granted, and only
     * the scope-driven leg takes a refused scope out of {@code S} (see {@link ScopeLeg}); the narrowing
     * cases here are the matched control for that.
     */
    @Nested
    @DisplayName("Granted scope set S across a near-expiry refresh")
    class GrantedScopeSet {

        private SessionRecord refreshedWith(RotationResult rotation) {
            SessionRecord live = storedSession();
            RefreshOutcome outcome = coordinator(NEAR, (_, _) -> rotation).refresh(live, COOKIE_HEADER, NOW);
            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            SessionRecord rotated = outcome.session();
            assertNotNull(rotated, "a refreshed outcome carries the rotated session");
            return rotated;
        }

        @Test
        @DisplayName("Should leave S unchanged when the response echoes the requested scope")
        void shouldKeepGrantedScopesOnEqualResponse() {
            SessionRecord rotated = refreshedWith(
                    rotation("openid profile email orders:read", RotationResult.ScopeDelta.EQUAL));

            assertEquals(GRANTED_SCOPES, rotated.grantedScopes());
            assertEquals(GRANTED_SCOPES, resolveById(store, SESSION_ID, NOW).orElseThrow().grantedScopes(),
                    "the persisted session carries S unchanged to the next request");
        }

        @Test
        @DisplayName("Should leave S unchanged when the response narrows A")
        void shouldKeepGrantedScopesWhenActiveNarrows() {
            SessionRecord rotated = refreshedWith(rotation("openid", RotationResult.ScopeDelta.NARROWED));

            assertEquals(Set.of("openid"), rotated.activeScopes(), "A follows the narrowed response");
            assertEquals(GRANTED_SCOPES, rotated.grantedScopes(),
                    "a narrowing near-expiry refresh leaves S alone — only a scope-driven refresh that asked "
                            + "for a scope and did not get it takes it out of S");
        }

        @Test
        @DisplayName("Should leave S unchanged when the response omits scope")
        void shouldKeepGrantedScopesWhenScopeOmitted() {
            SessionRecord rotated = refreshedWith(rotation());

            assertEquals(GRANTED_SCOPES, rotated.grantedScopes());
        }

        @Test
        @DisplayName("Should not widen S even when the response grants more than A")
        void shouldNotWidenGrantedScopesFromResponse() {
            SessionRecord rotated = refreshedWith(
                    rotation("openid profile email orders:read billing:read", RotationResult.ScopeDelta.EQUAL));

            assertEquals(GRANTED_SCOPES, rotated.grantedScopes(),
                    "only a widening obtains a new scope — a refresh never adds to S");
        }

        @Test
        @DisplayName("Should leave S unchanged across a refresh in cookie mode, where persist re-seals it")
        void shouldKeepGrantedScopesInCookieMode() {
            byte[] keyMaterial = new byte[32];
            Arrays.fill(keyMaterial, (byte) 0x11);
            SecretKey key = new SecretKeySpec(keyMaterial, "AES");
            byte[] salt = new byte[32];
            Arrays.fill(salt, (byte) 0x22);
            CookieSessionBinding cookieBinding = new CookieSessionBinding(new SealedSessionCookieCodec(
                            SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL,
                            SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, key, (byte) 1), salt, activityCodec(),
                    SESSION_TTL);
            String setCookie = cookieBinding.bind(session(CURRENT_REFRESH), NOW).setCookieHeaders().getFirst();
            String cookieHeader = setCookie.substring(0, setCookie.indexOf(';'));
            SessionRecord live = cookieBinding.resolve(cookieHeader, NOW).orElseThrow();
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                    (_, _) -> rotation("openid", RotationResult.ScopeDelta.NARROWED), cookieBinding, revoked::add,
                    DIRECT, EndedRefreshTokens.bounded());

            RefreshOutcome outcome = coordinator.refresh(live, cookieHeader, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            String reSealed = outcome.setCookieHeaders().getFirst();
            SessionRecord reResolved = cookieBinding.resolve(reSealed.substring(0, reSealed.indexOf(';')), NOW)
                    .orElseThrow();
            assertEquals(Set.of("openid"), reResolved.activeScopes(), "the re-sealed cookie carries the narrowed A");
            assertEquals(GRANTED_SCOPES, reResolved.grantedScopes(), "the re-sealed cookie carries S unchanged");
        }
    }

    /**
     * A refusal the engine classifies {@code PRE_REDEMPTION}: the identity provider never processed the
     * grant, so the presented refresh token is still valid and the session must survive.
     */
    @Nested
    @DisplayName("Pre-redemption failure — the session is kept")
    class PreRedemption {

        @Test
        @DisplayName("Should defer and keep the session when the access token is still valid")
        void shouldDeferWhileAccessTokenIsValid() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR,
                    throwing(new TransportException("Connection refused by the token endpoint")));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.DEFERRED, outcome.kind());
            assertEquals(live, outcome.session(), "the unchanged session is mediated with its still-valid token");
            assertFalse(outcome.isFailure(), "a deferred refresh did not end the session");
            assertFalse(outcome.requestFailed(), "a deferred refresh still has a token to mediate");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "nothing was re-bound, so no cookie is emitted");
            assertTrue(sessionResolvable(NOW), "a pre-redemption failure never destroys the session");
            assertTrue(revoked.isEmpty(), "the presented token is still valid and must not be revoked");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should report unavailable yet keep the session when the access token has expired")
        void shouldBeUnavailableOnceAccessTokenExpired() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(EXPIRED,
                    throwing(new IllegalStateException("provider metadata is missing the token endpoint")));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.UNAVAILABLE, outcome.kind());
            assertNull(outcome.session(), "an expired access token leaves nothing to mediate");
            assertTrue(outcome.requestFailed(), "this request fails");
            assertFalse(outcome.isFailure(), "but the session did not end");
            assertTrue(sessionResolvable(NOW), "an expired access token does not turn a transient fault into a logout");
            assertTrue(revoked.isEmpty());
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should keep the session on a non-redeemed client-protocol refusal, which the engine reads as pre-redemption")
        void shouldKeepSessionOnPlainClientProtocolRefusal() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR,
                    throwing(new ClientProtocolException("token endpoint rejected the refresh grant")));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.DEFERRED, outcome.kind(),
                    "a ClientProtocolException carrying no redemption is PRE_REDEMPTION, not a rejected credential");
            assertTrue(sessionResolvable(NOW));
        }

        @Test
        @DisplayName("Should make no engine call while the back-off is in force, and retry once it has elapsed")
        void shouldBackOffBeforeRetrying() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (presented, _) -> {
                calls.incrementAndGet();
                throw new TransportException("Token endpoint returned HTTP 503");
            });
            Instant insideWindow = NOW.plusSeconds(2);
            Instant windowElapsed = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);

            RefreshOutcome first = coordinator.refresh(live, COOKIE_HEADER, NOW);
            RefreshOutcome backedOff = coordinator.refresh(live, COOKIE_HEADER, insideWindow);
            int callsInsideWindow = calls.get();
            RefreshOutcome retried = coordinator.refresh(live, COOKIE_HEADER, windowElapsed);

            assertEquals(RefreshOutcome.Kind.DEFERRED, first.kind());
            assertEquals(RefreshOutcome.Kind.DEFERRED, backedOff.kind(),
                    "a backed-off request takes the same disposition without reaching the engine");
            assertEquals(1, callsInsideWindow, "no second engine call is made inside the back-off window");
            assertEquals(RefreshOutcome.Kind.DEFERRED, retried.kind());
            assertEquals(2, calls.get(), "the next near-expiry request after the window attempts the refresh again");
        }

        @Test
        @DisplayName("Should report unavailable for a backed-off request whose access token has expired meanwhile")
        void shouldBeUnavailableWhileBackingOffOnceExpired() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            Instant accessExpiry = NOW.plusSeconds(1);
            TokenRefreshCoordinator coordinator = coordinator(accessExpiry, (presented, _) -> {
                calls.incrementAndGet();
                throw new TransportException("Token endpoint returned HTTP 503");
            });

            coordinator.refresh(live, COOKIE_HEADER, NOW);
            RefreshOutcome backedOff = coordinator.refresh(live, COOKIE_HEADER, NOW.plusSeconds(3));

            assertEquals(RefreshOutcome.Kind.UNAVAILABLE, backedOff.kind());
            assertEquals(1, calls.get(), "the back-off still suppresses the engine call");
            assertTrue(sessionResolvable(NOW.plusSeconds(3)), "the session is kept throughout");
        }

        @Test
        @DisplayName("Should refresh normally once the identity provider recovers after the back-off")
        void shouldRefreshAfterRecovery() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (presented, _) -> {
                if (calls.incrementAndGet() == 1) {
                    throw new TransportException("Token endpoint returned HTTP 503");
                }
                return rotation();
            });

            coordinator.refresh(live, COOKIE_HEADER, NOW);
            RefreshOutcome recovered = coordinator.refresh(live, COOKIE_HEADER,
                    NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF));

            assertEquals(RefreshOutcome.Kind.REFRESHED, recovered.kind());
            assertEquals(ROTATED_ACCESS, resolveById(store, SESSION_ID, NOW).orElseThrow().accessToken());
        }
    }

    /**
     * The back-off map at its bound: {@link TokenRefreshCoordinator#MAX_BACKOFF_ENTRIES} sessions each
     * hold an unexpired window from a real {@code PRE_REDEMPTION} failure, so a further failing session
     * cannot be tracked individually and must claim the shared overflow window before its engine call
     * rather than dropping the throttle. The saturating failures run with the coordinator's logger raised to {@code ERROR} so the
     * ten thousand expected {@code ApiSheriff-127} records are neither retained nor printed; the level is
     * lowered again before anything this suite asserts about records.
     */
    @Nested
    @DisplayName("Pre-redemption back-off at capacity — the overflow window")
    class BackOffSaturation {

        private static final String SATURATING_PREFIX = "saturating-";
        private static final TransportException OUTAGE = new TransportException("Token endpoint returned HTTP 503");
        private static final int PROBE_CONTENDERS = 8;

        private final AtomicInteger calls = new AtomicInteger();
        private InMemorySessionStore saturationStore;
        private SessionBinding saturationBinding;

        @BeforeEach
        void setUpSaturation() {
            saturationStore = new InMemorySessionStore(TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES + 16, SESSION_TTL,
                    Integer.MAX_VALUE, sessionId -> {
                    });
            saturationBinding = new ServerSessionBinding(saturationStore,
                    new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
        }

        private TokenRefreshCoordinator failingCoordinator(Instant accessExpiry) {
            return new TokenRefreshCoordinator(LEEWAY, unused -> accessExpiry, (presented, _) -> {
                calls.incrementAndGet();
                throw OUTAGE;
            }, saturationBinding, revoked::add, DIRECT, EndedRefreshTokens.inert());
        }

        private SessionRecord stored(String sessionId) {
            SessionRecord live = SessionRecord.builder()
                    .sessionId(sessionId)
                    .accessToken("access-" + sessionId)
                    .refreshToken("refresh-" + sessionId)
                    .idToken("id-" + sessionId)
                    .sub("sub-" + sessionId)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .build();
            create(saturationStore, live, NOW);
            return live;
        }

        private RefreshOutcome refresh(TokenRefreshCoordinator coordinator, SessionRecord live, Instant at) {
            return coordinator.refresh(live, cookieHeaderFor(live.sessionId()), at);
        }

        /** Fills the back-off map with unexpired windows opened at {@link #NOW} through real failures. */
        private void saturate(TokenRefreshCoordinator coordinator) {
            TestLogLevel.ERROR.addLogger(TokenRefreshCoordinator.class);
            for (int i = 0; i < TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES; i++) {
                refresh(coordinator, stored(SATURATING_PREFIX + i), NOW);
            }
            TestLogLevel.INFO.addLogger(TokenRefreshCoordinator.class);
            TestLoggerFactory.getTestHandler().clearRecords();
            assertEquals(TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES, calls.get(),
                    "every saturating session reached the engine exactly once");
        }

        @Test
        @DisplayName("Should throttle a session without its own window through the overflow window, with no engine call and no record")
        void shouldThrottleUntrackedSessionInsideOverflowWindow() {
            TokenRefreshCoordinator coordinator = failingCoordinator(NOW.plusSeconds(3));
            saturate(coordinator);
            SessionRecord overflowing = stored("overflowing");
            SessionRecord untracked = stored("untracked");

            RefreshOutcome overflowed = refresh(coordinator, overflowing, NOW);
            int callsAfterOverflow = calls.get();
            TestLoggerFactory.getTestHandler().clearRecords();
            RefreshOutcome whileValid = refresh(coordinator, untracked, NOW.plusSeconds(2));
            RefreshOutcome onceExpired = refresh(coordinator, untracked, NOW.plusSeconds(4));

            assertAll("a saturated map degrades the throttle to one shared window instead of dropping it",
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, overflowed.kind(),
                            "the session that could not be tracked is still kept"),
                    () -> assertEquals(TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES + 1, callsAfterOverflow,
                            "the overflowing failure itself reached the engine once"),
                    () -> assertEquals(callsAfterOverflow, calls.get(),
                            "a session without its own window makes no engine call inside the overflow window"),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, whileValid.kind(),
                            "its still-valid access token is mediated"),
                    () -> assertEquals(RefreshOutcome.Kind.UNAVAILABLE, onceExpired.kind(),
                            "an access token that expired inside the window leaves the request without one"),
                    () -> assertTrue(resolveById(saturationStore, "untracked", NOW.plusSeconds(4)).isPresent(),
                            "the overflow window never ends a session"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
        }

        @Test
        @DisplayName("Should let a session without its own window attempt the refresh again once the overflow window elapsed")
        void shouldRetryUntrackedSessionAfterOverflowWindow() {
            TokenRefreshCoordinator coordinator = failingCoordinator(NEAR);
            saturate(coordinator);
            refresh(coordinator, stored("overflowing"), NOW);
            int callsAfterOverflow = calls.get();

            RefreshOutcome retried = refresh(coordinator, stored("untracked"),
                    NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF));

            assertEquals(RefreshOutcome.Kind.DEFERRED, retried.kind());
            assertEquals(callsAfterOverflow + 1, calls.get(),
                    "the overflow window expires on its own after the fixed back-off");
        }

        @Test
        @DisplayName("Should govern a session holding its own window by that window alone, even while the overflow window is open")
        void shouldKeepPerSessionBehaviourForTrackedSession() {
            TokenRefreshCoordinator coordinator = failingCoordinator(NEAR);
            saturate(coordinator);
            SessionRecord tracked = resolveById(saturationStore, SATURATING_PREFIX + 0, NOW).orElseThrow();
            Instant overflowOpened = NOW.plusSeconds(3);
            Instant ownWindowElapsed = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);
            refresh(coordinator, stored("overflowing"), overflowOpened);
            int callsAfterOverflow = calls.get();

            RefreshOutcome insideOwnWindow = refresh(coordinator, tracked, NOW.plusSeconds(4));
            int callsInsideOwnWindow = calls.get();
            RefreshOutcome untracked = refresh(coordinator, stored("untracked"), ownWindowElapsed);
            int callsForUntracked = calls.get();
            RefreshOutcome afterOwnWindow = refresh(coordinator, tracked, ownWindowElapsed);

            assertAll("the overflow window applies only to sessions without their own window",
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, insideOwnWindow.kind()),
                    () -> assertEquals(callsAfterOverflow, callsInsideOwnWindow,
                            "the tracked session's own window still suppresses its engine call"),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, untracked.kind()),
                    () -> assertEquals(callsInsideOwnWindow, callsForUntracked,
                            "the overflow window opened at +3s is still in force at +5s for an untracked session"),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, afterOwnWindow.kind()),
                    () -> assertEquals(callsForUntracked + 1, calls.get(),
                            "once its own window elapsed the tracked session attempts again, overflow window or not"));
        }

        @Test
        @DisplayName("Should let exactly one of many concurrent untracked sessions probe an elapsed overflow window")
        void shouldAdmitOneProbePerElapsedOverflowWindow() throws Exception {
            CountDownLatch othersReturned = new CountDownLatch(PROBE_CONTENDERS - 1);
            AtomicBoolean holdProbe = new AtomicBoolean();
            AtomicBoolean holdExpired = new AtomicBoolean();
            TokenRefreshCoordinator coordinator = coordinatorWith(NEAR, (presented, _) -> {
                calls.incrementAndGet();
                if (holdProbe.get()) {
                    holdExpired.set(!awaitQuietly(othersReturned));
                }
                throw OUTAGE;
            });
            saturate(coordinator);
            refresh(coordinator, stored("overflowing"), NOW);
            int callsAfterOverflow = calls.get();
            Instant windowElapsed = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);
            List<SessionRecord> contenders = new ArrayList<>();
            for (int i = 0; i < PROBE_CONTENDERS; i++) {
                contenders.add(stored("contender-" + i));
            }
            TestLoggerFactory.getTestHandler().clearRecords();
            holdProbe.set(true);

            List<RefreshOutcome> outcomes = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(PROBE_CONTENDERS);
            try {
                List<Future<RefreshOutcome>> futures = new ArrayList<>();
                for (SessionRecord contender : contenders) {
                    futures.add(pool.submit(() -> {
                        try {
                            return refresh(coordinator, contender, windowElapsed);
                        } finally {
                            othersReturned.countDown();
                        }
                    }));
                }
                for (Future<RefreshOutcome> future : futures) {
                    outcomes.add(Awaits.connect(future, "a contending untracked refresh to complete"));
                }
            } finally {
                pool.shutdownNow();
            }

            assertAll("the untracked sessions together make one attempt for the elapsed overflow window",
                    () -> assertFalse(holdExpired.get(),
                            "non-claiming contenders must return before the probe is released"),
                    () -> assertEquals(callsAfterOverflow + 1, calls.get(),
                            "exactly one contender claims the probe and reaches the engine (probe hold expired: "
                                    + holdExpired.get() + ")"),
                    () -> assertTrue(outcomes.stream().allMatch(o -> o.kind() == RefreshOutcome.Kind.DEFERRED),
                            "every contender keeps its session with a still-valid access token: " + outcomes),
                    () -> assertTrue(contenders.stream().allMatch(
                                    c -> resolveById(saturationStore, c.sessionId(), windowElapsed).isPresent()),
                            "no contender's session was ended"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
        }

        @Test
        @DisplayName("Should release the probe claim once the identity provider processes the probe's grant")
        void shouldReleaseProbeClaimOnRecovery() {
            AtomicBoolean recovered = new AtomicBoolean();
            TokenRefreshCoordinator coordinator = coordinatorWith(NEAR, (presented, _) -> {
                calls.incrementAndGet();
                if (recovered.get()) {
                    return rotation();
                }
                throw OUTAGE;
            });
            saturate(coordinator);
            refresh(coordinator, stored("overflowing"), NOW);
            Instant windowElapsed = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);
            recovered.set(true);
            int callsBeforeProbe = calls.get();

            RefreshOutcome probe = refresh(coordinator, stored("probe"), windowElapsed);
            RefreshOutcome next = refresh(coordinator, stored("next"), windowElapsed);

            assertAll("a recovered provider stops serializing the untracked sessions",
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, probe.kind()),
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, next.kind(),
                            "the next untracked session is not deferred behind a released claim"),
                    () -> assertEquals(callsBeforeProbe + 2, calls.get(),
                            "both the probe and the next untracked session reach the engine"));
        }

        @Test
        @DisplayName("Should release the probe claim when the failing probe can be tracked on its own window")
        void shouldReleaseProbeClaimWhenProbeIsTracked() {
            TokenRefreshCoordinator coordinator = failingCoordinator(NEAR);
            saturate(coordinator);
            refresh(coordinator, stored("overflowing"), NOW);
            Instant windowElapsed = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);
            int callsBeforeProbe = calls.get();

            RefreshOutcome probe = refresh(coordinator, stored("probe"), windowElapsed);
            RefreshOutcome next = refresh(coordinator, stored("next"), windowElapsed);

            assertAll("the saturating windows expired, so the per-session windows govern again",
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, probe.kind()),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, next.kind()),
                    () -> assertEquals(callsBeforeProbe + 2, calls.get(),
                            "the next untracked session is governed by no window and reaches the engine"));
        }

        @Test
        @DisplayName("Should keep the overflow window after a probe that fails while the map is still saturated, even at an equal instant")
        void shouldRetainOverflowWindowAfterSaturatedProbeFailure() {
            TokenRefreshCoordinator coordinator = failingCoordinator(NEAR);
            saturate(coordinator);
            refresh(coordinator, stored("overflowing"), NOW);
            Instant windowElapsed = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);
            refailTracked(coordinator, windowElapsed);
            int callsBeforeProbe = calls.get();

            RefreshOutcome probe = refresh(coordinator, stored("probe"), windowElapsed);
            int callsAfterProbe = calls.get();
            RefreshOutcome sameInstant = refresh(coordinator, stored("same-instant"), windowElapsed);
            RefreshOutcome insideWindow = refresh(coordinator, stored("inside-window"), windowElapsed.plusSeconds(3));

            assertAll("the probe's failure, recorded at the claimed instant, keeps the overflow window in force",
                    () -> assertEquals(callsBeforeProbe + 1, callsAfterProbe, "the probe reached the engine"),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, probe.kind()),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, sameInstant.kind()),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, insideWindow.kind()),
                    () -> assertEquals(callsAfterProbe, calls.get(),
                            "no further untracked session reaches the engine inside the retained window"));
        }

        @Test
        @DisplayName("Should let exactly one of many concurrent untracked sessions probe the first overflow window at a saturated map")
        void shouldAdmitOneProbeForFirstOverflowWindow() throws Exception {
            CountDownLatch othersReturned = new CountDownLatch(PROBE_CONTENDERS - 1);
            AtomicBoolean holdProbe = new AtomicBoolean();
            AtomicBoolean holdExpired = new AtomicBoolean();
            TokenRefreshCoordinator coordinator = coordinatorWith(NEAR, (presented, _) -> {
                calls.incrementAndGet();
                if (holdProbe.get()) {
                    holdExpired.set(!awaitQuietly(othersReturned));
                }
                throw OUTAGE;
            });
            saturate(coordinator);
            Instant insideSaturatingWindows = NOW.plusSeconds(1);
            List<SessionRecord> contenders = new ArrayList<>();
            for (int i = 0; i < PROBE_CONTENDERS; i++) {
                contenders.add(stored("first-window-contender-" + i));
            }
            holdProbe.set(true);

            List<RefreshOutcome> outcomes = runConcurrently(coordinator, contenders, insideSaturatingWindows,
                    othersReturned);

            assertAll("no overflow window was open, yet the untracked sessions together make one attempt",
                    () -> assertFalse(holdExpired.get(),
                            "non-claiming contenders must return before the probe is released"),
                    () -> assertEquals(TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES + 1, calls.get(),
                            "exactly one contender claims the first window and reaches the engine (probe hold expired: "
                                    + holdExpired.get() + ")"),
                    () -> assertTrue(outcomes.stream().allMatch(o -> o.kind() == RefreshOutcome.Kind.DEFERRED),
                            "every contender keeps its session with a still-valid access token: " + outcomes),
                    () -> assertTrue(contenders.stream().allMatch(
                                    c -> resolveById(saturationStore, c.sessionId(), insideSaturatingWindows).isPresent()),
                            "no contender's session was ended"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
        }

        @Test
        @DisplayName("Should prune expired saturating windows on admission so concurrent healthy untracked sessions are not serialised")
        void shouldNotPinUntrackedSessionsToExpiredSaturation() throws Exception {
            CountDownLatch allEntered = new CountDownLatch(PROBE_CONTENDERS);
            AtomicBoolean healthy = new AtomicBoolean();
            AtomicBoolean holdExpired = new AtomicBoolean();
            TokenRefreshCoordinator coordinator = coordinatorWith(NEAR, (presented, _) -> {
                calls.incrementAndGet();
                if (!healthy.get()) {
                    throw OUTAGE;
                }
                allEntered.countDown();
                if (!awaitQuietly(allEntered)) {
                    holdExpired.set(true);
                }
                return rotation();
            });
            saturate(coordinator);
            Instant saturatingWindowsExpired = NOW.plus(TokenRefreshCoordinator.PRE_REDEMPTION_RETRY_BACKOFF);
            List<SessionRecord> contenders = new ArrayList<>();
            for (int i = 0; i < PROBE_CONTENDERS; i++) {
                contenders.add(stored("healthy-contender-" + i));
            }
            healthy.set(true);

            List<RefreshOutcome> outcomes = runConcurrently(coordinator, contenders, saturatingWindowsExpired,
                    new CountDownLatch(0));

            assertAll("expired saturating windows no longer throttle anyone",
                    () -> assertFalse(holdExpired.get(),
                            "all healthy contenders must enter without serialisation"),
                    () -> assertEquals(TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES + PROBE_CONTENDERS, calls.get(),
                            "every contender reached the engine at once (entry hold expired: " + holdExpired.get()
                                    + ")"),
                    () -> assertTrue(outcomes.stream().allMatch(o -> o.kind() == RefreshOutcome.Kind.REFRESHED),
                            "every contender refreshed: " + outcomes));
        }

        /**
         * Runs one refresh per contender concurrently at {@code at}, counting {@code returned} down as each
         * one returns, and collects the outcomes with a bounded wait per contender.
         */
        private List<RefreshOutcome> runConcurrently(TokenRefreshCoordinator coordinator,
                List<SessionRecord> contenders, Instant at, CountDownLatch returned) throws Exception {
            TestLoggerFactory.getTestHandler().clearRecords();
            List<RefreshOutcome> outcomes = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(contenders.size());
            try {
                List<Future<RefreshOutcome>> futures = new ArrayList<>();
                for (SessionRecord contender : contenders) {
                    futures.add(pool.submit(() -> {
                        try {
                            return refresh(coordinator, contender, at);
                        } finally {
                            returned.countDown();
                        }
                    }));
                }
                for (Future<RefreshOutcome> future : futures) {
                    outcomes.add(Awaits.connect(future, "a contending untracked refresh to complete"));
                }
            } finally {
                pool.shutdownNow();
            }
            return outcomes;
        }

        private TokenRefreshCoordinator coordinatorWith(Instant accessExpiry, RefreshExchange exchange) {
            return new TokenRefreshCoordinator(LEEWAY, unused -> accessExpiry, exchange, saturationBinding,
                    revoked::add, DIRECT, EndedRefreshTokens.inert());
        }

        /**
         * Fails every saturating session again at {@code at}, once its own window has elapsed, so the map
         * holds {@link TokenRefreshCoordinator#MAX_BACKOFF_ENTRIES} unexpired windows past that instant.
         */
        private void refailTracked(TokenRefreshCoordinator coordinator, Instant at) {
            TestLogLevel.ERROR.addLogger(TokenRefreshCoordinator.class);
            for (int i = 0; i < TokenRefreshCoordinator.MAX_BACKOFF_ENTRIES; i++) {
                refresh(coordinator, resolveById(saturationStore, SATURATING_PREFIX + i, at).orElseThrow(), at);
            }
            TestLogLevel.INFO.addLogger(TokenRefreshCoordinator.class);
            TestLoggerFactory.getTestHandler().clearRecords();
        }

        /**
         * Holds the probe until the other contenders have returned, bounded and deliberately non-failing:
         * against a coordinator without the claim every contender holds here, and the test must then go red
         * on the engine-call count rather than on a timeout.
         *
         * @return whether the other contenders returned within the bound
         */
        private static boolean awaitQuietly(CountDownLatch latch) {
            try {
                return latch.await(Awaits.TEARDOWN_CEILING_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    /**
     * A refusal that ends the session: the identity provider rejected the credential, the gateway
     * refused a response the provider had already redeemed, or the rotated session could not be
     * persisted.
     */
    @Nested
    @DisplayName("Session-ending failures")
    class SessionEnding {

        @Test
        @DisplayName("Should destroy the session and revoke nothing when the identity provider rejects the credential")
        void shouldFailAndDestroyOnCredentialRejection() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, throwing(credentialRejected()));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertTrue(outcome.isFailure());
            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertNull(outcome.session(), "a failed outcome carries no session");
            assertFalse(sessionResolvable(NOW), "the session is destroyed on a rejected credential");
            assertTrue(revoked.isEmpty(), "nothing was redeemed, so there is no live refresh token to revoke");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_CREDENTIAL_REJECTED);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
        }

        /**
         * Reuse detection is the identity provider's strict rotation: a replayed superseded refresh token
         * is answered {@code invalid_grant}, which the engine raises as {@link CredentialRejectedException}.
         */
        @Test
        @DisplayName("Should destroy the session when the identity provider rejects a replayed refresh token")
        void shouldFailOnReplayRejectedByIdentityProvider() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (presented, _) -> {
                calls.incrementAndGet();
                throw credentialRejected();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);
            RefreshOutcome afterwards = coordinator.refresh(live, COOKIE_HEADER, NOW.plusSeconds(1));

            assertTrue(outcome.isFailure(), "a replay rejected under strict rotation fails the refresh");
            assertFalse(sessionResolvable(NOW), "the replaying session is destroyed");
            assertEquals(RefreshOutcome.Kind.NO_SESSION, afterwards.kind(),
                    "the destroyed session cannot be resumed on a later request: the leader resolves none");
            assertEquals(1, calls.get(), "a destroyed session never reaches the engine again");
        }

        @Test
        @DisplayName("Should destroy the session and revoke the successor when a rotated response is refused")
        void shouldRevokeSuccessorOnRefusedRotatedRedemption() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, throwing(new RedeemedScopeRefusalException(
                    "granted a broader scope than requested", RefreshRedemption.rotated(ROTATED_REFRESH))));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertFalse(sessionResolvable(NOW), "a refused redeemed response ends the session");
            assertEquals(List.of(ROTATED_REFRESH), revoked,
                    "the presented token is burned, so the successor is the one live refresh token");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_REDEEMED_RESPONSE_REFUSED);
        }

        @Test
        @DisplayName("Should destroy the session and revoke the presented token when a non-rotated response is refused")
        void shouldRevokePresentedTokenOnRefusedNonRotatedRedemption() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, throwing(new RedeemedScopeRefusalException(
                    "granted a broader scope than requested", RefreshRedemption.notRotated())));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertFalse(sessionResolvable(NOW));
            assertEquals(List.of(CURRENT_REFRESH), revoked,
                    "without rotation the presented token survived the exchange and is the live one");
        }

        @Test
        @DisplayName("Should destroy the session and revoke nothing when rotation is unknown")
        void shouldRevokeNothingWhenRotationUnknown() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR,
                    throwing(new RedeemedResponseException("Token endpoint returned an unparseable 2xx body")));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind());
            assertFalse(sessionResolvable(NOW), "an unknown rotation fails closed");
            assertTrue(revoked.isEmpty(), "no successor is known, so nothing can be named for revocation");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_REDEEMED_RESPONSE_REFUSED);
        }

        @Test
        @DisplayName("Should destroy the session and revoke the returned refresh token when persisting the rotation fails")
        void shouldEndSessionOnPersistFailure() {
            storedSession();
            SessionBinding persistFailing = new PersistFailingBinding(binding);
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                    (rt, _) -> rotation(), persistFailing, revoked::add, DIRECT, EndedRefreshTokens.inert());

            RefreshOutcome outcome = coordinator.refresh(session(CURRENT_REFRESH), COOKIE_HEADER, NOW);

            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind(),
                    "the presented token is already redeemed, so a persist failure cannot keep the session");
            assertFalse(sessionResolvable(NOW), "the session holding the burned token is destroyed");
            assertEquals(List.of(ROTATED_REFRESH), revoked, "the refresh token the exchange returned is revoked");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_PERSIST_FAILURE);
        }

        @Test
        @DisplayName("Should still fail and destroy the session when the revocation endpoint fails")
        void shouldIgnoreRevocationFailure() {
            SessionRecord live = storedSession();
            AtomicInteger attempts = new AtomicInteger();
            RefreshTokenRevocation failingRevocation = token -> {
                attempts.incrementAndGet();
                throw new TransportException("Revocation endpoint returned unexpected HTTP status 500");
            };
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                    throwing(new RedeemedScopeRefusalException("granted a broader scope than requested",
                            RefreshRedemption.rotated(ROTATED_REFRESH))),
                    binding, failingRevocation, DIRECT, EndedRefreshTokens.inert());

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertEquals(1, attempts.get(), "revocation was attempted");
            assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind(), "a failing revocation never changes the disposition");
            assertFalse(sessionResolvable(NOW));
        }

        @Test
        @DisplayName("Should report no session, not a session end, when the session was destroyed between the near-expiry check and the refresh")
        void shouldReportNoSessionWhenSessionGone() {
            SessionRecord live = session(CURRENT_REFRESH);
            // Deliberately NOT stored — models a session destroyed concurrently before the lead resolves it.
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertAll("a session absent from the store cannot be refreshed, and this coordinator did not end it",
                    () -> assertEquals(RefreshOutcome.Kind.NO_SESSION, outcome.kind()),
                    () -> assertFalse(outcome.isFailure(), "nothing was destroyed here, so it is no session end"),
                    () -> assertEquals(0, calls.get(), "no engine call is made for a request that resolves no session"),
                    () -> assertTrue(revoked.isEmpty(), "nothing is revoked"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }
    }

    /**
     * The leader's second resolve — from the request's own cookie, under the single-flight exclusion —
     * finds no session. That is not a destroyed session: in server mode a step-up or a widening may have
     * re-issued the cookie value while the session lives on. The coordinator reports
     * {@code NO_SESSION}, destroys, marks and revokes nothing, and leaves the session's back-off entry.
     * A refresh that was itself in flight across the re-issue still persists into the same session.
     */
    @Nested
    @DisplayName("No session for this request — the cookie value was re-issued (server mode)")
    class NoSessionForRequest {

        private static final String MISSING = "orders:write";
        private static final Set<String> REQUESTED = Set.of("openid", "profile", "email", "orders:read", MISSING);

        /** Re-issues the stored session's cookie value through the binding and returns the new request cookie. */
        private String reissueCookie(SessionRecord live) {
            String setCookie = binding.persistReissuingCookie(live, NOW).orElseThrow().setCookieHeaders().getFirst();
            return setCookie.substring(0, setCookie.indexOf(';'));
        }

        private void assertNothingEnded(RefreshOutcome outcome, String reissuedCookie, int engineCalls) {
            assertAll("the request resolved no session and nothing was ended",
                    () -> assertEquals(RefreshOutcome.Kind.NO_SESSION, outcome.kind()),
                    () -> assertNull(outcome.session(), "a no-session outcome carries no session"),
                    () -> assertTrue(outcome.setCookieHeaders().isEmpty(), "and no cookie"),
                    () -> assertFalse(outcome.isFailure(), "the session was not destroyed"),
                    () -> assertFalse(outcome.requestFailed(), "nor is it the kept-session disposition"),
                    () -> assertEquals(0, engineCalls, "no engine call is made"),
                    () -> assertEquals(1, store.size(), "the session is still stored"),
                    () -> assertTrue(binding.resolve(reissuedCookie, NOW).isPresent(),
                            "the session lives on under the re-issued cookie value"),
                    () -> assertTrue(revoked.isEmpty(), "nothing is revoked"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should report NO_SESSION and destroy nothing on the near-expiry leg")
        void shouldReportNoSessionOnNearExpiryLeg() {
            SessionRecord live = storedSession();
            String reissuedCookie = reissueCookie(live);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertNothingEnded(outcome, reissuedCookie, calls.get());
        }

        @Test
        @DisplayName("Should report NO_SESSION and destroy nothing on the scope-driven leg")
        void shouldReportNoSessionOnScopeLeg() {
            SessionRecord live = storedSession();
            String reissuedCookie = reissueCookie(live);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NOT_NEAR, (rt, _) -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);

            assertNothingEnded(outcome, reissuedCookie, calls.get());
        }

        @Test
        @DisplayName("Should serve the next request, which carries the re-issued cookie value")
        void shouldRefreshOnTheNextAttempt() {
            SessionRecord live = storedSession();
            String reissuedCookie = reissueCookie(live);
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> rotation());
            RefreshOutcome withPreviousValue = coordinator.refresh(live, COOKIE_HEADER, NOW);

            RefreshOutcome withReissuedValue = coordinator.refresh(live, reissuedCookie, NOW);

            assertAll("the one unauthenticated answer is followed by a served request",
                    () -> assertEquals(RefreshOutcome.Kind.NO_SESSION, withPreviousValue.kind()),
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, withReissuedValue.kind()),
                    () -> assertEquals(ROTATED_ACCESS,
                            binding.resolve(reissuedCookie, NOW).orElseThrow().accessToken()));
        }

        @Test
        @DisplayName("Should leave the session's back-off entry in place, so a session still backing off stays throttled")
        void shouldKeepTheBackOffEntry() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> {
                calls.incrementAndGet();
                throw new TransportException("Token endpoint returned HTTP 503");
            });
            RefreshOutcome backOffArmed = coordinator.refresh(live, COOKIE_HEADER, NOW);
            String reissuedCookie = reissueCookie(live);

            RefreshOutcome withPreviousValue = coordinator.refresh(live, COOKIE_HEADER, NOW.plusSeconds(1));
            RefreshOutcome insideWindow = coordinator.refresh(live, reissuedCookie, NOW.plusSeconds(2));

            assertAll("a no-session answer does not lift the throttle of a session that is alive",
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, backOffArmed.kind()),
                    () -> assertEquals(RefreshOutcome.Kind.NO_SESSION, withPreviousValue.kind()),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, insideWindow.kind(),
                            "the request with the live cookie is still deferred inside the back-off window"),
                    () -> assertEquals(1, calls.get(), "and makes no second engine call"));
        }

        @Test
        @DisplayName("Should persist a refresh in flight across a re-issue into the same session and leave the new cookie value in force")
        void shouldPersistAcrossAReissue() {
            SessionRecord live = storedSession();
            AtomicReference<String> reissuedCookie = new AtomicReference<>();
            // The exchange runs on the leader's thread between its resolve and its persist, so the
            // re-issue lands exactly while the refresh is in flight.
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> {
                reissuedCookie.set(reissueCookie(live));
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertAll("the rotated session is written under the stable session id",
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind()),
                    () -> assertTrue(outcome.setCookieHeaders().isEmpty(),
                            "the refresh's own write re-issues nothing"),
                    () -> assertEquals(1, store.size(), "it is still the one session"),
                    () -> assertEquals(ROTATED_ACCESS,
                            binding.resolve(reissuedCookie.get(), NOW).orElseThrow().accessToken(),
                            "the re-issued cookie value resolves the rotated token material"),
                    () -> assertEquals(SESSION_ID, binding.resolve(reissuedCookie.get(), NOW).orElseThrow().sessionId()),
                    () -> assertTrue(binding.resolve(COOKIE_HEADER, NOW).isEmpty(),
                            "the refresh did not put the previous cookie value back"),
                    () -> assertTrue(revoked.isEmpty(), "a persisted rotation revokes nothing"));
        }

        /**
         * The coalesced case: the leader's request still carries the previous cookie value, a waiter that
         * already carries the re-issued one joins the leader's refresh and shares its answer. The waiter's
         * own cookie is valid and the session is alive; the one no-session answer is the price of sharing
         * the leader's result instead of resolving again per waiter.
         */
        @Test
        @DisplayName("Should hand a coalesced waiter carrying the re-issued cookie value the leader's NO_SESSION answer once")
        void shouldShareNoSessionWithCoalescedWaiterCarryingTheNewValue() throws Exception {
            SessionRecord live = storedSession();
            String reissuedCookie = reissueCookie(live);
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch leaderResolving = new CountDownLatch(1);
            AtomicReference<Thread> waiterThread = new AtomicReference<>();
            // Holds the leader inside its second resolve until the waiter is parked in the single-flight
            // join. The hold is a condition, not a delay.
            SessionBinding holdingLeader = new ResolveHookBinding(binding, cookieHeader -> {
                if (COOKIE_HEADER.equals(cookieHeader)) {
                    leaderResolving.countDown();
                    TerminatedDuringRefresh.awaitParked(waiterThread);
                }
            });
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, (rt, _) -> {
                calls.incrementAndGet();
                return rotation();
            }, holdingLeader, revoked::add, DIRECT, EndedRefreshTokens.inert());
            FutureTask<RefreshOutcome> leader = new FutureTask<>(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
            FutureTask<RefreshOutcome> waiter = new FutureTask<>(() -> coordinator.refresh(live, reissuedCookie, NOW));
            Thread leading = new Thread(leader, "refresh-leader");
            Thread waiting = new Thread(waiter, "refresh-waiter");
            leading.start();
            RefreshOutcome leaderOutcome;
            RefreshOutcome waiterOutcome;
            try {
                Awaits.connect(leaderResolving, "the leader to reach its second resolve");
                waiterThread.set(waiting);
                waiting.start();
                leaderOutcome = Awaits.connect(leader, "the leader refresh to complete");
                waiterOutcome = Awaits.connect(waiter, "the coalesced waiter to complete");
            } finally {
                leading.interrupt();
                waiting.interrupt();
            }

            RefreshOutcome nextAttempt = coordinator.refresh(live, reissuedCookie, NOW);

            assertAll("the waiter shares the leader's answer although its own cookie value is the live one",
                    () -> assertEquals(RefreshOutcome.Kind.NO_SESSION, leaderOutcome.kind()),
                    () -> assertEquals(RefreshOutcome.Kind.NO_SESSION, waiterOutcome.kind(),
                            "the coalesced waiter is not resolved again: it gets the leader's no-session answer"),
                    () -> assertTrue(waiterOutcome.setCookieHeaders().isEmpty(), "and no cookie with it"),
                    () -> assertFalse(waiterOutcome.isFailure(), "which is not a session end"),
                    () -> assertEquals(1, store.size(), "the session is alive throughout"),
                    () -> assertTrue(revoked.isEmpty(), "nothing is revoked"),
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, nextAttempt.kind(),
                            "once: the waiter's next attempt with the same cookie value is served"),
                    () -> assertEquals(1, calls.get(), "and only that attempt reached the engine"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }
    }

    /**
     * A logout or a back-channel logout that destroys the session while its refresh is in flight —
     * after the leader resolved it, before the rotated session is persisted. In server mode the
     * rotated session must not be written back.
     * <p>
     * The interleaving is driven from the {@link RefreshExchange} stub, which runs on the leader's own
     * thread between the resolve and the persist: it terminates the session through the binding's own
     * destroy methods and then returns the rotation. Nothing depends on timing. Each leg carries a
     * matched control running the same stub with a termination that does nothing, so the refusal is
     * caused by the termination and not by the stub.
     */
    @Nested
    @DisplayName("Session terminated while the refresh is in flight (server mode)")
    class TerminatedDuringRefresh {

        private static final String IDP_SID = "idp-session-1";
        private static final String SUB = "sub-1";
        private static final String MISSING = "orders:write";
        private static final Set<String> REQUESTED = Set.of("openid", "profile", "email", "orders:read", MISSING);
        private static final String REQUESTED_SCOPE = "openid profile email orders:read orders:write";
        private static final String REFRESHED_ID = BffLogMessages.INFO.TOKEN_REFRESHED.resolveIdentifierString();

        /** The matched control's termination: it destroys nothing, so the session is there at the persist. */
        private static final BiConsumer<SessionBinding, SessionRecord> NO_TERMINATION = (target, session) -> {
            // Deliberately empty: the control runs the same stub and leaves the session in place.
        };

        static Stream<Arguments> terminations() {
            BiConsumer<SessionBinding, SessionRecord> bySessionIdentity = SessionBinding::destroy;
            BiConsumer<SessionBinding, SessionRecord> bySid = (target, session) -> target.destroyBySid(IDP_SID);
            BiConsumer<SessionBinding, SessionRecord> bySub = (target, session) -> target.destroyBySub(SUB);
            return Stream.of(
                    Arguments.of("a logout destroying the session by its identity", bySessionIdentity),
                    Arguments.of("a back-channel logout destroying it by sid", bySid),
                    Arguments.of("a back-channel logout destroying it by sub", bySub));
        }

        /** Stores a session that carries an IdP {@code sid}, so it is reachable by all three destroy forms. */
        private SessionRecord storedSessionWithSid() {
            SessionRecord live = SessionRecord.builder()
                    .sessionId(SESSION_ID)
                    .accessToken("access-current")
                    .refreshToken(CURRENT_REFRESH)
                    .idToken("id-current")
                    .sub(SUB)
                    .sid(IDP_SID)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .activeScopes(ACTIVE_SCOPES)
                    .grantedScopes(GRANTED_SCOPES)
                    .build();
            create(store, live, NOW);
            return live;
        }

        /**
         * An exchange that runs {@code termination} against the live session and then returns
         * {@code rotation} — the identity provider redeemed the grant while the session was terminated.
         */
        private RefreshExchange terminatingExchange(SessionRecord live,
                BiConsumer<SessionBinding, SessionRecord> termination, RotationResult rotation,
                AtomicInteger calls) {
            return (presented, _) -> {
                calls.incrementAndGet();
                termination.accept(binding, live);
                return rotation;
            };
        }

        @ParameterizedTest(name = "near-expiry leg: {0}")
        @MethodSource("terminations")
        @DisplayName("Should not write the rotated session back on the near-expiry leg, return FAILED and revoke the rotated token")
        void shouldNotRecreateOnNearExpiryLeg(String label, BiConsumer<SessionBinding, SessionRecord> termination) {
            SessionRecord live = storedSessionWithSid();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR,
                    terminatingExchange(live, termination, rotation(), calls));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertTerminated(label, outcome, calls.get());
        }

        @ParameterizedTest(name = "scope-driven leg: {0}")
        @MethodSource("terminations")
        @DisplayName("Should not write the rotated session back on the scope-driven leg, and return FAILED rather than SCOPE_REFUSED or REFRESHED")
        void shouldNotRecreateOnScopeLeg(String label, BiConsumer<SessionBinding, SessionRecord> termination) {
            SessionRecord live = storedSessionWithSid();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NOT_NEAR, terminatingExchange(live, termination,
                    rotation(REQUESTED_SCOPE, RotationResult.ScopeDelta.EQUAL), calls));

            RefreshOutcome outcome = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);

            assertTerminated(label, outcome, calls.get());
        }

        private void assertTerminated(String label, RefreshOutcome outcome, int engineCalls) {
            assertAll(label,
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind(),
                            "the request is answered as unauthenticated and the stage clears the cookie"),
                    () -> assertNull(outcome.session(), "no session is handed on"),
                    () -> assertTrue(outcome.setCookieHeaders().isEmpty(), "nothing was re-bound"),
                    () -> assertEquals(1, engineCalls, "the exchange ran, so the termination struck in flight"),
                    () -> assertFalse(sessionResolvable(NOW), "the terminated session is not written back"),
                    () -> assertEquals(0, store.size(), "the store holds no session at all"),
                    () -> assertEquals(List.of(ROTATED_REFRESH), revoked,
                            "the rotated refresh token is live at the identity provider and held nowhere, so it is revoked"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_SESSION_TERMINATED);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, TokenRefreshCoordinator.REASON_PERSIST_FAILURE);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.INFO, REFRESHED_ID);
            assertTrue(TestLoggerFactory.getTestHandler().resolveLogMessages(TestLogLevel.WARN).stream()
                            .filter(entry -> String.valueOf(entry.getMessage()).contains(REFRESH_FAILED_ID))
                            .allMatch(entry -> entry.getThrown() == null),
                    "a termination that overtook the refresh is no fault, so the record carries no exception");
        }

        @Test
        @DisplayName("Should refresh and persist on the near-expiry leg when nothing terminates the session (matched control)")
        void shouldRefreshOnNearExpiryLegWithoutTermination() {
            SessionRecord live = storedSessionWithSid();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR,
                    terminatingExchange(live, NO_TERMINATION, rotation(), calls));

            RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);

            assertAll("the same stub refreshes when the session is still there at the persist",
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind()),
                    () -> assertEquals(1, calls.get()),
                    () -> assertEquals(ROTATED_ACCESS, resolveById(store, SESSION_ID, NOW).orElseThrow().accessToken()),
                    () -> assertTrue(revoked.isEmpty(), "a persisted rotation revokes nothing"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should refresh and persist on the scope-driven leg when nothing terminates the session (matched control)")
        void shouldRefreshOnScopeLegWithoutTermination() {
            SessionRecord live = storedSessionWithSid();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NOT_NEAR, terminatingExchange(live, NO_TERMINATION,
                    rotation(REQUESTED_SCOPE, RotationResult.ScopeDelta.EQUAL), calls));

            RefreshOutcome outcome = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);

            assertAll("the same stub obtains the scope when the session is still there at the persist",
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind()),
                    () -> assertEquals(1, calls.get()),
                    () -> assertEquals(REQUESTED, resolveById(store, SESSION_ID, NOW).orElseThrow().activeScopes()),
                    () -> assertTrue(revoked.isEmpty(), "a persisted rotation revokes nothing"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }


        @Test
        @DisplayName("Should hand a coalesced near-expiry waiter the leader's FAILED outcome and revoke the rotated token once")
        void shouldShareTerminatedOutcomeWithCoalescedWaiter() throws Exception {
            SessionRecord live = storedSessionWithSid();

            CoalescedRun run = coalesceWithTermination(live,
                    coordinator -> coordinator.refresh(live, COOKIE_HEADER, NOW), rotation());

            assertCoalescedTermination(run);
        }

        @Test
        @DisplayName("Should hand a coalesced scope waiter the leader's FAILED outcome, never SCOPE_REFUSED")
        void shouldShareTerminatedOutcomeWithCoalescedScopeWaiter() throws Exception {
            SessionRecord live = storedSessionWithSid();

            CoalescedRun run = coalesceWithTermination(live,
                    coordinator -> coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW),
                    rotation(REQUESTED_SCOPE, RotationResult.ScopeDelta.EQUAL));

            assertCoalescedTermination(run);
        }

        private void assertCoalescedTermination(CoalescedRun run) {
            assertAll("the waiter shares the leader's session-ended outcome",
                    () -> assertEquals(1, run.calls(), "one exchange served both requests"),
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, run.leader().kind()),
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, run.follower().kind(),
                            "the coalesced waiter is answered as unauthenticated too"),
                    () -> assertFalse(sessionResolvable(NOW), "the terminated session is not written back"),
                    () -> assertEquals(List.of(ROTATED_REFRESH), revoked, "the rotated token is revoked exactly once"));
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_SESSION_TERMINATED);
        }

        /**
         * Runs {@code call} twice on the stored session: once as the single-flight leader and once as
         * a waiter that has provably coalesced with it. The leader's exchange holds until the waiter's
         * thread is parked — the single-flight join is the only place a refresh call parks — then
         * destroys the session and returns {@code rotation}. The hold is a condition, not a delay: it
         * ends when the waiter is observed parked and fails the test when that never happens.
         */
        private CoalescedRun coalesceWithTermination(SessionRecord live,
                Function<TokenRefreshCoordinator, RefreshOutcome> call, RotationResult rotation) throws Exception {
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch exchangeEntered = new CountDownLatch(1);
            AtomicReference<Thread> waiterThread = new AtomicReference<>();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (presented, _) -> {
                calls.incrementAndGet();
                exchangeEntered.countDown();
                awaitParked(waiterThread);
                binding.destroy(live);
                return rotation;
            });
            FutureTask<RefreshOutcome> leader = new FutureTask<>(() -> call.apply(coordinator));
            FutureTask<RefreshOutcome> waiter = new FutureTask<>(() -> call.apply(coordinator));
            Thread leading = new Thread(leader, "refresh-leader");
            Thread waiting = new Thread(waiter, "refresh-waiter");
            leading.start();
            try {
                Awaits.connect(exchangeEntered, "the leader to enter the engine refresh");
                waiterThread.set(waiting);
                waiting.start();
                RefreshOutcome leaderOutcome = Awaits.connect(leader, "the leader refresh to complete");
                RefreshOutcome waiterOutcome = Awaits.connect(waiter, "the coalesced waiter to complete");
                return new CoalescedRun(leaderOutcome, waiterOutcome, calls.get());
            } finally {
                leading.interrupt();
                waiting.interrupt();
            }
        }

        /** Holds the calling thread until the thread in {@code parked} is parked, bounded by the teardown tier. */
        private static void awaitParked(AtomicReference<Thread> parked) {
            try {
                Awaits.until(() -> {
                    Thread candidate = parked.get();
                    return candidate != null && candidate.getState() == Thread.State.WAITING;
                }, "the coalesced waiter to park in the single-flight join", Awaits.TEARDOWN_CEILING_SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("the waiter never coalesced with the in-flight refresh", e);
            }
        }
    }

    @Nested
    @DisplayName("Single-flight coalescing")
    class SingleFlight {

        @Test
        @DisplayName("Should coalesce concurrent refreshes on one session into a single engine call")
        void shouldCoalesceConcurrentRefreshes() throws Exception {
            CoalescedRun run = coalesce(rotation -> rotation);

            assertEquals(1, run.calls(), "concurrent requests on one session share a single engine refresh");
            assertEquals(RefreshOutcome.Kind.REFRESHED, run.leader().kind());
            assertEquals(RefreshOutcome.Kind.REFRESHED, run.follower().kind(),
                    "the coalesced follower shares the successful refresh");
            assertEquals(ROTATED_ACCESS, resolveById(store, SESSION_ID, NOW).orElseThrow().accessToken());
        }

        @Test
        @DisplayName("Should hand the coalesced follower the leader's deferred outcome on a pre-redemption failure")
        void shouldShareDeferredOutcome() throws Exception {
            CoalescedRun run = coalesce(rotation -> {
                throw new TransportException("Connection reset by the token endpoint");
            });

            assertEquals(1, run.calls());
            assertEquals(RefreshOutcome.Kind.DEFERRED, run.leader().kind());
            assertEquals(RefreshOutcome.Kind.DEFERRED, run.follower().kind());
            assertTrue(sessionResolvable(NOW));
        }

        @Test
        @DisplayName("Should hand the coalesced follower the leader's failed outcome on a rejected credential")
        void shouldShareCredentialRejectedOutcome() throws Exception {
            CoalescedRun run = coalesce(rotation -> {
                throw credentialRejected();
            });

            assertEquals(1, run.calls());
            assertEquals(RefreshOutcome.Kind.FAILED, run.leader().kind());
            assertEquals(RefreshOutcome.Kind.FAILED, run.follower().kind());
            assertFalse(sessionResolvable(NOW));
        }

        @Test
        @DisplayName("Should hand the coalesced follower the leader's failed outcome on a refused redemption")
        void shouldShareRedeemedOutcome() throws Exception {
            CoalescedRun run = coalesce(rotation -> {
                throw new RedeemedScopeRefusalException("granted a broader scope than requested",
                        RefreshRedemption.rotated(ROTATED_REFRESH));
            });

            assertEquals(1, run.calls());
            assertEquals(RefreshOutcome.Kind.FAILED, run.leader().kind());
            assertEquals(RefreshOutcome.Kind.FAILED, run.follower().kind());
            assertEquals(List.of(ROTATED_REFRESH), revoked, "the successor is revoked exactly once");
        }

        /**
         * Runs one leader and one coalesced follower on the stored session. The leader enters the
         * exchange and blocks there until the follower has been submitted, then applies
         * {@code afterRelease} to the rotation — returning it, or throwing the failure under test.
         */
        private CoalescedRun coalesce(UnaryOperator<RotationResult> afterRelease) throws Exception {
            SessionRecord live = storedSession();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> {
                calls.incrementAndGet();
                entered.countDown();
                awaitRelease(proceed);
                return afterRelease.apply(rotation());
            });

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<RefreshOutcome> leader = pool.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                Awaits.connect(entered, "the leader entered the engine refresh");
                Future<RefreshOutcome> follower = pool.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                // Let the follower reach the in-flight join before the leader is released. There is no
                // observable hook for a thread reaching CompletableFuture#join, so this best-effort
                // ordering sleep cannot be made deterministic without an added dependency.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the follower reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = Awaits.connect(leader, "the leader refresh to complete");
                RefreshOutcome followerOutcome = Awaits.connect(follower,
                        "the coalesced follower refresh to complete");
                return new CoalescedRun(leaderOutcome, followerOutcome, calls.get());
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private record CoalescedRun(RefreshOutcome leader, RefreshOutcome follower, int calls) {
    }

    /**
     * The session-ended outcome is published before the best-effort revocation, and the revocation runs
     * off the request path. Every wait here is bounded, so against a coordinator that revokes inline the
     * tests fail on a timeout rather than hang.
     */
    @Nested
    @DisplayName("Revocation ordering — outcome first, revocation off the request path")
    class RevocationOrdering {

        private static final long OUTCOME_WAIT_SECONDS = 5;
        private static final long REVOCATION_BLOCK_SECONDS = 30;

        @Test
        @DisplayName("Should publish FAILED to the leader and a coalesced waiter while a refused redemption's revocation is still blocked")
        void shouldPublishRedeemedOutcomeBeforeRevocation() throws Exception {
            storedSession();

            BlockedRevocationRun run = runWithBlockedRevocation(binding, () -> {
                throw new RedeemedScopeRefusalException("granted a broader scope than requested",
                        RefreshRedemption.rotated(ROTATED_REFRESH));
            });

            assertBlockedRevocationRun(run);
        }

        @Test
        @DisplayName("Should publish FAILED to the leader and a coalesced waiter while a persist failure's revocation is still blocked")
        void shouldPublishPersistFailureOutcomeBeforeRevocation() throws Exception {
            storedSession();

            BlockedRevocationRun run = runWithBlockedRevocation(new PersistFailingBinding(binding), TokenRefreshCoordinatorTest::rotation);

            assertBlockedRevocationRun(run);
        }

        @Test
        @DisplayName("Should keep the FAILED disposition and record nothing above DEBUG when the dispatched revocation throws")
        void shouldSwallowFailingDispatchedRevocation() throws Exception {
            SessionRecord live = storedSession();
            CountDownLatch attempted = new CountDownLatch(1);
            RefreshTokenRevocation failingRevocation = token -> {
                attempted.countDown();
                throw new TransportException("Revocation endpoint returned unexpected HTTP status 500");
            };
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                        throwing(new RedeemedScopeRefusalException("granted a broader scope than requested",
                                RefreshRedemption.rotated(ROTATED_REFRESH))),
                        binding, failingRevocation, executor, EndedRefreshTokens.inert());

                RefreshOutcome outcome = coordinator.refresh(live, COOKIE_HEADER, NOW);
                boolean revocationAttempted = attempted.await(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                executor.shutdown();
                boolean drained = executor.awaitTermination(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);

                assertAll("a failing revocation is best-effort and invisible above DEBUG",
                        () -> assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind()),
                        () -> assertTrue(revocationAttempted, "the revocation was dispatched and attempted"),
                        () -> assertTrue(drained, "the dispatched revocation finished"),
                        () -> assertFalse(sessionResolvable(NOW)),
                        () -> assertTrue(TestLoggerFactory.getTestHandler().resolveLogMessages(TestLogLevel.ERROR)
                                .isEmpty(), "a failing revocation records no ERROR"),
                        () -> assertTrue(TestLoggerFactory.getTestHandler().resolveLogMessages(TestLogLevel.WARN)
                                        .stream().allMatch(entry -> String.valueOf(entry.getMessage()).contains(REFRESH_FAILED_ID)),
                                "the only WARN is the session end's own ApiSheriff-111"));
            }
        }

        @Test
        @DisplayName("Should skip a revocation at DEBUG once the in-flight bound is saturated, and dispatch again once a permit is released")
        void shouldSkipRevocationBeyondInFlightBound() {
            TestLogLevel.DEBUG.addLogger(TokenRefreshCoordinator.class);
            InMemorySessionStore wideStore = new InMemorySessionStore(
                    TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS + 8, SESSION_TTL, Integer.MAX_VALUE,
                    sessionId -> {
                    });
            SessionBinding wideBinding = new ServerSessionBinding(wideStore,
                    new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));
            List<Runnable> parked = new CopyOnWriteArrayList<>();
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR,
                    throwing(new RedeemedScopeRefusalException("granted a broader scope than requested",
                            RefreshRedemption.rotated(ROTATED_REFRESH))),
                    wideBinding, revoked::add, parked::add, EndedRefreshTokens.inert());

            for (int i = 0; i <= TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS; i++) {
                endSession(coordinator, wideStore, "ending-" + i);
            }
            int parkedAtBound = parked.size();
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.DEBUG, "revocation skipped");
            parked.forEach(Runnable::run);
            int revokedAfterDrain = revoked.size();
            endSession(coordinator, wideStore, "after-drain");

            assertAll("the fixed bound acts and its permits are returned",
                    () -> assertEquals(TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS, parkedAtBound,
                            "one ending beyond the bound is not dispatched"),
                    () -> assertEquals(TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS, revokedAfterDrain),
                    () -> assertEquals(TokenRefreshCoordinator.MAX_CONCURRENT_REVOCATIONS + 1, parked.size(),
                            "a finished revocation returns its permit, so the next ending is dispatched again"));
        }

        private void endSession(TokenRefreshCoordinator coordinator, InMemorySessionStore target, String sessionId) {
            SessionRecord live = SessionRecord.builder()
                    .sessionId(sessionId)
                    .accessToken("access-" + sessionId)
                    .refreshToken("refresh-" + sessionId)
                    .idToken("id-" + sessionId)
                    .sub("sub-" + sessionId)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .build();
            create(target, live, NOW);
            assertEquals(RefreshOutcome.Kind.FAILED,
                    coordinator.refresh(live, cookieHeaderFor(sessionId), NOW).kind());
        }

        /**
         * Runs one leader and one coalesced waiter on the stored session against {@code sessionBinding}.
         * The leader's exchange blocks until the waiter has been submitted, then yields {@code exchange};
         * the revocation that follows blocks until this method has observed both outcomes.
         */
        private BlockedRevocationRun runWithBlockedRevocation(SessionBinding sessionBinding,
                Supplier<RotationResult> exchange) throws Exception {
            CountDownLatch exchangeEntered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            CountDownLatch revocationEntered = new CountDownLatch(1);
            CountDownLatch releaseRevocation = new CountDownLatch(1);
            CountDownLatch revocationDone = new CountDownLatch(1);
            RefreshTokenRevocation blockingRevocation = token -> {
                revocationEntered.countDown();
                try {
                    if (releaseRevocation.await(REVOCATION_BLOCK_SECONDS, TimeUnit.SECONDS)) {
                        revoked.add(token);
                    }
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                } finally {
                    revocationDone.countDown();
                }
            };
            ExecutorService requests = Executors.newFixedThreadPool(2);
            ExecutorService revocations = Executors.newVirtualThreadPerTaskExecutor();
            try {
                SessionRecord live = session(CURRENT_REFRESH);
                TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, (rt, _) -> {
                    exchangeEntered.countDown();
                    awaitRelease(proceed);
                    return exchange.get();
                }, sessionBinding, blockingRevocation, revocations, EndedRefreshTokens.inert());

                Future<RefreshOutcome> leader = requests.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                Awaits.connect(exchangeEntered, "the leader entered the engine refresh");
                Future<RefreshOutcome> waiter = requests.submit(() -> coordinator.refresh(live, COOKIE_HEADER, NOW));
                // Best-effort ordering, as in SingleFlight: no observable hook for the waiter reaching the join.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the waiter reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = leader.get(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                RefreshOutcome waiterOutcome = waiter.get(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                boolean revocationStarted = revocationEntered.await(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                boolean stillBlocked = revocationDone.getCount() == 1;
                releaseRevocation.countDown();
                boolean revocationFinished = revocationDone.await(OUTCOME_WAIT_SECONDS, TimeUnit.SECONDS);
                return new BlockedRevocationRun(leaderOutcome, waiterOutcome, revocationStarted, stillBlocked,
                        revocationFinished);
            } finally {
                releaseRevocation.countDown();
                requests.shutdownNow();
                revocations.shutdownNow();
            }
        }

        private void assertBlockedRevocationRun(BlockedRevocationRun run) {
            assertAll("the outcome is published before the revocation, which runs off the request path",
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, run.leader(),
                            "the failing request returned FAILED while its revocation was blocked"),
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, run.waiter(),
                            "the coalesced waiter observed FAILED while the revocation was blocked"),
                    () -> assertTrue(run.revocationStarted(), "the revocation was dispatched"),
                    () -> assertTrue(run.stillBlockedWhenObserved(),
                            "both outcomes were observed while the revocation had not returned"),
                    () -> assertTrue(run.revocationFinished(), "the released revocation completed"),
                    () -> assertEquals(List.of(ROTATED_REFRESH), revoked,
                            "the revocation was invoked with the one live refresh token"),
                    () -> assertFalse(sessionResolvable(NOW), "the session was ended"));
        }
    }

    private record BlockedRevocationRun(RefreshOutcome.Kind leader, RefreshOutcome.Kind waiter,
    boolean revocationStarted, boolean stillBlockedWhenObserved, boolean revocationFinished) {

        BlockedRevocationRun(RefreshOutcome leader, RefreshOutcome waiter, boolean revocationStarted,
                boolean stillBlockedWhenObserved, boolean revocationFinished) {
            this(leader.kind(), waiter.kind(), revocationStarted, stillBlockedWhenObserved, revocationFinished);
        }
    }

    @Nested
    @DisplayName("Cookie-mode refresh (stateless binding)")
    class CookieMode {

        private static final String COOKIE_NAME = "__Host-sheriff-session";

        private CookieSessionBinding cookieBinding;
        private String sealedCookieHeader;
        private SessionRecord cookieSession;

        @BeforeEach
        void setUpCookieMode() {
            byte[] keyMaterial = new byte[32];
            Arrays.fill(keyMaterial, (byte) 0x11);
            SecretKey key = new SecretKeySpec(keyMaterial, "AES");
            byte[] salt = new byte[32];
            Arrays.fill(salt, (byte) 0x22);
            cookieBinding = new CookieSessionBinding(
                    new SealedSessionCookieCodec(COOKIE_NAME, SESSION_TTL,
                            SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, key, (byte) 1), salt, activityCodec(),
                    SESSION_TTL);

            SessionBinding.BoundSession bound = cookieBinding.bind(session(CURRENT_REFRESH), NOW);
            String setCookie = bound.setCookieHeaders().getFirst();
            sealedCookieHeader = setCookie.substring(0, setCookie.indexOf(';'));
            // The sealed cookie IS the session, so the record to refresh is the resolved one — its
            // derived sessionId is what single-flight keys on.
            cookieSession = cookieBinding.resolve(sealedCookieHeader, NOW).orElseThrow();
        }

        private TokenRefreshCoordinator cookieCoordinator(RefreshExchange exchange) {
            return cookieCoordinator(exchange, cookieBinding, EndedRefreshTokens.inert());
        }

        private TokenRefreshCoordinator cookieCoordinator(RefreshExchange exchange, SessionBinding sessionBinding,
                EndedRefreshTokens marker) {
            return new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, sessionBinding, revoked::add, DIRECT,
                    marker);
        }

        @Test
        @DisplayName("Should mark the presented refresh token ended on each of the three session-ending dispositions")
        void shouldMarkPresentedTokenOnEverySessionEnd() {
            EndedRefreshTokens credentialRejectedMarker = EndedRefreshTokens.bounded();
            EndedRefreshTokens redeemedMarker = EndedRefreshTokens.bounded();
            EndedRefreshTokens persistFailureMarker = EndedRefreshTokens.bounded();

            RefreshOutcome credentialRejected = cookieCoordinator(throwing(credentialRejected()), cookieBinding,
                    credentialRejectedMarker).refresh(cookieSession, sealedCookieHeader, NOW);
            RefreshOutcome redeemed = cookieCoordinator(throwing(new RedeemedScopeRefusalException(
                    "granted a broader scope than requested", RefreshRedemption.rotated(ROTATED_REFRESH))),
                    cookieBinding, redeemedMarker).refresh(cookieSession, sealedCookieHeader, NOW);
            RefreshOutcome persistFailure = cookieCoordinator((rt, _) -> rotation(), new PersistFailingBinding(cookieBinding),
                    persistFailureMarker).refresh(cookieSession, sealedCookieHeader, NOW);

            assertAll("every session-ending path marks the token the ended session presented",
                    () -> assertTrue(credentialRejected.isFailure()),
                    () -> assertTrue(credentialRejectedMarker.isEnded(CURRENT_REFRESH, NOW), "credential-rejected"),
                    () -> assertTrue(redeemed.isFailure()),
                    () -> assertTrue(redeemedMarker.isEnded(CURRENT_REFRESH, NOW), "redeemed-response-refused"),
                    () -> assertTrue(persistFailure.isFailure()),
                    () -> assertTrue(persistFailureMarker.isEnded(CURRENT_REFRESH, NOW), "persist-failure"),
                    () -> assertFalse(redeemedMarker.isEnded(ROTATED_REFRESH, NOW),
                            "only the presented token is marked, never the revoked successor"));
        }

        @Test
        @DisplayName("Should refuse a replayed ended cookie locally with no engine call, no revocation and no ApiSheriff-111")
        void shouldRefuseReplayedEndedTokenLocally() {
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = cookieCoordinator((presented, _) -> {
                calls.incrementAndGet();
                throw new RedeemedScopeRefusalException("granted a broader scope than requested",
                        RefreshRedemption.rotated(ROTATED_REFRESH));
            }, cookieBinding, EndedRefreshTokens.bounded());
            RefreshOutcome ended = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);
            int revokedAfterEnd = revoked.size();
            TestLoggerFactory.getTestHandler().clearRecords();

            RefreshOutcome replayed = coordinator.refresh(cookieSession, sealedCookieHeader, NOW.plusSeconds(1));

            assertAll("the replay takes the session-ended disposition without reaching the identity provider",
                    () -> assertTrue(ended.isFailure()),
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, replayed.kind(),
                            "the stage still clears the cookie and negotiates on_failure"),
                    () -> assertEquals(1, calls.get(), "the replayed ended token makes no engine exchange"),
                    () -> assertEquals(1, revokedAfterEnd, "the original session end revoked its successor once"),
                    () -> assertEquals(revokedAfterEnd, revoked.size(), "the replay revokes nothing"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should let a successor cookie of the same session reach the identity provider once, then mark it")
        void shouldNotRefuseSuccessorWithDifferentRefreshToken() {
            AtomicInteger calls = new AtomicInteger();
            EndedRefreshTokens marker = EndedRefreshTokens.bounded();
            TokenRefreshCoordinator coordinator = cookieCoordinator((presented, _) -> {
                if (calls.incrementAndGet() == 1) {
                    return rotation();
                }
                throw credentialRejected();
            }, cookieBinding, marker);
            RefreshOutcome rotated = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);
            String successorSetCookie = rotated.setCookieHeaders().getFirst();
            String successorCookieHeader = successorSetCookie.substring(0, successorSetCookie.indexOf(';'));
            SessionRecord successor = cookieBinding.resolve(successorCookieHeader, NOW).orElseThrow();
            RefreshOutcome replayOfOriginal = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);
            int callsBeforeSuccessor = calls.get();

            RefreshOutcome successorOutcome = coordinator.refresh(successor, successorCookieHeader, NOW);

            assertAll("the marker is keyed on the token generation, not the shared session identity",
                    () -> assertEquals(cookieSession.sessionId(), successor.sessionId(),
                            "precondition: both cookies derive the same session identity"),
                    () -> assertTrue(replayOfOriginal.isFailure(), "the IdP refused the replayed original"),
                    () -> assertTrue(marker.isEnded(CURRENT_REFRESH, NOW)),
                    () -> assertEquals(callsBeforeSuccessor + 1, calls.get(),
                            "the successor's different refresh token is not marked and reaches the exchange"),
                    () -> assertTrue(successorOutcome.isFailure(), "the IdP refuses the successor too"),
                    () -> assertTrue(marker.isEnded(ROTATED_REFRESH, NOW), "and the successor is then marked itself"));
        }

        @Test
        @DisplayName("Should let a replay reach the exchange as before when the inert server-mode marker is bound")
        void shouldReachExchangeWithInertMarker() {
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = cookieCoordinator((presented, _) -> {
                calls.incrementAndGet();
                throw credentialRejected();
            });

            coordinator.refresh(cookieSession, sealedCookieHeader, NOW);
            RefreshOutcome replayed = coordinator.refresh(cookieSession, sealedCookieHeader, NOW.plusSeconds(1));

            assertTrue(replayed.isFailure());
            assertEquals(2, calls.get(), "the inert marker refuses nothing locally");
        }

        @Test
        @DisplayName("Should re-seal the rotated material into a new Set-Cookie rather than a store write")
        void shouldResealIntoANewCookie() {
            TokenRefreshCoordinator coordinator = cookieCoordinator((rt, _) -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            assertEquals(ROTATED_ACCESS, rotatedSession(outcome).accessToken());
            assertEquals(1, outcome.setCookieHeaders().size(),
                    "a stateless refresh persists by emitting exactly one re-sealed cookie");
            String reSealed = outcome.setCookieHeaders().getFirst();
            assertTrue(reSealed.startsWith(COOKIE_NAME + "="), reSealed);
            assertFalse(reSealed.contains(ROTATED_ACCESS), "the rotated token is sealed, never emitted in the clear");
            assertFalse(reSealed.contains(ROTATED_REFRESH), "the rotated refresh token is sealed, never emitted");
        }

        @Test
        @DisplayName("Should carry the rotated material in the re-sealed cookie the next request presents")
        void shouldServeTheRotatedMaterialFromTheReSealedCookie() {
            TokenRefreshCoordinator coordinator = cookieCoordinator((rt, _) -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            String reSealed = outcome.setCookieHeaders().getFirst();
            String nextRequestCookie = reSealed.substring(0, reSealed.indexOf(';'));
            SessionRecord nextRequest = cookieBinding.resolve(nextRequestCookie, NOW).orElseThrow();
            assertEquals(ROTATED_ACCESS, nextRequest.accessToken());
            assertEquals(ROTATED_REFRESH, nextRequest.refreshToken());
            assertEquals(cookieSession.expiresAt(), nextRequest.expiresAt(),
                    "the re-seal preserves the original absolute deadline — a refresh never extends the session");
        }

        @Test
        @DisplayName("Should propagate the session nonce from the previous record onto the rotated one")
        void shouldPropagateSessionNonceAcrossRotate() {
            // rotate() rebuilds the record component-by-component, so an uncopied component is
            // silently dropped. Dropping the nonce would make persist() refuse the re-seal outright —
            // this asserts the copy directly rather than inferring it from identity stability.
            assertNotNull(cookieSession.sessionNonce(),
                    "a resolved cookie-mode record always carries the nonce sealed at login");
            TokenRefreshCoordinator coordinator = cookieCoordinator((rt, _) -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(cookieSession.sessionNonce(), rotatedSession(outcome).sessionNonce(),
                    "the rotated record carries the previous record's nonce verbatim — never a fresh one");
        }

        @Test
        @DisplayName("Should leave a server-mode record's session nonce absent")
        void shouldLeaveServerModeNonceAbsent() {
            SessionRecord serverModeRecord = session(CURRENT_REFRESH);

            assertNull(serverModeRecord.sessionNonce(),
                    "the nonce is cookie-mode-only; server mode's minted opaque id is already unique");
        }

        @Test
        @DisplayName("Should keep the derived identity stable across the re-seal, so single-flight keys the same")
        void shouldKeepTheSingleFlightKeyStable() {
            TokenRefreshCoordinator coordinator = cookieCoordinator((rt, _) -> rotation());

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(cookieSession.sessionId(), rotatedSession(outcome).sessionId(),
                    "the single-flight key expression is unchanged in cookie mode");
        }

        @Test
        @DisplayName("Should coalesce two concurrent cookie-mode refreshes into exactly one engine exchange")
        void shouldCoalesceConcurrentCookieRefreshes() throws Exception {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = cookieCoordinator((rt, _) -> {
                calls.incrementAndGet();
                entered.countDown();
                awaitRelease(proceed);
                return rotation();
            });

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<RefreshOutcome> leader =
                        pool.submit(() -> coordinator.refresh(cookieSession, sealedCookieHeader, NOW));
                Awaits.connect(entered, "the leader entered the engine refresh");
                Future<RefreshOutcome> follower =
                        pool.submit(() -> coordinator.refresh(cookieSession, sealedCookieHeader, NOW));
                // Best-effort ordering: there is no observable hook for a thread reaching
                // CompletableFuture#join, so the follower's arrival cannot be awaited deterministically.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the follower reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = Awaits.connect(leader, "the leader refresh to complete");
                RefreshOutcome followerOutcome = Awaits.connect(follower,
                        "the coalesced follower refresh to complete");

                assertEquals(1, calls.get(),
                        "two threads on the same cookie produce exactly one engine exchange — single-flight is "
                                + "per instance in cookie mode, which is the documented accepted trade-off");
                assertFalse(leaderOutcome.isFailure());
                assertFalse(followerOutcome.isFailure(), "the coalesced follower shares the successful refresh");
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("Should fail when the identity provider rejects the cookie-mode refresh token")
        void shouldFailOnCredentialRejection() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(throwing(credentialRejected()));

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertTrue(outcome.isFailure(),
                    "a rejected credential ends the session in cookie mode too — the stage clears the cookie");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "a failed refresh emits no re-seal");
        }

        @Test
        @DisplayName("Should keep the sealed cookie and mediate it on a cookie-mode pre-redemption failure")
        void shouldDeferOnPreRedemptionFailure() {
            TokenRefreshCoordinator coordinator = cookieCoordinator(
                    throwing(new TransportException("Token endpoint returned HTTP 502")));

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertEquals(RefreshOutcome.Kind.DEFERRED, outcome.kind());
            assertEquals(cookieSession, outcome.session(), "the still-valid sealed session is mediated as it is");
            assertTrue(outcome.setCookieHeaders().isEmpty(), "nothing was re-sealed, so the browser keeps its cookie");
        }

        /**
         * The stateless limit, pinned rather than hidden. {@code destroy} removes nothing in cookie mode,
         * so a logout that lands while the refresh is in flight is not observable at the persist: the
         * rotated session is re-sealed exactly as before. Server mode refuses the same interleaving (see
         * {@link TerminatedDuringRefresh}).
         */
        @Test
        @DisplayName("Should still re-seal the rotated session when the session is destroyed during the exchange")
        void shouldStillResealWhenDestroyedDuringTheExchange() {
            TokenRefreshCoordinator coordinator = cookieCoordinator((rt, _) -> {
                cookieBinding.destroy(cookieSession);
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refresh(cookieSession, sealedCookieHeader, NOW);

            assertAll("the stateless update never reports the session gone",
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind()),
                    () -> assertEquals(1, outcome.setCookieHeaders().size(), "the rotated session is re-sealed"),
                    () -> assertTrue(revoked.isEmpty(), "no session ended, so nothing is revoked"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        private static SessionRecord rotatedSession(RefreshOutcome outcome) {
            SessionRecord rotated = outcome.session();
            assertNotNull(rotated, "a refreshed outcome carries the rotated session");
            return rotated;
        }
    }

    /**
     * The scope-driven leg: a session whose active scope set {@code A} lacks a needed scope inside the
     * granted set {@code S} asks for exactly the requested set, whatever its access token's remaining
     * lifetime. It shares the near-expiry leg's single-flight exclusion and refusal dispositions, and it
     * reports a grant that is still short of the set as {@code SCOPE_REFUSED} without arming the back-off.
     * Such a processed, narrower grant also takes every requested scope it did not return out of the
     * granted set {@code S}.
     * An outright {@code invalid_scope} refusal reaches the coordinator as the bare transport failure
     * {@code token-sheriff-client} raises for it, so it takes the pre-redemption back-off
     * (TokenSheriff#763).
     */
    @Nested
    @DisplayName("Scope-driven refresh leg")
    class ScopeLeg {

        private static final String MISSING = "orders:write";
        /** {@code A ∪ missing} — the set a session route asks the leg for. */
        private static final Set<String> REQUESTED = Set.of("openid", "profile", "email", "orders:read", MISSING);
        private static final String REQUESTED_SCOPE = "openid profile email orders:read orders:write";
        private static final String SHORT_SCOPE = "openid profile email orders:read";

        @Test
        @DisplayName("Should make exactly one exchange requesting A united with the missing scope, carry S over and persist")
        void shouldRefreshForRequestedScopes() {
            SessionRecord live = storedSession();
            List<Set<String>> requested = new CopyOnWriteArrayList<>();
            TokenRefreshCoordinator coordinator = coordinator(NOT_NEAR, (_, scopes) -> {
                requested.add(scopes);
                return rotation(REQUESTED_SCOPE, RotationResult.ScopeDelta.EQUAL);
            });

            RefreshOutcome outcome = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);

            SessionRecord persisted = resolveById(store, SESSION_ID, NOW).orElseThrow();
            assertAll("the leg refreshes regardless of the access token's remaining lifetime",
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind()),
                    () -> assertEquals(List.of(REQUESTED), requested,
                            "exactly one exchange sends the requested set verbatim as the grant's scope"),
                    () -> assertEquals(REQUESTED, persisted.activeScopes(), "the persisted A carries the obtained scope"),
                    () -> assertEquals(GRANTED_SCOPES, persisted.grantedScopes(), "S is carried over unchanged"),
                    () -> assertEquals(ROTATED_ACCESS, persisted.accessToken(),
                            "the rotated token material is persisted"),
                    () -> assertTrue(revoked.isEmpty(), "a successful scope refresh revokes nothing"));
        }

        @Test
        @DisplayName("Should take the requested set as the new A when the response omits scope")
        void shouldTakeRequestedSetWhenScopeOmitted() {
            SessionRecord live = storedSession();

            RefreshOutcome outcome = coordinator(NOT_NEAR, (_, _) -> rotation())
                    .refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);

            assertEquals(RefreshOutcome.Kind.REFRESHED, outcome.kind());
            assertEquals(REQUESTED, carried(outcome).activeScopes(),
                    "an omitted scope is identical to the one requested (RFC 6749 §5.1)");
            assertEquals(GRANTED_SCOPES, carried(outcome).grantedScopes(),
                    "an omitted scope refuses nothing, so S is unchanged");
        }

        @Test
        @DisplayName("Should take exactly the refused scopes out of S, leaving a granted scope the grant was not asked for")
        void shouldDropOnlyRefusedScopesFromGrantedSet() {
            String unrequested = "billing:read";
            SessionRecord live = SessionRecord.builder()
                    .sessionId(SESSION_ID)
                    .accessToken("access-current")
                    .refreshToken(CURRENT_REFRESH)
                    .idToken("id-current")
                    .sub("sub-1")
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .activeScopes(ACTIVE_SCOPES)
                    .grantedScopes(Set.of("openid", "profile", "email", "orders:read", MISSING, unrequested))
                    .build();
            create(store, live, NOW);
            // The response returns neither the missing scope nor 'orders:read', which was active before.
            TokenRefreshCoordinator coordinator = coordinator(NOT_NEAR,
                    (_, _) -> rotation("openid profile email", RotationResult.ScopeDelta.NARROWED));

            RefreshOutcome refused = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);

            SessionRecord persisted = resolveById(store, SESSION_ID, NOW).orElseThrow();
            assertAll("S records what the identity provider was just shown to grant",
                    () -> assertEquals(RefreshOutcome.Kind.SCOPE_REFUSED, refused.kind()),
                    () -> assertEquals(Set.of("openid", "profile", "email"), persisted.activeScopes(),
                            "A is the response's scope"),
                    () -> assertEquals(Set.of("openid", "profile", "email", unrequested), persisted.grantedScopes(),
                            "both requested scopes the grant did not return left S; the unrequested one stayed"));
        }

        @Test
        @DisplayName("Should re-seal the reduced S into the cookie a narrower scope grant re-binds, in cookie mode")
        void shouldResealReducedGrantedSetInCookieMode() {
            byte[] keyMaterial = new byte[32];
            Arrays.fill(keyMaterial, (byte) 0x11);
            SecretKey key = new SecretKeySpec(keyMaterial, "AES");
            byte[] salt = new byte[32];
            Arrays.fill(salt, (byte) 0x22);
            CookieSessionBinding cookieBinding = new CookieSessionBinding(new SealedSessionCookieCodec(
                            SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL,
                            SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET, key, (byte) 1), salt, activityCodec(),
                    SESSION_TTL);
            String setCookie = cookieBinding.bind(session(CURRENT_REFRESH), NOW).setCookieHeaders().getFirst();
            String cookieHeader = setCookie.substring(0, setCookie.indexOf(';'));
            SessionRecord live = cookieBinding.resolve(cookieHeader, NOW).orElseThrow();
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY, unused -> NOT_NEAR,
                    (_, _) -> rotation(SHORT_SCOPE, RotationResult.ScopeDelta.NARROWED), cookieBinding, revoked::add,
                    DIRECT, EndedRefreshTokens.bounded());

            RefreshOutcome outcome = coordinator.refreshForScopes(live, cookieHeader, REQUESTED, NOW);

            assertEquals(RefreshOutcome.Kind.SCOPE_REFUSED, outcome.kind());
            String reSealed = outcome.setCookieHeaders().getFirst();
            SessionRecord reResolved = cookieBinding.resolve(reSealed.substring(0, reSealed.indexOf(';')), NOW)
                    .orElseThrow();
            assertEquals(ACTIVE_SCOPES, reResolved.grantedScopes(),
                    "the next request presents a cookie whose S no longer holds the refused scope");
            assertEquals(live.sessionId(), reResolved.sessionId(), "S is no identity input: the same session");
        }

        @Test
        @DisplayName("Should return SCOPE_REFUSED carrying the persisted session when the grant still lacks a requested scope, arming no back-off")
        void shouldRefuseShortGrantWithoutBackOff() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (_, _) -> {
                calls.incrementAndGet();
                return rotation(SHORT_SCOPE, RotationResult.ScopeDelta.NARROWED);
            });

            RefreshOutcome refused = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);
            SessionRecord persisted = resolveById(store, SESSION_ID, NOW).orElseThrow();
            RefreshOutcome followUp = coordinator.refresh(persisted, COOKIE_HEADER, NOW);

            assertAll("a narrower grant keeps the rotated session and leaves the next refresh unthrottled",
                    () -> assertEquals(RefreshOutcome.Kind.SCOPE_REFUSED, refused.kind()),
                    () -> assertEquals(ROTATED_ACCESS, carried(refused).accessToken(),
                            "the refused outcome carries the rotated session"),
                    () -> assertFalse(carried(refused).activeScopes().contains(MISSING),
                            "the carried session is still short of the requested scope"),
                    () -> assertEquals(ROTATED_ACCESS, persisted.accessToken(),
                            "the redeemed grant's session is persisted, not dropped"),
                    () -> assertEquals(ACTIVE_SCOPES, persisted.grantedScopes(),
                            "S lost exactly the requested scope the grant did not return"),
                    () -> assertEquals(ACTIVE_SCOPES, carried(refused).grantedScopes(),
                            "the carried session has the same truthful S, so the caller widens from it"),
                    () -> assertFalse(refused.isFailure(), "the session is kept"),
                    () -> assertFalse(refused.requestFailed(), "the kept session still has a token"),
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, followUp.kind(),
                            "no back-off was armed: a near-expiry refresh at the same instant is admitted"),
                    () -> assertEquals(2, calls.get(), "the follow-up refresh reached the engine"));
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should take the pre-redemption back-off on an invalid_scope refusal, which the engine surfaces as a bare transport failure")
        void shouldBackOffOnInvalidScopeRefusal() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (_, _) -> {
                calls.incrementAndGet();
                // What token-sheriff-client raises for an OAuth invalid_scope answer: the error code is
                // not carried, so the refusal is indistinguishable from any other 4xx (TokenSheriff#763).
                throw new TransportException("Token endpoint returned unexpected HTTP status 400");
            });

            RefreshOutcome refused = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);
            RefreshOutcome insideWindow = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED,
                    NOW.plusSeconds(2));

            assertAll("an unclassifiable scope refusal keeps the session and backs off like any pre-redemption failure",
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, refused.kind()),
                    () -> assertEquals(live, refused.session(), "the unchanged session, still short of the scope"),
                    () -> assertEquals(GRANTED_SCOPES, resolveById(store, SESSION_ID, NOW).orElseThrow().grantedScopes(),
                            "a grant the provider never processed proves nothing about the scope, so S is unchanged"),
                    () -> assertTrue(sessionResolvable(NOW), "the session is kept"),
                    () -> assertTrue(revoked.isEmpty(), "nothing was redeemed"),
                    () -> assertEquals(RefreshOutcome.Kind.DEFERRED, insideWindow.kind()),
                    () -> assertEquals(1, calls.get(), "the back-off suppresses the second scope refresh"));
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, REFRESH_DEFERRED_ID);
            LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, REFRESH_FAILED_ID);
        }

        @Test
        @DisplayName("Should return the session unchanged with no engine call when it already carries the requested set")
        void shouldReturnCurrentWhenAlreadyCovered() {
            SessionRecord live = storedSession();
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (_, _) -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refreshForScopes(live, COOKIE_HEADER, ACTIVE_SCOPES, NOW);

            assertEquals(RefreshOutcome.Kind.CURRENT, outcome.kind());
            assertSame(live, outcome.session());
            assertEquals(0, calls.get(), "a satisfied set makes no engine call");
        }

        @Test
        @DisplayName("Should make no engine call when the leader's re-resolved session already carries the requested set")
        void shouldShareCoveringReResolvedSession() {
            SessionRecord stale = session(CURRENT_REFRESH);
            SessionRecord covering = SessionRecord.builder()
                    .sessionId(SESSION_ID)
                    .accessToken("access-covering")
                    .refreshToken("refresh-covering")
                    .idToken("id-covering")
                    .sub("sub-1")
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .activeScopes(REQUESTED)
                    .grantedScopes(GRANTED_SCOPES)
                    .build();
            create(store, covering, NOW);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (_, _) -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refreshForScopes(stale, COOKIE_HEADER, REQUESTED, NOW);

            assertAll("a concurrent refresh already obtained the set",
                    () -> assertEquals(RefreshOutcome.Kind.CURRENT, outcome.kind()),
                    () -> assertEquals("access-covering", carried(outcome).accessToken(),
                            "the re-resolved session is shared, not the caller's stale one"),
                    () -> assertEquals(0, calls.get(), "the re-check under exclusion makes no engine call"));
        }

        @Test
        @DisplayName("Should return SCOPE_REFUSED with no engine call when the session carries no refresh token")
        void shouldRefuseWithoutRefreshToken() {
            SessionRecord live = session(null);
            create(store, live, NOW);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (_, _) -> {
                calls.incrementAndGet();
                return rotation();
            });

            RefreshOutcome outcome = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);

            assertEquals(RefreshOutcome.Kind.SCOPE_REFUSED, outcome.kind());
            assertSame(live, outcome.session(), "the kept session is handed on to widening");
            assertEquals(0, calls.get(), "nothing can be presented to the engine");
        }

        @Test
        @DisplayName("Should end the session when the identity provider rejects the credential on the scope leg")
        void shouldFailAndDestroyOnCredentialRejection() {
            SessionRecord live = storedSession();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, throwing(credentialRejected()));

            RefreshOutcome outcome = coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);

            assertAll("CREDENTIAL_REJECTED keeps its ADR-0046 disposition on the scope leg",
                    () -> assertEquals(RefreshOutcome.Kind.FAILED, outcome.kind()),
                    () -> assertNull(outcome.session()),
                    () -> assertFalse(sessionResolvable(NOW), "the session is destroyed"),
                    () -> assertTrue(revoked.isEmpty(), "nothing was redeemed, so nothing is revoked"));
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    TokenRefreshCoordinator.REASON_CREDENTIAL_REJECTED);
        }

        @Test
        @DisplayName("Should hand a coalesced scope request the leader's scope refresh with one engine call")
        void shouldCoalesceConcurrentScopeRefreshes() throws Exception {
            CoalescedRun run = coalesce(true, rotation(REQUESTED_SCOPE, RotationResult.ScopeDelta.EQUAL));

            assertAll("two scope requests on one session share one exchange",
                    () -> assertEquals(1, run.calls()),
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, run.leader().kind()),
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, run.follower().kind()),
                    () -> assertTrue(carried(run.follower()).activeScopes().containsAll(REQUESTED),
                            "the shared session carries the requested set"));
        }

        @Test
        @DisplayName("Should hand a scope request that coalesced with a near-expiry refresh SCOPE_REFUSED rather than a second exchange")
        void shouldRefuseScopeRequestSharingNearExpiryRefresh() throws Exception {
            CoalescedRun run = coalesce(false, rotation());

            assertAll("the shared near-expiry refresh requested A, which still lacks the missing scope",
                    () -> assertEquals(1, run.calls(), "the scope request never presents the rotated-away token"),
                    () -> assertEquals(RefreshOutcome.Kind.REFRESHED, run.leader().kind()),
                    () -> assertEquals(RefreshOutcome.Kind.SCOPE_REFUSED, run.follower().kind()),
                    () -> assertEquals(ROTATED_ACCESS, carried(run.follower()).accessToken(),
                            "the scope request carries the shared rotated session on to widening"));
        }

        /**
         * Runs a leader — a scope refresh or a near-expiry refresh — and a coalesced scope-refresh follower
         * on the stored session. The leader enters the exchange and blocks there until the follower has been
         * submitted, then returns {@code rotation}.
         */
        private CoalescedRun coalesce(boolean scopeLeader, RotationResult rotation) throws Exception {
            SessionRecord live = storedSession();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch proceed = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (_, _) -> {
                calls.incrementAndGet();
                entered.countDown();
                awaitRelease(proceed);
                return rotation;
            });
            Callable<RefreshOutcome> scopeRefresh =
                    () -> coordinator.refreshForScopes(live, COOKIE_HEADER, REQUESTED, NOW);
            Callable<RefreshOutcome> leaderCall = scopeLeader ? scopeRefresh
                    : () -> coordinator.refresh(live, COOKIE_HEADER, NOW);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<RefreshOutcome> leader = pool.submit(leaderCall);
                Awaits.connect(entered, "the leader entered the engine refresh");
                Future<RefreshOutcome> follower = pool.submit(scopeRefresh);
                // Best-effort ordering, as in SingleFlight: no observable hook for the follower reaching the join.
                Thread.sleep(100); // NOSONAR java:S2925 - no observable hook for the follower reaching the in-flight join
                proceed.countDown();

                RefreshOutcome leaderOutcome = Awaits.connect(leader, "the leader refresh to complete");
                RefreshOutcome followerOutcome = Awaits.connect(follower,
                        "the coalesced scope refresh to complete");
                return new CoalescedRun(leaderOutcome, followerOutcome, calls.get());
            } finally {
                pool.shutdownNow();
            }
        }

        private static SessionRecord carried(RefreshOutcome outcome) {
            SessionRecord carried = outcome.session();
            assertNotNull(carried, "a session-carrying outcome carries the session");
            return carried;
        }
    }

    @Nested
    @DisplayName("Argument and outcome contracts")
    class Contracts {

        @Test
        @DisplayName("Should reject a null session and a null instant")
        void shouldRejectNullArguments() {
            TokenRefreshCoordinator coordinator = coordinator(NEAR, (rt, _) -> rotation());

            var session = session(CURRENT_REFRESH);
            assertThrows(NullPointerException.class, () -> coordinator.refresh(null, COOKIE_HEADER, NOW));
            assertThrows(NullPointerException.class, () -> coordinator.refresh(session, COOKIE_HEADER, null));
            assertThrows(NullPointerException.class,
                    () -> coordinator.refreshForScopes(null, COOKIE_HEADER, ACTIVE_SCOPES, NOW));
            assertThrows(NullPointerException.class,
                    () -> coordinator.refreshForScopes(session, COOKIE_HEADER, null, NOW));
            assertThrows(NullPointerException.class,
                    () -> coordinator.refreshForScopes(session, COOKIE_HEADER, ACTIVE_SCOPES, null));
        }

        @Test
        @DisplayName("Should reject a missing revocation seam, revocation executor or ended-token marker")
        void shouldRejectMissingRevocationSeam() {
            RefreshExchange exchange = (rt, _) -> rotation();
            RefreshTokenRevocation revocation = revoked::add;
            EndedRefreshTokens marker = EndedRefreshTokens.inert();

            assertThrows(NullPointerException.class,
                    () -> new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, binding, null, DIRECT, marker));
            assertThrows(NullPointerException.class,
                    () -> new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, binding, revocation, null, marker));
            assertThrows(NullPointerException.class,
                    () -> new TokenRefreshCoordinator(LEEWAY, unused -> NEAR, exchange, binding, revocation, DIRECT, null));
        }

        @Test
        @DisplayName("Should reject a session-carrying kind constructed without a session")
        void shouldRejectPresentContractViolation() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.CURRENT, null, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.DEFERRED, null, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.SCOPE_REFUSED, null, List.of()));
        }

        @Test
        @DisplayName("Should reject a session-less kind constructed with a session")
        void shouldRejectAbsentContractViolation() {
            SessionRecord live = session(CURRENT_REFRESH);

            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.UNAVAILABLE, live, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.FAILED, live, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new RefreshOutcome(RefreshOutcome.Kind.NO_SESSION, live, List.of()));
        }

        @Test
        @DisplayName("Should tell a no-session outcome apart from a failed and from an unavailable one")
        void noSessionOutcomeIsNeitherASessionEndNorAKeptSession() {
            RefreshOutcome noSession = RefreshOutcome.noSession();

            assertEquals(RefreshOutcome.Kind.NO_SESSION, noSession.kind());
            assertFalse(noSession.isFailure(), "nothing was destroyed");
            assertFalse(noSession.requestFailed(), "and no session is known to be kept");
            assertNull(noSession.session());
            assertTrue(noSession.setCookieHeaders().isEmpty());
        }

        @Test
        @DisplayName("Should expose an empty session and no cookies on a failed outcome")
        void failedOutcomeCarriesNoSession() {
            RefreshOutcome failed = RefreshOutcome.failed();

            assertTrue(failed.isFailure());
            assertFalse(failed.requestFailed());
            assertNull(failed.session());
            assertTrue(failed.setCookieHeaders().isEmpty());
        }

        @Test
        @DisplayName("Should tell an unavailable outcome apart from a failed one")
        void unavailableOutcomeIsNotASessionEnd() {
            RefreshOutcome unavailable = RefreshOutcome.unavailable();

            assertTrue(unavailable.requestFailed());
            assertFalse(unavailable.isFailure());
            assertNull(unavailable.session());
        }
    }

    /**
     * A binding that behaves like the wrapped one except that updating a rotated session fails, the
     * way {@link CookieSessionBinding#persist} does when the sealed cookie exceeds its size budget. It
     * throws rather than reporting the session gone: the session is still there, the binding just
     * cannot hold its updated form.
     */
    private static final class PersistFailingBinding implements SessionBinding {

        private final SessionBinding delegate;

        PersistFailingBinding(SessionBinding delegate) {
            this.delegate = delegate;
        }

        @Override
        public BoundSession bind(SessionRecord session, Instant now) {
            return delegate.bind(session, now);
        }

        @Override
        public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
            return delegate.resolve(cookieHeader, now);
        }

        @Override
        public Optional<BoundSession> persist(SessionRecord updated, Instant now) {
            throw new IllegalStateException("sealed session cookie exceeds the size budget");
        }

        @Override
        public void destroy(SessionRecord session) {
            delegate.destroy(session);
        }

        @Override
        public int destroyBySid(String sid) {
            return delegate.destroyBySid(sid);
        }

        @Override
        public int destroyBySub(String sub) {
            return delegate.destroyBySub(sub);
        }

        @Override
        public IdpDestruction idpDestruction() {
            return delegate.idpDestruction();
        }

        @Override
        public Optional<BoundSession> persistReissuingCookie(SessionRecord updated, Instant now) {
            throw new IllegalStateException("sealed session cookie exceeds the size budget");
        }

        @Override
        public List<String> recordAccess(SessionRecord session, @Nullable String cookieHeader, Instant now) {
            return delegate.recordAccess(session, cookieHeader, now);
        }

        @Override
        public List<String> clearingSetCookieHeaders() {
            return delegate.clearingSetCookieHeaders();
        }
    }

    /**
     * A binding that behaves exactly like the wrapped one, except that it runs {@code beforeResolve} with
     * the request's {@code Cookie} header on the calling thread before every resolve. The hook is how a
     * test holds a refresh leader at its second resolve without a sleep.
     */
    private static final class ResolveHookBinding implements SessionBinding {

        private final SessionBinding delegate;
        private final Consumer<@Nullable String> beforeResolve;

        ResolveHookBinding(SessionBinding delegate, Consumer<@Nullable String> beforeResolve) {
            this.delegate = delegate;
            this.beforeResolve = beforeResolve;
        }

        @Override
        public BoundSession bind(SessionRecord session, Instant now) {
            return delegate.bind(session, now);
        }

        @Override
        public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
            beforeResolve.accept(cookieHeader);
            return delegate.resolve(cookieHeader, now);
        }

        @Override
        public Optional<BoundSession> persist(SessionRecord updated, Instant now) {
            return delegate.persist(updated, now);
        }

        @Override
        public Optional<BoundSession> persistReissuingCookie(SessionRecord updated, Instant now) {
            return delegate.persistReissuingCookie(updated, now);
        }

        @Override
        public List<String> recordAccess(SessionRecord session, @Nullable String cookieHeader, Instant now) {
            return delegate.recordAccess(session, cookieHeader, now);
        }

        @Override
        public void destroy(SessionRecord session) {
            delegate.destroy(session);
        }

        @Override
        public int destroyBySid(String sid) {
            return delegate.destroyBySid(sid);
        }

        @Override
        public int destroyBySub(String sub) {
            return delegate.destroyBySub(sub);
        }

        @Override
        public IdpDestruction idpDestruction() {
            return delegate.idpDestruction();
        }

        @Override
        public List<String> clearingSetCookieHeaders() {
            return delegate.clearingSetCookieHeaders();
        }
    }
}
