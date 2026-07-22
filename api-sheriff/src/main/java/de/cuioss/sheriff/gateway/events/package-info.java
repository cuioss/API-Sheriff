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
 * Framework-agnostic event and error model for the API Sheriff gateway.
 * <p>
 * {@link de.cuioss.sheriff.gateway.events.EventCategory} groups failures by impact and names
 * the RFC 9457 problem type; {@link de.cuioss.sheriff.gateway.events.EventType} enumerates every
 * event with its category and (for request-time failures) HTTP status;
 * {@link de.cuioss.sheriff.gateway.events.GatewayEventCounter} is the lock-free in-process
 * counter feeding metrics and error mapping; and
 * {@link de.cuioss.sheriff.gateway.events.GatewayException} is the typed failure the HTTP edge
 * maps to a status and problem type.
 * <p>
 * <strong>Framework-agnostic seam (ADR-0005).</strong> This package carries no CDI, Quarkus,
 * Vert.x, MicroProfile, or Micrometer imports — enforced by
 * {@code FrameworkAgnosticArchTest}. The framework-bound rendering lives in the edge and
 * {@code quarkus} packages.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.events;

import org.jspecify.annotations.NullMarked;
