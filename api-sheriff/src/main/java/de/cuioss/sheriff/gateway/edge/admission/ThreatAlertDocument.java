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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;


import de.cuioss.sheriff.gateway.bff.runtime.GatewayJson;
import de.cuioss.sheriff.gateway.detection.SourceKey;
import de.cuioss.sheriff.gateway.detection.SourceTrip;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;

/**
 * Renders a threat-ledger {@link SourceTrip} as the structured ALERT document: one compact JSON object
 * whose field names are those of the Elastic Common Schema (ECS) {@value #ECS_VERSION}, serialized
 * through the gateway's own {@link GatewayJson} (ADR-0062), so no further JSON library is needed.
 * <p>
 * <strong>The field set.</strong> The document carries exactly these fields, each one an ECS field
 * of the pinned version except the {@code api_sheriff.threat} namespace, which is the gateway's own:
 * <ul>
 *   <li>{@code @timestamp}, {@code ecs.version}, {@code log.level} ({@value #LOG_LEVEL}) and a fixed
 *       {@code message};</li>
 *   <li>{@code event.kind} ({@value #EVENT_KIND}), {@code event.category} ({@link #EVENT_CATEGORIES}),
 *       {@code event.type} ({@link #EVENT_TYPES}), {@code event.action} ({@value #EVENT_ACTION}),
 *       {@code event.dataset} ({@value #EVENT_DATASET}), {@code event.module} ({@value #EVENT_MODULE}),
 *       {@code event.severity} ({@value #EVENT_SEVERITY}) and {@code event.risk_score} — the ledger
 *       level at the trip;</li>
 *   <li>{@code source.address}, always the canonical source text, and {@code source.ip} only when that
 *       text is a whole IPv4 address;</li>
 *   <li>{@code url.domain} only when the folded host is a declared host, never for
 *       {@link SourceKey#UNDECLARED_HOST};</li>
 *   <li>{@code threat.framework} ({@value #THREAT_FRAMEWORK}), {@code threat.tactic.id} /
 *       {@code threat.tactic.name} ({@value #TACTIC_ID} / {@value #TACTIC_NAME}) and
 *       {@code threat.technique.id} / {@code threat.technique.name} ({@value #TECHNIQUE_ID} /
 *       {@value #TECHNIQUE_NAME});</li>
 *   <li>{@code api_sheriff.threat.score}, {@code threshold}, {@code window_seconds}, {@code host},
 *       {@code contributions} — the weight per caller tag, keyed by the tag's name — and
 *       {@code categories} — the same weights folded by {@link EventCategory#slug()}, with
 *       {@value #RECON_TRIP_CATEGORY} for {@link EventType#SOURCE_TRIPPED}.</li>
 * </ul>
 * <p>
 * <strong>The source trap.</strong> ECS types {@code source.ip} as an IP address. An IPv6 source is
 * keyed on its {@code /64} prefix ({@code a:b:c:d::/64}) and an unresolvable one on
 * {@link SourceKey#UNKNOWN_SOURCE}; neither is a valid {@code ip} value, so both appear in
 * {@code source.address} only. The IPv4 check reads the text as a dotted-decimal literal and performs
 * no name lookup.
 * <p>
 * <strong>No request byte.</strong> Every value is gateway-derived: the canonical key texts the
 * {@link de.cuioss.sheriff.gateway.detection.SourceKeyResolver} produced, enum names, the ledger's
 * numbers and the constants of this class. No path, raw {@code Host} or header can reach the
 * document. Members are written in a fixed order; the two breakdown maps are ordered by key.
 * <p>
 * Usage:
 * <pre>{@code
 * String alert = new ThreatAlertDocument(gatewayJson).render(trip, Instant.now());
 * }</pre>
 * <p>
 * <strong>Thread safety.</strong> Stateless apart from the immutable serializer; safe for concurrent
 * use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ThreatAlertDocument {

    /** The ECS release the field names of the document are taken from. */
    public static final String ECS_VERSION = "8.17.0";

    /** The {@code log.level} of the ALERT record the document travels with. */
    public static final String LOG_LEVEL = "warn";

    /** The fixed {@code message}: a description of the event that carries no request-derived text. */
    public static final String MESSAGE = "A source reached the threat classification threshold";

    /** The {@code event.kind}: the document reports a detection, not a raw event. */
    public static final String EVENT_KIND = "alert";

    /**
     * The {@code event.category} values: an intrusion-detection finding about web traffic. Both ECS
     * categories allow the {@code event.type} {@code info}.
     */
    public static final List<String> EVENT_CATEGORIES = List.of("intrusion_detection", "web");

    /**
     * The {@code event.type} values. {@code info} rather than {@code denied}: the threat ledger only
     * observes, so the alert refuses nothing.
     */
    public static final List<String> EVENT_TYPES = List.of("info");

    /** The {@code event.action}: the threshold of the threat ledger was reached. */
    public static final String EVENT_ACTION = "threat-classification-threshold-reached";

    /** The {@code event.dataset} the alert is filed under. */
    public static final String EVENT_DATASET = "api_sheriff.threat";

    /** The {@code event.module} the alert is filed under. */
    public static final String EVENT_MODULE = "api_sheriff";

    /**
     * The fixed {@code event.severity}: medium on the 0 to 100 scale, which corresponds to OCSF
     * {@code severity_id} 3.
     */
    public static final int EVENT_SEVERITY = 47;

    /** The {@code threat.framework} the tactic and technique are drawn from. */
    public static final String THREAT_FRAMEWORK = "MITRE ATT&CK";

    /** The MITRE ATT&amp;CK tactic id: Reconnaissance. */
    public static final String TACTIC_ID = "TA0043";

    /** The MITRE ATT&amp;CK tactic name of {@link #TACTIC_ID}. */
    public static final String TACTIC_NAME = "Reconnaissance";

    /** The MITRE ATT&amp;CK technique id: Active Scanning. */
    public static final String TECHNIQUE_ID = "T1595";

    /** The MITRE ATT&amp;CK technique name of {@link #TECHNIQUE_ID}. */
    public static final String TECHNIQUE_NAME = "Active Scanning";

    /** The category key the recon ledger's trip ({@link EventType#SOURCE_TRIPPED}) is folded under. */
    public static final String RECON_TRIP_CATEGORY = "recon_trip";

    /**
     * The category key a contribution is folded under when its tag names no category: any tag other
     * than an {@link EventType} with a category or {@link EventType#SOURCE_TRIPPED}. The threat
     * classifier admits no such tag; the key keeps the fold total over the trip's tag type.
     */
    public static final String UNCATEGORIZED = "uncategorized";

    private static final int IPV4_OCTETS = 4;
    private static final int IPV4_MAX_OCTET_DIGITS = 3;
    private static final int IPV4_MAX_OCTET = 255;

    private final GatewayJson gatewayJson;

    /**
     * Creates the renderer.
     *
     * @param gatewayJson the serializer the document is rendered through
     */
    public ThreatAlertDocument(GatewayJson gatewayJson) {
        this.gatewayJson = Objects.requireNonNull(gatewayJson, "gatewayJson");
    }

    /**
     * Renders a threat-ledger trip as the compact JSON ALERT document.
     *
     * @param trip the trip the threat ledger handed its listener
     * @param now  the wall-clock instant written as {@code @timestamp}
     * @return the document as a compact JSON object
     * @throws java.io.UncheckedIOException when the serializer refuses the value graph
     */
    public String render(SourceTrip trip, Instant now) {
        return gatewayJson.toJson(document(trip, now));
    }

    /**
     * Builds the insertion-ordered value graph of the document.
     *
     * @param trip the trip the threat ledger handed its listener
     * @param now  the wall-clock instant written as {@code @timestamp}
     * @return the nested map of the document
     */
    static Map<String, Object> document(SourceTrip trip, Instant now) {
        Objects.requireNonNull(trip, "trip");
        Objects.requireNonNull(now, "now");
        SourceKey key = trip.key();

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("@timestamp", now.toString());
        document.put("ecs", Map.of("version", ECS_VERSION));
        document.put("log", Map.of("level", LOG_LEVEL));
        document.put("message", MESSAGE);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("kind", EVENT_KIND);
        event.put("category", EVENT_CATEGORIES);
        event.put("type", EVENT_TYPES);
        event.put("action", EVENT_ACTION);
        event.put("dataset", EVENT_DATASET);
        event.put("module", EVENT_MODULE);
        event.put("severity", EVENT_SEVERITY);
        event.put("risk_score", trip.levelAtTrip());
        document.put("event", event);

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("address", key.source());
        if (isIpv4Literal(key.source())) {
            source.put("ip", key.source());
        }
        document.put("source", source);

        if (!SourceKey.UNDECLARED_HOST.equals(key.host())) {
            document.put("url", Map.of("domain", key.host()));
        }

        Map<String, Object> threat = new LinkedHashMap<>();
        threat.put("framework", THREAT_FRAMEWORK);
        threat.put("tactic", orderedPair("id", List.of(TACTIC_ID), "name", List.of(TACTIC_NAME)));
        threat.put("technique", orderedPair("id", List.of(TECHNIQUE_ID), "name", List.of(TECHNIQUE_NAME)));
        document.put("threat", threat);

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("score", trip.levelAtTrip());
        breakdown.put("threshold", trip.threshold());
        breakdown.put("window_seconds", trip.window().toSeconds());
        breakdown.put("host", key.host());
        breakdown.put("contributions", contributionsByTag(trip.contributions()));
        breakdown.put("categories", contributionsByCategory(trip.contributions()));
        document.put("api_sheriff", Map.of("threat", breakdown));
        return document;
    }

    /**
     * The weight each tag contributed, keyed by the tag's enum name and ordered by it.
     */
    private static Map<String, Long> contributionsByTag(Map<Enum<?>, Long> contributions) {
        Map<String, Long> byTag = new TreeMap<>();
        contributions.forEach((tag, weight) -> byTag.merge(tag.name(), weight, Long::sum));
        return byTag;
    }

    /**
     * The contributed weights folded by category key and ordered by it.
     */
    private static Map<String, Long> contributionsByCategory(Map<Enum<?>, Long> contributions) {
        Map<String, Long> byCategory = new TreeMap<>();
        contributions.forEach((tag, weight) -> byCategory.merge(categoryOf(tag), weight, Long::sum));
        return byCategory;
    }

    /**
     * The category key of a contribution tag: {@value #RECON_TRIP_CATEGORY} for the recon trip, the
     * category slug for a categorized {@link EventType}, {@value #UNCATEGORIZED} for anything else.
     *
     * @param tag the contribution tag
     * @return the key the tag's weight is folded under
     */
    static String categoryOf(Enum<?> tag) {
        if (tag == EventType.SOURCE_TRIPPED) {
            return RECON_TRIP_CATEGORY;
        }
        EventCategory category = tag instanceof EventType eventType ? eventType.category() : null;
        return category != null ? category.slug() : UNCATEGORIZED;
    }

    /**
     * Whether the text is a whole IPv4 address in dotted-decimal form: four decimal octets of one to
     * three digits, each at most {@code 255}. Reads the text only; performs no lookup.
     *
     * @param text the canonical source text
     * @return {@code true} for an IPv4 literal
     */
    static boolean isIpv4Literal(String text) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != IPV4_OCTETS) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > IPV4_MAX_OCTET_DIGITS) {
                return false;
            }
            for (int index = 0; index < octet.length(); index++) {
                char digit = octet.charAt(index);
                if (digit < '0' || digit > '9') {
                    return false;
                }
            }
            if (Integer.parseInt(octet) > IPV4_MAX_OCTET) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Object> orderedPair(String firstKey, Object firstValue, String secondKey,
            Object secondValue) {
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put(firstKey, firstValue);
        pair.put(secondKey, secondValue);
        return pair;
    }
}
