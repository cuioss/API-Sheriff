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
package de.cuioss.sheriff.gateway.edge.end;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;


import de.cuioss.sheriff.gateway.edge.end.EarlyResponseEnd.Outcome;
import de.cuioss.sheriff.gateway.edge.end.EarlyResponseEnd.Quiescence;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule {@link EarlyResponseEnd} waits by, driven on synthetic times, windows and PING
 * acknowledgements: the end of an early-answered HTTP/2 response goes ahead once the client can send
 * nothing more, once the PING sent after its window had not moved for
 * {@value EarlyResponseEnd#QUIET_MILLIS} ms is acknowledged with the window still unmoved, or after
 * {@value EarlyResponseEnd#MAX_WAIT_MILLIS} ms — whichever comes first — and not before. An unmoved
 * window alone only asks for the PING; it never ends the wait, a window seen to move while the PING is
 * outstanding — even one back at its PING-time value by the acknowledgement — restarts the quiet period,
 * and a PING that fails or is refused is followed by a new one once the window has stood still for the
 * quiet period again.
 */
@EnableGeneratorController
@DisplayName("EarlyResponseEnd — when the end of an early-answered HTTP/2 response may go ahead")
class EarlyResponseEndTest {

    private static final long START = 1_000_000_000L;
    private static final long POLL = TimeUnit.MILLISECONDS.toNanos(EarlyResponseEnd.POLL_MILLIS);
    private static final long QUIET = TimeUnit.MILLISECONDS.toNanos(EarlyResponseEnd.QUIET_MILLIS);
    private static final long CAP = TimeUnit.MILLISECONDS.toNanos(EarlyResponseEnd.MAX_WAIT_MILLIS);

    private final int window = Generators.integers(1_024, 65_535).next();

    @Test
    @DisplayName("a client that can send nothing more ends the wait at once")
    void nothingSendableEndsTheWaitAtOnce() {
        assertAll("nothing sendable",
                () -> assertEquals(Outcome.NOTHING_SENDABLE, new Quiescence(START).next(START, true, 0),
                        "a used-up window"),
                () -> assertEquals(Outcome.NOTHING_SENDABLE, new Quiescence(START).next(START, false, window),
                        "a stream the client ended or lost"));
    }

    @Test
    @DisplayName("an open window that has stood still for the quiet period asks for a PING, and does not end the wait")
    void openWindowAsksForAPingAfterTheQuietPeriod() {
        Quiescence quiescence = new Quiescence(START);

        Outcome first = quiescence.next(START, true, window);
        Outcome justBefore = quiescence.next(START + QUIET - 1, true, window);
        Outcome atQuiet = quiescence.next(START + QUIET, true, window);

        assertAll("an unmoved open window",
                () -> assertEquals(Outcome.WAIT, first, "the first look never ends the wait"),
                () -> assertEquals(Outcome.WAIT, justBefore),
                () -> assertEquals(Outcome.PING, atQuiet,
                        "the quiet period only triggers the confirming PING; it is not an exit"));
    }

    @Test
    @DisplayName("a PING acknowledged with the window unmoved since it was sent ends the wait as quiet")
    void acknowledgementOnAnUnmovedWindowIsQuiet() {
        Quiescence quiescence = new Quiescence(START);
        quiescence.next(START, true, window);
        quiescence.next(START + QUIET, true, window);

        Outcome lookWhileOutstanding = quiescence.next(START + QUIET + POLL, true, window);
        Outcome acknowledged = quiescence.acknowledged(START + QUIET + 2 * POLL, true, window);

        assertAll("an acknowledgement on an unmoved window",
                () -> assertEquals(Outcome.WAIT, lookWhileOutstanding,
                        "a look while the PING is outstanding never ends the wait as quiet"),
                () -> assertEquals(Outcome.QUIET, acknowledged));
    }

    @Test
    @DisplayName("a window that moves between the PING and its acknowledgement restarts the quiet period")
    void windowMovedBeforeTheAcknowledgementRestartsTheQuietPeriod() {
        Quiescence quiescence = new Quiescence(START);
        quiescence.next(START, true, window);
        quiescence.next(START + QUIET, true, window);
        long movedAt = START + QUIET + POLL;

        Outcome lookAtTheMove = quiescence.next(movedAt, true, window - 1);
        Outcome acknowledged = quiescence.acknowledged(movedAt + POLL, true, window - 1);
        Outcome justBeforeTheNewQuietPeriod = quiescence.next(movedAt + QUIET - 1, true, window - 1);
        Outcome atTheNewQuietPeriod = quiescence.next(movedAt + QUIET, true, window - 1);

        assertAll("a window moving while the PING is outstanding",
                () -> assertEquals(Outcome.WAIT, lookAtTheMove),
                () -> assertEquals(Outcome.WAIT, acknowledged, "the acknowledgement of a moved window ends nothing"),
                () -> assertEquals(Outcome.WAIT, justBeforeTheNewQuietPeriod,
                        "the quiet period counts from the move, not from the PING"),
                () -> assertEquals(Outcome.PING, atTheNewQuietPeriod,
                        "a new PING follows once the moved window has stood still for the quiet period"));
    }

    /**
     * The value read at the acknowledgement is not enough on its own: a window that moved while the PING
     * was outstanding and is back at its PING-time value when the acknowledgement is read still moved, and
     * request data may have arrived in between. The matched control never moves and ends as quiet, so the
     * restart is caused by the move a look saw, nothing else.
     */
    @Test
    @DisplayName("a window that moves while the PING is outstanding and is back at its PING-time value at the acknowledgement restarts the quiet period")
    void windowMovedAndReturnedBeforeTheAcknowledgementRestartsTheQuietPeriod() {
        Quiescence returned = outstandingPing();
        Quiescence unmoved = outstandingPing();
        int moved = window - Generators.integers(1, window - 1).next();
        long movedAt = START + QUIET + POLL;
        long acknowledgedAt = movedAt + POLL;

        Outcome lookAtTheMove = returned.next(movedAt, true, moved);
        Outcome acknowledgedBack = returned.acknowledged(acknowledgedAt, true, window);
        Outcome justBeforeTheNewQuietPeriod = returned.next(acknowledgedAt + QUIET - 1, true, window);
        Outcome atTheNewQuietPeriod = returned.next(acknowledgedAt + QUIET, true, window);
        Outcome controlLook = unmoved.next(movedAt, true, window);
        Outcome controlAcknowledged = unmoved.acknowledged(acknowledgedAt, true, window);

        assertAll("a window that moved and returned while the PING was outstanding",
                () -> assertEquals(Outcome.WAIT, lookAtTheMove),
                () -> assertEquals(Outcome.WAIT, acknowledgedBack,
                        "a value equal to the PING-time one at the acknowledgement does not undo the move a look saw"),
                () -> assertEquals(Outcome.WAIT, justBeforeTheNewQuietPeriod,
                        "the quiet period counts from the last move, here the return seen at the acknowledgement"),
                () -> assertEquals(Outcome.PING, atTheNewQuietPeriod,
                        "a new PING follows once the window has stood still for the quiet period again"),
                () -> assertEquals(Outcome.WAIT, controlLook),
                () -> assertEquals(Outcome.QUIET, controlAcknowledged,
                        "the matched control, whose window never moved, ends as quiet"));
    }

    @Test
    @DisplayName("a window that moves and returns between two looks while the PING is outstanding is not quiet at the acknowledgement")
    void windowReturnedAtALookBeforeTheAcknowledgementIsNotQuiet() {
        Quiescence quiescence = outstandingPing();
        int moved = window - Generators.integers(1, window - 1).next();
        long movedAt = START + QUIET + POLL;
        long returnedAt = movedAt + POLL;

        quiescence.next(movedAt, true, moved);
        Outcome lookAtTheReturn = quiescence.next(returnedAt, true, window);
        Outcome acknowledged = quiescence.acknowledged(returnedAt + POLL, true, window);
        Outcome justBeforeTheNewQuietPeriod = quiescence.next(returnedAt + QUIET - 1, true, window);
        Outcome atTheNewQuietPeriod = quiescence.next(returnedAt + QUIET, true, window);

        assertAll("a move and its return both seen by looks while the PING was outstanding",
                () -> assertEquals(Outcome.WAIT, lookAtTheReturn),
                () -> assertEquals(Outcome.WAIT, acknowledged, "the move was seen, so the acknowledgement is not quiet"),
                () -> assertEquals(Outcome.WAIT, justBeforeTheNewQuietPeriod,
                        "the quiet period counts from the return, the last move a look saw"),
                () -> assertEquals(Outcome.PING, atTheNewQuietPeriod));
    }

    @Test
    @DisplayName("a move first seen at the acknowledgement restarts the quiet period from the acknowledgement")
    void moveFirstSeenAtTheAcknowledgementRestartsTheQuietPeriodFromThere() {
        Quiescence quiescence = new Quiescence(START);
        quiescence.next(START, true, window);
        quiescence.next(START + QUIET, true, window);
        long acknowledgedAt = START + QUIET + POLL / 2;

        Outcome acknowledged = quiescence.acknowledged(acknowledgedAt, true, window - 1);
        Outcome justBefore = quiescence.next(acknowledgedAt + QUIET - 1, true, window - 1);
        Outcome atQuiet = quiescence.next(acknowledgedAt + QUIET, true, window - 1);

        assertAll("a move between two looks, seen first by the acknowledgement",
                () -> assertEquals(Outcome.WAIT, acknowledged),
                () -> assertEquals(Outcome.WAIT, justBefore),
                () -> assertEquals(Outcome.PING, atQuiet));
    }

    @Test
    @DisplayName("while a PING is outstanding, a client that can send nothing more and the cap still end the wait")
    void nothingSendableAndTheCapStillWinWhileAPingIsOutstanding() {
        Quiescence closing = outstandingPing();
        Quiescence usedUp = outstandingPing();
        Quiescence capped = outstandingPing();
        Quiescence acknowledgedClosed = outstandingPing();

        assertAll("an outstanding PING",
                () -> assertEquals(Outcome.NOTHING_SENDABLE, closing.next(START + QUIET + POLL, false, window),
                        "the client ended or lost the stream"),
                () -> assertEquals(Outcome.NOTHING_SENDABLE, usedUp.next(START + QUIET + POLL, true, 0),
                        "the window is used up"),
                () -> assertEquals(Outcome.TIME_CAP, capped.next(START + CAP, true, window),
                        "the cap ends the wait whatever the PING does"),
                () -> assertEquals(Outcome.NOTHING_SENDABLE,
                        acknowledgedClosed.acknowledged(START + QUIET + POLL, false, window),
                        "an acknowledgement on a stream the client ended is not quiet but nothing sendable"));
    }

    @Test
    @DisplayName("a PING that is never acknowledged never ends the wait as quiet: one PING, then the cap")
    void unacknowledgedPingLeavesTheWaitToTheCap() {
        Quiescence quiescence = new Quiescence(START);
        List<Outcome> outcomes = new ArrayList<>();
        Outcome outcome;
        long lookedAt = START;
        do {
            outcome = quiescence.next(lookedAt, true, window);
            outcomes.add(outcome);
            lookedAt += POLL;
        } while ((outcome == Outcome.WAIT || outcome == Outcome.PING) && lookedAt <= START + 2 * CAP);
        Outcome ended = outcome;
        long waited = lookedAt - POLL - START;

        assertAll("an unmoved window whose PING is never acknowledged",
                () -> assertEquals(1L, outcomes.stream().filter(Outcome.PING::equals).count(),
                        () -> "at most one PING is outstanding per wait: " + outcomes),
                () -> assertFalse(outcomes.contains(Outcome.QUIET), "no look ends the wait as quiet"),
                () -> assertEquals(Outcome.TIME_CAP, ended),
                () -> assertTrue(waited >= CAP && waited < CAP + POLL,
                        () -> "the wait must end at the first look past the cap, ended after " + waited + " ns"));
    }

    @Test
    @DisplayName("an acknowledgement with no PING of the wait outstanding ends nothing")
    void acknowledgementWithoutAnOutstandingPingEndsNothing() {
        Quiescence quiescence = new Quiescence(START);
        quiescence.next(START, true, window);
        quiescence.next(START + QUIET, true, window);
        quiescence.acknowledged(START + QUIET + POLL, true, window - 1);

        Outcome secondAcknowledgement = quiescence.acknowledged(START + QUIET + 2 * POLL, true, window - 1);

        assertEquals(Outcome.WAIT, secondAcknowledgement,
                "only the acknowledgement of the PING the wait sent can end it as quiet");
    }

    @Test
    @DisplayName("a PING that fails or is refused ends nothing: a new PING follows once the window stands still for the quiet period again")
    void failedPingIsFollowedByANewPingAfterAnotherQuietPeriod() {
        Quiescence quiescence = outstandingPing();
        Quiescence closing = outstandingPing();
        long failedAt = START + QUIET + POLL;

        quiescence.pingFailed(failedAt);
        closing.pingFailed(failedAt);
        Outcome justBefore = quiescence.next(failedAt + QUIET - 1, true, window);
        Outcome atQuiet = quiescence.next(failedAt + QUIET, true, window);

        assertAll("a failed PING",
                () -> assertEquals(Outcome.WAIT, justBefore,
                        "the quiet period restarts at the failure, so no PING is asked for at once"),
                () -> assertEquals(Outcome.PING, atQuiet,
                        "the wait is not left without a PING: a new one follows after another quiet period"),
                () -> assertEquals(Outcome.NOTHING_SENDABLE, closing.next(failedAt + POLL, false, window),
                        "a client that can send nothing more still ends the wait after a failed PING"));
    }

    @Test
    @DisplayName("an acknowledgement after the PING failed ends nothing: only an acknowledged PING is quiet")
    void acknowledgementAfterAFailedPingIsNotQuiet() {
        Quiescence quiescence = outstandingPing();
        quiescence.pingFailed(START + QUIET + POLL);

        Outcome acknowledged = quiescence.acknowledged(START + QUIET + 2 * POLL, true, window);

        assertEquals(Outcome.WAIT, acknowledged, "a failed PING is no longer outstanding, so nothing it answers is quiet");
    }

    @Test
    @DisplayName("a failure with no PING of the wait outstanding changes nothing")
    void failureWithoutAnOutstandingPingChangesNothing() {
        Quiescence quiescence = new Quiescence(START);
        quiescence.next(START, true, window);

        quiescence.pingFailed(START + QUIET - 1);
        Outcome atQuiet = quiescence.next(START + QUIET, true, window);

        assertEquals(Outcome.PING, atQuiet, "the quiet period is not restarted by a failure no PING of the wait had");
    }

    @Test
    @DisplayName("a PING that fails every time never ends the wait as quiet: one new PING per quiet period, then the cap")
    void pingFailingEveryTimeLeavesTheWaitToTheCap() {
        Quiescence quiescence = new Quiescence(START);
        List<Outcome> outcomes = new ArrayList<>();
        Outcome outcome;
        long lookedAt = START;
        do {
            outcome = quiescence.next(lookedAt, true, window);
            outcomes.add(outcome);
            if (outcome == Outcome.PING) {
                quiescence.pingFailed(lookedAt);
            }
            lookedAt += POLL;
        } while ((outcome == Outcome.WAIT || outcome == Outcome.PING) && lookedAt <= START + 2 * CAP);
        Outcome ended = outcome;
        long waited = lookedAt - POLL - START;
        // A PING at every whole quiet period before the cap; the look at the cap itself ends the wait.
        long expectedPings = EarlyResponseEnd.MAX_WAIT_MILLIS / EarlyResponseEnd.QUIET_MILLIS - 1;

        assertAll("an unmoved window whose PING fails every time",
                () -> assertEquals(expectedPings, outcomes.stream().filter(Outcome.PING::equals).count(),
                        () -> "a failed PING is followed by a new one after each quiet period: " + outcomes),
                () -> assertFalse(outcomes.contains(Outcome.QUIET), "no look ends the wait as quiet"),
                () -> assertEquals(Outcome.TIME_CAP, ended),
                () -> assertTrue(waited >= CAP && waited < CAP + POLL,
                        () -> "the wait must end at the first look past the cap, ended after " + waited + " ns"));
    }

    /** A wait that has sent its PING on a window unmoved since {@link #START} and not had it acknowledged. */
    private Quiescence outstandingPing() {
        Quiescence quiescence = new Quiescence(START);
        quiescence.next(START, true, window);
        assertEquals(Outcome.PING, quiescence.next(START + QUIET, true, window), "precondition: the PING is sent");
        return quiescence;
    }

    @Test
    @DisplayName("a window that keeps moving restarts the quiet period and ends the wait when it is used up")
    void movingWindowRestartsTheQuietPeriod() {
        Quiescence quiescence = new Quiescence(START);
        quiescence.next(START, true, window);

        Outcome moved = quiescence.next(START + QUIET - 1, true, window - 1);
        Outcome afterTheFirstQuietPeriod = quiescence.next(START + QUIET + 1, true, window - 1);
        Outcome usedUp = quiescence.next(START + QUIET + 2, true, 0);

        assertAll("a moving window",
                () -> assertEquals(Outcome.WAIT, moved),
                () -> assertEquals(Outcome.WAIT, afterTheFirstQuietPeriod,
                        "the quiet period counts from the last movement, not from the start"),
                () -> assertEquals(Outcome.NOTHING_SENDABLE, usedUp));
    }

    @Test
    @DisplayName("a window that never stands still is waited on no longer than the cap")
    void neverQuietWindowEndsAtTheCap() {
        Quiescence quiescence = new Quiescence(START);
        Outcome outcome;
        long lookedAt = START;
        int sendable = window;
        do {
            outcome = quiescence.next(lookedAt, true, sendable);
            lookedAt += POLL;
            sendable--;
        } while (outcome == Outcome.WAIT && lookedAt <= START + 2 * CAP);
        Outcome ended = outcome;
        long waited = lookedAt - POLL - START;

        assertAll("a window moving at every look",
                () -> assertEquals(Outcome.TIME_CAP, ended, "the cap ends the wait whatever the window does"),
                () -> assertTrue(waited >= CAP && waited < CAP + POLL,
                        () -> "the wait must end at the first look past the cap, ended after " + waited + " ns"));
    }
}
