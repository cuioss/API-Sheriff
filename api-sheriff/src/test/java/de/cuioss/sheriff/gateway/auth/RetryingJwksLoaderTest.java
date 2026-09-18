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
package de.cuioss.sheriff.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.http.client.adapter.RetryConfig;
import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus.KeySetState;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.commons.transport.HttpJwksLoaderConfig;
import de.cuioss.sheriff.token.commons.transport.JwksType;
import de.cuioss.sheriff.token.commons.transport.LoaderStatus;
import de.cuioss.sheriff.token.validation.jwks.JwksLoader;
import de.cuioss.sheriff.token.validation.jwks.JwksLoaderFactory;
import de.cuioss.sheriff.token.validation.jwks.key.KeyInfo;
import de.cuioss.sheriff.token.validation.test.InMemoryKeyMaterialHandler;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link RetryingJwksLoader}: the gateway-owned loader records whether a key set was
 * loaded, reports {@link LoaderStatus#OK} exactly then, and answers every status read without a
 * network request.
 * <p>
 * The HTTP legs run the real library loader against a local JWKS endpoint on
 * {@link LoopbackHost#ADDRESS}; no test double framework is involved.
 */
@DisplayName("RetryingJwksLoader — gateway-owned, non-fetching per-issuer key-set state")
class RetryingJwksLoaderTest {

    private static final String ISSUER_NAME = "primary";
    private static final String ISSUER = "https://issuer.example";
    private static final String JWKS_PATH = "/jwks";
    private static final long AWAIT_SECONDS = 10;

    private MockWebServer server;
    private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (failuresBeforeSuccess.getAndDecrement() > 0) {
                    return new MockResponse.Builder().code(503).build();
                }
                return new MockResponse.Builder().code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(InMemoryKeyMaterialHandler.createDefaultJwks())
                        .build();
            }
        });
        server.start(InetAddress.getByName(LoopbackHost.ADDRESS), 0);
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private HttpJwksLoaderConfig httpConfig() {
        return HttpJwksLoaderConfig.builder()
                .issuerIdentifier(ISSUER)
                .jwksUrl(server.url(JWKS_PATH).toString())
                .allowInsecureHttp(true)
                .allowLoopbackEgress(true)
                .refreshIntervalSeconds(3600)
                .retryConfig(RetryConfig.builder().maxAttempts(1).build())
                .build();
    }

    private RetryingJwksLoader httpLoader() {
        return new RetryingJwksLoader(ISSUER_NAME, () -> JwksLoaderFactory.createHttpLoader(httpConfig()));
    }

    private static LoaderStatus initialise(RetryingJwksLoader loader) throws Exception {
        return loader.initJWKSLoader(new SecurityEventCounter()).get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    @Nested
    @DisplayName("first load over HTTP")
    class HttpFirstLoad {

        @Test
        @DisplayName("no state is claimed before the first load completes")
        void notLoadedBeforeInit() {
            // Arrange
            RetryingJwksLoader loader = httpLoader();

            // Act + Assert
            assertEquals(KeySetState.NOT_LOADED, loader.keySetState());
            assertNotEquals(LoaderStatus.OK, loader.getLoaderStatus(), "no key set, no OK");
        }

        @Test
        @DisplayName("a successful first load yields LOADED and OK")
        void successfulFirstLoad() throws Exception {
            // Arrange
            RetryingJwksLoader loader = httpLoader();

            // Act
            LoaderStatus status = initialise(loader);

            // Assert
            assertEquals(LoaderStatus.OK, status);
            assertEquals(KeySetState.LOADED, loader.keySetState());
            assertEquals(LoaderStatus.OK, loader.getLoaderStatus());
            Optional<KeyInfo> key = loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID);
            assertTrue(key.isPresent(), "the loaded key set answers key lookups through the wrapper");
            assertEquals(JwksType.HTTP, loader.getJwksType());
        }

        @Test
        @DisplayName("a failed first load yields FAILED and never OK")
        void failedFirstLoad() throws Exception {
            // Arrange
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = httpLoader();

            // Act
            LoaderStatus status = initialise(loader);

            // Assert
            assertNotEquals(LoaderStatus.OK, status, "a failed load is never reported OK");
            assertEquals(KeySetState.FAILED, loader.keySetState());
            assertNotEquals(LoaderStatus.OK, loader.getLoaderStatus());
        }

        @Test
        @DisplayName("status reads never issue a request")
        void statusReadsDoNotFetch() throws Exception {
            // Arrange
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = httpLoader();
            initialise(loader);
            int requestsAfterInit = server.getRequestCount();

            // Act — a burst of the reads readiness and the library's issuer cache perform
            for (int i = 0; i < 50; i++) {
                loader.keySetState();
                loader.getLoaderStatus();
            }

            // Assert
            assertEquals(requestsAfterInit, server.getRequestCount(), "a status read must never fetch");
        }

        @Test
        @DisplayName("close releases the delegate and describes the loader without its URL")
        void closeAndToString() throws Exception {
            // Arrange
            RetryingJwksLoader loader = httpLoader();
            initialise(loader);

            // Act + Assert
            assertDoesNotThrow(loader::close);
            String rendered = loader.toString();
            assertTrue(rendered.contains(ISSUER_NAME));
            assertFalse(rendered.contains(String.valueOf(server.getPort())), "the JWKS URL is never rendered");
        }
    }

    @Nested
    @DisplayName("first load from a file")
    class FileFirstLoad {

        @TempDir
        Path directory;

        @Test
        @DisplayName("a file source loads and reports LOADED")
        void fileSourceLoads() throws Exception {
            // Arrange
            Path jwks = Files.writeString(directory.resolve("jwks.json"), InMemoryKeyMaterialHandler.createDefaultJwks());
            RetryingJwksLoader loader = new RetryingJwksLoader(ISSUER_NAME,
                    () -> JwksLoaderFactory.createFileLoader(jwks.toString()));

            // Act
            LoaderStatus status = initialise(loader);

            // Assert
            assertEquals(LoaderStatus.OK, status);
            assertEquals(KeySetState.LOADED, loader.keySetState());
            assertEquals(JwksType.FILE, loader.getJwksType());
            assertDoesNotThrow(loader::close, "a file loader holds nothing to close");
        }
    }

    @Nested
    @DisplayName("outcome recording")
    class OutcomeRecording {

        @Test
        @DisplayName("a load that completes exceptionally is recorded as FAILED and reported as ERROR")
        void exceptionalLoadIsFailed() throws Exception {
            // Arrange
            RetryingJwksLoader loader = new RetryingJwksLoader(ISSUER_NAME, FailingLoader::new);

            // Act
            LoaderStatus status = initialise(loader);

            // Assert
            assertEquals(LoaderStatus.ERROR, status);
            assertEquals(KeySetState.FAILED, loader.keySetState());
        }

        @Test
        @DisplayName("an outcome reported for a delegate that is no longer current is ignored")
        void staleOutcomeIsIgnored() {
            // Arrange
            RetryingJwksLoader loader = new RetryingJwksLoader(ISSUER_NAME, FailingLoader::new);

            // Act
            loader.recordOutcome(new FailingLoader(), LoaderStatus.OK);

            // Assert
            assertEquals(KeySetState.NOT_LOADED, loader.keySetState(),
                    "a foreign delegate's success must not mark the current one loaded");
        }
    }

    /** A hand-written loader whose first load always completes exceptionally. */
    private static final class FailingLoader implements JwksLoader {

        @Override
        public Optional<KeyInfo> getKeyInfo(String kid) {
            return Optional.empty();
        }

        @Override
        public JwksType getJwksType() {
            return JwksType.HTTP;
        }

        @Override
        public Optional<String> getIssuerIdentifier() {
            return Optional.empty();
        }

        @Override
        public CompletableFuture<LoaderStatus> initJWKSLoader(SecurityEventCounter securityEventCounter) {
            return CompletableFuture.failedFuture(new IllegalStateException("simulated load failure"));
        }

        @Override
        public LoaderStatus getLoaderStatus() {
            return LoaderStatus.ERROR;
        }
    }
}
