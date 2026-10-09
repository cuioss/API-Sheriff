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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the properties the unreachable-upstream fixtures rely on: the picked port refuses a dial
 * at once, it lies below the ephemeral range, and no port is answered twice within one JVM —
 * whether by successive calls, by a multi-port pick after earlier picks, or by calls made from
 * several threads at once. The last test is the matched control for the range: it checks
 * {@link UnreachablePort#ephemeralFloor()} against the running kernel, so a platform whose range
 * differs from the assumed floor fails here, by name.
 */
@DisplayName("UnreachablePort — a refusing loopback port no ephemeral bind can be handed")
class UnreachablePortTest {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int EPHEMERAL_SAMPLES = 64;
    private static final int CONCURRENT_PICKS = 4;

    @Test
    @DisplayName("a dial to the picked port is refused, not left hanging")
    void refusesConnections() throws Exception {
        // Arrange
        InetSocketAddress target = new InetSocketAddress(LoopbackHost.ADDRESS, UnreachablePort.pick());

        try (Socket dialler = new Socket()) {
            // Act + Assert
            assertThrows(ConnectException.class, () -> dialler.connect(target, CONNECT_TIMEOUT_MILLIS));
        }
    }

    @Test
    @DisplayName("the picked port lies below the ephemeral range")
    void liesBelowTheEphemeralRange() throws Exception {
        // Act
        int port = UnreachablePort.pick();

        // Assert
        int floor = UnreachablePort.ephemeralFloor();
        assertTrue(port < floor, "port " + port + " is below the ephemeral floor " + floor);
    }

    @Test
    @DisplayName("a multi-port pick answers distinct ports")
    void multiPickAnswersDistinctPorts() throws Exception {
        // Act
        int[] ports = UnreachablePort.pick(3);

        // Assert
        assertEquals(3, Arrays.stream(ports).distinct().count(), Arrays.toString(ports));
    }

    @Test
    @DisplayName("successive picks in one JVM never answer the same port twice")
    void successivePicksAnswerDistinctPorts() throws Exception {
        int first = UnreachablePort.pick();
        int second = UnreachablePort.pick();
        int third = UnreachablePort.pick();

        assertEquals(3, IntStream.of(first, second, third).distinct().count(),
                "three picks answered " + first + ", " + second + " and " + third);
    }

    @Test
    @DisplayName("a multi-port pick answers no port an earlier pick already answered")
    void multiPickSkipsPortsAlreadyHandedOut() throws Exception {
        int[] earlier = UnreachablePort.pick(2);
        int single = UnreachablePort.pick();

        int[] later = UnreachablePort.pick(2);

        int[] all = IntStream.concat(IntStream.concat(Arrays.stream(earlier), IntStream.of(single)),
                Arrays.stream(later)).toArray();
        assertEquals(all.length, Arrays.stream(all).distinct().count(), Arrays.toString(all));
    }

    @Test
    @DisplayName("picks made from several threads at once answer distinct ports")
    void concurrentPicksAnswerDistinctPorts() throws Exception {
        List<Callable<Integer>> picks = Collections.nCopies(CONCURRENT_PICKS, UnreachablePort::pick);
        List<Integer> ports = new ArrayList<>();

        try (ExecutorService pickers = Executors.newFixedThreadPool(CONCURRENT_PICKS)) {
            for (Future<Integer> picked : pickers.invokeAll(picks)) {
                ports.add(picked.get());
            }
        }

        assertEquals(CONCURRENT_PICKS, new HashSet<>(ports).size(), ports.toString());
    }

    @Test
    @DisplayName("the running kernel never hands out an ephemeral port below the assumed floor")
    void ephemeralBindsStayAtOrAboveTheFloor() throws Exception {
        // Arrange
        int floor = UnreachablePort.ephemeralFloor();

        for (int sample = 0; sample < EPHEMERAL_SAMPLES; sample++) {
            try (ServerSocket ephemeral = new ServerSocket()) {
                // Act — the same SO_REUSEADDR ephemeral loopback bind Netty performs for listen(0, host)
                ephemeral.setReuseAddress(true);
                ephemeral.bind(new InetSocketAddress(LoopbackHost.ADDRESS, 0));

                // Assert
                int port = ephemeral.getLocalPort();
                assertTrue(port >= floor, "ephemeral port " + port + " is below the assumed floor " + floor
                        + "; UnreachablePort's range assumption does not hold on this platform");
            }
        }
    }
}
