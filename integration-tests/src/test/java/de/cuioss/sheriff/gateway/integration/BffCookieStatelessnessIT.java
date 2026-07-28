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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.Response;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the defining property of the Variant 3 sealed-cookie session — <em>statelessness</em> — as a
 * literal two-instance fact: a session created against one cookie-mode gateway is served by a
 * <strong>second, separate</strong> gateway process that shares nothing with the first but the AES
 * sealing key.
 * <p>
 * <strong>Why two real instances and not one.</strong> Everything a single-instance suite can observe
 * about a sealed cookie — that it is opaque, that it round-trips, that it survives a restart — is also
 * true of an implementation that quietly kept server-side state. The only observation that rules that
 * out is another process, which never saw the login, serving the very same cookie. The compose stack
 * therefore publishes {@code api-sheriff-cookie} on {@code 10445} and its key-sharing peer
 * {@code api-sheriff-cookie-2} on {@code 10446}: same image, same overlaid cookie {@code gateway.yaml},
 * identical {@code SESSION_ENCRYPTION_KEY}, and <em>no</em> shared session store, volume, or state.
 * <p>
 * <strong>No sticky routing.</strong> Each test addresses the two instances by their own published
 * host ports, so there is no load balancer and no affinity mechanism in the path that a passing result
 * could be attributed to — the request either reaches the peer directly or it does not reach it at all.
 * <p>
 * <strong>The peer is never logged into.</strong> The login round trip runs exclusively against the
 * first instance; the peer only ever receives an already-sealed cookie on a steady-state {@code GET}.
 * That is deliberate: the peer's mounted descriptor names {@code 10445} as its {@code redirect_uri}
 * and CSRF trusted origin — it is the same overlay, verbatim — and portability of an existing session,
 * not a second login surface, is the property under test.
 * <p>
 * {@link #thePeerRefusesACookieItCannotUnseal()} is what gives the two preceding assertions their
 * force: it establishes that the peer genuinely verifies the sealed material rather than admitting any
 * cookie-shaped value, so being served by the peer is evidence about the key, not about laxness.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffCookieStatelessnessIT {

    /** The cookie-mode instance the session is created against ({@code api-sheriff-cookie}, 10445). */
    private static final String ORIGIN = BffKeycloakLoginFlow.COOKIE_GATEWAY_ORIGIN;

    /** The key-sharing peer that never saw the login ({@code api-sheriff-cookie-2}, 10446). */
    private static final String PEER_ORIGIN = BffKeycloakLoginFlow.COOKIE_GATEWAY_PEER_ORIGIN;

    /** The {@code __Host-}-prefixed default session-cookie name (the cookie gateway.yaml sets none). */
    private static final String SESSION_COOKIE = "__Host-sheriff-session";

    /** The require:session route mediating a bearer to the go-httpbin echo upstream. */
    private static final String SESSION_ROUTE = "/bff-session/get";

    /** The reserved session/user-info fold, whose curated view carries the session's identity. */
    private static final String USER_INFO = "/auth/userinfo";

    @Test
    @DisplayName("a session sealed by one instance is served by a second instance sharing only the key")
    void aSessionSealedByOneInstanceIsServedByThePeer() {
        // Arrange — the login round trip touches ONLY the first instance.
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, ORIGIN);
        assertNotNull(session.gatewayCookies().get(SESSION_COOKIE),
                "the login must establish a sealed session cookie on the first instance");

        // Act — replay the sealed jar against the peer, addressed by its own published port.
        Response peerResponse = BffKeycloakLoginFlow.gateway(session.gatewayCookies(), PEER_ORIGIN)
                .when().get(SESSION_ROUTE)
                .then().statusCode(200).extract().response();

        // Assert — the peer authenticated the request and mediated a bearer upstream, having held no
        // server-side record of this session at any point. Only the shared key can account for that.
        assertEquals("GET", peerResponse.path("method"),
                "the peer must serve the mediated request for a session it never created");
        Object authorization = peerResponse.path("headers.Authorization");
        assertNotNull(authorization, "the peer must mediate a bearer unsealed from the portable cookie");
        assertTrue(authorization.toString().contains("Bearer"),
                "the mediated upstream credential must be a bearer token");
        assertNull(peerResponse.path("headers.Cookie"),
                "the sealed session cookie must never be forwarded upstream");
    }

    @Test
    @DisplayName("both instances resolve one sealed cookie to the same authenticated identity")
    void bothInstancesResolveTheSameIdentity() {
        // Arrange
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, ORIGIN);

        // Act — the curated user-info view from each instance for the SAME cookie.
        String identityOnOrigin = userInfoIdentity(session.gatewayCookies(), ORIGIN);
        String identityOnPeer = userInfoIdentity(session.gatewayCookies(), PEER_ORIGIN);

        // Assert — this is what rules out "the peer minted something of its own": a fresh or anonymous
        // session could not reproduce the seeded login identity, and an unauthenticated one would have
        // been refused 401 by the fold before any claim was disclosed.
        assertEquals("integration-user", identityOnOrigin,
                "the sealing instance must resolve the cookie to the seeded login identity");
        assertEquals(identityOnOrigin, identityOnPeer,
                "the peer must unseal the very same session, not establish an identity of its own");
    }

    @Test
    @DisplayName("the peer refuses a cookie it cannot unseal, so being served is evidence about the key")
    void thePeerRefusesACookieItCannotUnseal() {
        // Arrange — the same sealed jar, with the authentication tag broken.
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE, ORIGIN);
        Map<String, String> tamperedJar = new HashMap<>(session.gatewayCookies());
        tamperedJar.put(SESSION_COOKIE, tamper(session.gatewayCookies().get(SESSION_COOKIE)));

        // Act + Assert — the peer verifies rather than trusts: a cookie whose GCM tag no longer
        // authenticates is refused fail-closed. Without this, "the peer served it" would be equally
        // consistent with a peer that admits anything cookie-shaped.
        BffKeycloakLoginFlow.gateway(tamperedJar, PEER_ORIGIN)
                .header("Accept", "application/json")
                .when().get(SESSION_ROUTE)
                .then().statusCode(401);
    }

    /**
     * Reads the session's {@code preferred_username} out of the curated user-info view served by the
     * given instance for the supplied cookie jar.
     *
     * @param cookies the sealed gateway cookie jar
     * @param origin  the gateway instance to address
     * @return the authenticated identity the instance resolved the sealed cookie to
     */
    private static String userInfoIdentity(Map<String, String> cookies, String origin) {
        return BffKeycloakLoginFlow.gateway(cookies, origin)
                .header("Accept", "application/json")
                .when().get(USER_INFO)
                .then().statusCode(200).extract().path("claims.preferred_username");
    }

    /**
     * Flips a bit inside the trailing 16-byte GCM authentication tag.
     * <p>
     * The mutation is applied to the <em>decoded</em> bytes rather than to the final base64url
     * character, because editing that character is not reliably a mutation at all: when the sealed
     * length is not a multiple of three, the final character carries 2 or 4 unused padding bits that
     * {@link Base64#getUrlDecoder()} discards without validating. Flipping only those bits — which is
     * what {@code 'A' -> 'B'} does — re-decodes to the identical ciphertext and tag, so the peer
     * unseals it successfully and answers 200. Mutating a decoded tag byte is unambiguous.
     *
     * @param sealed the sealed cookie value
     * @return the tampered value, still in the base64url alphabet
     */
    private static String tamper(String sealed) {
        assertNotNull(sealed, "the login must establish a sealed session cookie");
        byte[] raw = Base64.getUrlDecoder().decode(sealed);
        raw[raw.length - 1] ^= 0x01;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
