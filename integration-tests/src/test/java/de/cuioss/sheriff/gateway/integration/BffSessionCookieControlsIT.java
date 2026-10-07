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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves four properties of the server-mode session and its cookie on the native image, through the
 * primary gateway instance and the compose Keycloak. Each is a rule the gateway applies on its own,
 * with no key declared for it in the primary stack's {@code gateway.yaml}.
 * <ul>
 *   <li><em>One subject holds at most {@value #DEFAULT_SESSIONS_PER_SUBJECT} sessions.</em> The
 *       primary descriptor declares no {@code oidc.session.max_sessions_per_subject}, so the default
 *       applies. The test logs one user in that many times, shows that the first session is still
 *       served, logs in once more, and then finds the first session — the one that logged in first —
 *       answered as unauthenticated while every later one is served.</li>
 *   <li><em>A login ends the session the callback's own cookie resolves.</em> A browser that arrives
 *       at the callback of a new login while it holds the cookie of a live session leaves with the
 *       new session only: the cookie value of the earlier session is answered as unauthenticated.</li>
 *   <li><em>An upstream cannot set the session cookie.</em> The go-httpbin upstream is made to answer
 *       two {@code Set-Cookie} lines, one naming the gateway's session cookie and one naming a cookie
 *       of its own. Only the second reaches the client — which is also the control that the route
 *       relays an upstream's {@code Set-Cookie} lines at all.</li>
 *   <li><em>An HTTP/2 request may carry its cookies in more than one {@code cookie} field.</em> RFC
 *       9113 section 8.2.3 lets a client split the header, and a gateway that read the first field
 *       alone would not find a session cookie sent in the second. The request is made with the JDK
 *       client, which sends each value of a repeated header as a field of its own, against the
 *       listener the image actually serves; the same split without the session cookie is the
 *       control.</li>
 * </ul>
 * <p>
 * <strong>The sessions of this suite and the other suites.</strong> The per-subject test logs
 * {@code integration-user} in {@value #DEFAULT_SESSIONS_PER_SUBJECT} times and once more, so sessions
 * of that user which earlier suites left on the instance are ended by it. That is harmless by the
 * rule itself: every suite establishes the session it asserts on inside its own test, and none keeps
 * more than a handful at a time. The eleven sessions of the test are the eleven newest of the user at
 * every point the test asserts, whatever was there before.
 * <p>
 * Like every {@code Bff*IT}, the suite sends cookie values itself and asserts nothing about browser
 * cookie policy (see {@link BffKeycloakLoginFlow}).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Server-mode session and session-cookie rules on the native image")
@Timeout(180) // per test: the largest one logs in eleven times, each login well inside a second
class BffSessionCookieControlsIT {

    /** The default session-cookie name; the primary stack's {@code gateway.yaml} declares no other. */
    private static final String SESSION_COOKIE = "__Host-sheriff-session";

    /** A {@code require: session} route of the primary instance. */
    private static final String SESSION_ROUTE = "/bff-session/get";

    /**
     * The route whose upstream, go-httpbin's {@code /response-headers}, answers every query parameter
     * back as a response header (see {@code endpoints/origin-headers.yaml}).
     */
    private static final String UPSTREAM_HEADERS_ROUTE = "/proxy/origin-headers";

    /** A cookie of the upstream's own, which the gateway has no claim on. */
    private static final String UPSTREAM_COOKIE_LINE = "upstream-preference=compact";

    /**
     * The bound that applies when {@code oidc.session.max_sessions_per_subject} is omitted. Stated
     * here as the test's own number: this module carries no gateway class on its classpath.
     */
    private static final int DEFAULT_SESSIONS_PER_SUBJECT = 10;

    private static final String SET_COOKIE = "Set-Cookie";
    private static final String COOKIE = "Cookie";

    private static final int OK = 200;
    private static final int UNAUTHENTICATED = 401;

    @Test
    @DisplayName("a login beyond the default per-subject bound ends the subject's first session and no other")
    void loginBeyondThePerSubjectBoundEndsTheFirstSession() {
        List<Session> sessions = new ArrayList<>();
        for (int login = 0; login < DEFAULT_SESSIONS_PER_SUBJECT; login++) {
            sessions.add(BffKeycloakLoginFlow.login(SESSION_ROUTE));
        }
        assertEquals(OK, status(sessions.getFirst()), "precondition: with " + DEFAULT_SESSIONS_PER_SUBJECT
                + " sessions the subject is at its bound and not beyond it, so its first session is served");

        Session beyondTheBound = BffKeycloakLoginFlow.login(SESSION_ROUTE);

        assertAll("the sessions of one subject after login " + (DEFAULT_SESSIONS_PER_SUBJECT + 1),
                () -> assertEquals(UNAUTHENTICATED, status(sessions.getFirst()),
                        "the login beyond the bound must end the session that logged in first"),
                () -> assertEquals(OK, status(beyondTheBound), "the login beyond the bound must itself succeed"),
                () -> assertAll(IntStream.range(1, sessions.size()).mapToObj(index -> () -> assertEquals(OK,
                        status(sessions.get(index)),
                        "session " + (index + 1) + " logged in after the first and must be untouched"))));
    }

    @Test
    @DisplayName("a login whose callback presents the cookie of a live session ends that session")
    void loginEndsTheSessionItsCallbackPresents() {
        Session earlier = BffKeycloakLoginFlow.login(SESSION_ROUTE);
        String earlierCookie = earlier.gatewayCookies().get(SESSION_COOKIE);
        assertNotNull(earlierCookie, "precondition: the login must set " + SESSION_COOKIE);
        assertEquals(OK, status(earlier), "precondition: the earlier session must be live when the callback arrives");

        Session later = BffKeycloakLoginFlow.loginPresentingAtCallback(SESSION_ROUTE,
                BffKeycloakLoginFlow.GATEWAY_ORIGIN, Map.of(SESSION_COOKIE, earlierCookie));

        String laterCookie = later.gatewayCookies().get(SESSION_COOKIE);
        assertNotNull(laterCookie, "the later login must set " + SESSION_COOKIE);
        // Compared on a boolean, so neither cookie value reaches a failure message.
        assertFalse(earlierCookie.equals(laterCookie), "the later login must establish a session of its own");
        assertAll("the two sessions after the later login",
                () -> assertEquals(UNAUTHENTICATED, status(earlier),
                        "the session whose cookie the callback presented must be ended by the login"),
                () -> assertEquals(OK, status(later), "the session the login established must be served"));
    }

    @Test
    @DisplayName("an upstream Set-Cookie line naming the session cookie is dropped, its own cookie is relayed")
    void upstreamCannotSetTheSessionCookie() {
        Response response = BffKeycloakLoginFlow.gateway(Map.of())
                .queryParam(SET_COOKIE, SESSION_COOKIE + "=set-by-the-upstream", UPSTREAM_COOKIE_LINE)
                .when().get(UPSTREAM_HEADERS_ROUTE)
                .then().extract().response();

        assertEquals(OK, response.statusCode(), "precondition: the upstream must answer the request");
        assertEquals(List.of(UPSTREAM_COOKIE_LINE), response.getHeaders().getValues(SET_COOKIE),
                "the upstream's own cookie must be relayed, and the line naming " + SESSION_COOKIE + " must not");
    }

    @Test
    @DisplayName("an HTTP/2 request carrying the session cookie in a second cookie field is served")
    void sessionCookieInASecondHttp2CookieFieldIsRead() throws Exception {
        Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE);
        String sessionCookie = session.gatewayCookies().get(SESSION_COOKIE);
        assertNotNull(sessionCookie, "precondition: the login must set " + SESSION_COOKIE);
        HttpClient client = LocalStackTls.clientBuilder().version(HttpClient.Version.HTTP_2).build();

        HttpResponse<String> split = client.send(sessionRouteRequest("sheriff-it-first=1",
                SESSION_COOKIE + "=" + sessionCookie), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> withoutTheSessionCookie = client.send(sessionRouteRequest("sheriff-it-first=1",
                "sheriff-it-second=2"), HttpResponse.BodyHandlers.ofString());

        assertAll("two cookie fields on one HTTP/2 request",
                () -> assertEquals(HttpClient.Version.HTTP_2, split.version(),
                        "precondition: the listener must negotiate HTTP/2, otherwise the client folds the two "
                                + "values into one HTTP/1.1 header line and nothing is proven"),
                () -> assertEquals(OK, split.statusCode(),
                        "the session cookie must be read from the second cookie field"),
                () -> assertEquals(UNAUTHENTICATED, withoutTheSessionCookie.statusCode(),
                        "control: the same two fields without the session cookie carry no session"));
    }

    private static HttpRequest sessionRouteRequest(String firstCookieField, String secondCookieField) {
        return HttpRequest.newBuilder(URI.create(BffKeycloakLoginFlow.GATEWAY_ORIGIN + SESSION_ROUTE))
                .header("Accept", "application/json")
                .header(COOKIE, firstCookieField)
                .header(COOKIE, secondCookieField)
                .timeout(Duration.ofSeconds(BffKeycloakLoginFlow.RESPONSE_TIMEOUT_SECONDS))
                .GET()
                .build();
    }

    private static int status(Session session) {
        return BffKeycloakLoginFlow.gateway(session.gatewayCookies())
                .header("Accept", "application/json")
                .redirects().follow(false)
                .when().get(SESSION_ROUTE)
                .then().extract().statusCode();
    }
}
