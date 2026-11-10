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
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;


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
import io.vertx.core.net.SocketAddress;
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
 * terminated Quarkus HTTPS listener, handing the client's address over through the relay's
 * {@link RelayedPeerRegistry} so the edge does not see every such client as the loopback relay
 * leg;</li>
 * <li>a missing / malformed SNI fails closed (GW-06): it takes the same terminated-strict path and
 * an audited {@code WARN} records the disposition only, never raw bytes.</li>
 * </ul>
 * <strong>Four bounds keep a connection the front has not routed yet, or a relay nobody uses, from
 * being held indefinitely, or one source from holding the others off.</strong> None of them is
 * configurable.
 * <ul>
 * <li><em>ClientHello deadline</em> — {@link #CLIENT_HELLO_DEADLINE}, ten seconds. The timer is
 * armed when the connection is accepted and cancelled when the route is decided; a connection that
 * has not completed its ClientHello by then is closed. A legitimate client sends its ClientHello in
 * its first flight, within one round trip, so the deadline only ever ends a silent, stalled or
 * dripping connection. The close is logged at {@code DEBUG} with the disposition only, never the
 * bytes received.</li>
 * <li><em>Undecided-connection cap</em> — {@link #MAX_UNDECIDED_CONNECTIONS}, 1024 connections
 * accepted and not yet routed or whose relay is still dialling its backend, across every event loop of
 * the front. A connection accepted beyond the cap is closed at once, without reading from it. An
 * undecided connection has received less than {@link ClientHelloSniParser#MAX_CLIENT_HELLO_BYTES}
 * (32 KiB) — the parser reaches its verdict as soon as that much has arrived — and between two reads
 * holds those bytes in three copies: the replay buffer and the parser's received and reassembled
 * arrays. Between reads, the cap therefore bounds the front's pre-route buffer memory at about 96 MiB.
 * A decided connection keeps its slot — of the cap and of its source's share — until the relay's
 * backend dial settles: the relay is established, or the dial fails or times out. When the relay throws
 * instead of starting the dial, the slot is returned at once and the connection closed. A client that leaves
 * during the dial does not return the slot early; its connection holds the slot until the dial settles
 * all the same, so no dialling connection escapes the cap. The dial window therefore counts against the
 * cap and is bounded by the dial client's fixed connect timeout,
 * {@link TlsEdgeProducer#RELAY_CONNECT_TIMEOUT} (ten seconds), whether or not the client is still
 * connected. The listener drops its parser
 * and its reference to the replay buffer at the route decision, so a dialling connection holds the
 * replay buffer alone, and the relay drops that too as soon as the client is known gone. Two amounts
 * lie outside the figure, each stated for the transport's default read sizing — Netty's adaptive
 * receive allocator, whose largest single read is 64 KiB; the front sets no receive-buffer option:
 * <ol>
 * <li>the read that decides a connection is appended to the replay buffer whole, because the relay
 * replays the client's bytes exactly, so that buffer ends up to one read past 32 KiB — below 96 KiB;</li>
 * <li>while a read is processed its bytes exist twice more, as the transport's buffer and as the copy
 * offered to the parser, and the extension walk of a complete ClientHello copies it once more —
 * transient copies of at most 64 + 64 + 32 KiB per event loop, which processes one read at a time.</li>
 * </ol>
 * The trade-off is deliberate: a client that holds
 * 1024 silent sockets makes new handshakes wait for at most one deadline window per wave of such
 * sockets — the deadline frees every slot again — and that bounded delay is preferred over unbounded
 * pre-route memory. Since a legitimate client completes its ClientHello within one round trip, 1024
 * concurrent undecided connections still admit tens of thousands of handshakes per second.</li>
 * <li><em>Per-source share</em> — {@link #MAX_UNDECIDED_PER_SOURCE}, a fixed quarter of the cap (256
 * connections), held by one source at the same time. A connection accepted while its source already
 * holds its full share is closed at once, like one beyond the cap, so a single source filling its
 * share leaves three quarters of the cap to everybody else. The source is the TCP peer address of the
 * accepted socket; every connection whose peer address is unavailable shares one fallback source.</li>
 * <li><em>Relay idle timeout</em> — the Vert.x idle timeout of the front server, the same value
 * {@link TlsEdgeProducer} also sets on the relay's dial client. It closes a relayed leg that carried
 * no byte in either direction for the timeout, which the relay's close handlers then propagate to the
 * other leg. {@link TlsEdgeProducer} derives the value at boot so that it never undercuts an idle
 * bound inside the gateway behind the terminated branch.</li>
 * </ul>
 * <strong>A refusal is visible.</strong> Every connection closed at accept — beyond the cap, or beyond
 * its source's share — is reported to the refusal recorder the listener was built with, which
 * {@link TlsEdgeProducer} wires to the {@code sheriff_front_connections_refused_total{reason}} counter,
 * and is logged as {@link ApiSheriffLogMessages.WARN#FRONT_CONNECTION_REFUSED} at most once per
 * {@link #SATURATION_WARN_INTERVAL} per {@link Refusal reason}; every further refusal inside that
 * window is a {@code DEBUG} diagnostic. Neither names the peer address or any byte received.
 * <p>
 * <strong>Why the share keys on the TCP peer address.</strong> The edge's {@code SourceKeys} resolves a
 * source from an HTTP request — a trusted forwarder's report and the {@code Host} header — and neither
 * exists before the ClientHello at L4; it also sits in a package whose dependencies lead back into this
 * one, so importing it here would close a package cycle. The peer address is the only source the front
 * has. Behind an L4 load balancer that does not propagate client addresses, every client therefore
 * shares one source and the share acts as a global sub-cap of 256 undecided connections — still tens of
 * thousands of one-round-trip handshakes per second. That is an accepted trade-off; like every front
 * bound, the share is not configurable.
 * <p>
 * The listener is constructed and started only when {@code tls.passthrough_sni} is non-empty (see
 * {@link TlsEdgeProducer}); when passthrough is unconfigured the front is never created, so the
 * default single-listener topology is unchanged.
 * <p>
 * <strong>Thread safety.</strong> Three states are shared across the front's event loops. The
 * undecided-connection count is claimed with an atomic increment, rolled back with an atomic decrement
 * when the claim overshoots the cap, and returned with an atomic decrement. The per-source counts are
 * claimed and returned inside {@link ConcurrentHashMap#compute} on the source key, which is atomic per
 * key and removes a source whose count reaches zero, so the map never holds more entries than there
 * are held slots; a share refusal rolls the already-claimed global slot back. Both slots of an admitted
 * connection are returned together, exactly once. The time of the last {@code WARN} per reason is
 * advanced with a compare-and-set, so two concurrent refusals never both log it. Everything else about
 * a connection is confined to that connection's event loop.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SniFrontListener {

    private static final CuiLogger LOGGER = new CuiLogger(SniFrontListener.class);

    /** How long an accepted connection may take to complete its ClientHello before it is closed. */
    public static final Duration CLIENT_HELLO_DEADLINE = Duration.ofSeconds(10);

    /**
     * How many accepted connections may be waiting at the same time — for their ClientHello, or, once
     * routed, for their relay's backend dial to settle.
     */
    public static final int MAX_UNDECIDED_CONNECTIONS = 1024;

    /**
     * How many accepted connections one source may hold at the same time waiting for their ClientHello
     * or for their relay's backend dial to settle: a fixed quarter of {@link #MAX_UNDECIDED_CONNECTIONS}.
     */
    public static final int MAX_UNDECIDED_PER_SOURCE = MAX_UNDECIDED_CONNECTIONS / 4;

    /**
     * The window within which {@link ApiSheriffLogMessages.WARN#FRONT_CONNECTION_REFUSED} is logged at
     * most once per {@link Refusal reason}; every further refusal for that reason inside the window is
     * a {@code DEBUG} diagnostic.
     */
    public static final Duration SATURATION_WARN_INTERVAL = Duration.ofSeconds(60);

    /** Why the front closed an accepted connection at once, without reading from it. */
    public enum Refusal {

        /** The undecided-connection cap was full. */
        CAP("cap"),

        /** The connection's source already held its full share of the cap. */
        SOURCE_SHARE("source_share");

        private final String token;

        Refusal(String token) {
            this.token = token;
        }

        /**
         * @return the fixed token the refusal is logged with
         */
        public String token() {
            return token;
        }
    }

    /** The Vert.x timer id meaning "no deadline timer was armed". */
    private static final long NO_TIMER = -1L;

    /** The last-warn time meaning "this reason has never been logged at {@code WARN}". */
    private static final long NEVER_WARNED = Long.MIN_VALUE;

    /**
     * The one source every connection whose TCP peer address is unavailable is counted under.
     * Package-private so a test can assert the key {@link #sourceOf} falls back to.
     */
    static final String UNKNOWN_SOURCE = "<unknown-source>";

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
    private final int maxUndecidedPerSource;
    private final Duration relayIdleTimeout;
    private final Duration warnInterval;
    private final LongSupplier nanoClock;
    private final Function<NetSocket, String> sourceKey;
    private final Consumer<Refusal> refusalRecorder;
    private final AtomicInteger undecidedConnections = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> undecidedPerSource = new ConcurrentHashMap<>();
    private final Map<Refusal, AtomicLong> lastWarnNanos = new EnumMap<>(Refusal.class);

    private @Nullable NetServer server;
    private @Nullable NetServerOptions serverOptions;

    /**
     * Creates a front listener bound on the Vert.x default host ({@link NetServerOptions#DEFAULT_HOST},
     * the wildcard address), which is the production bind scope {@link TlsEdgeProducer} relies on,
     * with the fixed {@link #CLIENT_HELLO_DEADLINE}, {@link #MAX_UNDECIDED_CONNECTIONS},
     * {@link #MAX_UNDECIDED_PER_SOURCE} and {@link #SATURATION_WARN_INTERVAL} on the monotonic
     * {@link System#nanoTime()} clock.
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
     * @param refusalRecorder    called once per connection closed at accept, with the reason; it runs
     *                           on the accepting event loop and must not block
     * @throws IllegalArgumentException when {@code relayIdleTimeout} is not positive
     */
    public SniFrontListener(Vertx vertx, PassthroughRelay relay, Map<String, RelayTarget> passthroughTargets,
            RelayTarget terminatedTarget, int publicPort, Duration relayIdleTimeout,
            Consumer<Refusal> refusalRecorder) {
        this(vertx, relay, passthroughTargets, terminatedTarget, publicPort, NetServerOptions.DEFAULT_HOST,
                CLIENT_HELLO_DEADLINE, MAX_UNDECIDED_CONNECTIONS, MAX_UNDECIDED_PER_SOURCE, relayIdleTimeout,
                SATURATION_WARN_INTERVAL, System::nanoTime, SniFrontListener::sourceOf, refusalRecorder);
    }

    /**
     * Creates a front listener with every bound explicit, so a test can bind a specific host, shorten
     * the bounds instead of waiting for them, drive the warn window with its own clock and assign
     * connections to sources without a second network address. Production uses the public
     * constructor, which fixes the host, the deadline, the cap, the share, the warn window, the clock
     * and the TCP-peer-address source key ({@link #sourceOf}).
     *
     * @param vertx                   the managed Vert.x instance the front server is created on
     * @param relay                   the opaque L4 relay both routing branches hand connections to
     * @param passthroughTargets      the immutable SNI (normalized, lower-cased) → backend map
     * @param terminatedTarget        the internal terminated Quarkus HTTPS endpoint
     * @param publicPort              the public TLS port the front server binds
     * @param host                    the host (address) the front server binds the public TLS port on
     * @param clientHelloDeadline     how long an accepted connection may take to complete its
     *                                ClientHello; positive
     * @param maxUndecidedConnections how many accepted connections may wait at the same time for their
     *                                ClientHello or for their relay's backend dial to settle; positive
     * @param maxUndecidedPerSource   how many of those one source may hold at the same time; positive
     * @param relayIdleTimeout        the idle timeout of the front server; positive
     * @param warnInterval            the window within which a refusal reason is logged at {@code WARN}
     *                                at most once; positive
     * @param nanoClock               the monotonic clock, in nanoseconds, the warn window is measured on
     * @param sourceKey               the source an accepted connection is counted under
     * @param refusalRecorder         called once per connection closed at accept, with the reason
     * @throws IllegalArgumentException when a bound is not positive
     */
    @SuppressWarnings("java:S107") // every bound is explicit by design: this is the test seam
    SniFrontListener(Vertx vertx, PassthroughRelay relay, Map<String, RelayTarget> passthroughTargets,
            RelayTarget terminatedTarget, int publicPort, String host, Duration clientHelloDeadline,
            int maxUndecidedConnections, int maxUndecidedPerSource, Duration relayIdleTimeout,
            Duration warnInterval, LongSupplier nanoClock, Function<NetSocket, String> sourceKey,
            Consumer<Refusal> refusalRecorder) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.relay = Objects.requireNonNull(relay, "relay");
        this.passthroughTargets = Map.copyOf(Objects.requireNonNull(passthroughTargets, "passthroughTargets"));
        this.terminatedTarget = Objects.requireNonNull(terminatedTarget, "terminatedTarget");
        this.publicPort = publicPort;
        this.host = Objects.requireNonNull(host, "host");
        this.clientHelloDeadlineMillis = requirePositive(clientHelloDeadline, "clientHelloDeadline").toMillis();
        this.maxUndecidedConnections = requirePositive(maxUndecidedConnections, "maxUndecidedConnections");
        this.maxUndecidedPerSource = requirePositive(maxUndecidedPerSource, "maxUndecidedPerSource");
        this.relayIdleTimeout = requirePositive(relayIdleTimeout, "relayIdleTimeout");
        this.warnInterval = requirePositive(warnInterval, "warnInterval");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        this.sourceKey = Objects.requireNonNull(sourceKey, "sourceKey");
        this.refusalRecorder = Objects.requireNonNull(refusalRecorder, "refusalRecorder");
        for (Refusal refusal : Refusal.values()) {
            lastWarnNanos.put(refusal, new AtomicLong(NEVER_WARNED));
        }
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
        this.serverOptions = options;
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
     * Test seam: the idle timeout {@link #start()} set on the front server's {@link NetServerOptions},
     * read back from those options — count and unit as {@link #applyIdleTimeout} chose them — so a test
     * can observe the bound the server was created with instead of waiting for it to elapse. Reading it
     * changes nothing.
     *
     * @return the idle timeout applied to the front server, or empty when the listener has not been
     *         started
     */
    Optional<Duration> appliedIdleTimeout() {
        NetServerOptions current = serverOptions;
        if (current == null) {
            return Optional.empty();
        }
        return Optional.of(Duration.of(current.getIdleTimeout(), current.getIdleTimeoutUnit().toChronoUnit()));
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
     * Admits an accepted connection when both the undecided-connection cap and its source's share have
     * a free slot, and refuses it — closes it at once, without reading from it — when either has none.
     */
    private void onConnect(NetSocket socket) {
        String source = sourceKey.apply(socket);
        Refusal refusal = claimSlot(source);
        if (refusal != null) {
            refuse(socket, refusal);
            return;
        }
        new Incoming(socket, source).begin();
    }

    /**
     * Claims one slot of the cap and one of {@code source}'s share. The global slot is claimed with one
     * atomic increment and rolled back with one atomic decrement on overshoot, so concurrent accepts on
     * different event loops can never push the count past the cap. The source's slot is claimed inside
     * {@link ConcurrentHashMap#compute}, atomic per key; when the share is full, the global slot already
     * claimed is rolled back, so a refused connection holds neither.
     *
     * @param source the source the connection is counted under
     * @return {@code null} when both slots were claimed, otherwise the reason neither is held
     */
    private @Nullable Refusal claimSlot(String source) {
        if (undecidedConnections.incrementAndGet() > maxUndecidedConnections) {
            undecidedConnections.decrementAndGet();
            return Refusal.CAP;
        }
        AtomicBoolean claimed = new AtomicBoolean();
        undecidedPerSource.compute(source, (key, held) -> {
            int count = held == null ? 0 : held;
            if (count >= maxUndecidedPerSource) {
                return held;
            }
            claimed.set(true);
            return count + 1;
        });
        if (!claimed.get()) {
            undecidedConnections.decrementAndGet();
            return Refusal.SOURCE_SHARE;
        }
        return null;
    }

    /**
     * Refuses a connection: logs the refusal — at {@code WARN} for the first refusal of its reason in a
     * warn window, at {@code DEBUG} for every further one, neither line naming the peer address or any
     * byte received — reports it to the recorder, and only then closes the connection without reading
     * from it, so the close a client observes always follows the refusal's record.
     */
    private void refuse(NetSocket socket, Refusal refusal) {
        socket.pause();
        String bound = Integer.toString(refusal == Refusal.CAP ? maxUndecidedConnections : maxUndecidedPerSource);
        if (warnDue(refusal)) {
            LOGGER.warn(ApiSheriffLogMessages.WARN.FRONT_CONNECTION_REFUSED, refusal.token(), bound,
                    Long.toString(warnInterval.toSeconds()));
        } else {
            LOGGER.debug("Front connection refused at accept (%s): the bound of %s undecided connections is full",
                    refusal.token(), bound);
        }
        refusalRecorder.accept(refusal);
        closeQuietly(socket);
    }

    /**
     * Whether this refusal is the one logged at {@code WARN} for its reason in the current warn window.
     * The last-warn time is advanced with a compare-and-set, so of two concurrent refusals at most one
     * wins the window.
     */
    private boolean warnDue(Refusal refusal) {
        AtomicLong lastWarn = lastWarnNanos.get(refusal);
        long now = nanoClock.getAsLong();
        long last = lastWarn.get();
        boolean windowOpen = last == NEVER_WARNED || now - last >= warnInterval.toNanos();
        return windowOpen && lastWarn.compareAndSet(last, now);
    }

    /**
     * The source a connection is counted under in production: its TCP peer address — the port is not
     * part of it — or the shared fallback source when the address is unavailable.
     *
     * @param socket the accepted connection
     * @return the connection's source key
     */
    static String sourceOf(NetSocket socket) {
        SocketAddress peer = socket.remoteAddress();
        String address = peer == null ? null : peer.hostAddress();
        return address == null || address.isEmpty() ? UNKNOWN_SOURCE : address;
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

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
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
     * connection closing or failing, and the deadline expiring sets it and cancels the deadline. Every
     * later path finds it set and does nothing. A close, failure or deadline returns the connection's
     * slots — of the undecided-connection cap and of its source's share, as one unit — at once; the route
     * decision instead hands the connection to the relay and returns the slots in the single completion
     * handler of the relay's dial-phase future, which completes when the backend dial settles — not when
     * the client leaves. A hand-off the relay refuses by throwing instead of returning that future —
     * Vert.x, for one, throws from the dial call for a backend port outside 0 to 65535 — is turned into a
     * failed dial phase, so the same handler runs at once: it returns the slots and closes the connection.
     * On every path the slots are returned exactly once. At the
     * route decision the connection also drops its parser and its reference to the replay buffer, whose
     * only owner from then on is the relay.
     */
    private final class Incoming {

        private final NetSocket socket;
        private final String source;
        private @Nullable Buffer buffer = Buffer.buffer();
        private @Nullable ClientHelloSniParser parser = new ClientHelloSniParser();
        private boolean settled;
        private long deadlineTimer = NO_TIMER;

        Incoming(NetSocket socket, String source) {
            this.socket = socket;
            this.source = source;
        }

        void begin() {
            socket.handler(this::onData);
            socket.exceptionHandler(this::onError);
            socket.closeHandler(closed -> settle());
            deadlineTimer = vertx.setTimer(clientHelloDeadlineMillis, timerId -> onDeadline());
        }

        private void onData(Buffer chunk) {
            Buffer replay = buffer;
            ClientHelloSniParser reader = parser;
            if (settled || replay == null || reader == null) {
                return;
            }
            replay.appendBuffer(chunk);
            ClientHelloSniParser.Result result = reader.offer(chunk.getBytes());
            if (result.complete() && decide()) {
                buffer = null;
                parser = null;
                socket.pause();
                // Future.future turns a relay that throws instead of returning its dial-phase future
                // into a failed dial phase, so the handler below still runs and the slots come back.
                Future.<Void>future(dialPhase -> route(result.serverName(), replay).onComplete(dialPhase))
                        .onComplete(dialPhaseOver -> {
                            releaseSlot();
                            if (dialPhaseOver.failed()) {
                                LOGGER.debug(dialPhaseOver.cause(),
                                        "Front connection closed: the hand-off to the relay failed: %s",
                                        dialPhaseOver.cause().getMessage());
                                closeQuietly(socket);
                            }
                        });
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
         * Sets the latch, cancels the deadline and returns the connection's slots at once — for a close,
         * a failure or the deadline, which end the connection before any route.
         *
         * @return {@code true} for the one call that settled the connection, {@code false} for every
         *         later call
         */
        private boolean settle() {
            if (!decide()) {
                return false;
            }
            releaseSlot();
            return true;
        }

        /**
         * Returns both slots this connection holds, the cap's and its source's share. The source's count
         * is decremented inside {@link ConcurrentHashMap#computeIfPresent}, which removes the source once
         * it holds no slot.
         */
        private void releaseSlot() {
            undecidedPerSource.computeIfPresent(source, (key, held) -> held <= 1 ? null : held - 1);
            undecidedConnections.decrementAndGet();
        }

        /**
         * Sets the latch and cancels the deadline — once — without returning the connection's slots.
         *
         * @return {@code true} for the one call that set the latch, {@code false} for every later call
         */
        private boolean decide() {
            if (settled) {
                return false;
            }
            settled = true;
            if (deadlineTimer != NO_TIMER) {
                vertx.cancelTimer(deadlineTimer);
            }
            return true;
        }

        /**
         * Hands the connection, with its replay buffer, to the relay for the target its SNI selects.
         *
         * @return the relay's dial-phase future, completing once the backend dial has settled
         */
        private Future<Void> route(@Nullable String serverName, Buffer replay) {
            if (serverName == null) {
                LOGGER.warn(ApiSheriffLogMessages.WARN.CLIENT_HELLO_FAIL_CLOSED, "no-usable-sni");
                return relay.relay(socket, replay, terminatedTarget, RelayKind.TERMINATED, "");
            }
            RelayTarget target = passthroughTargets.get(normalizeSni(serverName));
            if (target != null) {
                return relay.relay(socket, replay, target, RelayKind.PASSTHROUGH, serverName);
            }
            return relay.relay(socket, replay, terminatedTarget, RelayKind.TERMINATED, "");
        }
    }
}
