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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HexFormat;
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
import java.util.function.Supplier;

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
 * <strong>Five bounds keep a connection the front has not routed yet, or a relay nobody uses, from
 * being held indefinitely, or one source or one passthrough backend from holding the others off.</strong>
 * None of them is configurable.
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
 * cap, whether or not the client is still connected. It spans the host-name lookup of a target named by
 * host name, bounded by the DNS resolver's own query timeout, and the TCP connect, bounded by the dial
 * client's fixed connect timeout, {@link TlsEdgeProducer#RELAY_CONNECT_TIMEOUT} (ten seconds). The
 * listener drops its parser
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
 * The trade-off is deliberate: a slot held by a connection that never completes its ClientHello is
 * held for at most one deadline window — the deadline frees it again — and a slot held by a dialling
 * connection for at most one dial window. While the cap is full a new connection is not queued: it is
 * closed, and its client has to connect again. Refusing new connections for that bounded time is
 * preferred over unbounded pre-route memory. No single party can take the whole cap: one source holds
 * at most its share, a quarter of the cap, and the connections dialling one passthrough target at most
 * that target's dial budget, an eighth of it. Since a legitimate client completes its ClientHello
 * within one round trip, 1024 concurrent undecided connections still admit tens of thousands of
 * handshakes per second.</li>
 * <li><em>Per-source share</em> — {@link #MAX_UNDECIDED_PER_SOURCE}, a fixed quarter of the cap (256
 * connections), held by one source at the same time. A connection accepted while its source already
 * holds its full share is closed at once, like one beyond the cap, so a single source filling its
 * share leaves three quarters of the cap to everybody else. The source is the TCP peer address of the
 * accepted socket: an IPv4 peer is counted by its address, an IPv6 peer by the /64 prefix of its
 * address — the smallest prefix assigned to one end-site subnet, so one host cannot multiply its share
 * by spreading its connections over the addresses of its own /64 — and an IPv4-mapped IPv6 peer by the
 * IPv4 address it carries, the same source as that peer reaching the front over IPv4. Every connection
 * whose peer address is unavailable shares one fallback source.</li>
 * <li><em>Per-target dial budget</em> — {@link #MAX_DIALS_PER_TARGET}, a fixed eighth of the cap (128
 * connections) dialling one passthrough target at the same time. A passthrough match claims one dial
 * slot of its target at the route decision and returns it, together with its slots of the cap and of
 * its source's share, when the dial settles. A connection routed to a target whose budget is full is
 * refused there and then — its slots of the cap and of its source's share come back at once, and it is
 * counted and logged like a refusal at accept, under its own reason — so a passthrough backend that
 * does not answer the dial holds at most 128 slots of the cap, each for at most one dial window, and
 * leaves seven eighths of the cap to terminated handshakes and to every other target. The budget is a
 * share of the cap, not a second bound beside it: every dialling connection still counts against the
 * cap. Several dark targets add up: two hold at most a quarter of the cap, four at most half, and eight
 * could hold all of it — the budget bounds what one unreachable backend can take, not what a set of
 * them can. A target is a backend's host and port, not an SNI hostname: every {@code passthrough_sni}
 * entry that resolves to the same host and port shares one budget, and a host name is compared without
 * regard to letter case ({@link #dialBudgetKeyOf}), so two entries that spell one backend's name
 * differently share it too. The terminated hand-off target is exempt: its dial goes to the gateway's
 * own internal listener on loopback and settles at once, and budgeting it would refuse exactly the
 * terminated handshakes the budget exists to protect.</li>
 * <li><em>Relay idle timeout</em> — the Vert.x idle timeout of the front server, the same value
 * {@link TlsEdgeProducer} also sets on the relay's dial client. It closes a relayed leg that carried
 * no byte in either direction for the timeout, which the relay's close handlers then propagate to the
 * other leg. {@link TlsEdgeProducer} derives the value at boot so that it never undercuts an idle
 * bound inside the gateway behind the terminated branch.</li>
 * </ul>
 * <strong>A refusal is visible.</strong> Every connection closed at accept — beyond the cap, or beyond
 * its source's share — and every connection closed at its route decision beyond its target's dial
 * budget is reported to the refusal recorder the listener was built with, which
 * {@link TlsEdgeProducer} wires to the {@code sheriff_front_connections_refused_total{reason}} counter,
 * and is logged as {@link ApiSheriffLogMessages.WARN#FRONT_CONNECTION_REFUSED} at most once per
 * {@link #SATURATION_WARN_INTERVAL} per {@link Refusal reason}; every further refusal inside that
 * window is a {@code DEBUG} diagnostic. Neither names the peer address or any byte received. The
 * refusal is recorded before the connection is closed, so the close a client observes follows the
 * record; when logging or recording the refusal throws, the connection is closed all the same.
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
 * <strong>Thread safety.</strong> Four states are shared across the front's event loops. An accepted
 * connection claims its source's slot first and the cap's slot second. The per-source counts are
 * claimed and returned inside {@link ConcurrentHashMap#compute} on the source key, which is atomic per
 * key and removes a source whose count reaches zero, so the map never holds more entries than there
 * are held slots. The undecided-connection count is then claimed with a compare-and-set update that
 * increments it only while it is below the cap, so it never holds a value above the cap — not even for
 * an instant a concurrent accept could observe — and is returned with an atomic decrement. A full cap
 * returns the source's slot just claimed. A connection refused for its source's share therefore never
 * touched the cap, and one refused for the cap holds no share: every refusal names the bound that
 * refused it. The per-target dial
 * counts are claimed and returned the same way, inside {@link ConcurrentHashMap#compute} on the target's
 * budget key, so no read-then-write window lets two dials take the budget's last slot. Every slot of an
 * admitted connection is returned together, exactly once. The time of the last {@code WARN} per reason is
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
     * How many routed connections may be dialling one passthrough target at the same time: a fixed
     * eighth of {@link #MAX_UNDECIDED_CONNECTIONS}, counted inside that cap. The terminated hand-off
     * target is exempt.
     */
    public static final int MAX_DIALS_PER_TARGET = MAX_UNDECIDED_CONNECTIONS / 8;

    /**
     * The window within which {@link ApiSheriffLogMessages.WARN#FRONT_CONNECTION_REFUSED} is logged at
     * most once per {@link Refusal reason}; every further refusal for that reason inside the window is
     * a {@code DEBUG} diagnostic.
     */
    public static final Duration SATURATION_WARN_INTERVAL = Duration.ofSeconds(60);

    /**
     * Why the front closed a connection at once: at accept, without reading from it, or at the route
     * decision of a passthrough match, without dialling its backend.
     */
    public enum Refusal {

        /** The undecided-connection cap was full. */
        CAP("cap"),

        /** The connection's source already held its full share of the cap. */
        SOURCE_SHARE("source_share"),

        /** The connection's passthrough target already had its full dial budget in flight. */
        DIAL_BUDGET("dial_budget");

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

    /** How many leading bytes of an IPv6 peer address its source is keyed on: the /64 prefix. */
    private static final int IPV6_SOURCE_PREFIX_BYTES = 8;

    /** Appended to the hex-encoded /64 prefix, so an IPv6 source key never equals an IPv4 one. */
    private static final String IPV6_SOURCE_SUFFIX = "/64";

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
    private final int maxDialsPerTarget;
    private final Duration relayIdleTimeout;
    private final Duration warnInterval;
    private final LongSupplier nanoClock;
    private final Function<NetSocket, String> sourceKey;
    private final Consumer<Refusal> refusalRecorder;
    private final AtomicInteger undecidedConnections = new AtomicInteger();
    private final ConcurrentHashMap<String, Integer> undecidedPerSource = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RelayTarget, Integer> dialsPerTarget = new ConcurrentHashMap<>();
    private final Map<Refusal, AtomicLong> lastWarnNanos = new EnumMap<>(Refusal.class);

    private @Nullable NetServer server;
    private @Nullable NetServerOptions serverOptions;

    /**
     * Creates a front listener bound on the Vert.x default host ({@link NetServerOptions#DEFAULT_HOST},
     * the wildcard address), which is the production bind scope {@link TlsEdgeProducer} relies on,
     * with the fixed {@link #CLIENT_HELLO_DEADLINE}, {@link #MAX_UNDECIDED_CONNECTIONS},
     * {@link #MAX_UNDECIDED_PER_SOURCE}, {@link #MAX_DIALS_PER_TARGET} and
     * {@link #SATURATION_WARN_INTERVAL} on the monotonic {@link System#nanoTime()} clock.
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
     * @param refusalRecorder    called once per connection the front refuses, with the reason; it runs
     *                           on the connection's event loop and must not block
     * @throws IllegalArgumentException when {@code relayIdleTimeout} is not positive
     */
    public SniFrontListener(Vertx vertx, PassthroughRelay relay, Map<String, RelayTarget> passthroughTargets,
            RelayTarget terminatedTarget, int publicPort, Duration relayIdleTimeout,
            Consumer<Refusal> refusalRecorder) {
        this(vertx, relay, passthroughTargets, terminatedTarget, publicPort, NetServerOptions.DEFAULT_HOST,
                CLIENT_HELLO_DEADLINE, MAX_UNDECIDED_CONNECTIONS, MAX_UNDECIDED_PER_SOURCE, MAX_DIALS_PER_TARGET,
                relayIdleTimeout, SATURATION_WARN_INTERVAL, System::nanoTime, SniFrontListener::sourceOf,
                refusalRecorder);
    }

    /**
     * Creates a front listener with every bound explicit, so a test can bind a specific host, shorten
     * the bounds instead of waiting for them, drive the warn window with its own clock and assign
     * connections to sources without a second network address. Production uses the public
     * constructor, which fixes the host, the deadline, the cap, the share, the dial budget, the warn
     * window, the clock and the TCP-peer-address source key ({@link #sourceOf}).
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
     * @param maxDialsPerTarget       how many of those may be dialling one passthrough target at the same
     *                                time; positive
     * @param relayIdleTimeout        the idle timeout of the front server; positive
     * @param warnInterval            the window within which a refusal reason is logged at {@code WARN}
     *                                at most once; positive
     * @param nanoClock               the monotonic clock, in nanoseconds, the warn window is measured on
     * @param sourceKey               the source an accepted connection is counted under
     * @param refusalRecorder         called once per connection the front refuses, with the reason
     * @throws IllegalArgumentException when a bound is not positive
     */
    @SuppressWarnings("java:S107") // every bound is explicit by design: this is the test seam
    SniFrontListener(Vertx vertx, PassthroughRelay relay, Map<String, RelayTarget> passthroughTargets,
            RelayTarget terminatedTarget, int publicPort, String host, Duration clientHelloDeadline,
            int maxUndecidedConnections, int maxUndecidedPerSource, int maxDialsPerTarget,
            Duration relayIdleTimeout, Duration warnInterval, LongSupplier nanoClock,
            Function<NetSocket, String> sourceKey, Consumer<Refusal> refusalRecorder) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.relay = Objects.requireNonNull(relay, "relay");
        this.passthroughTargets = Map.copyOf(Objects.requireNonNull(passthroughTargets, "passthroughTargets"));
        this.terminatedTarget = Objects.requireNonNull(terminatedTarget, "terminatedTarget");
        this.publicPort = publicPort;
        this.host = Objects.requireNonNull(host, "host");
        this.clientHelloDeadlineMillis = requirePositive(clientHelloDeadline, "clientHelloDeadline").toMillis();
        this.maxUndecidedConnections = requirePositive(maxUndecidedConnections, "maxUndecidedConnections");
        this.maxUndecidedPerSource = requirePositive(maxUndecidedPerSource, "maxUndecidedPerSource");
        this.maxDialsPerTarget = requirePositive(maxDialsPerTarget, "maxDialsPerTarget");
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
     * Claims one slot of {@code source}'s share and then one of the cap. The source's slot is claimed
     * inside {@link ConcurrentHashMap#compute}, atomic per key. The cap's slot is claimed with a
     * compare-and-set update that increments the count only while it is below the cap, so concurrent
     * accepts on different event loops never store — and so never observe — a count above the cap. When
     * the cap is full, the source's slot just claimed is returned, so a refused connection holds neither,
     * and the refusal names the bound that refused it.
     *
     * @param source the source the connection is counted under
     * @return {@code null} when both slots were claimed, otherwise the reason neither is held
     */
    private @Nullable Refusal claimSlot(String source) {
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
            return Refusal.SOURCE_SHARE;
        }
        int before = undecidedConnections.getAndUpdate(held -> held < maxUndecidedConnections ? held + 1 : held);
        if (before >= maxUndecidedConnections) {
            releaseShare(source);
            return Refusal.CAP;
        }
        return null;
    }

    /**
     * Returns one slot of {@code source}'s share inside {@link ConcurrentHashMap#computeIfPresent}, which
     * removes the source once it holds no slot.
     */
    private void releaseShare(String source) {
        undecidedPerSource.computeIfPresent(source, (key, held) -> held <= 1 ? null : held - 1);
    }

    /**
     * Test seam: how many sources hold at least one slot of their share right now — the number of entries
     * of the per-source map, which drops a source once it holds no slot. Reading it changes nothing.
     *
     * @return the number of sources currently holding a slot
     */
    int sourcesHoldingSlots() {
        return undecidedPerSource.size();
    }

    /**
     * Refuses a connection: logs the refusal — at {@code WARN} for the first refusal of its reason in a
     * warn window, at {@code DEBUG} for every further one, neither line naming the peer address or any
     * byte received — reports it to the recorder, and only then closes the connection without reading
     * from it any further, so the close a client observes always follows the refusal's record. The close
     * runs in a {@code finally}: a recorder or a log handler that throws still leaves the connection
     * closed.
     */
    private void refuse(NetSocket socket, Refusal refusal) {
        try {
            socket.pause();
            String bound = Integer.toString(switch (refusal) {
                case CAP -> maxUndecidedConnections;
                case SOURCE_SHARE -> maxUndecidedPerSource;
                case DIAL_BUDGET -> maxDialsPerTarget;
            });
            if (warnDue(refusal)) {
                LOGGER.warn(ApiSheriffLogMessages.WARN.FRONT_CONNECTION_REFUSED, refusal.token(), bound,
                        Long.toString(warnInterval.toSeconds()));
            } else {
                LOGGER.debug("Front connection refused (%s): the bound of %s connections for this reason is full",
                        refusal.token(), bound);
            }
            refusalRecorder.accept(refusal);
        } finally {
            closeQuietly(socket);
        }
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
     * The source a connection is counted under in production: its TCP peer address, keyed by
     * {@link #sourceKeyOf} — the port is not part of it — or the shared fallback source when the address
     * is unavailable.
     *
     * @param socket the accepted connection
     * @return the connection's source key
     */
    static String sourceOf(NetSocket socket) {
        SocketAddress peer = socket.remoteAddress();
        String address = peer == null ? null : peer.hostAddress();
        return address == null || address.isEmpty() ? UNKNOWN_SOURCE : sourceKeyOf(address);
    }

    /**
     * The source key of a peer address literal: an IPv4 address is its own key; an IPv6 address is keyed
     * by its /64 prefix, so every address of one end-site subnet is one source; an IPv4-mapped IPv6
     * address is keyed as the IPv4 address it carries. The literal is parsed, never resolved — no name
     * lookup happens — and a zone index ({@code %scope}) is not part of the key. A literal that does not
     * parse as an IP address is its own key.
     *
     * @param address the peer's address literal, as the transport reports it
     * @return the source key the address is counted under
     */
    static String sourceKeyOf(String address) {
        if (address.indexOf(':') < 0) {
            return address;
        }
        int zone = address.indexOf('%');
        InetAddress parsed;
        try {
            parsed = InetAddress.ofLiteral(zone < 0 ? address : address.substring(0, zone));
        } catch (IllegalArgumentException _) {
            return address;
        }
        if (parsed instanceof Inet4Address mapped) {
            return mapped.getHostAddress();
        }
        return HexFormat.of().formatHex(parsed.getAddress(), 0, IPV6_SOURCE_PREFIX_BYTES) + IPV6_SOURCE_SUFFIX;
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
     * the client leaves. A passthrough match also holds one dial slot of its target's budget, claimed at
     * the route decision and returned in that same handler; when the budget is full the connection is
     * refused at the route decision instead, and its slots are returned at once. A hand-off the relay
     * refuses by throwing instead of returning that future — Vert.x, for one, throws from the dial call
     * for a backend port outside 0 to 65535 — is turned into a failed dial phase, so the same handler runs
     * at once: it returns the slots and closes the connection. On every path the slots are returned
     * exactly once. At the
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
                route(result.serverName(), replay);
            }
        }

        /**
         * Hands the connection, with its replay buffer, to the relay for the target its SNI selects. A
         * passthrough match first claims one dial slot of its target; when the target's budget is full the
         * connection is refused instead — its slots of the cap and of its source's share are returned at
         * once and it is never dialled. The terminated hand-off claims no dial slot.
         */
        private void route(@Nullable String serverName, Buffer replay) {
            RelayTarget target = serverName == null ? null : passthroughTargets.get(normalizeSni(serverName));
            if (serverName != null && target != null) {
                if (!claimDial(target)) {
                    releaseSlot();
                    refuse(socket, Refusal.DIAL_BUDGET);
                    return;
                }
                dial(() -> relay.relay(socket, replay, target, RelayKind.PASSTHROUGH, serverName), target);
                return;
            }
            if (serverName == null) {
                LOGGER.warn(ApiSheriffLogMessages.WARN.CLIENT_HELLO_FAIL_CLOSED, "no-usable-sni");
            }
            dial(() -> relay.relay(socket, replay, terminatedTarget, RelayKind.TERMINATED, ""), null);
        }

        /**
         * Starts the hand-off and returns every slot the connection holds — of the cap, of its source's
         * share and, for a passthrough match, of its target's dial budget — in the single completion
         * handler of the relay's dial-phase future.
         *
         * @param handOff  starts the relay and returns its dial-phase future
         * @param budgeted the passthrough target whose dial slot the connection holds, or {@code null} for
         *                 the terminated hand-off, which holds none
         */
        private void dial(Supplier<Future<Void>> handOff, @Nullable RelayTarget budgeted) {
            // Future.future turns a relay that throws instead of returning its dial-phase future into a
            // failed dial phase, so the handler below still runs and every slot comes back.
            Future.<Void>future(dialPhase -> handOff.get().onComplete(dialPhase))
                    .onComplete(dialPhaseOver -> {
                        releaseSlot();
                        if (budgeted != null) {
                            releaseDial(budgeted);
                        }
                        if (dialPhaseOver.failed()) {
                            LOGGER.debug(dialPhaseOver.cause(),
                                    "Front connection closed: the hand-off to the relay failed: %s",
                                    dialPhaseOver.cause().getMessage());
                            closeQuietly(socket);
                        }
                    });
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
            releaseShare(source);
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
         * Claims one dial slot of {@code target}'s budget inside {@link ConcurrentHashMap#compute}, atomic per
         * {@linkplain SniFrontListener#dialBudgetKeyOf budget key}: the count is read and written in one step,
         * so two dials can never both take the budget's last slot.
         *
         * @param target the passthrough target the connection is routed to
         * @return {@code true} when the slot was claimed, {@code false} when the budget is full
         */
        private boolean claimDial(RelayTarget target) {
            AtomicBoolean claimed = new AtomicBoolean();
            dialsPerTarget.compute(dialBudgetKeyOf(target), (key, held) -> {
                int count = held == null ? 0 : held;
                if (count >= maxDialsPerTarget) {
                    return held;
                }
                claimed.set(true);
                return count + 1;
            });
            return claimed.get();
        }

        /**
         * Returns one dial slot of {@code target}'s budget inside {@link ConcurrentHashMap#computeIfPresent},
         * under the same {@linkplain SniFrontListener#dialBudgetKeyOf budget key} it was claimed under; the key
         * is removed once no dial to it is in flight.
         */
        private void releaseDial(RelayTarget target) {
            dialsPerTarget.computeIfPresent(dialBudgetKeyOf(target), (key, held) -> held <= 1 ? null : held - 1);
        }
    }

    /**
     * The key a passthrough target's dial budget is counted under: the target with a host <em>name</em>
     * lowered in the root locale. Host names compare without regard to letter case, so two targets that
     * spell one backend's name differently dial the same backend and must share its budget; keyed by the
     * target as declared, each spelling would hold a budget of its own and one unreachable backend could
     * take a multiple of its eighth of the cap.
     * <p>
     * A host holding a colon is an IPv6 literal and is kept as declared: it may carry a zone index, which
     * names an interface and is case-sensitive. An IPv4 literal has no letter case. Only the count is
     * keyed this way — the relay dials the target as it was declared.
     *
     * @param target the passthrough target a connection is routed to
     * @return the target the dial budget counts it as
     */
    static RelayTarget dialBudgetKeyOf(RelayTarget target) {
        String host = target.host();
        return host.indexOf(':') >= 0 ? target : new RelayTarget(host.toLowerCase(Locale.ROOT), target.port());
    }
}
