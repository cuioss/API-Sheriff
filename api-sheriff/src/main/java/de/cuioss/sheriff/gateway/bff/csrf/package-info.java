/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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
 * The fixed CSRF defence for {@code require: session} routes (D7).
 * <p>
 * {@link de.cuioss.sheriff.gateway.bff.csrf.CsrfDefence} is fixed behaviour with no disable knob:
 * on unsafe methods (everything except {@code GET} / {@code HEAD} / {@code OPTIONS}) it accepts only
 * an {@code Origin} whose exact full origin (scheme + host + port) is in the trusted-origin set
 * ({@code session.csrf.trusted_origins}, defaulting to the {@code redirect_uri} origin) or, when
 * {@code Origin} is absent, a {@code Sec-Fetch-Site: same-origin} header; everything else is rejected
 * {@code 403} with a {@link de.cuioss.sheriff.gateway.events.EventType#CSRF_REJECTED} event.
 * <p>
 * The class is framework-agnostic and immutable; the session runtime wires it into the request edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.csrf;

import org.jspecify.annotations.NullMarked;
