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
 * The per-route model.
 * <p>
 * {@link de.cuioss.sheriff.gateway.routing.Route} is the one immutable per-route type — every
 * resolved value, its pure derivations and the boot-attached collaborators;
 * {@link de.cuioss.sheriff.gateway.routing.RouteTable} is the exact-first then longest-prefix-ordered
 * table of them; {@link de.cuioss.sheriff.gateway.routing.RouteMatcher} is a route's compiled
 * matcher; and the {@link de.cuioss.sheriff.gateway.routing.ProtocolProcessor} /
 * {@link de.cuioss.sheriff.gateway.routing.HttpProtocolProcessor} /
 * {@link de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry} triad selects and shares
 * the protocol strategy, rejecting unsupported protocols at boot.
 * <p>
 * <strong>Framework-free.</strong> The pre-boot route table, which the offline
 * {@code --validate-config} check also builds (ADR-0061), is built from
 * {@link de.cuioss.sheriff.gateway.routing.Route}, so this package holds no Vert.x or SmallRye type
 * (ADR-0062). The shared upstream client and the resilience guard are kept by the edge, outside the
 * route: a client per upstream target, a guard per resilience shape (that target plus the route's
 * retry setting).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.routing;

import org.jspecify.annotations.NullMarked;
