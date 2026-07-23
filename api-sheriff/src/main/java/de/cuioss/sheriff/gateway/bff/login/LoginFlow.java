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
package de.cuioss.sheriff.gateway.bff.login;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The confidential-client login initiation (D1/D2/D2b) — the browser-facing start of the OIDC
 * auth-code flow, the mirror of {@link de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint}.
 * <p>
 * The gateway re-implements <strong>no</strong> OAuth leg: the engine owns PKCE ({@code S256}),
 * {@code state}, {@code nonce}, and the authorization-URL construction. {@link LoginFlow} only
 * orchestrates the gateway-side concerns — it asks the engine to {@linkplain AuthorizationInitiation
 * authorize} (yielding the authorization URL and the transaction {@code FlowContext}), persists the
 * context as a single-use {@link PendingAuthorizationRecord}, sets the short-lived {@code __Host-}
 * browser-binding cookie carrying the record id, and redirects the browser to the IdP. The binding
 * cookie ties the in-flight login to this browser so the later callback cannot be replayed in
 * another (the pre-session analogue of the session cookie).
 * <p>
 * The post-login return URL is same-origin-validated ({@link PendingAuthorizationRecord#sameOrigin})
 * before it is recorded — a cross-origin or unparseable target falls back to {@link #DEFAULT_RETURN_URL},
 * so the post-login redirect is never an open redirect. The engine authorization is reached through
 * the {@link AuthorizationInitiation} seam, keeping the flow decoupled from the confidential-client
 * wiring (discovery metadata) and unit-testable without a live IdP.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LoginFlow {

    private static final CuiLogger LOGGER = new CuiLogger(LoginFlow.class);

    /** The safe default post-login landing when no valid same-origin return URL is supplied. */
    public static final String DEFAULT_RETURN_URL = "/";

    private final AuthorizationInitiation authorization;
    private final PendingAuthorizationStore pendingStore;
    private final BindingCookieCodec bindingCookieCodec;
    private final String gatewayOrigin;

    /**
     * Assembles the login flow with the engine authorization seam and the gateway-side stores.
     *
     * @param authorization      the engine authorization seam (bound to {@link AuthorizationCodeFlow#authorize})
     * @param pendingStore       the single-use pending-authorization store
     * @param bindingCookieCodec the browser-binding cookie codec
     * @param gatewayOrigin      the gateway's own origin (the {@code redirect_uri} origin) used to
     *                           same-origin-validate the post-login return URL
     */
    public LoginFlow(AuthorizationInitiation authorization, PendingAuthorizationStore pendingStore,
            BindingCookieCodec bindingCookieCodec, String gatewayOrigin) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.pendingStore = Objects.requireNonNull(pendingStore, "pendingStore");
        this.bindingCookieCodec = Objects.requireNonNull(bindingCookieCodec, "bindingCookieCodec");
        this.gatewayOrigin = Objects.requireNonNull(gatewayOrigin, "gatewayOrigin");
    }

    /**
     * Initiates a login: drives the engine authorization, persists the transaction, sets the binding
     * cookie, and redirects the browser to the IdP.
     *
     * @param requestedReturnUrl the post-login return target the browser asked for, may be absent
     * @param now                the reference instant (the pending record's TTL anchor)
     * @return the redirect to the IdP authorization URL carrying the binding {@code Set-Cookie}
     */
    public LoginRedirect initiate(@Nullable String requestedReturnUrl, Instant now) {
        Objects.requireNonNull(now, "now");
        AuthorizationCodeFlow.AuthorizationRedirect redirect = authorization.authorize();
        String returnUrl = PendingAuthorizationRecord.sameOrigin(requestedReturnUrl, gatewayOrigin)
                ? requestedReturnUrl : DEFAULT_RETURN_URL;
        PendingAuthorizationRecord record = PendingAuthorizationRecord.create(redirect.context(), returnUrl, now);
        pendingStore.store(record);
        LOGGER.debug("Initiated OIDC login; pending record persisted, redirecting to the IdP");
        List<String> setCookies = List.of(bindingCookieCodec.toSetCookieHeader(record.id()));
        return new LoginRedirect(redirect.authorizationUrl(), setCookies);
    }

    /**
     * The engine authorization seam. The session runtime binds it to the engine as
     * {@code () -> authorizationCodeFlow.authorize(providerMetadata)}; a test binds it to a
     * hand-built redirect. Keeping the discovery-metadata wiring behind the seam decouples the flow
     * from it and makes the initiation path unit-testable without a live IdP.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface AuthorizationInitiation {

        /**
         * Builds the authorization URL and the transaction context (PKCE/state/nonce owned by the
         * engine) for a fresh login.
         *
         * @return the engine's authorization redirect (URL + transaction {@code FlowContext})
         */
        AuthorizationCodeFlow.AuthorizationRedirect authorize();
    }

    /**
     * The framework-agnostic result of a login initiation: a {@code 302} redirect to the IdP
     * authorization URL, carrying the browser-binding {@code Set-Cookie} header.
     *
     * @param authorizationUrl the IdP authorization endpoint URL to redirect the browser to
     * @param setCookieHeaders the {@code Set-Cookie} header values to emit (the binding cookie)
     * @author API Sheriff Team
     * @since 1.0
     */
    public record LoginRedirect(String authorizationUrl, List<String> setCookieHeaders) {

        /**
         * Canonical constructor rejecting an absent URL and defensively copying the cookies.
         */
        public LoginRedirect {
            Objects.requireNonNull(authorizationUrl, "authorizationUrl");
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }
    }
}
