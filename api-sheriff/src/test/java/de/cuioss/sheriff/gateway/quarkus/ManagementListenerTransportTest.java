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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The transport bounds that act on the management interface, observed on a live management listener.
 * <p>
 * No {@code HttpServerOptionsCustomizer} reaches the management interface, so none of
 * {@code EdgeHardeningOptions}' bounds applies there. What does apply is the part of the
 * {@code quarkus.management.limits.*} family Quarkus 3.40.1 hands to the management interface: six
 * listener options ({@code HttpServerOptionsUtils.applyCommonOptionsForManagementInterface}) — the
 * header block, the request line, the chunk and three form limits — and the body size, which the body
 * handler Quarkus installs on every management route enforces. The shipped
 * {@code application.properties} sets the header block to the main listener's 16 KiB and the body to
 * 32 KiB, and keeps every other bound at its default. Quarkus applies no HTTP/2 setting to the
 * management listener at all.
 * <p>
 * Every bound but the chunk is shown by a request past it, which is refused, and a matched request
 * under or at it, which the liveness probe serves; for HTTP/2 the announced setting is read instead.
 * The header-block and body cases are the two the shipped keys change: under the 20 KiB header and
 * 10 MiB body Quarkus defaults each would serve the request that is refused here. Each listed default
 * SmallRye Health route ({@code health}, {@code health/live}, {@code health/ready},
 * {@code health/started}, {@code health/group}, {@code health/well}) and the {@code metrics} route
 * still answers a body-less {@code GET} at the body bound, and the form
 * and chunk cases below stay green under it, which is what shows the body bound pre-empts none of
 * them. The request-line case pins a transport default the shipped
 * file relies on. The HTTP/2 case reads the header-list size the listener announces to a
 * prior-knowledge client: the transport's 8 KiB, not the main listener's 16 KiB.
 * <p>
 * The form limits act because the management body handler decodes a URL-encoded body on every
 * management route, the liveness probe included, which also answers a {@code POST}. Each is exact, so
 * the refused form lies one unit past its bound and the served form at it, and each refusal is a
 * {@code 400}: 257 fields refused against 256 served, one attribute of 2049 bytes against 2048, and
 * 1025 undecoded bytes against 1024. The field count is checked as the decoder completes a field while
 * the body is still arriving; the field that ends the body is decoded only after it has arrived and is
 * not counted, so the refused form ends its 257th field with a separator. The buffered-bytes bound
 * holds the part of the body the decoder has not decoded yet, which is a field name still waiting for
 * its {@code =}; the refused form is such a name, while a name followed by its {@code =} is decoded at
 * once and never held. The body size is refused with a {@code 413} on the declared
 * {@code Content-Length} alone, before any body byte is read, and the control carries a body of exactly
 * the shipped 32 KiB.
 * <p>
 * The chunk bound refuses nothing, and that is what is asserted: a chunk twice its 8 KiB is served. The
 * codec delivers a larger chunk to the server in pieces of at most that size; the pieces are reassembled
 * before anything is answered, so no client can observe the split itself.
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

    /** The form field count of the management listener: the Quarkus default, kept by the shipped file. */
    private static final int FORM_FIELDS_BOUND = 256;

    /** The size of one form attribute on the management listener: the Quarkus default, kept by the shipped file. */
    private static final int FORM_ATTRIBUTE_BOUND = 2048;

    /** The undecoded form bytes the management listener buffers: the Quarkus default, kept by the shipped file. */
    private static final int FORM_BUFFERED_BOUND = 1024;

    /** The chunk bound of the management listener: the transport default, kept by the shipped file. */
    private static final int CHUNK_BOUND = 8 * 1024;

    /** The body bound of the management routes: the 32 KiB the shipped file sets, below the 10 MiB Quarkus default. */
    private static final int BODY_BOUND = 32 * 1024;

    /** How far past, or short of, a bound the refused and the served request lie. */
    private static final int MARGIN = 512;

    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int PAYLOAD_TOO_LARGE = 413;
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

    @Test
    @DisplayName("refuses a form whose 257th field completes while the body arrives with 400, and serves 256")
    void boundsTheFormFieldCount() throws Exception {
        int refused = statusOf(formRequest(formFields(FORM_FIELDS_BOUND + 1) + "&"));
        int served = statusOf(formRequest(formFields(FORM_FIELDS_BOUND) + "&"));

        assertAll("the management router's form field count",
                () -> assertEquals(BAD_REQUEST, refused, "a form field past the 256th is refused"),
                () -> assertEquals(OK, served, "control: a form of 256 fields is served"));
    }

    @Test
    @DisplayName("refuses a form attribute past 2 KiB with 400, and serves one of exactly 2 KiB")
    void boundsTheFormAttributeSize() throws Exception {
        int refused = statusOf(formRequest("a=" + "v".repeat(FORM_ATTRIBUTE_BOUND + 1)));
        int served = statusOf(formRequest("a=" + "v".repeat(FORM_ATTRIBUTE_BOUND)));

        assertAll("the management router's form attribute size",
                () -> assertEquals(BAD_REQUEST, refused, "a form attribute past 2048 bytes is refused"),
                () -> assertEquals(OK, served, "control: a form attribute of 2048 bytes is served"));
    }

    @Test
    @DisplayName("refuses an undecoded form remainder past 1 KiB with 400, and serves one of exactly 1 KiB")
    void boundsTheBufferedFormBytes() throws Exception {
        int refused = statusOf(formRequest("n".repeat(FORM_BUFFERED_BOUND + 1)));
        int served = statusOf(formRequest("n".repeat(FORM_BUFFERED_BOUND)));

        assertAll("the management router's buffered form bytes",
                () -> assertEquals(BAD_REQUEST, refused, "an undecoded form remainder past 1024 bytes is refused"),
                () -> assertEquals(OK, served, "control: an undecoded form remainder of 1024 bytes is served"));
    }

    @Test
    @DisplayName("serves a chunk twice the 8 KiB chunk bound: the bound splits a chunk, it does not refuse it")
    void servesAChunkPastTheChunkBound() throws Exception {
        assertEquals(OK, statusOf(chunkedRequestWithOneChunkOf(2 * CHUNK_BOUND)),
                "a chunk past the 8 KiB chunk bound is served, not refused");
    }

    @Test
    @DisplayName("refuses a declared body past the shipped 32 KiB with 413, and serves a body of exactly 32 KiB")
    void boundsTheBodyAtTheShippedBound() throws Exception {
        int refused = statusOf(requestDeclaringBodyOf(BODY_BOUND + 1));
        int served = statusOf(requestWithBodyOf(BODY_BOUND));

        assertAll("the management router's body size",
                () -> assertEquals(PAYLOAD_TOO_LARGE, refused,
                        "a declared body one byte past 32 KiB is refused before a body byte is read"),
                () -> assertEquals(OK, served, "control: a body of exactly 32 KiB is served"));
    }

    @ParameterizedTest(name = "GET {0}")
    @ValueSource(strings = {"health", "health/live", "health/ready", "health/started", "health/group",
            "health/well", "metrics"})
    @DisplayName("answers a body-less GET on each listed default SmallRye Health and metrics route with 200 under the body bound")
    void servesEveryProbeRouteABodylessGet(String route) throws Exception {
        assertEquals(OK, statusOf(bodylessGetOf(route)),
                "the probe route " + route + " is served: no probe needs a body the bound could refuse");
    }

    /**
     * @param route the probe route, relative to the management root path
     * @return a body-less GET of that route
     */
    private String bodylessGetOf(String route) {
        return "GET " + managementPath(route) + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
    }

    /**
     * @param count the number of fields
     * @return a URL-encoded form of that many short fields, {@code f0=a&f1=a&...}, with no trailing separator
     */
    private static String formFields(int count) {
        StringBuilder form = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                form.append('&');
            }
            form.append('f').append(i).append("=a");
        }
        return form.toString();
    }

    /**
     * @param form the URL-encoded form body
     * @return a liveness POST carrying that form
     */
    private String formRequest(String form) {
        return "POST " + livenessPath() + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/x-www-form-urlencoded\r\n"
                + "Content-Length: " + form.length() + "\r\nConnection: close\r\n\r\n" + form;
    }

    /**
     * @param chunkBytes the size of the single chunk
     * @return a chunked liveness POST whose body is exactly one chunk of that size
     */
    private String chunkedRequestWithOneChunkOf(int chunkBytes) {
        return "POST " + livenessPath() + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                + Integer.toHexString(chunkBytes) + "\r\n" + "o".repeat(chunkBytes) + "\r\n0\r\n\r\n";
    }

    /**
     * @param bodyBytes the size of the body
     * @return a liveness POST carrying a body of exactly that size
     */
    private String requestWithBodyOf(int bodyBytes) {
        return requestDeclaringBodyOf(bodyBytes) + "o".repeat(bodyBytes);
    }

    /**
     * @param bodyBytes the body size the request declares
     * @return the head of a liveness POST declaring a body of that size, without the body itself
     */
    private String requestDeclaringBodyOf(int bodyBytes) {
        return "POST " + livenessPath() + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Content-Type: application/octet-stream\r\n"
                + "Content-Length: " + bodyBytes + "\r\nConnection: close\r\n\r\n";
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
        return managementPath(LIVENESS_PATH);
    }

    /**
     * @param route a route relative to the management root path
     * @return that route beneath the management root path
     */
    private String managementPath(String route) {
        String root = managementUrl.getPath();
        return (root.endsWith("/") ? root : root + "/") + route;
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
