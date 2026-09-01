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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;


import de.cuioss.sheriff.gateway.bff.login.QueryResponseModeAuthorizationRequestBuilder;
import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
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
            List<AuthorizationRequestBuilder> wired = reachableAuthorizationRequestBuilders(runtime);

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
     * Collects every {@link AuthorizationRequestBuilder} reachable from {@code root} by walking
     * instance fields, following lambda captures so a seam held only inside a closure is still seen.
     * <p>
     * The walk is bounded to the gateway's and the engine's own packages: it never descends into JDK
     * or container types, which keeps it away from the strongly-encapsulated {@code java.*} modules
     * and stops it wandering through collections and class loaders. A field the JVM refuses to open
     * is skipped rather than failing the walk — the caller's non-empty assertion is what guarantees
     * the result is still meaningful.
     */
    private static List<AuthorizationRequestBuilder> reachableAuthorizationRequestBuilders(Object root) {
        List<AuthorizationRequestBuilder> found = new ArrayList<>();
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Object current = pending.pop();
            if (current == null || !seen.add(current)) {
                continue;
            }
            if (current instanceof AuthorizationRequestBuilder builder) {
                found.add(builder);
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

            assertTrue(generated.isActive(),
                    "omitting the key selects generate-on-startup, a supported production mode — not a boot failure");
            assertNotNull(generated.sessionStage());
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
        GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(oidc).build();
        return new BffRuntimeProducer(gatewayConfig, new SingletonInstance<>(tokenValidator));
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
