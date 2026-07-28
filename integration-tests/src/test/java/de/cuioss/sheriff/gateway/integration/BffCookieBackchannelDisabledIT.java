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
package de.cuioss.sheriff.gateway.integration;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the back-channel logout capability gate at the live edge, on both bindings side by side: the
 * cookie-mode instance ({@code api-sheriff-cookie}, {@code 10445}) answers {@code 404}, while the
 * landed server-mode instance ({@code 10443}) still answers its unchanged {@code 400} contract for
 * the same request.
 * <p>
 * <strong>Why {@code 404} and not {@code 501}.</strong> A stateless binding holds no server-side
 * session index, so it cannot honour an IdP-driven {@code sid}/{@code sub} destruction. Rather than
 * accept a post it could only answer with a destruction that never happened, the endpoint refuses
 * before the form body is parsed — the {@code logout_token} is never read. Reporting the reserved
 * path as simply absent is the honest answer to a relying party probing for the capability.
 * <p>
 * <strong>{@code Cache-Control: no-store} is the load-bearing assertion, not decoration.</strong> It
 * is what distinguishes the reserved endpoint <em>deliberately</em> answering {@code 404} from the
 * path having fallen through to the proxy route table and produced an incidental
 * {@code NO_ROUTE_MATCHED} {@code 404}. Those two are indistinguishable by status alone, and only
 * the first is the contract this suite exists to pin.
 * <p>
 * Both requests bind their instance origin explicitly rather than relying on the
 * {@code BaseIntegrationTest} defaults, because the whole point of the suite is the contrast between
 * two instances.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffCookieBackchannelDisabledIT {

    /** The reserved back-channel logout path both instances register (oidc.logout.backchannel_path). */
    private static final String BACKCHANNEL_PATH = "/auth/backchannel";

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    @Test
    @DisplayName("the cookie-mode gateway gates the back-channel endpoint off with 404 no-store")
    void cookieModeBackchannelIsGatedOff() {
        // Act
        Response response = given().relaxedHTTPSValidation()
                .baseUri(BffKeycloakLoginFlow.COOKIE_GATEWAY_ORIGIN)
                .contentType(FORM_CONTENT_TYPE)
                .when().post(BACKCHANNEL_PATH)
                .then().statusCode(404).extract().response();

        // Assert — no-store proves this is the reserved endpoint refusing, not a route-table miss.
        assertEquals("no-store", response.header("Cache-Control"),
                "the gated back-channel response must be the reserved endpoint's, served uncacheable");
    }

    @Test
    @DisplayName("the server-mode gateway still answers the same back-channel post with 400 no-store")
    void serverModeBackchannelContractIsUnchanged() {
        // Act — the identical request against the primary instance, whose store-backed binding does
        // support IdP-driven destruction and therefore reaches the fail-closed token validation.
        Response response = given().relaxedHTTPSValidation()
                .baseUri(BffKeycloakLoginFlow.GATEWAY_ORIGIN)
                .contentType(FORM_CONTENT_TYPE)
                .when().post(BACKCHANNEL_PATH)
                .then().statusCode(400).extract().response();

        // Assert — the gate is per-binding, so activating cookie mode on its own instance must leave
        // the landed server-mode contract untouched.
        assertEquals("no-store", response.header("Cache-Control"),
                "a back-channel logout response must be uncacheable in every outcome");
    }
}
