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

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import de.cuioss.sheriff.gateway.ApiSheriffLogMessages;
import de.cuioss.sheriff.gateway.detection.SourceLedger;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.quarkus.SheriffMetrics;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The ALERT tier of per-source threat classification: the trip listener and overflow reporter of the
 * threat ledger.
 * <p>
 * <strong>One ALERT per episode.</strong> {@link #onTrip} is the threat ledger's trip listener. The
 * ledger trips a source at most once per {@code CLEAR} period — it re-arms only after the source's
 * score has drained to zero — so each call is one episode's ALERT: it renders the ECS-shaped document
 * through {@link ThreatAlertDocument}, logs it once as {@link ApiSheriffLogMessages.WARN#THREAT_ALERT}
 * with the canonical source and the folded host, and counts
 * {@link SheriffMetrics#THREAT_ALERTS_TOTAL}. The recon ledger's own trip is never routed here: it is
 * an input to the threat score, so it raises no ALERT of its own.
 * <p>
 * <strong>A rendering failure is never silent.</strong> When the document cannot be rendered, the
 * ALERT is still logged at the same identifier, with the fixed marker {@value #DOCUMENT_UNAVAILABLE}
 * in place of the document and the failure attached, and it is still counted.
 * <p>
 * <strong>The overflow.</strong> A rejection the ledger could not score because it was at its bound on
 * tracked sources is reported through {@link #reportUntracked}: it is counted on
 * {@link SheriffMetrics#THREAT_UNTRACKED_TOTAL}, and the first overflow of the process is logged once
 * as {@link ApiSheriffLogMessages.WARN#THREAT_UNTRACKED}; every later one is a {@code DEBUG}
 * diagnostic.
 * <p>
 * Nothing request-derived reaches a record: the source and host are the canonical key texts, and the
 * document carries gateway-derived values only.
 * <p>
 * Usage:
 * <pre>{@code
 * ThreatAlerts alerts = new ThreatAlerts(new ThreatAlertDocument(gatewayJson), sheriffMetrics, Clock.systemUTC());
 * SourceLedger threatLedger = new SourceLedger(settings, System::nanoTime, alerts::onTrip);
 * }</pre>
 * <p>
 * <strong>Thread safety.</strong> Thread-safe and non-blocking: the overflow latch is atomic, the
 * metrics adapter and the renderer are thread-safe, and every other field is immutable.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ThreatAlerts {

    private static final CuiLogger LOGGER = new CuiLogger(ThreatAlerts.class);

    /** The text logged in place of the ECS document when the document could not be rendered. */
    public static final String DOCUMENT_UNAVAILABLE = "<document unavailable>";

    private final ThreatAlertDocument document;
    private final SheriffMetrics sheriffMetrics;
    private final Clock clock;
    private final AtomicBoolean overflowReported = new AtomicBoolean();

    /**
     * Creates the ALERT tier.
     *
     * @param document       the renderer of the ECS-shaped ALERT document
     * @param sheriffMetrics the Micrometer adapter the ALERTs and overflows are counted through
     * @param clock          the wall clock the document's {@code @timestamp} is read from
     */
    public ThreatAlerts(ThreatAlertDocument document, SheriffMetrics sheriffMetrics, Clock clock) {
        this.document = Objects.requireNonNull(document, "document");
        this.sheriffMetrics = Objects.requireNonNull(sheriffMetrics, "sheriffMetrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The threat ledger's trip listener: logs the episode's ALERT once with its ECS document and counts
     * it.
     *
     * @param trip the trip the threat ledger handed its listener
     */
    public void onTrip(SourceTrip trip) {
        Objects.requireNonNull(trip, "trip");
        sheriffMetrics.recordThreatAlert();
        String source = trip.key().source();
        String host = trip.key().host();
        String rendered;
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            rendered = document.render(trip, clock.instant());
        } catch (RuntimeException renderingFailure) {
            LOGGER.warn(renderingFailure, ApiSheriffLogMessages.WARN.THREAT_ALERT, source, host, DOCUMENT_UNAVAILABLE);
            return;
        }
        LOGGER.warn(ApiSheriffLogMessages.WARN.THREAT_ALERT, source, host, rendered);
    }

    /**
     * Counts one rejection the threat ledger left unscored and logs the first overflow of the process
     * once; every later one is a {@code DEBUG} diagnostic.
     */
    public void reportUntracked() {
        sheriffMetrics.recordThreatUntracked();
        if (overflowReported.compareAndSet(false, true)) {
            LOGGER.warn(ApiSheriffLogMessages.WARN.THREAT_UNTRACKED, SourceLedger.MAX_TRACKED_SOURCES);
            return;
        }
        LOGGER.debug("Threat classification left a further source untracked at its bound of %s sources",
                SourceLedger.MAX_TRACKED_SOURCES);
    }
}
