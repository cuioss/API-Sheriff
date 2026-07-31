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
 * <strong>Two header-value carve-outs.</strong> The cap every header value is measured against is the
 * <em>resolved baseline</em> — whatever {@code security_defaults.profile} resolves to, 1024 characters
 * under {@code strict} and 8192 under {@code lenient} — never a fixed absolute. Two header names are
 * carved out of it, each by a dedicated value pipeline selected on the header NAME:
 * <ul>
 *   <li><strong>{@code Cookie} / {@code Set-Cookie}</strong> — a cookie-mode BFF's sealed session
 *       cookie is designed to a multi-kilobyte budget the resolved baseline would reject at the edge
 *       on every authenticated request. The OPTIONAL {@code cookieHeaderConfiguration} supplies the
 *       raised cap; a bearer-only or server-mode gateway passes {@code null} and keeps the resolved
 *       baseline on every header, byte-for-byte unaffected. Its budget key is
 *       {@code oidc.session.max_cookie_size}.</li>
 *   <li><strong>{@code Authorization}</strong> — a bearer token plus its {@code Bearer } prefix
 *       routinely exceeds the {@code strict} baseline, which would reject every bearer request here,
 *       before route selection and before bearer validation ever runs. Unlike the cookie carve-out
 *       this configuration is <em>unconditional and non-null</em>: a bearer-capable gateway is every
 *       gateway, so there is no mode that switches it off. Its budget key is
 *       {@code security_defaults.max_authorization_header_value_length}.</li>
 * </ul>
 * Both carve-outs are bounded on the same three axes ADR-0019 states. <em>By header</em>: the raised
 * cap reaches those header names only — every other header is measured at the resolved baseline.
 * <em>By validator</em>: each carve-out configuration is seeded from the resolved baseline and differs
 * from it in {@code maxHeaderValueLength} alone, so every non-length validator (null-byte,
 * control-character, extended-ASCII, injection-pattern) still applies to the carved-out value exactly
 * as to any other. <em>By deployment</em>: the cookie carve-out exists only in an active cookie-mode
 * BFF, while the {@code Authorization} carve-out is unconditional — a genuinely weaker axis, not a
 * parallel one.
 * <p>
 * Both relaxations are necessarily <strong>gateway-wide, not per-anchor</strong>: this stage runs
 * BEFORE route selection, so no anchor is resolved yet. Both configuration keys sit on global blocks
 * ({@code oidc.session} and {@code security_defaults}) for exactly that reason — their placement must
 * not be read as per-anchor enforcement.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BasicChecksStage {

    private static final String COOKIE_HEADER = "cookie";
    private static final String SET_COOKIE_HEADER = "set-cookie";
    private static final String AUTHORIZATION_HEADER = "authorization";

    private final SecurityConfiguration configuration;
    private final PipelineFactory.PipelineSet pipelines;
    private final HttpSecurityValidator cookieHeaderValuePipeline;
    private final HttpSecurityValidator authorizationHeaderValuePipeline;

    /**
     * @param configuration      the gateway's resolved baseline inbound validation policy
     * @param eventCounter       the shared cui-http security event counter (never a local instance)
     * @param cookieHeaderConfiguration the policy applied to {@code Cookie} / {@code Set-Cookie}
     *                           header VALUES only, or {@code null} to validate them under
     *                           {@code configuration} like every other header. Supplied only by a
     *                           cookie-mode BFF gateway, whose sealed session cookie exceeds the
     *                           resolved baseline header-value cap
     * @param authorizationHeaderConfiguration the policy applied to {@code Authorization} header
     *                           VALUES only. Unconditional and never {@code null}: every gateway is
     *                           bearer-capable, so unlike the cookie carve-out there is no mode that
     *                           switches it off
     */
    public BasicChecksStage(SecurityConfiguration configuration, SecurityEventCounter eventCounter,
            @Nullable SecurityConfiguration cookieHeaderConfiguration,
            SecurityConfiguration authorizationHeaderConfiguration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        SecurityEventCounter counter = Objects.requireNonNull(eventCounter, "eventCounter");
        Objects.requireNonNull(authorizationHeaderConfiguration, "authorizationHeaderConfiguration");
        this.pipelines = PipelineFactory.createCommonPipelines(configuration, counter);
        this.cookieHeaderValuePipeline = cookieHeaderConfiguration == null
                ? this.pipelines.headerValuePipeline()
                : PipelineFactory.createHeaderValuePipeline(cookieHeaderConfiguration, counter);
        this.authorizationHeaderValuePipeline =
                PipelineFactory.createHeaderValuePipeline(authorizationHeaderConfiguration, counter);
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
                // The header NAME is already in hand here — that is the seam both carve-outs hang
                // off. Only Authorization and Cookie / Set-Cookie values may use a raised cap;
                // every other header falls through to the resolved baseline pipeline.
                HttpSecurityValidator valuePipeline = valuePipelineFor(name);
                for (String value : header.getValue()) {
                    valuePipeline.validate(value);
                }
            }
        } catch (UrlSecurityException violation) {
            throw rejected(violation);
        }
    }

    /**
     * The three-way header-name dispatch: {@code Authorization} and {@code Cookie} / {@code Set-Cookie}
     * each resolve to their own carve-out pipeline, everything else to the resolved baseline. The two
     * carve-out names are disjoint, so the order of the branches carries no precedence decision.
     */
    private HttpSecurityValidator valuePipelineFor(String name) {
        if (isAuthorizationHeader(name)) {
            return authorizationHeaderValuePipeline;
        }
        if (isCookieHeader(name)) {
            return cookieHeaderValuePipeline;
        }
        return pipelines.headerValuePipeline();
    }

    private static boolean isAuthorizationHeader(String name) {
        return AUTHORIZATION_HEADER.equalsIgnoreCase(name);
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
