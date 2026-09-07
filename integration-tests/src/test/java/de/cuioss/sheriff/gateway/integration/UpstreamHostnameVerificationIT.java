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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves {@code egress_tls.upstream_verify_hostname} (ADR-0040) end to end against the matched
 * control pair in the Docker stack — and proves what, specifically, the knob relaxes.
 * <p>
 * The knob is bound at Vert.x client construction, so it is a property of the whole gateway process
 * and one instance can only ever exhibit one side of it. The fixture is therefore a
 * <strong>pair</strong> of instances — {@code api-sheriff-egress-verify-on} on 10450 and
 * {@code api-sheriff-egress-verify-off} on 10451 — whose overlaid {@code gateway.yaml} documents
 * differ in exactly that one scalar. Both dial the same {@code mismatched-tls-backend}, whose
 * certificate's only SAN is {@code upstream-mismatch} while the gateways reach it as
 * {@code mismatched-tls-backend}, and both bind the {@code it-upstream} trust profile that holds
 * that certificate. Chain trust therefore <em>succeeds</em> on both legs and hostname matching is
 * the only variable left. {@code EgressVerifyActivationWiringTest} is the fast surefire guard that
 * the fixture still has that shape; this suite is the black-box proof that the shape produces the
 * behaviour.
 * <p>
 * <strong>Three legs, because two are not enough.</strong> The first two legs establish that the
 * knob changes the outcome. They do not, on their own, establish <em>what</em> it relaxed: a knob
 * that had quietly disabled certificate verification wholesale would produce the identical pair of
 * observations. The third leg is what separates the two readings — it dials an origin whose
 * certificate chains to nothing the pair trusts while its SAN <em>does</em> cover the dialled name,
 * so with hostname matching relaxed the verify-off instance must still refuse it. Without that leg
 * the suite would pass just as happily against a gateway that had thrown away upstream trust
 * entirely, which for a security gateway is the failure that matters.
 * <p>
 * All three legs run against the gateway's public edge over HTTPS with relaxed validation — the
 * edge certificate is the self-signed {@code localhost} bundle, and it is not what is under test
 * here. The TLS property under test is on the gateway's <em>egress</em> leg, which the client never
 * sees except through the status code.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Upstream hostname verification (egress TLS)")
class UpstreamHostnameVerificationIT {

    /**
     * The verify-ON leg's published port. Wired by the Failsafe configuration from the same literal
     * the compose file publishes; the fallback keeps a manually-launched run pointed at the right
     * instance rather than silently at the primary gateway on 10443, which dials plain-HTTP
     * go-httpbin and would make every assertion below meaningless.
     */
    private static final String DEFAULT_VERIFY_ON_PORT = "10450";

    /** The verify-OFF leg's published port, wired and defaulted on the same reasoning. */
    private static final String DEFAULT_VERIFY_OFF_PORT = "10451";

    /**
     * The shared {@code /proxy} route. Both instances resolve its {@code UPSTREAM} alias through
     * {@code TOPOLOGY_UPSTREAM} to the hostname-mismatched backend, so this one path is the whole
     * matched control: the same request, against the same upstream, on two instances.
     */
    private static final String MISMATCHED_UPSTREAM_PATH = "/proxy";

    /**
     * The chain-trust control route ({@code endpoints/untrusted.yaml}), whose {@code UNTRUSTED_UPSTREAM}
     * alias resolves to {@code passthrough-backend} — an origin whose self-signed certificate is
     * generated at container start and so chains to nothing the {@code it-upstream} profile trusts,
     * while naming the dialled host correctly.
     */
    private static final String UNTRUSTED_UPSTREAM_PATH = "/proxy/untrusted";

    /**
     * The status the error contract maps a failed upstream dial to. Pinned to the exact value rather
     * than "any 5xx" because the neighbouring upstream statuses mean different things and a test
     * that accepted them all would pass on the wrong failure: 504 is
     * {@code UPSTREAM_TIMEOUT} (the dial completed and the upstream was slow) and 503 is
     * {@code UPSTREAM_CIRCUIT_OPEN} (the upstream was never called at all). A TLS handshake failure
     * carries neither shape, so {@code UpstreamFailureMapper} classifies it {@code UPSTREAM_ERROR}
     * and the edge renders 502.
     */
    private static final int UPSTREAM_DIAL_REFUSED = 502;

    /**
     * The fixed body {@code mismatched-tls-backend} answers every path with. Asserting the body and
     * not merely the status is what makes the negative leg a reachability proof: a 200 alone could
     * in principle come from somewhere else in the chain, whereas this string is served by exactly
     * one container in the stack.
     */
    private static final String MISMATCHED_BACKEND_BODY = "mismatched-tls-backend";

    @Test
    @DisplayName("verify-on: the gateway refuses an upstream whose certificate does not name it")
    void verifyOnInstanceRefusesTheHostnameMismatchedUpstream() {
        ExtractableResponse<Response> response = givenVerifyOnInstance()
                .when()
                .get(MISMATCHED_UPSTREAM_PATH)
                .then()
                .extract();

        assertUpstreamDialRefused(response,
                "the verify-on instance dials mismatched-tls-backend, whose certificate names"
                        + " upstream-mismatch and not the dialled host, so the handshake must fail"
                        + " and the edge must render " + UPSTREAM_DIAL_REFUSED + ". A 200 here means"
                        + " upstream_verify_hostname: true performed no hostname check at all");
    }

    @Test
    @DisplayName("verify-off: the same upstream, on the sibling instance, is reached")
    void verifyOffInstanceReachesTheHostnameMismatchedUpstream() {
        ExtractableResponse<Response> response = givenVerifyOffInstance()
                .when()
                .get(MISMATCHED_UPSTREAM_PATH)
                .then()
                .statusCode(200)
                .extract();

        // The matched half of the control. Its value is entirely in being the SAME request against
        // the SAME upstream as the leg above: the only thing that differs between the two instances
        // is upstream_verify_hostname, so a green pair attributes the difference to that scalar and
        // to nothing else. Read alone this leg proves only that the backend answers.
        assertTrue(response.asString().contains(MISMATCHED_BACKEND_BODY),
                "the verify-off instance must reach mismatched-tls-backend and return its body,"
                        + " proving the dial completed rather than merely that some 200 was produced;"
                        + " body was: " + response.asString());
    }

    @Test
    @DisplayName("verify-off: relaxing hostname matching does NOT relax chain trust")
    void verifyOffInstanceStillRefusesAnUntrustedChain() {
        ExtractableResponse<Response> response = givenVerifyOffInstance()
                .when()
                .get(UNTRUSTED_UPSTREAM_PATH)
                .then()
                .extract();

        // This is the leg that makes the other two mean what they claim. The instance under test is
        // the one with hostname verification OFF, and the origin's SAN covers the dialled name — so
        // hostname matching cannot be what fails here, and the only remaining reason to refuse is
        // that the certificate chains to nothing the it-upstream profile trusts. A 200 would mean
        // the knob had switched off certificate verification wholesale, which is a silent downgrade
        // from a hostname policy to no upstream trust at all.
        assertUpstreamDialRefused(response,
                "the verify-off instance must STILL refuse an untrusted chain: upstream_verify_hostname"
                        + " relaxes hostname matching only. A 200 here means the knob disabled"
                        + " certificate verification wholesale, and the two legs above would then be"
                        + " consistent with a gateway that trusts any upstream certificate");
    }

    /**
     * Asserts the edge rendered the upstream-dial refusal, reporting the observed body when it did
     * not. The body is included because the two ways this assertion fails need different fixes: a
     * 200 carrying the upstream's own body means the dial succeeded and the knob did nothing, while
     * a 404 means the route is not in the assembled table and the request never reached an upstream
     * — a fixture fault rather than a behavioural one.
     *
     * @param response the extracted response
     * @param reason   why the refusal is expected on this leg
     */
    private static void assertUpstreamDialRefused(ExtractableResponse<Response> response, String reason) {
        assertEquals(UPSTREAM_DIAL_REFUSED, response.statusCode(),
                reason + ". Body was: " + response.asString());
    }

    /**
     * A request specification bound to the verify-ON instance's public edge.
     * <p>
     * Relaxed HTTPS validation is set per-specification rather than through
     * {@link BaseIntegrationTest}'s global switch because this suite deliberately does not extend
     * that base: the base pins REST Assured to the primary gateway on 10443, and inheriting it would
     * let a missing system property silently retarget every assertion at an instance that performs
     * no hostname verification at all.
     *
     * @return a specification aimed at the verify-on leg
     */
    private static RequestSpecification givenVerifyOnInstance() {
        return givenInstance(System.getProperty("test.egress.verify.on.port", DEFAULT_VERIFY_ON_PORT));
    }

    /**
     * A request specification bound to the verify-OFF instance's public edge.
     *
     * @return a specification aimed at the verify-off leg
     */
    private static RequestSpecification givenVerifyOffInstance() {
        return givenInstance(System.getProperty("test.egress.verify.off.port", DEFAULT_VERIFY_OFF_PORT));
    }

    private static RequestSpecification givenInstance(String port) {
        return given()
                .relaxedHTTPSValidation()
                .baseUri("https://localhost:" + port)
                .basePath("");
    }
}
