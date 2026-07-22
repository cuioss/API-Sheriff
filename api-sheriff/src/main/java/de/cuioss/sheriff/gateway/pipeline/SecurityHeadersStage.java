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
package de.cuioss.sheriff.gateway.pipeline;

import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.Cors;

/**
 * Stage 0 — response-header preparation and CORS preflight, run before authentication.
 * <p>
 * The stage seeds the {@link PipelineRequest#responseHeaders() response-header map} the edge
 * applies to <em>every</em> response (success and rejection alike): {@code Strict-Transport-Security},
 * {@code X-Content-Type-Options: nosniff}, and {@code X-Frame-Options: DENY}, each emitted only when
 * the global {@code security_headers} block enables it. When CORS is enabled and the inbound request
 * is a preflight ({@code OPTIONS} carrying {@code Origin} and {@code Access-Control-Request-Method}
 * from an allow-listed origin), the stage answers it here — {@linkplain PipelineRequest#shortCircuit(int)
 * short-circuiting} with {@code 204} and the CORS response headers — so a browser preflight never
 * reaches authentication or the upstream.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SecurityHeadersStage {

    private static final int NO_CONTENT = 204;
    private static final String WILDCARD_ORIGIN = "*";

    private final Optional<SecurityHeadersConfig> config;

    /**
     * @param config the global {@code security_headers} posture, empty when none is configured
     */
    public SecurityHeadersStage(Optional<SecurityHeadersConfig> config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Seeds response headers and, for an allow-listed CORS preflight, short-circuits the request.
     *
     * @param request the in-flight request context
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        config.ifPresent(headers -> applyResponseHeaders(request, headers));
        config.flatMap(SecurityHeadersConfig::cors)
                .filter(cors -> cors.enabled().orElse(Boolean.FALSE))
                .ifPresent(cors -> applyCors(request, cors));
    }

    private static void applyResponseHeaders(PipelineRequest request, SecurityHeadersConfig headers) {
        headers.hsts().ifPresent(hsts -> {
            StringBuilder value = new StringBuilder("max-age=").append(hsts.maxAge().orElse(0));
            if (hsts.includeSubdomains().orElse(Boolean.FALSE)) {
                value.append("; includeSubDomains");
            }
            request.responseHeaders().put("Strict-Transport-Security", value.toString());
        });
        if (headers.contentTypeNosniff().orElse(Boolean.FALSE)) {
            request.responseHeaders().put("X-Content-Type-Options", "nosniff");
        }
        if (headers.frameDeny().orElse(Boolean.FALSE)) {
            request.responseHeaders().put("X-Frame-Options", "DENY");
        }
    }

    private static void applyCors(PipelineRequest request, Cors cors) {
        Optional<String> origin = request.firstHeader("Origin");
        if (origin.isEmpty() || !isOriginAllowed(cors, origin.get())) {
            return;
        }
        request.responseHeaders().put("Access-Control-Allow-Origin", origin.get());
        if (cors.allowCredentials().orElse(Boolean.FALSE)) {
            request.responseHeaders().put("Access-Control-Allow-Credentials", "true");
        }
        if (isPreflight(request)) {
            if (!cors.allowedMethods().isEmpty()) {
                request.responseHeaders().put("Access-Control-Allow-Methods", String.join(", ", cors.allowedMethods()));
            }
            if (!cors.allowedHeaders().isEmpty()) {
                request.responseHeaders().put("Access-Control-Allow-Headers", String.join(", ", cors.allowedHeaders()));
            }
            request.shortCircuit(NO_CONTENT);
        }
    }

    /**
     * Decides whether {@code origin} is CORS-allowed. A configured {@code "*"} is a real wildcard
     * (ConfigValidator permits it only when {@code allowCredentials} is false): a request {@code Origin}
     * header is never literally {@code "*"}, so without this wildcard branch a configured wildcard would
     * silently never match and CORS headers would never be emitted for any origin. When the wildcard is
     * present the presented origin is accepted and reflected by the caller; otherwise the origin must be
     * listed explicitly.
     */
    private static boolean isOriginAllowed(Cors cors, String origin) {
        return cors.allowedOrigins().contains(WILDCARD_ORIGIN) || cors.allowedOrigins().contains(origin);
    }

    private static boolean isPreflight(PipelineRequest request) {
        return request.method() == HttpMethod.OPTIONS
                && request.hasHeader("Access-Control-Request-Method");
    }
}
