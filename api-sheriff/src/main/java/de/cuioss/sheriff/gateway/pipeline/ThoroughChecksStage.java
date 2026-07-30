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
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;
import de.cuioss.sheriff.gateway.routing.RouteRuntime;

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
 *       above on the hot path.</li>
 * </ul>
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

    private final SecurityConfiguration defaultConfiguration;
    private final SecurityEventCounter eventCounter;
    private final Map<SecurityConfiguration, PipelineFactory.PipelineSet> pipelineCache = new ConcurrentHashMap<>();

    /**
     * @param defaultConfiguration the stage-1 default policy, used to skip a route whose config matches
     * @param eventCounter         the shared cui-http security event counter (never a local instance)
     */
    public ThoroughChecksStage(SecurityConfiguration defaultConfiguration, SecurityEventCounter eventCounter) {
        this.defaultConfiguration = Objects.requireNonNull(defaultConfiguration, "defaultConfiguration");
        this.eventCounter = Objects.requireNonNull(eventCounter, "eventCounter");
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
        PipelineFactory.PipelineSet pipelines = pipelinesFor(routeConfig);
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
     * stage-1 default. The parameter loop is deliberately absent: {@link #validateParameters} already
     * ran under this same configuration, so re-running it here would be duplicate hot-path work.
     */
    private void reRunPipelines(PipelineRequest request, SecurityConfiguration routeConfig, String canonicalPath) {
        PipelineFactory.PipelineSet pipelines = pipelinesFor(routeConfig);
        try {
            pipelines.urlPathPipeline().validate(canonicalPath);
            for (List<String> values : request.headers().values()) {
                for (String value : values) {
                    pipelines.headerValuePipeline().validate(value);
                }
            }
        } catch (UrlSecurityException violation) {
            throw rejected(violation);
        }
    }

    private PipelineFactory.PipelineSet pipelinesFor(SecurityConfiguration routeConfig) {
        return pipelineCache.computeIfAbsent(routeConfig,
                config -> PipelineFactory.createCommonPipelines(config, eventCounter));
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
