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
 * fixed at client construction: one of the three governed egress clients is the
 * edge-wide WebSocket client, which a per-route value could not bind. There is no
 * per-route override.
 * <p>
 * <strong>The block is not one switch over every https leg — read each key's own
 * scope.</strong> {@code upstreamVerifyHostname} and {@code upstreamTlsProfile} govern
 * the three Vert.x egress clients; {@code jwksVerifyHostname} governs the JWKS
 * back-channel and nothing else. The two hostname keys are deliberately separate rather
 * than one shared flag, because the legs are dialled by different clients and an operator
 * relaxing one has no reason to relax the other. The asset-origin fetch
 * ({@code UpstreamAssetSource.httpFetcher}) builds a JDK {@code java.net.http.HttpClient},
 * which carries neither setting: an {@code https} asset origin therefore keeps full
 * hostname verification and the JVM default trust store whatever this block says.
 * The two directions differ. Hostname verification is <em>fail-safe</em> there —
 * turning {@code upstreamVerifyHostname} off cannot weaken the asset leg. Trust is
 * the real limit: {@code upstreamTlsProfile} does not reach the asset leg either, so
 * an operator serving assets from a private-CA origin finds that fetch failing
 * closed rather than silently unverified, and binds those anchors into the JVM trust
 * store instead. Governing the JDK client would need {@code SSLParameters} plumbing
 * that ADR-0040 deliberately did not scope.
 * <p>
 * <strong>Both flags relax hostname matching only.</strong> Turning one off stops
 * the dialled name from being compared against the certificate's names; it does
 * <em>not</em> disable certificate-chain validation, does not accept a self-signed
 * certificate, and does not accept a certificate issued by an untrusted authority.
 * Chain trust is a separate mechanism, reached per leg: {@code upstreamTlsProfile} for
 * the Vert.x egress clients, the per-issuer {@code jwks.tls_profile} for the JWKS
 * back-channel.
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
 *                               {@code true}). Bound at all three Vert.x egress
 *                               client-construction sites; the JDK-client asset-origin
 *                               leg is out of scope and always verifies
 * @param jwksVerifyHostname     whether the JWKS back-channel verifies the same
 *                               (default {@code true}). Read by
 *                               {@code TokenValidatorProducer}, which resolves it once
 *                               at bean construction and passes it to token-sheriff's
 *                               {@code HttpJwksLoaderConfigBuilder#verifyHostname} for
 *                               every {@code http} JWKS source, so it governs every
 *                               configured issuer's JWKS fetch and that leg only.
 *                               Hostname matching only: chain trust is untouched and an
 *                               untrusted JWKS certificate is still refused. It is
 *                               <em>mutually exclusive</em> with a per-issuer
 *                               {@code jwks.tls_profile} — that profile supplies a
 *                               caller-built {@code SSLContext}, and the relaxation
 *                               applies only to the default-trust-store context the JWKS
 *                               client derives itself, so the combination is refused at
 *                               boot with {@code CONFIG_INVALID} rather than accepted and
 *                               silently ignored (ADR-0041). Resolving to {@code false}
 *                               logs {@code ApiSheriff-120} once at boot
 * @param upstreamTlsProfile     the logical name of the trust profile whose anchors
 *                               verify terminated upstream certificates, {@code null}
 *                               when omitted — the clients then keep the JVM default
 *                               trust store. The name carries no trust material; the
 *                               deployment supplies the anchors (ADR-0011). A named
 *                               profile <em>replaces</em> the client's anchors rather
 *                               than adding to them, on those three clients only — the
 *                               asset-origin leg keeps the JVM default trust store
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
