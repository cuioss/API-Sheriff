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
package de.cuioss.sheriff.gateway.quarkus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;


import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The transport bounds that act on the management interface, observed on a live management listener.
 * <p>
 * No {@code HttpServerOptionsCustomizer} reaches the management interface, so none of
 * {@code EdgeHardeningOptions}' bounds applies there. What does apply is the part of the
 * {@code quarkus.management.limits.*} family Quarkus hands to the management listener. The shipped
 * {@code application.properties} sets its header block to the main listener's 16 KiB, and keeps the
 * request line and the chunk at their transport defaults, which are tighter than the main listener's.
 * Quarkus applies no HTTP/2 setting to the management listener at all.
 * <p>
 * Each bound is shown by a request just over it, which the listener refuses, and a request just under
 * it, which the listener serves. The header-block case is the one the shipped key changes: under the
 * 20 KiB Quarkus default it would serve the request that is refused here. The request-line case pins a
 * transport default the shipped file relies on. The HTTP/2 case reads the header-list size the listener
 * announces to a prior-knowledge client: the transport's 8 KiB, not the main listener's 16 KiB.
 * <p>
 * The chunk bound is not observed here. The codec does not refuse a larger chunk; it delivers it in
 * pieces of at most that size, which no client can tell apart from the outside.
 * <p>
 * The profile enables the management interface, which the boot-wide test properties disable, on a
 * randomly assigned test port. It sets no bound of its own, so the bounds observed are the shipped
 * ones.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusTest
@TestProfile(ManagementListenerTransportTest.ManagementEnabledProfile.class)
@DisplayName("Management interface — the transport bounds that act on its live listener")
@Tag("quarkus-fork")
class ManagementListenerTransportTest {

    /** The header block the shipped file allows the management listener: the main listener's bound. */
    private static final int HEADER_BLOCK_BOUND = 16 * 1024;

    /** The request-line bound of the management listener: the transport default, kept by the shipped file. */
    private static final int REQUEST_LINE_BOUND = 4096;

    /** The HTTP/2 header-list size the transport announces when nothing sets it. */
    private static final long TRANSPORT_HEADER_LIST_SIZE = 8 * 1024L;

    /** How far past, or short of, a bound the refused and the served request lie. */
    private static final int MARGIN = 512;

    private static final int OK = 200;
    private static final int URI_TOO_LONG = 414;
    private static final int HEADER_FIELDS_TOO_LARGE = 431;

    /** How long one exchange with the management listener may take. */
    private static final int EXCHANGE_TIMEOUT_MILLIS = 10_000;

    /** The liveness probe, relative to the management root path the injected URL carries. */
    private static final String LIVENESS_PATH = "health/live";

    @TestHTTPResource(management = true)
    URL managementUrl;

    @Inject
    Vertx vertx;

    /** Enables the management interface on a randomly assigned test port and sets nothing else. */
    public static final class ManagementEnabledProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.management.enabled", "true",
                    "quarkus.management.test-port", "0");
        }
    }

    @Test
    @DisplayName("refuses a header block past the main listener's 16 KiB with 431, and serves one under it")
    void boundsTheHeaderBlockAtTheMainListenersBound() throws Exception {
        int refused = statusOf(requestWithHeaderBlockOf(HEADER_BLOCK_BOUND + MARGIN));
        int served = statusOf(requestWithHeaderBlockOf(HEADER_BLOCK_BOUND - MARGIN));

        assertAll("the management listener's header block",
                () -> assertEquals(HEADER_FIELDS_TOO_LARGE, refused,
                        "a header block past 16 KiB is refused, below the 20 KiB Quarkus default"),
                () -> assertEquals(OK, served, "control: a header block under the bound is served"));
    }

    @Test
    @DisplayName("refuses a request line past the 4 KiB transport default with 414, and serves one under it")
    void boundsTheRequestLineAtTheTransportDefault() throws Exception {
        int refused = statusOf(requestWithLineOf(REQUEST_LINE_BOUND + MARGIN));
        int served = statusOf(requestWithLineOf(REQUEST_LINE_BOUND - MARGIN));

        assertAll("the management listener's request line",
                () -> assertEquals(URI_TOO_LONG, refused, "a request line past 4 KiB is refused"),
                () -> assertEquals(OK, served, "control: a request line under the bound is served"));
    }

    @Test
    @DisplayName("announces the transport's 8 KiB HTTP/2 header list to a prior-knowledge client")
    void announcesTheTransportHeaderListSize() throws Exception {
        HttpClient client = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setHttp2ClearTextUpgrade(false));
        try {
            HttpClientRequest request = client.request(HttpMethod.GET, managementUrl.getPort(),
                    managementUrl.getHost(), livenessPath())
                    .toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            HttpClientResponse response = request.send()
                    .toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);

            assertAll("the management listener's HTTP/2 settings",
                    () -> assertEquals(HttpVersion.HTTP_2, response.version(),
                            "control: the exchange really ran over HTTP/2"),
                    () -> assertEquals(OK, response.statusCode(), "control: the probe is served"),
                    () -> assertEquals(TRANSPORT_HEADER_LIST_SIZE,
                            request.connection().remoteSettings().getMaxHeaderListSize(),
                            "no HTTP/2 setting reaches the management listener, so it announces the transport's 8 KiB"));
        } finally {
            client.close().toCompletionStage().toCompletableFuture().get(EXCHANGE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * @param headerBlockBytes the size of the header block, the lines after the request line
     * @return a liveness request whose header block is exactly that size, padded by one header
     */
    private String requestWithHeaderBlockOf(int headerBlockBytes) {
        String host = "Host: localhost\r\n";
        String padName = "X-Pad: ";
        int padding = headerBlockBytes - host.length() - padName.length() - "\r\n".length();
        return "GET " + livenessPath() + " HTTP/1.1\r\n" + host + padName + "a".repeat(padding) + "\r\n"
                + "Connection: close\r\n\r\n";
    }

    /**
     * @param lineBytes the length of the request line, without its line terminator
     * @return a liveness request whose request line is exactly that long, padded in the query
     */
    private String requestWithLineOf(int lineBytes) {
        String start = "GET " + livenessPath() + "?pad=";
        String end = " HTTP/1.1";
        return start + "a".repeat(lineBytes - start.length() - end.length()) + end + "\r\n"
                + "Host: localhost\r\nConnection: close\r\n\r\n";
    }

    /** @return the liveness probe beneath the management root path */
    private String livenessPath() {
        String root = managementUrl.getPath();
        return (root.endsWith("/") ? root : root + "/") + LIVENESS_PATH;
    }

    /**
     * Sends one raw HTTP/1.1 request to the management listener and reads the status of its answer.
     *
     * @param request the complete request, head and blank line
     * @return the status code of the answer
     * @throws IOException when the exchange fails or the listener closes without answering
     */
    private int statusOf(String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(managementUrl.getHost(), managementUrl.getPort()),
                    EXCHANGE_TIMEOUT_MILLIS);
            socket.setSoTimeout(EXCHANGE_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            String statusLine = reader.readLine();
            if (statusLine == null) {
                throw new IOException("the management listener closed the connection without answering");
            }
            return Integer.parseInt(statusLine.split(" ", 3)[1]);
        }
    }
}
