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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves, through the native image and a real WebSocket client, that a relay opened with a session
 * ends with that session: when the session is logged out, the gateway closes the established relay
 * with WebSocket close {@value #CLOSE_POLICY_VIOLATION} and the reason {@value #SESSION_ENDED}.
 * <p>
 * <strong>The route.</strong> {@code endpoints/bff-session-websocket.yaml} declares the one
 * {@code require: session} WebSocket route of the stack, {@value #SESSION_WEBSOCKET_PATH}, relayed to
 * the go-httpbin WebSocket echo. Its {@code allowed_origins} names the primary gateway's own origin.
 * <p>
 * <strong>Why the close is the session's and nothing else's.</strong> The relay is first shown to be
 * established and carrying frames — a text frame round-trips through the echo — and is then left
 * alone: the client sends nothing further and closes nothing. The only thing that happens between the
 * echo and the close frame is the logout of the session the upgrade was let through with, made on a
 * separate HTTP request. The relay's idle reclaim answers with another code ({@code 1001}), so it
 * cannot be mistaken for this close; and the one other event that closes a relay with
 * {@value #CLOSE_POLICY_VIOLATION} — the session's absolute expiry — is an hour away
 * ({@code ttl_seconds: 3600}), far outside the {@value #CLOSE_TIMEOUT_SECONDS}-second window the
 * close frame is awaited in.
 * <p>
 * The suite drives the primary instance, which holds its sessions server-side: only there can a
 * logout reach a relay at all. In cookie mode the gateway holds nothing a logout could end, and a
 * relay is closed at the session's absolute expiry alone — that leg is proven at unit level.
 * <p>
 * Like every {@code Bff*IT}, the suite sends the cookies itself and asserts nothing about browser
 * cookie policy (see {@link BffKeycloakLoginFlow}).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("A WebSocket relay opened with a session ends with it")
@Timeout(120) // per test: a login and three waits of at most 15 seconds each
class BffSessionWebSocketIT {

    /** The {@code require: session} WebSocket route (see {@code endpoints/bff-session-websocket.yaml}). */
    private static final String SESSION_WEBSOCKET_PATH = "/bff-session/ws";

    /** The one Origin the route allow-lists: the primary gateway's own. */
    private static final String ALLOWED_ORIGIN = BffKeycloakLoginFlow.GATEWAY_ORIGIN;

    private static final String FOREIGN_ORIGIN = "https://evil.example";

    private static final String WEBSOCKET_URI =
            ALLOWED_ORIGIN.replaceFirst("^https", "wss") + SESSION_WEBSOCKET_PATH;

    /** WebSocket close 1008 (Policy Violation): the code of a relay closed because its session ended. */
    private static final int CLOSE_POLICY_VIOLATION = 1008;

    /** The one close reason sent with that code. */
    private static final String SESSION_ENDED = "session ended";

    private static final int HANDSHAKE_TIMEOUT_SECONDS = 15;

    /** How long the close frame may take to arrive after the logout was answered. */
    private static final int CLOSE_TIMEOUT_SECONDS = 15;

    private static HttpClient httpClient;

    @BeforeAll
    static void setUpWebSocketClient() throws Exception {
        httpClient = LocalStackTls.clientBuilder().build();
    }

    @Test
    @DisplayName("a relay opened with a session is closed 1008 'session ended' when the session is logged out")
    void relayIsClosedWhenItsSessionIsLoggedOut() throws Exception {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");
        var listener = new RecordingListener();
        WebSocket socket = httpClient.newWebSocketBuilder()
                .header("Origin", ALLOWED_ORIGIN)
                .header("Cookie", cookieHeader(session.gatewayCookies()))
                .buildAsync(URI.create(WEBSOCKET_URI), listener)
                .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        try {
            socket.sendText("sheriff-session-relay", true).get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals("sheriff-session-relay",
                    listener.firstMessage.get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "precondition: the relay must be established and carry frames before the session ends");

            BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                    .redirects().follow(false)
                    .when().get("/auth/logout")
                    .then().statusCode(302);

            Close close = listener.closed.get(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertAll("the close frame of a relay whose session was logged out",
                    () -> assertEquals(CLOSE_POLICY_VIOLATION, close.statusCode(),
                            "a relay must be closed with 1008 when its session ends"),
                    () -> assertEquals(SESSION_ENDED, close.reason(),
                            "the close reason says that the session is over and nothing about why"));
        } finally {
            socket.abort();
        }
    }

    @Test
    @DisplayName("the session WebSocket route refuses a foreign Origin 403 even with a live session")
    void foreignOriginIsRefusedWithALiveSession() {
        Session session = BffKeycloakLoginFlow.login("/bff-session/get");

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> httpClient.newWebSocketBuilder()
                .header("Origin", FOREIGN_ORIGIN)
                .header("Cookie", cookieHeader(session.gatewayCookies()))
                .buildAsync(URI.create(WEBSOCKET_URI), new RecordingListener())
                .get(HANDSHAKE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "a handshake from an Origin the route does not allow-list must fail");

        WebSocketHandshakeException refused = assertInstanceOf(WebSocketHandshakeException.class, thrown.getCause(),
                "a refused WebSocket handshake must surface a WebSocketHandshakeException");
        assertEquals(403, refused.getResponse().statusCode(),
                "a live session does not open the socket to a page of another origin");
    }

    private static String cookieHeader(Map<String, String> cookies) {
        return cookies.entrySet().stream()
                .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                .collect(Collectors.joining("; "));
    }

    /**
     * The close frame a relay ended with.
     *
     * @param statusCode the WebSocket close code
     * @param reason     the close reason, empty when the frame carried none
     */
    private record Close(int statusCode, String reason) {
    }

    /** Captures the first fully-assembled text message and the close frame. */
    private static final class RecordingListener implements WebSocket.Listener {

        private final CompletableFuture<String> firstMessage = new CompletableFuture<>();
        private final CompletableFuture<Close> closed = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            if (last) {
                firstMessage.complete(buffer.toString());
                buffer.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(new Close(statusCode, reason));
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            firstMessage.completeExceptionally(error);
            closed.completeExceptionally(error);
        }
    }
}
