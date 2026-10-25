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
package de.cuioss.sheriff.gateway.edge.admission;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamGuards;
import io.vertx.core.http.Http2Settings;
import io.vertx.core.http.HttpServerOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("EdgeHardeningOptions — inbound transport bounds and admission/drain limits")
class EdgeHardeningOptionsTest {

    private static final int MAX_HEADER_SIZE_BYTES = 16 * 1024;
    private static final int MAX_INITIAL_LINE_LENGTH_BYTES = 8 * 1024;
    private static final int MAX_CHUNK_SIZE_BYTES = 16 * 1024;
    private static final int IDLE_TIMEOUT_SECONDS = 60;
    private static final long DRAIN_TIMEOUT_MILLIS = 25_000L;

    @Test
    @DisplayName("clamps the plaintext listener framing to bounded header, line, and chunk sizes")
    void clampsPlaintextListenerFraming() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        HttpServerOptions options = new HttpServerOptions();

        // Act
        hardening.customizeHttpServer(options);

        // Assert — an over-long line, over-large header block, or over-large chunk is rejected by
        // the Vert.x codec before a request is ever admitted to the pipeline.
        assertEquals(MAX_HEADER_SIZE_BYTES, options.getMaxHeaderSize(),
                "The whole request header block is bounded to 16 KiB");
        assertEquals(MAX_INITIAL_LINE_LENGTH_BYTES, options.getMaxInitialLineLength(),
                "The HTTP/1.x request line is bounded to 8 KiB");
        assertEquals(MAX_CHUNK_SIZE_BYTES, options.getMaxChunkSize(),
                "A single chunked-transfer chunk is bounded to 16 KiB");
    }

    @Test
    @DisplayName("reaps an idle connection after a bounded idle window (slow-loris / h2 abuse guard)")
    void reapsIdleConnection() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        HttpServerOptions options = new HttpServerOptions();

        // Act
        hardening.customizeHttpServer(options);

        // Assert — an idle connection is reaped so a slow-loris / h2 abuse load cannot pin slots.
        assertEquals(IDLE_TIMEOUT_SECONDS, options.getIdleTimeout(),
                "An idle connection is reaped after 60 seconds");
        assertEquals(TimeUnit.SECONDS, options.getIdleTimeoutUnit(),
                "The idle-timeout unit is seconds");
    }

    @Test
    @DisplayName("applies the same transport bounds to the TLS listener as to the plaintext listener")
    void appliesSameBoundsToHttpsListener() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        HttpServerOptions options = new HttpServerOptions();

        // Act
        hardening.customizeHttpsServer(options);

        // Assert — the customizer applies to every listener Quarkus opens, TLS included.
        assertEquals(MAX_HEADER_SIZE_BYTES, options.getMaxHeaderSize(),
                "The TLS listener bounds the header block identically");
        assertEquals(MAX_INITIAL_LINE_LENGTH_BYTES, options.getMaxInitialLineLength(),
                "The TLS listener bounds the request line identically");
        assertEquals(MAX_CHUNK_SIZE_BYTES, options.getMaxChunkSize(),
                "The TLS listener bounds the chunk size identically");
        assertEquals(IDLE_TIMEOUT_SECONDS, options.getIdleTimeout(),
                "The TLS listener reaps idle connections identically");
        assertEquals(TimeUnit.SECONDS, options.getIdleTimeoutUnit(),
                "The TLS listener idle-timeout unit is seconds");
    }

    @Test
    @DisplayName("bounds in-flight requests and WebSocket relays with the documented default budget")
    void exposesDefaultAdmissionBudget() {
        // Arrange — the no-arg construction is the documented defaults path a gateway that declares
        // no edge_hardening block resolves to
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();

        // Act
        int admissionCap = hardening.admissionCap();
        int relayCap = hardening.webSocketRelayCap();

        // Assert — a request beyond the admission cap is rejected 503 before a virtual thread is
        // dispatched, so a flood cannot spawn unbounded virtual threads; the relay cap is a strictly
        // smaller sub-budget, so long-lived relays cannot consume the whole pool.
        assertEquals(EdgeHardeningConfig.DEFAULT_ADMISSION_CAP, admissionCap,
                "The admission cap is the documented default");
        assertEquals(EdgeHardeningConfig.DEFAULT_WEBSOCKET_RELAY_CAP, relayCap,
                "The WebSocket relay cap is the documented default");
        assertTrue(admissionCap > 0, "The admission cap must be a positive bound");
        assertTrue(relayCap > 0, "The WebSocket relay cap must be a positive bound");
        assertTrue(relayCap <= admissionCap,
                "The relay sub-budget must stay within the admission pool it draws from");
    }

    @Test
    @DisplayName("carries the operator-configured caps when the edge_hardening block declares them")
    void carriesOperatorConfiguredCaps() {
        // Arrange
        EdgeHardeningConfig configured = new EdgeHardeningConfig(64, 8);

        // Act
        EdgeHardeningOptions hardening = new EdgeHardeningOptions(configured);

        // Assert
        assertEquals(64, hardening.admissionCap(), "The declared admission_cap is carried through");
        assertEquals(8, hardening.webSocketRelayCap(), "The declared websocket_relay_cap is carried through");
    }

    @Test
    @DisplayName("falls back per member, deriving an omitted relay cap from the declared admission cap")
    void fallsBackPerOmittedMember() {
        // Arrange — only the admission cap is declared, and it is raised above the default
        EdgeHardeningConfig partial = new EdgeHardeningConfig(4096, null);

        // Act
        EdgeHardeningOptions hardening = new EdgeHardeningOptions(partial);

        // Assert — the omitted member resolves to a quarter of the effective admission cap, so the
        // documented three-quarters-for-HTTP reservation scales with the pool instead of staying
        // pinned to DEFAULT_WEBSOCKET_RELAY_CAP
        assertEquals(4096, hardening.admissionCap(), "The declared admission_cap is carried through");
        assertEquals(1024, hardening.webSocketRelayCap(),
                "An omitted websocket_relay_cap resolves to a quarter of the effective admission_cap");
    }

    @Test
    @DisplayName("keeps a lowered admission cap valid, deriving a relay sub-budget that stays inside it")
    void derivesRelayCapBelowALoweredAdmissionCap() {
        // Arrange — the operator lowers only the admission cap, well below the shipped relay default
        EdgeHardeningConfig partial = new EdgeHardeningConfig(64, null);

        // Act
        EdgeHardeningOptions hardening = new EdgeHardeningOptions(partial);

        // Assert — the derived sub-budget must never exceed the pool it draws from, otherwise the
        // boot-time validator would refuse a block the configuration contract documents as valid
        assertEquals(64, hardening.admissionCap(), "The declared admission_cap is carried through");
        assertEquals(16, hardening.webSocketRelayCap(),
                "An omitted websocket_relay_cap resolves to a quarter of the lowered admission_cap");
        assertTrue(hardening.webSocketRelayCap() <= hardening.admissionCap(),
                "The relay sub-budget must stay within the admission pool it draws from");
    }

    @Test
    @DisplayName("bounds a reserved-path body at the same 16 KiB inbound unit as the header block")
    void exposesReservedBodyCeilingDerivedFromHeaderBound() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();
        HttpServerOptions options = new HttpServerOptions();
        hardening.customizeHttpServer(options);

        // Act
        long reservedBodyMax = hardening.reservedBodyMaxBytes();

        // Assert — the ceiling is DERIVED from the header-block bound rather than restated as its own
        // literal, so the gateway's single 16 KiB inbound-unit bound cannot drift into two constants.
        assertEquals(options.getMaxHeaderSize(), reservedBodyMax,
                "The reserved-body ceiling is derived from the 16 KiB header-block bound, not a second literal");
        assertTrue(reservedBodyMax > 0L, "The reserved-body ceiling must be a positive bound");
    }

    @Test
    @DisplayName("bounds the graceful-drain wait below the Quarkus shutdown window")
    void exposesBoundedDrainTimeout() {
        // Arrange
        EdgeHardeningOptions hardening = new EdgeHardeningOptions();

        // Act
        long drainMillis = hardening.drainTimeoutMillis();

        // Assert — the drain timeout is a positive, bounded wait kept below the Quarkus default
        // 30-second shutdown timeout so the drain completes within the shutdown window.
        assertEquals(DRAIN_TIMEOUT_MILLIS, drainMillis, "The drain timeout is the documented secure default");
        assertTrue(drainMillis > 0L, "The drain timeout must be a positive bound");
        assertTrue(drainMillis < 30_000L, "The drain timeout must stay below the 30s Quarkus shutdown window");
    }

    /**
     * The request-body idle timeout: how long a proxied dispatch holding an upstream connection may go
     * without request-body progress. A fixed gateway constant, below the upstream guard's timeout so the
     * dispatch is ended as the request's before the guard would end it as the upstream's.
     */
    @Nested
    @DisplayName("request-body idle timeout")
    class RequestBodyIdleTimeout {

        /** The fixed value, restated as a literal so that a change to it is noticed here. */
        private static final long REQUEST_BODY_IDLE_TIMEOUT_MILLIS = 10_000L;
        /** The fixed request-body deadline the two-argument test-only constructor keeps. */
        private static final long REQUEST_BODY_DEADLINE_MILLIS = 25_000L;

        @Test
        @DisplayName("is the fixed 10,000 ms, whatever the edge_hardening block declares")
        void isTheFixedTenSeconds() {
            assertEquals(REQUEST_BODY_IDLE_TIMEOUT_MILLIS, new EdgeHardeningOptions().requestBodyIdleTimeoutMillis(),
                    "The request-body idle timeout is the fixed gateway constant");
            assertEquals(REQUEST_BODY_IDLE_TIMEOUT_MILLIS,
                    new EdgeHardeningOptions(new EdgeHardeningConfig(64, 8)).requestBodyIdleTimeoutMillis(),
                    "No edge_hardening member reaches the request-body idle timeout");
        }

        @Test
        @DisplayName("stays below the upstream guard's whole-dispatch timeout")
        void staysBelowTheUpstreamGuardTimeout() {
            long guardTimeoutMillis = TimeUnit.SECONDS.toMillis(UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS);

            assertTrue(new EdgeHardeningOptions().requestBodyIdleTimeoutMillis() < guardTimeoutMillis,
                    "A body that stopped arriving must be ended before the guard would end the dispatch as an "
                            + "upstream timeout");
        }

        @ParameterizedTest(name = "{0} ms")
        @ValueSource(longs = {0L, -1L})
        @DisplayName("the test-only constructor refuses a timeout that is not positive")
        void constructorForTestsRefusesANonPositiveTimeout(long timeoutMillis) {
            EdgeHardeningConfig defaults = EdgeHardeningConfig.defaults();

            assertThrows(IllegalArgumentException.class, () -> new EdgeHardeningOptions(defaults, timeoutMillis));
        }

        @ParameterizedTest(name = "{0} ms")
        @ValueSource(longs = {REQUEST_BODY_DEADLINE_MILLIS, REQUEST_BODY_DEADLINE_MILLIS + 1})
        @DisplayName("the test-only constructor refuses a timeout not shorter than the fixed 25,000 ms deadline")
        void constructorForTestsRefusesATimeoutNotBelowTheFixedDeadline(long timeoutMillis) {
            EdgeHardeningConfig defaults = EdgeHardeningConfig.defaults();

            assertThrows(IllegalArgumentException.class, () -> new EdgeHardeningOptions(defaults, timeoutMillis));
        }

        @ParameterizedTest(name = "deadline {0} ms")
        @ValueSource(longs = {300L, 299L})
        @DisplayName("the test-only constructor refuses a deadline not longer than a 300 ms idle timeout")
        void constructorForTestsRefusesADeadlineNotLongerThanTheTimeout(long deadlineMillis) {
            EdgeHardeningConfig defaults = EdgeHardeningConfig.defaults();

            assertThrows(IllegalArgumentException.class,
                    () -> new EdgeHardeningOptions(defaults, 300L, deadlineMillis));
        }

        @Test
        @DisplayName("the test-only constructor carries a deadline just longer than the idle timeout")
        void constructorForTestsCarriesTheGivenDeadline() {
            EdgeHardeningOptions hardening = new EdgeHardeningOptions(EdgeHardeningConfig.defaults(), 300L, 301L);

            assertAll("the test-only construction with both bounds",
                    () -> assertEquals(300L, hardening.requestBodyIdleTimeoutMillis()),
                    () -> assertEquals(301L, hardening.requestBodyDeadlineMillis()));
        }

        @Test
        @DisplayName("the test-only constructor carries the given timeout and the same admission budget")
        void constructorForTestsCarriesTheGivenTimeout() {
            EdgeHardeningOptions hardening = new EdgeHardeningOptions(new EdgeHardeningConfig(64, 8), 300L);

            assertAll("the test-only construction",
                    () -> assertEquals(300L, hardening.requestBodyIdleTimeoutMillis()),
                    () -> assertEquals(64, hardening.admissionCap()),
                    () -> assertEquals(8, hardening.webSocketRelayCap()));
        }
    }

    /**
     * The inbound HTTP/2 concurrent-stream bound: an unbounded or larger announced value is lowered to
     * 100 streams per connection, a lower one is kept, and the connection window follows the result.
     */
    @Nested
    @DisplayName("inbound HTTP/2 concurrent-stream bound")
    class ConcurrentStreamBound {

        /** The bound, restated as a literal so that a change to it is noticed here. */
        private static final long MAX_CONCURRENT_STREAMS = 100L;

        /** 100 concurrent streams of the default 65,535-byte stream window each. */
        private static final int BOUNDED_CONNECTION_WINDOW_BYTES = 6_553_500;

        private static HttpServerOptions listenerAnnouncing(long maxConcurrentStreams) {
            return new HttpServerOptions()
                    .setInitialSettings(new Http2Settings().setMaxConcurrentStreams(maxConcurrentStreams));
        }

        /**
         * Listeners announcing a stream limit at or below the bound, each with the limit it must keep:
         * two explicit values and a listener left at the Vert.x defaults of 100 streams of 65,535 bytes.
         */
        static Stream<Arguments> listenersAtOrBelowTheBound() {
            return Stream.of(
                    Arguments.of(Named.of("7 streams", listenerAnnouncing(7L)), 7L),
                    Arguments.of(Named.of("100 streams", listenerAnnouncing(MAX_CONCURRENT_STREAMS)),
                            MAX_CONCURRENT_STREAMS),
                    Arguments.of(Named.of("the Vert.x default listener", new HttpServerOptions()),
                            new HttpServerOptions().getInitialSettings().getMaxConcurrentStreams()));
        }

        @ParameterizedTest(name = "{0} streams")
        @ValueSource(longs = {Http2Settings.DEFAULT_MAX_CONCURRENT_STREAMS, 250L})
        @DisplayName("lowers an unbounded or larger listener to 100 streams and a 6,553,500-byte connection window")
        void lowersAnUnboundedOrLargerValueToTheBound(long maxConcurrentStreams) {
            HttpServerOptions options = listenerAnnouncing(maxConcurrentStreams);

            new EdgeHardeningOptions().customizeHttpServer(options);

            assertAll("a listener announcing " + maxConcurrentStreams + " streams",
                    () -> assertEquals(MAX_CONCURRENT_STREAMS, options.getInitialSettings().getMaxConcurrentStreams(),
                            "An unbounded or larger announced value is lowered to 100 concurrent streams"),
                    () -> assertEquals(BOUNDED_CONNECTION_WINDOW_BYTES, options.getHttp2ConnectionWindowSize(),
                            "The connection window is derived from the bounded 100 streams x 65,535 bytes"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("listenersAtOrBelowTheBound")
        @DisplayName("keeps a listener's stream limit at or below 100, and the connection window follows it")
        void keepsAValueAtOrBelowTheBound(HttpServerOptions options, long keptStreams) {
            new EdgeHardeningOptions().customizeHttpServer(options);

            assertAll("a listener keeping " + keptStreams + " streams",
                    () -> assertEquals(keptStreams, options.getInitialSettings().getMaxConcurrentStreams(),
                            "A value at or below the bound is kept"),
                    () -> assertEquals(keptStreams * 65_535, options.getHttp2ConnectionWindowSize(),
                            "The connection window is the kept stream count x 65,535 bytes"));
        }

        @Test
        @DisplayName("bounds the TLS listener's streams and window exactly as the plaintext listener's")
        void boundsTheTlsListenerIdentically() {
            HttpServerOptions unbounded = listenerAnnouncing(Http2Settings.DEFAULT_MAX_CONCURRENT_STREAMS);
            HttpServerOptions vertxDefault = new HttpServerOptions();

            new EdgeHardeningOptions().customizeHttpsServer(unbounded);
            new EdgeHardeningOptions().customizeHttpsServer(vertxDefault);

            assertAll("an unbounded and a default TLS listener",
                    () -> assertEquals(MAX_CONCURRENT_STREAMS, unbounded.getInitialSettings().getMaxConcurrentStreams(),
                            "The TLS listener is lowered to 100 concurrent streams identically"),
                    () -> assertEquals(BOUNDED_CONNECTION_WINDOW_BYTES, unbounded.getHttp2ConnectionWindowSize(),
                            "The TLS listener sizes its connection window identically"),
                    () -> assertEquals(BOUNDED_CONNECTION_WINDOW_BYTES, vertxDefault.getHttp2ConnectionWindowSize(),
                            "A TLS listener left at the Vert.x defaults sizes its window to every stream's window"));
        }
    }

    /**
     * The inbound HTTP/2 connection window, derived from the stream settings the listener announces so
     * every stream it admits at once can fill its own window.
     */
    @Nested
    @DisplayName("inbound HTTP/2 connection window")
    class ConnectionWindow {

        @Test
        @DisplayName("follows the stream settings the listener actually announces")
        void followsTheListenersOwnStreamSettings() {
            HttpServerOptions options = new HttpServerOptions()
                    .setInitialSettings(new Http2Settings().setMaxConcurrentStreams(7).setInitialWindowSize(1_000));

            new EdgeHardeningOptions().customizeHttpServer(options);

            assertEquals(7_000, options.getHttp2ConnectionWindowSize(),
                    "7 concurrent streams of 1,000 bytes each need a 7,000-byte connection window");
        }

        @Test
        @DisplayName("caps the window at 2^31-1 when 100 stream windows would exceed it")
        void capsTheWindowWhenEveryStreamsWindowExceedsTheMaximum() {
            HttpServerOptions options = new HttpServerOptions()
                    .setInitialSettings(new Http2Settings()
                            .setMaxConcurrentStreams(100L)
                            .setInitialWindowSize(Integer.MAX_VALUE));

            new EdgeHardeningOptions().customizeHttpServer(options);

            assertEquals(Integer.MAX_VALUE, options.getHttp2ConnectionWindowSize(),
                    "RFC 9113 section 6.9.1 bounds a flow-control window at 2^31-1");
        }

        @Test
        @DisplayName("never sizes the window below one stream window")
        void neverSizesTheWindowBelowOneStreamWindow() {
            HttpServerOptions options = new HttpServerOptions()
                    .setInitialSettings(new Http2Settings().setMaxConcurrentStreams(0).setInitialWindowSize(1_000));

            new EdgeHardeningOptions().customizeHttpServer(options);

            assertEquals(1_000, options.getHttp2ConnectionWindowSize(),
                    "A listener admitting no concurrent stream still keeps one stream window for the connection");
        }
    }
}
