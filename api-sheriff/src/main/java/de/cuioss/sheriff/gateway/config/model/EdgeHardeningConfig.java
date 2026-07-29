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
package de.cuioss.sheriff.gateway.config.model;

import java.util.Objects;
import java.util.Optional;

import lombok.Builder;

/**
 * The operator-facing {@code edge_hardening} block: the gateway's admission budget.
 * <p>
 * <strong>Two caps, not one.</strong> {@code admission_cap} bounds how many requests may be in flight
 * across the whole edge at once. {@code websocket_relay_cap} is a <em>sub-budget acquired in addition
 * to</em> that general permit, never instead of it, and exists because a WebSocket relay holds its
 * admission permit for the connection's entire lifetime rather than for one request/response
 * round-trip. Without the sub-budget a modest number of long-lived relays would consume the general
 * pool and starve ordinary HTTP traffic; with it, WebSocket pressure is bounded independently and
 * HTTP keeps the remaining headroom. A {@code websocket_relay_cap} larger than {@code admission_cap}
 * is meaningless — the sub-budget can never bind — and is refused at boot by the config validator.
 * <p>
 * Both members are optional: an omitted block, or an omitted member, resolves to the
 * {@link #defaults() documented defaults}, so an operator who never writes the block keeps today's
 * behaviour. Values are validated at boot (both {@code >= 1}); this record only carries them.
 *
 * @param admissionCap       the maximum number of concurrently in-flight requests the edge admits,
 *                           empty when the operator did not declare one
 * @param websocketRelayCap  the maximum number of concurrently established WebSocket relays, empty
 *                           when the operator did not declare one
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record EdgeHardeningConfig(Optional<Integer> admissionCap, Optional<Integer> websocketRelayCap) {

    /** The admission cap applied when the operator declares none — the gateway's historical bound. */
    public static final int DEFAULT_ADMISSION_CAP = 2048;

    /**
     * The WebSocket-relay sub-budget applied when the operator declares none: a quarter of
     * {@link #DEFAULT_ADMISSION_CAP}. A relay holds its permit for the connection's lifetime, so the
     * default deliberately leaves three quarters of the general pool for ordinary request/response
     * traffic while still admitting far more concurrent relays than a typical deployment sustains.
     */
    public static final int DEFAULT_WEBSOCKET_RELAY_CAP = 512;

    /** Canonical constructor normalizing absent optionals to {@link Optional#empty()}. */
    public EdgeHardeningConfig {
        admissionCap = Objects.requireNonNullElse(admissionCap, Optional.empty());
        websocketRelayCap = Objects.requireNonNullElse(websocketRelayCap, Optional.empty());
    }

    /**
     * @return the block an omitted {@code edge_hardening} resolves to — both caps at their documented
     *         defaults, preserving the behaviour of a gateway that never declares the block
     */
    public static EdgeHardeningConfig defaults() {
        return new EdgeHardeningConfig(Optional.of(DEFAULT_ADMISSION_CAP),
                Optional.of(DEFAULT_WEBSOCKET_RELAY_CAP));
    }

    /**
     * @return the declared admission cap, or {@link #DEFAULT_ADMISSION_CAP} when the member is absent
     */
    public int effectiveAdmissionCap() {
        return admissionCap.orElse(DEFAULT_ADMISSION_CAP);
    }

    /**
     * @return the declared WebSocket-relay sub-budget, or {@link #DEFAULT_WEBSOCKET_RELAY_CAP} when
     *         the member is absent
     */
    public int effectiveWebsocketRelayCap() {
        return websocketRelayCap.orElse(DEFAULT_WEBSOCKET_RELAY_CAP);
    }
}
