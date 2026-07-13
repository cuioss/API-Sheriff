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
 * Endpoint-enablement and topology-alias resolution for the boot pipeline.
 * <p>
 * {@link de.cuioss.sheriff.api.config.topology.EndpointEnablementResolver} computes
 * each endpoint's effective enabled state (environment override over the file
 * default) and drops disabled endpoints, and
 * {@link de.cuioss.sheriff.api.config.topology.TopologyResolver} resolves the
 * topology aliases referenced by the surviving enabled endpoints — applying the
 * {@code TOPOLOGY_<ALIAS>} environment override, validating each URL, and
 * decomposing it once into a
 * {@link de.cuioss.sheriff.api.config.model.ResolvedUpstream} (ADR-0004).
 * <p>
 * <strong>Framework-agnostic seam (ADR-0005).</strong> Both resolvers take their
 * environment lookup as a constructor parameter and carry no framework imports.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.api.config.topology;

import org.jspecify.annotations.NullMarked;
