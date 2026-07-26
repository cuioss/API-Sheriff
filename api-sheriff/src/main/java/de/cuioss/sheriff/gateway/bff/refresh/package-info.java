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
 * Transparent token refresh and RFC 9470 step-up orchestration for {@code require: session}
 * routes (D7).
 * <p>
 * The gateway re-implements <strong>no</strong> OAuth leg: the engine
 * ({@code token-sheriff-client}) owns the refresh grant with refresh-token rotation and reuse
 * detection, and owns the RFC 9470 challenge grammar and the step-up authorization-request
 * construction. This package holds only the gateway-side orchestration, framework-agnostically (no
 * CDI, no JAX-RS/Vert.x coupling), so every class is unit-testable without a container or a live
 * IdP:
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.refresh.TokenRefreshCoordinator} refreshes the mediated
 *       token within its expiry leeway through the engine, <em>single-flighted per session</em> so
 *       concurrent requests on one session share one refresh; a refresh failure (IdP rejection or
 *       engine-detected refresh-token reuse revoking the family) destroys the session so the caller
 *       treats the request as unauthenticated (navigation re-driven through login, any other request
 *       answered {@code 401 application/problem+json}).</li>
 *   <li>{@link de.cuioss.sheriff.gateway.bff.refresh.StepUpCoordinator} parses an upstream
 *       {@code insufficient_user_authentication} challenge, attempts silent satisfaction, and
 *       otherwise re-drives the auth-code flow with the challenge's elevated {@code acr_values} /
 *       {@code max_age} — persisting the re-drive transaction as a single-use pending-authorization
 *       record with a same-origin-validated replay target and a browser-binding cookie.</li>
 * </ul>
 * Both coordinators reach the engine through functional-interface seams and return framework-agnostic
 * outcome records; the session runtime binds the seams (confidential-client wiring) and performs the
 * request/response edge (the content negotiation, the redirect, the request replay).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.refresh;

import org.jspecify.annotations.NullMarked;
