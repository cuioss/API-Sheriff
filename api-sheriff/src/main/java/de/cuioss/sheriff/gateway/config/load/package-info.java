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
 * Boot-time loader for the API Sheriff file-based configuration.
 * <p>
 * {@link de.cuioss.sheriff.gateway.config.load.ConfigLoader} reads {@code gateway.yaml}
 * and the {@code endpoints/*.yaml} files from the configuration directory,
 * validates each document against the bundled D2 JSON Schemas
 * (draft 2020-12, via {@code com.networknt:json-schema-validator}), resolves
 * {@code ${ENV_VAR}} secret references through
 * {@link de.cuioss.sheriff.gateway.config.load.EnvSecretResolver}, and binds the valid
 * trees to the immutable {@link de.cuioss.sheriff.gateway.config.model} records with
 * Jackson.
 * <p>
 * <strong>Error aggregation.</strong> Loading never fails on the first problem: all
 * schema violations, secret-resolution failures, and binding errors are collected —
 * each carrying its source file and a JSON-pointer location — and raised together as
 * a single {@link de.cuioss.sheriff.gateway.config.load.ConfigLoadException}.
 * <p>
 * <strong>Framework-agnostic seam (ADR-0005).</strong> The loader carries no CDI,
 * Quarkus, or framework imports; its configuration directory and secret resolver are
 * supplied by constructor parameters. The cross-cutting semantic validation,
 * endpoint-enablement resolution, topology-alias resolution, and route-table
 * assembly are layered on by later deliverables.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config.load;

import org.jspecify.annotations.NullMarked;
