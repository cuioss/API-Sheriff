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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Boundary assertions for declared gateway limits that the landed corpus names but never exercises
 * <em>at their boundary</em>. Each case proves a cap rejects <strong>because of the value</strong>,
 * so every negative leg is paired with a matched positive control one unit the other side of the
 * cap.
 * <p>
 * The coverage matrix this suite closes five rows of is
 * {@code doc/development/declared-limit-assertion-coverage.adoc}.
 * <p>
 * <strong>Two of the caps asserted here appear in no descriptor.</strong> {@code /proxy} declares no
 * {@code security_filter.profile}, so it resolves the gateway-wide
 * {@code security_defaults.profile: strict} and inherits that preset's <em>resolved</em> caps — a
 * 1 MiB body and 1024-character header values. Those numbers are written in no yaml file, which is
 * precisely why they are the limits most likely to lose their guard unnoticed.
 * <p>
 * <strong>What the Authorization pair really proves.</strong> {@code G1} and {@code G3} are one
 * assertion split in two, and neither means much alone. {@code security_defaults
 * .max_authorization_header_value_length: 8192} is an ADR-0019 carve-out from the strict preset's
 * 1024-character header-value floor, taken at the non-skippable pre-route floor
 * ({@code BasicChecksStage}) that runs <em>before</em> route selection. Asserting only the carve-out
 * would pass equally well against a relaxation that had silently widened to <em>every</em> header;
 * asserting only the 1024 floor would pass against a gateway with no carve-out at all — under which
 * every bearer request is rejected 400 before bearer validation ever runs. Together they pin the
 * relaxation's <em>scope</em>: {@code Authorization} gets 8192, everything else still gets 1024.
 * <p>
 * The existing {@code BearerValidationIT} proves the carve-out is <em>in force</em> (a real token
 * above 1024 characters is admitted). It never probes 8192 itself, which is the gap this suite
 * closes.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class DeclaredLimitBoundaryIT extends BaseIntegrationTest {

    /**
     * The declared {@code security_defaults.max_authorization_header_value_length} — the carve-out
     * bounding the {@code Authorization} header VALUE at the pre-route floor.
     */
    private static final int AUTHORIZATION_VALUE_CAP = 8192;

    /** The strict preset's resolved header-value cap, applying to every OTHER header name. */
    private static final int STRICT_HEADER_VALUE_CAP = 1024;

    /** The strict preset's resolved body cap (1 MiB) inherited by every route omitting its own. */
    private static final int STRICT_BODY_CAP_BYTES = 1048576;

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String INPUT_VALIDATION_TYPE = "urn:api-sheriff:problem:input-validation";

    /** The bearer-protected route: only here does an admitted header reach bearer validation. */
    private static final String SECURE_PATH = "/secure/get";

    /** The strict, public route inheriting the gateway-wide preset. */
    private static final String PROXY_GET_PATH = "/proxy/get";
    private static final String PROXY_POST_PATH = "/proxy/post";

    @Nested
    @DisplayName("G1 — the Authorization carve-out admits up to its declared 8192-character cap")
    class AuthorizationHeaderValueCap {

        @Test
        @DisplayName("an Authorization value at the 8192 cap is admitted and reaches bearer validation")
        void authorizationValueAtCapReachesBearerValidation() {
            // Arrange — exactly AUTHORIZATION_VALUE_CAP characters, syntactically a bearer credential
            // but not a valid token.
            String atCap = BEARER_PREFIX + "a".repeat(AUTHORIZATION_VALUE_CAP - BEARER_PREFIX.length());
            assertEquals(AUTHORIZATION_VALUE_CAP, atCap.length(), "the at-cap probe must measure exactly the cap");

            // Act
            var response = given()
                    .header("Authorization", atCap)
                    .when()
                    .get(SECURE_PATH)
                    .then()
                    .statusCode(401)
                    .extract();

            // Assert — 401 (not 400) is the load-bearing discriminator: the header was ADMITTED by the
            // pre-route floor and rejected later, by stage-4 bearer validation, for being an invalid
            // token. A 400 here would mean the floor refused the header outright and the carve-out is
            // gone or narrower than declared.
            assertTrue(response.contentType().contains(PROBLEM_JSON),
                    "a bearer rejection carries the gateway's RFC 9457 envelope");
            assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
        }

        @Test
        @DisplayName("an Authorization value one character over the cap is rejected 400 at the pre-route floor")
        void authorizationValueOverCapIsRejectedAtTheFloor() {
            // Arrange — one character past the cap; everything else identical to the admitted case.
            String overCap = BEARER_PREFIX + "a".repeat(AUTHORIZATION_VALUE_CAP - BEARER_PREFIX.length() + 1);
            assertEquals(AUTHORIZATION_VALUE_CAP + 1, overCap.length(),
                    "the over-cap probe must measure exactly one over the cap");

            // Act + Assert — 400 from BasicChecksStage, before route selection and before bearer
            // validation. The single-character delta from the case above is what proves the rejection
            // is about the LENGTH and not about the credential.
            given()
                    .header("Authorization", overCap)
                    .when()
                    .get(SECURE_PATH)
                    .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("G3 — the strict preset's 1024-character floor still binds every other header")
    class StrictHeaderValueCap {

        private static final String PROBE_HEADER = "X-Sheriff-Length-Probe";

        @Test
        @DisplayName("a non-Authorization header value at 1024 characters is admitted and forwarded")
        void headerValueAtStrictCapIsAdmitted() {
            // Arrange
            String atCap = "a".repeat(STRICT_HEADER_VALUE_CAP);

            // Act
            var response = given()
                    .header(PROBE_HEADER, atCap)
                    .when()
                    .get(PROXY_GET_PATH)
                    .then()
                    .statusCode(200)
                    .extract();

            // Assert — 200 plus an echo body is the whole assertion, deliberately. The probe header is
            // NOT named by /proxy's forward.headers_allow and is not a gateway-understood protocol
            // header either, so the positive-list mode strips it before the upstream — asserting it
            // appears in the echoed headers map would be asserting the opposite of what G5 below
            // proves. The echo is what shows the request actually reached go-httpbin rather than being
            // answered by some earlier gate; the 400-vs-200 delta against the case below is what makes
            // this a cap test.
            assertNotNull(response.path("method"),
                    "an admitted request must reach the upstream — its echo carries the method");
        }

        @Test
        @DisplayName("a non-Authorization header value at 1025 characters is rejected 400")
        void headerValueOverStrictCapIsRejected() {
            // Arrange — one character past the strict floor.
            String overCap = "a".repeat(STRICT_HEADER_VALUE_CAP + 1);

            // Act + Assert — the carve-out is scoped to Authorization by name, so this header gets the
            // preset floor and nothing else. Paired with G1's admitted 8192-character Authorization,
            // this is what proves the relaxation did not silently widen to every header.
            given()
                    .header(PROBE_HEADER, overCap)
                    .when()
                    .get(PROXY_GET_PATH)
                    .then()
                    .statusCode(400);
        }
    }

    @Nested
    @DisplayName("G4 — the inherited strict body cap (1 MiB) binds a route that declares none")
    class StrictInheritedBodyCap {

        @Test
        @DisplayName("a body one byte over the inherited 1 MiB cap is rejected 413 by the gateway")
        void bodyOverInheritedCapIsRejectedByTheGateway() {
            // Arrange
            byte[] overCap = new byte[STRICT_BODY_CAP_BYTES + 1];
            Arrays.fill(overCap, (byte) 'x');

            // Act
            var response = given()
                    .contentType("text/plain")
                    .body(overCap)
                    .when()
                    .post(PROXY_POST_PATH)
                    .then()
                    .statusCode(413)
                    .extract();

            // Assert — the envelope, not just the status, is what proves the GATEWAY rejected this. The
            // compose stack raises the framework floor to 128M (docker-compose.yml:242), far above this
            // 1 MiB cap, so the Quarkus root handler cannot shadow the gateway here — and its own
            // pre-check would answer a bare 413 carrying no problem+json body at all.
            assertTrue(response.contentType().contains(PROBLEM_JSON),
                    "the gateway must render its own RFC 9457 envelope, not the framework's bare 413");
            String problemType = response.path("type");
            assertTrue(String.valueOf(problemType).contains(INPUT_VALIDATION_TYPE),
                    "an oversize body is an input-validation rejection, type was: " + problemType);
            assertEquals("Input Validation", response.path("title"));
            assertNull(response.path("method"), "a rejected request must not reach the go-httpbin upstream");
        }

        @Test
        @DisplayName("a body one byte under the inherited cap is forwarded and echoed")
        void bodyUnderInheritedCapIsForwarded() {
            // Arrange — the matched positive control: one byte the other side of the same cap, proving
            // the rejection above is about the SIZE and not about POST or a body being refused on this
            // route outright.
            byte[] underCap = new byte[STRICT_BODY_CAP_BYTES - 1];
            Arrays.fill(underCap, (byte) 'x');

            // Act
            var response = given()
                    .contentType("text/plain")
                    .body(underCap)
                    .when()
                    .post(PROXY_POST_PATH)
                    .then()
                    .statusCode(200)
                    .extract();

            // Assert
            assertEquals("POST", response.path("method"),
                    "an accepted body must reach the go-httpbin upstream and echo its method");
        }
    }

    @Nested
    @DisplayName("G5 — the forward stage resolves one of three modes inside an authorised route")
    class ForwardModeProjection {

        /** Allow-listed by endpoints/httpbin.yaml's forward.query_allow on the /proxy route. */
        private static final String ALLOWED_QUERY = "probe";

        /** Deliberately absent from that allowlist. */
        private static final String DENIED_QUERY = "smuggled";

        /**
         * Allow-listed by /proxy's forward.headers_allow AND outside the gateway-understood protocol
         * set — both halves are load-bearing. The former control was {@code Content-Type}, which the
         * protocol set now carries with no entry at all: the assertion stayed green while proving
         * nothing about the allowlist, because the header would have crossed either way.
         */
        private static final String ALLOWED_HEADER = "X-Sheriff-Allowed";

        /** Deliberately absent from that allowlist. */
        private static final String DENIED_HEADER = "X-Sheriff-Smuggled";

        /** A protocol-set member /proxy's positive-list deliberately does NOT name. */
        private static final String UNLISTED_PROTOCOL_HEADER = "Accept-Language";

        /** The negative-list route: everything crosses except its two declared names. */
        private static final String DENY_LIST_PATH = "/proxy/deny-list";

        /** Named by that route's headers_deny, and a protocol-set member — the precedence case. */
        private static final String DENIED_PROTOCOL_HEADER = "Accept-Language";

        /** Named by that route's headers_deny, and an ordinary header. */
        private static final String DENIED_ORDINARY_HEADER = "X-Sheriff-Denied";

        /** The route declaring no forward block at all: forward-all on both dimensions. */
        private static final String FORWARD_ALL_PATH = "/proxy/forward-all";

        /** The permissive control on that route: an ordinary header no list anywhere names. */
        private static final String UNLISTED_HEADER = "X-Sheriff-Unlisted";

        /**
         * A never-forward member admitted end-to-end for exactly one value — the only member whose
         * crossing depends on what it says rather than on what it is called.
         */
        private static final String TE_HEADER = "TE";

        @Test
        @DisplayName("positive-list: the allow-listed header and query cross, the unlisted pair is stripped")
        void positiveListCrossesOnlyWhatItNames() {
            // Arrange + Act — a SINGLE request carrying both pairs, so the positive control and the
            // negative assertion observe the same forward decision rather than two separate requests
            // that could differ for unrelated reasons.
            var response = given()
                    .header(ALLOWED_HEADER, "allow-listed-value")
                    .header(DENIED_HEADER, "must-not-cross")
                    .queryParam(ALLOWED_QUERY, "allow-listed-value")
                    .queryParam(DENIED_QUERY, "must-not-cross")
                    .when()
                    .get(PROXY_GET_PATH)
                    .then()
                    .statusCode(200)
                    .extract();

            // Assert — the allow-listed query crossed (positive control: the stage forwards what it is
            // told to forward, so a stripped denied name is a DECISION and not a broken stage) ...
            assertEquals("allow-listed-value", response.path("args." + ALLOWED_QUERY + "[0]"),
                    "the allow-listed query parameter must reach the upstream");

            // ... and the non-allow-listed one did not.
            assertNull(response.path("args." + DENIED_QUERY),
                    "a query parameter absent from query_allow must be stripped before the upstream");

            Map<String, ?> forwardedHeaders = response.path("headers");
            assertNotNull(forwardedHeaders, "go-httpbin echoes the headers it received");
            assertTrue(containsIgnoringCase(forwardedHeaders, ALLOWED_HEADER),
                    "the allow-listed header must reach the upstream, echoed headers were: "
                            + forwardedHeaders.keySet());
            assertFalse(containsIgnoringCase(forwardedHeaders, DENIED_HEADER),
                    "a header absent from headers_allow must be stripped before the upstream, echoed headers were: "
                            + forwardedHeaders.keySet());
        }

        @Test
        @DisplayName("a gateway-understood protocol header crosses the same route WITHOUT being allow-listed")
        void protocolHeaderCrossesUnlisted() {
            // Arrange + Act — the same positive-list route, one request carrying a protocol-set member
            // the route never names alongside an ordinary header it never names. The pair is the point:
            // both are unlisted, so anything that separates them is the protocol set and nothing else.
            var response = given()
                    .header(UNLISTED_PROTOCOL_HEADER, "de-DE")
                    .header(DENIED_HEADER, "must-not-cross")
                    .when()
                    .get(PROXY_GET_PATH)
                    .then()
                    .statusCode(200)
                    .extract();

            // Assert
            Map<String, ?> forwardedHeaders = response.path("headers");
            assertNotNull(forwardedHeaders, "go-httpbin echoes the headers it received");
            assertTrue(containsIgnoringCase(forwardedHeaders, UNLISTED_PROTOCOL_HEADER),
                    "HTTP mechanics cross without an operator entry — that is what stops a positive-list"
                            + " route from having to enumerate them. Echoed headers were: "
                            + forwardedHeaders.keySet());
            assertFalse(containsIgnoringCase(forwardedHeaders, DENIED_HEADER),
                    "the matched control: an ordinary unlisted header is still stripped, so the crossing"
                            + " above is the protocol set and not a broken allowlist. Echoed headers were: "
                            + forwardedHeaders.keySet());
        }

        @Test
        @DisplayName("negative-list: a deny entry beats the protocol set, and an undenied header crosses")
        void denyEntryBeatsTheProtocolSet() {
            // Arrange + Act — one request against the negative-list route carrying all three legs: the
            // denied protocol header, the denied ordinary header, and an undenied header that is the
            // positive control proving the permissive copy really ran.
            var response = given()
                    .header(DENIED_PROTOCOL_HEADER, "de-DE")
                    .header(DENIED_ORDINARY_HEADER, "must-not-cross")
                    .header(ALLOWED_HEADER, "undenied-value")
                    .when()
                    .get(DENY_LIST_PATH)
                    .then()
                    .statusCode(200)
                    .extract();

            // Assert
            Map<String, ?> forwardedHeaders = response.path("headers");
            assertNotNull(forwardedHeaders, "go-httpbin echoes the headers it received");
            assertTrue(containsIgnoringCase(forwardedHeaders, ALLOWED_HEADER),
                    "control: a negative-list crosses everything it does not name, echoed headers were: "
                            + forwardedHeaders.keySet());
            assertFalse(containsIgnoringCase(forwardedHeaders, DENIED_ORDINARY_HEADER),
                    "the denied ordinary header must not cross, echoed headers were: "
                            + forwardedHeaders.keySet());
            assertFalse(containsIgnoringCase(forwardedHeaders, DENIED_PROTOCOL_HEADER),
                    "a deny entry OUTRANKS the gateway-understood protocol set — otherwise an operator"
                            + " could never withhold one, since the set is a fixed constant with no"
                            + " configuration surface. Echoed headers were: " + forwardedHeaders.keySet());
        }

        @Test
        @DisplayName("forward-all: an unlisted client header crosses, but no gateway-owned name does")
        void forwardAllIsBoundedByTheGatewayOwnedSet() {
            // Arrange + Act — the route declares no forward block at all, which is the shape most
            // deployments ship. One request carries the permissive control alongside the three
            // never-forward classes the baseline has to keep withholding for it to be safe at all:
            // a mediated credential, the BFF's sealed cookie, and a spoofed provenance claim.
            var response = given()
                    .header(UNLISTED_HEADER, "crosses-on-the-baseline")
                    .header("Cookie", "__Host-sid=sealed")
                    .header("Authorization", "Bearer inbound-must-not-cross")
                    .header("X-Real-IP", "9.9.9.9")
                    .when()
                    .get(FORWARD_ALL_PATH)
                    .then()
                    .statusCode(200)
                    .extract();

            // Assert
            Map<String, ?> forwardedHeaders = response.path("headers");
            assertNotNull(forwardedHeaders, "go-httpbin echoes the headers it received");
            assertTrue(containsIgnoringCase(forwardedHeaders, UNLISTED_HEADER),
                    "control: with neither list declared every client header crosses, echoed headers were: "
                            + forwardedHeaders.keySet());
            assertFalse(containsIgnoringCase(forwardedHeaders, "Cookie"),
                    "Cookie is absolute — no mode and no list re-admits it, and that absoluteness is what"
                            + " makes a forward-all baseline safe to offer. Echoed headers were: "
                            + forwardedHeaders.keySet());
            assertFalse(containsIgnoringCase(forwardedHeaders, "Authorization"),
                    "Authorization is re-admittable ONLY under a positive-list naming it; forward-all"
                            + " declares none, so a permissive route never leaks an inbound credential."
                            + " Echoed headers were: " + forwardedHeaders.keySet());
            assertFalse(containsIgnoringCase(forwardedHeaders, "X-Real-IP"),
                    "a provenance claim is the gateway's alone to make: the vendor client-IP aliases and"
                            + " the underscore spellings of the forwarding names are members of the"
                            + " never-forward set, so a backend folding '-' and '_' into one CGI variable"
                            + " cannot be fed a spoofed one. Echoed headers were: "
                            + forwardedHeaders.keySet());
        }

        @Test
        @DisplayName("TE crosses as the trailers token without an allowlist entry, and not for any other value")
        void teCrossesOnlyAsTheTrailersToken() {
            // Arrange + Act — the only case here that needs TWO requests, because one request cannot
            // carry two different TE values and the DELTA between them is the whole assertion: it is
            // what shows the carve-out turns on the VALUE rather than on the name. Both legs ride the
            // forward-all route, so neither is allow-listed, and both carry the same permissive
            // control so a missing TE can be told apart from a request that never arrived.
            var trailersLeg = given()
                    .header(TE_HEADER, "trailers")
                    .header(UNLISTED_HEADER, "crosses-on-the-baseline")
                    .when()
                    .get(FORWARD_ALL_PATH)
                    .then()
                    .statusCode(200)
                    .extract();
            var otherValueLeg = given()
                    .header(TE_HEADER, "gzip")
                    .header(UNLISTED_HEADER, "crosses-on-the-baseline")
                    .when()
                    .get(FORWARD_ALL_PATH)
                    .then()
                    .statusCode(200)
                    .extract();

            // Assert
            Map<String, ?> withTrailers = trailersLeg.path("headers");
            Map<String, ?> withOtherValue = otherValueLeg.path("headers");
            assertNotNull(withTrailers, "go-httpbin echoes the headers it received");
            assertNotNull(withOtherValue, "go-httpbin echoes the headers it received");
            assertAll("the TE carve-out is value-dependent, which is why it is not a set membership",
                    () -> assertTrue(containsIgnoringCase(withTrailers, TE_HEADER),
                            "RFC 9110 admits TE: trailers end-to-end, and the permissive copy carries it"
                                    + " with no entry naming it — which is why a gRPC upstream behind a"
                                    + " forward-all route works unconfigured. Echoed headers were: "
                                    + withTrailers.keySet()),
                    () -> assertFalse(containsIgnoringCase(withOtherValue, TE_HEADER),
                            "any other TE value negotiates a transfer coding for the hop the gateway"
                                    + " terminates, so it is withheld. Echoed headers were: "
                                    + withOtherValue.keySet()),
                    () -> assertTrue(containsIgnoringCase(withOtherValue, UNLISTED_HEADER),
                            "the matched control: the withheld leg's request DID reach the upstream, so"
                                    + " the absence above is the carve-out and not a dropped request."
                                    + " Echoed headers were: " + withOtherValue.keySet()));
        }

        /**
         * Case-insensitive key lookup. HTTP header names are case-insensitive and both the gateway and
         * the upstream may canonicalise them, so an exact-key check could report a smuggled header as
         * absent merely because its casing changed in transit.
         *
         * @param headers the echoed header map
         * @param name the header name to look for
         * @return {@code true} when any key matches ignoring case
         */
        private static boolean containsIgnoringCase(Map<String, ?> headers, String name) {
            return headers.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(name));
        }
    }
}
