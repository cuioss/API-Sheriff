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
 * Transport and admission bounds of the edge: the hardening options the edge's HTTP server and
 * request handling are bounded by, the admission of requests into the edge, and the two per-source
 * consumers of the detection substrate — the recon gate in front of that admission and the threat
 * classification beside it.
 * <p>
 * <strong>Contents.</strong>
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions} and
 *       {@link de.cuioss.sheriff.gateway.edge.admission.EdgeAdmission}, which admits requests under those
 *       bounds and drains them on shutdown;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.admission.SourceKeys}, which resolves and stashes the
 *       source a request is tracked under, once, for both consumers;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.admission.SourceGate}, which wires the recon ledger into
 *       the edge and answers a blocked source before admission;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.admission.ThreatClassifier}, which keeps the weighted
 *       threat score on a ledger of its own, with
 *       {@link de.cuioss.sheriff.gateway.edge.admission.ThreatWeights} giving each rejection its weight;</li>
 *   <li>{@link de.cuioss.sheriff.gateway.edge.admission.ThreatAlerts}, which raises one ALERT per threat
 *       episode, rendered by {@link de.cuioss.sheriff.gateway.edge.admission.ThreatAlertDocument} as an
 *       ECS-shaped document.</li>
 * </ul>
 * <p>
 * <strong>Dependencies.</strong> This package may depend on the edge packages {@code error},
 * {@code end} and {@code request}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.edge.admission;

import org.jspecify.annotations.NullMarked;
