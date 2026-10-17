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
 * Boot-only route assembly: the
 * {@link de.cuioss.sheriff.gateway.edge.assembly.RouteAssembler} that completes every
 * {@link de.cuioss.sheriff.gateway.routing.Route} of the frozen
 * {@link de.cuioss.sheriff.gateway.routing.RouteTable} with its boot-attached components, and the
 * types the rest of the edge reads its results by.
 * <p>
 * <strong>Contents.</strong> The assembler with its result record, the immutable
 * {@link de.cuioss.sheriff.gateway.edge.assembly.UpstreamClients} lookup and its two keys
 * {@link de.cuioss.sheriff.gateway.edge.assembly.UpstreamTarget} and
 * {@link de.cuioss.sheriff.gateway.edge.assembly.ResilienceShape}, the
 * {@link de.cuioss.sheriff.gateway.edge.assembly.DispatchPath} protocol split, the resolved
 * {@link de.cuioss.sheriff.gateway.edge.assembly.SecurityPosture}, the four factory interfaces
 * the assembler is driven by, and the two boot-time resolvers behind two of them:
 * {@link de.cuioss.sheriff.gateway.edge.assembly.SecurityPostures}, which also resolves the
 * gateway-wide {@code Cookie} and {@code Authorization} header-value carve-outs, and
 * {@link de.cuioss.sheriff.gateway.edge.assembly.AssetSources}.
 * <p>
 * <strong>Dependencies.</strong> This package depends on no other edge package: the edge
 * sub-packages that list it as a dependency (see the dependency table of ADR-0068) read it, and it
 * reads none of them.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.assembly;

import org.jspecify.annotations.NullMarked;
