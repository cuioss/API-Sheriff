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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The client JWKS endpoint ({@code oidc.client_authentication.jwks_path}) — the reserved gateway path
 * an identity provider fetches the gateway's client-authentication public key from, so it can verify
 * the {@code private_key_jwt} client assertion the gateway signs. It owns the reserved
 * {@link ReservedPathRegistry.ReservedEndpoint#CLIENT_JWKS} path and has two forms.
 * <p>
 * <strong>Publishing form</strong> — {@link #ClientJwksEndpoint(Map)}, built with the public JWK of
 * the client-authentication key. {@code GET} answers {@code 200} with exactly that one JWK under
 * {@code keys}; every other method answers {@code 405} with {@code Allow: GET} and no body.
 * <ul>
 *   <li><em>What it publishes.</em> The client-authentication public key, and nothing else. A
 *       separate key that signs the gateway's DPoP proofs is never published here: it travels inside
 *       every proof it signs, so an identity provider needs no out-of-band copy of it.</li>
 *   <li><em>Why {@code Cache-Control: no-store}.</em> The key changes when the operator replaces the
 *       key file, and on every restart when the key is generated. After a key change no cache may
 *       keep serving the previous document, or the identity provider would go on verifying against a
 *       key the gateway no longer signs with.</li>
 *   <li><em>Why {@code application/json}.</em> It is the media type the identity provider's own
 *       key-set endpoint serves, and the one the client implementations taken as precedent for this
 *       endpoint serve their key set with.</li>
 * </ul>
 * The document is fixed at construction and is the same for every request. The endpoint reads no
 * header, cookie, query or body and requires no credential: a public key is public.
 * <p>
 * <strong>Withheld form</strong> — {@link #withheld()}, for client-secret authentication, where no
 * client-authentication key exists. Every method yields the {@code 404} outcome, which carries no
 * header and no body. The gateway edge does not write that outcome itself: it answers the request
 * through the renderer it uses for a path no route matches, so the answer does not differ from that
 * of a path the gateway does not know — the same status, media type, body and headers. A
 * {@code 404} written in a shape of its own would let an anonymous caller read the
 * client-authentication mode off the difference between the two answers.
 * <p>
 * <em>Why the path is answered here and not released to the route table.</em> The path stays
 * reserved in both client-authentication modes. Releasing it in client-secret mode would hand the
 * request to whichever proxy route covers the path, and an upstream that happens to serve a key set
 * there would then answer under the very URL an identity provider may still hold as this client's
 * key-set URL — publishing keys the gateway does not control as the client's own.
 * <p>
 * The endpoint is framework-agnostic (an HTTP method name in, a {@link JwksOutcome} the edge renders
 * out — no JAX-RS/Vert.x coupling), so it is unit-testable without a container. It logs nothing
 * above {@code DEBUG}.
 * <p>
 * Instances are immutable and safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ClientJwksEndpoint {

    private static final CuiLogger LOGGER = new CuiLogger(ClientJwksEndpoint.class);

    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;

    private static final String GET = "GET";
    private static final String KEYS_MEMBER = "keys";
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_STORE = "no-store";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String APPLICATION_JSON = "application/json";
    private static final String ALLOW = "Allow";

    /**
     * The JWK members that carry private or symmetric key material (RFC 7518 sections 6.2.2, 6.3.2
     * and 6.4.1). A JWK holding any of them is refused at construction, so this endpoint can never
     * publish one.
     */
    private static final Set<String> PRIVATE_MEMBERS = Set.of("d", "p", "q", "dp", "dq", "qi", "oth", "k");

    private final @Nullable Map<String, Object> document;

    /**
     * Assembles the publishing form.
     *
     * @param publicJwk the public JWK of the client-authentication key; its member order is kept
     * @throws IllegalArgumentException when {@code publicJwk} carries a private or symmetric key
     *                                  member
     */
    public ClientJwksEndpoint(Map<String, Object> publicJwk) {
        Objects.requireNonNull(publicJwk, "publicJwk");
        if (publicJwk.keySet().stream().anyMatch(PRIVATE_MEMBERS::contains)) {
            // Fixed text: the offending member's value is key material and must not be echoed.
            throw new IllegalArgumentException("the JWK to publish carries a private key member");
        }
        this.document = Map.of(KEYS_MEMBER,
                List.of(Collections.unmodifiableMap(new LinkedHashMap<>(publicJwk))));
    }

    private ClientJwksEndpoint() {
        this.document = null;
    }

    /**
     * @return the withheld form — the endpoint of a gateway that authenticates with a client secret
     *         and therefore has no client-authentication key to publish
     */
    public static ClientJwksEndpoint withheld() {
        return new ClientJwksEndpoint();
    }

    /**
     * Serves one request to the client JWKS path.
     *
     * @param httpMethod the request HTTP method, in any letter case
     * @return in the publishing form, {@code 200} with the key set for {@code GET} and {@code 405}
     *         for every other method; in the withheld form, the header-less and body-less
     *         {@code 404} for every method, which the edge answers as it answers an unrouted path
     */
    public JwksOutcome handle(String httpMethod) {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Map<String, Object> published = document;
        if (published == null) {
            LOGGER.debug("Client JWKS path requested while no client-authentication key exists — 404");
            return JwksOutcome.notFound();
        }
        if (!GET.equalsIgnoreCase(httpMethod)) {
            LOGGER.debug("Client JWKS path requested with a method other than GET — 405");
            return JwksOutcome.methodNotAllowed();
        }
        return JwksOutcome.keySet(published);
    }

    /**
     * The framework-agnostic result of a client JWKS request: the HTTP status the edge returns, the
     * fixed response headers, and the key-set document when there is one to serve.
     *
     * @param status   the HTTP status the edge returns
     * @param headers  the fixed response headers the edge emits verbatim
     * @param document the JWKS document, {@code null} for an outcome that carries no body
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    public record JwksOutcome(int status, Map<String, String> headers, @Nullable Map<String, Object> document) {

        /**
         * Canonical constructor defensively copying the headers into an immutable, insertion-ordered
         * map.
         */
        public JwksOutcome {
            headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }

        private static JwksOutcome keySet(Map<String, Object> document) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put(CACHE_CONTROL, NO_STORE);
            headers.put(CONTENT_TYPE, APPLICATION_JSON);
            return new JwksOutcome(OK, headers, document);
        }

        private static JwksOutcome methodNotAllowed() {
            return new JwksOutcome(METHOD_NOT_ALLOWED, Map.of(ALLOW, GET), null);
        }

        /**
         * The withheld form's outcome. It names no header: the edge answers it with the response of
         * an unrouted path, and a header set here would be the one thing that tells the two apart.
         */
        private static JwksOutcome notFound() {
            return new JwksOutcome(NOT_FOUND, Map.of(), null);
        }
    }
}
