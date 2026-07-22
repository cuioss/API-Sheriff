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
 * Cross-cutting configuration validation for the boot pipeline.
 * <p>
 * {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator} runs the
 * {@link de.cuioss.sheriff.gateway.config.validation.rule.ValidationRule} set that
 * enforces the semantic rules the structural JSON Schemas cannot express — endpoint
 * and route id uniqueness, alias resolvability for enabled endpoints, effective-auth
 * dependencies, effective allowed-method membership, whole-second timeouts,
 * forwarded trust-all rejection, CORS wildcard-with-credentials rejection, and
 * session-mode conditionals — collecting every violation in a single pass.
 * <p>
 * <strong>Framework-agnostic seam (ADR-0005).</strong> The validator and its rules
 * carry no framework imports; the rule set is supplied at construction.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config.validation;

import org.jspecify.annotations.NullMarked;
