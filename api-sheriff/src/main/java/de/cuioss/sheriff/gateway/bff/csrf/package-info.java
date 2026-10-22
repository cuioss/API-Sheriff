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
 * The gateway's one same-origin proof for unsafe-method browser requests,
 * {@link de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence}, used in two places:
 * <ul>
 *   <li>as the <strong>fixed CSRF defence of {@code require: session} routes</strong> (D7), with no
 *       disable knob, trusting {@code session.csrf.trusted_origins} (defaulting to the
 *       {@code redirect_uri} origin); and</li>
 *   <li>as the <strong>per-route same-origin check of a cookie-forwarding route</strong>, trusting the
 *       origin the request was received on plus the route's {@code cookies.additional_origins}, asked
 *       for only on an unsafe request that carries a cookie the route would forward.</li>
 * </ul>
 * <p>
 * On unsafe methods (everything except {@code GET} / {@code HEAD} / {@code OPTIONS}) both uses accept
 * only an {@code Origin} whose full origin (scheme + host + port) is trusted or, when {@code Origin} is
 * absent, a {@code Sec-Fetch-Site: same-origin} header; everything else is rejected {@code 403} with a
 * {@link de.cuioss.sheriff.gateway.events.EventType#CSRF_REJECTED} event. A rejection is recorded by
 * that event counter and a {@code DEBUG} diagnostic only; no {@code INFO} / {@code WARN} log record
 * is written for it.
 * <p>
 * The class is framework-agnostic and immutable; the session runtime wires the session instance into
 * the request edge, and route-table assembly builds one cookie-forwarding instance per qualifying
 * route.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.csrf;

import org.jspecify.annotations.NullMarked;
