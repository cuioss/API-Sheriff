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

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A loopback port that refuses connections and that no ephemeral bind can be handed — the upstream
 * target of every "unreachable upstream" fixture in this module.
 *
 * <h2>Why "bind, close, hope" is not enough</h2>
 * The earlier idiom bound an ephemeral listener, closed it and kept the number as "a port nothing
 * listens on". The kernel is free to hand that number to the very next ephemeral bind, and in those
 * fixtures the next ephemeral bind is the edge front server itself. When that happened, the route
 * pointing at the "dead" port proxied into the edge, which forwarded to itself hop after hop until
 * the accumulated forwarding headers tripped its own input validation. The outer edge then relayed
 * that inner {@code 400 input-validation} verbatim in place of the expected gateway-originated
 * {@code 502}.
 *
 * <h2>Why the port is not held instead</h2>
 * Holding the port with a bound socket that never listens keeps it away from other binds, but it
 * does not refuse on every platform. Linux answers a SYN to such a port with a reset. macOS matches
 * the SYN to the bound socket and silently drops it, so the dial hangs until its timeout rather than
 * failing fast — a different failure class from the one these fixtures exercise.
 *
 * <h2>What is chosen instead</h2>
 * A port <em>below the kernel's ephemeral range</em> that nothing is bound to. {@code listen(0)} and
 * the local side of every outbound connect draw only from that range, so no fixture listener, no
 * client connection and no self-connect can ever land on the chosen port, and nothing is listening
 * there to accept the dial. Every platform refuses such a dial with an immediate reset. The range
 * floor is read from {@code /proc/sys/net/ipv4/ip_local_port_range} on Linux and is the IANA
 * dynamic-port floor {@value #IANA_EPHEMERAL_FLOOR}, the macOS and BSD default, elsewhere.
 * {@code UnreachablePortTest} verifies that floor against the running kernel, so a platform
 * configured differently fails there, by name, instead of reintroducing the race.
 *
 * <p>The search runs downward from just below the floor and returns the first port a plain,
 * non-reusing loopback bind accepts, so the result is deterministic on a quiet machine.
 *
 * <p>Thread-safe: the class is stateless.
 *
 * @since 1.0
 */
public final class UnreachablePort {

    /** The IANA dynamic-port floor, which macOS and the BSDs use as their ephemeral range start. */
    static final int IANA_EPHEMERAL_FLOOR = 49_152;

    private static final Path LINUX_PORT_RANGE = Path.of("/proc/sys/net/ipv4/ip_local_port_range");
    private static final int LOWEST_UNPRIVILEGED_PORT = 1_024;
    private static final int SEARCH_SPAN = 512;

    private UnreachablePort() {
        // utility class
    }

    /**
     * Picks a loopback port below the ephemeral range that nothing is bound to.
     *
     * @return the port number, strictly below {@link #ephemeralFloor()}
     * @throws IOException if the ephemeral range cannot be read
     * @throws IllegalStateException if no port below the floor is free, or the floor leaves no
     *         unprivileged port beneath it
     */
    public static int pick() throws IOException {
        return pick(1)[0];
    }

    /**
     * Picks {@code count} distinct loopback ports below the ephemeral range that nothing is bound to,
     * for a fixture that needs several unreachable targets told apart (a circuit breaker per target,
     * for instance).
     *
     * @param count how many ports to pick, at least one
     * @return the distinct port numbers, each strictly below {@link #ephemeralFloor()}
     * @throws IOException if the ephemeral range cannot be read
     * @throws IllegalStateException if fewer than {@code count} ports below the floor are free, or the
     *         floor leaves no unprivileged port beneath it
     */
    public static int[] pick(int count) throws IOException {
        int floor = ephemeralFloor();
        int lowest = Math.max(LOWEST_UNPRIVILEGED_PORT, floor - SEARCH_SPAN);
        int[] ports = new int[count];
        int found = 0;
        for (int candidate = floor - 1; candidate >= lowest && found < count; candidate--) {
            if (isUnbound(candidate)) {
                ports[found++] = candidate;
            }
        }
        if (found < count) {
            throw new IllegalStateException("Only " + found + " of " + count + " unbound loopback ports in ["
                    + lowest + ", " + floor + "), below the ephemeral range floor " + floor);
        }
        return ports;
    }

    /**
     * @return the lowest port the running kernel hands out for an ephemeral bind
     * @throws IOException if the Linux port range file exists but cannot be read
     * @throws IllegalStateException if the Linux port range file does not hold a well-formed
     *         unprivileged range
     */
    public static int ephemeralFloor() throws IOException {
        if (!Files.isReadable(LINUX_PORT_RANGE)) {
            return IANA_EPHEMERAL_FLOOR;
        }
        // One buffered read from offset 0. Files.readString must not be used here: procfs reports a
        // size of 0, so it reads a single byte first and then reads on from offset 1, where the
        // kernel's sysctl handler answers EOF — leaving "3" of "32768 60999".
        String range;
        try (BufferedReader reader = Files.newBufferedReader(LINUX_PORT_RANGE, StandardCharsets.US_ASCII)) {
            range = String.valueOf(reader.readLine()).strip();
        }
        String[] bounds = range.split("\\s+");
        if (bounds.length != 2 || !bounds[0].matches("\\d+") || !bounds[1].matches("\\d+")) {
            throw new IllegalStateException("Malformed " + LINUX_PORT_RANGE + ": '" + range + "'");
        }
        int first = Integer.parseInt(bounds[0]);
        int last = Integer.parseInt(bounds[1]);
        if (first <= LOWEST_UNPRIVILEGED_PORT || first > last) {
            throw new IllegalStateException("Implausible ephemeral range in " + LINUX_PORT_RANGE + ": '" + range
                    + "'");
        }
        return first;
    }

    /** Binds {@code port} on loopback without {@code SO_REUSEADDR}, never listening, then releases it. */
    private static boolean isUnbound(int port) {
        try (Socket probe = new Socket()) {
            probe.setReuseAddress(false);
            probe.bind(new InetSocketAddress(LoopbackHost.ADDRESS, port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
