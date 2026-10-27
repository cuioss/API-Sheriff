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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AssetConfig;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedAsset;
import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.Route;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The first producer's predicate of the detection substrate: which rejections count as
 * reconnaissance. Every {@link EventType} constant carries a recorded disposition — a member of the
 * recon set, or an exclusion with its reason — so a constant added without one turns this test red.
 */
@EnableGeneratorController
@DisplayName("ReconEvents — which rejections count as reconnaissance")
class ReconEventsTest {

    /** The eleven members of the recon set, written out: the literal IS the contract. */
    private static final Set<EventType> EXPECTED_RECON_SET = EnumSet.of(
            EventType.NO_ROUTE_MATCHED,
            EventType.PASSTHROUGH_HOST_SMUGGLED,
            EventType.METHOD_NOT_ALLOWED,
            EventType.SECURITY_FILTER_VIOLATION,
            EventType.PATH_NOT_ALLOWED,
            EventType.PARAMETER_LIMIT_EXCEEDED,
            EventType.CONTENT_TOO_LARGE,
            EventType.CSRF_REJECTED,
            EventType.TOKEN_MISSING,
            EventType.TOKEN_INVALID,
            EventType.PRE_TRUST_INTERNAL_FAILURE);

    /** Every constant outside the recon set, with the reason it is not counted. */
    private static final Map<EventType, String> EXCLUSIONS = exclusions();

    private static Map<EventType, String> exclusions() {
        Map<EventType, String> table = new EnumMap<>(EventType.class);
        String notARejection = "informational, boot or session lifecycle: not a rejection of a request";
        String postTrust = "raised after the trust boundary: the caller is trusted";
        String upstream = "describes the upstream, not the caller's probing";
        String bodyStopped = "a body that stopped arriving says nothing about which addresses exist";
        String reserved = "reserved paths are a fixed, public set";
        table.put(EventType.REQUEST_FORWARDED, notARejection);
        table.put(EventType.TOKEN_REFRESHED, notARejection);
        table.put(EventType.CONFIG_LOADED, notARejection);
        table.put(EventType.CONFIG_INVALID, notARejection);
        table.put(EventType.AUTH_WEAKENED, notARejection);
        table.put(EventType.RESERVED_BODY_TOO_LARGE, reserved);
        table.put(EventType.INBOUND_BODY_ABORTED, bodyStopped);
        table.put(EventType.REQUEST_BODY_TIMEOUT, bodyStopped);
        table.put(EventType.SCOPE_MISSING, postTrust);
        table.put(EventType.UPSTREAM_ERROR, upstream);
        table.put(EventType.UPSTREAM_CIRCUIT_OPEN, upstream);
        table.put(EventType.UPSTREAM_TIMEOUT, upstream);
        table.put(EventType.SESSION_CREATED, notARejection);
        table.put(EventType.SESSION_DESTROYED, notARejection);
        table.put(EventType.SESSION_REFRESH_FAILED, notARejection);
        table.put(EventType.BACKCHANNEL_LOGOUT, notARejection);
        table.put(EventType.LOGOUT_TOKEN_INVALID, reserved);
        table.put(EventType.WEBSOCKET_ORIGIN_REJECTED, postTrust);
        table.put(EventType.WEBSOCKET_IDLE_TIMEOUT, notARejection);
        table.put(EventType.SOURCE_TRIPPED, notARejection);
        table.put(EventType.SOURCE_BLOCKED,
                "the refusal of a source already blocked: counting it would extend the block by the requests it refuses");
        return table;
    }

    @Nested
    @DisplayName("every EventType constant has exactly one recorded disposition")
    class Dispositions {

        @ParameterizedTest(name = "{0}")
        @EnumSource(EventType.class)
        @DisplayName("a constant is a recon member or a recorded exclusion, never both and never neither")
        void everyConstantHasADisposition(EventType eventType) {
            boolean member = ReconEvents.RECON_SET.contains(eventType);
            boolean excluded = EXCLUSIONS.containsKey(eventType);

            assertNotEquals(member, excluded, eventType
                    + " needs exactly one disposition: add it to ReconEvents.RECON_SET, "
                    + "or to the exclusion table here with its reason");
        }

        @Test
        @DisplayName("the recon set is exactly the eleven documented members")
        void reconSetIsTheDocumentedEleven() {
            assertEquals(EXPECTED_RECON_SET, ReconEvents.RECON_SET);
        }

        @Test
        @DisplayName("a pre-trust internal failure counts with weight 1, and not once the trust boundary is passed")
        void preTrustInternalFailureIsAReconSignal() {
            PipelineRequest untrusted = routedRequest(dynamicPath(), null);
            PipelineRequest trusted = routedRequest(dynamicPath(), null);
            trusted.markTrustBoundaryPassed();

            assertAll("PRE_TRUST_INTERNAL_FAILURE",
                    () -> assertEquals(1, ReconEvents.weightOf(untrusted, EventType.PRE_TRUST_INTERNAL_FAILURE),
                            "answered as the uniform 404 to an untrusted caller, so one more probe"),
                    () -> assertEquals(0, ReconEvents.weightOf(trusted, EventType.PRE_TRUST_INTERNAL_FAILURE),
                            "a trusted caller is never counted"));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(EventType.class)
        @DisplayName("on an untrusted, non-static request the weight is exactly recon-set membership")
        void weightFollowsTheReconSet(EventType eventType) {
            int expected = EXPECTED_RECON_SET.contains(eventType) ? ReconEvents.RECON_WEIGHT : 0;

            assertEquals(expected, ReconEvents.weightOf(routedRequest(dynamicPath(), null), eventType),
                    eventType.name());
        }
    }

    @Nested
    @DisplayName("the trust boundary")
    class TrustBoundary {

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = EventType.class, names = {"METHOD_NOT_ALLOWED", "SECURITY_FILTER_VIOLATION",
                "CONTENT_TOO_LARGE", "TOKEN_MISSING", "TOKEN_INVALID", "CSRF_REJECTED"})
        @DisplayName("a member raised after the trust boundary is not counted")
        void postTrustIsNotCounted(EventType eventType) {
            PipelineRequest request = routedRequest(dynamicPath(), null);
            int before = ReconEvents.weightOf(request, eventType);
            request.markTrustBoundaryPassed();

            assertAll(eventType.name(),
                    () -> assertEquals(ReconEvents.RECON_WEIGHT, before, "control: counted before the boundary"),
                    () -> assertEquals(0, ReconEvents.weightOf(request, eventType)));
        }

        @Test
        @DisplayName("a rejection raised before any request was built is counted")
        void noRequestIsPreTrust() {
            assertEquals(ReconEvents.RECON_WEIGHT, ReconEvents.weightOf(null, EventType.SECURITY_FILTER_VIOLATION));
        }

        @Test
        @DisplayName("a 401 or 405 rendered as the uniform 404 is counted under its true event")
        void renderedAsUniformNotFoundIsCountedUnderTheTrueEvent() {
            PipelineRequest request = routedRequest(dynamicPath(), null);
            UniformNotFound uniformNotFound = new UniformNotFound(true);

            assertAll("the renderer hides the route; the counter keeps the true event",
                    () -> assertTrue(uniformNotFound.applies(request, EventType.TOKEN_MISSING)),
                    () -> assertTrue(uniformNotFound.applies(request, EventType.METHOD_NOT_ALLOWED)),
                    () -> assertEquals(ReconEvents.RECON_WEIGHT,
                            ReconEvents.weightOf(request, EventType.TOKEN_MISSING)),
                    () -> assertEquals(ReconEvents.RECON_WEIGHT,
                            ReconEvents.weightOf(request, EventType.METHOD_NOT_ALLOWED)));
        }
    }

    @Nested
    @DisplayName("static resources")
    class StaticResources {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"ico", "png", "jpg", "jpeg", "gif", "svg", "webp", "css", "js", "map", "woff",
                "woff2", "ttf", "PNG", "Css"})
        @DisplayName("a miss on a static extension is not counted, whatever its case")
        void staticExtensionIsNotCounted(String extension) {
            PipelineRequest request = routedRequest("/assets/" + dynamicSegment() + "." + extension, null);

            assertAll(extension,
                    () -> assertTrue(ReconEvents.isStaticResource(request)),
                    () -> assertEquals(0, ReconEvents.weightOf(request, EventType.NO_ROUTE_MATCHED)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"/admin", "/admin.php", "/.env", "/wp-login.php", "/css/admin", "/backup.js.bak"})
        @DisplayName("a path whose last segment carries no static extension is counted")
        void nonStaticPathIsCounted(String path) {
            PipelineRequest request = routedRequest(path, null);

            assertAll(path,
                    () -> assertFalse(ReconEvents.isStaticResource(request)),
                    () -> assertEquals(ReconEvents.RECON_WEIGHT,
                            ReconEvents.weightOf(request, EventType.NO_ROUTE_MATCHED)));
        }

        @Test
        @DisplayName("any rejection on an asset route is not counted, whatever its path")
        void assetRouteIsNotCounted() {
            ResolvedAsset asset = new ResolvedAsset(AssetConfig.Source.DIRECTORY, AccessLevel.PUBLIC, "/srv/www",
                    null, null, null);
            PipelineRequest request = routedRequest(dynamicPath(), asset);

            assertAll("the asset terminal action marks the request static",
                    () -> assertTrue(ReconEvents.isStaticResource(request)),
                    () -> assertEquals(0, ReconEvents.weightOf(request, EventType.METHOD_NOT_ALLOWED)));
        }

        @Test
        @DisplayName("before stage 1 the raw request path decides")
        void rawPathDecidesBeforeCanonicalization() {
            PipelineRequest request = PipelineRequest.builder().method(HttpMethod.GET)
                    .requestPath("/favicon.ico").build();

            assertEquals(0, ReconEvents.weightOf(request, EventType.SECURITY_FILTER_VIOLATION));
        }
    }

    private static String dynamicSegment() {
        return Generators.letterStrings(3, 10).next();
    }

    private static String dynamicPath() {
        return "/" + dynamicSegment();
    }

    private static PipelineRequest routedRequest(String path, @Nullable ResolvedAsset asset) {
        PipelineRequest request = PipelineRequest.builder().method(HttpMethod.GET).requestPath(path).build();
        request.canonicalPath(path);
        Route.RouteBuilder route = Route.builder()
                .id(dynamicSegment())
                .match(MatchConfig.builder().pathPrefix("/").build())
                .effectiveAuth(AuthConfig.builder().require(Require.BEARER).build());
        if (asset != null) {
            route.asset(asset);
        } else {
            route.upstream(new ResolvedUpstream("https", "orders.example", 443, ""));
        }
        request.selectedRoute(route.build());
        return request;
    }
}
