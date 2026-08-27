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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;


import de.cuioss.sheriff.gateway.asset.UpstreamAssetSource.UpstreamFetcher;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;

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

    /** A seam that returns the whole upstream response — nothing was cut off at the fetch cap. */
    private static UpstreamFetcher cannedFetcher(int status, Map<String, String> headers, byte[] body) {
        return _ -> new UpstreamFetcher.Fetched(status, headers, body, false);
    }

    /**
     * A seam that stopped at its OWN body cap, so {@code body} is a prefix of the upstream response.
     * This is the shape a fetcher capped below the source's {@code maxBytes} produces.
     */
    private static UpstreamFetcher truncatingFetcher(int status, Map<String, String> headers, byte[] body) {
        return _ -> new UpstreamFetcher.Fetched(status, headers, body, true);
    }

    private static UpstreamFetcher mustNotFetch() {
        return target -> {
            throw new AssertionError("the upstream must not be touched: " + target);
        };
    }

    private UpstreamAssetSource source(AccessLevel access, UpstreamFetcher fetcher) {
        return source(access, fetcher, Map.of());
    }

    private UpstreamAssetSource source(AccessLevel access, UpstreamFetcher fetcher,
            Map<String, String> operatorContentTypes) {
        return new UpstreamAssetSource(HTTPS_UPSTREAM, access, new PathConfinement(), fetcher, MAX_BYTES,
                operatorContentTypes);
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
                () -> assertFalse(served.headers().keySet().stream().anyMatch("Set-Cookie"::equalsIgnoreCase),
                        "an upstream Set-Cookie must never reach the client"),
                () -> assertEquals("yes", served.headers().get("X-Keep")));
    }

    @Test
    @DisplayName("Should strip the upstream's connection-specific headers (issue #172)")
    void shouldStripUpstreamConnectionHeaders() {
        // The header shape any stock origin answers with. Relayed onto the client's connection they
        // make an HTTP/2 response malformed (RFC 9113 §8.2.2), and the client answers the stream
        // with PROTOCOL_ERROR — the asset never arrives at all, while HTTP/1.1 tolerates them and
        // the very same route works. That asymmetry is the whole bug.
        UpstreamFetcher fetcher = cannedFetcher(OK, headers(
                "Connection", "keep-alive",
                "Keep-Alive", "timeout=5",
                "Proxy-Connection", "keep-alive",
                "Transfer-Encoding", "chunked",
                "Content-Length", String.valueOf(BODY.length),
                "Server", "nginx"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertAll(
                () -> assertEquals(OK, served.status()),
                () -> assertFalse(served.headers().keySet().stream().anyMatch("Connection"::equalsIgnoreCase),
                        "the upstream's connection state must never reach the client's connection"),
                () -> assertFalse(served.headers().keySet().stream().anyMatch("Keep-Alive"::equalsIgnoreCase),
                        "Keep-Alive is connection-specific and malformed over HTTP/2"),
                () -> assertFalse(
                        served.headers().keySet().stream().anyMatch("Proxy-Connection"::equalsIgnoreCase),
                        "RFC 9113 §8.2.2 names Proxy-Connection connection-specific alongside Connection"),
                () -> assertFalse(
                        served.headers().keySet().stream().anyMatch("Transfer-Encoding"::equalsIgnoreCase),
                        "the client's framing is the edge's to choose, never the upstream's"),
                () -> assertFalse(served.headers().keySet().stream().anyMatch("Content-Length"::equalsIgnoreCase),
                        "the edge recomputes the length from the buffer it writes"),
                () -> assertEquals("nginx", served.headers().get("Server"),
                        "an end-to-end upstream header still passes through"),
                () -> assertArrayEqualsBody(BODY, served.body()));
    }

    @Test
    @DisplayName("Should strip the upstream Content-Length on a body-less outcome")
    void shouldStripContentLengthOnBodylessOutcome() {
        // A 404 is served with an empty body, so relaying the upstream's Content-Length would
        // declare bytes that never follow — a hang on HTTP/1.1 and a mismatch on HTTP/2. The same
        // holds for HEAD, which is why the strip is unconditional rather than status-dependent.
        UpstreamFetcher fetcher = cannedFetcher(NOT_FOUND, headers("Content-Length", "1234"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "missing.js");

        assertAll(
                () -> assertEquals(NOT_FOUND, served.status()),
                () -> assertEquals(0, served.body().length, "an upstream error status is served body-less"),
                () -> assertFalse(served.headers().keySet().stream().anyMatch("Content-Length"::equalsIgnoreCase),
                        "a length describing a body the gateway does not serve must not be relayed"));
    }

    @Test
    @DisplayName("Should strip an HTTP/2 pseudo-header an h2-negotiated upstream fetch reports")
    void shouldStripUpstreamPseudoHeader() {
        // The JDK HTTP client negotiates HTTP/2 by default, so an https asset upstream advertising
        // h2 in its ALPN set hands the fetch seam a ":status" entry among the response headers.
        UpstreamFetcher fetcher = cannedFetcher(OK, headers(":status", "200", "X-Keep", "yes"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertAll(
                () -> assertFalse(served.headers().containsKey(":status"),
                        "a pseudo-header in a normal header block is malformed by definition"),
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
                fileScheme, AccessLevel.PUBLIC, new PathConfinement(), mustNotFetch(), MAX_BYTES, Map.of());

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

    // --- Fetch-cap / serve-cap relation ----------------------------------------------------------
    // A fetch seam capped BELOW this source's maxBytes returns a body cut off at its own limit —
    // short enough to pass every length check serve() could apply, so it used to be served as a
    // normal 200 carrying a partial asset. The relation is now carried by an explicit signal on the
    // seam rather than by a prose caller invariant, and the three tests below pin it as a matched
    // set: the refusal, the positive control that stops it being unconditional, and the negative
    // control that proves the SIGNAL drives it rather than the body length.

    @Test
    @DisplayName("Should refuse a truncated upstream fetch rather than serving a partial asset")
    void shouldRefuseTruncatedFetch() {
        byte[] prefix = "console.log('x".getBytes(StandardCharsets.UTF_8);
        UpstreamFetcher fetcher = truncatingFetcher(OK, headers("Content-Type", "text/plain"), prefix);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertAll(
                () -> assertEquals(PAYLOAD_TOO_LARGE, served.status(),
                        "a fetch that stopped at its own cap must be refused — serving it would pass "
                                + "a prefix of the asset off as the whole asset"),
                () -> assertEquals(0, served.body().length,
                        "the refusal carries no body, so no partial asset reaches the caller"));
    }

    @Test
    @DisplayName("Should still serve a complete at-cap body untouched")
    void shouldServeCompleteAtCapBody() {
        // THE POSITIVE CONTROL for the refusal above. Without it, a source that refused every fetch
        // — or one that read the truncation signal inverted — would satisfy the refusal test while
        // serving nothing at all.
        byte[] atCap = new byte[(int) MAX_BYTES];
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Content-Type", "text/plain"), atCap);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertAll(
                () -> assertEquals(OK, served.status(),
                        "a complete body exactly at the cap is within it and serves"),
                () -> assertEquals((int) MAX_BYTES, served.body().length,
                        "the at-cap body serves in full, not truncated"));
    }

    @Test
    @DisplayName("Should refuse a truncated fetch on the signal alone, not on the body length")
    void shouldRefuseTruncatedFetchOnTheSignalNotTheLength() {
        // THE NEGATIVE CONTROL. This body is far UNDER the cap, so every length-based check passes
        // it — exactly the case a fetcher capped below maxBytes produces. Only the explicit
        // truncation signal can refuse it, so this test fails the moment serve() goes back to
        // deciding on body.length alone.
        byte[] tinyPrefix = new byte[1];
        UpstreamFetcher fetcher = truncatingFetcher(OK, headers("Content-Type", "text/plain"), tinyPrefix);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertAll(
                () -> assertTrue(tinyPrefix.length < MAX_BYTES,
                        "the staged body must sit UNDER the cap for this to test the signal rather "
                                + "than the length"),
                () -> assertEquals(PAYLOAD_TOO_LARGE, served.status(),
                        "a truncated fetch is refused however short its body is — the length cannot "
                                + "carry the fact that bytes were cut off"));
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
    @DisplayName("Should map a transport IOException to 502")
    void shouldMapFetchFailureToBadGateway() {
        UpstreamFetcher fetcher = target -> {
            throw new IOException("connection refused");
        };

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "app.js");

        assertEquals(BAD_GATEWAY, served.status(), "a transport failure surfaces as 502");
    }

    @Test
    @DisplayName("Should construct the default source with the SSRF-guarded transport fetcher")
    void shouldConstructWithDefaultFetcher() {
        assertDoesNotThrow(() -> new UpstreamAssetSource(HTTPS_UPSTREAM, AccessLevel.PUBLIC, Map.of()),
                "the short constructor wires the default confinement, transport fetcher, timeouts, and cap");
    }

    @Test
    @DisplayName("Should serve an operator-declared extension with the operator's content type")
    void shouldServeOperatorDeclaredExtension() {
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Content-Type", "text/html-but-evil"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher,
                Map.of("webmanifest", "application/manifest+json")).serve(HttpMethod.GET, "site.webmanifest");

        assertEquals("application/manifest+json", served.headers().get(AssetResponseEnvelope.CONTENT_TYPE),
                "an extension the gateway does not map resolves to the operator's value, and the "
                        + "hostile upstream Content-Type is still overridden");
    }

    @Test
    @DisplayName("Should keep the built-in content type when an operator entry names a built-in extension")
    void shouldKeepBuiltInContentTypeAgainstOperatorOverride() {
        UpstreamFetcher fetcher = cannedFetcher(OK, headers("Content-Type", "text/html"), BODY);

        AssetSource.Served served = source(AccessLevel.PUBLIC, fetcher,
                Map.of("svg", "text/html; charset=utf-8")).serve(HttpMethod.GET, "logo.svg");

        assertEquals("image/svg+xml", served.headers().get(AssetResponseEnvelope.CONTENT_TYPE),
                "remapping svg to text/html is the stored-XSS lever the add-only ruling removes — "
                        + "the built-in wins here, and the entry is refused at boot");
    }

    @Test
    @DisplayName("Fetched equals/hashCode compare the body by content, not array identity")
    void fetchedEqualsIsContentBased() {
        Map<String, String> headers = headers("Content-Type", "text/plain");
        UpstreamFetcher.Fetched first = new UpstreamFetcher.Fetched(OK, headers, BODY.clone(), false);
        UpstreamFetcher.Fetched second = new UpstreamFetcher.Fetched(OK, headers, BODY.clone(), false);

        assertAll(
                () -> assertEquals(first, second, "two Fetched with equal body bytes are equal"),
                () -> assertEquals(first.hashCode(), second.hashCode(), "equal Fetched share a hashCode"),
                () -> assertEquals(first, first, "a Fetched equals itself"));
    }

    @Test
    @DisplayName("Fetched with differing body bytes are not equal")
    void fetchedWithDifferentBodyNotEqual() {
        Map<String, String> headers = headers("Content-Type", "text/plain");
        UpstreamFetcher.Fetched first = new UpstreamFetcher.Fetched(OK, headers, BODY, false);
        UpstreamFetcher.Fetched other = new UpstreamFetcher.Fetched(OK, headers,
                "different".getBytes(StandardCharsets.UTF_8), false);

        assertAll(
                () -> assertNotEquals(first, other, "differing body bytes break equality"),
                () -> assertNotEquals(first, new UpstreamFetcher.Fetched(NOT_FOUND, headers, BODY, false),
                        "a differing status breaks equality"),
                () -> assertNotEquals(first, new UpstreamFetcher.Fetched(OK, headers, BODY, true),
                        "a differing truncation signal breaks equality — a prefix and the whole "
                                + "response are not the same value even when their bytes match"),
                () -> assertNotEquals(BODY, first, "a Fetched never equals an unrelated type"));
    }

    @Test
    @DisplayName("Fetched toString reports only the body length, never the raw bytes")
    void fetchedToStringHidesBody() {
        byte[] secret = "top-secret-token".getBytes(StandardCharsets.UTF_8);
        UpstreamFetcher.Fetched fetched =
                new UpstreamFetcher.Fetched(OK, headers("Content-Type", "text/plain"), secret, true);

        String rendered = fetched.toString();

        assertAll(
                () -> assertTrue(rendered.contains("body.length=" + secret.length),
                        "the length is rendered for diagnostics"),
                () -> assertTrue(rendered.contains("truncated=true"),
                        "the truncation signal is rendered — a diagnostic that omitted it would hide "
                                + "why an apparently fine response was refused"),
                () -> assertFalse(rendered.contains("top-secret-token"),
                        "the raw upstream body bytes must never be dumped"));
    }

    @Test
    @DisplayName("Should build the default SSRF-guarded fetcher without error")
    void shouldBuildDefaultFetcher() {
        UpstreamFetcher fetcher = UpstreamAssetSource.httpFetcher(
                UpstreamAssetSource.DEFAULT_CONNECT_TIMEOUT,
                UpstreamAssetSource.DEFAULT_READ_TIMEOUT,
                AssetSource.DEFAULT_MAX_BYTES);

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
            return new UpstreamFetcher.Fetched(OK, headers("Content-Type", "text/plain"), BODY, false);
        };

        AssetSource.Served served =
                source(AccessLevel.PUBLIC, fetcher).serve(HttpMethod.GET, "nested/app.js");

        assertEquals(OK, served.status());
    }
}
