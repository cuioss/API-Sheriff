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
package de.cuioss.sheriff.gateway.bff.login;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;


import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.token.client.config.ClientConfiguration;
import de.cuioss.sheriff.token.client.discovery.ProviderMetadata;
import de.cuioss.sheriff.token.client.flow.AuthorizationRequestBuilder;
import de.cuioss.sheriff.token.client.flow.FlowContext;
import de.cuioss.tools.logging.CuiLogger;

/**
 * The gateway-owned authorization-request builder that drives the OIDC auth-code flow with
 * {@code response_mode=query} instead of the engine's built-in {@code response_mode=form_post}.
 * <p>
 * <strong>Why the gateway overrides the engine here.</strong> {@link AuthorizationRequestBuilder}
 * unconditionally emits {@code response_mode=form_post}, so after a successful IdP login the browser
 * performs a <em>cross-site POST</em> (the IdP's auto-submit form) to the {@code redirect_uri}. The
 * short-lived browser-binding cookie minted by {@link BindingCookieCodec} carries
 * {@code SameSite=Lax}, and a Lax cookie is <em>not</em> sent on a cross-site POST — only on a
 * top-level GET navigation. The real browser therefore dropped the binding cookie on the callback
 * leg and every login dead-ended on the
 * {@code OIDC callback without a browser-binding cookie — rejected} {@code 403} branch. Driving the
 * request with {@code response_mode=query} makes the callback a {@code 302} top-level GET
 * navigation, for which {@code SameSite=Lax} <em>is</em> sent, so the binding cookie survives.
 * <p>
 * The rejected alternative was {@code SameSite=None} on the binding cookie: that would have weakened
 * the exact cross-site binding control the cookie exists to provide. No cookie attribute is changed
 * by this component — {@code __Host-} prefix, {@code Secure}, {@code HttpOnly}, {@code Path=/} and
 * {@code SameSite=Lax} all stay exactly as they were.
 * <p>
 * <strong>Accepted tradeoff — the authorization code travels in the URL query string.</strong>
 * {@code response_mode=query} places the {@code code} in the callback URL rather than in a POST
 * body, which exposes it to the {@code Referer} header, to proxy / CDN and server access logs, and to
 * browser history. That exposure is the standard reason {@code form_post} is preferred, and it is
 * accepted here <em>deliberately, by operator decision</em>, because it is what makes the
 * browser-facing flow work at all. The mitigations, each verified in this codebase rather than
 * assumed:
 * <ul>
 *   <li><strong>PKCE is in force.</strong> {@link FlowContext} carries a non-optional
 *       {@code PkceChallenge} and the engine builder always emits {@code code_challenge} /
 *       {@code code_challenge_method}; it additionally refuses to start the flow when the provider
 *       does not advertise {@code S256}. A leaked code is therefore not redeemable without the
 *       verifier, which never leaves the gateway.</li>
 *   <li><strong>The code is single-use and short-lived.</strong> The authorization code is redeemed
 *       once at the token endpoint; a replay of the same code fails there. The integration realm
 *       ({@code integration-tests/src/main/docker/keycloak/integration-realm.json}) declares no
 *       {@code accessCodeLifespan} override, so Keycloak's own short default lifetime is what is in
 *       force — the gateway does not widen it.</li>
 *   <li><strong>The binding cookie plus the {@code state} double-check.</strong>
 *       {@code CallbackEndpoint.handle} resolves the pending record by the unguessable id in the
 *       binding cookie <em>and</em> constant-time-compares the returned {@code state}, so a code
 *       replayed from a different browser is rejected {@code 403} even when the code itself is still
 *       live.</li>
 * </ul>
 * Not overstated: the exposure of the code to intermediaries is real and is not removed by any of
 * the above — it is bounded by them.
 * <p>
 * <strong>How the rewrite works.</strong> {@link #build} delegates to the engine and then rewrites
 * <em>only</em> the {@code response_mode} parameter of the returned URL. The rewrite is
 * parameter-aware (it splits the query into its {@code name=value} pairs rather than substring-
 * replacing the literal {@code form_post}), it preserves every other authorization parameter
 * byte-for-byte in its original order and encoding ({@code client_id}, {@code redirect_uri},
 * {@code scope}, {@code state}, {@code nonce}, {@code code_challenge},
 * {@code code_challenge_method}, {@code acr_values}, {@code max_age}), and it is idempotent — a URL
 * that already carries {@code response_mode=query} comes back unchanged.
 * <p>
 * The same instance is wired into <em>both</em> engine seams that build an authorization URL — the
 * {@code AuthorizationCodeFlow} login leg and the {@code StepUpHandler} RFC 9470 re-drive leg — so
 * the step-up leg cannot keep emitting the broken mode.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class QueryResponseModeAuthorizationRequestBuilder extends AuthorizationRequestBuilder {

    private static final CuiLogger LOGGER = new CuiLogger(QueryResponseModeAuthorizationRequestBuilder.class);

    /** The authorization-request parameter this builder rewrites. */
    private static final String PARAM_RESPONSE_MODE = "response_mode";

    /** The response mode the gateway drives: a {@code 302} top-level GET callback. */
    private static final String RESPONSE_MODE_QUERY = "query";

    private static final String RESPONSE_MODE_PAIR = PARAM_RESPONSE_MODE + '=' + RESPONSE_MODE_QUERY;
    private static final char QUERY_START = '?';
    private static final String PAIR_SEPARATOR = "&";
    private static final char NAME_VALUE_SEPARATOR = '=';

    /**
     * Builds the engine's authorization URL and rewrites its {@code response_mode} to {@code query}.
     *
     * @param configuration the confidential-client configuration
     * @param metadata      the resolved OIDC provider metadata
     * @param context       the transaction context (state / nonce / PKCE, owned by the engine)
     * @return the engine's authorization URL with {@code response_mode=query}, every other parameter
     *         preserved verbatim
     */
    @Override
    public String build(ClientConfiguration configuration, ProviderMetadata metadata, FlowContext context) {
        return withQueryResponseMode(super.build(configuration, metadata, context));
    }

    /**
     * Rewrites the {@code response_mode} parameter of an authorization URL to {@code query}, leaving
     * every other parameter untouched.
     * <p>
     * The comparison is against the literal parameter name because the engine form-encodes the query
     * and {@code response_mode} contains no character that encoding alters. Values are never decoded
     * and re-encoded: each untouched pair is copied through exactly as the engine emitted it, so no
     * round-trip can corrupt an already-encoded {@code redirect_uri} or {@code scope}. The method is
     * idempotent and total — a URL with no query at all, or with an empty query, simply gains the
     * parameter.
     *
     * @param authorizationUrl the engine-built authorization URL
     * @return the same URL with {@code response_mode=query}
     */
    public static String withQueryResponseMode(String authorizationUrl) {
        Objects.requireNonNull(authorizationUrl, "authorizationUrl");
        int queryStart = authorizationUrl.indexOf(QUERY_START);
        if (queryStart < 0) {
            return authorizationUrl + QUERY_START + RESPONSE_MODE_PAIR;
        }
        String prefix = authorizationUrl.substring(0, queryStart + 1);
        String query = authorizationUrl.substring(queryStart + 1);
        if (query.isEmpty()) {
            return prefix + RESPONSE_MODE_PAIR;
        }
        List<String> pairs = new ArrayList<>();
        boolean rewritten = false;
        for (String pair : query.split(PAIR_SEPARATOR, -1)) {
            if (PARAM_RESPONSE_MODE.equals(nameOf(pair))) {
                pairs.add(RESPONSE_MODE_PAIR);
                rewritten = true;
            } else {
                pairs.add(pair);
            }
        }
        if (!rewritten) {
            pairs.add(RESPONSE_MODE_PAIR);
        }
        // Never log the URL itself: it carries state, nonce and the PKCE code_challenge.
        LOGGER.debug("Authorization request driven with response_mode=%s (rewritten=%s)",
                RESPONSE_MODE_QUERY, rewritten);
        return prefix + String.join(PAIR_SEPARATOR, pairs);
    }

    private static String nameOf(String pair) {
        int separator = pair.indexOf(NAME_VALUE_SEPARATOR);
        return separator < 0 ? pair : pair.substring(0, separator);
    }
}
