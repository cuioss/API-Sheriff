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
package de.cuioss.sheriff.gateway.edge.admission;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The source-key stage: the key a request is tracked under follows the {@code forwarded} trust
 * decision, never reads an untrusted peer's header, never keys a trusted proxy's request on the proxy,
 * folds a malformed or undeclared {@code Host} to {@code *} and an IPv6 source to its {@code /64}, and
 * is stashed on the context under {@link SourceKeys#SOURCE_KEY} for both per-source consumers.
 */
@DisplayName("SourceKeys — the one source key both per-source consumers read")
class SourceKeysTest {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String PROXY = "192.0.2.10";
    private static final String PEER = "198.51.100.23";
    private static final String FORWARDED_CLIENT = "203.0.113.7";
    private static final String DECLARED_HOST = "api.example.com";
    /** Marks a request whose authority parse throws, as Vert.x does for a malformed {@code Host}. */
    private static final HostAndPort MALFORMED = HostAndPort.create("malformed.invalid", -1);

    @Test
    @DisplayName("a trusted proxy's request is keyed by its forwarded client")
    void trustedProxyRequestIsKeyedByItsForwardedClient() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey key = sourceKeys.resolve(request(PROXY, Map.of(FORWARDED_FOR, FORWARDED_CLIENT), null));

        assertEquals(new SourceKey(FORWARDED_CLIENT, SourceKey.UNDECLARED_HOST), key);
    }

    @Test
    @DisplayName("an untrusted peer is keyed by the peer, its forwarding header never read")
    void untrustedPeerIsKeyedByThePeer() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey key = sourceKeys.resolve(request(PEER, Map.of(FORWARDED_FOR, FORWARDED_CLIENT), null));

        assertEquals(new SourceKey(PEER, SourceKey.UNDECLARED_HOST), key);
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "not-an-address", "999.0.0.1", "unknown"})
    @DisplayName("a trusted proxy's request whose forwarding header names no client is keyed on the unknown source")
    void trustedProxyRequestWithoutAClientIsKeyedOnTheUnknownSource(String forwardedFor) {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey key = sourceKeys.resolve(request(PROXY, Map.of(FORWARDED_FOR, forwardedFor), null));

        assertEquals(new SourceKey(SourceKey.UNKNOWN_SOURCE, SourceKey.UNDECLARED_HOST), key,
                "never the proxy's own address");
    }

    @Test
    @DisplayName("a trusted proxy's request with no forwarding header is keyed on the unknown source")
    void trustedProxyRequestWithoutAHeaderIsKeyedOnTheUnknownSource() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey key = sourceKeys.resolve(request(PROXY, Map.of(), null));

        assertEquals(new SourceKey(SourceKey.UNKNOWN_SOURCE, SourceKey.UNDECLARED_HOST), key);
    }

    @Test
    @DisplayName("a malformed Host folds onto the undeclared-host key instead of failing the handler")
    void malformedHostFoldsToTheUndeclaredHost() {
        SourceKeys sourceKeys = sourceKeys(List.of());

        SourceKey key = sourceKeys.resolve(request(PEER, Map.of(), MALFORMED));

        assertEquals(new SourceKey(PEER, SourceKey.UNDECLARED_HOST), key);
    }

    @Test
    @DisplayName("a declared Host is kept lower-cased, an undeclared one folds to *")
    void declaredHostIsKeptAndUndeclaredFolds() {
        SourceKeys sourceKeys = sourceKeys(List.of());

        SourceKey declared = sourceKeys.resolve(request(PEER, Map.of(), HostAndPort.create("API.Example.com", 443)));
        SourceKey undeclared = sourceKeys.resolve(request(PEER, Map.of(), HostAndPort.create("other.example", 443)));

        assertAll("the host fold",
                () -> assertEquals(new SourceKey(PEER, DECLARED_HOST), declared),
                () -> assertEquals(new SourceKey(PEER, SourceKey.UNDECLARED_HOST), undeclared));
    }

    @Test
    @DisplayName("an IPv6 source is keyed on its /64 prefix, for a peer and for a forwarded client alike")
    void ipv6SourceIsFoldedToItsPrefix() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey peer = sourceKeys.resolve(request("2001:db8:a:b:1:2:3:4", Map.of(), null));
        SourceKey forwarded = sourceKeys.resolve(request(PROXY, Map.of(FORWARDED_FOR, "2001:db8:c:d:5:6:7:8"), null));

        assertAll("one key per /64",
                () -> assertEquals(new SourceKey("2001:db8:a:b::/64", SourceKey.UNDECLARED_HOST), peer),
                () -> assertEquals(new SourceKey("2001:db8:c:d::/64", SourceKey.UNDECLARED_HOST), forwarded));
    }

    @Test
    @DisplayName("resolveAndStash stashes the resolved key under SOURCE_KEY and returns it")
    void resolveAndStashStashesTheKey() {
        SourceKeys sourceKeys = sourceKeys(List.of());
        Map<String, Object> data = new HashMap<>();
        HttpServerRequest raw = request(PEER, Map.of(), null);

        SourceKey key = sourceKeys.resolveAndStash(context(raw, data));

        assertAll("the stash",
                () -> assertEquals(new SourceKey(PEER, SourceKey.UNDECLARED_HOST), key),
                () -> assertSame(key, data.get(SourceKeys.SOURCE_KEY)),
                () -> assertEquals("sheriff.sourcekey", SourceKeys.SOURCE_KEY));
    }

    /** The stage with the given trusted proxies, the resolver configured as the forward stage's. */
    private static SourceKeys sourceKeys(List<String> trustedProxies) {
        ForwardedHeaderResolver forwardedResolver = new ForwardedHeaderResolver(
                ForwardedResolverConfig.builder().trustedProxies(Set.copyOf(trustedProxies))
                        .securityConfig(SecurityConfiguration.defaults()).build(),
                new SecurityEventCounter());
        return new SourceKeys(new SourceKeyResolver(List.of(DECLARED_HOST)), new TcpPeerGate(trustedProxies),
                forwardedResolver);
    }

    /**
     * A request answering only the three calls source resolution makes; every other call fails the test.
     * The {@link #MALFORMED} authority makes {@code authority()} throw.
     */
    private static HttpServerRequest request(String peer, Map<String, String> headers,
            @Nullable HostAndPort authority) {
        MultiMap multiMap = MultiMap.caseInsensitiveMultiMap();
        headers.forEach(multiMap::add);
        SocketAddress remote = SocketAddress.inetSocketAddress(50_000, peer);
        return (HttpServerRequest) Proxy.newProxyInstance(HttpServerRequest.class.getClassLoader(),
                new Class<?>[]{HttpServerRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "remoteAddress" -> remote;
                    case "headers" -> multiMap;
                    case "authority" -> {
                        if (authority == MALFORMED) {
                            throw new IllegalStateException("malformed authority");
                        }
                        yield authority;
                    }
                    default -> throw new AssertionError("source resolution must not call " + method.getName());
                });
    }

    /** A context answering {@code request()} and a map-backed {@code put}. */
    private static RoutingContext context(HttpServerRequest raw, Map<String, Object> data) {
        return (RoutingContext) Proxy.newProxyInstance(RoutingContext.class.getClassLoader(),
                new Class<?>[]{RoutingContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "request" -> raw;
                    case "put" -> {
                        data.put((String) args[0], args[1]);
                        yield proxy;
                    }
                    default -> throw new AssertionError("the stage must not call " + method.getName());
                });
    }
}
