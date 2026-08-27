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
 * The confidential-client login initiation for the BFF auth-code flow (D1/D2/D2b).
 * <p>
 * {@link de.cuioss.sheriff.gateway.bff.login.LoginFlow} is the browser-facing start of the OIDC
 * code flow and the mirror of the callback endpoint: it drives the engine to build the
 * authorization URL and the transaction {@code FlowContext} (PKCE/state/nonce owned by the engine,
 * never re-implemented), persists the context as a single-use pending-authorization record, sets
 * the {@code __Host-} browser-binding cookie, and redirects the browser to the IdP. The post-login
 * return URL is same-origin-validated before it is recorded, so the eventual redirect is never an
 * open redirect.
 * <p>
 * The class is framework-agnostic (no CDI, no JAX-RS/Vert.x coupling) and reaches the engine
 * through a seam, so it is unit-testable without a live IdP; the session runtime wires it to the
 * request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.login;

import org.jspecify.annotations.NullMarked;
