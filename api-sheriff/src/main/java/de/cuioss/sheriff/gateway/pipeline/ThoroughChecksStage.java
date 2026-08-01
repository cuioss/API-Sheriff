/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.core.HttpSecurityValidator;
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;

import org.jspecify.annotations.Nullable;

/**
 * Stage 3 — per-route thorough checks, run after the verb gate on the selected route. The stage is
 * <strong>always dispatched</strong>: there is no profile condition at the {@code GatewayEdgeRoute}
 * call site, because two of its four enforcements must survive every mode.
 * <p>
 * <strong>Unconditional half</strong> — runs for every route, {@code profile: none} included:
 * <ul>
 *   <li><strong>{@code max_body_bytes} fast-reject.</strong> A declared {@code Content-Length}
 *       already exceeding the route config's {@code maxBodySize} — the effective cap derived for the
 *       route — is rejected 413 ({@link EventType#CONTENT_TOO_LARGE}) before the body is read. It is a
 *       DoS resource guard, not an injection defence, so it carries no reason to ride the mode
 *       switch.</li>
 *   <li><strong>{@code allowed_paths} whitelist.</strong> When the caller supplies a non-empty
 *       whitelist, the canonical path must match one pattern, where a {@code {name}} segment matches
 *       exactly one path segment; a miss is a 400 {@link EventType#PATH_NOT_ALLOWED}. A path
 *       allowlist is likewise not an injection defence.</li>
 * </ul>
 * <p>
 * <strong>Skippable half</strong> — gated on
 * {@link de.cuioss.sheriff.gateway.config.model.SecurityProfile#skippableValidationEnabled()}, so it
 * runs for {@code strict} and {@code lenient} and is skipped only under {@code none}:
 * <ul>
 *   <li><strong>url-parameter name + value validation.</strong> Relocated here from the pre-route
 *       {@code BasicChecksStage} so it runs under the ROUTE's configuration; it validates the
 *       parameter name as well as each value. It runs unconditionally for a non-{@code none} route
 *       because it is now the only run, not a re-run.</li>
 *   <li><strong>Divergent pipeline re-run.</strong> When the route carries a
 *       {@link SecurityConfiguration} that differs from the stage-1 default, the path and header
 *       pipelines are re-run under it; a route whose config equals the default is skipped (stage 1
 *       already covered it). Per-config pipeline sets are cached so shared route shapes reuse one set.
 *       The parameter loop is deliberately NOT re-run here — that would duplicate the validation
 *       above on the hot path. The re-run honours the SAME two header-value carve-outs the pre-route
 *       floor grants — see below.</li>
 * </ul>
 * <p>
 * <strong>The two header-value carve-outs survive the re-run.</strong> {@code BasicChecksStage}
 * dispatches on the header NAME across three branches so an {@code Authorization} value and a
 * {@code Cookie} / {@code Set-Cookie} value are each measured against a raised cap. This stage
 * re-validates every header value under the ROUTE's configuration, so without the same dispatch it
 * would silently undo both carve-outs one stage later — and it runs BEFORE the authentication stage,
 * so an {@code Authorization} value is still present and unmodified when it does. The concrete
 * regression: on a {@code strict} gateway a route declaring nothing but a raised
 * {@code max_body_bytes} diverges from the baseline, which triggers the re-run, which then rejected a
 * bearer token {@code 400} before bearer validation ever ran. This stage therefore repeats the
 * three-way dispatch, seeding each carve-out from the ROUTE's own configuration — never from the
 * gateway baseline — via {@link #carveOutConfigurationFor(SecurityConfiguration, int)}.
 * <p>
 * The carve-out cap is {@code max(routeConfig.maxHeaderValueLength(), budget)}, and the {@code max}
 * is load-bearing in both directions: a route that legitimately RAISES its own header-value cap above
 * the carve-out budget keeps its higher cap, while a route sitting at or below the baseline still
 * admits the carved-out header up to the budget. The carve-out never LOWERS a route's own cap, and it
 * changes {@code maxHeaderValueLength} alone — every other validator (null-byte, control-character,
 * extended-ASCII, injection-pattern, case-sensitivity) stays on the ROUTE's setting for the carved-out
 * headers, exactly as for every other header. The direction of change is admit-more-length-only, for
 * exactly two header names.
 * <p>
 * <strong>The closed list of what {@code none} turns off is exactly those two items</strong> — the
 * relocated url-parameter name/value validation and the pipeline re-run. Everything else keeps
 * running: the whole non-skippable pre-route floor (collection caps, the URL path pipeline that
 * produces the canonical path, {@code CanonicalPathGuard}, {@code FramingGate}, the passthrough host
 * guard, header validation), the body cap and the path allowlist above, and everything downstream
 * (verb gate, CSRF defence, authentication, forward policy, dispatch). Widening this list is a scope
 * change that belongs back with the operator, not here. See ADR-0024.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ThoroughChecksStage {

    private static final String SEGMENT_WILDCARD_PREFIX = "{";
    private static final String SEGMENT_WILDCARD_SUFFIX = "}";
    private static final String COOKIE_HEADER = "cookie";
    private static final String SET_COOKIE_HEADER = "set-cookie";
    private static final String AUTHORIZATION_HEADER = "authorization";

    private final SecurityConfiguration defaultConfiguration;
    private final SecurityEventCounter eventCounter;
    private final int authorizationCarveOutBudget;
    private final @Nullable Integer cookieCarveOutBudget;
    private final Map<SecurityConfiguration, RoutePipelines> pipelineCache = new ConcurrentHashMap<>();

    /**
     * @param defaultConfiguration the stage-1 default policy, used to skip a route whose config matches
     * @param eventCounter         the shared cui-http security event counter (never a local instance)
     * @param cookieHeaderConfiguration the gateway's pre-route {@code Cookie} / {@code Set-Cookie}
     *                             carve-out policy, or {@code null} for a gateway that is not an
     *                             active cookie-mode BFF and therefore has no cookie carve-out to
     *                             carry forward. Only its {@code maxHeaderValueLength} is read, as
     *                             the carve-out BUDGET; every other dimension applied to a cookie
     *                             value here comes from the ROUTE's configuration
     * @param authorizationHeaderConfiguration the gateway's pre-route {@code Authorization} carve-out
     *                             policy. Unconditional and never {@code null}, mirroring
     *                             {@code BasicChecksStage}. Only its {@code maxHeaderValueLength} is
     *                             read, as the carve-out BUDGET
     */
    public ThoroughChecksStage(SecurityConfiguration defaultConfiguration, SecurityEventCounter eventCounter,
            @Nullable SecurityConfiguration cookieHeaderConfiguration,
            SecurityConfiguration authorizationHeaderConfiguration) {
        this.defaultConfiguration = Objects.requireNonNull(defaultConfiguration, "defaultConfiguration");
        this.eventCounter = Objects.requireNonNull(eventCounter, "eventCounter");
        Objects.requireNonNull(authorizationHeaderConfiguration, "authorizationHeaderConfiguration");
        this.authorizationCarveOutBudget = authorizationHeaderConfiguration.maxHeaderValueLength();
        this.cookieCarveOutBudget = cookieHeaderConfiguration == null
                ? null
                : cookieHeaderConfiguration.maxHeaderValueLength();
    }

    /**
     * Runs the per-route thorough checks: the unconditional half first, then the profile-gated half.
     *
     * @param request      the in-flight request context; its route must be selected (stage 2)
     * @param allowedPaths the selected route's {@code allowed_paths} whitelist, empty when unrestricted
     * @throws GatewayException on a parameter or divergent-pipeline violation
     *                          ({@link EventType#SECURITY_FILTER_VIOLATION}), a whitelist miss
     *                          ({@link EventType#PATH_NOT_ALLOWED}), or a body-cap breach
     *                          ({@link EventType#CONTENT_TOO_LARGE})
     */
    public void process(PipelineRequest request, List<String> allowedPaths) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(allowedPaths, "allowedPaths");
        RouteRuntime route = requireSelectedRoute(request);
        String canonicalPath = requireCanonicalPath(request);
        // Present for every assembler-produced route (the posture resolver runs for all of them), so
        // the fallback covers only a RouteRuntime built without a resolved configuration.
        SecurityConfiguration routeConfig = route.getSecurityConfiguration().orElse(defaultConfiguration);

        // Unconditional half — a resource guard and a path allowlist, neither of which is an
        // injection defence, so neither rides the profile switch.
        enforceBodyCap(request, routeConfig);
        enforceAllowedPaths(canonicalPath, allowedPaths);

        // Skippable half — the closed list of what 'none' turns off, and nothing more.
        if (!route.getSecurityProfile().skippableValidationEnabled()) {
            return;
        }
        validateParameters(request, routeConfig);
        if (!routeConfig.equals(defaultConfiguration)) {
            reRunPipelines(request, routeConfig, canonicalPath);
        }
    }

    /**
     * Validates every query-parameter NAME and value under the route's configuration. Relocated from
     * the pre-route {@code BasicChecksStage}: cui-http exposes no dedicated URL-parameter-name
     * pipeline to this project, so the url-parameter pipeline is reused against the key — closing the
     * name-validation gap with the same rigor applied to values, mirroring the header validation that
     * stays in the pre-route floor.
     * <p>
     * Reserved BFF paths never reach this stage: they terminate in {@code handleReservedPath} before
     * route selection, which is what makes the ADR-0019 reserved-path relaxation structural rather
     * than predicate-driven.
     */
    private void validateParameters(PipelineRequest request, SecurityConfiguration routeConfig) {
        PipelineFactory.PipelineSet pipelines = pipelinesFor(routeConfig).pipelines();
        try {
            for (Map.Entry<String, List<String>> parameter : request.queryParameters().entrySet()) {
                pipelines.urlParameterPipeline().validate(parameter.getKey());
                for (String value : parameter.getValue()) {
                    pipelines.urlParameterPipeline().validate(value);
                }
            }
        } catch (UrlSecurityException violation) {
            throw rejected(violation);
        }
    }

    /**
     * Re-runs the path and header pipelines under a route configuration that diverges from the
     * stage-1 default. Header NAMES as well as header VALUES are re-run: the pre-route
     * {@code BasicChecksStage} validates names under the gateway baseline only, so a route whose
     * declared {@code security_filter} carries a stricter name policy ({@code allowed_header_names},
     * {@code blocked_header_names}, a lower {@code maxHeaderNameLength}) would otherwise never have
     * it applied. The parameter loop is deliberately absent: {@link #validateParameters} already
     * ran under this same configuration, so re-running it here would be duplicate hot-path work.
     * <p>
     * Header VALUES go through the same three-way header-NAME dispatch the pre-route floor uses, so
     * the {@code Authorization} and {@code Cookie} / {@code Set-Cookie} carve-outs are not undone
     * here. See the class Javadoc for the {@code max}-bounded cap and the by-header / by-validator
     * bounds.
     */
    private void reRunPipelines(PipelineRequest request, SecurityConfiguration routeConfig, String canonicalPath) {
        RoutePipelines pipelines = pipelinesFor(routeConfig);
        try {
            pipelines.pipelines().urlPathPipeline().validate(canonicalPath);
            for (Map.Entry<String, List<String>> header : request.headers().entrySet()) {
                String name = header.getKey();
                pipelines.pipelines().headerNamePipeline().validate(name);
                HttpSecurityValidator valuePipeline = pipelines.valuePipelineFor(name);
                for (String value : header.getValue()) {
                    valuePipeline.validate(value);
                }
            }
        } catch (UrlSecurityException violation) {
            throw rejected(violation);
        }
    }

    private RoutePipelines pipelinesFor(SecurityConfiguration routeConfig) {
        return pipelineCache.computeIfAbsent(routeConfig, this::buildPipelines);
    }

    /**
     * Builds the per-route pipeline triple: the route's own common pipeline set plus the two
     * carve-out header-value validators derived from it. Cached per route configuration, so the
     * carve-out derivation is a boot-shaped cost rather than a per-request one.
     */
    private RoutePipelines buildPipelines(SecurityConfiguration routeConfig) {
        PipelineFactory.PipelineSet routePipelines =
                PipelineFactory.createCommonPipelines(routeConfig, eventCounter);
        HttpSecurityValidator authorization =
                carveOutValuePipeline(routeConfig, authorizationCarveOutBudget, routePipelines);
        // A gateway that is not an active cookie-mode BFF supplies no cookie budget, so its Cookie /
        // Set-Cookie values stay on the ROUTE's own header-value pipeline like any other header —
        // the same by-deployment bound the pre-route floor applies.
        HttpSecurityValidator cookie = cookieCarveOutBudget == null
                ? routePipelines.headerValuePipeline()
                : carveOutValuePipeline(routeConfig, cookieCarveOutBudget, routePipelines);
        return new RoutePipelines(routePipelines, authorization, cookie);
    }

    /**
     * Resolves the value pipeline for one carve-out, reusing the route's own header-value pipeline
     * whenever the route's cap already meets or exceeds the budget — so a route that raised its own
     * cap allocates no second pipeline and, more importantly, is never measured against a lower one.
     */
    private HttpSecurityValidator carveOutValuePipeline(SecurityConfiguration routeConfig, int budget,
            PipelineFactory.PipelineSet routePipelines) {
        SecurityConfiguration carveOut = carveOutConfigurationFor(routeConfig, budget);
        return carveOut.equals(routeConfig)
                ? routePipelines.headerValuePipeline()
                : PipelineFactory.createHeaderValuePipeline(carveOut, eventCounter);
    }

    /**
     * The per-route carve-out policy: {@code routeConfig} with its header-value cap raised to
     * {@code max(routeConfig.maxHeaderValueLength(), budget)} and <strong>nothing else changed</strong>.
     * <p>
     * Seeded through the shared {@link SecurityConfigurations#builderSeededFrom(SecurityConfiguration)}
     * rather than a locally-written component list, which is what makes "differs from the ROUTE
     * configuration in {@code maxHeaderValueLength} alone" structurally true instead of a comment.
     * Returns {@code routeConfig} itself when the route's own cap already meets the budget, so the
     * carve-out can only ever raise a cap, never lower one.
     * <p>
     * Package-private so the seeding is asserted directly rather than only through a booted pipeline,
     * matching the precedent the two pre-route carve-out factories set.
     *
     * @param routeConfig the route's resolved configuration, the sole seed for every dimension
     * @param budget      the gateway-wide carve-out budget for this header name
     * @return the carve-out policy, or {@code routeConfig} unchanged when its cap already suffices
     */
    static SecurityConfiguration carveOutConfigurationFor(SecurityConfiguration routeConfig, int budget) {
        if (routeConfig.maxHeaderValueLength() >= budget) {
            return routeConfig;
        }
        return SecurityConfigurations.builderSeededFrom(routeConfig)
                .maxHeaderValueLength(budget)
                .build();
    }

    /**
     * The three-way header-name dispatch, mirroring the pre-route floor: {@code Authorization} and
     * {@code Cookie} / {@code Set-Cookie} each resolve to their own carve-out pipeline, everything
     * else to the route's own header-value pipeline. The two carve-out names are disjoint, so the
     * order of the branches carries no precedence decision.
     */
    private record RoutePipelines(PipelineFactory.PipelineSet pipelines,
    HttpSecurityValidator authorization, HttpSecurityValidator cookie) {

        HttpSecurityValidator valuePipelineFor(String name) {
            if (AUTHORIZATION_HEADER.equalsIgnoreCase(name)) {
                return authorization;
            }
            if (COOKIE_HEADER.equalsIgnoreCase(name) || SET_COOKIE_HEADER.equalsIgnoreCase(name)) {
                return cookie;
            }
            return pipelines.headerValuePipeline();
        }
    }

    private static GatewayException rejected(UrlSecurityException violation) {
        return new GatewayException(EventType.SECURITY_FILTER_VIOLATION,
                "Per-route filter rejected %s at %s".formatted(violation.getFailureType(),
                        violation.getValidationType()),
                violation);
    }

    private static void enforceBodyCap(PipelineRequest request, SecurityConfiguration routeConfig) {
        long cap = routeConfig.maxBodySize();
        if (request.declaredContentLength() > cap) {
            throw new GatewayException(EventType.CONTENT_TOO_LARGE,
                    "Declared body %d exceeds route cap %d".formatted(request.declaredContentLength(), cap));
        }
    }

    private static void enforceAllowedPaths(String canonicalPath, List<String> allowedPaths) {
        if (allowedPaths.isEmpty()) {
            return;
        }
        for (String pattern : allowedPaths) {
            if (matchesPattern(canonicalPath, pattern)) {
                return;
            }
        }
        throw new GatewayException(EventType.PATH_NOT_ALLOWED, "Canonical path outside route allowed_paths");
    }

    private static boolean matchesPattern(String path, String pattern) {
        String[] pathSegments = path.split("/", -1);
        String[] patternSegments = pattern.split("/", -1);
        if (pathSegments.length != patternSegments.length) {
            return false;
        }
        for (int i = 0; i < patternSegments.length; i++) {
            String patternSegment = patternSegments[i];
            if (isWildcard(patternSegment)) {
                if (pathSegments[i].isEmpty()) {
                    return false;
                }
            } else if (!patternSegment.equals(pathSegments[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWildcard(String segment) {
        return segment.startsWith(SEGMENT_WILDCARD_PREFIX) && segment.endsWith(SEGMENT_WILDCARD_SUFFIX);
    }

    private static RouteRuntime requireSelectedRoute(PipelineRequest request) {
        RouteRuntime route = request.selectedRoute();
        if (route == null) {
            throw new IllegalStateException("Thorough checks require the route selected at stage 2");
        }
        return route;
    }

    private static String requireCanonicalPath(PipelineRequest request) {
        Optional<String> canonicalPath = Optional.ofNullable(request.canonicalPath());
        return canonicalPath.orElseThrow(
                () -> new IllegalStateException("Thorough checks require the canonical path resolved at stage 1"));
    }
}
