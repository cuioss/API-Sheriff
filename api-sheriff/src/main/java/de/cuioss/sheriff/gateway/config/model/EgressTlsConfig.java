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
package de.cuioss.sheriff.gateway.config.model;

import org.jspecify.annotations.Nullable;

/**
 * The global {@code egress_tls} block of {@code gateway.yaml} — the outbound
 * counterpart of the server-side {@link TlsConfig} block (ADR-0040).
 * <p>
 * The block is global rather than per route because the underlying settings are
 * fixed at client construction: one of the three egress clients is the edge-wide
 * WebSocket client, which a per-route value could not bind. There is no per-route
 * override.
 * <p>
 * <strong>Both flags relax hostname matching only.</strong> Turning one off stops
 * the dialled name from being compared against the certificate's names; it does
 * <em>not</em> disable certificate-chain validation, does not accept a self-signed
 * certificate, and does not accept a certificate issued by an untrusted authority.
 * Chain trust is a separate mechanism reached through {@code upstreamTlsProfile}.
 * <p>
 * <strong>An omitted flag resolves to {@code true}, not to the primitive default.</strong>
 * Jackson would bind an absent boolean to {@code false}, so a block naming only
 * {@code upstream_tls_profile} would silently disable hostname verification from a
 * document that never mentions it. {@code ConfigLoader}'s dedicated
 * {@code EgressTlsDeserializer} resolves each absent flag to {@code true}; this record
 * is only ever constructed with the resolved values.
 *
 * @param upstreamVerifyHostname whether a terminated upstream dial verifies that the
 *                               upstream certificate names the dialled host (default
 *                               {@code true}). Bound at all three egress
 *                               client-construction sites
 * @param jwksVerifyHostname     whether the JWKS back-channel verifies the same
 *                               (default {@code true}). <strong>Declared and bindable,
 *                               but no production code reads it today</strong> — it is
 *                               not an available control, and a test asserting that it
 *                               binds settles nothing about whether it acts. PLAN-04
 *                               owns both the reader and the behaviour test that proves
 *                               it acts
 * @param upstreamTlsProfile     the logical name of the trust profile whose anchors
 *                               verify terminated upstream certificates, {@code null}
 *                               when omitted — the clients then keep the JVM default
 *                               trust store. The name carries no trust material; the
 *                               deployment supplies the anchors (ADR-0011). A named
 *                               profile <em>replaces</em> the client's anchors rather
 *                               than adding to them
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
public record EgressTlsConfig(
boolean upstreamVerifyHostname,
boolean jwksVerifyHostname,
@Nullable String upstreamTlsProfile) {

    /**
     * The default {@code egress_tls} — both hostname-verification flags {@code true}
     * and no trust profile — applied when the block is not declared at all.
     *
     * @return a defaults instance with both flags enabled and no trust profile
     */
    public static EgressTlsConfig defaults() {
        return new EgressTlsConfig(true, true, null);
    }
}
