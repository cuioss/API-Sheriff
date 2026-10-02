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
package de.cuioss.sheriff.gateway.bff.reserved;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


import de.cuioss.sheriff.gateway.bff.client.ClientSigningKey;
import de.cuioss.sheriff.gateway.bff.client.TestSigningKeys;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link ClientJwksEndpoint}: the publishing form answers {@code GET} with exactly the one
 * public key it was built with and every other method with {@code 405}; the withheld form answers
 * every method with {@code 404}; and no form can be made to publish a private key member.
 * <p>
 * The published key is the real public JWK of a {@link ClientSigningKey} — read from a key file
 * {@link TestSigningKeys} writes, for EC and for RSA — so what is asserted is the document an
 * identity provider would actually fetch, not a hand-built map.
 */
@EnableGeneratorController
@DisplayName("ClientJwksEndpoint — the published client key set and its withheld form")
class ClientJwksEndpointTest {

    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_STORE = "no-store";
    /** The members a published key must never carry: the private RSA and EC members, and key_ops. */
    private static final List<String> NEVER_PUBLISHED = List.of("d", "p", "q", "dp", "dq", "qi", "key_ops");

    @TempDir
    Path directory;

    /** The two key types the gateway signs with, each with the algorithm and the members it publishes. */
    enum KeyType {

        EC("EC", "ES256", List.of("crv", "x", "y")) {
        @Override
        KeyPair generate() {
            return TestSigningKeys.ecKeyPair();
        }
    },

        RSA("RSA", "PS256", List.of("n", "e")) {
            @Override
            KeyPair generate() {
                return TestSigningKeys.rsaKeyPair();
            }
        };

        private final String keyType;
        private final String algorithm;
        private final List<String> publicMembers;

        KeyType(String keyType, String algorithm, List<String> publicMembers) {
            this.keyType = keyType;
            this.algorithm = algorithm;
            this.publicMembers = publicMembers;
        }

        abstract KeyPair generate();
    }

    private ClientSigningKey signingKey(KeyType keyType) {
        return ClientSigningKey.resolve(TestSigningKeys.writeKeyFile(directory, keyType.generate()).toString(),
                ClientSigningKey.Purpose.CLIENT_AUTHENTICATION);
    }

    /** The one key of a published document, asserting on the way that there is exactly one. */
    private static Map<?, ?> onlyKeyOf(ClientJwksEndpoint.JwksOutcome outcome) {
        Map<String, Object> document = outcome.document();
        assertNotNull(document, "a published outcome carries a document");
        assertEquals(List.of("keys"), List.copyOf(document.keySet()), "the document holds the key set and nothing else");
        List<?> keys = (List<?>) document.get("keys");
        assertEquals(1, keys.size(), "exactly one key is published");
        return (Map<?, ?>) keys.getFirst();
    }

    @Nested
    @DisplayName("Publishing form")
    class Publishing {

        @ParameterizedTest
        @EnumSource(KeyType.class)
        @DisplayName("Should answer GET with 200, the two headers and exactly the one public key")
        void shouldPublishTheOneKeyOnGet(KeyType keyType) {
            ClientSigningKey key = signingKey(keyType);
            ClientJwksEndpoint endpoint = new ClientJwksEndpoint(key.publicJwk());

            ClientJwksEndpoint.JwksOutcome outcome = endpoint.handle("GET");

            Map<?, ?> published = onlyKeyOf(outcome);
            assertAll("the published " + keyType + " key",
                    () -> assertEquals(200, outcome.status()),
                    () -> assertEquals(Map.of(CACHE_CONTROL, NO_STORE, "Content-Type", "application/json"),
                            outcome.headers(), "no-store and application/json, and no other header"),
                    () -> assertEquals(keyType.keyType, published.get("kty")),
                    () -> assertEquals("sig", published.get("use"), "the key is published for signatures"),
                    () -> assertEquals(keyType.algorithm, published.get("alg")),
                    () -> assertEquals(key.keyId(), published.get("kid"), "the key id is the key's thumbprint"),
                    () -> assertTrue(published.keySet().containsAll(keyType.publicMembers),
                            "the public members of the key type are published: " + published.keySet()),
                    () -> assertTrue(NEVER_PUBLISHED.stream().noneMatch(published::containsKey),
                            "no private member and no key_ops is published: " + published.keySet()),
                    () -> assertEquals(key.publicJwk(), published, "the document carries the key as it was handed in"));
        }

        @Test
        @DisplayName("Should read the method case-insensitively")
        void shouldReadTheMethodCaseInsensitively() {
            ClientJwksEndpoint endpoint = new ClientJwksEndpoint(signingKey(KeyType.EC).publicJwk());

            assertEquals(200, endpoint.handle("get").status());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"POST", "PUT", "DELETE", "HEAD"})
        @DisplayName("Should answer every method other than GET with 405, Allow: GET and no body")
        void shouldRefuseOtherMethods(String method) {
            ClientJwksEndpoint endpoint = new ClientJwksEndpoint(signingKey(KeyType.EC).publicJwk());

            ClientJwksEndpoint.JwksOutcome outcome = endpoint.handle(method);

            assertAll(method + " on the publishing form",
                    () -> assertEquals(405, outcome.status()),
                    () -> assertEquals(Map.of("Allow", "GET"), outcome.headers(), "Allow: GET and no other header"),
                    () -> assertNull(outcome.document(), "a refused method carries no body"));
        }

        @Test
        @DisplayName("Should serve the identical document on every call")
        void shouldServeTheSameDocumentOnEveryCall() {
            ClientJwksEndpoint endpoint = new ClientJwksEndpoint(signingKey(KeyType.RSA).publicJwk());

            ClientJwksEndpoint.JwksOutcome first = endpoint.handle("GET");
            endpoint.handle("POST");
            ClientJwksEndpoint.JwksOutcome second = endpoint.handle("GET");

            assertEquals(first, second, "the document is fixed at construction, whatever was requested in between");
        }

        @Test
        @DisplayName("Should fix the document at construction, unaffected by a later change of the source map")
        void shouldFixTheDocumentAtConstruction() {
            Map<String, Object> source = new LinkedHashMap<>(signingKey(KeyType.EC).publicJwk());
            Map<String, Object> asConstructed = Map.copyOf(source);
            ClientJwksEndpoint endpoint = new ClientJwksEndpoint(source);

            source.put("d", Generators.letterStrings(16, 32).next());
            source.remove("kid");

            Map<?, ?> published = onlyKeyOf(endpoint.handle("GET"));
            assertAll("the published key is a copy taken at construction",
                    () -> assertEquals(asConstructed, published),
                    () -> assertThrows(UnsupportedOperationException.class, published::clear,
                            "and the published key cannot be modified through the outcome"));
        }

        @ParameterizedTest(name = "a JWK carrying {0}")
        @ValueSource(strings = {"d", "p", "q", "dp", "dq", "qi", "oth", "k"})
        @DisplayName("Should refuse to be built with a JWK that carries a private key member, without echoing it")
        void shouldRefuseAPrivateKeyMember(String privateMember) {
            Map<String, Object> jwk = new LinkedHashMap<>(signingKey(KeyType.EC).publicJwk());
            String keyMaterial = Generators.letterStrings(16, 32).next();
            jwk.put(privateMember, keyMaterial);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new ClientJwksEndpoint(jwk));

            assertFalse(refused.getMessage().contains(keyMaterial), "the refusal must not echo the key material");
        }
    }

    @Nested
    @DisplayName("Withheld form")
    class Withheld {

        /**
         * The outcome names no header on purpose. The edge answers it with the response of an
         * unrouted path, and a header of the endpoint's own — the {@code no-store} of the publishing
         * form, or an {@code Allow} — would be what tells the two answers apart. That the answer on
         * the wire equals the one of an unknown path is asserted where both exist, in
         * {@code GatewayEdgeRouteBffWiringTest}.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"GET", "HEAD", "POST", "PUT", "DELETE"})
        @DisplayName("Should answer every method with 404, no header at all and no body")
        void shouldAnswerEveryMethodWith404(String method) {
            ClientJwksEndpoint.JwksOutcome outcome = ClientJwksEndpoint.withheld().handle(method);

            assertAll(method + " on the withheld form",
                    () -> assertEquals(404, outcome.status()),
                    () -> assertEquals(Map.of(), outcome.headers(),
                            "no header of the endpoint's own: neither no-store, nor Allow, nor a content type"),
                    () -> assertNull(outcome.document(), "there is no key, so there is no body"));
        }
    }
}
