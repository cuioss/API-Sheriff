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
 * The home of the boot-time configuration rules, grouped by concern.
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.config.validation.rule.ValidationRule} is the contract every
 *       rule implements: it appends a {@link de.cuioss.sheriff.gateway.config.load.ConfigError} per
 *       violation to the shared accumulator, so the
 *       {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator} can report every violation
 *       in one pass.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport} holds the helpers every
 *       rule shares: the reporting file and pointer, the anchors pointer, the CWE-117-safe echo of a
 *       configured value, anchor resolution, which protocol a route rides, which {@code auth.require}
 *       a route resolves, the wildcard-origin token, the RFC 9110 media-type token, anchor-namespace
 *       containment, and whether the gateway lacks a configured token issuer.</li>
 *   <li>One {@code {Concern}Rules} class per concern — for example
 *       {@link de.cuioss.sheriff.gateway.config.validation.rule.PortalRules} — owns the rule bodies
 *       that judge one configuration block, each a stateless {@code public static validateXxx}
 *       method. A concern class publishes no rule list of its own: the validator's single ordered list
 *       registers each rule and decides the reporting order.</li>
 * </ul>
 * Where a new rule goes, how it is registered and where its tests live is described on the
 * contributor page {@code doc/development/boot-validation-rules.adoc}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config.validation.rule;

import org.jspecify.annotations.NullMarked;
