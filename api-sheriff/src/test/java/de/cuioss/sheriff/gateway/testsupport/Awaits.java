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

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import de.cuioss.tools.logging.CuiLogger;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

/**
 * The single owner of every polling ceiling in the live-server edge and TLS tests, and of the
 * diagnostics emitted when one of those ceilings is reached.
 *
 * <h2>Tiers, not values</h2>
 * A call site picks a ceiling by naming <em>what it is waiting for</em>, never by naming a number.
 * Two tiers exist and they are the only ceiling values in the codebase:
 * <ul>
 *   <li>{@link #CONNECT_CEILING_SECONDS} — everything that waits on progress from a real server:
 *       listen, connect, request, response, relay. Generous, because a loaded CI machine is slow,
 *       not broken.</li>
 *   <li>{@link #TEARDOWN_CEILING_SECONDS} — everything that waits on a {@code close()} or
 *       {@code shutdown()} completing. Tight, because teardown that has not finished quickly is a
 *       leak rather than a slow machine.</li>
 * </ul>
 * Choosing by tier is what keeps a slow-but-healthy machine from failing the suite while still
 * failing fast on a genuine hang.
 *
 * <h2>A timeout here explains itself</h2>
 * A bare {@code TimeoutException} from {@code Future.get} says only that time ran out — the most
 * expensive failure to diagnose from CI logs alone. Every entry point below instead captures the
 * elapsed time and a full thread dump, then rethrows a {@link TimeoutException} whose message
 * carries the label, the ceiling, the elapsed time and the dump. The dump names the thread that was
 * stuck and what it was stuck on.
 *
 * <h2>The dump has to see virtual threads</h2>
 * The gateway hands every request to a virtual-thread executor, so the request pipeline — the very
 * thing a hang needs explaining — runs on virtual threads. {@link Thread#getAllStackTraces()} has
 * enumerated <em>platform threads only</em> since JDK 21 and would silently omit exactly those
 * frames, producing a dump that proves the event loops were idle while saying nothing about the
 * thread that actually hung. The capture therefore goes through
 * {@link HotSpotDiagnosticMXBean#dumpThreads(String, HotSpotDiagnosticMXBean.ThreadDumpFormat)} —
 * the in-process equivalent of {@code jcmd <pid> Thread.dump_to_file}, present in the
 * {@code jdk.management} module of every standard JDK since 21, so still no additional dependency.
 * Producing a {@code TimeoutException} always wins over producing a perfect one: if that capture
 * fails for any reason the dump degrades to the {@link Thread#getAllStackTraces()} rendering rather
 * than propagating, and the timeout is still reported.
 *
 * <h2>Structure</h2>
 * The public surface is tier-named and takes no duration. Each entry point delegates to a
 * package-private {@link Duration}-taking core, which is where the instrumentation lives. That seam
 * exists so {@code AwaitsTest} can drive a real timeout in milliseconds instead of waiting out a
 * 30-second tier; it is not part of the surface call sites use.
 *
 * <p>Thread-safe: the class is stateless and every member is static.
 *
 * @since 1.0
 */
public final class Awaits {

    /**
     * Ceiling for any await on progress from a real server — listen, connect, request, response,
     * relay. Deliberately generous so a loaded CI machine does not read as a hang.
     */
    public static final long CONNECT_CEILING_SECONDS = 30;

    /**
     * Ceiling for any await on a {@code close()} / {@code shutdown()} completion. Deliberately
     * tight: teardown that has not completed by now indicates a leak, not a slow machine.
     */
    public static final long TEARDOWN_CEILING_SECONDS = 5;

    private static final CuiLogger LOGGER = new CuiLogger(Awaits.class);

    private static final Duration CONNECT_CEILING = Duration.ofSeconds(CONNECT_CEILING_SECONDS);
    private static final Duration TEARDOWN_CEILING = Duration.ofSeconds(TEARDOWN_CEILING_SECONDS);

    /**
     * Poll cadence for the condition-based waits. Short enough that a converted busy-wait loop is
     * no slower than the {@code Thread.sleep} poll it replaced.
     */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(25);

    /**
     * Distinguishes successive capture files within one JVM. The MXBean refuses an existing path, so
     * every capture needs a name of its own.
     */
    private static final AtomicLong DUMP_SEQUENCE = new AtomicLong();

    private Awaits() {
        // utility class
    }

    /**
     * Awaits a server-progress future on the connect tier.
     *
     * @param future the future to await, must not be {@code null}
     * @param what   what is being awaited, surfaced verbatim in the timeout diagnostics
     * @param <T>    the future's value type
     * @return the future's value
     * @throws TimeoutException     enriched with the label, ceiling, elapsed time and a thread dump
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if the future completed exceptionally
     */
    public static <T> T connect(Future<T> future, String what)
            throws InterruptedException, ExecutionException, TimeoutException {
        return await(future, what, CONNECT_CEILING);
    }

    /**
     * Awaits a Vert.x server-progress future on the connect tier.
     *
     * @param future the future to await, must not be {@code null}
     * @param what   what is being awaited, surfaced verbatim in the timeout diagnostics
     * @param <T>    the future's value type
     * @return the future's value
     * @throws TimeoutException     enriched with the label, ceiling, elapsed time and a thread dump
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if the future completed exceptionally
     */
    public static <T> T connect(io.vertx.core.Future<T> future, String what)
            throws InterruptedException, ExecutionException, TimeoutException {
        return await(future, what, CONNECT_CEILING);
    }

    /**
     * Awaits a {@code close()} / {@code shutdown()} future on the teardown tier.
     *
     * @param future the future to await, must not be {@code null}
     * @param what   what is being awaited, surfaced verbatim in the timeout diagnostics
     * @param <T>    the future's value type
     * @return the future's value
     * @throws TimeoutException     enriched with the label, ceiling, elapsed time and a thread dump
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if the future completed exceptionally
     */
    public static <T> T teardown(Future<T> future, String what)
            throws InterruptedException, ExecutionException, TimeoutException {
        return await(future, what, TEARDOWN_CEILING);
    }

    /**
     * Awaits a Vert.x {@code close()} / {@code shutdown()} future on the teardown tier.
     *
     * @param future the future to await, must not be {@code null}
     * @param what   what is being awaited, surfaced verbatim in the timeout diagnostics
     * @param <T>    the future's value type
     * @return the future's value
     * @throws TimeoutException     enriched with the label, ceiling, elapsed time and a thread dump
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if the future completed exceptionally
     */
    public static <T> T teardown(io.vertx.core.Future<T> future, String what)
            throws InterruptedException, ExecutionException, TimeoutException {
        return await(future, what, TEARDOWN_CEILING);
    }

    /**
     * Awaits a latch reaching zero on the connect tier, asserting that it did.
     *
     * @param latch the latch to await, must not be {@code null}
     * @param what  what is being awaited, surfaced verbatim in the timeout diagnostics
     * @throws TimeoutException     if the latch did not reach zero within the ceiling, enriched
     *                              with the label, ceiling, elapsed time and a thread dump
     * @throws InterruptedException if the waiting thread is interrupted
     */
    public static void connect(CountDownLatch latch, String what)
            throws InterruptedException, TimeoutException {
        awaitLatch(latch, what, CONNECT_CEILING);
    }

    /**
     * Polls a condition until it holds, on the tier the caller names.
     *
     * @param condition     evaluated repeatedly until it returns {@code true}
     * @param what          what is being awaited, surfaced verbatim in the timeout diagnostics
     * @param ceilingSeconds one of {@link #CONNECT_CEILING_SECONDS} or
     *                      {@link #TEARDOWN_CEILING_SECONDS}
     * @throws TimeoutException if the condition did not hold within the ceiling, enriched with the
     *                          label, ceiling, elapsed time and a thread dump
     */
    public static void until(Callable<Boolean> condition, String what, long ceilingSeconds)
            throws TimeoutException {
        awaitCondition(condition, what, Duration.ofSeconds(ceilingSeconds));
    }

    /**
     * The instrumented core every future-shaped entry point funnels through.
     *
     * @param future  the future to await
     * @param what    what is being awaited
     * @param ceiling how long to wait before giving up
     * @param <T>     the future's value type
     * @return the future's value
     * @throws TimeoutException     enriched with the label, ceiling, elapsed time and a thread dump
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if the future completed exceptionally
     */
    static <T> T await(Future<T> future, String what, Duration ceiling)
            throws InterruptedException, ExecutionException, TimeoutException {
        long startNanos = System.nanoTime();
        try {
            return future.get(ceiling.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException cause) {
            throw timedOut(what, ceiling, System.nanoTime() - startNanos, cause);
        }
    }

    /**
     * Adapts a Vert.x future onto the instrumented core above, so both future shapes share one
     * diagnostics path.
     *
     * @param future  the future to await
     * @param what    what is being awaited
     * @param ceiling how long to wait before giving up
     * @param <T>     the future's value type
     * @return the future's value
     * @throws TimeoutException     enriched with the label, ceiling, elapsed time and a thread dump
     * @throws InterruptedException if the waiting thread is interrupted
     * @throws ExecutionException   if the future completed exceptionally
     */
    static <T> T await(io.vertx.core.Future<T> future, String what, Duration ceiling)
            throws InterruptedException, ExecutionException, TimeoutException {
        return await(future.toCompletionStage().toCompletableFuture(), what, ceiling);
    }

    /**
     * The instrumented core behind {@link #connect(CountDownLatch, String)}.
     *
     * @param latch   the latch to await
     * @param what    what is being awaited
     * @param ceiling how long to wait before giving up
     * @throws TimeoutException     if the latch did not reach zero in time
     * @throws InterruptedException if the waiting thread is interrupted
     */
    static void awaitLatch(CountDownLatch latch, String what, Duration ceiling)
            throws InterruptedException, TimeoutException {
        long startNanos = System.nanoTime();
        if (!latch.await(ceiling.toMillis(), TimeUnit.MILLISECONDS)) {
            throw timedOut(what, ceiling, System.nanoTime() - startNanos, null);
        }
    }

    /**
     * The instrumented core behind {@link #until(Callable, String, long)}, backed by Awaitility's
     * {@code until(Callable)} overload — the one that takes a plain predicate and touches no
     * Hamcrest type.
     *
     * @param condition evaluated repeatedly until it returns {@code true}
     * @param what      what is being awaited
     * @param ceiling   how long to wait before giving up
     * @throws TimeoutException if the condition did not hold in time
     */
    static void awaitCondition(Callable<Boolean> condition, String what, Duration ceiling)
            throws TimeoutException {
        long startNanos = System.nanoTime();
        try {
            Awaitility.await()
                    .atMost(ceiling)
                    .pollDelay(Duration.ZERO)
                    .pollInterval(POLL_INTERVAL)
                    .until(condition);
        } catch (ConditionTimeoutException cause) {
            throw timedOut(what, ceiling, System.nanoTime() - startNanos, cause);
        }
    }

    /**
     * Builds the enriched timeout carrying everything needed to diagnose the hang from a CI log
     * alone: the label, the ceiling, the measured elapsed time and a full thread dump.
     *
     * @param what         what was being awaited
     * @param ceiling      the ceiling that was reached
     * @param elapsedNanos the measured elapsed time
     * @param cause        the underlying timeout, or {@code null} where there was no exception
     * @return the exception to throw
     */
    private static TimeoutException timedOut(String what, Duration ceiling, long elapsedNanos,
            Throwable cause) {
        String message = "timed out awaiting %s: ceiling=%s ms, elapsed=%s ns (%s ms)%n%s".formatted(
                what, ceiling.toMillis(), elapsedNanos,
                TimeUnit.NANOSECONDS.toMillis(elapsedNanos), threadDump());
        LOGGER.debug("Await ceiling reached: %s", message);
        TimeoutException failure = new TimeoutException(message);
        if (null != cause) {
            failure.initCause(cause);
        }
        return failure;
    }

    /**
     * Renders every live thread's stack. This is the payload that turns "it timed out" into "this
     * thread was blocked here".
     *
     * <p>Prefers the virtual-thread-aware capture and falls back to the platform-only rendering, so
     * a capture failure costs detail rather than the whole diagnostic.
     *
     * @return the rendered dump, never {@code null}
     */
    private static String threadDump() {
        return virtualThreadAwareDump().orElseGet(Awaits::platformThreadDump);
    }

    /**
     * Captures a dump that includes virtual threads, via the {@code jdk.management} MXBean that
     * backs {@code jcmd Thread.dump_to_file}.
     *
     * <p>Two sharp edges are handled here. The bean refuses to write to a path that already exists,
     * so the destination is a freshly named file that is deliberately never pre-created; and it
     * reports failure by exception, which must never escape and rob the caller of its
     * {@link TimeoutException}. Every failure mode therefore degrades to {@link Optional#empty()}.
     *
     * @return the captured dump, or empty when the capture was not possible
     */
    private static Optional<String> virtualThreadAwareDump() {
        HotSpotDiagnosticMXBean bean =
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        if (null == bean) {
            return Optional.empty();
        }
        Path file = Path.of(System.getProperty("java.io.tmpdir"))
                .toAbsolutePath()
                .resolve("awaits-thread-dump-%d-%d.txt".formatted(
                        ProcessHandle.current().pid(), DUMP_SEQUENCE.incrementAndGet()));
        try {
            bean.dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            return Optional.of("thread dump (virtual threads included):"
                    + System.lineSeparator() + Files.readString(file));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException cause) {
            LOGGER.debug(cause,
                    "Virtual-thread-aware dump unavailable, degrading to the platform-only rendering");
            return Optional.empty();
        } finally {
            deleteQuietly(file);
        }
    }

    /**
     * Removes the capture file, swallowing failure: a leftover temp file is not worth losing the
     * timeout report over.
     *
     * @param file the file to remove
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException cause) {
            LOGGER.debug(cause, "Could not delete thread-dump capture %s", file);
        }
    }

    /**
     * The platform-threads-only rendering, kept as the degraded path. It cannot see virtual threads,
     * which is why it is the fallback rather than the primary capture.
     *
     * @return the rendered dump, never {@code null}
     */
    private static String platformThreadDump() {
        Map<Thread, StackTraceElement[]> traces = Thread.getAllStackTraces();
        StringBuilder rendered = new StringBuilder(1024)
                .append("thread dump (").append(traces.size()).append(" platform threads):");
        traces.forEach((thread, frames) -> {
            rendered.append(System.lineSeparator())
                    .append('"').append(thread.getName()).append("\" state=").append(thread.getState());
            for (StackTraceElement frame : frames) {
                rendered.append(System.lineSeparator()).append("\tat ").append(frame);
            }
        });
        return rendered.toString();
    }
}
