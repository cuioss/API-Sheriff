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
 * The {@code require: session} stage-4 runtime (D4) — the server-session counterpart of the offline
 * bearer validation.
 * <p>
 * {@link de.cuioss.sheriff.gateway.bff.runtime.SessionAuthenticationStage} resolves the request's
 * live session through the mode-neutral
 * {@link de.cuioss.sheriff.gateway.bff.session.SessionBinding} seam — no opaque session id appears
 * in the stage's contract, so the stage is identical for a server-side store and for a stateless
 * binding. It then offers the session to the single-flight refresh seam (the D9 hook), emits any
 * {@code Set-Cookie} the binding returns, enforces {@code required_scopes} against the mediated
 * token's granted scopes ({@code 403}), and records the mediated access token for automatic
 * upstream {@code Authorization: Bearer} injection. An unauthenticated request is
 * content-negotiated: a navigation request is redirected into the auth-code flow, everything else
 * is challenged {@code 401} {@code application/problem+json}.
 * <p>
 * The stage is framework-agnostic and driven through seams (refresh, scope membership, login
 * initiation), so it is unit-testable without a container or a live IdP; the session runtime binds
 * the seams to the engine and the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff.runtime;

import org.jspecify.annotations.NullMarked;
