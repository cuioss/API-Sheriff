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
package de.cuioss.sheriff.gateway.edge;

import java.util.Objects;
import java.util.Set;

/**
 * Decides whether a {@code Set-Cookie} line an upstream sent is relayed to the client.
 * <p>
 * <strong>The gateway's cookies are set by the gateway alone.</strong> The session cookie, its
 * activity cookie, the login-binding cookie and the logout-state cookie are written by the session
 * runtime and by nothing else. A relayed upstream response shares the browser's cookie jar with
 * them, so a {@code Set-Cookie} line of an upstream that names one of them is dropped; every other
 * cookie an upstream sets is relayed exactly as it was sent.
 * <p>
 * <strong>How a line is matched.</strong> The cookie name is read the way a user agent reads it
 * (RFC 6265bis §5.6): the text before the first {@code =} of the name-value pair — the pair being
 * the line up to its first {@code ;} — with surrounding spaces and tabs removed. A line is dropped
 * when
 * <ul>
 *   <li>that name equals a gateway cookie name, compared case-sensitively as a browser stores it;
 *       the {@code __Host-} and {@code __Secure-} prefixes alone are compared without regard to
 *       case, because a user agent recognises them that way; or</li>
 *   <li>the name is empty and the value begins with a gateway cookie name followed by {@code =}: a
 *       nameless cookie is sent back as its bare value, which a server then reads as that name.</li>
 * </ul>
 * An instance built from an empty name set relays every line.
 * <p>
 * Immutable and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class UpstreamSetCookieFilter {

    private static final String SET_COOKIE = "Set-Cookie";
    private static final String[] CASE_INSENSITIVE_PREFIXES = {"__Host-", "__Secure-"};

    private final Set<String> gatewayCookieNames;

    /**
     * @param gatewayCookieNames the names of the cookies only the gateway may set; empty when the
     *                           gateway sets none
     */
    UpstreamSetCookieFilter(Set<String> gatewayCookieNames) {
        this.gatewayCookieNames = Set.copyOf(Objects.requireNonNull(gatewayCookieNames, "gatewayCookieNames"));
    }

    /**
     * @param headerName  the upstream response-header name
     * @param headerValue the upstream response-header value
     * @return {@code false} exactly when the header is a {@code Set-Cookie} line naming a gateway
     *         cookie; {@code true} for every other header, which this filter does not judge
     */
    boolean relays(String headerName, String headerValue) {
        if (gatewayCookieNames.isEmpty() || !SET_COOKIE.equalsIgnoreCase(headerName)) {
            return true;
        }
        return !namesGatewayCookie(headerValue);
    }

    private boolean namesGatewayCookie(String setCookieLine) {
        int attributes = setCookieLine.indexOf(';');
        String pair = attributes < 0 ? setCookieLine : setCookieLine.substring(0, attributes);
        int separator = pair.indexOf('=');
        String name = separator < 0 ? "" : trim(pair.substring(0, separator));
        String value = trim(separator < 0 ? pair : pair.substring(separator + 1));
        for (String owned : gatewayCookieNames) {
            if (name.isEmpty() ? startsWithName(value, owned) : sameName(name, owned)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a nameless cookie's value would be read back as the cookie {@code owned}. */
    private static boolean startsWithName(String value, String owned) {
        int length = owned.length();
        return value.length() > length && value.charAt(length) == '=' && sameName(value.substring(0, length), owned);
    }

    private static boolean sameName(String candidate, String owned) {
        if (candidate.length() != owned.length()) {
            return false;
        }
        for (String prefix : CASE_INSENSITIVE_PREFIXES) {
            int length = prefix.length();
            if (owned.startsWith(prefix)) {
                return candidate.regionMatches(true, 0, prefix, 0, length)
                        && candidate.regionMatches(length, owned, length, owned.length() - length);
            }
        }
        return candidate.equals(owned);
    }

    /** Removes the surrounding spaces and horizontal tabs a user agent ignores around a name or value. */
    private static String trim(String text) {
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
