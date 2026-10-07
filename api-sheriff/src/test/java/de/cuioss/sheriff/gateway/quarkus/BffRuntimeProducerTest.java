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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.cuioss.sheriff.gateway.auth.JwksTrustProfileResolver;
import de.cuioss.sheriff.gateway.auth.SanMismatchedJwksServer;
import de.cuioss.sheriff.gateway.auth.SignatureOnlyTokenVerifier;
import de.cuioss.sheriff.gateway.auth.TestTlsConfigurationRegistry;
import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.client.TestSigningKeys;
import de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionPayload;
import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.login.BoundTokenEndpointClient;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.PushedAuthorizationRequests;
import de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder;
import de.cuioss.sheriff.gateway.bff.login.ReturnTargetScopes;
import de.cuioss.sheriff.gateway.bff.login.ScopedEngineFlows;
import de.cuioss.sheriff.gateway.bff.login.SessionWidening;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.refresh.EndedRefreshTokens;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.reserved.StepUpEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.bff.session.SessionRelayRegistry;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EgressTlsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.sheriff.gateway.testsupport.StubIdentityProvider;
import de.cuioss.sheriff.token.client.auth.ClientAuthentication;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.DiscoveryResolver;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.dpop.DpopProofGenerator;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.client.flow.CallbackParameters;
import de.cuioss.sheriff.token.client.flow.CredentialRejectedException;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.sheriff.token.client.flow.ParClient;
import de.cuioss.sheriff.token.client.flow.RefreshFailureClassification;
import de.cuioss.sheriff.token.client.flow.RefreshFlow;
import de.cuioss.sheriff.token.client.flow.StepUpChallengeParser.StepUpChallenge;
import de.cuioss.sheriff.token.client.flow.TokenEndpointClient;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.sheriff.token.validation.IssuerConfig;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.test.InMemoryKeyMaterialHandler;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.sheriff.token.validation.util.JwkThumbprintUtil;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Covers {@link BffRuntimeProducer}: the runtime is active (and its reserved handlers and session
 * stage are wired) only when a global {@code oidc} block with {@code session.mode=server} and a
 * {@code redirect_uri} is configured, and inert (bearer-only) otherwise. Assembly resolves no OIDC
 * discovery (that is deferred to first engine use), so the producer builds a working runtime without
 * a live IdP. The requests a produced runtime sends on its back-channel — discovery, the pushed
 * authorization request, the code exchange, the refresh grant and revocation — are asserted against
 * {@link StubIdentityProvider}; the round-trips that need a real grant are covered by the Keycloak
 * integration tests.
 */
@EnableGeneratorController
@DisplayName("BffRuntimeProducer — server-mode activation and inert bearer-only default")
class BffRuntimeProducerTest {

    private static final String ORIGIN = "https://gw.example.com";
    private static final String REDIRECT_URI = ORIGIN + "/auth/callback";
    private static final String ISSUER = "https://idp.example.com";
    private static final String SUBJECT = "session-subject";
    /**
     * Deliberately a fixed literal, not generated: the rotated-session assertion's whole content is
     * that the mediated token is <em>this</em> value rather than the pre-refresh one the session was
     * built with, so the two must be distinguishable by construction.
     */
    private static final String ROTATED_ACCESS_TOKEN = "rotated-access-token";

    /** The scope a scoped endpoint adds on top of {@code oidc.scopes = [openid]}. */
    private static final String SCOPED_ENDPOINT_SCOPE = "orders:read";

    /** Stands in for the Quarkus-managed virtual-thread executor the producer hands the refresh coordinator. */
    private static final ExecutorService REVOCATION_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** The bundled gateway schema, read off the classpath so the contract sees the shipped copy. */
    private static final String GATEWAY_SCHEMA_RESOURCE = "/schema/gateway.schema.json";

    /** JSON pointer to the {@code oidc.session.refresh.on_failure} enum array in the bundled gateway schema. */
    private static final String ON_FAILURE_ENUM_POINTER =
            "/properties/oidc/properties/session/properties/refresh/properties/on_failure/enum";

    /** The in-memory issuer backing both the validator and the signature-only verifier below. */
    private final IssuerConfig testIssuer = TestTokenGenerators.accessTokens().next().getIssuerConfig();

    private final TokenValidator tokenValidator = TokenValidator.builder().issuerConfig(testIssuer).build();

    /**
     * The seam the back-channel logout receiver is bound to. Built over the SAME issuer as the
     * validator, so the assembled runtime is wired exactly as production wires it — the two must not
     * drift apart, since a verifier over a different issuer set would verify nothing the validator
     * trusts.
     */
    private final SignatureOnlyTokenVerifier logoutTokenVerifier =
            new SignatureOnlyTokenVerifier(List.of(testIssuer), tokenValidator.getSecurityEventCounter());

    @Nested
    @DisplayName("Active server-mode runtime")
    class Active {

        private final BffRuntime runtime = producer(serverModeOidc()).bffRuntime();

        @Test
        @DisplayName("Should activate the runtime and expose the session stage and CSRF defence")
        void shouldActivate() {
            assertTrue(runtime.isActive());
            assertNotNull(runtime.sessionStage());
            assertNotNull(runtime.csrfDefence());
            assertNotNull(runtime.stepUpCoordinator());
        }

        /**
         * Assembly must not perform an OIDC discovery round-trip — that is deferred to first engine
         * use, which is what lets the gateway boot without a live IdP. A bare
         * {@code assertDoesNotThrow} cannot say this: a producer that <em>did</em> resolve discovery
         * against a reachable IdP would complete just as quietly. The issuer here is therefore on
         * {@code 192.0.2.0/24} (RFC 5737 TEST-NET-1, guaranteed unroutable), so a discovery attempt
         * would burn the connect timeout instead of returning — which the preemptive bound catches.
         */
        @Test
        @DisplayName("Should assemble without resolving OIDC discovery (no live IdP required)")
        void shouldAssembleWithoutDiscovery() {
            // Arrange — a well-formed server-mode configuration whose issuer nothing can reach
            OidcConfig unreachableIssuer = OidcConfig.builder()
                    .issuer("https://192.0.2.1:9999/realms/nowhere")
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .userInfo(OidcConfig.UserInfo.builder()
                            .path("/auth/userinfo")
                            .allowedClaims(List.of("sub", "name"))
                            .defaultView(List.of("sub"))
                            .build())
                    .login(OidcConfig.Login.builder().path("/auth/login").build())
                    .build();

            // Act
            BffRuntime assembled = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> producer(unreachableIssuer).bffRuntime(),
                    "assembly must not reach the IdP — a discovery round-trip against an unroutable "
                            + "issuer would exhaust the connect timeout instead of returning");

            // Assert — and what came back is a fully wired runtime, not a degraded or inert one
            assertTrue(assembled.isActive(),
                    "an unreachable issuer still yields an active runtime, because discovery is deferred");
            assertEquals(401, assembled.dispatch(ReservedEndpoint.USER_INFO,
                            new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET"),
                            Instant.parse("2026-07-25T10:00:00Z")).status(),
                    "the reserved endpoints are wired although no discovery ever ran");
        }

        @Test
        @DisplayName("Should keep the back-channel path un-gated — an absent logout_token yields the 400 contract")
        void shouldNotGateBackchannelInServerMode() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.BACKCHANNEL_LOGOUT,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, "other=value", "POST"),
                    Instant.parse("2026-07-25T10:00:00Z"));

            assertEquals(400, response.status(),
                    "the store-backed binding supports IdP destruction, so the endpoint stays open");
        }

        @Test
        @DisplayName("Should wire the user-info fold reachably — no session yields 401")
        void shouldWireUserInfo() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.USER_INFO,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET"),
                    Instant.parse("2026-07-25T10:00:00Z"));
            assertEquals(401, response.status());
        }

        /**
         * The wiring-level half of the response-mode assertion — the seam's own behaviour is pinned by
         * {@code QueryResponseModeAuthorizationRequestBuilderTest}.
         * <p>
         * This exists because the failure mode it guards is an <em>omission</em>, and an omission is
         * invisible to a behavioural test of the seam. The engine's
         * {@link AuthorizationRequestBuilder} emits {@code response_mode=form_post} unconditionally,
         * and its shorter constructors silently install that default: a future refactor that rebuilt
         * {@code AuthorizationCodeFlow} through the 4-argument constructor, or {@code StepUpHandler}
         * through its no-argument one, would compile, pass every seam test, and quietly reintroduce
         * the cross-site POST callback on which the {@code SameSite=Lax} binding cookie is dropped.
         * <p>
         * The assertion is therefore made against the object graph the producer actually built, and
         * it is <strong>type-directed rather than name-directed</strong>: it finds every
         * {@code AuthorizationRequestBuilder} reachable from the assembled runtime and requires each
         * one to be the gateway's query-mode subclass. Renaming an engine field does not break it;
         * reverting a seam to the engine default does — which is exactly the intended sensitivity.
         */
        @Test
        @DisplayName("Should wire the query-mode response builder into every engine authorization seam")
        void shouldWireQueryResponseModeIntoEveryAuthorizationSeam() {
            List<AuthorizationRequestBuilder> wired = reachableInstancesOf(runtime, AuthorizationRequestBuilder.class);

            assertFalse(wired.isEmpty(),
                    "no AuthorizationRequestBuilder was reachable from the assembled runtime — this test "
                            + "must never pass vacuously; if the producer's wiring moved, retarget the walk");
            assertAll("every engine seam that builds an authorization URL carries the query-mode builder",
                    wired.stream().map(builder -> (Executable) () ->
                            assertInstanceOf(QueryResponseModeAuthorizationRequestBuilder.class, builder,
                                    "an engine seam is still on the default builder, which emits "
                                            + "response_mode=form_post")));
        }
    }

    /**
     * Collects every instance of {@code target} reachable from {@code root} by walking instance
     * fields, following lambda captures so a collaborator held only inside a closure is still seen.
     * <p>
     * The walk is bounded to the gateway's and the engine's own packages: it never descends into JDK
     * or container types, which keeps it away from the strongly-encapsulated {@code java.*} modules
     * and stops it wandering through collections and class loaders. A field the JVM refuses to open
     * is skipped rather than failing the walk — the caller's non-empty assertion is what guarantees
     * the result is still meaningful.
     * <p>
     * Every caller that asserts an <em>absence</em> MUST be paired with one asserting the matching
     * presence, because an over-skipped walk returns the empty list too: only the positive control
     * distinguishes "the producer did not wire it" from "the walk could not see it".
     *
     * @param root   the assembled object graph to search
     * @param target the collaborator type to collect
     * @param <T>    the collaborator type
     * @return every reachable instance of {@code target}, in walk order
     */
    private static <T> List<T> reachableInstancesOf(Object root, Class<T> target) {
        List<T> found = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Object current = pending.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }
            if (target.isInstance(current)) {
                found.add(target.cast(current));
                continue;
            }
            for (Class<?> type = current.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        Object value = field.get(current);
                        if (value != null && isWalkable(value.getClass())) {
                            pending.push(value);
                        }
                    } catch (ReflectiveOperationException | InaccessibleObjectException _) {
                        // A field the JVM will not open tells us nothing; the non-empty assertion above
                        // is what keeps an over-skipped walk from passing vacuously. Only the two
                        // exceptions setAccessible/get can actually raise here are caught: a broader
                        // catch would swallow a genuine defect in the walk itself.
                    }
                }
            }
        }
        return found;
    }

    /** Restricts the walk to gateway and engine types — never JDK, container or collection internals. */
    private static boolean isWalkable(Class<?> type) {
        String name = type.getName();
        return name.startsWith("de.cuioss.sheriff.gateway.") || name.startsWith("de.cuioss.sheriff.token.client.");
    }

    /**
     * The one instance a walk is expected to find. Exactly one, not merely at least one: zero means
     * the caller would go on to assert nothing, and more than one means the first hit can no longer be
     * assumed to be the instance the runtime actually uses.
     *
     * @param found the result of {@link #reachableInstancesOf(Object, Class)}
     * @param what  what was searched for, for the failure message
     * @param <T>   the collaborator type
     * @return the single instance
     */
    private static <T> T single(List<T> found, String what) {
        assertEquals(1, found.size(), "exactly one " + what + " is reachable from the assembled runtime — "
                + "this test must never pass vacuously; if the producer's wiring moved, retarget the walk");
        return found.getFirst();
    }

    /**
     * The produced runtime tracks a long-lived relay under its session. In server mode the session
     * store the producer built reports every session it ends to the registry the runtime tracks in —
     * the two are wired together by the producer and nowhere else, so these cases reach the store
     * through the assembled object graph and end sessions on it directly.
     */
    @Nested
    @DisplayName("Session relays — the produced store reports session ends to the produced registry")
    class SessionRelays {

        private SessionRecord newSession(String sid) {
            return SessionRecord.builder().sessionId(SessionRecord.newSessionId())
                    .accessToken(Generators.letterStrings(16, 32).next())
                    .idToken(Generators.letterStrings(16, 32).next())
                    .sub(SUBJECT).sid(sid).expiresAt(Instant.now().plusSeconds(3600)).build();
        }

        @Test
        @DisplayName("server mode: a session the store destroys closes the relay tracked through the runtime")
        void serverModeStoreEndClosesTrackedRelay() {
            BffRuntime runtime = producer(serverModeOidc()).bffRuntime();
            InMemorySessionStore store = single(reachableInstancesOf(runtime, InMemorySessionStore.class),
                    "session store");
            String sid = Generators.letterStrings(8, 16).next();
            SessionRecord session = newSession(sid);
            store.create(session, "handle-" + SessionRecord.newSessionId(), Instant.now());
            AtomicInteger closes = new AtomicInteger();
            SessionRelayRegistry.Tracked relay = runtime.trackSessionRelay(session.sessionId(), session.expiresAt())
                    .orElseThrow();
            relay.onSessionEnd(closes::incrementAndGet);
            assertFalse(relay.sessionEnded(), "precondition: the runtime found the session held by the store");

            int destroyed = store.destroyBySid(sid);

            assertAll("the store's end reached the relay",
                    () -> assertEquals(1, destroyed, "the store ended the session"),
                    () -> assertEquals(1, closes.get(), "the relay's close action ran once"),
                    () -> assertTrue(relay.sessionEnded()));
        }

        @Test
        @DisplayName("server mode: the sweep removing an expired session closes the relay tracked through the runtime")
        void serverModeSweepClosesTrackedRelay() {
            BffRuntime runtime = producer(serverModeOidc()).bffRuntime();
            InMemorySessionStore store = single(reachableInstancesOf(runtime, InMemorySessionStore.class),
                    "session store");
            SessionRecord session = newSession(Generators.letterStrings(8, 16).next());
            store.create(session, "handle-" + SessionRecord.newSessionId(), Instant.now());
            AtomicInteger closes = new AtomicInteger();
            runtime.trackSessionRelay(session.sessionId(), session.expiresAt()).orElseThrow()
                    .onSessionEnd(closes::incrementAndGet);

            int swept = store.sweepExpired(session.expiresAt().plusSeconds(1));

            assertEquals(1, swept, "precondition: the sweep removed the session");
            assertEquals(1, closes.get(), "the relay's close action ran once");
        }

        @Test
        @DisplayName("server mode: a relay tracked for a session the store does not hold is ended at once")
        void serverModeLooksTheSessionUpInTheStore() {
            BffRuntime runtime = producer(serverModeOidc()).bffRuntime();

            SessionRelayRegistry.Tracked relay = runtime
                    .trackSessionRelay(SessionRecord.newSessionId(), Instant.now().plusSeconds(3600)).orElseThrow();

            assertTrue(relay.sessionEnded(), "the lookup after tracking asks the store, which holds no such session");
        }

        @Test
        @DisplayName("cookie mode: no store exists to report an end; the relay carries the session's absolute expiry")
        void cookieModeHasOnlyTheAbsoluteExpiry() {
            BffRuntime runtime = producer(cookieModeOidc()).bffRuntime();

            SessionRelayRegistry.Tracked relay = runtime
                    .trackSessionRelay(SessionRecord.newSessionId(), Instant.now().plusSeconds(3600)).orElseThrow();
            Duration untilExpiry = relay.untilAbsoluteExpiry().orElseThrow();

            assertAll("a stateless session ends for a relay at its absolute expiry only",
                    () -> assertEquals(List.of(), reachableInstancesOf(runtime, InMemorySessionStore.class),
                            "no server-side store is wired (the server-mode cases above find theirs)"),
                    () -> assertFalse(relay.sessionEnded(), "a session nothing holds server-side is not reported gone"),
                    () -> assertTrue(untilExpiry.compareTo(Duration.ofSeconds(3600)) <= 0
                            && untilExpiry.compareTo(Duration.ofSeconds(3500)) > 0,
                            "the time left is the session's own: " + untilExpiry));
        }

        @Test
        @DisplayName("tracks at most 10 000 relays and hands back nothing for the next")
        void boundsTheTrackedRelays() {
            BffRuntime runtime = producer(cookieModeOidc()).bffRuntime();
            Instant expiry = Instant.now().plusSeconds(3600);
            for (int tracked = 0; tracked < 10_000; tracked++) {
                assertTrue(runtime.trackSessionRelay("session-" + tracked, expiry).isPresent(),
                        "relay " + tracked + " is inside the bound");
            }

            assertEquals(Optional.empty(), runtime.trackSessionRelay("session-beyond", expiry));
        }
    }

    /**
     * {@code oidc.session.max_sessions_per_subject} acts: the store the producer builds applies the
     * declared bound, and the documented default when the key is omitted. Each case logs one subject in
     * repeatedly on the store reached through the assembled runtime.
     */
    @Nested
    @DisplayName("Per-subject session bound — oidc.session.max_sessions_per_subject reaches the produced store")
    class PerSubjectSessionBound {

        private InMemorySessionStore storeFor(OidcConfig.Session session) {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(ISSUER)
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(session)
                    .build();
            return single(reachableInstancesOf(producer(oidc).bffRuntime(), InMemorySessionStore.class),
                    "session store");
        }

        /** Logs {@link #SUBJECT} in {@code logins} times and returns the session identities in login order. */
        private List<String> logIn(InMemorySessionStore store, int logins) {
            List<String> sessionIds = new ArrayList<>();
            Instant login = Instant.now();
            for (int index = 0; index < logins; index++) {
                SessionRecord session = SessionRecord.builder().sessionId(SessionRecord.newSessionId())
                        .accessToken(Generators.letterStrings(16, 32).next())
                        .idToken(Generators.letterStrings(16, 32).next())
                        .sub(SUBJECT).expiresAt(login.plusSeconds(3600)).build();
                store.create(session, "handle-" + SessionRecord.newSessionId(), login);
                sessionIds.add(session.sessionId());
            }
            return sessionIds;
        }

        private List<String> held(InMemorySessionStore store, List<String> sessionIds) {
            return sessionIds.stream().filter(store::isHeld).toList();
        }

        @Test
        @DisplayName("a declared bound of 1 ends the subject's first session at its second login")
        void declaredBoundIsApplied() {
            InMemorySessionStore store = storeFor(
                    OidcConfig.Session.builder().mode("server").maxSessionsPerSubject(1).build());

            List<String> sessions = logIn(store, 2);

            assertEquals(List.of(sessions.get(1)), held(store, sessions),
                    "only the newest session of the subject is held");
        }

        @Test
        @DisplayName("an omitted key bounds a subject at 10: the eleventh login ends the first session")
        void omittedKeyAppliesTheDefault() {
            InMemorySessionStore store = storeFor(OidcConfig.Session.builder().mode("server").build());

            List<String> ten = logIn(store, 10);
            List<String> heldAfterTen = held(store, ten);
            List<String> eleventh = logIn(store, 1);

            assertAll("the default bound is ten sessions per subject",
                    () -> assertEquals(ten, heldAfterTen, "ten logins of one subject are all held"),
                    () -> assertEquals(ten.subList(1, 10), held(store, ten),
                            "the eleventh login ended the first session and no other"),
                    () -> assertTrue(store.isHeld(eleventh.getFirst()), "and the eleventh session is held"));
        }

        @Test
        @DisplayName("an omitted key is capped at max_sessions: a subject filling a store of 3 is not refused")
        void omittedKeyIsCappedAtMaxSessions() {
            InMemorySessionStore store = storeFor(
                    OidcConfig.Session.builder().mode("server").maxSessions(3).build());
            List<String> three = logIn(store, 3);

            List<String> fourth = assertDoesNotThrow(() -> logIn(store, 1),
                    "the subject's own bound of 3 frees a slot before the store-wide bound is tested");

            assertAll("the subject's oldest session made room",
                    () -> assertEquals(three.subList(1, 3), held(store, three)),
                    () -> assertTrue(store.isHeld(fourth.getFirst())),
                    () -> assertEquals(3, store.size()));
        }
    }

    /**
     * The names the produced runtime hands the edge as the cookies no upstream response may set. The
     * set is derived from the session binding the producer built, so it follows the session mode and a
     * configured {@code cookie_name}.
     */
    @Nested
    @DisplayName("Gateway cookie names — the cookies the produced runtime sets, by session mode and cookie_name")
    class GatewayCookieNames {

        private static final String BINDING_COOKIE = "__Host-sheriff-binding";
        private static final String LOGOUT_STATE_COOKIE = "__Host-sheriff-logout";
        private static final String CONFIGURED_NAME = "__Host-shop-session";

        private Set<String> namesFor(OidcConfig.Session session) {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(ISSUER)
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(session)
                    .build();
            return producer(oidc).bffRuntime().gatewayCookieNames();
        }

        @Test
        @DisplayName("server mode: the login-binding cookie, the logout-state cookie and the default session cookie")
        void serverModeDefaultName() {
            assertEquals(Set.of(BINDING_COOKIE, LOGOUT_STATE_COOKIE, SessionCookieCodec.DEFAULT_COOKIE_NAME),
                    namesFor(OidcConfig.Session.builder().mode("server").build()));
        }

        @Test
        @DisplayName("server mode: a configured cookie_name replaces the default session cookie name")
        void serverModeConfiguredName() {
            assertEquals(Set.of(BINDING_COOKIE, LOGOUT_STATE_COOKIE, CONFIGURED_NAME),
                    namesFor(OidcConfig.Session.builder().mode("server").cookieName(CONFIGURED_NAME).build()));
        }

        @Test
        @DisplayName("cookie mode: the activity cookie of the session cookie is owned as well")
        void cookieModeDefaultName() {
            assertEquals(Set.of(BINDING_COOKIE, LOGOUT_STATE_COOKIE, SessionCookieCodec.DEFAULT_COOKIE_NAME,
                            SessionCookieCodec.DEFAULT_COOKIE_NAME + "-activity"),
                    namesFor(OidcConfig.Session.builder().mode("cookie").build()));
        }

        @Test
        @DisplayName("cookie mode: a configured cookie_name names the session cookie and its activity cookie")
        void cookieModeConfiguredName() {
            assertEquals(Set.of(BINDING_COOKIE, LOGOUT_STATE_COOKIE, CONFIGURED_NAME, CONFIGURED_NAME + "-activity"),
                    namesFor(OidcConfig.Session.builder().mode("cookie").cookieName(CONFIGURED_NAME).build()));
        }

        @Test
        @DisplayName("control: the inert runtime of a gateway without sessions owns no cookie")
        void inertRuntimeOwnsNothing() {
            assertEquals(Set.of(), BffRuntime.inert().gatewayCookieNames());
        }
    }

    @Nested
    @DisplayName("Active cookie-mode runtime")
    class ActiveCookieMode {

        private final BffRuntime runtime = producer(cookieModeOidc()).bffRuntime();

        @Test
        @DisplayName("Should activate the runtime for session.mode=cookie, exactly as for server mode")
        void shouldActivateForCookieMode() {
            assertTrue(runtime.isActive(), "cookie mode is a recognised BFF mode, not a bearer-only gateway");
            assertNotNull(runtime.sessionStage());
            assertNotNull(runtime.csrfDefence());
            assertNotNull(runtime.stepUpCoordinator());
        }

        @Test
        @DisplayName("Should wire the same reserved endpoints — no session yields 401 from the user-info fold")
        void shouldWireTheSameReservedEndpoints() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.USER_INFO,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET"),
                    Instant.parse("2026-07-25T10:00:00Z"));
            assertEquals(401, response.status(), "both modes drive identical wiring above the session binding");
        }

        @Test
        @DisplayName("Should still register the back-channel path, answering a deliberate uncacheable 404")
        void shouldRegisterBackchannelPathGatedTo404() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.BACKCHANNEL_LOGOUT,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null,
                            "logout_token=abc.def.ghi", "POST"),
                    Instant.parse("2026-07-25T10:00:00Z"));

            assertEquals(404, response.status(),
                    "the reserved path stays registered and returns a deliberate 404, never falling through");
            assertEquals("no-store", response.headers().get("Cache-Control"),
                    "the gated outcome is served uncacheable exactly as the 200/400 outcomes are");
        }

        @Test
        @DisplayName("Should boot cookie mode without an encryption key, generating one on startup")
        void shouldBootCookieModeWithoutKey() {
            OidcConfig noKey = OidcConfig.builder()
                    .issuer(ISSUER)
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("cookie").build())
                    .build();

            BffRuntime generated = producer(noKey).bffRuntime();
            BffRuntime secondBoot = producer(noKey).bffRuntime();

            // isActive() and a non-null sessionStage say the runtime came up; neither says a key was
            // generated, and both are satisfied by a cookie-mode runtime that came up with no key at
            // all. Reach the codec the producer actually assembled and make it do the one thing a key
            // is for — then prove the key is fresh per startup rather than a shipped constant.
            SealedSessionCookieCodec codec = assembledCodecOf(generated);
            SealedSessionPayload session = cookieSession();
            String sealed = assertDoesNotThrow(() -> codec.seal(session));

            assertAll("cookie mode generated a usable, per-startup key",
                    () -> assertTrue(generated.isActive(),
                            "omitting the key selects generate-on-startup, a supported production mode "
                                    + "— not a boot failure"),
                    () -> assertNotNull(generated.sessionStage()),
                    () -> assertEquals(Optional.of(new SealedSessionCookieCodec.Unsealed(session)),
                            codec.unseal(sealed),
                            "the generated key seals and unseals a real session"),
                    () -> assertTrue(assembledCodecOf(secondBoot).unseal(sealed).isEmpty(),
                            "and the key is generated per startup: a second boot cannot open the first "
                                    + "boot's cookie, which a hard-coded or absent key would"));
        }

        /**
         * The single {@link SealedSessionCookieCodec} the cookie-mode producer wired, located by the
         * same bounded object-graph walk the query-mode assertions use.
         *
         * @param runtime the assembled cookie-mode runtime
         * @return the codec the producer built
         */
        private SealedSessionCookieCodec assembledCodecOf(BffRuntime runtime) {
            List<SealedSessionCookieCodec> wired =
                    reachableInstancesOf(runtime, SealedSessionCookieCodec.class);
            // Exactly one, not merely at least one. The walk de-duplicates by identity but returns every
            // DISTINCT codec, so a second one reaching the graph would leave getFirst() free to hand back
            // the codec the runtime path does not use — and every assertion built on it would then be
            // about the wrong object while still passing.
            assertEquals(1, wired.size(),
                    "the cookie-mode runtime must expose exactly one reachable SealedSessionCookieCodec — "
                            + "zero means this test would pass vacuously and the walk needs retargeting "
                            + "because the producer's wiring moved; more than one means getFirst() can no "
                            + "longer be assumed to be the codec the runtime actually seals with");
            return wired.getFirst();
        }

        /** A minimal but real cookie session; the login instant carries no sub-second part the wire form would drop. */
        private static SealedSessionPayload cookieSession() {
            return new SealedSessionPayload("raw-access-token", null, "raw-id-token", "user-sub-1",
                    null, null, null, Instant.ofEpochSecond(Instant.now().getEpochSecond()),
                    "session-nonce-material", Set.of(), Set.of());
        }

        @Test
        @DisplayName("Should refuse an encryption key that is not a base64 AES-256 value")
        void shouldRefuseMalformedKey() {
            BffRuntimeProducer nonBase64 = producer(cookieModeOidcWithKey("not-base64-~~~"));
            BffRuntimeProducer aes128 =
                    producer(cookieModeOidcWithKey(Base64.getEncoder().encodeToString(new byte[16])));

            assertThrows(IllegalStateException.class, nonBase64::bffRuntime);
            assertThrows(IllegalStateException.class, aes128::bffRuntime,
                    "an AES-128 key is refused — the codec is specified as AES-256-GCM");
        }
    }

    /**
     * {@code oidc.step_up.path} is proven to <em>act</em>, not just parse: the reserved-path registry
     * the edge builds from the same {@link OidcConfig} resolves the configured path to
     * {@link ReservedEndpoint#STEP_UP}, and the runtime the producer assembled dispatches that kind to a
     * wired {@link StepUpEndpoint} in both session modes. Deleting the key turns
     * {@link #shouldDispatchConfiguredPathInBothModes()} red at the registry leg; the omitted-key case
     * is the matched control.
     */
    @Nested
    @DisplayName("Step-up path (oidc.step_up.path)")
    class StepUpPath {

        private static final String OIDC_HOST = "gw.example.com";
        private static final String STEP_UP_PATH = "/auth/step-up";
        private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");

        private static OidcConfig withStepUpPath(OidcConfig base, @Nullable String path) {
            return new OidcConfig(base.issuer(), base.clientId(), base.clientSecret(), base.scopes(),
                    base.redirectUri(), base.logout(), base.session(), OidcConfig.StepUp.builder().path(path).build(),
                    base.userInfo(), base.login(), base.clientAuthentication(), base.senderConstraint());
        }

        @Test
        @DisplayName("Should dispatch a configured step-up path to a wired endpoint in server and cookie mode")
        void shouldDispatchConfiguredPathInBothModes() {
            List<OidcConfig> modes = List.of(withStepUpPath(serverModeOidc(), STEP_UP_PATH),
                    withStepUpPath(cookieModeOidc(), STEP_UP_PATH));

            assertAll("every session mode registers and dispatches STEP_UP",
                    modes.stream().map(oidc -> (Executable) () -> {
                        assertEquals(Optional.of(ReservedEndpoint.STEP_UP),
                                ReservedPathRegistry.from(oidc).match(OIDC_HOST, STEP_UP_PATH),
                                "the configured key reserves the step-up path on the OIDC host");
                        BffRuntime runtime = producer(oidc).bffRuntime();
                        BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.STEP_UP,
                                new BffRuntime.ReservedHttpRequest("", null, null, "/orders", null, null, "GET"), NOW);
                        assertEquals(401, response.status(),
                                "a wired step-up endpoint answers a request without a session 401");
                        assertEquals("application/problem+json", response.headers().get("Content-Type"));
                        assertTrue(response.locationOptional().isEmpty(), "never an IdP redirect without a session");
                        assertTrue(response.jsonBodyOptional().isPresent(), "the problem body is rendered");
                        assertEquals(1, reachableInstancesOf(runtime, StepUpEndpoint.class).size(),
                                "exactly one step-up endpoint is wired");
                    }));
        }

        @Test
        @DisplayName("Should reserve no step-up path when the key is omitted (matched control)")
        void shouldReserveNothingWithoutKey() {
            OidcConfig omitted = withStepUpPath(serverModeOidc(), null);

            assertAll("an omitted key registers no STEP_UP endpoint",
                    () -> assertTrue(ReservedPathRegistry.from(omitted).match(OIDC_HOST, STEP_UP_PATH).isEmpty()),
                    () -> assertFalse(ReservedPathRegistry.reservedPaths(omitted).contains(STEP_UP_PATH)));
        }

        /**
         * The step-up endpoint and the callback's interactive re-drive go through the same widening
         * coordinator — one {@link SessionWidening} per runtime. A second instance would carry its own
         * collaborators and let the two legs drift apart.
         */
        @Test
        @DisplayName("Should build the step-up endpoint over the runtime's single SessionWidening")
        void shouldShareTheSingleSessionWidening() {
            BffRuntime runtime = producer(withStepUpPath(serverModeOidc(), STEP_UP_PATH)).bffRuntime();

            List<StepUpEndpoint> endpoints = reachableInstancesOf(runtime, StepUpEndpoint.class);
            assertEquals(1, endpoints.size(), "the walk must see the step-up endpoint, or this test is vacuous");
            List<SessionWidening> fromRuntime = reachableInstancesOf(runtime, SessionWidening.class);
            List<SessionWidening> fromStepUp = reachableInstancesOf(endpoints.getFirst(), SessionWidening.class);

            assertAll("one widening coordinator, shared",
                    () -> assertEquals(1, fromRuntime.size(), "exactly one SessionWidening per runtime"),
                    () -> assertEquals(1, fromStepUp.size()),
                    () -> assertSame(fromRuntime.getFirst(), fromStepUp.getFirst(),
                            "the step-up endpoint holds the runtime's own instance"));
        }
    }

    /**
     * The session stage's scope enforcement is proven to be <em>wired</em> by the producer: the
     * scope-refresh seam drives the very coordinator the near-expiry seam drives (or nothing at all when
     * refresh is switched off), and {@code oidc.step_up.path} reaches the stage's {@code 403} answer.
     * The widening seam needs a resolvable discovery document and is therefore asserted beside the
     * other fixture-backed tests, in {@code OidcBackChannelTls}.
     * <p>
     * The step-up assertions are behavioural: a live session is bound through the binding the assembled
     * runtime actually holds and a request is driven through the assembled stage, so deleting the key
     * — or the producer no longer passing it — turns {@link #shouldNameConfiguredStepUpPathOn403()} red.
     * {@link #shouldNameNoStepUpUrlWithoutKey()} is the matched control.
     */
    @Nested
    @DisplayName("Session-route scope enforcement wiring (scope seams and oidc.step_up.path)")
    class SessionRouteScopeWiring {

        private static final String STEP_UP_PATH = "/auth/step-up";
        private static final String OPENID_SCOPE = "openid";
        private static final String NEEDED_SCOPE = "orders:read";

        @Test
        @DisplayName("Should drive the near-expiry and the scope-refresh seam through one coordinator in server and cookie mode")
        void shouldDriveBothRefreshSeamsThroughOneCoordinator() {
            assertAll("one coordinator per runtime, behind both stage seams",
                    Stream.of(serverModeOidc(), cookieModeOidc()).map(oidc -> (Executable) () -> {
                        SessionAuthenticationStage stage = producer(oidc).bffRuntime().sessionStage();

                        List<TokenRefreshCoordinator> behindScopeSeam = reachableInstancesOf(
                                singleSeam(stage, SessionAuthenticationStage.ScopeRefresh.class),
                                TokenRefreshCoordinator.class);
                        List<TokenRefreshCoordinator> behindNearExpirySeam = reachableInstancesOf(
                                singleSeam(stage, SessionAuthenticationStage.TokenRefresh.class),
                                TokenRefreshCoordinator.class);

                        assertEquals(1, behindNearExpirySeam.size(),
                                "the walk must see the near-expiry coordinator, or this test is vacuous");
                        assertEquals(1, behindScopeSeam.size(), "the scope seam is bound to a coordinator");
                        assertSame(behindNearExpirySeam.getFirst(), behindScopeSeam.getFirst(),
                                "both seams share one coordinator");
                    }));
        }

        @Test
        @DisplayName("Should bind a scope seam that reaches no coordinator and keeps the session when refresh.enabled is false")
        void shouldBindPassThroughScopeSeamWhenRefreshDisabled() {
            SessionAuthenticationStage stage = producer(refreshOidc(Boolean.FALSE)).bffRuntime().sessionStage();
            SessionAuthenticationStage.ScopeRefresh scopeSeam =
                    singleSeam(stage, SessionAuthenticationStage.ScopeRefresh.class);
            SessionRecord live = SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken(token())
                    .idToken(token())
                    .sub(SUBJECT)
                    .expiresAt(Instant.now().plus(Duration.ofHours(1)))
                    .activeScopes(Set.of(OPENID_SCOPE))
                    .grantedScopes(Set.of(OPENID_SCOPE, NEEDED_SCOPE))
                    .build();

            SessionAuthenticationStage.RefreshResult result =
                    scopeSeam.refreshForScopes(live, null, Set.of(OPENID_SCOPE, NEEDED_SCOPE), Instant.now());

            assertAll("with refresh off the stage goes straight to widening",
                    () -> assertTrue(reachableInstancesOf(scopeSeam, TokenRefreshCoordinator.class).isEmpty(),
                            "no coordinator sits behind the scope seam"),
                    () -> assertSame(live, assertInstanceOf(SessionAuthenticationStage.RefreshResult.Mediate.class,
                                    result, "the seam keeps the session").boundSession().session(),
                            "the still under-scoped session is handed back unchanged"));
        }

        @Test
        @DisplayName("Should name the configured step-up path on a session route's 403 in server and cookie mode")
        void shouldNameConfiguredStepUpPathOn403() {
            assertAll("oidc.step_up.path reaches the stage in every session mode",
                    Stream.of(serverModeOidc(), cookieModeOidc()).map(base -> (Executable) () -> {
                        GatewayException thrown =
                                refuseUnderScopedApiCall(StepUpPath.withStepUpPath(base, STEP_UP_PATH));

                        assertEquals(EventType.SCOPE_MISSING, thrown.getEventType(),
                                "the assembled stage refuses the under-scoped API call 403");
                        assertEquals(List.of(NEEDED_SCOPE),
                                thrown.getProblemExtensions().get(SessionAuthenticationStage.MISSING_SCOPES_MEMBER),
                                "the missing scope is named");
                        assertEquals(STEP_UP_PATH + "?returnUrl=%2Forders%2Flist",
                                thrown.getProblemExtensions().get(SessionAuthenticationStage.STEP_UP_URL_MEMBER),
                                "the configured path is the one the answer names");
                    }));
        }

        @Test
        @DisplayName("Should name no step-up URL when oidc.step_up.path is omitted (matched control)")
        void shouldNameNoStepUpUrlWithoutKey() {
            GatewayException thrown = refuseUnderScopedApiCall(serverModeOidc());

            assertEquals(Map.of(SessionAuthenticationStage.MISSING_SCOPES_MEMBER, List.of(NEEDED_SCOPE)),
                    thrown.getProblemExtensions(),
                    "an omitted key leaves the refusal without a step-up URL");
        }

        /**
         * Drives an API call through the assembled session stage with a live session that carries
         * {@code openid} only, on a route that additionally needs {@link #NEEDED_SCOPE}. The scope was
         * never granted, so no refresh can obtain it and the stage refuses.
         */
        private GatewayException refuseUnderScopedApiCall(OidcConfig oidc) {
            BffRuntime runtime = producer(oidc).bffRuntime();
            String cookieHeader = bindLiveSession(runtime, Set.of(OPENID_SCOPE));
            PipelineRequest request = sessionRouteRequest(cookieHeader, "application/json",
                    Set.of(OPENID_SCOPE, NEEDED_SCOPE));
            SessionAuthenticationStage stage = runtime.sessionStage();

            return assertThrows(GatewayException.class, () -> stage.process(request),
                    "an under-scoped API call must be refused, never relayed");
        }

        /** The one seam of {@code seamType} the assembled stage holds. */
        private static <T> T singleSeam(SessionAuthenticationStage stage, Class<T> seamType) {
            List<T> seams = reachableInstancesOf(stage, seamType);
            assertEquals(1, seams.size(), "exactly one " + seamType.getSimpleName() + " is reachable from the "
                    + "assembled session stage — if the producer's wiring moved, retarget the walk");
            return seams.getFirst();
        }
    }

    /**
     * Binds a live session whose active and granted scope sets are both {@code scopes} through the
     * binding the assembled runtime actually holds, and returns the request {@code Cookie} header that
     * presents it. Mode-neutral: a server-mode binding stores the record and a cookie-mode binding
     * seals it. The session carries no refresh token, so the near-expiry seam hands it back without
     * ever parsing its opaque access token.
     */
    private static String bindLiveSession(BffRuntime runtime, Set<String> scopes) {
        List<SessionBinding> bindings = reachableInstancesOf(runtime.sessionStage(), SessionBinding.class);
        assertEquals(1, bindings.size(), "exactly one session binding is reachable from the assembled session "
                + "stage — if the producer's wiring moved, retarget the walk");
        Instant now = Instant.now();
        SessionBinding.BoundSession bound = bindings.getFirst().bind(SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken(token())
                .idToken(token())
                .sub(SUBJECT)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .activeScopes(scopes)
                .grantedScopes(scopes)
                .build(), now);
        return bound.setCookieHeaders().getFirst().split(";", 2)[0];
    }

    /** A request on a {@code require: session} route at {@code /orders/list} needing {@code neededScopes}. */
    private static PipelineRequest sessionRouteRequest(String cookieHeader, String accept, Set<String> neededScopes) {
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath("/orders/list")
                .queryParameters(List.of())
                .headers(Map.of("cookie", List.of(cookieHeader), "accept", List.of(accept)))
                .build();
        request.canonicalPath("/orders/list");
        request.selectedRoute(RouteRuntime.builder().id("orders")
                .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                .neededScopes(neededScopes)
                .build());
        return request;
    }

    /**
     * {@code oidc.session.refresh.enabled} is the switch for the whole transparent-refresh path, and
     * this is where it is proven to <em>act</em> rather than merely parse: the key was carried in the
     * config model and in every BFF descriptor while {@link BffRuntimeProducer} read only
     * {@code leewaySeconds()}, so setting it to {@code false} changed nothing at all.
     * <p>
     * The assertion is made against the object graph the producer actually built, because the
     * failure mode is an <em>omission</em> — a producer that silently stopped consulting the key
     * would keep assembling a coordinator and keep every behavioural test green. The three cases
     * below are a matched control set: the disabled case asserts an absence, and the two enabled
     * cases (explicit {@code true}, and the omitted-key default) are what prove the walk can see a
     * coordinator when one is wired, so the absence means "not assembled" and not "not found".
     */
    @Nested
    @DisplayName("Transparent-refresh switch (oidc.session.refresh.enabled)")
    class RefreshSwitch {

        @Test
        @DisplayName("Should assemble the refresh coordinator when refresh.enabled is true")
        void shouldWireCoordinatorWhenEnabled() {
            assertFalse(coordinatorsFor(refreshOidc(Boolean.TRUE)).isEmpty(),
                    "an explicitly enabled refresh must reach the stage's refresh seam");
        }

        @Test
        @DisplayName("Should default to enabled when the key — or the whole refresh block — is omitted")
        void shouldDefaultToEnabled() {
            assertAll("an absent declaration resolves the documented default: refresh on",
                    () -> assertFalse(coordinatorsFor(refreshOidc(null)).isEmpty(),
                            "a refresh block declaring only leeway_seconds keeps refresh on"),
                    () -> assertFalse(coordinatorsFor(serverModeOidc()).isEmpty(),
                            "no refresh block at all resolves the same default"));
        }

        @Test
        @DisplayName("Should assemble no refresh coordinator at all when refresh.enabled is false")
        void shouldOmitCoordinatorWhenDisabled() {
            BffRuntime disabled = producer(refreshOidc(Boolean.FALSE)).bffRuntime();

            assertTrue(disabled.isActive(),
                    "turning refresh off is a policy choice, not a de-activation — the BFF stays wired");
            assertTrue(reachableInstancesOf(disabled, TokenRefreshCoordinator.class).isEmpty(),
                    "refresh.enabled=false must leave no coordinator in the graph; one that is present "
                            + "but unreachable would still hold the engine RefreshFlow and the leeway");
        }

        private List<TokenRefreshCoordinator> coordinatorsFor(OidcConfig oidc) {
            return reachableInstancesOf(producer(oidc).bffRuntime(), TokenRefreshCoordinator.class);
        }
    }

    /**
     * The ended-refresh-token marker is selected by session mode: cookie mode binds the bounded marker
     * that refuses a replayed ended token locally, server mode the inert one. The selection is asserted
     * through what each marker does, and the assembled runtime is walked to prove the selected marker is
     * the one the coordinator actually holds.
     */
    @Nested
    @DisplayName("Ended refresh-token marker by session mode")
    class EndedRefreshTokenMarker {

        private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");

        @Test
        @DisplayName("Should bind a marker that remembers an ended token in cookie mode")
        void shouldBindBoundedMarkerInCookieMode() {
            EndedRefreshTokens marker = BffRuntimeProducer.endedRefreshTokens(sessionOf(cookieModeOidc()));
            String refreshToken = token();

            marker.markEnded(refreshToken, NOW.plusSeconds(3600), NOW);

            assertTrue(marker.isEnded(refreshToken, NOW), "cookie mode must refuse a replayed ended token locally");
        }

        @Test
        @DisplayName("Should bind a marker that remembers nothing in server mode")
        void shouldBindInertMarkerInServerMode() {
            EndedRefreshTokens marker = BffRuntimeProducer.endedRefreshTokens(sessionOf(serverModeOidc()));
            String refreshToken = token();

            marker.markEnded(refreshToken, NOW.plusSeconds(3600), NOW);

            assertFalse(marker.isEnded(refreshToken, NOW),
                    "server mode destroys the stored session, so the marker must stay inert");
        }

        @Test
        @DisplayName("Should hand the mode's marker to the assembled refresh coordinator")
        void shouldWireMarkerIntoAssembledCoordinator() {
            List<EndedRefreshTokens> cookieMarkers =
                    reachableInstancesOf(producer(cookieModeOidc()).bffRuntime(), EndedRefreshTokens.class);
            List<EndedRefreshTokens> serverMarkers =
                    reachableInstancesOf(producer(serverModeOidc()).bffRuntime(), EndedRefreshTokens.class);

            assertAll("each mode's coordinator holds exactly its own marker",
                    () -> assertEquals(1, cookieMarkers.size(), "cookie mode assembles one marker"),
                    () -> assertInstanceOf(EndedRefreshTokens.Bounded.class, cookieMarkers.getFirst()),
                    () -> assertEquals(List.of(EndedRefreshTokens.inert()), serverMarkers,
                            "server mode assembles the inert marker"));
        }

        private static OidcConfig.Session sessionOf(OidcConfig oidc) {
            return Objects.requireNonNull(oidc.session(), "session");
        }
    }

    /**
     * {@code oidc.session.refresh.on_failure} is proven to <em>act</em>: the key was declared on the
     * config model and in the documentation while no main-code class read it. The assembled runtime is
     * walked for the policy the stage actually holds, so deleting the key from the reject descriptor —
     * or the producer no longer passing it — turns {@link #shouldHandDeclaredRejectToTheStage()} red; the
     * omitted-key case is the matched control proving the walk sees the policy at all.
     */
    @Nested
    @DisplayName("Refresh-failure policy (oidc.session.refresh.on_failure)")
    class OnFailurePolicy {

        @Test
        @DisplayName("Should hand a declared on_failure: reject to the assembled session stage")
        void shouldHandDeclaredRejectToTheStage() {
            List<SessionAuthenticationStage.OnFailure> policies = reachableInstancesOf(
                    producer(onFailureOidc("reject")).bffRuntime(), SessionAuthenticationStage.OnFailure.class);

            assertEquals(List.of(SessionAuthenticationStage.OnFailure.REJECT), policies,
                    "the declared reject must be the one policy the stage holds");
        }

        @Test
        @DisplayName("Should default to reauthenticate when on_failure is omitted (matched control)")
        void shouldDefaultToReauthenticate() {
            List<SessionAuthenticationStage.OnFailure> policies = reachableInstancesOf(
                    producer(onFailureOidc(null)).bffRuntime(), SessionAuthenticationStage.OnFailure.class);

            assertEquals(List.of(SessionAuthenticationStage.OnFailure.REAUTHENTICATE), policies,
                    "an omitted key resolves the documented default, and the walk can see the policy");
        }

        @Test
        @DisplayName("Should resolve both declared spellings and the omitted key")
        void shouldResolveDeclaredSpellings() {
            assertAll("the two schema spellings and the omitted key",
                    () -> assertEquals(SessionAuthenticationStage.OnFailure.REAUTHENTICATE,
                            BffRuntimeProducer.onFailurePolicy("reauthenticate")),
                    () -> assertEquals(SessionAuthenticationStage.OnFailure.REJECT,
                            BffRuntimeProducer.onFailurePolicy("reject")),
                    () -> assertEquals(SessionAuthenticationStage.OnFailure.REAUTHENTICATE,
                            BffRuntimeProducer.onFailurePolicy(null)));
        }

        @Test
        @DisplayName("Should refuse an unrecognised on_failure rather than silently defaulting")
        void shouldRefuseUnrecognisedValue() {
            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> BffRuntimeProducer.onFailurePolicy("ignore"));

            assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
            assertTrue(thrown.getMessage().contains("oidc.session.refresh.on_failure"),
                    "the refusal must name the key: " + thrown.getMessage());
        }

        /**
         * The schema and the runtime mapping each declare the {@code on_failure} spellings, and nothing
         * but this contract ties the two: a schema-admitted value the mapping refuses would fail boot on a
         * document validation accepted, and a mapped value the schema rejects would be unreachable. The
         * enum is read structurally through {@link #ON_FAILURE_ENUM_POINTER} off the bundled classpath
         * copy, the way {@code DocumentedSetsContractTest} binds the {@code Require} posture set.
         */
        @Test
        @DisplayName("Should resolve every on_failure value the bundled schema admits onto exactly the stage's policies")
        void shouldBindSchemaEnumToRuntimePolicy() {
            JsonNode declared = bundledOnFailureEnum();

            assertOnFailureEnumBound(declared, GATEWAY_SCHEMA_RESOURCE);
        }

        /**
         * The negative control for {@link #shouldBindSchemaEnumToRuntimePolicy()}: a copy of the shipped
         * enum carrying a value the mapping does not know must be refused by the same assertion, so the
         * resolution half cannot pass vacuously; and a copy missing one spelling must be refused by the
         * policy-coverage half.
         */
        @Test
        @DisplayName("Should refuse a schema enum that admits an unmapped value or omits a mapped one (negative control)")
        void shouldRefuseDriftedSchemaEnum() {
            ArrayNode widened = bundledOnFailureEnum().deepCopy();
            widened.add("ignore");
            ArrayNode narrowed = bundledOnFailureEnum().deepCopy();
            narrowed.remove(narrowed.size() - 1);

            assertAll("both directions of drift are detected",
                    () -> assertThrows(AssertionError.class,
                            () -> assertOnFailureEnumBound(widened, "a widened copy of " + GATEWAY_SCHEMA_RESOURCE),
                            "a schema value the runtime mapping refuses must fail the contract"),
                    () -> assertThrows(AssertionError.class,
                            () -> assertOnFailureEnumBound(narrowed, "a narrowed copy of " + GATEWAY_SCHEMA_RESOURCE),
                            "a runtime policy the schema no longer admits must fail the contract"));
        }

        private static ArrayNode bundledOnFailureEnum() {
            JsonNode schema;
            try (InputStream in = BffRuntimeProducerTest.class.getResourceAsStream(GATEWAY_SCHEMA_RESOURCE)) {
                assertNotNull(in, "the bundled schema " + GATEWAY_SCHEMA_RESOURCE + " is not on the test classpath");
                schema = new ObjectMapper().readTree(in);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read the bundled schema " + GATEWAY_SCHEMA_RESOURCE, e);
            }
            return assertInstanceOf(ArrayNode.class, schema.at(ON_FAILURE_ENUM_POINTER), GATEWAY_SCHEMA_RESOURCE
                    + ": nothing array-valued resolves at " + ON_FAILURE_ENUM_POINTER + ", so the on_failure"
                    + " schema-to-policy contract has nothing to bind. Update ON_FAILURE_ENUM_POINTER to where the"
                    + " schema now declares the enum");
        }

        private static void assertOnFailureEnumBound(JsonNode declared, String label) {
            assertFalse(declared.isEmpty(), label + ": " + ON_FAILURE_ENUM_POINTER + " resolved to an empty array,"
                    + " so the contract would pass vacuously");
            Set<SessionAuthenticationStage.OnFailure> resolved = EnumSet.noneOf(SessionAuthenticationStage.OnFailure.class);
            for (JsonNode value : declared) {
                String spelling = value.asText();
                resolved.add(assertDoesNotThrow(() -> BffRuntimeProducer.onFailurePolicy(spelling),
                        label + " admits on_failure '" + spelling + "' at " + ON_FAILURE_ENUM_POINTER
                                + ", but BffRuntimeProducer.onFailurePolicy refuses it — a document the schema"
                                + " validates would fail boot"));
            }
            assertEquals(EnumSet.allOf(SessionAuthenticationStage.OnFailure.class), resolved,
                    label + " at " + ON_FAILURE_ENUM_POINTER + " does not resolve onto every"
                            + " SessionAuthenticationStage.OnFailure constant through BffRuntimeProducer.onFailurePolicy"
                            + " — a runtime policy the schema does not admit is unreachable configuration");
            assertEquals(SessionAuthenticationStage.OnFailure.values().length, declared.size(),
                    label + " at " + ON_FAILURE_ENUM_POINTER + " declares " + declared.size() + " entries for "
                            + SessionAuthenticationStage.OnFailure.values().length
                            + " SessionAuthenticationStage.OnFailure constants — a duplicate or an extra spelling"
                            + " a set comparison cannot see");
        }

        private OidcConfig onFailureOidc(@Nullable String onFailure) {
            return refreshOidc(Boolean.TRUE, onFailure);
        }
    }

    /**
     * {@code oidc.login.default_return_url} is proven to <em>act</em>: the assembled runtime is walked
     * for the {@link LoginFlow} it actually holds (the login-initiation endpoint and the session stage's
     * login seam both reach it), and the step-up coordinator is read for the fallback it was built with.
     * Deleting the key from the declaring descriptor — or the producer no longer passing it — turns
     * {@link #shouldHandDeclaredDefaultToLoginFlowAndStepUp()} red; the omitted-key case is the matched
     * control proving the walk sees the flow at all and that the fallback is {@code /}.
     */
    @Nested
    @DisplayName("Post-login default return URL (oidc.login.default_return_url)")
    class DefaultReturnUrl {

        private static final String CONFIGURED_DEFAULT = "/home";

        @Test
        @DisplayName("Should hand a declared default_return_url to the login flow and the step-up coordinator")
        void shouldHandDeclaredDefaultToLoginFlowAndStepUp() {
            BffRuntime runtime = producer(loginOidc(OidcConfig.Login.builder()
                    .path("/auth/login").defaultReturnUrl(CONFIGURED_DEFAULT).build())).bffRuntime();

            assertLoginFlowsFallBackTo(runtime, CONFIGURED_DEFAULT);
            assertEquals(CONFIGURED_DEFAULT, stepUpFallback(runtime),
                    "the step-up re-drive falls back to the same configured target");
        }

        @Test
        @DisplayName("Should fall back to '/' when default_return_url is omitted (matched control)")
        void shouldFallBackToRootWhenOmitted() {
            BffRuntime runtime = producer(loginOidc(OidcConfig.Login.builder().path("/auth/login").build()))
                    .bffRuntime();

            assertLoginFlowsFallBackTo(runtime, "/");
            assertEquals("/", stepUpFallback(runtime), "an omitted key resolves to '/' on the step-up leg too");
        }

        @Test
        @DisplayName("Should resolve a declared value, an omitted key and an omitted login block")
        void shouldResolveDeclaredAndOmitted() {
            assertAll("declared, key omitted, block omitted",
                    () -> assertEquals(CONFIGURED_DEFAULT, BffRuntimeProducer.defaultReturnUrl(loginOidc(
                            OidcConfig.Login.builder().defaultReturnUrl(CONFIGURED_DEFAULT).build()))),
                    () -> assertEquals("/", BffRuntimeProducer.defaultReturnUrl(loginOidc(
                            OidcConfig.Login.builder().path("/auth/login").build()))),
                    () -> assertEquals("/", BffRuntimeProducer.defaultReturnUrl(loginOidc(null))));
        }

        private void assertLoginFlowsFallBackTo(BffRuntime runtime, String expected) {
            List<LoginFlow> flows = reachableInstancesOf(runtime, LoginFlow.class);

            assertFalse(flows.isEmpty(), "no LoginFlow was reachable from the assembled runtime — this test "
                    + "must never pass vacuously; if the producer's wiring moved, retarget the walk");
            assertAll("every reachable login flow carries the resolved default",
                    flows.stream().map(flow -> (Executable) () -> assertEquals(expected, flow.defaultReturnUrl())));
        }

        private static String stepUpFallback(BffRuntime runtime) {
            StepUpCoordinator coordinator = runtime.stepUpCoordinator();
            assertNotNull(coordinator, "an active runtime exposes the step-up coordinator");
            try {
                Field field = StepUpCoordinator.class.getDeclaredField("defaultReturnUrl");
                field.setAccessible(true);
                return (String) field.get(coordinator);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("StepUpCoordinator no longer holds a defaultReturnUrl field — retarget "
                        + "this read to where the step-up fallback now lives", e);
            }
        }

        private static OidcConfig loginOidc(OidcConfig.@Nullable Login login) {
            return OidcConfig.builder()
                    .issuer(ISSUER)
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .login(login)
                    .build();
        }
    }

    /**
     * Server-mode configuration whose {@code refresh} block declares {@code leeway_seconds} and the
     * supplied {@code enabled} value — {@code null} standing for the key being omitted, which is the
     * case that must resolve the default.
     */
    private static OidcConfig refreshOidc(@Nullable Boolean enabled) {
        return refreshOidc(enabled, null);
    }

    private static OidcConfig refreshOidc(@Nullable Boolean enabled, @Nullable String onFailure) {
        OidcConfig.Session session = OidcConfig.Session.builder()
                .mode("server")
                .ttlSeconds(3600)
                .refresh(OidcConfig.Refresh.builder().enabled(enabled).leewaySeconds(30).onFailure(onFailure).build())
                .build();
        return OidcConfig.builder()
                .issuer(ISSUER)
                .clientId("gateway-client")
                .clientSecret("secret")
                .scopes(List.of("openid"))
                .redirectUri(REDIRECT_URI)
                .session(session)
                .login(OidcConfig.Login.builder().path("/auth/login").build())
                .build();
    }

    /**
     * The switch's two <em>decisions</em>, as opposed to the wiring {@link RefreshSwitch} asserts.
     * <p>
     * {@code RefreshSwitch} can only see that a coordinator is or is not present in the object graph;
     * it cannot see what the seams built around it actually do, because the producer holds them as
     * lambdas that no assembled-runtime test can invoke without a live IdP. The seams — the exchange
     * policy, and the near-expiry and scope-refresh seams with their disabled alternatives — are
     * therefore extracted to package-private factories and driven here directly — with a real
     * store-backed binding and hand-built engine objects, no live token endpoint and no test-double
     * framework, exactly as {@code TokenRefreshCoordinatorTest} drives the coordinator itself.
     * <p>
     * Two of the decisions below are security-relevant rather than cosmetic: dropping the refresh
     * token at login when refresh is off keeps a credential the gateway will never redeem out of the
     * session store (and out of the sealed browser cookie in cookie mode), and mapping a
     * {@code FAILED} refresh to session-ended — while an {@code UNAVAILABLE} one fails only the
     * request — is what stops the stage mediating the pre-refresh token of a destroyed session without
     * clearing the cookie of one that is still live.
     */
    @Nested
    @DisplayName("Refresh seams — the decisions the assembly's lambdas carry")
    class RefreshSeams {

        private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
        private static final Duration LEEWAY = Duration.ofSeconds(60);
        private static final Duration SESSION_TTL = Duration.ofHours(8);
        /** None of these seam decisions reaches a revocation, so the seam is bound inert. */
        private static final TokenRefreshCoordinator.RefreshTokenRevocation NO_REVOCATION = refreshToken -> {
        };
        private static final String OPENID_SCOPE = "openid";
        /** The granted scope set {@code S} of the scope-seam sessions: one scope more than their active set. */
        private static final Set<String> GRANTED_SCOPES = Set.of(OPENID_SCOPE, "orders:read");

        /** The idle timeout equals the absolute lifetime, so it is not in play in these seam decisions. */
        private final InMemorySessionStore store = new InMemorySessionStore(16, SESSION_TTL, Integer.MAX_VALUE,
                sessionId -> {
                });
        private final SessionBinding binding = new ServerSessionBinding(store,
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));

        /** The cookie handle a session of these cases is stored under — derived from, never equal to, its id. */
        private static String handleOf(SessionRecord live) {
            return "handle-of-" + live.sessionId();
        }

        @Test
        @DisplayName("Should hand the engine's exchange through untouched when refresh is enabled")
        void shouldRetainRefreshTokenWhenEnabled() {
            String refreshToken = token();
            AuthorizationCodeFlow.AuthenticationResult exchanged = authenticationResult(refreshToken);

            AuthorizationCodeFlow.AuthenticationResult applied =
                    BffRuntimeProducer.applyRefreshPolicy(exchanged, true);

            assertSame(exchanged, applied, "an enabled refresh must not rebuild the engine's result");
            assertEquals(refreshToken, applied.refreshToken(),
                    "the refresh token is what CallbackEndpoint seeds into the session — without it "
                            + "TokenRefreshCoordinator.refresh returns on its first guard");
        }

        @Test
        @DisplayName("Should drop the refresh token at login when refresh is disabled, keeping both other tokens")
        void shouldDropRefreshTokenWhenDisabled() {
            AuthorizationCodeFlow.AuthenticationResult exchanged = authenticationResult(token());

            AuthorizationCodeFlow.AuthenticationResult applied =
                    BffRuntimeProducer.applyRefreshPolicy(exchanged, false);

            assertAll("a credential the gateway will never redeem never reaches the session binding",
                    () -> assertNull(applied.refreshToken(),
                            "the refresh token must be dropped here, not stored and ignored"),
                    () -> assertSame(exchanged.accessToken(), applied.accessToken(),
                            "the validated access token is carried over untouched"),
                    () -> assertSame(exchanged.idToken(), applied.idToken(),
                            "the validated ID token is carried over untouched"));
        }

        @Test
        @DisplayName("Should yield the session unchanged and no cookies on the disabled refresh seam")
        void shouldYieldSessionUnchangedWhenRefreshDisabled() {
            SessionRecord live = session(token());

            SessionAuthenticationStage.RefreshResult result =
                    BffRuntimeProducer.sessionUnchanged().refreshIfNeeded(live, null, NOW);

            SessionBinding.BoundSession bound = assertInstanceOf(SessionAuthenticationStage.RefreshResult.Mediate.class,
                    result, "turning refresh off is a policy choice — it must not make a live session unauthenticated")
                    .boundSession();
            assertAll("a disabled refresh leaves the session exactly as it was resolved",
                    () -> assertSame(live, bound.session(), "the resolved session is handed back verbatim"),
                    () -> assertTrue(bound.setCookieHeaders().isEmpty(),
                            "an unwired seam re-binds nothing, so it emits no Set-Cookie"));
        }

        @Test
        @DisplayName("Should hand back the current session when the mediated token is not near expiry")
        void shouldYieldCurrentSessionWhenNotNearExpiry() {
            SessionRecord live = storedSession(token());
            AtomicInteger engineCalls = new AtomicInteger();

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer
                    .nearExpiryRefresh(coordinator(NOW.plusSeconds(600), engineCalls))
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertSame(live, mediated(result).session());
            assertEquals(0, engineCalls.get(), "a token outside the leeway must not reach the engine");
        }

        /**
         * The null-refresh-token short circuit sits in {@code TokenRefreshCoordinator.refresh}
         * <em>before</em> the near-expiry check, so a session seeded without a refresh token never
         * refreshes however close to expiry its token is — and the seam reports that as an ordinary
         * authenticated request, which is precisely why the defect this plan was written for was
         * silent. Pinning it here keeps the two halves of the switch honest: whenever the exchange
         * seam drops the token, this is the behaviour the session is left with.
         */
        @Test
        @DisplayName("Should never reach the engine when the session carries no refresh token")
        void shouldShortCircuitWithoutRefreshToken() {
            SessionRecord live = storedSession(null);
            AtomicInteger engineCalls = new AtomicInteger();

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer
                    .nearExpiryRefresh(coordinator(NOW, engineCalls))
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertSame(live, mediated(result).session(), "a session with no refresh token is still authenticated");
            assertEquals(0, engineCalls.get(),
                    "no refresh token means no refresh at all — silently, and regardless of expiry");
        }

        @Test
        @DisplayName("Should carry the rotated session through the seam when the engine refreshes")
        void shouldYieldRotatedSession() {
            SessionRecord live = storedSession(token());
            AtomicInteger engineCalls = new AtomicInteger();
            TokenRefreshCoordinator coordinator = coordinator(NOW, engineCalls);

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer.nearExpiryRefresh(coordinator)
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertEquals(1, engineCalls.get(), "a token inside the leeway drives exactly one engine refresh");
            assertEquals(ROTATED_ACCESS_TOKEN, mediated(result).session().accessToken(),
                    "the stage must mediate from the rotated token, never the pre-refresh one");
        }

        @Test
        @DisplayName("Should end the session when the refresh failed and the session was destroyed")
        void shouldEndSessionOnRefreshFailure() {
            SessionRecord live = storedSession(token());
            TokenRefreshCoordinator rejecting = new TokenRefreshCoordinator(LEEWAY, sessionRecord -> NOW,
                    (refreshToken, _) -> {
                        throw new CredentialRejectedException("Token endpoint rejected the credential with HTTP 400");
                    },
                    binding, NO_REVOCATION, Runnable::run, EndedRefreshTokens.inert());

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer.nearExpiryRefresh(rejecting)
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertInstanceOf(SessionAuthenticationStage.RefreshResult.SessionEnded.class, result,
                    "a FAILED outcome must reach the stage as session-ended so it clears the cookie, rather "
                            + "than mediating the token of a session just destroyed");
        }

        @Test
        @DisplayName("Should mediate the still-valid token when a pre-redemption failure defers the refresh")
        void shouldMediateOnDeferredRefresh() {
            SessionRecord live = storedSession(token());
            TokenRefreshCoordinator unreachable = new TokenRefreshCoordinator(LEEWAY,
                    sessionRecord -> NOW.plusSeconds(30), (refreshToken, _) -> {
                        throw new TransportException("Token endpoint unreachable");
                    },
                    binding, NO_REVOCATION, Runnable::run, EndedRefreshTokens.inert());

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer.nearExpiryRefresh(unreachable)
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertEquals(live.accessToken(), mediated(result).session().accessToken(),
                    "a DEFERRED outcome keeps mediating the access token that has not expired yet");
        }

        @Test
        @DisplayName("Should fail only the request when a pre-redemption failure meets an expired access token")
        void shouldFailRequestOnUnavailableRefresh() {
            SessionRecord live = storedSession(token());
            TokenRefreshCoordinator unreachable = new TokenRefreshCoordinator(LEEWAY, sessionRecord -> NOW,
                    (refreshToken, _) -> {
                        throw new TransportException("Token endpoint unreachable");
                    },
                    binding, NO_REVOCATION, Runnable::run, EndedRefreshTokens.inert());

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer.nearExpiryRefresh(unreachable)
                    .refreshIfNeeded(live, cookieHeader(live), NOW);

            assertAll("UNAVAILABLE keeps the session and fails only this request",
                    () -> assertInstanceOf(SessionAuthenticationStage.RefreshResult.RequestFailed.class, result,
                            "an UNAVAILABLE outcome must not reach the stage as session-ended, which would clear "
                                    + "the cookie of a session that is still live"),
                    () -> assertTrue(binding.resolve(cookieHeader(live), NOW).isPresent(),
                            "the session is still resolvable, so the next request can retry the refresh"));
        }

        @Test
        @DisplayName("Should yield the session unchanged and no cookies on the disabled scope-refresh seam")
        void shouldYieldSessionUnchangedOnDisabledScopeSeam() {
            SessionRecord live = storedScopedSession(token());

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer.scopesUnobtainable()
                    .refreshForScopes(live, cookieHeader(live), GRANTED_SCOPES, NOW);

            SessionBinding.BoundSession bound = mediated(result);
            assertAll("with refresh off no grant can restore a scope, so the stage is left to widen",
                    () -> assertSame(live, bound.session(), "the still under-scoped session is handed back verbatim"),
                    () -> assertTrue(bound.setCookieHeaders().isEmpty(), "an unwired seam re-binds nothing"));
        }

        @Test
        @DisplayName("Should carry the scope-refreshed session through the scope seam, requesting exactly the set it is given")
        void shouldYieldScopeRefreshedSession() {
            SessionRecord live = storedScopedSession(token());
            List<Set<String>> requestedOfEngine = new CopyOnWriteArrayList<>();
            // The access token is ten minutes from expiry — far outside the leeway — so only the scope
            // leg, which ignores the remaining lifetime, can be what reaches the engine.
            TokenRefreshCoordinator coordinator = new TokenRefreshCoordinator(LEEWAY,
                    sessionRecord -> NOW.plusSeconds(600), (refreshToken, scopes) -> {
                        requestedOfEngine.add(Set.copyOf(scopes));
                        return rotation();
                    },
                    binding, NO_REVOCATION, Runnable::run, EndedRefreshTokens.inert());

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer.scopeRefresh(coordinator)
                    .refreshForScopes(live, cookieHeader(live), GRANTED_SCOPES, NOW);

            SessionRecord refreshed = mediated(result).session();
            assertAll("the scope seam drives the coordinator's scope-driven leg",
                    () -> assertEquals(List.of(GRANTED_SCOPES), requestedOfEngine,
                            "exactly one grant, requesting exactly the set the stage handed in"),
                    () -> assertEquals(GRANTED_SCOPES, refreshed.activeScopes(),
                            "the mediated session carries the requested set"),
                    () -> assertEquals(ROTATED_ACCESS_TOKEN, refreshed.accessToken(),
                            "the stage relays the rotated token, never the under-scoped one"));
        }

        @Test
        @DisplayName("Should mediate the kept session when no refresh token can obtain the scope")
        void shouldMediateKeptSessionWithoutRefreshToken() {
            SessionRecord live = storedScopedSession(null);
            AtomicInteger engineCalls = new AtomicInteger();

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer
                    .scopeRefresh(coordinator(NOW.plusSeconds(600), engineCalls))
                    .refreshForScopes(live, cookieHeader(live), GRANTED_SCOPES, NOW);

            assertAll("a SCOPE_REFUSED outcome hands the still-short session back for the stage to widen",
                    () -> assertSame(live, mediated(result).session(), "the session is kept as it was"),
                    () -> assertEquals(0, engineCalls.get(), "nothing can be presented, so the engine is not reached"));
        }

        @Test
        @DisplayName("Should end the session when the identity provider rejects the scope refresh")
        void shouldEndSessionOnRejectedScopeRefresh() {
            SessionRecord live = storedScopedSession(token());
            TokenRefreshCoordinator rejecting = new TokenRefreshCoordinator(LEEWAY,
                    sessionRecord -> NOW.plusSeconds(600), (refreshToken, _) -> {
                        throw new CredentialRejectedException("Token endpoint rejected the credential with HTTP 400");
                    },
                    binding, NO_REVOCATION, Runnable::run, EndedRefreshTokens.inert());

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer.scopeRefresh(rejecting)
                    .refreshForScopes(live, cookieHeader(live), GRANTED_SCOPES, NOW);

            assertInstanceOf(SessionAuthenticationStage.RefreshResult.SessionEnded.class, result,
                    "a FAILED scope refresh must reach the stage as session-ended, exactly as on the near-expiry leg");
        }

        @Test
        @DisplayName("Should fail only the request when the scope refresh is unavailable and the access token has expired")
        void shouldFailRequestOnUnavailableScopeRefresh() {
            SessionRecord live = storedScopedSession(token());
            TokenRefreshCoordinator unreachable = new TokenRefreshCoordinator(LEEWAY, sessionRecord -> NOW,
                    (refreshToken, _) -> {
                        throw new TransportException("Token endpoint unreachable");
                    },
                    binding, NO_REVOCATION, Runnable::run, EndedRefreshTokens.inert());

            SessionAuthenticationStage.RefreshResult result = BffRuntimeProducer.scopeRefresh(unreachable)
                    .refreshForScopes(live, cookieHeader(live), GRANTED_SCOPES, NOW);

            assertAll("UNAVAILABLE keeps the session and fails only this request on the scope leg too",
                    () -> assertInstanceOf(SessionAuthenticationStage.RefreshResult.RequestFailed.class, result,
                            "an UNAVAILABLE scope refresh must not clear a live session's cookie"),
                    () -> assertTrue(binding.resolve(cookieHeader(live), NOW).isPresent(),
                            "the session is still resolvable"));
        }

        /** A stored session whose active scope set lacks a scope its granted set holds — the scope-refresh case. */
        private SessionRecord storedScopedSession(@Nullable String refreshToken) {
            SessionRecord live = SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken(token())
                    .refreshToken(refreshToken)
                    .idToken(token())
                    .sub(SUBJECT)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .activeScopes(Set.of(OPENID_SCOPE))
                    .grantedScopes(GRANTED_SCOPES)
                    .build();
            store.create(live, handleOf(live), NOW);
            return live;
        }

        private SessionBinding.BoundSession mediated(SessionAuthenticationStage.RefreshResult result) {
            return assertInstanceOf(SessionAuthenticationStage.RefreshResult.Mediate.class, result,
                    "the seam must mediate this outcome").boundSession();
        }

        private TokenRefreshCoordinator coordinator(Instant accessTokenExpiry, AtomicInteger engineCalls) {
            return new TokenRefreshCoordinator(LEEWAY, sessionRecord -> accessTokenExpiry,
                    (refreshToken, _) -> {
                        engineCalls.incrementAndGet();
                        return rotation();
                    },
                    binding, NO_REVOCATION, Runnable::run, EndedRefreshTokens.inert());
        }

        private SessionRecord storedSession(@Nullable String refreshToken) {
            SessionRecord live = session(refreshToken);
            store.create(live, handleOf(live), NOW);
            return live;
        }

        private static String cookieHeader(SessionRecord live) {
            return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + handleOf(live);
        }

        private static SessionRecord session(@Nullable String refreshToken) {
            return SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken(token())
                    .refreshToken(refreshToken)
                    .idToken(token())
                    .sub(SUBJECT)
                    .expiresAt(NOW.plus(SESSION_TTL))
                    .build();
        }

        private static AuthorizationCodeFlow.AuthenticationResult authenticationResult(String refreshToken) {
            Map<String, ClaimValue> accessClaims = new HashMap<>();
            accessClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
            Map<String, ClaimValue> idClaims = new HashMap<>();
            idClaims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
            return new AuthorizationCodeFlow.AuthenticationResult(new AccessTokenContent(accessClaims, token()),
                    new IdTokenContent(idClaims, token()), refreshToken);
        }

        /**
         * The rotation the engine seam returns. {@code grantedScope} is {@code null} and
         * {@code scopeDelta} {@code UNDECLARED} — the "the IdP declared no scope on the refresh
         * response" pair — because nothing on the path under test reads either component; picking
         * {@code EQUAL} would assert a scope comparison this fixture never performs.
         */
        private static RotationResult rotation() {
            Map<String, ClaimValue> claims = new HashMap<>();
            claims.put(ClaimName.SUBJECT.getName(), ClaimValue.forPlainString(SUBJECT));
            return new RotationResult(new AccessTokenContent(claims, ROTATED_ACCESS_TOKEN), token(), token(),
                    300L, true, null, RotationResult.ScopeDelta.UNDECLARED);
        }
    }

    /** Opaque token material — no production code under test parses it, so any non-blank value serves. */
    private static String token() {
        return Generators.letterStrings(16, 32).next();
    }

    @Nested
    @DisplayName("Inert bearer-only runtime")
    class Inert {

        @Test
        @DisplayName("Should stay inert when no oidc block is configured")
        void shouldBeInertWithoutOidc() {
            BffRuntime runtime = producer(null).bffRuntime();
            assertFalse(runtime.isActive());
        }

        @Test
        @DisplayName("Should stay inert for an unrecognised session mode")
        void shouldBeInertForUnrecognisedMode() {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(ISSUER)
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("stateless").build())
                    .build();
            assertFalse(producer(oidc).bffRuntime().isActive());
        }

        @Test
        @DisplayName("Should stay inert when a mode is set but no redirect_uri is configured")
        void shouldBeInertWithoutRedirectUri() {
            OidcConfig serverNoRedirect = OidcConfig.builder()
                    .issuer(ISSUER)
                    .session(OidcConfig.Session.builder().mode("server").build())
                    .build();
            OidcConfig cookieNoRedirect = OidcConfig.builder()
                    .issuer(ISSUER)
                    .session(OidcConfig.Session.builder().mode("cookie").build())
                    .build();
            assertFalse(producer(serverNoRedirect).bffRuntime().isActive());
            assertFalse(producer(cookieNoRedirect).bffRuntime().isActive());
        }

        @Test
        @DisplayName("Should reject reserved dispatch and session-stage access on the inert runtime")
        void shouldRejectUseOfInert() {
            BffRuntime runtime = BffRuntime.inert();
            BffRuntime.ReservedHttpRequest request =
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, "GET");
            Instant now = Instant.now();
            assertThrows(IllegalStateException.class, runtime::sessionStage);
            assertThrows(IllegalStateException.class,
                    () -> runtime.dispatch(ReservedEndpoint.USER_INFO, request, now));
        }
    }

    /**
     * The {@code egress_tls.oidc_verify_hostname} / {@code egress_tls.oidc_tls_profile} reader on the BFF
     * OIDC back-channel, asserted <em>behaviourally</em> against a real TLS dial — the sibling of
     * {@code TokenValidatorProducerTest.JwksVerifyHostname}.
     * <p>
     * <strong>Why a real server rather than a getter assertion.</strong> {@code isVerifyHostname()} on the
     * built configuration would prove the value was <em>carried</em>, never that it <em>acts</em>. So
     * discovery is dialled against {@link SanMismatchedJwksServer}, whose certificate chains to an
     * installed anchor but names the wrong host. The configuration is always the one the producer
     * builds through its {@code backChannelConfiguration} seam — the same call {@code build} makes — and
     * never one constructed here.
     * <p>
     * <strong>The falsification check.</strong> Deleting the {@code .verifyHostname(...)} call from
     * {@link BffRuntimeProducer#backChannelConfiguration} turns the relaxed leg of
     * {@link #hostnameVerificationGatesDiscovery()} red: token-sheriff's own default verifies, so without
     * the call the relaxed dial fails exactly like the strict one. Deleting the pre-builder collision
     * refusal turns {@link #relaxedHostnameWithTlsProfileIsRefusedAtBoot()} red, because the failure then
     * surfaces as the library's {@link IllegalArgumentException} rather than {@code CONFIG_INVALID}.
     */
    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @EnableTestLogger
    @DisplayName("egress_tls.oidc_verify_hostname / oidc_tls_profile — the BFF OIDC back-channel posture")
    class OidcBackChannelTls {

        private static final String PROFILE = "corporate-idp";
        private static final String EMPTY_JWKS = "{\"keys\":[]}";

        private Path fixtureDir;
        private SanMismatchedJwksServer server;

        @BeforeAll
        void startFixtureServer() throws Exception {
            fixtureDir = Files.createTempDirectory("san-mismatched-oidc");
            server = SanMismatchedJwksServer.start(fixtureDir, EMPTY_JWKS);
        }

        @AfterAll
        void stopFixtureServer() throws IOException {
            if (server != null) {
                server.close();
            }
            if (fixtureDir != null) {
                try (Stream<Path> entries = Files.walk(fixtureDir)) {
                    entries.sorted(Comparator.reverseOrder()).forEach(BffRuntimeProducerTest::deleteQuietly);
                }
            }
        }

        @Test
        @DisplayName("the flag decides a real dial: verifying refuses the SAN-mismatched IdP, relaxed completes discovery")
        void hostnameVerificationGatesDiscovery() {
            DiscoveryResolver verifying = new DiscoveryResolver(backChannelFor(server, EgressTlsConfig.defaults()));
            DiscoveryResolver relaxed = new DiscoveryResolver(backChannelFor(server, oidcHostname(false)));

            assertThrows(TransportException.class, verifying::resolve,
                    "with oidc_verify_hostname true discovery must fail against a certificate that does "
                            + "not name the dialled host — chain trust succeeds, so nothing else is left to fail on");
            ProviderMetadata metadata = assertDoesNotThrow(relaxed::resolve,
                    "with oidc_verify_hostname false the same dial must complete — every other input is identical");
            assertEquals(Optional.of(server.issuer()), metadata.getIssuer(),
                    "the discovery document was fetched over the relaxed dial");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    ConfigLogMessages.WARN.OIDC_HOSTNAME_VERIFICATION_DISABLED.resolveIdentifierString());
        }

        @Test
        @DisplayName("an omitted egress_tls block resolves to verification ON on the BFF back-channel")
        void omittedBlockVerifiesHostname() {
            DiscoveryResolver omitted = new DiscoveryResolver(backChannelFor(server, null));
            DiscoveryResolver relaxed = new DiscoveryResolver(backChannelFor(server, oidcHostname(false)));

            assertThrows(TransportException.class, omitted::resolve,
                    "an absent egress_tls block must verify the hostname, not silently relax it");
            assertDoesNotThrow(relaxed::resolve,
                    "the control must reach the same server, or the refusal above proves nothing about the omitted block");
        }

        @Test
        @DisplayName("with the flag false an UNTRUSTED IdP chain is still refused — the relaxation is not a TLS disable")
        void relaxedHostnameStillRefusesAnUntrustedChain() throws Exception {
            try (SanMismatchedJwksServer untrusted = SanMismatchedJwksServer.startUntrusted(EMPTY_JWKS)) {
                DiscoveryResolver relaxed = new DiscoveryResolver(backChannelFor(untrusted, oidcHostname(false)));

                assertThrows(TransportException.class, relaxed::resolve,
                        "oidc_verify_hostname false must relax hostname matching ONLY — an identity provider "
                                + "whose certificate names the dialled host but does not chain to a trusted "
                                + "anchor must still be refused");
            }
        }

        @Test
        @DisplayName("oidc_verify_hostname false collides with a named oidc_tls_profile and is refused at boot")
        void relaxedHostnameWithTlsProfileIsRefusedAtBoot() {
            BffRuntimeProducer producer = producer(serverModeOidc(),
                    new EgressTlsConfig(true, true, null, false, PROFILE), TestTlsConfigurationRegistry.with(PROFILE));

            GatewayException thrown = assertThrows(GatewayException.class, producer::bffRuntime);

            assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
            String message = thrown.getMessage();
            assertAll("the refusal names both gateway keys and the profile, in the gateway's own vocabulary",
                    () -> assertTrue(message.contains("egress_tls.oidc_tls_profile") && message.contains(PROFILE),
                            "the refusal must name the profile key and the profile: " + message),
                    () -> assertTrue(message.contains("egress_tls.oidc_verify_hostname"),
                            "the refusal must name the hostname key that collided: " + message));
        }

        @Test
        @DisplayName("the same false flag without a profile assembles cleanly (matched control)")
        void relaxedHostnameWithoutTlsProfileAssembles() {
            BffRuntimeProducer producer = producer(serverModeOidc(), oidcHostname(false),
                    TestTlsConfigurationRegistry.with(PROFILE));

            assertTrue(assertDoesNotThrow(producer::bffRuntime).isActive(),
                    "oidc_verify_hostname false is a legitimate posture on its own; only the collision is refused");
        }

        @Test
        @DisplayName("a named oidc_tls_profile supplies its anchors to the back-channel and is reported at boot")
        void namedProfileIsAppliedAndReported() {
            TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
            OidcConfig oidc = serverModeOidc();

            ClientConfiguration configuration = assembledBackChannel(producer(oidc,
                    new EgressTlsConfig(true, true, null, true, PROFILE), registry), oidc, oidc.scopes());

            assertSame(registry.profileContext(), configuration.getSslContext(),
                    "a named oidc_tls_profile must put exactly its own trust anchors on the back-channel");
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    ConfigLogMessages.WARN.OIDC_TRUST_PROFILE_IN_EFFECT.resolveIdentifierString());
        }

        @Test
        @DisplayName("an omitted oidc_tls_profile leaves the back-channel on the JVM default trust store (matched control)")
        void omittedProfileKeepsDefaultTrust() {
            OidcConfig oidc = serverModeOidc();

            ClientConfiguration configuration = assembledBackChannel(producer(oidc, EgressTlsConfig.defaults(),
                    TestTlsConfigurationRegistry.empty()), oidc, oidc.scopes());

            assertNull(configuration.getSslContext(),
                    "without a profile no caller context is set, so the resolver — which would refuse the "
                            + "empty registry — is never consulted");
        }

        @Test
        @DisplayName("an unbound oidc_tls_profile fails the producer rather than falling back to default trust")
        void unboundProfileFailsTheProducer() {
            BffRuntimeProducer producer = producer(serverModeOidc(),
                    new EgressTlsConfig(true, true, null, true, PROFILE), TestTlsConfigurationRegistry.empty());

            GatewayException thrown = assertThrows(GatewayException.class, producer::bffRuntime);

            assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
            assertTrue(thrown.getMessage().contains("egress_tls.oidc_tls_profile"),
                    "the refusal must name the gateway key that declared the profile: " + thrown.getMessage());
        }

        @Test
        @DisplayName("every scoped configuration carries the base configuration's pinned hostname and trust posture")
        void scopedConfigurationsCarryTheBasePosture() {
            TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
            OidcConfig oidc = serverModeOidc();
            BffRuntimeProducer profiled = producer(oidc, new EgressTlsConfig(true, true, null, true, PROFILE), registry);
            BffRuntimeProducer relaxed = producer(oidc, oidcHostname(false), TestTlsConfigurationRegistry.empty());
            List<String> scoped = List.of("openid", SCOPED_ENDPOINT_SCOPE);

            ClientConfiguration profiledBase = profiled.backChannelConfiguration(oidc, oidc.scopes());
            ClientConfiguration profiledScoped = profiled.backChannelConfiguration(oidc, scoped);
            ClientConfiguration relaxedBase = relaxed.backChannelConfiguration(oidc, oidc.scopes());
            ClientConfiguration relaxedScoped = relaxed.backChannelConfiguration(oidc, scoped);

            assertAll("the scope list is the only thing a scoped variant changes",
                    () -> assertEquals(scoped, profiledScoped.getScopes(), "the variant carries the requested scopes"),
                    () -> assertEquals(oidc.scopes(), profiledBase.getScopes(), "the base carries oidc.scopes"),
                    () -> assertSame(registry.profileContext(), profiledScoped.getSslContext(),
                            "a scoped variant dials with the profile's own trust anchors"),
                    () -> assertEquals(profiledBase.isVerifyHostname(), profiledScoped.isVerifyHostname()),
                    () -> assertFalse(relaxedScoped.isVerifyHostname(),
                            "a relaxed hostname posture reaches every scoped variant too"),
                    () -> assertEquals(relaxedBase.isVerifyHostname(), relaxedScoped.isVerifyHostname()),
                    () -> assertNull(relaxedScoped.getSslContext(), "no profile, no caller context, on every variant"));
        }

        @Test
        @DisplayName("the hostname/profile collision is refused for a scoped variant as well as at boot")
        void scopedVariantRefusesTheCollision() {
            OidcConfig oidc = serverModeOidc();
            BffRuntimeProducer colliding = producer(oidc, new EgressTlsConfig(true, true, null, false, PROFILE),
                    TestTlsConfigurationRegistry.with(PROFILE));
            List<String> scoped = List.of("openid", SCOPED_ENDPOINT_SCOPE);

            GatewayException thrown = assertThrows(GatewayException.class,
                    () -> colliding.backChannelConfiguration(oidc, scoped));

            assertEquals(EventType.CONFIG_INVALID, thrown.getEventType(),
                    "a scoped variant can never be built on the posture boot refuses");
            assertThrows(GatewayException.class, colliding::bffRuntime, "and boot itself still refuses it");
        }

        /**
         * The fixture's discovery document names no {@code pushed_authorization_request_endpoint}, and
         * the runtime has no mode without the push (ADR-0058). The login is therefore refused before a
         * redirect is built — and before the pending authorization is stored, which the walk to the
         * runtime's own pending store shows.
         */
        @Test
        @DisplayName("a login against a provider that advertises no pushed-authorization-request endpoint is refused with the 502 event")
        void loginAgainstAProviderWithoutPushedRequestEndpointIsRefused() {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(server.issuer())
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .login(OidcConfig.Login.builder().path("/auth/login").build())
                    .build();
            BffRuntime runtime = producer(oidc, oidcHostname(false), TestTlsConfigurationRegistry.empty()).bffRuntime();
            BffRuntime.ReservedHttpRequest login =
                    new BffRuntime.ReservedHttpRequest("", null, null, "/", null, null, "GET");
            Instant now = Instant.parse("2026-07-25T10:00:00Z");

            GatewayException refused = assertThrows(GatewayException.class,
                    () -> runtime.dispatch(ReservedEndpoint.LOGIN, login, now));

            PendingAuthorizationStore.InMemory pendingStore = single(
                    reachableInstancesOf(runtime, PendingAuthorizationStore.InMemory.class), "pending store");
            assertAll("a login against a provider without a pushed-authorization-request endpoint",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, refused.getEventType()),
                    () -> assertEquals(502, refused.getEventType().httpStatus(), "the edge answers 502"),
                    () -> assertTrue(String.valueOf(refused.getMessage()).contains("no-par-endpoint"),
                            "the refusal names its reason: " + refused.getMessage()),
                    () -> assertEquals(0, pendingStore.size(), "nothing is stored for a login that was never started"));
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    BffLogMessages.WARN.AUTHORIZATION_PUSH_REFUSED.resolveIdentifierString());
        }

        @Test
        @DisplayName("the pushed-authorization-request client dials with the base configuration when oidc_verify_hostname is false")
        void pushedRequestClientRidesTheBaseConfigurationWithARelaxedHostname() {
            assertPushedRequestClientRidesTheBaseConfiguration(oidcHostname(false), false);
        }

        @Test
        @DisplayName("the pushed-authorization-request client dials with the base configuration at the default hostname posture")
        void pushedRequestClientRidesTheBaseConfigurationAtTheDefaultPosture() {
            assertPushedRequestClientRidesTheBaseConfiguration(null, true);
        }

        /**
         * The pushed authorization request has to carry the hostname and trust posture of every other
         * back-channel leg (ADR-0045), which it does by riding the base back-channel configuration.
         * The assertion is type-directed, in the style of the query-mode builder walk: it collects the
         * one {@link ParClient} reachable from the assembled runtime, then the one
         * {@link ClientConfiguration} reachable from that client, and requires it to be the very
         * instance the producer built as its base configuration. No engine field is named, so an
         * engine field rename does not break it; a client built over any other configuration does.
         *
         * @param egressTls        the {@code egress_tls} block, {@code null} for an omitted block
         * @param verifiesHostname the hostname posture the base configuration must then carry
         */
        private void assertPushedRequestClientRidesTheBaseConfiguration(@Nullable EgressTlsConfig egressTls,
                boolean verifiesHostname) {
            OidcConfig oidc = serverModeOidc();
            RecordingProducer recording = new RecordingProducer(
                    GatewayConfig.builder().version(1).oidc(oidc).egressTls(egressTls).build(), tokenValidator,
                    logoutTokenVerifier);

            BffRuntime runtime = recording.bffRuntime();

            ParClient parClient = single(reachableInstancesOf(runtime, ParClient.class),
                    "pushed-authorization-request client");
            ClientConfiguration dialled = single(reachableInstancesOf(parClient, ClientConfiguration.class),
                    "client configuration the pushed-authorization-request client holds");
            assertAll("the configuration the push dials the identity provider with",
                    () -> assertEquals(List.of(oidc.scopes()), recording.requested,
                            "assembly builds the base configuration and no scoped variant"),
                    () -> assertEquals(1, recording.built.size(), "so exactly one configuration exists to compare with"),
                    () -> assertSame(recording.built.getFirst(), dialled,
                            "the push rides the base back-channel configuration itself, not a copy or a variant"),
                    () -> assertEquals(verifiesHostname, dialled.isVerifyHostname(),
                            "and so carries the hostname posture of every other back-channel leg"));
        }

        /**
         * The widening counterpart of
         * {@link #loginAgainstAProviderWithoutPushedRequestEndpointIsRefused()}: a session widening has
         * no mode without the push either (ADR-0058). The fixture's discovery document names no
         * {@code pushed_authorization_request_endpoint}, so the navigation of a live session that lacks a
         * needed scope is refused before a redirect is built and before the widening's pending
         * authorization is stored — it is not sent to the identity provider with its parameters in the
         * URL. What a widening pushes when the provider does offer the endpoint is asserted against the
         * stub identity provider, in {@code StubIdentityProviderRuntime}.
         */
        @Test
        @DisplayName("a session widening against a provider that advertises no pushed-authorization-request endpoint is refused with the 502 event")
        void wideningAgainstAProviderWithoutPushedRequestEndpointIsRefused() {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(server.issuer())
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .build();
            BffRuntime runtime = producer(oidc, oidcHostname(false), TestTlsConfigurationRegistry.empty()).bffRuntime();
            String cookieHeader = bindLiveSession(runtime, Set.of("openid", "profile"));
            PipelineRequest request = sessionRouteRequest(cookieHeader, "text/html",
                    Set.of("openid", SCOPED_ENDPOINT_SCOPE));
            SessionAuthenticationStage stage = runtime.sessionStage();

            GatewayException refused = assertThrows(GatewayException.class, () -> stage.process(request));

            PendingAuthorizationStore.InMemory pendingStore = single(
                    reachableInstancesOf(runtime, PendingAuthorizationStore.InMemory.class), "pending store");
            assertAll("a widening against a provider without a pushed-authorization-request endpoint",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, refused.getEventType()),
                    () -> assertEquals(502, refused.getEventType().httpStatus(), "the edge answers 502"),
                    () -> assertTrue(String.valueOf(refused.getMessage()).contains("no-par-endpoint"),
                            "the refusal names its reason: " + refused.getMessage()),
                    () -> assertEquals(0, pendingStore.size(), "nothing is stored for a widening that was never started"),
                    () -> assertNull(request.responseHeaders().get("Location"),
                            "and the browser is not redirected — there is no front-channel fall-back"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(),
                            "the under-scoped session's token is never recorded for the upstream"));
            LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                    BffLogMessages.WARN.AUTHORIZATION_PUSH_REFUSED.resolveIdentifierString());
        }

        /**
         * The refresh binding the producer hands the coordinator is driven directly, against the
         * fixture's discovery document, and the back-channel configuration factory is recorded: the
         * engine sends exactly the {@code scope} of the configuration it is driven over, so the scope
         * list that configuration is built for IS the refresh grant's {@code scope}
         * ({@code ScopedEngineFlowsTest} proves that last step on the wire). The fixture serves no token
         * endpoint, so the grant itself is refused — the factory call happens before the post.
         */
        @Test
        @DisplayName("the assembled refresh binding requests exactly the set it is given, never the static oidc.scopes")
        void refreshBindingRequestsTheSetItIsGiven() {
            RecordingProducer recording = recordingProducer();
            TokenRefreshCoordinator coordinator = single(
                    reachableInstancesOf(recording.bffRuntime(), TokenRefreshCoordinator.class), "refresh coordinator");
            TokenRefreshCoordinator.RefreshExchange exchange = single(
                    reachableInstancesOf(coordinator, TokenRefreshCoordinator.RefreshExchange.class),
                    "refresh exchange the coordinator holds");
            Set<String> activeScopes = Set.of("openid", "profile", "email", SCOPED_ENDPOINT_SCOPE);
            String refreshToken = token();
            recording.requested.clear();

            assertThrows(RuntimeException.class, () -> exchange.exchange(refreshToken, activeScopes),
                    "the fixture serves no token endpoint, so the grant is refused after the configuration is built");

            assertEquals(List.of(List.of("email", "openid", SCOPED_ENDPOINT_SCOPE, "profile")), recording.requested,
                    "the refresh rides a configuration built for exactly A (canonical order) — the pre-change "
                            + "binding drove a flow over the base configuration and asked the factory for nothing");
            assertFalse(recording.requested.contains(List.of("openid")),
                    "the static oidc.scopes are never what a refresh requests");
        }

        private RecordingProducer recordingProducer() {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(server.issuer())
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .build();
            GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(oidc)
                    .egressTls(oidcHostname(false)).build();
            return new RecordingProducer(gatewayConfig, tokenValidator, logoutTokenVerifier);
        }

        private ClientConfiguration backChannelFor(SanMismatchedJwksServer target, @Nullable EgressTlsConfig egressTls) {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(target.issuer())
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .build();
            return assembledBackChannel(producer(oidc, egressTls, TestTlsConfigurationRegistry.empty()), oidc,
                    oidc.scopes());
        }

        private static EgressTlsConfig oidcHostname(boolean verify) {
            return new EgressTlsConfig(true, true, null, verify, null);
        }
    }

    /**
     * A producer that records every scope list its back-channel configuration factory is asked for and
     * every configuration it then yields, building each exactly as the production method does. Only
     * the recording is added.
     * {@link Vetoed} because {@code @ApplicationScoped} is inherited: without it the test-class index a
     * {@code @QuarkusTest} run builds would see a second producer bean.
     */
    @Vetoed
    private static final class RecordingProducer extends BffRuntimeProducer {

        private final List<List<String>> requested = new CopyOnWriteArrayList<>();
        /** The configurations the factory built, in call order — one per entry of {@link #requested}. */
        private final List<ClientConfiguration> built = new CopyOnWriteArrayList<>();

        RecordingProducer(GatewayConfig gatewayConfig, TokenValidator tokenValidator,
                SignatureOnlyTokenVerifier logoutTokenVerifier) {
            super(gatewayConfig, new RouteTable(List.of()), new SingletonInstance<>(tokenValidator),
                    new SingletonInstance<>(logoutTokenVerifier),
                    new JwksTrustProfileResolver(TestTlsConfigurationRegistry.empty()), REVOCATION_EXECUTOR,
                    new GatewayJson(new ObjectMapper()), new RecordingTimers().vertx());
        }

        @Override
        ClientConfiguration backChannelConfiguration(OidcConfig oidc, List<String> scopes) {
            requested.add(List.copyOf(scopes));
            ClientConfiguration configuration = super.backChannelConfiguration(oidc, scopes);
            built.add(configuration);
            return configuration;
        }
    }

    /**
     * Deletes one entry of the SAN-mismatch fixture's temp tree, surfacing a failed cleanup rather than
     * swallowing it.
     *
     * @param path the entry to delete
     */
    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not clean up the SAN-mismatch fixture at " + path, e);
        }
    }

    /**
     * Tests that drive a runtime the producer built against {@link StubIdentityProvider}, so that what
     * is asserted is the request an identity provider actually receives rather than a value carried on
     * a configuration object.
     * <p>
     * <strong>The runtime reaches the stub through configuration alone</strong> — the route a deployment
     * with a private-CA identity provider takes. {@code oidc.issuer} is the stub's issuer,
     * {@code egress_tls.oidc_tls_profile} names a profile the test registry binds to the stub's root
     * certificate, and {@code egress_tls.oidc_verify_hostname} stays at its default. Nothing is relaxed
     * to get there, and {@link #discoveryReachesTheStubThroughTheNamedProfile()} with its two matched
     * controls is what shows it: the same dial is refused without the profile, and a plain-HTTP issuer
     * is refused before anything is sent.
     * <p>
     * <strong>The four back-channel legs that present the client credential.</strong> The pushed
     * authorization request is driven through the runtime's own reserved login dispatch, and the code
     * exchange through that login followed by the callback dispatch; the refresh grant and the
     * revocation through the two seams the assembled refresh coordinator holds. Unless a test scripts
     * an answer, the stub accepts every push and its token endpoint refuses every grant, so each token
     * leg ends in a refusal and the assertion is made on the request the stub recorded.
     * <p>
     * <strong>The pushed authorization request.</strong> A login redirect carries {@code client_id} and
     * {@code request_uri} and nothing else, so nothing else about the authorization request can be read
     * from it. Whatever a test asserts about that request — its scope set, its {@code state} — is read
     * from the form body the stub's pushed-authorization-request endpoint recorded.
     * <p>
     * <strong>The sender constraint.</strong> Every token request carries a DPoP proof, and the tests of
     * the binding check script the token endpoint's answer: a success answer that is not bound to the
     * proof key is refused by the runtime, and one that is bound is accepted.
     * <p>
     * <strong>What the captured records cover.</strong> The assertion that no record carries the client
     * secret reads every captured record down to {@code DEBUG}. The root level alone does not open the
     * loggers of the gateway and of the token library for that — see {@link SheriffDebugCapture} — so
     * the extension is registered here, and the assertion first proves that a {@code DEBUG} record of
     * the loggers on the back-channel legs is captured.
     */
    @Nested
    @EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
    @ExtendWith(SheriffDebugCapture.class)
    @DisplayName("Produced runtime against the stub identity provider")
    class StubIdentityProviderRuntime {

        private static final String PROFILE = "stub-idp";
        private static final String CLIENT_ID = "gateway-client";
        private static final Instant NOW = Instant.parse("2026-07-25T10:00:00Z");
        private static final String CLIENT_ASSERTION = "client_assertion";
        private static final String CLIENT_ASSERTION_TYPE = "client_assertion_type";
        private static final String JWT_BEARER_ASSERTION = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
        private static final String CLIENT_SECRET_PARAMETER = "client_secret";
        private static final String AUTHORIZATION = "Authorization";
        private static final String BASIC_SCHEME = "Basic ";
        private static final String KEY_FILE_FIELD = "oidc.client_authentication.key_file";
        private static final String SENDER_CONSTRAINT_KEY_FILE_FIELD = "oidc.sender_constraint.key_file";
        private static final String GENERATED_CLIENT_AUTHENTICATION_KEY =
                "Signing key for client-authentication generated at startup";
        private static final String DPOP_HEADER = "DPoP";
        private static final String DPOP_NONCE_HEADER = "DPoP-Nonce";
        private static final String TYPE_DPOP = "DPoP";
        private static final String TYPE_BEARER = "Bearer";
        /** An RFC 7638 SHA-256 thumbprint: 32 bytes, base64url without padding. */
        private static final String THUMBPRINT_SHAPE = "[A-Za-z0-9_-]{43}";
        private static final String EC_KEY_TYPE = "EC";
        /** The private members of an RSA or EC JWK — none of which the client JWKS path may publish. */
        private static final List<String> PRIVATE_JWK_MEMBERS = List.of("d", "p", "q", "dp", "dq", "qi", "oth");
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String SCOPED_ROUTE_PREFIX = "/orders";
        /** {@code oidc.scopes ∪ endpoint.scopes} for the scoped session route. */
        private static final Set<String> SCOPED_NEEDED_SCOPES = Set.of("openid", SCOPED_ENDPOINT_SCOPE);
        private static final String OTHER_SCOPED_ROUTE_PREFIX = "/invoices";
        /** The needed scopes of a second scoped session route, differing from the first in one scope. */
        private static final Set<String> OTHER_SCOPED_NEEDED_SCOPES = Set.of("openid", "invoices:read");
        private static final String PARAM_CLIENT_ID = "client_id";
        private static final String PARAM_REQUEST_URI = "request_uri";
        private static final String PARAM_STATE = "state";
        /** The parameter names of a pushed-request redirect, in the order the runtime renders them. */
        private static final List<String> PUSHED_REDIRECT_PARAMETERS = List.of(PARAM_CLIENT_ID, PARAM_REQUEST_URI);
        private static final String REQUEST_URI_PREFIX = "urn:ietf:params:oauth:request_uri:";
        private static final String STEP_UP_ACR = "urn:example:gold";
        private static final int STEP_UP_MAX_AGE = 300;

        @TempDir
        Path keyDirectory;

        private StubIdentityProvider stub;

        @BeforeEach
        void startStub() throws IOException {
            stub = StubIdentityProvider.start();
        }

        @AfterEach
        void stopStub() {
            stub.close();
        }

        /** The three ways a signing key of the client is resolved, each with the algorithm it signs. */
        enum KeyMode {

            PROVIDED_EC("ES256", TestSigningKeys::ecKeyPair),

            PROVIDED_RSA("PS256", TestSigningKeys::rsaKeyPair),

            GENERATED("ES256", null);

            private final String algorithm;
            private final @Nullable Supplier<KeyPair> providedKey;

            KeyMode(String algorithm, @Nullable Supplier<KeyPair> providedKey) {
                this.algorithm = algorithm;
                this.providedKey = providedKey;
            }
        }

        /** The four back-channel legs that present the client credential. */
        enum Leg {
            PUSHED_REQUEST, CODE_EXCHANGE, REFRESH_GRANT, REVOCATION
        }

        /** Three key files the producer must refuse, each written the way an operator gets it wrong. */
        enum RefusedKeyFile {

            MISMATCHED_HALVES {
            @Override
            Path write(Path directory) {
                return TestSigningKeys.writeHalves(directory, TestSigningKeys.ecKeyPair().getPrivate(),
                        TestSigningKeys.ecKeyPair().getPublic());
            }
        },

            ENCRYPTED_BLOCK {
                @Override
                Path write(Path directory) {
                    return TestSigningKeys.writeRelabelledPrivateBlock(directory, TestSigningKeys.rsaKeyPair(),
                            "ENCRYPTED PRIVATE KEY");
                }
            },

            UNDERSIZED_RSA {
                @Override
                Path write(Path directory) {
                    return TestSigningKeys.writeKeyFile(directory, TestSigningKeys.undersizedRsaKeyPair());
                }
            };

            abstract Path write(Path directory);
        }

        /**
         * A key-mode configuration together with the key id its client assertion must carry — known for
         * a provided key, whose thumbprint the test computes itself, and unknown for a generated one.
         */
        private record KeyFixture(OidcConfig oidc, Optional<String> expectedKeyId) {
        }

        /** A client-secret-mode configuration together with the two values its credential is built from. */
        private record SecretFixture(OidcConfig oidc, String clientId, String secret) {

            /** The credential as RFC 6749 section 2.3.1 renders it: both halves form-encoded, then joined. */
            String formEncodedCredential() {
                return URLEncoder.encode(clientId, StandardCharsets.UTF_8) + ":"
                        + URLEncoder.encode(secret, StandardCharsets.UTF_8);
            }

            String basicCredential() {
                return Base64.getEncoder().encodeToString(formEncodedCredential().getBytes(StandardCharsets.UTF_8));
            }
        }

        /**
         * A client-secret-mode configuration whose {@code sender_constraint} block follows a
         * {@link KeyMode}, together with the proof-key thumbprint every proof must then carry — known for
         * a provided key and unknown for a generated one.
         */
        private record SecretKeyFixture(SecretFixture credential, Optional<String> expectedProofKey) {
        }

        /**
         * A widening brought to its callback: the request the widening pushed, the authorization code
         * the callback presented, the code exchange the token endpoint recorded for it and the
         * callback's answer.
         */
        private record WideningCallback(StubIdentityProvider.ReceivedRequest pushed, String code,
        StubIdentityProvider.ReceivedRequest exchange, BffRuntime.ReservedHttpResponse answered) {
        }

        static Stream<Arguments> keyModesOnEveryLeg() {
            return Stream.of(KeyMode.values())
                    .flatMap(mode -> Stream.of(Leg.values()).map(leg -> Arguments.of(mode, leg)));
        }

        @Test
        @DisplayName("Should complete discovery against the stub when the trust profile is named")
        void discoveryReachesTheStubThroughTheNamedProfile() {
            OidcConfig oidc = stubOidc().build();
            ClientConfiguration configuration = assembledBackChannel(stubProducer(oidc), oidc, oidc.scopes());

            ProviderMetadata metadata = assertDoesNotThrow(() -> new DiscoveryResolver(configuration).resolve(),
                    "the profile holds the stub's root, and the served certificate names the dialled address");

            assertAll("the discovery document was fetched from the stub, over a posture nothing relaxed",
                    () -> assertEquals(Optional.of(stub.issuer()), metadata.getIssuer()),
                    () -> assertEquals(Optional.of(stub.url(StubIdentityProvider.Endpoint.TOKEN)),
                            metadata.getTokenEndpoint()),
                    () -> assertEquals(Optional.of(stub.url(StubIdentityProvider.Endpoint.REVOCATION)),
                            metadata.getRevocationEndpoint()),
                    () -> assertTrue(configuration.isVerifyHostname(), "hostname verification stays on"),
                    () -> assertEquals(1, stub.received(StubIdentityProvider.Endpoint.DISCOVERY).size(),
                            "the stub served exactly the one discovery request"));
        }

        @Test
        @DisplayName("Should refuse the same dial when no trust profile is named (matched control)")
        void discoveryIsRefusedWithoutTheProfile() {
            OidcConfig oidc = stubOidc().build();
            DiscoveryResolver withoutProfile = new DiscoveryResolver(assembledBackChannel(
                    producer(oidc, EgressTlsConfig.defaults(), TestTlsConfigurationRegistry.empty()), oidc,
                    oidc.scopes()));

            assertThrows(TransportException.class, withoutProfile::resolve,
                    "the JVM default trust store does not hold the stub's root, so the handshake is refused");

            assertEquals(List.of(), stub.received(StubIdentityProvider.Endpoint.DISCOVERY),
                    "a refused handshake sends no request — the profile is what reaches the stub");
        }

        @Test
        @DisplayName("Should refuse a plain-HTTP issuer before anything is sent (matched control)")
        void plainHttpIssuerIsRefusedBeforeAnythingIsSent() {
            OidcConfig oidc = stubOidc().issuer(stub.issuer().replaceFirst("^https", "http")).build();
            DiscoveryResolver plainHttp = new DiscoveryResolver(
                    assembledBackChannel(stubProducer(oidc), oidc, oidc.scopes()));

            TransportException refused = assertThrows(TransportException.class, plainHttp::resolve);

            assertAll("the configuration the producer builds cannot dial plain HTTP",
                    () -> assertNull(refused.getCause(),
                            "the refusal is decided on the scheme: a dial that was attempted and failed would "
                                    + "chain its I/O failure as the cause"),
                    () -> assertEquals(List.of(), stub.received(StubIdentityProvider.Endpoint.DISCOVERY),
                            "the stub records no request"));
        }

        @Test
        @DisplayName("Should push the request of a login and redirect with client_id and request_uri only")
        void shouldRedirectALoginThroughThePushedRequestEndpoint() {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();
            String requestUri = scriptedRequestUri();
            stub.script(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST, pushAccepted(requestUri));
            AtomicReference<BffRuntime.ReservedHttpResponse> redirected = new AtomicReference<>();

            StubIdentityProvider.ReceivedRequest pushed = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> redirected.set(login(runtime, "/")));

            BffRuntime.ReservedHttpResponse answer = redirected.get();
            String location = answer.locationOptional().orElseThrow();
            Map<String, String> form = pushed.form();
            assertEquals(requestUri, assertPushedRedirect(location, CLIENT_ID),
                    "the redirect carries the request_uri the identity provider answered");
            assertAll("a login of the produced runtime",
                    () -> assertEquals(302, answer.status()),
                    () -> assertEquals(1, answer.setCookieHeaders().size(), "the browser-binding cookie is set"),
                    () -> assertEquals(1, pendingStoreOf(runtime).size(), "the pending authorization is stored"),
                    () -> assertEquals(Set.of("openid"), scopeOf(pushed), "an unrouted login asks for oidc.scopes"),
                    () -> assertEquals("query", form.get("response_mode")),
                    () -> assertEquals(REDIRECT_URI, form.get("redirect_uri")),
                    () -> assertEquals(CLIENT_ID, form.get(PARAM_CLIENT_ID)),
                    () -> assertNotNull(form.get(PARAM_STATE), "the state travels in the pushed request"),
                    () -> assertNotNull(form.get("nonce"), "and the nonce"),
                    () -> assertNotNull(form.get("code_challenge"), "and the PKCE challenge"),
                    () -> assertFalse(location.contains(String.valueOf(form.get(PARAM_STATE))),
                            "none of which the redirect shows the browser"),
                    () -> assertFalse(form.containsKey("dpop_jkt"), "the gateway adds no dpop_jkt"),
                    () -> assertEquals(Optional.empty(), pushed.header(DPOP_HEADER), "and the push carries no proof"));
        }

        @Test
        @DisplayName("Should push the request of a step-up re-drive and redirect with client_id and request_uri only")
        void shouldReDriveAStepUpThroughThePushedRequestEndpoint() {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();
            String requestUri = scriptedRequestUri();
            stub.script(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST, pushAccepted(requestUri));
            StepUpChallenge challenge = new StepUpChallenge(STEP_UP_ACR, STEP_UP_MAX_AGE);
            SessionRecord session = sessionToElevate();
            AtomicReference<StepUpCoordinator.StepUpOutcome> coordinated = new AtomicReference<>();

            StubIdentityProvider.ReceivedRequest pushed = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> coordinated.set(runtime.stepUpCoordinator().coordinate(session, challenge, "/orders/42", NOW)));

            StepUpCoordinator.StepUpOutcome outcome = coordinated.get();
            Map<String, String> form = pushed.form();
            assertEquals(StepUpCoordinator.StepUpOutcome.Kind.RE_DRIVE, outcome.kind(),
                    "the produced runtime satisfies no challenge silently, so the browser is re-driven");
            assertEquals(requestUri,
                    assertPushedRedirect(Objects.requireNonNull(outcome.location(), "location"), CLIENT_ID),
                    "the re-drive location carries the request_uri the identity provider answered");
            assertAll("a step-up re-drive of the produced runtime",
                    () -> assertEquals(1, outcome.setCookieHeaders().size(), "the browser-binding cookie is set"),
                    () -> assertEquals(1, pendingStoreOf(runtime).size(), "the pending authorization is stored"),
                    () -> assertEquals(STEP_UP_ACR, form.get("acr_values"),
                            "the elevated authentication context travels in the pushed request"),
                    () -> assertEquals(Integer.toString(STEP_UP_MAX_AGE), form.get("max_age"),
                            "and so does the authentication age"),
                    () -> assertEquals(Set.of("openid"), scopeOf(pushed),
                            "the step-up request is built from the static oidc.scopes"),
                    () -> assertEquals("query", form.get("response_mode")),
                    () -> assertNotNull(form.get(PARAM_STATE), "the state travels in the pushed request"));
        }

        @Test
        @DisplayName("Should refuse a login with the 502 event when the identity provider refuses the push, storing nothing")
        void shouldRefuseALoginWhosePushIsRefused() {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();
            stub.script(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    StubIdentityProvider.Answer.json(400, "{\"error\":\"invalid_request\"}"));

            GatewayException refused = assertThrows(GatewayException.class, () -> login(runtime, "/"));

            assertAll("a login whose authorization request the identity provider refuses",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, refused.getEventType()),
                    () -> assertEquals(502, refused.getEventType().httpStatus(), "the edge answers 502"),
                    () -> assertTrue(String.valueOf(refused.getMessage()).contains("push-failed"),
                            "the refusal names its reason: " + refused.getMessage()),
                    () -> assertEquals(1,
                            stub.received(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST).size(),
                            "the push did reach the identity provider — the refusal is of its answer"),
                    () -> assertEquals(0, pendingStoreOf(runtime).size(),
                            "nothing is stored for a login that was never started"),
                    () -> assertEquals(1, recordsContaining(TestLogLevel.WARN, pushRefused()),
                            "the refusal is recorded once"));
        }

        @Test
        @DisplayName("Should push a different scope set for two routes whose needed scope sets differ")
        void shouldPushTheScopeSetOfTheRouteALoginLandsOn() {
            BffRuntime runtime = scopedRuntime();

            StubIdentityProvider.ReceivedRequest orders = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> login(runtime, SCOPED_ROUTE_PREFIX + "/list"));
            StubIdentityProvider.ReceivedRequest invoices = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> login(runtime, OTHER_SCOPED_ROUTE_PREFIX + "/list"));

            assertAll("two logins of one runtime, landing on two routes",
                    () -> assertEquals(SCOPED_NEEDED_SCOPES, scopeOf(orders)),
                    () -> assertEquals(OTHER_SCOPED_NEEDED_SCOPES, scopeOf(invoices)),
                    () -> assertNotEquals(scopeOf(orders), scopeOf(invoices),
                            "the pushed scope follows the route, it is not one static set"),
                    () -> assertNotEquals(orders.form().get(PARAM_STATE), invoices.form().get(PARAM_STATE),
                            "each login pushes a transaction of its own"));
        }

        @Test
        @DisplayName("a navigation login on a scoped session route requests exactly oidc.scopes united with the endpoint's scopes")
        void sessionRouteLoginRequestsNeededScopes() {
            BffRuntime runtime = scopedRuntime();
            PipelineRequest request = PipelineRequest.builder()
                    .method(HttpMethod.GET)
                    .requestPath(SCOPED_ROUTE_PREFIX + "/list")
                    .queryParameters(List.of())
                    .headers(Map.of("accept", List.of("text/html")))
                    .build();
            request.canonicalPath(SCOPED_ROUTE_PREFIX + "/list");
            request.selectedRoute(RouteRuntime.builder().id("orders")
                    .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                    .neededScopes(SCOPED_NEEDED_SCOPES)
                    .build());

            StubIdentityProvider.ReceivedRequest pushed = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> runtime.sessionStage().process(request));

            assertEquals(Optional.of(302), request.shortCircuitStatus(), "the navigation is redirected into login");
            assertPushedRedirect(request.responseHeaders().get("Location"), CLIENT_ID);
            assertEquals(SCOPED_NEEDED_SCOPES, scopeOf(pushed),
                    "the pushed request asks for the route's neededScopes — nothing missing, nothing extra, and "
                            + "never the static oidc.scopes alone");
        }

        @Test
        @DisplayName("/auth/login requests the landing route's needed scopes, and oidc.scopes alone for an unrouted target")
        void loginInitiationRequestsReturnTargetScopes() {
            BffRuntime runtime = scopedRuntime();
            AtomicReference<BffRuntime.ReservedHttpResponse> routedRedirect = new AtomicReference<>();
            AtomicReference<BffRuntime.ReservedHttpResponse> unroutedRedirect = new AtomicReference<>();

            StubIdentityProvider.ReceivedRequest routed = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> routedRedirect.set(login(runtime, SCOPED_ROUTE_PREFIX + "/list")));
            StubIdentityProvider.ReceivedRequest unrouted = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> unroutedRedirect.set(login(runtime, "/unrouted")));

            assertPushedRedirect(routedRedirect.get().locationOptional().orElseThrow(), CLIENT_ID);
            assertPushedRedirect(unroutedRedirect.get().locationOptional().orElseThrow(), CLIENT_ID);
            assertAll("the producer wires ReturnTargetScopes over the injected route table",
                    () -> assertEquals(SCOPED_NEEDED_SCOPES, scopeOf(routed),
                            "the routed target asks for oidc.scopes united with the endpoint's scopes"),
                    () -> assertEquals(Set.of("openid"), scopeOf(unrouted),
                            "the unrouted target asks for oidc.scopes alone"));
            assertFalse(reachableInstancesOf(runtime, ReturnTargetScopes.class).isEmpty(),
                    "the login-initiation endpoint holds the producer-built resolver");
        }

        // Session widening (ADR-0057) under the pushed request, the sender constraint and the one client
        // authentication (ADR-0058). A widening is an authorization request and a code exchange like a
        // login, and the scope-driven refresh is a refresh grant like the near-expiry one, so each of the
        // tests below drives one of those legs through the produced runtime and asserts on the request the
        // stub recorded. The stub mints no token, so no widening is completed here: what is shown is what
        // the identity provider is sent, and that an answer not bound to the proof key is refused.

        /**
         * The live session's granted set holds a scope the route does not need ({@code profile}) and
         * lacks one it does, so the pushed scope set tells the two candidate bindings apart: a widening
         * for the needed scopes alone would drop {@code profile}.
         */
        @Test
        @DisplayName("Should push the request of a silent session widening and redirect the navigation with client_id and request_uri only")
        void shouldPushASilentWideningOfASessionRouteNavigation() {
            BffRuntime runtime = scopedRuntime();
            String requestUri = scriptedRequestUri();
            stub.script(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST, pushAccepted(requestUri));
            String sessionCookie = bindLiveSession(runtime, Set.of("openid", "profile"));
            PipelineRequest request = sessionRouteRequest(sessionCookie, "text/html", SCOPED_NEEDED_SCOPES);

            StubIdentityProvider.ReceivedRequest pushed = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> runtime.sessionStage().process(request));

            String location = request.responseHeaders().get("Location");
            Map<String, String> form = pushed.form();
            assertEquals(Optional.of(302), request.shortCircuitStatus(),
                    "the navigation is redirected rather than relayed");
            assertEquals(requestUri, assertPushedRedirect(location, CLIENT_ID),
                    "the redirect carries the request_uri the identity provider answered, and no prompt, "
                            + "scope or state of its own");
            assertAll("the silent widening of a session route navigation",
                    () -> assertEquals(Set.of("openid", "profile", SCOPED_ENDPOINT_SCOPE), scopeOf(pushed),
                            "the pushed request asks for the granted set united with the route's needed scopes"),
                    () -> assertEquals("none", form.get("prompt"),
                            "the first attempt is silent, and prompt=none travels in the pushed request"),
                    () -> assertEquals("query", form.get("response_mode")),
                    () -> assertEquals(REDIRECT_URI, form.get("redirect_uri")),
                    () -> assertNotNull(form.get(PARAM_STATE), "the state travels in the pushed request"),
                    () -> assertNotNull(form.get("nonce"), "and the nonce"),
                    () -> assertNotNull(form.get("code_challenge"), "and the PKCE challenge"),
                    () -> assertFalse(location.contains(String.valueOf(form.get(PARAM_STATE))),
                            "none of which the redirect shows the browser"),
                    () -> assertNotNull(form.get(CLIENT_ASSERTION),
                            "the push presents the client credential, as the push of a login does"),
                    () -> assertEquals(Optional.empty(), pushed.header(DPOP_HEADER), "and carries no proof"),
                    () -> assertEquals(1, pendingStoreOf(runtime).size(), "the widening's pending record is stored"),
                    () -> assertTrue(request.mediatedBearer().isEmpty(),
                            "the under-scoped session's token is never recorded for the upstream"));
        }

        /**
         * The two attempts of one widening, started at the step-up path: the silent attempt, and the one
         * interactive attempt the callback re-drives when the identity provider answers that interaction
         * is needed. Both go through the runtime's one {@code SessionWidening}, so both are pushed.
         */
        @Test
        @DisplayName("Should push the silent attempt of a step-up widening and its one interactive re-drive, each redirecting with client_id and request_uri only")
        void shouldPushBothAttemptsOfAStepUpWidening() {
            BffRuntime runtime = scopedRuntime();
            String sessionCookie = bind(runtime, sessionGranted(Set.of("openid", "profile")));
            AtomicReference<BffRuntime.ReservedHttpResponse> silent = new AtomicReference<>();
            AtomicReference<BffRuntime.ReservedHttpResponse> interactive = new AtomicReference<>();

            StubIdentityProvider.ReceivedRequest silentPush = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> silent.set(stepUp(runtime, sessionCookie, SCOPED_ROUTE_PREFIX + "/list")));
            StubIdentityProvider.ReceivedRequest interactivePush = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> interactive.set(callback(runtime,
                            "error=login_required&state=" + encoded(silentPush.form().get(PARAM_STATE)),
                            bindingCookieOf(silent.get()))));

            assertPushedRedirect(silent.get().locationOptional().orElseThrow(), CLIENT_ID);
            assertPushedRedirect(interactive.get().locationOptional().orElseThrow(), CLIENT_ID);
            Set<String> widened = Set.of("openid", "profile", SCOPED_ENDPOINT_SCOPE);
            assertAll("the two attempts of one step-up widening",
                    () -> assertEquals(302, silent.get().status()),
                    () -> assertEquals(302, interactive.get().status(),
                            "login_required on the silent attempt owes exactly one interactive attempt"),
                    () -> assertEquals("none", silentPush.form().get("prompt"), "the silent attempt pushes prompt=none"),
                    () -> assertFalse(interactivePush.form().containsKey("prompt"),
                            "the interactive attempt pushes no prompt, so the identity provider may interact"),
                    () -> assertEquals(widened, scopeOf(silentPush)),
                    () -> assertEquals(widened, scopeOf(interactivePush), "the re-drive asks for the same scope set"),
                    () -> assertNotEquals(silentPush.form().get(PARAM_STATE), interactivePush.form().get(PARAM_STATE),
                            "each attempt pushes a transaction of its own"),
                    () -> assertEquals(1, interactive.get().setCookieHeaders().size(),
                            "the re-drive sets a new browser-binding cookie"),
                    () -> assertNotEquals(bindingCookieOf(silent.get()), bindingCookieOf(interactive.get())),
                    () -> assertEquals(1, pendingStoreOf(runtime).size(),
                            "the silent record was consumed and the interactive one stored"));
        }

        @Test
        @DisplayName("Should refuse a session widening with the 502 event when the identity provider refuses the push, storing nothing and keeping the session")
        void shouldRefuseAWideningWhosePushIsRefused() {
            BffRuntime runtime = scopedRuntime();
            Set<String> granted = Set.of("openid", "profile");
            String sessionCookie = bind(runtime, sessionGranted(granted));
            stub.script(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    StubIdentityProvider.Answer.json(400, "{\"error\":\"invalid_request\"}"));

            GatewayException refused = assertThrows(GatewayException.class,
                    () -> stepUp(runtime, sessionCookie, SCOPED_ROUTE_PREFIX + "/list"));

            assertAll("a widening whose authorization request the identity provider refuses",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, refused.getEventType()),
                    () -> assertEquals(502, refused.getEventType().httpStatus(), "the edge answers 502"),
                    () -> assertTrue(String.valueOf(refused.getMessage()).contains("push-failed"),
                            "the refusal names its reason: " + refused.getMessage()),
                    () -> assertEquals(1,
                            stub.received(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST).size(),
                            "the push did reach the identity provider — the refusal is of its answer"),
                    () -> assertEquals(0, pendingStoreOf(runtime).size(),
                            "nothing is stored for a widening that was never started"),
                    () -> assertEquals(granted, liveSessionOf(runtime, sessionCookie).grantedScopes(),
                            "the live session is left as it was"),
                    () -> assertEquals(1, recordsContaining(TestLogLevel.WARN, pushRefused()),
                            "the refusal is recorded once"));
        }

        /**
         * The interactive re-drive has no weaker form either. The silent record is consumed by the
         * callback that asks for the re-drive, so a refused push leaves no pending record at all: the
         * browser holds no binding the identity provider's answer could be bound to.
         */
        @Test
        @DisplayName("Should refuse the interactive re-drive of a widening with the 502 event when its push is refused, storing nothing")
        void shouldRefuseTheInteractiveReDriveWhosePushIsRefused() {
            BffRuntime runtime = scopedRuntime();
            Set<String> granted = Set.of("openid", "profile");
            String sessionCookie = bind(runtime, sessionGranted(granted));
            AtomicReference<BffRuntime.ReservedHttpResponse> silent = new AtomicReference<>();
            StubIdentityProvider.ReceivedRequest silentPush = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> silent.set(stepUp(runtime, sessionCookie, SCOPED_ROUTE_PREFIX + "/list")));
            String interactionNeeded = "error=interaction_required&state=" + encoded(silentPush.form().get(PARAM_STATE));
            String bindingCookie = bindingCookieOf(silent.get());
            stub.script(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    StubIdentityProvider.Answer.json(400, "{\"error\":\"invalid_request\"}"));

            GatewayException refused = assertThrows(GatewayException.class,
                    () -> callback(runtime, interactionNeeded, bindingCookie));

            assertAll("an interactive re-drive whose authorization request the identity provider refuses",
                    () -> assertEquals(EventType.UPSTREAM_ERROR, refused.getEventType()),
                    () -> assertEquals(502, refused.getEventType().httpStatus(), "the edge answers 502"),
                    () -> assertEquals(2,
                            stub.received(StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST).size(),
                            "the re-drive was pushed, and refused — it was not sent through the browser instead"),
                    () -> assertEquals(0, pendingStoreOf(runtime).size(),
                            "the silent record is consumed and no interactive record is stored"),
                    () -> assertEquals(granted, liveSessionOf(runtime, sessionCookie).grantedScopes(),
                            "the live session is left as it was"));
        }

        /**
         * The code exchange of a widening callback runs on the base flow, through the runtime's one
         * token-endpoint client, exactly as the exchange of a login does. So it carries the client
         * credential of the widening's own push and a proof of the key a login's exchange proves.
         */
        @Test
        @DisplayName("Should present the client credential and a DPoP proof on the code exchange of a widening callback")
        void shouldPresentTheClientCredentialAndAProofOnTheCodeExchangeOfAWidening() throws Exception {
            BffRuntime runtime = scopedRuntime();
            String sessionCookie = bind(runtime, sessionGranted(Set.of("openid", "profile")));
            String loginProofKey = proofKeyThumbprint(proofOf(drive(Leg.CODE_EXCHANGE, runtime)));

            WideningCallback widening = wideningAnsweredWithABearerToken(runtime, sessionCookie);

            StubIdentityProvider.ReceivedRequest exchange = widening.exchange();
            String proof = proofOf(exchange);
            JsonNode proofClaims = jwtPart(proof, 1);
            String proofKey = proofKeyThumbprint(proof);
            assertAll("the code exchange of a widening callback",
                    () -> assertEquals("authorization_code", exchange.form().get("grant_type")),
                    () -> assertEquals(widening.code(), exchange.form().get("code")),
                    () -> assertEquals(keyIdOf(widening.pushed()), keyIdOf(exchange),
                            "the exchange presents the client credential the widening's push presented"),
                    () -> assertEquals("dpop+jwt", jwtPart(proof, 0).path("typ").asText(),
                            "the exchange carries a DPoP proof"),
                    () -> assertEquals(stub.url(StubIdentityProvider.Endpoint.TOKEN), proofClaims.path("htu").asText(),
                            "bound to the token endpoint"),
                    () -> assertEquals(loginProofKey, proofKey,
                            "of the one proof key the exchange of a login proves"));
        }

        /**
         * The exchange of the test above is judged as the exchange of a login is: a token response of
         * type {@code Bearer} is refused, and nothing is merged into the live session.
         */
        @Test
        @DisplayName("Should refuse a token response of type Bearer on the code exchange of a widening callback without touching the session")
        void shouldRefuseAWideningWhoseTokenResponseIsNotBound() {
            BffRuntime runtime = scopedRuntime();
            Set<String> granted = Set.of("openid", "profile");
            String sessionCookie = bind(runtime, sessionGranted(granted));
            String accessTokenBefore = liveSessionOf(runtime, sessionCookie).accessToken();

            BffRuntime.ReservedHttpResponse answered =
                    wideningAnsweredWithABearerToken(runtime, sessionCookie).answered();

            List<LogRecord> refusals = TestLoggerFactory.getTestHandler()
                    .resolveLogMessagesContaining(TestLogLevel.WARN, tokenResponseNotBound());
            SessionRecord after = liveSessionOf(runtime, sessionCookie);
            assertAll("a widening whose tokens are not bound to the proof key",
                    () -> assertEquals(400, answered.status(), "the widening is refused"),
                    () -> assertEquals(List.of(), answered.setCookieHeaders(), "no cookie is set"),
                    () -> assertEquals(Optional.empty(), answered.locationOptional(), "and no redirect issued"),
                    () -> assertEquals(1, refusals.size(), "the refusal is recorded exactly once"),
                    () -> assertTrue(String.valueOf(refusals.getFirst().getMessage())
                                    .contains("on the code-exchange leg"),
                            "under the leg of the code exchange: " + refusals.getFirst().getMessage()),
                    () -> assertEquals(0, recordsContaining(TestLogLevel.INFO,
                            BffLogMessages.INFO.SESSION_WIDENED.resolveIdentifierString()),
                            "nothing was merged into the session"),
                    () -> assertEquals(granted, after.grantedScopes(), "the live session keeps its granted set"),
                    () -> assertEquals(accessTokenBefore, after.accessToken(), "and the token it held"));
        }

        /**
         * The scope-driven refresh a session route makes for a scope inside the granted set goes through
         * the exchange seam the near-expiry refresh uses, so it is sender-constrained and judged like it:
         * the grant carries a proof of the runtime's proof key, and a token response of type
         * {@code Bearer} ends the session as a redeemed grant.
         */
        @Test
        @DisplayName("Should present a DPoP proof on a scope-driven refresh, request exactly the set it is given, and end the session when the token response is of type Bearer")
        void shouldRefuseAScopeDrivenRefreshWhoseTokenResponseIsNotBound() throws Exception {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();
            String nearExpiryProofKey = proofKeyThumbprint(proofOf(drive(Leg.REFRESH_GRANT, runtime)));
            Set<String> requested = Set.of("openid", SCOPED_ENDPOINT_SCOPE);
            String refreshToken = token();
            SessionRecord live = refreshableSession(Set.of("openid"), requested, refreshToken);
            String sessionCookie = bind(runtime, live);
            SessionAuthenticationStage.ScopeRefresh scopeSeam = scopeRefreshSeamOf(runtime);
            stub.script(StubIdentityProvider.Endpoint.TOKEN, tokenAnswer(TYPE_BEARER, token()));
            AtomicReference<SessionAuthenticationStage.RefreshResult> result = new AtomicReference<>();

            StubIdentityProvider.ReceivedRequest grant = receivedBy(StubIdentityProvider.Endpoint.TOKEN,
                    () -> result.set(scopeSeam.refreshForScopes(live, sessionCookie, requested, NOW)));

            String proof = proofOf(grant);
            List<LogRecord> refusals = TestLoggerFactory.getTestHandler()
                    .resolveLogMessagesContaining(TestLogLevel.WARN, tokenResponseNotBound());
            assertAll("the grant of a scope-driven refresh",
                    () -> assertEquals("refresh_token", grant.form().get("grant_type")),
                    () -> assertEquals(refreshToken, grant.form().get("refresh_token")),
                    () -> assertEquals(requested, scopeOf(grant),
                            "the grant asks for the active set united with the missing scope"),
                    () -> assertNotNull(grant.form().get(CLIENT_ASSERTION), "it presents the client credential"),
                    () -> assertEquals("dpop+jwt", jwtPart(proof, 0).path("typ").asText(), "and a DPoP proof"),
                    () -> assertEquals(nearExpiryProofKey, proofKeyThumbprint(proof),
                            "of the one proof key the near-expiry refresh proves"));
            assertAll("a scope-driven refresh whose tokens are not bound to the proof key",
                    () -> assertInstanceOf(SessionAuthenticationStage.RefreshResult.SessionEnded.class, result.get(),
                            "the identity provider consumed the grant, so the session is not kept"),
                    () -> assertEquals(1, refusals.size(), "the refusal is recorded exactly once"),
                    () -> assertTrue(String.valueOf(refusals.getFirst().getMessage()).contains("on the refresh leg"),
                            "under the leg of the refresh grant: " + refusals.getFirst().getMessage()),
                    () -> assertEquals(1, recordsContaining(TestLogLevel.WARN, "redeemed-response-refused"),
                            "the session end is recorded as a redeemed refusal"),
                    () -> assertEquals(Optional.empty(), sessionBindingOf(runtime).resolve(sessionCookie, NOW),
                            "and the session is gone"));
        }

        /**
         * The positive control of the refusal above: the same scope-driven refresh accepts a response of
         * type {@code DPoP} whose access token names the proof key, so the refusal is of the unbound
         * response and not of the scope-driven leg. The stub mints no token; the test supplies the body.
         */
        @Test
        @DisplayName("Should accept, on a scope-driven refresh, a DPoP token response bound to the proof key (control)")
        void shouldAcceptABoundTokenResponseOnAScopeDrivenRefresh() throws Exception {
            KeyFixture fixture = senderConstraintFixture(KeyMode.PROVIDED_EC);
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();
            Set<String> requested = Set.of("openid", SCOPED_ENDPOINT_SCOPE);
            SessionRecord live = refreshableSession(Set.of("openid"), requested, token());
            String sessionCookie = bind(runtime, live);
            String accessToken = accessTokenBoundTo(fixture.expectedKeyId().orElseThrow());
            stub.script(StubIdentityProvider.Endpoint.TOKEN, tokenAnswer(TYPE_DPOP, accessToken));

            SessionAuthenticationStage.RefreshResult result =
                    scopeRefreshSeamOf(runtime).refreshForScopes(live, sessionCookie, requested, NOW);

            SessionRecord mediated = assertInstanceOf(SessionAuthenticationStage.RefreshResult.Mediate.class, result,
                    "a bound response keeps the session").boundSession().session();
            assertAll("a bound token response passes the check on the scope-driven leg",
                    () -> assertEquals(accessToken, mediated.accessToken(), "the session holds the rotated token"),
                    () -> assertEquals(accessToken, liveSessionOf(runtime, sessionCookie).accessToken(),
                            "and it was persisted"),
                    () -> assertEquals(0, recordsContaining(TestLogLevel.WARN, tokenResponseNotBound()),
                            "an accepted response is not recorded as a refusal"));
        }

        /**
         * The widening and the step-up path add no second client of the identity provider. Whatever
         * reaches the pushed-authorization-request endpoint is the runtime's one adapter over its one
         * {@link ParClient}, whatever reaches the token endpoint its one refusing client, and every
         * authenticated leg holds the one {@link ClientAuthentication}. The walk is type-directed and
         * made on a runtime with {@code oidc.step_up.path} declared, in both client-authentication modes.
         */
        @ParameterizedTest(name = "client secret configured: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("Should give the login, the widening and the step-up path one pushed-request adapter, one token-endpoint client and one client authentication")
        void shouldShareOnePushedRequestAdapterAndOneTokenEndpointClientWithTheWidening(boolean clientSecret) {
            OidcConfig.OidcConfigBuilder oidc = clientSecret
                    ? stubOidc().clientSecret(Generators.letterStrings(16, 32).next())
                    : stubOidc();
            BffRuntime runtime = stubProducer(
                    oidc.stepUp(OidcConfig.StepUp.builder().path("/auth/step-up").build()).build()).bffRuntime();

            PushedAuthorizationRequests adapter = single(
                    reachableInstancesOf(runtime, PushedAuthorizationRequests.class), "pushed-request adapter");
            SessionWidening widening = single(reachableInstancesOf(runtime, SessionWidening.class), "session widening");
            LoginFlow loginFlow = single(reachableInstancesOf(runtime, LoginFlow.class), "login flow");
            StepUpEndpoint stepUpEndpoint = single(reachableInstancesOf(runtime, StepUpEndpoint.class),
                    "step-up endpoint");
            List<TokenEndpointClient> tokenEndpointClients = reachableInstancesOf(runtime, TokenEndpointClient.class);

            assertAll("one identity-provider client of each kind for the whole runtime",
                    () -> assertEquals(1, reachableInstancesOf(runtime, ParClient.class).size(),
                            "exactly one pushed-authorization-request client is constructed"),
                    () -> assertSame(adapter, single(reachableInstancesOf(widening, PushedAuthorizationRequests.class),
                            "pushed-request adapter behind the widening seam"),
                            "a widening pushes through the adapter a login pushes through"),
                    () -> assertSame(adapter, single(reachableInstancesOf(loginFlow, PushedAuthorizationRequests.class),
                            "pushed-request adapter behind the login seam")),
                    () -> assertSame(widening, single(reachableInstancesOf(stepUpEndpoint, SessionWidening.class),
                            "session widening behind the step-up endpoint"),
                            "the step-up path starts its widening through the runtime's one SessionWidening"),
                    () -> assertEquals(1, tokenEndpointClients.size(),
                            "exactly one token-endpoint client is constructed — a second one would be a path "
                                    + "to a token the binding check does not sit on"),
                    () -> assertInstanceOf(BoundTokenEndpointClient.class, tokenEndpointClients.getFirst(),
                            "and it is the one that refuses an unbound response"),
                    () -> assertEquals(1, reachableInstancesOf(runtime, ClientAuthentication.class).size(),
                            "every authenticated leg presents the one client authentication"));
        }

        @Test
        @DisplayName("Should name one audience in the client assertion of the pushed request and of the token legs")
        void shouldNameOneAudienceOnThePushedRequestAndTheTokenLegs() throws Exception {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();

            JsonNode pushed = jwtPart(clientAssertionOf(drive(Leg.PUSHED_REQUEST, runtime)), 1);
            JsonNode exchanged = jwtPart(clientAssertionOf(drive(Leg.CODE_EXCHANGE, runtime)), 1);
            JsonNode refreshed = jwtPart(clientAssertionOf(drive(Leg.REFRESH_GRANT, runtime)), 1);

            assertAll("the audience of the client assertion, leg by leg",
                    () -> assertTrue(pushed.path("aud").isTextual(),
                            "the audience of the pushed request is one JSON string: " + pushed.path("aud")),
                    () -> assertEquals(stub.issuer(), pushed.path("aud").asText(),
                            "and it is the configured issuer, not the pushed-authorization-request endpoint"),
                    () -> assertEquals(exchanged.path("aud"), pushed.path("aud"),
                            "the pushed request names the audience the code exchange names"),
                    () -> assertEquals(refreshed.path("aud"), pushed.path("aud"),
                            "and the audience the refresh grant names"),
                    () -> assertNotEquals(exchanged.path("jti").asText(), pushed.path("jti").asText(),
                            "each leg presents an assertion of its own"));
        }

        @ParameterizedTest(name = "{0} key on the {1} leg")
        @MethodSource("keyModesOnEveryLeg")
        @DisplayName("Should present a client assertion signed with the resolved key, and no secret, in key mode")
        void shouldPresentAClientAssertionInKeyMode(KeyMode mode, Leg leg) throws Exception {
            KeyFixture fixture = keyFixture(mode);
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();

            StubIdentityProvider.ReceivedRequest request = drive(leg, runtime);

            Map<String, String> form = request.form();
            String assertion = form.get(CLIENT_ASSERTION);
            assertNotNull(assertion, "the request body carries no client_assertion: " + form.keySet());
            JsonNode header = jwtPart(assertion, 0);
            JsonNode claims = jwtPart(assertion, 1);
            String keyId = header.path("kid").asText();
            assertAll("private_key_jwt on the " + leg + " leg with a " + mode + " key",
                    () -> assertEquals(JWT_BEARER_ASSERTION, form.get(CLIENT_ASSERTION_TYPE)),
                    () -> assertEquals(mode.algorithm, header.path("alg").asText(),
                            "the algorithm follows the key type"),
                    () -> assertTrue(keyId.matches(THUMBPRINT_SHAPE),
                            "the key id is an RFC 7638 thumbprint: " + keyId),
                    () -> fixture.expectedKeyId().ifPresent(expected -> assertEquals(expected, keyId,
                            "the key id is the thumbprint of the configured key")),
                    () -> assertTrue(claims.path("aud").isTextual(),
                            "the audience is one JSON string, not an array: " + claims.path("aud")),
                    () -> assertEquals(stub.issuer(), claims.path("aud").asText(),
                            "the audience is the configured issuer"),
                    () -> assertEquals(CLIENT_ID, claims.path("iss").asText()),
                    () -> assertEquals(Optional.empty(), request.header(AUTHORIZATION),
                            "key mode sends no Authorization header, so no Basic credential"),
                    () -> assertFalse(form.containsKey(CLIENT_SECRET_PARAMETER),
                            "key mode sends no client_secret parameter"));
        }

        @Test
        @DisplayName("Should sign every leg with the one generated key, and generate a fresh key per runtime")
        void shouldUseOneGeneratedKeyPerRuntime() throws Exception {
            OidcConfig oidc = stubOidc().build();
            BffRuntime runtime = stubProducer(oidc).bffRuntime();
            BffRuntime secondBoot = stubProducer(oidc).bffRuntime();

            String pushedKeyId = keyIdOf(drive(Leg.PUSHED_REQUEST, runtime));
            String exchangeKeyId = keyIdOf(drive(Leg.CODE_EXCHANGE, runtime));
            String refreshKeyId = keyIdOf(drive(Leg.REFRESH_GRANT, runtime));
            String revocationKeyId = keyIdOf(drive(Leg.REVOCATION, runtime));
            String secondBootKeyId = keyIdOf(drive(Leg.REFRESH_GRANT, secondBoot));

            assertAll("one key per assembled runtime",
                    () -> assertEquals(exchangeKeyId, pushedKeyId, "the pushed request signs with the exchange's key"),
                    () -> assertEquals(exchangeKeyId, refreshKeyId, "the refresh grant signs with the exchange's key"),
                    () -> assertEquals(exchangeKeyId, revocationKeyId, "revocation signs with the exchange's key"),
                    () -> assertNotEquals(exchangeKeyId, secondBootKeyId,
                            "the key is generated per startup: a second runtime authenticates with another key"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(RefusedKeyFile.class)
        @DisplayName("Should abort the build with CONFIG_INVALID for a key file the resolver refuses")
        void shouldAbortTheBuildOnARefusedKeyFile(RefusedKeyFile refused) throws Exception {
            Path keyFile = refused.write(keyDirectory);
            List<String> keyMembers = Files.readAllLines(keyFile, StandardCharsets.US_ASCII).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("-----"))
                    .toList();
            BffRuntimeProducer producer = stubProducer(stubOidc().clientAuthentication(keyFileSettings(keyFile)).build());

            GatewayException thrown = assertThrows(GatewayException.class, producer::bffRuntime);

            String message = thrown.getMessage();
            assertAll("the refusal of a " + refused + " key file",
                    () -> assertEquals(EventType.CONFIG_INVALID, thrown.getEventType()),
                    () -> assertTrue(message.contains(KEY_FILE_FIELD),
                            "the refusal must name the configuration field: " + message),
                    () -> assertFalse(keyMembers.isEmpty(), "the fixture must hold content that could leak"),
                    () -> assertTrue(keyMembers.stream().noneMatch(message::contains),
                            "no line of the key file may be echoed: " + message),
                    () -> assertFalse(message.contains(keyFile.toString()),
                            "the configured path is not echoed either: " + message));
        }

        @ParameterizedTest(name = "on the {0} leg")
        @EnumSource(Leg.class)
        @DisplayName("Should present the Basic credential, and no client assertion, in client-secret mode")
        void shouldPresentTheBasicCredentialInClientSecretMode(Leg leg) {
            SecretFixture fixture = secretFixture();
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();

            StubIdentityProvider.ReceivedRequest request = drive(leg, runtime);

            Map<String, String> form = request.form();
            String authorization = request.header(AUTHORIZATION).orElse("");
            assertTrue(authorization.startsWith(BASIC_SCHEME),
                    "client-secret mode authenticates with an Authorization: Basic header, got: "
                            + request.headers().keySet());
            String[] credential = new String(Base64.getDecoder().decode(authorization.substring(BASIC_SCHEME.length())),
                    StandardCharsets.UTF_8).split(":", -1);
            assertAll("client_secret_basic on the " + leg + " leg",
                    () -> assertEquals(2, credential.length,
                            "the form-encoded halves carry no colon of their own, so exactly one separates them"),
                    () -> assertEquals(fixture.formEncodedCredential(), String.join(":", credential),
                            "the credential is the form-encoded client id and secret"),
                    () -> assertEquals(fixture.clientId(), URLDecoder.decode(credential[0], StandardCharsets.UTF_8)),
                    () -> assertEquals(fixture.secret(), URLDecoder.decode(credential[1], StandardCharsets.UTF_8)),
                    () -> assertFalse(form.containsKey(CLIENT_ASSERTION_TYPE), "no client_assertion_type is sent"),
                    () -> assertFalse(form.containsKey(CLIENT_ASSERTION), "no client_assertion is sent"),
                    () -> assertFalse(form.containsKey(CLIENT_SECRET_PARAMETER),
                            "the secret travels in the header only, never as a form parameter"));
            assertNoRecordCarriesTheSecret(fixture);
        }

        @Test
        @DisplayName("Should report client-secret authentication exactly once per runtime and resolve no client-authentication key")
        void shouldWarnOncePerRuntimeInClientSecretMode() {
            SecretFixture fixture = secretFixture();

            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();
            for (Leg leg : Leg.values()) {
                drive(leg, runtime);
            }

            assertAll("client-secret mode is reported once, at the build, and resolves no key",
                    () -> assertEquals(1, recordsContaining(TestLogLevel.WARN, clientSecretWarning()),
                            "one record per assembled runtime — not one per leg or per scoped configuration"),
                    () -> assertEquals(0, recordsContaining(TestLogLevel.INFO, GENERATED_CLIENT_AUTHENTICATION_KEY),
                            "no client-authentication key is generated when a secret authenticates"));
            assertNoRecordCarriesTheSecret(fixture);
        }

        @Test
        @DisplayName("Should not report client-secret authentication for a runtime built without a secret (matched control)")
        void shouldNotWarnInKeyMode() {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();

            assertAll("key mode: the generated key is recorded and the client-secret warning is absent",
                    () -> assertTrue(runtime.isActive()),
                    () -> assertEquals(0, recordsContaining(TestLogLevel.WARN, clientSecretWarning()),
                            "without oidc.client_secret the warning must not appear"),
                    () -> assertEquals(1, recordsContaining(TestLogLevel.INFO, GENERATED_CLIENT_AUTHENTICATION_KEY),
                            "the handler does see this build: the generated client-authentication key is recorded"));
        }

        /**
         * The published key and the signing key are one key. The key id alone would not show that — an
         * endpoint publishing some other key under the assertion's key id would pass a key-id
         * comparison — so the assertion the stub's token endpoint recorded is verified against the key
         * the client JWKS path published, the way an identity provider verifies it.
         */
        @ParameterizedTest(name = "{0} key")
        @EnumSource(KeyMode.class)
        @DisplayName("Should publish, on the client JWKS path, the key the client assertion is signed with")
        void shouldPublishTheKeyTheClientAssertionIsSignedWith(KeyMode mode)
                throws Exception {
            KeyFixture fixture = keyFixture(mode);
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();

            JsonNode published = publishedKeyOf(runtime);
            String assertion = clientAssertionOf(drive(Leg.CODE_EXCHANGE, runtime));

            JsonNode header = jwtPart(assertion, 0);
            String publishedKeyId = published.path("kid").asText();
            assertAll("the published " + mode + " key and the client assertion",
                    () -> assertTrue(publishedKeyId.matches(THUMBPRINT_SHAPE),
                            "the published key id is an RFC 7638 thumbprint: " + publishedKeyId),
                    () -> assertEquals(publishedKeyId, header.path("kid").asText(),
                            "the assertion names the published key"),
                    () -> fixture.expectedKeyId().ifPresent(expected -> assertEquals(expected, publishedKeyId,
                            "the published key id is the thumbprint of the configured key")),
                    () -> assertEquals(mode.algorithm, published.path("alg").asText()),
                    () -> assertEquals(header.path("alg").asText(), published.path("alg").asText(),
                            "the published algorithm is the one the assertion is signed with"),
                    () -> assertEquals("sig", published.path("use").asText()),
                    () -> assertTrue(PRIVATE_JWK_MEMBERS.stream().noneMatch(published::has),
                            "no private key member is published: " + memberNamesOf(published)),
                    () -> assertTrue(verifies(published, assertion),
                            "the assertion's signature verifies against the published key"));
        }

        /**
         * The negative control for the signature verification above, and the per-startup property of a
         * generated key seen from the endpoint: a second runtime publishes another key, under which the
         * first runtime's assertion does not verify.
         */
        @Test
        @DisplayName("Should publish a fresh generated key per runtime, under which another runtime's assertion fails (control)")
        void shouldPublishAFreshGeneratedKeyPerRuntime() throws Exception {
            OidcConfig oidc = stubOidc().build();
            BffRuntime runtime = stubProducer(oidc).bffRuntime();
            BffRuntime secondBoot = stubProducer(oidc).bffRuntime();

            JsonNode published = publishedKeyOf(runtime);
            JsonNode secondBootPublished = publishedKeyOf(secondBoot);
            String assertion = clientAssertionOf(drive(Leg.REFRESH_GRANT, runtime));

            assertAll("each runtime publishes its own generated key",
                    () -> assertNotEquals(published.path("kid").asText(), secondBootPublished.path("kid").asText(),
                            "a second runtime publishes another key"),
                    () -> assertEquals(published, publishedKeyOf(runtime),
                            "one runtime publishes the same key on every request"),
                    () -> assertTrue(verifies(published, assertion), "the assertion verifies under its own runtime's key"),
                    () -> assertFalse(verifies(secondBootPublished, assertion),
                            "and not under the other runtime's key — the verification is of this key, not of any key"));
        }

        @Test
        @DisplayName("Should publish the key set without contacting the identity provider")
        void shouldPublishWithoutContactingTheIdentityProvider() throws Exception {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();

            publishedKeyOf(runtime);

            assertAll("the key set is served from the runtime alone",
                    () -> assertEquals(List.of(), stub.received(StubIdentityProvider.Endpoint.DISCOVERY)),
                    () -> assertEquals(List.of(), stub.received(StubIdentityProvider.Endpoint.TOKEN)));
        }

        @Test
        @DisplayName("Should answer a method other than GET with 405 and Allow: GET in key mode")
        void shouldRefuseAnotherMethodOnTheClientJwksPathInKeyMode() {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();

            BffRuntime.ReservedHttpResponse response = clientJwks(runtime, "POST");

            assertAll("POST on the client JWKS path in key mode",
                    () -> assertEquals(405, response.status()),
                    () -> assertEquals(Map.of("Allow", "GET"), response.headers()),
                    () -> assertEquals(Optional.empty(), response.jsonBodyOptional()));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"GET", "HEAD", "POST"})
        @DisplayName("Should answer the client JWKS path 404 with no body in client-secret mode")
        void shouldWithholdTheKeySetInClientSecretMode(String method) {
            BffRuntime runtime = stubProducer(secretFixture().oidc()).bffRuntime();

            BffRuntime.ReservedHttpResponse response = clientJwks(runtime, method);

            assertAll(method + " on the client JWKS path with a client secret configured",
                    () -> assertTrue(runtime.isActive(), "the path is dispatched by an active runtime"),
                    () -> assertEquals(404, response.status(),
                            "there is no client-authentication key, so there is nothing to publish"),
                    () -> assertEquals(Map.of(), response.headers(),
                            "no header of the endpoint's own — neither no-store nor Allow: the edge answers "
                                    + "this outcome with the response of an unrouted path"),
                    () -> assertEquals(Optional.empty(), response.jsonBodyOptional(), "no body, not an empty key set"),
                    () -> assertEquals(0, recordsContaining(TestLogLevel.INFO, GENERATED_CLIENT_AUTHENTICATION_KEY),
                            "no client-authentication key was generated to answer it"));
        }

        @ParameterizedTest(name = "{0} sender-constraint key")
        @EnumSource(KeyMode.class)
        @DisplayName("Should present a DPoP proof for the token endpoint on the code exchange")
        void shouldPresentADpopProofOnTheCodeExchange(KeyMode mode) throws Exception {
            KeyFixture fixture = senderConstraintFixture(mode);
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();

            String proof = proofOf(drive(Leg.CODE_EXCHANGE, runtime));

            JsonNode header = jwtPart(proof, 0);
            JsonNode claims = jwtPart(proof, 1);
            JsonNode proofKey = header.path("jwk");
            String thumbprint = proofKeyThumbprint(proof);
            assertAll("the DPoP proof of the code exchange with a " + mode + " key",
                    () -> assertEquals("dpop+jwt", header.path("typ").asText()),
                    () -> assertEquals(mode.algorithm, header.path("alg").asText(),
                            "the algorithm follows the key type"),
                    () -> assertTrue(proofKey.isObject(), "the proof embeds its public key: " + header),
                    () -> assertTrue(PRIVATE_JWK_MEMBERS.stream().noneMatch(proofKey::has),
                            "no private key member travels in the proof: " + memberNamesOf(proofKey)),
                    () -> assertEquals("POST", claims.path("htm").asText()),
                    () -> assertEquals(stub.url(StubIdentityProvider.Endpoint.TOKEN), claims.path("htu").asText(),
                            "the proof is bound to the token endpoint"),
                    () -> assertTrue(thumbprint.matches(THUMBPRINT_SHAPE),
                            "the proof key has an RFC 7638 thumbprint: " + thumbprint),
                    () -> fixture.expectedKeyId().ifPresent(expected -> assertEquals(expected, thumbprint,
                            "the proof is signed with the configured sender-constraint key")));
        }

        /**
         * One sender constraint binds every flow. The base flow's exchange and the refresh grant are
         * driven through the runtime's own seams; the per-scope login flow's exchange has no production
         * caller and is driven by hand — see {@link #loginFlowExchange(BffRuntime)}, which reads the
         * cached flow from {@code ScopedEngineFlows.authorizationFlows} by name.
         */
        @ParameterizedTest(name = "{0} sender-constraint key")
        @EnumSource(KeyMode.class)
        @DisplayName("Should prove possession of one key on the code exchange, a per-scope login flow and a refresh")
        void shouldProveOneKeyOnEveryFlow(KeyMode mode) throws Exception {
            KeyFixture fixture = senderConstraintFixture(mode);
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();

            String baseFlowKey = proofKeyThumbprint(proofOf(drive(Leg.CODE_EXCHANGE, runtime)));
            String loginFlowKey = proofKeyThumbprint(proofOf(loginFlowExchange(runtime)));
            String refreshKey = proofKeyThumbprint(proofOf(drive(Leg.REFRESH_GRANT, runtime)));

            assertAll("one proof key for the whole runtime, with a " + mode + " key",
                    () -> assertEquals(baseFlowKey, loginFlowKey,
                            "a per-scope login flow proves possession of the base flow's key"),
                    () -> assertEquals(baseFlowKey, refreshKey,
                            "the refresh grant proves possession of the base flow's key"),
                    () -> fixture.expectedKeyId().ifPresent(expected -> assertEquals(expected, baseFlowKey,
                            "and that key is the configured sender-constraint key")));
        }

        @Test
        @DisplayName("Should answer a DPoP-Nonce challenge with exactly one retry whose proof carries the nonce")
        void shouldRetryOnceWithTheChallengedNonce() throws Exception {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();
            String nonce = token();
            stub.script(StubIdentityProvider.Endpoint.TOKEN, new StubIdentityProvider.Answer(400,
                    Map.of(DPOP_NONCE_HEADER, nonce, "Content-Type", "application/json"),
                    "{\"error\":\"use_dpop_nonce\"}"));

            BffRuntime.ReservedHttpResponse callback = loginAndCallback(runtime, token());

            List<StubIdentityProvider.ReceivedRequest> received =
                    stub.received(StubIdentityProvider.Endpoint.TOKEN);
            assertEquals(2, received.size(), "the challenge is answered by exactly one retry");
            String challenged = proofOf(received.getFirst());
            String retried = proofOf(received.getLast());
            JsonNode challengedClaims = jwtPart(challenged, 1);
            JsonNode retriedClaims = jwtPart(retried, 1);
            assertAll("the retry of a DPoP-Nonce challenge",
                    () -> assertTrue(challengedClaims.path("nonce").isMissingNode(),
                            "the first proof carries no nonce"),
                    () -> assertEquals(nonce, retriedClaims.path("nonce").asText(),
                            "the retry's proof echoes the challenged nonce"),
                    () -> assertNotEquals(challengedClaims.path("jti").asText(), retriedClaims.path("jti").asText(),
                            "the retry carries a fresh single-use proof"),
                    () -> assertEquals(proofKeyThumbprint(challenged), proofKeyThumbprint(retried),
                            "signed with the same key"),
                    () -> assertEquals(400, callback.status(),
                            "the stub refuses the retried grant, so the login is not completed"));
        }

        /**
         * The positive control of the binding check on an assembled runtime: a token response of type
         * {@code DPoP}, whose access token validates against the runtime's own validator and names the
         * proof key, is accepted. The stub mints no token, so the test supplies the response body.
         */
        @Test
        @DisplayName("Should accept, on the refresh seam, a DPoP token response bound to the proof key")
        void shouldAcceptABoundTokenResponseOnTheRefreshSeam() throws Exception {
            KeyFixture fixture = senderConstraintFixture(KeyMode.PROVIDED_EC);
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();
            String accessToken = accessTokenBoundTo(fixture.expectedKeyId().orElseThrow());
            stub.script(StubIdentityProvider.Endpoint.TOKEN, tokenAnswer(TYPE_DPOP, accessToken));
            String refreshToken = token();

            RotationResult rotated = refreshExchangeOf(runtime).exchange(refreshToken, Set.of("openid"));

            assertAll("a bound token response passes the check and the engine's validation",
                    () -> assertEquals(accessToken, rotated.accessToken().getRawToken()),
                    () -> assertEquals(refreshToken, rotated.refreshToken(),
                            "the response rotated nothing, so the presented refresh token stays in use"),
                    () -> assertEquals(0, recordsContaining(TestLogLevel.WARN, tokenResponseNotBound()),
                            "an accepted response is not recorded as a refusal"));
        }

        @Test
        @DisplayName("Should keep the published client key apart from the proof key when two keys are in use")
        void shouldKeepThePublishedKeyApartFromTheProofKey() throws Exception {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();

            String publishedKeyId = publishedKeyOf(runtime).path("kid").asText();
            String proofKey = proofKeyThumbprint(proofOf(drive(Leg.CODE_EXCHANGE, runtime)));

            assertNotEquals(publishedKeyId, proofKey,
                    "the client JWKS path publishes the client-authentication key, not the separate DPoP proof key");
        }

        @Test
        @DisplayName("Should publish and prove the same key when both blocks name one key file")
        void shouldUseOneKeyForBothPurposesWhenBothBlocksNameOneFile() throws Exception {
            Path keyFile = TestSigningKeys.writeKeyFile(keyDirectory, TestSigningKeys.ecKeyPair());
            OidcConfig oidc = stubOidc()
                    .clientAuthentication(keyFileSettings(keyFile))
                    .senderConstraint(senderConstraintSettings(keyFile))
                    .build();
            BffRuntime runtime = stubProducer(oidc).bffRuntime();

            String publishedKeyId = publishedKeyOf(runtime).path("kid").asText();
            String proofKey = proofKeyThumbprint(proofOf(drive(Leg.CODE_EXCHANGE, runtime)));

            assertEquals(publishedKeyId, proofKey,
                    "one file is one key: its thumbprint is both the published key id and the proof key");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(RefusedKeyFile.class)
        @DisplayName("Should abort the build with CONFIG_INVALID for a sender-constraint key file the resolver refuses")
        void shouldAbortTheBuildOnARefusedSenderConstraintKeyFile(RefusedKeyFile refused) throws Exception {
            Path keyFile = refused.write(keyDirectory);
            List<String> keyMembers = Files.readAllLines(keyFile, StandardCharsets.US_ASCII).stream()
                    .filter(line -> !line.isBlank() && !line.startsWith("-----"))
                    .toList();
            BffRuntimeProducer producer =
                    stubProducer(stubOidc().senderConstraint(senderConstraintSettings(keyFile)).build());

            GatewayException thrown = assertThrows(GatewayException.class, producer::bffRuntime);

            String message = thrown.getMessage();
            assertAll("the refusal of a " + refused + " sender-constraint key file",
                    () -> assertEquals(EventType.CONFIG_INVALID, thrown.getEventType()),
                    () -> assertTrue(message.contains(SENDER_CONSTRAINT_KEY_FILE_FIELD),
                            "the refusal must name the configuration field: " + message),
                    () -> assertFalse(message.contains(KEY_FILE_FIELD),
                            "and not the client-authentication one, which is not at fault: " + message),
                    () -> assertFalse(keyMembers.isEmpty(), "the fixture must hold content that could leak"),
                    () -> assertTrue(keyMembers.stream().noneMatch(message::contains),
                            "no line of the key file may be echoed: " + message),
                    () -> assertFalse(message.contains(keyFile.toString()),
                            "the configured path is not echoed either: " + message));
        }

        /**
         * The binding check is tied to the assembled runtime by type: whichever token-endpoint client
         * the flows post through, it must be the refusing one, and there must be exactly one — a second
         * client would be a second path to a token, and one the check does not sit on. The walk is
         * type-directed, in the style of the query-mode builder assertion: a field rename does not
         * break it, a flow built over the engine's own client does.
         */
        @Test
        @DisplayName("Should hand every flow the one token-endpoint client that refuses an unbound response")
        void shouldWireTheBoundTokenEndpointClientIntoEveryFlow() {
            List<TokenEndpointClient> keyMode = reachableInstancesOf(
                    stubProducer(stubOidc().build()).bffRuntime(), TokenEndpointClient.class);
            List<TokenEndpointClient> secretMode = reachableInstancesOf(
                    stubProducer(secretFixture().oidc()).bffRuntime(), TokenEndpointClient.class);

            assertAll("the token-endpoint client of the runtime, in both client-authentication modes",
                    () -> assertFalse(keyMode.isEmpty(),
                            "no TokenEndpointClient was reachable from the assembled runtime — this test must "
                                    + "never pass vacuously; if the producer's wiring moved, retarget the walk"),
                    () -> assertEquals(1, keyMode.size(), "key mode shares exactly one client between its flows"),
                    () -> assertInstanceOf(BoundTokenEndpointClient.class, keyMode.getFirst()),
                    () -> assertEquals(1, secretMode.size(),
                            "client-secret mode shares exactly one client between its flows"),
                    () -> assertInstanceOf(BoundTokenEndpointClient.class, secretMode.getFirst(),
                            "the check does not depend on how the client authenticates"));
        }

        @Test
        @DisplayName("Should answer a callback 400 without a session cookie when the token response is of type Bearer")
        void shouldRefuseALoginWhoseTokenResponseIsNotBound() {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();
            stub.script(StubIdentityProvider.Endpoint.TOKEN, tokenAnswer(TYPE_BEARER, token()));

            BffRuntime.ReservedHttpResponse callback = loginAndCallback(runtime, token());

            List<LogRecord> refusals = TestLoggerFactory.getTestHandler()
                    .resolveLogMessagesContaining(TestLogLevel.WARN, tokenResponseNotBound());
            assertAll("a login whose tokens are not bound to the proof key",
                    () -> assertEquals(400, callback.status(), "the login is refused"),
                    () -> assertEquals(List.of(), callback.setCookieHeaders(), "and no session cookie is set"),
                    () -> assertEquals(Optional.empty(), callback.locationOptional(), "nor a redirect issued"),
                    () -> assertEquals(1, refusals.size(), "the refusal is recorded exactly once"),
                    () -> assertTrue(String.valueOf(refusals.getFirst().getMessage())
                                    .contains("on the code-exchange leg"),
                            "under the leg of the code exchange: " + refusals.getFirst().getMessage()),
                    () -> assertEquals(1, stub.received(StubIdentityProvider.Endpoint.TOKEN).size(),
                            "the token endpoint did answer — the refusal is the gateway's"));
        }

        /**
         * What follows from a redeemed refusal — the session destroyed, {@code ApiSheriff-111}, the
         * {@code on_failure} answer — is pinned by {@code TokenRefreshCoordinatorTest} and by the tests
         * of the session stage. This test stops at the seam: the failure the coordinator is handed is
         * one the engine classifies as redeemed.
         */
        @Test
        @DisplayName("Should fail a refresh whose token response is of type Bearer as a redeemed grant")
        void shouldFailARefreshWhoseTokenResponseIsNotBoundAsRedeemed() {
            BffRuntime runtime = stubProducer(stubOidc().build()).bffRuntime();
            TokenRefreshCoordinator.RefreshExchange exchange = refreshExchangeOf(runtime);
            stub.script(StubIdentityProvider.Endpoint.TOKEN, tokenAnswer(TYPE_BEARER, token()));
            String refreshToken = token();
            Set<String> scopes = Set.of("openid");

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> exchange.exchange(refreshToken, scopes));

            List<LogRecord> refusals = TestLoggerFactory.getTestHandler()
                    .resolveLogMessagesContaining(TestLogLevel.WARN, tokenResponseNotBound());
            assertAll("a refresh whose tokens are not bound to the proof key",
                    () -> assertEquals(RefreshFailureClassification.Kind.REDEEMED,
                            RefreshFlow.classify(failure).kind(),
                            "the identity provider consumed the grant, so the session must not be kept"),
                    () -> assertEquals(1, refusals.size(), "the refusal is recorded exactly once"),
                    () -> assertTrue(String.valueOf(refusals.getFirst().getMessage()).contains("on the refresh leg"),
                            "under the leg of the refresh grant: " + refusals.getFirst().getMessage()));
        }

        // Client-secret authentication together with pushed requests and DPoP. Only the client
        // authentication differs in that mode, so each test below drives a runtime built with
        // oidc.client_secret over the legs the key-mode tests above drive, and asserts on the request
        // the stub recorded: the Basic credential in place of the client assertion, and everything else
        // — the pushed request, the proof, the nonce retry, the binding check — unchanged.

        @ParameterizedTest(name = "{0} sender-constraint key")
        @EnumSource(KeyMode.class)
        @DisplayName("Should push the request of a login and of a step-up with the Basic credential in client-secret mode")
        void shouldPushWithTheBasicCredentialInClientSecretMode(KeyMode mode) {
            SecretFixture fixture = secretFixture(mode).credential();
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();
            StepUpChallenge challenge = new StepUpChallenge(STEP_UP_ACR, STEP_UP_MAX_AGE);
            SessionRecord session = sessionToElevate();
            AtomicReference<BffRuntime.ReservedHttpResponse> redirected = new AtomicReference<>();
            AtomicReference<StepUpCoordinator.StepUpOutcome> coordinated = new AtomicReference<>();

            StubIdentityProvider.ReceivedRequest loginPush = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> redirected.set(login(runtime, "/")));
            StubIdentityProvider.ReceivedRequest stepUpPush = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> coordinated.set(runtime.stepUpCoordinator().coordinate(session, challenge, "/orders/42", NOW)));

            assertPresentsTheBasicCredentialOnly(fixture, loginPush, "the pushed request of the login");
            assertPresentsTheBasicCredentialOnly(fixture, stepUpPush, "the pushed request of the step-up");
            assertPushedRedirect(redirected.get().locationOptional().orElseThrow(), fixture.clientId());
            assertPushedRedirect(Objects.requireNonNull(coordinated.get().location(), "location"),
                    fixture.clientId());
            assertAll("the two pushed requests of a client-secret runtime",
                    () -> assertEquals(fixture.clientId(), loginPush.form().get(PARAM_CLIENT_ID)),
                    () -> assertEquals(STEP_UP_ACR, stepUpPush.form().get("acr_values"),
                            "the step-up request is the elevated one"),
                    () -> assertEquals(Optional.empty(), loginPush.header(DPOP_HEADER), "the login push carries no proof"),
                    () -> assertEquals(Optional.empty(), stepUpPush.header(DPOP_HEADER),
                            "the step-up push carries no proof"));
            assertNoRecordCarriesTheSecret(fixture);
        }

        /**
         * The per-scope login flow's exchange has no production caller and is driven by hand, as in
         * {@link #shouldProveOneKeyOnEveryFlow(KeyMode)} and for the same reason — see
         * {@link #loginFlowExchange(BffRuntime)}.
         */
        @ParameterizedTest(name = "{0} sender-constraint key")
        @EnumSource(KeyMode.class)
        @DisplayName("Should present the Basic credential and a DPoP proof of one key on every token leg in client-secret mode")
        void shouldBindEveryTokenLegInClientSecretMode(KeyMode mode) throws Exception {
            SecretKeyFixture keyed = secretFixture(mode);
            SecretFixture fixture = keyed.credential();
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();

            StubIdentityProvider.ReceivedRequest baseExchange = drive(Leg.CODE_EXCHANGE, runtime);
            StubIdentityProvider.ReceivedRequest loginFlowExchange = loginFlowExchange(runtime);
            StubIdentityProvider.ReceivedRequest refresh = drive(Leg.REFRESH_GRANT, runtime);

            assertPresentsTheBasicCredentialOnly(fixture, baseExchange, "the code exchange of the base flow");
            assertPresentsTheBasicCredentialOnly(fixture, loginFlowExchange, "the code exchange of a per-scope flow");
            assertPresentsTheBasicCredentialOnly(fixture, refresh, "the refresh grant");
            String baseFlowKey = assertCarriesAProof(baseExchange, mode);
            String loginFlowKey = assertCarriesAProof(loginFlowExchange, mode);
            String refreshKey = assertCarriesAProof(refresh, mode);
            assertAll("one proof key on every token leg of a client-secret runtime, with a " + mode + " key",
                    () -> assertEquals(baseFlowKey, loginFlowKey,
                            "a per-scope login flow proves possession of the base flow's key"),
                    () -> assertEquals(baseFlowKey, refreshKey,
                            "the refresh grant proves possession of the base flow's key"),
                    () -> keyed.expectedProofKey().ifPresent(expected -> assertEquals(expected, baseFlowKey,
                            "and that key is the configured sender-constraint key")));
            assertNoRecordCarriesTheSecret(fixture);
        }

        @ParameterizedTest(name = "{0} sender-constraint key")
        @EnumSource(KeyMode.class)
        @DisplayName("Should retry a DPoP-Nonce challenge once, with the nonce and the same Basic credential, in client-secret mode")
        void shouldRetryANonceChallengeWithTheSameCredentialInClientSecretMode(KeyMode mode) throws Exception {
            SecretFixture fixture = secretFixture(mode).credential();
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();
            String nonce = token();
            stub.script(StubIdentityProvider.Endpoint.TOKEN, new StubIdentityProvider.Answer(400,
                    Map.of(DPOP_NONCE_HEADER, nonce, "Content-Type", "application/json"),
                    "{\"error\":\"use_dpop_nonce\"}"));

            BffRuntime.ReservedHttpResponse callback = loginAndCallback(runtime, token());

            List<StubIdentityProvider.ReceivedRequest> received =
                    stub.received(StubIdentityProvider.Endpoint.TOKEN);
            assertEquals(2, received.size(), "the challenge is answered by exactly one retry");
            StubIdentityProvider.ReceivedRequest challenged = received.getFirst();
            StubIdentityProvider.ReceivedRequest retried = received.getLast();
            assertPresentsTheBasicCredentialOnly(fixture, challenged, "the challenged code exchange");
            assertPresentsTheBasicCredentialOnly(fixture, retried, "the retried code exchange");
            JsonNode challengedClaims = jwtPart(proofOf(challenged), 1);
            JsonNode retriedClaims = jwtPart(proofOf(retried), 1);
            assertAll("the retry of a DPoP-Nonce challenge in client-secret mode",
                    () -> assertTrue(challengedClaims.path("nonce").isMissingNode(),
                            "the first proof carries no nonce"),
                    () -> assertEquals(nonce, retriedClaims.path("nonce").asText(),
                            "the retry's proof echoes the challenged nonce"),
                    () -> assertNotEquals(challengedClaims.path("jti").asText(), retriedClaims.path("jti").asText(),
                            "the retry carries a fresh single-use proof"),
                    () -> assertEquals(assertCarriesAProof(challenged, mode), assertCarriesAProof(retried, mode),
                            "signed with the same key"),
                    () -> assertEquals(challenged.header(AUTHORIZATION), retried.header(AUTHORIZATION),
                            "and authenticated with the same Basic credential"),
                    () -> assertEquals(400, callback.status(),
                            "the stub refuses the retried grant, so the login is not completed"));
            assertNoRecordCarriesTheSecret(fixture);
        }

        @Test
        @DisplayName("Should revoke with the Basic credential and no DPoP proof in client-secret mode, as in key mode")
        void shouldRevokeWithoutAProofInClientSecretMode() {
            SecretFixture fixture = secretFixture();
            BffRuntime secretRuntime = stubProducer(fixture.oidc()).bffRuntime();
            BffRuntime keyRuntime = stubProducer(stubOidc().build()).bffRuntime();

            StubIdentityProvider.ReceivedRequest secretMode = drive(Leg.REVOCATION, secretRuntime);
            StubIdentityProvider.ReceivedRequest keyMode = drive(Leg.REVOCATION, keyRuntime);

            assertPresentsTheBasicCredentialOnly(fixture, secretMode, "the revocation");
            assertAll("revocation is authenticated, never sender-constrained, in both modes",
                    () -> assertEquals(Optional.empty(), secretMode.header(DPOP_HEADER),
                            "client-secret mode sends no proof with a revocation"),
                    () -> assertEquals(Optional.empty(), keyMode.header(DPOP_HEADER),
                            "and neither does key mode"),
                    () -> assertNotNull(keyMode.form().get(CLIENT_ASSERTION),
                            "the control runtime did authenticate with a client assertion"));
            assertNoRecordCarriesTheSecret(fixture);
        }

        @ParameterizedTest(name = "{0} sender-constraint key")
        @EnumSource(KeyMode.class)
        @DisplayName("Should refuse, in client-secret mode, a refresh whose token response is of type Bearer as a redeemed grant")
        void shouldRefuseAnUnboundRefreshInClientSecretMode(KeyMode mode) throws Exception {
            SecretFixture fixture = secretFixture(mode).credential();
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();
            TokenEndpointClient tokenEndpointClient =
                    single(reachableInstancesOf(runtime, TokenEndpointClient.class), "token-endpoint client");
            TokenRefreshCoordinator.RefreshExchange exchange = refreshExchangeOf(runtime);
            stub.script(StubIdentityProvider.Endpoint.TOKEN, tokenAnswer(TYPE_BEARER, token()));
            String refreshToken = token();
            Set<String> scopes = Set.of("openid");

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> exchange.exchange(refreshToken, scopes));

            List<LogRecord> refusals = TestLoggerFactory.getTestHandler()
                    .resolveLogMessagesContaining(TestLogLevel.WARN, tokenResponseNotBound());
            StubIdentityProvider.ReceivedRequest refused =
                    stub.received(StubIdentityProvider.Endpoint.TOKEN).getLast();
            assertPresentsTheBasicCredentialOnly(fixture, refused, "the refused refresh grant");
            assertCarriesAProof(refused, mode);
            assertAll("the binding check of a client-secret runtime",
                    () -> assertInstanceOf(BoundTokenEndpointClient.class, tokenEndpointClient,
                            "the one token-endpoint client of the runtime is the refusing one"),
                    () -> assertEquals(RefreshFailureClassification.Kind.REDEEMED,
                            RefreshFlow.classify(failure).kind(),
                            "the identity provider consumed the grant, so the session must not be kept"),
                    () -> assertEquals(1, refusals.size(), "the refusal is recorded exactly once"),
                    () -> assertTrue(String.valueOf(refusals.getFirst().getMessage()).contains("on the refresh leg"),
                            "under the leg of the refresh grant: " + refusals.getFirst().getMessage()));
            assertNoRecordCarriesTheSecret(fixture);
        }

        /**
         * The positive control of the refusal above: the same client-secret runtime accepts a response of
         * type {@code DPoP} whose access token names the proof key, so the refusal is of the unbound
         * response and not of client-secret mode. The stub mints no token; the test supplies the body.
         */
        @Test
        @DisplayName("Should accept, in client-secret mode, a refresh whose token response is bound to the proof key (control)")
        void shouldAcceptABoundRefreshInClientSecretMode() throws Exception {
            SecretKeyFixture keyed = secretFixture(KeyMode.PROVIDED_EC);
            SecretFixture fixture = keyed.credential();
            BffRuntime runtime = stubProducer(fixture.oidc()).bffRuntime();
            String accessToken = accessTokenBoundTo(keyed.expectedProofKey().orElseThrow());
            stub.script(StubIdentityProvider.Endpoint.TOKEN, tokenAnswer(TYPE_DPOP, accessToken));
            String refreshToken = token();

            RotationResult rotated = refreshExchangeOf(runtime).exchange(refreshToken, Set.of("openid"));

            StubIdentityProvider.ReceivedRequest accepted =
                    stub.received(StubIdentityProvider.Endpoint.TOKEN).getLast();
            assertPresentsTheBasicCredentialOnly(fixture, accepted, "the accepted refresh grant");
            assertAll("a bound token response passes the check in client-secret mode",
                    () -> assertEquals(accessToken, rotated.accessToken().getRawToken()),
                    () -> assertEquals(0, recordsContaining(TestLogLevel.WARN, tokenResponseNotBound()),
                            "an accepted response is not recorded as a refusal"));
            assertNoRecordCarriesTheSecret(fixture);
        }

        /**
         * Asserts that a recorded request authenticates with the {@code client_secret_basic} credential
         * of {@code fixture} and with nothing else: no client assertion and no secret in the form body.
         */
        private static void assertPresentsTheBasicCredentialOnly(SecretFixture fixture,
                StubIdentityProvider.ReceivedRequest request, String what) {
            Map<String, String> form = request.form();
            String authorization = request.header(AUTHORIZATION).orElse("");
            assertTrue(authorization.startsWith(BASIC_SCHEME),
                    what + " authenticates with an Authorization: Basic header, got: " + request.headers().keySet());
            String credential = new String(Base64.getDecoder().decode(authorization.substring(BASIC_SCHEME.length())),
                    StandardCharsets.UTF_8);
            assertAll("client_secret_basic on " + what,
                    () -> assertEquals(fixture.formEncodedCredential(), credential,
                            "the credential is the form-encoded client id and secret, joined by one colon"),
                    () -> assertFalse(form.containsKey(CLIENT_ASSERTION_TYPE), "no client_assertion_type is sent"),
                    () -> assertFalse(form.containsKey(CLIENT_ASSERTION), "no client_assertion is sent"),
                    () -> assertFalse(form.containsKey(CLIENT_SECRET_PARAMETER),
                            "the secret travels in the header only, never as a form parameter"));
        }

        /**
         * Asserts that a recorded token request carries a DPoP proof — a {@code dpop+jwt} that embeds
         * its public key and is signed with the algorithm of {@code mode}.
         *
         * @return the RFC 7638 thumbprint of the proof key
         */
        private static String assertCarriesAProof(StubIdentityProvider.ReceivedRequest request, KeyMode mode)
                throws IOException {
            String proof = proofOf(request);
            JsonNode header = jwtPart(proof, 0);
            assertAll("the DPoP proof of a token request",
                    () -> assertEquals("dpop+jwt", header.path("typ").asText()),
                    () -> assertEquals(mode.algorithm, header.path("alg").asText(), "the algorithm follows the key type"),
                    () -> assertTrue(header.path("jwk").isObject(), "the proof embeds its public key: " + header));
            return proofKeyThumbprint(proof);
        }

        private static BffRuntime.ReservedHttpResponse clientJwks(BffRuntime runtime, String method) {
            return runtime.dispatch(ReservedEndpoint.CLIENT_JWKS,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null, method), NOW);
        }

        /** The one key a {@code GET} on the client JWKS path publishes, asserting that there is exactly one. */
        private static JsonNode publishedKeyOf(BffRuntime runtime) throws IOException {
            BffRuntime.ReservedHttpResponse response = clientJwks(runtime, "GET");
            assertEquals(200, response.status(), "key mode publishes the client key set");
            assertEquals(Map.of("Cache-Control", "no-store", "Content-Type", "application/json"), response.headers());
            JsonNode document = JSON.readTree(response.jsonBodyOptional().orElseThrow());
            assertEquals(List.of("keys"), memberNamesOf(document), "the document holds the key set and nothing else");
            JsonNode keys = document.path("keys");
            assertEquals(1, keys.size(), "exactly one key is published: " + keys);
            return keys.get(0);
        }

        private static List<String> memberNamesOf(JsonNode object) {
            return object.properties().stream().map(Map.Entry::getKey).toList();
        }

        private static String clientAssertionOf(StubIdentityProvider.ReceivedRequest request) {
            String assertion = request.form().get(CLIENT_ASSERTION);
            assertNotNull(assertion, "the request body carries no client_assertion");
            return assertion;
        }

        /**
         * Verifies a compact JWS against a published JWK with the JDK alone: {@code ES256} over the raw
         * {@code R || S} signature of an EC key, {@code PS256} over an RSA key.
         */
        private static boolean verifies(JsonNode jwk, String compactJws) throws GeneralSecurityException {
            String[] parts = compactJws.split("\\.");
            assertEquals(3, parts.length, "a compact JWS has three parts");
            Signature verifier;
            if (EC_KEY_TYPE.equals(jwk.path("kty").asText())) {
                verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            } else {
                verifier = Signature.getInstance("RSASSA-PSS");
                verifier.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
            }
            verifier.initVerify(publicKeyOf(jwk));
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            return verifier.verify(Base64.getUrlDecoder().decode(parts[2]));
        }

        /** Rebuilds the public key from the published members alone — what an identity provider has to go on. */
        private static PublicKey publicKeyOf(JsonNode jwk) throws GeneralSecurityException {
            if (EC_KEY_TYPE.equals(jwk.path("kty").asText())) {
                assertEquals("P-256", jwk.path("crv").asText(), "the published EC key is on curve P-256");
                AlgorithmParameters parameters = AlgorithmParameters.getInstance(EC_KEY_TYPE);
                parameters.init(new ECGenParameterSpec("secp256r1"));
                return KeyFactory.getInstance(EC_KEY_TYPE).generatePublic(new ECPublicKeySpec(
                        new ECPoint(unsignedMember(jwk, "x"), unsignedMember(jwk, "y")),
                        parameters.getParameterSpec(ECParameterSpec.class)));
            }
            assertEquals("RSA", jwk.path("kty").asText(), "the published key is EC or RSA");
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(unsignedMember(jwk, "n"), unsignedMember(jwk, "e")));
        }

        private static BigInteger unsignedMember(JsonNode jwk, String member) {
            return new BigInteger(1, Base64.getUrlDecoder().decode(jwk.path(member).asText()));
        }

        private StubIdentityProvider.ReceivedRequest drive(Leg leg, BffRuntime runtime) {
            return switch (leg) {
                case PUSHED_REQUEST -> pushedRequest(runtime);
                case CODE_EXCHANGE -> codeExchange(runtime);
                case REFRESH_GRANT -> refreshGrant(runtime);
                case REVOCATION -> revocation(runtime);
            };
        }

        /**
         * Drives one login through the runtime's reserved dispatch and returns the pushed authorization
         * request it sent. The stub accepts the push, so the login answers with the redirect.
         */
        private StubIdentityProvider.ReceivedRequest pushedRequest(BffRuntime runtime) {
            StubIdentityProvider.ReceivedRequest request = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> assertEquals(302, login(runtime, "/").status(),
                            "the stub accepts the push, so the login is redirected to the identity provider"));

            assertEquals("code", request.form().get("response_type"),
                    "the request is the pushed authorization request");
            return request;
        }

        /** Dispatches the reserved login path for {@code returnUrl} on a browser that holds no session. */
        private static BffRuntime.ReservedHttpResponse login(BffRuntime runtime, String returnUrl) {
            return runtime.dispatch(ReservedEndpoint.LOGIN,
                    new BffRuntime.ReservedHttpRequest("", null, null, returnUrl, null, null, "GET"), NOW);
        }

        /** Dispatches the reserved step-up path for {@code returnUrl} on a browser presenting {@code sessionCookie}. */
        private static BffRuntime.ReservedHttpResponse stepUp(BffRuntime runtime, String sessionCookie,
                String returnUrl) {
            return runtime.dispatch(ReservedEndpoint.STEP_UP,
                    new BffRuntime.ReservedHttpRequest("", sessionCookie, null, returnUrl, null, null, "GET"), NOW);
        }

        /** Dispatches the reserved callback path with the raw query and the {@code Cookie} header given. */
        private static BffRuntime.ReservedHttpResponse callback(BffRuntime runtime, String rawQuery,
                String cookieHeader) {
            return runtime.dispatch(ReservedEndpoint.CALLBACK,
                    new BffRuntime.ReservedHttpRequest(rawQuery, cookieHeader, null, null, null, null, "GET"), NOW);
        }

        /** The {@code name=value} pair of the browser-binding cookie a redirect set. */
        private static String bindingCookieOf(BffRuntime.ReservedHttpResponse redirect) {
            assertEquals(1, redirect.setCookieHeaders().size(), "the redirect sets the browser-binding cookie");
            return redirect.setCookieHeaders().getFirst().split(";", 2)[0];
        }

        private static String encoded(@Nullable String value) {
            assertNotNull(value, "the pushed request carries the state the callback has to echo");
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        /** The one session binding of an assembled runtime, located by the bounded walk. */
        private static SessionBinding sessionBindingOf(BffRuntime runtime) {
            return single(reachableInstancesOf(runtime.sessionStage(), SessionBinding.class), "session binding");
        }

        /** The live session {@code sessionCookie} presents to {@code runtime}, as its binding resolves it now. */
        private static SessionRecord liveSessionOf(BffRuntime runtime, String sessionCookie) {
            return sessionBindingOf(runtime).resolve(sessionCookie, NOW).orElseThrow(
                    () -> new AssertionError("the session is no longer resolvable"));
        }

        /** The scope-driven refresh seam the assembled session stage holds. */
        private static SessionAuthenticationStage.ScopeRefresh scopeRefreshSeamOf(BffRuntime runtime) {
            return single(reachableInstancesOf(runtime.sessionStage(), SessionAuthenticationStage.ScopeRefresh.class),
                    "scope-refresh seam");
        }

        /**
         * A session that holds a refresh token and whose active scope set differs from its granted one —
         * the session a scope-driven refresh is made for.
         */
        private static SessionRecord refreshableSession(Set<String> activeScopes, Set<String> grantedScopes,
                @Nullable String refreshToken) {
            return SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken(token())
                    .refreshToken(refreshToken)
                    .idToken(token())
                    .sub(SUBJECT)
                    .expiresAt(NOW.plusSeconds(3600))
                    .activeScopes(activeScopes)
                    .grantedScopes(grantedScopes)
                    .build();
        }

        /**
         * A session without a refresh token whose active and granted scope sets are both {@code scopes},
         * valid at the fixed instant the reserved dispatch of these tests is driven with — the session a
         * widening started at the step-up path is made for.
         */
        private static SessionRecord sessionGranted(Set<String> scopes) {
            return refreshableSession(scopes, scopes, null);
        }

        /** Binds {@code session} through the runtime's own binding and returns the cookie pair presenting it. */
        private static String bind(BffRuntime runtime, SessionRecord session) {
            return sessionBindingOf(runtime).bind(session, NOW).setCookieHeaders().getFirst().split(";", 2)[0];
        }

        /**
         * Drives the code exchange through the runtime's reserved login and callback dispatch: the login
         * leg pushes the {@code state} and yields the browser-binding cookie, and the callback presents
         * both with an authorization code. The stub refuses the grant, so the callback answers
         * {@code 400}.
         */
        private StubIdentityProvider.ReceivedRequest codeExchange(BffRuntime runtime) {
            String code = token();

            StubIdentityProvider.ReceivedRequest request = receivedBy(StubIdentityProvider.Endpoint.TOKEN,
                    () -> assertEquals(400, loginAndCallback(runtime, code).status(),
                            "the stub refuses the grant, so the login is not completed"));

            assertAll("the request is the code exchange",
                    () -> assertEquals("authorization_code", request.form().get("grant_type")),
                    () -> assertEquals(code, request.form().get("code")));
            return request;
        }

        /**
         * Drives one login through the runtime's reserved dispatch — the login leg, then the callback
         * presenting {@code code} with the login's {@code state} and browser-binding cookie — and
         * returns the callback's answer. The token endpoint answers from the stub's script, or refuses
         * the grant when nothing is scripted.
         * <p>
         * The redirect of a pushed request carries no {@code state}, so the callback's {@code state} is
         * taken from where the identity provider has it: the form body the stub's
         * pushed-authorization-request endpoint recorded for this login.
         */
        private BffRuntime.ReservedHttpResponse loginAndCallback(BffRuntime runtime, String code) {
            AtomicReference<BffRuntime.ReservedHttpResponse> redirected = new AtomicReference<>();
            StubIdentityProvider.ReceivedRequest pushed = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> redirected.set(login(runtime, "/")));
            String state = pushed.form().get(PARAM_STATE);
            assertNotNull(state, "the pushed request carries the state the callback has to echo");
            String bindingCookie = redirected.get().setCookieHeaders().getFirst().split(";", 2)[0];
            return runtime.dispatch(ReservedEndpoint.CALLBACK,
                    new BffRuntime.ReservedHttpRequest(
                            "code=" + code + "&state=" + URLEncoder.encode(state, StandardCharsets.UTF_8),
                            bindingCookie, null, null, null, null, "GET"),
                    NOW);
        }

        /**
         * Starts a widening of the session {@code sessionCookie} presents at the step-up path and drives
         * its callback with an authorization code, the widening's {@code state} and both cookies. The
         * token endpoint is scripted to answer the exchange with a token response of type
         * {@code Bearer}, which the stub would otherwise refuse as a grant.
         */
        private WideningCallback wideningAnsweredWithABearerToken(BffRuntime runtime, String sessionCookie) {
            AtomicReference<BffRuntime.ReservedHttpResponse> redirected = new AtomicReference<>();
            StubIdentityProvider.ReceivedRequest pushed = receivedBy(
                    StubIdentityProvider.Endpoint.PUSHED_AUTHORIZATION_REQUEST,
                    () -> redirected.set(stepUp(runtime, sessionCookie, SCOPED_ROUTE_PREFIX + "/list")));
            String code = token();
            String query = "code=" + code + "&state=" + encoded(pushed.form().get(PARAM_STATE));
            String cookies = bindingCookieOf(redirected.get()) + "; " + sessionCookie;
            stub.script(StubIdentityProvider.Endpoint.TOKEN, tokenAnswer(TYPE_BEARER, token()));
            AtomicReference<BffRuntime.ReservedHttpResponse> answered = new AtomicReference<>();

            StubIdentityProvider.ReceivedRequest exchange = receivedBy(StubIdentityProvider.Endpoint.TOKEN,
                    () -> answered.set(callback(runtime, query, cookies)));

            return new WideningCallback(pushed, code, exchange, answered.get());
        }

        /**
         * Drives a code exchange on the per-scope login flow the runtime cached, by hand.
         * <p>
         * In a running gateway that flow only renders the authorization URL: the callback's exchange
         * runs on the base flow. So no dispatch reaches this exchange, and the flow sits in a
         * {@link java.util.concurrent.ConcurrentHashMap} the reachable-instances walk does not enter.
         * The cached flow is therefore read from {@code ScopedEngineFlows.authorizationFlows} by name
         * and driven directly against the stub's token endpoint, with the client authentication the
         * runtime itself holds. The stub refuses the grant; the request it recorded is what is asserted.
         *
         * @param runtime a runtime whose login leg has already been dispatched once, so that exactly
         *                one per-scope flow is cached
         * @return the token request the per-scope flow sent
         */
        private StubIdentityProvider.ReceivedRequest loginFlowExchange(BffRuntime runtime) {
            AuthorizationCodeFlow loginFlow = cachedLoginFlowOf(runtime);
            ClientAuthentication authentication = single(
                    reachableInstancesOf(runtime, ClientAuthentication.class), "client authentication");
            ProviderMetadata metadata = stubMetadata();
            FlowContext context = loginFlow.authorize(metadata).context();
            CallbackParameters callback = CallbackParameters.parse("code=" + token() + "&state=" + context.state());

            StubIdentityProvider.ReceivedRequest request = receivedBy(StubIdentityProvider.Endpoint.TOKEN,
                    () -> assertThrows(TransportException.class,
                            () -> loginFlow.exchange(metadata, context, callback, authentication),
                            "the stub refuses the grant"));

            assertEquals("authorization_code", request.form().get("grant_type"),
                    "the request is the per-scope flow's code exchange");
            return request;
        }

        /** The one per-scope login flow the runtime cached, read from the seam's cache field by name. */
        private static AuthorizationCodeFlow cachedLoginFlowOf(BffRuntime runtime) {
            ScopedEngineFlows scopedFlows = single(reachableInstancesOf(runtime, ScopedEngineFlows.class),
                    "per-scope engine flow seam");
            try {
                Field cache = ScopedEngineFlows.class.getDeclaredField("authorizationFlows");
                cache.setAccessible(true);
                Map<?, ?> flows = (Map<?, ?>) cache.get(scopedFlows);
                assertEquals(1, flows.size(),
                        "the login leg cached exactly the one flow of the scope set it requested");
                return assertInstanceOf(AuthorizationCodeFlow.class, flows.values().iterator().next());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("ScopedEngineFlows no longer holds an authorizationFlows field — "
                        + "retarget this read to where the per-scope login flows are cached", e);
            }
        }

        /** The provider metadata of the stub, hand-built: the issuer, the token endpoint and PKCE S256. */
        private ProviderMetadata stubMetadata() {
            ProviderMetadata metadata = new ProviderMetadata();
            metadata.issuer = stub.issuer();
            metadata.authorizationEndpoint = stub.issuer() + "/authorize";
            metadata.tokenEndpoint = stub.url(StubIdentityProvider.Endpoint.TOKEN);
            metadata.codeChallengeMethodsSupported = List.of(ProviderMetadata.CODE_CHALLENGE_METHOD_S256);
            return metadata;
        }

        private TokenRefreshCoordinator.RefreshExchange refreshExchangeOf(BffRuntime runtime) {
            return single(reachableInstancesOf(refreshCoordinatorOf(runtime),
                    TokenRefreshCoordinator.RefreshExchange.class), "refresh exchange the coordinator holds");
        }

        /** The compact DPoP proof a recorded token request carried. */
        private static String proofOf(StubIdentityProvider.ReceivedRequest request) {
            return request.header(DPOP_HEADER).orElseThrow(
                    () -> new AssertionError("the token request carries no DPoP header: "
                            + request.headers().keySet()));
        }

        /** The RFC 7638 thumbprint of the public key a DPoP proof embeds in its header. */
        private static String proofKeyThumbprint(String proof) throws IOException {
            Map<String, Object> jwk = JSON.convertValue(jwtPart(proof, 0).path("jwk"),
                    new TypeReference<Map<String, Object>>() {
                    });
            return JwkThumbprintUtil.computeThumbprint(jwk);
        }

        /** A success answer of the token endpoint carrying {@code accessToken} under {@code tokenType}. */
        private static StubIdentityProvider.Answer tokenAnswer(String tokenType, String accessToken) {
            ObjectNode body = JSON.createObjectNode()
                    .put("access_token", accessToken)
                    .put("token_type", tokenType)
                    .put("expires_in", 300);
            return StubIdentityProvider.Answer.json(200, body.toString());
        }

        /**
         * An access token the runtime's validator accepts, carrying a {@code cnf} object that names
         * {@code thumbprint}. The stub mints no token, so the test issues one: the claims of a
         * generated test token plus the confirmation claim, signed with the test issuer's own key.
         * The test token library renders a claim as a string or a list, never as an object, which is
         * why the claim is added here rather than through the holder.
         */
        private static String accessTokenBoundTo(String thumbprint) throws Exception {
            TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
            String issued = holder.getRawToken();
            String[] segments = issued.split("\\.");
            assertEquals("RS256", jwtPart(issued, 0).path("alg").asText(),
                    "this helper signs with SHA256withRSA; a holder signing another algorithm needs its own");
            ObjectNode claims = (ObjectNode) jwtPart(issued, 1);
            claims.putObject("cnf").put("jkt", thumbprint);
            String signingInput = segments[0] + "."
                    + Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(claims));
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(InMemoryKeyMaterialHandler.getPrivateKey(holder.getSigningAlgorithm(), holder.getKeyId()));
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        }

        /**
         * A key-mode configuration whose {@code sender_constraint} block follows {@code mode}, together
         * with the proof-key thumbprint every proof must then carry — known for a provided key, whose
         * thumbprint the test computes itself, and unknown for a generated one.
         */
        private KeyFixture senderConstraintFixture(KeyMode mode) {
            Supplier<KeyPair> providedKey = mode.providedKey;
            if (providedKey == null) {
                return new KeyFixture(stubOidc().build(), Optional.empty());
            }
            KeyPair keyPair = providedKey.get();
            Path keyFile = TestSigningKeys.writeKeyFile(keyDirectory, keyPair);
            OidcConfig oidc = stubOidc().senderConstraint(senderConstraintSettings(keyFile)).build();
            return new KeyFixture(oidc, Optional.of(new DpopProofGenerator(keyPair, mode.algorithm).jkt()));
        }

        /** A {@code sender_constraint} block naming {@code keyFile}. */
        private static OidcConfig.SenderConstraintSettings senderConstraintSettings(Path keyFile) {
            return new OidcConfig.SenderConstraintSettings(keyFile.toString());
        }

        private static String tokenResponseNotBound() {
            return BffLogMessages.WARN.TOKEN_RESPONSE_NOT_BOUND.resolveIdentifierString();
        }

        private StubIdentityProvider.ReceivedRequest refreshGrant(BffRuntime runtime) {
            String refreshToken = token();
            Set<String> scopes = Set.of("openid");
            TokenRefreshCoordinator.RefreshExchange exchange = refreshExchangeOf(runtime);

            StubIdentityProvider.ReceivedRequest request = receivedBy(StubIdentityProvider.Endpoint.TOKEN,
                    () -> assertThrows(RuntimeException.class, () -> exchange.exchange(refreshToken, scopes),
                            "the stub refuses the grant"));

            assertAll("the request is the refresh grant",
                    () -> assertEquals("refresh_token", request.form().get("grant_type")),
                    () -> assertEquals(refreshToken, request.form().get("refresh_token")));
            return request;
        }

        private StubIdentityProvider.ReceivedRequest revocation(BffRuntime runtime) {
            String refreshToken = token();
            TokenRefreshCoordinator.RefreshTokenRevocation revocation = single(
                    reachableInstancesOf(refreshCoordinatorOf(runtime),
                            TokenRefreshCoordinator.RefreshTokenRevocation.class),
                    "revocation seam the coordinator holds");

            StubIdentityProvider.ReceivedRequest request = receivedBy(StubIdentityProvider.Endpoint.REVOCATION,
                    () -> revocation.revoke(refreshToken));

            assertEquals(refreshToken, request.form().get("token"), "the request revokes the presented token");
            return request;
        }

        private TokenRefreshCoordinator refreshCoordinatorOf(BffRuntime runtime) {
            return single(reachableInstancesOf(runtime, TokenRefreshCoordinator.class), "refresh coordinator");
        }

        /** Runs one leg and returns the single request it sent to {@code endpoint}. */
        private StubIdentityProvider.ReceivedRequest receivedBy(StubIdentityProvider.Endpoint endpoint, Runnable leg) {
            int before = stub.received(endpoint).size();
            leg.run();
            List<StubIdentityProvider.ReceivedRequest> received = stub.received(endpoint);
            assertEquals(before + 1, received.size(),
                    "the leg sends exactly one request to the " + endpoint + " endpoint");
            return received.getLast();
        }

        private OidcConfig.OidcConfigBuilder stubOidc() {
            return OidcConfig.builder()
                    .issuer(stub.issuer())
                    .clientId(CLIENT_ID)
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .login(OidcConfig.Login.builder().path("/auth/login").build());
        }

        /** A producer whose back-channel reaches the stub through the named trust profile alone. */
        private BffRuntimeProducer stubProducer(OidcConfig oidc) {
            return producer(oidc, new EgressTlsConfig(true, true, null, true, PROFILE),
                    TestTlsConfigurationRegistry.withAnchor(PROFILE, stub.rootCertificate()));
        }

        private KeyFixture keyFixture(KeyMode mode) {
            Supplier<KeyPair> providedKey = mode.providedKey;
            if (providedKey == null) {
                return new KeyFixture(stubOidc().build(), Optional.empty());
            }
            KeyPair keyPair = providedKey.get();
            Path keyFile = TestSigningKeys.writeKeyFile(keyDirectory, keyPair);
            OidcConfig oidc = stubOidc().clientAuthentication(keyFileSettings(keyFile)).build();
            return new KeyFixture(oidc, Optional.of(new DpopProofGenerator(keyPair, mode.algorithm).jkt()));
        }

        /** A {@code client_authentication} block naming {@code keyFile} and declaring no {@code jwks_path}. */
        private static OidcConfig.ClientAuthenticationSettings keyFileSettings(Path keyFile) {
            return OidcConfig.ClientAuthenticationSettings.builder().keyFile(keyFile.toString()).build();
        }

        /**
         * A client id and a secret that both carry characters the form encoding has to escape — a space,
         * a colon, and the reserved characters of a form body — so an unencoded credential and a
         * form-encoded one cannot be the same string.
         */
        private SecretFixture secretFixture() {
            String clientId = "gateway client:" + Generators.letterStrings(4, 8).next();
            String secret = Generators.letterStrings(16, 32).next() + "+/ :&=%" + Generators.letterStrings(4, 8).next();
            return new SecretFixture(stubOidc().clientId(clientId).clientSecret(secret).build(), clientId, secret);
        }

        /**
         * The client-secret fixture with a sender-constraint key resolved the way {@code mode} says: a
         * key file of the mode's key type, or no {@code sender_constraint} block at all for a generated
         * key. No {@code client_authentication} block is declared — the secret authenticates.
         */
        private SecretKeyFixture secretFixture(KeyMode mode) {
            SecretFixture credential = secretFixture();
            Supplier<KeyPair> providedKey = mode.providedKey;
            if (providedKey == null) {
                return new SecretKeyFixture(credential, Optional.empty());
            }
            KeyPair keyPair = providedKey.get();
            Path keyFile = TestSigningKeys.writeKeyFile(keyDirectory, keyPair);
            OidcConfig oidc = stubOidc().clientId(credential.clientId()).clientSecret(credential.secret())
                    .senderConstraint(senderConstraintSettings(keyFile)).build();
            return new SecretKeyFixture(new SecretFixture(oidc, credential.clientId(), credential.secret()),
                    Optional.of(new DpopProofGenerator(keyPair, mode.algorithm).jkt()));
        }

        private String keyIdOf(StubIdentityProvider.ReceivedRequest request) throws IOException {
            return jwtPart(clientAssertionOf(request), 0).path("kid").asText();
        }

        private static JsonNode jwtPart(String compactJwt, int index) throws IOException {
            return JSON.readTree(Base64.getUrlDecoder().decode(compactJwt.split("\\.")[index]));
        }

        /** The decoded query parameters of {@code url}, in URL order; a repeated name fails the test. */
        private static Map<String, String> queryParametersOf(String url) {
            Map<String, String> parameters = new LinkedHashMap<>();
            for (String pair : URI.create(url).getRawQuery().split("&")) {
                String[] nameValue = pair.split("=", 2);
                String name = URLDecoder.decode(nameValue[0], StandardCharsets.UTF_8);
                assertNull(parameters.put(name, nameValue.length == 2
                                ? URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8) : ""),
                        "the URL names " + name + " twice: " + url);
            }
            return parameters;
        }

        /**
         * Asserts that {@code location} is the redirect of a pushed request: the stub's authorization
         * endpoint with {@code client_id} and {@code request_uri}, and nothing else.
         *
         * @return the {@code request_uri} the redirect carries
         */
        private String assertPushedRedirect(String location, String clientId) {
            Map<String, String> query = queryParametersOf(location);
            assertAll("the redirect of a pushed authorization request",
                    () -> assertEquals(stub.issuer() + "/authorize", location.substring(0, location.indexOf('?')),
                            "the browser is sent to the authorization endpoint"),
                    () -> assertEquals(PUSHED_REDIRECT_PARAMETERS, List.copyOf(query.keySet()),
                            "the redirect carries client_id and request_uri and nothing else"),
                    () -> assertEquals(clientId, query.get(PARAM_CLIENT_ID)),
                    () -> assertTrue(String.valueOf(query.get(PARAM_REQUEST_URI)).startsWith(REQUEST_URI_PREFIX),
                            "the request_uri is the identity provider's: " + query.get(PARAM_REQUEST_URI)));
            return query.get(PARAM_REQUEST_URI);
        }

        /** The scope set a recorded pushed authorization request asks for. */
        private static Set<String> scopeOf(StubIdentityProvider.ReceivedRequest pushed) {
            String scope = pushed.form().get("scope");
            assertNotNull(scope, "the pushed request carries no scope parameter: " + pushed.form().keySet());
            return Set.of(scope.split(" "));
        }

        /** A request-URI a test scripts, so the redirect can be shown to carry exactly what the stub answered. */
        private static String scriptedRequestUri() {
            return REQUEST_URI_PREFIX + Generators.letterStrings(16, 24).next();
        }

        private static StubIdentityProvider.Answer pushAccepted(String requestUri) {
            return StubIdentityProvider.Answer.json(201,
                    JSON.createObjectNode().put(PARAM_REQUEST_URI, requestUri).put("expires_in", 60).toString());
        }

        /**
         * An active runtime that reaches the stub through the named trust profile, over a route table
         * carrying two scoped session routes whose needed scope sets differ.
         */
        private BffRuntime scopedRuntime() {
            return producer(stubOidc().build(), new EgressTlsConfig(true, true, null, true, PROFILE),
                    TestTlsConfigurationRegistry.withAnchor(PROFILE, stub.rootCertificate()),
                    new RouteTable(List.of(
                            scopedRoute("orders", SCOPED_ROUTE_PREFIX, SCOPED_NEEDED_SCOPES),
                            scopedRoute("invoices", OTHER_SCOPED_ROUTE_PREFIX, OTHER_SCOPED_NEEDED_SCOPES))))
                    .bffRuntime();
        }

        private static ResolvedRoute scopedRoute(String id, String pathPrefix, Set<String> neededScopes) {
            return ResolvedRoute.builder()
                    .id(id)
                    .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                    .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.GET))
                    .upstream(new ResolvedUpstream("https", id + ".example", 443, ""))
                    .neededScopes(neededScopes)
                    .build();
        }

        /** A live session with no authentication context, the session an upstream step-up challenge meets. */
        private static SessionRecord sessionToElevate() {
            return SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken(token())
                    .idToken(token())
                    .sub(SUBJECT)
                    .expiresAt(NOW.plusSeconds(3600))
                    .build();
        }

        private static String clientSecretWarning() {
            return ConfigLogMessages.WARN.OIDC_CLIENT_SECRET_AUTHENTICATION.resolveIdentifierString();
        }

        private static String pushRefused() {
            return BffLogMessages.WARN.AUTHORIZATION_PUSH_REFUSED.resolveIdentifierString();
        }

        /** The one pending-authorization store of an assembled runtime, located by the bounded walk. */
        private static PendingAuthorizationStore.InMemory pendingStoreOf(BffRuntime runtime) {
            return single(reachableInstancesOf(runtime, PendingAuthorizationStore.InMemory.class), "pending store");
        }

        private static int recordsContaining(TestLogLevel level, String part) {
            return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(level, part).size();
        }

        /**
         * Asserts that no record captured so far, down to {@code DEBUG}, carries the secret — neither as
         * written, nor form-encoded, nor inside the Basic credential — in its message or in the message
         * of a throwable it chains.
         * <p>
         * The control comes first: a {@code DEBUG} record of the producer, of the two gateway classes on
         * the pushed-request and token legs, and of the two engine clients those legs send through is
         * captured, so the records read below include the {@code DEBUG} output of the code that holds
         * the secret.
         */
        private void assertNoRecordCarriesTheSecret(SecretFixture fixture) {
            SheriffDebugCapture.assertDebugIsCaptured(BffRuntimeProducer.class, PushedAuthorizationRequests.class,
                    BoundTokenEndpointClient.class, ParClient.class, TokenEndpointClient.class);
            List<LogRecord> records = SheriffDebugCapture.capturedRecords();
            assertFalse(records.isEmpty(),
                    "no record was captured at all, so the absence of the secret would prove nothing");
            List<String> forbidden = List.of(fixture.secret(),
                    URLEncoder.encode(fixture.secret(), StandardCharsets.UTF_8), fixture.basicCredential());
            assertAll("no captured record carries the client secret",
                    records.stream().map(captured -> (Executable) () -> {
                        String rendered = SheriffDebugCapture.rendered(captured);
                        assertTrue(forbidden.stream().noneMatch(rendered::contains),
                                "a " + captured.getLevel() + " record of " + captured.getLoggerName()
                                        + " carries the client secret");
                    }));
        }
    }

    @Nested
    @DisplayName("Gateway-origin derivation (default-port normalization)")
    class OriginDerivation {

        @Test
        @DisplayName("Should drop the default https port 443 so the origin matches a browser Origin header")
        void shouldNormalizeHttpsDefaultPort() {
            assertEquals("https://gw.example.com",
                    BffRuntimeProducer.originOf("https://gw.example.com:443/auth/callback"));
        }

        @Test
        @DisplayName("Should drop the default http port 80 so the origin matches a browser Origin header")
        void shouldNormalizeHttpDefaultPort() {
            assertEquals("http://gw.example.com",
                    BffRuntimeProducer.originOf("http://gw.example.com:80/auth/callback"));
        }

        @Test
        @DisplayName("Should preserve a non-default explicit port and a portless URL")
        void shouldPreserveNonDefaultPort() {
            assertEquals("https://gw.example.com:8443",
                    BffRuntimeProducer.originOf("https://gw.example.com:8443/auth/callback"));
            assertEquals("https://gw.example.com",
                    BffRuntimeProducer.originOf("https://gw.example.com/auth/callback"));
            assertEquals("http://gw.example.com:8080",
                    BffRuntimeProducer.originOf("http://gw.example.com:8080/auth/callback"));
        }

        @Test
        @DisplayName("Should reject a redirect_uri that is not an absolute URI")
        void shouldRejectRelativeRedirectUri() {
            assertThrows(IllegalStateException.class, () -> BffRuntimeProducer.originOf("/auth/callback"));
        }
    }

    private BffRuntimeProducer producer(@Nullable OidcConfig oidc) {
        return producer(oidc, null, TestTlsConfigurationRegistry.empty());
    }

    private BffRuntimeProducer producer(@Nullable OidcConfig oidc, @Nullable EgressTlsConfig egressTls,
            TestTlsConfigurationRegistry registry) {
        return producer(oidc, egressTls, registry, new RouteTable(List.of()));
    }

    private BffRuntimeProducer producer(@Nullable OidcConfig oidc, @Nullable EgressTlsConfig egressTls,
            TestTlsConfigurationRegistry registry, RouteTable routeTable) {
        return producer(oidc, egressTls, registry, routeTable, new RecordingTimers());
    }

    /** A producer whose periodic timers are recorded by {@code timers} instead of being scheduled. */
    private BffRuntimeProducer producer(@Nullable OidcConfig oidc, @Nullable EgressTlsConfig egressTls,
            TestTlsConfigurationRegistry registry, RouteTable routeTable, RecordingTimers timers) {
        GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(oidc).egressTls(egressTls).build();
        return new BffRuntimeProducer(gatewayConfig, routeTable, new SingletonInstance<>(tokenValidator),
                new SingletonInstance<>(logoutTokenVerifier), new JwksTrustProfileResolver(registry),
                REVOCATION_EXECUTOR, new GatewayJson(new ObjectMapper()), timers.vertx());
    }

    /**
     * Stands in for the Quarkus-managed Vert.x instance the producer registers its periodic session
     * sweep on. It records every periodic timer and every cancellation and schedules nothing, so a test
     * fires the timer itself instead of waiting for the sweep interval. Every other Vert.x operation is
     * unreachable from the producer and says so.
     */
    private static final class RecordingTimers implements InvocationHandler {

        /** One periodic timer the producer registered. */
        record Periodic(long id, long delayMillis, Handler<?> handler) {
        }

        private final List<Periodic> registered = new CopyOnWriteArrayList<>();
        private final List<Long> cancelled = new CopyOnWriteArrayList<>();
        private final AtomicLong timerIds = new AtomicLong(100);
        private final Vertx vertx = (Vertx) Proxy.newProxyInstance(Vertx.class.getClassLoader(),
                new Class<?>[]{Vertx.class}, this);

        Vertx vertx() {
            return vertx;
        }

        List<Periodic> registered() {
            return registered;
        }

        List<Long> cancelled() {
            return cancelled;
        }

        /** Fires the one registered periodic timer once, on the calling thread, as an event loop would. */
        void fireOnce() {
            assertEquals(1, registered.size(), "exactly one periodic timer must be registered to fire it");
            Periodic periodic = registered.getFirst();
            try {
                Handler.class.getMethod("handle", Object.class).invoke(periodic.handler(), periodic.id());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("the periodic timer handler could not be fired", e);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("setPeriodic".equals(method.getName())) {
                long id = timerIds.incrementAndGet();
                registered.add(new Periodic(id, (Long) args[args.length - 2], (Handler<?>) args[args.length - 1]));
                return id;
            }
            if ("cancelTimer".equals(method.getName())) {
                cancelled.add((Long) args[0]);
                return Boolean.TRUE;
            }
            throw new UnsupportedOperationException(
                    "the producer must not use Vertx#" + method.getName() + " — only the periodic sweep timer");
        }
    }

    /**
     * The session lifetime the producer wires: the resolved idle timeout reaches the binding of either
     * mode, a server-mode runtime sweeps its store on a periodic timer that is cancelled at shutdown, and
     * the activity-cookie codec exists in cookie mode only.
     */
    @Nested
    @DisplayName("Session lifetime wiring — idle timeout, periodic sweep, activity cookie")
    class SessionLifetimeWiring {

        private static final Instant LOGIN = Instant.parse("2026-07-25T10:00:00Z");

        private final RecordingTimers timers = new RecordingTimers();

        private BffRuntimeProducer producerFor(@Nullable OidcConfig oidc) {
            return producer(oidc, null, TestTlsConfigurationRegistry.empty(), new RouteTable(List.of()), timers);
        }

        private static OidcConfig oidc(String mode, int ttlSeconds, @Nullable Integer idleTimeoutSeconds) {
            byte[] key = new byte[32];
            Arrays.fill(key, (byte) 0x11);
            OidcConfig.Session session = OidcConfig.Session.builder()
                    .mode(mode)
                    .ttlSeconds(ttlSeconds)
                    .idleTimeoutSeconds(idleTimeoutSeconds)
                    .encryptionKey("cookie".equals(mode) ? Base64.getEncoder().encodeToString(key) : null)
                    .build();
            return OidcConfig.builder()
                    .issuer(ISSUER)
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(session)
                    .build();
        }

        private static SessionRecord sessionExpiringAt(Instant expiresAt) {
            return SessionRecord.builder()
                    .sessionId(SessionRecord.newSessionId())
                    .accessToken(token())
                    .idToken(token())
                    .sub(SUBJECT)
                    .expiresAt(expiresAt)
                    .build();
        }

        private static <T> T theOne(List<T> found, String what) {
            assertEquals(1, found.size(), "exactly one " + what + " must be reachable from the assembled runtime");
            return found.getFirst();
        }

        /** The three ways the idle timeout is resolved: declared, omitted under a long ttl, omitted under a short one. */
        static Stream<Arguments> idleTimeouts() {
            return Stream.of(
                    Arguments.of("declared 120 s under ttl 3600 s", 3600, 120, 120),
                    Arguments.of("omitted under ttl 7200 s resolves to 1800 s", 7200, null, 1800),
                    Arguments.of("omitted under ttl 600 s resolves to the ttl", 600, null, 600));
        }

        @ParameterizedTest(name = "server mode: {0}")
        @MethodSource("idleTimeouts")
        @DisplayName("Should hand the resolved idle timeout to the store behind the server-mode binding")
        void shouldHandIdleTimeoutToTheServerModeStore(String label, int ttlSeconds, @Nullable Integer declared,
                int expectedIdleSeconds) {
            BffRuntime runtime = producerFor(oidc("server", ttlSeconds, declared)).bffRuntime();
            InMemorySessionStore store = theOne(reachableInstancesOf(runtime, InMemorySessionStore.class),
                    "session store");
            // The absolute expiry lies far beyond every idle deadline here, so only the idle timeout decides.
            store.create(sessionExpiringAt(LOGIN.plus(Duration.ofDays(1))), "cookie-handle", LOGIN);
            Instant idleDeadline = LOGIN.plusSeconds(expectedIdleSeconds);

            assertAll(label,
                    () -> assertTrue(store.resolve("cookie-handle", idleDeadline.minusSeconds(1)).isPresent(),
                            "the session is live up to the resolved idle deadline"),
                    () -> assertTrue(store.resolve("cookie-handle", idleDeadline).isEmpty(),
                            "and gone at it"));
        }

        @ParameterizedTest(name = "cookie mode: {0}")
        @MethodSource("idleTimeouts")
        @DisplayName("Should hand the resolved idle timeout to the cookie-mode binding")
        void shouldHandIdleTimeoutToTheCookieModeBinding(String label, int ttlSeconds, @Nullable Integer declared,
                int expectedIdleSeconds) {
            BffRuntime runtime = producerFor(oidc("cookie", ttlSeconds, declared)).bffRuntime();
            CookieSessionBinding binding = theOne(reachableInstancesOf(runtime, CookieSessionBinding.class),
                    "cookie-mode binding");
            String setCookie = binding.bind(sessionExpiringAt(LOGIN.plusSeconds(ttlSeconds)), LOGIN)
                    .setCookieHeaders().getFirst();
            String cookie = setCookie.substring(0, setCookie.indexOf(';'));
            Instant idleDeadline = LOGIN.plusSeconds(expectedIdleSeconds);

            assertAll(label,
                    () -> assertTrue(binding.resolve(cookie, idleDeadline.minusSeconds(1)).isPresent(),
                            "the session is live up to the resolved idle deadline"),
                    () -> assertTrue(binding.resolve(cookie, idleDeadline).isEmpty(), "and gone at it"));
        }

        @Test
        @DisplayName("Should register one periodic sweep timer at the sweep interval in server mode")
        void shouldRegisterTheSweepTimerInServerMode() {
            producerFor(oidc("server", 3600, null)).bffRuntime();

            assertEquals(1, timers.registered().size(), "a server-mode runtime sweeps its store periodically");
            assertEquals(BffRuntimeProducer.SESSION_SWEEP_INTERVAL.toMillis(),
                    timers.registered().getFirst().delayMillis(), "at the fixed sweep interval");
            assertEquals(60_000L, BffRuntimeProducer.SESSION_SWEEP_INTERVAL.toMillis(),
                    "an expired session leaves memory within 60 s");
            assertTrue(timers.cancelled().isEmpty(), "and the timer stays registered while the gateway runs");
        }

        @Test
        @DisplayName("Should register no sweep timer in cookie mode and on a bearer-only gateway")
        void shouldRegisterNoSweepTimerOutsideServerMode() {
            BffRuntime cookieMode = producerFor(oidc("cookie", 3600, null)).bffRuntime();
            BffRuntime bearerOnly = producerFor(null).bffRuntime();

            assertTrue(cookieMode.isActive(), "precondition: the cookie-mode runtime was assembled");
            assertFalse(bearerOnly.isActive(), "precondition: a gateway without oidc builds no runtime");
            assertTrue(timers.registered().isEmpty(), "neither holds a session to sweep");
        }

        @Test
        @DisplayName("Should cancel the sweep timer at shutdown, once")
        void shouldCancelTheSweepTimerAtShutdown() {
            BffRuntimeProducer serverMode = producerFor(oidc("server", 3600, null));
            serverMode.bffRuntime();
            long timerId = timers.registered().getFirst().id();

            serverMode.cancelSessionSweep();
            serverMode.cancelSessionSweep();

            assertEquals(List.of(timerId), timers.cancelled(),
                    "the registered timer is cancelled, and a second shutdown call cancels nothing more");
        }

        @Test
        @DisplayName("Should cancel nothing at shutdown when no sweep timer was registered")
        void shouldCancelNothingWithoutASweepTimer() {
            BffRuntimeProducer cookieMode = producerFor(oidc("cookie", 3600, null));
            cookieMode.bffRuntime();

            cookieMode.cancelSessionSweep();

            assertTrue(timers.cancelled().isEmpty());
        }

        /**
         * The session is never looked up: it is created already expired and only the timer is fired. The
         * sweep runs on the executor the producer was given, off the thread that fired the timer, so the
         * removal is awaited as a condition.
         */
        @Test
        @DisplayName("Should remove an expired session through the periodic task alone, without any lookup")
        void shouldSweepAnExpiredSessionWithoutALookup() throws Exception {
            BffRuntime runtime = producerFor(oidc("server", 3600, null)).bffRuntime();
            InMemorySessionStore store = theOne(reachableInstancesOf(runtime, InMemorySessionStore.class),
                    "session store");
            Instant wallClock = Instant.now();
            store.create(sessionExpiringAt(wallClock.minusSeconds(1)), "expired-handle", wallClock.minusSeconds(120));
            store.create(sessionExpiringAt(wallClock.plus(Duration.ofHours(1))), "live-handle", wallClock);
            assertEquals(2, store.size(), "precondition: the expired session still occupies memory");

            timers.fireOnce();

            Awaits.until(() -> store.size() == 1, "the periodic sweep to remove the expired session",
                    Awaits.TEARDOWN_CEILING_SECONDS);
            assertEquals(1, store.size(), "the sweep removed the expired session and nothing else");
            assertTrue(store.resolve("live-handle", wallClock).isPresent(), "the live session is untouched");
        }

        @Test
        @DisplayName("Should wire the activity-cookie codec in cookie mode only")
        void shouldWireTheActivityCodecInCookieModeOnly() {
            BffRuntime cookieMode = producerFor(oidc("cookie", 3600, null)).bffRuntime();
            BffRuntime serverMode = producerFor(oidc("server", 3600, null)).bffRuntime();

            List<SessionActivityCookieCodec> inCookieMode =
                    reachableInstancesOf(cookieMode, SessionActivityCookieCodec.class);
            List<SessionActivityCookieCodec> inServerMode =
                    reachableInstancesOf(serverMode, SessionActivityCookieCodec.class);

            assertAll(
                    () -> assertEquals(1, inCookieMode.size(), "cookie mode keeps the last access in a cookie"),
                    () -> assertTrue(inCookieMode.getFirst().cookieName().endsWith("-activity"),
                            inCookieMode.getFirst().cookieName()),
                    () -> assertTrue(inServerMode.isEmpty(), "server mode keeps the last access in its store"),
                    () -> assertEquals(1, reachableInstancesOf(serverMode, InMemorySessionStore.class).size(),
                            "control: the walk does see the server-mode runtime's store, so the absence above "
                                    + "is the wiring's and not the walk's"));
        }
    }

    /**
     * Builds the back-channel configuration exactly as {@code build} does: the posture is reported once,
     * then the configuration for {@code scopes} is built through the same seam every scoped variant uses.
     */
    private static ClientConfiguration assembledBackChannel(BffRuntimeProducer producer, OidcConfig oidc,
            List<String> scopes) {
        producer.reportBackChannelPosture();
        return producer.backChannelConfiguration(oidc, scopes);
    }

    private static OidcConfig serverModeOidc() {
        OidcConfig.Session session = OidcConfig.Session.builder()
                .mode("server")
                .ttlSeconds(3600)
                .build();
        return OidcConfig.builder()
                .issuer(ISSUER)
                .clientId("gateway-client")
                .clientSecret("secret")
                .scopes(List.of("openid"))
                .redirectUri(REDIRECT_URI)
                .session(session)
                .userInfo(OidcConfig.UserInfo.builder()
                        .path("/auth/userinfo")
                        .allowedClaims(List.of("sub", "name"))
                        .defaultView(List.of("sub"))
                        .build())
                .login(OidcConfig.Login.builder().path("/auth/login").build())
                .build();
    }

    private static OidcConfig cookieModeOidc() {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 0x11);
        return cookieModeOidcWithKey(Base64.getEncoder().encodeToString(key));
    }

    private static OidcConfig cookieModeOidcWithKey(String encryptionKey) {
        OidcConfig.Session session = OidcConfig.Session.builder()
                .mode("cookie")
                .ttlSeconds(3600)
                .encryptionKey(encryptionKey)
                .build();
        return OidcConfig.builder()
                .issuer(ISSUER)
                .clientId("gateway-client")
                .clientSecret("secret")
                .scopes(List.of("openid"))
                .redirectUri(REDIRECT_URI)
                .session(session)
                .userInfo(OidcConfig.UserInfo.builder()
                        .path("/auth/userinfo")
                        .allowedClaims(List.of("sub", "name"))
                        .defaultView(List.of("sub"))
                        .build())
                .login(OidcConfig.Login.builder().path("/auth/login").build())
                .build();
    }

    /**
     * Minimal {@link Instance} test double resolving to a single supplied bean; the producer resolves
     * the validator only on the active path via {@link #get()}.
     */
    private static final class SingletonInstance<T> implements Instance<T> {

        private final T value;

        SingletonInstance(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }

        @Override
        public Instance<T> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return false;
        }

        @Override
        public boolean isAmbiguous() {
            return false;
        }

        @Override
        public void destroy(T instance) {
            // no-op: the test double owns no lifecycle
        }

        @Override
        public Handle<T> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<T>> handles() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterator<T> iterator() {
            return List.of(value).iterator();
        }
    }
}
