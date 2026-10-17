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
 * meters and logs a rejection and records the per-request metrics, and the
 * {@link de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter} that renders it.
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
