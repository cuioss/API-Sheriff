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
package de.cuioss.sheriff.gateway.asset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;

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
    @DisplayName("The fixed content-type map")
    class ContentTypeMap {

        static Stream<Arguments> knownExtensions() {
            return Stream.of(
                    Arguments.of("index.html", "text/html; charset=utf-8"),
                    Arguments.of("app.css", "text/css; charset=utf-8"),
                    Arguments.of("bundle.js", "text/javascript; charset=utf-8"),
                    Arguments.of("data.json", "application/json"),
                    Arguments.of("logo.svg", "image/svg+xml"),
                    Arguments.of("photo.png", "image/png"),
                    Arguments.of("photo.JPG", "image/jpeg"),
                    Arguments.of("font.woff2", "font/woff2"),
                    Arguments.of("module.wasm", "application/wasm"));
        }

        @ParameterizedTest
        @MethodSource("knownExtensions")
        @DisplayName("Should resolve the gateway content type from the extension, case-insensitively")
        void shouldResolveKnownExtension(String filename, String expected) {
            assertEquals(expected, AssetResponseEnvelope.contentTypeFor(filename),
                    () -> "unexpected content type for " + filename);
        }

        @Test
        @DisplayName("Should fall back to application/octet-stream for unknown or absent extensions")
        void shouldFallBackForUnknownExtension() {
            assertAll(
                    () -> assertEquals(AssetResponseEnvelope.DEFAULT_CONTENT_TYPE,
                            AssetResponseEnvelope.contentTypeFor("archive.xyz")),
                    () -> assertEquals(AssetResponseEnvelope.DEFAULT_CONTENT_TYPE,
                            AssetResponseEnvelope.contentTypeFor("README")),
                    () -> assertEquals(AssetResponseEnvelope.DEFAULT_CONTENT_TYPE,
                            AssetResponseEnvelope.contentTypeFor("trailingdot.")));
        }

        @Test
        @DisplayName("Should resolve the extension from the last path segment, ignoring dots in directories")
        void shouldResolveFromLastSegment() {
            assertEquals("text/css; charset=utf-8",
                    AssetResponseEnvelope.contentTypeFor("v1.2/assets/app.css"));
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
                    "index.html", AccessLevel.PUBLIC, sourceHeaders);

            assertAll(
                    () -> assertEquals(AssetResponseEnvelope.NOSNIFF,
                            governed.get(AssetResponseEnvelope.CONTENT_TYPE_OPTIONS)),
                    () -> assertEquals("text/html; charset=utf-8",
                            governed.get(AssetResponseEnvelope.CONTENT_TYPE),
                            "the gateway content type must override the source's claimed type"));
        }

        @Test
        @DisplayName("Should force Cache-Control: no-store for authenticated access regardless of source")
        void shouldForceNoStoreForAuthenticatedAccess() {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("Cache-Control", "public, max-age=31536000");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "secret.json", AccessLevel.AUTHENTICATED, sourceHeaders);

            assertEquals(AssetResponseEnvelope.NO_STORE, governed.get(AssetResponseEnvelope.CACHE_CONTROL),
                    "an authenticated asset must never be cacheable, overriding the source's Cache-Control");
        }

        @Test
        @DisplayName("Should preserve a public asset's source Cache-Control (no forced no-store)")
        void shouldNotForceNoStoreForPublicAccess() {
            Map<String, String> sourceHeaders = new LinkedHashMap<>();
            sourceHeaders.put("Cache-Control", "public, max-age=600");

            Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                    "logo.png", AccessLevel.PUBLIC, sourceHeaders);

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
                    "app.js", AccessLevel.PUBLIC, sourceHeaders);

            assertAll(
                    () -> assertFalse(governed.keySet().stream().anyMatch(k -> "Set-Cookie".equalsIgnoreCase(k)),
                            "an asset action must never establish a session"),
                    () -> assertEquals("kept", governed.get("X-Custom"),
                            "unrelated source headers pass through"));
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
