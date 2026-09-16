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
package de.cuioss.sheriff.gateway.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;


import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The URI literals below ARE the contract under test — scheme, authority, port and path-segment
 * boundaries decide whether a {@code Location} is mapped — so they are spelled out rather than
 * generated.
 */
@DisplayName("LocationRewriter — upstream.rewrite_location mapping (AS-11)")
class LocationRewriterTest {

    private static final ResolvedUpstream UPSTREAM = new ResolvedUpstream("https", "backend", 8443, "/svc/v1");
    private static final LocationRewriter REWRITER = new LocationRewriter(UPSTREAM, "/api", false);

    @Nested
    @DisplayName("base-path match")
    class BasePathMatch {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "https://backend:8443/svc/v1/items/7, /api/items/7",
                "https://backend:8443/svc/v1, /api",
                "https://backend:8443/svc/v1/, /api/",
                "/svc/v1/items/7, /api/items/7",
                "/svc/v1, /api"})
        @DisplayName("maps a same-origin or path-absolute Location inside the base path onto the match key")
        void mapsInsideBasePath(String location, String expected) {
            assertEquals(expected, REWRITER.rewrite(location));
        }

        @Test
        @DisplayName("matches scheme and host case-insensitively")
        void matchesSchemeAndHostCaseInsensitively() {
            assertEquals("/api/x", REWRITER.rewrite("HTTPS://Backend:8443/svc/v1/x"));
        }

        @Test
        @DisplayName("ignores trailing slashes on the base path and the match key")
        void ignoresTrailingSlashesOnBaseAndMatchKey() {
            var rewriter = new LocationRewriter(new ResolvedUpstream("https", "backend", 8443, "/svc/v1/"), "/api/",
                    false);

            assertEquals("/api/x", rewriter.rewrite("https://backend:8443/svc/v1/x"));
            assertEquals("/api", rewriter.rewrite("/svc/v1"));
        }
    }

    /**
     * An exact route's {@link RouteMatcher} admits the match key by string equality and routes nothing
     * below it, so a mapping that is not equal to the match key names a path the gateway does not serve.
     * Both arms are asserted together: the mapping onto the match key itself must still happen, and the
     * mapping below it must not — a fix that simply disabled rewriting on exact routes would satisfy
     * only the second.
     */
    @Nested
    @DisplayName("exact-route match key")
    class ExactMatchKey {

        private final LocationRewriter rewriter = new LocationRewriter(UPSTREAM, "/login", true);

        @Test
        @DisplayName("maps a Location at the base path onto the exact match key")
        void mapsOntoExactPathMatchKey() {
            assertEquals("/login", rewriter.rewrite("https://backend:8443/svc/v1"));
            assertEquals("/login", rewriter.rewrite("/svc/v1"));
        }

        @Test
        @DisplayName("keeps the query and fragment on the mapping onto the exact match key")
        void keepsQueryAndFragmentOnExactMapping() {
            assertEquals("/login?next=%2Fhome#top", rewriter.rewrite("/svc/v1?next=%2Fhome#top"));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "https://backend:8443/svc/v1/child",
                "/svc/v1/child",
                "/svc/v1/",
                "/svc/v1/child/grandchild"})
        @DisplayName("relays a Location below the base path unchanged rather than mapping it onto an unroutable path")
        void relaysBelowExactMatchKeyUnchanged(String location) {
            assertEquals(location, rewriter.rewrite(location),
                    "an exact route matches its match key by equality, so a mapping below it would send"
                            + " the browser to a path this gateway answers with 404");
        }

        @Test
        @DisplayName("a prefix route with the same match key still maps the whole subtree")
        void prefixRouteWithTheSameMatchKeyStillMapsBelowIt() {
            var prefixRewriter = new LocationRewriter(UPSTREAM, "/login", false);

            assertEquals("/login/child", prefixRewriter.rewrite("/svc/v1/child"),
                    "the refusal above must be driven by the matcher's exactness, not by the match-key"
                            + " value — a prefix matcher admits the subtree and the mapping stands");
        }

        @Test
        @DisplayName("honours a trailing slash on the exact match key")
        void honoursTrailingSlashOnExactMatchKey() {
            var slashRewriter = new LocationRewriter(UPSTREAM, "/login/", true);

            assertEquals("/svc/v1", slashRewriter.rewrite("/svc/v1"),
                    "an exact matcher treats a trailing slash as significant, so the mapping '/login'"
                            + " would not be routed by the very route that produced it");
        }

        @Test
        @DisplayName("maps onto a root exact match key")
        void mapsOntoRootExactMatchKey() {
            var rootRewriter = new LocationRewriter(new ResolvedUpstream("http", "backend", 8080, ""), "/", true);

            assertEquals("/", rootRewriter.rewrite("http://backend:8080/"));
            assertEquals("http://backend:8080/login", rootRewriter.rewrite("http://backend:8080/login"),
                    "the exact match key '/' only routes '/', so a deeper path must be relayed unchanged");
        }
    }

    /**
     * The base-path test runs on the path as received, so a {@code ..} segment inside it continues the
     * base path textually while the browser resolves the mapping outside the match key. Confinement
     * cannot be proved for such a value without resolving it, so it is not mapped at all.
     */
    @Nested
    @DisplayName("dot segments")
    class DotSegments {

        @ParameterizedTest
        @ValueSource(strings = {
                "/svc/v1/../login",
                "https://backend:8443/svc/v1/../login",
                "/svc/v1/a/../../login",
                "/svc/v1/%2e%2e/login",
                "/svc/v1/%2E%2E/login",
                "/svc/v1/.%2e/login",
                "/svc/v1/./items",
                "/svc/v1/%2e/items",
                "/svc/v1/..",
                "/svc/v1/items/.."})
        @DisplayName("relays a path carrying a literal or percent-encoded dot segment unchanged")
        void relaysDotSegmentPathsUnchanged(String location) {
            assertEquals(location, REWRITER.rewrite(location),
                    "the mapping would carry the dot segment through to the browser, which resolves it"
                            + " outside the match key the rewrite is supposed to confine the redirect to");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "/svc/v1/...",
                "/svc/v1/..a",
                "/svc/v1/a..",
                "/svc/v1/.hidden",
                "/svc/v1/%2ea"})
        @DisplayName("still maps a segment that merely contains dots but is not a dot segment")
        void mapsPathsWhoseSegmentsMerelyContainDots(String location) {
            assertEquals("/api" + location.substring("/svc/v1".length()), REWRITER.rewrite(location),
                    "only '.' and '..' are dot segments; refusing every segment containing a dot would"
                            + " stop mapping ordinary paths");
        }

        @Test
        @DisplayName("ignores dots in the query and fragment")
        void ignoresDotsInQueryAndFragment() {
            assertEquals("/api/items?next=/../x#..", REWRITER.rewrite("/svc/v1/items?next=/../x#.."),
                    "only the path decides confinement; the query and fragment are carried verbatim");
        }
    }

    /**
     * A backslash and a percent-encoded {@code /} or {@code \} are separators the mapping does not see
     * but the browser -- or an intermediary that decodes first -- does. The encoded backslash can move
     * the <em>origin</em>, and the encoded slash hides a traversal from the dot-segment guard, which
     * splits on the literal {@code /} only. The refusal set mirrors
     * {@code ConfigValidator.gatewayPathRefusal}, which already applies it to a configured redirect
     * target (AS-3 / GW-13).
     */
    @Nested
    @DisplayName("ambiguous separators")
    class AmbiguousSeparators {

        /**
         * The empty base path and the root match key are what make the origin reachable: the mapping
         * emits {@code gatewayPrefix + remainder} with the upstream authority stripped, so the
         * separator lands at the front of a gateway-relative value.
         */
        private final LocationRewriter rootRewriter =
                new LocationRewriter(new ResolvedUpstream("http", "backend", 8080, ""), "/", false);

        /**
         * The origin-change arm, and the one the mapping would <em>manufacture</em>: each value below is
         * an absolute upstream URI, which binds its authority before its path and therefore keeps the
         * browser on the upstream as it stands. Mapped, the authority is gone and the decoded path is
         * read as one -- {@code /\attacker.com} is {@code //attacker.com} under the WHATWG URL Standard,
         * which parses {@code \} as {@code /} in the special schemes. The absolute form is also what
         * makes the assertion non-vacuous: a path-absolute input under a root match key maps to itself,
         * so it would pass with or without the guard.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "http://backend:8080/%5Cattacker.com",
                "http://backend:8080/%5cattacker.com",
                "http://backend:8080/%5C%5Cattacker.com",
                "http://backend:8080/%2f%2fattacker.com",
                "http://backend:8080/%2F%2Fattacker.com",
                "http://backend:8080/%5c%2Fattacker.com"})
        @DisplayName("relays an absolute upstream URI whose path carries an encoded separator unchanged")
        void relaysEncodedSeparatorOntoRootUnchanged(String location) {
            assertEquals(location, rootRewriter.rewrite(location),
                    "stripping the upstream authority would turn a path that is inert on the upstream"
                            + " into one the browser resolves as a foreign origin");
        }

        /**
         * The confinement arm. {@code %2f} is a separator the dot-segment guard cannot see, because it
         * splits on the literal {@code /}: every value below is a single segment to that guard, and
         * {@code /svc/v1/%2e%2e%2fadmin} defeats even its {@code %2e} recognizer, since the segment ends
         * in {@code %2f} rather than in dots. Decoded, each leaves the match key the mapping promises to
         * confine the redirect to.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/svc/v1/a%2f..%2f..%2fadmin",
                "https://backend:8443/svc/v1/a%2F..%2F..%2Fadmin",
                "/svc/v1/%2e%2e%2fadmin",
                "/svc/v1/x%2fy",
                "/svc/v1/%5Cattacker.com",
                "https://backend:8443/svc/v1/%5cattacker.com",
                "/svc/v1/%5c%2Fx"})
        @DisplayName("relays a path carrying an encoded separator in any case spelling unchanged")
        void relaysEncodedSeparatorPathsUnchanged(String location) {
            assertEquals(location, REWRITER.rewrite(location),
                    "the mapping would carry the encoded separator through to the browser, which resolves"
                            + " it outside the match key the rewrite is supposed to confine it to");
        }

        /**
         * A literal backslash is refused too, but by an earlier gate: {@code \} is not a legal path
         * character, so {@link java.net.URI} rejects the value and {@code rewrite} relays it from its
         * parse-failure branch before the separator guard is reached. Pinned here because the refusal is
         * what this class is about, wherever it is decided -- and because a laxer parser would hand these
         * values to the guard, which refuses them on its own terms.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "/\\attacker.com",
                "/\\\\attacker.com",
                "http://backend:8080/\\attacker.com",
                "/svc/v1/\\attacker.com",
                "https://backend:8443/svc/v1/\\attacker.com"})
        @DisplayName("relays a path carrying a literal backslash unchanged")
        void relaysLiteralBackslashPathsUnchanged(String location) {
            assertEquals(location, REWRITER.rewrite(location));
            assertEquals(location, rootRewriter.rewrite(location));
        }

        /**
         * The matched positive controls for the refusals above: a guard that refused every encoded octet,
         * or every absolute URI, would pass every assertion in this class but these. {@code %5B} /
         * {@code %5D} and {@code %2D} are the neighbours of {@code %5C} and {@code %2F} in the encoding
         * table, so they also prove the test is on the separator and not on a {@code "%5"} / {@code "%2"}
         * prefix.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "/svc/v1/items/7, /api/items/7",
                "https://backend:8443/svc/v1/items/7, /api/items/7",
                "/svc/v1/%5Bx%5D, /api/%5Bx%5D",
                "/svc/v1/%2Dx, /api/%2Dx",
                "/svc/v1/%2ex, /api/%2ex"})
        @DisplayName("still maps a path whose encodings are not separators")
        void mapsPathsWithoutAmbiguousSeparators(String location, String expected) {
            assertEquals(expected, REWRITER.rewrite(location));
        }

        @Test
        @DisplayName("still maps an absolute upstream URI with an ordinary path onto the root match key")
        void mapsOrdinaryAbsoluteUriOntoRoot() {
            assertEquals("/login", rootRewriter.rewrite("http://backend:8080/login"),
                    "the refusal must be driven by the separator, not by the value being absolute");
        }

        /**
         * The deliberate scope boundary: the guard runs on the path, the way the dot-segment guard does.
         * An encoded separator after the {@code ?} or {@code #} cannot form an authority -- a relative
         * reference's authority is decided before the query delimiter -- and refusing it would stop
         * mapping the ordinary encoded-path parameter an upstream redirect carries, which
         * {@code ExactMatchKey.keepsQueryAndFragmentOnExactMapping} already asserts is kept. A
         * <em>literal</em> backslash has no case here: {@link java.net.URI} rejects it in a query and a
         * fragment exactly as it does in a path, so the encoded spelling is the only one that reaches
         * the mapping.
         */
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "/svc/v1/items?next=%2Fhome, /api/items?next=%2Fhome",
                "/svc/v1/items?next=a%5Cb, /api/items?next=a%5Cb",
                "/svc/v1/items#frag%5Cx, /api/items#frag%5Cx",
                "/svc/v1/items?next=a%5Cb#frag%2Fy, /api/items?next=a%5Cb#frag%2Fy"})
        @DisplayName("still maps a path whose query or fragment carries an encoded separator")
        void mapsEncodedSeparatorInQueryAndFragment(String location, String expected) {
            assertEquals(expected, REWRITER.rewrite(location),
                    "only the path decides confinement and the origin; the query and fragment are carried"
                            + " verbatim and cannot form an authority");
        }
    }

    @Nested
    @DisplayName("segment boundary")
    class SegmentBoundary {

        @ParameterizedTest
        @ValueSource(strings = {
                "https://backend:8443/svc/v10/items",
                "/svc/v1x",
                "/svc",
                "/other/svc/v1/items",
                "https://backend:8443"})
        @DisplayName("relays a path that does not continue the base path on a segment boundary unchanged")
        void relaysPathOutsideBasePath(String location) {
            assertEquals(location, REWRITER.rewrite(location));
        }
    }

    @Nested
    @DisplayName("empty base path")
    class EmptyBasePath {

        private final LocationRewriter rewriter =
                new LocationRewriter(new ResolvedUpstream("http", "backend", 8080, ""), "/app/", false);

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "/login, /app/login",
                "http://backend:8080/login?next=1, /app/login?next=1",
                "http://backend:8080, /app/",
                "/, /app/"})
        @DisplayName("every same-origin path continues an empty base path")
        void everyPathContinuesEmptyBase(String location, String expected) {
            assertEquals(expected, rewriter.rewrite(location));
        }

        /**
         * RFC 3986 section 5.3 resolves a scheme-less reference with an empty path against the
         * <em>current request path</em>, so these keep the browser where it is. An empty base path is
         * what makes the case reachable: they pass {@code pointsAtUpstream}, and synthesizing
         * {@code /} for their empty path would map {@code ?page=2} onto {@code /app/?page=2} — a
         * different address than the upstream named. The positive control for the same branch is
         * {@code everyPathContinuesEmptyBase}'s {@code http://backend:8080 -> /app/} row: an
         * <em>absolute</em> URI with no path does name the origin root and must still map, so a fix
         * that returned early for every empty path would fail there.
         */
        @ParameterizedTest
        @ValueSource(strings = {"?page=2", "#section", "?page=2#section", ""})
        @DisplayName("relays a scheme-less reference with an empty path unchanged")
        void relaysEmptyPathRelativeReferenceUnchanged(String location) {
            assertEquals(location, rewriter.rewrite(location),
                    "an empty path is relative to the current gateway path, not to the upstream root,"
                            + " so mapping it onto the match key would move the redirect");
        }

        @Test
        @DisplayName("keeps the path as-is under a root match key")
        void keepsPathUnderRootMatchKey() {
            var rootRewriter = new LocationRewriter(new ResolvedUpstream("http", "backend", 8080, ""), "/", false);

            assertEquals("/login", rootRewriter.rewrite("http://backend:8080/login"));
            assertEquals("/", rootRewriter.rewrite("http://backend:8080/"));
        }

        @Test
        @DisplayName("never emits a scheme-relative mapping")
        void neverEmitsSchemeRelativeMapping() {
            var rootRewriter = new LocationRewriter(new ResolvedUpstream("http", "backend", 8080, ""), "/", false);
            String location = "http://backend:8080//evil.example/steal";

            assertEquals(location, rootRewriter.rewrite(location),
                    "a mapping starting with // would redirect the browser to another origin");
        }
    }

    @Nested
    @DisplayName("foreign and non-candidate values")
    class ForeignValues {

        @ParameterizedTest
        @ValueSource(strings = {
                "https://other.example/svc/v1/items",
                "https://backend:9443/svc/v1/items",
                "http://backend:8443/svc/v1/items",
                "https://backend/svc/v1/items",
                "https://user@backend:8443/svc/v1/items"})
        @DisplayName("relays a Location naming another origin, port, scheme or carrying user-info unchanged")
        void relaysForeignOriginUnchanged(String location) {
            assertEquals(location, REWRITER.rewrite(location));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "//backend:8443/svc/v1/items",
                "//other.example/svc/v1/items"})
        @DisplayName("relays a scheme-relative //host Location unchanged")
        void relaysSchemeRelativeUnchanged(String location) {
            assertEquals(location, REWRITER.rewrite(location));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "items/7",
                "../svc/v1/items",
                "mailto:ops@backend",
                "https://backend:8443/svc/v1/a b",
                ""})
        @DisplayName("relays a relative-path reference, an opaque URI or an unparseable value unchanged")
        void relaysNonCandidatesUnchanged(String location) {
            assertEquals(location, REWRITER.rewrite(location));
        }
    }

    @Nested
    @DisplayName("query and fragment")
    class QueryAndFragment {

        @Test
        @DisplayName("keeps the raw query and fragment verbatim")
        void keepsQueryAndFragmentVerbatim() {
            assertEquals("/api/search?q=a%20b&x=1#top",
                    REWRITER.rewrite("https://backend:8443/svc/v1/search?q=a%20b&x=1#top"));
        }

        @Test
        @DisplayName("keeps a fragment without a query")
        void keepsFragmentWithoutQuery() {
            assertEquals("/api#section", REWRITER.rewrite("/svc/v1#section"));
        }

        @Test
        @DisplayName("keeps an empty query")
        void keepsEmptyQuery() {
            assertEquals("/api/x?", REWRITER.rewrite("/svc/v1/x?"));
        }
    }

    @Nested
    @DisplayName("default ports")
    class DefaultPorts {

        @ParameterizedTest
        @ValueSource(strings = {"https://backend/svc/v1/x", "https://backend:443/svc/v1/x"})
        @DisplayName("resolves an omitted https port to 443")
        void resolvesHttpsDefaultPort(String location) {
            var rewriter = new LocationRewriter(new ResolvedUpstream("https", "backend", 443, "/svc/v1"), "/api",
                    false);

            assertEquals("/api/x", rewriter.rewrite(location));
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://backend/svc/v1/x", "http://backend:80/svc/v1/x"})
        @DisplayName("resolves an omitted http port to 80")
        void resolvesHttpDefaultPort(String location) {
            var rewriter = new LocationRewriter(new ResolvedUpstream("http", "backend", 80, "/svc/v1"), "/api",
                    false);

            assertEquals("/api/x", rewriter.rewrite(location));
        }

        @Test
        @DisplayName("does not treat a default port as equal to a non-default upstream port")
        void defaultPortDoesNotMatchNonDefaultUpstreamPort() {
            var rewriter = new LocationRewriter(new ResolvedUpstream("https", "backend", 8443, "/svc/v1"), "/api",
                    false);
            String location = "https://backend/svc/v1/x";

            assertEquals(location, rewriter.rewrite(location));
        }
    }

    @Nested
    @DisplayName("argument contract")
    class ArgumentContract {

        @Test
        @DisplayName("rejects null arguments")
        void rejectsNullArguments() {
            assertThrows(NullPointerException.class, () -> new LocationRewriter(null, "/api", false));
            assertThrows(NullPointerException.class, () -> new LocationRewriter(UPSTREAM, null, false));
            assertThrows(NullPointerException.class, () -> REWRITER.rewrite(null));
        }
    }
}
