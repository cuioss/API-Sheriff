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
 * Topology-alias resolution for the boot pipeline.
 * <p>
 * {@link de.cuioss.sheriff.gateway.config.topology.TopologyResolver} resolves the topology
 * aliases referenced by the enabled endpoints — reading each value from
 * {@code topology.properties}, running it through the shared {@code ${VAR}} /
 * {@code ${VAR:-default}} substitution engine (D4, ADR-0004 Amendment A1), validating
 * each URL, and decomposing it once into a
 * {@link de.cuioss.sheriff.gateway.config.model.ResolvedUpstream} (ADR-0004). Endpoint
 * enablement is a plain file-level {@code enabled} flag (a {@code ${VAR:-default}}
 * placeholder makes the environment override visible in-file); the removed
 * convention-named enablement resolver no longer participates.
 * <p>
 * <strong>Framework-agnostic seam (ADR-0005).</strong> The resolver takes its
 * substitution engine as a constructor parameter and carries no framework imports.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config.topology;

import org.jspecify.annotations.NullMarked;
