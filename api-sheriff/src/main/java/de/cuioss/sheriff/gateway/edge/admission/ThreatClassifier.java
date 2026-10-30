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
import java.util.Objects;
import java.util.function.LongSupplier;


import de.cuioss.sheriff.gateway.config.model.ThreatClassificationConfig;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceState;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.edge.error.ErrorResponseWriter;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import io.vertx.ext.web.RoutingContext;
import org.jspecify.annotations.Nullable;

/**
 * The score tier of per-source threat classification: the second consumer of the per-source detection
 * substrate, keeping a weighted anomaly score per source on a {@link SourceLedger} of its own.
 * <p>
 * <strong>Its own ledger.</strong> The classifier builds a ledger independent of the recon ledger, tuned
 * by the resolved {@code threat_classification} block: its threshold and window, and the action
 * {@link SourceLedger.Action#OBSERVE}, fixed — the threat score never changes an answer. The block
 * duration the substrate requires is set to the window and is never used under {@code OBSERVE}. The
 * ledger's trip listener is the {@link ThreatAlerts} ALERT tier, so the first threshold crossing of a
 * source's episode raises exactly one ALERT: the substrate trips a source at most once per
 * {@code CLEAR} period and re-arms only after its score drained to zero.
 * <p>
 * <strong>The feed.</strong> As the {@link ErrorResponseWriter.ThreatFeed}, the classifier admits the
 * rejections {@link ErrorResponseWriter#handleGatewayRejection} hands it that carry a weight, under
 * their TRUE event as the tag and with the weight {@link ThreatWeights} gives them, for the source
 * {@link SourceKeys} stashed on the context. A rejection weighed at {@code 0} is not admitted, and a
 * request that carries no stashed source admits nothing; {@link ThreatWeights} lists the zero-weight
 * rejections and the ones answered without reaching the feed.
 * <p>
 * <strong>The recon trip.</strong> {@link #admitReconTrip} admits a trip of the recon ledger as one more
 * input, with the weight {@code weights.recon_trip} under the tag {@link EventType#SOURCE_TRIPPED}, for
 * the tripped source. A recon trip is never an ALERT of its own: it raises the score, and only the
 * threat ledger's own trip alerts.
 * <p>
 * <strong>The overflow.</strong> A rejection the ledger could not score — the source was new and the
 * ledger was at its bound on tracked sources — is handed to {@link ThreatAlerts#reportUntracked()}.
 * <p>
 * The classifier never holds, delays or changes an answer.
 * <p>
 * Usage:
 * <pre>{@code
 * ThreatClassifier classifier = new ThreatClassifier(threatClassificationConfig,
 *         new ThreatAlerts(new ThreatAlertDocument(gatewayJson), sheriffMetrics, Clock.systemUTC()),
 *         System::nanoTime);
 * }</pre>
 * <p>
 * Thread-safe: the ledger is thread-safe and every other field is immutable or thread-safe.
 * {@link #admit} runs on the thread that renders the rejection, {@link #admitReconTrip} on the thread
 * the recon ledger tripped on, and either may call the ALERT tier there.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ThreatClassifier implements ErrorResponseWriter.ThreatFeed {

    private final ThreatWeights weights;
    private final ThreatAlerts alerts;
    private final SourceLedger ledger;

    /**
     * Builds the classifier and the threat ledger it feeds.
     *
     * @param config    the resolved {@code threat_classification} block; the classifier is built only
     *                  when it is enabled
     * @param alerts    the ALERT tier — the ledger's trip listener and the overflow reporter
     * @param nanoClock a monotonic nanosecond clock, {@code System::nanoTime} in production
     */
    public ThreatClassifier(ThreatClassificationConfig config, ThreatAlerts alerts, LongSupplier nanoClock) {
        Objects.requireNonNull(config, "config");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.weights = new ThreatWeights(config.effectiveWeights());
        Duration window = Duration.ofSeconds(config.effectiveWindowSeconds());
        SourceLedger.Settings settings = new SourceLedger.Settings(config.effectiveThreshold(), window,
                SourceLedger.Action.OBSERVE, window);
        this.ledger = new SourceLedger(settings, Objects.requireNonNull(nanoClock, "nanoClock"), alerts::onTrip);
    }

    /**
     * @return the threat ledger — the substrate's query API for this consumer
     */
    public SourceLedger ledger() {
        return ledger;
    }

    /**
     * Admits one reported rejection to the threat ledger under the source {@link SourceKeys} stashed,
     * with the weight {@link ThreatWeights} gives its TRUE event. A rejection weighed at {@code 0}, and
     * a request without a stashed source, admit nothing.
     *
     * @param ctx       the rejected request
     * @param request   the pipeline request, {@code null} for a rejection raised before one was built
     * @param eventType the TRUE event of the rejection
     */
    @Override
    public void admit(RoutingContext ctx, @Nullable PipelineRequest request, EventType eventType) {
        SourceKey key = ctx.get(SourceKeys.SOURCE_KEY);
        if (key == null) {
            return;
        }
        score(key, weights.weightOf(request, eventType), eventType);
    }

    /**
     * Admits a trip of the recon ledger to the threat ledger: the weight {@code weights.recon_trip} under
     * the tag {@link EventType#SOURCE_TRIPPED}, for the tripped source. The recon trip is an input to the
     * score, never an ALERT of its own.
     *
     * @param reconTrip the trip the recon ledger handed its listener
     */
    public void admitReconTrip(SourceTrip reconTrip) {
        Objects.requireNonNull(reconTrip, "reconTrip");
        score(reconTrip.key(), weights.reconTrip(), EventType.SOURCE_TRIPPED);
    }

    /**
     * Admits a weight above {@code 0} to the threat ledger and reports a source the ledger could not
     * track.
     */
    private void score(SourceKey key, int weight, EventType tag) {
        if (weight <= 0) {
            return;
        }
        SourceState state = ledger.admit(key, weight, tag);
        if (state.status() == SourceState.Status.UNTRACKED) {
            alerts.reportUntracked();
        }
    }
}
