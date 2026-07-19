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
package de.cuioss.sheriff.api.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;
import de.cuioss.sheriff.api.routing.RouteRuntime;

/**
 * Stage 3 — per-route thorough checks, run after the verb gate on the selected route.
 * <p>
 * Three per-route enforcements, all on the single canonical path:
 * <ul>
 *   <li><strong>Re-run only on divergence.</strong> When the route carries its own
 *       {@link SecurityConfiguration} that differs from the stage-1 default, its pipelines are
 *       re-run on the canonical path, every parameter value, and every header value; a route whose
 *       config equals the default is skipped (stage 1 already covered it). Per-config pipeline sets
 *       are cached so shared route shapes reuse one set.</li>
 *   <li><strong>{@code allowed_paths} whitelist.</strong> When the caller supplies a non-empty
 *       whitelist, the canonical path must match one pattern, where a {@code {name}} segment matches
 *       exactly one path segment; a miss is a 400 {@link EventType#PATH_NOT_ALLOWED}.</li>
 *   <li><strong>{@code max_body_bytes} fast-reject.</strong> A declared {@code Content-Length}
 *       already exceeding the route config's {@code maxBodySize} is rejected 400
 *       ({@link EventType#PARAMETER_LIMIT_EXCEEDED}) before the body is read.</li>
 * </ul>
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
     * Runs the per-route thorough checks.
     *
     * @param request      the in-flight request context; its route must be selected (stage 2)
     * @param allowedPaths the selected route's {@code allowed_paths} whitelist, empty when unrestricted
     * @throws GatewayException on a divergent-pipeline violation ({@link EventType#SECURITY_FILTER_VIOLATION}),
     *                          a whitelist miss ({@link EventType#PATH_NOT_ALLOWED}), or a body-cap breach
     *                          ({@link EventType#PARAMETER_LIMIT_EXCEEDED})
     */
    public void process(PipelineRequest request, List<String> allowedPaths) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(allowedPaths, "allowedPaths");
        RouteRuntime route = requireSelectedRoute(request);
        String canonicalPath = requireCanonicalPath(request);

        route.getSecurityConfiguration().ifPresent(routeConfig -> {
            if (!routeConfig.equals(defaultConfiguration)) {
                reRunPipelines(request, routeConfig, canonicalPath);
            }
            enforceBodyCap(request, routeConfig);
        });
        enforceAllowedPaths(canonicalPath, allowedPaths);
    }

    private void reRunPipelines(PipelineRequest request, SecurityConfiguration routeConfig, String canonicalPath) {
        PipelineFactory.PipelineSet pipelines = pipelineCache.computeIfAbsent(routeConfig,
                config -> PipelineFactory.createCommonPipelines(config, eventCounter));
        try {
            pipelines.urlPathPipeline().validate(canonicalPath);
            for (List<String> values : request.queryParameters().values()) {
                for (String value : values) {
                    pipelines.urlParameterPipeline().validate(value);
                }
            }
            for (List<String> values : request.headers().values()) {
                for (String value : values) {
                    pipelines.headerValuePipeline().validate(value);
                }
            }
        } catch (UrlSecurityException violation) {
            throw new GatewayException(EventType.SECURITY_FILTER_VIOLATION,
                    "Per-route filter rejected %s at %s".formatted(violation.getFailureType(),
                            violation.getValidationType()),
                    violation);
        }
    }

    private static void enforceBodyCap(PipelineRequest request, SecurityConfiguration routeConfig) {
        long cap = routeConfig.maxBodySize();
        if (request.declaredContentLength() > cap) {
            throw new GatewayException(EventType.PARAMETER_LIMIT_EXCEEDED,
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
