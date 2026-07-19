/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
 * {@link de.cuioss.sheriff.api.edge.RouteRuntimeAssembler} that compiles the frozen route
 * table into immutable {@link de.cuioss.sheriff.api.routing.RouteRuntime} instances with
 * deduplicated heavy collaborators.
 * <p>
 * This package is framework-coupled (Vert.x, SmallRye Fault-Tolerance) and is therefore
 * outside the ADR-0005 framework-agnostic arch-gate rule set.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.api.edge;

import org.jspecify.annotations.NullMarked;
