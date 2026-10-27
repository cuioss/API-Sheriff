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
 * The one end rule for a response and the early-answer path (ADR-0066): how a response the edge
 * writes is ended, how an HTTP/2 response answered while its request body is still open is
 * ended once request data stops arriving or a time cap passes, how an answer is held back until a
 * release time without holding a thread, and how an HTTP/1.x connection whose request boundaries can
 * no longer be trusted is retired.
 * <p>
 * <strong>Contents.</strong> {@link de.cuioss.sheriff.gateway.edge.end.ResponseEnds}, holding the one
 * end rule every response end passes and the response-writing helpers every writer shares, the
 * {@link de.cuioss.sheriff.gateway.edge.end.ResponseEnd} seam a relay ends its response through, the
 * {@link de.cuioss.sheriff.gateway.edge.end.ResponseDelay} delay primitive that writes an answer no
 * earlier than its release time from a Vert.x timer — the uniform {@code 404}'s response-timing floor
 * writes through it — the package-private {@code EarlyResponseEnd} that ends an early answer once
 * request data stops arriving or a time cap passes, the package-private {@code InboundStreamWindow} it
 * reads the stream's transport state from, and the
 * {@link de.cuioss.sheriff.gateway.edge.end.ConnectionRetirement} that retires a connection after a
 * framing rejection or a reserved-body {@code 413}.
 * <p>
 * <strong>Dependencies.</strong> This package may depend on
 * {@link de.cuioss.sheriff.gateway.edge.upstream} only.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.end;

import org.jspecify.annotations.NullMarked;
