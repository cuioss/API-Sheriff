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
/**
 * The gateway's one per-source detection substrate: a bounded set of leaky buckets keyed by the
 * source a request comes from, fed with weighted events by a caller and answering whether that source
 * has crossed its threshold.
 * <p>
 * <strong>Contents.</strong>
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.detection.SourceKey} — the key: the canonical source address
 *       and the host folded to the declared set;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.detection.SourceKeyResolver} — derives a key from the peer
 *       address, a trusted forwarded client address and the authority host;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.detection.SourceLedger} — the substrate itself: weighted-event
 *       admission, linear drain, the {@code CLEAR -> TRIPPED -> BLOCKED} state machine, the query API
 *       and the cardinality bound;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.detection.SourceState} — the snapshot the query API
 *       answers;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.detection.SourceTrip} — the raw record a trip emits to its
 *       listener.</li>
 * </ul>
 * <p>
 * <strong>Framework-free.</strong> No type of this package depends on Vert.x, CDI, Micrometer or any
 * other framework type: the clock is an injected {@link java.util.function.LongSupplier} and a trip is
 * handed to a plain {@link java.util.function.Consumer}. The edge wires the substrate to the wire, the
 * event counter, the metrics and the log; a further consumer builds its own instance with its own
 * weights, window and threshold.
 * <p>
 * <strong>The written contract</strong> — keying, the cardinality bound, bucket semantics, the state
 * machine, the emit shape, threading and what the substrate deliberately does not serve — is
 * {@code doc/development/source-detection-substrate.adoc}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.detection;

import org.jspecify.annotations.NullMarked;
