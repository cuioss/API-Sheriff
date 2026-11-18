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
package de.cuioss.sheriff.gateway.edge.upstream;

import static org.junit.jupiter.api.Assertions.*;

import java.io.Serial;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The state machine of {@link UpstreamCircuitBreaker}, driven under a manual nano clock and small policies,
 * so no case waits on a real delay.
 * <p>
 * Every case reads the breaker through two observables only: whether a call is admitted (its action runs)
 * or refused (a {@link CircuitBreakerOpenException}, its action never run), and the list of states the
 * breaker reported entering. The three outcomes are produced by the action itself — it returns (success),
 * throws a {@link GatewayException} (neutral) or throws anything else (failure).
 * <p>
 * Where a case needs two calls in flight at once, or a result that arrives after the state moved on, the
 * calls are nested: the outer action runs the inner calls before it returns, which orders the admissions
 * and the outcomes deterministically without a second thread. The breaker holds no lock while an action
 * runs, so a nested call is a call that arrived while the outer one was still waiting for its upstream.
 */
@EnableGeneratorController
@EnableTestLogger
@DisplayName("UpstreamCircuitBreaker — a neutral outcome is neither charged nor credited")
class UpstreamCircuitBreakerTest {

    /** How long the breakers here stay open; only ever passed on the manual clock. */
    private static final Duration OPEN_DELAY = Duration.ofSeconds(UpstreamGuards.BREAKER_RESET_SECONDS);

    private static final String ANSWER = "answered";

    private final AtomicLong clock = new AtomicLong();
    private final List<CircuitBreakerState> transitions = new CopyOnWriteArrayList<>();
    private String name;

    @BeforeEach
    void setUp() {
        clock.set(Generators.integers(0, Integer.MAX_VALUE).next());
        name = Generators.letterStrings(8, 16).next();
    }

    @Nested
    @DisplayName("closed")
    class Closed {

        @Test
        @DisplayName("a neutral outcome records nothing: neither a failure that opens nor a success that dilutes")
        void neutralOutcomeRecordsNothing() {
            UpstreamCircuitBreaker breaker = breaker(2, 1.0, 1);
            int neutralCalls = Generators.integers(3, 10).next();

            failCall(breaker);
            for (int call = 0; call < neutralCalls; call++) {
                endNeutral(breaker);
            }
            List<CircuitBreakerState> afterNeutral = List.copyOf(transitions);
            failCall(breaker);

            assertAll("one failure, neutral outcomes, a second failure on a two-call window",
                    () -> assertEquals(List.of(), afterNeutral,
                            "the neutral calls neither opened the breaker nor filled its window"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the second failure filled the window with two failures and opened the breaker"));
            assertRefused(breaker);
        }

        @Test
        @DisplayName("the window holds only the last recorded outcomes: an evicted failure no longer counts")
        void windowIsTheLastRecordedOutcomes() throws Exception {
            UpstreamCircuitBreaker breaker = breaker(4, 0.5, 1);

            failCall(breaker);
            for (int call = 0; call < 4; call++) {
                assertEquals(ANSWER, succeed(breaker));
            }
            failCall(breaker);
            List<CircuitBreakerState> afterEviction = List.copyOf(transitions);
            failCall(breaker);

            assertAll("a failure pushed out of a four-call window by four successes",
                    () -> assertEquals(List.of(), afterEviction,
                            "the second failure overall is the only one in the window: closed"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the third failure overall makes two in the window: open"));
        }

        @Test
        @DisplayName("the failure ratio is judged only once the window is full")
        void opensOnlyOnceTheWindowIsFull() throws Exception {
            UpstreamCircuitBreaker breaker = breaker(4, 0.5, 1);

            failCall(breaker);
            failCall(breaker);
            succeed(breaker);
            List<CircuitBreakerState> beforeFull = List.copyOf(transitions);
            succeed(breaker);

            assertAll("two failures in a four-call window",
                    () -> assertEquals(List.of(), beforeFull, "three outcomes leave the window short: closed"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the fourth outcome fills the window at the failure ratio: open"));
        }
    }

    @Nested
    @DisplayName("open")
    class Open {

        @Test
        @DisplayName("refuses every call, unrun and naming the breaker, until the open delay has passed")
        void refusesUntilTheDelayPassed() throws Exception {
            UpstreamCircuitBreaker breaker = opened(1);

            assertRefused(breaker);
            clock.addAndGet(OPEN_DELAY.toNanos() - 1);
            assertRefused(breaker);
            clock.incrementAndGet();
            String answer = succeed(breaker);

            assertAll("the first call once the delay passed",
                    () -> assertEquals(ANSWER, answer, "it was admitted and answered"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                                    CircuitBreakerState.CLOSED), transitions,
                            "it moved the breaker to half-open, and its success closed it"));
        }
    }

    @Nested
    @DisplayName("half-open")
    class HalfOpen {

        @Test
        @DisplayName("admits at most the success threshold of trials at once and refuses the rest")
        void admitsAtMostTheSuccessThresholdOfTrials() throws Exception {
            UpstreamCircuitBreaker breaker = halfOpenDue(2);
            AtomicReference<CircuitBreakerOpenException> third = new AtomicReference<>();

            String answer = breaker.call(() -> breaker.call(() -> {
                third.set(assertRefused(breaker));
                return ANSWER;
            }));

            assertAll("two trials in flight and a third call",
                    () -> assertNotNull(third.get(), "the third call was refused while two trials were in flight"),
                    () -> assertEquals(ANSWER, answer, "both trials were admitted and answered"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                            CircuitBreakerState.CLOSED), transitions, "the two successful trials closed the breaker"));
        }

        @Test
        @DisplayName("a neutral trial gives its permit back: a further call is admitted in its place")
        void neutralTrialGivesItsPermitBack() throws Exception {
            UpstreamCircuitBreaker breaker = halfOpenDue(2);
            AtomicBoolean replacementRan = new AtomicBoolean();

            String answer = breaker.call(() -> {
                endNeutral(breaker);
                return breaker.call(() -> {
                    replacementRan.set(true);
                    return ANSWER;
                });
            });

            assertAll("a neutral trial beside a trial still in flight",
                    () -> assertTrue(replacementRan.get(),
                            "the call after the neutral trial was admitted although one trial was still in flight"),
                    () -> assertEquals(ANSWER, answer, "the replacement and the outer trial were answered"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                            CircuitBreakerState.CLOSED), transitions, "the two answered trials closed the breaker"));
        }

        @Test
        @DisplayName("a neutral trial does not count toward the close")
        void neutralTrialDoesNotCountTowardTheClose() throws Exception {
            UpstreamCircuitBreaker breaker = halfOpenDue(2);

            endNeutral(breaker);
            endNeutral(breaker);
            succeed(breaker);
            List<CircuitBreakerState> afterOneSuccess = List.copyOf(transitions);
            succeed(breaker);

            assertAll("two neutral trials and two successful ones",
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN),
                            afterOneSuccess, "two neutral trials and one success leave the breaker half-open"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                            CircuitBreakerState.CLOSED), transitions, "the second success closes it"));
        }

        @Test
        @DisplayName("a failed trial reopens the breaker for another open delay")
        void failedTrialReopens() throws Exception {
            UpstreamCircuitBreaker breaker = halfOpenDue(2);

            failCall(breaker);
            assertRefused(breaker);
            clock.addAndGet(OPEN_DELAY.toNanos());
            String answer = succeed(breaker);

            assertAll("a failed trial",
                    () -> assertEquals(ANSWER, answer, "a trial is admitted again once the new delay passed"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                                    CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN), transitions,
                            "the failed trial reopened the breaker, the next trial half-opened it again"));
        }

        @Test
        @DisplayName("enough successful trials close the breaker with an empty window")
        void successfulTrialsCloseWithAnEmptyWindow() throws Exception {
            UpstreamCircuitBreaker breaker = breaker(2, 1.0, 2);
            failCall(breaker);
            failCall(breaker);
            clock.addAndGet(OPEN_DELAY.toNanos());

            succeed(breaker);
            succeed(breaker);
            failCall(breaker);
            String answer = succeed(breaker);

            assertAll("a two-call window opened by two failures, closed by two trials, then one failure",
                    () -> assertEquals(ANSWER, answer, "the call after the failure was admitted"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                                    CircuitBreakerState.CLOSED), transitions,
                            "the failures that opened the breaker were cleared on the close, so one failure does not reopen it"));
        }
    }

    @Nested
    @DisplayName("a result recorded after the state moved on")
    class SupersededGeneration {

        @Test
        @DisplayName("a failure admitted while closed and ending while half-open does not reopen the breaker")
        void supersededFailureChangesNothing() throws Exception {
            UpstreamCircuitBreaker breaker = breaker(2, 1.0, 2);
            AtomicReference<List<CircuitBreakerState>> whileOuterInFlight = new AtomicReference<>();

            assertThrows(IllegalStateException.class, () -> breaker.call(() -> {
                openAndTrialOnce(breaker);
                whileOuterInFlight.set(List.copyOf(transitions));
                throw new IllegalStateException(Generators.letterStrings(8, 16).next());
            }));
            List<CircuitBreakerState> afterOuter = List.copyOf(transitions);
            succeed(breaker);

            assertAll("a closed-state failure that ends after the breaker opened and half-opened",
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN),
                            whileOuterInFlight.get(), "the inner calls opened and half-opened the breaker"),
                    () -> assertEquals(whileOuterInFlight.get(), afterOuter,
                            "the superseded failure did not reopen the half-open breaker"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                                    CircuitBreakerState.CLOSED), transitions,
                            "the next successful trial is the second one and closes the breaker"));
        }

        @Test
        @DisplayName("a success admitted while closed and ending while half-open does not count toward the close")
        void supersededSuccessChangesNothing() throws Exception {
            UpstreamCircuitBreaker breaker = breaker(2, 1.0, 2);
            AtomicReference<List<CircuitBreakerState>> whileOuterInFlight = new AtomicReference<>();

            String answer = breaker.call(() -> {
                openAndTrialOnce(breaker);
                whileOuterInFlight.set(List.copyOf(transitions));
                return ANSWER;
            });
            List<CircuitBreakerState> afterOuter = List.copyOf(transitions);

            assertAll("a closed-state success that ends after the breaker opened and half-opened",
                    () -> assertEquals(ANSWER, answer, "the outer call returned its answer unchanged"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN),
                            whileOuterInFlight.get(), "the inner calls opened and half-opened the breaker"),
                    () -> assertEquals(whileOuterInFlight.get(), afterOuter,
                            "the superseded success was not taken as the second successful trial"));
        }

        /**
         * Opens a two-call, ratio-1 breaker with two failures, lets the open delay pass and runs one
         * successful trial, leaving the breaker half-open with one success toward its close.
         */
        private void openAndTrialOnce(UpstreamCircuitBreaker breaker) throws Exception {
            failCall(breaker);
            failCall(breaker);
            clock.addAndGet(OPEN_DELAY.toNanos());
            succeed(breaker);
        }
    }

    @Nested
    @DisplayName("outcome classification")
    class OutcomeClassification {

        /**
         * The gateway rejections an action can end in, each with its {@link GatewayException} at a
         * different place in the cause chain.
         *
         * @return one named supplier per rejection
         */
        static Stream<Arguments> gatewayRejections() {
            Supplier<Exception> clientLeft = () -> new DispatchStage.ClientDeparted(departureCause());
            Supplier<Exception> wrappedClientLeft =
                    () -> new ExecutionException(new DispatchStage.ClientDeparted(departureCause()));
            Supplier<Exception> poolSaturated = () -> new GatewayException(EventType.UPSTREAM_POOL_SATURATED,
                    Generators.letterStrings(8, 16).next(), departureCause());
            return Stream.of(
                    Arguments.of(Named.of("a client that left", clientLeft)),
                    Arguments.of(Named.of("a client that left, wrapped by an ExecutionException", wrappedClientLeft)),
                    Arguments.of(Named.of("a full wait queue for an upstream connection", poolSaturated)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("gatewayRejections")
        @DisplayName("a GatewayException anywhere in the cause chain is neutral and is rethrown unchanged")
        void gatewayRejectionIsNeutral(Supplier<Exception> rejection) {
            UpstreamCircuitBreaker breaker = breaker(1, 1.0, 1);
            Exception thrown = rejection.get();

            Exception surfaced = assertThrows(Exception.class, () -> breaker.call(() -> {
                throw thrown;
            }));
            List<CircuitBreakerState> afterRejection = List.copyOf(transitions);
            failCall(breaker);

            assertAll("a gateway rejection on a one-call window",
                    () -> assertSame(thrown, surfaced, "the rejection is rethrown unchanged"),
                    () -> assertEquals(List.of(), afterRejection, "the rejection was not counted as a failure"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "control: an ordinary failure on the same window opens the breaker"));
        }

        @Test
        @DisplayName("an action ending in an Error is recorded as a failure and the Error is rethrown unchanged")
        void errorIsAFailure() {
            UpstreamCircuitBreaker breaker = breaker(1, 1.0, 1);
            ActionError error = new ActionError(Generators.letterStrings(8, 16).next());

            ActionError surfaced = assertThrows(ActionError.class, () -> breaker.call(() -> {
                throw error;
            }));

            assertAll("an Error on a one-call window",
                    () -> assertSame(error, surfaced, "the Error is rethrown unchanged"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the Error was recorded as a failure and opened the breaker"));
        }

        @Test
        @DisplayName("a half-open trial ending in an Error settles its permit as a failed trial")
        void errorSettlesItsTrialPermit() throws Exception {
            UpstreamCircuitBreaker breaker = halfOpenDue(1);
            ActionError error = new ActionError(Generators.letterStrings(8, 16).next());

            assertThrows(ActionError.class, () -> breaker.call(() -> {
                throw error;
            }));
            assertRefused(breaker);
            clock.addAndGet(OPEN_DELAY.toNanos());
            String answer = succeed(breaker);

            assertAll("a trial ending in an Error",
                    () -> assertEquals(ANSWER, answer, "a later trial is admitted: no permit was left behind"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                                    CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED),
                            transitions, "the Error reopened the breaker; the later trial closed it"));
        }
    }

    @Nested
    @DisplayName("state-change reporting")
    class Reporting {

        @Test
        @DisplayName("each transition is reported once, after the breaker's monitor was left")
        void transitionsAreReportedOutsideTheMonitor() throws Exception {
            AtomicReference<UpstreamCircuitBreaker> holder = new AtomicReference<>();
            List<Boolean> heldTheMonitor = new CopyOnWriteArrayList<>();
            UpstreamCircuitBreaker breaker = new UpstreamCircuitBreaker(1, 1.0, OPEN_DELAY, 1, clock::get, name,
                    state -> {
                        heldTheMonitor.add(Thread.holdsLock(holder.get()));
                        transitions.add(state);
                    });
            holder.set(breaker);

            failCall(breaker);
            clock.addAndGet(OPEN_DELAY.toNanos());
            succeed(breaker);

            assertAll("a full open, half-open, closed cycle",
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                            CircuitBreakerState.CLOSED), transitions, "each transition was reported once"),
                    () -> assertEquals(List.of(false, false, false), heldTheMonitor,
                            "no transition was reported while the breaker's monitor was held"));
        }

        @Test
        @DisplayName("a consumer that throws on the open does not replace the failure that opened the breaker")
        void throwingReportKeepsTheOpeningFailure() {
            UpstreamCircuitBreaker breaker = reportingTo(1, failingOn(CircuitBreakerState.OPEN));
            IllegalStateException failure = new IllegalStateException(Generators.letterStrings(8, 16).next());

            Exception surfaced = assertThrows(Exception.class, () -> breaker.call(() -> {
                throw failure;
            }));

            assertAll("a failure that opens the breaker while its report throws",
                    () -> assertSame(failure, surfaced, "the call surfaces its own failure, not the report's"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the transition was still delivered to the consumer"),
                    () -> LogAsserts.assertSingleLogMessagePresentContaining(TestLogLevel.WARN,
                            ApiSheriffLogMessages.WARN.CIRCUIT_BREAKER_REPORT_FAILED.resolveIdentifierString()));
            assertRefused(breaker);
        }

        @Test
        @DisplayName("a consumer that throws on the close does not replace the answer of the trial that closed it")
        void throwingReportKeepsTheClosingAnswer() throws Exception {
            UpstreamCircuitBreaker breaker = reportingTo(1, failingOn(CircuitBreakerState.CLOSED));
            failCall(breaker);
            clock.addAndGet(OPEN_DELAY.toNanos());

            String answer = succeed(breaker);

            assertAll("a successful trial that closes the breaker while its report throws",
                    () -> assertEquals(ANSWER, answer, "the trial returns its answer, not the report's failure"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                            CircuitBreakerState.CLOSED), transitions, "the close was still delivered"));
        }

        @Test
        @DisplayName("a consumer that throws on the half-open runs the admitted trial and leaves no permit behind")
        void throwingReportOnHalfOpenRunsTheTrial() throws Exception {
            UpstreamCircuitBreaker breaker = reportingTo(1, failingOn(CircuitBreakerState.HALF_OPEN));
            failCall(breaker);
            clock.addAndGet(OPEN_DELAY.toNanos());
            AtomicBoolean trialRan = new AtomicBoolean();

            String answer = breaker.call(() -> {
                trialRan.set(true);
                return ANSWER;
            });
            failCall(breaker);
            List<CircuitBreakerState> afterReopen = List.copyOf(transitions);
            clock.addAndGet(OPEN_DELAY.toNanos());
            String laterAnswer = succeed(breaker);

            assertAll("the call that moved the breaker to half-open while its report threw",
                    () -> assertTrue(trialRan.get(), "the admitted trial ran its action"),
                    () -> assertEquals(ANSWER, answer, "and returned its answer"),
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                                    CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN), afterReopen,
                            "its success closed the breaker; a further failure opened it again"),
                    () -> assertEquals(ANSWER, laterAnswer,
                            "a trial is admitted after the next half-open report threw too: no permit leaked"));
        }

        /**
         * @param successThreshold the trials that close it
         * @param consumer         the state-change consumer
         * @return a one-call, ratio-1 breaker on the manual clock reporting to {@code consumer}
         */
        private UpstreamCircuitBreaker reportingTo(int successThreshold, Consumer<CircuitBreakerState> consumer) {
            return new UpstreamCircuitBreaker(1, 1.0, OPEN_DELAY, successThreshold, clock::get, name, consumer);
        }

        /**
         * @param failing the state whose report throws
         * @return a consumer that records every state in {@link #transitions} and then throws on {@code failing}
         */
        private Consumer<CircuitBreakerState> failingOn(CircuitBreakerState failing) {
            return state -> {
                transitions.add(state);
                if (state == failing) {
                    throw new IllegalStateException("report of " + state + " failed");
                }
            };
        }
    }

    @Nested
    @DisplayName("concurrent calls")
    class Concurrent {

        /** How many threads call one breaker at once. */
        private static final int THREADS = 8;

        /** How many calls each thread makes. */
        private static final int CALLS_PER_THREAD = 500;

        @Test
        @DisplayName("concurrent failures open the breaker exactly once and every later call is refused")
        void concurrentFailuresOpenItOnce() throws Exception {
            UpstreamCircuitBreaker breaker = breaker(UpstreamGuards.BREAKER_REQUEST_VOLUME_THRESHOLD,
                    UpstreamGuards.BREAKER_FAILURE_RATIO, UpstreamGuards.BREAKER_SUCCESS_THRESHOLD);
            AtomicLong refused = new AtomicLong();
            AtomicLong ran = new AtomicLong();

            runConcurrently(() -> {
                try {
                    breaker.call(() -> {
                        ran.incrementAndGet();
                        throw new IllegalStateException("upstream failed");
                    });
                } catch (CircuitBreakerOpenException refusal) {
                    refused.incrementAndGet();
                } catch (IllegalStateException expected) {
                    // the admitted call's own failure
                } catch (Exception unexpected) {
                    throw new AssertionError("a call ended neither in its own failure nor in a refusal",
                            unexpected);
                }
            });

            assertAll("8 threads x 500 failing calls on the production policy",
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN), transitions,
                            "the breaker reported one open and nothing else"),
                    () -> assertEquals((long) THREADS * CALLS_PER_THREAD, ran.get() + refused.get(),
                            "every call was either run or refused"),
                    () -> assertTrue(ran.get() >= UpstreamGuards.BREAKER_REQUEST_VOLUME_THRESHOLD,
                            "at least a full window of calls ran before the breaker opened"),
                    () -> assertTrue(ran.get() < (long) THREADS * CALLS_PER_THREAD,
                            "once open, calls were refused unrun"));
            assertRefused(breaker);
        }

        @Test
        @DisplayName("concurrent successful calls on a half-open breaker close it exactly once")
        void concurrentTrialsCloseItOnce() throws Exception {
            UpstreamCircuitBreaker breaker = halfOpenDue(UpstreamGuards.BREAKER_SUCCESS_THRESHOLD);
            AtomicLong answered = new AtomicLong();

            runConcurrently(() -> {
                try {
                    breaker.call(() -> ANSWER);
                    answered.incrementAndGet();
                } catch (CircuitBreakerOpenException refusal) {
                    // a trial refused while the trials were all in flight
                } catch (Exception unexpected) {
                    throw new AssertionError("a successful call ended neither answered nor refused", unexpected);
                }
            });

            assertAll("8 threads x 500 successful calls on a half-open breaker",
                    () -> assertEquals(List.of(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN,
                            CircuitBreakerState.CLOSED), transitions, "the breaker closed exactly once"),
                    () -> assertTrue(answered.get() >= UpstreamGuards.BREAKER_SUCCESS_THRESHOLD,
                            "the successful trials were answered"),
                    () -> assertEquals(ANSWER, succeed(breaker), "the closed breaker admits the next call"));
        }

        /**
         * Runs {@code calls} {@value #CALLS_PER_THREAD} times on each of {@value #THREADS} threads released
         * together, and waits for all of them.
         */
        private void runConcurrently(Runnable calls) throws Exception {
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
                List<Future<?>> workers = new ArrayList<>(THREADS);
                for (int thread = 0; thread < THREADS; thread++) {
                    workers.add(pool.submit(() -> {
                        start.await();
                        for (int call = 0; call < CALLS_PER_THREAD; call++) {
                            calls.run();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> worker : workers) {
                    worker.get(30, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    @DisplayName("refuses a policy outside its bounds")
    void refusesInvalidPolicies() {
        Duration negativeOpenDelay = OPEN_DELAY.negated();
        assertAll("invalid policies",
                () -> assertThrows(IllegalArgumentException.class, () -> breaker(0, 0.5, 1),
                        "an empty window"),
                () -> assertThrows(IllegalArgumentException.class, () -> breaker(4, 0.0, 1),
                        "a failure ratio of zero"),
                () -> assertThrows(IllegalArgumentException.class, () -> breaker(4, 1.5, 1),
                        "a failure ratio above one"),
                () -> assertThrows(IllegalArgumentException.class, () -> breaker(4, Double.NaN, 1),
                        "a failure ratio that is no number"),
                () -> assertThrows(IllegalArgumentException.class, () -> breaker(4, 0.5, 0),
                        "no successful trial needed to close"),
                () -> assertThrows(IllegalArgumentException.class, () -> new UpstreamCircuitBreaker(4, 0.5,
                        negativeOpenDelay, 1, clock::get, name, transitions::add), "a negative open delay"));
    }

    /**
     * @param requestVolumeThreshold the window size
     * @param failureRatio           the failure ratio
     * @param successThreshold       the trials that close it
     * @return a breaker on the manual clock, reporting to {@link #transitions}
     */
    private UpstreamCircuitBreaker breaker(int requestVolumeThreshold, double failureRatio, int successThreshold) {
        return new UpstreamCircuitBreaker(requestVolumeThreshold, failureRatio, OPEN_DELAY, successThreshold,
                clock::get, name, transitions::add);
    }

    /**
     * @param successThreshold the trials that close it
     * @return a one-call, ratio-1 breaker opened by one failure, its open delay not yet passed
     */
    private UpstreamCircuitBreaker opened(int successThreshold) {
        UpstreamCircuitBreaker breaker = breaker(1, 1.0, successThreshold);
        failCall(breaker);
        assertEquals(List.of(CircuitBreakerState.OPEN), transitions, "the breaker opened on its one failure");
        return breaker;
    }

    /**
     * @param successThreshold the trials that close it
     * @return an open breaker whose open delay has passed, so its next call moves it to half-open
     */
    private UpstreamCircuitBreaker halfOpenDue(int successThreshold) {
        UpstreamCircuitBreaker breaker = opened(successThreshold);
        clock.addAndGet(OPEN_DELAY.toNanos());
        return breaker;
    }

    private static String succeed(UpstreamCircuitBreaker breaker) throws Exception {
        return breaker.call(() -> ANSWER);
    }

    private static void failCall(UpstreamCircuitBreaker breaker) {
        assertThrows(IllegalStateException.class, () -> breaker.call(() -> {
            throw new IllegalStateException(Generators.letterStrings(8, 16).next());
        }), "a failing call was admitted and failed");
    }

    private static void endNeutral(UpstreamCircuitBreaker breaker) {
        assertThrows(DispatchStage.ClientDeparted.class, () -> breaker.call(() -> {
            throw new DispatchStage.ClientDeparted(departureCause());
        }), "a client-ended call was admitted and ended as the client's");
    }

    private CircuitBreakerOpenException assertRefused(UpstreamCircuitBreaker breaker) {
        AtomicBoolean ran = new AtomicBoolean();
        CircuitBreakerOpenException refused = assertThrows(CircuitBreakerOpenException.class,
                () -> breaker.call(() -> {
                    ran.set(true);
                    return ANSWER;
                }), "the call is refused");
        assertAll("a refused call",
                () -> assertFalse(ran.get(), "the refused call never ran its action"),
                () -> assertTrue(refused.getMessage().contains(name), "the refusal names the breaker"));
        return refused;
    }

    private static Throwable departureCause() {
        return new IllegalStateException(Generators.letterStrings(8, 16).next());
    }

    /** An {@link Error} an action ends in. */
    private static final class ActionError extends Error {
        @Serial
        private static final long serialVersionUID = 1L;

        private ActionError(String message) {
            super(message);
        }
    }
}
