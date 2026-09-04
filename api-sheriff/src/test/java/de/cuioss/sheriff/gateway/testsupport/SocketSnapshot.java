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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import de.cuioss.tools.logging.CuiLogger;

/**
 * Captures the operating system's view of this JVM's TCP sockets at the instant an {@link Awaits}
 * ceiling is reached.
 *
 * <h2>What this is for</h2>
 * A thread dump answers "what was the JVM doing" and has already been read: on every observed
 * occurrence of the macOS-local loopback stall the acceptor thread and all event loops sit parked in
 * {@code sun.nio.ch.KQueue.poll}, doing nothing. That establishes the work never <em>arrived</em>,
 * but it cannot say whether the work reached the machine at all. Only the kernel's own socket table
 * can, and this class is the capture of it.
 *
 * <h2>How to read the capture</h2>
 * The two commands answer different halves of the question and are both required.
 *
 * <p>{@code lsof} answers <em>which sockets this process holds and in what state</em>:
 * <ul>
 *   <li>an {@code (ESTABLISHED)} pair on both ends of the loopback — the connection completed, so
 *       any stall is above the socket layer, not in connection setup</li>
 *   <li>{@code (SYN_SENT)} — the connect never completed; a different mechanism entirely, re-frame</li>
 *   <li>no socket for the port at all — the connect was never issued; look above the socket layer</li>
 * </ul>
 *
 * <p>{@code netstat} adds the columns that actually discriminate the surviving hypothesis. The
 * invocation is platform-dependent — {@code -anv -p tcp} on macOS, {@code -ant} on Linux, because
 * Linux reads {@code -p} as <em>show-program</em> rather than as a protocol selector — and the
 * discriminating columns below are the macOS ones. On macOS the {@code -v} is load-bearing rather
 * than cosmetic. It adds {@code rxbytes} /
 * {@code txbytes} — cumulative per-socket byte counters, which say whether anything was <em>ever</em>
 * transferred, where a queue depth says only what is pending right now — and {@code process:pid},
 * which is the only way to tell a socket this JVM still owns from a kernel-side remnant of one it has
 * already closed. Read alongside the queue depths macOS reports:
 * <ul>
 *   <li>{@code Recv-Q > 0} on a socket whose owning thread is parked in {@code KQueue.poll} — the
 *       bytes are sitting in the kernel receive buffer and the selector never told anyone. That is
 *       the undelivered-readiness hypothesis observed directly, not inferred.</li>
 *   <li>{@code Recv-Q == 0} and {@code Send-Q == 0} on both ends, <em>and</em> {@code rxbytes} and
 *       {@code txbytes} both zero — nothing was ever put on the wire. The stall is upstream of the
 *       socket and the readiness hypothesis does not explain it. <strong>The byte counters are
 *       required for this reading, not decoration.</strong> The queue depths are instantaneous, so
 *       zero queues alone mean only that nothing is pending at capture time — an established
 *       connection that transferred and consumed its data before the capture shows exactly the same
 *       zeros. Only the cumulative counters distinguish "never sent" from "already drained".</li>
 *   <li>{@code Send-Q > 0} on the sender with {@code Recv-Q == 0} on the peer — bytes are stuck in
 *       the send buffer, which is a flow-control or window question, not a readiness one.</li>
 * </ul>
 *
 * <p>The three readings are mutually exclusive, which is what makes the capture a discriminator
 * rather than more context.
 *
 * <h2>Diagnostic, never a gate</h2>
 * Every failure mode — a missing binary, an I/O error, a command that overruns its bound, an
 * unparseable rendering — degrades to a stated note inside the returned text. This class must never
 * throw and must never fail a test: it exists to explain a failure that has already happened, and
 * losing the underlying {@link java.util.concurrent.TimeoutException} to a diagnostic's own error
 * would destroy the very report it was added to produce.
 *
 * <p>Thread-safe: the class is stateless and every member is static.
 *
 * @since 1.0
 */
public final class SocketSnapshot {

    /**
     * The heading every capture starts with. Package-private rather than inlined at the call site so
     * the matched control in {@code AwaitsTest} can assert on the same constant the production path
     * emits, instead of on a copy that could drift.
     */
    static final String SECTION_HEADER = "socket snapshot (OS view of this JVM's TCP sockets):";

    /**
     * Opens the {@code lsof} half of a capture. Package-private and consumed by {@code capture()}
     * itself so a probe that narrows a message to one tool's rows cannot drift from the rendering
     * it narrows: changing the banner changes both sides at once.
     */
    static final String LSOF_SUBSECTION = "  lsof -w -nP -iTCP -a -p ";

    /**
     * Opens the {@code netstat} half of a capture. Same drift argument as {@link #LSOF_SUBSECTION}.
     * <p>
     * Deliberately carries no argv: the flags are platform-dependent (see {@link #netstatArgs()}),
     * and a banner that named one platform's flags would stop matching on the other — silently
     * turning a probe that narrows a message to these rows into one that finds nothing. The argv
     * actually used is rendered inside the section instead.
     */
    static final String NETSTAT_SUBSECTION =
            "  netstat (Recv-Q/Send-Q, rxbytes/txbytes and process:pid discriminate):";

    /** {@code true} on macOS, whose {@code netstat} differs from Linux's in both flags and format. */
    private static final boolean IS_MACOS = System.getProperty("os.name", "").startsWith("Mac");

    private static final CuiLogger LOGGER = new CuiLogger(SocketSnapshot.class);

    /**
     * Absolute candidate paths, never a bare command name: a test must not depend on whatever
     * {@code PATH} the surrounding build happens to export. The first executable candidate wins.
     */
    private static final List<String> LSOF_BINARIES = List.of("/usr/sbin/lsof", "/usr/bin/lsof");

    private static final List<String> NETSTAT_BINARIES =
            List.of("/usr/sbin/netstat", "/bin/netstat", "/usr/bin/netstat");

    /** Bounded so a wedged diagnostic cannot become a second hang on top of the one being reported. */
    private static final long COMMAND_TIMEOUT_SECONDS = 5;

    /**
     * Caps how much of a capture is read back onto the heap. A system-wide {@code netstat} on a
     * socket-heavy host can be large, and this diagnostic must not become the failure it reports.
     * Generous enough that an ordinary capture is never truncated.
     */
    private static final int MAX_CAPTURE_BYTES = 512 * 1024;

    /**
     * Matches the port of an {@code lsof} address token — {@code 127.0.0.1:59120} or
     * {@code [::1]:59120}, on either side of the {@code ->} of an established pair.
     */
    private static final Pattern LSOF_PORT = Pattern.compile(":(\\d{1,5})\\b");

    /**
     * Matches a {@code netstat} address token. macOS separates the port with a dot
     * ({@code 127.0.0.1.59120}, {@code ::1.59120}); Linux uses a colon ({@code 127.0.0.1:59120}),
     * the same shape {@code lsof} prints.
     * <p>
     * Platform-selected rather than a union of both: a dot-matching pattern applied to Linux output
     * matches the dots <em>inside</em> the IPv4 address, so {@code 127.0.0.1:59120} would yield a
     * spurious port {@code 1}. Accepting either separator everywhere would not widen coverage, it
     * would manufacture wrong ports.
     */
    private static final Pattern NETSTAT_PORT = Pattern.compile(
            IS_MACOS ? "\\.(\\d{1,5})(?:\\s|$|-)" : ":(\\d{1,5})\\b");

    /**
     * Caps the loopback fallback rendering, so a machine with many loopback services cannot paste an
     * unbounded table into a failure message.
     */
    private static final int MAX_FALLBACK_LINES = 120;

    /** Distinguishes successive capture files within one JVM; the redirect target must be unique. */
    private static final AtomicLong CAPTURE_SEQUENCE = new AtomicLong();

    private SocketSnapshot() {
        // utility class
    }

    /**
     * Captures the OS socket state for this process.
     *
     * @return the rendered capture, never {@code null} and never empty; degradation is reported
     *         inside the text rather than by returning nothing
     */
    public static String capture() {
        long pid = ProcessHandle.current().pid();
        String lsof = run(LSOF_BINARIES, "lsof",
                "-w", "-nP", "-iTCP", "-a", "-p", String.valueOf(pid));
        Set<String> ports = portsIn(lsof, LSOF_PORT);
        String[] netstatArgs = netstatArgs();
        String netstat = filterToPorts(run(NETSTAT_BINARIES, "netstat", netstatArgs), ports);
        return new StringBuilder(512)
                .append(SECTION_HEADER)
                .append(System.lineSeparator()).append("  pid=").append(pid)
                .append(", ports observed=").append(ports.isEmpty() ? "<none>" : ports)
                .append(System.lineSeparator()).append(LSOF_SUBSECTION).append(pid).append(':')
                .append(System.lineSeparator()).append(indent(lsof))
                .append(System.lineSeparator())
                .append(NETSTAT_SUBSECTION)
                .append(System.lineSeparator()).append("    argv: netstat ")
                .append(String.join(" ", netstatArgs))
                .append(System.lineSeparator()).append(indent(netstat))
                .toString();
    }

    /**
     * The {@code netstat} flags for this platform.
     *
     * <p>macOS selects the protocol with {@code -p tcp} and takes {@code -v} for the extra
     * Recv-Q/Send-Q and process columns. Linux's {@code netstat} reads {@code -p} as
     * <em>show-program</em> — it takes no argument there — so passing {@code -p tcp} does not select
     * TCP and does not produce the intended table. {@code -ant} is the Linux spelling that does.
     *
     * <p>This divergence is why the netstat half produced no usable rows on Linux CI while passing
     * on macOS: the invocation was macOS-only, and so was the port pattern above.
     *
     * @return the argv tail for this platform, never empty
     */
    private static String[] netstatArgs() {
        return IS_MACOS ? new String[]{"-anv", "-p", "tcp"} : new String[]{"-ant"};
    }

    /**
     * Reports whether both capture commands resolve to an executable on this machine.
     *
     * <p>Exists so a matched control can assert on the <em>content</em> of a capture where the tools
     * are present, and abstain — rather than fail — where they are not. A test that quietly passes on
     * a permanently degraded capture would prove nothing, so the two cases are separated explicitly
     * instead of being papered over by a weaker assertion.
     *
     * @return {@code true} when {@code lsof} and {@code netstat} were both found
     */
    public static boolean available() {
        return binary(LSOF_BINARIES).isPresent() && binary(NETSTAT_BINARIES).isPresent();
    }

    /**
     * Runs one capture command with its output redirected to a file.
     *
     * <p>The redirect is not incidental. Reading a child's pipe only after {@code waitFor} deadlocks
     * as soon as the child outproduces the 64 KiB pipe buffer, and {@code netstat -an} on a busy
     * machine does exactly that — the diagnostic would then time out precisely on the machines whose
     * socket tables are most worth reading.
     *
     * <p>The capture file is created by {@link Files#createTempFile}, never by composing a
     * predictable name under {@code java.io.tmpdir}. A composed name is guessable, and
     * {@code ProcessBuilder}'s output redirect follows a pre-existing symlink at that path — so on a
     * shared temp directory another user could pre-create the name and have this process's redirect
     * write through it. {@code createTempFile} creates the file atomically with owner-only
     * permissions, which removes both the guess and the pre-creation window.
     *
     * @param candidates absolute paths to try, in order
     * @param label      the command's name, used in degradation notes
     * @param arguments  the command's arguments
     * @return the command's combined output, or a stated degradation note
     */
    private static String run(List<String> candidates, String label, String... arguments) {
        Optional<String> resolved = binary(candidates);
        if (resolved.isEmpty()) {
            return "%s unavailable (no executable at %s)".formatted(label, String.join(" or ", candidates));
        }
        Path target;
        try {
            target = Files.createTempFile("socket-snapshot-%s-%d-%d-".formatted(
                    label, ProcessHandle.current().pid(), CAPTURE_SEQUENCE.incrementAndGet()), ".txt");
        } catch (IOException cause) {
            return "%s unavailable (could not create capture file: %s)".formatted(label, cause);
        }
        try {
            ProcessBuilder builder = new ProcessBuilder();
            builder.command().add(resolved.get());
            builder.command().addAll(List.of(arguments));
            Process process = builder.redirectErrorStream(true)
                    .redirectOutput(target.toFile())
                    .start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "%s unavailable (no answer within %ss)".formatted(label, COMMAND_TIMEOUT_SECONDS);
            }
            int status = process.exitValue();
            String output = readBounded(target).strip();
            if (output.isEmpty()) {
                return "%s produced no output (exit=%d) — this is NOT an observation that the process "
                        .formatted(label, status)
                        + "holds no sockets, only that the command said nothing";
            }
            return 0 == status ? output
                    : "%s exited %d; output follows:%n%s".formatted(label, status, output);
        } catch (InterruptedException cause) {
            Thread.currentThread().interrupt();
            return "%s unavailable (interrupted: %s)".formatted(label, cause);
        } catch (IOException cause) {
            return "%s unavailable (%s)".formatted(label, cause);
        } finally {
            deleteQuietly(target);
        }
    }

    /**
     * Resolves the first executable candidate.
     *
     * @param candidates absolute paths to try, in order
     * @return the first executable path, or empty when none is
     */
    private static Optional<String> binary(List<String> candidates) {
        return candidates.stream().filter(candidate -> Files.isExecutable(Path.of(candidate))).findFirst();
    }

    /**
     * Extracts every port mentioned in a rendering.
     *
     * @param text    the rendering to scan
     * @param pattern the address-token pattern for that rendering's format
     * @return the ports found, in encounter order, never {@code null}
     */
    private static Set<String> portsIn(String text, Pattern pattern) {
        Set<String> ports = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            ports.add(matcher.group(1));
        }
        return ports;
    }

    /**
     * Narrows a {@code netstat} rendering to the rows that concern this process's sockets.
     *
     * <p>{@code netstat} cannot filter by pid, so the port set {@code lsof} produced is what joins
     * the two views. Where that set is empty — {@code lsof} missing or degraded — the rendering is
     * capped rather than dropped, because a stall showing <em>no</em> socket for the expected port is
     * itself one of the three readings and needs the surrounding table to be legible.
     *
     * @param netstat the full rendering
     * @param ports   the ports of interest, possibly empty
     * @return the narrowed rendering, never {@code null}
     */
    private static String filterToPorts(String netstat, Set<String> ports) {
        List<String> lines = netstat.lines().toList();
        if (ports.isEmpty()) {
            return loopbackRows(lines);
        }
        List<String> matched = lines.stream().filter(line -> carriesAnyPort(line, ports)).toList();
        if (matched.isEmpty()) {
            return "no netstat row carries any of the ports %s (scanned %d rows)%n%s"
                    .formatted(ports, lines.size(), loopbackRows(lines));
        }
        return String.join(System.lineSeparator(), matched);
    }

    /**
     * Reads at most {@link #MAX_CAPTURE_BYTES} of a capture file into memory.
     *
     * <p>The command's stdout is redirected to a file rather than a pipe, so the child can never
     * block on a full pipe buffer and the on-disk size is bounded by {@link #COMMAND_TIMEOUT_SECONDS}
     * of writing. What was NOT bounded was the read back: a plain {@code readString} pulls the whole
     * file onto the heap before any line cap applies, so a socket-heavy host could turn this
     * diagnostic into an {@code OutOfMemoryError} — replacing the {@code TimeoutException} it exists
     * to explain with a failure of its own, which is exactly what this class promises never to do.
     *
     * <p>Truncation is stated in the returned text rather than left silent: a capture cut short
     * still reads as a capture, and a reader must be able to tell "this is all there was" from
     * "this is as much as was kept".
     *
     * <p>The truncation test compares the retained length against the file's actual size, never
     * against the cap. {@code readNBytes(cap)} returns exactly {@code cap} bytes for a file of
     * exactly that size as well as for a larger one, so testing against the cap would stamp a false
     * truncation note on a capture that was in fact complete — the same class of overclaim this
     * note exists to prevent, in the opposite direction.
     *
     * @param target the capture file
     * @return the retained content, with a truncation note appended when the cap was hit
     * @throws IOException when the file cannot be read
     */
    private static String readBounded(Path target) throws IOException {
        try (var stream = Files.newInputStream(target)) {
            byte[] retained = stream.readNBytes(MAX_CAPTURE_BYTES);
            String text = new String(retained, StandardCharsets.UTF_8);
            long total = Files.size(target);
            if (retained.length >= total) {
                return text;
            }
            return text + System.lineSeparator()
                    + "... (truncated at %d bytes of %d — the capture was larger than this diagnostic retains)"
                    .formatted(MAX_CAPTURE_BYTES, total);
        }
    }

    /**
     * Reports whether a {@code netstat} row carries a loopback address, in this platform's spelling.
     *
     * <p>Platform-aware for the same reason {@link #NETSTAT_PORT} is: macOS renders
     * {@code 127.0.0.1.59120} and Linux {@code 127.0.0.1:59120}. A macOS-only predicate applied to
     * Linux output discards every row, so this fallback — which is reached precisely when
     * {@code lsof} gave no port set to filter by — would render an empty table on Linux while
     * reporting itself as "loopback rows only".
     *
     * @param line one rendering row
     * @return {@code true} when the row names a loopback address
     */
    private static boolean isLoopbackRow(String line) {
        return IS_MACOS
                ? line.contains("127.0.0.1.") || line.contains("::1.")
                : line.contains("127.0.0.1:") || line.contains("[::1]:") || line.contains("::1:");
    }

    /**
     * The fallback view: every loopback row, capped.
     *
     * <p>Capping the <em>unfiltered</em> table instead would be actively misleading. {@code netstat}
     * renders roughly newest-socket-first, so on a machine with ordinary outbound traffic the rows
     * that matter drift past the cap within seconds and a re-sample silently loses the very pair it
     * was taken to watch. Loopback is the whole scope of the stall under investigation, so narrowing
     * to it keeps every relevant row inside the cap.
     *
     * @param lines the full rendering
     * @return the loopback rows, capped, never {@code null}
     */
    private static String loopbackRows(List<String> lines) {
        List<String> loopback = lines.stream()
                .filter(SocketSnapshot::isLoopbackRow)
                .toList();
        List<String> capped = loopback.stream().limit(MAX_FALLBACK_LINES).toList();
        String rendered = "loopback rows only (no port set to filter by):" + System.lineSeparator()
                + String.join(System.lineSeparator(), capped);
        return loopback.size() > capped.size()
                ? rendered + System.lineSeparator()
                + "... (%d further loopback rows omitted)".formatted(loopback.size() - capped.size())
                : rendered;
    }

    /**
     * Reports whether a {@code netstat} row carries one of the ports of interest.
     *
     * @param line  the row
     * @param ports the ports of interest
     * @return {@code true} when the row's local or foreign address uses one of them
     */
    private static boolean carriesAnyPort(String line, Set<String> ports) {
        return portsIn(line, NETSTAT_PORT).stream().anyMatch(ports::contains);
    }

    /**
     * Indents a block so it reads as a nested section of the failure message.
     *
     * @param text the block to indent
     * @return the indented block, never {@code null}
     */
    private static String indent(String text) {
        return text.lines().map(line -> "    " + line)
                .reduce((first, second) -> first + System.lineSeparator() + second)
                .orElse("    <empty>");
    }

    /**
     * Removes the redirect target, swallowing failure: a leftover temp file is not worth losing the
     * report over.
     *
     * @param file the file to remove
     */
    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException cause) {
            LOGGER.debug(cause, "Could not delete socket-snapshot capture %s", file);
        }
    }
}
