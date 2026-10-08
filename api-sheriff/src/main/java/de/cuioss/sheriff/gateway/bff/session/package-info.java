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
 * The session-binding seam (D7) and its server-mode implementation (D3, {@code mode: server}).
 * <p>
 * The package is split into the mode-neutral <strong>contract</strong> the rest of the BFF binds
 * and the server-mode <strong>implementation detail</strong> behind it:
 * <ul>
 *   <li><strong>Seam.</strong> {@link de.cuioss.sheriff.gateway.bff.session.SessionBinding} is the
 *       single session-state contract the stage, the refresh coordinator, and every reserved
 *       endpoint bind — bind / resolve / the two updating writes / the access notification /
 *       destroy plus the two IdP-driven destruction forms and their
 *       {@code SUPPORTED}/{@code UNSUPPORTED} capability flag. It names no store and no opaque id, so
 *       a stateless variant is representable.
 *       <ul>
 *         <li><em>The creating write.</em> {@code bind} is the login's write and the only one that
 *             creates a session.</li>
 *         <li><em>The two updating writes.</em> {@code persist} updates a session that already
 *             exists — the refresh's write. {@code persistReissuingCookie} does the same and
 *             additionally replaces the cookie value the browser holds — the write a step-up or a
 *             scope widening makes. Both report the session gone instead of writing when the
 *             implementation can observe that it was destroyed.</li>
 *         <li><em>The two deadlines.</em> A session ends at its absolute lifetime and, earlier, when
 *             it has not been accessed for the idle timeout
 *             ({@code oidc.session.idle_timeout_seconds}). {@code resolve} enforces both and extends
 *             neither; only {@code recordAccess}, which the session stage calls for a request it
 *             lets through to a session-protected route, moves the idle deadline.</li>
 *       </ul></li>
 *   <li><strong>Record.</strong> {@link de.cuioss.sheriff.gateway.bff.session.SessionRecord} holds
 *       the access, refresh, and raw ID tokens plus session metadata; every credential is redacted
 *       from {@code toString()}. Its {@code sessionId} is the one identity model — a stable
 *       per-session identity every binding populates, and never the cookie value.</li>
 *   <li><strong>Server-mode implementation.</strong>
 *       {@link de.cuioss.sheriff.gateway.bff.session.ServerSessionBinding} is a thin adapter over
 *       {@link de.cuioss.sheriff.gateway.bff.session.SessionStore} (implemented only by
 *       {@link de.cuioss.sheriff.gateway.bff.session.InMemorySessionStore}) and
 *       {@link de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec}, which sets and reads the
 *       hardened {@code __Host-} session cookie. In this mode the token material never leaves the
 *       server.
 *       <ul>
 *         <li><em>The cookie handle.</em> The store is keyed by the stable session id; the cookie
 *             carries a separate opaque handle the store resolves to that id. Re-issuing the cookie
 *             swaps the handle in the same atomic step that replaces the record, and the previous
 *             handle resolves nothing afterwards. The session id, the {@code sid}/{@code sub}
 *             indexes and the absolute expiry are untouched by it.</li>
 *         <li><em>The writes.</em> Every write is one method under the store's single monitor and
 *             therefore atomic with every destroy, so a destroyed session is not written back. The
 *             last access is written as an instant beside the record, never as a record, so it
 *             cannot overwrite the tokens a concurrent refresh stored.</li>
 *         <li><em>The sweep.</em> Expiry is enforced lazily on resolve, and a sweep removes every
 *             expired session. The session runtime runs the sweep as a periodic task, and a
 *             capacity-consuming create runs it on reaching the max-session bound, which caps live
 *             sessions. The store itself starts no thread and no timer.</li>
 *       </ul></li>
 * </ul>
 * The classes carry no CDI and no JAX-RS/Vert.x coupling; the runtime and reserved-endpoint packages
 * wire them to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.session;

import org.jspecify.annotations.NullMarked;
