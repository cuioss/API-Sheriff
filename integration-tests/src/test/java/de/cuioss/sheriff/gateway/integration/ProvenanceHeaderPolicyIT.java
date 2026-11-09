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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.cuioss.sheriff.gateway.http.ConnectionHeaders;

import io.restassured.response.Response;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves on the native image that the gateway is the only provenance authority an upstream hears,
 * against the three routes of {@code endpoints/provenance-headers.yaml} and go-httpbin's
 * {@code /anything} echo, which answers the request headers it received.
 * <ul>
 *   <li><strong>The provenance and URL-override class is withheld under forward-all and under a
 *       negative-list.</strong> Every one of its fifteen names, in its canonical spelling and in its
 *       {@code _} spelling, is absent from the echo, while an unrelated header in either spelling
 *       crosses as the matched control — so the absence is the gateway's decision, not a route that
 *       answered nothing or an upstream that drops underscore names.</li>
 *   <li><strong>A positive-list re-admits exactly the member it names.</strong> The route naming
 *       {@code X-Forwarded-Server} relays that one member and no other withheld name, and — being a
 *       positive-list — no unlisted client header either.</li>
 *   <li><strong>The {@code X-Proxy*} resolver inputs neither cross nor steer.</strong> A client-sent
 *       {@code X-ProxyHost} or {@code X-ProxyContextPath} does not reach the upstream and leaves the
 *       regenerated {@code X-Forwarded-Host} and {@code X-Forwarded-Prefix} exactly as a request
 *       without them leaves them. The shared stack declares no {@code forwarded.trusted_proxies}, so the
 *       IT client is an untrusted peer and the resolver-input mask is the code path under test.</li>
 * </ul>
 * <p>
 * <strong>Every key of the descriptor has a leg that goes red when the key is deleted</strong> — the
 * question {@code doc/development/declared-limit-assertion-coverage.adoc} asks of every declared
 * control. Deleting {@code headers_deny} lets {@code X-Sheriff-Provenance-Denied} cross on the
 * negative-list route; deleting {@code headers_allow} turns the positive-list route into forward-all,
 * which withholds {@code X-Forwarded-Server} and relays the unlisted control; deleting a route's
 * {@code upstream.path} moves the echo off {@code /anything}, which every request below checks.
 * <p>
 * Each withheld name is driven in a request of its own. That keeps every request far below the strict
 * preset's header-count cap and makes a red leg name the one header that crossed.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("The provenance and URL-override headers and the X-Proxy resolver inputs on the native image")
class ProvenanceHeaderPolicyIT extends BaseIntegrationTest {

    private static final String FORWARD_ALL = "/proxy/provenance-headers/forward-all";
    private static final String NEGATIVE_LIST = "/proxy/provenance-headers/negative-list";
    private static final String POSITIVE_LIST = "/proxy/provenance-headers/positive-list";

    /** The remainder every request appends to its route; the descriptor's upstream.path prefixes it. */
    private static final String REMAINDER = "/probe";

    /** Where the remainder lands on go-httpbin when the route's {@code upstream.path: /anything} applies. */
    private static final String UPSTREAM_ECHO_PATH = "/anything" + REMAINDER;

    /**
     * The provenance and URL-override class, in the spelling the client sends it — the names a backend
     * may read as the client's address, as the scheme, host or server it was reached through, or as the
     * URL to serve in place of the request line. {@link #listedNamesAreTheWithheldReadmittableClass()}
     * holds this list to {@link ConnectionHeaders#REQUEST_READMITTABLE}, so a member added to or removed
     * from the production class fails the build instead of leaving this list stale.
     */
    private static final List<String> PROVENANCE_OVERRIDE_CLASS = List.of(
            "X-Forwarded-Server", "X-Forwarded-Ssl", "Front-End-Https", "X-Url-Scheme",
            "X-Forwarded-Scheme", "X-Forwarded-Protocol", "X-Forwarded-Uri", "X-Forwarded-Path",
            "X-Original-URL", "X-Rewrite-URL", "X-Original-Host", "X-Host",
            "X-Original-Forwarded-For", "X-Cluster-Client-IP", "Fastly-Client-IP");

    /** The one member {@code provenance-positive-list} names in its {@code headers_allow}. */
    private static final String READMITTED = "X-Forwarded-Server";

    /** The test-only name {@code provenance-negative-list} names in its {@code headers_deny}. */
    private static final String DENIED = "X-Sheriff-Provenance-Denied";

    /**
     * A header the gateway has no opinion about, sent canonically. It is in neither gateway-owned set
     * and in no route's list, so it crosses forward-all and the negative-list and is withheld only by
     * the positive-list.
     */
    private static final String CONTROL = "X-Sheriff-Provenance-Control";

    /**
     * A second unrelated header, sent in {@code _} spelling. It proves an underscore name survives the
     * whole chain — client, gateway, upstream client and go-httpbin — so the absence of a withheld
     * name's {@code _} spelling is the gateway's decision rather than a property of the transport. Its
     * name folds to a different name than {@link #CONTROL}, so the two are never confused in the echo.
     */
    private static final String UNDERSCORE_CONTROL = "X_Sheriff_Underscore_Control";

    private static final String CONTROL_VALUE = "control-crosses";
    private static final String UNDERSCORE_CONTROL_VALUE = "underscore-control-crosses";

    private static final String PROXY_HOST = "X-ProxyHost";
    private static final String PROXY_CONTEXT_PATH = "X-ProxyContextPath";
    private static final String FORWARDED_HOST = "X-Forwarded-Host";
    private static final String FORWARDED_PREFIX = "X-Forwarded-Prefix";

    private static final String SPOOFED_HOST = "proxyhost.spoof.example";
    private static final String SPOOFED_UNDERSCORE_HOST = "underscore-proxyhost.spoof.example";
    private static final String SPOOFED_PREFIX = "/spoofed-context-path";
    private static final String SPOOFED_UNDERSCORE_PREFIX = "/spoofed-underscore-context-path";

    private static final int OK = 200;

    static Stream<String> provenanceOverrideClass() {
        return PROVENANCE_OVERRIDE_CLASS.stream();
    }

    static Stream<String> withheldNamesBesideTheReadmittedOne() {
        return PROVENANCE_OVERRIDE_CLASS.stream().filter(name -> !READMITTED.equals(name));
    }

    @Test
    @DisplayName("the fifteen names driven here are exactly the re-admittable class beside Authorization")
    void listedNamesAreTheWithheldReadmittableClass() {
        Set<String> listed = PROVENANCE_OVERRIDE_CLASS.stream()
                .map(ConnectionHeaders::separatorFolded)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> production = ConnectionHeaders.REQUEST_READMITTABLE.stream()
                .filter(name -> !"authorization".equals(name))
                .collect(Collectors.toCollection(TreeSet::new));

        assertAll("the list this suite drives is the production class, and every member is withheld",
                () -> assertEquals(production, listed,
                        "a member added to or removed from ConnectionHeaders.REQUEST_READMITTABLE must be "
                                + "added to or removed from this suite in the same change"),
                () -> assertTrue(PROVENANCE_OVERRIDE_CLASS.stream().allMatch(ConnectionHeaders::isRequestStripped),
                        "every member is a never-forward name, withheld unless a positive-list names it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provenanceOverrideClass")
    @DisplayName("forward-all withholds the name in both spellings while unrelated headers cross")
    void forwardAllWithholdsTheName(String name) {
        Map<String, String> sent = withheldAndControls(name);

        Map<String, ?> echoed = echo(FORWARD_ALL, sent);

        assertAll("forward-all copies every client header except the gateway-owned never-forward set",
                () -> assertEquals(CONTROL_VALUE, echoedValue(echoed, CONTROL),
                        "THE CONTROL: an unrelated header crosses, so the route forwards client headers"),
                () -> assertEquals(UNDERSCORE_CONTROL_VALUE, echoedValue(echoed, UNDERSCORE_CONTROL),
                        "THE CONTROL: an unrelated underscore header crosses, so the transport keeps them"),
                () -> assertNull(echoedValue(echoed, name),
                        () -> name + " must not reach the upstream in either spelling under forward-all; "
                                + "echoed headers were " + echoed.keySet()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("provenanceOverrideClass")
    @DisplayName("a negative-list withholds the name in both spellings and never re-admits it")
    void negativeListWithholdsTheName(String name) {
        Map<String, String> sent = withheldAndControls(name);

        Map<String, ?> echoed = echo(NEGATIVE_LIST, sent);

        assertAll("a negative-list withholds its deny entries and the never-forward set alike",
                () -> assertEquals(CONTROL_VALUE, echoedValue(echoed, CONTROL),
                        "THE CONTROL: a header the deny list does not name crosses"),
                () -> assertEquals(UNDERSCORE_CONTROL_VALUE, echoedValue(echoed, UNDERSCORE_CONTROL),
                        "THE CONTROL: an unrelated underscore header crosses, so the transport keeps them"),
                () -> assertNull(echoedValue(echoed, name),
                        () -> name + " must not reach the upstream in either spelling under a "
                                + "negative-list; echoed headers were " + echoed.keySet()));
    }

    @Test
    @DisplayName("headers_deny withholds its test-only name, which forward-all relays")
    void deniedNameIsWithheldOnlyWhereTheDenyListNamesIt() {
        Map<String, String> sent = new LinkedHashMap<>();
        sent.put(DENIED, "denied-value");
        sent.put(CONTROL, CONTROL_VALUE);

        Map<String, ?> onNegativeList = echo(NEGATIVE_LIST, sent);
        Map<String, ?> onForwardAll = echo(FORWARD_ALL, sent);

        assertAll("the same two headers on the negative-list route and on the forward-all route",
                () -> assertNull(echoedValue(onNegativeList, DENIED),
                        "headers_deny names " + DENIED + ", so the negative-list route withholds it"),
                () -> assertEquals(CONTROL_VALUE, echoedValue(onNegativeList, CONTROL),
                        "THE CONTROL: the negative-list route relays a header its list does not name"),
                () -> assertEquals("denied-value", echoedValue(onForwardAll, DENIED),
                        "THE CONTROL: the name is an ordinary client header that forward-all relays, so "
                                + "only the deny entry withholds it"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("withheldNamesBesideTheReadmittedOne")
    @DisplayName("a positive-list naming X-Forwarded-Server re-admits it and no other withheld name")
    void positiveListReadmitsOnlyTheNamedMember(String name) {
        Map<String, String> sent = new LinkedHashMap<>();
        sent.put(READMITTED, "readmitted-value");
        sent.putAll(withheldAndControls(name));

        Map<String, ?> echoed = echo(POSITIVE_LIST, sent);

        assertAll("headers_allow re-admits exactly the member it names",
                () -> assertEquals("readmitted-value", echoedValue(echoed, READMITTED),
                        READMITTED + " is named by headers_allow, so the client's own value crosses"),
                () -> assertNull(echoedValue(echoed, name),
                        () -> name + " is withheld and not named by the positive-list, so it must not "
                                + "cross in either spelling; echoed headers were " + echoed.keySet()),
                () -> assertNull(echoedValue(echoed, CONTROL),
                        "a positive-list relays nothing it does not name, the unrelated header included"));
    }

    @Test
    @DisplayName("client X-ProxyHost and X-ProxyContextPath neither reach the upstream nor steer regeneration")
    void resolverInputsNeitherCrossNorSteerTheRegeneratedSet() {
        Map<String, String> baselineHeaders = new LinkedHashMap<>();
        baselineHeaders.put(CONTROL, "baseline-run");
        Map<String, String> spoofedHeaders = new LinkedHashMap<>();
        spoofedHeaders.put(CONTROL, "spoofed-run");
        spoofedHeaders.put(PROXY_HOST, SPOOFED_HOST);
        spoofedHeaders.put(PROXY_CONTEXT_PATH, SPOOFED_PREFIX);
        spoofedHeaders.put(underscoreSpelling(PROXY_HOST), SPOOFED_UNDERSCORE_HOST);
        spoofedHeaders.put(underscoreSpelling(PROXY_CONTEXT_PATH), SPOOFED_UNDERSCORE_PREFIX);

        Map<String, ?> baseline = echo(FORWARD_ALL, baselineHeaders);
        Map<String, ?> spoofed = echo(FORWARD_ALL, spoofedHeaders);

        String forwardedHost = echoedValue(spoofed, FORWARDED_HOST);
        String forwardedPrefix = echoedValue(spoofed, FORWARDED_PREFIX);
        assertAll("the X-Proxy* inputs are skipped by the copy and masked from the resolver",
                () -> assertEquals("baseline-run", echoedValue(baseline, CONTROL),
                        "THE CONTROL for the baseline run: the upstream echoed the request"),
                () -> assertEquals("spoofed-run", echoedValue(spoofed, CONTROL),
                        "THE CONTROL for the spoofed run: the upstream echoed the request"),
                () -> assertNull(echoedValue(spoofed, PROXY_HOST),
                        () -> PROXY_HOST + " is a resolver input and is never copied from the client, in "
                                + "either spelling; echoed headers were " + spoofed.keySet()),
                () -> assertNull(echoedValue(spoofed, PROXY_CONTEXT_PATH),
                        () -> PROXY_CONTEXT_PATH + " is a resolver input and is never copied from the "
                                + "client, in either spelling; echoed headers were " + spoofed.keySet()),
                () -> assertEquals(echoedValue(baseline, FORWARDED_HOST), forwardedHost,
                        "the regenerated " + FORWARDED_HOST + " must be what a request without "
                                + PROXY_HOST + " produces: the untrusted peer's input is masked"),
                () -> assertEquals(echoedValue(baseline, FORWARDED_PREFIX), forwardedPrefix,
                        "the regenerated " + FORWARDED_PREFIX + " must be what a request without "
                                + PROXY_CONTEXT_PATH + " produces: the untrusted peer's input is masked"),
                () -> assertTrue(forwardedHost == null || !forwardedHost.contains("spoof.example"),
                        () -> "the spoofed host must not surface in " + FORWARDED_HOST + ": " + forwardedHost),
                () -> assertTrue(forwardedPrefix == null || !forwardedPrefix.contains("spoofed"),
                        () -> "the spoofed context path must not surface in " + FORWARDED_PREFIX + ": "
                                + forwardedPrefix));
    }

    /**
     * The headers every withholding leg sends: {@code name} in its canonical spelling and in its
     * {@code _} spelling, each with a value of its own, and the two unrelated controls.
     */
    private static Map<String, String> withheldAndControls(String name) {
        Map<String, String> sent = new LinkedHashMap<>();
        sent.put(name, "canonical-" + ConnectionHeaders.separatorFolded(name));
        sent.put(underscoreSpelling(name), "underscore-" + ConnectionHeaders.separatorFolded(name));
        sent.put(CONTROL, CONTROL_VALUE);
        sent.put(UNDERSCORE_CONTROL, UNDERSCORE_CONTROL_VALUE);
        return sent;
    }

    /** The {@code _} spelling of a hyphenated header name, which a CGI-folding backend reads as the same. */
    private static String underscoreSpelling(String name) {
        return name.replace('-', '_');
    }

    /**
     * Sends a {@code GET} through {@code route} carrying {@code headers} and returns the header map
     * go-httpbin echoes, after asserting the request reached the {@code /anything} echo.
     */
    private static Map<String, ?> echo(String route, Map<String, String> headers) {
        Response response = given()
                .headers(headers)
                .when()
                .get(route + REMAINDER)
                .then()
                .extract()
                .response();

        assertEquals(OK, response.statusCode(),
                () -> "precondition: the echo below " + route + " must answer; body was " + response.asString());
        String url = response.path("url");
        assertTrue(url != null && url.contains(UPSTREAM_ECHO_PATH),
                () -> "the route's upstream.path must place the request on go-httpbin's echo at "
                        + UPSTREAM_ECHO_PATH + "; the upstream saw " + url);
        Map<String, ?> echoed = response.path("headers");
        assertNotNull(echoed, "go-httpbin echoes the headers it received");
        return echoed;
    }

    /**
     * The value the upstream received under any spelling of {@code name}.
     * <p>
     * The comparison is {@linkplain ConnectionHeaders#separatorFolded separator-folded}: case is
     * ignored, as HTTP field names require and as go-httpbin's canonicalisation demands, and {@code -}
     * and {@code _} are read as one, so a single lookup answers for the canonical and the underscore
     * spelling alike. go-httpbin renders each field as a list; several values, or several spellings that
     * crossed, are joined with {@code ", "}.
     *
     * @return the joined value, or {@code null} when no spelling of {@code name} reached the upstream
     */
    private static String echoedValue(Map<String, ?> echoed, String name) {
        String folded = ConnectionHeaders.separatorFolded(name);
        List<String> values = echoed.entrySet().stream()
                .filter(entry -> folded.equals(ConnectionHeaders.separatorFolded(entry.getKey())))
                .flatMap(entry -> valuesOf(entry.getValue()))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
        return values.isEmpty() ? null : String.join(", ", values);
    }

    /** One echoed field's values: the elements of go-httpbin's list, or the bare value of another shape. */
    private static Stream<Object> valuesOf(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(Object.class::cast);
        }
        return Stream.of(value);
    }
}
