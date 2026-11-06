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
package de.cuioss.sheriff.gateway.pipeline;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.boot.ConfigBootPipeline;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.edge.assembly.SecurityPostures;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.Route;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The canonicalisation claim of the compose sample's {@code /auth}-fronted variant, proven against the
 * shipped configuration set rather than stated in its documentation.
 * <p>
 * <strong>The claim.</strong> In that variant the gateway fronts Keycloak under {@code /auth} with an
 * <em>allowlist</em> of Keycloak's path namespaces ({@code endpoints/idp.yaml}): the sample realm, the
 * theme resources and the well-known documents. Nothing else under {@code /auth} is routed, so the admin
 * console ({@code /auth/admin/}) and the master realm ({@code /auth/realms/master/}) are unreachable by
 * construction. That holds only if no SPELLING of an unlisted path can ride an allowlisted route into
 * Keycloak — a dot-segment, an encoded separator or dot, a doubled slash, a matrix parameter, a
 * backslash, a case variant or a double encoding that the gateway matched against
 * {@code /auth/realms/sample} but that Keycloak would resolve to {@code /auth/admin/}.
 * <p>
 * <strong>How it is driven.</strong> The shipped set is assembled by the production
 * {@link ConfigBootPipeline} — the same loader, topology resolver, validator and route-table builder the
 * boot runs — and every spelling is pushed through the inbound pipeline the edge runs before any request
 * is forwarded, in the edge's order and with the edge's wiring: {@link BasicChecksStage} (the pre-route
 * floor that records the single canonical path) seeded from the resolved {@code security_defaults}
 * profile exactly as {@code GatewayEdgeRoute} seeds it, then {@link CanonicalPathGuard}, then
 * {@link RouteSelectionStage} over the assembled routes. A spelling passes the claim when the gateway
 * refuses it ({@link EventType#SECURITY_FILTER_VIOLATION}), routes it nowhere
 * ({@link EventType#NO_ROUTE_MATCHED}) or routes it to a route that is not the identity provider's. It
 * breaches the claim when it is selected onto a route of the {@code idp} endpoint.
 * <p>
 * <strong>Every spelling {@code check-bff-login.sh --idp-prefix /auth} probes live is in the corpus</strong>,
 * plus case variants, double encodings and the overlong UTF-8 dot. The positive control proves the
 * allowlisted paths DO reach the {@code idp} endpoint unchanged, so a set that routed nothing at all
 * cannot pass by refusing everything.
 * <p>
 * <strong>Red against its deliberate cuts.</strong> The {@link DeliberateCuts} drive the same verdict over
 * two broken variants, and each must be reported as a breach: a copy of the shipped set with a catch-all
 * {@code /auth} route added to {@code idp.yaml}, and the shipped set with the canonical-path floor and guard
 * bypassed, so the raw spelling reaches route selection. Without those, a verdict function that reported
 * nothing for any input would pass this class green.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("/auth-fronted compose sample — no spelling of an unlisted path reaches the identity provider")
class IdpFrontingCanonicalPathTest {

    /** The repository root; surefire runs with the module root as the working directory. */
    private static final Path REPOSITORY = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();

    /** The shipped configuration set of the variant, mounted by {@code docker-compose.auth-fronted.yml}. */
    private static final Path AUTH_FRONTED_SET =
            REPOSITORY.resolve("deployment/compose-sample/docker/sheriff-config-auth-fronted");

    /** The endpoint fronting Keycloak — its routes are the only way into the identity provider. */
    private static final String IDP_ENDPOINT_ID = "idp";

    /** The shipped {@code quarkus.http.limits.max-body-size} floor the boot path is wired with. */
    private static final long FRAMEWORK_LIMIT_BYTES = 67108864L;

    /**
     * The values {@code docker-compose.yml} supplies for the bare placeholders of the variant's
     * {@code gateway.yaml}. The addresses and paths are placeholders only — nothing here dials or reads them.
     */
    private static final Map<String, String> ENVIRONMENT = Map.of(
            "SHERIFF_TRUSTED_PROXIES", "192.0.2.10/32",
            "SHERIFF_CLIENT_AUTH_KEY_FILE", "/app/certificates/client-auth.pem",
            "SHERIFF_DPOP_KEY_FILE", "/app/certificates/dpop.pem");

    /** The catch-all route the first deliberate cut appends to the routes list of a copied {@code idp.yaml}. */
    private static final String CATCH_ALL_ROUTE = """
                - id: idp-catch-all
                  match:
                    path_prefix: /auth
                  upstream:
                    path: /auth
            """;

    private static ConfigBootPipeline.Assembly shipped;
    private static FrontedPipeline shippedPipeline;

    @BeforeAll
    static void assembleTheShippedSet() {
        shipped = assemble(AUTH_FRONTED_SET);
        shippedPipeline = new FrontedPipeline(shipped, true);
    }

    /**
     * The spellings that must never reach the identity provider: the namespaces the allowlist exists to
     * keep out, and every spelling of the admin console or the master realm that starts under an
     * allowlisted prefix.
     *
     * @return the hostile request paths, raw, exactly as they would arrive on the wire
     */
    static Stream<String> hostileSpellings() {
        return Stream.of(
                // The namespaces outside the allowlist, spelled plainly — check-bff-login.sh probes the first four.
                "/auth/admin/",
                "/auth/realms/master/",
                "/auth/metrics",
                "/auth/health",
                "/auth/admin/master/console/",
                "/auth/realms/master/protocol/openid-connect/token",
                "/auth/health/ready",
                "/auth",
                "/auth/",
                "/auth/realms/",
                // A realm whose name merely starts with the allowlisted one: the prefix is a segment, not a string.
                "/auth/realms/sample2/",
                // The dot-segment, encoded, doubled-slash, matrix and backslash spellings check-bff-login.sh probes.
                "/auth/realms/sample/../../admin/",
                "/auth/%2e%2e/admin/",
                "/auth/realms/sample/%2F..%2F..%2Fadmin/",
                "/auth//admin/",
                "/auth/./admin/",
                "/auth/realms/sample/..;/..;/admin/",
                "/auth/realms/sample/..\\..\\admin/",
                // Further dot-segment and encoded-dot forms of the same escape, and one into the master realm.
                "/auth/realms/sample/../master/",
                "/auth/realms/sample/%2e%2e/%2e%2e/admin/",
                "/auth/realms/sample/%2E%2E/%2E%2E/admin/",
                "/auth/realms/sample/.%2e/.%2e/admin/",
                "/auth/realms/sample/%2f..%2f..%2fadmin/",
                "/auth/realms/sample/%5c..%5c..%5cadmin/",
                "/auth/realms/sample/..%5c..%5cadmin/",
                "/auth/realms/sample/;/../../admin/",
                // Case variants: a matcher or a decoder that folded case would treat these as the namespaces above.
                "/AUTH/admin/",
                "/Auth/realms/sample/../../admin/",
                "/auth/ADMIN/",
                "/auth/Realms/master/",
                "/auth/realms/SAMPLE/../../admin/",
                "/auth/realms/sample/%2E%2e/%2e%2E/admin/",
                // Double encoding: a value decoded once by the gateway and once more by the upstream.
                "/auth/%252e%252e/admin/",
                "/auth/realms/sample/%252e%252e/%252e%252e/admin/",
                "/auth/realms/sample/%252F..%252F..%252Fadmin/",
                // The overlong UTF-8 encoding of '.', which a lenient decoder reads as a dot.
                "/auth/realms/sample/%c0%ae%c0%ae/%c0%ae%c0%ae/admin/");
    }

    /**
     * One allowlisted request the login flow sends, and the {@code idp} route it must be selected onto.
     *
     * @param method  the request method
     * @param path    the raw request path
     * @param routeId the route of {@code endpoints/idp.yaml} that must serve it
     */
    record AllowedRequest(HttpMethod method, String path, String routeId) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Stream<AllowedRequest> allowedRequests() {
        return Stream.of(
                new AllowedRequest(HttpMethod.GET, "/auth/realms/sample/.well-known/openid-configuration",
                        "idp-realm-sample"),
                new AllowedRequest(HttpMethod.GET, "/auth/realms/sample/protocol/openid-connect/auth",
                        "idp-realm-sample"),
                new AllowedRequest(HttpMethod.POST, "/auth/realms/sample/login-actions/authenticate",
                        "idp-realm-sample"),
                new AllowedRequest(HttpMethod.GET, "/auth/resources/abc12/login/keycloak.v2/css/styles.css",
                        "idp-resources"),
                new AllowedRequest(HttpMethod.GET, "/auth/.well-known/oauth-authorization-server/realms/sample",
                        "idp-well-known"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("hostileSpellings")
    @DisplayName("a spelling of an unlisted path is refused or unrouted, never selected onto an idp route")
    void hostileSpellingNeverReachesTheIdentityProvider(String rawPath) {
        Outcome outcome = shippedPipeline.drive(HttpMethod.GET, rawPath);

        assertFalse(outcome.reachesIdentityProvider(), () -> rawPath + " was selected onto the identity "
                + "provider's route " + outcome.routeId() + " with the canonical path " + outcome.canonicalPath()
                + " — a spelling of a path outside the allowlist would be forwarded into Keycloak");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allowedRequests")
    @DisplayName("an allowlisted path is selected onto its idp route with the path unchanged (positive control)")
    void allowlistedPathReachesItsIdpRoute(AllowedRequest request) {
        Outcome outcome = shippedPipeline.drive(request.method(), request.path());

        assertAll(request.toString(),
                () -> assertEquals(Kind.ROUTED, outcome.kind(),
                        () -> "an allowlisted path must be routed, but was " + outcome),
                () -> assertEquals(request.routeId(), outcome.routeId(),
                        () -> "an allowlisted path must be served by its own idp route, but was " + outcome),
                () -> assertTrue(outcome.reachesIdentityProvider(),
                        () -> request.routeId() + " must be a route of the " + IDP_ENDPOINT_ID + " endpoint"),
                () -> assertEquals(request.path(), outcome.canonicalPath(),
                        "a canonical allowlisted path must reach route selection unchanged"));
    }

    @Test
    @DisplayName("the shipped set fronts the identity provider through an idp endpoint with routes at all")
    void theShippedSetDeclaresTheIdpRoutesTheVerdictReadsAgainst() {
        Set<String> idpRoutes = idpRouteIds(shipped);

        assertAll(
                () -> assertFalse(idpRoutes.isEmpty(), () -> "the shipped set declares no route on an '"
                        + IDP_ENDPOINT_ID + "' endpoint, so every hostile spelling would pass by finding no IdP "
                        + "route to reach"),
                () -> assertTrue(shipped.routeTable().routes().stream().map(Route::id).toList()
                                .containsAll(idpRoutes),
                        "every idp route must be in the assembled route table the pipeline selects over"));
    }

    /**
     * The same verdict, driven over two deliberately broken variants. Each must be reported as a breach,
     * which is what shows the verdict above can fail at all.
     */
    @Nested
    @DisplayName("deliberate cuts the verdict must report")
    class DeliberateCuts {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("a catch-all /auth route added to idp.yaml lets the admin console through")
        void catchAllRouteIsReported() throws Exception {
            Path cut = copyOfTheShippedSet(tempDir);
            // The leading line break keeps the appended item off the last line of the shipped file.
            Files.writeString(cut.resolve("endpoints/idp.yaml"), "\n" + CATCH_ALL_ROUTE, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
            FrontedPipeline pipeline = new FrontedPipeline(assemble(cut), true);

            List<String> breaches = breachesOf(pipeline);

            assertAll(
                    () -> assertTrue(breaches.contains("/auth/admin/"),
                            () -> "the catch-all route must carry the plain admin console into Keycloak: "
                                    + breaches),
                    () -> assertTrue(breaches.contains("/auth/realms/master/"),
                            () -> "the catch-all route must carry the master realm into Keycloak: " + breaches));
        }

        @Test
        @DisplayName("bypassing the canonical-path floor and guard lets a dot-segment ride the realm route")
        void bypassedGuardIsReported() {
            FrontedPipeline bypassed = new FrontedPipeline(shipped, false);

            List<String> breaches = breachesOf(bypassed);

            assertAll(
                    () -> assertTrue(breaches.contains("/auth/realms/sample/../../admin/"),
                            () -> "without canonicalisation a dot-segment must be matched against the realm route: "
                                    + breaches),
                    () -> assertTrue(breaches.contains("/auth/realms/sample/%2F..%2F..%2Fadmin/"),
                            () -> "without the guard an encoded separator must be matched against the realm route: "
                                    + breaches),
                    () -> assertFalse(breaches.contains("/auth/admin/"),
                            () -> "the plain admin console stays unrouted without a catch-all, so the breach above "
                                    + "is the bypass and not the allowlist: " + breaches));
        }

        private static List<String> breachesOf(FrontedPipeline pipeline) {
            return hostileSpellings()
                    .filter(rawPath -> pipeline.drive(HttpMethod.GET, rawPath).reachesIdentityProvider())
                    .toList();
        }

        private static Path copyOfTheShippedSet(Path target) throws IOException {
            List<Path> sources;
            try (Stream<Path> walk = Files.walk(AUTH_FRONTED_SET)) {
                sources = walk.filter(Files::isRegularFile).toList();
            }
            assertFalse(sources.isEmpty(), () -> "the shipped set " + AUTH_FRONTED_SET + " holds no file to copy");
            for (Path source : sources) {
                Path copy = target.resolve(AUTH_FRONTED_SET.relativize(source).toString());
                Files.createDirectories(Objects.requireNonNull(copy.getParent()));
                Files.copy(source, copy);
            }
            return target;
        }
    }

    private static ConfigBootPipeline.Assembly assemble(Path configDir) {
        ConfigBootPipeline.Outcome outcome = new ConfigBootPipeline(new EnvSecretResolver(ENVIRONMENT::get))
                .run(configDir, FRAMEWORK_LIMIT_BYTES);
        assertEquals(List.of(), outcome.violations(),
                () -> configDir + " must assemble without a violation, or every verdict below is about nothing");
        return Objects.requireNonNull(outcome.assembly());
    }

    private static Set<String> idpRouteIds(ConfigBootPipeline.Assembly assembly) {
        return assembly.enabledEndpoints().stream()
                .filter(endpoint -> IDP_ENDPOINT_ID.equals(endpoint.id()))
                .map(EndpointConfig::routes)
                .flatMap(List::stream)
                .map(RouteConfig::id)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** What the inbound pipeline did with one request. */
    enum Kind {
        /** Refused by the canonical-path floor or guard. */
        REFUSED,
        /** No route matched: the unrouted 404. */
        UNROUTED,
        /** Selected onto a route. */
        ROUTED
    }

    /**
     * The outcome of one request.
     *
     * @param kind                    what the pipeline did
     * @param canonicalPath           the canonical path route selection consumed, {@code null} when refused
     *                                before one was recorded
     * @param routeId                 the selected route, {@code null} unless routed
     * @param reachesIdentityProvider whether the selected route belongs to the {@code idp} endpoint
     */
    record Outcome(Kind kind, @Nullable String canonicalPath, @Nullable String routeId,
    boolean reachesIdentityProvider) {
    }

    /**
     * The edge's inbound pipeline up to and including route selection, wired from one assembled set the
     * way {@code GatewayEdgeRoute} wires it.
     */
    static final class FrontedPipeline {

        private final @Nullable BasicChecksStage basicChecks;
        private final @Nullable CanonicalPathGuard guard;
        private final RouteSelectionStage routeSelection;
        private final Set<String> idpRouteIds;

        /**
         * @param assembly      the assembled configuration set
         * @param canonicalises {@code false} for the deliberate cut that bypasses the floor and the guard,
         *                      handing route selection the raw path as its canonical path
         */
        FrontedPipeline(ConfigBootPipeline.Assembly assembly, boolean canonicalises) {
            GatewayConfig gateway = assembly.gateway();
            SecurityProfile globalProfile = RouteTableBuilder.globalProfile(gateway);
            SecurityDefaultsConfig defaults = gateway.securityDefaults();
            SecurityConfiguration baseline = SecurityConfigurations.withDeclaredCharacterPolicy(
                    SecurityProfile.limitsProfile(globalProfile, globalProfile).preset(),
                    defaults == null ? null : defaults.allowExtendedAscii());
            // No probe carries a Cookie header, so the cookie carve-out — which needs the live BFF runtime —
            // has nothing to measure and is left out; every other header policy is the edge's own.
            this.basicChecks = canonicalises
                    ? new BasicChecksStage(baseline, new SecurityEventCounter(), null,
                    SecurityPostures.authorizationHeaderConfigurationFor(gateway, baseline))
                    : null;
            this.guard = canonicalises ? new CanonicalPathGuard() : null;
            this.routeSelection = new RouteSelectionStage(assembly.routeTable().routes());
            this.idpRouteIds = idpRouteIds(assembly);
        }

        Outcome drive(HttpMethod method, String rawPath) {
            PipelineRequest request = PipelineRequest.builder()
                    .method(method)
                    .requestPath(rawPath)
                    .queryParameters(List.of())
                    .headers(Map.of())
                    .build();
            try {
                canonicalise(request);
                routeSelection.process(request);
            } catch (GatewayException rejection) {
                return switch (rejection.getEventType()) {
                    case SECURITY_FILTER_VIOLATION -> new Outcome(Kind.REFUSED, request.canonicalPath(), null, false);
                    case NO_ROUTE_MATCHED -> new Outcome(Kind.UNROUTED, request.canonicalPath(), null, false);
                    default -> fail(rawPath + " was rejected with an event the inbound pipeline does not raise "
                            + "before forwarding: " + rejection.getEventType());
                };
            }
            Route selected = Objects.requireNonNull(request.selectedRoute());
            return new Outcome(Kind.ROUTED, request.canonicalPath(), selected.id(),
                    idpRouteIds.contains(selected.id()));
        }

        private void canonicalise(PipelineRequest request) {
            if (basicChecks == null || guard == null) {
                request.canonicalPath(request.requestPath());
                return;
            }
            basicChecks.process(request);
            guard.process(request);
        }
    }
}
