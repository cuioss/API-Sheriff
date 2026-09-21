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
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import de.cuioss.sheriff.gateway.auth.JwksTrustProfileResolver;
import de.cuioss.sheriff.gateway.auth.SanMismatchedJwksServer;
import de.cuioss.sheriff.gateway.auth.TestTlsConfigurationRegistry;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionPayload;
import de.cuioss.sheriff.gateway.bff.login.LoginFlow;
import de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder;
import de.cuioss.sheriff.gateway.bff.login.ReturnTargetScopes;
import de.cuioss.sheriff.gateway.bff.refresh.EndedRefreshTokens;
import de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator;
import de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage;
import de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore;
import de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
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
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.DiscoveryResolver;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.client.flow.CredentialRejectedException;
import de.cuioss.sheriff.token.client.token.RotationResult;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimName;
import de.cuioss.sheriff.token.validation.domain.claim.ClaimValue;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.domain.token.IdTokenContent;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Vetoed;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;

/**
 * Covers {@link BffRuntimeProducer}: the runtime is active (and its reserved handlers and session
 * stage are wired) only when a global {@code oidc} block with {@code session.mode=server} and a
 * {@code redirect_uri} is configured, and inert (bearer-only) otherwise. Assembly resolves no OIDC
 * discovery (that is deferred to first engine use), so the producer builds a working runtime without
 * a live IdP; the live engine round-trips are covered by the Keycloak integration tests.
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

    /**
     * Stands in for the Quarkus-managed virtual-thread executor the producer hands the refresh coordinator.
     * No assembly test here reaches a revocation, so nothing is ever submitted to it.
     */
    private static final ExecutorService REVOCATION_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    /** The bundled gateway schema, read off the classpath so the contract sees the shipped copy. */
    private static final String GATEWAY_SCHEMA_RESOURCE = "/schema/gateway.schema.json";

    /** JSON pointer to the {@code oidc.session.refresh.on_failure} enum array in the bundled gateway schema. */
    private static final String ON_FAILURE_ENUM_POINTER =
            "/properties/oidc/properties/session/properties/refresh/properties/on_failure/enum";

    private final TokenValidator tokenValidator = TokenValidator.builder()
            .issuerConfig(TestTokenGenerators.accessTokens().next().getIssuerConfig()).build();

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
                    "session-nonce-material", Set.of());
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
     * lambdas that no assembled-runtime test can invoke without a live IdP. The three seams are
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

        private final InMemorySessionStore store = new InMemorySessionStore(16);
        private final SessionBinding binding = new ServerSessionBinding(store,
                new SessionCookieCodec(SessionCookieCodec.DEFAULT_COOKIE_NAME, SESSION_TTL));

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
            assertAll("the gateway mediates the token it was issued until the absolute TTL expires",
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
            store.create(live, NOW);
            return live;
        }

        private static String cookieHeader(SessionRecord live) {
            return SessionCookieCodec.DEFAULT_COOKIE_NAME + "=" + live.sessionId();
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
        /** The scope a scoped endpoint adds on top of {@code oidc.scopes = [openid]}. */
        private static final String SCOPED_ENDPOINT_SCOPE = "orders:read";
        private static final String SCOPED_ROUTE_PREFIX = "/orders";
        /** {@code oidc.scopes ∪ endpoint.scopes} for the scoped session route. */
        private static final Set<String> SCOPED_NEEDED_SCOPES = Set.of("openid", SCOPED_ENDPOINT_SCOPE);

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

            runtime.sessionStage().process(request);

            assertEquals(Optional.of(302), request.shortCircuitStatus(), "the navigation is redirected into login");
            assertEquals(SCOPED_NEEDED_SCOPES, scopeOf(request.responseHeaders().get("Location")),
                    "the authorization URL requests the route's neededScopes, never the static oidc.scopes alone");
        }

        @Test
        @DisplayName("/auth/login requests the landing route's needed scopes, and oidc.scopes alone for an unrouted target")
        void loginInitiationRequestsReturnTargetScopes() {
            BffRuntime runtime = scopedRuntime();
            Instant now = Instant.parse("2026-07-25T10:00:00Z");

            BffRuntime.ReservedHttpResponse routed = runtime.dispatch(ReservedEndpoint.LOGIN,
                    new BffRuntime.ReservedHttpRequest("", null, null, SCOPED_ROUTE_PREFIX + "/list", null, null, "GET"),
                    now);
            BffRuntime.ReservedHttpResponse unrouted = runtime.dispatch(ReservedEndpoint.LOGIN,
                    new BffRuntime.ReservedHttpRequest("", null, null, "/unrouted", null, null, "GET"), now);

            assertAll("the producer wires ReturnTargetScopes over the injected route table",
                    () -> assertEquals(SCOPED_NEEDED_SCOPES, scopeOf(routed.locationOptional().orElseThrow())),
                    () -> assertEquals(Set.of("openid"), scopeOf(unrouted.locationOptional().orElseThrow())));
            assertFalse(reachableInstancesOf(runtime, ReturnTargetScopes.class).isEmpty(),
                    "the login-initiation endpoint holds the producer-built resolver");
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
        @DisplayName("the assembled refresh binding requests the session's active scope set A, never the static oidc.scopes")
        void refreshBindingRequestsActiveScopes() {
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
            return new RecordingProducer(gatewayConfig, tokenValidator);
        }

        private static <T> T single(List<T> found, String what) {
            assertEquals(1, found.size(), "exactly one " + what + " is reachable from the assembled runtime — "
                    + "this test must never pass vacuously; if the producer's wiring moved, retarget the walk");
            return found.getFirst();
        }

        /**
         * An active server-mode runtime whose discovery reaches the SAN-mismatch fixture over the relaxed
         * hostname posture, over a route table carrying one scoped session route.
         */
        private BffRuntime scopedRuntime() {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(server.issuer())
                    .clientId("gateway-client")
                    .clientSecret("secret")
                    .scopes(List.of("openid"))
                    .redirectUri(REDIRECT_URI)
                    .session(OidcConfig.Session.builder().mode("server").ttlSeconds(3600).build())
                    .login(OidcConfig.Login.builder().path("/auth/login").build())
                    .build();
            ResolvedRoute scopedRoute = ResolvedRoute.builder()
                    .id("orders")
                    .match(MatchConfig.builder().pathPrefix(SCOPED_ROUTE_PREFIX).build())
                    .effectiveAuth(AuthConfig.builder().require(Require.SESSION).build())
                    .effectiveAllowedMethods(List.of(HttpMethod.GET))
                    .upstream(new ResolvedUpstream("https", "orders.example", 443, ""))
                    .neededScopes(SCOPED_NEEDED_SCOPES)
                    .build();
            return producer(oidc, oidcHostname(false), TestTlsConfigurationRegistry.empty(),
                    new RouteTable(List.of(scopedRoute))).bffRuntime();
        }

        private static Set<String> scopeOf(String authorizationUrl) {
            String rawQuery = URI.create(authorizationUrl).getRawQuery();
            for (String pair : rawQuery.split("&")) {
                String[] nameValue = pair.split("=", 2);
                if ("scope".equals(nameValue[0])) {
                    return Set.of(URLDecoder.decode(nameValue[1], StandardCharsets.UTF_8).split(" "));
                }
            }
            throw new AssertionError("the authorization URL carries no scope parameter: " + authorizationUrl);
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
     * A producer that records every scope list its back-channel configuration factory is asked for,
     * then builds the configuration exactly as the production method does. Only the recording is added.
     * {@link Vetoed} because {@code @ApplicationScoped} is inherited: without it the test-class index a
     * {@code @QuarkusTest} run builds would see a second producer bean.
     */
    @Vetoed
    private static final class RecordingProducer extends BffRuntimeProducer {

        private final List<List<String>> requested = new CopyOnWriteArrayList<>();

        RecordingProducer(GatewayConfig gatewayConfig, TokenValidator tokenValidator) {
            super(gatewayConfig, new RouteTable(List.of()), new SingletonInstance<>(tokenValidator),
                    new JwksTrustProfileResolver(TestTlsConfigurationRegistry.empty()), REVOCATION_EXECUTOR);
        }

        @Override
        ClientConfiguration backChannelConfiguration(OidcConfig oidc, List<String> scopes) {
            requested.add(List.copyOf(scopes));
            return super.backChannelConfiguration(oidc, scopes);
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
        GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(oidc).egressTls(egressTls).build();
        return new BffRuntimeProducer(gatewayConfig, routeTable, new SingletonInstance<>(tokenValidator),
                new JwksTrustProfileResolver(registry), REVOCATION_EXECUTOR);
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
