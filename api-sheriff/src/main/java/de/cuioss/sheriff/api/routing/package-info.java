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
 * The compiled per-route runtime model.
 * <p>
 * {@link de.cuioss.sheriff.api.routing.RouteRuntime} is the immutable, boot-assembled runtime
 * for one route; {@link de.cuioss.sheriff.api.routing.RouteMatcher} is its compiled matcher;
 * and the {@link de.cuioss.sheriff.api.routing.ProtocolProcessor} /
 * {@link de.cuioss.sheriff.api.routing.HttpProtocolProcessor} /
 * {@link de.cuioss.sheriff.api.routing.ProtocolProcessorRegistry} triad selects and shares
 * the protocol strategy, rejecting unsupported protocols at boot.
 * <p>
 * <strong>Deliberately framework-coupled (operator resolution 2026-07-19).</strong> Because
 * {@code RouteRuntime} holds the shared Vert.x {@code HttpClient} reference and the per-route
 * SmallRye Fault-Tolerance guard directly, this package is <em>excluded</em> from the ADR-0005
 * framework-agnostic arch-gate rule set (which covers {@code config.model},
 * {@code config.validation}, {@code events}, {@code forward}, and {@code pipeline} only). The
 * deviation is recorded in {@code architecture.adoc}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.api.routing;

import org.jspecify.annotations.NullMarked;
