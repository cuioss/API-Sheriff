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
 * Framework-agnostic configuration assembly for the API Sheriff gateway.
 * <p>
 * This package hosts the collaborators that turn the bound, validated
 * configuration model into the runtime-ready {@link de.cuioss.sheriff.gateway.config.model.RouteTable}:
 * the {@link de.cuioss.sheriff.gateway.config.RouteTableBuilder} that materializes the
 * effective per-route settings, and the {@link de.cuioss.sheriff.gateway.config.ConfigLogMessages}
 * structured-log catalogue. The framework-bound wiring — the CDI producer that
 * drives the boot pipeline and fails startup on invalid configuration — lives in
 * the {@code de.cuioss.sheriff.gateway.quarkus} edge package (ADR-0005); nothing here
 * carries a CDI, Quarkus, or MicroProfile import.
 * <p>
 * <strong>Null-safety.</strong> The package is {@link org.jspecify.annotations.NullMarked}:
 * references are non-null by default, with any nullable component annotated
 * explicitly.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config;

import org.jspecify.annotations.NullMarked;
