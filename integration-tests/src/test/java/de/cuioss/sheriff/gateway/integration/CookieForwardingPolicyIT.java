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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import de.cuioss.sheriff.gateway.integration.BffKeycloakLoginFlow.Session;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Proves the per-route {@code cookies} policy end to end on the native image, through the primary
 * gateway instance and the go-httpbin upstream, against the routes of
 * {@code endpoints/cookie-policy.yaml}.
 * <ul>
 *   <li><em>Request direction.</em> The default {@code allow: none} forwards no browser cookie, a list
 *       forwards exactly the names it lists, and {@code all} forwards every cookie but the gateway's
 *       own — the BFF session cookie included, which stays at the gateway on a {@code require: session}
 *       route that forwards everything else.</li>
 *   <li><em>Response direction.</em> An upstream {@code Set-Cookie} line outside the route's policy is
 *       not relayed, and a relayed line loses its {@code Domain} attribute.</li>
 *   <li><em>The same-origin check.</em> A cross-site unsafe request carrying a cookie the route would
 *       forward is refused before it reaches the upstream — before the trust boundary, so the shipped
 *       default answers the refusal as the unrouted {@code 404} (ADR-0073), the matched same-origin
 *       request being the control that ties it to the check; an {@code additional_origins}
 *       entry admits its origin; {@code same_origin_check: false} admits a cross-site request; and on
 *       one route the check keys on the forwardable cookie, not on the route: the same cross-site
 *       request without it is forwarded.</li>
 * </ul>
 * <p>
 * Each new key has an assertion that goes red if the key is deleted from the descriptor — the
 * question {@code doc/development/declared-limit-assertion-coverage.adoc} asks of every declared
 * control. Deleting a route's {@code allow} turns it into {@code none}, which the list and
 * {@code all} cases see; deleting {@code additional_origins} refuses the partner origin; deleting
 * {@code same_origin_check: false} refuses the unchecked route's cross-site request.
 * <p>
 * The upstream echo is go-httpbin's {@code /anything}, which answers the request headers it received;
 * the Cookie header the gateway rebuilt is read from that echo. {@code /response-headers} answers
 * every query parameter back as a response header, which is how the upstream is made to send the
 * {@code Set-Cookie} lines the response cases name.
 * <p>
 * Like every IT that logs in through {@link BffKeycloakLoginFlow}, the suite sends cookie values
 * itself and asserts nothing about browser cookie policy.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The per-route cookies policy on the native image")
@Timeout(120) // per test: the slowest one logs in once, well inside a second
class CookieForwardingPolicyIT {

    private static final String DEFAULT_ROUTE = "/proxy/cookie-policy/default";
    private static final String NAMED_ROUTE = "/proxy/cookie-policy/named";
    private static final String EVERY_ROUTE = "/proxy/cookie-policy/every";
    private static final String SESSION_ROUTE = "/app/cookie-policy";
    private static final String PARTNER_ROUTE = "/proxy/cookie-policy/partner";
    private static final String UNCHECKED_ROUTE = "/proxy/cookie-policy/unchecked";

    /** go-httpbin's echo of the request it received, for any method. */
    private static final String ECHO = "/anything";

    /** go-httpbin's endpoint answering each query parameter back as a response header. */
    private static final String RESPONSE_HEADERS = "/response-headers";

    /** go-httpbin's endpoint setting each query parameter as a cookie of its own, then redirecting. */
    private static final String COOKIES_SET = "/cookies/set";

    /** The two names {@code cookie-policy-named} lists; {@code cookie-policy-unchecked} lists the first. */
    private static final String THEME = "app-theme";
    private static final String LOCALE = "app-locale";

    /** A cookie no route lists. */
    private static final String TRACKER = "tracker";

    /** The default session-cookie name; the primary stack's {@code gateway.yaml} declares no other. */
    private static final String SESSION_COOKIE = "__Host-sheriff-session";

    /** A name under the gateway's reserved prefix, which is a gateway cookie with or without a session. */
    private static final String RESERVED_PREFIX_COOKIE = "__Host-sheriff-preference";

    private static final String GATEWAY_COOKIE_PREFIX = "__Host-sheriff-";

    /** The origin the primary instance receives requests on, which the check trusts. */
    private static final String RECEIVED_ORIGIN = BffKeycloakLoginFlow.GATEWAY_ORIGIN;

    /** The one further origin {@code cookie-policy-partner} trusts. */
    private static final String PARTNER_ORIGIN = "https://partner.sheriff.test";

    /** An origin no route trusts. */
    private static final String FOREIGN_ORIGIN = "https://foreign.sheriff.test";

    private static final String COOKIE = "Cookie";
    private static final String SET_COOKIE = "Set-Cookie";
    private static final String ORIGIN = "Origin";
    private static final String SEC_FETCH_SITE = "Sec-Fetch-Site";

    private static final int OK = 200;

    /** What a refused same-origin check answers an untrusted caller: the unrouted status. */
    private static final int UNROUTED = UniformNotFoundAssertions.UNROUTED_STATUS;

    @Nested
    @DisplayName("request direction: which browser cookies reach the upstream")
    class RequestDirection {

        @Test
        @DisplayName("the default none forwards no cookie, while all forwards the same cookies")
        void defaultNoneForwardsNoCookie() {
            String cookies = pairs(THEME, LOCALE);

            Response onDefault = echo(DEFAULT_ROUTE, cookies);
            Response onEvery = echo(EVERY_ROUTE, cookies);

            assertAll("one Cookie header on the default route and on the all route",
                    () -> assertEquals(Set.of(), forwardedCookieNames(onDefault),
                            "a route declaring no cookies block resolves allow: none and forwards no cookie"),
                    () -> assertEquals(Set.of(THEME, LOCALE), forwardedCookieNames(onEvery),
                            "control: the same header crosses a route declaring allow: all"));
        }

        @Test
        @DisplayName("a list forwards exactly the names it lists")
        void listForwardsExactlyTheListedNames() {
            Response response = echo(NAMED_ROUTE, pairs(TRACKER, THEME, LOCALE));

            assertEquals(Set.of(THEME, LOCALE), forwardedCookieNames(response),
                    "only the two listed cookies may cross; the unlisted one is removed from the rebuilt header");
        }

        @Test
        @DisplayName("all forwards every cookie except the gateway's own")
        void allForwardsEverythingButGatewayCookies() {
            Response response = echo(EVERY_ROUTE,
                    pairs(THEME, SESSION_COOKIE, TRACKER, RESERVED_PREFIX_COOKIE));

            assertEquals(Set.of(THEME, TRACKER), forwardedCookieNames(response),
                    "allow: all forwards the application cookies and never a gateway cookie: the session cookie"
                            + " and every name under the reserved prefix stay at the gateway");
        }

        @Test
        @DisplayName("on a require: session route under all, the session cookie stays at the gateway")
        void sessionCookieStaysAtTheGatewayUnderAll() {
            Session session = BffKeycloakLoginFlow.login(SESSION_ROUTE + ECHO);
            String sessionCookie = session.gatewayCookies().get(SESSION_COOKIE);
            assertNotNull(sessionCookie, "precondition: the login must set " + SESSION_COOKIE);
            List<String> sent = new ArrayList<>();
            session.gatewayCookies().forEach((name, value) -> sent.add(name + "=" + value));
            sent.add(THEME + "=dark");

            Response response = BffKeycloakLoginFlow.gateway(Map.of())
                    .header(COOKIE, String.join("; ", sent))
                    .header("Accept", "application/json")
                    .redirects().follow(false)
                    .when().get(SESSION_ROUTE + ECHO)
                    .then().extract().response();

            assertEquals(OK, response.statusCode(),
                    "precondition: the session cookie must authenticate the request at the gateway");
            Set<String> forwarded = forwardedCookieNames(response);
            assertAll("the cookies the upstream of a session route received under allow: all",
                    () -> assertTrue(forwarded.contains(THEME),
                            "control: the browser's own cookie crosses, so the route does forward cookies; "
                                    + "forwarded names were " + forwarded),
                    () -> assertTrue(forwarded.stream().noneMatch(name -> name.startsWith(GATEWAY_COOKIE_PREFIX)),
                            "the session cookie and every other gateway cookie must stay at the gateway; "
                                    + "forwarded names were " + forwarded));
        }
    }

    @Nested
    @DisplayName("response direction: which upstream Set-Cookie lines reach the client")
    class ResponseDirection {

        @Test
        @DisplayName("an upstream Set-Cookie line outside the policy is not relayed")
        void outOfPolicySetCookieIsNotRelayed() {
            String query = "?Set-Cookie=" + THEME + "%3Ddark&Set-Cookie=" + TRACKER + "%3D1";

            Response onNamed = upstreamHeaders(NAMED_ROUTE + RESPONSE_HEADERS + query);
            Response onDefault = upstreamHeaders(DEFAULT_ROUTE + RESPONSE_HEADERS + query);

            assertAll("the same two upstream Set-Cookie lines on a list route and on the default route",
                    () -> assertEquals(OK, onNamed.statusCode(), "precondition: the upstream must answer"),
                    () -> assertTrue(onNamed.asString().contains(TRACKER + "=1"),
                            "precondition: the upstream must have sent the unlisted line, which its body echoes"),
                    () -> assertEquals(List.of(THEME + "=dark"), onNamed.getHeaders().getValues(SET_COOKIE),
                            "a list relays the line setting a listed cookie and drops the unlisted one"),
                    () -> assertEquals(OK, onDefault.statusCode(), "precondition: the upstream must answer"),
                    () -> assertEquals(List.of(), onDefault.getHeaders().getValues(SET_COOKIE),
                            "the default allow: none relays no upstream Set-Cookie line at all"));
        }

        @Test
        @DisplayName("the cookies go-httpbin sets itself are relayed by the same rule")
        void cookiesTheUpstreamSetsItselfFollowThePolicy() {
            Response response = BffKeycloakLoginFlow.gateway(Map.of())
                    .redirects().follow(false)
                    .when().get(NAMED_ROUTE + COOKIES_SET + "?" + THEME + "=dark&" + TRACKER + "=1")
                    .then().extract().response();

            assertEquals(Set.of(THEME), relayedCookieNames(response),
                    "of the two cookies the upstream's /cookies/set answer sets, only the listed one may be relayed");
        }

        @Test
        @DisplayName("a relayed Set-Cookie line loses its Domain attribute and keeps the others")
        void relayedLineLosesItsDomain() {
            Response response = upstreamHeaders(EVERY_ROUTE + RESPONSE_HEADERS
                    + "?Set-Cookie=" + THEME + "%3Ddark%3BDomain%3Dlocalhost%3BHttpOnly");

            assertEquals(OK, response.statusCode(), "precondition: the upstream must answer");
            assertEquals(List.of(THEME + "=dark;HttpOnly"), response.getHeaders().getValues(SET_COOKIE),
                    "the relayed line must keep its pair and its other attributes, with Domain removed, so the"
                            + " cookie stays a host-only cookie of the gateway's own origin");
        }
    }

    @Nested
    @DisplayName("the same-origin check on an unsafe request carrying a forwarded cookie")
    class SameOriginCheck {

        @Test
        @DisplayName("a cross-site unsafe request carrying a forwarded cookie is refused as the unrouted 404")
        void crossSiteRequestCarryingAForwardedCookieIsRefused() {
            String cookie = pairs(THEME);

            Response crossSite = post(EVERY_ROUTE, cookie, Map.of(ORIGIN, FOREIGN_ORIGIN));
            Response crossSiteByFetchMetadata = post(EVERY_ROUTE, cookie, Map.of(SEC_FETCH_SITE, "cross-site"));
            Response sameOrigin = post(EVERY_ROUTE, cookie, Map.of(ORIGIN, RECEIVED_ORIGIN));
            Response sameOriginByFetchMetadata = post(EVERY_ROUTE, cookie, Map.of(SEC_FETCH_SITE, "same-origin"));

            assertAll("one unsafe request carrying a forwarded cookie, four provenance claims",
                    () -> assertRefused(crossSite, "a foreign Origin"),
                    () -> assertRefused(crossSiteByFetchMetadata, "no Origin and Sec-Fetch-Site: cross-site"),
                    () -> assertForwardedWithCookie(sameOrigin, "the received origin as Origin"),
                    () -> assertForwardedWithCookie(sameOriginByFetchMetadata,
                            "no Origin and Sec-Fetch-Site: same-origin"));
        }

        @Test
        @DisplayName("the check keys on a forwardable cookie: the same cross-site request without one is forwarded")
        void theCheckKeysOnAForwardableCookie() {
            Map<String, String> crossSite = Map.of(ORIGIN, FOREIGN_ORIGIN);

            Response cookieless = post(EVERY_ROUTE, null, crossSite);
            Response withACookie = post(EVERY_ROUTE, pairs(THEME), crossSite);
            Response withOnlyAnUnlistedCookie = post(NAMED_ROUTE, pairs(TRACKER), crossSite);

            assertAll("one cross-site unsafe request, with and without a cookie the route forwards",
                    () -> assertEquals(OK, cookieless.statusCode(),
                            "a request carrying no cookie hands the upstream no ambient credential and is"
                                    + " forwarded"),
                    () -> assertEquals("POST", cookieless.path("method"),
                            "the upstream must have recorded the cookieless request"),
                    () -> assertRefused(withACookie, "the same request carrying a forwarded cookie"),
                    () -> assertEquals(OK, withOnlyAnUnlistedCookie.statusCode(),
                            "a request whose only cookie the route's list does not admit carries nothing the"
                                    + " route forwards, so it is not checked"),
                    () -> assertEquals(Set.of(), forwardedCookieNames(withOnlyAnUnlistedCookie),
                            "and the unlisted cookie is not forwarded either"));
        }

        @Test
        @DisplayName("additional_origins admits its origin and no other")
        void additionalOriginIsAdmitted() {
            String cookie = pairs(THEME);

            Response fromPartner = post(PARTNER_ROUTE, cookie, Map.of(ORIGIN, PARTNER_ORIGIN));
            Response fromForeign = post(PARTNER_ROUTE, cookie, Map.of(ORIGIN, FOREIGN_ORIGIN));

            assertAll("one cookie-carrying unsafe request from the listed and from an unlisted origin",
                    () -> assertForwardedWithCookie(fromPartner, "the additional_origins entry as Origin"),
                    () -> assertRefused(fromForeign, "an origin the route does not list"));
        }

        @Test
        @DisplayName("same_origin_check: false forwards a cross-site request carrying a forwarded cookie")
        void switchedOffCheckForwardsACrossSiteRequest() {
            Response response = post(UNCHECKED_ROUTE, pairs(THEME), Map.of(ORIGIN, FOREIGN_ORIGIN));

            assertForwardedWithCookie(response, "a foreign Origin on a route with the check switched off");
        }

        private static void assertRefused(Response response, String provenance) {
            assertEquals(UNROUTED, response.statusCode(), () -> "an unsafe request carrying a forwarded cookie"
                    + " and " + provenance + " must be refused, answered as the unrouted 404");
            assertEquals(UniformNotFoundAssertions.ROUTING_PROBLEM_TYPE, response.path("type"),
                    () -> "the refusal (" + provenance + ") carries the unrouted routing problem");
            assertNull(response.path("method"), () -> "the refused request (" + provenance
                    + ") must not reach the upstream");
        }

        private static void assertForwardedWithCookie(Response response, String provenance) {
            assertEquals(OK, response.statusCode(), () -> "an unsafe request carrying a forwarded cookie and "
                    + provenance + " must be forwarded");
            assertEquals(Set.of(THEME), forwardedCookieNames(response), () -> "the forwarded request ("
                    + provenance + ") must carry its cookie to the upstream");
        }
    }

    /** A {@code Cookie} header value naming each cookie once, each with a value of its own. */
    private static String pairs(String... names) {
        List<String> pairs = new ArrayList<>();
        for (String name : names) {
            pairs.add(name + "=value-of-" + name);
        }
        return String.join("; ", pairs);
    }

    /** A {@code GET} of the upstream echo below {@code route}, carrying {@code cookieHeader}. */
    private static Response echo(String route, String cookieHeader) {
        Response response = BffKeycloakLoginFlow.gateway(Map.of())
                .header(COOKIE, cookieHeader)
                .when().get(route + ECHO)
                .then().extract().response();
        assertEquals(OK, response.statusCode(), () -> "precondition: the echo below " + route + " must answer");
        return response;
    }

    /**
     * A {@code POST} of the upstream echo below {@code route}, carrying {@code cookieHeader} when it is
     * not {@code null} and the given provenance headers.
     */
    private static Response post(String route, String cookieHeader, Map<String, String> provenance) {
        RequestSpecification request = BffKeycloakLoginFlow.gateway(Map.of())
                .header("Content-Type", "application/json")
                .headers(provenance)
                .body("{}");
        if (cookieHeader != null) {
            request = request.header(COOKIE, cookieHeader);
        }
        return request.when().post(route + ECHO).then().extract().response();
    }

    /** A {@code GET} of an already percent-encoded gateway path, sent without re-encoding. */
    private static Response upstreamHeaders(String encodedPathAndQuery) {
        return BffKeycloakLoginFlow.gateway(Map.of())
                .urlEncodingEnabled(false)
                .when().get(encodedPathAndQuery)
                .then().extract().response();
    }

    /** The cookie names of the {@code Set-Cookie} lines the gateway relayed to the client. */
    private static Set<String> relayedCookieNames(Response response) {
        return response.getHeaders().getValues(SET_COOKIE).stream()
                .map(line -> line.indexOf(';') < 0 ? line : line.substring(0, line.indexOf(';')))
                .map(pair -> pair.indexOf('=') < 0 ? pair : pair.substring(0, pair.indexOf('=')))
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * The names in the {@code Cookie} header go-httpbin echoes as received — empty when the gateway
     * forwarded none.
     */
    private static Set<String> forwardedCookieNames(Response echo) {
        Map<String, List<String>> headers = echo.path("headers");
        assertNotNull(headers, "go-httpbin echoes the headers it received");
        return headers.entrySet().stream()
                .filter(entry -> COOKIE.equalsIgnoreCase(entry.getKey()))
                .flatMap(entry -> entry.getValue().stream())
                .flatMap(value -> List.of(value.split(";")).stream())
                .map(String::trim)
                .filter(pair -> !pair.isEmpty())
                .map(pair -> pair.indexOf('=') < 0 ? pair : pair.substring(0, pair.indexOf('=')))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
