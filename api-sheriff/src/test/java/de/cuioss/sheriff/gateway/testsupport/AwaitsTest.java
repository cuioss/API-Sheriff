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
package de.cuioss.sheriff.gateway.testsupport;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import io.vertx.core.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The matched control for {@link Awaits}. Every other test in this module consumes the two tiers
 * and would stay green if the timeout diagnostics silently stopped working — a ceiling that is
 * never reached exercises none of the reporting. This class is the one place that reaches a
 * ceiling on purpose and asserts what the resulting failure says.
 *
 * <p>It drives the package-private {@link Duration}-taking seam rather than the public tier
 * methods, so a genuine timeout costs about 50 ms instead of the 30-second connect tier. That seam
 * exists for exactly this reason, and this class is its only caller.
 *
 * <p>The assertions are deliberately falsifiable: drop the elapsed measurement, or drop the thread
 * dump, and the tests below go red rather than continuing to pass on a message that no longer
 * explains anything.
 */
@EnableGeneratorController
@DisplayName("Awaits")
class AwaitsTest {

    /** The label handed to the seam; asserted verbatim in the produced diagnostics. */
    private static final String CONTROL_LABEL = "control";

    /** Small enough that a real timeout costs milliseconds, large enough to measure. */
    private static final Duration CONTROL_CEILING = Duration.ofMillis(50);

    private static final Pattern ELAPSED_NANOS = Pattern.compile("elapsed=(\\d+) ns");
    private static final Pattern STACK_FRAME = Pattern.compile("\\tat \\S+\\.\\S+\\(");

    @Test
    @DisplayName("a future that never completes fails with the label, a measured elapsed time and a thread dump")
    void reportsLabelElapsedAndThreadDumpOnTimeout() {
        CompletableFuture<String> neverCompletes = new CompletableFuture<>();

        TimeoutException failure = assertThrows(TimeoutException.class,
                () -> Awaits.await(neverCompletes, CONTROL_LABEL, CONTROL_CEILING));

        String message = failure.getMessage();
        assertAll("timeout diagnostics",
                () -> assertTrue(message.contains(CONTROL_LABEL),
                        "the failure names what was being awaited"),
                () -> assertTrue(elapsedNanosIn(message) > 0L,
                        "the failure carries a measured, non-zero elapsed time"),
                () -> assertTrue(STACK_FRAME.matcher(message).find(),
                        "the failure carries at least one stack frame from the thread dump"),
                () -> assertTrue(message.contains(AwaitsTest.class.getSimpleName()),
                        "the dump captures the stuck thread, whose stack runs through this test"));
    }

    @Test
    @DisplayName("an already-completed future returns its value and never enters the diagnostics branch")
    void returnsTheValueWithoutEnteringTheDiagnosticsBranch() {
        String expected = Generators.letterStrings(8, 16).next();

        String actual = assertDoesNotThrow(() -> Awaits.await(
                CompletableFuture.completedFuture(expected), CONTROL_LABEL, CONTROL_CEILING));

        assertEquals(expected, actual, "the awaited value is returned unchanged");
    }

    @Test
    @DisplayName("a latch that never reaches zero fails with the same diagnostics")
    void reportsDiagnosticsWhenALatchNeverReachesZero() {
        CountDownLatch neverCountedDown = new CountDownLatch(1);

        TimeoutException failure = assertThrows(TimeoutException.class,
                () -> Awaits.awaitLatch(neverCountedDown, CONTROL_LABEL, CONTROL_CEILING));

        String message = failure.getMessage();
        assertAll("latch timeout diagnostics",
                () -> assertTrue(message.contains(CONTROL_LABEL),
                        "the failure names what was being awaited"),
                () -> assertTrue(elapsedNanosIn(message) > 0L,
                        "the failure carries a measured, non-zero elapsed time"),
                () -> assertTrue(STACK_FRAME.matcher(message).find(),
                        "the failure carries at least one stack frame from the thread dump"));
    }

    @Test
    @DisplayName("a condition that never holds fails with the same diagnostics")
    void reportsDiagnosticsWhenAConditionNeverHolds() {
        TimeoutException failure = assertThrows(TimeoutException.class,
                () -> Awaits.awaitCondition(() -> false, CONTROL_LABEL, CONTROL_CEILING));

        String message = failure.getMessage();
        assertAll("condition timeout diagnostics",
                () -> assertTrue(message.contains(CONTROL_LABEL),
                        "the failure names what was being awaited"),
                () -> assertTrue(elapsedNanosIn(message) > 0L,
                        "the failure carries a measured, non-zero elapsed time"),
                () -> assertTrue(STACK_FRAME.matcher(message).find(),
                        "the failure carries at least one stack frame from the thread dump"));
    }

    /**
     * The public tier methods take no duration, so they cannot be driven to a timeout cheaply. What
     * this pins instead is that each one genuinely reaches the instrumented core — a satisfied
     * await returns through it rather than around it.
     */
    @Test
    @DisplayName("every public tier entry point delegates to the instrumented core")
    void publicTierEntryPointsDelegateToTheInstrumentedCore() {
        String expected = Generators.letterStrings(8, 16).next();
        CountDownLatch alreadyAtZero = new CountDownLatch(0);

        assertAll("public tier surface",
                () -> assertEquals(expected,
                        Awaits.connect(CompletableFuture.completedFuture(expected), CONTROL_LABEL),
                        "connect returns the value of a completed java.util.concurrent future"),
                () -> assertEquals(expected,
                        Awaits.teardown(CompletableFuture.completedFuture(expected), CONTROL_LABEL),
                        "teardown returns the value of a completed java.util.concurrent future"),
                () -> assertEquals(expected,
                        Awaits.connect(Future.succeededFuture(expected), CONTROL_LABEL),
                        "connect returns the value of a succeeded Vert.x future"),
                () -> assertEquals(expected,
                        Awaits.teardown(Future.succeededFuture(expected), CONTROL_LABEL),
                        "teardown returns the value of a succeeded Vert.x future"),
                () -> assertDoesNotThrow(() -> Awaits.connect(alreadyAtZero, CONTROL_LABEL),
                        "connect accepts a latch that has already reached zero"),
                () -> assertDoesNotThrow(
                        () -> Awaits.until(() -> true, CONTROL_LABEL, Awaits.TEARDOWN_CEILING_SECONDS),
                        "until accepts a condition that already holds"));
    }

    @Test
    @DisplayName("the two tiers carry the declared ceilings")
    void pinsTheTwoTierCeilings() {
        assertAll("tier ceilings",
                () -> assertEquals(30L, Awaits.CONNECT_CEILING_SECONDS,
                        "the connect tier is generous enough for a loaded CI machine"),
                () -> assertEquals(5L, Awaits.TEARDOWN_CEILING_SECONDS,
                        "the teardown tier is tight enough to surface a leak"));
    }

    /**
     * Extracts the elapsed nanos the diagnostics claim to have measured.
     *
     * @param message the timeout message
     * @return the reported elapsed nanos, or {@code -1} when the message carries no measurement at
     *         all — which is itself a failure of the contract under test
     */
    private static long elapsedNanosIn(String message) {
        Matcher matcher = ELAPSED_NANOS.matcher(message);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1L;
    }
}
