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
 * Transient pre-login state for the BFF auth-code flow (D2b).
 * <p>
 * Between the redirect to the IdP and the callback — before any session exists — the gateway
 * must hold the OIDC transaction and tie it to the browser that started it. This package owns
 * that concern without re-inventing any engine control:
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord} wraps the
 *       engine's {@code FlowContext} (which owns {@code state}/{@code nonce}/PKCE/{@code
 *       redirect_uri}) and adds the gateway-only fields the engine leaves open: a short fixed
 *       TTL, single-use semantics, and a same-origin-validated post-login return URL.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore} is the pluggable
 *       storage seam — a bounded, single-use, TTL-expiring in-memory map for server mode, with
 *       room for the PLAN-08 cookie-sealed variant.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec} sets and reads the
 *       short-lived {@code __Host-} browser-binding cookie carrying the record id, so a callback
 *       replayed in a different browser is rejected even with a valid {@code state}.</li>
 * </ul>
 * The classes are framework-agnostic (no CDI, no JAX-RS/Vert.x coupling); the reserved-endpoint
 * and login-flow packages wire them to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.pending;

import org.jspecify.annotations.NullMarked;
