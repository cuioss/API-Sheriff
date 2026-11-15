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
import java.net.ServerSocket;


import io.vertx.core.Future;
import io.vertx.core.net.NetServer;

/**
 * Standing <strong>negative control</strong> for the wildcard-ephemeral-bind guard in
 * {@code LoopbackEphemeralBindArchTest}: a class that deliberately calls both bare bind spellings,
 * so the guard has a known violation of each to detect.
 * <p>
 * <strong>Both spellings are present on purpose.</strong> The exposure class is the bind
 * <em>overload signature</em>, not one API — a guard written against {@code listen(int)} alone
 * would leave {@code new ServerSocket(int)} free to reintroduce the same dual-stack wildcard bind
 * under a different name. Carrying both here is what makes a one-sided guard fail this control
 * rather than pass it.
 * <p>
 * <strong>Neither method is ever invoked.</strong> ArchUnit reads bytecode, so the call sites only
 * have to <em>exist</em>. Both take their collaborator as a parameter rather than constructing one,
 * which keeps the specimen from standing up a {@code Vertx} instance or holding a real port.
 * <p>
 * Its matched positive counterpart is {@link LoopbackEphemeralBindSpecimen}, which carries the same
 * two shapes in their host-bound form. The pair is what proves the guard <em>discriminates</em>
 * between the bare and the host-bound overload rather than merely always-failing.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class WildcardEphemeralBindSpecimen {

    /**
     * The deliberate violation in its {@code java.net} spelling: the single-int
     * {@link ServerSocket#ServerSocket(int)} constructor, which binds the dual-stack wildcard.
     * <p>
     * <strong>Must stay the single-argument form.</strong> Widening it to
     * {@code ServerSocket(int, int, InetAddress)} would silently disarm this half of the control —
     * the guard would stop reporting a violation and {@code assertThrows} would fail, which is the
     * loud failure this constraint exists to keep loud.
     *
     * @return a wildcard-bound ephemeral server socket; the caller owns closing it
     * @throws IOException when the bind fails
     */
    public ServerSocket bindWildcardViaServerSocket() throws IOException {
        return new ServerSocket(0);
    }

    /**
     * The deliberate violation in its Vert.x spelling: the single-int {@code listen(int)} overload,
     * which binds the dual-stack wildcard.
     * <p>
     * <strong>Must stay the single-argument form</strong>, for the same reason as
     * {@link #bindWildcardViaServerSocket()}.
     *
     * @param server the server to bind; supplied by the caller so this specimen never creates a
     *               {@code Vertx} instance
     * @return the listen future, never completed because this method is never invoked
     */
    public Future<NetServer> bindWildcardViaListen(NetServer server) {
        return server.listen(0);
    }

    /**
     * The deliberate violation the <em>bytecode</em> rule structurally cannot see: the host-bound
     * {@code listen(int, String)} overload passed a wildcard host literal. At the call-target level
     * this is indistinguishable from {@code listen(0, LoopbackHost.ADDRESS)} — same name, same
     * parameter types — so only a source-level sweep can tell them apart.
     * <p>
     * It is the standing positive control for that sweep: the sweep must FIND this line. A sweep
     * that stopped matching would otherwise report zero offenders across the tree and read exactly
     * like a clean one.
     *
     * @param server the server to bind; supplied by the caller so this specimen never creates a
     *               {@code Vertx} instance
     * @return the listen future, never completed because this method is never invoked
     */
    public Future<NetServer> bindWildcardHostViaListen(NetServer server) {
        return server.listen(0, "0.0.0.0");
    }

    /**
     * The same violation with its arguments <em>wrapped across lines</em>. A sweep that scanned one
     * physical line at a time would miss this while still finding
     * {@link #bindWildcardHostViaListen(NetServer)} — and would therefore report a clean tree while
     * the bypass sat open, since the bytecode rule cannot see this shape either.
     * <p>
     * Kept formatted exactly as written, and that is enforced rather than merely asked for:
     * {@code LoopbackEphemeralBindArchTest} carries a shape-specific assertion requiring this call
     * to still span multiple physical lines. The violation-count assertion cannot serve that
     * purpose — joining the call leaves the count unchanged and still passes — so a join would
     * otherwise retire this coverage silently.
     *
     * @param server the server to bind; supplied by the caller so this specimen never creates a
     *               {@code Vertx} instance
     * @return the listen future, never completed because this method is never invoked
     */
    public Future<NetServer> bindWildcardHostAcrossLines(NetServer server) {
        return server.listen(
                0,
                "0.0.0.0");
    }

    /**
     * The same violation with a <em>non-literal port</em>. The exposure is decided entirely by the
     * host argument, so this is the identical defect — and a sweep keyed on a numeric port literal
     * would miss it while the bytecode rule accepts it too, since the target is still the compliant
     * {@code listen(int, String)}.
     *
     * @param server the server to bind; supplied by the caller so this specimen never creates a
     *               {@code Vertx} instance
     * @param port   the port, deliberately a variable rather than a literal
     * @return the listen future, never completed because this method is never invoked
     */
    public Future<NetServer> bindWildcardHostWithVariablePort(NetServer server, int port) {
        return server.listen(port, "0.0.0.0");
    }

    /**
     * The same violation with the port supplied by a <em>nested call</em>. A sweep whose argument
     * class simply excluded parentheses would stop at the {@code (} and miss this, while the
     * bytecode rule accepts it for the usual reason — the target is still
     * {@code listen(int, String)}.
     *
     * @param server the server to bind; supplied by the caller so this specimen never creates a
     *               {@code Vertx} instance
     * @return the listen future, never completed because this method is never invoked
     */
    public Future<NetServer> bindWildcardHostWithNestedPortCall(NetServer server) {
        return server.listen(ephemeralPort(), "0.0.0.0");
    }

    /**
     * A stand-in port source, present only so the nested-call specimen above has a real call to
     * nest. Never invoked.
     *
     * @return zero, the ephemeral-port sentinel
     */
    private static int ephemeralPort() {
        return 0;
    }

}
