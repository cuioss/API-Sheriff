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
package de.cuioss.sheriff.gateway.integration;

import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.JWKS_PATH;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.MEDIATED_PATH;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.TOKEN_NOT_BOUND_RECORD;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.TOKEN_REFRESHED_RECORD;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.WAIT_INTO_REFRESH_WINDOW_SECONDS;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.assertFrontChannelRequestRefused;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.assertRecordCountAbove;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.bearerToken;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.confirmationThumbprint;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.mediatedCall;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.publicKeyThumbprint;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.recordCount;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.signingKeyFile;
import static de.cuioss.sheriff.gateway.integration.BffFapiControlsIT.sleepSeconds;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the client-secret mode of the BFF against the native image and a real identity provider, on
 * {@code api-sheriff-refresh} ({@link BffKeycloakLoginFlow#REFRESH_GATEWAY_ORIGIN}) — the one compose
 * gateway whose descriptor declares {@code oidc.client_secret}.
 * <p>
 * <strong>What the mode changes, and what it does not.</strong> A configured client secret selects
 * {@code client_secret_basic} for every authenticated back-channel call. The
 * authorization request is still pushed, every token request still carries a DPoP proof, and a token
 * response that is not bound to the proof key is still refused. Each test below states one of those
 * facts and carries the control that keeps it from passing for another reason.
 * <ul>
 *   <li><em>The boot warning.</em> The instance log carries the client-secret {@code WARN} record
 *       ({@value #CLIENT_SECRET_RECORD}) with the words that it is not FAPI 2.0 conformant, and does
 *       not contain the secret value. The control is the log of a key-authenticated instance, which
 *       carries no such record although it demonstrably booted — the warning follows the mode.</li>
 *   <li><em>The pushed request.</em> The login redirect of this instance carries exactly
 *       {@code client_id} and {@code request_uri}, and the login completes, while a hand-built
 *       front-channel authorization request for {@code refresh-client} without {@code request_uri} is
 *       refused by Keycloak.</li>
 *   <li><em>The sender constraint and the binding check.</em> The token mediated to the echo upstream
 *       carries a {@code cnf.jkt} equal to the thumbprint this test computes from the public block of
 *       the committed RSA proof key file, and the token rotated inside the near-expiry window carries
 *       the same {@code jkt}. So the code exchange and the refresh grant were both made with
 *       {@code client_secret_basic} <em>and</em> a DPoP proof, and Keycloak bound both tokens. Because
 *       this gateway refuses an unbound token response, the completed login and rotation are the proof
 *       in client-secret mode that the bound response is accepted on both token legs; the instance log
 *       gains a token-refreshed record ({@value BffFapiControlsIT#TOKEN_REFRESHED_RECORD}) and
 *       carries no refused-response record ({@value BffFapiControlsIT#TOKEN_NOT_BOUND_RECORD}).</li>
 *   <li><em>The key set.</em> {@code GET} and {@code POST} on {@code /auth/jwks} of this instance
 *       answer {@code 404}: there is no client-authentication key to publish. The control is the
 *       primary instance, which answers {@code 200} to {@code GET} on the same path.</li>
 * </ul>
 * <p>
 * <strong>What this suite does NOT prove.</strong> The three refresh outcomes of this instance are
 * {@code BffTokenRefreshIT}'s, and the replayed refresh token is {@code BffRefreshReuseIT}'s. That the
 * secret travels in the {@code Authorization} header only, form-encoded, and on every authenticated
 * leg, is asserted at unit level on the request a stub identity provider records
 * ({@code BffRuntimeProducerTest}); here the request is not observable, only that Keycloak accepted it.
 * <p>
 * The suite logs in as {@link BffKeycloakLoginFlow#REFRESH_USERNAME} and ends no session, its own
 * included: it revokes nothing at the identity provider. It does not extend
 * {@code BaseIntegrationTest}, which binds the primary instance's origin.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Client-secret mode: the boot warning, the pushed request, the DPoP binding and the withheld key set")
class BffClientSecretModeIT {

    private static final String ORIGIN = BffKeycloakLoginFlow.REFRESH_GATEWAY_ORIGIN;

    /** The client of this instance (see {@code sheriff-config-refresh/gateway.yaml}). */
    private static final String CLIENT_ID = "refresh-client";

    /** The value compose passes as {@code OIDC_CLIENT_SECRET}; a test-fixture value only. */
    private static final String CLIENT_SECRET = "refresh-secret";

    /** WARN — the BFF authenticates with a client secret. */
    private static final String CLIENT_SECRET_RECORD = "ApiSheriff-133";

    /** INFO — the effective default trust source; emitted at every boot of every instance. */
    private static final String BOOT_RECORD = "ApiSheriff-17:";

    private static final String NOT_CONFORMANT = "not FAPI 2.0 conformant";

    private static final String SECRET_INSTANCE_LOG = "quarkus-refresh.log";

    /** The log of a key-authenticated instance — the control for the boot warning. */
    private static final String KEY_INSTANCE_LOG = "quarkus-cookie-refresh.log";

    @Test
    @DisplayName("the instance log carries the client-secret warning without the secret; a key-authenticated instance logs none")
    void bootWarningFollowsTheMode() {
        assertAll("the warning is emitted in client-secret mode, and only there",
                () -> assertTrue(recordCount(SECRET_INSTANCE_LOG, CLIENT_SECRET_RECORD) > 0,
                        "the instance that authenticates with a secret must say so at boot"),
                () -> assertEquals(recordCount(SECRET_INSTANCE_LOG, CLIENT_SECRET_RECORD),
                        recordCount(SECRET_INSTANCE_LOG, NOT_CONFORMANT),
                        "every such record must state that the mode is not FAPI 2.0 conformant"),
                () -> assertEquals(0, recordCount(SECRET_INSTANCE_LOG, CLIENT_SECRET),
                        "no line of the instance log may carry the secret value"),
                () -> assertTrue(recordCount(KEY_INSTANCE_LOG, BOOT_RECORD) > 0,
                        "control: the key-authenticated instance booted and wrote its log"),
                () -> assertEquals(0, recordCount(KEY_INSTANCE_LOG, CLIENT_SECRET_RECORD),
                        "control: an instance that authenticates with a key must not log the warning"));
    }

    @Test
    @DisplayName("the login redirect carries client_id and request_uri only and the login completes; a front-channel request is refused")
    void authorizationRequestIsPushedInClientSecretMode() {
        Response initiation = BffKeycloakLoginFlow.gateway(Map.of(), ORIGIN)
                .header("Accept", "text/html")
                .redirects().follow(false)
                .when().get(MEDIATED_PATH)
                .then().statusCode(302)
                .extract().response();

        Session session = login();

        BffLoginInitiationIT.assertPushedRequestRedirect(initiation, CLIENT_ID);
        assertEquals("GET", mediatedCall(session.gatewayCookies(), ORIGIN).path("method"),
                "a login started with a request pushed under client_secret_basic must complete");
        assertFrontChannelRequestRefused(CLIENT_ID, ORIGIN + "/auth/callback");
    }

    @Test
    @DisplayName("the mediated and the rotated token carry the jkt of the RSA proof key, and no token response is refused")
    void bothTokenLegsAreBoundToTheProofKeyInClientSecretMode() throws Exception {
        Session session = login();
        String atLogin = bearerToken(mediatedCall(session.gatewayCookies(), ORIGIN));
        long refreshedBefore = recordCount(SECRET_INSTANCE_LOG, TOKEN_REFRESHED_RECORD);

        sleepSeconds(WAIT_INTO_REFRESH_WINDOW_SECONDS);
        String rotated = bearerToken(mediatedCall(session.gatewayCookies(), ORIGIN));

        String proofKey = publicKeyThumbprint(signingKeyFile("dpop-rsa.pem"));
        assertNotEquals(atLogin, rotated, "the call inside the near-expiry window must mediate a rotated token");
        assertAll("a secret-authenticated client's access tokens are bound like any other's",
                () -> assertEquals(proofKey, confirmationThumbprint(atLogin),
                        "the code exchange carried a proof: its token must name the committed RSA proof key"),
                () -> assertEquals(proofKey, confirmationThumbprint(rotated),
                        "the refresh grant carried a proof: its token must name the same key"));
        assertRecordCountAbove(SECRET_INSTANCE_LOG, TOKEN_REFRESHED_RECORD, refreshedBefore,
                "the rotation must be recorded, or the absence asserted next was read from a log that shows nothing");
        assertEquals(0, recordCount(SECRET_INSTANCE_LOG, TOKEN_NOT_BOUND_RECORD),
                "the gateway accepted Keycloak's bound responses on both legs, so it recorded no refusal");
    }

    @Test
    @DisplayName("the JWKS path answers 404 to GET and POST, while the primary instance publishes its key there")
    void jwksPathAnswersNotFoundAndPublishesNothing() {
        int get = BffKeycloakLoginFlow.gateway(Map.of(), ORIGIN).when().get(JWKS_PATH).statusCode();
        int post = BffKeycloakLoginFlow.gateway(Map.of(), ORIGIN).when().post(JWKS_PATH).statusCode();
        int primary = BffKeycloakLoginFlow.gateway(Map.of()).when().get(JWKS_PATH).statusCode();

        assertAll("there is no client-authentication key to publish",
                () -> assertEquals(404, get, "GET on the reserved path must answer 404 in client-secret mode"),
                () -> assertEquals(404, post, "and so must POST: the path answers 404 to every method"),
                () -> assertEquals(200, primary,
                        "control: a key-authenticated instance answers 200 on the same path"));
    }

    private static Session login() {
        return BffKeycloakLoginFlow.login(MEDIATED_PATH, ORIGIN,
                BffKeycloakLoginFlow.REFRESH_USERNAME, BffKeycloakLoginFlow.REFRESH_PASSWORD);
    }
}
