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
import java.util.function.Consumer;
import java.util.function.Predicate;


import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceState;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.edge.end.ResponseDelay;
import de.cuioss.sheriff.gateway.edge.end.ResponseEnds;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.edge.error.ReconEvents;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayEventCounter;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.tools.logging.CuiLogger;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * Wires the per-source detection substrate ({@code de.cuioss.sheriff.gateway.detection}) into the
 * edge as its recon consumer: it answers a blocked source before admission, feeds the recon ledger with
 * every pre-trust rejection, and reports every trip.
 * <p>
 * <strong>One recon ledger.</strong> The gate builds the edge's recon {@link SourceLedger} from the
 * resolved {@code enumeration_detection} block and registers itself as its trip listener. The
 * configuration action is mapped onto the ledger's own {@link SourceLedger.Action}: {@code block}
 * drives the ledger's {@code BLOCKED} state, {@code delay} and {@code observe} never do.
 * <p>
 * <strong>The source.</strong> The gate does not resolve the source itself: {@link SourceKeys} resolves
 * the request's {@link SourceKey} on the event loop before {@link #answersBlocked} runs and stashes it
 * on the context under {@link SourceKeys#SOURCE_KEY}, where both the gate and the rejection feed read
 * it — the feed after the virtual-thread hop. A request that carries no stashed source is neither
 * answered nor admitted.
 * <p>
 * <strong>The unknown source is never acted on.</strong> A request whose client did not resolve — a
 * trusted proxy's request that carries no client-address header — is keyed on the shared
 * {@link SourceKey#UNKNOWN_SOURCE}, which stands for every such client at once. It is admitted to the
 * ledger like any source, so it is counted, can trip, and its trip is logged and handed to the
 * recon-trip observer; but whatever the action, it is never answered {@code 429}, never refused for a
 * used-up held-answer share and never held, because acting on it would act on all of those clients for
 * the probing of one.
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
 * held-answer sub-budget is used up, so one delayed source cannot occupy the whole sub-budget, once
 * that has more than one permit. Below an {@code edge_hardening.admission_cap} of {@code 8} the
 * sub-budget has one permit, and one source's share is that whole permit. The share is judged and counted
 * under the key the ledger tracks the source under ({@link SourceState#key()}): an IPv6 {@code /64} the
 * ledger aggregated to its {@code /48} under pressure draws on the {@code /48}'s one share, so a tripped
 * site cannot gain a fresh share per {@code /64} it rotates through; a {@code /64} tracked under its own
 * key keeps its own share. Neither answer is ever given to the {@link SourceKey#UNKNOWN_SOURCE unknown}
 * source.
 * <p>
 * <strong>The full ledger fails closed.</strong> Under {@code block} and {@code delay} a source the
 * ledger does not track and has no room for ({@link SourceLedger#hasRoomFor}) is answered the same
 * block-style {@code 429}, with {@code Retry-After} the {@link SourceLedger#SWEEP_INTERVAL sweep interval}
 * in whole seconds, at least {@code 1}, and its overflow is reported like an untracked rejection's. A
 * source the ledger cannot count could never trip or be blocked, so letting it through would hand an
 * unbounded number of fresh sources free probing once the ledger is full. The consequence is accepted
 * on purpose: while a flood keeps the ledger full of live sources, every NEW source is refused, a
 * legitimate one included, until the flood drains and the sweep frees room; sources already tracked
 * are answered as before. Under {@code observe} nothing is refused, and the
 * {@link SourceKey#UNKNOWN_SOURCE unknown} source is never refused. The check claims no slot: room that
 * a concurrent admission takes after it falls back to the untracked count below.
 * <p>
 * <strong>The feed.</strong> As the {@link ErrorResponseWriter.ReconFeed}, the gate admits every
 * reported rejection to the ledger with the weight {@link ReconEvents#weightOf} gives its TRUE event —
 * a recon event before the trust boundary that is not a static resource. Under {@code delay}, a
 * rejection whose source has tripped is held until admission plus {@code delay_millis}, and its hold
 * counts against that source's share under its tracked key ({@link EdgeAdmission#HELD_SOURCE_KEY}). An unexpected
 * internal failure answered as the uniform {@code 404} ({@link EventType#PRE_TRUST_INTERNAL_FAILURE})
 * is never admitted, but under {@code delay} it is held exactly as a recon rejection of the same
 * request would be: otherwise, for a tripped source, a crafted input that made a stage fail on a routed
 * address would be answered at the floor while the same input on an absent address waited for the
 * delay, and the timing would tell the two apart. A source the
 * ledger could not track — the cardinality bound — is counted on
 * {@link SheriffMetrics#SOURCE_UNTRACKED_TOTAL}, whether the gate refused it or the feed found no room
 * for its rejection, and the first overflow of the process is logged once
 * ({@link ApiSheriffLogMessages.WARN#SOURCE_UNTRACKED}).
 * <p>
 * <strong>The trip.</strong> Every trip, whatever the action, is counted as
 * {@link EventType#SOURCE_TRIPPED} on the {@link GatewayEventCounter}, on
 * {@link SheriffMetrics#SOURCE_TRIPS_TOTAL} under its action, and logged as
 * {@link ApiSheriffLogMessages.WARN#SOURCE_TRIPPED} — the canonical source and the folded host, never
 * the raw {@code Host}, the path or any header; the logged source is the key the ledger tracks, the
 * {@code /48} when it aggregated the source. Under the default {@code observe} that is the whole
 * effect of a trip: no answer changes. The trip is then handed to the recon-trip observer — the threat
 * classifier's {@link ThreatClassifier#admitReconTrip}, which scores it as one more input, or a no-op
 * when threat classification is disabled. The gate admits each rejection under the source
 * {@link SourceKeys} stashed for the request, so the trip it hands on carries that request's own key
 * ({@link SourceTrip#requestKey()}) beside the tracked key, and the observer scores the trip on the
 * request's own source.
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

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final GatewayEventCounter gatewayEventCounter;
    private final SheriffMetrics sheriffMetrics;
    private final String blockedProblemBody;
    /** The answer hold under {@code delay}; empty under {@code block} and {@code observe}. */
    private final OptionalLong delayNanos;
    private final AtomicBoolean overflowReported = new AtomicBoolean();
    private final SourceLedger ledger;
    /** Whether a source's share of the held-answer sub-budget is used up ({@link EdgeAdmission#heldShareExhausted}). */
    private final Predicate<SourceKey> heldShareExhausted;
    /** Receives every recon trip after it was reported; the threat score's input, or a no-op. */
    private final Consumer<SourceTrip> reconTripObserver;

    /**
     * Builds the gate and the recon ledger it listens to.
     *
     * @param config              the resolved {@code enumeration_detection} block; the gate is built only
     *                            when it is enabled
     * @param gatewayEventCounter the shared in-process event counter
     * @param sheriffMetrics      the Micrometer adapter the block, trip and overflow are recorded through
     * @param gatewayJson         the serializer the {@code 429} problem document is rendered through
     * @param heldShareExhausted  whether a source's share of the held-answer sub-budget is used up — the
     *                            edge admission's {@link EdgeAdmission#heldShareExhausted}; read only
     *                            under {@code delay}
     * @param reconTripObserver   receives every recon trip once, after it was counted and logged — the
     *                            threat classifier's {@link ThreatClassifier#admitReconTrip}, or a no-op;
     *                            called on the admitting thread and must not block
     */
    public SourceGate(EnumerationDetectionConfig config, GatewayEventCounter gatewayEventCounter,
            SheriffMetrics sheriffMetrics, GatewayJson gatewayJson, Predicate<SourceKey> heldShareExhausted,
            Consumer<SourceTrip> reconTripObserver) {
        Objects.requireNonNull(config, "config");
        this.heldShareExhausted = Objects.requireNonNull(heldShareExhausted, "heldShareExhausted");
        this.reconTripObserver = Objects.requireNonNull(reconTripObserver, "reconTripObserver");
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
     * Answers the request at once with the block-style {@code 429} when the source {@link SourceKeys}
     * stashed under {@link SourceKeys#SOURCE_KEY} is blocked ({@code block}), or when it has tripped
     * under {@code delay} and its share of the held-answer sub-budget is used up — its
     * {@code Retry-After} is then the whole seconds of {@code delay_millis}, at least {@code 1} — or,
     * under either, when the ledger neither tracks the source nor has room for it, with
     * {@code Retry-After} the sweep interval. Called on the event loop before
     * {@link EdgeAdmission#tryAdmit()}, after the source was stashed; when it answers, it takes no
     * admission permit, starts no virtual thread and arms no timer. A request without a stashed source,
     * and a request keyed on {@link SourceKey#UNKNOWN_SOURCE}, is never answered here.
     *
     * @param ctx the incoming request
     * @return {@code true} when the request was answered {@code 429} and the caller must stop
     */
    public boolean answersBlocked(RoutingContext ctx) {
        SourceLedger.Action action = ledger.settings().action();
        if (action == SourceLedger.Action.OBSERVE) {
            return false;
        }
        SourceKey key = ctx.get(SourceKeys.SOURCE_KEY);
        if (key == null || isUnknownSource(key)) {
            return false;
        }
        Optional<SourceState> state = ledger.state(key);
        if (state.isEmpty()) {
            if (ledger.hasRoomFor(key)) {
                return false;
            }
            // Fail closed: a source the full ledger cannot count could never trip, so it is refused
            // rather than handed free probing; the check claims no slot, admit decides again.
            reportUntracked();
            answerBlocked(ctx, retryAfterSeconds(SourceLedger.SWEEP_INTERVAL.toNanos()));
            return true;
        }
        if (action == SourceLedger.Action.BLOCK) {
            if (!state.get().isBlocked()) {
                return false;
            }
            answerBlocked(ctx, retryAfterSeconds(state.get().remainingBlockNanos(System.nanoTime())));
            return true;
        }
        // The share is judged under the key the ledger tracks — a /64 aggregated to its /48 is judged under
        // the /48 its held answers were counted on, so rotating /64s draws on one share.
        if (!state.get().hasTripped() || !heldShareExhausted.test(state.get().key())) {
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
     * Admits one reported rejection to the recon ledger under the source {@link SourceKeys} stashed. A
     * rejection {@link ReconEvents#weightOf} weighs at {@code 0} — not a recon event, past the
     * trust boundary, or a static resource — is not admitted and is never held, with one exception: an
     * {@link EventType#PRE_TRUST_INTERNAL_FAILURE} is not admitted either, but it is held under
     * {@code delay} exactly when a recon rejection of the same request would be, so that it stays
     * indistinguishable from the uniform {@code 404} it is answered as.
     *
     * @param ctx       the rejected request
     * @param request   the pipeline request, {@code null} for a rejection raised before one was built
     * @param eventType the TRUE event of the rejection
     * @return admission plus {@code delay_millis} when the action is {@code delay} and the source has
     *         tripped and is not {@link SourceKey#UNKNOWN_SOURCE}; empty otherwise
     */
    @Override
    public OptionalLong admit(RoutingContext ctx, @Nullable PipelineRequest request, EventType eventType) {
        SourceKey key = ctx.get(SourceKeys.SOURCE_KEY);
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
        if (delayNanos.isEmpty() || !state.hasTripped() || isUnknownSource(key)) {
            return OptionalLong.empty();
        }
        return delayedHold(ctx, state.key());
    }

    /**
     * Whether {@code key} is the shared source of every request whose client did not resolve
     * ({@link SourceKey#UNKNOWN_SOURCE}) — counted, tripped, logged and observed like any source, but
     * never answered, refused or held: it stands for many clients at once, so acting on it would act on
     * all of them for the probing of one.
     */
    private static boolean isUnknownSource(SourceKey key) {
        return SourceKey.UNKNOWN_SOURCE.equals(key.source());
    }

    /**
     * The {@code delay} hold of a tripped source's answer: admission plus {@code delay_millis}. The
     * key the ledger tracks the source under — its {@code /48} aggregate when the ledger aggregated it —
     * is stashed under {@link EdgeAdmission#HELD_SOURCE_KEY}, so the hold this answer enters counts
     * against that tracked source's share of the held-answer sub-budget.
     */
    private OptionalLong delayedHold(RoutingContext ctx, SourceKey trackedKey) {
        ctx.put(EdgeAdmission.HELD_SOURCE_KEY, trackedKey);
        return OptionalLong.of(admittedAtNanos(ctx) + delayNanos.getAsLong());
    }

    /**
     * The hold of a pre-trust internal failure, which carries no detection weight and is never admitted:
     * under {@code delay} it is held until admission plus {@code delay_millis} when the source has
     * already tripped and the request is one a recon rejection would be counted for — not past the trust
     * boundary and not a static resource — and is answered without a hold otherwise, as it always is for
     * the {@link SourceKey#UNKNOWN_SOURCE unknown} source. The source's state
     * is only read, so the failure neither adds weight nor starts tracking an untracked source.
     */
    private OptionalLong holdUncounted(RoutingContext ctx, @Nullable PipelineRequest request, SourceKey key) {
        if (delayNanos.isEmpty() || isUnknownSource(key)) {
            return OptionalLong.empty();
        }
        boolean reconShaped = request == null
                || !(request.trustBoundaryPassed() || ReconEvents.isStaticResource(request));
        if (!reconShaped) {
            return OptionalLong.empty();
        }
        Optional<SourceState> state = ledger.state(key).filter(SourceState::hasTripped);
        if (state.isEmpty()) {
            return OptionalLong.empty();
        }
        return delayedHold(ctx, state.get().key());
    }

    /**
     * The trip listener: counts the trip as an event and on the trip meter, writes the trip WARN under the
     * tracked key, then hands the trip — carrying the tripping request's own source as
     * {@link SourceTrip#requestKey()} — to the recon-trip observer.
     */
    private void onTrip(SourceTrip trip) {
        gatewayEventCounter.increment(EventType.SOURCE_TRIPPED);
        sheriffMetrics.recordSourceTrip(trip.action());
        LOGGER.warn(ApiSheriffLogMessages.WARN.SOURCE_TRIPPED, trip.key().source(), trip.key().host(),
                trip.threshold(), trip.window().toSeconds(), trip.action().name().toLowerCase(Locale.ROOT));
        reconTripObserver.accept(trip);
    }

    /**
     * Counts one source the full ledger could not track — refused at the gate or left untracked by the
     * feed — and logs the first overflow of the process once; every later one is a {@code DEBUG}
     * diagnostic.
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
