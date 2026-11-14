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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape;
import de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.events.GatewayException;
import io.smallrye.faulttolerance.api.Guard;
import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binds the breaker rules of the production guard to a test: every guard here is the one
 * {@link UpstreamGuards#guardFor} builds — its request-volume threshold, its failure ratio and its
 * {@code skipOn(GatewayException.class)} — driven with actions that fail at once, so no case waits on the
 * guard's timeout.
 * <p>
 * A dispatch the client ended reaches the guard as a {@link GatewayException}. The breaker skips it, so
 * twice its threshold of such calls leaves it closed and the call behind them reaches its action. The
 * matched control makes the same number of calls failing with an ordinary exception, through a guard of
 * another shape: the breaker counts them, opens at its threshold and refuses the call behind them, so the
 * threshold is reached by that many calls and the skip case is not vacuous.
 * <p>
 * {@code GrpcDispatchStageTest}, {@code DispatchStageTest} and {@code InboundBodyDepartureTest} run their
 * dispatches under test-local guards that repeat these rules with a smaller threshold; this test is the one
 * that fails when the production guard stops skipping a gateway rejection.
 */
@DisplayName("UpstreamGuards — the production breaker skips a gateway rejection and counts an upstream failure")
class UpstreamGuardsTest {

    /**
     * The request-volume threshold {@link UpstreamGuards#guardFor} gives its breaker: the breaker decides
     * whether to open only once it holds this many results.
     */
    private static final int PRODUCTION_REQUEST_VOLUME_THRESHOLD = 20;

    /** How many failing calls each case makes: twice the threshold, so the breaker has decided at least once. */
    private static final int FAILING_CALLS = 2 * PRODUCTION_REQUEST_VOLUME_THRESHOLD;

    /** The upstream port of the shape the skip cases build their guard for. */
    private static final int SKIP_CASE_PORT = 50051;

    /** The upstream port of the control's shape: another shape, so the control holds its own breaker. */
    private static final int CONTROL_PORT = 50052;

    private static final String ANSWER = "answered";

    private final GatewayEventCounter eventCounter = new GatewayEventCounter();
    private final UpstreamGuards upstreamGuards = new UpstreamGuards(new UpstreamFailureMapper(eventCounter));

    /**
     * The gateway rejections a dispatch the client ended reaches the guard as, each made fresh per call.
     *
     * @return one named supplier per rejection type
     */
    static Stream<Arguments> clientEndedDispatches() {
        Supplier<GatewayException> noCompleteMessage = DispatchStage.RequestMessageTimeout::new;
        Supplier<GatewayException> streamStillOpen = () -> new DispatchStage.RequestBodyDeadlineExceeded(true);
        Supplier<GatewayException> clientLeft =
                () -> new DispatchStage.ClientDeparted(new IllegalStateException("the client reset its stream"));
        return Stream.of(
                Arguments.of(Named.of("a gRPC call whose next complete message did not arrive within the message bound",
                        noCompleteMessage)),
                Arguments.of(Named.of("a gRPC call whose request stream was still open at its pre-response deadline",
                        streamStillOpen)),
                Arguments.of(Named.of("a dispatch whose client left before it was answered", clientLeft)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("clientEndedDispatches")
    @DisplayName("twice the threshold of gateway rejections leaves the breaker closed: the call behind them reaches its action")
    void gatewayRejectionsNeverOpenTheBreaker(Supplier<GatewayException> rejection) {
        Guard guard = upstreamGuards.guardFor(grpcShape(SKIP_CASE_PORT));
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

        assertAll("gateway rejections past the production breaker's threshold",
                () -> assertEquals(raised, surfaced,
                        "every rejected call surfaced its own gateway rejection, none a breaker refusal"),
                () -> assertEquals(1, behindRan.get(), "the call behind the rejections ran its action"),
                () -> assertEquals(ANSWER, answer, "the call behind the rejections returned its answer"),
                () -> assertEquals(0L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                        "the breaker never reported opening"));
    }

    @Test
    @DisplayName("control — as many upstream failures open the breaker at its threshold, which refuses the call behind them")
    void upstreamFailuresOpenTheBreaker() {
        Guard guard = upstreamGuards.guardFor(grpcShape(CONTROL_PORT));
        AtomicInteger failingRan = new AtomicInteger();
        AtomicInteger behindRan = new AtomicInteger();

        for (int call = 0; call < FAILING_CALLS; call++) {
            assertThrows(Exception.class, () -> guard.call(() -> {
                failingRan.incrementAndGet();
                throw new IllegalStateException("the upstream connection failed");
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
                () -> assertEquals(PRODUCTION_REQUEST_VOLUME_THRESHOLD, failingRan.get(),
                        "the breaker opened at its threshold and refused every failing call after it"),
                () -> assertEquals(1L, eventCounter.getCount(EventType.UPSTREAM_CIRCUIT_OPEN),
                        "the breaker reported opening once to the failure mapper"));
    }

    /**
     * @param port the upstream port
     * @return the resilience shape of a gRPC route without retry: a forced-HTTP/2 target on {@code port}
     */
    private static ResilienceShape grpcShape(int port) {
        return new ResilienceShape(new UpstreamTarget("http", "svc.internal", port, true), false);
    }
}
