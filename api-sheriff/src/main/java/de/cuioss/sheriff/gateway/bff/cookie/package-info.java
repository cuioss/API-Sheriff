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
 * The stateless cookie-mode session binding (D1, {@code session.mode: cookie}) — the second
 * implementation of {@link de.cuioss.sheriff.gateway.bff.session.SessionBinding}, alongside the
 * server-mode store adapter.
 * <p>
 * The gateway holds <strong>no</strong> server-side session state in this mode: the mediated token
 * set is sealed into the session cookie itself and read back per request.
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.cookie.SealedSessionPayload} is the record that gets
 *       sealed — the token material, the identity/session claims, and the absolute login instant
 *       that anchors the server-enforced TTL. Every credential-bearing component is redacted from
 *       {@code toString()}.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec} performs the
 *       AES-256-GCM sealing: a fresh 96-bit random nonce per seal, with the format version, the
 *       key id, and the cookie name bound into the GCM associated data. Any tampering, truncation,
 *       unknown version, or unknown key id unseals to "no session" — never a server error.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.cookie.CookieSessionBinding} is the seam
 *       implementation over the codec. It reports
 *       {@link de.cuioss.sheriff.gateway.bff.session.SessionBinding.IdpDestruction#UNSUPPORTED}
 *       because a stateless gateway holds no index to destroy another browser's session through.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec} seals the
 *       <em>activity cookie</em>: the session's derived identity and the instant of its last access,
 *       under a key that exists for this cookie alone, with a format version of its own.</li>
 * </ul>
 * <strong>Idle timeout.</strong> A session ends at its absolute lifetime and, earlier, when it has not
 * been accessed for {@code oidc.session.idle_timeout_seconds}. A stateless gateway can remember the
 * last access only in the browser, and it does so in the activity cookie, named after the session
 * cookie with the suffix {@code -activity}. A request let through on a session-protected route gets a
 * new activity cookie when the last one is at least a minute old, or half the idle timeout where that
 * is shorter, so an access can be recorded before every idle deadline. <strong>The session cookie is not
 * rewritten on access</strong>: the token-bearing value changes only at login, refresh and widening,
 * so recording an access cannot race a refresh, and the sealed session format is unchanged. An
 * activity cookie that is missing, unreadable, forged or bound to another session never extends a
 * session — idleness is then measured from the login instant.
 * <p>
 * The classes carry no CDI and no JAX-RS/Vert.x coupling and introduce no new dependency — the
 * sealing uses the JDK's own {@code javax.crypto} AES-GCM provider.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.cookie;

import org.jspecify.annotations.NullMarked;
