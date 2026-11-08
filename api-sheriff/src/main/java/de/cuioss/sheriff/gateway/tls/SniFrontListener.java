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
package de.cuioss.sheriff.gateway.tls;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayKind;
import de.cuioss.sheriff.gateway.tls.PassthroughRelay.RelayTarget;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetServerOptions;
import io.vertx.core.net.NetSocket;
import io.vertx.core.net.TCPSSLOptions;
import org.jspecify.annotations.Nullable;

/**
 * The Vert.x {@link NetServer} front, bound to the public TLS port, that performs the accept-time
 * SNI split at L4.
 * <p>
 * The front is a plain-TCP server: the TLS ClientHello is sent in cleartext at the record layer, so
 * no decryption is needed to read the SNI. Each accepted connection owns its own
 * {@link ClientHelloSniParser} and offers every chunk it receives to it exactly once; the chunks are
 * also kept in a buffer, solely so the relay can replay the ClientHello byte-identically. Once the
 * parser reports a complete ClientHello the listener routes:
 * <ul>
 * <li>a case-insensitively matched {@code passthrough_sni} SNI hands the connection — buffered
 * ClientHello included — to the opaque {@link PassthroughRelay} targeting the resolved backend; the
 * gateway never handshakes;</li>
 * <li>an SNI that is not in the passthrough set relays the still-encrypted stream to the internal
 * terminated Quarkus HTTPS listener;</li>
 * <li>a missing / malformed SNI fails closed (GW-06): it takes the same terminated-strict path and
 * an audited {@code WARN} records the disposition only, never raw bytes.</li>
 * </ul>
 * <strong>Three bounds keep a connection the front has not routed yet, or a relay nobody uses, from
 * being held indefinitely.</strong> None of them is configurable.
 * <ul>
 * <li><em>ClientHello deadline</em> — {@link #CLIENT_HELLO_DEADLINE}, ten seconds. The timer is
 * armed when the connection is accepted and cancelled when the route is decided; a connection that
 * has not completed its ClientHello by then is closed. A legitimate client sends its ClientHello in
 * its first flight, within one round trip, so the deadline only ever ends a silent, stalled or
 * dripping connection. The close is logged at {@code DEBUG} with the disposition only, never the
 * bytes received.</li>
 * <li><em>Undecided-connection cap</em> — {@link #MAX_UNDECIDED_CONNECTIONS}, 1024 connections
 * accepted and not yet routed, across every event loop of the front. A connection accepted beyond
 * the cap is closed at once, without reading from it. Each undecided connection receives at most
 * {@link ClientHelloSniParser#MAX_CLIENT_HELLO_BYTES} (32 KiB) before its verdict and holds those bytes
 * in three copies — the replay buffer and the parser's received and reassembled arrays — so the cap
 * bounds the front's pre-route buffer memory at about 96 MiB. The trade-off is deliberate: a client that holds
 * 1024 silent sockets makes new handshakes wait for at most one deadline window per wave of such
 * sockets — the deadline frees every slot again — and that bounded delay is preferred over unbounded
 * pre-route memory. Since a legitimate client completes its ClientHello within one round trip, 1024
 * concurrent undecided connections still admit tens of thousands of handshakes per second.</li>
 * <li><em>Relay idle timeout</em> — the Vert.x idle timeout of the front server, the same value
 * {@link TlsEdgeProducer} also sets on the relay's dial client. It closes a relayed leg that carried
 * no byte in either direction for the timeout, which the relay's close handlers then propagate to the
 * other leg. {@link TlsEdgeProducer} derives the value at boot so that it never undercuts an idle
 * bound inside the gateway behind the terminated branch.</li>
 * </ul>
 * The listener is constructed and started only when {@code tls.passthrough_sni} is non-empty (see
 * {@link TlsEdgeProducer}); when passthrough is unconfigured the front is never created, so the
 * default single-listener topology is unchanged.
 * <p>
 * <strong>Thread safety.</strong> The undecided-connection count is the one state shared across the
 * front's event loops: it is claimed with an atomic increment, rolled back with an atomic decrement
 * when the claim overshoots the cap, and returned with an atomic decrement exactly once per admitted
 * connection. Everything else about a connection is confined to that connection's event loop.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SniFrontListener {

    private static final CuiLogger LOGGER = new CuiLogger(SniFrontListener.class);

    /** How long an accepted connection may take to complete its ClientHello before it is closed. */
    public static final Duration CLIENT_HELLO_DEADLINE = Duration.ofSeconds(10);

    /** How many accepted connections may be waiting for their ClientHello at the same time. */
    public static final int MAX_UNDECIDED_CONNECTIONS = 1024;

    /** The Vert.x timer id meaning "no deadline timer was armed". */
    private static final long NO_TIMER = -1L;

    /** The units an idle timeout is set in, finest first; see {@link #applyIdleTimeout}. */
    private static final List<TimeUnit> IDLE_TIMEOUT_UNITS = List.of(TimeUnit.MILLISECONDS, TimeUnit.SECONDS,
            TimeUnit.MINUTES, TimeUnit.HOURS, TimeUnit.DAYS);

    private final Vertx vertx;
    private final PassthroughRelay relay;
    private final Map<String, RelayTarget> passthroughTargets;
    private final RelayTarget terminatedTarget;
    private final int publicPort;
    private final String host;
    private final long clientHelloDeadlineMillis;
    private final int maxUndecidedConnections;
    private final Duration relayIdleTimeout;
    private final AtomicInteger undecidedConnections = new AtomicInteger();

    private @Nullable NetServer server;

    /**
     * Creates a front listener bound on the Vert.x default host ({@link NetServerOptions#DEFAULT_HOST},
     * the wildcard address), which is the production bind scope {@link TlsEdgeProducer} relies on,
     * with the fixed {@link #CLIENT_HELLO_DEADLINE} and {@link #MAX_UNDECIDED_CONNECTIONS}.
     *
     * @param vertx              the managed Vert.x instance the front server is created on
     * @param relay              the opaque L4 relay both routing branches hand connections to
     * @param passthroughTargets the immutable SNI (normalized, lower-cased) → backend map; a match
     *                           relays opaquely to the backend
     * @param terminatedTarget   the internal terminated Quarkus HTTPS endpoint every non-passthrough
     *                           connection is relayed to
     * @param publicPort         the public TLS port the front server binds
     * @param relayIdleTimeout   the idle timeout of the front server, derived by
     *                           {@link TlsEdgeProducer}; positive
     * @throws IllegalArgumentException when {@code relayIdleTimeout} is not positive
     */
    public SniFrontListener(Vertx vertx, PassthroughRelay relay, Map<String, RelayTarget> passthroughTargets,
            RelayTarget terminatedTarget, int publicPort, Duration relayIdleTimeout) {
        this(vertx, relay, passthroughTargets, terminatedTarget, publicPort, NetServerOptions.DEFAULT_HOST,
                CLIENT_HELLO_DEADLINE, MAX_UNDECIDED_CONNECTIONS, relayIdleTimeout);
    }

    /**
     * Creates a front listener with every bound explicit, so a test can bind a specific host and
     * shorten the bounds instead of waiting for them. Production uses the public constructor, which
     * fixes the host, the deadline and the cap.
     *
     * @param vertx                   the managed Vert.x instance the front server is created on
     * @param relay                   the opaque L4 relay both routing branches hand connections to
     * @param passthroughTargets      the immutable SNI (normalized, lower-cased) → backend map
     * @param terminatedTarget        the internal terminated Quarkus HTTPS endpoint
     * @param publicPort              the public TLS port the front server binds
     * @param host                    the host (address) the front server binds the public TLS port on
     * @param clientHelloDeadline     how long an accepted connection may take to complete its
     *                                ClientHello; positive
     * @param maxUndecidedConnections how many accepted connections may wait for their ClientHello at
     *                                the same time; positive
     * @param relayIdleTimeout        the idle timeout of the front server; positive
     * @throws IllegalArgumentException when a bound is not positive
     */
    @SuppressWarnings("java:S107") // every bound is explicit by design: this is the test seam
    SniFrontListener(Vertx vertx, PassthroughRelay relay, Map<String, RelayTarget> passthroughTargets,
            RelayTarget terminatedTarget, int publicPort, String host, Duration clientHelloDeadline,
            int maxUndecidedConnections, Duration relayIdleTimeout) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.relay = Objects.requireNonNull(relay, "relay");
        this.passthroughTargets = Map.copyOf(Objects.requireNonNull(passthroughTargets, "passthroughTargets"));
        this.terminatedTarget = Objects.requireNonNull(terminatedTarget, "terminatedTarget");
        this.publicPort = publicPort;
        this.host = Objects.requireNonNull(host, "host");
        this.clientHelloDeadlineMillis = requirePositive(clientHelloDeadline, "clientHelloDeadline").toMillis();
        if (maxUndecidedConnections <= 0) {
            throw new IllegalArgumentException("maxUndecidedConnections must be positive: " + maxUndecidedConnections);
        }
        this.maxUndecidedConnections = maxUndecidedConnections;
        this.relayIdleTimeout = requirePositive(relayIdleTimeout, "relayIdleTimeout");
    }

    /**
     * Creates the {@link NetServer} with the relay idle timeout, wires the connect handler, and binds
     * the public TLS port on the configured host.
     *
     * @return a future completing when the front server is listening, or failing when the bind fails
     */
    public Future<Void> start() {
        NetServerOptions options = new NetServerOptions();
        applyIdleTimeout(options, relayIdleTimeout);
        NetServer netServer = vertx.createNetServer(options);
        netServer.connectHandler(this::onConnect);
        this.server = netServer;
        return netServer.listen(publicPort, host)
                .onSuccess(bound -> LOGGER.info(ApiSheriffLogMessages.INFO.SNI_FRONT_LISTENER_STARTED,
                        Integer.toString(publicPort), Integer.toString(passthroughTargets.size())))
                .mapEmpty();
    }

    /**
     * Closes the front server, if started.
     *
     * @return a future completing when the server is closed
     */
    public Future<Void> stop() {
        NetServer current = server;
        return current == null ? Future.succeededFuture() : current.close();
    }

    /**
     * The port the front server is actually bound to. When the configured port is {@code 0} the
     * server binds an ephemeral port, and this returns the concrete port it resolved to.
     *
     * @return the bound port, or {@code -1} when the server has not been started
     */
    public int actualPort() {
        NetServer current = server;
        return current == null ? -1 : current.actualPort();
    }

    /**
     * Sets {@code idleTimeout} as the Vert.x idle timeout of a server or client, in the finest of
     * milliseconds, seconds, minutes, hours and days whose count fits the {@code int} Vert.x takes. The
     * count is rounded up, so the timeout set is <strong>never shorter</strong> than the one given: a
     * positive duration below one millisecond is set as one millisecond — a zero would mean no idle
     * timeout at all — and a duration past {@link Integer#MAX_VALUE} seconds falls back to whole
     * minutes instead of being capped, so the {@link TlsEdgeProducer#RELAY_IDLE_MARGIN} over the
     * longest inner bound is kept.
     *
     * @param options     the server or client options to configure
     * @param idleTimeout the idle timeout; positive
     * @throws IllegalArgumentException when the timeout exceeds {@link Integer#MAX_VALUE} days, which no
     *                                  unit Vert.x takes can carry
     */
    static void applyIdleTimeout(TCPSSLOptions options, Duration idleTimeout) {
        for (TimeUnit unit : IDLE_TIMEOUT_UNITS) {
            Duration oneUnit = Duration.of(1, unit.toChronoUnit());
            if (idleTimeout.compareTo(oneUnit.multipliedBy(Integer.MAX_VALUE)) <= 0) {
                long whole = idleTimeout.dividedBy(oneUnit);
                // whole < Integer.MAX_VALUE whenever a remainder is left, so rounding up still fits
                long roundedUp = oneUnit.multipliedBy(whole).equals(idleTimeout) ? whole : whole + 1;
                options.setIdleTimeout((int) roundedUp);
                options.setIdleTimeoutUnit(unit);
                return;
            }
        }
        throw new IllegalArgumentException("idle timeout too long for a Vert.x idle timeout: " + idleTimeout);
    }

    /**
     * Admits an accepted connection when the undecided-connection cap has a free slot, and closes it
     * at once, without reading from it, when it has none. The slot is claimed with one atomic
     * increment and rolled back with one atomic decrement on overshoot, so concurrent accepts on
     * different event loops can never push the count past the cap.
     */
    private void onConnect(NetSocket socket) {
        if (undecidedConnections.incrementAndGet() > maxUndecidedConnections) {
            undecidedConnections.decrementAndGet();
            LOGGER.debug("Front connection closed at accept: %s connections are already waiting for their ClientHello",
                    Integer.toString(maxUndecidedConnections));
            socket.pause();
            closeQuietly(socket);
            return;
        }
        new Incoming(socket).begin();
    }

    private static void closeQuietly(NetSocket socket) {
        socket.close().onFailure(failure ->
                LOGGER.debug(failure, "Front connection close failed: %s", failure.getMessage()));
    }

    private static Duration requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive: " + value);
        }
        return value;
    }

    /**
     * Normalizes an SNI hostname for case-insensitive, root-dot-insensitive exact matching: lowered
     * (root locale), stripped, and with a single trailing FQDN dot removed. Shared by
     * {@link TlsEdgeProducer} when it builds the relay-map keys so lookup and insertion agree.
     *
     * @param host the raw SNI hostname
     * @return the normalized matching key
     */
    static String normalizeSni(String host) {
        String lower = host.toLowerCase(Locale.ROOT).strip();
        return lower.endsWith(".") ? lower.substring(0, lower.length() - 1) : lower;
    }

    /**
     * One admitted connection while its ClientHello is being reassembled. All callbacks — the data,
     * exception and close handlers and the deadline timer, which is armed from the connect handler and
     * therefore fires on the same context — run on the connection's Vert.x event loop, so
     * {@code buffer}, {@code parser} and {@code settled} are single-threaded.
     * <p>
     * {@code settled} is the per-connection latch: whichever comes first of the route decision, the
     * connection closing or failing, and the deadline expiring sets it, cancels the deadline and
     * returns the connection's slot of the undecided-connection cap. Every later path finds it set and
     * does nothing, so the slot is returned exactly once.
     */
    private final class Incoming {

        private final NetSocket socket;
        private final Buffer buffer = Buffer.buffer();
        private final ClientHelloSniParser parser = new ClientHelloSniParser();
        private boolean settled;
        private long deadlineTimer = NO_TIMER;

        Incoming(NetSocket socket) {
            this.socket = socket;
        }

        void begin() {
            socket.handler(this::onData);
            socket.exceptionHandler(this::onError);
            socket.closeHandler(closed -> settle());
            deadlineTimer = vertx.setTimer(clientHelloDeadlineMillis, timerId -> onDeadline());
        }

        private void onData(Buffer chunk) {
            if (settled) {
                return;
            }
            buffer.appendBuffer(chunk);
            ClientHelloSniParser.Result result = parser.offer(chunk.getBytes());
            if (result.complete() && settle()) {
                socket.pause();
                route(result.serverName());
            }
        }

        private void onError(Throwable failure) {
            if (settle()) {
                LOGGER.debug(failure, "Front connection failed before ClientHello was read: %s", failure.getMessage());
                closeQuietly(socket);
            }
        }

        private void onDeadline() {
            if (settle()) {
                LOGGER.debug("Front connection closed: no complete ClientHello within %s ms",
                        Long.toString(clientHelloDeadlineMillis));
                closeQuietly(socket);
            }
        }

        /**
         * Sets the latch, cancels the deadline and returns the connection's slot — once.
         *
         * @return {@code true} for the one call that settled the connection, {@code false} for every
         *         later call
         */
        private boolean settle() {
            if (settled) {
                return false;
            }
            settled = true;
            if (deadlineTimer != NO_TIMER) {
                vertx.cancelTimer(deadlineTimer);
            }
            undecidedConnections.decrementAndGet();
            return true;
        }

        private void route(@Nullable String serverName) {
            if (serverName == null) {
                LOGGER.warn(ApiSheriffLogMessages.WARN.CLIENT_HELLO_FAIL_CLOSED, "no-usable-sni");
                relay.relay(socket, buffer, terminatedTarget, RelayKind.TERMINATED, "");
                return;
            }
            RelayTarget target = passthroughTargets.get(normalizeSni(serverName));
            if (target != null) {
                relay.relay(socket, buffer, target, RelayKind.PASSTHROUGH, serverName);
            } else {
                relay.relay(socket, buffer, terminatedTarget, RelayKind.TERMINATED, "");
            }
        }
    }
}
