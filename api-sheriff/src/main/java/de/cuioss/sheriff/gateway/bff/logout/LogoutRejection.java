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
 * <strong>Every member is recorded the same way.</strong> A rejected request can be presented again,
 * whichever check refused it — only an <em>accepted</em> token is remembered by
 * {@link LogoutTokenReplayGuard}. Each member is therefore recorded through the latch in
 * {@link LogoutRejectionLog}: one {@code WARN} per member per process, {@code DEBUG} afterwards.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum LogoutRejection {

    /** The active session binding holds no server-side index and cannot honour sid/sub destruction. */
    NO_IDP_DESTRUCTION_CAPABILITY("no-idp-destruction-capability"),

    /** The request body carried no usable {@code logout_token} form parameter. */
    MISSING_LOGOUT_TOKEN("missing-logout-token"),

    /** The logout token failed signature or structural verification at the engine seam. */
    SIGNATURE_REJECTED("signature-rejected"),

    /** The token carries a {@code typ} header that is not {@code logout+jwt}. */
    TYPE_MISMATCH("type-mismatch"),

    /** The token's {@code iss} is not the configured issuer. */
    ISSUER_MISMATCH("issuer-mismatch"),

    /** The token's {@code aud} does not contain the configured client id. */
    AUDIENCE_MISMATCH("audience-mismatch"),

    /** The token's {@code iat} is absent, or outside the symmetric freshness window. */
    IAT_OUTSIDE_WINDOW("iat-outside-window"),

    /** The token's {@code exp} is absent, or the token has expired. */
    EXPIRED("expired"),

    /** The token's {@code events} claim does not carry the back-channel-logout member key. */
    EVENTS_MISSING("events-missing"),

    /** The token carries a {@code nonce}, which is prohibited in a logout token. */
    NONCE_PRESENT("nonce-present"),

    /** The token carries neither {@code sub} nor {@code sid}, so nothing can be destroyed. */
    NO_SUB_OR_SID("no-sub-or-sid"),

    /** The token carries no {@code jti}, so a repeat of it could not be recognised. */
    JTI_MISSING("jti-missing"),

    /** A token with the same {@code jti} was already accepted inside its freshness window. */
    REPLAYED("replayed"),

    /**
     * The memory of accepted {@code jti} values is at its bound and holds no expired entry, so the
     * token cannot be remembered — and a token that cannot be remembered is not acted on.
     */
    REPLAY_MEMORY_FULL("replay-memory-full");

    private final String token;

    LogoutRejection(String token) {
        this.token = Objects.requireNonNull(token, "token");
    }

    /**
     * The bounded, non-sensitive reason token this rejection is recorded with.
     *
     * @return the fixed lower-case token, never {@code null}
     */
    public String token() {
        return token;
    }
}
