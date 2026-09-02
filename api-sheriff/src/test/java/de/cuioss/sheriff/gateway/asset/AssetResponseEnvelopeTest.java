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
package de.cuioss.sheriff.gateway.asset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.edge.ResponseStage;
import de.cuioss.sheriff.gateway.http.ConnectionHeaders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests for {@link AssetResponseEnvelope}: the gateway-owned response governance that
 * a backing source can never override — the fixed content-type map, {@code nosniff},
 * forced {@code no-store} for authenticated access, {@code Set-Cookie} stripping, and
 * {@code GET}/{@code HEAD}-only serving — plus the sealed {@link AssetSource} seam
 * that carries the auth-before-source-resolution ordering contract.
 */
class AssetResponseEnvelopeTest {

    @Nested
    @DisplayName("The built-in content-type map, with no operator additions configured")
    class ContentTypeMap {

        /**
         * Every one of the built-in extensions with the content type it must resolve to. The list is
         * exhaustive on purpose: the add-only ruling makes the built-in map an immutable contract, so
         * this is the regression fence proving an absent {@code asset_defaults} block resolves each
         * one exactly as it did before the block existed.
         */
        static Stream<Arguments> knownExtensions() {
            return Stream.of(
                    Arguments.of("index.html", "text/html; charset=utf-8"),
                    Arguments.of("index.htm", "text/html; charset=utf-8"),
                    Arguments.of("app.css", "text/css; charset=utf-8"),
                    Arguments.of("bundle.js", "text/javascript; charset=utf-8"),
                    Arguments.of("module.mjs", "text/javascript; charset=utf-8"),
                    Arguments.of("data.json", "application/json"),
                    Arguments.of("bundle.map", "application/json"),
                    Arguments.of("feed.xml", "application/xml"),
                    Arguments.of("notes.txt", "text/plain; charset=utf-8"),
                    Arguments.of("logo.svg", "image/svg+xml"),
                    Arguments.of("photo.png", "image/png"),
                    Arguments.of("photo.jpg", "image/jpeg"),
                    Arguments.of("photo.jpeg", "image/jpeg"),
                    Arguments.of("anim.gif", "image/gif"),
                    Arguments.of("photo.webp", "image/webp"),
                    Arguments.of("favicon.ico", "image/x-icon"),
                    Arguments.of("font.woff", "font/woff"),
                    Arguments.of("font.woff2", "font/woff2"),
                    Arguments.of("font.ttf", "font/ttf"),
                    Arguments.of("manual.pdf", "application/pdf"),
                    Arguments.of("module.wasm", "application/wasm"),
                    Arguments.of("photo.JPG", "image/jpeg"));
        }

        @ParameterizedTest
        @MethodSource("knownExtensions")
        @DisplayName("Should resolve the gateway content type from the extension, case-insensitively")
        void shouldResolveKnownExtension(String filename, String expected) {
            assertEquals(expected, AssetResponseEnvelope.contentTypeFor(filename, Map.of()),
                    () -> "unexpected content type for " + filename);
        }

        @Test
        @DisplayName("Should expose exactly the 21 built-in extensions as the immutable set")
        void shouldExposeBuiltInExtensions() {
            Set<String> builtIn = AssetResponseEnvelope.builtInExtensions();

            assertEquals(Set.of("html", "htm", "css", "js", "mjs", "json", "map", "xml", "txt", "svg",
                            "png", "jpg", "jpeg", "gif", "webp", "ico", "woff", "woff2", "ttf", "pdf", "wasm"),
                    builtIn,
                    "the built-in extension set is the boot validator's source of truth for the "
                            + "add-only refusal and must not drift from the map itself");
        }

        @Test
        @DisplayName("Should fall back to application/octet-stream for unknown or absent extensions")
        void shouldFallBackForUnknownExtension() {
            assertAll(
                    () -> assertEquals(AssetResponseEnvelope.DEFAULT_CONTENT_TYPE,
                            AssetResponseEnvelope.contentTypeFor("archive.xyz", Map.of())),
                    () -> assertEquals(AssetResponseEnvelope.DEFAULT_CONTENT_TYPE,
                            AssetResponseEnvelope.contentTypeFor("README", Map.of())),
                    () -> assertEquals(AssetResponseEnvelope.DEFAULT_CONTENT_TYPE,
                            AssetResponseEnvelope.contentTypeFor("trailingdot.", Map.of())));
        }

        @Test
        @DisplayName("Should resolve the extension from the last path segment, ignoring dots in directories")
        void shouldResolveFromLastSegment() {
            assertEquals("text/css; charset=utf-8",
                    AssetResponseEnvelope.contentTypeFor("v1.2/assets/app.css", Map.of()));
        }
    }

    @Nested
    @DisplayName("The add-only operator content-type additions")
    class OperatorContentTypes {

        @Test
        @DisplayName("Should resolve an operator entry for an extension the gateway does not map")
        void shouldResolveOperatorAddition() {
            Map<String, String> operatorTypes = Map.of("webmanifest", "application/manifest+json");

            assertEquals("application/manifest+json",
                    AssetResponseEnvelope.contentTypeFor("site.webmanifest", operatorTypes),
                    "an unmapped extension is exactly what the operator block exists to teach");
        }

        @Test
        @DisplayName("Should still fall back for an extension neither the built-in nor the operator map carries")
        void shouldFallBackForExtensionInNeitherMap() {
            Map<String, String> operatorTypes = Map.of("webmanifest", "application/manifest+json");

            assertEquals(AssetResponseEnvelope.DEFAULT_CONTENT_TYPE,
                    AssetResponseEnvelope.contentTypeFor("archive.xyz", operatorTypes),
                    "an undeclared, unmapped extension keeps the terminal fallback");
        }

        @ParameterizedTest
        @MethodSource("de.cuioss.sheriff.gateway.asset.AssetResponseEnvelopeTest$ContentTypeMap#knownExtensions")
        @DisplayName("Should resolve every built-in identically whether or not an operator block is configured")
        void shouldResolveBuiltInsIdenticallyWithOperatorBlock(String filename, String expected) {
            Map<String, String> operatorTypes = Map.of("webmanifest", "application/manifest+json");

            assertEquals(expected, AssetResponseEnvelope.contentTypeFor(filename, operatorTypes),
                    () -> "a configured operator block must not perturb the built-in resolution of "
                            + filename);
        }

        @Test
        @DisplayName("Should let the built-in map win over an operator entry naming a built-in extension")
        void shouldRefuseOperatorOverrideOfBuiltIn() {
            Map<String, String> hostile = Map.of("svg", "text/html; charset=utf-8");

            assertAll(
                    () -> assertEquals("image/svg+xml",
                            AssetResponseEnvelope.contentTypeFor("logo.svg", hostile),
                            "remapping svg to text/html is the stored-XSS lever the add-only ruling "
                                    + "removes: the built-in mapping must win at runtime"),
                    () -> assertTrue(AssetResponseEnvelope.builtInExtensions().contains("svg"),
                            "and the boot validator refuses such an entry outright — see "
                                    + "ConfigValidatorTest"));
        }
    }

    @Nested
    @DisplayName("The governed response headers")
    class GovernedHeaders {

        @Test
        @DisplayName("Should always set X-Content-Type-Options: nosniff and override the content type")
        void shouldSetNosniffAndOverrideContentType() {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("Content-Type", "text/plain");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "index.html", AccessLevel.PUBLIC, sourceHeaders, Map.of());

            assertAll(
                    () -> assertEquals(AssetResponseEnvelope.NOSNIFF,
                            governed.get(AssetResponseEnvelope.CONTENT_TYPE_OPTIONS)),
                    () -> assertEquals("text/html; charset=utf-8",
                            governed.get(AssetResponseEnvelope.CONTENT_TYPE),
                            "the gateway content type must override the source's claimed type"));
        }

        @Test
        @DisplayName("Should forward the operator additions into the resolved Content-Type")
        void shouldForwardOperatorTypesIntoContentType() {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("Content-Type", "text/plain");
            Map<String, String> operatorTypes = Map.of("webmanifest", "application/manifest+json");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "site.webmanifest", AccessLevel.PUBLIC, sourceHeaders, operatorTypes);

            assertEquals("application/manifest+json", governed.get(AssetResponseEnvelope.CONTENT_TYPE),
                    "the operator addition must reach the governed header, still overriding the source");
        }

        @Test
        @DisplayName("Should force Cache-Control: no-store for authenticated access regardless of source")
        void shouldForceNoStoreForAuthenticatedAccess() {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("Cache-Control", "public, max-age=31536000");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "secret.json", AccessLevel.AUTHENTICATED, sourceHeaders, Map.of());

            assertEquals(AssetResponseEnvelope.NO_STORE, governed.get(AssetResponseEnvelope.CACHE_CONTROL),
                    "an authenticated asset must never be cacheable, overriding the source's Cache-Control");
        }

        @Test
        @DisplayName("Should preserve a public asset's source Cache-Control (no forced no-store)")
        void shouldNotForceNoStoreForPublicAccess() {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("Cache-Control", "public, max-age=600");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "logo.png", AccessLevel.PUBLIC, sourceHeaders, Map.of());

            assertEquals("public, max-age=600", governed.get(AssetResponseEnvelope.CACHE_CONTROL),
                    "a public asset keeps the source's caching");
        }

        @Test
        @DisplayName("Should strip Set-Cookie the source proposed, case-insensitively")
        void shouldStripSetCookie() {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("set-cookie", "SESSION=abc; HttpOnly");
            sourceHeaders.put("X-Custom", "kept");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "app.js", AccessLevel.PUBLIC, sourceHeaders, Map.of());

            assertAll(
                    () -> assertFalse(governed.keySet().stream().anyMatch("Set-Cookie"::equalsIgnoreCase),
                            "an asset action must never establish a session"),
                    () -> assertEquals("kept", governed.get("X-Custom"),
                            "unrelated source headers pass through"));
        }
    }

    /**
     * The connection-specific strip — the governance that keeps an {@code source: upstream} asset
     * route servable over HTTP/2 (issue #172).
     * <p>
     * A source's hop headers describe the source's connection, not the client's. Re-emitting one
     * makes the response malformed under RFC 9113 §8.2.2, and an HTTP/2 client answers the stream
     * with {@code PROTOCOL_ERROR} — the response is discarded whole, which is why the symptom was
     * an empty reply rather than a wrong header.
     */
    @Nested
    @DisplayName("The connection-specific and framing header strip")
    class ConnectionSpecificStrip {

        /**
         * Every stripped name, sourced from the production set rather than restated — a name added
         * there without a case here would otherwise go unexercised. The mixed casing is deliberate:
         * an origin sends {@code Connection}, not {@code connection}, so the case-insensitive match
         * is what is actually under test.
         */
        static Stream<String> strippedNames() {
            return ConnectionHeaders.RESPONSE_STRIP.stream();
        }

        @ParameterizedTest
        @MethodSource("strippedNames")
        @DisplayName("Should strip each connection-specific header the source proposed")
        void shouldStripConnectionSpecificHeader(String name) {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put(capitalized(name), "whatever");
            sourceHeaders.put("X-Custom", "kept");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "index.html", AccessLevel.PUBLIC, sourceHeaders, Map.of());

            assertAll(
                    () -> assertFalse(governed.keySet().stream().anyMatch(name::equalsIgnoreCase),
                            () -> name + " describes the source's connection, never the client's — "
                                    + "re-emitting it makes an HTTP/2 response malformed"),
                    () -> assertEquals("kept", governed.get("X-Custom"),
                            "unrelated source headers still pass through"));
        }

        @Test
        @DisplayName("Should strip the whole hop set at once, as a real origin sends it")
        void shouldStripTheWholeHopSet() {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("Connection", "keep-alive");
            sourceHeaders.put("Keep-Alive", "timeout=5");
            sourceHeaders.put("Proxy-Connection", "keep-alive");
            sourceHeaders.put("Transfer-Encoding", "chunked");
            sourceHeaders.put("Content-Length", "530");
            sourceHeaders.put("Server", "nginx");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "index.html", AccessLevel.PUBLIC, sourceHeaders, Map.of());

            assertAll(
                    () -> assertEquals(Set.of(AssetResponseEnvelope.CONTENT_TYPE,
                                    AssetResponseEnvelope.CONTENT_TYPE_OPTIONS, "Server"), governed.keySet(),
                            "only the gateway's own headers and the end-to-end Server header survive"),
                    () -> assertEquals("nginx", governed.get("Server")));
        }

        @Test
        @DisplayName("Should strip an HTTP/2 pseudo-header a source reached over h2 reports")
        void shouldStripPseudoHeaders() {
            // The JDK HTTP client surfaces :status among the response headers when it negotiated
            // HTTP/2 with the upstream, so an https asset upstream with h2 in its ALPN set hands
            // the gateway one. A pseudo-header in a normal header block is malformed by definition.
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put(":status", "200");
            sourceHeaders.put("X-Custom", "kept");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "app.js", AccessLevel.PUBLIC, sourceHeaders, Map.of());

            assertAll(
                    () -> assertFalse(governed.containsKey(":status"),
                            "a pseudo-header is never a real field the gateway may re-emit"),
                    () -> assertEquals("kept", governed.get("X-Custom")));
        }

        /**
         * The both-paths fence. {@link ConnectionHeaders} is the one declaration, but a shared
         * constant only helps if both response paths actually consult it — a path that kept a local
         * copy would compile, pass its own tests, and reintroduce issue #172 the moment a name was
         * added here alone. This walks the shared set through the proxy relay's own public
         * forwardability predicate, so the proxy path is proven to honour every name the asset path
         * strips, not merely to have the same constant on its classpath.
         */
        @Test
        @DisplayName("Should strip nothing the proxy data plane's ResponseStage would forward")
        void shouldAgreeWithTheProxyResponseStrip() {
            for (String name : ConnectionHeaders.RESPONSE_STRIP) {
                assertFalse(ResponseStage.isForwardableResponseHeader(name, true),
                        () -> name + " is stripped by the asset envelope but forwarded by "
                                + "ResponseStage — the two response paths have drifted");
            }
        }

        private static String capitalized(String name) {
            return Character.toUpperCase(name.charAt(0)) + name.substring(1);
        }
    }

    @Nested
    @DisplayName("The read-only verb enforcement")
    class MethodEnforcement {

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"GET", "HEAD"})
        @DisplayName("Should serve GET and HEAD")
        void shouldAllowReadVerbs(HttpMethod method) {
            assertTrue(AssetResponseEnvelope.isAllowedMethod(method),
                    () -> method + " should be servable by an asset action");
        }

        @ParameterizedTest
        @EnumSource(value = HttpMethod.class, names = {"POST", "PUT", "PATCH", "DELETE", "OPTIONS"})
        @DisplayName("Should reject every write / non-read verb")
        void shouldRejectNonReadVerbs(HttpMethod method) {
            assertFalse(AssetResponseEnvelope.isAllowedMethod(method),
                    () -> method + " must not be servable by an asset action");
        }
    }

    @Nested
    @DisplayName("The sealed asset-source seam (auth-before-source-resolution ordering)")
    class SourceSeam {

        @Test
        @DisplayName("Should seal the source hierarchy to exactly the directory and upstream sources")
        void shouldSealSourceHierarchy() {
            assertTrue(AssetSource.class.isSealed(), "the asset-source seam must be sealed");
            List<Class<?>> permitted = List.of(AssetSource.class.getPermittedSubclasses());
            assertAll(
                    () -> assertEquals(2, permitted.size(), "exactly two source kinds are permitted"),
                    () -> assertTrue(permitted.contains(DirectoryAssetSource.class),
                            "the local-directory source is a permitted seam member"),
                    () -> assertTrue(permitted.contains(UpstreamAssetSource.class),
                            "the secondary-origin source is a permitted seam member"));
        }

        @Test
        @DisplayName("Should have both sources implement the sealed seam")
        void shouldHaveSourcesImplementSeam() {
            assertAll(
                    () -> assertTrue(AssetSource.class.isAssignableFrom(DirectoryAssetSource.class),
                            "DirectoryAssetSource must implement AssetSource"),
                    () -> assertTrue(AssetSource.class.isAssignableFrom(UpstreamAssetSource.class),
                            "UpstreamAssetSource must implement AssetSource"));
        }
    }
}
