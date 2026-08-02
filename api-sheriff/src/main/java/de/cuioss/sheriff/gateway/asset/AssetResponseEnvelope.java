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
package de.cuioss.sheriff.gateway.asset;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;

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
 *       from the {@linkplain #contentTypeFor(String, Map) gateway's own extension
 *       map}, overriding whatever the source claimed; an unknown extension falls back
 *       to {@value #DEFAULT_CONTENT_TYPE}. An operator may <em>add</em> mappings for
 *       extensions the built-in map does not carry, but can never replace one of
 *       them — see {@link #contentTypeFor(String, Map)}.</li>
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
     * The extensions the gateway maps itself — the mappings an operator can never
     * replace.
     * <p>
     * Exposed so the boot-time config validator can refuse an
     * {@code asset_defaults.content_types} entry that names one of them, rather than
     * letting the add-only rule be enforced only at request time (where a rejected
     * entry would look like a silently ignored one). The returned set is immutable.
     *
     * @return the lowercase built-in extension names
     */
    public static Set<String> builtInExtensions() {
        return CONTENT_TYPES.keySet();
    }

    /**
     * Resolves the gateway-governed content type for a filename by its extension.
     * <p>
     * The built-in map is consulted first and always wins; {@code operatorTypes} — the
     * boot-resolved {@code asset_defaults.content_types} block — is consulted only for
     * an extension the built-in map does not carry. That precedence is what makes the
     * operator block <strong>add-only</strong>: it can teach the gateway an extension
     * it did not know, but can never restate one it did, so no configuration can turn
     * {@code svg} into {@code text/html} and make a served asset a stored-XSS vector.
     * An entry naming a built-in extension is additionally refused at boot, so it never
     * reaches this method as a silently-ignored mapping.
     * <p>
     * The operator map is passed in explicitly rather than held in a static field: the
     * class stays free of mutable static state, so nothing is assigned after class
     * initialisation and GraalVM has no build-time-snapshot hazard to reason about.
     *
     * @param filename      the served filename (may contain a path); never {@code null}
     * @param operatorTypes the boot-resolved operator additions keyed by lowercase
     *                      extension; never {@code null}, empty when unconfigured
     * @return the mapped content type, or {@value #DEFAULT_CONTENT_TYPE} for an
     *         extension neither map carries, and for an absent extension
     */
    public static String contentTypeFor(String filename, Map<String, String> operatorTypes) {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(operatorTypes, "operatorTypes");
        int lastSlash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        String name = filename.substring(lastSlash + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return DEFAULT_CONTENT_TYPE;
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String builtIn = CONTENT_TYPES.get(extension);
        if (builtIn != null) {
            return builtIn;
        }
        return operatorTypes.getOrDefault(extension, DEFAULT_CONTENT_TYPE);
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
     * @param operatorTypes the boot-resolved add-only operator content-type additions,
     *                      forwarded to {@link #contentTypeFor(String, Map)}; never
     *                      {@code null}, empty when unconfigured
     * @return an ordered, mutable copy of the governed headers
     */
    public static Map<String, String> governedHeaders(String filename, AccessLevel access,
            Map<String, String> sourceHeaders, Map<String, String> operatorTypes) {
        Objects.requireNonNull(filename, "filename");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(sourceHeaders, "sourceHeaders");
        Objects.requireNonNull(operatorTypes, "operatorTypes");
        boolean authenticated = access == AccessLevel.AUTHENTICATED;
        Map<String, String> governed = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : sourceHeaders.entrySet()) {
            if (isGatewayOwned(header.getKey(), authenticated)) {
                continue;
            }
            governed.put(header.getKey(), header.getValue());
        }
        governed.put(CONTENT_TYPE, contentTypeFor(filename, operatorTypes));
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
