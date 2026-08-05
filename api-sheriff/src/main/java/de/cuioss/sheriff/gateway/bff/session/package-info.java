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
 * The session-binding seam (D7) and its server-mode implementation (D3, {@code mode: server}).
 * <p>
 * The package is split into the mode-neutral <strong>contract</strong> the rest of the BFF binds
 * and the server-mode <strong>implementation detail</strong> behind it:
 * <ul>
 *   <li><strong>Seam.</strong> {@link de.cuioss.sheriff.gateway.bff.session.SessionBinding} is the
 *       single session-state contract the stage, the refresh coordinator, and every reserved
 *       endpoint bind — bind / resolve / persist / destroy plus the two IdP-driven destruction
 *       forms and their {@code SUPPORTED}/{@code UNSUPPORTED} capability flag. It names no store
 *       and no opaque id, so a stateless variant is representable.</li>
 *   <li><strong>Record.</strong> {@link de.cuioss.sheriff.gateway.bff.session.SessionRecord} holds
 *       the access, refresh, and raw ID tokens plus session metadata; every credential is redacted
 *       from {@code toString()}. Its {@code sessionId} is the one identity model — a stable
 *       per-session identity every binding populates.</li>
 *   <li><strong>Server-mode implementation.</strong>
 *       {@link de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding} is a thin adapter over
 *       {@link de.cuioss.sheriff.gateway.bff.session.SessionStore} (implemented only by
 *       {@link de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore} — keyed by opaque id,
 *       with secondary indexes by {@code sid}/{@code sub} for O(1) back-channel destruction, an
 *       absolute TTL enforced lazily on resolve plus an opportunistic sweep triggered when a
 *       create arrives at the max-session bound — no scheduler and no timer threads — and a
 *       documented max-session bound capping live sessions)
 *       and {@link de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec}, which sets and reads
 *       the hardened {@code __Host-} session cookie carrying only the opaque id. In this mode the
 *       token material never leaves the server.</li>
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
