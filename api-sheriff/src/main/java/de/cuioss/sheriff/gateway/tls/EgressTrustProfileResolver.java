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
package de.cuioss.sheriff.gateway.tls;

import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.TrustOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The single mapping seam between {@code gateway.yaml}'s logical
 * {@code egress_tls.upstream_tls_profile} name and the concrete trust anchors the egress clients
 * verify terminated upstream certificates against — the egress sibling of
 * {@code JwksTrustProfileResolver}, on the same ADR-0011 boundary rule.
 * <p>
 * <strong>This class is the only place that knows how the egress profile name is bound.</strong>
 * {@code gateway.yaml} is API Sheriff's own configuration language, so it names a trust profile in
 * its own vocabulary — {@code upstream_tls_profile: corporate-up} — and says nothing about how that
 * name is bound. Every client-construction site deals only in the logical name and the resulting
 * {@link TrustOptions}. Confining the binding here is what keeps an operator's {@code gateway.yaml}
 * portable: the runtime underneath can change without invalidating the document, because only this
 * class would have to follow.
 *
 * <h2>Why the indirection, and why the material stays outside gateway.yaml</h2>
 *
 * The logical name expresses <em>intent</em> ("verify upstreams against the corporate anchors"); the
 * deployment supplies the <em>material</em> (the trust store, its password, its rotation). That split
 * keeps trust-store passwords out of the document operators edit and commit, avoids a second, weaker
 * re-implementation of key-store handling, and lets the same {@code gateway.yaml} move between
 * environments that bind the profile to different anchors.
 *
 * <h2>Blast radius: a named profile replaces the anchors, it does not add to them</h2>
 *
 * The clients apply the returned {@link TrustOptions} via {@code setTrustOptions}, which
 * <em>replaces</em> the client's trust anchors rather than extending them. A deployment that names a
 * profile is therefore choosing those anchors <em>exclusively</em> for terminated egress — an
 * operator naming a profile that holds only their private CA stops trusting public CAs on the
 * upstream leg. That is the correct semantic for a named profile, but it is a deliberate choice and
 * is documented as such for operators.
 *
 * <h2>Boundary rule: config neutral, diagnostics concrete</h2>
 *
 * {@code gateway.yaml} and its JSON schema never name the runtime. The startup errors below
 * deliberately do — an operator who names an unbound profile needs to be told exactly which knob to
 * set, not handed an abstraction. Every refusal names the concrete
 * {@code quarkus.tls.<name>.trust-store.*} key.
 *
 * <h2>Failure behaviour</h2>
 *
 * A named-but-unbound profile is a hard startup failure, never a fallback to default trust. Silently
 * falling back would turn a misconfigured trust anchor into an upstream dial that either fails
 * obscurely later or — worse — succeeds against anchors the operator did not intend.
 * <p>
 * A profile that <em>is</em> bound but carries no trust material is refused for the same reason. The
 * runtime registers a named bucket as soon as any {@code quarkus.tls.<name>.*} key exists — this
 * gateway ships exactly such a material-free bucket for the plain-HTTP management marker — and a
 * bucket with no anchors leaves the clients on the JVM default trust store. Accepting it would
 * reintroduce the silent fallback through the back door: the operator named a profile, the startup
 * succeeded, and the upstream dial trusts anchors nobody chose.
 * <p>
 * A profile that sets {@code quarkus.tls.<name>.trust-all} is refused ahead of both, because it is
 * the same failure one step worse. Such a bucket <em>does</em> carry trust options — the runtime
 * binds it to a trust-everything {@link TrustOptions} — so it would slip past the anchor-free check,
 * and every terminated upstream dial would then accept <em>any</em> certificate. A named profile
 * means <em>these anchors</em>; it never means <em>no verification at all</em>.
 * <p>
 * An {@code egress_tls} block that omits {@code upstream_tls_profile} never reaches this class: the
 * caller skips resolution entirely, no {@code setTrustOptions} call is made, and the clients keep the
 * JVM default trust store with no behavioural change.
 *
 * <h2>The one divergence from the JWKS sibling</h2>
 *
 * {@code JwksTrustProfileResolver} returns an {@code SSLContext} because cui-http's
 * {@code HttpHandler} consumes one. The Vert.x client options consume {@link TrustOptions} directly,
 * which is exactly what {@link TlsConfiguration#getTrustStoreOptions()} yields, so this resolver
 * returns that type and introduces no conversion layer.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class EgressTrustProfileResolver {

    private final TlsConfigurationRegistry registry;

    /**
     * @param registry the runtime's registry of named TLS configurations — the concrete side of the
     *                 mapping this class owns
     */
    @Inject
    public EgressTrustProfileResolver(TlsConfigurationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Resolves a logical trust-profile name to the Vert.x {@link TrustOptions} the egress clients use
     * to verify terminated upstream certificates.
     *
     * @param tlsProfile the logical profile name from {@code egress_tls.upstream_tls_profile}
     * @return the profile's trust anchors, never {@code null}
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the deployment defines no
     *                          such profile, when the profile disables verification entirely via
     *                          {@code trust-all}, when the profile is defined but carries no trust
     *                          material, or when its trust material cannot be loaded
     */
    public TrustOptions resolve(String tlsProfile) {
        TlsConfiguration configuration = registry.get(tlsProfile)
                .orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                        "gateway.yaml names egress_tls.upstream_tls_profile '" + tlsProfile
                                + "' but no such trust profile is configured — define it via "
                                + "quarkus.tls." + tlsProfile + ".trust-store.*"));
        // Checked BEFORE the anchor-free guard below: a trust-all bucket carries trust options, so it
        // would otherwise pass that guard and yield clients accepting any upstream certificate.
        if (configuration.isTrustAll()) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "gateway.yaml names egress_tls.upstream_tls_profile '" + tlsProfile
                            + "' but that profile sets quarkus.tls." + tlsProfile + ".trust-all — every "
                            + "terminated upstream dial would accept any certificate, so a machine on "
                            + "the path to the upstream could impersonate it; bind the profile to real "
                            + "anchors via quarkus.tls." + tlsProfile + ".trust-store.*");
        }
        TrustOptions trustOptions;
        try {
            trustOptions = configuration.getTrustStoreOptions();
            // The runtime turns the configured material into Vert.x options; an unreadable store, a
            // wrong password or an unsupported format surfaces here as an unchecked failure. There is
            // no narrower type to catch, and every mode behind it is the same configuration error from
            // the gateway's point of view.
        } catch (RuntimeException loadFailure) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "gateway.yaml names egress_tls.upstream_tls_profile '" + tlsProfile
                            + "' but its trust material could not be loaded: " + loadFailure.getMessage(),
                    loadFailure);
        }
        // The anchor-free guard is phrased on the trust OPTIONS alone, where the JWKS sibling also
        // accepts a loaded KeyStore: this resolver hands the clients the options directly, so a bucket
        // yielding none leaves them on the JVM default store — which is precisely the failure the
        // guard exists to refuse, whatever other shape the bucket may hold the material in.
        if (trustOptions == null) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "gateway.yaml names egress_tls.upstream_tls_profile '" + tlsProfile
                            + "' but that profile carries no trust material — the egress clients would "
                            + "verify upstreams against the JVM default trust store instead of the "
                            + "anchors the name promises; define it via quarkus.tls." + tlsProfile
                            + ".trust-store.*");
        }
        return trustOptions;
    }
}
