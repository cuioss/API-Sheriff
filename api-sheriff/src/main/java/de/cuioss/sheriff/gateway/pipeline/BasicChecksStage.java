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


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.core.HttpSecurityValidator;
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.events.GatewayException;

import org.jspecify.annotations.Nullable;

/**
 * Stage 1 — the <strong>non-skippable pre-route floor</strong>: the baseline cui-http path and
 * header filter plus the collection-limit fast-reject, run for every request before route selection
 * and unaffected by any {@code security_filter} profile, {@code none} included.
 * <p>
 * The stage validates the raw path and every header name / value through the shared
 * {@link PipelineFactory.PipelineSet} built from the gateway's resolved baseline
 * {@link SecurityConfiguration}. The path pipeline yields the <strong>single canonical path</strong>
 * ({@link PipelineRequest#canonicalPath(String)}) that GW-01 requires every later stage to consume —
 * and that route selection hard-requires, which is precisely why this floor cannot be made skippable
 * or moved behind route selection. A pipeline violation ({@link UrlSecurityException}) becomes a
 * {@link EventType#SECURITY_FILTER_VIOLATION} (400); a parameter- or header-count overflow beyond
 * the configured caps becomes a {@link EventType#PARAMETER_LIMIT_EXCEEDED} (400) — both without ever
 * echoing the offending value.
 * <p>
 * <strong>What moved out.</strong> The url-parameter NAME and VALUE validation now lives in the
 * post-route {@code ThoroughChecksStage}, where it runs under the route's own configuration and
 * under the {@code profile} gate. The parameter <em>count</em> cap stays here: a collection limit is
 * part of the floor even though the values it bounds are validated post-route. With that move the
 * ADR-0019 reserved-BFF-path relaxation becomes <strong>structural</strong> — a reserved path
 * terminates in {@code handleReservedPath} before route selection and therefore never reaches the
 * parameter pipeline at all — so this stage no longer takes a reserved-path predicate.
 * <p>
 * <strong>The cookie-mode header-value carve-out.</strong> A cookie-mode BFF's sealed session cookie
 * is designed to a multi-kilobyte budget, which the default 2048-character header-value cap would
 * reject at the edge on every authenticated request. The stage therefore accepts an OPTIONAL
 * {@code cookieHeaderConfiguration} whose only difference from the default policy is a raised
 * {@code maxHeaderValueLength}, and applies it to the {@code Cookie} / {@code Set-Cookie} header
 * values ONLY — every other header keeps the default cap, and every other validator in the pipeline
 * (null-byte, control-character, injection-pattern) applies to the cookie header unchanged. A
 * bearer-only or server-mode gateway passes {@code null} and is byte-for-byte unaffected.
 * <p>
 * The relaxation is necessarily <strong>gateway-wide, not per-anchor</strong>: this stage runs
 * BEFORE route selection, so no anchor is resolved yet. The configuration key that supplies the
 * budget ({@code oidc.session.max_cookie_size}) sits on the global session block for exactly that
 * reason — its placement must not be read as per-anchor enforcement.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BasicChecksStage {

    private static final String COOKIE_HEADER = "cookie";
    private static final String SET_COOKIE_HEADER = "set-cookie";

    private final SecurityConfiguration configuration;
    private final PipelineFactory.PipelineSet pipelines;
    private final HttpSecurityValidator cookieHeaderValuePipeline;

    /**
     * @param configuration      the gateway's resolved baseline inbound validation policy
     * @param eventCounter       the shared cui-http security event counter (never a local instance)
     * @param cookieHeaderConfiguration the policy applied to {@code Cookie} / {@code Set-Cookie}
     *                           header VALUES only, or {@code null} to validate them under
     *                           {@code configuration} like every other header. Supplied only by a
     *                           cookie-mode BFF gateway, whose sealed session cookie exceeds the
     *                           default header-value cap
     */
    public BasicChecksStage(SecurityConfiguration configuration, SecurityEventCounter eventCounter,
            @Nullable SecurityConfiguration cookieHeaderConfiguration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        SecurityEventCounter counter = Objects.requireNonNull(eventCounter, "eventCounter");
        this.pipelines = PipelineFactory.createCommonPipelines(configuration, counter);
        this.cookieHeaderValuePipeline = cookieHeaderConfiguration == null
                ? this.pipelines.headerValuePipeline()
                : PipelineFactory.createHeaderValuePipeline(cookieHeaderConfiguration, counter);
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
        request.canonicalPath(validatePath(request.requestPath()));
        validateHeaders(request.headers());
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

    private void validateHeaders(Map<String, List<String>> headers) {
        try {
            for (Map.Entry<String, List<String>> header : headers.entrySet()) {
                String name = header.getKey();
                pipelines.headerNamePipeline().validate(name);
                // The header NAME is already in hand here — that is the seam the cookie-mode
                // carve-out hangs off. Only Cookie / Set-Cookie values may use the raised cap.
                HttpSecurityValidator valuePipeline = isCookieHeader(name)
                        ? cookieHeaderValuePipeline
                        : pipelines.headerValuePipeline();
                for (String value : header.getValue()) {
                    valuePipeline.validate(value);
                }
            }
        } catch (UrlSecurityException violation) {
            throw rejected(violation);
        }
    }

    private static boolean isCookieHeader(String name) {
        return COOKIE_HEADER.equalsIgnoreCase(name) || SET_COOKIE_HEADER.equalsIgnoreCase(name);
    }

    private static GatewayException rejected(UrlSecurityException violation) {
        return new GatewayException(EventType.SECURITY_FILTER_VIOLATION,
                "Security filter rejected %s at %s".formatted(violation.getFailureType(), violation.getValidationType()),
                violation);
    }
}
