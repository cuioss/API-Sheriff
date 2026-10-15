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
 * The framework edge: the Vert.x-bound catch-all route, edge hardening, streamed upstream
 * dispatch / response, and the boot-time
 * {@link de.cuioss.sheriff.gateway.edge.RouteAssembler} that completes every
 * {@link de.cuioss.sheriff.gateway.routing.Route} of the frozen
 * {@link de.cuioss.sheriff.gateway.routing.RouteTable} with its boot-attached components and holds
 * the deduplicated upstream clients and resilience guards in an immutable lookup beside the routes.
 * <p>
 * This package is framework-coupled (Vert.x, SmallRye Fault-Tolerance) by design. The
 * framework-free gate does not cover it (ADR-0062): it holds the framework collaborators so the
 * route type itself can stay framework-free.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge;

import org.jspecify.annotations.NullMarked;
