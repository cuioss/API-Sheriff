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

import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.Cors;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.HeaderMode;
import de.cuioss.sheriff.gateway.config.model.SecurityHeadersConfig.Hsts;
import org.jspecify.annotations.Nullable;

/**
 * Response security-header preparation, applied at two fixed positions in the pipeline.
 * <p>
 * <strong>Stage 0 — {@link #process}, before route selection and authentication.</strong> The stage
 * seeds the {@link PipelineRequest#responseHeaders() response-header map} the edge applies to
 * <em>every</em> response (success and rejection alike) from the <em>global</em>
 * {@code security_headers} block: {@code Strict-Transport-Security},
 * {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY}, and the verbatim
 * {@code Content-Security-Policy}, each emitted only when the block enables (or declares) it. CORS is global and stays here: the {@code Access-Control-Allow-*} headers of
 * an actual request are added at this position, and when the inbound request is a preflight
 * ({@code OPTIONS} carrying {@code Origin} and {@code Access-Control-Request-Method} from an
 * allow-listed origin) the stage answers it — {@linkplain PipelineRequest#shortCircuit(int)
 * short-circuiting} with {@code 204} and the CORS response headers — so a browser preflight never
 * reaches route selection, authentication or the upstream. Every response produced before a route is
 * selected (a stage-1 rejection, a 404 for an unrouted path, the preflight) therefore carries the
 * global block.
 * <p>
 * <strong>Stage 2a — {@link #applyRouteHeaders}, immediately after route selection.</strong> Once a
 * route is selected, its resolved {@code security_headers} block (ADR-0007: the anchor block when its
 * anchor declares one, otherwise the global block) replaces the gateway-owned security headers seeded
 * at stage 0 <em>wholesale</em>: every gateway-owned name is removed first and only the names the
 * route's block enables are seeded again — there is no key-by-key merge, so an anchor block declaring
 * only {@code frame_deny} drops the global {@code Strict-Transport-Security} for its routes. CORS
 * headers and every non-security entry already on the map are left untouched.
 * <p>
 * <strong>Precedence relative to origin headers.</strong> At both positions each gateway-owned header is
 * seeded according to its resolved {@code header_modes} entry: a {@code set} header goes into
 * {@link PipelineRequest#responseHeaders()} (overwrites an origin value), a {@code default} header into
 * {@link PipelineRequest#responseDefaultHeaders()} (applied by the proxy relay only when the origin sent no
 * value for that name). A name lives in exactly one of the two maps. CORS headers are always set-mode.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SecurityHeadersStage {

    private static final int NO_CONTENT = 204;
    private static final String WILDCARD_ORIGIN = "*";

    /**
     * The gateway-owned response security headers — the single authoritative definition driving
     * <em>both</em> halves of the stage-0 / stage-2a contract.
     * <p>
     * {@code applyResponseHeaders} seeds by iterating these constants and {@code applyRouteHeaders}
     * removes the names of the same constants, so the two halves cannot enumerate different sets:
     * adding a header here seeds it and removes it in one edit, and there is no second list to keep
     * in step. That structural coupling replaces the previous arrangement, where the seeding half
     * spelled the four names inline while the removal half read a separate {@code List<String>} — a
     * header added to the seeding half alone was never removed at stage 2a, so an anchored route kept
     * the GLOBAL value of that header, which is precisely the leak stage 2a exists to prevent. A test
     * asserting a surviving key set cannot close that gap on its own: it can only observe the headers
     * its own fixture enables, so a newly seeded one escapes it.
     * <p>
     * Declaration order is emission order.
     */
    private enum OwnedHeader {

        /** {@code Strict-Transport-Security}, rendered from the block's {@code hsts} settings. */
        HSTS("Strict-Transport-Security") {
            @Override
            @Nullable
            String value(SecurityHeadersConfig headers) {
                Hsts hsts = headers.hsts();
                if (hsts == null) {
                    return null;
                }
                Integer maxAge = hsts.maxAge();
                StringBuilder rendered = new StringBuilder("max-age=").append(maxAge != null ? maxAge : 0);
                if (Boolean.TRUE.equals(hsts.includeSubdomains())) {
                    rendered.append("; includeSubDomains");
                }
                return rendered.toString();
            }

            @Override
            HeaderMode mode(SecurityHeadersConfig headers) {
                return headers.hstsMode();
            }
        },

        /** {@code X-Content-Type-Options: nosniff}, emitted when the block enables it. */
        CONTENT_TYPE_OPTIONS("X-Content-Type-Options") {
            @Override
            @Nullable
            String value(SecurityHeadersConfig headers) {
                return Boolean.TRUE.equals(headers.contentTypeNosniff()) ? "nosniff" : null;
            }

            @Override
            HeaderMode mode(SecurityHeadersConfig headers) {
                return headers.contentTypeNosniffMode();
            }
        },

        /** {@code X-Frame-Options: DENY}, emitted when the block enables it. */
        FRAME_OPTIONS("X-Frame-Options") {
            @Override
            @Nullable
            String value(SecurityHeadersConfig headers) {
                return Boolean.TRUE.equals(headers.frameDeny()) ? "DENY" : null;
            }

            @Override
            HeaderMode mode(SecurityHeadersConfig headers) {
                return headers.frameDenyMode();
            }
        },

        /**
         * {@code Content-Security-Policy}, served verbatim: a control character in the value is
         * refused at schema load and again at boot by {@code ConfigValidator}, so no header can be
         * injected through it.
         */
        CONTENT_SECURITY_POLICY("Content-Security-Policy") {
            @Override
            @Nullable
            String value(SecurityHeadersConfig headers) {
                return headers.contentSecurityPolicy();
            }

            @Override
            HeaderMode mode(SecurityHeadersConfig headers) {
                return headers.contentSecurityPolicyMode();
            }
        };

        private final String headerName;

        OwnedHeader(String headerName) {
            this.headerName = headerName;
        }

        /** @return the wire name of this response header */
        String headerName() {
            return headerName;
        }

        /**
         * @param headers the block being applied
         * @return the value this header resolves to under {@code headers}, or {@code null} when the
         * block does not enable it (no header is emitted)
         */
        abstract @Nullable String value(SecurityHeadersConfig headers);

        /**
         * @param headers the block being applied
         * @return the block's resolved precedence for this header relative to an origin header
         */
        abstract HeaderMode mode(SecurityHeadersConfig headers);
    }

    private final @Nullable SecurityHeadersConfig config;

    /**
     * @param config the global {@code security_headers} posture, {@code null} when none is configured
     */
    public SecurityHeadersStage(@Nullable SecurityHeadersConfig config) {
        this.config = config;
    }

    /**
     * Stage 0: seeds the global response security headers and applies global CORS; for an
     * allow-listed CORS preflight, short-circuits the request.
     *
     * @param request the in-flight request context
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        if (config == null) {
            return;
        }
        applyResponseHeaders(request, config);
        Cors cors = config.cors();
        if (cors != null && Boolean.TRUE.equals(cors.enabled())) {
            applyCors(request, cors);
        }
    }

    /**
     * Stage 2a: replaces the gateway-owned response security headers with the selected route's resolved
     * block. Every gateway-owned name is removed from the response map, then the names
     * {@code routeHeaders} enables are seeded — a wholesale replacement, never a merge. CORS headers
     * and non-security entries (for example {@code Allow} or {@code WWW-Authenticate}) are untouched,
     * and so is the short-circuit state.
     * <p>
     * The removed set and the seeded set are the same set by construction: both iterate the single
     * {@code OwnedHeader} definition, so no header can be seeded at stage 0 without also being
     * removed here.
     *
     * @param request      the in-flight request context, with a route already selected
     * @param routeHeaders the route's resolved {@code security_headers} block, {@code null} when neither
     *                     its anchor nor the gateway declares one
     */
    public void applyRouteHeaders(PipelineRequest request, @Nullable SecurityHeadersConfig routeHeaders) {
        Objects.requireNonNull(request, "request");
        request.responseHeaders().keySet().removeIf(SecurityHeadersStage::isGatewayOwned);
        request.responseDefaultHeaders().keySet().removeIf(SecurityHeadersStage::isGatewayOwned);
        if (routeHeaders != null) {
            applyResponseHeaders(request, routeHeaders);
        }
    }

    private static boolean isGatewayOwned(String name) {
        for (OwnedHeader owned : OwnedHeader.values()) {
            if (owned.headerName().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void applyResponseHeaders(PipelineRequest request, SecurityHeadersConfig headers) {
        for (OwnedHeader owned : OwnedHeader.values()) {
            String value = owned.value(headers);
            if (value != null) {
                seed(request, owned.mode(headers), owned.headerName(), value);
            }
        }
    }

    /**
     * Seeds one gateway-owned header into the map its mode selects — the set-map for
     * {@link HeaderMode#SET}, the default-map for {@link HeaderMode#DEFAULT} — and removes the name from
     * the other map, so a name lives in exactly one of the two.
     */
    private static void seed(PipelineRequest request, HeaderMode mode, String name, String value) {
        if (mode == HeaderMode.DEFAULT) {
            request.responseHeaders().remove(name);
            request.responseDefaultHeaders().put(name, value);
        } else {
            request.responseDefaultHeaders().remove(name);
            request.responseHeaders().put(name, value);
        }
    }

    private static void applyCors(PipelineRequest request, Cors cors) {
        Optional<String> origin = request.firstHeader("Origin");
        if (origin.isEmpty() || !isOriginAllowed(cors, origin.get())) {
            return;
        }
        request.responseHeaders().put("Access-Control-Allow-Origin", origin.get());
        if (Boolean.TRUE.equals(cors.allowCredentials())) {
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
