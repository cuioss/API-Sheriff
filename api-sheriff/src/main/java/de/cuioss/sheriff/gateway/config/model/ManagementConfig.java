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

import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The global {@code management} block of {@code gateway.yaml} — the neutral
 * <em>policy</em> surface for the management interface (health and metrics on their own port).
 * <p>
 * <strong>This block declares no {@code port} component, deliberately (ADR-0025).</strong> The
 * ruling that governs the whole server-TLS surface classifies every knob as TLS <em>policy</em>,
 * <em>deployment-bound</em>, or <em>build-time</em>: policy is named neutrally here, while ports and
 * trust material stay deployment-supplied. The management port therefore remains the deployment's
 * knob at its existing {@code quarkus.management.port} default of 9000, overridable per deployment
 * by {@code QUARKUS_MANAGEMENT_PORT}.
 * <p>
 * The omission is structural rather than cosmetic. A {@code management.port} declared here could only
 * reach Quarkus through {@code NeutralTlsConfigSource}, whose ordinal is deliberately above the
 * environment source's 300 so a policy named in {@code gateway.yaml} is not silently overridden. A
 * port projected at that ordinal would in turn silently override {@code QUARKUS_MANAGEMENT_PORT} —
 * exactly the operator surprise the ruling rules out — and the only way to let the environment win
 * would be a second, lower-ordinal source, which the same ruling forbids. {@code gateway.schema.json}
 * therefore declares {@code management.port} solely in order to refuse it, with a message naming the
 * property that does own the port.
 *
 * @param tls the management-interface TLS policy, {@code null} when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record ManagementConfig(@Nullable ManagementTls tls) {

    /**
     * TLS policy for the management interface.
     * <p>
     * Unlike the main HTTP listener, the management interface has exactly <em>one</em> port —
     * Quarkus' management configuration declares no {@code ssl-port} and no
     * {@code insecure-requests} key — so this toggle governs the whole management surface. There is
     * no simultaneous HTTPS listener to fall back to: taking management off TLS takes health and
     * metrics to plain HTTP in their entirety, which is why the downgrade is audited with a loud
     * {@code WARN} at startup rather than passing silently.
     * <p>
     * {@code true} is the secure default and the shipped posture.
     *
     * @param enabled whether the management interface serves over TLS
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record ManagementTls(boolean enabled) {
    }
}
