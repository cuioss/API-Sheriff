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
 * Rejection metering, logging and rendering: how a request the edge rejects is counted, logged and
 * answered — as an RFC 9457 problem document, a trailers-only gRPC status, or the negotiated HTML
 * error page of the portal.
 * <p>
 * <strong>Contents.</strong> The {@link de.cuioss.sheriff.gateway.edge.error.RejectionReporter} that
 * meters and logs a rejection and records the per-request metrics, the
 * {@link de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter} that renders it, and the
 * {@link de.cuioss.sheriff.gateway.edge.error.UniformNotFound} predicate behind the renderer's
 * trust-boundary branch.
 * <p>
 * <strong>The trust-boundary branch.</strong> A rejection raised after route selection and before
 * the trust boundary — a validated bearer or a completed stage 4 — for an event of the closed set
 * {@link de.cuioss.sheriff.gateway.edge.error.UniformNotFound#CLOSED_SET} is answered to the caller
 * exactly as an unrouted address is answered: the unrouted {@code 404}, its headers and its
 * negotiated HTML page, never a trailers-only gRPC status. The rejection is still metered and logged
 * under its true event; only the answer changes (ADR-0073).
 * <p>
 * <strong>Dependencies.</strong> This package may depend on the edge packages {@code end},
 * {@code upstream}, {@code relay} and {@code request}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.error;

import org.jspecify.annotations.NullMarked;
