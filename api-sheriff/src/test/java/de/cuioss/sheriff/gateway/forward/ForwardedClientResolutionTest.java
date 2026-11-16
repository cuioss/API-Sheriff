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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;


import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.http.forwarded.ForwardedResolverConfig;
import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The one {@code X-Forwarded-For} fallback both source-key consumers share, pinned against the real
 * cui-http {@link ForwardedHeaderResolver}: a trusted peer whose RFC 7239 {@code Forwarded} and
 * {@code X-Forwarded-For} chains disagree resolves no client from the combined headers, and the
 * fallback then resolves the {@code X-Forwarded-For} client through the same resolver.
 */
@DisplayName("ForwardedClientResolution — the X-Forwarded-For fallback of a contested chain")
class ForwardedClientResolutionTest {

    private static final String FORWARDED = "Forwarded";
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final String XFF_CLIENT = "203.0.113.7";
    private static final String FORWARDED_CLIENT = "203.0.113.9";
    private static final String PROXY_RANGE = "192.0.2.10/32";
    /** An X-Forwarded-For chain carrying a control character, which the resolver's validation refuses. */
    private static final String MALFORMED_CHAIN = XFF_CLIENT + '\0';

    @Test
    @DisplayName("cui-http resolves no client when a trusted peer's Forwarded and X-Forwarded-For disagree")
    void combinedResolveOfADisagreeingChainYieldsNoClient() {
        ForwardedHeaderResolver resolver = resolver();

        Optional<String> contested = resolver.resolve(lookup(Map.of(FORWARDED, "for=" + FORWARDED_CLIENT,
                FORWARDED_FOR, XFF_CLIENT))).clientIp();
        Optional<String> agreeing = resolver.resolve(lookup(Map.of(FORWARDED, "for=" + XFF_CLIENT,
                FORWARDED_FOR, XFF_CLIENT))).clientIp();

        assertAll("the library behaviour the fallback is triggered by",
                () -> assertTrue(contested.isEmpty(), "a disagreeing pair resolves no client: " + contested),
                () -> assertEquals(Optional.of(XFF_CLIENT), agreeing,
                        "control: an agreeing pair resolves its client"));
    }

    @Test
    @DisplayName("a contested chain resolves the X-Forwarded-For client through the fallback pass")
    void contestedChainResolvesTheXForwardedForClient() {
        RecordingLookup lookup = new RecordingLookup(
                Map.of(FORWARDED, "for=" + FORWARDED_CLIENT, FORWARDED_FOR, XFF_CLIENT));

        Optional<String> client = ForwardedClientResolution.clientOf(resolver(), lookup);

        assertAll("the X-Forwarded-For fallback",
                () -> assertEquals(Optional.of(XFF_CLIENT), client),
                () -> assertTrue(lookup.maskedPassSeen(), "the client came from the pass with Forwarded masked"));
    }

    @Test
    @DisplayName("an agreeing chain resolves its client unchanged, in one pass")
    void agreeingChainResolvesItsClientUnchanged() {
        RecordingLookup lookup = new RecordingLookup(Map.of(FORWARDED, "for=" + XFF_CLIENT, FORWARDED_FOR, XFF_CLIENT));

        Optional<String> client = ForwardedClientResolution.clientOf(resolver(), lookup);

        assertAll("the matched control",
                () -> assertEquals(Optional.of(XFF_CLIENT), client),
                () -> assertFalse(lookup.maskedPassSeen(), "a resolvable chain never takes the fallback pass"));
    }

    @Test
    @DisplayName("an X-Forwarded-For chain alone resolves its client")
    void xForwardedForAloneResolvesItsClient() {
        Optional<String> client = ForwardedClientResolution.clientOf(resolver(), lookup(Map.of(FORWARDED_FOR, XFF_CLIENT)));

        assertEquals(Optional.of(XFF_CLIENT), client);
    }

    @Test
    @DisplayName("a contested chain without a usable X-Forwarded-For resolves no client")
    void contestedChainWithoutAUsableXForwardedForResolvesNoClient() {
        Optional<String> unusable = ForwardedClientResolution.clientOf(resolver(),
                lookup(Map.of(FORWARDED, "for=" + FORWARDED_CLIENT, FORWARDED_FOR, "not-an-address")));
        Optional<String> absent = ForwardedClientResolution.clientOf(resolver(), lookup(Map.of()));

        assertAll("nothing to fall back to",
                () -> assertTrue(unusable.isEmpty(), "an unusable X-Forwarded-For: " + unusable),
                () -> assertTrue(absent.isEmpty(), "no forwarding header at all: " + absent));
    }

    @Test
    @DisplayName("a malformed X-Forwarded-For of a contested chain is counted once per pass, on the consumer's own counter")
    void malformedHeaderIsCountedOncePerPass() {
        Map<String, String> headers = Map.of(FORWARDED, "for=" + FORWARDED_CLIENT, FORWARDED_FOR, MALFORMED_CHAIN);
        SecurityEventCounter singlePassCounter = new SecurityEventCounter();
        SecurityEventCounter consumerCounter = new SecurityEventCounter();

        resolver(singlePassCounter).resolve(lookup(headers));
        Optional<String> client = ForwardedClientResolution.clientOf(resolver(consumerCounter), lookup(headers));

        long onePass = singlePassCounter.getTotalCount();
        assertAll("the documented double count",
                () -> assertTrue(client.isEmpty(), "precondition: neither pass resolves a client"),
                () -> assertTrue(onePass >= 1, "precondition: one pass records the malformed header"),
                () -> assertEquals(2 * onePass, consumerCounter.getTotalCount(),
                        "both passes record it, on the counter the consumer owns"));
    }

    @Test
    @DisplayName("Should reject an absent resolver and an absent lookup")
    void shouldRejectNulls() {
        ForwardedHeaderResolver resolver = resolver();
        Function<String, List<String>> lookup = lookup(Map.of());

        assertAll("absent collaborators",
                () -> assertThrows(NullPointerException.class, () -> ForwardedClientResolution.clientOf(null, lookup)),
                () -> assertThrows(NullPointerException.class, () -> ForwardedClientResolution.clientOf(resolver, null)));
    }

    private static ForwardedHeaderResolver resolver() {
        return resolver(new SecurityEventCounter());
    }

    private static ForwardedHeaderResolver resolver(SecurityEventCounter counter) {
        return new ForwardedHeaderResolver(ForwardedResolverConfig.builder().trustedProxies(Set.of(PROXY_RANGE))
                .securityConfig(SecurityConfiguration.defaults()).build(), counter);
    }

    /**
     * A lookup counting how often the resolver read {@code Forwarded} and {@code X-Forwarded-For}
     * through it, so a test can tell whether the masked fallback pass ran.
     */
    private static final class RecordingLookup implements Function<String, List<String>> {

        private final Function<String, List<String>> delegate;
        private int forwardedForReads;
        private int forwardedReads;

        RecordingLookup(Map<String, String> headers) {
            this.delegate = lookup(headers);
        }

        @Override
        public List<String> apply(String name) {
            if (FORWARDED.equalsIgnoreCase(name)) {
                forwardedReads++;
            } else if (FORWARDED_FOR.equalsIgnoreCase(name)) {
                forwardedForReads++;
            }
            return delegate.apply(name);
        }

        /**
         * Whether {@code X-Forwarded-For} was read more often than {@code Forwarded}: the combined pass
         * reads both, the fallback pass reads only the former, because the masking wrapper answers
         * {@code Forwarded} itself and never reaches this lookup.
         */
        boolean maskedPassSeen() {
            return forwardedForReads > forwardedReads;
        }
    }

    /** A case-insensitive header lookup answering one value per present header, the empty list otherwise. */
    private static Function<String, List<String>> lookup(Map<String, String> headers) {
        Map<String, String> byName = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        byName.putAll(headers);
        return name -> byName.containsKey(name) ? List.of(byName.get(name)) : List.of();
    }
}
