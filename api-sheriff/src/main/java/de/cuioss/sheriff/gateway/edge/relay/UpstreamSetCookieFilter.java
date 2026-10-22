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
package de.cuioss.sheriff.gateway.edge.relay;

import java.util.Objects;

import de.cuioss.sheriff.gateway.config.model.CookieAllow;
import de.cuioss.sheriff.gateway.config.model.CookiesConfig;
import de.cuioss.sheriff.gateway.http.GatewayCookies;
import org.jspecify.annotations.Nullable;

/**
 * Decides which {@code Set-Cookie} lines an upstream sent are relayed to the client, and in what
 * form — by the route's {@code cookies} policy.
 * <p>
 * <strong>Which lines are relayed.</strong>
 * <ul>
 *   <li><strong>The gateway's cookies are set by the gateway alone</strong>, under every mode. Every
 *       cookie under the reserved {@value GatewayCookies#RESERVED_PREFIX} prefix, the configured
 *       session cookie and, in cookie mode, its activity cookie are written by the gateway and by
 *       nothing else — with or without a session runtime. A relayed upstream response shares the
 *       browser's cookie jar with them, so an upstream line naming one of them is dropped.</li>
 *   <li>{@code allow: none} — the default when no scope declares a {@code cookies} block — drops
 *       every line.</li>
 *   <li>A list of names relays only the lines setting a listed cookie, compared exactly and
 *       case-sensitively. A nameless line is never listed.</li>
 *   <li>{@code allow: all} relays every remaining line.</li>
 * </ul>
 * <strong>How a relayed line is shaped.</strong> Its {@code Domain} attribute is removed — every
 * occurrence, the attribute name compared without regard to case — so the cookie stays a host-only
 * cookie of the gateway's own origin and never widens to a parent domain the upstream named. The
 * name-value pair, {@code Path} and every other attribute are kept exactly as sent; only the
 * attributes after the pair are read, so a cookie value holding the text {@code Domain=} is left
 * untouched.
 * <p>
 * <strong>How a line is matched</strong> is the user-agent reading {@link GatewayCookies} owns: the
 * name before the first {@code =} of the pair, the pair being the text up to the first {@code ;},
 * spaces and tabs around it removed. A line without {@code =} in its pair is a nameless cookie;
 * {@link GatewayCookies} counts it as the gateway cookie its value names.
 * <p>
 * Immutable and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class UpstreamSetCookieFilter {

    private static final String SET_COOKIE = "Set-Cookie";

    private static final String DOMAIN_ATTRIBUTE = "Domain";

    private static final char ATTRIBUTE_SEPARATOR = ';';

    private static final char NAME_VALUE_SEPARATOR = '=';

    private final GatewayCookies gatewayCookies;

    /**
     * @param gatewayCookies the gateway's own cookies, which no upstream may set
     */
    UpstreamSetCookieFilter(GatewayCookies gatewayCookies) {
        this.gatewayCookies = Objects.requireNonNull(gatewayCookies, "gatewayCookies");
    }

    /**
     * Judges one upstream response header under the route's {@code cookies} policy.
     *
     * @param headerName  the upstream response-header name
     * @param headerValue the upstream response-header value
     * @param policy      the route's effective {@code cookies} block
     * @return the value to relay — a {@code Set-Cookie} line with its {@code Domain} attribute
     *         removed, or any other header's value unchanged, since this filter judges
     *         {@code Set-Cookie} lines only — or {@code null} when the line is dropped
     */
    @Nullable
    String relayedValue(String headerName, String headerValue, CookiesConfig policy) {
        Objects.requireNonNull(headerName, "headerName");
        Objects.requireNonNull(headerValue, "headerValue");
        Objects.requireNonNull(policy, "policy");
        if (!SET_COOKIE.equalsIgnoreCase(headerName)) {
            return headerValue;
        }
        if (!policy.forwardsAny() || gatewayCookies.namesGatewayCookie(headerValue)) {
            return null;
        }
        if (!admits(policy.allow(), cookieName(headerValue))) {
            return null;
        }
        return withoutDomain(headerValue);
    }

    /**
     * Whether the mode admits a line that names no gateway cookie: {@code all} admits every line, a
     * list only a line whose name it lists — a nameless line is never listed.
     */
    private static boolean admits(CookieAllow allow, String cookieName) {
        if (cookieName.isEmpty()) {
            return allow instanceof CookieAllow.All;
        }
        return allow.admits(cookieName);
    }

    /**
     * The cookie name of a {@code Set-Cookie} line: the text before the first {@code =} of its pair,
     * spaces and tabs around it removed; empty for a nameless line.
     */
    private static String cookieName(String setCookieLine) {
        int attributes = setCookieLine.indexOf(ATTRIBUTE_SEPARATOR);
        String pair = attributes < 0 ? setCookieLine : setCookieLine.substring(0, attributes);
        int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
        return separator < 0 ? "" : trimBlanks(pair.substring(0, separator));
    }

    /**
     * Removes every {@code Domain} attribute of a {@code Set-Cookie} line, keeping the pair and every
     * other attribute exactly as sent.
     */
    private static String withoutDomain(String setCookieLine) {
        int attributes = setCookieLine.indexOf(ATTRIBUTE_SEPARATOR);
        if (attributes < 0) {
            return setCookieLine;
        }
        StringBuilder relayed = new StringBuilder(setCookieLine.length())
                .append(setCookieLine, 0, attributes);
        int start = attributes + 1;
        while (start <= setCookieLine.length()) {
            int end = setCookieLine.indexOf(ATTRIBUTE_SEPARATOR, start);
            if (end < 0) {
                end = setCookieLine.length();
            }
            String attribute = setCookieLine.substring(start, end);
            if (!isDomain(attribute)) {
                relayed.append(ATTRIBUTE_SEPARATOR).append(attribute);
            }
            start = end + 1;
        }
        return relayed.toString();
    }

    /** Whether an attribute — the text between two {@code ;} — is a {@code Domain} attribute. */
    private static boolean isDomain(String attribute) {
        int separator = attribute.indexOf(NAME_VALUE_SEPARATOR);
        String name = separator < 0 ? attribute : attribute.substring(0, separator);
        return DOMAIN_ATTRIBUTE.equalsIgnoreCase(trimBlanks(name));
    }

    /** Removes the spaces and horizontal tabs a user agent ignores around a cookie name or attribute name. */
    private static String trimBlanks(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isBlank(text.charAt(start))) {
            start++;
        }
        while (end > start && isBlank(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isBlank(char character) {
        return character == ' ' || character == '\t';
    }
}
