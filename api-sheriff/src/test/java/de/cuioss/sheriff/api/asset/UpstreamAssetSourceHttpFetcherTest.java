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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;


import de.cuioss.sheriff.api.asset.UpstreamAssetSource.UpstreamFetcher;
import de.cuioss.test.mockwebserver.EnableMockWebServer;
import de.cuioss.test.mockwebserver.URIBuilder;
import de.cuioss.test.mockwebserver.dispatcher.HttpMethodMapper;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcher;
import de.cuioss.test.mockwebserver.dispatcher.ModuleDispatcherElement;

import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the SSRF-guarded {@linkplain UpstreamAssetSource#httpFetcher(Duration, Duration, long)
 * default transport fetcher} against a real in-process server so the streaming
 * {@code CappedByteArrayBodySubscriber} — header mapping, the mid-flight abort at the size
 * cap, and the read-timeout mapping — is covered end-to-end rather than through the
 * injectable seam. The seam-level governance and ordering assertions live in
 * {@link UpstreamAssetSourceTest}.
 */
@EnableMockWebServer
@ModuleDispatcher
class UpstreamAssetSourceHttpFetcherTest {

    private static final long CAP = 16;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final String SMALL_BODY = "hello-asset";
    private static final String OVERSIZED_BODY = "X".repeat(64);

    /**
     * Routes by request target: {@code /assets/large.*} serves an over-cap body,
     * {@code /assets/slow.*} delays its body past a short read timeout, and every other
     * target serves a small body with two mappable headers.
     *
     * @return the routing dispatcher
     */
    public ModuleDispatcherElement getModuleDispatcher() {
        return new ModuleDispatcherElement() {

            @Override
            public String getBaseUrl() {
                return "/assets";
            }

            @Override
            public Optional<MockResponse> handleGet(RecordedRequest request) {
                String target = request.getTarget();
                if (target.contains("large")) {
                    return Optional.of(new MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/octet-stream")
                            .body(OVERSIZED_BODY)
                            .build());
                }
                if (target.contains("slow")) {
                    return Optional.of(new MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "text/plain")
                            .headersDelay(5, TimeUnit.SECONDS)
                            .body("late")
                            .build());
                }
                return Optional.of(new MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "text/javascript")
                        .addHeader("X-Trace", "trace-1")
                        .body(SMALL_BODY)
                        .build());
            }

            @Override
            public Set<HttpMethodMapper> supportedMethods() {
                return Set.of(HttpMethodMapper.GET);
            }
        };
    }

    private static UpstreamFetcher fetcher(Duration readTimeout) {
        return UpstreamAssetSource.httpFetcher(CONNECT_TIMEOUT, readTimeout, CAP);
    }

    private static String headerValue(Map<String, String> headers, String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    @Test
    @DisplayName("Should stream a small body and map the first value per header name")
    void shouldStreamBodyAndMapHeaders(URIBuilder uriBuilder) throws Exception {
        // Arrange
        URI target = uriBuilder.addPathSegments("assets", "small.js").build();

        // Act
        UpstreamFetcher.Fetched fetched = fetcher(Duration.ofSeconds(5)).fetch(target);

        // Assert
        assertAll(
                () -> assertEquals(200, fetched.status()),
                () -> assertEquals(SMALL_BODY, new String(fetched.body(), StandardCharsets.UTF_8),
                        "the streamed body is delivered intact when under the cap"),
                () -> assertEquals("text/javascript", headerValue(fetched.headers(), "Content-Type"),
                        "each header contributes its first value"),
                () -> assertEquals("trace-1", headerValue(fetched.headers(), "X-Trace")));
    }

    @Test
    @DisplayName("Should abort the stream at the cap and cap the returned body at maxBytes + 1")
    void shouldTrimBodyThatExceedsCap(URIBuilder uriBuilder) throws Exception {
        // Arrange — the served body is far larger than the cap.
        URI target = uriBuilder.addPathSegments("assets", "large.bin").build();

        // Act
        UpstreamFetcher.Fetched fetched = fetcher(Duration.ofSeconds(5)).fetch(target);

        // Assert — the mid-flight abort stops pulling and the backstop trims to a stable
        // over-cap signal (maxBytes + 1) so serve() can reject it deterministically.
        assertAll(
                () -> assertEquals(200, fetched.status()),
                () -> assertTrue(fetched.body().length > CAP,
                        "the over-cap signal is preserved for the serve() size check"),
                () -> assertTrue(fetched.body().length <= CAP + 1,
                        "the body never buffers past maxBytes + 1"));
    }

    @Test
    @DisplayName("Should map a body delayed past the read timeout to an UpstreamTimeoutException")
    void shouldRaiseTimeoutOnDelayedBody(URIBuilder uriBuilder) {
        // Arrange — a 300 ms read timeout against a 2 s body delay.
        URI target = uriBuilder.addPathSegments("assets", "slow.js").build();
        UpstreamFetcher fetcher = fetcher(Duration.ofMillis(300));

        // Act + Assert
        assertThrows(UpstreamFetcher.UpstreamTimeoutException.class, () -> fetcher.fetch(target),
                "a read-timeout on the transport surfaces as an UpstreamTimeoutException");
    }
}
