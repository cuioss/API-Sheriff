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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry.ReservedEndpoint;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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

        @Test
        @DisplayName("Should assemble without resolving OIDC discovery (no live IdP required)")
        void shouldAssembleWithoutDiscovery() {
            assertDoesNotThrow(() -> producer(serverModeOidc()).bffRuntime());
        }

        @Test
        @DisplayName("Should wire the user-info fold reachably — no session yields 401")
        void shouldWireUserInfo() {
            BffRuntime.ReservedHttpResponse response = runtime.dispatch(ReservedEndpoint.USER_INFO,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null),
                    Instant.parse("2026-07-25T10:00:00Z"));
            assertEquals(401, response.status());
        }
    }

    @Nested
    @DisplayName("Inert bearer-only / cookie-mode runtime")
    class Inert {

        @Test
        @DisplayName("Should stay inert when no oidc block is configured")
        void shouldBeInertWithoutOidc() {
            BffRuntime runtime = producer(Optional.empty()).bffRuntime();
            assertFalse(runtime.isActive());
        }

        @Test
        @DisplayName("Should stay inert for a cookie-mode session (server mode not selected)")
        void shouldBeInertForCookieMode() {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(Optional.of(ISSUER))
                    .redirectUri(Optional.of(REDIRECT_URI))
                    .session(Optional.of(OidcConfig.Session.builder().mode(Optional.of("cookie")).build()))
                    .build();
            assertFalse(producer(Optional.of(oidc)).bffRuntime().isActive());
        }

        @Test
        @DisplayName("Should stay inert when server mode is set but no redirect_uri is configured")
        void shouldBeInertWithoutRedirectUri() {
            OidcConfig oidc = OidcConfig.builder()
                    .issuer(Optional.of(ISSUER))
                    .session(Optional.of(OidcConfig.Session.builder().mode(Optional.of("server")).build()))
                    .build();
            assertFalse(producer(Optional.of(oidc)).bffRuntime().isActive());
        }

        @Test
        @DisplayName("Should reject reserved dispatch and session-stage access on the inert runtime")
        void shouldRejectUseOfInert() {
            BffRuntime runtime = BffRuntime.inert();
            assertThrows(IllegalStateException.class, () -> runtime.sessionStage());
            assertThrows(IllegalStateException.class, () -> runtime.dispatch(ReservedEndpoint.USER_INFO,
                    new BffRuntime.ReservedHttpRequest("", null, null, null, null, null), Instant.now()));
        }
    }

    private BffRuntimeProducer producer(Optional<OidcConfig> oidc) {
        GatewayConfig gatewayConfig = GatewayConfig.builder().version(1).oidc(oidc).build();
        return new BffRuntimeProducer(gatewayConfig, new SingletonInstance<>(tokenValidator));
    }

    private static Optional<OidcConfig> serverModeOidc() {
        OidcConfig.Session session = OidcConfig.Session.builder()
                .mode(Optional.of("server"))
                .ttlSeconds(Optional.of(3600))
                .build();
        return Optional.of(OidcConfig.builder()
                .issuer(Optional.of(ISSUER))
                .clientId(Optional.of("gateway-client"))
                .clientSecret(Optional.of("secret"))
                .scopes(List.of("openid"))
                .redirectUri(Optional.of(REDIRECT_URI))
                .session(Optional.of(session))
                .userInfo(Optional.of(OidcConfig.UserInfo.builder()
                        .path(Optional.of("/auth/userinfo"))
                        .allowedClaims(List.of("sub", "name"))
                        .defaultView(List.of("sub"))
                        .build()))
                .login(Optional.of(OidcConfig.Login.builder().path(Optional.of("/auth/login")).build()))
                .build());
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
