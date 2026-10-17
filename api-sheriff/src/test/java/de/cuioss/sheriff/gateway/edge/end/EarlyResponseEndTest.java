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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.edge.end.EarlyResponseEnd.Outcome;
import de.cuioss.sheriff.gateway.edge.end.EarlyResponseEnd.Quiescence;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rule {@link EarlyResponseEnd} waits by, driven on synthetic times and windows: the end of an
 * early-answered HTTP/2 response goes ahead once the client can send nothing more, once its window has
 * not moved for {@value EarlyResponseEnd#QUIET_MILLIS} ms, or after {@value EarlyResponseEnd#MAX_WAIT_MILLIS} ms
 * — whichever comes first — and not before.
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
    @DisplayName("an open window is waited on until it has stood still for the quiet period")
    void openWindowIsQuietOnlyAfterTheQuietPeriod() {
        Quiescence quiescence = new Quiescence(START);

        Outcome first = quiescence.next(START, true, window);
        Outcome justBefore = quiescence.next(START + QUIET - 1, true, window);
        Outcome atQuiet = quiescence.next(START + QUIET, true, window);

        assertAll("an unmoved open window",
                () -> assertEquals(Outcome.WAIT, first, "the first look never ends the wait"),
                () -> assertEquals(Outcome.WAIT, justBefore),
                () -> assertEquals(Outcome.QUIET, atQuiet));
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
