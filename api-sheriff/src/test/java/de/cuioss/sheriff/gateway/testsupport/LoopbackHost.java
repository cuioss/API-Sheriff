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

/**
 * The single owner of the loopback address this module's live-socket fixtures bind to and dial.
 *
 * <p><strong>Scope, stated precisely.</strong> This covers every fixture the loopback-bind guard
 * selects — which is the module's test tree minus one class. {@code TlsEdgeProducerTest} is a
 * documented class-level carve-out and deliberately keeps wildcard-bound sockets: collision holders
 * that occupy a port so production's own wildcard bind is refused, and the {@code freePort()}
 * probes. Those bind wildcard on purpose and must not route through this constant, so "every
 * fixture" would be an overclaim: {@code LoopbackEphemeralBindArchTest} excludes that class
 * entirely and enforces nothing inside it.
 *
 * <h2>Why one constant rather than a literal per site</h2>
 * A fixture that binds its ephemeral server with the single-argument {@code listen(0)} overload gets
 * the <em>dual-stack wildcard</em>, while the client in that same fixture dials loopback. That
 * mismatch is the measured exposure: a wildcard ephemeral bind is reachable from every interface, so
 * the kernel may hand the port to — or accept a connection from — something other than the fixture's
 * own client. Binding the listener to {@link #ADDRESS} and dialling the same {@code ADDRESS} closes
 * the mismatch by construction.
 *
 * <p>Routing every site through one constant is what makes that property <em>checkable</em>. A
 * per-file literal is invisible to a content sweep the moment someone spells it {@code "localhost"}
 * or re-introduces a bare bind; a named constant leaves exactly one definition to read and one
 * symbol to search for. The measured mechanism and the evidence behind it are written up in
 * {@code doc/development/build-gate-discipline.adoc}.
 *
 * <p>This is deliberately <em>not</em> named {@code Test*}: Surefire's default includes match
 * {@code **}{@code /Test*.java}, so that spelling would make the constant holder itself a test
 * class. It also deliberately lives in {@code testsupport} rather than inside any one feature
 * package, because every feature package's fixtures need it.
 *
 * <p>Thread-safe: the class is stateless and its only member is a compile-time constant.
 *
 * @since 1.0
 */
public final class LoopbackHost {

    /**
     * The loopback address every fixture in this module binds its ephemeral listener to and dials.
     *
     * <p>Deliberately the numeric literal rather than {@code "localhost"}: the name resolves through
     * the platform resolver and can yield either an IPv4 or an IPv6 answer, which re-introduces the
     * very bind/dial family mismatch this constant exists to remove.
     */
    public static final String ADDRESS = "127.0.0.1";

    private LoopbackHost() {
        // utility class
    }
}
