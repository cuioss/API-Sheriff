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
 * The gateway-side logout logic for both OIDC logout channels (D5, BFF-09).
 * <p>
 * The gateway re-implements <strong>no</strong> OAuth leg: the engine
 * ({@code token-sheriff-client}) owns the {@code end_session_endpoint} redirect construction, the
 * {@code id_token_hint}, and the exact-match {@code post_logout_redirect_uri} open-redirect defence.
 * This package owns only the gateway-side concerns, framework-agnostically (no CDI, no JAX-RS/Vert.x
 * coupling), so every class is unit-testable without a container or a live IdP:
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout} orchestrates RP-initiated
 *       logout — it revokes the mediated tokens (RFC 7009, best-effort), mints a session-bound
 *       {@code state}, carries it in the short-lived single-use {@code __Host-sheriff-logout} cookie,
 *       and on the return leg verifies the returned {@code state} against that cookie before landing
 *       the browser on {@code final_redirect}.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver} receives an OIDC
 *       back-channel {@code logout_token}: it signature-verifies the token through the engine's
 *       issuer/JWKS infrastructure, runs the claim residual, then destroys the affected sessions
 *       through the store's O(1) secondary index.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.logout.LogoutTokenValidator} is the pure OIDC
 *       back-channel-logout-token check pipeline the receiver applies after signature verification —
 *       the gateway-side residual the engine library does not carry.</li>
 * </ul>
 * The {@code reserved} package's {@code LogoutEndpoint} and {@code BackchannelLogoutEndpoint} own the
 * request/response edge and the session store; this package holds only the transport-free logic.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.logout;

import org.jspecify.annotations.NullMarked;
