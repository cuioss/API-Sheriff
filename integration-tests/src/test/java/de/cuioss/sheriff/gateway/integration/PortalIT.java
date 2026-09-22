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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the application portal against the <strong>native image</strong> on the primary instance
 * (10443), whose {@code sheriff-config/gateway.yaml} declares the {@code portal} block.
 * <p>
 * <strong>The fixture.</strong> The portal answers {@code /portal}, an address no anchor prefix covers,
 * so every observation below is the portal being dispatched <em>ahead of</em> the route table rather
 * than an accident of routing. Two endpoints declare a {@code catalog} block:
 * <ul>
 *   <li>{@code endpoints/assets.yaml} — enabled, titled with all four HTML-significant characters
 *       {@code < & " '}, linking to the demo SPA;</li>
 *   <li>{@code endpoints/portal-hidden.yaml} — {@code enabled: ${ENDPOINT_PORTAL_HIDDEN_ENABLED:-true}},
 *       which {@code docker-compose.yml} sets to {@code false} on this instance only.</li>
 * </ul>
 * The page is rendered from the operator template mounted at
 * {@code sheriff-config/portal/portal.html}; its marker appears in no built-in template, so finding it
 * proves the runtime-parsed operator template renders natively through the standalone Qute engine.
 * <p>
 * <strong>What this suite proves.</strong> The portal lists exactly the active entries, escapes every
 * catalog value, carries the gateway-owned envelope (content type, the portal CSP, {@code nosniff},
 * {@code Cache-Control: max-age=60} and {@code Vary: Cookie} on the anonymous page), answers
 * {@code HEAD} with an empty body and every other method {@code 405}, renders the fixed-vocabulary
 * {@code logged-out} notice and drops any other notice value, and is reachable on any {@code Host}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class PortalIT extends BaseIntegrationTest {

    /** The configured {@code portal.path}. */
    static final String PORTAL_PATH = "/portal";

    /** The marker only the mounted IT operator template carries. */
    static final String OPERATOR_TEMPLATE_MARKER = "it-operator-template-5c1e9a";

    /** The fixed portal policy the gateway composes onto every portal-rendered response. */
    static final String PORTAL_CSP =
            "default-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    /** The configured {@code portal.title}. */
    private static final String PORTAL_TITLE = "API Sheriff Integration Portal";

    /** The declared {@code portal.cache_seconds}. */
    private static final int CACHE_SECONDS = 60;

    /** The link target of the active {@code assets} catalog entry. */
    private static final String ACTIVE_ENTRY = "/assets/demo/index.html";

    /** The link target of the {@code portal-hidden} catalog entry, disabled on this instance. */
    private static final String HIDDEN_ENTRY = "/assets/portal-hidden";

    /** The title of the {@code portal-hidden} catalog entry. */
    private static final String HIDDEN_TITLE = "Hidden Portal Entry";

    /** One rendered catalog entry, as the operator template spells it. */
    private static final String ENTRY_MARKUP = "class=\"portal-app\"";

    /** The notice text the template renders for the {@code logged-out} notice. */
    private static final String LOGGED_OUT_TEXT = "You have been signed out.";

    @Test
    @DisplayName("the portal lists exactly the active catalog entries — the disabled endpoint's entry is absent")
    void listsExactlyTheActiveEntries() {
        String body = anonymousPage().asString();

        assertAll("catalog membership",
                () -> assertEquals(1, occurrences(body, ENTRY_MARKUP),
                        "exactly one catalog entry is active on this instance; body was: " + body),
                () -> assertTrue(body.contains("href=\"" + ACTIVE_ENTRY + "\""),
                        "the enabled assets endpoint's entry must be listed"),
                () -> assertFalse(body.contains(HIDDEN_ENTRY),
                        "ENDPOINT_PORTAL_HIDDEN_ENABLED=false disables portal-hidden, so its entry must not be listed"),
                () -> assertFalse(body.contains(HIDDEN_TITLE),
                        "the disabled entry's title must not leak onto the page either"));
    }

    @Test
    @DisplayName("control: the hidden endpoint's route is absent too — the toggle disabled the endpoint, not just its entry")
    void disabledEndpointServesNoRoute() {
        given()
                .redirects().follow(false)
                .when()
                .get(HIDDEN_ENTRY)
                .then()
                .statusCode(404);
    }

    @Test
    @DisplayName("every catalog value is HTML-escaped — the special-character title never reaches the page raw")
    void catalogTitleIsEscaped() {
        String body = anonymousPage().asString();

        assertAll("escaping of 'Demo <SPA> & \"Sheriff's\" Assets'",
                () -> assertTrue(body.contains("&lt;SPA&gt;"), "'<' and '>' must be escaped; body was: " + body),
                () -> assertTrue(body.contains(" &amp; "), "'&' must be escaped"),
                () -> assertTrue(body.contains("&quot;Sheriff"), "'\"' must be escaped"),
                () -> assertFalse(body.contains("<SPA>"), "the raw tag must never reach the page"),
                () -> assertFalse(body.contains("Sheriff's"), "the raw apostrophe must never reach the page"));
    }

    @Test
    @DisplayName("the runtime-loaded operator template renders in the native image")
    void operatorTemplateRenders() {
        String body = anonymousPage().asString();

        assertAll("operator template",
                () -> assertTrue(body.contains(OPERATOR_TEMPLATE_MARKER),
                        "the mounted operator template's marker must be present; body was: " + body),
                () -> assertTrue(body.contains(PORTAL_TITLE), "the configured portal title must be rendered"),
                () -> assertTrue(body.contains("id=\"portal-login\""),
                        "an anonymous page on an active BFF offers the login link"));
    }

    @Test
    @DisplayName("the anonymous page carries the gateway-owned envelope, the portal CSP and a cacheable Vary: Cookie")
    void anonymousPageCarriesTheEnvelope() {
        ExtractableResponse<Response> page = anonymousPage();

        assertAll("portal envelope",
                () -> assertTrue(page.contentType().toLowerCase(Locale.ROOT).startsWith("text/html"),
                        "the portal page is HTML; was " + page.contentType()),
                () -> assertEquals(PORTAL_CSP, page.header("Content-Security-Policy"),
                        "the portal page carries the fixed portal policy verbatim"),
                () -> assertEquals("nosniff", page.header("X-Content-Type-Options")),
                () -> assertEquals("max-age=" + CACHE_SECONDS, page.header("Cache-Control"),
                        "a session-free page is cacheable for exactly the declared cache_seconds"),
                () -> assertVaryNamesCookie(page.header("Vary")));
    }

    @Test
    @DisplayName("HEAD answers 200 with the page's headers and an empty body")
    void headAnswersWithoutBody() {
        ExtractableResponse<Response> head = given()
                .when()
                .head(PORTAL_PATH)
                .then()
                .statusCode(200)
                .extract();

        assertAll("HEAD",
                () -> assertTrue(head.asString().isEmpty(), "a HEAD answer carries no body"),
                () -> assertEquals(PORTAL_CSP, head.header("Content-Security-Policy")));
    }

    @Test
    @DisplayName("POST is answered 405 with Allow: GET, HEAD")
    void postIsMethodNotAllowed() {
        given()
                .when()
                .post(PORTAL_PATH)
                .then()
                .statusCode(405)
                .header("Allow", "GET, HEAD");
    }

    @Test
    @DisplayName("notice=logged-out renders the fixed signed-out notice")
    void loggedOutNoticeRenders() {
        String body = given()
                .queryParam("notice", "logged-out")
                .when()
                .get(PORTAL_PATH)
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertTrue(body.contains(LOGGED_OUT_TEXT), "the recognised notice must be rendered; body was: " + body);
    }

    @Test
    @DisplayName("a notice outside the fixed vocabulary is dropped, never echoed")
    void unknownNoticeIsDropped() {
        String body = given()
                .queryParam("notice", "<script>alert(1)</script>")
                .when()
                .get(PORTAL_PATH)
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertAll("dropped notice",
                () -> assertFalse(body.contains("id=\"portal-notice\""), "no notice may be rendered"),
                () -> assertFalse(body.contains("<script>"), "the value must never be echoed raw"),
                () -> assertFalse(body.contains("alert(1)"), "the value must not be echoed in any form"));
    }

    @Test
    @DisplayName("the portal path is host-independent — it answers on a Host other than the oidc host")
    void reachableOnAnotherHost() {
        String body = given()
                .header("Host", "portal.alternate.test")
                .when()
                .get(PORTAL_PATH)
                .then()
                .statusCode(200)
                .extract()
                .asString();

        assertTrue(body.contains(OPERATOR_TEMPLATE_MARKER),
                "the portal must render on a Host other than the oidc host; body was: " + body);
    }

    private static ExtractableResponse<Response> anonymousPage() {
        return given()
                .when()
                .get(PORTAL_PATH)
                .then()
                .statusCode(200)
                .extract();
    }

    private static void assertVaryNamesCookie(String vary) {
        assertNotNull(vary, "an anonymous page on an active BFF must announce Vary");
        boolean namesCookie = false;
        for (String name : vary.split(",")) {
            if ("Cookie".equalsIgnoreCase(name.strip())) {
                namesCookie = true;
            }
        }
        assertTrue(namesCookie, "the anonymous page must vary on Cookie; Vary was: " + vary);
    }

    private static int occurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
