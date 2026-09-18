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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;


import de.cuioss.http.client.adapter.RetryConfig;
import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus.KeySetState;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.commons.transport.HttpJwksLoaderConfig;
import de.cuioss.sheriff.token.commons.transport.JwksType;
import de.cuioss.sheriff.token.commons.transport.LoaderStatus;
import de.cuioss.sheriff.token.validation.jwks.JwksLoader;
import de.cuioss.sheriff.token.validation.jwks.JwksLoaderFactory;
import de.cuioss.sheriff.token.validation.jwks.key.KeyInfo;
import de.cuioss.sheriff.token.validation.test.InMemoryKeyMaterialHandler;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
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
 * loaded, reports {@link LoaderStatus#OK} exactly then, answers every status read without a network
 * request, and retries a failed load on a bounded exponential backoff driven by its scheduler alone.
 * <p>
 * The HTTP legs run the real library loader against a local JWKS endpoint on
 * {@link LoopbackHost#ADDRESS}; no test double framework is involved. Except for the one leg that pins
 * the production timing, every loader runs its retries on a per-test scheduler, so no retry outlives
 * its test.
 */
@EnableTestLogger
@DisplayName("RetryingJwksLoader — gateway-owned, non-fetching per-issuer key-set state")
class RetryingJwksLoaderTest {

    private static final String ISSUER_NAME = "primary";
    private static final String ISSUER = "https://issuer.example";
    private static final String JWKS_PATH = "/jwks";
    private static final long AWAIT_SECONDS = 10;
    private static final Duration REFRESH_INTERVAL = Duration.ofSeconds(3600);
    /** A first-retry delay no test outlives: the retry is scheduled but never runs. */
    private static final Duration NEVER_WITHIN_TEST = Duration.ofHours(1);
    /** A first-retry delay short enough for a retry sequence to complete within a test. */
    private static final Duration FAST = Duration.ofMillis(50);

    private MockWebServer server;
    private ScheduledExecutorService scheduler;
    private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();

    @BeforeEach
    void startSchedulerAndServer() throws IOException {
        scheduler = Executors.newSingleThreadScheduledExecutor();
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
    void stopServerAndScheduler() {
        // Stop the scheduler first so no retry can dial the server while it is closing.
        scheduler.shutdownNow();
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

    /** An HTTP loader whose first retry is scheduled far beyond the test. */
    private RetryingJwksLoader httpLoader() {
        return httpLoader(NEVER_WITHIN_TEST, REFRESH_INTERVAL);
    }

    private RetryingJwksLoader httpLoader(Duration initialRetryDelay, Duration refreshInterval) {
        return new RetryingJwksLoader(ISSUER_NAME, () -> JwksLoaderFactory.createHttpLoader(httpConfig()),
                refreshInterval, initialRetryDelay, scheduler);
    }

    private RetryingJwksLoader loader(Supplier<JwksLoader> delegateFactory) {
        return new RetryingJwksLoader(ISSUER_NAME, delegateFactory, REFRESH_INTERVAL, NEVER_WITHIN_TEST, scheduler);
    }

    /**
     * Blocks until every task the test scheduler would have run within {@code horizon} has run: the
     * scheduler is single-threaded and runs delayed tasks in deadline order, so a barrier due after
     * {@code horizon} completes only once every earlier-due task has completed or was cancelled.
     */
    private void drainScheduler(Duration horizon) throws Exception {
        scheduler.schedule(() -> null, horizon.toMillis(), TimeUnit.MILLISECONDS)
                .get(AWAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** Polls until a log line at {@code level} contains {@code part}; the retry logs off-thread. */
    private static void awaitLog(TestLogLevel level, String part) throws TimeoutException {
        Awaits.until(() -> {
            try {
                LogAsserts.assertLogMessagePresentContaining(level, part);
                return true;
            } catch (AssertionError _) {
                return false;
            }
        }, level + " log containing '" + part + "'", Awaits.CONNECT_CEILING_SECONDS);
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
            assertTrue(loader.retryPending(), "a failed first load schedules a retry");
        }

        @Test
        @DisplayName("a successful first load schedules no retry")
        void successfulFirstLoadSchedulesNoRetry() throws Exception {
            // Arrange
            RetryingJwksLoader loader = httpLoader();

            // Act
            initialise(loader);

            // Assert
            assertFalse(loader.retryPending());
        }

        @Test
        @DisplayName("a repeated init starts no second load and reports the current status")
        void repeatedInitStartsNothing() throws Exception {
            // Arrange
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = httpLoader();
            initialise(loader);
            int requestsAfterInit = server.getRequestCount();

            // Act
            LoaderStatus second = initialise(loader);

            // Assert
            assertNotEquals(LoaderStatus.OK, second);
            assertEquals(requestsAfterInit, server.getRequestCount(), "a second init must not fetch");
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
            RetryingJwksLoader loader = loader(() -> JwksLoaderFactory.createFileLoader(jwks.toString()));

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
            RetryingJwksLoader loader = loader(FailingLoader::new);

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
            RetryingJwksLoader loader = loader(FailingLoader::new);

            // Act
            loader.recordOutcome(new FailingLoader(), LoaderStatus.OK);

            // Assert
            assertEquals(KeySetState.NOT_LOADED, loader.keySetState(),
                    "a foreign delegate's success must not mark the current one loaded");
        }
    }

    @Nested
    @DisplayName("fast retry of a failed load")
    class FastRetry {

        @Test
        @DisplayName("a failed first load is retried until the key set loads, then reports OK")
        void failedLoadIsRetriedUntilLoaded() throws Exception {
            // Arrange — the endpoint fails twice, then serves the key set
            failuresBeforeSuccess.set(2);
            RetryingJwksLoader loader = httpLoader(FAST, REFRESH_INTERVAL);

            // Act
            LoaderStatus first = initialise(loader);
            Awaits.until(() -> loader.keySetState() == KeySetState.LOADED, "key set loaded by retry",
                    Awaits.CONNECT_CEILING_SECONDS);

            // Assert
            assertNotEquals(LoaderStatus.OK, first, "the first load failed");
            assertEquals(LoaderStatus.OK, loader.getLoaderStatus(), "a key set loaded by a retry reports OK");
            assertTrue(loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isPresent(),
                    "the retried delegate answers key lookups through the wrapper");
            assertEquals(3, server.getRequestCount(), "one fetch per attempt: the first load and two retries");
            assertFalse(loader.retryPending(), "retries stop at the first loaded key set");
            awaitLog(TestLogLevel.WARN, "JWKS key set for issuer '" + ISSUER_NAME + "' not loaded — retrying in 50 ms");
            awaitLog(TestLogLevel.WARN, "JWKS key set for issuer '" + ISSUER_NAME + "' not loaded — retrying in 100 ms");
            awaitLog(TestLogLevel.INFO, "JWKS key set for issuer '" + ISSUER_NAME + "' loaded after 2 retry attempt(s)");
            loader.close();
        }

        @Test
        @DisplayName("a retry whose delegate cannot be built counts as a failed attempt and the sequence continues")
        void refusedRebuildIsAFailedAttempt() throws Exception {
            // Arrange — the endpoint fails the first load; the first retry's rebuild is refused with
            // the gateway's own config-assembly failure; the second retry builds and loads
            failuresBeforeSuccess.set(1);
            AtomicInteger builds = new AtomicInteger();
            RetryingJwksLoader loader = new RetryingJwksLoader(ISSUER_NAME, () -> {
                if (builds.incrementAndGet() == 2) {
                    throw new GatewayException(EventType.CONFIG_INVALID, "simulated tls_profile resolution failure");
                }
                return JwksLoaderFactory.createHttpLoader(httpConfig());
            }, REFRESH_INTERVAL, FAST, scheduler);

            // Act
            initialise(loader);
            Awaits.until(() -> loader.keySetState() == KeySetState.LOADED, "key set loaded by the second retry",
                    Awaits.CONNECT_CEILING_SECONDS);

            // Assert
            assertEquals(3, builds.get(), "the first delegate, the refused rebuild and the successful one");
            assertEquals(2, server.getRequestCount(), "the refused rebuild issued no fetch");
            assertEquals(LoaderStatus.OK, loader.getLoaderStatus());
            awaitLog(TestLogLevel.WARN, "JWKS key set for issuer '" + ISSUER_NAME + "' not loaded — retrying in 100 ms");
            loader.close();
        }

        @Test
        @DisplayName("with the production timing the key set arrives within seconds, far below the refresh interval")
        void productionTimingRecoversFarBelowRefreshInterval() throws Exception {
            // Arrange — the shared scheduler and the production first-retry delay of one second
            failuresBeforeSuccess.set(1);
            try (RetryingJwksLoader loader = new RetryingJwksLoader(ISSUER_NAME,
                    () -> JwksLoaderFactory.createHttpLoader(httpConfig()), REFRESH_INTERVAL)) {
                long start = System.nanoTime();

                // Act
                initialise(loader);
                Awaits.until(() -> loader.keySetState() == KeySetState.LOADED, "key set loaded by retry",
                        Awaits.CONNECT_CEILING_SECONDS);

                // Assert — recovered after the one-second first retry, not after the 3600 s refresh interval
                Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                assertTrue(elapsed.compareTo(Duration.ofSeconds(AWAIT_SECONDS)) < 0,
                        () -> "recovery took " + elapsed + ", the refresh interval is " + REFRESH_INTERVAL);
                assertEquals(LoaderStatus.OK, loader.getLoaderStatus());
            }
        }

        @Test
        @DisplayName("reads during an outage never fetch: only the scheduler does")
        void readsDuringOutageIssueNoFetch() throws Exception {
            // Arrange — the retry is scheduled but cannot fire within the test
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = httpLoader();
            initialise(loader);
            int requestsAfterInit = server.getRequestCount();

            // Act — a burst of the reads request threads, the issuer cache and readiness perform
            for (int i = 0; i < 100; i++) {
                loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID);
                loader.getLoaderStatus();
                loader.keySetState();
            }

            // Assert
            assertEquals(requestsAfterInit, server.getRequestCount(), "request traffic must never trigger a fetch");
            assertTrue(loader.retryPending(), "the one scheduled retry is still the only pending attempt");
        }

        @Test
        @DisplayName("close cancels a pending retry, so it never fetches again")
        void closeCancelsPendingRetry() throws Exception {
            // Arrange
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = httpLoader(FAST, REFRESH_INTERVAL);
            initialise(loader);
            assertTrue(loader.retryPending(), "precondition: a retry is scheduled");
            int requestsAfterInit = server.getRequestCount();

            // Act
            loader.close();
            drainScheduler(FAST.multipliedBy(4));

            // Assert — the cancelled retry was due long before the barrier and never ran
            assertFalse(loader.retryPending());
            assertEquals(requestsAfterInit, server.getRequestCount(), "a closed loader never fetches");
        }

        @Test
        @DisplayName("the retry delay never exceeds the issuer's refresh interval")
        void retryDelayIsBoundedByRefreshInterval() throws Exception {
            // Arrange — a refresh interval shorter than the second backoff step
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = httpLoader(FAST, Duration.ofMillis(80));

            // Act
            initialise(loader);

            // Assert — 50 ms, then 80 ms instead of the doubled 100 ms
            awaitLog(TestLogLevel.WARN, "retrying in 50 ms");
            awaitLog(TestLogLevel.WARN, "retrying in 80 ms");
            loader.close();
        }

        @Test
        @DisplayName("without a refresh interval the retry delay is capped at thirty seconds")
        void retryDelayWithoutRefreshIntervalIsCappedAtThirtySeconds() throws Exception {
            // Arrange — a first-retry delay above the cap and no refresh interval to bound it
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = new RetryingJwksLoader(ISSUER_NAME,
                    () -> JwksLoaderFactory.createHttpLoader(httpConfig()), Duration.ZERO, Duration.ofMinutes(1),
                    scheduler);

            // Act
            initialise(loader);

            // Assert
            awaitLog(TestLogLevel.WARN, "retrying in 30000 ms");
            loader.close();
        }
    }

    @Nested
    @DisplayName("backoff schedule")
    class Backoff {

        @Test
        @DisplayName("the delay starts at the initial delay, doubles per retry and is capped")
        void delayDoublesAndIsCapped() {
            Duration initial = RetryingJwksLoader.INITIAL_RETRY_DELAY;
            Duration cap = RetryingJwksLoader.MAX_RETRY_DELAY;

            assertEquals(Duration.ofSeconds(1), RetryingJwksLoader.backoffDelay(1, initial, cap));
            assertEquals(Duration.ofSeconds(2), RetryingJwksLoader.backoffDelay(2, initial, cap));
            assertEquals(Duration.ofSeconds(4), RetryingJwksLoader.backoffDelay(3, initial, cap));
            assertEquals(Duration.ofSeconds(8), RetryingJwksLoader.backoffDelay(4, initial, cap));
            assertEquals(Duration.ofSeconds(16), RetryingJwksLoader.backoffDelay(5, initial, cap));
            assertEquals(Duration.ofSeconds(30), RetryingJwksLoader.backoffDelay(6, initial, cap));
            assertEquals(Duration.ofSeconds(30), RetryingJwksLoader.backoffDelay(1_000, initial, cap),
                    "a long outage stays at the cap and never overflows");
        }

        @Test
        @DisplayName("a cap below the initial delay wins from the first retry")
        void capBelowInitialWins() {
            assertEquals(Duration.ofMillis(500),
                    RetryingJwksLoader.backoffDelay(1, Duration.ofSeconds(1), Duration.ofMillis(500)));
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
