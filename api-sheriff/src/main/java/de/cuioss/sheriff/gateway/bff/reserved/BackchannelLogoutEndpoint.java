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
package de.cuioss.sheriff.gateway.bff.reserved;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.logout.BackchannelLogoutReceiver;
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

    private final BackchannelLogoutReceiver receiver;

    /**
     * Assembles the endpoint with the back-channel logout receiver.
     *
     * @param receiver the transport-free back-channel logout receiver (verify, validate, destroy)
     */
    public BackchannelLogoutEndpoint(BackchannelLogoutReceiver receiver) {
        this.receiver = Objects.requireNonNull(receiver, "receiver");
    }

    /**
     * Handles one back-channel logout request: extracts the {@code logout_token} from the raw form
     * body and drives the receiver.
     *
     * @param rawFormBody the raw {@code application/x-www-form-urlencoded} request body, may be absent
     * @param now         the reference instant for the {@code iat} freshness check
     * @return {@code 200} carrying the destroyed-session count on an accepted token, or {@code 400}
     *         when {@code logout_token} is absent or the token is rejected
     */
    public BackchannelLogoutOutcome receive(@Nullable String rawFormBody, Instant now) {
        Objects.requireNonNull(now, "now");

        Optional<String> logoutToken = extractLogoutToken(rawFormBody);
        if (logoutToken.isEmpty()) {
            LOGGER.debug("Back-channel logout request missing the logout_token form parameter — rejected");
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
        for (String pair : rawFormBody.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if (LOGOUT_TOKEN_PARAM.equals(decode(pair.substring(0, equals)))) {
                String value = decode(pair.substring(equals + 1));
                return value.isBlank() ? Optional.empty() : Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    /**
     * The framework-agnostic result of a back-channel logout: the HTTP status the edge returns and,
     * on acceptance, how many server-side sessions were destroyed. A rejected outcome destroys nothing.
     * Both outcomes must be served uncacheable ({@code Cache-Control: no-store}) by the edge.
     *
     * @param status    the HTTP status the edge returns ({@code 200} accepted, {@code 400} rejected)
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
         * @param status the {@code 4xx} status
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
