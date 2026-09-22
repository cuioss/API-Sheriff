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
 * <strong>{@link #isSignatureVerified()} is the log-level discriminator, not a description.</strong>
 * The back-channel path is reserved and <em>unauthenticated</em>, so a rejection reached <em>before</em>
 * the logout token's signature has been verified is attacker-triggerable: anyone who can reach the
 * gateway can drive it, without a credential, a session, or even a {@code logout_token}. Those members
 * report {@code false} and are recorded through the latch in {@link LogoutRejectionLog} (one
 * {@code WARN} per member per process, {@code DEBUG} afterwards). The members reporting {@code true}
 * are reachable only by a token the configured identity provider actually signed, so they are recorded
 * at {@code WARN} unconditionally — an attacker cannot reach them at all.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum LogoutRejection {

    /** The active session binding holds no server-side index and cannot honour sid/sub destruction. */
    NO_IDP_DESTRUCTION_CAPABILITY("no-idp-destruction-capability", false),

    /** The request body carried no usable {@code logout_token} form parameter. */
    MISSING_LOGOUT_TOKEN("missing-logout-token", false),

    /** The logout token failed signature or structural verification at the engine seam. */
    SIGNATURE_REJECTED("signature-rejected", false),

    /** The token's {@code iss} is not the configured issuer. */
    ISSUER_MISMATCH("issuer-mismatch", true),

    /** The token's {@code aud} does not contain the configured client id. */
    AUDIENCE_MISMATCH("audience-mismatch", true),

    /** The token's {@code iat} is absent, or outside the symmetric replay-guard window. */
    IAT_OUTSIDE_WINDOW("iat-outside-window", true),

    /** The token's {@code events} claim does not carry the back-channel-logout member key. */
    EVENTS_MISSING("events-missing", true),

    /** The token carries a {@code nonce}, which is prohibited in a logout token. */
    NONCE_PRESENT("nonce-present", true),

    /** The token carries neither {@code sub} nor {@code sid}, so nothing can be destroyed. */
    NO_SUB_OR_SID("no-sub-or-sid", true);

    private final String token;
    private final boolean signatureVerified;

    LogoutRejection(String token, boolean signatureVerified) {
        this.token = Objects.requireNonNull(token, "token");
        this.signatureVerified = signatureVerified;
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
     * Whether this rejection is reachable only <em>after</em> the logout token's signature has been
     * verified against the configured issuer's JWKS.
     *
     * @return {@code true} when only a genuinely signed token reaches this rejection (recorded at
     *         {@code WARN} unconditionally), {@code false} when an unauthenticated caller can drive
     *         it (recorded through the per-process latch)
     */
    public boolean isSignatureVerified() {
        return signatureVerified;
    }
}
