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
package de.cuioss.sheriff.gateway.arch.specimen;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;


import de.cuioss.sheriff.gateway.testsupport.LoopbackHost;
import io.vertx.core.Future;
import io.vertx.core.net.NetServer;

/**
 * Standing <strong>matched positive control</strong> for the wildcard-ephemeral-bind guard in
 * {@code LoopbackEphemeralBindArchTest}: the deliberate near-miss.
 * <p>
 * It calls the <em>same two APIs</em> as {@link WildcardEphemeralBindSpecimen}, on the same
 * ephemeral port {@code 0}, differing only in the one thing the guard is allowed to see — the
 * overload signature carries a host argument. Without this control a rule that failed on
 * <em>every</em> bind would satisfy the negative control just as well; this is what proves the
 * guard discriminates between the bare and the host-bound overload rather than banning binding.
 * <p>
 * Both sites bind {@link LoopbackHost#ADDRESS}, which is also what every real live-socket fixture
 * in this module does — so the control is a faithful stand-in for compliant code rather than a
 * shape invented for the test.
 * <p>
 * <strong>Neither method is ever invoked</strong>; ArchUnit reads bytecode, so the call sites only
 * have to exist.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LoopbackEphemeralBindSpecimen {

    /**
     * The compliant {@code java.net} spelling: the host-bound
     * {@link ServerSocket#ServerSocket(int, int, InetAddress)} constructor on an ephemeral port.
     *
     * @return a loopback-bound ephemeral server socket; the caller owns closing it
     * @throws IOException when the bind fails or the loopback address cannot be resolved
     */
    public ServerSocket bindLoopbackViaServerSocket() throws IOException {
        return new ServerSocket(0, 1, InetAddress.getByName(LoopbackHost.ADDRESS));
    }

    /**
     * The compliant Vert.x spelling: the host-bound {@code listen(int, String)} overload on an
     * ephemeral port.
     *
     * @param server the server to bind; supplied by the caller so this specimen never creates a
     *               {@code Vertx} instance
     * @return the listen future, never completed because this method is never invoked
     */
    public Future<NetServer> bindLoopbackViaListen(NetServer server) {
        return server.listen(0, LoopbackHost.ADDRESS);
    }
}
