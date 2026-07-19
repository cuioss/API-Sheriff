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
package de.cuioss.sheriff.api.quarkus;

import de.cuioss.sheriff.api.events.EventCategory;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Framework-edge handler that renders a {@link GatewayException} as an RFC 9457
 * {@code application/problem+json} response. The problem {@code type} is named by the
 * failing event's {@link EventCategory}; the {@code status} is the event's HTTP status;
 * no internal detail is leaked into the body.
 * <p>
 * {@link TokenValidationException} thrown by the token-sheriff validator is normalized to
 * the gateway's {@link EventType#TOKEN_MISSING} / {@link EventType#TOKEN_INVALID}
 * equivalents via {@link #translate(TokenValidationException)}, so bearer-token failures
 * render through the same contract.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@Provider
public class GatewayExceptionMapper implements ExceptionMapper<GatewayException> {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final int INTERNAL_ERROR = 500;

    @Override
    public Response toResponse(GatewayException exception) {
        return render(exception.getEventType());
    }

    /**
     * Renders the RFC 9457 problem+json response for the given event type.
     *
     * @param eventType the failure event
     * @return a problem+json {@link Response} carrying {@code type}, {@code title}, and {@code status}
     */
    static Response render(EventType eventType) {
        int status = eventType.hasHttpMapping() ? eventType.httpStatus() : INTERNAL_ERROR;
        EventCategory category = eventType.category();
        String type = category != null ? category.problemType() : "about:blank";
        String title = category != null ? category.title() : "Internal Server Error";
        String body = "{\"type\":\"" + type + "\",\"title\":\"" + title + "\",\"status\":" + status + "}";
        return Response.status(status)
                .type(PROBLEM_JSON)
                .entity(body)
                .build();
    }

    /**
     * Normalizes a token-validation failure into the gateway's authentication event. An
     * empty / missing token maps to {@link EventType#TOKEN_MISSING}; every other validation
     * failure maps to {@link EventType#TOKEN_INVALID}.
     *
     * @param exception the token-sheriff validation failure
     * @return an equivalent {@link GatewayException} carrying the mapped event type
     */
    static GatewayException translate(TokenValidationException exception) {
        EventType mapped = exception.getEventType() == SecurityEventCounter.EventType.TOKEN_EMPTY
                ? EventType.TOKEN_MISSING
                : EventType.TOKEN_INVALID;
        return new GatewayException(mapped, mapped.name(), exception);
    }
}
