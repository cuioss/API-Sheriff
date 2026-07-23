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
package de.cuioss.sheriff.gateway.pipeline;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

/**
 * A pipeline pre-check that rejects a Host-vs-SNI smuggle before stage-2 route selection: a
 * <em>terminated</em> request whose {@code Host} header names a {@code passthrough_sni} hostname is
 * rejected {@code 404} ({@link EventType#PASSTHROUGH_HOST_SMUGGLED}) rather than being routed.
 * <p>
 * A passthrough hostname is meant to be split off at L4 by the accept-time front listener and never
 * terminated here; a terminated request carrying that hostname in its {@code Host} header is
 * therefore attempting to reach the passthrough backend's identity through the terminated listener
 * (a request-smuggling / host-confusion vector). Matching is case-insensitive and normalized
 * (lower-cased, trailing FQDN dot and any {@code :port} suffix removed), mirroring the front
 * listener's SNI normalization.
 * <p>
 * This is the genuinely-new runtime half of the guard, distinct from the framing / anti-smuggling
 * {@code FramingGate} and from the boot-time collision rule
 * ({@code ConfigValidator.validatePassthroughHostCollision}). The stage is inert when
 * {@code passthrough_sni} is empty, and framework-agnostic (ADR-0005) — it consumes only the
 * agnostic {@link PipelineRequest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PassthroughHostGuardStage {

    private final Set<String> passthroughHosts;

    /**
     * @param passthroughSniHosts the {@code tls.passthrough_sni} hostnames (SNI keys); normalized
     *                            into the reserved-host set the guard rejects a terminated
     *                            {@code Host} match against
     */
    public PassthroughHostGuardStage(Collection<String> passthroughSniHosts) {
        Objects.requireNonNull(passthroughSniHosts, "passthroughSniHosts");
        Set<String> normalized = new HashSet<>();
        for (String host : passthroughSniHosts) {
            normalized.add(normalize(host));
        }
        this.passthroughHosts = Set.copyOf(normalized);
    }

    /**
     * Rejects the request when its {@code Host} names a passthrough SNI; a no-op otherwise.
     *
     * @param request the in-flight request context
     * @throws GatewayException with {@link EventType#PASSTHROUGH_HOST_SMUGGLED} when the terminated
     *                          request's {@code Host} names a reserved passthrough hostname
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        if (passthroughHosts.isEmpty()) {
            return;
        }
        String host = request.host();
        if (host == null) {
            return;
        }
        if (passthroughHosts.contains(normalize(host))) {
            throw new GatewayException(EventType.PASSTHROUGH_HOST_SMUGGLED,
                    "Terminated request Host names a reserved passthrough SNI");
        }
    }

    private static String normalize(String host) {
        String lower = host.toLowerCase(Locale.ROOT).strip();
        int colon = lower.indexOf(':');
        if (colon > 0 && lower.lastIndexOf(':') == colon) {
            String maybePort = lower.substring(colon + 1);
            if (!maybePort.isEmpty() && maybePort.chars().allMatch(Character::isDigit)) {
                lower = lower.substring(0, colon);
            }
        }
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }
}
