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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.testsupport.Awaits;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.smallrye.faulttolerance.api.Guard;
import jakarta.enterprise.util.TypeLiteral;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binds the breaker rules of the production guard to a test: every guard here is the one
 * {@link UpstreamGuards#guardFor} builds — the gateway's {@link UpstreamCircuitBreaker} with its fixed
 * window, failure ratio, reset window and success threshold — driven with actions that fail at once, so no
 * case waits on the guard's timeout.
 * <p>
 * A dispatch the client ended, or one refused because the wait queue for an upstream connection was full,
 * reaches the guard as a {@link GatewayException}. The breaker records that outcome as neutral: neither a
 * failure nor a success. Each case pins one consequence through a matched control of another shape:
 * <ul>
 *   <li>twice the window of such calls leaves the breaker closed, where as many upstream failures open it at
 *       its window;</li>
 *   <li>such calls interleaved with upstream failures do not dilute the window: the breaker opens at the
 *       same failure count as the failures alone — a breaker that recorded them as successes would hold at
 *       most a quarter of failures in its window and never open;</li>
 *   <li>such calls admitted as half-open trials do not close the breaker, so a failure after them reopens
 *       it, where two answered trials close it — a breaker that recorded them as successes would close on
 *       them.</li>
 * </ul>
 * <p>
 * {@code GrpcDispatchStageTest}, {@code DispatchStageTest} and {@code InboundBodyDepartureTest} run their
 * dispatches under test-local guards with a smaller window; those show only that a gateway rejection is not
 * counted as a failure. This test is the one that fails when the production guard records a gateway
 * rejection as anything but neutral.
 */
@EnableGeneratorController
@DisplayName("UpstreamGuards — the production breaker records a gateway rejection as neutral and counts an upstream failure")
class UpstreamGuardsTest {

    /**
     * The window {@link UpstreamGuards#guardFor} gives its breaker: the breaker judges its failure ratio over
     * this many calls whose outcome the upstream decided, and opens once half of them failed. Stated here
     * as the contract the cases pin, not read off the production constant.
     */
    private static final int PRODUCTION_WINDOW = 20;

    /** How many failing calls the window cases make: twice the window, so the breaker has decided at least once. */
    private static final int FAILING_CALLS = 2 * PRODUCTION_WINDOW;

    /** The upstream port of the shape the neutral cases build their guard for. */
    private static final int NEUTRAL_CASE_PORT = 50051;

    /** The upstream port of the controls' shape: another shape, so each control holds its own breaker. */
    private static final int CONTROL_PORT = 50052;

    /**
     * How long a half-open case polls for the breaker to admit its first trial: the reset window has to pass
     * first, so the ceiling sits well above {@link UpstreamGuards#BREAKER_RESET_SECONDS}.
     */
    private static final long TRIAL_ADMISSION_CEILING_SECONDS = Awaits.ADMISSION_RELEASE_CEILING_SECONDS;

    private static final String ANSWER = "answered";

    private final GatewayEventCounter eventCounter = new GatewayEventCounter();
    private final UpstreamFailureMapper upstreamFailureMapper = new UpstreamFailureMapper(eventCounter);
    private final UpstreamGuards upstreamGuards = new UpstreamGuards(upstreamFailureMapper);

    /**
     * The gateway rejections a dispatch the client ended reaches the guard as, each made fresh per call.
     *
     * @return one named supplier per rejection type
     */
    static Stream<Arguments> clientEndedDispatches() {
        Supplier<GatewayException> noCompleteMessage = DispatchStage.RequestMessageTimeout::new;
        Supplier<GatewayException> streamStillOpen = () -> new DispatchStage.RequestBodyDeadlineExceeded(true);
        Supplier<GatewayException> clientLeft = () -> new DispatchStage.ClientDeparted(failureCause());
        return Stream.of(
                Arguments.of(Named.of("a gRPC call whose next complete message did not arrive within the message bound",
                        noCompleteMessage)),
                Arguments.of(Named.of("a gRPC call whose request stream was still open at its pre-response deadline",
                        streamStillOpen)),
                Arguments.of(Named.of("a dispatch whose client left before it was answered", clientLeft)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientEndedDispatches")
    @DisplayName("twice the window of gateway rejections leaves the breaker closed: the call behind them reaches its action")
    void gatewayRejectionsNeverOpenTheBreaker(Supplier<GatewayException> rejection) {
        Guard guard = upstreamGuards.guardFor(grpcShape(NEUTRAL_CASE_PORT));
        List<GatewayException> raised = new ArrayList<>();
        List<Exception> surfaced = new ArrayList<>();
        AtomicInteger behindRan = new AtomicInteger();

        for (int call = 0; call < FAILING_CALLS; call++) {
            GatewayException thrown = rejection.get();
            raised.add(thrown);
            surfaced.add(assertThrows(Exception.class, () -> guard.call(() -> {
                throw thrown;
            }, String.class), "a rejected call is not answered"));
        }
        String answer = assertDoesNotThrow(() -> guard.call(() -> {
            behindRan.incrementAndGet();
            return ANSWER;
        }, String.class), "the call behind the rejections is not refused by an open breaker");

        assertAll("gateway rejections past the production breaker's window",
                () -> assertEquals(raised, surfaced,
                        "every rejected call surfaced its own gateway rejection, none a breaker refusal"),
                () -> assertEquals(1, behindRan.get(), "the call behind the rejections ran its action"),
                () -> assertEquals(ANSWER, answer, "the call behind the rejections returned its answer"),
                () -> assertEquals(0L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                        "the breaker never reported opening"));
    }

    @Test
    @DisplayName("control — as many upstream failures open the breaker at its window, which refuses the call behind them")
    void upstreamFailuresOpenTheBreaker() {
        Guard guard = upstreamGuards.guardFor(grpcShape(CONTROL_PORT));
        AtomicInteger failingRan = new AtomicInteger();
        AtomicInteger behindRan = new AtomicInteger();

        for (int call = 0; call < FAILING_CALLS; call++) {
            assertThrows(Exception.class, () -> guard.call(() -> {
                failingRan.incrementAndGet();
                throw upstreamFailure();
            }, String.class), "a failed upstream call is not answered");
        }
        Exception refused = assertThrows(Exception.class, () -> guard.call(() -> {
            behindRan.incrementAndGet();
            return ANSWER;
        }, String.class), "the call behind the failures is not answered");

        assertAll("upstream failures through the production breaker",
                () -> assertInstanceOf(CircuitBreakerOpenException.class, refused,
                        "the open breaker refuses the call behind the failures"),
                () -> assertEquals(0, behindRan.get(), "the refused call never ran its action"),
                () -> assertEquals(PRODUCTION_WINDOW, failingRan.get(),
                        "the breaker opened at its window and refused every failing call after it"),
                () -> assertEquals(1L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                        "the breaker reported opening once to the failure mapper"));
    }

    @Test
    @DisplayName("gateway rejections interleaved with upstream failures do not dilute the window: it opens at the same failure count")
    void interleavedGatewayRejectionsDoNotDiluteTheWindow() {
        Guard guard = upstreamGuards.guardFor(grpcShape(NEUTRAL_CASE_PORT));
        List<Supplier<GatewayException>> rejections = rejectionsRaisedInsideTheGuard();
        AtomicInteger rejectionsRan = new AtomicInteger();
        AtomicInteger failingRan = new AtomicInteger();
        AtomicInteger behindRan = new AtomicInteger();
        List<Exception> surfacedRejections = new ArrayList<>();
        List<GatewayException> raisedRejections = new ArrayList<>();

        for (int failure = 0; failure < PRODUCTION_WINDOW; failure++) {
            for (Supplier<GatewayException> rejection : rejections) {
                GatewayException thrown = rejection.get();
                raisedRejections.add(thrown);
                surfacedRejections.add(assertThrows(Exception.class, () -> guard.call(() -> {
                    rejectionsRan.incrementAndGet();
                    throw thrown;
                }, String.class), "a rejected call is not answered"));
            }
            assertThrows(Exception.class, () -> guard.call(() -> {
                failingRan.incrementAndGet();
                throw upstreamFailure();
            }, String.class), "a failed upstream call is not answered");
        }
        Exception refused = assertThrows(Exception.class, () -> guard.call(() -> {
            behindRan.incrementAndGet();
            return ANSWER;
        }, String.class), "the call behind the failures is not answered");

        assertAll("a window's worth of upstream failures, each preceded by the gateway rejections a dispatch raises",
                () -> assertEquals(raisedRejections, surfacedRejections,
                        "every rejected call ran and surfaced its own gateway rejection: the breaker stayed closed throughout"),
                () -> assertEquals(PRODUCTION_WINDOW * rejections.size(), rejectionsRan.get(),
                        "every rejected call ran its action"),
                () -> assertEquals(PRODUCTION_WINDOW, failingRan.get(),
                        "every upstream failure ran its action"),
                () -> assertInstanceOf(CircuitBreakerOpenException.class, refused,
                        "the breaker opened at the last failure and refuses the call behind it"),
                () -> assertEquals(0, behindRan.get(), "the refused call never ran its action"),
                () -> assertEquals(1L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                        "the breaker reported opening once to the failure mapper"));
    }

    @Nested
    @DisplayName("half-open")
    class HalfOpen {

        @Test
        @DisplayName("two client-ended trials do not close the breaker: the failure after them reopens it")
        void clientEndedTrialsDoNotCloseTheBreaker() throws Exception {
            Guard guard = openedGuard(NEUTRAL_CASE_PORT);
            Supplier<GatewayException> clientLeft = () -> new DispatchStage.ClientDeparted(failureCause());
            AtomicInteger failingRan = new AtomicInteger();
            AtomicInteger behindRan = new AtomicInteger();

            Awaits.until(() -> admitted(guard, clientLeft),
                    "the breaker admitting a first client-ended trial once its reset window passed",
                    TRIAL_ADMISSION_CEILING_SECONDS);
            boolean secondTrialAdmitted = admitted(guard, clientLeft);
            assertThrows(IllegalStateException.class, () -> guard.call(() -> {
                failingRan.incrementAndGet();
                throw upstreamFailure();
            }, String.class), "the failing trial is admitted and fails");
            Exception refused = assertThrows(Exception.class, () -> guard.call(() -> {
                behindRan.incrementAndGet();
                return ANSWER;
            }, String.class), "the call behind the failing trial is not answered");

            assertAll("two client-ended trials, then a failing trial",
                    () -> assertTrue(secondTrialAdmitted, "the second client-ended trial was admitted and ran"),
                    () -> assertEquals(1, failingRan.get(),
                            "the failing call was admitted as a trial: the client-ended trials gave their permits back"),
                    () -> assertEquals(2L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                            "the failing trial reopened the breaker: the client-ended trials had not closed it"),
                    () -> assertInstanceOf(CircuitBreakerOpenException.class, refused,
                            "the reopened breaker refuses the call behind the failing trial"),
                    () -> assertEquals(0, behindRan.get(), "the refused call never ran its action"));
        }

        @Test
        @DisplayName("control — two answered trials close the breaker: the failure after them lands in a fresh window")
        void answeredTrialsCloseTheBreaker() throws Exception {
            Guard guard = openedGuard(CONTROL_PORT);
            AtomicInteger failingRan = new AtomicInteger();
            AtomicInteger behindRan = new AtomicInteger();

            Awaits.until(() -> answered(guard),
                    "the breaker admitting a first answered trial once its reset window passed",
                    TRIAL_ADMISSION_CEILING_SECONDS);
            boolean secondTrialAnswered = answered(guard);
            assertThrows(IllegalStateException.class, () -> guard.call(() -> {
                failingRan.incrementAndGet();
                throw upstreamFailure();
            }, String.class), "the failing call is admitted and fails");
            String answer = assertDoesNotThrow(() -> guard.call(() -> {
                behindRan.incrementAndGet();
                return ANSWER;
            }, String.class), "the call behind the failure is not refused");

            assertAll("two answered trials, then a failing call",
                    () -> assertTrue(secondTrialAnswered, "the second answered trial was admitted and answered"),
                    () -> assertEquals(1, failingRan.get(), "the failing call was admitted"),
                    () -> assertEquals(1L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                            "the breaker never reopened: the two answered trials closed it"),
                    () -> assertEquals(1, behindRan.get(), "the call behind the failure ran its action"),
                    () -> assertEquals(ANSWER, answer, "the call behind the failure returned its answer"));
        }

        /**
         * @param port the upstream port of the shape
         * @return a production guard whose breaker a window of upstream failures has just opened
         */
        private Guard openedGuard(int port) {
            Guard guard = upstreamGuards.guardFor(grpcShape(port));
            for (int call = 0; call < PRODUCTION_WINDOW; call++) {
                assertThrows(IllegalStateException.class, () -> guard.call(() -> {
                    throw upstreamFailure();
                }, String.class), "a failed upstream call is not answered");
            }
            assertEquals(1L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                    "a window of upstream failures opened the breaker");
            return guard;
        }

        /**
         * Offers the guard one call the client ends.
         *
         * @return {@code true} when the breaker admitted the call and it ended as the client's,
         *         {@code false} when the breaker refused it unrun
         */
        private boolean admitted(Guard guard, Supplier<GatewayException> rejection) throws Exception {
            AtomicBoolean ran = new AtomicBoolean();
            GatewayException thrown = rejection.get();
            try {
                guard.call(() -> {
                    ran.set(true);
                    throw thrown;
                }, String.class);
            } catch (CircuitBreakerOpenException _) {
                assertFalse(ran.get(), "a refused call never runs its action");
                return false;
            } catch (GatewayException ended) {
                assertSame(thrown, ended, "an admitted call surfaces its own gateway rejection");
                return ran.get();
            }
            return fail("a client-ended call returned an answer");
        }

        /**
         * Offers the guard one call the upstream answers.
         *
         * @return {@code true} when the breaker admitted and the call was answered, {@code false} when the
         *         breaker refused it unrun
         */
        private boolean answered(Guard guard) throws Exception {
            AtomicBoolean ran = new AtomicBoolean();
            try {
                String answer = guard.call(() -> {
                    ran.set(true);
                    return ANSWER;
                }, String.class);
                return ran.get() && ANSWER.equals(answer);
            } catch (CircuitBreakerOpenException _) {
                assertFalse(ran.get(), "a refused call never runs its action");
                return false;
            }
        }
    }

    @Test
    @DisplayName("a retry-enabled route's guard runs an answered call once and returns its answer")
    void retryEnabledGuardAnswers() throws Exception {
        Guard guard = upstreamGuards.guardFor(
                new ResilienceShape(new UpstreamTarget("http", "svc.internal", CONTROL_PORT, false), true));
        AtomicInteger ran = new AtomicInteger();

        String answer = guard.call(() -> {
            ran.incrementAndGet();
            return ANSWER;
        }, String.class);

        assertAll("an answered call through the retry, breaker and timeout",
                () -> assertEquals(ANSWER, answer, "the answer is returned unchanged"),
                () -> assertEquals(1, ran.get(), "the action ran once"));
    }

    @Test
    @DisplayName("refuses every entry point but the synchronous call(Callable, Class), and an asynchronous result type")
    void refusesUnsupportedEntryPoints() {
        Guard guard = upstreamGuards.guardFor(grpcShape(NEUTRAL_CASE_PORT));
        AtomicInteger ran = new AtomicInteger();
        Callable<String> callable = () -> {
            ran.incrementAndGet();
            return ANSWER;
        };
        Supplier<String> supplier = () -> {
            ran.incrementAndGet();
            return ANSWER;
        };
        TypeLiteral<String> stringType = new StringType();
        Callable<AnswerStage> asynchronous = () -> {
            ran.incrementAndGet();
            return null;
        };

        assertAll("the refused entry points",
                () -> assertThrows(UnsupportedOperationException.class, () -> guard.call(callable, stringType),
                        "call(Callable, TypeLiteral)"),
                () -> assertThrows(UnsupportedOperationException.class, () -> guard.get(supplier, String.class),
                        "get(Supplier, Class)"),
                () -> assertThrows(UnsupportedOperationException.class, () -> guard.get(supplier, stringType),
                        "get(Supplier, TypeLiteral)"),
                () -> assertThrows(UnsupportedOperationException.class,
                        () -> guard.call(asynchronous, AnswerStage.class),
                        "call(Callable, Class) with an asynchronous result type"),
                () -> assertEquals(0, ran.get(), "no refused entry point ran its action"));
    }

    /**
     * The gateway rejections a dispatch raises inside the guard, besides a gRPC request bound: a client that
     * left with nothing left to send, a client that left while its body was still arriving, and a full wait
     * queue for an upstream connection. Each is made fresh per call.
     *
     * @return the rejections, in the order the interleaved case raises them before each upstream failure
     */
    private List<Supplier<GatewayException>> rejectionsRaisedInsideTheGuard() {
        return List.of(
                () -> new DispatchStage.ClientDeparted(failureCause()),
                () -> new GatewayException(EventType.INBOUND_BODY_ABORTED,
                        DispatchStage.ByteCappedBodyStream.INBOUND_BODY_FAILED, failureCause()),
                () -> upstreamFailureMapper.toPoolSaturation(failureCause()));
    }

    private static IllegalStateException upstreamFailure() {
        return new IllegalStateException("the upstream connection failed: " + Generators.letterStrings(8, 16).next());
    }

    private static Throwable failureCause() {
        return new IllegalStateException(Generators.letterStrings(8, 16).next());
    }

    /**
     * @param port the upstream port
     * @return the resilience shape of a gRPC route without retry: a forced-HTTP/2 target on {@code port}
     */
    private static ResilienceShape grpcShape(int port) {
        return new ResilienceShape(new UpstreamTarget("http", "svc.internal", port, true), false);
    }

    /** An asynchronous result type: the guard refuses it before running anything. */
    private interface AnswerStage extends CompletionStage<String> {
    }

    /** The {@link TypeLiteral} of the answer, for the {@code TypeLiteral} entry points. */
    private static final class StringType extends TypeLiteral<String> {
        @Serial
        private static final long serialVersionUID = 1L;
    }
}
