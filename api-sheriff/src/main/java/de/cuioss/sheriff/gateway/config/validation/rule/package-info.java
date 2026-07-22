/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
 * The {@link de.cuioss.sheriff.gateway.config.validation.rule.ValidationRule} contract:
 * the functional interface a single cross-cutting configuration rule implements,
 * appending a {@link de.cuioss.sheriff.gateway.config.load.ConfigError} per violation to
 * the shared accumulator so the
 * {@link de.cuioss.sheriff.gateway.config.validation.ConfigValidator} can report every
 * violation in one pass.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config.validation.rule;

import org.jspecify.annotations.NullMarked;
