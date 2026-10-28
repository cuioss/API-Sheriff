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

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import de.cuioss.http.forwarded.ForwardedHeaderResolver;
import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceKeyResolver;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceState;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.edge.end.ResponseDelay;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.edge.error.ReconEvents;
import de.cuioss.sheriff.gateway.edge.request.EdgeRequests;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.forward.TcpPeerGate;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * Wires the per-source detection substrate ({@code de.cuioss.sheriff.gateway.detection}) into the
 * edge: it resolves the source a request is tracked under, answers a blocked source before
 * admission, feeds the recon ledger with every pre-trust rejection, and reports every trip.
 * <p>
 * <strong>One recon ledger.</strong> The gate builds the edge's recon {@link SourceLedger} from the
 * resolved {@code enumeration_detection} block and registers itself as its trip listener. The
 * configuration action is mapped onto the ledger's own {@link SourceLedger.Action}: {@code block}
 * drives the ledger's {@code BLOCKED} state, {@code delay} and {@code observe} never do.
 * <p>
 * <strong>The source.</strong> {@link #answersBlocked} resolves the request's {@link SourceKey} on the
 * event loop through the {@link SourceKeyResolver} and stashes it on the context under
 * {@link #SOURCE_KEY}, where the rejection feed finds it after the virtual-thread hop. The source is
 * the client address a trusted forwarder reported — resolved only for a peer the {@link TcpPeerGate}
 * trusts, through a {@link ForwardedHeaderResolver} configured exactly as the forward stage's, so the
 * address keyed on is the one the gateway regenerates {@code X-Forwarded-For} from — and otherwise the
 * TCP peer. An untrusted peer's {@code X-Forwarded-For} is never read.
 * <p>
 * <strong>The block.</strong> A source can be {@code BLOCKED} only when the operator configured
 * {@code action: block}; the default {@code observe} never blocks. A request from a blocked source is
 * answered on the event loop, before {@link EdgeAdmission#tryAdmit()}: it is metered as
 * {@link EventType#SOURCE_BLOCKED} and answered {@code 429} with {@code Retry-After} — the whole
 * seconds left of the block, at least {@code 1} — and the {@link EventCategory#RATE_LIMITING} problem
 * document, through the one end rule ({@link ResponseEnds#endResponse}). No admission permit, no
 * virtual thread and no timer is held for it. Under {@code delay} the same answer — with
 * {@code Retry-After} the whole seconds of {@code delay_millis} — refuses a tripped source's request
 * while that source's {@linkplain EdgeHardeningOptions#heldAnswerSourceShare() share} of the
 * held-answer sub-budget is used up, so one delayed source cannot occupy the whole sub-budget.
 * <p>
 * <strong>The feed.</strong> As the {@link ErrorResponseWriter.ReconFeed}, the gate admits every
 * reported rejection to the ledger with the weight {@link ReconEvents#weightOf} gives its TRUE event —
 * a recon event before the trust boundary that is not a static resource. Under {@code delay}, a
 * rejection whose source has tripped is held until admission plus {@code delay_millis}, and its hold
 * counts against that source's share ({@link EdgeAdmission#HELD_SOURCE_KEY}). An unexpected
 * internal failure answered as the uniform {@code 404} ({@link EventType#PRE_TRUST_INTERNAL_FAILURE})
 * is never admitted, but under {@code delay} it is held exactly as a recon rejection of the same
 * request would be: otherwise, for a tripped source, a crafted input that made a stage fail on a routed
 * address would be answered at the floor while the same input on an absent address waited for the
 * delay, and the timing would tell the two apart. A source the
 * ledger could not track — the cardinality bound — is counted on
 * {@link SheriffMetrics#SOURCE_UNTRACKED_TOTAL}, and the first overflow of the process is logged once
 * ({@link ApiSheriffLogMessages.WARN#SOURCE_UNTRACKED}).
 * <p>
 * <strong>The trip.</strong> Every trip, whatever the action, is counted as
 * {@link EventType#SOURCE_TRIPPED} on the {@link GatewayEventCounter}, on
 * {@link SheriffMetrics#SOURCE_TRIPS_TOTAL} under its action, and logged as
 * {@link ApiSheriffLogMessages.WARN#SOURCE_TRIPPED} — the canonical source and the folded host, never
 * the raw {@code Host}, the path or any header. Under the default {@code observe} that is the whole
 * effect of a trip: no answer changes.
 * <p>
 * Thread-safe: the ledger is thread-safe, the overflow latch is atomic, and every other field is
 * immutable or thread-safe. {@link #answersBlocked} runs on the event loop; {@link #admit} runs on the
 * thread that renders the rejection and calls the trip listener there.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SourceGate implements ErrorResponseWriter.ReconFeed {

    private static final CuiLogger LOGGER = new CuiLogger(SourceGate.class);

    /**
     * Holds the {@link SourceKey} {@link #answersBlocked} resolved on the event loop, so the rejection
     * feed — which runs after the virtual-thread hop, where only the {@link RoutingContext} is in scope
     * — admits the rejection under the same source.
     */
    public static final String SOURCE_KEY = "sheriff.sourcekey";

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final SourceKeyResolver keyResolver;
    private final TcpPeerGate peerGate;
    private final ForwardedHeaderResolver forwardedResolver;
    private final GatewayEventCounter gatewayEventCounter;
    private final SheriffMetrics sheriffMetrics;
    private final String blockedProblemBody;
    /** The answer hold under {@code delay}; empty under {@code block} and {@code observe}. */
    private final OptionalLong delayNanos;
    private final AtomicBoolean overflowReported = new AtomicBoolean();
    private final SourceLedger ledger;
    /** Whether a source's share of the held-answer sub-budget is used up ({@link EdgeAdmission#heldShareExhausted}). */
    private final Predicate<SourceKey> heldShareExhausted;

    /**
     * Builds the gate and the recon ledger it listens to.
     *
     * @param config              the resolved {@code enumeration_detection} block; the gate is built only
     *                            when it is enabled
     * @param keyResolver         the resolver of the key a request is tracked under, holding the
     *                            declared {@code match.host} set
     * @param peerGate            the immediate-TCP-peer trust gate of the {@code forwarded} block
     * @param forwardedResolver   a forwarded-header resolver configured exactly as the forward stage's
     * @param gatewayEventCounter the shared in-process event counter
     * @param sheriffMetrics      the Micrometer adapter the block, trip and overflow are recorded through
     * @param gatewayJson         the serializer the {@code 429} problem document is rendered through
     * @param heldShareExhausted  whether a source's share of the held-answer sub-budget is used up — the
     *                            edge admission's {@link EdgeAdmission#heldShareExhausted}; read only
     *                            under {@code delay}
     */
    // The collaborators are the gate's distinct dependencies; a parameter object would only group them
    // for the count.
    @SuppressWarnings("java:S107")
    public SourceGate(EnumerationDetectionConfig config, SourceKeyResolver keyResolver, TcpPeerGate peerGate,
            ForwardedHeaderResolver forwardedResolver, GatewayEventCounter gatewayEventCounter,
            SheriffMetrics sheriffMetrics, GatewayJson gatewayJson, Predicate<SourceKey> heldShareExhausted) {
        Objects.requireNonNull(config, "config");
        this.heldShareExhausted = Objects.requireNonNull(heldShareExhausted, "heldShareExhausted");
        this.keyResolver = Objects.requireNonNull(keyResolver, "keyResolver");
        this.peerGate = Objects.requireNonNull(peerGate, "peerGate");
        this.forwardedResolver = Objects.requireNonNull(forwardedResolver, "forwardedResolver");
        this.gatewayEventCounter = Objects.requireNonNull(gatewayEventCounter, "gatewayEventCounter");
        this.sheriffMetrics = Objects.requireNonNull(sheriffMetrics, "sheriffMetrics");
        EventCategory blocked = EventCategory.RATE_LIMITING;
        this.blockedProblemBody = ErrorResponseWriter.problemBody(Objects.requireNonNull(gatewayJson, "gatewayJson"),
                blocked.problemType(), blocked.title(), EventType.SOURCE_BLOCKED.httpStatus(), Map.of());
        SourceLedger.Action action = ledgerAction(config.effectiveAction());
        this.delayNanos = action == SourceLedger.Action.DELAY
                ? OptionalLong.of(Duration.ofMillis(config.effectiveDelayMillis()).toNanos())
                : OptionalLong.empty();
        SourceLedger.Settings settings = new SourceLedger.Settings(config.effectiveThreshold(),
                Duration.ofSeconds(config.effectiveWindowSeconds()), action,
                Duration.ofSeconds(config.effectiveBlockSeconds()));
        this.ledger = new SourceLedger(settings, System::nanoTime, this::onTrip);
    }

    /**
     * Maps the configuration's action onto the ledger's. The two enums are kept apart on purpose: the
     * configuration model is the operator vocabulary, the ledger's is the substrate's, and this edge is
     * where one is read as the other.
     *
     * @param action the configured action
     * @return the ledger action of the same name
     */
    static SourceLedger.Action ledgerAction(EnumerationDetectionConfig.Action action) {
        return switch (action) {
            case BLOCK -> SourceLedger.Action.BLOCK;
            case DELAY -> SourceLedger.Action.DELAY;
            case OBSERVE -> SourceLedger.Action.OBSERVE;
        };
    }

    /**
     * @return the edge's recon ledger — the substrate's query API for this producer
     */
    public SourceLedger ledger() {
        return ledger;
    }

    /**
     * Resolves the request's source, stashes it under {@link #SOURCE_KEY} and answers the request at
     * once with the block-style {@code 429} when the source is blocked ({@code block}), or when it has
     * tripped under {@code delay} and its share of the held-answer sub-budget is used up — its
     * {@code Retry-After} is then the whole seconds of {@code delay_millis}, at least {@code 1}. Called
     * on the event loop before {@link EdgeAdmission#tryAdmit()}; when it answers, it takes no admission
     * permit, starts no virtual thread and arms no timer.
     *
     * @param ctx the incoming request
     * @return {@code true} when the request was answered {@code 429} and the caller must stop
     */
    public boolean answersBlocked(RoutingContext ctx) {
        SourceKey key = resolveSource(ctx.request());
        ctx.put(SOURCE_KEY, key);
        SourceLedger.Action action = ledger.settings().action();
        if (action == SourceLedger.Action.OBSERVE) {
            return false;
        }
        Optional<SourceState> state = ledger.state(key);
        if (state.isEmpty()) {
            return false;
        }
        if (action == SourceLedger.Action.BLOCK) {
            if (!state.get().isBlocked()) {
                return false;
            }
            answerBlocked(ctx, retryAfterSeconds(state.get().remainingBlockNanos(System.nanoTime())));
            return true;
        }
        if (!state.get().hasTripped() || !heldShareExhausted.test(key)) {
            return false;
        }
        // delay: the tripped source's share of the held-answer sub-budget is used up, so its further
        // request is refused here as a blocked one is, until one of its held answers is released.
        answerBlocked(ctx, retryAfterSeconds(delayNanos.orElse(0L)));
        return true;
    }

    /**
     * Writes the block-style answer: metered as {@link EventType#SOURCE_BLOCKED} and answered
     * {@code 429} with {@code Retry-After} and the {@link EventCategory#RATE_LIMITING} problem document,
     * through the one end rule.
     */
    private void answerBlocked(RoutingContext ctx, long retryAfterSeconds) {
        gatewayEventCounter.increment(EventType.SOURCE_BLOCKED);
        // Refused before route selection, so the route label is the shared no-route value.
        sheriffMetrics.recordError(SheriffMetrics.NO_ROUTE, EventType.SOURCE_BLOCKED);
        HttpServerResponse response = ctx.response();
        response.setStatusCode(EventType.SOURCE_BLOCKED.httpStatus());
        response.putHeader(ErrorResponseWriter.RETRY_AFTER_HEADER, Long.toString(retryAfterSeconds));
        response.putHeader(CONTENT_TYPE_HEADER, PROBLEM_JSON);
        ResponseEnds.endResponse(ctx, () -> response.end(blockedProblemBody));
    }

    /**
     * Admits one reported rejection to the recon ledger under the source {@link #answersBlocked}
     * stashed. A rejection {@link ReconEvents#weightOf} weighs at {@code 0} — not a recon event, past the
     * trust boundary, or a static resource — is not admitted and is never held, with one exception: an
     * {@link EventType#PRE_TRUST_INTERNAL_FAILURE} is not admitted either, but it is held under
     * {@code delay} exactly when a recon rejection of the same request would be, so that it stays
     * indistinguishable from the uniform {@code 404} it is answered as.
     *
     * @param ctx       the rejected request
     * @param request   the pipeline request, {@code null} for a rejection raised before one was built
     * @param eventType the TRUE event of the rejection
     * @return admission plus {@code delay_millis} when the action is {@code delay} and the source has
     *         tripped; empty otherwise
     */
    @Override
    public OptionalLong admit(RoutingContext ctx, @Nullable PipelineRequest request, EventType eventType) {
        SourceKey key = ctx.get(SOURCE_KEY);
        if (key == null) {
            return OptionalLong.empty();
        }
        if (eventType == EventType.PRE_TRUST_INTERNAL_FAILURE) {
            return holdUncounted(ctx, request, key);
        }
        int weight = ReconEvents.weightOf(request, eventType);
        if (weight == 0) {
            return OptionalLong.empty();
        }
        SourceState state = ledger.admit(key, weight, eventType);
        if (state.status() == SourceState.Status.UNTRACKED) {
            reportUntracked();
            return OptionalLong.empty();
        }
        if (delayNanos.isEmpty() || !state.hasTripped()) {
            return OptionalLong.empty();
        }
        return delayedHold(ctx, key);
    }

    /**
     * The {@code delay} hold of a tripped source's answer: admission plus {@code delay_millis}. The
     * source is stashed under {@link EdgeAdmission#HELD_SOURCE_KEY}, so the hold this answer enters
     * counts against the source's share of the held-answer sub-budget.
     */
    private OptionalLong delayedHold(RoutingContext ctx, SourceKey key) {
        ctx.put(EdgeAdmission.HELD_SOURCE_KEY, key);
        return OptionalLong.of(admittedAtNanos(ctx) + delayNanos.getAsLong());
    }

    /**
     * The hold of a pre-trust internal failure, which carries no detection weight and is never admitted:
     * under {@code delay} it is held until admission plus {@code delay_millis} when the source has
     * already tripped and the request is one a recon rejection would be counted for — not past the trust
     * boundary and not a static resource — and is answered without a hold otherwise. The source's state
     * is only read, so the failure neither adds weight nor starts tracking an untracked source.
     */
    private OptionalLong holdUncounted(RoutingContext ctx, @Nullable PipelineRequest request, SourceKey key) {
        if (delayNanos.isEmpty()) {
            return OptionalLong.empty();
        }
        boolean reconShaped = request == null
                || !(request.trustBoundaryPassed() || ReconEvents.isStaticResource(request));
        if (!reconShaped || !ledger.state(key).map(SourceState::hasTripped).orElse(false)) {
            return OptionalLong.empty();
        }
        return delayedHold(ctx, key);
    }

    /**
     * The key a request is tracked under: the forwarded client address for a trusted peer, otherwise
     * the TCP peer, with the authority host folded to the declared set.
     */
    private SourceKey resolveSource(HttpServerRequest raw) {
        SocketAddress remote = raw.remoteAddress();
        String peerAddress = remote != null ? remote.hostAddress() : null;
        String forwardedClient = peerGate.isTrustedPeer(peerAddress)
                ? forwardedResolver.resolve(name -> raw.headers().getAll(name)).clientIp().orElse(null)
                : null;
        return keyResolver.resolve(peerAddress, forwardedClient, authorityHostOf(raw));
    }

    /**
     * The authority host, or {@code null} when the request declares none or declares one that cannot
     * be parsed. A malformed {@code Host} makes the parse throw; on the event loop that must not end the
     * handler, so it is read as no host — the source then folds onto the undeclared-host key — and the
     * pipeline still answers the request as it would have without this gate.
     */
    private static @Nullable String authorityHostOf(HttpServerRequest raw) {
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            return EdgeRequests.authorityHost(raw);
        } catch (RuntimeException malformedAuthority) {
            LOGGER.debug(malformedAuthority, "Authority host unreadable for source resolution: %s",
                    malformedAuthority.getMessage());
            return null;
        }
    }

    /**
     * The trip listener: counts the trip as an event and on the trip meter, and writes the trip WARN.
     */
    private void onTrip(SourceTrip trip) {
        gatewayEventCounter.increment(EventType.SOURCE_TRIPPED);
        sheriffMetrics.recordSourceTrip(trip.action());
        LOGGER.warn(ApiSheriffLogMessages.WARN.SOURCE_TRIPPED, trip.key().source(), trip.key().host(),
                trip.threshold(), trip.window().toSeconds(), trip.action().name().toLowerCase(Locale.ROOT));
    }

    /**
     * Counts one untracked recon event and logs the first overflow of the process once; every later
     * one is a {@code DEBUG} diagnostic.
     */
    private void reportUntracked() {
        sheriffMetrics.recordSourceUntracked();
        if (overflowReported.compareAndSet(false, true)) {
            LOGGER.warn(ApiSheriffLogMessages.WARN.SOURCE_UNTRACKED, SourceLedger.MAX_TRACKED_SOURCES);
            return;
        }
        LOGGER.debug("Enumeration detection left a further source untracked at its bound of %s sources",
                SourceLedger.MAX_TRACKED_SOURCES);
    }

    /**
     * The whole seconds left of a block, rounded up and at least {@code 1}, so a client honouring
     * {@code Retry-After} never returns while the block still holds.
     */
    private static long retryAfterSeconds(long remainingNanos) {
        return Math.max(1L, Math.ceilDiv(remainingNanos, NANOS_PER_SECOND));
    }

    /**
     * The admission reading the edge root stashed ({@link ResponseDelay#ADMITTED_AT_KEY}); a request
     * that carries none is measured from now.
     */
    private static long admittedAtNanos(RoutingContext ctx) {
        Long admittedAt = ctx.get(ResponseDelay.ADMITTED_AT_KEY);
        return admittedAt != null ? admittedAt : System.nanoTime();
    }
}
