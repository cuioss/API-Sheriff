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
/**
 * The framework edge's entry point: {@link de.cuioss.sheriff.gateway.edge.GatewayEdgeRoute}, the
 * Vert.x-bound catch-all route that admits every inbound request, runs it through the fixed pipeline
 * on a virtual thread and hands its terminal action off. It holds the wiring and the per-request flow
 * only; every job of the edge lives in one of nine sub-packages:
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.assembly} — boot-only route assembly: the
 *       {@link de.cuioss.sheriff.gateway.edge.assembly.RouteAssembler} that completes every
 *       {@link de.cuioss.sheriff.gateway.routing.Route} of the frozen
 *       {@link de.cuioss.sheriff.gateway.routing.RouteTable} and holds the deduplicated upstream
 *       clients and resilience guards in an immutable lookup, the security postures and the asset
 *       sources;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.upstream} — guarded upstream dispatch and its failure
 *       mapping, the egress clients and the upstream guards;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.end} — the one end rule every response passes, the
 *       early-answer path (ADR-0066) and connection retirement;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.relay} — streaming an upstream answer back to the
 *       client;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.admission} — transport and admission bounds, admission
 *       and the drain on shutdown;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.request} — turning the Vert.x request into the
 *       pipeline request;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.error} — rejection metering, logging and
 *       rendering;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.reserved} — the reserved OIDC paths, the portal and the
 *       reserved-body read;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.terminal} — serving a route's terminal action.</li>
 * </ul>
 * <p>
 * <strong>Dependency rule.</strong> The sub-packages form an acyclic graph, and none of them depends
 * on this package: {@code assembly}, {@code request} and {@code admission} depend on no edge package;
 * {@code upstream} on {@code assembly}; {@code end} on {@code upstream}; {@code relay} on {@code end}
 * and {@code upstream}; {@code error} on {@code end}, {@code upstream}, {@code relay} and
 * {@code request}; {@code reserved} on {@code error}, {@code end} and {@code request};
 * {@code terminal} on {@code relay}, {@code upstream}, {@code error}, {@code end}, {@code admission},
 * {@code request} and {@code assembly}. This package depends on all nine.
 * <p>
 * The edge is framework-coupled (Vert.x, SmallRye Fault-Tolerance) by design. The framework-free gate
 * does not cover it (ADR-0062): it holds the framework collaborators so the route type itself can stay
 * framework-free.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge;

import org.jspecify.annotations.NullMarked;
