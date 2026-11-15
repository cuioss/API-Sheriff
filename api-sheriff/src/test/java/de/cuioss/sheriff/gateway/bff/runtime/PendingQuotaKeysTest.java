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
package de.cuioss.sheriff.gateway.bff.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The quota key a pending-authorization record is counted under: a trusted forwarder's client, else
 * the TCP peer (whose forwarding headers are never read), else {@code unknown}; IPv4 kept whole and IPv6
 * reduced to its {@code /64}; derived from the request's peer and forwarding headers alone.
 */
@DisplayName("PendingQuotaKeys — the requester source a pending record is counted under")
class PendingQuotaKeysTest {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String PROXY = "192.0.2.10";
    private static final String PEER = "198.51.100.23";
    private static final String FORWARDED_CLIENT = "203.0.113.7";
    /** The loopback relay leg every request behind the gateway's own SNI front arrives from. */
    private static final String RELAY_LEG = "127.0.0.1";
    private static final String CLIENT_A = "198.51.100.1";
    private static final String CLIENT_B = "198.51.100.2";

    private final PendingQuotaKeys quotaKeys = quotaKeys(List.of(PROXY + "/32"));

    @Test
    @DisplayName("a trusted forwarder's request is keyed on the client it forwards")
    void trustedForwarderIsKeyedOnItsClient() {
        String key = quotaKeys.quotaKeyOf(request(PROXY, Map.of(FORWARDED_FOR, FORWARDED_CLIENT)));

        assertEquals(FORWARDED_CLIENT, key);
    }

    @Test
    @DisplayName("an untrusted peer is keyed on its TCP address and its X-Forwarded-For is ignored")
    void untrustedPeerIsKeyedOnItsAddress() {
        String key = quotaKeys.quotaKeyOf(request(PEER, Map.of(FORWARDED_FOR, FORWARDED_CLIENT)));

        assertEquals(PEER, key, "a header an untrusted peer sends cannot move it onto another source's quota");
    }

    @ParameterizedTest(name = "[{0}]")
    @ValueSource(strings = {"", "not-an-address", "999.0.0.1", "unknown"})
    @DisplayName("a trusted forwarder whose header resolves no client is keyed on unknown, never on itself")
    void trustedForwarderWithoutClientIsUnknown(String forwardedFor) {
        String key = quotaKeys.quotaKeyOf(request(PROXY, Map.of(FORWARDED_FOR, forwardedFor)));

        assertEquals(SourceKey.UNKNOWN_SOURCE, key);
    }

    @Test
    @DisplayName("a trusted forwarder's request without a forwarding header is keyed on unknown")
    void trustedForwarderWithoutHeaderIsUnknown() {
        assertEquals(SourceKey.UNKNOWN_SOURCE, quotaKeys.quotaKeyOf(request(PROXY, Map.of())));
    }

    @Test
    @DisplayName("an IPv6 source is reduced to its /64, as a peer and as a forwarded client")
    void ipv6IsReducedToItsPrefix() {
        String peer = quotaKeys.quotaKeyOf(request("2001:db8:a:b:1:2:3:4", Map.of()));
        String samePrefix = quotaKeys.quotaKeyOf(request("2001:db8:a:b:ffff:2:3:4", Map.of()));
        String forwarded = quotaKeys.quotaKeyOf(request(PROXY, Map.of(FORWARDED_FOR, "2001:db8:c:d:5:6:7:8")));

        assertAll("one key per /64",
                () -> assertEquals("2001:db8:a:b::/64", peer),
                () -> assertEquals(peer, samePrefix, "addresses of one /64 share one quota"),
                () -> assertEquals("2001:db8:c:d::/64", forwarded));
    }

    @Test
    @DisplayName("a request without a peer address is keyed on unknown")
    void absentPeerIsUnknown() {
        assertEquals(SourceKey.UNKNOWN_SOURCE, quotaKeys.quotaKeyOf(request(null, Map.of())));
    }

    @Test
    @DisplayName("the key depends on the peer and its forwarding headers alone — not on host, path or method")
    void keyIgnoresEverythingButTheSource() {
        PipelineRequest login = PipelineRequest.builder().method(HttpMethod.GET).requestPath("/auth/login")
                .host("api.example.com").peerAddress(PEER).headers(Map.of()).build();
        PipelineRequest navigation = PipelineRequest.builder().method(HttpMethod.POST).requestPath("/orders")
                .host("other.example.org").peerAddress(PEER).headers(Map.of("Accept", List.of("text/html")))
                .build();

        assertEquals(quotaKeys.quotaKeyOf(login), quotaKeys.quotaKeyOf(navigation),
                "the same source has one quota whatever authority, path or method it calls");
    }

    @Test
    @DisplayName("with no trusted proxy configured every request is keyed on its TCP peer")
    void noTrustedProxyKeysOnThePeer() {
        PendingQuotaKeys untrusting = quotaKeys(List.of());

        assertEquals(PROXY, untrusting.quotaKeyOf(request(PROXY, Map.of(FORWARDED_FOR, FORWARDED_CLIENT))));
    }

    /**
     * Behind the gateway's own SNI front every terminated request arrives from the relay's loopback
     * leg. Without the hand-off two clients share that one key; with it each is keyed on its own
     * address — the matched pair that ties the distinct keys to the hand-off.
     */
    @Test
    @DisplayName("two relayed clients are keyed apart, where without the hand-off they share the relay leg's key")
    void relayedClientsAreKeyedApart() {
        PendingQuotaKeys untrusting = quotaKeys(List.of());

        String first = untrusting.quotaKeyOf(relayed(RELAY_LEG, CLIENT_A, Map.of()));
        String second = untrusting.quotaKeyOf(relayed(RELAY_LEG, CLIENT_B, Map.of()));
        String firstWithoutHandOff = untrusting.quotaKeyOf(request(RELAY_LEG, Map.of()));
        String secondWithoutHandOff = untrusting.quotaKeyOf(request(RELAY_LEG, Map.of()));

        assertAll("the relayed client takes the relay leg's place",
                () -> assertEquals(List.of(CLIENT_A, CLIENT_B), List.of(first, second)),
                () -> assertEquals(List.of(RELAY_LEG, RELAY_LEG), List.of(firstWithoutHandOff, secondWithoutHandOff),
                        "control: without the hand-off every relayed client shares the loopback key"));
    }

    @Test
    @DisplayName("the relayed client is the peer the trust gate judges, in both of its branches")
    void relayedClientIsThePeerTheGateJudges() {
        String trustedForwarder = quotaKeys.quotaKeyOf(relayed(RELAY_LEG, PROXY, Map.of(FORWARDED_FOR, FORWARDED_CLIENT)));
        String untrustedClient = quotaKeys.quotaKeyOf(relayed(RELAY_LEG, PEER, Map.of(FORWARDED_FOR, FORWARDED_CLIENT)));

        assertAll(
                () -> assertEquals(FORWARDED_CLIENT, trustedForwarder,
                        "a trusted proxy behind the front is judged as the peer, so its header is read"),
                () -> assertEquals(PEER, untrustedClient,
                        "an untrusted client behind the front is keyed on its own address, its header ignored"));
    }

    /**
     * Behind the front, a request on the terminated listener that the relay holds no hand-off for
     * arrives from the loopback relay leg — here a trusted forwarder. Its forwarding header must not
     * name its source; the matched control is the same request without the mark, whose header is read.
     */
    @Test
    @DisplayName("an unattributed terminated-listener request is keyed on its TCP peer, even from a trusted peer")
    void unattributedTerminatedLegIsKeyedOnItsPeer() {
        PendingQuotaKeys trustingRelayLeg = quotaKeys(List.of(RELAY_LEG + "/32"));
        Map<String, String> forged = Map.of(FORWARDED_FOR, FORWARDED_CLIENT);

        String unattributed = trustingRelayLeg.quotaKeyOf(unattributed(RELAY_LEG, forged));
        String unattributedUntrusting = quotaKeys(List.of()).quotaKeyOf(unattributed(RELAY_LEG, forged));
        String unmarked = trustingRelayLeg.quotaKeyOf(request(RELAY_LEG, forged));

        assertAll("the mark keeps the forwarding header out of the key",
                () -> assertEquals(RELAY_LEG, unattributed, "no header stands in for the missing hand-off"),
                () -> assertEquals(RELAY_LEG, unattributedUntrusting, "the same key without any trusted proxy"),
                () -> assertEquals(FORWARDED_CLIENT, unmarked,
                        "control: without the mark the trusted peer's header names the source"));
    }

    @Test
    @DisplayName("Should reject absent collaborators and an absent request")
    void shouldRejectNulls() {
        TcpPeerGate gate = new TcpPeerGate(List.of());
        ForwardedHeaderResolver resolver = resolver(List.of());

        assertThrows(NullPointerException.class, () -> new PendingQuotaKeys(null, resolver));
        assertThrows(NullPointerException.class, () -> new PendingQuotaKeys(gate, null));
        assertThrows(NullPointerException.class, () -> quotaKeys.quotaKeyOf(null));
    }

    /**
     * The derivation with the given trusted proxies and a resolver validating under
     * {@link SecurityConfiguration#defaults()} — not the gateway baseline {@link ForwardedTrust}
     * derives; these cases exercise the key rules, not the baseline.
     */
    private static PendingQuotaKeys quotaKeys(List<String> trustedProxies) {
        return new PendingQuotaKeys(new TcpPeerGate(trustedProxies), resolver(trustedProxies));
    }

    private static ForwardedHeaderResolver resolver(List<String> trustedProxies) {
        return new ForwardedHeaderResolver(
                ForwardedResolverConfig.builder().trustedProxies(Set.copyOf(trustedProxies))
                        .securityConfig(SecurityConfiguration.defaults()).build(),
                new SecurityEventCounter());
    }

    private static PipelineRequest request(@Nullable String peer, Map<String, String> headers) {
        return relayed(peer, null, headers);
    }

    /** A request whose TCP peer is {@code peer} and for which the edge recorded {@code relayedPeer}. */
    private static PipelineRequest relayed(@Nullable String peer, @Nullable String relayedPeer,
            Map<String, String> headers) {
        return builder(peer, headers).relayedPeerAddress(relayedPeer).build();
    }

    /** A request the edge marked {@linkplain PipelineRequest#unattributedTerminatedLeg() unattributed}. */
    private static PipelineRequest unattributed(String peer, Map<String, String> headers) {
        return builder(peer, headers).unattributedTerminatedLeg(true).build();
    }

    private static PipelineRequest.Builder builder(@Nullable String peer, Map<String, String> headers) {
        Map<String, List<String>> multi = new LinkedHashMap<>();
        headers.forEach((name, value) -> multi.put(name, List.of(value)));
        return PipelineRequest.builder().method(HttpMethod.GET).requestPath("/auth/login").peerAddress(peer)
                .headers(multi);
    }
}
