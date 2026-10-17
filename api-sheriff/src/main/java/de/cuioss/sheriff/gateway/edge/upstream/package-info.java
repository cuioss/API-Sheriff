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
 * Guarded upstream dispatch and its failure mapping: sending a proxied request to its upstream
 * through the route's shared client and resilience guard, and turning an upstream failure into the
 * gateway's answer.
 * <p>
 * <strong>Contents.</strong> The HTTP
 * {@link de.cuioss.sheriff.gateway.edge.upstream.DispatchStage}, the forced-HTTP/2
 * {@link de.cuioss.sheriff.gateway.edge.upstream.GrpcDispatchStage} that delegates to it, the
 * {@link de.cuioss.sheriff.gateway.edge.upstream.StreamAwareRetryGate} deciding whether a failed
 * attempt may be retried, the
 * {@link de.cuioss.sheriff.gateway.edge.upstream.UpstreamFailureMapper}, the
 * {@link de.cuioss.sheriff.gateway.edge.upstream.EgressClients} every upstream client and the
 * edge-wide WebSocket client are built by, and the
 * {@link de.cuioss.sheriff.gateway.edge.upstream.UpstreamGuards} every resilience guard is built
 * by.
 * <p>
 * <strong>Dependencies.</strong> This package may depend on
 * {@link de.cuioss.sheriff.gateway.edge.assembly} only.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.upstream;

import org.jspecify.annotations.NullMarked;
