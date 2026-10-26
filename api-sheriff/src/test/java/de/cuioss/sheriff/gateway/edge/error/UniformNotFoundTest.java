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
package de.cuioss.sheriff.gateway.edge.error;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The trust-boundary predicate: which rejections are answered to an untrusted caller as the unrouted
 * {@code 404}. Every {@link EventType} constant carries a recorded disposition — a member of the closed
 * set, or an exclusion with its reason — so a constant added without one turns this test red instead
 * of silently staying honest.
 */
@EnableGeneratorController
@DisplayName("UniformNotFound — the trust-boundary predicate of the rejection renderer")
class UniformNotFoundTest {

    /** The eight members of the closed set, written out: the literal IS the contract. */
    private static final Set<EventType> EXPECTED_CLOSED_SET = EnumSet.of(
            EventType.METHOD_NOT_ALLOWED,
            EventType.PATH_NOT_ALLOWED,
            EventType.SECURITY_FILTER_VIOLATION,
            EventType.PARAMETER_LIMIT_EXCEEDED,
            EventType.CONTENT_TOO_LARGE,
            EventType.CSRF_REJECTED,
            EventType.TOKEN_MISSING,
            EventType.TOKEN_INVALID);

    /** Every constant outside the closed set, with the reason it keeps its honest answer. */
    private static final Map<EventType, String> EXCLUSIONS = exclusions();

    private static Map<EventType, String> exclusions() {
        Map<EventType, String> table = new EnumMap<>(EventType.class);
        String informational = "informational: no HTTP mapping";
        String boot = "boot-only: aborts startup, never an HTTP answer";
        String postTrust = "raised after stage 4 completed: the caller is trusted, or the anonymous route's success already discloses it";
        String reserved = "reserved paths are answered before route selection and are a fixed, public set";
        table.put(EventType.REQUEST_FORWARDED, informational);
        table.put(EventType.TOKEN_REFRESHED, informational);
        table.put(EventType.CONFIG_LOADED, informational);
        table.put(EventType.CONFIG_INVALID, boot);
        table.put(EventType.AUTH_WEAKENED, boot);
        table.put(EventType.NO_ROUTE_MATCHED, "it IS the unrouted answer, raised before route selection");
        table.put(EventType.PASSTHROUGH_HOST_SMUGGLED, "raised before route selection, identical for every address");
        table.put(EventType.RESERVED_BODY_TOO_LARGE, reserved);
        table.put(EventType.INBOUND_BODY_ABORTED, postTrust);
        table.put(EventType.REQUEST_BODY_TIMEOUT, postTrust);
        table.put(EventType.SCOPE_MISSING, "the credential validated, so the caller is trusted");
        table.put(EventType.UPSTREAM_ERROR, postTrust);
        table.put(EventType.UPSTREAM_CIRCUIT_OPEN, postTrust);
        table.put(EventType.UPSTREAM_TIMEOUT, postTrust);
        table.put(EventType.SESSION_CREATED, informational);
        table.put(EventType.SESSION_DESTROYED, informational);
        table.put(EventType.SESSION_REFRESH_FAILED, informational);
        table.put(EventType.BACKCHANNEL_LOGOUT, informational);
        table.put(EventType.LOGOUT_TOKEN_INVALID, reserved);
        table.put(EventType.WEBSOCKET_ORIGIN_REJECTED, postTrust);
        table.put(EventType.WEBSOCKET_IDLE_TIMEOUT, "informational: after the 101 upgrade, no HTTP mapping");
        return table;
    }

    @Nested
    @DisplayName("every EventType constant has exactly one recorded disposition")
    class Dispositions {

        @ParameterizedTest(name = "{0}")
        @EnumSource(EventType.class)
        @DisplayName("a constant is a closed-set member or a recorded exclusion, never both and never neither")
        void everyConstantHasADisposition(EventType eventType) {
            boolean member = UniformNotFound.CLOSED_SET.contains(eventType);
            boolean excluded = EXCLUSIONS.containsKey(eventType);

            assertNotEquals(member, excluded, eventType
                    + " needs exactly one disposition: add it to UniformNotFound.CLOSED_SET with its raise site, "
                    + "or to the exclusion table here with its reason");
        }

        @Test
        @DisplayName("the closed set is exactly the eight documented members")
        void closedSetIsTheDocumentedEight() {
            assertEquals(EXPECTED_CLOSED_SET, UniformNotFound.CLOSED_SET);
        }

        @Test
        @DisplayName("the closed set cannot be widened at runtime")
        void closedSetIsUnmodifiable() {
            Set<EventType> closedSet = UniformNotFound.CLOSED_SET;

            assertThrows(UnsupportedOperationException.class, () -> closedSet.add(EventType.SCOPE_MISSING));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(EventType.class)
        @DisplayName("on a routed, untrusted, non-session request the predicate is exactly closed-set membership")
        void predicateFollowsTheClosedSet(EventType eventType) {
            PipelineRequest request = routedRequest();

            boolean applies = new UniformNotFound(true).applies(request, eventType);

            assertEquals(EXPECTED_CLOSED_SET.contains(eventType), applies, eventType.name());
        }
    }

    @Nested
    @DisplayName("each leg of the predicate refuses on its own")
    class PredicateLegs {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = EventType.class, names = {"METHOD_NOT_ALLOWED", "PATH_NOT_ALLOWED",
                "SECURITY_FILTER_VIOLATION", "PARAMETER_LIMIT_EXCEEDED", "CONTENT_TOO_LARGE", "CSRF_REJECTED",
                "TOKEN_MISSING", "TOKEN_INVALID"})
        @DisplayName("switch off: no member is answered as the unrouted 404")
        void switchOffKeepsEveryMemberHonest(EventType eventType) {
            UniformNotFound switchedOff = new UniformNotFound(false);

            assertAll(eventType.name(),
                    () -> assertFalse(switchedOff.enabled()),
                    () -> assertFalse(switchedOff.applies(routedRequest(), eventType)));
        }

        @Test
        @DisplayName("switch on: the accessor reports the resolved switch")
        void switchOnIsReported() {
            assertTrue(new UniformNotFound(true).enabled());
        }

        @Test
        @DisplayName("no selected route: a rejection raised before selection is never resolved")
        void noRouteIsNeverResolved() {
            PipelineRequest unrouted = PipelineRequest.builder().method(HttpMethod.GET)
                    .requestPath("/" + Generators.letterStrings(3, 10).next()).build();

            assertFalse(new UniformNotFound(true).applies(unrouted, EventType.METHOD_NOT_ALLOWED));
        }

        @Test
        @DisplayName("no request at all: a rejection raised before the request was built is never resolved")
        void nullRequestIsNeverResolved() {
            assertFalse(new UniformNotFound(true).applies(null, EventType.TOKEN_MISSING));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = EventType.class, names = {"METHOD_NOT_ALLOWED", "PATH_NOT_ALLOWED",
                "SECURITY_FILTER_VIOLATION", "PARAMETER_LIMIT_EXCEEDED", "CONTENT_TOO_LARGE", "CSRF_REJECTED",
                "TOKEN_MISSING", "TOKEN_INVALID"})
        @DisplayName("trust boundary passed: the caller keeps the honest answer for every member")
        void trustedCallerKeepsTheHonestAnswer(EventType eventType) {
            PipelineRequest request = routedRequest();
            request.markTrustBoundaryPassed();

            assertFalse(new UniformNotFound(true).applies(request, eventType));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = EventType.class, names = {"TOKEN_MISSING", "TOKEN_INVALID"})
        @DisplayName("session branch x authentication category: the 302 / 401 negotiation is kept")
        void sessionBranchKeepsItsAuthenticationNegotiation(EventType eventType) {
            PipelineRequest request = routedRequest();
            request.markSessionBranch();

            assertAll(eventType.name(),
                    () -> assertEquals(EventCategory.AUTHENTICATION, eventType.category(),
                            "precondition: the member is an authentication-category event"),
                    () -> assertFalse(new UniformNotFound(true).applies(request, eventType)));
        }

        @Test
        @DisplayName("control: the same authentication events off the session branch are answered as the 404")
        void authenticationOffTheSessionBranchIsUniform() {
            PipelineRequest request = routedRequest();
            UniformNotFound uniformNotFound = new UniformNotFound(true);

            assertAll("the bearer branch hides the route",
                    () -> assertTrue(uniformNotFound.applies(request, EventType.TOKEN_MISSING)),
                    () -> assertTrue(uniformNotFound.applies(request, EventType.TOKEN_INVALID)));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = EventType.class, names = {"CSRF_REJECTED", "METHOD_NOT_ALLOWED", "PATH_NOT_ALLOWED",
                "SECURITY_FILTER_VIOLATION", "CONTENT_TOO_LARGE"})
        @DisplayName("session branch x a non-authentication member: still answered as the unrouted 404")
        void sessionBranchNonAuthenticationMemberStaysUniform(EventType eventType) {
            PipelineRequest request = routedRequest();
            request.markSessionBranch();

            assertTrue(new UniformNotFound(true).applies(request, eventType),
                    "the session branch keeps only its authentication answers; its CSRF refusal hides the route");
        }
    }

    private static PipelineRequest routedRequest() {
        String path = "/" + Generators.letterStrings(3, 10).next();
        PipelineRequest request = PipelineRequest.builder()
                .method(HttpMethod.GET)
                .requestPath(path)
                .queryParameters(List.of())
                .headers(Map.of())
                .build();
        request.canonicalPath(path);
        request.selectedRoute(Route.builder()
                .id(Generators.letterStrings(3, 10).next())
                .match(MatchConfig.builder().pathPrefix("/").build())
                .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build())
                .upstream(new ResolvedUpstream("https", "orders.example", 443, ""))
                .build());
        return request;
    }
}
