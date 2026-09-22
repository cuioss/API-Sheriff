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
package de.cuioss.sheriff.gateway.portal;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.gateway.pipeline.SecurityHeadersStage;

/**
 * The gateway-owned header set of every portal-rendered response — the overview page and the HTML
 * error page alike.
 * <p>
 * Every such response carries {@code Content-Type: text/html; charset=utf-8} and
 * {@code X-Content-Type-Options: nosniff}. The cache policy depends on what the body carries:
 * <ul>
 *   <li>a response carrying session data (the page names a signed-in user) and every HTML error page
 *       is {@code Cache-Control: no-store} — neither may ever be replayed from a cache;</li>
 *   <li>a session-free overview page is {@code Cache-Control: max-age=<cache_seconds>}; when the BFF
 *       runtime is active it additionally announces {@code Vary: Cookie}, because the anonymous page
 *       must never be served from cache to a browser that has since signed in. The edge merges the
 *       {@code Cookie} name into any {@code Vary} value already accumulated for the response (for
 *       example the CORS reflection's {@code Origin}) through {@link SecurityHeadersStage#mergedVary}.</li>
 * </ul>
 * The envelope never emits {@code Set-Cookie}. The portal {@code Content-Security-Policy} is not part of
 * this set: it is composed with the operator's {@code header_modes} precedence by
 * {@link SecurityHeadersStage#applyPortalHeaders}.
 * <p>
 * Immutable and framework-agnostic; thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PortalResponseEnvelope {

    /** The media type of every portal-rendered response. */
    public static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";

    /** The {@code Content-Type} response-header name. */
    public static final String CONTENT_TYPE_HEADER = "Content-Type";

    /** The {@code X-Content-Type-Options} response-header name. */
    public static final String CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";

    /** The {@code Cache-Control} response-header name. */
    public static final String CACHE_CONTROL_HEADER = "Cache-Control";

    /** The {@code Vary} response-header name. */
    public static final String VARY_HEADER = "Vary";

    /** The only {@code X-Content-Type-Options} value. */
    public static final String NOSNIFF = "nosniff";

    /** The cache directive of a session-bearing response and of every HTML error page. */
    public static final String NO_STORE = "no-store";

    /** The request-header name a session-free page varies on while the BFF runtime is active. */
    private static final String COOKIE_HEADER = "Cookie";

    private final int cacheSeconds;
    private final boolean bffActive;

    /**
     * Cache classes of a portal-rendered response.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    public enum Cacheability {

        /** An overview page carrying no session data — cacheable for {@code cache_seconds}. */
        SESSION_FREE,

        /** An overview page carrying session data — never stored. */
        SESSION_BEARING,

        /** An HTML error page — never stored. */
        ERROR_PAGE
    }

    /**
     * @param cacheSeconds the effective {@code portal.cache_seconds}, zero or positive
     * @param bffActive    whether the BFF runtime is active, i.e. whether a browser can hold a session
     *                     that would change the page
     * @throws IllegalArgumentException if {@code cacheSeconds} is negative
     */
    public PortalResponseEnvelope(int cacheSeconds, boolean bffActive) {
        if (cacheSeconds < 0) {
            throw new IllegalArgumentException("cacheSeconds must not be negative: " + cacheSeconds);
        }
        this.cacheSeconds = cacheSeconds;
        this.bffActive = bffActive;
    }

    /**
     * Composes the envelope headers of one portal-rendered response, in emission order.
     *
     * @param cacheability the cache class of the response
     * @return an unmodifiable, insertion-ordered map of header name to value; it carries
     * {@code Vary: Cookie} only for a session-free page while the BFF runtime is active
     */
    public Map<String, String> headers(Cacheability cacheability) {
        Objects.requireNonNull(cacheability, "cacheability");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CONTENT_TYPE_HEADER, HTML_CONTENT_TYPE);
        headers.put(CONTENT_TYPE_OPTIONS_HEADER, NOSNIFF);
        if (cacheability == Cacheability.SESSION_FREE) {
            headers.put(CACHE_CONTROL_HEADER, "max-age=" + cacheSeconds);
            if (bffActive) {
                headers.put(VARY_HEADER, COOKIE_HEADER);
            }
        } else {
            headers.put(CACHE_CONTROL_HEADER, NO_STORE);
        }
        return Collections.unmodifiableMap(headers);
    }
}
