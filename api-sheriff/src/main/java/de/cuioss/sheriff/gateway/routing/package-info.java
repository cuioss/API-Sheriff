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
 * The compiled per-route runtime model.
 * <p>
 * {@link de.cuioss.sheriff.gateway.routing.RouteRuntime} is the immutable, boot-assembled runtime
 * for one route; {@link de.cuioss.sheriff.gateway.routing.RouteMatcher} is its compiled matcher;
 * and the {@link de.cuioss.sheriff.gateway.routing.ProtocolProcessor} /
 * {@link de.cuioss.sheriff.gateway.routing.HttpProtocolProcessor} /
 * {@link de.cuioss.sheriff.gateway.routing.ProtocolProcessorRegistry} triad selects and shares
 * the protocol strategy, rejecting unsupported protocols at boot.
 * <p>
 * <strong>Framework-coupled by design.</strong> {@code RouteRuntime} holds the shared Vert.x
 * {@code HttpClient} reference and the per-route SmallRye Fault-Tolerance guard directly. The
 * framework-free gate covers the pre-boot configuration packages only (ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.routing;

import org.jspecify.annotations.NullMarked;
