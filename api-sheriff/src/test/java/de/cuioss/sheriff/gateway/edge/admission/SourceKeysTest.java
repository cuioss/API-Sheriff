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

import static org.junit.jupiter.api.Assertions.*;

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
 * keys a trusted proxy's present but unresolvable forwarding chain on that proxy's unresolved source
 * rather than on the exempt {@code unknown}, never lets a client choose its key through a family the
 * proxy does not manage, folds a malformed or undeclared {@code Host} to {@code *} and an IPv6 source
 * to its {@code /64}, and is stashed on the context under {@link SourceKeys#SOURCE_KEY} for both
 * per-source consumers.
 */
@DisplayName("SourceKeys — the one source key both per-source consumers read")
class SourceKeysTest {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String FORWARDED = "Forwarded";
    private static final String OTHER_CLIENT = "203.0.113.9";
    private static final String CLIENT_CHOSEN = "198.18.7.9";
    private static final String CLIENT_ROTATED = "198.18.7.10";
    private static final String PROXY = "192.0.2.10";
    private static final String SECOND_PROXY = "192.0.2.20";
    /** The source a request through {@link #PROXY} is keyed on when its chain resolves no client. */
    private static final String UNRESOLVED_VIA_PROXY = "unresolved-via-192.0.2.10";
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
    @ValueSource(strings = {"not-an-address", "999.0.0.1", "unknown", "garbage"})
    @DisplayName("a trusted proxy's request whose forwarding header names no client is keyed on the proxy's unresolved source")
    void trustedProxyRequestWithAnUnresolvableHeaderIsKeyedOnTheProxysUnresolvedSource(String forwardedFor) {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey key = sourceKeys.resolve(request(PROXY, Map.of(FORWARDED_FOR, forwardedFor), null));

        assertEquals(new SourceKey(UNRESOLVED_VIA_PROXY, SourceKey.UNDECLARED_HOST), key,
                "a key the per-source consumers act on: never unknown, never the proxy's own address");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "   "})
    @DisplayName("a trusted proxy's request whose forwarding header is blank is keyed on the unknown source")
    void trustedProxyRequestWithABlankHeaderIsKeyedOnTheUnknownSource(String forwardedFor) {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey key = sourceKeys.resolve(request(PROXY, Map.of(FORWARDED_FOR, forwardedFor), null));

        assertEquals(new SourceKey(SourceKey.UNKNOWN_SOURCE, SourceKey.UNDECLARED_HOST), key,
                "never the proxy's own address");
    }

    @Test
    @DisplayName("a client behind a Forwarded-only proxy cannot choose its key through X-Forwarded-For")
    void clientCannotChooseItsKeyThroughAnUnmanagedXForwardedFor() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey chosen = sourceKeys.resolve(request(PROXY,
                Map.of(FORWARDED, "for=" + FORWARDED_CLIENT, FORWARDED_FOR, CLIENT_CHOSEN), null));
        SourceKey rotated = sourceKeys.resolve(request(PROXY,
                Map.of(FORWARDED, "for=" + FORWARDED_CLIENT, FORWARDED_FOR, CLIENT_ROTATED), null));

        assertAll("no X-Forwarded-For fallback for a family not shown proxy-managed",
                () -> assertEquals(new SourceKey(UNRESOLVED_VIA_PROXY, SourceKey.UNDECLARED_HOST), chosen,
                        "the client-chosen address is never the key"),
                () -> assertEquals(chosen, rotated, "rotating the chosen address does not rotate the key"));
    }

    @Test
    @DisplayName("matched control: behind a proxy that appends to X-Forwarded-For the key is the real client, as before")
    void appendingProxyKeysTheRealClientWhateverTheClientPrefixes() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey chosenPrefix = sourceKeys.resolve(request(PROXY,
                Map.of(FORWARDED_FOR, CLIENT_CHOSEN + ", " + FORWARDED_CLIENT), null));
        SourceKey rotatedPrefix = sourceKeys.resolve(request(PROXY,
                Map.of(FORWARDED_FOR, CLIENT_ROTATED + ", " + FORWARDED_CLIENT), null));
        SourceKey malformedPrefix = sourceKeys.resolve(request(PROXY,
                Map.of(FORWARDED_FOR, "garbage, " + FORWARDED_CLIENT), null));
        SourceKey separateLines = sourceKeys.resolve(requestWithInstances(PROXY,
                Map.of(FORWARDED_FOR, List.of(CLIENT_CHOSEN, FORWARDED_CLIENT))));

        SourceKey realClient = new SourceKey(FORWARDED_CLIENT, SourceKey.UNDECLARED_HOST);
        assertAll("the appending proxy",
                () -> assertEquals(realClient, chosenPrefix),
                () -> assertEquals(realClient, rotatedPrefix),
                () -> assertEquals(realClient, malformedPrefix),
                () -> assertEquals(realClient, separateLines, "the client's line and the proxy's own line"));
    }

    @Test
    @DisplayName("a client-sent X-Forwarded-For beside a Forwarded-only proxy's chain is keyed on the proxy's unresolved source")
    void clientSentMalformedXForwardedForIsKeyedOnTheProxysUnresolvedSource() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey malformed = sourceKeys.resolve(request(PROXY,
                Map.of(FORWARDED, "for=" + FORWARDED_CLIENT, FORWARDED_FOR, "garbage"), null));
        SourceKey contested = sourceKeys.resolve(request(PROXY,
                Map.of(FORWARDED, "for=" + OTHER_CLIENT, FORWARDED_FOR, FORWARDED_CLIENT), null));
        SourceKey agreeing = sourceKeys.resolve(request(PROXY,
                Map.of(FORWARDED, "for=" + FORWARDED_CLIENT, FORWARDED_FOR, FORWARDED_CLIENT), null));

        assertAll("a present but unresolvable chain is not exempt from the configured action",
                () -> assertEquals(new SourceKey(UNRESOLVED_VIA_PROXY, SourceKey.UNDECLARED_HOST), malformed),
                () -> assertEquals(new SourceKey(UNRESOLVED_VIA_PROXY, SourceKey.UNDECLARED_HOST), contested),
                () -> assertEquals(new SourceKey(FORWARDED_CLIENT, SourceKey.UNDECLARED_HOST), agreeing,
                        "matched control: an agreeing chain keys on its client as before"));
    }

    @Test
    @DisplayName("a client line that pushes the joined X-Forwarded-For over the cap is keyed on the proxy's unresolved source")
    void joinedChainOverTheCapIsKeyedOnTheProxysUnresolvedSource() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));
        String clientLine = chainOfAtLeast(SecurityConfiguration.defaults().maxHeaderValueLength() - FORWARDED_CLIENT.length());

        SourceKey overCap = sourceKeys.resolve(requestWithInstances(PROXY,
                Map.of(FORWARDED_FOR, List.of(clientLine, FORWARDED_CLIENT))));
        SourceKey shortLine = sourceKeys.resolve(requestWithInstances(PROXY,
                Map.of(FORWARDED_FOR, List.of(CLIENT_CHOSEN, FORWARDED_CLIENT))));

        assertAll("the resolver drops the joined chain; the request is still acted on",
                () -> assertTrue(clientLine.length() <= SecurityConfiguration.defaults().maxHeaderValueLength(),
                        "precondition: the client line alone is within the cap"),
                () -> assertEquals(new SourceKey(UNRESOLVED_VIA_PROXY, SourceKey.UNDECLARED_HOST), overCap),
                () -> assertEquals(new SourceKey(FORWARDED_CLIENT, SourceKey.UNDECLARED_HOST), shortLine,
                        "matched control: a short client line keeps the real client"));
    }

    @Test
    @DisplayName("each trusted proxy's unresolvable requests get a key of their own")
    void unresolvedSourceIsPerProxy() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32", SECOND_PROXY + "/32"));

        SourceKey first = sourceKeys.resolve(request(PROXY, Map.of(FORWARDED_FOR, "garbage"), null));
        SourceKey second = sourceKeys.resolve(request(SECOND_PROXY, Map.of(FORWARDED_FOR, "garbage"), null));

        assertAll("one unresolved source per proxy",
                () -> assertEquals(new SourceKey(UNRESOLVED_VIA_PROXY, SourceKey.UNDECLARED_HOST), first),
                () -> assertEquals(new SourceKey("unresolved-via-" + SECOND_PROXY, SourceKey.UNDECLARED_HOST), second));
    }

    @Test
    @DisplayName("an untrusted peer's contested chain is never read")
    void untrustedPeersContestedChainIsNeverRead() {
        SourceKeys sourceKeys = sourceKeys(List.of(PROXY + "/32"));

        SourceKey key = sourceKeys.resolve(request(PEER,
                Map.of(FORWARDED, "for=" + OTHER_CLIENT, FORWARDED_FOR, FORWARDED_CLIENT), null));

        assertEquals(new SourceKey(PEER, SourceKey.UNDECLARED_HOST), key);
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
        return request(peer, multiMap, authority);
    }

    /** A request carrying each header as the given separate instances, in order, and no authority. */
    private static HttpServerRequest requestWithInstances(String peer, Map<String, List<String>> headers) {
        MultiMap multiMap = MultiMap.caseInsensitiveMultiMap();
        headers.forEach(multiMap::add);
        return request(peer, multiMap, null);
    }

    /** A well-formed chain of IPv4 addresses joined with {@code ", "}, at least {@code length} characters long. */
    private static String chainOfAtLeast(int length) {
        StringBuilder chain = new StringBuilder("1.1.1.1");
        while (chain.length() < length) {
            chain.append(", 1.1.1.1");
        }
        return chain.toString();
    }

    private static HttpServerRequest request(String peer, MultiMap multiMap, @Nullable HostAndPort authority) {
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
