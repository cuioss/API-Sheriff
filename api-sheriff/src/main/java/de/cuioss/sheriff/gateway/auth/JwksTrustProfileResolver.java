/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.gateway.auth;

import javax.net.ssl.SSLContext;


import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The single mapping seam between {@code gateway.yaml}'s logical
 * {@code token_validation.issuers[].jwks.tls_profile} name and the concrete trust material the
 * runtime holds.
 * <p>
 * <strong>This class is the only place in API Sheriff that knows the mapping exists.</strong>
 * {@code gateway.yaml} is API Sheriff's own configuration language, so it names a trust profile
 * in its own vocabulary — {@code tls_profile: corporate-idp} — and says nothing about how that
 * name is bound. Every other collaborator, {@link TokenValidatorProducer} included, deals only in
 * that logical name and the resulting {@link SSLContext}. Confining the binding here is what makes
 * a user's {@code gateway.yaml} portable: the runtime underneath can change without invalidating
 * the operator's configuration, because only this class would have to follow.
 *
 * <h2>Why the indirection, and why the material stays outside gateway.yaml</h2>
 *
 * The logical name expresses <em>intent</em> ("verify this IdP against the corporate trust
 * anchors"); the deployment supplies the <em>material</em> (the trust store, its password, its
 * rotation). That split is deliberate and buys three things:
 * <ul>
 *   <li><strong>Secrets stay out of {@code gateway.yaml}.</strong> A trust-store password never
 *       appears in the document operators edit, review, and commit.</li>
 *   <li><strong>No re-implementation of key-store handling.</strong> Loading, formats, reloads,
 *       and rotation are solved on the runtime side; API Sheriff consumes the result rather than
 *       growing a second, weaker implementation of the same thing.</li>
 *   <li><strong>Portability.</strong> The same {@code gateway.yaml} moves between environments
 *       that bind the profile to different anchors, and survives a change of runtime.</li>
 * </ul>
 *
 * <h2>Boundary rule: config neutral, diagnostics concrete</h2>
 *
 * {@code gateway.yaml} and its JSON schema never name the runtime. The startup error deliberately
 * does — an operator who names an unbound profile needs to be told exactly which knob to set, not
 * handed an abstraction. {@link #resolve(IssuerConfig, String)} therefore fails with a message
 * naming the concrete runtime key.
 *
 * <h2>Failure behaviour</h2>
 *
 * A named-but-unbound profile is a hard startup failure, never a fallback to default trust.
 * Silently falling back would turn a misconfigured trust anchor into a JWKS fetch that either
 * fails obscurely later or — worse — succeeds against anchors the operator did not intend. This
 * matches {@code ConfigProducer}'s existing refuse-to-start posture for invalid configuration.
 * <p>
 * An issuer that omits {@code tls_profile} never reaches this class: the caller skips resolution
 * entirely, so the JWKS client keeps the JVM default trust store with no behavioural change.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class JwksTrustProfileResolver {

    private final TlsConfigurationRegistry registry;

    /**
     * @param registry the runtime's registry of named TLS configurations — the concrete side of
     *                 the mapping this class owns
     */
    @Inject
    public JwksTrustProfileResolver(TlsConfigurationRegistry registry) {
        this.registry = registry;
    }

    /**
     * Resolves a logical trust-profile name to the {@link SSLContext} the JWKS client uses to
     * verify the IdP's server certificate.
     *
     * @param issuer      the issuer declaring the profile, for error context
     * @param tlsProfile  the logical profile name from {@code jwks.tls_profile}
     * @return the SSL context carrying the profile's trust anchors, never {@code null}
     * @throws GatewayException with {@link EventType#CONFIG_INVALID} when the deployment defines
     *                          no such profile, or when the profile is defined but its trust
     *                          material cannot be turned into an {@link SSLContext}
     */
    public SSLContext resolve(IssuerConfig issuer, String tlsProfile) {
        TlsConfiguration configuration = registry.get(tlsProfile)
                .orElseThrow(() -> new GatewayException(EventType.CONFIG_INVALID,
                        "Issuer '" + issuer.name() + "' names jwks.tls_profile '" + tlsProfile
                                + "' but no such trust profile is configured — define it via "
                                + "quarkus.tls." + tlsProfile + ".trust-store.*"));
        try {
            return configuration.createSSLContext();
            // Catching Exception is forced by the contract: TlsConfiguration#createSSLContext
            // declares `throws Exception`, so there is no narrower type to catch. Every failure
            // mode behind it — unreadable store, wrong password, unsupported format — is the same
            // configuration error from the gateway's point of view.
            // cui-rewrite:disable InvalidExceptionUsageRecipe
        } catch (Exception e) {
            throw new GatewayException(EventType.CONFIG_INVALID,
                    "Issuer '" + issuer.name() + "' names jwks.tls_profile '" + tlsProfile
                            + "' but its trust material could not be loaded: " + e.getMessage(), e);
        }
    }
}
