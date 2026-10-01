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
package de.cuioss.sheriff.gateway.events;

import java.io.Serial;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import org.jspecify.annotations.Nullable;

/**
 * Typed gateway failure carrying the {@link EventType} that produced it. The HTTP edge
 * reads {@link #getEventType()} to render the correct status and RFC 9457 problem type
 * without leaking internal detail. The exception <em>message</em> is for logging only and
 * is never placed in the response body.
 * <p>
 * <strong>Problem extension members.</strong> A failure may additionally carry RFC 9457 extension
 * members ({@link #getProblemExtensions()}), which the edge writes into the
 * {@code application/problem+json} body after the standard members, in insertion order. Unlike the
 * message they <em>are</em> disclosed to the client, so a member must never carry token material, a
 * session identifier, or free text taken from the request: only values the gateway itself controls —
 * a name from its boot configuration, a URL it built from its own configured path — belong here.
 * A failure without extension members renders exactly the standard body.
 * <p>
 * <strong>Thread safety.</strong> Immutable once constructed; the extension members are held in an
 * unmodifiable map.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public class GatewayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The RFC 9457 standard member names an extension member must not redefine. */
    private static final Set<String> STANDARD_MEMBERS = Set.of("type", "title", "status", "detail", "instance");

    private final EventType eventType;

    /**
     * The problem extension members. Not part of the serialized form — the values are arbitrary JSON
     * shapes — so a deserialized instance carries none (see {@link #getProblemExtensions()}).
     */
    private final transient Map<String, Object> problemExtensions;

    /**
     * @param eventType the failure event; its {@code name()} becomes the log message
     */
    public GatewayException(EventType eventType) {
        this(eventType, eventType.name());
    }

    /**
     * @param eventType the failure event
     * @param message   the internal log message (never rendered to the client)
     */
    public GatewayException(EventType eventType, String message) {
        super(message);
        this.eventType = eventType;
        this.problemExtensions = Map.of();
    }

    /**
     * @param eventType the failure event
     * @param message   the internal log message (never rendered to the client)
     * @param cause     the underlying cause, if any
     */
    public GatewayException(EventType eventType, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.eventType = eventType;
        this.problemExtensions = Map.of();
    }

    /**
     * Creates a failure carrying RFC 9457 problem extension members.
     *
     * @param eventType         the failure event
     * @param message           the internal log message (never rendered to the client)
     * @param problemExtensions the extension members rendered into the problem body after the standard
     *                          members, in iteration order; each value is a JSON-shaped value — a
     *                          {@link String}, {@link Number}, {@link Boolean}, {@link Collection} or
     *                          {@link Map}. Must never carry token material or free text from the
     *                          request (see the class documentation)
     * @throws NullPointerException     when the map, a member name or a member value is {@code null}
     * @throws IllegalArgumentException when a member redefines a standard problem member
     *                                  ({@code type}, {@code title}, {@code status}, {@code detail},
     *                                  {@code instance})
     */
    public GatewayException(EventType eventType, String message, Map<String, Object> problemExtensions) {
        super(message);
        this.eventType = eventType;
        this.problemExtensions = immutableCopy(Objects.requireNonNull(problemExtensions, "problemExtensions"));
    }

    /**
     * @return the event type that produced this failure
     */
    public EventType getEventType() {
        return eventType;
    }

    /**
     * @return the RFC 9457 problem extension members in rendering order, unmodifiable; empty when the
     *         failure carries none, which is also what an instance restored from its serialized form
     *         reports
     */
    public Map<String, Object> getProblemExtensions() {
        // The field is transient, so it is null on a deserialized instance and nowhere else.
        return problemExtensions != null ? problemExtensions : Map.of();
    }

    /**
     * Copies the members into an unmodifiable, insertion-ordered map. A collection value is copied
     * too, so a caller that keeps mutating the list it passed cannot change the rendered body.
     */
    private static Map<String, Object> immutableCopy(Map<String, Object> members) {
        Map<String, Object> copy = new LinkedHashMap<>();
        members.forEach((name, value) -> {
            Objects.requireNonNull(name, "problem extension member name");
            Objects.requireNonNull(value, "problem extension member value");
            if (STANDARD_MEMBERS.contains(name)) {
                throw new IllegalArgumentException(
                        "problem extension member '" + name + "' redefines a standard problem member");
            }
            copy.put(name, value instanceof Collection<?> collection ? List.copyOf(collection) : value);
        });
        return Collections.unmodifiableMap(copy);
    }
}
