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
 * The framework-agnostic request pipeline: stages 0-3 of the fixed inbound flow plus the D3b
 * GW-01 / GW-02 hardening gates.
 * <p>
 * Every stage operates on the agnostic {@link de.cuioss.sheriff.gateway.pipeline.PipelineRequest}
 * carrier the edge builds from its framework-specific request, so this package carries no
 * {@code io.vertx..} / {@code io.quarkus..} / {@code jakarta..} / {@code org.eclipse.microprofile..}
 * imports and stays inside the ADR-0005 framework-agnostic arch-gate rule set. Stages reject by
 * throwing a typed {@link de.cuioss.sheriff.gateway.events.GatewayException}; the framework edge maps it
 * to the RFC 9457 response and never lets a rejected request reach the upstream.
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.pipeline.SecurityHeadersStage} — stage 0: response-header
 *       preparation and CORS preflight, before auth;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.pipeline.BasicChecksStage} — stage 1: the baseline cui-http
 *       filter yielding the single canonical path, plus collection-limit fast-reject;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.pipeline.FramingGate} — D3b GW-02 anti-smuggling framing gate;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.pipeline.CanonicalPathGuard} — D3b GW-01 single-canonical-path
 *       guard (encoded separator / matrix parameter rejection);</li>
 *   <li>{@link de.cuioss.sheriff.gateway.pipeline.RouteSelectionStage} — stage 2: deny-by-default
 *       longest-prefix route selection;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.pipeline.VerbGateStage} — stage 2b: the 405 verb gate with
 *       {@code Allow};</li>
 *   <li>{@link de.cuioss.sheriff.gateway.pipeline.ThoroughChecksStage} — stage 3: per-route divergent
 *       filters, {@code allowed_paths} whitelist, and {@code max_body_bytes} fast-reject.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.pipeline;

import org.jspecify.annotations.NullMarked;
