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
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.LogRecord;

import de.cuioss.http.client.adapter.RetryConfig;
import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus.KeySetState;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
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
import de.cuioss.sheriff.token.validation.jwks.http.HttpJwksLoader;
import de.cuioss.sheriff.token.validation.jwks.key.KeyInfo;
import de.cuioss.sheriff.token.validation.test.InMemoryKeyMaterialHandler;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Tests for {@link RetryingJwksLoader}: the gateway-owned loader records whether a key set was
 * loaded, reports {@link LoaderStatus#OK} exactly then, answers every status read without a network
 * request, retries a failed load on a bounded exponential backoff driven by its scheduler alone, and
 * schedules at most one refresh per window when a lookup names a key id it does not hold.
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
    /** The unknown-key-id window of the loaders this fixture builds. */
    private static final Duration WINDOW = Duration.ofSeconds(30);
    /** A grace period no test outlives: a replaced delegate stays retired for the whole test. */
    private static final Duration GRACE = Duration.ofSeconds(300);
    private static final String WARN_UNKNOWN_KEY_ID = "Token rejected for issuer '" + ISSUER_NAME + "'";
    /** The identifier of the WARN an unanticipated refresh failure records. */
    private static final String WARN_REFRESH_FAILED =
            ConfigLogMessages.WARN.JWKS_REFRESH_FAILED.resolveIdentifierString();

    private MockWebServer server;
    /** The key set the endpoint serves; a test replaces it to play a signing-key change. */
    private final AtomicReference<String> jwksBody =
            new AtomicReference<>(InMemoryKeyMaterialHandler.createDefaultJwks());
    /** When set, the endpoint holds every response until the latch opens. */
    private final AtomicReference<CountDownLatch> responseGate = new AtomicReference<>();
    /** The instant every loader built by this fixture reads; a test moves it to cross a window. */
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
    /**
     * The per-test scheduler the loaders built by this fixture retry on; the one production-timing
     * test uses the shared production scheduler instead. It is held as the concrete pool so a test
     * can read its queue, and it drops a task from that queue the moment the task is cancelled, so
     * what is still scheduled is a fact read off the scheduler.
     */
    private ScheduledThreadPoolExecutor scheduler;
    private final AtomicInteger failuresBeforeSuccess = new AtomicInteger();

    @BeforeEach
    void startSchedulerAndServer() throws IOException {
        scheduler = new ScheduledThreadPoolExecutor(1);
        scheduler.setRemoveOnCancelPolicy(true);
        server = new MockWebServer();
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                CountDownLatch gate = responseGate.get();
                if (gate != null) {
                    gate.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                }
                if (failuresBeforeSuccess.getAndDecrement() > 0) {
                    return new MockResponse.Builder().code(503).build();
                }
                return new MockResponse.Builder().code(200)
                        .addHeader("Content-Type", "application/json")
                        .body(jwksBody.get())
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
        return loader(() -> JwksLoaderFactory.createHttpLoader(httpConfig()), refreshInterval, initialRetryDelay);
    }

    private RetryingJwksLoader loader(Supplier<JwksLoader> delegateFactory) {
        return loader(delegateFactory, REFRESH_INTERVAL, NEVER_WITHIN_TEST);
    }

    private RetryingJwksLoader loader(Supplier<JwksLoader> delegateFactory, Duration refreshInterval,
            Duration initialRetryDelay) {
        return loader(delegateFactory, refreshInterval, initialRetryDelay, WINDOW, GRACE);
    }

    /** The full fixture loader: the test's own scheduler and the test's own clock. */
    private RetryingJwksLoader loader(Supplier<JwksLoader> delegateFactory, Duration refreshInterval,
            Duration initialRetryDelay, Duration unknownKeyIdWindow, Duration keyRotationGracePeriod) {
        InstantSource clock = now::get;
        return new RetryingJwksLoader(ISSUER_NAME, delegateFactory, refreshInterval, unknownKeyIdWindow,
                keyRotationGracePeriod, initialRetryDelay, scheduler, clock);
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

    /** The formatted WARN records logged so far that contain {@code part}, read off the test log handler. */
    private static List<String> warningsContaining(String part) {
        return TestLoggerFactory.getTestHandler()
                .resolveLogMessagesContaining(TestLogLevel.WARN, part)
                .stream()
                .map(LogRecord::getMessage)
                .toList();
    }

    /** The formatted unknown-key-id WARN records logged so far. */
    private static List<String> unknownKeyIdWarnings() {
        return warningsContaining(WARN_UNKNOWN_KEY_ID);
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
            // Arrange — the endpoint never succeeds, so a retry episode is live and a re-dial is
            // already scheduled: there is something for close() to release. The loader retries on
            // the test's own scheduler, which this test alone has used, whose queue the test can
            // read, and which drops a task from that queue the moment it is cancelled. What is still
            // scheduled is then a fact read off the scheduler, not something inferred from a re-dial
            // failing to arrive within a wait. The fixture stops that scheduler after the test
            // whatever an assertion did, so a re-dial left queued for an hour is discarded with it.
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = httpLoader();
            initialise(loader);
            assertEquals(1, scheduler.getQueue().size(), "precondition: the re-dial is on the scheduler");
            int requestsBeforeClose = server.getRequestCount();

            // Act
            loader.close();

            // Assert — the release is observable on the scheduler rather than only on the loader's
            // own flag: the one re-dial it held is gone, so no later fetch can come from it. A
            // close() that rendered a tidy toString while leaving the retry scheduled would still
            // have it queued, which is the half the old assertDoesNotThrow(loader::close) could
            // not distinguish.
            assertTrue(scheduler.getQueue().isEmpty(), "a closed loader leaves no re-dial scheduled");
            assertEquals(requestsBeforeClose, server.getRequestCount(), "closing issues no fetch of its own");
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
            RetryingJwksLoader loader = loader(() -> {
                if (builds.incrementAndGet() == 2) {
                    throw new GatewayException(EventType.CONFIG_INVALID, "simulated tls_profile resolution failure");
                }
                return JwksLoaderFactory.createHttpLoader(httpConfig());
            }, REFRESH_INTERVAL, FAST);

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

        /**
         * A loader whose first load fails at the endpoint and whose first retry then fails at
         * {@code escape}, with a failure the loader does not anticipate; every other build yields an
         * ordinary HTTP delegate, and the endpoint serves the key set from the second fetch on.
         *
         * @param builds        counts every delegate the loader asks for
         * @param beforeFailing runs on the scheduler thread immediately before the failure is raised,
         *                      with the loader under test
         */
        private RetryingJwksLoader escapingLoader(RetryEscape escape, AtomicInteger builds,
                Consumer<RetryingJwksLoader> beforeFailing) {
            failuresBeforeSuccess.set(1);
            AtomicReference<RetryingJwksLoader> self = new AtomicReference<>();
            Runnable failing = () -> beforeFailing.accept(self.get());
            RetryingJwksLoader loader = loader(() -> switch (builds.incrementAndGet()) {
                case 1 -> escape == RetryEscape.REPLACED_DELEGATE_CLOSE_THROWS
                        ? closeFailingDelegate(failing)
                        : JwksLoaderFactory.createHttpLoader(httpConfig());
                case 2 -> firstRetryDelegate(escape, failing);
                default -> JwksLoaderFactory.createHttpLoader(httpConfig());
            }, REFRESH_INTERVAL, FAST);
            self.set(loader);
            return loader;
        }

        /** The delegate the first retry asks for, failing as {@code escape} prescribes. */
        private @Nullable JwksLoader firstRetryDelegate(RetryEscape escape, Runnable failing) {
            return switch (escape) {
                case FACTORY_YIELDS_NULL -> {
                    failing.run();
                    yield null;
                }
                case FACTORY_THROWS_UNCHECKED -> {
                    failing.run();
                    throw new UnsupportedOperationException("simulated unanticipated factory failure");
                }
                case FACTORY_THROWS_ERROR -> {
                    failing.run();
                    throw new LinkageError("simulated unanticipated factory error");
                }
                case REPLACED_DELEGATE_CLOSE_THROWS -> JwksLoaderFactory.createHttpLoader(httpConfig());
                case LOAD_START_THROWS -> new TrackedLoader(httpConfig()) {
                    @Override
                    public CompletableFuture<LoaderStatus> initJWKSLoader(SecurityEventCounter securityEventCounter) {
                        failing.run();
                        throw new UnsupportedOperationException("simulated failure to start the load");
                    }
                };
            };
        }

        /** A first delegate whose close, which the first retry performs, fails unanticipated. */
        private JwksLoader closeFailingDelegate(Runnable failing) {
            return new TrackedLoader(httpConfig()) {
                @Override
                public void close() {
                    super.close();
                    failing.run();
                    throw new UnsupportedOperationException("simulated failure to close the replaced delegate");
                }
            };
        }

        @ParameterizedTest
        @EnumSource(RetryEscape.class)
        @DisplayName("a retry that fails in a way nobody anticipated is followed by exactly one further retry")
        void unanticipatedRetryFailureContinuesTheSequence(RetryEscape escape) throws Exception {
            // Arrange — the endpoint fails the first load, the first retry fails at the escape point,
            // and the second retry builds an ordinary delegate that loads
            AtomicInteger builds = new AtomicInteger();
            RetryingJwksLoader loader = escapingLoader(escape, builds, _ -> {
                // the loader stays open
            });

            // Act
            initialise(loader);
            Awaits.until(() -> loader.keySetState() == KeySetState.LOADED, "key set loaded by the second retry",
                    Awaits.CONNECT_CEILING_SECONDS);
            drainScheduler(FAST.multipliedBy(4));

            // Assert — the failed retry left one continuation behind, and only one: a second one would
            // have built a fourth delegate and logged a second 100 ms WARN
            assertEquals(LoaderStatus.OK, loader.getLoaderStatus());
            assertTrue(loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isPresent(),
                    "the second retry's delegate answers key lookups through the wrapper");
            assertEquals(3, builds.get(), "the first delegate, the failed retry's and the second retry's");
            assertEquals(2, server.getRequestCount(), "the first load and the second retry; the failed one fetched nothing");
            assertEquals(1, warningsContaining("retrying in 100 ms").size(),
                    "exactly one continuation is scheduled from the failed retry");
            assertFalse(loader.retryPending(), "retries stop at the first loaded key set");
            loader.close();
        }

        @ParameterizedTest
        @EnumSource(RetryEscape.class)
        @DisplayName("a retry that fails in a way nobody anticipated after the loader was closed schedules nothing")
        void unanticipatedRetryFailureAfterCloseSchedulesNothing(RetryEscape escape) throws Exception {
            // Arrange — the loader is closed on the scheduler thread immediately before the failure is
            // raised: a close() racing the retry
            AtomicInteger builds = new AtomicInteger();
            RetryingJwksLoader loader = escapingLoader(escape, builds, RetryingJwksLoader::close);

            // Act — the barrier is due long after the first retry, so the retry has run when it returns
            initialise(loader);
            drainScheduler(FAST.multipliedBy(6));

            // Assert
            assertEquals(2, builds.get(), "the first delegate and the failed retry's; nothing after the close");
            assertTrue(warningsContaining("retrying in 100 ms").isEmpty(), "a closed loader schedules no next attempt");
            assertTrue(scheduler.getQueue().isEmpty(), "nothing is left on the scheduler");
            assertFalse(loader.retryPending());
            assertEquals(1, server.getRequestCount(), "only the first load fetched");
        }

        @Test
        @DisplayName("with the production timing the key set arrives within seconds, far below the refresh interval")
        void productionTimingRecoversFarBelowRefreshInterval() throws Exception {
            // Arrange — the shared scheduler and the production first-retry delay of one second
            failuresBeforeSuccess.set(1);
            try (RetryingJwksLoader loader = new RetryingJwksLoader(ISSUER_NAME,
                         () -> JwksLoaderFactory.createHttpLoader(httpConfig()), REFRESH_INTERVAL, WINDOW, GRACE)) {
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
            RetryingJwksLoader loader = loader(() -> JwksLoaderFactory.createHttpLoader(httpConfig()), Duration.ZERO,
                    Duration.ofMinutes(1));

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

    /**
     * The refresh an unknown key id schedules. Every leg runs the real library loader against the
     * local endpoint; the delegates are {@link TrackedLoader}s so a test can tell which one was
     * closed, the clock is the fixture's own, and the refresh runs on the fixture's scheduler.
     */
    @Nested
    @DisplayName("bounded refresh on an unknown key id")
    class UnknownKeyIdRefresh {

        /** The key id of the key set the endpoint serves after a signing-key change. */
        private static final String ROTATED_KEY_ID = "rotated-key";

        /** Every delegate the loader under test asked for, in build order. */
        private final List<TrackedLoader> built = new CopyOnWriteArrayList<>();

        private JwksLoader trackedDelegate() {
            TrackedLoader delegate = new TrackedLoader(httpConfig());
            built.add(delegate);
            return delegate;
        }

        private RetryingJwksLoader refreshingLoader() {
            return refreshingLoader(WINDOW, GRACE);
        }

        private RetryingJwksLoader refreshingLoader(Duration window, Duration grace) {
            return loader(this::trackedDelegate, REFRESH_INTERVAL, NEVER_WITHIN_TEST, window, grace);
        }

        /** The same key material under another key id: what a signing-key change publishes. */
        private void rotateSigningKey() {
            jwksBody.set(InMemoryKeyMaterialHandler.createDefaultJwks()
                    .replace(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID, ROTATED_KEY_ID));
        }

        private void awaitBuilt(int count) throws TimeoutException {
            Awaits.until(() -> built.size() >= count, count + " delegate build(s)", Awaits.CONNECT_CEILING_SECONDS);
        }

        private void awaitClosed(int index) throws TimeoutException {
            awaitBuilt(index + 1);
            Awaits.until(() -> built.get(index).closed.get(), "delegate " + index + " to be closed",
                    Awaits.CONNECT_CEILING_SECONDS);
        }

        private void awaitRotatedKeyPublished(RetryingJwksLoader loader) throws TimeoutException {
            Awaits.until(() -> loader.getKeyInfo(ROTATED_KEY_ID).isPresent(), "the refreshed key set to be published",
                    Awaits.CONNECT_CEILING_SECONDS);
        }

        /**
         * Asserts that a refresh which was discarded changed nothing: the fresh delegate is closed, the
         * first one still answers, the state is still loaded and no failed-load retry was started.
         */
        private void assertRefreshDiscarded(RetryingJwksLoader loader) throws Exception {
            awaitClosed(1);
            drainScheduler(Duration.ZERO);
            assertEquals(2, built.size(), "exactly one refresh was built");
            assertFalse(built.getFirst().closed.get(), "the loaded delegate stays open");
            assertTrue(loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isPresent(),
                    "the loaded delegate keeps answering");
            assertEquals(KeySetState.LOADED, loader.keySetState());
            assertFalse(loader.retryPending(), "a discarded refresh never starts the failed-load retry");
        }

        @Test
        @DisplayName("1000 lookups with 1000 distinct unknown key ids build one delegate per window, and warn once")
        void oneRefreshPerWindowWhateverTheKeyIds() throws Exception {
            // Arrange — the endpoint keeps serving the loaded key set, so every refresh is discarded
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            Instant loadedAt = now.get();
            now.set(loadedAt.plusSeconds(5));

            // Act — the first window
            for (int i = 0; i < 1000; i++) {
                assertTrue(loader.getKeyInfo("invented-" + i).isEmpty());
            }
            awaitClosed(1);
            drainScheduler(Duration.ZERO);

            // Assert — one refresh, one WARN, carrying the issuer name and the last-loaded instant only
            assertEquals(2, built.size(), "the first delegate and exactly one refresh");
            List<String> warnings = unknownKeyIdWarnings();
            assertEquals(1, warnings.size(), () -> "one WARN per window, got " + warnings);
            String warning = warnings.getFirst();
            assertTrue(warning.contains("itself at " + loadedAt + ";"), warning);
            assertFalse(warning.contains("invented-"), "the key id is never logged: " + warning);
            assertFalse(warning.contains(JWKS_PATH), "the JWKS url is never logged: " + warning);
            assertFalse(warning.contains(String.valueOf(server.getPort())), "the JWKS url is never logged: " + warning);

            // Act — one millisecond before the window ends nothing more is admitted
            now.set(loadedAt.plusSeconds(5).plus(WINDOW).minusMillis(1));
            for (int i = 1000; i < 2000; i++) {
                loader.getKeyInfo("invented-" + i);
            }
            drainScheduler(Duration.ZERO);
            assertEquals(2, built.size(), "a lookup inside the claimed window builds nothing");

            // Act — the second window admits exactly one more
            now.set(loadedAt.plusSeconds(5).plus(WINDOW));
            for (int i = 2000; i < 3000; i++) {
                loader.getKeyInfo("invented-" + i);
            }
            awaitClosed(2);
            drainScheduler(Duration.ZERO);

            // Assert
            assertEquals(3, built.size(), "the second window admits exactly one more refresh");
            loader.close();
        }

        @Test
        @DisplayName("a known key id triggers no refresh and no WARN")
        void knownKeyIdTriggersNothing() throws Exception {
            // Arrange
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);

            // Act
            for (int i = 0; i < 100; i++) {
                assertTrue(loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isPresent());
            }
            drainScheduler(Duration.ZERO);

            // Assert
            assertEquals(1, built.size());
            assertTrue(unknownKeyIdWarnings().isEmpty(), "no unknown-key-id WARN is logged");
            loader.close();
        }

        @Test
        @DisplayName("a lookup before the first load completed triggers no refresh")
        void lookupBeforeFirstLoadTriggersNothing() throws Exception {
            // Arrange — never initialised, so no key set is loaded
            RetryingJwksLoader loader = refreshingLoader();

            // Act
            Optional<KeyInfo> key = loader.getKeyInfo("invented");
            drainScheduler(Duration.ZERO);

            // Assert
            assertTrue(key.isEmpty());
            assertEquals(1, built.size());
            assertTrue(unknownKeyIdWarnings().isEmpty(), "no unknown-key-id WARN is logged");
            loader.close();
        }

        @Test
        @DisplayName("no refresh starts while a failed-load retry is pending")
        void noRefreshWhileRetryPending() throws Exception {
            // Arrange — the first load failed, so the retry sequence owns the issuer
            failuresBeforeSuccess.set(Integer.MAX_VALUE);
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            assertTrue(loader.retryPending(), "precondition: a retry is scheduled");
            int requestsAfterInit = server.getRequestCount();

            // Act
            loader.getKeyInfo("invented");

            // Assert — nothing was handed to the scheduler beside the retry, and nothing was fetched
            assertEquals(1, scheduler.getQueue().size(), "only the retry is scheduled");
            assertEquals(1, built.size());
            assertEquals(requestsAfterInit, server.getRequestCount());
            assertTrue(unknownKeyIdWarnings().isEmpty(), "no unknown-key-id WARN is logged");
            loader.close();
        }

        @Test
        @DisplayName("a refresh whose load fails leaves the loaded delegate published and closes the fresh one")
        void failedRefreshChangesNothing() throws Exception {
            // Arrange
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            failuresBeforeSuccess.set(Integer.MAX_VALUE);

            // Act
            loader.getKeyInfo(ROTATED_KEY_ID);

            // Assert
            assertRefreshDiscarded(loader);
            loader.close();
        }

        @Test
        @DisplayName("a refresh that loads no key leaves the loaded delegate published and closes the fresh one")
        void keylessRefreshChangesNothing() throws Exception {
            // Arrange
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            jwksBody.set("{\"keys\":[]}");

            // Act
            loader.getKeyInfo(ROTATED_KEY_ID);

            // Assert
            assertRefreshDiscarded(loader);
            loader.close();
        }

        @Test
        @DisplayName("a refresh whose key set lacks the triggering key id leaves the loaded delegate published")
        void refreshWithoutTheTriggeringKeyIdChangesNothing() throws Exception {
            // Arrange — the endpoint did rotate, but the lookup names a key id it never published
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            rotateSigningKey();

            // Act
            loader.getKeyInfo("invented");

            // Assert — an invented key id cannot displace the loaded key set, however fresh the fetch
            assertRefreshDiscarded(loader);
            assertTrue(loader.getKeyInfo(ROTATED_KEY_ID).isEmpty(), "the discarded key set answers nothing");
            loader.close();
        }

        @Test
        @DisplayName("a refresh that cannot be built changes nothing, and the next window tries again")
        void refusedRefreshBuildChangesNothing() throws Exception {
            // Arrange — the second build, which is the refresh, is refused by the factory
            AtomicInteger builds = new AtomicInteger();
            RetryingJwksLoader loader = loader(() -> {
                if (builds.incrementAndGet() == 2) {
                    throw new GatewayException(EventType.CONFIG_INVALID, "simulated tls_profile resolution failure");
                }
                return trackedDelegate();
            });
            initialise(loader);
            rotateSigningKey();

            // Act — the refused refresh, then a lookup in the next window
            loader.getKeyInfo(ROTATED_KEY_ID);
            drainScheduler(Duration.ZERO);
            boolean answeredAfterRefusal = loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isPresent();
            now.set(now.get().plus(WINDOW));
            loader.getKeyInfo(ROTATED_KEY_ID);
            awaitRotatedKeyPublished(loader);

            // Assert — an anticipated refusal stays DEBUG: it records no unexpected-failure WARN
            assertTrue(answeredAfterRefusal, "the loaded delegate keeps answering after a refused build");
            assertEquals(3, builds.get(), "the first delegate, the refused refresh and the next window's one");
            assertTrue(warningsContaining(WARN_REFRESH_FAILED).isEmpty(),
                    "an anticipated refusal is not reported as an unexpected refresh failure");
            loader.close();
        }

        @Test
        @DisplayName("a refresh build that fails in a way nobody anticipated does not switch the refresh off")
        void unanticipatedRefreshBuildFailureReleasesTheRefresh() throws Exception {
            // Arrange — the second build, which is the refresh, fails with a type the loader does not
            // name among the refusals it expects from the factory
            AtomicInteger builds = new AtomicInteger();
            RetryingJwksLoader loader = loader(() -> {
                if (builds.incrementAndGet() == 2) {
                    throw new UnsupportedOperationException("simulated unanticipated factory failure");
                }
                return trackedDelegate();
            });
            initialise(loader);
            rotateSigningKey();

            // Act — the failed refresh, then a lookup in the next window
            loader.getKeyInfo(ROTATED_KEY_ID);
            drainScheduler(Duration.ZERO);
            now.set(now.get().plus(WINDOW));
            loader.getKeyInfo(ROTATED_KEY_ID);
            awaitRotatedKeyPublished(loader);

            // Assert — the next window's refresh ran, so the first failure left no in-flight claim behind
            assertEquals(3, builds.get(), "the first delegate, the failed refresh and the next window's one");
            // ... and the failure was reported exactly once, by issuer name only
            LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN, WARN_REFRESH_FAILED);
            String warning = warningsContaining(WARN_REFRESH_FAILED).getFirst();
            assertTrue(warning.contains("'" + ISSUER_NAME + "'"), "the WARN names the issuer: " + warning);
            assertFalse(warning.contains(ROTATED_KEY_ID), "the WARN never carries the triggering key id");
            assertFalse(warning.contains(JWKS_PATH), "the WARN never carries the JWKS path");
            assertFalse(warning.contains(String.valueOf(server.getPort())), "the WARN never carries the JWKS port");
            assertFalse(warning.contains("simulated unanticipated factory failure"),
                    "the WARN never carries the failure's message");
            loader.close();
        }

        @Test
        @DisplayName("a refresh delegate that is built but cannot be started is closed and does not switch the refresh off")
        void refreshDelegateThatCannotBeStartedIsClosedAndReleasesTheRefresh() throws Exception {
            // Arrange — the second delegate, which is the refresh, is built but refuses to start its load
            AtomicInteger builds = new AtomicInteger();
            RetryingJwksLoader loader = loader(() -> {
                if (builds.incrementAndGet() != 2) {
                    return trackedDelegate();
                }
                TrackedLoader refusing = new TrackedLoader(httpConfig()) {
                    @Override
                    public CompletableFuture<LoaderStatus> initJWKSLoader(SecurityEventCounter securityEventCounter) {
                        throw new IllegalStateException("simulated failure to start the load");
                    }
                };
                built.add(refusing);
                return refusing;
            });
            initialise(loader);
            rotateSigningKey();

            // Act — the refresh that cannot be started, then a lookup in the next window
            loader.getKeyInfo(ROTATED_KEY_ID);
            awaitClosed(1);
            drainScheduler(Duration.ZERO);
            boolean answeredAfterFailure = loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isPresent();
            now.set(now.get().plus(WINDOW));
            loader.getKeyInfo(ROTATED_KEY_ID);
            awaitRotatedKeyPublished(loader);

            // Assert — the built delegate was closed, and the next window's refresh ran
            assertTrue(answeredAfterFailure, "the loaded delegate keeps answering after a refresh that did not start");
            assertEquals(3, builds.get(), "the first delegate, the refresh that did not start and the next window's one");
            loader.close();
        }

        @Test
        @DisplayName("a refresh holding the triggering key id is published and answers the next lookup")
        void refreshHoldingTheKeyIdIsPublished() throws Exception {
            // Arrange
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            rotateSigningKey();

            // Act — the triggering lookup itself is answered from the key sets held at that moment
            Optional<KeyInfo> triggering = loader.getKeyInfo(ROTATED_KEY_ID);
            awaitRotatedKeyPublished(loader);

            // Assert
            assertTrue(triggering.isEmpty(), "the lookup that triggers a refresh never waits for it");
            assertEquals(2, built.size());
            assertEquals(KeySetState.LOADED, loader.keySetState());
            assertEquals(LoaderStatus.OK, loader.getLoaderStatus());
            assertFalse(built.get(1).closed.get(), "the published delegate stays open");
            loader.close();
        }

        @Test
        @DisplayName("the replaced delegate answers its own key id until the grace deadline and is closed after it")
        void replacedDelegateAnswersUntilTheGraceDeadline() throws Exception {
            // Arrange — a grace period the scheduler reaches within the test. The deadline a lookup
            // honours is read from the fixture's clock; the closing of the replaced delegate is run by
            // the scheduler after the same period of real time, so the period is long enough for the
            // in-grace lookup below to run well before the scheduler gets there.
            Duration grace = Duration.ofSeconds(2);
            RetryingJwksLoader loader = refreshingLoader(WINDOW, grace);
            initialise(loader);
            rotateSigningKey();
            loader.getKeyInfo(ROTATED_KEY_ID);
            awaitRotatedKeyPublished(loader);
            Instant swappedAt = now.get();

            // Act + Assert — inside the grace period the previous key id still verifies
            now.set(swappedAt.plus(grace).minusMillis(1));
            assertTrue(loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isPresent(),
                    "a token signed with the replaced key is still accepted inside the grace period");

            // Act + Assert — at the deadline it no longer does, and the replaced delegate is closed
            now.set(swappedAt.plus(grace));
            assertTrue(loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isEmpty(),
                    "the replaced key set stops answering at the grace deadline");
            awaitClosed(0);
            assertFalse(built.get(1).closed.get(), "the current delegate is untouched by the retirement");
            loader.close();
        }

        @Test
        @DisplayName("a grace period of zero closes the replaced delegate at once")
        void zeroGraceRetiresAtOnce() throws Exception {
            // Arrange
            RetryingJwksLoader loader = refreshingLoader(WINDOW, Duration.ZERO);
            initialise(loader);
            rotateSigningKey();

            // Act
            loader.getKeyInfo(ROTATED_KEY_ID);
            awaitRotatedKeyPublished(loader);

            // Assert
            awaitClosed(0);
            assertTrue(loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isEmpty(),
                    "without a grace period the replaced key set answers nothing");
            loader.close();
        }

        @Test
        @DisplayName("a second replacement inside the grace period closes the older retired delegate")
        void secondReplacementClosesTheOlderRetiredDelegate() throws Exception {
            // Arrange — one replacement done, its predecessor retired for the whole test
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            rotateSigningKey();
            loader.getKeyInfo(ROTATED_KEY_ID);
            awaitRotatedKeyPublished(loader);
            String secondRotation = "rotated-again";
            jwksBody.set(InMemoryKeyMaterialHandler.createDefaultJwks()
                    .replace(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID, secondRotation));

            // Act — the next window replaces the key set again
            now.set(now.get().plus(WINDOW));
            loader.getKeyInfo(secondRotation);
            Awaits.until(() -> loader.getKeyInfo(secondRotation).isPresent(), "the second refresh to be published",
                    Awaits.CONNECT_CEILING_SECONDS);

            // Assert — at most one retired delegate: the oldest is closed, its successor is retired
            awaitClosed(0);
            assertFalse(built.get(1).closed.get(), "the delegate replaced last is the retired one");
            assertTrue(loader.getKeyInfo(ROTATED_KEY_ID).isPresent(), "and still answers its own key id");
            loader.close();
        }

        @Test
        @DisplayName("a zero window logs the WARN once and builds nothing")
        void zeroWindowWarnsAndBuildsNothing() throws Exception {
            // Arrange — what a file source passes: nothing to fetch
            RetryingJwksLoader loader = refreshingLoader(Duration.ZERO, Duration.ZERO);
            initialise(loader);

            // Act
            for (int i = 0; i < 100; i++) {
                loader.getKeyInfo("invented-" + i);
            }
            drainScheduler(Duration.ZERO);

            // Assert — the WARN stays rate-limited although no refresh window is configured
            assertEquals(1, built.size(), "a disabled refresh builds no delegate");
            assertEquals(1, unknownKeyIdWarnings().size(), "one WARN, however many lookups");
            loader.close();
        }

        @Test
        @DisplayName("close closes the current, the retired and a still loading delegate")
        void closeClosesEveryHeldDelegate() throws Exception {
            // Arrange — one replacement done, so a current and a retired delegate exist ...
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            rotateSigningKey();
            loader.getKeyInfo(ROTATED_KEY_ID);
            awaitRotatedKeyPublished(loader);
            // ... and a second refresh whose response the endpoint holds, so it is still loading
            CountDownLatch gate = new CountDownLatch(1);
            responseGate.set(gate);
            int requestsBefore = server.getRequestCount();
            now.set(now.get().plus(WINDOW));
            loader.getKeyInfo("invented");
            Awaits.until(() -> server.getRequestCount() > requestsBefore, "the refresh to reach the endpoint",
                    Awaits.CONNECT_CEILING_SECONDS);

            // Act
            loader.close();

            // Assert
            try {
                assertEquals(3, built.size());
                assertTrue(built.get(0).closed.get(), "the retired delegate is closed");
                assertTrue(built.get(1).closed.get(), "the current delegate is closed");
                assertTrue(built.get(2).closed.get(), "the delegate still loading is closed");
            } finally {
                gate.countDown();
            }
        }

        @Test
        @DisplayName("a closed loader schedules no refresh")
        void closedLoaderSchedulesNothing() throws Exception {
            // Arrange
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            loader.close();

            // Act
            loader.getKeyInfo("invented");
            drainScheduler(Duration.ZERO);

            // Assert
            assertEquals(1, built.size());
            assertTrue(unknownKeyIdWarnings().isEmpty(), "no unknown-key-id WARN is logged");
        }

        @Test
        @DisplayName("a scheduler that refuses the refresh never fails the lookup")
        void refusedSchedulingStaysOffTheRequestThread() throws Exception {
            // Arrange — the scheduler is shut down, so it rejects every new task
            RetryingJwksLoader loader = refreshingLoader();
            initialise(loader);
            scheduler.shutdown();

            // Act
            Optional<KeyInfo> key = assertDoesNotThrow(() -> loader.getKeyInfo("invented"),
                    "the lookup is answered from the held key sets whatever the scheduler does");

            // Assert
            assertTrue(key.isEmpty());
            assertEquals(1, built.size());
            assertTrue(loader.getKeyInfo(InMemoryKeyMaterialHandler.DEFAULT_KEY_ID).isPresent());
            loader.close();
        }

        @Test
        @DisplayName("the loader reports the window and the grace period it was built with")
        void reportsItsTiming() {
            // Arrange + Act
            RetryingJwksLoader loader = refreshingLoader(Duration.ofSeconds(7), Duration.ofSeconds(11));

            // Assert
            assertEquals(Duration.ofSeconds(7), loader.unknownKeyIdWindow());
            assertEquals(Duration.ofSeconds(11), loader.keyRotationGracePeriod());
            loader.close();
        }
    }

    /**
     * Where a retry fails in a way the loader does not anticipate: every member raises a failure
     * outside the refusals the loader expects from its factory.
     */
    enum RetryEscape {
        /** The factory yields {@code null}, which the loader refuses with a NullPointerException. */
        FACTORY_YIELDS_NULL,
        /** The factory throws an unchecked exception of a type the loader does not name. */
        FACTORY_THROWS_UNCHECKED,
        /** The factory throws an error. */
        FACTORY_THROWS_ERROR,
        /** Closing the delegate the retry replaces throws. */
        REPLACED_DELEGATE_CLOSE_THROWS,
        /** The fresh delegate, already published, throws synchronously when its load is started. */
        LOAD_START_THROWS
    }

    /** The real library HTTP loader, recording that it was closed. */
    private static class TrackedLoader extends HttpJwksLoader {

        private final AtomicBoolean closed = new AtomicBoolean();

        TrackedLoader(HttpJwksLoaderConfig config) {
            super(config);
        }

        @Override
        public void close() {
            closed.set(true);
            super.close();
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
