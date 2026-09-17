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
 * fixed at client construction: one of the governed egress clients is the
 * edge-wide WebSocket client, which a per-route value could not bind. There is no
 * per-route override.
 * <p>
 * <strong>The block is not one switch over every https leg — read each key's own
 * scope.</strong> The gateway dials six TLS-terminating outbound legs (enumeration
 * re-derived from source), and each key reaches a named subset of them:
 * <ol>
 *   <li>the default Vert.x HTTP egress client — {@code upstreamVerifyHostname},
 *       {@code upstreamTlsProfile};</li>
 *   <li>the forced-HTTP/2 (gRPC) Vert.x egress client — {@code upstreamVerifyHostname},
 *       {@code upstreamTlsProfile};</li>
 *   <li>the edge-wide Vert.x WebSocket client — {@code upstreamVerifyHostname},
 *       {@code upstreamTlsProfile};</li>
 *   <li>the JWKS back-channel built in {@code TokenValidatorProducer} —
 *       {@code jwksVerifyHostname}, with trust reached per issuer through
 *       {@code jwks.tls_profile};</li>
 *   <li>the asset-origin JDK client in {@code UpstreamAssetSource} — no key in this
 *       block (see below);</li>
 *   <li>the BFF OIDC back-channel built in {@code BffRuntimeProducer} —
 *       {@code oidcVerifyHostname}, {@code oidcTlsProfile}.</li>
 * </ol>
 * The hostname keys are deliberately separate rather than one shared flag, because the
 * legs are dialled by different clients and an operator relaxing one has no reason to
 * relax another. The asset-origin fetch
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
 * <strong>The BFF OIDC back-channel is bound through its own peer keys (ADR-0045).</strong> The
 * {@code ClientConfiguration} built in {@code BffRuntimeProducer} is what
 * {@code DiscoveryResolver}, {@code TokenEndpointClient}, {@code RefreshFlow} and
 * {@code RevocationClient} dial the identity provider with — discovery, the authorization-code
 * exchange, refresh and refresh-token revocation — presenting the client secret under
 * {@code CLIENT_SECRET_BASIC}. {@code oidcVerifyHostname} is passed to
 * that builder's {@code verifyHostname} on every build, the {@code true} path included, so an
 * upstream default change cannot move the leg's posture (ADR-0022); {@code oidcTlsProfile}, when
 * named, supplies the builder's {@code sslContext}. The two are mutually exclusive in the same way
 * as {@code jwksVerifyHostname} and {@code jwks.tls_profile} (ADR-0041): the library relaxes
 * hostname matching only on the default-trust-store context it derives itself, so a
 * {@code false} flag together with a named profile is refused at boot with
 * {@code CONFIG_INVALID}.
 * <p>
 * <strong>Every flag relaxes hostname matching only.</strong> Turning one off stops
 * the dialled name from being compared against the certificate's names; it does
 * <em>not</em> disable certificate-chain validation, does not accept a self-signed
 * certificate, and does not accept a certificate issued by an untrusted authority.
 * Chain trust is a separate mechanism, reached per leg: {@code upstreamTlsProfile} for
 * the Vert.x egress clients, the per-issuer {@code jwks.tls_profile} for the JWKS
 * back-channel, {@code oidcTlsProfile} for the BFF OIDC back-channel.
 * <p>
 * <strong>An omitted flag resolves to {@code true}, not to the primitive default.</strong>
 * Jackson would bind an absent boolean to {@code false}, so a block naming only
 * {@code upstream_tls_profile} would silently disable hostname verification — on every
 * leg — from a document that never mentions it. {@code ConfigLoader}'s dedicated
 * {@code EgressTlsDeserializer} resolves each absent flag to {@code true} and each absent
 * profile to {@code null}; this record is only ever constructed with the resolved values.
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
 *                               untrusted JWKS certificate is still refused. On an
 *                               {@code http}-sourced issuer it is <em>mutually exclusive</em>
 *                               with a per-issuer {@code jwks.tls_profile} — that profile
 *                               supplies a caller-built {@code SSLContext}, and the relaxation
 *                               applies only to the default-trust-store context the JWKS
 *                               client derives itself, so the combination is refused at
 *                               boot with {@code CONFIG_INVALID} rather than accepted and
 *                               silently ignored (ADR-0041). The {@code http} qualifier is
 *                               exact: a {@code file}-sourced issuer opens no TLS connection,
 *                               so it reaches no such collision and none is refused there —
 *                               and its {@code jwks.tls_profile} has no TLS leg to act on.
 *                               Resolving to {@code false} logs {@code ApiSheriff-120} once
 *                               at boot
 * @param upstreamTlsProfile     the logical name of the trust profile whose anchors
 *                               verify terminated upstream certificates, {@code null}
 *                               when omitted — the clients then keep the JVM default
 *                               trust store. The name carries no trust material; the
 *                               deployment supplies the anchors (ADR-0011). A named
 *                               profile <em>replaces</em> the client's anchors rather
 *                               than adding to them, on those three clients only — the
 *                               asset-origin leg keeps the JVM default trust store
 * @param oidcVerifyHostname     whether the BFF OIDC back-channel — discovery, the
 *                               authorization-code exchange, refresh and refresh-token
 *                               revocation — verifies that the identity provider's
 *                               certificate names the dialled host
 *                               (default {@code true}). Read by {@code BffRuntimeProducer},
 *                               which passes it to token-sheriff's
 *                               {@code ClientConfigurationBuilder#verifyHostname} on every
 *                               build, so it governs that leg only. Hostname matching only:
 *                               chain trust is untouched and an untrusted identity provider
 *                               is still refused. Mutually exclusive with
 *                               {@code oidcTlsProfile} — the combination is refused at boot
 *                               with {@code CONFIG_INVALID} (ADR-0045). Resolving to
 *                               {@code false} logs {@code ApiSheriff-125} once at boot
 * @param oidcTlsProfile         the logical name of the trust profile whose anchors verify
 *                               the identity provider's certificate on the BFF OIDC
 *                               back-channel, {@code null} when omitted — the leg then keeps
 *                               the JVM default trust store. The name carries no trust
 *                               material (ADR-0011). A named profile <em>replaces</em> the
 *                               leg's anchors rather than adding to them and logs
 *                               {@code ApiSheriff-126} once at boot
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
public record EgressTlsConfig(
boolean upstreamVerifyHostname,
boolean jwksVerifyHostname,
@Nullable String upstreamTlsProfile,
boolean oidcVerifyHostname,
@Nullable String oidcTlsProfile) {

    /**
     * The default {@code egress_tls} — every hostname-verification flag {@code true}
     * and no trust profile — applied when the block is not declared at all.
     *
     * @return a defaults instance with every flag enabled and no trust profile
     */
    public static EgressTlsConfig defaults() {
        return new EgressTlsConfig(true, true, null, true, null);
    }
}
