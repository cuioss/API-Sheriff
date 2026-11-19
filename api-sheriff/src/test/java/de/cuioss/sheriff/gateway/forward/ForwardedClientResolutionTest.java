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
package de.cuioss.sheriff.gateway.forward;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.core.HttpSecurityValidator;
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.forward.ForwardedClientResolution.Absent;
import de.cuioss.sheriff.gateway.forward.ForwardedClientResolution.ForwardedClient;
import de.cuioss.sheriff.gateway.forward.ForwardedClientResolution.Resolved;
import de.cuioss.sheriff.gateway.forward.ForwardedClientResolution.Unresolvable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The one derivation of a trusted forwarder's client, pinned against the real cui-http
 * {@link ForwardedHeaderResolver}: the three outcomes {@link ForwardedClientResolution#clientOf} answers,
 * the absence of any second, single-family pass, and the joined value the resolver judges a
 * client-address header sent more than once as.
 */
@DisplayName("ForwardedClientResolution — the client of a trusted forwarder and the value it is judged as")
class ForwardedClientResolutionTest {

    private static final String FORWARDED = "Forwarded";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String XFF_CLIENT = "203.0.113.7";
    private static final String FORWARDED_CLIENT = "203.0.113.9";
    private static final String CLIENT_CHOSEN = "198.18.7.9";
    private static final String PROXY_RANGE = "192.0.2.10/32";
    /** An X-Forwarded-For chain carrying a control character, which the resolver's validation refuses. */
    private static final String MALFORMED_CHAIN = XFF_CLIENT + '\0';
    /** A small header-value cap, so a chain reaching it stays a handful of addresses long. */
    private static final int SMALL_CAP = 64;

    @Test
    @DisplayName("cui-http resolves no client when a trusted peer's Forwarded and X-Forwarded-For disagree")
    void combinedResolveOfADisagreeingChainYieldsNoClient() {
        ForwardedHeaderResolver resolver = resolver();

        Optional<String> contested = resolver.resolve(lookup(Map.of(FORWARDED, List.of("for=" + FORWARDED_CLIENT),
                FORWARDED_FOR, List.of(XFF_CLIENT)))).clientIp();
        Optional<String> agreeing = resolver.resolve(lookup(Map.of(FORWARDED, List.of("for=" + XFF_CLIENT),
                FORWARDED_FOR, List.of(XFF_CLIENT)))).clientIp();

        assertAll("the library behaviour a disagreeing pair meets",
                () -> assertTrue(contested.isEmpty(), "a disagreeing pair resolves no client: " + contested),
                () -> assertEquals(Optional.of(XFF_CLIENT), agreeing, "control: an agreeing pair resolves its client"));
    }

    @Nested
    @DisplayName("Resolved — the resolver named an address")
    class ResolvedClient {

        @Test
        @DisplayName("an agreeing chain resolves its client")
        void agreeingChainResolvesItsClient() {
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(),
                    lookup(Map.of(FORWARDED, List.of("for=" + XFF_CLIENT), FORWARDED_FOR, List.of(XFF_CLIENT))));

            assertEquals(new Resolved(XFF_CLIENT), client);
        }

        @Test
        @DisplayName("an X-Forwarded-For chain alone resolves its client")
        void xForwardedForAloneResolvesItsClient() {
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(),
                    lookup(Map.of(FORWARDED_FOR, List.of(XFF_CLIENT))));

            assertEquals(new Resolved(XFF_CLIENT), client);
        }

        @Test
        @DisplayName("a proxy appending the real client behind a client-sent prefix resolves the real client")
        void appendingProxyResolvesTheRealClientBehindAClientPrefix() {
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(),
                    lookup(Map.of(FORWARDED_FOR, List.of("garbage, " + XFF_CLIENT))));

            assertEquals(new Resolved(XFF_CLIENT), client, "matched control: a resolvable client stays unaffected");
        }

        @Test
        @DisplayName("a resolved client refuses an absent address")
        void resolvedRefusesAnAbsentAddress() {
            assertThrows(NullPointerException.class, () -> new Resolved(null));
        }
    }

    @Nested
    @DisplayName("Unresolvable — a client-address header is present, yet names no client")
    class UnresolvableClient {

        @Test
        @DisplayName("a Forwarded-only proxy's client cannot choose its address through X-Forwarded-For")
        void clientChosenXForwardedForBehindAForwardedOnlyProxyIsNotTaken() {
            ForwardedClient chosen = ForwardedClientResolution.clientOf(resolver(), lookup(Map.of(
                    FORWARDED, List.of("for=" + FORWARDED_CLIENT), FORWARDED_FOR, List.of(CLIENT_CHOSEN))));

            assertEquals(new Unresolvable(), chosen,
                    "neither family is resolved on its own: the client-chosen X-Forwarded-For is never the client");
        }

        @ParameterizedTest(name = "X-Forwarded-For [{0}]")
        @ValueSource(strings = {"not-an-address", "garbage", "999.0.0.1", "unknown", "[::1"})
        @DisplayName("a malformed X-Forwarded-For is unresolvable, never absent")
        void malformedXForwardedForIsUnresolvable(String forwardedFor) {
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(),
                    lookup(Map.of(FORWARDED_FOR, List.of(forwardedFor))));

            assertEquals(new Unresolvable(), client);
        }

        @ParameterizedTest(name = "X-Forwarded-For [{0}]")
        @ValueSource(strings = {"garbage", "unknown", "[::1"})
        @DisplayName("a client-sent X-Forwarded-For beside a Forwarded-only proxy's chain is unresolvable, never absent")
        void clientSentXForwardedForBesideAForwardedChainIsUnresolvable(String forwardedFor) {
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(), lookup(Map.of(
                    FORWARDED, List.of("for=" + FORWARDED_CLIENT), FORWARDED_FOR, List.of(forwardedFor))));

            assertEquals(new Unresolvable(), client);
        }

        @Test
        @DisplayName("an obfuscated Forwarded identifier is no address, so it is unresolvable")
        void obfuscatedIdentifierIsUnresolvable() {
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(),
                    lookup(Map.of(FORWARDED, List.of("for=_hidden"))));

            assertEquals(new Unresolvable(), client);
        }

        @Test
        @DisplayName("a malformed chain is resolved once, so the consumer's counter records it as one pass does")
        void malformedHeaderIsResolvedInOnePass() {
            Map<String, List<String>> headers = Map.of(FORWARDED, List.of("for=" + FORWARDED_CLIENT),
                    FORWARDED_FOR, List.of(MALFORMED_CHAIN));
            SecurityEventCounter singlePassCounter = new SecurityEventCounter();
            SecurityEventCounter consumerCounter = new SecurityEventCounter();

            resolver(singlePassCounter).resolve(lookup(headers));
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(consumerCounter), lookup(headers));

            long onePass = singlePassCounter.getTotalCount();
            assertAll("no second, single-family pass",
                    () -> assertEquals(new Unresolvable(), client),
                    () -> assertTrue(onePass >= 1, "precondition: one pass records the malformed header"),
                    () -> assertEquals(onePass, consumerCounter.getTotalCount(),
                            "the derivation resolves once, so it records exactly what one pass records"));
        }
    }

    @Nested
    @DisplayName("Absent — no client-address header carries a value")
    class AbsentClient {

        @Test
        @DisplayName("a request without a client-address header is absent")
        void noClientAddressHeaderIsAbsent() {
            ForwardedClient none = ForwardedClientResolution.clientOf(resolver(), lookup(Map.of()));
            ForwardedClient otherOnly = ForwardedClientResolution.clientOf(resolver(),
                    lookup(Map.of("X-Real-IP", List.of(XFF_CLIENT))));

            assertAll("nothing a client-address header carries",
                    () -> assertEquals(new Absent(), none),
                    () -> assertEquals(new Absent(), otherOnly, "a header the resolver never reads is not a client address"));
        }

        @ParameterizedTest(name = "[{0}]")
        @ValueSource(strings = {"", "   "})
        @DisplayName("a blank client-address header is absent")
        void blankClientAddressHeaderIsAbsent(String value) {
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(),
                    lookup(Map.of(FORWARDED_FOR, List.of(value), FORWARDED, List.of(value))));

            assertEquals(new Absent(), client);
        }
    }

    @Nested
    @DisplayName("the judged value — every instance joined as the resolver reads them")
    class JudgedValue {

        @Test
        @DisplayName("instances are joined with ', ' in inbound order; one instance is itself")
        void instancesAreJoinedInOrder() {
            assertAll("the join",
                    () -> assertEquals("1.1.1.1, " + XFF_CLIENT, ForwardedClientResolution.judgedValue(
                            List.of("1.1.1.1", XFF_CLIENT))),
                    () -> assertEquals(XFF_CLIENT, ForwardedClientResolution.judgedValue(List.of(XFF_CLIENT))),
                    () -> assertEquals(", ", ForwardedClientResolution.INSTANCE_SEPARATOR));
        }

        @Test
        @DisplayName("a joined chain exactly at the cap is resolved; one character more is dropped by the resolver")
        void resolverJudgesTheJoinedValueAtTheCap() {
            SecurityConfiguration capped = cappedConfiguration();
            ForwardedHeaderResolver resolver = resolver(capped, new SecurityEventCounter());
            List<String> atCap = List.of(chainOfLength(SMALL_CAP - separatedTailLength()), XFF_CLIENT);
            List<String> overCap = List.of(chainOfLength(SMALL_CAP + 1 - separatedTailLength()), XFF_CLIENT);

            ForwardedClient atCapClient = ForwardedClientResolution.clientOf(resolver, lookup(Map.of(FORWARDED_FOR, atCap)));
            ForwardedClient overCapClient = ForwardedClientResolution.clientOf(resolver,
                    lookup(Map.of(FORWARDED_FOR, overCap)));

            assertAll("the resolver judges the ', '-joined value against the cap",
                    () -> assertEquals(SMALL_CAP, ForwardedClientResolution.judgedValue(atCap).length()),
                    () -> assertEquals(SMALL_CAP + 1, ForwardedClientResolution.judgedValue(overCap).length()),
                    () -> assertEquals(new Resolved(XFF_CLIENT), atCapClient, "joined value exactly at the cap"),
                    () -> assertEquals(new Unresolvable(), overCapClient, "joined value one over the cap"));
        }

        @Test
        @DisplayName("each instance passes the per-instance check while the joined value the resolver judges fails it")
        void perInstanceCheckAndResolverJudgeDifferentValues() {
            SecurityConfiguration capped = cappedConfiguration();
            HttpSecurityValidator perInstance = PipelineFactory.createHeaderValuePipeline(capped, new SecurityEventCounter());
            List<String> overCap = List.of(chainOfLength(SMALL_CAP + 1 - separatedTailLength()), XFF_CLIENT);

            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(capped, new SecurityEventCounter()),
                    lookup(Map.of(FORWARDED_FOR, overCap)));

            assertAll("the discrepancy the edge closes by judging the joined value",
                    () -> assertTrue(passes(perInstance, overCap.get(0)), "the client's instance passes alone"),
                    () -> assertTrue(passes(perInstance, overCap.get(1)), "the proxy's instance passes alone"),
                    () -> assertFalse(passes(perInstance, ForwardedClientResolution.judgedValue(overCap)),
                            "the joined value fails the same check"),
                    () -> assertEquals(new Unresolvable(), client, "and the resolver drops the chain"));
        }
    }

    /**
     * {@link ForwardedClientResolution#CLIENT_ADDRESS_HEADERS} is kept as a constant because cui-http does not
     * expose the headers its {@link ForwardedHeaderResolver} reads a client address from. These cases keep the two
     * in step: every header a client address is commonly carried in, and every other header of the forwarded
     * family, is handed to the real resolver alone, carrying an address it would resolve, from a trusted proxy.
     * The headers it resolves a client from must be exactly the constant — so a resolver release that reads one
     * more of these candidate headers, or one fewer, turns this red instead of leaving the edge's header check
     * and the unresolved-chain keying judging a set the resolver no longer reads. A header that is not among the
     * candidates is not covered.
     */
    @Nested
    @DisplayName("the client-address headers are the headers the resolver reads a client from")
    class HeaderSetInStepWithTheResolver {

        /**
         * The headers tried: those a client address is commonly carried in by proxies, CDNs and load balancers,
         * and the rest of the forwarded family the resolver reads for other fields.
         */
        private static final List<String> CANDIDATE_HEADERS = List.of(FORWARDED, FORWARDED_FOR, "X-Real-IP",
                "Forwarded-For", "X-Forwarded", "X-Client-IP", "Client-IP", "True-Client-IP", "CF-Connecting-IP",
                "Fastly-Client-IP", "X-Cluster-Client-IP", "X-Original-Forwarded-For", "X-ProxyUser-Ip", "Via",
                "X-Forwarded-Host", "X-Forwarded-Proto", "X-Forwarded-Port", "X-Forwarded-Prefix", "X-ProxyHost",
                "X-ProxyScheme", "X-ProxyPort", "X-ProxyContextPath");

        /** Whether the resolver names a client from {@code header} alone, in either value shape. */
        private boolean resolvesAClientFrom(ForwardedHeaderResolver resolver, String header) {
            return List.of(XFF_CLIENT, "for=" + XFF_CLIENT).stream()
                    .anyMatch(value -> resolver.resolve(lookup(Map.of(header, List.of(value)))).clientIp()
                            .filter(XFF_CLIENT::equals).isPresent());
        }

        @Test
        @DisplayName("the resolver names a client from exactly the headers of CLIENT_ADDRESS_HEADERS")
        void resolverReadsExactlyTheClientAddressHeaders() {
            ForwardedHeaderResolver resolver = resolver();

            Set<String> read = CANDIDATE_HEADERS.stream()
                    .filter(header -> resolvesAClientFrom(resolver, header))
                    .map(header -> header.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());

            assertEquals(ForwardedClientResolution.CLIENT_ADDRESS_HEADERS, read,
                    "the headers the resolver reads a client address from drifted from CLIENT_ADDRESS_HEADERS");
        }

        @Test
        @DisplayName("every header of CLIENT_ADDRESS_HEADERS is tried, so none can drop out unnoticed")
        void everyClientAddressHeaderIsACandidate() {
            Set<String> tried = CANDIDATE_HEADERS.stream()
                    .map(header -> header.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());

            assertTrue(tried.containsAll(ForwardedClientResolution.CLIENT_ADDRESS_HEADERS),
                    () -> "a client-address header is not among the headers tried: " + tried);
        }

        @Test
        @DisplayName("control — a header outside the set, alone, leaves the request without a client-address header")
        void headerOutsideTheSetIsAbsent() {
            ForwardedClient client = ForwardedClientResolution.clientOf(resolver(),
                    lookup(Map.of("X-Real-IP", List.of(XFF_CLIENT))));

            assertEquals(new Absent(), client, "a header the resolver does not read is no client-address header");
        }
    }

    @Test
    @DisplayName("Forwarded and X-Forwarded-For are the client-address headers, in any letter case")
    void clientAddressHeadersAreRecognisedCaseInsensitively() {
        assertAll("the client-address headers",
                () -> assertTrue(ForwardedClientResolution.isClientAddressHeader("x-forwarded-for")),
                () -> assertTrue(ForwardedClientResolution.isClientAddressHeader("X-FORWARDED-FOR")),
                () -> assertTrue(ForwardedClientResolution.isClientAddressHeader(FORWARDED)),
                () -> assertFalse(ForwardedClientResolution.isClientAddressHeader("X-Real-IP")),
                () -> assertFalse(ForwardedClientResolution.isClientAddressHeader("X-Forwarded-Host")));
    }

    @Test
    @DisplayName("Should reject absent arguments")
    void shouldRejectNulls() {
        ForwardedHeaderResolver resolver = resolver();
        Function<String, List<String>> lookup = lookup(Map.of());

        assertAll("absent arguments",
                () -> assertThrows(NullPointerException.class, () -> ForwardedClientResolution.clientOf(null, lookup)),
                () -> assertThrows(NullPointerException.class, () -> ForwardedClientResolution.clientOf(resolver, null)),
                () -> assertThrows(NullPointerException.class, () -> ForwardedClientResolution.judgedValue(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> ForwardedClientResolution.isClientAddressHeader(null)));
    }

    private static ForwardedHeaderResolver resolver() {
        return resolver(new SecurityEventCounter());
    }

    private static ForwardedHeaderResolver resolver(SecurityEventCounter counter) {
        return resolver(SecurityConfiguration.defaults(), counter);
    }

    private static ForwardedHeaderResolver resolver(SecurityConfiguration configuration, SecurityEventCounter counter) {
        return new ForwardedHeaderResolver(ForwardedResolverConfig.builder().trustedProxies(Set.of(PROXY_RANGE))
                .securityConfig(configuration).build(), counter);
    }

    private static SecurityConfiguration cappedConfiguration() {
        return SecurityConfigurations.builderSeededFrom(SecurityConfiguration.defaults())
                .maxHeaderValueLength(SMALL_CAP).build();
    }

    /** The length the proxy's instance adds to the joined value: the separator and the client address. */
    private static int separatedTailLength() {
        return ForwardedClientResolution.INSTANCE_SEPARATOR.length() + XFF_CLIENT.length();
    }

    /**
     * A well-formed chain of IPv4 addresses joined with {@code ", "}, exactly {@code length} characters
     * long ({@code length} at least 7).
     */
    static String chainOfLength(int length) {
        StringBuilder chain = new StringBuilder();
        int remaining = length;
        while (remaining > 15) {
            chain.append("1.1.1.1, ");
            remaining -= 9;
        }
        return chain.append(addressOfLength(remaining)).toString();
    }

    /** An IPv4 literal exactly {@code length} characters long, 7 to 15. */
    private static String addressOfLength(int length) {
        int digits = length - 3;
        StringBuilder address = new StringBuilder();
        for (int octet = 0; octet < 4; octet++) {
            int width = Math.min(3, digits - (3 - octet));
            digits -= width;
            if (octet > 0) {
                address.append('.');
            }
            address.append("1".repeat(width));
        }
        return address.toString();
    }

    private static boolean passes(HttpSecurityValidator validator, String value) {
        try {
            validator.validate(value);
            return true;
        } catch (UrlSecurityException _) {
            return false;
        }
    }

    /** A case-insensitive header lookup answering every value of a present header, the empty list otherwise. */
    private static Function<String, List<String>> lookup(Map<String, List<String>> headers) {
        Map<String, List<String>> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byName.putAll(headers);
        return name -> byName.getOrDefault(name, List.of());
    }
}
