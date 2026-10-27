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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.api.Nested;
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
 * running rounds until enough of them admitted, up to a cap, instead of a fixed number of rounds, and
 * fails when the cap is reached first. A round whose threads were not both running when it was
 * released counts as unsettled.
 * <p>
 * The held-answer sub-budget is pinned in {@link HeldAnswerSubBudget}: a hold swaps the general permit
 * for a held one, a full sub-budget changes nothing, and every permit and the in-flight count go back
 * exactly once.
 */
@DisplayName("EdgeAdmission — admission against the shutdown drain")
class EdgeAdmissionTest {

    /** The default admission budget, from the public constructor over the omitted-block defaults. */
    private static final EdgeHardeningOptions HARDENING = new EdgeHardeningOptions(EdgeHardeningConfig.defaults());

    /** An admission cap small enough to exhaust every pool; its held-answer sub-budget is one permit. */
    private static final int SMALL_CAP = 4;
    private static final EdgeHardeningOptions SMALL = new EdgeHardeningOptions(new EdgeHardeningConfig(SMALL_CAP, null));

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
                () -> assertTrue(admitted >= ADMITTED_ROUNDS_WANTED,
                        "control: %d rounds must admit within %d, or the race did not sample the case it pins"
                                .formatted(ADMITTED_ROUNDS_WANTED, MAX_RACE_ROUNDS)),
                () -> assertEquals(List.of(), admittedAfterTheDrain,
                        "an admission that succeeds must be one the drain waits for"),
                () -> assertEquals(List.of(), unsettled,
                        "every round's threads must both run before its release, "
                                + "and finish once the admitted request is released"));
    }

    /**
     * The held-answer sub-budget: an answer held back on a timer moves from the general pool to a
     * sub-budget of its own ({@link EdgeAdmission#tryEnterHold}), and every permit and the in-flight
     * count go back exactly once whichever of the hold and the end handler comes first. The admission
     * cap here is {@value #SMALL_CAP}, so the sub-budget is a single permit and every pool can be
     * exhausted and counted through the public API.
     */
    @Nested
    @DisplayName("the held-answer sub-budget")
    class HeldAnswerSubBudget {

        private final EdgeAdmission small = new EdgeAdmission(SMALL);

        @Test
        @DisplayName("entering a hold hands the general permit back and takes the one held permit")
        void holdEntryReturnsTheGeneralPermitAndTakesAHeldOne() {
            List<Admitted> admitted = admitAll(small, SMALL_CAP);
            boolean admittedWhileFull = small.tryAdmit();

            boolean held = small.tryEnterHold(admitted.getFirst().ctx());
            boolean admittedAfterTheHold = small.tryAdmit();
            boolean secondHeld = small.tryEnterHold(admitted.get(1).ctx());

            assertAll("a hold against a full general pool",
                    () -> assertEquals(1, SMALL.heldAnswerCap(), "precondition: a single held permit"),
                    () -> assertFalse(admittedWhileFull, "precondition: the general pool is exhausted"),
                    () -> assertTrue(held, "the first hold takes the held permit"),
                    () -> assertTrue(admittedAfterTheHold, "the held request's general permit is free again"),
                    () -> assertFalse(secondHeld, "the sub-budget's single permit is taken"));
        }

        @Test
        @DisplayName("a full sub-budget refuses the hold and returns no permit")
        void fullSubBudgetRefusesWithoutSideEffects() {
            List<Admitted> admitted = admitAll(small, SMALL_CAP);
            assertTrue(small.tryEnterHold(admitted.getFirst().ctx()), "precondition: the held permit is taken");
            assertTrue(small.tryAdmit(), "precondition: the returned general permit is taken again");

            boolean refused = small.tryEnterHold(admitted.get(1).ctx());
            boolean admittedAfterTheRefusal = small.tryAdmit();
            Admitted refusedRequest = admitted.get(1);
            small.releaseAdmission(refusedRequest.ctx(), refusedRequest.released());
            long admittedAfterItsRelease = countAdmissions(small, SMALL_CAP);

            assertAll("a refused hold",
                    () -> assertFalse(refused, "the full sub-budget refuses the hold"),
                    () -> assertFalse(admittedAfterTheRefusal, "the refused hold handed no general permit back"),
                    () -> assertEquals(1L, admittedAfterItsRelease,
                            "the refused request still holds exactly its one general permit, returned at its end"));
        }

        @Test
        @DisplayName("the end handler after a hold returns the held permit once and never a second general permit")
        void endAfterHoldReturnsEachPermitExactlyOnce() {
            Admitted first = admit(small);
            Admitted second = admit(small);
            assertTrue(small.tryEnterHold(first.ctx()), "precondition: the first request's answer is held");

            small.releaseAdmission(first.ctx(), first.released());
            small.releaseAdmission(first.ctx(), first.released());
            long generalPermitsFree = countAdmissions(small, SMALL_CAP);
            boolean heldPermitBack = small.tryEnterHold(second.ctx());

            assertAll("the release of a held request, run twice",
                    () -> assertTrue(first.released().get(), "the end handler takes the in-flight latch"),
                    () -> assertEquals(SMALL_CAP - 1L, generalPermitsFree,
                            "the second request holds one general permit; the held request's went back once"),
                    () -> assertTrue(heldPermitBack, "the held permit went back at the end of the held answer"));
        }

        @Test
        @DisplayName("a hold entered after the end handler ran gives its held permit straight back")
        void holdEnteredAfterTheEndLeavesNothingBehind() {
            Admitted late = admit(small);
            Admitted other = admit(small);
            small.releaseAdmission(late.ctx(), late.released());

            boolean lateHold = small.tryEnterHold(late.ctx());
            long generalPermitsFree = countAdmissions(small, SMALL_CAP);
            boolean heldPermitFree = small.tryEnterHold(other.ctx());

            assertAll("a hold entered too late",
                    () -> assertTrue(lateHold, "the held permit is taken"),
                    () -> assertEquals(SMALL_CAP - 1L, generalPermitsFree,
                            "the late hold returned no general permit the end handler had already returned"),
                    () -> assertTrue(heldPermitFree, "the late hold gave its held permit straight back"));
        }

        @Test
        @DisplayName("the drain waits for a held answer, and a repeated release decrements the in-flight count once")
        void drainWaitsForAHeldAnswer() throws Exception {
            Admitted held = admit(small);
            Admitted other = admit(small);
            assertTrue(small.tryEnterHold(held.ctx()), "precondition: the answer is held");
            Thread drain = Thread.ofPlatform().daemon().start(small::drain);

            boolean returnedWhileHeld = drain.join(HELD);
            small.releaseAdmission(held.ctx(), held.released());
            small.releaseAdmission(held.ctx(), held.released());
            boolean returnedWithOtherInFlight = drain.join(HELD);
            small.releaseAdmission(other.ctx(), other.released());
            boolean returnedAfterBoth = drain.join(PROMPT);

            assertAll("a drain over a held answer",
                    () -> assertFalse(returnedWhileHeld, "a held answer is still in flight, its permit swap aside"),
                    () -> assertFalse(returnedWithOtherInFlight,
                            "the repeated release decremented the in-flight count once, so the other request is waited for"),
                    () -> assertTrue(returnedAfterBoth, "the drain returns once both requests are released"));
        }

        @Test
        @DisplayName("a request whose release guards were never armed cannot enter a hold")
        void unarmedRequestCannotEnterAHold() {
            assertTrue(small.tryAdmit(), "precondition: admitted, but without armed release guards");
            RoutingContext unarmed = routingContext();

            assertThrows(IllegalStateException.class, () -> small.tryEnterHold(unarmed));
        }
    }

    /** Admits one request and arms its release guards, as the edge root does. */
    private static Admitted admit(EdgeAdmission admission) {
        assertTrue(admission.tryAdmit(), "precondition: the request is admitted");
        RoutingContext ctx = routingContext();
        return new Admitted(ctx, admission.armReleaseGuards(ctx));
    }

    private static List<Admitted> admitAll(EdgeAdmission admission, int count) {
        List<Admitted> admitted = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            admitted.add(admit(admission));
        }
        return admitted;
    }

    /** Tries {@code attempts} admissions and returns how many succeeded — the general permits that were free. */
    private static long countAdmissions(EdgeAdmission admission, int attempts) {
        return IntStream.range(0, attempts).filter(_ -> admission.tryAdmit()).count();
    }

    /**
     * An admitted request and its in-flight latch.
     *
     * @param ctx      the request's context, carrying its armed release guards
     * @param released the in-flight latch {@link EdgeAdmission#armReleaseGuards} returned
     */
    private record Admitted(RoutingContext ctx, AtomicBoolean released) {
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
        boolean bothRunning = release(running, go);
        boolean admitSettled = admit.join(PROMPT);
        boolean drainSettled = drain.join(PROMPT);

        return new RaceOutcome(admitted.get(), admittedAfterTheDrainReturned.get(),
                bothRunning && admitSettled && drainSettled);
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
     * starts, so no thread spins forever.
     *
     * @return whether both threads were running at the release; a round released without them did
     *         not race and is reported unsettled
     */
    private static boolean release(AtomicInteger running, AtomicBoolean go) {
        long deadline = System.nanoTime() + PROMPT.toNanos();
        while (running.get() < 2 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        boolean bothRunning = running.get() == 2;
        go.set(true);
        return bothRunning;
    }

    /**
     * A {@link RoutingContext} backed by a map for {@code get} and {@code put}, the only calls the
     * admission makes on it; every other call fails the test.
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
     * @param settled                       whether both threads were running at the release and finished
     *                                      within {@link #PROMPT}
     */
    private record RaceOutcome(boolean admitted, boolean admittedAfterTheDrainReturned, boolean settled) {
    }
}
