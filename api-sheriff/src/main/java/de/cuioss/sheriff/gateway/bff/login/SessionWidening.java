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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import de.cuioss.sheriff.gateway.bff.login.LoginFlow.LoginRedirect;
import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord.Widening;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.flow.AuthorizationCodeFlow;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * Widens a <em>live</em> session — the sibling of {@link LoginFlow}
 * built on the same re-drive: a {@link PendingAuthorizationRecord}, the {@link BindingCookieCodec}
 * browser-binding cookie, and the {@link de.cuioss.sheriff.gateway.bff.reserved.CallbackEndpoint}
 * landing.
 * <p>
 * <strong>What is requested.</strong> A widening asks the IdP for the session's granted-scope set
 * {@code S} united with the route's needed scopes, never for the needed scopes alone: the callback
 * replaces both the session's active set {@code A} and {@code S} itself with what this grant returns,
 * so a request for less than {@code S} would silently narrow the session. A member of {@code S} the
 * IdP no longer grants does leave the session that way — that is the grant being truthful, not the
 * request being short. The request goes through the
 * {@link AuthorizationWidening} seam, bound at runtime to {@link ScopedEngineFlows#widen}, which
 * builds its engine flow per call because {@code S} is IdP-derived.
 * <p>
 * <strong>Silent first, one interactive attempt.</strong> A widening starts as a
 * {@link Widening.Attempt#SILENT} ({@code prompt=none}) request, which the IdP can answer from its
 * own SSO session without showing the user anything. When it answers that interaction is needed,
 * the callback re-drives exactly one {@link Widening.Attempt#INTERACTIVE} request through
 * {@link #redriveInteractive}; any refusal of that attempt is terminal.
 * <p>
 * <strong>Both attempts are pushed (ADR-0058).</strong> The runtime binds the
 * {@link AuthorizationWidening} seam to {@link ScopedEngineFlows#widen} followed by
 * {@link PushedAuthorizationRequests#push}, so the URL the seam yields — for the silent and for the
 * interactive attempt alike — carries {@code client_id} and {@code request_uri} and nothing else; the
 * scope set and the silent attempt's {@code prompt=none} travel in the pushed request. A failed push
 * propagates out of the seam as a {@code 502} refusal. Both entry points call the seam before they
 * store the pending record and before they set the binding cookie, so a refused push leaves nothing
 * half-started and the live session as it was.
 * <p>
 * <strong>What the pending record carries.</strong> The record is a widening record
 * ({@link PendingAuthorizationRecord#createWidening}): it names the live session's {@code sub}, so
 * the callback can refuse a grant for any other identity and merge into the live session instead of
 * minting a new one, and it names the attempt, so the callback knows whether one interactive
 * re-drive is still owed.
 * <p>
 * The return URL is same-origin-validated with {@link PendingAuthorizationRecord#sameOrigin} and falls
 * back to the configured {@code oidc.login.default_return_url}, exactly as a login does, so a
 * widening is never an open redirect. Nothing this class logs carries the authorization URL, a token,
 * a session id or a subject.
 * <p>
 * <strong>Deliberately not {@code StepUpCoordinator}.</strong> That coordinator is the RFC 9470
 * {@code acr} leg: it builds its request from the static {@code oidc.scopes} and its re-drive always
 * lands as a new session. A widening needs the IdP-derived {@code S}, and it must land in the live
 * session.
 * <p>
 * <strong>Thread safety.</strong> Immutable; the collaborators are safe for concurrent use. One
 * instance serves every request of a runtime.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SessionWidening {

    private static final CuiLogger LOGGER = new CuiLogger(SessionWidening.class);

    private final AuthorizationWidening authorization;
    private final PendingAuthorizationStore pendingStore;
    private final BindingCookieCodec bindingCookieCodec;
    private final String gatewayOrigin;
    private final String defaultReturnUrl;

    /**
     * Assembles the widening coordinator with the engine authorization seam and the gateway-side stores.
     *
     * @param authorization      the widening authorization seam (bound to {@link ScopedEngineFlows#widen}
     *                           and the push of the request it renders)
     * @param pendingStore       the single-use pending-authorization store
     * @param bindingCookieCodec the browser-binding cookie codec
     * @param gatewayOrigin      the gateway's own origin used to same-origin-validate the return URL
     * @param defaultReturnUrl   the resolved {@code oidc.login.default_return_url} ({@code /} when
     *                           unset): the landing when no valid same-origin return URL is supplied
     */
    public SessionWidening(AuthorizationWidening authorization, PendingAuthorizationStore pendingStore,
            BindingCookieCodec bindingCookieCodec, String gatewayOrigin, String defaultReturnUrl) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.pendingStore = Objects.requireNonNull(pendingStore, "pendingStore");
        this.bindingCookieCodec = Objects.requireNonNull(bindingCookieCodec, "bindingCookieCodec");
        this.gatewayOrigin = Objects.requireNonNull(gatewayOrigin, "gatewayOrigin");
        this.defaultReturnUrl = Objects.requireNonNull(defaultReturnUrl, "defaultReturnUrl");
    }

    /**
     * Initiates a widening of {@code live}: requests {@code live.grantedScopes() ∪ neededScopes},
     * persists a widening pending record carrying the live session's {@code sub} and the attempt, and
     * redirects the browser to the IdP.
     *
     * @param live         the live session to widen
     * @param returnUrl    the target the browser returns to after the widening, may be absent; an
     *                     absent or off-origin target falls back to the configured default
     * @param neededScopes the scopes the route needs
     * @param attempt      the attempt to issue — {@link Widening.Attempt#SILENT} for a fresh widening
     * @param now          the reference instant (the pending record's TTL anchor)
     * @return the redirect to the IdP authorization URL carrying the binding {@code Set-Cookie}
     */
    public LoginRedirect initiate(SessionRecord live, @Nullable String returnUrl, Set<String> neededScopes,
            Widening.Attempt attempt, Instant now) {
        Objects.requireNonNull(live, "live");
        Objects.requireNonNull(neededScopes, "neededScopes");
        Objects.requireNonNull(attempt, "attempt");
        Objects.requireNonNull(now, "now");
        Set<String> requested = new TreeSet<>(live.grantedScopes());
        requested.addAll(neededScopes);
        String validatedReturnUrl = returnUrl != null && PendingAuthorizationRecord.sameOrigin(returnUrl, gatewayOrigin)
                ? returnUrl : defaultReturnUrl;
        return redirect(live.sub(), validatedReturnUrl, requested, attempt, now);
    }

    /**
     * Re-drives the single interactive attempt a silent widening is owed when the IdP answered that
     * interaction is needed. The interactive request carries the same return URL and scope set as the
     * silent one, and the same subject.
     *
     * @param silent the consumed pending record of the silent attempt; must be a widening record
     *               whose attempt is {@link Widening.Attempt#SILENT}
     * @param now    the reference instant (the new pending record's TTL anchor)
     * @return the redirect to the IdP authorization URL carrying a new binding {@code Set-Cookie}
     * @throws IllegalArgumentException when {@code silent} is not the record of a silent widening
     */
    public LoginRedirect redriveInteractive(PendingAuthorizationRecord silent, Instant now) {
        Objects.requireNonNull(silent, "silent");
        Objects.requireNonNull(now, "now");
        Widening widening = silent.widening();
        if (widening == null || widening.attempt() != Widening.Attempt.SILENT) {
            throw new IllegalArgumentException("only a silent widening attempt is re-driven interactively");
        }
        // The return URL on the record was same-origin-validated when the silent attempt was issued.
        return redirect(widening.sub(), silent.returnUrl(), silent.requestedScopes(), Widening.Attempt.INTERACTIVE,
                now);
    }

    private LoginRedirect redirect(String sub, String returnUrl, Collection<String> requested,
            Widening.Attempt attempt, Instant now) {
        AuthorizationCodeFlow.AuthorizationRedirect redirect = authorization.widen(requested,
                attempt == Widening.Attempt.SILENT);
        PendingAuthorizationRecord pending = PendingAuthorizationRecord.createWidening(redirect.context(), returnUrl,
                requested, sub, attempt, now);
        pendingStore.store(pending);
        LOGGER.debug("Initiated %s session widening; pending record persisted, redirecting to the IdP", attempt);
        List<String> setCookies = List.of(bindingCookieCodec.toSetCookieHeader(pending.id()));
        return new LoginRedirect(redirect.authorizationUrl(), setCookies);
    }

    /**
     * The widening authorization seam. The session runtime binds it to
     * {@code scopedEngineFlows.widen(providerMetadata, scopes, silent)} followed by the push of the
     * request that call rendered; a test binds it to a hand-built redirect.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface AuthorizationWidening {

        /**
         * Builds the authorization URL and the transaction context for a widening requesting
         * {@code scopes}.
         *
         * @param scopes the scope set the authorization request's {@code scope} parameter carries
         * @param silent {@code true} when the request must carry {@code prompt=none}
         * @return the engine's authorization redirect (URL + transaction {@code FlowContext})
         */
        AuthorizationCodeFlow.AuthorizationRedirect widen(Collection<String> scopes, boolean silent);
    }
}
