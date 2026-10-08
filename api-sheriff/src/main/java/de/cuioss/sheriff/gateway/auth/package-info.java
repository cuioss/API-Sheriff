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
 * Stage 4 — offline bearer-token validation and its CDI wiring.
 * <p>
 * {@link de.cuioss.sheriff.gateway.auth.AuthenticationStage} enforces each route's effective auth
 * posture on the agnostic {@code PipelineRequest}, validating {@code Bearer} tokens offline through
 * the shared {@link de.cuioss.sheriff.token.validation.TokenValidator}.
 * {@link de.cuioss.sheriff.gateway.auth.TokenValidatorProducer} produces that validator from the
 * gateway's {@code token_validation} config, tagged with the
 * {@link de.cuioss.sheriff.gateway.auth.GatewayValidator} qualifier so it coexists with the validator
 * shipped by {@code token-sheriff-validation-quarkus}.
 * <p>
 * <strong>Framework edge.</strong> This package holds CDI wiring ({@code jakarta.enterprise} /
 * {@code jakarta.inject}) and is therefore part of the framework edge. No package rule forbids
 * that: the framework-free gate covers the pre-boot configuration packages only (ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.auth;

import org.jspecify.annotations.NullMarked;
