/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.events;

import java.io.Serial;

import org.jspecify.annotations.Nullable;

/**
 * Typed gateway failure carrying the {@link EventType} that produced it. The HTTP edge
 * reads {@link #getEventType()} to render the correct status and RFC 9457 problem type
 * without leaking internal detail. The exception <em>message</em> is for logging only and
 * is never placed in the response body.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public class GatewayException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient EventType eventType;

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
    }

    /**
     * @param eventType the failure event
     * @param message   the internal log message (never rendered to the client)
     * @param cause     the underlying cause, if any
     */
    public GatewayException(EventType eventType, String message, @Nullable Throwable cause) {
        super(message, cause);
        this.eventType = eventType;
    }

    /**
     * @return the event type that produced this failure
     */
    public EventType getEventType() {
        return eventType;
    }
}
