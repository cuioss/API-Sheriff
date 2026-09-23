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
package de.cuioss.sheriff.gateway.bff.reserved;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
import de.cuioss.sheriff.gateway.bff.logout.LogoutRejection;
import de.cuioss.sheriff.gateway.bff.logout.LogoutRejectionLog;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The OIDC back-channel logout endpoint ({@code oidc.logout.backchannel_path}) — the request/response
 * edge over {@link BackchannelLogoutReceiver} (D2c, BFF-09). It owns the reserved
 * {@link ReservedPathRegistry.ReservedEndpoint#BACKCHANNEL_LOGOUT} path; the signature verification,
 * the claim residual, and the secondary-index session destruction live in the receiver.
 * <p>
 * The <a href="https://openid.net/specs/openid-connect-backchannel-1_0.html">OpenID Connect
 * Back-Channel Logout</a> request is an {@code application/x-www-form-urlencoded} {@code POST} carrying
 * a single {@code logout_token} parameter. The endpoint extracts that parameter from the <em>raw</em>
 * form body (URL-decoding the value) and hands it to {@link BackchannelLogoutReceiver#receive}: an
 * accepted token yields {@code 200} (with the destroyed-session count for observability), a
 * missing/absent {@code logout_token} or a signature/claim rejection yields {@code 400}, and nothing
 * is destroyed on rejection. Per the spec both the success and error responses must be served
 * uncacheable ({@code Cache-Control: no-store}); the framework edge renders that header.
 * <p>
 * <strong>Capability gate (D4).</strong> The endpoint is gated on the active
 * {@link SessionBinding}'s {@linkplain SessionBinding#idpDestruction() IdP-destruction capability},
 * never on a mode string. A binding reporting {@link SessionBinding.IdpDestruction#UNSUPPORTED} — the
 * stateless cookie-mode binding, which holds no server-side index — cannot honour an IdP-driven
 * {@code sid}/{@code sub} destruction, so the endpoint answers {@code 404} <em>before</em> the form
 * body is parsed: the {@code logout_token} is never read and the receiver is never reached. That is
 * strictly better than accepting a post the gateway could only answer with a destruction that never
 * happened.
 * <p>
 * <strong>The log-flood rule on this path was decided, not forgotten.</strong> Every rejection here is
 * recorded through {@link LogoutRejectionLog} carrying a bounded, non-sensitive
 * {@link LogoutRejection} reason — never token material. Because the path is reserved and
 * <em>unauthenticated</em>, both rejections this class can raise ({@code no-idp-destruction-capability}
 * and {@code missing-logout-token}) are attacker-triggerable, so both are <em>latched</em>: the first
 * occurrence of each reason in a process is recorded at {@code WARN} and every repeat drops to
 * {@code DEBUG}. That bounds an attacker to at most two {@code WARN} lines for the life of the process
 * while still surfacing a genuine first occurrence at the default log level — the earlier
 * unconditional-{@code DEBUG} rule bounded the flood too, but at the price of making a delivery that
 * arrived and was refused indistinguishable from one that never arrived at all. See
 * {@link LogoutRejectionLog} for the full rule, including why the signature-verified rejections the
 * receiver raises are <em>not</em> latched. Server-mode behaviour is unchanged.
 * <p>
 * The endpoint is framework-agnostic (raw form body in, a {@link BackchannelLogoutOutcome} the edge
 * renders out — no JAX-RS/Vert.x coupling), so it is unit-testable without a container or a live IdP;
 * the session runtime wires it to the request/response edge.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class BackchannelLogoutEndpoint {

    private static final CuiLogger LOGGER = new CuiLogger(BackchannelLogoutEndpoint.class);

    /** The single form parameter the OIDC back-channel logout request carries. */
    public static final String LOGOUT_TOKEN_PARAM = "logout_token";

    private static final int OK = 200;
    private static final int BAD_REQUEST = 400;
    private static final int NOT_FOUND = 404;

    private final BackchannelLogoutReceiver receiver;
    private final SessionBinding sessionBinding;
    private final LogoutRejectionLog rejectionLog = new LogoutRejectionLog(LOGGER);

    /**
     * Assembles the endpoint with the back-channel logout receiver and the active session binding
     * whose IdP-destruction capability gates the endpoint.
     *
     * @param receiver       the transport-free back-channel logout receiver (verify, validate, destroy)
     * @param sessionBinding the active session binding; a binding reporting
     *                       {@link SessionBinding.IdpDestruction#UNSUPPORTED} gates the endpoint to
     *                       {@code 404}
     */
    public BackchannelLogoutEndpoint(BackchannelLogoutReceiver receiver, SessionBinding sessionBinding) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
        this.sessionBinding = Objects.requireNonNull(sessionBinding, "sessionBinding");
    }

    /**
     * Handles one back-channel logout request: extracts the {@code logout_token} from the raw form
     * body and drives the receiver.
     *
     * @param rawFormBody the raw {@code application/x-www-form-urlencoded} request body, may be absent
     * @param now         the reference instant for the {@code iat} freshness check
     * @return {@code 404} when the active session binding cannot honour IdP-driven destruction,
     *         {@code 200} carrying the destroyed-session count on an accepted token, or {@code 400}
     *         when {@code logout_token} is absent or the token is rejected
     */
    public BackchannelLogoutOutcome receive(@Nullable String rawFormBody, Instant now) {
        Objects.requireNonNull(now, "now");

        if (sessionBinding.idpDestruction() == SessionBinding.IdpDestruction.UNSUPPORTED) {
            // Fail closed before the body is touched: the logout_token is never read and the receiver
            // is never reached, so the gateway cannot report a destruction it could not perform.
            // Latched, not unconditional: this path is reserved and unauthenticated — see the
            // log-flood rule on the type.
            rejectionLog.recordRejection(LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY);
            return BackchannelLogoutOutcome.error(NOT_FOUND);
        }

        Optional<String> logoutToken = extractLogoutToken(rawFormBody);
        if (logoutToken.isEmpty()) {
            rejectionLog.recordRejection(LogoutRejection.MISSING_LOGOUT_TOKEN);
            return BackchannelLogoutOutcome.error(BAD_REQUEST);
        }

        BackchannelLogoutReceiver.BackchannelResult result = receiver.receive(logoutToken.get(), now);
        if (!result.accepted()) {
            return BackchannelLogoutOutcome.error(BAD_REQUEST);
        }
        return BackchannelLogoutOutcome.accepted(result.destroyed());
    }

    private static Optional<String> extractLogoutToken(@Nullable String rawFormBody) {
        if (rawFormBody == null || rawFormBody.isBlank()) {
            return Optional.empty();
        }
        // Single-exit loop body: the two skip conditions are folded into one positive match test so
        // the scan carries no continue statements. Behaviour is unchanged and still fails closed — a
        // pair without a name (equals <= 0), a name whose percent-encoding is malformed, and a name
        // that is not logout_token are all simply not matched, so the scan falls through to an absent
        // value and the caller answers 400.
        for (String pair : rawFormBody.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && isLogoutTokenName(pair.substring(0, equals))) {
                return decode(pair.substring(equals + 1)).filter(value -> !value.isBlank());
            }
        }
        return Optional.empty();
    }

    /**
     * @return {@code true} when {@code rawName} decodes to exactly the {@code logout_token} parameter
     *         name; a malformed percent-encoded name decodes to an absent value and is never a match.
     */
    private static boolean isLogoutTokenName(String rawName) {
        return decode(rawName).filter(LOGOUT_TOKEN_PARAM::equals).isPresent();
    }

    private static Optional<String> decode(String value) {
        // Malformed percent-encoding (URLDecoder.decode throws IllegalArgumentException) is treated as
        // an absent parameter so the endpoint fails closed to 400 rather than surfacing a 500.
        try {
            return Optional.of(URLDecoder.decode(value, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformed) {
            LOGGER.debug(malformed, "Back-channel logout form value carried malformed percent-encoding — treated as absent");
            return Optional.empty();
        }
    }

    /**
     * The framework-agnostic result of a back-channel logout: the HTTP status the edge returns and,
     * on acceptance, how many server-side sessions were destroyed. A rejected or gated outcome
     * destroys nothing. Every outcome must be served uncacheable ({@code Cache-Control: no-store}) by
     * the edge.
     *
     * @param status    the HTTP status the edge returns ({@code 200} accepted, {@code 400} rejected,
     *                  {@code 404} gated off for a binding without IdP-destruction capability)
     * @param destroyed the number of sessions destroyed, always {@code 0} for a rejected outcome
     * @author API Sheriff Team
     * @since 1.0
     */
    public record BackchannelLogoutOutcome(int status, int destroyed) {

        /**
         * An accepted {@code 200} outcome carrying the destroyed-session count.
         *
         * @param destroyed the number of sessions destroyed
         * @return the accepted outcome
         */
        public static BackchannelLogoutOutcome accepted(int destroyed) {
            return new BackchannelLogoutOutcome(OK, destroyed);
        }

        /**
         * An error outcome carrying the given status and destroying nothing.
         *
         * @param status the {@code 4xx} status ({@code 400} rejected, {@code 404} gated off)
         * @return the error outcome
         */
        public static BackchannelLogoutOutcome error(int status) {
            return new BackchannelLogoutOutcome(status, 0);
        }

        /**
         * @return {@code true} when the back-channel logout token was accepted
         */
        public boolean isAccepted() {
            return status == OK;
        }
    }
}
