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
 * Cross-cutting configuration validation for the boot pipeline.
 * <p>
 * {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator} is the orchestrator of
 * ONE ordered list of {@link de.cuioss.sheriff.gateway.config.validation.rule.ValidationRule}s:
 * the semantic rules the structural JSON Schemas cannot express — endpoint and route id
 * uniqueness, alias resolvability for enabled endpoints, effective-auth dependencies,
 * effective allowed-method membership, forwarded trust-all rejection, CORS
 * wildcard-with-credentials rejection, session-mode conditionals and the rest. It runs every
 * entry in list order and collects every violation in a single pass; the list is the only place
 * that decides the reporting order. The rule bodies live in the concern classes of the
 * {@code rule} package or, for a concern not yet extracted, as private methods of the validator.
 * <p>
 * <strong>Pre-boot seam (ADR-0061, ADR-0062).</strong> The validator and its rules
 * carry no framework imports, because the offline configuration check runs them
 * before the framework starts. {@code PreBootFrameworkFreeArchTest} enforces that.
 * The rule set is supplied at construction.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config.validation;

import org.jspecify.annotations.NullMarked;
