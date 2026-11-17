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
 * window alone only asks for the PING; it never ends the wait.
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
    @DisplayName("a PING that fails or is never acknowledged never ends the wait as quiet: one PING, then the cap")
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
