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
package de.cuioss.sheriff.api.asset;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;


import de.cuioss.sheriff.api.config.model.AccessLevel;
import de.cuioss.sheriff.api.config.model.HttpMethod;

import lombok.experimental.UtilityClass;

/**
 * The gateway-owned response envelope for the asset terminal action (decision:
 * ADR-0014).
 * <p>
 * The gateway — never the backing source — governs the response headers of every
 * served asset, so a compromised or misconfigured directory/upstream cannot dictate
 * caching, content sniffing, or cookie behaviour. The governance is uniform across
 * both {@link AssetSource} implementations:
 * <ul>
 *   <li><strong>Fixed content type.</strong> The {@code Content-Type} is derived
 *       from the {@linkplain #contentTypeFor(String) gateway's own extension map},
 *       overriding whatever the source claimed; an unknown extension falls back to
 *       {@value #DEFAULT_CONTENT_TYPE}.</li>
 *   <li><strong>No MIME sniffing.</strong> {@code X-Content-Type-Options: nosniff}
 *       is always set.</li>
 *   <li><strong>Governed caching.</strong> An {@link AccessLevel#AUTHENTICATED}
 *       asset is forced to {@code Cache-Control: no-store} regardless of source, so
 *       authenticated content is never written to a shared cache.</li>
 *   <li><strong>No cookies.</strong> Any {@code Set-Cookie} the source emitted is
 *       stripped — an asset action never establishes a session.</li>
 *   <li><strong>Read-only verbs.</strong> Only {@code GET} and {@code HEAD} are
 *       served ({@link #isAllowedMethod(HttpMethod)}).</li>
 * </ul>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class AssetResponseEnvelope {

    /** The content type served for an extension the gateway does not recognise. */
    public static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** The {@code Content-Type} response header name. */
    public static final String CONTENT_TYPE = "Content-Type";
    /** The {@code X-Content-Type-Options} response header name. */
    public static final String CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";
    /** The {@code Cache-Control} response header name. */
    public static final String CACHE_CONTROL = "Cache-Control";
    /** The {@code Set-Cookie} response header name (always stripped). */
    public static final String SET_COOKIE = "Set-Cookie";
    /** The {@code nosniff} value for {@link #CONTENT_TYPE_OPTIONS}. */
    public static final String NOSNIFF = "nosniff";
    /** The {@code no-store} value forced for authenticated assets. */
    public static final String NO_STORE = "no-store";

    private static final Map<String, String> CONTENT_TYPES = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("xml", "application/xml"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("wasm", "application/wasm"));

    /**
     * Resolves the gateway-governed content type for a filename by its extension.
     *
     * @param filename the served filename (may contain a path); never {@code null}
     * @return the mapped content type, or {@value #DEFAULT_CONTENT_TYPE} for an
     *         unknown or absent extension
     */
    public static String contentTypeFor(String filename) {
        Objects.requireNonNull(filename, "filename");
        int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = filename.substring(lastSlash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return DEFAULT_CONTENT_TYPE;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return CONTENT_TYPES.getOrDefault(extension, DEFAULT_CONTENT_TYPE);
    }

    /**
     * Whether the request verb is servable by an asset action — {@code GET} and
     * {@code HEAD} only.
     *
     * @param method the request method; never {@code null}
     * @return {@code true} for {@link HttpMethod#GET} or {@link HttpMethod#HEAD}
     */
    public static boolean isAllowedMethod(HttpMethod method) {
        Objects.requireNonNull(method, "method");
        return method == HttpMethod.GET || method == HttpMethod.HEAD;
    }

    /**
     * Builds the final, gateway-governed response header set for a served asset.
     * <p>
     * The source headers are copied verbatim except for the four the gateway owns:
     * {@code Content-Type} is overridden from the extension map, {@code Set-Cookie}
     * is dropped, {@code X-Content-Type-Options: nosniff} is added, and — for
     * {@link AccessLevel#AUTHENTICATED} — {@code Cache-Control: no-store} overrides
     * whatever the source declared. Header-name matching is case-insensitive.
     *
     * @param filename      the served filename, used to resolve the content type
     * @param access        the effective access level of the serving route
     * @param sourceHeaders the raw headers the backing source proposed; never
     *                      {@code null}
     * @return an ordered, mutable copy of the governed headers
     */
    public static Map<String, String> governedHeaders(String filename, AccessLevel access,
            Map<String, String> sourceHeaders) {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(sourceHeaders, "sourceHeaders");
        boolean authenticated = access == AccessLevel.AUTHENTICATED;
        Map<String, String> governed = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : sourceHeaders.entrySet()) {
            if (isGatewayOwned(header.getKey(), authenticated)) {
                continue;
            }
            governed.put(header.getKey(), header.getValue());
        }
        governed.put(CONTENT_TYPE, contentTypeFor(filename));
        governed.put(CONTENT_TYPE_OPTIONS, NOSNIFF);
        if (authenticated) {
            governed.put(CACHE_CONTROL, NO_STORE);
        }
        return governed;
    }

    private static boolean isGatewayOwned(String headerName, boolean authenticated) {
        return CONTENT_TYPE.equalsIgnoreCase(headerName)
                || CONTENT_TYPE_OPTIONS.equalsIgnoreCase(headerName)
                || SET_COOKIE.equalsIgnoreCase(headerName)
                || (authenticated && CACHE_CONTROL.equalsIgnoreCase(headerName));
    }
}
