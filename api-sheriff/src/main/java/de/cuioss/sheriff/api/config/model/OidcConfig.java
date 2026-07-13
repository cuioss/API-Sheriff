/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.config.model;

import java.util.List;
import java.util.Optional;

import lombok.Builder;

/**
 * The global {@code oidc} block of {@code gateway.yaml}: the confidential-client
 * configuration used by the BFF variants. Ignored when no route's effective auth
 * is {@code require: session}.
 *
 * @param issuer       the OIDC issuer, empty when omitted
 * @param clientId     the client id, empty when omitted
 * @param clientSecret the client secret ({@code ${ENV_VAR}} reference), empty
 *                     when omitted
 * @param scopes       the requested scopes, empty when none
 * @param redirectUri  the gateway callback URI, empty when omitted
 * @param logout       the logout settings, empty when omitted
 * @param session      the session settings, empty when omitted
 * @param stepUp       the step-up authentication settings, empty when omitted
 * @author API Sheriff Team
 * @since 1.0
 */
@Builder
public record OidcConfig(Optional<String> issuer, Optional<String> clientId, Optional<String> clientSecret,
                         List<String> scopes, Optional<String> redirectUri, Optional<Logout> logout, Optional<Session> session,
                         Optional<StepUp> stepUp) {

    /**
     * Canonical constructor defensively copying {@code scopes} and normalizing
     * absent components.
     */
    public OidcConfig {
        issuer = issuer == null ? Optional.empty() : issuer;
        clientId = clientId == null ? Optional.empty() : clientId;
        clientSecret = clientSecret == null ? Optional.empty() : clientSecret;
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        redirectUri = redirectUri == null ? Optional.empty() : redirectUri;
        logout = logout == null ? Optional.empty() : logout;
        session = session == null ? Optional.empty() : session;
        stepUp = stepUp == null ? Optional.empty() : stepUp;
    }

    /**
     * Overridden to redact {@link #clientSecret()}. The default record
     * {@code toString()} would otherwise print the resolved secret value verbatim
     * — {@link de.cuioss.sheriff.api.config.load.ConfigLoader} substitutes the
     * {@code ${ENV_VAR}} reference with the real secret before binding, so an
     * unredacted {@code toString()} would leak it into any log line, exception
     * message, or debugger view that captures this instance.
     *
     * @return a string representation with {@code clientSecret} redacted
     */
    @Override
    public String toString() {
        return "OidcConfig[issuer=%s, clientId=%s, clientSecret=%s, scopes=%s, redirectUri=%s, logout=%s, session=%s, stepUp=%s]"
                .formatted(issuer, clientId, redact(clientSecret), scopes, redirectUri, logout, session, stepUp);
    }

    /**
     * Redacts a secret-bearing {@link Optional} for display, preserving presence
     * without exposing the value.
     *
     * @param secret the secret-bearing optional to redact
     * @return {@code "Optional[***REDACTED***]"} when present, {@code "Optional.empty"} otherwise
     */
    private static String redact(Optional<String> secret) {
        return secret.isPresent() ? "Optional[***REDACTED***]" : "Optional.empty";
    }

    /**
     * RP-initiated logout settings.
     *
     * @param path                  the gateway-served logout path, empty when omitted
     * @param postLogoutRedirectUri  the gateway-owned return leg, empty when omitted
     * @param finalRedirect         the application landing URL after logout, empty
     *                              when omitted
     * @param backchannelPath       the back-channel logout receiver path, empty when
     *                              omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Logout(Optional<String> path, Optional<String> postLogoutRedirectUri, Optional<String> finalRedirect,
                         Optional<String> backchannelPath) {

        /**
         * Canonical constructor normalizing absent components to {@link Optional#empty()}.
         */
        public Logout {
            path = path == null ? Optional.empty() : path;
            postLogoutRedirectUri = postLogoutRedirectUri == null ? Optional.empty() : postLogoutRedirectUri;
            finalRedirect = finalRedirect == null ? Optional.empty() : finalRedirect;
            backchannelPath = backchannelPath == null ? Optional.empty() : backchannelPath;
        }
    }

    /**
     * Session settings. The exhibited fields span both modes: {@code store} is
     * server-mode only, the encryption keys are cookie-mode only.
     *
     * @param mode          the session mode ({@code cookie} / {@code server}), empty
     *                      when omitted
     * @param store         the server-mode store, empty when omitted
     * @param cookieName    the session cookie name, empty when omitted
     * @param encryptionKey the cookie-mode AES key ({@code ${ENV_VAR}} reference),
     *                      empty when omitted
     * @param previousKey   the optional decrypt-only rotation key, empty when omitted
     * @param ttlSeconds    the absolute session lifetime in seconds, empty when
     *                      omitted
     * @param csrf          the CSRF settings, empty when omitted
     * @param refresh       the token-refresh settings, empty when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Session(Optional<String> mode, Optional<String> store, Optional<String> cookieName,
                          Optional<String> encryptionKey, Optional<String> previousKey, Optional<Integer> ttlSeconds,
                          Optional<Csrf> csrf, Optional<Refresh> refresh) {

        /**
         * Canonical constructor normalizing absent components to {@link Optional#empty()}.
         */
        public Session {
            mode = mode == null ? Optional.empty() : mode;
            store = store == null ? Optional.empty() : store;
            cookieName = cookieName == null ? Optional.empty() : cookieName;
            encryptionKey = encryptionKey == null ? Optional.empty() : encryptionKey;
            previousKey = previousKey == null ? Optional.empty() : previousKey;
            ttlSeconds = ttlSeconds == null ? Optional.empty() : ttlSeconds;
            csrf = csrf == null ? Optional.empty() : csrf;
            refresh = refresh == null ? Optional.empty() : refresh;
        }

        /**
         * Overridden to redact {@link #encryptionKey()} and {@link #previousKey()}.
         * The default record {@code toString()} would otherwise print the resolved
         * cookie-encryption key values verbatim into any log line, exception message,
         * or debugger view that captures this instance.
         *
         * @return a string representation with both key fields redacted
         */
        @Override
        public String toString() {
            return "Session[mode=%s, store=%s, cookieName=%s, encryptionKey=%s, previousKey=%s, ttlSeconds=%s, csrf=%s, refresh=%s]"
                    .formatted(mode, store, cookieName, redact(encryptionKey), redact(previousKey), ttlSeconds, csrf,
                            refresh);
        }
    }

    /**
     * CSRF settings for {@code require: session} routes.
     *
     * @param trustedOrigins the browser origins allowed on unsafe methods, empty
     *                       when defaulting to the {@code redirect_uri} origin
     * @author API Sheriff Team
     * @since 1.0
     */
    public record Csrf(List<String> trustedOrigins) {

        /**
         * Canonical constructor defensively copying {@code trustedOrigins} and
         * normalizing an absent list to empty.
         */
        public Csrf {
            trustedOrigins = trustedOrigins == null ? List.of() : List.copyOf(trustedOrigins);
        }
    }

    /**
     * Transparent token-refresh settings.
     *
     * @param enabled       whether refresh is enabled, empty when omitted
     * @param leewaySeconds how long before expiry to refresh, empty when omitted
     * @param onFailure     the on-failure behaviour ({@code reauthenticate} /
     *                      {@code reject}), empty when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record Refresh(Optional<Boolean> enabled, Optional<Integer> leewaySeconds, Optional<String> onFailure) {

        /**
         * Canonical constructor normalizing absent components to {@link Optional#empty()}.
         */
        public Refresh {
            enabled = enabled == null ? Optional.empty() : enabled;
            leewaySeconds = leewaySeconds == null ? Optional.empty() : leewaySeconds;
            onFailure = onFailure == null ? Optional.empty() : onFailure;
        }
    }

    /**
     * RFC 9470 step-up authentication settings.
     *
     * @param enabled                whether step-up is honored, empty when omitted
     * @param honorUpstreamChallenge whether upstream challenges are honored, empty
     *                               when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    @Builder
    public record StepUp(Optional<Boolean> enabled, Optional<Boolean> honorUpstreamChallenge) {

        /**
         * Canonical constructor normalizing absent components to {@link Optional#empty()}.
         */
        public StepUp {
            enabled = enabled == null ? Optional.empty() : enabled;
            honorUpstreamChallenge = honorUpstreamChallenge == null ? Optional.empty() : honorUpstreamChallenge;
        }
    }
}
