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
package de.cuioss.sheriff.gateway.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.cuioss.sheriff.gateway.bff.cookie.SessionActivityCookieCodec;
import de.cuioss.sheriff.gateway.bff.session.SessionCookieCodec;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;

/**
 * The one definition of the gateway's own cookies — the cookies API Sheriff sets and nothing else
 * may set, read or receive.
 * <p>
 * <strong>What it covers.</strong>
 * <ul>
 *   <li>Every cookie under the reserved prefix {@value #RESERVED_PREFIX}, always — on a gateway with
 *       or without a BFF. The login-binding cookie, the logout-state cookie and the default session
 *       cookie live under it, and so does any gateway cookie added later: it is covered by
 *       construction, not by a second list someone has to remember to extend.</li>
 *   <li>The configured {@code oidc.session.cookie_name} ({@link SessionCookieCodec#DEFAULT_COOKIE_NAME}
 *       when the key is omitted), because an operator may name the session cookie outside the
 *       prefix.</li>
 *   <li>In {@code cookie} session mode, the activity cookie of that session cookie — its name plus
 *       {@link SessionActivityCookieCodec#COOKIE_NAME_SUFFIX}.</li>
 * </ul>
 * The definition is derived from the configuration, not from the assembled session runtime, so the
 * pre-boot validation rules can read it before any runtime exists; the boot rules, the request
 * direction and the response direction all consume this one instance of the rule.
 * <p>
 * <strong>How a cookie name is matched.</strong> A name is read the way a user agent reads it
 * (RFC 6265bis §5.6): the text before the first {@code =} of the name-value pair — the pair being the
 * text up to its first {@code ;} — with surrounding spaces and tabs removed. The {@code __Host-} and
 * {@code __Secure-} prefixes are compared without regard to case, because a user agent recognises
 * them that way; the rest of the name is compared case-sensitively, as a browser stores it. A nameless
 * cookie — a pair whose name is empty — is sent back by the browser as its bare value, which a server
 * then reads as a cookie of its own: such a crumb counts as the gateway cookie its value names before
 * its first {@code =}.
 * <p>
 * Usage:
 * {@snippet :
 * GatewayCookies gatewayCookies = GatewayCookies.of(gatewayConfig);
 * boolean dropped = gatewayCookies.namesGatewayCookie("__Host-sheriff-session=forged; Path=/");
 * }
 * <p>
 * Framework-free, immutable and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class GatewayCookies {

    /** The prefix every cookie the gateway sets under its own naming carries. */
    public static final String RESERVED_PREFIX = "__Host-sheriff-";

    private static final String[] CASE_INSENSITIVE_PREFIXES = {"__Host-", "__Secure-"};

    /** The gateway cookie names that may lie outside {@link #RESERVED_PREFIX}. */
    private final List<String> names;

    private GatewayCookies(List<String> names) {
        this.names = List.copyOf(names);
    }

    /**
     * Derives the gateway's own cookies from the bound configuration.
     *
     * @param gateway the bound gateway document
     * @return the definition: the reserved prefix, the configured session cookie and, in
     *         {@code cookie} session mode, its activity cookie
     */
    public static GatewayCookies of(GatewayConfig gateway) {
        Objects.requireNonNull(gateway, "gateway");
        OidcConfig oidc = gateway.oidc();
        OidcConfig.Session session = oidc == null ? null : oidc.session();
        String declaredName = session == null ? null : session.cookieName();
        String sessionCookieName = declaredName == null ? SessionCookieCodec.DEFAULT_COOKIE_NAME : declaredName;
        List<String> names = new ArrayList<>(2);
        names.add(sessionCookieName);
        if (session != null && session.isCookieMode()) {
            names.add(sessionCookieName + SessionActivityCookieCodec.COOKIE_NAME_SUFFIX);
        }
        return new GatewayCookies(names);
    }

    /**
     * @param cookieName a cookie name, already read off its pair
     * @return {@code true} when the name is one of the gateway's own cookies
     */
    public boolean matchesName(String cookieName) {
        Objects.requireNonNull(cookieName, "cookieName");
        if (underReservedPrefix(cookieName)) {
            return true;
        }
        for (String owned : names) {
            if (sameName(cookieName, owned)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Judges one cookie pair — a crumb of a request {@code Cookie} header, or the pair of a
     * {@code Set-Cookie} line.
     *
     * @param name  the pair's name, spaces and tabs around it already removed; empty for a nameless
     *              cookie
     * @param value the pair's value, spaces and tabs around it already removed
     * @return {@code true} when the pair is one of the gateway's own cookies, a nameless crumb whose
     *         value would be read back as one included
     */
    public boolean matchesPair(String name, String value) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        if (!name.isEmpty()) {
            return matchesName(name);
        }
        int separator = value.indexOf('=');
        return separator >= 0 && matchesName(value.substring(0, separator));
    }

    /**
     * @param setCookieLine the value of one {@code Set-Cookie} header line
     * @return {@code true} when the line sets one of the gateway's own cookies
     */
    public boolean namesGatewayCookie(String setCookieLine) {
        Objects.requireNonNull(setCookieLine, "setCookieLine");
        int attributes = setCookieLine.indexOf(';');
        String pair = attributes < 0 ? setCookieLine : setCookieLine.substring(0, attributes);
        int separator = pair.indexOf('=');
        String name = separator < 0 ? "" : trimBlanks(pair.substring(0, separator));
        String value = trimBlanks(separator < 0 ? pair : pair.substring(separator + 1));
        return matchesPair(name, value);
    }

    /**
     * Removes the spaces and horizontal tabs a user agent ignores around a cookie pair, name or value,
     * the trimming this class reads a pair with — shared with the request-direction rebuild of the
     * {@code Cookie} header so both read a pair alike.
     *
     * @param text the text to trim
     * @return {@code text} without its leading and trailing spaces and horizontal tabs
     */
    public static String trimBlanks(String text) {
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

    private static boolean underReservedPrefix(String candidate) {
        return candidate.length() >= RESERVED_PREFIX.length()
                && sameName(candidate.substring(0, RESERVED_PREFIX.length()), RESERVED_PREFIX);
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

    private static boolean isBlank(char character) {
        return character == ' ' || character == '\t';
    }
}
