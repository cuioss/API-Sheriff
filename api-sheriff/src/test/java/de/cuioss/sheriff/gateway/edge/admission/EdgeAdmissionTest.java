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
package de.cuioss.sheriff.gateway.edge.admission;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Admission into the edge against its shutdown drain: once the drain has returned, no request is
 * admitted any more, a refused request leaves nothing behind for a later drain to wait for, and a
 * drain waits for every request admitted before it.
 * <p>
 * The race case is the regression pin for the order of the accesses in {@link EdgeAdmission#tryAdmit()}:
 * the admission publishes its claim before it reads the draining flag, and the drain sets the flag
 * before it reads the in-flight count. With that order an admission that succeeds is always one the
 * drain waits for, so it is never admitted after the drain has returned. With the flag read first,
 * an admission can pass the check, the drain can read zero in flight and return, and the admission
 * then completes after the drain has finished. The race also requires at least one admitted round: a
 * race in which no round admits observes nothing about that order and must not pass.
 * <p>
 * Both threads of a round are released from a spin barrier, so both are already running when the
 * round starts. A latch wakes its waiters one at a time, and on a loaded runner the drain's single
 * flag write then won every round, which left the control with nothing admitted. The race also keeps
 * running rounds until enough of them admitted, up to a cap, instead of a fixed number of rounds.
 */
@DisplayName("EdgeAdmission — admission against the shutdown drain")
class EdgeAdmissionTest {

    /** The default admission budget, from the public constructor over the omitted-block defaults. */
    private static final EdgeHardeningOptions HARDENING = new EdgeHardeningOptions(EdgeHardeningConfig.defaults());

    /** Far inside the fixed 25 s drain window, and far above the drain's 50 ms poll interval. */
    private static final Duration PROMPT = Duration.ofSeconds(5);

    /** How long the waiting case watches the drain hold on: several drain polls. */
    private static final Duration HELD = Duration.ofMillis(300);

    private static final int REFUSED_ATTEMPTS = 1_000;

    /** The race runs until this many rounds admitted; an admitted round costs the drain at most one 50 ms poll. */
    private static final int ADMITTED_ROUNDS_WANTED = 50;

    /** The cap on the race's rounds. A round that admits nothing costs microseconds. */
    private static final int MAX_RACE_ROUNDS = 5_000;

    private final EdgeAdmission admission = new EdgeAdmission(HARDENING);

    @Test
    @DisplayName("refuses an admission once the drain has returned")
    void refusesAnAdmissionOnceTheDrainReturned() {
        boolean admittedBeforeTheDrain = admission.tryAdmit();
        admission.releaseAdmission(routingContext(), new AtomicBoolean());

        assertTimeoutPreemptively(PROMPT, admission::drain, "a drain with nothing in flight returns at once");
        boolean admittedAfterTheDrain = admission.tryAdmit();

        assertAll("an admission before and after the drain",
                () -> assertTrue(admittedBeforeTheDrain, "control: a request before the drain is admitted"),
                () -> assertFalse(admittedAfterTheDrain, "a request after the drain returned must be refused"));
    }

    @Test
    @DisplayName("a refused admission during the drain leaves no in-flight count behind")
    void refusedAdmissionLeavesNoInFlightCountBehind() {
        assertTimeoutPreemptively(PROMPT, admission::drain, "a drain with nothing in flight returns at once");

        long admittedWhileDraining = IntStream.range(0, REFUSED_ATTEMPTS).filter(_ -> admission.tryAdmit()).count();

        assertAll("refused admissions while draining",
                () -> assertEquals(0L, admittedWhileDraining, "every admission while draining is refused"),
                () -> assertTimeoutPreemptively(PROMPT, admission::drain,
                        "a second drain returns far inside its 25 s window: no refusal left a count to wait for"));
    }

    @Test
    @DisplayName("the drain waits while an admitted request is unreleased, and returns soon after its release")
    void drainWaitsForAnAdmittedRequestUntilItIsReleased() throws Exception {
        assertTrue(admission.tryAdmit(), "precondition: the request is admitted before the drain starts");
        Thread drain = Thread.ofPlatform().daemon().start(admission::drain);

        boolean returnedWhileHeld = drain.join(HELD);
        AtomicBoolean released = new AtomicBoolean();
        admission.releaseAdmission(routingContext(), released);
        boolean returnedAfterTheRelease = drain.join(PROMPT);

        assertAll("a drain over one admitted request",
                () -> assertFalse(returnedWhileHeld, "the drain must wait for the admitted request"),
                () -> assertTrue(released.get(), "the release takes the request's latch"),
                () -> assertTrue(returnedAfterTheRelease,
                        "the drain returns soon after the release, far inside its 25 s window"));
    }

    @Test
    @DisplayName("an admission racing the drain is never admitted after the drain has returned")
    void admissionRacingTheDrainIsNeverAdmittedAfterTheDrainReturned() throws Exception {
        int admittedRounds = 0;
        int rounds = 0;
        List<Integer> admittedAfterTheDrain = new ArrayList<>();
        List<Integer> unsettled = new ArrayList<>();

        for (int round = 0; round < MAX_RACE_ROUNDS && admittedRounds < ADMITTED_ROUNDS_WANTED; round++) {
            rounds++;
            RaceOutcome outcome = race();
            if (outcome.admitted()) {
                admittedRounds++;
            }
            if (outcome.admittedAfterTheDrainReturned()) {
                admittedAfterTheDrain.add(round);
            }
            if (!outcome.settled()) {
                unsettled.add(round);
            }
        }

        int admitted = admittedRounds;
        assertAll("%d rounds of tryAdmit() against drain(), %d of them admitted".formatted(rounds, admitted),
                () -> assertTrue(admitted > 0,
                        "control: at least one round must admit, or the race never ran the case it pins"),
                () -> assertEquals(List.of(), admittedAfterTheDrain,
                        "an admission that succeeds must be one the drain waits for"),
                () -> assertEquals(List.of(), unsettled,
                        "every round's admission and drain must finish once the admitted request is released"));
    }

    /**
     * One round: a fresh admission, a drain and an admission released together from a spin barrier.
     * The admitting thread reads whether the drain had returned right after its admission succeeded,
     * and only then releases the request, so a drain still waiting for it cannot have returned yet.
     */
    private static RaceOutcome race() throws InterruptedException {
        EdgeAdmission racing = new EdgeAdmission(HARDENING);
        AtomicInteger running = new AtomicInteger();
        AtomicBoolean go = new AtomicBoolean();
        AtomicBoolean drainReturned = new AtomicBoolean();
        AtomicBoolean admitted = new AtomicBoolean();
        AtomicBoolean admittedAfterTheDrainReturned = new AtomicBoolean();

        Thread drain = Thread.ofPlatform().daemon().start(() -> {
            awaitRelease(running, go);
            racing.drain();
            drainReturned.set(true);
        });
        Thread admit = Thread.ofPlatform().daemon().start(() -> {
            awaitRelease(running, go);
            if (racing.tryAdmit()) {
                admitted.set(true);
                admittedAfterTheDrainReturned.set(drainReturned.get());
                racing.releaseAdmission(routingContext(), new AtomicBoolean());
            }
        });
        release(running, go);
        boolean admitSettled = admit.join(PROMPT);
        boolean drainSettled = drain.join(PROMPT);

        return new RaceOutcome(admitted.get(), admittedAfterTheDrainReturned.get(), admitSettled && drainSettled);
    }

    /** Announces the thread as running, then spins until the round is released. */
    private static void awaitRelease(AtomicInteger running, AtomicBoolean go) {
        running.incrementAndGet();
        while (!go.get()) {
            Thread.onSpinWait();
        }
    }

    /**
     * Releases the round once both threads are running, or after {@link #PROMPT} when one never
     * starts; the round then settles or reports itself unsettled.
     */
    private static void release(AtomicInteger running, AtomicBoolean go) {
        long deadline = System.nanoTime() + PROMPT.toNanos();
        while (running.get() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        go.set(true);
    }

    /**
     * A {@link RoutingContext} backed by a map for {@code get} and {@code put}, the only calls
     * {@link EdgeAdmission#releaseAdmission} makes on it; every other call fails the test.
     */
    private static RoutingContext routingContext() {
        Map<String, Object> data = new HashMap<>();
        return (RoutingContext) Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "get" -> data.get(args[0]);
                    case "put" -> {
                        data.put((String) args[0], args[1]);
                        yield proxy;
                    }
                    default -> throw new AssertionError("the admission release must not call " + method.getName());
                });
    }

    /**
     * What one race round observed.
     *
     * @param admitted                      whether the racing admission succeeded
     * @param admittedAfterTheDrainReturned whether it succeeded after the drain had already returned
     * @param settled                       whether both threads finished within {@link #PROMPT}
     */
    private record RaceOutcome(boolean admitted, boolean admittedAfterTheDrainReturned, boolean settled) {
    }
}
