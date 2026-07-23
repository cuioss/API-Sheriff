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
package de.cuioss.sheriff.gateway.bff.session;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Encodes and reads the opaque server-session cookie (D3).
 * <p>
 * The cookie carries <strong>only</strong> the opaque {@link SessionRecord#sessionId()} — never
 * any token material, which stays server-side. It is hardened by construction: the
 * {@code __Host-} prefix (browser-honoured only with {@code Secure} + {@code Path=/} + no
 * {@code Domain}), {@code HttpOnly} (no script access), and {@code SameSite=Lax} (survives a
 * top-level navigation while blocking cross-site sends). The cookie name is operator-configurable
 * ({@code session.cookie_name}); {@link #DEFAULT_COOKIE_NAME} is the {@code __Host-} default.
 * <p>
 * The codec is framework-agnostic: it produces and parses raw header values with no JAX-RS/Vert.x
 * coupling, so it is unit-testable without a container.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SessionCookieCodec {

    /** The default {@code __Host-}-prefixed session-cookie name. */
    public static final String DEFAULT_COOKIE_NAME = "__Host-sheriff-session";

    private final String cookieName;
    private final Duration maxAge;

    /**
     * Creates a codec for the given cookie name and lifetime.
     *
     * @param cookieName the cookie name (normally {@link #DEFAULT_COOKIE_NAME} or the configured
     *                   {@code session.cookie_name})
     * @param maxAge     the cookie {@code Max-Age}
     */
    public SessionCookieCodec(String cookieName, Duration maxAge) {
        this.cookieName = requireNonBlank(cookieName);
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
    }

    /**
     * Builds the {@code Set-Cookie} header value carrying the opaque {@code sessionId}.
     *
     * @param sessionId the opaque session id
     * @return the hardened {@code Set-Cookie} header value
     */
    public String toSetCookieHeader(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        return "%s=%s; Max-Age=%d; Path=/; Secure; HttpOnly; SameSite=Lax"
                .formatted(cookieName, sessionId, maxAge.toSeconds());
    }

    /**
     * Builds the {@code Set-Cookie} header value that clears the session cookie (logout).
     *
     * @return the clearing {@code Set-Cookie} header value
     */
    public String toClearingSetCookieHeader() {
        return cookieName + "=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax";
    }

    /**
     * Reads the opaque session id out of a request {@code Cookie} header value.
     *
     * @param cookieHeader the raw {@code Cookie} header value (may be absent/blank)
     * @return the session id when the session cookie is present with a non-empty value; empty otherwise
     */
    public Optional<String> readSessionId(@Nullable String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && cookieName.equals(trimmed.substring(0, equals))) {
                String value = trimmed.substring(equals + 1);
                return value.isEmpty() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static String requireNonBlank(String cookieName) {
        Objects.requireNonNull(cookieName, "cookieName");
        if (cookieName.isBlank()) {
            throw new IllegalArgumentException("cookieName must not be blank");
        }
        return cookieName;
    }
}
