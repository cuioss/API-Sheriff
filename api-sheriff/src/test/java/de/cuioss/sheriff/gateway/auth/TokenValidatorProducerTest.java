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
package de.cuioss.sheriff.gateway.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;


import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.IssuerConfig;
import de.cuioss.sheriff.gateway.config.model.TokenValidationConfig;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.token.commons.error.TransportException;
import de.cuioss.sheriff.token.commons.transport.EgressPolicy;
import de.cuioss.sheriff.token.commons.transport.HttpJwksLoaderConfig;
import de.cuioss.sheriff.token.validation.TokenValidator;
import de.cuioss.sheriff.token.validation.domain.context.AccessTokenRequest;
import de.cuioss.sheriff.token.validation.domain.token.AccessTokenContent;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;
import de.cuioss.sheriff.token.validation.test.InMemoryKeyMaterialHandler;
import de.cuioss.sheriff.token.validation.test.TestTokenHolder;
import de.cuioss.sheriff.token.validation.test.generator.TestTokenGenerators;
import de.cuioss.test.generator.junit.EnableGeneratorController;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnableGeneratorController
@DisplayName("TokenValidatorProducer — builds the shared gateway validator from token_validation")
class TokenValidatorProducerTest {

    private static final String ISSUER = "https://issuer.example";
    private static final String JWKS_URL = "https://issuer.example/jwks";

    @Test
    @DisplayName("fails config-invalid when no token_validation block is present")
    void failsWhenTokenValidationAbsent() {
        // Arrange
        TokenValidatorProducer producer = new TokenValidatorProducer(GatewayConfig.builder().version(1).build(),
                new JwksTrustProfileResolver(TestTlsConfigurationRegistry.empty()));

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    /**
     * The {@code audience} posture the producer resolves, asserted <em>behaviourally</em>.
     * <p>
     * The gateway's {@code audience} key is optional while token-sheriff requires an explicit choice
     * at build time, so {@code toValidationIssuer} either sets an expected audience or sets the
     * explicit opt-out. Neither choice is visible on the built {@link TokenValidator}, which exposes
     * no view of its issuer configs — so the posture is asserted by *validating a real token* through
     * the produced validator. That needs key material the validator can load without an IdP, which is
     * why these two cases use a {@code file} JWKS source seeded from the in-memory test key material
     * rather than the {@code http} source the surrounding cases use.
     */
    @Nested
    @DisplayName("audience posture — expected audience vs the explicit opt-out")
    class AudiencePosture {

        @TempDir
        Path jwksDir;

        @Test
        @DisplayName("a declared audience reaches the validator and refuses a token that does not carry it")
        void declaredAudienceIsEnforced() throws Exception {
            // Arrange — the declared audience is deliberately not one the generated token carries
            TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
            TokenValidator declaringForeignAudience = fileIssuerValidator(holder, "an-audience-the-token-lacks");
            TokenValidator declaringOwnAudience =
                    fileIssuerValidator(holder, holder.getAudience().iterator().next());
            AccessTokenRequest request = AccessTokenRequest.of(holder.getRawToken());

            // Act + Assert — the declared audience is genuinely applied ...
            assertThrows(TokenValidationException.class,
                    () -> declaringForeignAudience.createAccessToken(request),
                    "a declared audience must reach the built validator and refuse a token without it");

            // ... and the matched control: the same token, key material and issuer pass once the
            // declared audience is one the token actually carries, so the refusal above is
            // attributable to the audience decision and not to the token or the key set.
            AccessTokenContent accepted = declaringOwnAudience.createAccessToken(request);
            assertEquals(holder.getAudience(), accepted.getAudience(),
                    "the accepted token carries exactly the audience the issuer declared");
        }

        @Test
        @DisplayName("an issuer configuring no audience disables audience validation rather than refusing the token")
        void audienceLessIssuerDisablesAudienceValidation() throws Exception {
            // Arrange — the same token and key material, this time with no audience declared at all
            TestTokenHolder holder = TestTokenGenerators.accessTokens().next();
            TokenValidator validator = fileIssuerValidator(holder, null);

            // Act
            AccessTokenContent content =
                    validator.createAccessToken(AccessTokenRequest.of(holder.getRawToken()));

            // Assert — the token validates although the producer declared no expected audience, which
            // is only possible because the audience-less branch sets the explicit opt-out. Without it
            // token-sheriff's IssuerConfig.build() refuses to build the issuer at all, so this is the
            // exact contrast with declaredAudienceIsEnforced above: swap the two arrange blocks and
            // both tests fail.
            assertEquals(holder.getAudience(), content.getAudience(),
                    "the token is admitted unchanged, audience claim included, with validation disabled");
        }

        /**
         * A producer whose single issuer loads its key set from an on-disk JWKS file, so validation
         * runs fully offline.
         *
         * @param holder   the generated token whose issuer identifier and key material are mirrored
         * @param audience the {@code audience} to declare, or {@code null} to declare none at all
         * @return the produced gateway validator
         * @throws IOException when the JWKS fixture cannot be written
         */
        private TokenValidator fileIssuerValidator(TestTokenHolder holder, @Nullable String audience)
                throws IOException {
            Path jwks = Files.writeString(jwksDir.resolve("jwks-%s.json".formatted(audience)),
                    InMemoryKeyMaterialHandler.createDefaultJwks());
            IssuerConfig.IssuerConfigBuilder issuer = IssuerConfig.builder()
                    .name("primary")
                    .issuer(holder.getIssuer())
                    .jwks(IssuerConfig.Jwks.builder().source("file").file(jwks.toString()).build());
            if (audience != null) {
                issuer.audience(audience);
            }
            return producerFor(issuer.build()).gatewayTokenValidator();
        }
    }

    @Test
    @DisplayName("fails config-invalid when an issuer declares no jwks source")
    void failsWhenIssuerHasNoJwks() {
        // Arrange
        TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                .name("primary")
                .issuer(ISSUER)
                .build());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    /**
     * Covers the three unusable jwks-source shapes that must all fail config-invalid:
     * an {@code http} source without a url, a {@code file} source without a file path,
     * and a source that is not supported at all.
     */
    @ParameterizedTest(name = "jwks source ''{0}''")
    @ValueSource(strings = {"http", "file", "ldap"})
    @DisplayName("fails config-invalid when a jwks source is incomplete or unsupported")
    void failsForUnusableJwksSource(String source) {
        // Arrange
        TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                .name("primary")
                .issuer(ISSUER)
                .jwks(IssuerConfig.Jwks.builder().source(source).build())
                .build());

        // Act
        GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

        // Assert
        assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
    }

    @Nested
    @DisplayName("jwks.allowed_egress_hosts — SSRF egress allowlist")
    class AllowedEgressHosts {

        /**
         * Resolves to 127.0.0.1 from the hosts file, so the loopback branch of
         * {@link EgressPolicy}'s address check fires deterministically without a DNS
         * lookup. An unresolvable name would be waved through (the policy fails open on
         * {@code UnknownHostException}) and would therefore prove nothing.
         */
        private static final String BLOCKED_HOST = "localhost";
        private static final URI BLOCKED_JWKS_URI = URI.create("https://localhost:8443/jwks");

        @Test
        @DisplayName("omitting the field keeps the secure default — a private-address JWKS URL stays blocked")
        void omittedFieldKeepsSecureDefault() {
            // Arrange — an http issuer that says nothing about egress
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert — structurally the secure default, and behaviourally still blocking
            assertEquals(EgressPolicy.secureDefault(), policy,
                    "an absent allowed_egress_hosts must not widen egress");
            assertThrows(TransportException.class, () -> policy.check(BLOCKED_JWKS_URI),
                    "the secure default must refuse a JWKS URL resolving to a loopback address");
        }

        @Test
        @DisplayName("an empty list keeps the secure default — an empty allowlist is not a wildcard")
        void emptyListKeepsSecureDefault() {
            // Arrange — the field is present but carries no entries
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .allowedEgressHosts(List.of())
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert
            assertEquals(EgressPolicy.secureDefault(), policy,
                    "an empty allowed_egress_hosts must not widen egress");
            assertThrows(TransportException.class, () -> policy.check(BLOCKED_JWKS_URI),
                    "an empty allowlist must still refuse a loopback-resolving JWKS URL");
        }

        @Test
        @DisplayName("a listed host is exempted from the egress check")
        void listedHostIsExempted() {
            // Arrange — the trusted IdP host is named explicitly
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .allowedEgressHosts(List.of(BLOCKED_HOST))
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert
            assertDoesNotThrow(() -> policy.check(BLOCKED_JWKS_URI),
                    "the host named in allowed_egress_hosts must be reachable");
        }

        @Test
        @DisplayName("the exemption is host-exact — an unrelated entry does not widen egress for another host")
        void exemptionIsScopedToTheListedHost() {
            // Arrange — a different host is allowlisted than the one being checked
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .allowedEgressHosts(List.of("some-other-idp.internal"))
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert
            assertThrows(TransportException.class, () -> policy.check(BLOCKED_JWKS_URI),
                    "allowlisting one host must not exempt any other host");
        }

        @Test
        @DisplayName("several trusted hosts can be allowlisted independently")
        void severalHostsAreAllowlisted() {
            // Arrange
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .allowedEgressHosts(List.of("some-other-idp.internal", BLOCKED_HOST))
                    .build();

            // Act
            EgressPolicy policy = egressPolicyFor(jwks);

            // Assert
            assertDoesNotThrow(() -> policy.check(BLOCKED_JWKS_URI),
                    "every entry in allowed_egress_hosts must be applied, not just the first");
        }

        /**
         * The built {@link TokenValidator} exposes no view of its issuer configs, so the last hop of
         * the assembly is asserted at the producer's own {@code toHttpJwksLoaderConfig} seam driven
         * with the very issuer the public entry point consumed. Driving
         * {@link TokenValidatorProducer#gatewayTokenValidator()} first is what proves the allowlist
         * does not abort the whole-graph assembly; the policy assertions are what prove it survived
         * rather than being silently dropped back to the secure default.
         */
        @Test
        @DisplayName("the allowlist is carried through the full producer path, not only the seam")
        void allowlistSurvivesTheProducerPath() {
            // Arrange — a declared allowlist, and the matched control that differs from it in exactly
            // one respect: the absence of that declaration
            IssuerConfig.Jwks declaringAllowlist = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .allowedEgressHosts(List.of(BLOCKED_HOST))
                    .build();
            IssuerConfig.Jwks declaringNothing = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .build();

            // Act — both policies come from the public producer entry point, not from the seam alone
            EgressPolicy withAllowlist = producerPathEgressPolicy(declaringAllowlist);
            EgressPolicy withoutAllowlist = producerPathEgressPolicy(declaringNothing);

            // Assert — the declared allowlist survived the assembly ...
            assertDoesNotThrow(() -> withAllowlist.check(BLOCKED_JWKS_URI),
                    "the allowlisted host must be reachable through the policy the producer path builds");

            // ... and the admission is attributable to the allowlist rather than to an inert guard:
            // the same path over the same host refuses it once the allowlist is gone. Asserting the
            // policy is not EgressPolicy.secureDefault() would NOT do this job — EgressPolicy's
            // equality does not carry the host allowlist, so an allowlisted policy compares equal to
            // the secure default.
            assertThrows(TransportException.class, () -> withoutAllowlist.check(BLOCKED_JWKS_URI),
                    "without the declared allowlist the same producer path must still refuse the host");
        }

        private static EgressPolicy egressPolicyFor(IssuerConfig.Jwks jwks) {
            IssuerConfig issuer = IssuerConfig.builder().name("primary").issuer(ISSUER)
                    .jwks(jwks).build();
            return producerFor(issuer).toHttpJwksLoaderConfig(issuer, jwks).getEgressPolicy();
        }

        /**
         * The egress policy reached through the <em>full</em> producer path: unlike
         * {@link #egressPolicyFor(IssuerConfig.Jwks)} this drives
         * {@link TokenValidatorProducer#gatewayTokenValidator()} first, so a declaration that aborted
         * the whole-graph assembly could never reach the seam the policy is read from.
         *
         * @param jwks the jwks block whose egress declaration is under test
         * @return the egress policy the public producer entry point ends up with
         */
        private static EgressPolicy producerPathEgressPolicy(IssuerConfig.Jwks jwks) {
            IssuerConfig issuer = IssuerConfig.builder()
                    .name("benchmark-keycloak")
                    .issuer(ISSUER)
                    .jwks(jwks)
                    .build();
            TokenValidatorProducer producer = producerFor(issuer);
            producer.gatewayTokenValidator();
            return producer.toHttpJwksLoaderConfig(issuer, jwks).getEgressPolicy();
        }
    }

    @Nested
    @DisplayName("jwks.tls_profile — logical trust profile for the JWKS client")
    class TlsProfile {

        private static final String PROFILE = "corporate-idp";

        @Test
        @DisplayName("omitting the field keeps default trust — the profile's anchors are not applied")
        void omittedProfileKeepsDefaultTrust() {
            // Arrange — the profile IS defined, but this issuer does not name it
            TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .build();
            IssuerConfig issuer = issuerWith(jwks);

            // Act
            HttpJwksLoaderConfig config = producerFor(issuer, registry).toHttpJwksLoaderConfig(issuer, jwks);

            // Assert — an absent tls_profile must leave the client on whatever trust it had before
            // the feature existed. Asserting identity (not nullness) is deliberate: the JWKS client
            // fabricates its own default context, so a null check would prove nothing.
            assertNotSame(registry.profileContext(), config.getHttpHandler().getSslContext(),
                    "an absent tls_profile must not apply any profile's trust anchors");
        }

        @Test
        @DisplayName("an absent tls_profile never consults the resolver at all")
        void omittedProfileNeverConsultsTheResolver() {
            // Arrange — a resolver whose registry would fail any lookup
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .build();
            IssuerConfig issuer = issuerWith(jwks);

            // Act & Assert — reaching the resolver would throw, so completing proves it was skipped
            assertDoesNotThrow(() -> producerFor(issuer, TestTlsConfigurationRegistry.empty())
                            .toHttpJwksLoaderConfig(issuer, jwks),
                    "an absent tls_profile must short-circuit before the mapping seam");
        }

        @Test
        @DisplayName("a named profile is resolved and applied to the JWKS client")
        void namedProfileIsApplied() {
            // Arrange
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .tlsProfile(PROFILE)
                    .build();
            IssuerConfig issuer = issuerWith(jwks);

            // Act
            TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
            HttpJwksLoaderConfig config = producerFor(issuer, registry).toHttpJwksLoaderConfig(issuer, jwks);

            // Assert — the exact context the profile resolved to reaches the JWKS client
            assertSame(registry.profileContext(), config.getHttpHandler().getSslContext(),
                    "a named tls_profile must put its own trust anchors on the JWKS client");
        }

        @Test
        @DisplayName("an unresolvable profile fails the whole producer path rather than degrading")
        void unresolvableProfileFailsTheProducer() {
            // Arrange — the profile is named but the deployment bound nothing
            IssuerConfig issuer = issuerWith(IssuerConfig.Jwks.builder()
                    .source("http")
                    .url(JWKS_URL)
                    .tlsProfile(PROFILE)
                    .build());

            // Act
            TokenValidatorProducer producer = producerFor(issuer, TestTlsConfigurationRegistry.empty());
            GatewayException thrown = assertThrows(GatewayException.class, producer::gatewayTokenValidator);

            // Assert — the failure surfaces at validator assembly, which boot forces, so the
            // gateway refuses to start instead of silently validating against default trust
            assertEquals(EventType.CONFIG_INVALID, thrown.getEventType());
        }

        @Test
        @DisplayName("tls_profile and allowed_egress_hosts apply together on one issuer")
        void profileAndEgressAllowlistCombine() {
            // Arrange — the real deployment shape: a private-network IdP behind an internal CA,
            // which needs BOTH the egress widening and the trust profile to work at all
            IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder()
                    .source("http")
                    .url("https://localhost:8443/jwks")
                    .allowedEgressHosts(List.of("localhost"))
                    .tlsProfile(PROFILE)
                    .build();
            IssuerConfig issuer = issuerWith(jwks);

            // Act
            TestTlsConfigurationRegistry registry = TestTlsConfigurationRegistry.with(PROFILE);
            HttpJwksLoaderConfig config = producerFor(issuer, registry).toHttpJwksLoaderConfig(issuer, jwks);

            // Assert — the two knobs are independent; applying one must not drop the other
            assertSame(registry.profileContext(), config.getHttpHandler().getSslContext(),
                    "the trust profile must survive alongside the egress allowlist");
            assertDoesNotThrow(() -> config.getEgressPolicy().check(URI.create("https://localhost:8443/jwks")),
                    "the egress allowlist must survive alongside the trust profile");
        }

        private static IssuerConfig issuerWith(IssuerConfig.Jwks jwks) {
            return IssuerConfig.builder().name("corporate").issuer(ISSUER).jwks(jwks).build();
        }
    }

    @Nested
    @DisplayName("onStartup — forces eager validator assembly at boot")
    class OnStartup {

        /**
         * The {@code @GatewayValidator TokenValidator} is injected as a lazy {@code @ApplicationScoped}
         * client proxy, so ArC does not run {@link TokenValidatorProducer#gatewayTokenValidator()}
         * until a business method is called on that proxy. {@code onStartup} therefore MUST invoke a
         * method on the injected validator — the pre-fix no-op body ignored the parameter entirely and
         * would not dereference it. Passing a {@code null} validator proves a method is dereferenced:
         * the fixed body throws {@link NullPointerException}, the old body would complete silently.
         */
        @Test
        @DisplayName("dereferences the injected validator so contextual-instance creation is forced")
        void forcesEagerAssemblyByInvokingTheValidator() {
            // Arrange — config is irrelevant; onStartup only touches the validator parameter
            TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                    .name("primary")
                    .issuer(ISSUER)
                    .jwks(IssuerConfig.Jwks.builder().source("http").url(JWKS_URL).build())
                    .build());

            // Act & Assert — a no-op onStartup would not dereference the validator and would not throw
            assertThrows(NullPointerException.class, () -> producer.onStartup(null, null),
                    "onStartup must invoke a method on the injected validator to force eager assembly");
        }

        @Test
        @DisplayName("completes cleanly when the injected validator assembles without error")
        void completesForAValidlyConfiguredValidator() {
            // Arrange — a good http issuer whose validator assembles without a config error
            TokenValidatorProducer producer = producerFor(IssuerConfig.builder()
                    .name("primary")
                    .issuer(ISSUER)
                    .jwks(IssuerConfig.Jwks.builder().source("http").url(JWKS_URL).build())
                    .build());
            TokenValidator validator = producer.gatewayTokenValidator();

            // Act & Assert — forcing assembly of a validly-configured validator must not fail startup
            assertDoesNotThrow(() -> producer.onStartup(null, validator),
                    "forcing eager assembly of a valid validator must not abort startup");
        }
    }

    @Test
    @DisplayName("an absent allowed_egress_hosts binds to an empty list on the model")
    void jwksNormalizesAbsentEgressAllowlist() {
        // Arrange & Act
        IssuerConfig.Jwks jwks = IssuerConfig.Jwks.builder().source("http").build();

        // Assert
        assertEquals(List.of(), jwks.allowedEgressHosts(),
                "an omitted allowed_egress_hosts normalizes to an empty list, never null");
    }

    private static TokenValidatorProducer producerFor(IssuerConfig issuer) {
        return producerFor(issuer, TestTlsConfigurationRegistry.empty());
    }

    private static TokenValidatorProducer producerFor(IssuerConfig issuer, TestTlsConfigurationRegistry registry) {
        GatewayConfig config = GatewayConfig.builder()
                .version(1)
                .tokenValidation(new TokenValidationConfig(List.of(issuer)))
                .build();
        return new TokenValidatorProducer(config, new JwksTrustProfileResolver(registry));
    }
}
