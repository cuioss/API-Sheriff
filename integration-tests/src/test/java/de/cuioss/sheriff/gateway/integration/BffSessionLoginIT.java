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

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the full server-mode BFF authorization-code login through the reserved {@code /auth/callback}
 * against the compose Keycloak {@code integration} realm.
 * <p>
 * The scripted browser-less flow ({@link BffKeycloakLoginFlow}) navigates onto the require:session
 * {@code /bff-session} route, follows the {@code 302} into Keycloak, submits the seeded
 * {@code integration-user} credentials, and follows the callback back to the gateway. This suite
 * asserts where that callback sends the browser. That the login created a usable server-side session —
 * the gateway set a session cookie on the callback response, and replaying that cookie jar on the
 * protected route serves the upstream — is asserted by
 * {@link BffSessionMediationIT#mediatedSessionStaysUsableAcrossRequests()}; a fresh, cookieless
 * request being challenged is covered there too.
 */
class BffSessionLoginIT extends BaseIntegrationTest {

    @Test
    @DisplayName("the login returns the browser to exactly the requested path and raw query")
    void loginReturnsToRequestedPathAndRawQuery() {
        // A query with two parameters and a percent-encoded '/' in a value: the gateway must record the
        // canonical path plus the raw query byte for byte, never a re-encoded or re-ordered rendering.
        // The start path is sent unencoded by the login flow, so %2F reaches the gateway as sent.
        String requested = "/bff-session/liste/1?tab=a&x=%2F";

        Session session = BffKeycloakLoginFlow.login(requested);

        assertEquals(requested, session.callbackLocation(),
                "after the Keycloak login the callback must redirect to exactly the requested path and query");
    }
}
