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

import java.util.Locale;
import java.util.regex.Pattern;


import de.cuioss.sheriff.gateway.events.EventType;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The exhaustive classification of which gateway exits may answer with an HTML error page, and the
 * content negotiation that decides whether a given request gets one.
 * <p>
 * An HTML error page is only ever rendered for an error the gateway <em>originated</em>. Two
 * classifications cover every exit:
 * <ul>
 *   <li>{@link Classification#HTML_ELIGIBLE} — a gateway-originated rejection a browser navigation
 *       can meaningfully land on: an unrouted address, a missing scope, a CSRF rejection, a body over
 *       the route cap, an upstream failure, an open circuit or an upstream timeout, a failed OIDC
 *       callback, and a directory-asset miss.</li>
 *   <li>{@link Classification#KEEP_SHAPE} — every other exit keeps its current body and content
 *       type. That includes, above all, every response <strong>relayed from an origin</strong>
 *       ({@link Exit#ORIGIN_RELAY}, {@link Exit#UPSTREAM_ASSET}): the gateway never replaces what an
 *       upstream said.</li>
 * </ul>
 * <p>
 * <strong>Exhaustive by construction.</strong> {@link #classify(EventType)} is a {@code switch}
 * expression over every {@link EventType} constant with no {@code default} branch, so adding a
 * constant fails compilation until it is classified here — a new error cannot silently start, or
 * silently fail, to render HTML. The non-event exits are the closed {@link Exit} enum, classified
 * the same way.
 * <p>
 * <strong>Negotiation.</strong> {@link #offersHtml(String)} answers {@code true} only when the
 * {@code Accept} header lists the {@code text/html} media range explicitly with a quality above
 * zero. Wildcards ({@code *}{@code /*}, {@code text/*}) do not qualify, so {@code fetch()}, XHR and
 * REST clients — which send {@code *}{@code /*} by default — keep the {@code application/problem+json}
 * body, while a browser navigation ({@code text/html,…}) gets the HTML page. The status code is the
 * same either way.
 * <p>
 * <strong>Fixed titles.</strong> {@link #titleFor(int)} maps a status to a fixed, generic title; an
 * error page never carries a problem detail, an exception message or an upstream address.
 * <p>
 * Framework-agnostic and stateless; thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ErrorPageClassifier {

    /** The one media range that qualifies a request for an HTML error page. */
    private static final String TEXT_HTML = "text/html";

    /** The RFC 9110 {@code qvalue} grammar: {@code 0} or {@code 1} with up to three decimals. */
    private static final Pattern QUALITY_VALUE = Pattern.compile("[01](?:\\.\\d{0,3})?");

    /** The generic title rendered for a status the fixed table does not name. */
    public static final String FALLBACK_TITLE = "Error";

    /**
     * Whether a gateway exit may answer with an HTML error page.
     */
    public enum Classification {

        /** A gateway-originated error that renders HTML when the request explicitly accepts it. */
        HTML_ELIGIBLE,

        /** An exit whose body and content type never change, whatever the request accepts. */
        KEEP_SHAPE
    }

    /**
     * The gateway exits that do not carry an {@link EventType}, named so each one is classified
     * explicitly rather than falling through.
     */
    public enum Exit {

        /** The OIDC callback failed and answers an error status without a {@code Location}. */
        CALLBACK_FAILURE,

        /** A {@code source: directory} asset route found no file for the request. */
        DIRECTORY_ASSET_NOT_FOUND,

        /** A response relayed from the route's origin — never replaced. */
        ORIGIN_RELAY,

        /** A {@code source: upstream} asset response relayed from a secondary origin — never replaced. */
        UPSTREAM_ASSET,

        /** A gRPC rejection, answered trailers-only in the gRPC status vocabulary. */
        GRPC_REJECTION,

        /** An admission-budget rejection, answered before any request processing. */
        ADMISSION_REJECT,

        /** A relay that failed after the response head was already sent — no body can be replaced. */
        RELAY_FAILURE_AFTER_HEAD,

        /** An unexpected internal failure — answered in its current, detail-free shape. */
        UNEXPECTED_INTERNAL
    }

    /**
     * Classifies a gateway event. Default-free: a new {@link EventType} constant fails compilation
     * until it is classified here.
     *
     * @param event the event the exit renders
     * @return the classification
     */
    public static Classification classify(EventType event) {
        return switch (event) {
            case NO_ROUTE_MATCHED, SCOPE_MISSING, CSRF_REJECTED, CONTENT_TOO_LARGE, UPSTREAM_ERROR,
                    UPSTREAM_CIRCUIT_OPEN, UPSTREAM_TIMEOUT -> Classification.HTML_ELIGIBLE;
            case REQUEST_FORWARDED, TOKEN_REFRESHED, CONFIG_LOADED, CONFIG_INVALID, AUTH_WEAKENED,
                    SECURITY_FILTER_VIOLATION, PATH_NOT_ALLOWED, PARAMETER_LIMIT_EXCEEDED,
                    PASSTHROUGH_HOST_SMUGGLED, METHOD_NOT_ALLOWED, RESERVED_BODY_TOO_LARGE, TOKEN_MISSING,
                    TOKEN_INVALID, SESSION_CREATED, SESSION_DESTROYED, SESSION_REFRESH_FAILED,
                    BACKCHANNEL_LOGOUT, LOGOUT_TOKEN_INVALID, WEBSOCKET_ORIGIN_REJECTED,
                    WEBSOCKET_IDLE_TIMEOUT -> Classification.KEEP_SHAPE;
        };
    }

    /**
     * Classifies a non-event gateway exit. Default-free: a new {@link Exit} constant fails
     * compilation until it is classified here.
     *
     * @param exit the exit
     * @return the classification
     */
    public static Classification classify(Exit exit) {
        return switch (exit) {
            case CALLBACK_FAILURE, DIRECTORY_ASSET_NOT_FOUND -> Classification.HTML_ELIGIBLE;
            case ORIGIN_RELAY, UPSTREAM_ASSET, GRPC_REJECTION, ADMISSION_REJECT, RELAY_FAILURE_AFTER_HEAD,
                    UNEXPECTED_INTERNAL -> Classification.KEEP_SHAPE;
        };
    }

    /**
     * Whether the request's {@code Accept} header lists {@code text/html} explicitly with a quality
     * above zero. A wildcard range never qualifies; an absent or malformed header, or a malformed
     * quality on the {@code text/html} range, answers {@code false}.
     *
     * @param accept the raw {@code Accept} header value, {@code null} when absent
     * @return {@code true} when the request explicitly accepts {@code text/html}
     */
    public static boolean offersHtml(@Nullable String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        for (String range : accept.split(",", -1)) {
            String[] parts = range.split(";", -1);
            if (TEXT_HTML.equals(parts[0].strip().toLowerCase(Locale.ROOT)) && qualityAboveZero(parts)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The fixed, generic title for an error status. It never derives from a problem detail.
     *
     * @param status the HTTP status
     * @return the title, or {@link #FALLBACK_TITLE} for a status outside the fixed table
     */
    public static String titleFor(int status) {
        return switch (status) {
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 413 -> "Content Too Large";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            default -> FALLBACK_TITLE;
        };
    }

    /**
     * Reads the {@code q} parameter of one media range. An absent {@code q} means {@code 1}; a
     * {@code q} that is not a number within {@code [0, 1]}, or a parameter without {@code =}, makes
     * the range unqualified.
     */
    private static boolean qualityAboveZero(String[] parts) {
        double quality = 1.0;
        for (int index = 1; index < parts.length; index++) {
            String parameter = parts[index].strip();
            int separator = parameter.indexOf('=');
            if (separator <= 0) {
                return false;
            }
            if ("q".equalsIgnoreCase(parameter.substring(0, separator).strip())) {
                String value = parameter.substring(separator + 1).strip();
                if (!QUALITY_VALUE.matcher(value).matches()) {
                    return false;
                }
                quality = Double.parseDouble(value);
                if (quality > 1.0) {
                    return false;
                }
            }
        }
        return quality > 0.0;
    }
}
