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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;

import org.jspecify.annotations.Nullable;

/**
 * The secondary-origin {@link AssetSource} (decision: ADR-0014).
 * <p>
 * Serves assets from an upstream reached through the same fixed-topology, SSRF-guarded
 * egress the proxy action uses — no parallel fetch stack of its own. The target is a
 * {@link ResolvedUpstream} decomposed at boot from a topology alias, so the host is
 * never attacker-controlled; the untrusted request remainder is confined by the shared
 * {@link PathConfinement} (over a synthetic base, rejecting traversal/encoding) BEFORE
 * the upstream is touched, and only appended to the resolved base path. The single
 * blocking fetch is delegated to an {@link UpstreamFetcher} seam whose
 * {@linkplain #httpFetcher(Duration, Duration, long) default} embodies the SSRF
 * controls: a scheme allowlist ({@code http}/{@code https}), {@code followRedirects}
 * set to {@code NEVER}, a connect/read timeout, and a bounded response body.
 * <p>
 * Every response is passed through {@link AssetResponseEnvelope}, so the gateway — not
 * the (possibly hostile) upstream — decides the response metadata: the content type is
 * taken from the fixed extension map (overriding the upstream's claim), {@code nosniff}
 * is set, an upstream {@code Set-Cookie} is stripped, and an
 * {@link AccessLevel#AUTHENTICATED} asset is forced to {@code no-store} even when the
 * upstream sent {@code Cache-Control: public}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UpstreamAssetSource implements AssetSource {

    /** The default served-asset size cap (10 MiB). */
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    /** The default upstream connect timeout. */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** The default upstream read timeout. */
    public static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(10);

    private static final int OK = 200;
    private static final int NOT_FOUND = 404;
    private static final int METHOD_NOT_ALLOWED = 405;
    private static final int PAYLOAD_TOO_LARGE = 413;
    private static final int BAD_GATEWAY = 502;
    private static final int GATEWAY_TIMEOUT = 504;
    private static final int LOWEST_ERROR_STATUS = 400;
    private static final byte[] EMPTY_BODY = new byte[0];
    /**
     * The synthetic confinement base: it never touches the filesystem, but gives the
     * shared {@link PathConfinement} a non-root anchor so a remainder that traverses
     * above it is rejected (a {@code "/"} anchor would confine nothing).
     */
    private static final Path CONFINEMENT_BASE = Path.of("/__sheriff_asset_base__");

    private final ResolvedUpstream upstream;
    private final AccessLevel access;
    private final PathConfinement confinement;
    private final UpstreamFetcher fetcher;
    private final long maxBytes;

    /**
     * Creates a source for a fixed-topology upstream and route access level, using the
     * default {@link PathConfinement}, the {@linkplain #httpFetcher(Duration, Duration,
     * long) SSRF-guarded HTTP fetcher}, and the default timeouts and size cap.
     *
     * @param upstream the boot-resolved upstream target (mandatory)
     * @param access   the serving route's effective access level (mandatory)
     */
    public UpstreamAssetSource(ResolvedUpstream upstream, AccessLevel access) {
        this(upstream, access, new PathConfinement(),
                httpFetcher(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, DEFAULT_MAX_BYTES), DEFAULT_MAX_BYTES);
    }

    /**
     * Creates a source with an explicit confinement, fetch seam, and size cap.
     *
     * @param upstream    the boot-resolved upstream target (mandatory)
     * @param access      the serving route's effective access level (mandatory)
     * @param confinement the shared path confinement (mandatory)
     * @param fetcher     the upstream fetch seam (mandatory)
     * @param maxBytes    the maximum served-asset size in bytes
     */
    public UpstreamAssetSource(ResolvedUpstream upstream, AccessLevel access, PathConfinement confinement,
            UpstreamFetcher fetcher, long maxBytes) {
        this.upstream = Objects.requireNonNull(upstream, "upstream");
        this.access = Objects.requireNonNull(access, "access");
        this.confinement = Objects.requireNonNull(confinement, "confinement");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.maxBytes = maxBytes;
    }

    /**
     * Serves the confined upstream asset addressed by {@code remainder}.
     *
     * @param method    the request verb; only {@code GET} and {@code HEAD} are served
     * @param remainder the untrusted request path remainder after the route prefix
     * @return the governed {@link Served} response — {@code 405} for a non-read verb,
     *         {@code 404} for a confinement rejection, {@code 502} for a disallowed
     *         scheme or fetch error, {@code 504} on a timeout, {@code 413} for an
     *         oversized body, otherwise the governed upstream status
     */
    @Override
    public Served serve(HttpMethod method, String remainder) {
        Objects.requireNonNull(method, "method");
        if (!AssetResponseEnvelope.isAllowedMethod(method)) {
            return new Served(METHOD_NOT_ALLOWED, Map.of(), EMPTY_BODY);
        }
        if (!isAllowedScheme(upstream.scheme())) {
            return new Served(BAD_GATEWAY, Map.of(), EMPTY_BODY);
        }
        Optional<String> confined = confinedRemainder(remainder);
        if (confined.isEmpty()) {
            return new Served(NOT_FOUND, Map.of(), EMPTY_BODY);
        }
        URI target;
        try {
            target = upstreamUri(confined.get());
        } catch (URISyntaxException _) {
            return new Served(BAD_GATEWAY, Map.of(), EMPTY_BODY);
        }
        UpstreamFetcher.Fetched fetched;
        try {
            fetched = fetcher.fetch(target);
        } catch (UpstreamFetcher.UpstreamTimeoutException timeout) {
            return new Served(GATEWAY_TIMEOUT, Map.of(), EMPTY_BODY);
        } catch (IOException _) {
            return new Served(BAD_GATEWAY, Map.of(), EMPTY_BODY);
        }
        if (fetched.body().length > maxBytes) {
            return new Served(PAYLOAD_TOO_LARGE, Map.of(), EMPTY_BODY);
        }
        Map<String, String> governed = AssetResponseEnvelope.governedHeaders(
                filenameOf(confined.get()), access, fetched.headers());
        boolean serveBody = method == HttpMethod.GET && fetched.status() < LOWEST_ERROR_STATUS;
        byte[] body = serveBody ? fetched.body() : EMPTY_BODY;
        return new Served(fetched.status(), governed, body);
    }

    private Optional<String> confinedRemainder(String remainder) {
        return confinement.confine(CONFINEMENT_BASE, remainder)
                .map(confined -> "/" + CONFINEMENT_BASE.relativize(confined).toString().replace('\\', '/'));
    }

    private URI upstreamUri(String urlRemainder) throws URISyntaxException {
        String base = upstream.basePath();
        String prefix = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String path = "/".equals(urlRemainder) && prefix.isEmpty() ? "/" : prefix + urlRemainder;
        return new URI(upstream.scheme(), null, upstream.host(), upstream.port(), path, null, null);
    }

    private static String filenameOf(String urlRemainder) {
        int lastSlash = urlRemainder.lastIndexOf('/');
        return lastSlash < 0 ? urlRemainder : urlRemainder.substring(lastSlash + 1);
    }

    private static boolean isAllowedScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /**
     * The SSRF-guarded default fetch seam: a blocking JDK HTTP client that never follows
     * redirects, honours a connect and read timeout, and reads a <strong>streamed</strong>,
     * mid-flight-abort-capped body — consistent with the proxy data plane's
     * {@code DispatchStage.ByteCappedBodyStream} (ADR-0006): the fetch never buffers the whole
     * upstream body before enforcing {@code maxBytes}, it stops pulling further chunks and
     * completes as soon as the cap is crossed.
     * <p>
     * The redirect-{@code NEVER} policy is the SSRF control that keeps a hostile upstream
     * from bouncing the fetch to an internal address; the fixed-topology target supplied
     * by {@link ResolvedUpstream} keeps the host itself off the attacker's control.
     *
     * @param connectTimeout the connect timeout
     * @param readTimeout    the per-request read timeout
     * @param maxBytes       the response body cap in bytes
     * @return the SSRF-guarded fetch seam
     */
    public static UpstreamFetcher httpFetcher(Duration connectTimeout, Duration readTimeout, long maxBytes) {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        return target -> {
            HttpRequest request = HttpRequest.newBuilder(target).timeout(readTimeout).GET().build();
            HttpResponse<byte[]> response;
            try {
                response = client.send(request, info -> new CappedByteArrayBodySubscriber(maxBytes));
            } catch (HttpTimeoutException timeout) {
                throw new UpstreamFetcher.UpstreamTimeoutException(timeout);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("upstream fetch interrupted", interrupted);
            }
            byte[] body = response.body();
            // The subscriber already stopped pulling once the cap was crossed, so body is at most
            // one in-flight chunk past maxBytes; this backstop trims it to a fixed, small over-cap
            // size so callers checking body.length > maxBytes see a stable signal either way.
            if (body.length > maxBytes) {
                body = Arrays.copyOf(body, (int) Math.min(maxBytes + 1, body.length));
            }
            Map<String, String> headers = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    headers.put(name, values.getFirst());
                }
            });
            return new UpstreamFetcher.Fetched(response.statusCode(), headers, body);
        };
    }

    /**
     * A {@link HttpResponse.BodySubscriber} that accumulates the upstream body only up to
     * {@code maxBytes} and then <strong>cancels its subscription</strong> — the mid-flight abort
     * that keeps a large or slow (hostile or merely misbehaving) asset upstream from forcing the
     * gateway to buffer an unbounded response into memory before the size cap has any effect,
     * mirroring {@code DispatchStage.ByteCappedBodyStream}'s streamed request-body cap.
     */
    private static final class CappedByteArrayBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final long maxBytes;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.@Nullable Subscription subscription;
        private long bytesSeen;

        CappedByteArrayBodySubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            // Late chunks may still be delivered after the cap-crossed branch below cancels the
            // subscription and completes the result — cancellation is not instantaneous. Fast-return
            // once the result is settled so the over-cap buffer is bounded at the first cap crossing
            // rather than growing by whatever the publisher delivers during cancel propagation.
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                byte[] chunk = new byte[item.remaining()];
                item.get(chunk);
                buffer.writeBytes(chunk);
                bytesSeen += chunk.length;
            }
            if (bytesSeen > maxBytes && subscription != null) {
                // Abort the in-flight fetch: stop pulling further chunks. The over-cap buffer
                // already accumulated is enough to trip the caller's body.length > maxBytes check
                // (backstop-trimmed there), without ever reading the remainder of a large body.
                subscription.cancel();
                result.complete(buffer.toByteArray());
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            // A no-op when the cap-crossed branch in onNext already completed result.
            result.complete(buffer.toByteArray());
        }
    }

    /**
     * The upstream fetch seam — a single blocking GET against a fixed-topology target,
     * governed for SSRF by its implementation.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface UpstreamFetcher {

        /**
         * Fetches the target, never following redirects.
         *
         * @param target the fully-resolved upstream URI
         * @return the raw upstream response
         * @throws IOException on a transport failure; an
         *                     {@link UpstreamTimeoutException} signals a timeout
         */
        Fetched fetch(URI target) throws IOException;

        /**
         * A raw upstream response, before gateway governance.
         *
         * @param status  the upstream HTTP status
         * @param headers the upstream response headers (first value per name)
         * @param body    the upstream response body
         * @author API Sheriff Team
         * @since 1.0
         */
        record Fetched(int status, Map<String, String> headers, byte[] body) {

            /**
             * Canonical constructor defensively copying the headers and body.
             */
            public Fetched {
                headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
                body = Objects.requireNonNull(body, "body").clone();
            }

            /**
             * @return a defensive copy of the upstream body
             */
            @Override
            public byte[] body() {
                return body.clone();
            }

            /**
             * Value equality over the status, headers, and body <em>content</em> — the
             * generated accessor would compare the {@code body} array by identity, so it
             * is overridden to use {@link Arrays#equals(byte[], byte[])}.
             *
             * @param other the object to compare against
             * @return {@code true} when {@code other} is a {@code Fetched} with the same
             *         status, headers, and body bytes
             */
            @Override
            public boolean equals(Object other) {
                if (this == other) {
                    return true;
                }
                return other instanceof Fetched(var otherStatus, var otherHeaders, var otherBody)
                        && status == otherStatus
                        && headers.equals(otherHeaders)
                        && Arrays.equals(body, otherBody);
            }

            /**
             * Content-based hash consistent with {@link #equals(Object)} — the
             * {@code body} array contributes via {@link Arrays#hashCode(byte[])} rather
             * than identity.
             *
             * @return the content hash
             */
            @Override
            public int hashCode() {
                return Objects.hash(status, headers, Arrays.hashCode(body));
            }

            /**
             * Renders the status and headers with only the body <em>length</em> — the raw
             * upstream body bytes are never dumped, since they may carry sensitive content
             * on this security-focused gateway.
             *
             * @return a body-content-free description of this upstream response
             */
            @Override
            public String toString() {
                return "Fetched[status=%d, headers=%s, body.length=%d]".formatted(status, headers, body.length);
            }
        }

        /**
         * Signals that the upstream fetch exceeded its read timeout.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        final class UpstreamTimeoutException extends IOException {

            private static final long serialVersionUID = 1L;

            /**
             * @param cause the underlying timeout
             */
            public UpstreamTimeoutException(Throwable cause) {
                super("upstream fetch timed out", cause);
            }
        }
    }
}
