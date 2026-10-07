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
package de.cuioss.sheriff.gateway.bff.logout;

import java.util.Objects;

/**
 * The bounded, non-sensitive vocabulary a back-channel logout rejection is recorded with.
 * <p>
 * Every member carries a fixed lower-case {@linkplain #token() token} and nothing else: no token
 * material, no {@code sub}, no {@code sid}, no raw offending value (BFF-10). The set is closed and
 * enumerated here, so {@code ApiSheriff-112} can never widen into a free-text sink — the only thing
 * a caller may put into that record's single placeholder is one of these tokens.
 * <p>
 * <strong>{@link #isRepeatable()} is the log-level discriminator.</strong> The back-channel path is
 * reserved and <em>unauthenticated</em>. A rejection is <em>repeatable</em> when a party other than
 * the configured identity provider can drive it as often as it likes: every rejection reached
 * <em>before</em> the logout token's signature has been verified — anyone who can reach the gateway
 * can cause it, without a credential, a session, or even a {@code logout_token} — and the replay
 * rejection, which anyone holding one captured, genuinely signed token can cause again and again
 * until that token leaves its freshness window. Those members are recorded through the latch in
 * {@link LogoutRejectionLog} (one {@code WARN} per member per process, {@code DEBUG} afterwards). Every
 * other member is reached once per token the identity provider actually signed, so it is recorded at
 * {@code WARN} on every occurrence.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum LogoutRejection {

    /** The active session binding holds no server-side index and cannot honour sid/sub destruction. */
    NO_IDP_DESTRUCTION_CAPABILITY("no-idp-destruction-capability", true),

    /** The request body carried no usable {@code logout_token} form parameter. */
    MISSING_LOGOUT_TOKEN("missing-logout-token", true),

    /** The logout token failed signature or structural verification at the engine seam. */
    SIGNATURE_REJECTED("signature-rejected", true),

    /** The token carries a {@code typ} header that is not {@code logout+jwt}. */
    TYPE_MISMATCH("type-mismatch", false),

    /** The token's {@code iss} is not the configured issuer. */
    ISSUER_MISMATCH("issuer-mismatch", false),

    /** The token's {@code aud} does not contain the configured client id. */
    AUDIENCE_MISMATCH("audience-mismatch", false),

    /** The token's {@code iat} is absent, or outside the symmetric freshness window. */
    IAT_OUTSIDE_WINDOW("iat-outside-window", false),

    /** The token's {@code exp} is absent, or the token has expired. */
    EXPIRED("expired", false),

    /** The token's {@code events} claim does not carry the back-channel-logout member key. */
    EVENTS_MISSING("events-missing", false),

    /** The token carries a {@code nonce}, which is prohibited in a logout token. */
    NONCE_PRESENT("nonce-present", false),

    /** The token carries neither {@code sub} nor {@code sid}, so nothing can be destroyed. */
    NO_SUB_OR_SID("no-sub-or-sid", false),

    /** The token carries no {@code jti}, so a repeat of it could not be recognised. */
    JTI_MISSING("jti-missing", false),

    /** A token with the same {@code jti} was already accepted inside its freshness window. */
    REPLAYED("replayed", true),

    /**
     * The memory of accepted {@code jti} values is at its bound and holds no expired entry, so the
     * token cannot be remembered — and a token that cannot be remembered is not acted on.
     */
    REPLAY_MEMORY_FULL("replay-memory-full", false);

    private final String token;
    private final boolean repeatable;

    LogoutRejection(String token, boolean repeatable) {
        this.token = Objects.requireNonNull(token, "token");
        this.repeatable = repeatable;
    }

    /**
     * The bounded, non-sensitive reason token this rejection is recorded with.
     *
     * @return the fixed lower-case token, never {@code null}
     */
    public String token() {
        return token;
    }

    /**
     * Whether a party other than the configured identity provider can cause this rejection
     * repeatedly: a rejection that precedes signature verification, or the replay of a captured
     * token.
     *
     * @return {@code true} when the rejection is recorded through the per-process latch,
     *         {@code false} when it is recorded at {@code WARN} on every occurrence
     */
    public boolean isRepeatable() {
        return repeatable;
    }
}
