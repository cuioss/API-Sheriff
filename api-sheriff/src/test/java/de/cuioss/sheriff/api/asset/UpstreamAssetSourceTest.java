/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.asset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;


import de.cuioss.sheriff.api.asset.UpstreamAssetSource.UpstreamFetcher;
import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.ResolvedUpstream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UpstreamAssetSource}: the gateway governs every upstream-asset
 * response — a hostile upstream {@code Content-Type} is overridden by the fixed map,
 * {@code Set-Cookie} is stripped, and an authenticated asset is forced to
 * {@code no-store} even when the upstream sent {@code Cache-Control: public}. The
 * SSRF posture is reused, not re-invented: the untrusted remainder is confined before
 * the upstream is touched, a non-allowlisted scheme is refused, a redirect is never
 * followed, and the response size is bounded. Governance and ordering are exercised
 * through the injectable {@link UpstreamFetcher} seam so the assertions are
 * deterministic; the default seam enforces {@code followRedirects(NEVER)} at the
 * transport layer.
 */
class UpstreamAssetSourceTest {

    private static final byte[] BODY = "console.log('x')".getBytes(StandardCharsets.UTF_8);
    private static final int OK = 200;
    private static final int FOUND = 302;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int PAYLOAD_TOO_LARGE = 413;
    private static final int BAD_GATEWAY = 502;
    private static final int GATEWAY_TIMEOUT = 504;
    private static final long MAX_BYTES = 64;

    private static final ResolvedUpstream HTTPS_UPSTREAM =
            new ResolvedUpstream("https", "assets.internal", 443, "/static");

    private static UpstreamFetcher cannedFetcher(int status, Map<String, String> headers, byte[] body) {
        return target -> new UpstreamFetcher.Fetched(status, headers, body);
    }

    private static UpstreamFetcher mustNotFetch() {
        return target -> {
            throw new AssertionError("the upstream must not be touched: " + target);
        };
    }

    private UpstreamAssetSource source(AccessLevel access, UpstreamFetcher fetcher) {
        return new UpstreamAssetSource(HTTPS_UPSTREAM, access, new PathConfinement(), fetcher, MAX_BYTES);
    }

    private static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("Should override a hostile upstream Content-Type from the fixed map and set nosniff")
    void shouldOverrideHostileContentType() {
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Content-Type", "text/html-but-evil"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertAll(
                () -> assertEquals(OK, served.status()),
                () -> assertEquals("text/javascript; charset=utf-8",
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE),
                        "the gateway map overrides the upstream Content-Type"),
                () -> assertEquals(AssetResponseEnvelope.NOSNIFF,
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE_OPTIONS)),
                () -> assertArrayEqualsBody(BODY, served.body()));
    }

    @Test
    @DisplayName("Should strip an upstream Set-Cookie")
    void shouldStripUpstreamSetCookie() {
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Set-Cookie", "SID=1; HttpOnly", "X-Keep", "yes"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertAll(
                () -> assertFalse(served.headers().keySet().stream().anyMatch(k -> "Set-Cookie".equalsIgnoreCase(k)),
                        "an upstream Set-Cookie must never reach the client"),
                () -> assertEquals("yes", served.headers().get("X-Keep")));
    }

    @Test
    @DisplayName("Should force no-store for authenticated access even when the upstream sent Cache-Control: public")
    void shouldForceNoStoreForAuthenticatedOverUpstreamPublic() {
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Cache-Control", "public, max-age=99999"), BODY);

        AssetSource.Served served =
                source(AccessLevel.AUTHENTICATED, fetcher).serve(HttpMethod.GET, "secret.json");

        assertEquals(AssetResponseEnvelope.NO_STORE, served.headers().get(AssetResponseEnvelope.CACHE_CONTROL),
                "an authenticated upstream asset must be forced to no-store");
    }

    @Test
    @DisplayName("Should keep the upstream caching for public access")
    void shouldKeepUpstreamCachingForPublicAccess() {
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Cache-Control", "public, max-age=600"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "logo.png");

        assertEquals("public, max-age=600", served.headers().get(AssetResponseEnvelope.CACHE_CONTROL));
    }

    @Test
    @DisplayName("Should confine the remainder and never touch the upstream on an out-of-root escape")
    void shouldConfineBeforeTouchingUpstream() {
        AssetSource.Served served =
                source(AccessLevel.PUBLIC, mustNotFetch()).serve(HttpMethod.GET, "../../secret");

        assertEquals(NOT_FOUND, served.status(), "an escape must be denied before the upstream is touched");
    }

    @Test
    @DisplayName("Should refuse a non-allowlisted upstream scheme without touching the upstream")
    void shouldRefuseNonAllowlistedScheme() {
        ResolvedUpstream fileScheme = new ResolvedUpstream("file", "assets.internal", 0, "/static");
        UpstreamAssetSource source = new UpstreamAssetSource(
                fileScheme, AccessLevel.PUBLIC, new PathConfinement(), mustNotFetch(), MAX_BYTES);

        AssetSource.Served served = source.serve(HttpMethod.GET, "app.js");

        assertEquals(BAD_GATEWAY, served.status(), "only http/https are allowlisted egress schemes");
    }

    @Test
    @DisplayName("Should reject a write verb without touching the upstream")
    void shouldRejectWriteVerb() {
        AssetSource.Served served =
                source(AccessLevel.PUBLIC, mustNotFetch()).serve(HttpMethod.POST, "app.js");

        assertEquals(METHOD_NOT_ALLOWED, served.status());
    }

    @Test
    @DisplayName("Should never follow a redirect — an upstream 302 is returned as-is with no body")
    void shouldNotFollowRedirect() {
        UpstreamFetcher fetcher = cannedFetcher(FOUND,
                headers("Location", "https://evil.internal/loot"), new byte[0]);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertAll(
                () -> assertEquals(FOUND, served.status(), "the 302 is surfaced, not followed"),
                () -> assertEquals(0, served.body().length, "a non-2xx upstream carries no asset body"));
    }

    @Test
    @DisplayName("Should bound the response size")
    void shouldBoundResponseSize() {
        byte[] oversized = new byte[(int) MAX_BYTES + 1];
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Content-Type", "text/plain"), oversized);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "big.bin");

        assertEquals(PAYLOAD_TOO_LARGE, served.status(), "a body over the cap is refused");
    }

    @Test
    @DisplayName("Should map an upstream timeout to 504")
    void shouldMapTimeoutToGatewayTimeout() {
        UpstreamFetcher fetcher = target -> {
            throw new UpstreamFetcher.UpstreamTimeoutException(new HttpTimeoutException("slow"));
        };

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertEquals(GATEWAY_TIMEOUT, served.status());
    }

    @Test
    @DisplayName("Should serve HEAD with governed headers and no body")
    void shouldServeHeadWithoutBody() {
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Content-Type", "text/plain"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.HEAD, "app.js");

        assertAll(
                () -> assertEquals(OK, served.status()),
                () -> assertEquals(0, served.body().length, "HEAD carries no body"),
                () -> assertEquals("text/javascript; charset=utf-8",
                        served.headers().get(AssetResponseEnvelope.CONTENT_TYPE)));
    }

    @Test
    @DisplayName("Should build the default SSRF-guarded fetcher without error")
    void shouldBuildDefaultFetcher() {
        UpstreamFetcher fetcher = UpstreamAssetSource.httpFetcher(
                UpstreamAssetSource.DEFAULT_CONNECT_TIMEOUT,
                UpstreamAssetSource.DEFAULT_READ_TIMEOUT,
                UpstreamAssetSource.DEFAULT_MAX_BYTES);

        assertNotNull(fetcher, "the default transport fetcher is wired");
    }

    private static void assertArrayEqualsBody(byte[] expected, byte[] actual) {
        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(actual, StandardCharsets.UTF_8),
                "the upstream body should be streamed for a 2xx GET");
    }

    @Test
    @DisplayName("Should append the confined remainder to the resolved base path and fetch it")
    void shouldBuildResolvableUri() {
        // A normal remainder resolves cleanly against the fixed-topology target.
        UpstreamFetcher fetcher = target -> {
            assertEquals(URI.create("https://assets.internal:443/static/nested/app.js"), target,
                    "the confined remainder is appended to the resolved base path");
            return new UpstreamFetcher.Fetched(OK, headers("Content-Type", "text/plain"), BODY);
        };

        AssetSource.Served served =
                source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "nested/app.js");

        assertEquals(OK, served.status());
    }
}
