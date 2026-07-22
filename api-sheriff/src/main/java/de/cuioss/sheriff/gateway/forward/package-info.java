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
 * Stage 5 — the zero-trust forward policy.
 * <p>
 * {@link de.cuioss.sheriff.gateway.forward.ForwardPolicyStage} computes the deny-by-default upstream
 * projection — allow-listed headers and query parameters, static {@code set_headers}, and
 * <em>regenerated</em> forwarding headers (inbound {@code X-Forwarded-*} / {@code Forwarded} are
 * never propagated) — through the shared cui-http forwarded-header resolver.
 * {@link de.cuioss.sheriff.gateway.forward.TcpPeerGate} is the gateway-side immediate-TCP-peer trust
 * gate (ADR-0003) over the boot-parsed {@code trusted_proxies} CIDR set.
 * <p>
 * <strong>Framework-agnostic.</strong> The package operates on the agnostic
 * {@link de.cuioss.sheriff.gateway.pipeline.PipelineRequest} (the immediate peer address is supplied by
 * the edge) and carries no {@code io.vertx..} / {@code io.quarkus..} / {@code jakarta..} /
 * {@code org.eclipse.microprofile..} / {@code io.micrometer..} imports, so it stays inside the
 * ADR-0005 framework-agnostic arch-gate rule set.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.forward;

import org.jspecify.annotations.NullMarked;
