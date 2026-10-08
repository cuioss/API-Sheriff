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
 * The single boot configuration pipeline seam.
 * <p>
 * {@link de.cuioss.sheriff.gateway.config.boot.ConfigBootPipeline} runs the configuration
 * stages of the gateway boot — load, enablement filter, topology resolution, semantic
 * validation plus the framework body-limit check, route-table assembly — and returns every
 * violation, every applied in-file default and every check it could not evaluate as one
 * outcome. It is shared by the gateway boot ({@code quarkus.ConfigProducer}) and the offline
 * configuration check, so both reach their verdict through the same code and cannot drift.
 * <p>
 * <strong>Pre-boot seam (ADR-0061, ADR-0062).</strong> This package carries no CDI, Quarkus,
 * or framework imports, because the offline configuration check runs it before the framework
 * starts. {@code PreBootFrameworkFreeArchTest} enforces that. The secret resolver is
 * constructor-injected and the one framework value a run needs is passed in by the caller.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config.boot;

import org.jspecify.annotations.NullMarked;
