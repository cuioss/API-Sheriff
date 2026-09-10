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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Proves the plain-HTTP contract end to end in the shipped distroless native image: a
 * certificate-less deployment serves cleartext <em>when it declares that it wants to</em>, and
 * refuses to boot when it does not.
 * <p>
 * <strong>Both halves are here on purpose, because either one alone is satisfiable by the wrong
 * implementation.</strong> A gateway that always serves plain HTTP without a certificate passes the
 * opt-in half — and is exactly the silent downgrade this contract forbids. A gateway that always
 * refuses passes the refusal half, and is useless behind a TLS-terminating ingress. Only the pair
 * pins "plain HTTP is declared, never inferred".
 * <p>
 * <strong>Why an integration test rather than a {@code @QuarkusTest}.</strong> The gate is an
 * {@code @ApplicationScoped} {@code HttpServerOptionsCustomizer}, so two distinct things have to
 * survive native-image generation for it to act at all: Arc has to still discover the bean, and
 * {@code VertxHttpRecorder.initializeMainHttpServer} has to still invoke the customizer loop before
 * its own {@code getKeyCertOptions()} guard. Neither is observable from a JVM-mode boot. Nothing
 * rebuilds anything for this test — the opt-in instance runs the same {@code api-sheriff:distroless}
 * image as every other gateway service, and every refusal leg runs that same image once.
 * <p>
 * <strong>A third leg class covers the combinations the opt-in does NOT stand down.</strong> The
 * opt-in declares the posture of a <em>coherent</em> deployment; declared key material, inbound mTLS
 * and an SNI passthrough topology each only mean something on a listener that terminates TLS, so
 * each is refused rather than ignored. {@link IncoherentCombinations} drives all three through the
 * same one-off container harness the withheld-opt-in leg uses, over gateway.yaml overlays that
 * already ship — no new compose service, no new configuration directory.
 * <p>
 * <strong>A fourth covers what the mode changes about a RUNNING gateway,</strong> asserted against
 * the long-lived opt-in instance on its published plain port: client-address attribution, and the
 * cookie hardening the gateway emits for a cleartext listener. See {@link ObservableInteractions}
 * for what each of those two legs does and does not establish.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("No-certificate plain-HTTP opt-in")
class NoCertificatePlainHttpOptInIT {

    /** The plain HTTP port the opt-in instance serves on — compose publishes container 8080 here. */
    private static final String DEFAULT_PLAIN_PORT = "10453";

    /**
     * The terminated HTTPS port. Compose publishes it deliberately with <em>nothing</em> behind it:
     * a port that is never published cannot be dialled at all, and "I could not dial it" would then
     * be a property of the descriptor rather than of the gateway.
     */
    private static final String DEFAULT_HTTPS_PORT = "10454";

    private static final String LOG_FILE = "quarkus-no-certificate.log";

    /**
     * A directory-served asset on the shared {@code /assets/static} route, which the endpoint
     * descriptor declares {@code require: none}. It is served from a mounted file rather than
     * proxied, so the positive control depends on no upstream container and no token.
     */
    private static final String PUBLIC_ASSET = "/assets/static/app.css";

    /** The framework's own startup line — present only when boot completed. */
    private static final String STARTED_MARKER = "started in";

    /**
     * The framework's own refusal. It is the message this gate exists to pre-empt: it names
     * {@code enabled} to an operator who set {@code redirect}, says nothing about the certificate
     * that is actually missing, and offers no remedy. Neither leg of this test may see it.
     */
    private static final String FRAMEWORK_REFUSAL =
            "Cannot set quarkus.http.insecure-requests without enabling SSL.";

    private static String plainBaseUri() {
        return "http://localhost:" + System.getProperty("test.no.certificate.plain.port", DEFAULT_PLAIN_PORT);
    }

    private static String httpsUri() {
        return "https://localhost:"
                + System.getProperty("test.no.certificate.https.port", DEFAULT_HTTPS_PORT) + PUBLIC_ASSET;
    }

    /**
     * The deliberate deployment: no certificate, and {@code QUARKUS_HTTP_INSECURE_REQUESTS=enabled}
     * saying so. Asserted against the long-lived compose instance.
     */
    @Nested
    @DisplayName("With the opt-in declared, the gateway serves plain HTTP")
    class WithTheOptIn {

        @Test
        @DisplayName("the terminated main listener serves the application over PLAIN HTTP")
        void mainListenerServesOverPlainHttp() {
            given()
                    .baseUri(plainBaseUri())
                    .basePath("")
                    .when()
                    .get(PUBLIC_ASSET)
                    .then()
                    .statusCode(200);
        }

        @Test
        @DisplayName("the HTTPS port does not answer — the plain answer is not a redirect")
        void httpsPortDoesNotAnswer() {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(httpsUri()))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            // IOException rather than SSLException specifically, and that is the correct breadth HERE
            // even though the sibling ManagementPlainHttpOptOutIT deliberately narrows to
            // SSLException. The two tests are asking opposite questions. There, something IS
            // listening and the claim is that it speaks cleartext, so the broad type would also pass
            // against a dead port and report success for the wrong reason. Here the claim is that
            // NOTHING terminates TLS on this port, so a connection reset from the
            // published-but-unbacked port and a failed handshake are both the expected outcome and
            // neither is more correct than the other. What keeps the assertion from passing
            // vacuously is the matched positive control in mainListenerServesOverPlainHttp: the
            // instance demonstrably answers on its plain port, so "nothing answered here" cannot be
            // a container that never started.
            assertThrows(IOException.class,
                    () -> client.send(request, HttpResponse.BodyHandlers.discarding()),
                    "no key material was declared, so no terminated HTTPS listener may exist; if "
                            + "this request succeeds the instance is serving HTTPS and the plain-HTTP "
                            + "answer above was a redirect or a second listener");
        }

        @Test
        @DisplayName("boot reported the RESOLVED plain-HTTP listener with WARN ApiSheriff-121")
        void bootReportedTheResolvedPlainHttpListener() {
            String log = readContainerLog();

            assertAll("the cleartext was asked for, and is still said out loud",
                    () -> assertTrue(log.contains("ApiSheriff-121"),
                            "the resolved plain-HTTP state must be announced with the WARN "
                                    + "identifier so it is greppable"),
                    () -> assertTrue(log.contains("Terminated main listener is serving PLAIN HTTP on port"),
                            "the WARN must name what actually happened, not merely fire"),
                    () -> assertFalse(log.contains("ApiSheriff-123"),
                            "ApiSheriff-123 announced an INFERRED downgrade and was retired with the "
                                    + "inference. Its reappearance would mean something is again "
                                    + "projecting an exposure strategy nobody declared"));
        }

        @Test
        @DisplayName("the container reached a running state, and never saw the framework refusal")
        void containerReachedARunningState() {
            String log = readContainerLog();

            assertAll("the opt-in is what makes this boot legal",
                    () -> assertTrue(log.contains(STARTED_MARKER),
                            "the framework's own startup line is the property that separates "
                                    + "'serving plain HTTP' from 'never got that far' — the other "
                                    + "assertions here cannot tell them apart"),
                    () -> assertFalse(log.contains(FRAMEWORK_REFUSAL),
                            () -> "the declared opt-in must satisfy the framework guard outright: "
                                    + FRAMEWORK_REFUSAL));
        }
    }

    /**
     * The matched negative leg: the SAME image and the SAME certificate-less state, with the opt-in
     * withheld.
     * <p>
     * <strong>Why a one-off {@code docker run} rather than a compose service.</strong> A service
     * whose whole purpose is to fail could not live in the stack: the readiness gate selects every
     * {@code api-sheriff*} service and waits for it to report healthy, so a permanently-refusing
     * instance would fail the gate rather than be observed by it. The one-off container is driven
     * with no {@code /logs} mount and no {@code QUARKUS_LOG_FILE_ENABLED}, so unlike the opt-in
     * instance it writes no log file at all and the refusal is read from its own merged output.
     * <p>
     * <strong>The refusal lands at the LISTENER seam, not during configuration assembly, and the
     * difference is what
     * {@link NoCertificatePlainHttpOptInIT#runWithoutCertificateOrOptIn()} has to provision
     * for.</strong> The
     * gate is invoked from {@code VertxHttpRecorder.initializeMainHttpServer}, which runs after every
     * {@code StartupEvent} observer has completed. A container that dies in one of those observers
     * therefore never reaches the gate — and exits non-zero without a startup line while doing so,
     * which is indistinguishable from a real refusal on those two properties alone. That is why the
     * run below mirrors the compose service's whole environment and why
     * {@link NoCertificatePlainHttpOptInIT#REACHED_LISTENER_SEAM} is asserted before either test
     * reads the output.
     */
    @Nested
    @DisplayName("Without the opt-in, the gateway refuses to boot")
    class WithoutTheOptIn {

        /** The message this gate replaces the framework's own with. */
        private static final String OUR_REFUSAL = "No TLS key material is declared";

        @Test
        @DisplayName("it exits non-zero rather than falling back to cleartext")
        void refusesToBoot() {
            RefusalRun run = runWithoutCertificateOrOptIn();

            assertAll("a missing certificate must never resolve itself into a plain-HTTP listener",
                    () -> assertNotEquals(0, run.exitCode(),
                            () -> "the container exited 0, so it started on a configuration that "
                                    + "declares neither key material nor a plain-HTTP opt-in. Output: "
                                    + run.output()),
                    () -> assertFalse(run.output().contains(STARTED_MARKER),
                            () -> "the framework's startup line appeared, so the gateway served "
                                    + "something before giving up — the refusal must land while the "
                                    + "listener is being initialized, before it ever binds. "
                                    + "Output: " + run.output()));
        }

        @Test
        @DisplayName("the refusal names the missing material and BOTH remedies, not the framework's message")
        void refusalCarriesOurMessage() {
            RefusalRun run = runWithoutCertificateOrOptIn();
            String output = run.output();

            assertAll("the whole point of acting this early is to say something usable",
                    () -> assertTrue(output.contains(OUR_REFUSAL),
                            () -> "the refusal must name what is missing: " + output),
                    () -> assertTrue(output.contains("quarkus.http.ssl.certificate.key-store-file"),
                            () -> "remedy one is 'declare a certificate', which is worthless unless "
                                    + "the supported spellings are named — a keystore deployment "
                                    + "carries no .files key at all: " + output),
                    () -> assertTrue(output.contains("quarkus.http.insecure-requests=enabled"),
                            () -> "remedy two is the explicit plain-HTTP opt-in, spelled as the "
                                    + "operator would type it: " + output),
                    () -> assertFalse(output.contains(FRAMEWORK_REFUSAL),
                            () -> "the framework's own message names 'enabled' to an operator who "
                                    + "set 'redirect' and offers no remedy; reaching it means this "
                                    + "gate did not act first: " + output));
        }
    }

    /**
     * The three combinations the opt-in does <em>not</em> stand down.
     * <p>
     * Each leg sets {@code QUARKUS_HTTP_INSECURE_REQUESTS=enabled} — so none of them is the
     * certificate-absence refusal the class above covers — and then adds exactly one declaration
     * that only means something on a listener which terminates TLS. All three run through the same
     * one-off container harness, over configuration that already ships: the passthrough-empty
     * overlay for R1, the mTLS overlay for R2, and for R3 <em>no</em> overlay at all, so the shared
     * gateway.yaml with its non-empty {@code passthrough_sni} applies.
     * <p>
     * Each keeps the {@link NoCertificatePlainHttpOptInIT#reachedTheListenerSeam} positive control,
     * so a container dying anywhere before the gate cannot pass for a refusal.
     */
    @Nested
    @DisplayName("With the opt-in declared, an incoherent TLS declaration still refuses the boot")
    class IncoherentCombinations {

        @Test
        @DisplayName("R1: declared server key material alongside the opt-in refuses, naming the key")
        void declaredKeyMaterialRefuses() {
            RefusalRun run = runRefusal(
                    List.of(OPT_IN,
                            "QUARKUS_HTTP_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt",
                            "QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key"),
                    overlayMount(PASSTHROUGH_EMPTY_GATEWAY));
            String output = run.output();

            assertAll("one listener cannot both serve cleartext and terminate TLS",
                    () -> assertNotEquals(0, run.exitCode(),
                            () -> "the container started on a configuration declaring both a "
                                    + "certificate and the plain-HTTP opt-in. Output: " + output),
                    () -> assertFalse(output.contains(STARTED_MARKER),
                            () -> "the refusal must land while the listener is initialized, before it "
                                    + "ever binds. Output: " + output),
                    () -> assertTrue(output.contains("quarkus.http.ssl.certificate.files"),
                            () -> "the refusal must name the offending key, and it must be the one "
                                    + "this deployment actually set: " + output),
                    () -> assertTrue(output.contains("Either remove quarkus.http.ssl.certificate.files"),
                            () -> "remedy one is 'drop the certificate', spelled out for THAT key: "
                                    + output),
                    () -> assertTrue(output.contains("remove " + OPT_IN_KEY),
                            () -> "remedy two is 'drop the opt-in, the declared certificate then "
                                    + "terminates TLS': " + output),
                    () -> assertFalse(output.contains(FRAMEWORK_REFUSAL),
                            () -> "reaching the framework's own message means this gate did not act "
                                    + "first: " + output));
        }

        @Test
        @DisplayName("R2: tls.mtls.enabled alongside the opt-in refuses, naming the key")
        void inboundMtlsRefuses() {
            RefusalRun run = runRefusal(List.of(OPT_IN), overlayMount(MTLS_GATEWAY));
            String output = run.output();

            assertAll("a gateway that terminates no TLS can verify no client certificate",
                    () -> assertNotEquals(0, run.exitCode(),
                            () -> "the container started with a client-certificate requirement that "
                                    + "no handshake could ever enforce. Output: " + output),
                    () -> assertFalse(output.contains(STARTED_MARKER),
                            () -> "the refusal must land before the listener binds. Output: " + output),
                    () -> assertTrue(output.contains("tls.mtls.enabled"),
                            () -> "the refusal must name the offending key in the gateway.yaml "
                                    + "spelling the operator wrote it in: " + output),
                    () -> assertTrue(output.contains("remove the tls.mtls block"),
                            () -> "remedy one is 'drop the mTLS requirement': " + output),
                    () -> assertTrue(output.contains("remove " + OPT_IN_KEY),
                            () -> "remedy two is 'terminate TLS instead': " + output),
                    () -> assertTrue(output.contains("silently inert"),
                            () -> "the message must say WHY the combination is refused rather than "
                                    + "ignored — the requirement never runs at all: " + output));
        }

        @Test
        @DisplayName("R3: a non-empty tls.passthrough_sni alongside the opt-in refuses, naming the key")
        void passthroughSniRefuses() {
            // No overlay: the SHARED sheriff-config/gateway.yaml applies, and it is the document that
            // declares passthrough_sni. Mounting nothing is what selects this leg's configuration.
            RefusalRun run = runRefusal(List.of(OPT_IN), List.of());
            String output = run.output();

            assertAll("the front listener claims the public TLS port a plain port contradicts",
                    () -> assertNotEquals(0, run.exitCode(),
                            () -> "the container started with both an SNI passthrough topology and a "
                                    + "plain-HTTP application port. Output: " + output),
                    () -> assertFalse(output.contains(STARTED_MARKER),
                            () -> "the refusal must land before the listener binds. Output: " + output),
                    () -> assertTrue(output.contains("tls.passthrough_sni"),
                            () -> "the refusal must name the offending key: " + output),
                    () -> assertTrue(output.contains("remove the tls.passthrough_sni block"),
                            () -> "remedy one is 'drop the passthrough topology': " + output),
                    () -> assertTrue(output.contains("remove " + OPT_IN_KEY),
                            () -> "remedy two is 'terminate TLS instead': " + output));
        }
    }

    /**
     * What the mode changes about a gateway that is actually running, asserted end to end against
     * the long-lived opt-in instance.
     * <p>
     * Both legs are bounded deliberately, and the bounds are the point:
     * <ul>
     *   <li><strong>Leg A</strong> asserts that NO {@code X-Forwarded-For} reaches the upstream, as a
     *       matched pair. What differs by posture is <em>not</em> the VALUE the gateway regenerates
     *       but WHETHER it regenerates one at all — none under zero trust, the honoured chain under
     *       a declared trusted peer. This instance declares no {@code forwarded.trusted_proxies}, so
     *       {@code TcpPeerGate} trusts no peer and
     *       {@code ForwardPolicyStage.applyRegeneratedForwarding} (:387-399) hands the resolver an
     *       empty list for every forwarding name, while cui-http's
     *       {@code ForwardedHeaderResolver.resolveClientIp} returns empty unconditionally on an
     *       empty {@code trustedProxies} — so {@code ResolvedForwarding.toXForwardedHeaders} emits
     *       nothing. The gateway never synthesises a chain from the TCP peer instead: that resolver
     *       ({@code ForwardedHeaderResolver.java:53,62-63}) is never handed the socket remote address
     *       and by design does not accept it as a parameter. Declaring the caller's own peer — or
     *       any range containing it — in {@code forwarded.trusted_proxies} makes run A's spoofed
     *       {@code 203.0.113.9} honoured and regenerated upstream, so the leg goes red; that is what
     *       makes the absence discriminate the zero-trust default rather than restate an
     *       unconditional strip. A marker header the gateway has no opinion about, echoed back by
     *       the upstream on both runs, is what keeps the null reading a real observation rather than
     *       a broken route or an empty echo.</li>
     *   <li><strong>Leg B</strong> asserts the emitted {@code Set-Cookie} and claims nothing about
     *       what a browser does with it. This lane drives a Java HTTP client that accepts what a
     *       browser would refuse — the limitation
     *       {@code doc/development/cookie-deliverability-blindness.adoc} owns — so the browser-side
     *       half of the cookie verdict belongs to the operator documentation's topology statement,
     *       not to this test.</li>
     * </ul>
     */
    @Nested
    @DisplayName("What the plain-HTTP mode changes about a running gateway")
    class ObservableInteractions {

        /**
         * RFC 5737 TEST-NET-3 — reserved for documentation and never routable, so it cannot collide
         * with a real address this stack might legitimately see.
         */
        private static final String SPOOFED_CLIENT = "203.0.113.9";

        private static final String FORWARDED_FOR = "X-Forwarded-For";

        /**
         * A header the gateway has no opinion about, sent on both runs of Leg A purely so its echo
         * proves the upstream saw the request.
         * <p>
         * It is in neither {@code ForwardPolicyStage.FORWARDING_HEADERS} nor
         * {@code ConnectionHeaders.REQUEST_STRIP}, and the forward-all route declares no
         * {@code headers_deny}, so the copy carries it verbatim. That is what makes it a vacuity
         * guard for an assertion whose subject is an ABSENT header: without it a null
         * {@code X-Forwarded-For} would read the same whether the gateway withheld one or the route
         * never answered at all.
         */
        private static final String ECHO_PROBE = "X-Sheriff-Echo-Probe";

        /** The login path the shared oidc block carves out on the {@code localhost} oidc host. */
        private static final String LOGIN_PATH = "/auth/login";

        private static final String BINDING_COOKIE = "__Host-sheriff-binding";

        @Test
        @DisplayName("Leg A: an untrusted peer gets NO regenerated forwarding attribution, spoof or no spoof")
        void noForwardingAttributionIsRegeneratedForAnUntrustedPeer() {
            // Arrange + Act — the SAME instance and the SAME public route twice, differing only in
            // whether the caller claims a forwarded chain. Each run carries its own marker value so
            // an echo can never be mistaken for the other run's.
            String spoofedProbe = "run-a-with-spoof";
            String unspoofedProbe = "run-b-no-inbound-header";

            EchoedRequest withSpoof = echoOnce(SPOOFED_CLIENT, spoofedProbe);
            EchoedRequest withoutSpoof = echoOnce(null, unspoofedProbe);

            assertAll("under zero trust the gateway attributes nobody, and says so by emitting nothing",
                    () -> assertEquals(spoofedProbe, withSpoof.marker(),
                            "the vacuity guard for run A: the upstream must echo " + ECHO_PROBE
                                    + " back, or the absent " + FORWARDED_FOR + " below is a broken "
                                    + "route rather than a gateway decision"),
                    () -> assertEquals(unspoofedProbe, withoutSpoof.marker(),
                            "the vacuity guard for run B, for the same reason"),
                    () -> assertNull(withSpoof.forwardedFor(),
                            "THIS is the assertion that carries the verdict. Run A claimed "
                                    + SPOOFED_CLIENT + " and the upstream must see NO "
                                    + FORWARDED_FOR + " at all: the peer is untrusted, so "
                                    + "ForwardPolicyStage.applyRegeneratedForwarding (:387-399) "
                                    + "blanks the inbound claim AND the resolver — whose "
                                    + "trustedProxies is empty — regenerates nothing to put in its "
                                    + "place. Declare the caller's own peer (or any range containing "
                                    + "it) in forwarded.trusted_proxies and the chain IS honoured: "
                                    + "the upstream then sees '" + SPOOFED_CLIENT + "' here and this "
                                    + "assertion goes red, which is what makes the absence "
                                    + "discriminate the zero-trust default rather than restate an "
                                    + "unconditional strip"),
                    () -> assertNull(withoutSpoof.forwardedFor(),
                            "the matched control: with no inbound header the upstream sees none "
                                    + "either. The gateway emits no forwarding attribution for "
                                    + "anyone under this posture — it never derives one from the TCP "
                                    + "peer, because cui-http's ForwardedHeaderResolver "
                                    + "(:53,62-63) is never handed the socket remote address and by "
                                    + "design does not accept it"));
        }

        @Test
        @DisplayName("Leg B: the login-time binding cookie keeps Secure on a cleartext listener")
        void bindingCookieStaysHardenedOnThePlainListener() {
            // Arrange + Act — the reserved login path emits the BINDING cookie at redirect time.
            // The session cookie is emitted only at callback, after the IdP round trip, and this
            // instance's redirect_uri names a different origin — so the binding cookie is the
            // artifact this leg can actually observe, and it is the one the leg is named for.
            var response = given()
                    .baseUri(plainBaseUri())
                    .basePath("")
                    .redirects().follow(false)
                    .when()
                    .get(LOGIN_PATH)
                    .then()
                    .statusCode(302)
                    .extract();

            String bindingCookie = setCookieFor(response.headers().getValues("Set-Cookie"));

            // Assert — the gateway does not weaken a cookie attribute because the listener is plain.
            assertNotNull(bindingCookie,
                    () -> "the login redirect must emit " + BINDING_COOKIE + "; Set-Cookie headers "
                            + "were: " + response.headers().getValues("Set-Cookie"));
            assertAll("nothing relaxes the hardening for cleartext — that is the whole verdict",
                    () -> assertTrue(bindingCookie.contains("Secure"),
                            () -> "Secure is emitted unconditionally, which is exactly why a browser "
                                    + "reaching this port over http:// would drop the cookie: "
                                    + bindingCookie),
                    () -> assertTrue(bindingCookie.contains("HttpOnly"),
                            () -> "HttpOnly is unconditional too: " + bindingCookie),
                    () -> assertTrue(bindingCookie.contains("Path=/"),
                            () -> "Path=/ is one of the three attributes the __Host- prefix requires: "
                                    + bindingCookie),
                    () -> assertTrue(bindingCookie.contains("SameSite=Lax"),
                            () -> "SameSite=Lax pairs with the query response mode the gateway drives: "
                                    + bindingCookie));
        }

        /**
         * What one run of the forward-all echo route observed at the upstream.
         *
         * @param forwardedFor the {@code X-Forwarded-For} the gateway regenerated, or {@code null}
         *                     when the upstream saw none — the leg's subject
         * @param marker       the echoed {@link #ECHO_PROBE} value, or {@code null} when the upstream
         *                     saw none — the guard that makes a {@code null} above an observation
         */
        private record EchoedRequest(String forwardedFor, String marker) {
        }

        /**
         * Drives the forward-all echo route once and reports what crossed to the upstream.
         *
         * @param spoofedChain the forwarding chain the caller claims, or {@code null} to send none
         * @param probeValue   the {@link #ECHO_PROBE} value this run sends, distinct per run so an
         *                     echo cannot be confused with the other run's
         * @return the regenerated forwarding attribution and the echoed marker
         */
        private EchoedRequest echoOnce(String spoofedChain, String probeValue) {
            var request = given()
                    .baseUri(plainBaseUri())
                    .basePath("")
                    .header(ECHO_PROBE, probeValue);
            if (spoofedChain != null) {
                request = request.header(FORWARDED_FOR, spoofedChain);
            }
            var response = request
                    .when()
                    .get(ECHO_ROUTE)
                    .then()
                    .statusCode(200)
                    .extract();

            Map<String, ?> echoed = response.path("headers");
            assertNotNull(echoed, "go-httpbin echoes the headers it received");
            return new EchoedRequest(firstValueIgnoringCase(echoed, FORWARDED_FOR),
                    firstValueIgnoringCase(echoed, ECHO_PROBE));
        }

        /**
         * @param setCookies every {@code Set-Cookie} header on the login redirect
         * @return the binding cookie's header value, or {@code null} when it was not emitted
         */
        private String setCookieFor(List<String> setCookies) {
            for (String header : setCookies) {
                if (header.startsWith(BINDING_COOKIE + "=")) {
                    return header;
                }
            }
            return null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Harness
    // ---------------------------------------------------------------------------------------------

    /** Upper bound on the refusal container's whole lifetime; it fails as the listener starts. */
    private static final long REFUSAL_TIMEOUT_SECONDS = 90L;

    /** The image every gateway instance in this stack runs, and the one under test here. */
    private static final String IMAGE = "api-sheriff:distroless";

    /** The shipped integration configuration, mounted exactly as the compose service mounts it. */
    private static final Path SHERIFF_CONFIG = Path.of("src", "main", "docker", "sheriff-config");

    /**
     * The certificate directory. It is mounted for {@code test-jwks.json} and the management
     * listener's material — NOT for the main listener, which is what this test withholds.
     * <p>
     * Mounting it does not weaken the state under test, and the reason is the whole shape of the
     * gate: key material is declared through configuration KEYS, never inferred from a file being
     * present. No {@code quarkus.http.ssl.certificate.*} key and no
     * {@code quarkus.http.tls-configuration-name} is set below, so the main listener declares
     * nothing whatever this directory contains.
     */
    private static final Path CERTIFICATES = Path.of("src", "main", "docker", "certificates");

    /**
     * The no-{@code passthrough_sni} gateway.yaml the compose service overlays. Without it the shared
     * gateway.yaml starts the accept-time SNI front listener, which is a second, unrelated way for
     * this container to behave differently from the instance it is the matched negative of.
     */
    private static final Path PASSTHROUGH_EMPTY_GATEWAY =
            Path.of("src", "main", "docker", "sheriff-config-passthrough-empty", "gateway.yaml");

    /**
     * The already-shipped overlay whose {@code tls} block enables inbound mTLS. Mounted by the R2
     * leg; no new configuration directory is added for it.
     */
    private static final Path MTLS_GATEWAY =
            Path.of("src", "main", "docker", "sheriff-config-mtls", "gateway.yaml");

    /** The explicit plain-HTTP opt-in, as an environment entry and as the key the refusals name. */
    private static final String OPT_IN_KEY = "quarkus.http.insecure-requests=enabled";
    private static final String OPT_IN = "QUARKUS_HTTP_INSECURE_REQUESTS=enabled";

    /** The forward-all echo route: no forward block, so the upstream echoes what actually crossed. */
    private static final String ECHO_ROUTE = "/proxy/forward-all";

    /**
     * Proof that the container got as far as the seam the gate acts at.
     * <p>
     * {@code DefaultTrustSourceAudit} emits this from a {@code StartupEvent} observer, so its
     * presence means configuration loading, CDI bean creation, token-validator construction and TLS
     * registry resolution all succeeded and the only remaining step was starting the HTTP listener.
     * It is the positive control that stops "the container exited non-zero without a startup line"
     * from passing for an unrelated crash — which is exactly how this test once passed its refusal
     * assertion while the container was in fact dying on a missing JWKS file, several steps before
     * the gate.
     * <p>
     * <strong>It is deliberately NOT {@code ApiSheriff-121}, and the difference is load-bearing.</strong>
     * That record is emitted only when the listener resolves to plain HTTP, so it holds for the legs
     * whose deployment declares no key material — but the R1 leg declares a certificate, resolves to
     * HTTPS, and would never emit it. A control that silently failed on one leg would make that leg
     * unrunnable rather than merely weaker. {@code ApiSheriff-17} is emitted on every boot whatever
     * the listener resolved to, so one control serves every leg, and the surefire evidence is that it
     * lands before the gate refuses.
     */
    private static final String REACHED_LISTENER_SEAM = "ApiSheriff-17";

    /**
     * The outcome of one refusal container.
     *
     * @param exitCode the container's exit status
     * @param output   its merged stdout and stderr
     */
    private record RefusalRun(int exitCode, String output) {
    }

    /**
     * Runs the shipped image once with neither key material nor the plain-HTTP opt-in, and reports
     * how it ended.
     * <p>
     * <strong>{@link #runRefusal} mirrors the {@code api-sheriff-no-certificate} compose service
     * except the variables each leg puts under test.</strong> That is a correctness requirement
     * rather than tidiness:
     * the gate runs after every {@code StartupEvent} observer, so any under-provisioning that kills
     * one of those observers stops the container before the gate is ever consulted — and produces a
     * non-zero exit with no startup line, which is precisely the shape a genuine refusal has. The
     * omission that made this test measure the wrong thing was {@link #CERTIFICATES}, whose
     * {@code test-jwks.json} the {@code token_validation} block loads from disk at
     * {@code StartupEvent}; without it the boot died in {@code TokenValidatorProducer} and never
     * reached the listener.
     * <p>
     * The single deliberate difference from the compose service is the absence of
     * {@code QUARKUS_HTTP_INSECURE_REQUESTS=enabled}. No port is published because this container is
     * never expected to bind one, which also keeps it from colliding with the live stack.
     *
     * @return the exit status and merged output of that container
     */
    private static RefusalRun runWithoutCertificateOrOptIn() {
        return runRefusal(List.of(), overlayMount(PASSTHROUGH_EMPTY_GATEWAY));
    }

    /**
     * The {@code -v} argument pair mounting a gateway.yaml overlay over the shared configuration
     * directory. A leg that passes {@link List#of()} instead keeps the shared document, which is how
     * the passthrough leg selects the only document that declares {@code passthrough_sni}.
     *
     * @param overlay the overlay document to mount
     * @return the two docker arguments mounting it
     */
    private static List<String> overlayMount(Path overlay) {
        return List.of("-v", overlay.toAbsolutePath() + ":/app/sheriff-config/gateway.yaml:ro");
    }

    /**
     * Runs the shipped image once over a deployment that must refuse, and reports how it ended.
     *
     * @param extraEnv     the environment entries this leg adds, in {@code NAME=value} form
     * @param overlayMount the gateway.yaml overlay mount, or empty to keep the shared document
     * @return the exit status and merged output of that container
     */
    private static RefusalRun runRefusal(List<String> extraEnv, List<String> overlayMount) {
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--rm",
                "-e", "QUARKUS_PROFILE=it",
                "-e", "QUARKUS_CONFIG_LOCATIONS=/app/certificates/benchmark-idp-trust.properties",
                // The MANAGEMENT listener's material. A different key family from the main
                // listener's, which is the one these legs govern; the gate never reads these.
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES=/app/certificates/localhost.crt",
                "-e", "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_KEY_FILES=/app/certificates/localhost.key",
                "-e", "SHERIFF_CONFIG_DIR=/app/sheriff-config",
                "-e", "OIDC_CLIENT_SECRET=integration-secret"));
        for (String entry : extraEnv) {
            command.add("-e");
            command.add(entry);
        }
        command.add("-v");
        command.add(CERTIFICATES.toAbsolutePath() + ":/app/certificates:ro");
        command.add("-v");
        command.add(SHERIFF_CONFIG.toAbsolutePath() + ":/app/sheriff-config:ro");
        command.addAll(overlayMount);
        command.add(IMAGE);
        command.add("-Djavax.net.ssl.trustStore=/app/certificates/localhost-truststore.p12");
        command.add("-Djavax.net.ssl.trustStorePassword=localhost-trust");
        command.add("-Djavax.net.ssl.trustStoreType=PKCS12");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Path captured = null;
        try {
            captured = Files.createTempFile("api-sheriff-refusal-", ".out");
            builder.redirectOutput(captured.toFile());
            Process process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(REFUSAL_TIMEOUT_SECONDS))) {
                process.destroyForcibly();
                return fail("the container was still running after " + REFUSAL_TIMEOUT_SECONDS
                        + "s, so it did not refuse at all — it is serving on a configuration this "
                        + "gate is supposed to reject. Environment under test: " + extraEnv
                        + ". Output so far: " + readQuietly(captured));
            }
            return reachedTheListenerSeam(
                    new RefusalRun(process.exitValue(), Files.readString(captured)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run the refusal container — is "
                    + IMAGE + " built?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running the refusal container", e);
        } finally {
            deleteQuietly(captured);
        }
    }

    /**
     * Asserts the container got far enough for the gate to have been consulted at all.
     * <p>
     * Every refusal test reads properties — a non-zero exit, an absent startup line, an absent
     * framework message — that a container dying anywhere before the listener seam satisfies just as
     * well as a container the gate refused. This is the discriminator between the two, and it is
     * checked in the harness rather than in any single test so that none of them can be satisfied by
     * a run that never reached the code under test.
     *
     * @param run the finished refusal container
     * @return that same run, when it reached the seam
     */
    private static RefusalRun reachedTheListenerSeam(RefusalRun run) {
        assertTrue(run.output().contains(REACHED_LISTENER_SEAM),
                () -> "the container never reached the seam the gate acts at, so this run cannot say "
                        + "anything about the gate: it died before the HTTP listener was initialized "
                        + "and its non-zero exit is some OTHER failure. Provision the run to match "
                        + "the api-sheriff-no-certificate compose service — the whole environment, "
                        + "plus only the declarations the leg puts under test. Output: "
                        + run.output());
        return run;
    }

    /**
     * Case-insensitive lookup of one echoed header's value.
     * <p>
     * The lookup ignores case because HTTP field names are case-insensitive and both the gateway and
     * the upstream may canonicalise them, so an exact-key read could report a regenerated header as
     * absent merely because its casing changed in transit. The value is normalized because
     * go-httpbin renders a field as a JSON list, and a generic path read yields either that list or
     * a bare string depending on the echo's shape — the caller compares values, not representations.
     *
     * @param headers the echoed header map
     * @param name    the field name to look for
     * @return the value, comma-joined when the echo carried several, or {@code null} when the
     *         upstream saw no such field
     */
    private static String firstValueIgnoringCase(Map<String, ?> headers, String name) {
        for (Map.Entry<String, ?> entry : headers.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(name)) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof List<?> values) {
                return values.isEmpty()
                        ? null
                        : values.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse(null);
            }
            return value == null ? null : String.valueOf(value);
        }
        return null;
    }

    private static String readQuietly(Path captured) {
        try {
            return Files.readString(captured);
        } catch (IOException e) {
            return "<unreadable: " + e.getMessage() + ">";
        }
    }

    /**
     * Removes the capture file without letting its cleanup mask the outcome of the run it served.
     *
     * @param captured the file to remove; ignored when {@code null}
     */
    private static void deleteQuietly(Path captured) {
        if (captured != null && !captured.toFile().delete()) {
            captured.toFile().deleteOnExit();
        }
    }

    private static String readContainerLog() {
        Path logDir = Path.of(System.getProperty("test.log.dir", "target/quarkus-logs"));
        Path logFile = logDir.resolve(LOG_FILE);
        assertTrue(Files.isRegularFile(logFile),
                () -> "expected the opt-in instance log at " + logFile.toAbsolutePath());
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + logFile, e);
        }
    }
}
