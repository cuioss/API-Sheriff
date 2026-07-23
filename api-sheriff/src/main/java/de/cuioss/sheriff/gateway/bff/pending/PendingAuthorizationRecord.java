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

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import de.cuioss.sheriff.token.client.flow.FlowContext;

import lombok.Builder;

/**
 * The gateway-side transaction record for a browser's in-flight auth-code login (D2b).
 * <p>
 * The engine's {@link FlowContext} <em>is</em> the OIDC transaction DTO — it owns the
 * {@code state} (32-byte SecureRandom), {@code nonce}, PKCE verifier ({@code S256}), and
 * {@code redirect_uri}, and verifies {@code state}/{@code nonce} constant-time on callback.
 * What the engine deliberately does not provide — and what this record adds — is the
 * gateway's job: <em>persistence</em>, a <em>short fixed TTL</em>, <em>single-use
 * enforcement</em> (the engine's single-use is a caller contract, not enforced by the type),
 * and a <em>same-origin-validated post-login return URL</em>. The record is therefore a thin
 * wrapper that never re-invents any engine control.
 * <p>
 * Single-use is enforced by {@link PendingAuthorizationStore} (which removes the record on
 * consumption), so this record stays immutable and carries no mutable consumed flag. The
 * unguessable {@link #id()} is the store key and the value carried by the browser-binding
 * cookie ({@link BindingCookieCodec}); a callback is valid only when both the returned
 * {@code state} matches and the binding cookie resolves to this same record.
 *
 * @param id          the unguessable record id (store key and binding-cookie value)
 * @param flowContext the engine transaction DTO owning {@code state}/{@code nonce}/PKCE
 * @param returnUrl   the same-origin-validated post-login redirect target
 * @param createdAt   the instant the record was created (TTL anchor)
 * @param ttl         the short fixed lifetime before the record expires
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record PendingAuthorizationRecord(String id, FlowContext flowContext, String returnUrl, Instant createdAt,
        Duration ttl) {

    /**
     * The short fixed lifetime a pending-authorization record lives before it expires. Fixed
     * (not operator-configurable): an unauthenticated browser creates these, so the window is
     * a security parameter, not a tuning knob.
     */
    public static final Duration FIXED_TTL = Duration.ofMinutes(5);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int ID_BYTES = 32;

    /**
     * Canonical constructor rejecting any absent component — every field is mandatory.
     */
    public PendingAuthorizationRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(flowContext, "flowContext");
        Objects.requireNonNull(returnUrl, "returnUrl");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(ttl, "ttl");
    }

    /**
     * Creates a record with a freshly generated unguessable id and the {@link #FIXED_TTL}.
     *
     * @param flowContext the engine transaction DTO
     * @param returnUrl   the already same-origin-validated post-login redirect target
     * @param createdAt   the creation instant (TTL anchor)
     * @return a new pending-authorization record
     */
    public static PendingAuthorizationRecord create(FlowContext flowContext, String returnUrl, Instant createdAt) {
        return new PendingAuthorizationRecord(newId(), flowContext, returnUrl, createdAt, FIXED_TTL);
    }

    /**
     * Generates a 256-bit URL-safe unguessable record id.
     *
     * @return the base64url (unpadded) encoding of 32 secure-random bytes
     */
    public static String newId() {
        byte[] bytes = new byte[ID_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * @return the instant this record expires ({@code createdAt + ttl})
     */
    public Instant expiresAt() {
        return createdAt.plus(ttl);
    }

    /**
     * Whether this record has expired at {@code now} — expiry is inclusive of the boundary.
     *
     * @param now the reference instant
     * @return {@code true} when {@code now} is at or after {@link #expiresAt()}
     */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt());
    }

    /**
     * Whether {@code returnUrl} is safe to redirect a browser to after login: a gateway-relative
     * path ({@code /...}), or an absolute URL whose origin (scheme + host + port) matches
     * {@code gatewayOrigin}. A schema-relative ({@code //host}) value, a cross-origin absolute
     * URL, a blank value, or an unparseable value is rejected — the post-login redirect is never
     * an open redirect.
     *
     * @param returnUrl     the candidate post-login redirect target (may be absent/blank)
     * @param gatewayOrigin the gateway's own origin (e.g. the {@code redirect_uri} origin)
     * @return {@code true} only when the candidate is same-origin with the gateway
     */
    public static boolean sameOrigin(@Nullable String returnUrl, String gatewayOrigin) {
        if (returnUrl == null || returnUrl.isBlank() || returnUrl.startsWith("//")) {
            return false;
        }
        if (returnUrl.startsWith("/")) {
            return true;
        }
        try {
            return sameOriginAbsolute(URI.create(returnUrl), URI.create(gatewayOrigin));
        } catch (IllegalArgumentException unparseable) {
            return false;
        }
    }

    private static boolean sameOriginAbsolute(URI candidate, URI reference) {
        if (candidate.getScheme() == null || candidate.getHost() == null) {
            return false;
        }
        return candidate.getScheme().equalsIgnoreCase(reference.getScheme())
                && candidate.getHost().equalsIgnoreCase(reference.getHost())
                && effectivePort(candidate) == effectivePort(reference);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme)) {
            return 443;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            return 80;
        }
        return -1;
    }
}
