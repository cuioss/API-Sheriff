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
 * {@link de.cuioss.sheriff.gateway.forward.ForwardPolicyStage} computes what crosses to the upstream
 * <em>within a route the request is already authorised for</em>. Headers and query parameters each
 * resolve one of three forward modes independently: a positive-list when {@code headers_allow} /
 * {@code query_allow} is declared, a negative-list when {@code headers_deny} / {@code query_deny} is
 * declared, and forward-all when neither is — so an operator states a posture rather than
 * enumerating HTTP mechanics. To the mode-filtered client copy the stage adds static
 * {@code set_headers} and <em>regenerated</em> forwarding headers (inbound {@code X-Forwarded-*} /
 * {@code Forwarded} are never propagated) through the shared cui-http forwarded-header resolver.
 * <p>
 * <strong>Deny-by-default lives at the URL layer, not here.</strong> A request matching no route is
 * stamped {@code NO_ROUTE} and reaches no upstream at all; that is where the posture holds. Two
 * gateway-owned sets bound this stage instead, and neither is operator-overridable in the permissive
 * direction: {@link de.cuioss.sheriff.gateway.http.ConnectionHeaders#REQUEST_STRIP} names are
 * withheld on every mode — which is what makes the forward-all baseline safe — while the
 * gateway-understood protocol set crosses on its own, so content negotiation, ranges and the
 * conditional validators work without being enumerated.
 * <p>
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
