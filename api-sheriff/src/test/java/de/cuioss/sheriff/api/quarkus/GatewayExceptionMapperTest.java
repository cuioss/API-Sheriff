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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


import de.cuioss.sheriff.api.events.EventCategory;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.validation.exception.TokenValidationException;

import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("GatewayExceptionMapper — RFC 9457 problem+json rendering")
class GatewayExceptionMapperTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "SECURITY_FILTER_VIOLATION, 400",
            "PATH_NOT_ALLOWED,          400",
            "PARAMETER_LIMIT_EXCEEDED,  400",
            "NO_ROUTE_MATCHED,          404",
            "METHOD_NOT_ALLOWED,        405",
            "TOKEN_MISSING,             401",
            "TOKEN_INVALID,             401",
            "SCOPE_MISSING,             403",
            "CSRF_REJECTED,             403",
            "UPSTREAM_ERROR,            502",
            "UPSTREAM_CIRCUIT_OPEN,     503",
            "UPSTREAM_TIMEOUT,          504"
    })
    @DisplayName("Should render every error-contract row as its status + problem+json shape")
    void shouldRenderEveryErrorContractRow(EventType eventType, int expectedStatus) {
        var response = GatewayExceptionMapper.render(eventType);

        String body = (String) response.getEntity();
        EventCategory category = eventType.category();
        assertAll("problem+json for " + eventType,
                () -> assertEquals(expectedStatus, response.getStatus(), "Status must match the contract"),
                () -> assertEquals(PROBLEM_JSON, response.getMediaType().toString(),
                        "Media type must be application/problem+json"),
                () -> assertTrue(body.contains("\"type\":\"" + category.problemType() + "\""),
                        "Body must name the RFC 9457 problem type: " + body),
                () -> assertTrue(body.contains("\"title\":\"" + category.title() + "\""),
                        "Body must carry the category title: " + body),
                () -> assertTrue(body.contains("\"status\":" + expectedStatus),
                        "Body must carry the numeric status: " + body));
    }

    @Test
    @DisplayName("Should route a GatewayException through render via toResponse")
    void shouldRouteGatewayExceptionThroughToResponse() {
        var mapper = new GatewayExceptionMapper();

        Response response = mapper.toResponse(new GatewayException(EventType.SCOPE_MISSING));

        assertAll("mapped GatewayException",
                () -> assertEquals(403, response.getStatus(), "SCOPE_MISSING renders 403"),
                () -> assertEquals(PROBLEM_JSON, response.getMediaType().toString(), "Media type stays problem+json"));
    }

    @Test
    @DisplayName("Should not leak internal detail in the problem body")
    void shouldNotLeakInternalDetail() {
        var response = GatewayExceptionMapper.render(EventType.UPSTREAM_ERROR);

        String body = (String) response.getEntity();

        assertTrue(body.startsWith("{\"type\":") && !body.contains("detail"),
                "The RFC 9457 body must not carry an internal detail member: " + body);
    }

    @ParameterizedTest(name = "token {0} -> {1}")
    @CsvSource({
            "TOKEN_EMPTY,                 TOKEN_MISSING",
            "SIGNATURE_VALIDATION_FAILED, TOKEN_INVALID",
            "TOKEN_EXPIRED,               TOKEN_INVALID",
            "ISSUER_MISMATCH,             TOKEN_INVALID"
    })
    @DisplayName("Should translate a TokenValidationException to the gateway auth event")
    void shouldTranslateTokenValidationException(String tokenEvent, EventType expected) {
        var tokenException = new TokenValidationException(
                SecurityEventCounter.EventType.valueOf(tokenEvent), "validation failed");

        GatewayException translated = GatewayExceptionMapper.translate(tokenException);

        assertAll("translated token failure",
                () -> assertEquals(expected, translated.getEventType(), "Mapped event must match"),
                () -> assertEquals(EventCategory.AUTHENTICATION, translated.getEventType().category(),
                        "Token failures are AUTHENTICATION failures"),
                () -> assertEquals(401, translated.getEventType().httpStatus(), "Token failures render 401"));
    }
}
