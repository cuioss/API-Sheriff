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
 * </ul>
 * The classes are framework-agnostic (no CDI, no JAX-RS/Vert.x coupling) and introduce no new
 * dependency — the sealing uses the JDK's own {@code javax.crypto} AES-GCM provider.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.cookie;

import org.jspecify.annotations.NullMarked;
