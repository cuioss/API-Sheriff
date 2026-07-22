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

import java.util.List;
import java.util.Map;
import java.util.Objects;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

/**
 * Stage 1 — the baseline cui-http security filter plus collection-limit fast-reject, run for
 * every request before route selection.
 * <p>
 * The stage validates the raw path, every query-parameter value, and every header name / value
 * through the shared {@link PipelineFactory.PipelineSet} built from the gateway's default
 * {@link SecurityConfiguration}. The path pipeline yields the <strong>single canonical path</strong>
 * ({@link PipelineRequest#canonicalPath(String)}) that GW-01 requires every later stage to consume.
 * A pipeline violation ({@link UrlSecurityException}) becomes a
 * {@link EventType#SECURITY_FILTER_VIOLATION} (400); a parameter- or header-count overflow beyond
 * the configured caps becomes a {@link EventType#PARAMETER_LIMIT_EXCEEDED} (400) — both without ever
 * echoing the offending value.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BasicChecksStage {

    private final SecurityConfiguration configuration;
    private final PipelineFactory.PipelineSet pipelines;

    /**
     * @param configuration the gateway's default inbound validation policy
     * @param eventCounter  the shared cui-http security event counter (never a local instance)
     */
    public BasicChecksStage(SecurityConfiguration configuration, SecurityEventCounter eventCounter) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.pipelines = PipelineFactory.createCommonPipelines(configuration,
                Objects.requireNonNull(eventCounter, "eventCounter"));
    }

    /**
     * Runs the baseline filter, records the canonical path, and enforces collection caps.
     *
     * @param request the in-flight request context
     * @throws GatewayException with {@link EventType#SECURITY_FILTER_VIOLATION} on a pipeline
     *                          violation, or {@link EventType#PARAMETER_LIMIT_EXCEEDED} on a
     *                          parameter- or header-count overflow
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        enforceCollectionLimits(request);
        String canonical = validatePath(request.requestPath());
        validateParameters(request.queryParameters());
        validateHeaders(request.headers());
        request.canonicalPath(canonical);
    }

    private void enforceCollectionLimits(PipelineRequest request) {
        long paramCount = request.queryParameters().values().stream().mapToLong(List::size).sum();
        if (paramCount > configuration.maxParameterCount()) {
            throw new GatewayException(EventType.PARAMETER_LIMIT_EXCEEDED,
                    "Query-parameter count %d exceeds cap %d".formatted(paramCount, configuration.maxParameterCount()));
        }
        long headerCount = request.headers().values().stream().mapToLong(List::size).sum();
        if (headerCount > configuration.maxHeaderCount()) {
            throw new GatewayException(EventType.PARAMETER_LIMIT_EXCEEDED,
                    "Header count %d exceeds cap %d".formatted(headerCount, configuration.maxHeaderCount()));
        }
    }

    private String validatePath(String rawPath) {
        try {
            return pipelines.urlPathPipeline().validate(rawPath).orElse(rawPath);
        } catch (UrlSecurityException violation) {
            throw rejected(violation);
        }
    }

    private void validateParameters(Map<String, List<String>> parameters) {
        try {
            for (Map.Entry<String, List<String>> parameter : parameters.entrySet()) {
                // Validate the parameter NAME as well as each value. cui-http exposes no dedicated
                // URL-parameter-name pipeline to this project, so the url-parameter pipeline is reused
                // against the key — closing the name-validation gap with the same rigor applied to
                // values, mirroring validateHeaders which validates both header name and value.
                pipelines.urlParameterPipeline().validate(parameter.getKey());
                for (String value : parameter.getValue()) {
                    pipelines.urlParameterPipeline().validate(value);
                }
            }
        } catch (UrlSecurityException violation) {
            throw rejected(violation);
        }
    }

    private void validateHeaders(Map<String, List<String>> headers) {
        try {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                pipelines.headerNamePipeline().validate(header.getKey());
                for (String value : header.getValue()) {
                    pipelines.headerValuePipeline().validate(value);
                }
            }
        } catch (UrlSecurityException violation) {
            throw rejected(violation);
        }
    }

    private static GatewayException rejected(UrlSecurityException violation) {
        return new GatewayException(EventType.SECURITY_FILTER_VIOLATION,
                "Security filter rejected %s at %s".formatted(violation.getFailureType(), violation.getValidationType()),
                violation);
    }
}
