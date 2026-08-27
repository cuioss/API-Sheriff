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
 * The Backend-for-Frontend (BFF) surface for {@code require: session} routes and its
 * cross-cutting observability vocabulary.
 * <p>
 * The confidential-client login, session, refresh, CSRF, and logout mechanics live in the
 * sub-packages ({@code bff.login}, {@code bff.session}, {@code bff.refresh}, {@code bff.csrf},
 * {@code bff.logout}, {@code bff.pending}, {@code bff.reserved}, {@code bff.runtime}). This
 * root package holds only the framework-agnostic
 * {@link de.cuioss.sheriff.gateway.bff.BffLogMessages} catalogue — the DSL-style
 * {@link de.cuioss.tools.logging.LogRecord} vocabulary for the session lifecycle, transparent
 * refresh, CSRF, and logout events those sub-packages emit. Keeping the catalogue in the
 * framework-agnostic {@code cui-tools} {@code LogRecord} abstraction carries no framework
 * dependency (ADR-0005).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.bff;

import org.jspecify.annotations.NullMarked;
