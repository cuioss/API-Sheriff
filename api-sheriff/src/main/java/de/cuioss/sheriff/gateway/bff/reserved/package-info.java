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
 * The gateway's reserved OIDC endpoints, carved out of the proxy route table (D2/D2c).
 * <p>
 * The BFF variants reserve four gateway-owned paths — the {@code oidc.redirect_uri} callback, the
 * RP-initiated logout path, its return leg, and the back-channel logout receiver — matched
 * exactly and only on the OIDC host. This package owns the carve-out and the callback landing:
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry} classifies an exact
 *       host+path match to a reserved endpoint. The edge consults it <em>before</em> the route
 *       table, so a proxy route such as {@code path_prefix: /auth} never swallows the exact
 *       {@code /auth/callback}.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint} handles the OIDC
 *       auth-code callback: it parses the <em>raw</em> query (BFF-13 duplicate-parameter
 *       defence), resolves the browser-bound pending-authorization record, drives the engine's
 *       code exchange and token validation, creates the server-side session, and redirects to the
 *       recorded return URL.</li>
 * </ul>
 * The classes are framework-agnostic (no CDI, no JAX-RS/Vert.x coupling), so they are
 * unit-testable without a container; the session runtime wires them to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.reserved;

import org.jspecify.annotations.NullMarked;
