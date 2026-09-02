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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.UpgradeRejectedException;
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
 *
 * <p>The rejected-upgrade enrichment carries a fourth, independent red-on-drop property, narrowed by
 * two probes: drop the enrichment and both {@link #reportsStatusAndHeadersOnARejectedUpgrade()} and
 * {@link #statesTheAbsenceOfAZeroLengthRejectionBody()} go red, while dropping only the empty-body
 * rendering reddens exactly the latter. Neither joins an existing set, because both assert on nothing
 * but the thrown type, the cause identity and the rejection detail — never on the dump, never on the
 * elapsed time. Keeping the probes disjoint is what lets a single red method name which mechanism was
 * lost, so a new assertion here must not reach for the dump or the measurement either.
 */
@EnableGeneratorController
@DisplayName("Awaits")
class AwaitsTest {

    /** The label handed to the seam; asserted verbatim in the produced diagnostics. */
    private static final String CONTROL_LABEL = "control";

    /** Small enough that a real timeout costs milliseconds, large enough to measure. */
    private static final Duration CONTROL_CEILING = Duration.ofMillis(50);

    private static final Pattern ELAPSED_NANOS = Pattern.compile("elapsed=(\\d+) ns");

    /**
     * Matches an indented rendered {@code StackTraceElement}. Deliberately tolerant of the frame
     * prefix, because the two renderings {@code Awaits} can produce — the virtual-thread-aware
     * capture and the platform-only degraded path — indent frames differently. What both guarantee,
     * and all this pins, is that frames are present at all.
     */
    private static final Pattern STACK_FRAME =
            Pattern.compile("^\\s+(?:at )?\\S+\\.[^\\s.(]+\\(", Pattern.MULTILINE);

    @Test
    @DisplayName("a future that never completes fails with the label, a measured elapsed time and a dump carrying both the stuck thread and a parked virtual thread")
    void reportsLabelElapsedAndThreadDumpOnTimeout() throws Exception {
        CountDownLatch parked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread probe = Thread.ofVirtual().name("awaits-virtual-probe")
                .start(() -> ParkedVirtualProbe.parkUntilReleased(parked, release));
        parked.await();
        CompletableFuture<String> neverCompletes = new CompletableFuture<>();

        TimeoutException failure;
        try {
            failure = assertThrows(TimeoutException.class,
                    () -> Awaits.await(neverCompletes, CONTROL_LABEL, CONTROL_CEILING));
        } finally {
            release.countDown();
        }
        probe.join();

        String message = failure.getMessage();
        assertAll("timeout diagnostics",
                () -> assertTrue(message.contains(CONTROL_LABEL),
                        "the failure names what was being awaited"),
                () -> assertTrue(elapsedNanosIn(message) > 0L,
                        "the failure carries a measured, non-zero elapsed time"),
                () -> assertTrue(STACK_FRAME.matcher(message).find(),
                        "the failure carries at least one stack frame from the thread dump"),
                () -> assertTrue(message.contains("reportsLabelElapsedAndThreadDumpOnTimeout"),
                        "the dump captures the stuck thread, whose stack runs through this method"),
                () -> assertTrue(message.contains(ParkedVirtualProbe.class.getSimpleName()),
                        "the dump captures the parked virtual thread — the platform-only rendering "
                                + "cannot see it, so this is what proves the capture is virtual-thread aware"));
    }

    @Test
    @DisplayName("an already-completed future returns its value and never enters the diagnostics branch")
    void returnsTheValueWithoutEnteringTheDiagnosticsBranch() {
        String expected = Generators.letterStrings(8, 16).next();

        String actual = assertDoesNotThrow(() -> Awaits.await(
                CompletableFuture.completedFuture(expected), CONTROL_LABEL, CONTROL_CEILING));

        assertEquals(expected, actual, "the awaited value is returned unchanged");
    }

    /**
     * The matched control for the rejected-upgrade enrichment. It asserts on the thrown type, the
     * cause identity and the rejection detail only — deliberately not on the thread dump or the
     * elapsed measurement, so it stays disjoint from the three timeout probes above.
     */
    @Test
    @DisplayName("a rejected upgrade keeps its wrapper and its cause, and gains a message naming the status and headers")
    void reportsStatusAndHeadersOnARejectedUpgrade() {
        int status = Generators.integers(400, 599).next();
        String headerName = "X-" + Generators.letterStrings(4, 10).next();
        // The rejection's own message deliberately carries no digits: were it to restate the status,
        // the wrapper's default toString rendering would satisfy the status assertion below even with
        // the enrichment dropped, and this probe would stop being falsifiable.
        String bodyContent = Generators.letterStrings(4, 10).next();
        UpgradeRejectedException rejected = new UpgradeRejectedException(
                Generators.letterStrings(8, 16).next(), status,
                MultiMap.caseInsensitiveMultiMap().add(headerName, Generators.letterStrings(4, 10).next()),
                Buffer.buffer(bodyContent));
        CompletableFuture<String> refused = new CompletableFuture<>();
        refused.completeExceptionally(rejected);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> Awaits.await(refused, CONTROL_LABEL, CONTROL_CEILING));

        String message = failure.getMessage();
        assertAll("rejected-upgrade diagnostics",
                () -> assertSame(rejected, failure.getCause(),
                        "the cause is the identical instance received — the downstream assertInstanceOf "
                                + "sites key on exactly that"),
                () -> assertTrue(message.contains(String.valueOf(status)),
                        "the failure names the rejected status"),
                () -> assertTrue(message.contains(headerName),
                        "the failure names the rejection's headers, whose presence is the discriminator"),
                () -> assertTrue(message.contains(bodyContent),
                        "the failure names the rejection's body content — without this the pair is "
                                + "blind to a regression that stops rendering a non-empty body, since "
                                + "the companion test only pins the empty case"));
    }

    /**
     * The empty-body companion to {@link #reportsStatusAndHeadersOnARejectedUpgrade()}. A rejection
     * whose body is a zero-length {@link Buffer} must state that absence, rather than render nothing
     * after {@code body=} — the same treatment an empty header map already gets. It stays inside the
     * same disjoint set: thrown type, cause identity and rejection detail only, never the dump and
     * never the elapsed measurement.
     */
    @Test
    @DisplayName("a rejected upgrade with a zero-length body states that absence rather than rendering nothing")
    void statesTheAbsenceOfAZeroLengthRejectionBody() {
        UpgradeRejectedException rejected = new UpgradeRejectedException(
                Generators.letterStrings(8, 16).next(), Generators.integers(400, 599).next(),
                MultiMap.caseInsensitiveMultiMap(), Buffer.buffer());
        CompletableFuture<String> refused = new CompletableFuture<>();
        refused.completeExceptionally(rejected);

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> Awaits.await(refused, CONTROL_LABEL, CONTROL_CEILING));

        String message = failure.getMessage();
        assertAll("zero-length rejection body",
                () -> assertSame(rejected, failure.getCause(),
                        "the cause is the identical instance received — the enrichment rewrites the "
                                + "message and nothing else"),
                () -> assertTrue(message.contains("body=<none>"),
                        "a zero-length body is stated explicitly; rendering it as nothing would leave "
                                + "the message trailing off after body= with no way to tell an empty "
                                + "body from an absent one"));
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

    /**
     * Parks a virtual thread on a frame whose class name appears nowhere else, so finding that name
     * in a dump is unambiguous evidence that the dump enumerated virtual threads. The thread stays
     * inside {@link #parkUntilReleased} for the whole window between {@code parked} counting down
     * and {@code release} being counted down, which is what makes the observation race-free.
     */
    private static final class ParkedVirtualProbe {

        private ParkedVirtualProbe() {
            // frame holder
        }

        private static void parkUntilReleased(CountDownLatch parked, CountDownLatch release) {
            parked.countDown();
            try {
                release.await();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
