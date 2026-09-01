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
package de.cuioss.sheriff.gateway.config.model;

import java.util.List;
import java.util.Locale;


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The global {@code oidc} block of {@code gateway.yaml}: the confidential-client
 * configuration used by the BFF variants. Ignored when no route's effective auth
 * is {@code require: session}.
 *
 * @param issuer       the OIDC issuer, {@code null} when omitted
 * @param clientId     the client id, {@code null} when omitted
 * @param clientSecret the client secret ({@code ${ENV_VAR}} reference), {@code null}
 *                     when omitted
 * @param scopes       the requested scopes, empty when none
 * @param redirectUri  the gateway callback URI, {@code null} when omitted
 * @param logout       the logout settings, {@code null} when omitted
 * @param session      the session settings, {@code null} when omitted
 * @param stepUp       the step-up authentication settings, {@code null} when omitted
 * @param userInfo     the session/user-info reserved-endpoint settings, {@code null}
 *                     when omitted
 * @param login        the login-initiation reserved-path settings, {@code null} when
 *                     omitted
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record OidcConfig(
@Nullable String issuer,
@Nullable String clientId,
@Nullable String clientSecret,
List<String> scopes,
@Nullable String redirectUri,
@Nullable Logout logout,
@Nullable Session session,
@Nullable StepUp stepUp,
@Nullable UserInfo userInfo,
@Nullable Login login) {

    /**
     * Canonical constructor defensively copying {@code scopes}.
     */
    public OidcConfig {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /**
     * Overridden to redact {@link #clientSecret()}. The default record
     * {@code toString()} would otherwise print the resolved secret value verbatim
     * — {@link de.cuioss.sheriff.gateway.config.load.ConfigLoader} substitutes the
     * {@code ${ENV_VAR}} reference with the real secret before binding, so an
     * unredacted {@code toString()} would leak it into any log line, exception
     * message, or debugger view that captures this instance.
     *
     * @return a string representation with {@code clientSecret} redacted
     */
    @Override
    public String toString() {
        return "OidcConfig[issuer=%s, clientId=%s, clientSecret=%s, scopes=%s, redirectUri=%s, logout=%s, session=%s, stepUp=%s, userInfo=%s, login=%s]"
                .formatted(issuer, clientId, redact(clientSecret), scopes, redirectUri, logout, session, stepUp, userInfo,
                        login);
    }

    /**
     * Redacts a secret-bearing value for display, preserving presence without
     * exposing the value.
     *
     * @param secret the secret-bearing value to redact, {@code null} when absent
     * @return {@code "***REDACTED***"} when present, {@code "null"} otherwise
     */
    private static String redact(@Nullable String secret) {
        return secret != null ? "***REDACTED***" : "null";
    }

    /**
     * RP-initiated logout settings.
     *
     * @param path                  the gateway-served logout path, {@code null} when omitted
     * @param postLogoutRedirectUri  the gateway-owned return leg, {@code null} when omitted
     * @param finalRedirect         the application landing URL after logout, {@code null}
     *                              when omitted
     * @param backchannelPath       the back-channel logout receiver path, {@code null} when
     *                              omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Logout(
    @Nullable String path,
    @Nullable String postLogoutRedirectUri,
    @Nullable String finalRedirect,
    @Nullable String backchannelPath) {
    }

    /**
     * Session settings. The exhibited fields span both modes: {@code store} is
     * server-mode only, the encryption keys are cookie-mode only.
     *
     * @param mode          the session mode, canonicalized to lower case by the
     *                      canonical constructor ({@link Session#MODE_COOKIE} /
     *                      {@link Session#MODE_SERVER}), {@code null} when omitted. Compare
     *                      it through {@link Session#isCookieMode()} /
     *                      {@link Session#isServerMode()} — never against a
     *                      locally-declared constant
     * @param store         the server-mode store, {@code null} when omitted
     * @param cookieName    the session cookie name, {@code null} when omitted
     * @param encryptionKey the cookie-mode AES-256 sealing key ({@code ${ENV_VAR}}
     *                      reference). Present selects the <em>passed-key</em> mode;
     *                      {@code null} selects <em>generate-on-startup</em>, which is a
     *                      fully supported production mode whose key is fresh per
     *                      boot — so every session is dropped on restart and the key
     *                      cannot be shared across replicas
     * @param ttlSeconds    the absolute session lifetime in seconds, {@code null} when
     *                      omitted
     * @param csrf          the CSRF settings, {@code null} when omitted
     * @param refresh       the token-refresh settings, {@code null} when omitted
     * @param maxSessions   the server-mode upper bound on concurrently stored
     *                      sessions — a DoS guard on the in-memory store, {@code null}
     *                      when omitted
     * @param maxCookieSize the cookie-mode sealed cookie-value size budget in
     *                      bytes, {@code null} when omitted (the codec's
     *                      {@code DEFAULT_COOKIE_VALUE_BUDGET} then applies). It is
     *                      the single declared number driving BOTH the seal-time
     *                      budget and the gateway's pre-route {@code Cookie}
     *                      header-value cap
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Session(
    @Nullable String mode,
    @Nullable String store,
    @Nullable String cookieName,
    @Nullable String encryptionKey,
    @Nullable Integer ttlSeconds,
    @Nullable Csrf csrf,
    @Nullable Refresh refresh,
    @Nullable Integer maxSessions,
    @Nullable Integer maxCookieSize) {

        /** The stateless cookie session mode, in its one canonical spelling. */
        public static final String MODE_COOKIE = "cookie";

        /** The server-side store session mode, in its one canonical spelling. */
        public static final String MODE_SERVER = "server";

        /**
         * Canonical constructor canonicalizing {@link #mode()} to its lower-case, trimmed
         * spelling.
         * <p>
         * The mode is canonicalized <em>here, once</em>, so every downstream consumer compares the
         * same spelling. Leaving it raw let a value like {@code Cookie} be read case-sensitively by
         * boot validation (which then silently skipped both the cookie and server companion rules)
         * and case-insensitively by the edge (which relaxed the pre-route {@code Cookie}
         * header-value cap) — a validated-but-wrong runtime mode with a weakened inbound control.
         * Consumers MUST use {@link #isCookieMode()} / {@link #isServerMode()} rather than
         * re-deriving a comparison against a locally-declared constant.
         */
        public Session {
            if (mode != null) {
                String canonical = mode.trim().toLowerCase(Locale.ROOT);
                mode = canonical.isEmpty() ? null : canonical;
            }
        }

        /**
         * The single cookie-mode predicate every consumer shares — boot validation, the edge's
         * pre-route {@code Cookie} header-value cap, and the runtime session-binding selection.
         *
         * @return {@code true} when the declared mode is the stateless cookie mode
         */
        public boolean isCookieMode() {
            return MODE_COOKIE.equals(mode);
        }

        /**
         * The single server-mode predicate every consumer shares (see {@link #isCookieMode()}).
         *
         * @return {@code true} when the declared mode is the server-side store mode
         */
        public boolean isServerMode() {
            return MODE_SERVER.equals(mode);
        }

        /**
         * @return {@code true} when the declared mode is one of the two recognised session modes;
         *         an unrecognised or absent mode leaves the gateway bearer-only
         */
        public boolean isRecognisedMode() {
            return isCookieMode() || isServerMode();
        }

        /**
         * Overridden to redact {@link #encryptionKey()}. The default record
         * {@code toString()} would otherwise print the resolved cookie-encryption key
         * value verbatim into any log line, exception message, or debugger view that
         * captures this instance.
         *
         * @return a string representation with the key field redacted
         */
        @Override
        public String toString() {
            return "Session[mode=%s, store=%s, cookieName=%s, encryptionKey=%s, ttlSeconds=%s, csrf=%s, refresh=%s, maxSessions=%s, maxCookieSize=%s]"
                    .formatted(mode, store, cookieName, redact(encryptionKey), ttlSeconds, csrf,
                            refresh, maxSessions, maxCookieSize);
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
     * @param enabled       whether refresh is enabled, {@code null} when omitted
     * @param leewaySeconds how long before expiry to refresh, {@code null} when omitted
     * @param onFailure     the on-failure behaviour ({@code reauthenticate} /
     *                      {@code reject}), {@code null} when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Refresh(@Nullable Boolean enabled, @Nullable Integer leewaySeconds, @Nullable String onFailure) {
    }

    /**
     * RFC 9470 step-up authentication settings.
     *
     * @param enabled                whether step-up is honored, {@code null} when omitted
     * @param honorUpstreamChallenge whether upstream challenges are honored, {@code null}
     *                               when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record StepUp(@Nullable Boolean enabled, @Nullable Boolean honorUpstreamChallenge) {
    }

    /**
     * Session/user-info reserved-endpoint settings (fold). Configures the curated
     * identity view the gateway serves to the browser, capped by an operator-owned
     * claim allowlist whose secure default is closed: an empty {@code allowedClaims}
     * discloses nothing, so the operator — never the browser client — widens
     * disclosure.
     *
     * @param path          the gateway-served user-info path, {@code null} when omitted
     * @param allowedClaims the operator claim allowlist; empty is the secure closed
     *                      default that discloses nothing
     * @param defaultView   the curated default-view claim selector returned when the
     *                      caller requests no explicit claims; every entry must lie
     *                      within {@code allowedClaims}
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record UserInfo(@Nullable String path, List<String> allowedClaims, List<String> defaultView) {

        /**
         * Canonical constructor defensively copying the claim lists.
         */
        public UserInfo {
            allowedClaims = allowedClaims == null ? List.of() : List.copyOf(allowedClaims);
            defaultView = defaultView == null ? List.of() : List.copyOf(defaultView);
        }
    }

    /**
     * Login-initiation reserved-path settings (fold). Mirrors the {@link Logout}
     * shape so the login-initiation endpoint reads as {@code oidc.login.path}.
     *
     * @param path the gateway-served login-initiation path, {@code null} when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Login(@Nullable String path) {
    }
}
