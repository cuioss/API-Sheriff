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
package de.cuioss.sheriff.gateway.bff.logout;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.session.SessionStore;
import de.cuioss.sheriff.token.commons.error.TokenSheriffException;
import de.cuioss.sheriff.token.validation.domain.token.TokenContent;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The gateway-side back-channel logout receiver (D2c): signature-verify the logout token via the
 * engine's issuer/JWKS infrastructure, run the {@link LogoutTokenValidator claim residual}, then
 * destroy the affected sessions through the store's O(1) secondary index.
 * <p>
 * Signature verification is reached through the {@link LogoutTokenVerifier} seam — the session
 * runtime binds it to the engine's token validation (reuse of {@code token-sheriff-validation},
 * ADR-11); a test binds it to a hand-built token or a failing stub. Keeping the JWKS wiring behind
 * the seam decouples the receiver from the confidential-client configuration and makes both the
 * signature-failure and the claim-rejection paths unit-testable without a live IdP.
 * <p>
 * Destruction is fail-closed and precise: a token carrying a {@code sid} destroys exactly that IdP
 * session ({@link SessionStore#destroyBySid}); a token carrying only a {@code sub} destroys every
 * session for the subject ({@link SessionStore#destroyBySub}). A signature or claim failure destroys
 * nothing. The receiver is framework-agnostic; the {@link de.cuioss.sheriff.gateway.bff.reserved.BackchannelLogoutEndpoint}
 * owns the HTTP concern.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BackchannelLogoutReceiver {

    private static final CuiLogger LOGGER = new CuiLogger(BackchannelLogoutReceiver.class);

    private final LogoutTokenVerifier verifier;
    private final LogoutTokenValidator validator;
    private final SessionStore sessionStore;

    /**
     * Assembles the receiver with the signature-verification seam, the claim residual, and the store.
     *
     * @param verifier     the engine signature-verification seam (bound to the JWKS token validation)
     * @param validator    the pure logout-token claim pipeline
     * @param sessionStore the server-side session store destroyed through its secondary index
     */
    public BackchannelLogoutReceiver(LogoutTokenVerifier verifier, LogoutTokenValidator validator,
            SessionStore sessionStore) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
    }

    /**
     * Receives one back-channel logout token: signature-verify, claim-validate, and destroy.
     *
     * @param rawLogoutToken the raw {@code logout_token} JWT from the back-channel request
     * @param now            the reference instant for the {@code iat} freshness check
     * @return an accepted result carrying the destroyed-session count, or a rejected result
     */
    public BackchannelResult receive(String rawLogoutToken, Instant now) {
        Objects.requireNonNull(rawLogoutToken, "rawLogoutToken");
        Objects.requireNonNull(now, "now");

        TokenContent token;
        try {
            token = verifier.verify(rawLogoutToken);
        } catch (TokenSheriffException signatureFailure) {
            LOGGER.debug(signatureFailure, "Back-channel logout token signature/validation failed — rejected");
            return BackchannelResult.rejected();
        }

        Optional<LogoutTokenValidator.LogoutSubject> subject = validator.validate(token, now);
        if (subject.isEmpty()) {
            return BackchannelResult.rejected();
        }

        LogoutTokenValidator.LogoutSubject logoutSubject = subject.get();
        int destroyed = logoutSubject.sid().map(sessionStore::destroyBySid)
                .orElseGet(() -> logoutSubject.sub().map(sessionStore::destroyBySub).orElse(0));
        LOGGER.debug("Back-channel logout accepted — destroyed %s session(s)", destroyed);
        return BackchannelResult.accepted(destroyed);
    }

    /**
     * The engine signature-verification seam. The session runtime binds it to the engine's JWKS
     * token validation; a test binds it to a hand-built {@link TokenContent} or a stub that throws.
     * Keeping the confidential-client/JWKS wiring behind the seam decouples the receiver from it and
     * makes the signature-failure path unit-testable without a live IdP.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface LogoutTokenVerifier {

        /**
         * Signature-verifies (and structurally validates) a raw logout token via the JWKS infrastructure.
         *
         * @param rawLogoutToken the raw logout-token JWT
         * @return the signature-verified token content
         * @throws de.cuioss.sheriff.token.commons.error.TokenSheriffException when the signature or
         *         structural validation fails
         */
        TokenContent verify(String rawLogoutToken);
    }

    /**
     * The framework-agnostic outcome of a back-channel logout: whether the token was accepted and, if
     * so, how many server-side sessions were destroyed. A rejected result destroys nothing.
     *
     * @param accepted  whether the logout token passed signature and claim validation
     * @param destroyed the number of sessions destroyed, always {@code 0} for a rejected result
     * @author API Sheriff Team
     * @since 1.0
     */
    public record BackchannelResult(boolean accepted, int destroyed) {

        /**
         * An accepted result carrying the destroyed-session count.
         *
         * @param destroyed the number of sessions destroyed
         * @return the accepted result
         */
        public static BackchannelResult accepted(int destroyed) {
            return new BackchannelResult(true, destroyed);
        }

        /**
         * A rejected result destroying nothing.
         *
         * @return the rejected result
         */
        public static BackchannelResult rejected() {
            return new BackchannelResult(false, 0);
        }
    }
}
