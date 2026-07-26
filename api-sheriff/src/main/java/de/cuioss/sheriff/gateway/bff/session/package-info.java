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
 * The server-side session store and its opaque cookie (D3, {@code mode: server}).
 * <p>
 * After a successful IdP login the gateway holds the mediated tokens server-side and hands the
 * browser only an opaque handle:
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.session.SessionRecord} holds the access, refresh,
 *       and raw ID tokens plus session metadata; token material never leaves the server and is
 *       redacted from {@code toString()}.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.session.SessionStore} is the store contract, and
 *       {@link de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore} its only
 *       implementation — keyed by opaque id, with secondary indexes by {@code sid}/{@code sub}
 *       for O(1) back-channel destruction, an absolute TTL enforced lazily plus by a periodic
 *       sweep (no per-session timer threads), and a documented max-session bound.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec} sets and reads the
 *       hardened {@code __Host-} session cookie carrying only the opaque id.</li>
 * </ul>
 * The classes are framework-agnostic (no CDI, no JAX-RS/Vert.x coupling); the runtime and
 * reserved-endpoint packages wire them to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.session;

import org.jspecify.annotations.NullMarked;
