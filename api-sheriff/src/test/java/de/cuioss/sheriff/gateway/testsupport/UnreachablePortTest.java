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
import java.util.Arrays;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the properties the unreachable-upstream fixtures rely on: the picked port refuses a dial
 * at once, and it lies below the ephemeral range. The last test is the matched control for that
 * range: it checks {@link UnreachablePort#ephemeralFloor()} against the running kernel, so a
 * platform whose range differs from the assumed floor fails here, by name.
 */
@DisplayName("UnreachablePort — a refusing loopback port no ephemeral bind can be handed")
class UnreachablePortTest {

    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int EPHEMERAL_SAMPLES = 64;

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
