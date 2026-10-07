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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;
import de.cuioss.sheriff.gateway.integration.StubIdentityProviderRig.Endpoint;
import de.cuioss.sheriff.gateway.integration.StubIdentityProviderRig.RecordedRequest;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the {@code scope} form parameter of the refresh grant <em>on the wire</em>, read from the
 * journal of a stub identity provider ({@link StubIdentityProviderRig}), for both triggers of a
 * refresh: the near-expiry leg and the scope-driven leg (ADR-0057).
 * <p>
 * <strong>Why the stub.</strong> {@code BffTokenRefreshIT.refreshedOutcomeKeepsTheEndpointScope} reads
 * the scope of the token Keycloak grants after a refresh. A granted token cannot tell a grant that
 * named the session's scope from one that carried no {@code scope} parameter at all — RFC 6749 §6
 * makes an omitted {@code scope} mean "the scope originally granted" — and Keycloak cannot be made to
 * answer a refresh with a narrower {@code scope} than it was asked for. Only a recorded request shows
 * what was sent, and only a scripted answer can narrow what is granted.
 * <p>
 * <strong>What this suite proves.</strong> One session, four mediated calls, three recorded refresh
 * grants, each asserted literally:
 * <ol>
 *   <li>The login starts on the scoped session route, so the pushed authorization request asks for
 *       {@code oidc.scopes} plus {@code sheriff_it_endpoint} and the session's active and granted scope
 *       sets are that set. Its access token is minted inside the refresh leeway.</li>
 *   <li>The first call refreshes because the token is near expiry. The <em>first recorded grant</em>
 *       carries {@code scope} equal to the full active set. The stub answers it with the narrowed set
 *       {@code oidc.scopes} and another token inside the leeway.</li>
 *   <li>The second call refreshes for the same reason. The <em>second recorded grant</em> carries the
 *       narrowed set: the active set followed the answer of the first grant, and the granted set was
 *       not used in its place. The stub answers with a token far from expiry.</li>
 *   <li>The third call is on the scoped route. The token is not near expiry, the route's scope is
 *       missing from the active set and lies inside the granted set, so the gateway refreshes for the
 *       scope. The <em>third recorded grant</em> carries the narrowed set plus the missing scope.</li>
 *   <li>Control: a fourth call, on the plain route, records no further grant — the three counted
 *       above are refreshes the session's state caused, and a session that needs nothing asks for
 *       nothing.</li>
 * </ol>
 * The first two calls are made on the plain session route on purpose. On the scoped route the second
 * one would be followed, within the same request, by the scope-driven refresh, and the two legs could
 * no longer be read apart.
 * <p>
 * <strong>What this suite does NOT prove.</strong> It does not prove anything about a real identity
 * provider: the stub validates neither the client credential, nor the refresh token, nor the DPoP
 * proof, and grants whatever it was scripted to grant. It does not exercise a scope outside the granted
 * set (a widening), a refused scope refresh, or cookie mode. The order of the scope names on the wire
 * is not part of the contract and is not asserted; that no name is sent twice is. Like every
 * {@code Bff*IT} it replays a cookie map and asserts nothing about browser cookie policy.
 * <p>
 * The suite does not extend {@code BaseIntegrationTest}: every request goes to the rig's own gateway.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class BffRefreshScopeWireIT {

    /** The plain session route: it needs {@code oidc.scopes} and nothing else. */
    private static final String PLAIN_SESSION_PATH = "/bff-session/get";

    /** The scoped session route: it needs {@code oidc.scopes} plus {@code sheriff_it_endpoint}. */
    private static final String SCOPED_SESSION_PATH = "/bff-session/scoped/get";

    /**
     * The scope set a login on the scoped route asks for, spelled out: this suite asserts what is sent
     * on the wire, so it states the names itself instead of reading them from another suite.
     */
    private static final Set<String> FULL_SCOPES = Set.of("openid", "profile", "email", "sheriff_it_endpoint");

    /** What the stub narrows the session to: {@code oidc.scopes}, without the endpoint scope. */
    private static final List<String> NARROWED_SCOPES = List.of("openid", "profile", "email");

    /** {@link #NARROWED_SCOPES} plus the one scope the scoped route then misses. */
    private static final List<String> NARROWED_PLUS_MISSING_SCOPES =
            List.of("openid", "profile", "email", "sheriff_it_endpoint");

    /**
     * A token lifetime below the descriptor's {@code session.refresh.leeway_seconds} of 30, so a
     * session holding such a token is inside the near-expiry window from the moment it is issued, and
     * long enough that the token has not expired when the next call arrives.
     */
    private static final Duration INSIDE_THE_LEEWAY = Duration.ofSeconds(20);

    /** A token lifetime far above the leeway, so no near-expiry refresh is due. */
    private static final Duration FAR_FROM_EXPIRY = Duration.ofMinutes(10);

    private static final String PARAM_SCOPE = "scope";

    @Test
    @DisplayName("the refresh grant carries the active scope set, follows a narrowed grant, and adds the missing scope")
    void refreshGrantsCarryTheSessionScopeOnTheWire() {
        try (StubIdentityProviderRig rig = StubIdentityProviderRig.start()) {
            Session session = rig.login(SCOPED_SESSION_PATH, INSIDE_THE_LEEWAY);
            RecordedRequest pushed = rig.received(Endpoint.PUSHED_AUTHORIZATION_REQUEST).getLast();
            assertEquals(FULL_SCOPES, scopeOf(pushed, "the pushed authorization request of the login"),
                    "the login on the scoped route must ask for oidc.scopes plus the endpoint scope; "
                            + "that set is the session's active and granted scope set");
            assertEquals(0, rig.refreshGrants().size(), "the login itself must not have refreshed");

            rig.script(Endpoint.TOKEN, rig.tokenAnswer(NARROWED_SCOPES, INSIDE_THE_LEEWAY));
            String afterFirstRefresh = mediatedBearer(session, PLAIN_SESSION_PATH);
            List<RecordedRequest> afterFirstCall = rig.refreshGrants();
            assertEquals(1, afterFirstCall.size(),
                    "the first call lands inside the leeway and must record exactly one refresh grant");
            assertEquals(FULL_SCOPES, scopeOf(afterFirstCall.get(0), "the first recorded refresh grant"),
                    "the near-expiry refresh must send the session's active scope set as its scope parameter");
            assertEquals(Set.copyOf(NARROWED_SCOPES), BffEndpointScopesIT.grantedScopes(afterFirstRefresh),
                    "the first call must mediate the token of the narrowed answer, otherwise the grant "
                            + "below would not be the one that follows a narrowed scope");

            rig.script(Endpoint.TOKEN, rig.tokenAnswer(NARROWED_SCOPES, FAR_FROM_EXPIRY));
            String afterSecondRefresh = mediatedBearer(session, PLAIN_SESSION_PATH);
            List<RecordedRequest> afterSecondCall = rig.refreshGrants();
            assertEquals(2, afterSecondCall.size(),
                    "the second call lands inside the leeway of the rotated token and must record a second grant");
            assertEquals(Set.copyOf(NARROWED_SCOPES),
                    scopeOf(afterSecondCall.get(1), "the second recorded refresh grant"),
                    "after a refresh answered with a narrower scope the next near-expiry refresh must send "
                            + "the narrowed active set, not the set the session was granted at login");
            assertNotEquals(afterFirstRefresh, afterSecondRefresh, "the second refresh must rotate the bearer");

            rig.script(Endpoint.TOKEN, rig.tokenAnswer(NARROWED_PLUS_MISSING_SCOPES, FAR_FROM_EXPIRY));
            String afterScopeRefresh = mediatedBearer(session, SCOPED_SESSION_PATH);
            List<RecordedRequest> afterThirdCall = rig.refreshGrants();
            assertEquals(3, afterThirdCall.size(), "the call on the scoped route must record exactly one "
                    + "further grant: its token is far from expiry, so only the missing scope causes it");
            assertEquals(Set.copyOf(NARROWED_PLUS_MISSING_SCOPES),
                    scopeOf(afterThirdCall.get(2), "the third recorded refresh grant"),
                    "the scope-driven refresh must send the narrowed active set plus the missing scope");
            assertEquals(Set.copyOf(NARROWED_PLUS_MISSING_SCOPES), BffEndpointScopesIT.grantedScopes(afterScopeRefresh),
                    "the scoped route must be served with the token the scope-driven refresh obtained");

            mediatedBearer(session, PLAIN_SESSION_PATH);
            assertEquals(3, rig.refreshGrants().size(), "control: a call that is neither near expiry nor "
                    + "short of a scope must record no refresh grant");
        }
    }

    /**
     * Makes one mediated call on the rig's gateway and returns the bearer it relayed, as the echo
     * upstream reports it.
     */
    private static String mediatedBearer(Session session, String path) {
        Response response = BffKeycloakLoginFlow.gateway(session.gatewayCookies(), StubIdentityProviderRig.ORIGIN)
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when().get(path)
                .then().extract().response();
        assertEquals(200, response.statusCode(), () -> "the session must be served on " + path + ". "
                + OneOffGatewayContainers.gatewayLog(StubIdentityProviderRig.GATEWAY));
        return BffEndpointScopesIT.mediatedAuthorization(response);
    }

    /**
     * The scope names a recorded request carries as its {@code scope} form parameter.
     *
     * @param request the recorded request
     * @param what    which recorded request this is, for the failure message
     * @return the names, as a set; a request without the parameter, or one naming a scope twice, fails
     */
    private static Set<String> scopeOf(RecordedRequest request, String what) {
        String scope = request.form().get(PARAM_SCOPE);
        assertNotNull(scope, () -> what + " carries no scope form parameter; its parameters are "
                + request.form().keySet());
        List<String> names = List.of(scope.split(" "));
        Set<String> distinct = new TreeSet<>(names);
        assertEquals(names.size(), distinct.size(), () -> what + " names a scope more than once: " + scope);
        return distinct;
    }
}
