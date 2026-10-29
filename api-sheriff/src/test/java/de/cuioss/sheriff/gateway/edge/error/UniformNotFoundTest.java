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
import java.util.stream.LongStream;
import java.util.stream.Stream;

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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The trust-boundary predicate: which rejections are answered to an untrusted caller as the unrouted
 * {@code 404}. Every {@link EventType} constant carries a recorded disposition — a member of the closed
 * set, or an exclusion with its reason — so a constant added without one turns this test red instead
 * of silently staying honest. The release-time step rule of {@link UniformNotFound#release} — the
 * release time and the step index an overrun is metered from — is pinned here as well.
 */
@EnableGeneratorController
@DisplayName("UniformNotFound — the trust-boundary predicate of the rejection renderer")
class UniformNotFoundTest {

    /** The nine members of the closed set, written out: the literal IS the contract. */
    private static final Set<EventType> EXPECTED_CLOSED_SET = EnumSet.of(
            EventType.METHOD_NOT_ALLOWED,
            EventType.PATH_NOT_ALLOWED,
            EventType.SECURITY_FILTER_VIOLATION,
            EventType.PARAMETER_LIMIT_EXCEEDED,
            EventType.CONTENT_TOO_LARGE,
            EventType.CSRF_REJECTED,
            EventType.TOKEN_MISSING,
            EventType.TOKEN_INVALID,
            EventType.PRE_TRUST_INTERNAL_FAILURE);

    /** Every constant outside the closed set, with the reason it keeps its honest answer. */
    private static final Map<EventType, String> EXCLUSIONS = exclusions();

    /** The fixed admission reading the release-step cases are measured from. */
    private static final long ADMITTED_AT_NANOS = 1_000_000_000L;

    /** The floor, which is also the width of one release step. */
    private static final long FLOOR_NANOS = UniformNotFound.RESPONSE_FLOOR.toNanos();

    /** The exclusive upper bound of the jitter on top of a step. */
    private static final long JITTER_NANOS = UniformNotFound.RESPONSE_JITTER.toNanos();

    /** Draws per case, enough to spread the jitter across its range. */
    private static final int DRAWS = 200;

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
        table.put(EventType.SOURCE_TRIPPED, informational);
        table.put(EventType.SOURCE_BLOCKED,
                "raised before route selection and admission, keyed by the source: identical for every address");
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
        @DisplayName("the closed set is exactly the nine documented members")
        void closedSetIsTheDocumentedNine() {
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
                "TOKEN_MISSING", "TOKEN_INVALID", "PRE_TRUST_INTERNAL_FAILURE"})
        @DisplayName("switch off: no member is answered as the unrouted 404")
        void switchOffKeepsEveryMemberHonest(EventType eventType) {
            UniformNotFound switchedOff = new UniformNotFound(false);

            assertFalse(switchedOff.applies(routedRequest(), eventType));
        }

        @Test
        @DisplayName("the switch alone flips both the rendering and the timing floor for the same request")
        void switchAloneFlipsRenderingAndTimingFloor() {
            PipelineRequest request = routedRequest();
            UniformNotFound switchedOn = new UniformNotFound(true);
            UniformNotFound switchedOff = new UniformNotFound(false);

            assertAll("the same routed, untrusted member request under each switch position",
                    () -> assertTrue(switchedOn.applies(request, EventType.METHOD_NOT_ALLOWED),
                            "switch on: the member is answered as the unrouted 404"),
                    () -> assertFalse(switchedOff.applies(request, EventType.METHOD_NOT_ALLOWED),
                            "switch off: the member keeps its honest answer"),
                    () -> assertTrue(switchedOn.delaysAnswer(EventType.NO_ROUTE_MATCHED),
                            "switch on: the unrouted 404 waits for the response-timing floor"),
                    () -> assertFalse(switchedOff.delaysAnswer(EventType.NO_ROUTE_MATCHED),
                            "switch off: no answer is delayed"));
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
                "TOKEN_MISSING", "TOKEN_INVALID", "PRE_TRUST_INTERNAL_FAILURE"})
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
                "SECURITY_FILTER_VIOLATION", "CONTENT_TOO_LARGE", "PRE_TRUST_INTERNAL_FAILURE"})
        @DisplayName("session branch x a non-authentication member: still answered as the unrouted 404")
        void sessionBranchNonAuthenticationMemberStaysUniform(EventType eventType) {
            PipelineRequest request = routedRequest();
            request.markSessionBranch();

            assertTrue(new UniformNotFound(true).applies(request, eventType),
                    "the session branch keeps only its authentication answers; its CSRF refusal hides the route");
        }
    }

    @Nested
    @DisplayName("release places the answer on the next floor step its processing has not passed")
    class ReleaseSteps {

        @ParameterizedTest(name = "{0}")
        @MethodSource("de.cuioss.sheriff.gateway.edge.error.UniformNotFoundTest#releaseStepCases")
        @DisplayName("the release offset is the step count times the floor, plus a jitter below its bound")
        void releaseLandsOnTheExpectedStep(String label, long elapsedNanos, long expectedSteps) {
            UniformNotFound uniformNotFound = new UniformNotFound(true);
            long nowNanos = ADMITTED_AT_NANOS + elapsedNanos;

            long[] offsets = releaseOffsets(uniformNotFound, ADMITTED_AT_NANOS, nowNanos);

            assertOffsetsOnStep(label, offsets, expectedSteps);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("de.cuioss.sheriff.gateway.edge.error.UniformNotFoundTest#releaseStepCases")
        @DisplayName("the exposed step index is the step the answer is released on, and only a step above 1 overran")
        void releaseExposesItsStepIndex(String label, long elapsedNanos, long expectedSteps) {
            UniformNotFound uniformNotFound = new UniformNotFound(true);

            UniformNotFound.Release release = uniformNotFound.release(ADMITTED_AT_NANOS,
                    ADMITTED_AT_NANOS + elapsedNanos);

            assertAll(label,
                    () -> assertEquals(expectedSteps, release.step(), "the step index"),
                    () -> assertEquals(expectedSteps > 1, release.overran(),
                            "an overrun is exactly a release on a step above the first"));
        }

        @Test
        @DisplayName("a now reading taken before admission counts as no elapsed time: the first step")
        void nowBeforeAdmissionCountsAsNoElapsedTime() {
            UniformNotFound uniformNotFound = new UniformNotFound(true);
            // More than one floor before admission: the signed difference counts as zero and lands on
            // the first step, where its magnitude of 3.5 floors would land on the fourth.
            long nowNanos = ADMITTED_AT_NANOS - FLOOR_NANOS * 7 / 2;

            long[] offsets = releaseOffsets(uniformNotFound, ADMITTED_AT_NANOS, nowNanos);

            assertOffsetsOnStep("now 3.5 floors before admission", offsets, 1);
        }

        @Test
        @DisplayName("the step is computed on the difference of the readings, so it holds across the nanoTime wrap")
        void stepHoldsAcrossTheNanoTimeWrap() {
            UniformNotFound uniformNotFound = new UniformNotFound(true);
            long admittedAtNanos = Long.MAX_VALUE - FLOOR_NANOS / 2;
            long nowNanos = admittedAtNanos + FLOOR_NANOS * 7 / 2;

            long[] offsets = releaseOffsets(uniformNotFound, admittedAtNanos, nowNanos);

            assertOffsetsOnStep("admission just below the wrap, 3.5 floors elapsed", offsets, 4);
        }

        @Test
        @DisplayName("the jitter is drawn afresh per answer and spreads across its range")
        void jitterSpreadsAcrossItsRange() {
            UniformNotFound uniformNotFound = new UniformNotFound(true);

            long[] offsets = releaseOffsets(uniformNotFound, ADMITTED_AT_NANOS, ADMITTED_AT_NANOS);

            long halfJitter = JITTER_NANOS / 2;
            assertAll("jitter of " + DRAWS + " draws on the first step",
                    () -> assertTrue(LongStream.of(offsets).anyMatch(offset -> offset - FLOOR_NANOS < halfJitter),
                            "some draw lands in the lower half of the jitter range"),
                    () -> assertTrue(LongStream.of(offsets).anyMatch(offset -> offset - FLOOR_NANOS >= halfJitter),
                            "some draw lands in the upper half of the jitter range"));
        }

        private static long[] releaseOffsets(UniformNotFound uniformNotFound, long admittedAtNanos, long nowNanos) {
            long[] offsets = new long[DRAWS];
            for (int draw = 0; draw < DRAWS; draw++) {
                offsets[draw] = uniformNotFound.release(admittedAtNanos, nowNanos).atNanos() - admittedAtNanos;
            }
            return offsets;
        }

        private static void assertOffsetsOnStep(String label, long[] offsets, long expectedSteps) {
            long lowerBound = expectedSteps * FLOOR_NANOS;
            long upperBound = lowerBound + JITTER_NANOS;
            for (long offset : offsets) {
                assertTrue(offset >= lowerBound && offset < upperBound, label + ": offset " + offset
                        + " ns must lie in [" + lowerBound + ", " + upperBound + ") for step " + expectedSteps);
            }
        }
    }

    static Stream<Arguments> releaseStepCases() {
        return Stream.of(
                Arguments.of("elapsed 0: step 1", 0L, 1L),
                Arguments.of("elapsed just under the floor: step 1", FLOOR_NANOS - 1, 1L),
                Arguments.of("elapsed exactly one floor: step 1", FLOOR_NANOS, 1L),
                Arguments.of("elapsed one floor plus 1 ns: step 2", FLOOR_NANOS + 1, 2L),
                Arguments.of("elapsed 3.5 floors: step 4", FLOOR_NANOS * 7 / 2, 4L));
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
