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
package de.cuioss.sheriff.gateway.bff.pending;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;


import de.cuioss.sheriff.token.client.flow.FlowContext;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The gateway-side transaction record for a browser's in-flight auth-code flow (D2b).
 * <p>
 * The engine's {@link FlowContext} <em>is</em> the OIDC transaction DTO — it owns the
 * {@code state} (32-byte SecureRandom), {@code nonce}, PKCE verifier ({@code S256}), and
 * {@code redirect_uri}, and verifies {@code state}/{@code nonce} constant-time on callback.
 * What the engine deliberately does not provide — and what this record adds — is the
 * gateway's job: <em>persistence</em>, a <em>short fixed TTL</em>, <em>single-use
 * enforcement</em> (the engine's single-use is a caller contract, not enforced by the type),
 * and a <em>same-origin-validated return URL</em>. The record is therefore a thin
 * wrapper that never re-invents any engine control.
 * <p>
 * Single-use is enforced by {@link PendingAuthorizationStore} (which removes the record on
 * consumption), so this record stays immutable and carries no mutable consumed flag. The
 * unguessable {@link #id()} is the store key and the value carried by the browser-binding
 * cookie ({@link BindingCookieCodec}); a callback is valid only when both the returned
 * {@code state} matches and the binding cookie resolves to this same record.
 * <p>
 * The record also carries the scope set the authorization request asked for. The callback falls back
 * to it when the issued access token carries no {@code scope} claim, so a session always knows which
 * scope set to refresh with.
 * <p>
 * <strong>Login versus widening.</strong> A record created by {@link #create} is a plain login: its
 * {@link #widening()} is {@code null} and its callback mints a new session. A record created by
 * {@link #createWidening} belongs to a live session widening its scopes: it carries the live
 * session's {@code sub} and the {@linkplain Widening.Attempt attempt} it was issued for, and its
 * callback merges into that live session instead of minting one.
 *
 * @param id              the unguessable record id (store key and binding-cookie value)
 * @param flowContext     the engine transaction DTO owning {@code state}/{@code nonce}/PKCE
 * @param returnUrl       the same-origin-validated redirect target
 * @param requestedScopes the scope set the authorization request carried in its {@code scope}
 *                        parameter
 * @param createdAt       the instant the record was created (TTL anchor)
 * @param ttl             the short fixed lifetime before the record expires
 * @param widening        the widening marker, or {@code null} for a plain login
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record PendingAuthorizationRecord(
String id,
FlowContext flowContext,
String returnUrl,
Set<String> requestedScopes,
Instant createdAt,
Duration ttl,
@Nullable Widening widening) {

    /**
     * The short fixed lifetime a pending-authorization record lives before it expires. Fixed
     * (not operator-configurable): an unauthenticated browser creates these, so the window is
     * a security parameter, not a tuning knob.
     */
    public static final Duration FIXED_TTL = Duration.ofMinutes(5);

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int ID_BYTES = 32;

    /**
     * Canonical constructor rejecting any absent mandatory component — every field except
     * {@code widening} is mandatory — and defensively copying {@code requestedScopes} into an
     * immutable set.
     *
     * @throws NullPointerException when a mandatory component is {@code null}, or
     *                              {@code requestedScopes} contains a {@code null} element
     */
    public PendingAuthorizationRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(flowContext, "flowContext");
        Objects.requireNonNull(returnUrl, "returnUrl");
        requestedScopes = Set.copyOf(Objects.requireNonNull(requestedScopes, "requestedScopes"));
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(ttl, "ttl");
    }

    /**
     * Creates a record with a freshly generated unguessable id and the {@link #FIXED_TTL}.
     *
     * @param flowContext     the engine transaction DTO
     * @param returnUrl       the already same-origin-validated post-login redirect target
     * @param requestedScopes the scope set the authorization request carried; duplicates collapse
     * @param createdAt       the creation instant (TTL anchor)
     * @return a new plain-login pending-authorization record ({@link #widening()} is {@code null})
     */
    public static PendingAuthorizationRecord create(FlowContext flowContext, String returnUrl,
            Collection<String> requestedScopes, Instant createdAt) {
        Objects.requireNonNull(requestedScopes, "requestedScopes");
        return new PendingAuthorizationRecord(newId(), flowContext, returnUrl, Set.copyOf(requestedScopes),
                createdAt, FIXED_TTL, null);
    }

    /**
     * Creates a widening record for a live session, with a freshly generated unguessable id and the
     * {@link #FIXED_TTL}.
     *
     * @param flowContext     the engine transaction DTO
     * @param returnUrl       the already same-origin-validated redirect target after the widening
     * @param requestedScopes the scope set the widening authorization request carried; duplicates
     *                        collapse
     * @param sub             the live session's subject, the identity the callback must land on
     * @param attempt         the attempt this authorization request was issued for
     * @param createdAt       the creation instant (TTL anchor)
     * @return a new widening pending-authorization record
     */
    public static PendingAuthorizationRecord createWidening(FlowContext flowContext, String returnUrl,
            Collection<String> requestedScopes, String sub, Widening.Attempt attempt, Instant createdAt) {
        Objects.requireNonNull(requestedScopes, "requestedScopes");
        return new PendingAuthorizationRecord(newId(), flowContext, returnUrl, Set.copyOf(requestedScopes),
                createdAt, FIXED_TTL, new Widening(sub, attempt));
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
     * Whether {@code returnUrl} is safe to redirect a browser to: a gateway-relative
     * path ({@code /...}), or an absolute URL whose origin (scheme + host + port) matches
     * {@code gatewayOrigin}. A schema-relative ({@code //host}) value, a backslash-authority
     * ({@code /\host}, which browsers normalize to {@code //host}) value, a value carrying any
     * control character ({@code /\t/host}, which browsers strip to {@code //host}), a cross-origin
     * absolute URL, a blank value, or an unparseable value is rejected — the redirect is
     * never an open redirect.
     *
     * @param returnUrl     the candidate redirect target (may be absent/blank)
     * @param gatewayOrigin the gateway's own origin (e.g. the {@code redirect_uri} origin)
     * @return {@code true} only when the candidate is same-origin with the gateway
     */
    public static boolean sameOrigin(@Nullable String returnUrl, String gatewayOrigin) {
        if (returnUrl == null || returnUrl.isBlank() || returnUrl.startsWith("//")) {
            return false;
        }
        // Browsers normalize a backslash to a forward slash, so /\evil.com or \\evil.com can be
        // coerced into a protocol-relative //evil.com open redirect. A legitimate return URL — a
        // gateway-relative path or an absolute gateway URL — never carries a raw backslash, so any
        // backslash is rejected outright (closes /\evil.com, /\/evil.com, \evil.com).
        if (returnUrl.indexOf('\\') >= 0) {
            return false;
        }
        // The WHATWG URL parser removes every ASCII tab and newline from a Location value before
        // parsing it, so /<TAB>/evil.com (a decoded ?returnUrl=/%09/evil.com) would pass the checks
        // above yet land as the protocol-relative //evil.com. A legitimate return URL carries no raw
        // control character (the gateway-built one is a canonical path plus a still-encoded query),
        // so any control character is rejected outright.
        if (returnUrl.chars().anyMatch(Character::isISOControl)) {
            return false;
        }
        if (returnUrl.startsWith("/")) {
            return true;
        }
        try {
            return sameOriginAbsolute(URI.create(returnUrl), URI.create(gatewayOrigin));
        } catch (IllegalArgumentException _) {
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

    /**
     * The marker that turns a pending record into a live-session widening: the identity the callback
     * must merge into and the attempt the authorization request was issued for.
     *
     * @param sub     the live session's subject; the callback refuses a grant for any other subject
     * @param attempt the attempt this authorization request was issued for
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Widening(String sub, Attempt attempt) {

        /**
         * Canonical constructor rejecting an absent component.
         *
         * @throws NullPointerException when a component is {@code null}
         */
        public Widening {
            Objects.requireNonNull(sub, "sub");
            Objects.requireNonNull(attempt, "attempt");
        }

        /**
         * The attempt a widening authorization request was issued for. A widening starts
         * {@link #SILENT}; an IdP answer that needs interaction re-drives exactly one
         * {@link #INTERACTIVE} attempt, and any refusal of that attempt is terminal.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        public enum Attempt {

            /** The {@code prompt=none} attempt: the IdP may answer only from its own SSO session. */
            SILENT,

            /** The single interactive attempt that follows a silent attempt needing interaction. */
            INTERACTIVE
        }
    }
}
