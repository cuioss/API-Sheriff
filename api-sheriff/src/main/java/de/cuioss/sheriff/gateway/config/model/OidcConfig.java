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
 * <p>
 * <strong>Client authentication is selected by the presence of {@code client_secret}.</strong> A
 * configured secret selects client-secret authentication ({@code client_secret_basic}); an absent
 * secret selects {@code private_key_jwt}, signed with the key the {@code client_authentication}
 * block names or, when it names none, with a key generated at startup. There is no separate switch.
 * Every consumer reads the mode through {@link #usesClientSecret()}.
 *
 * @param issuer               the OIDC issuer, {@code null} when omitted
 * @param clientId             the client id, {@code null} when omitted
 * @param clientSecret         the client secret ({@code ${ENV_VAR}} reference), {@code null}
 *                             when omitted. A configured secret selects client-secret
 *                             authentication ({@code client_secret_basic}), which is not FAPI 2.0
 *                             conformant; an absent secret selects {@code private_key_jwt}
 * @param scopes               the requested scopes, empty when none
 * @param redirectUri          the gateway callback URI, {@code null} when omitted
 * @param logout               the logout settings, {@code null} when omitted
 * @param session              the session settings, {@code null} when omitted
 * @param stepUp               the step-up settings, {@code null} when omitted
 * @param userInfo             the session/user-info reserved-endpoint settings, {@code null}
 *                             when omitted
 * @param login                the login-initiation reserved-path settings, {@code null} when
 *                             omitted
 * @param clientAuthentication the client-authentication settings — the {@code private_key_jwt}
 *                             key file and the client JWKS path — {@code null} when omitted
 * @param senderConstraint     the sender-constraint settings — the key file of the DPoP proof key
 *                             the gateway's access tokens are bound to — {@code null} when omitted.
 *                             Read in both client-authentication modes
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
@Nullable Login login,
@Nullable ClientAuthenticationSettings clientAuthentication,
@Nullable SenderConstraintSettings senderConstraint) {

    /**
     * Canonical constructor defensively copying {@code scopes}.
     */
    public OidcConfig {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    /**
     * The single client-authentication mode predicate every consumer shares — boot validation and
     * the runtime producer both read it, so the two cannot resolve the mode differently.
     * <p>
     * The mode is selected by presence alone: a declared {@code client_secret} selects
     * client-secret authentication whatever its value, and boot validation refuses a declared
     * secret that resolves to a blank value rather than letting it fall back to the key-based mode.
     *
     * @return {@code true} exactly when {@link #clientSecret()} is present, selecting
     *         {@code client_secret_basic}; {@code false} selects {@code private_key_jwt}
     */
    public boolean usesClientSecret() {
        return clientSecret != null;
    }

    /**
     * The single resolution of the path the client JWKS endpoint is reserved at — the reserved-path
     * registry and boot validation both read it, so the two cannot resolve the default differently.
     * <p>
     * An absent {@code client_authentication} block and a block that declares no {@code jwks_path}
     * both resolve to {@link ClientAuthenticationSettings#DEFAULT_JWKS_PATH}; a declared
     * {@code jwks_path} is returned as declared. The path is resolved whatever the
     * client-authentication mode: it stays reserved when {@code client_secret} is configured.
     *
     * @return the effective client JWKS path, never {@code null}
     */
    public String effectiveClientJwksPath() {
        String declared = clientAuthentication == null ? null : clientAuthentication.jwksPath();
        return declared == null ? ClientAuthenticationSettings.DEFAULT_JWKS_PATH : declared;
    }

    /**
     * Overridden to redact {@link #clientSecret()}. The default record
     * {@code toString()} would otherwise print the resolved secret value verbatim
     * — {@link de.cuioss.sheriff.gateway.config.load.ConfigLoader} substitutes the
     * {@code ${ENV_VAR}} reference with the real secret before binding, so an
     * unredacted {@code toString()} would leak it into any log line, exception
     * message, or debugger view that captures this instance.
     * <p>
     * {@link #clientAuthentication()} and {@link #senderConstraint()} are rendered as they are: a
     * key file is a path, not a secret.
     *
     * @return a string representation with {@code clientSecret} redacted
     */
    @Override
    public String toString() {
        return "OidcConfig[issuer=%s, clientId=%s, clientSecret=%s, scopes=%s, redirectUri=%s, logout=%s, session=%s, stepUp=%s, userInfo=%s, login=%s, clientAuthentication=%s, senderConstraint=%s]"
                .formatted(issuer, clientId, redact(clientSecret), scopes, redirectUri, logout, session, stepUp, userInfo,
                        login, clientAuthentication, senderConstraint);
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
     * @param idleTimeoutSeconds how long a session may go without a request let through on a
     *                      session-protected route before it ends, in seconds; {@code null}
     *                      when omitted. Read it through
     *                      {@link Session#effectiveIdleTimeoutSeconds()}, which resolves the
     *                      omitted case — never directly
     * @param maxSessionsPerSubject the server-mode upper bound on the live sessions of one
     *                      subject; a login beyond it ends that subject's oldest session.
     *                      {@code null} when omitted. Read it through
     *                      {@link Session#effectiveMaxSessionsPerSubject()}, which resolves the
     *                      omitted case — never directly
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
    @Nullable Integer maxCookieSize,
    @Nullable Integer idleTimeoutSeconds,
    @Nullable Integer maxSessionsPerSubject) {

        /** The stateless cookie session mode, in its one canonical spelling. */
        public static final String MODE_COOKIE = "cookie";

        /** The server-side store session mode, in its one canonical spelling. */
        public static final String MODE_SERVER = "server";

        /**
         * The absolute session lifetime applied when {@code ttl_seconds} is omitted, in seconds.
         * <p>
         * Declared on the config model rather than on a single consumer because two independent
         * readers resolve the same omitted key: the runtime producer, which builds the codec's
         * {@code Duration}, and boot validation, which needs the same resolved lifetime to derive
         * the {@code Set-Cookie} header size a configured {@code max_cookie_size} would emit. Two
         * private copies of the figure would let the validator reason about a TTL the runtime does
         * not use.
         */
        public static final int DEFAULT_TTL_SECONDS = 3600;

        /**
         * The idle timeout applied when {@code idle_timeout_seconds} is omitted, in seconds — unless
         * the effective {@code ttl_seconds} is shorter, in which case that lifetime is the idle
         * timeout too (see {@link #effectiveIdleTimeoutSeconds()}).
         */
        public static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 1800;

        /**
         * The server-mode bound on concurrently stored sessions applied when {@code max_sessions} is
         * omitted. Declared here because the runtime producer and boot validation both resolve the
         * omitted key (see {@link #effectiveMaxSessions()}).
         */
        public static final int DEFAULT_MAX_SESSIONS = 10_000;

        /**
         * The bound on the live sessions of one subject applied when
         * {@code max_sessions_per_subject} is omitted — unless the effective {@code max_sessions} is
         * lower, in which case that is the bound per subject too (see
         * {@link #effectiveMaxSessionsPerSubject()}).
         */
        public static final int DEFAULT_MAX_SESSIONS_PER_SUBJECT = 10;

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
         * Resolves the idle timeout in force — the one resolution boot validation and the runtime
         * share, so the validator cannot reason about an idle timeout the runtime does not use.
         * <p>
         * A declared {@code idle_timeout_seconds} is taken as declared; boot validation refuses a
         * declared value below {@code 1} or above the effective {@code ttl_seconds}. An omitted
         * key resolves to the smaller of {@link #DEFAULT_IDLE_TIMEOUT_SECONDS} and the effective
         * {@code ttl_seconds}, so a deployment whose {@code ttl_seconds} is below the default keeps
         * booting and keeps its lifetime. No value switches the idle timeout off; declaring it
         * equal to {@code ttl_seconds} has that effect.
         *
         * @return the idle timeout in force, in seconds
         */
        public int effectiveIdleTimeoutSeconds() {
            if (idleTimeoutSeconds != null) {
                return idleTimeoutSeconds;
            }
            int effectiveTtlSeconds = ttlSeconds == null ? DEFAULT_TTL_SECONDS : ttlSeconds;
            return Math.min(DEFAULT_IDLE_TIMEOUT_SECONDS, effectiveTtlSeconds);
        }

        /**
         * Resolves the server-mode bound on concurrently stored sessions — the one resolution boot
         * validation and the runtime share.
         *
         * @return the declared {@code max_sessions}, or {@link #DEFAULT_MAX_SESSIONS} when omitted
         */
        public int effectiveMaxSessions() {
            return maxSessions == null ? DEFAULT_MAX_SESSIONS : maxSessions;
        }

        /**
         * Resolves the server-mode bound on the live sessions of one subject — the one resolution
         * boot validation and the runtime share.
         * <p>
         * A declared {@code max_sessions_per_subject} is taken as declared; boot validation refuses
         * a declared value below {@code 1} or above the effective {@code max_sessions}, and refuses
         * the key in cookie mode, which holds no sessions to count. An omitted key resolves to the
         * smaller of {@link #DEFAULT_MAX_SESSIONS_PER_SUBJECT} and the effective
         * {@code max_sessions}, so a deployment whose {@code max_sessions} is below the default keeps
         * booting. No value switches the bound off; declaring it equal to {@code max_sessions} has
         * that effect.
         *
         * @return the bound per subject in force
         */
        public int effectiveMaxSessionsPerSubject() {
            if (maxSessionsPerSubject != null) {
                return maxSessionsPerSubject;
            }
            return Math.min(DEFAULT_MAX_SESSIONS_PER_SUBJECT, effectiveMaxSessions());
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
            return "Session[mode=%s, store=%s, cookieName=%s, encryptionKey=%s, ttlSeconds=%s, csrf=%s, refresh=%s, maxSessions=%s, maxCookieSize=%s, idleTimeoutSeconds=%s, maxSessionsPerSubject=%s]"
                    .formatted(mode, store, cookieName, redact(encryptionKey), ttlSeconds, csrf,
                            refresh, maxSessions, maxCookieSize, idleTimeoutSeconds, maxSessionsPerSubject);
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
     * Step-up settings: the RFC 9470 upstream-challenge leg and the gateway-served step-up path.
     * <p>
     * The two are independent. {@code enabled} and {@code honorUpstreamChallenge} govern the
     * RFC 9470 {@code acr} leg only and do not gate {@code path}: the path's presence alone
     * registers the reserved step-up endpoint, exactly like every other reserved path. That
     * endpoint is the target of the {@code step_up_url} a session route names when it refuses
     * a non-navigation request for a missing scope.
     *
     * @param enabled                whether step-up is honored, {@code null} when omitted
     * @param honorUpstreamChallenge whether upstream challenges are honored, {@code null}
     *                               when omitted
     * @param path                   the gateway-served step-up path ({@code oidc.step_up.path}),
     *                               {@code null} when omitted, in which case no step-up endpoint
     *                               is registered and no {@code step_up_url} is ever named
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record StepUp(@Nullable Boolean enabled, @Nullable Boolean honorUpstreamChallenge, @Nullable String path) {
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
     * <p>
     * {@code defaultReturnUrl} is the return target an absent, cross-origin or unparseable
     * target falls back to, and the runtime resolves an omitted value to {@code /}. Boot
     * validation refuses a value that is
     * not same-origin with {@code redirect_uri}.
     *
     * @param path             the gateway-served login-initiation path, {@code null} when
     *                         omitted
     * @param defaultReturnUrl the fallback return target, {@code null} when omitted
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record Login(@Nullable String path, @Nullable String defaultReturnUrl) {
    }

    /**
     * The {@code client_authentication} block: the key the gateway signs its
     * {@code private_key_jwt} client assertion with, and the path its public half is published at.
     * {@code keyFile} is read only when no {@code client_secret} is configured (see
     * {@link OidcConfig#usesClientSecret()}); boot validation refuses a document that declares a key
     * file together with a secret. {@code jwksPath} is read in both client-authentication modes.
     * <p>
     * The record is named {@code ClientAuthenticationSettings} so that it does not shadow the token
     * engine's {@code ClientAuthentication} type where both are in scope.
     *
     * @param keyFile  the path of a PEM file on a mount holding the client-authentication key,
     *                 {@code null} when omitted — a key is then generated at startup. The value is a
     *                 location, not a secret: it may be written literally and is never redacted
     * @param jwksPath the gateway path the client JWKS endpoint is reserved at, {@code null} when
     *                 omitted — {@link #DEFAULT_JWKS_PATH} then applies. Read it through
     *                 {@link OidcConfig#effectiveClientJwksPath()}, never directly, so the default is
     *                 resolved in one place
     * @author API Sheriff Team
     * @since 1.0
     */
    // cui-rewrite:disable AnnotationNewlineFormat
    @Builder
    public record ClientAuthenticationSettings(@Nullable String keyFile, @Nullable String jwksPath) {

        /** The path the client JWKS endpoint is reserved at when {@code jwks_path} is omitted. */
        // java:S1075 — the documented default of a configuration key, not a customizable URI/filesystem path.
        @SuppressWarnings("java:S1075")
        public static final String DEFAULT_JWKS_PATH = "/auth/jwks";
    }

    /**
     * The {@code sender_constraint} block: the key the gateway signs its DPoP proofs with, and
     * therefore the key every access token it obtains is bound to. The block is read in both
     * client-authentication modes — a configured {@code client_secret} changes how the gateway
     * authenticates, not whether its access tokens are sender-constrained.
     * <p>
     * The record is named {@code SenderConstraintSettings} so that it does not shadow the token
     * engine's {@code SenderConstraint} type where both are in scope.
     *
     * @param keyFile the path of a PEM file on a mount holding the DPoP proof key, {@code null} when
     *                omitted — a key is then generated at startup. The value is a location, not a
     *                secret: it may be written literally and is never redacted. It may name the same
     *                file as {@code client_authentication.key_file}; two keys are recommended
     * @author API Sheriff Team
     * @since 1.0
     */
    public record SenderConstraintSettings(@Nullable String keyFile) {
    }
}
