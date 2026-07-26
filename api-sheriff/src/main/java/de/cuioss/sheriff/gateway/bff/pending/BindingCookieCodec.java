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
package de.cuioss.sheriff.gateway.bff.pending;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Encodes and reads the short-lived browser-binding cookie that ties a
 * {@link PendingAuthorizationRecord} to the browser that started the login (D2b).
 * <p>
 * The cookie carries only the unguessable record id (server mode). It is set at redirect time
 * and consulted at callback time: a callback is valid only when both the returned {@code state}
 * matches and this cookie resolves to the same record, so a callback replayed in a different
 * browser (which carries no binding cookie) is rejected even with a valid {@code state} — the
 * pre-session analogue of the session cookie.
 * <p>
 * The cookie is hardened by construction: the {@code __Host-} prefix (which the browser only
 * honours with {@code Secure} + {@code Path=/} + no {@code Domain}), plus {@code HttpOnly} (no
 * script access) and {@code SameSite=Lax} (survives the top-level IdP redirect while blocking
 * cross-site sends). The codec is framework-agnostic: it produces and parses raw header values,
 * so it carries no JAX-RS/Vert.x coupling and is unit-testable without a container.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BindingCookieCodec {

    /** The {@code __Host-}-prefixed browser-binding cookie name. */
    public static final String COOKIE_NAME = "__Host-sheriff-binding";

    private final Duration maxAge;

    /**
     * Creates a codec whose set-cookie output carries the given lifetime — normally
     * {@link PendingAuthorizationRecord#FIXED_TTL}, so the cookie and the record expire together.
     *
     * @param maxAge the cookie {@code Max-Age}
     */
    public BindingCookieCodec(Duration maxAge) {
        this.maxAge = Objects.requireNonNull(maxAge, "maxAge");
    }

    /**
     * Builds the {@code Set-Cookie} header value binding the browser to {@code recordId}.
     *
     * @param recordId the pending-authorization record id
     * @return the hardened {@code Set-Cookie} header value
     */
    public String toSetCookieHeader(String recordId) {
        Objects.requireNonNull(recordId, "recordId");
        return "%s=%s; Max-Age=%d; Path=/; Secure; HttpOnly; SameSite=Lax"
                .formatted(COOKIE_NAME, recordId, maxAge.toSeconds());
    }

    /**
     * Builds the {@code Set-Cookie} header value that clears the binding cookie (single-use:
     * expire it once the callback has consumed the record).
     *
     * @return the clearing {@code Set-Cookie} header value
     */
    public String toClearingSetCookieHeader() {
        return COOKIE_NAME + "=; Max-Age=0; Path=/; Secure; HttpOnly; SameSite=Lax";
    }

    /**
     * Reads the binding record id out of a request {@code Cookie} header value.
     *
     * @param cookieHeader the raw {@code Cookie} header value (may be absent/blank)
     * @return the record id when the binding cookie is present with a non-empty value; empty otherwise
     */
    public Optional<String> readRecordId(@Nullable String cookieHeader) {
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return Optional.empty();
        }
        for (String pair : cookieHeader.split(";")) {
            String trimmed = pair.trim();
            int equals = trimmed.indexOf('=');
            if (equals > 0 && COOKIE_NAME.equals(trimmed.substring(0, equals))) {
                String value = trimmed.substring(equals + 1);
                return value.isEmpty() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
