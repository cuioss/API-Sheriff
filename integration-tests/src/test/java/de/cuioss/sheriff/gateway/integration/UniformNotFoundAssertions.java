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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;

/**
 * The shared assertions for a rejection the shipped default answers to an untrusted caller as the
 * unrouted {@code 404} (ADR-0073, {@code security_defaults.uniform_not_found: true}).
 * <p>
 * A rejection raised after route selection and before the trust boundary — a missing or invalid
 * bearer, the verb gate, the route-scoped filter and body cap, the CSRF and cookie same-origin checks
 * — no longer names its cause in the answer. Two signals keep it a test of the cause rather than of
 * routing:
 * <ul>
 *   <li>{@link #assertUnroutedProblem} — the answer is exactly the unrouted one: status {@code 404},
 *       the routing problem document, no {@code WWW-Authenticate}, no {@code Allow}, and no echo from
 *       the go-httpbin upstream, so the request was refused at the gateway;</li>
 *   <li>{@link #assertTrueEventMetered} — the TRUE event still moves {@code sheriff_errors_total} on
 *       the route the request selected, and {@code NO_ROUTE_MATCHED} does not: the rejection is
 *       rendered TO the unrouted shape, never reported AS it. Read on the primary instance, or — for a
 *       one-off gateway — through that gateway's own published management origin.</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class UniformNotFoundAssertions {

    /** An address no anchor prefix and no reserved path covers. */
    static final String UNROUTED_PATH = "/no-such-route/resource";

    /** The status of the unrouted answer, and so of every pre-trust refusal on the shipped default. */
    static final int UNROUTED_STATUS = 404;

    /** The RFC 9457 media type of a gateway rejection. */
    static final String PROBLEM_JSON = "application/problem+json";

    /** The problem type of the unrouted answer, the {@code ROUTING} category (ADR-0059). */
    static final String ROUTING_PROBLEM_TYPE = "urn:api-sheriff:problem:routing";

    /** The event the unrouted answer is reported as when it is genuine. */
    static final String NO_ROUTE_MATCHED = "NO_ROUTE_MATCHED";

    private UniformNotFoundAssertions() {
        // static helpers only
    }

    /**
     * Asserts the answer is the unrouted problem document and that nothing on it discloses the route
     * or the cause.
     *
     * @param response the answer under test
     * @param what     names the request in failure messages
     */
    static void assertUnroutedProblem(ExtractableResponse<Response> response, String what) {
        String body = response.asString();
        assertAll(what + " is answered as the unrouted 404",
                () -> assertEquals(UNROUTED_STATUS, response.statusCode(), () -> "body: " + body),
                () -> assertTrue(String.valueOf(response.contentType()).startsWith(PROBLEM_JSON),
                        () -> "the unrouted problem document; content type was " + response.contentType()),
                () -> assertEquals(ROUTING_PROBLEM_TYPE, response.path("type"), "the routing problem type"),
                () -> assertEquals("Routing", response.path("title")),
                () -> assertNull(response.header("WWW-Authenticate"), "no challenge discloses a bearer route"),
                () -> assertNull(response.header("Allow"), "no Allow discloses the route's verbs"),
                () -> assertNull(response.path("method"), "a refused request must not reach the go-httpbin upstream"));
    }

    /**
     * Runs {@code request} against the primary instance and asserts it moved the
     * {@code sheriff_errors_total} series of {@code trueEvent} on {@code routeId}, while the
     * {@code NO_ROUTE_MATCHED} series of that route did not move.
     *
     * @param routeId   the config-fixed id of the route the request selects
     * @param trueEvent the {@code EventType} name the rejection is reported under
     * @param request   sends the request and returns its answer
     * @return the answer {@code request} returned, for further assertions
     */
    static ExtractableResponse<Response> assertTrueEventMetered(String routeId, String trueEvent,
            Supplier<ExtractableResponse<Response>> request) {
        return assertTrueEventMetered(PrometheusScrape::scrape, routeId, trueEvent, request);
    }

    /**
     * {@link #assertTrueEventMetered(String, String, Supplier)} for a gateway other than the primary
     * instance: a one-off gateway meters into its own registry, so the counter is read through
     * {@code scrape} — typically {@code () -> PrometheusScrape.scrape(managementOrigin)}.
     *
     * @param scrape    returns the Prometheus exposition of the gateway {@code request} is sent to
     * @param routeId   the config-fixed id of the route the request selects
     * @param trueEvent the {@code EventType} name the rejection is reported under
     * @param request   sends the request and returns its answer
     * @return the answer {@code request} returned, for further assertions
     */
    static ExtractableResponse<Response> assertTrueEventMetered(Supplier<String> scrape, String routeId,
            String trueEvent, Supplier<ExtractableResponse<Response>> request) {
        String before = scrape.get();
        double trueBefore = PrometheusScrape.errorCount(before, routeId, trueEvent);
        double unroutedBefore = PrometheusScrape.errorCount(before, routeId, NO_ROUTE_MATCHED);

        ExtractableResponse<Response> response = request.get();

        String after = scrape.get();
        double trueAfter = PrometheusScrape.errorCount(after, routeId, trueEvent);
        double unroutedAfter = PrometheusScrape.errorCount(after, routeId, NO_ROUTE_MATCHED);
        assertAll("the true event of the rejection on route " + routeId,
                () -> assertTrue(trueAfter > trueBefore, () -> "sheriff_errors_total{route=\"" + routeId
                        + "\",event=\"" + trueEvent + "\"} must move: before=" + trueBefore + ", after=" + trueAfter),
                () -> assertEquals(unroutedBefore, unroutedAfter, () -> "the rejection is rendered as the unrouted"
                        + " 404, never reported as NO_ROUTE_MATCHED on route " + routeId));
        return response;
    }
}
