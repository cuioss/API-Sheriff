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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed descriptors still form the
 * <strong>matched control</strong> {@link UpstreamHostnameVerificationIT} reads as one
 * (ADR-0040).
 * <p>
 * That suite's value is entirely in the <em>difference</em> between two gateway instances. A
 * matched control is fragile in a way a single assertion is not: it goes on passing while quietly
 * ceasing to prove anything, and every way it does so is a descriptor edit rather than a code
 * change. Drop the {@code it-upstream} trust profile from the verify-off leg and it fails on chain
 * trust instead of succeeding — the pair still reads red/green while the knob under test is never
 * exercised. Let a second scalar diverge between the two overlays and the observed difference is no
 * longer attributable to the one under measurement. Point the chain-trust leg at the mismatched
 * backend and it collapses into a duplicate of the first leg, still red, still green overall.
 * Retarget a published port and the ITs silently address the primary gateway, which dials
 * plain-HTTP {@code go-httpbin} and performs no hostname verification at all.
 * <p>
 * None of those failures is visible from inside the ITs, and each takes a full native build and a
 * nine-container stack to discover. This guard parses the committed descriptors and catches them in
 * seconds — it starts no container and reaches no network.
 * <p>
 * <strong>The single-variable claim is asserted by construction, not by an enumerated exception
 * list.</strong> The two overlays do carry more than one textual difference: each names its own
 * published port in the {@code oidc} URLs and its own {@code config_version}, so the documents are
 * internally consistent rather than one silently pointing at the other's port. Listing those as
 * permitted exceptions would make the guard weaker every time the list grew. Instead every
 * difference other than the scalar under test must survive the <em>leg-identity substitution</em>
 * ({@code 10450} → {@code 10451}, {@code verify-on} → {@code verify-off}): a value that is the
 * sibling's value with the leg's own name and port swapped in is a restatement of which instance
 * the document belongs to, and a value that is not is a behavioural divergence. That rule admits a
 * new port-bearing key without an edit here and refuses a new behavioural key without one.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Egress hostname-verification matched control — deployment activation")
class EgressVerifyActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    private static final Path VERIFY_ON_OVERLAY = DOCKER.resolve("sheriff-config-egress-verify-on/gateway.yaml");
    private static final Path VERIFY_OFF_OVERLAY = DOCKER.resolve("sheriff-config-egress-verify-off/gateway.yaml");

    /** The trust binding both legs load, and the store it must bind the logical profile to. */
    private static final Path TRUST_BINDING = DOCKER.resolve("certificates/it-upstream-trust.properties");
    private static final String MOUNTED_TRUST_BINDING = "/app/certificates/it-upstream-trust.properties";
    private static final String TRUST_STORE_PATH_KEY = "quarkus.tls.it-upstream.trust-store.p12.path";
    private static final String TRUST_STORE_PASSWORD_KEY = "quarkus.tls.it-upstream.trust-store.p12.password";
    private static final String EXPECTED_TRUST_STORE = "/app/certificates/upstream-truststore.p12";

    /**
     * The compose service-name prefix every member of the egress-verify family carries. The family is
     * DERIVED from the parsed compose document through this prefix rather than enumerated, so a leg
     * added later is swept by the overlay, trust-binding and origin-gating assertions without an edit
     * here — an enumerated pair cannot see a new instance that bypasses all three.
     */
    private static final String EGRESS_VERIFY_SERVICE_PREFIX = "api-sheriff-egress-verify-";

    private static final String VERIFY_ON_SERVICE = EGRESS_VERIFY_SERVICE_PREFIX + "on";
    private static final String VERIFY_OFF_SERVICE = EGRESS_VERIFY_SERVICE_PREFIX + "off";

    /** The shared config-directory mount both legs carry beneath their own single-file overlay. */
    private static final String SHARED_CONFIG_MOUNT = "./src/main/docker/sheriff-config:/app/sheriff-config:ro";

    /**
     * The host-port publications the two Failsafe system properties
     * ({@code test.egress.verify.on.port} / {@code test.egress.verify.off.port}) name. Held as
     * {@code "PORT:"} prefixes because a compose publication is {@code "HOST:CONTAINER"} and the
     * trailing colon is what stops {@code 10450} from also matching a hypothetical {@code 104500}.
     * <p>
     * These two literals are the lockstep point between three files that must agree and cannot check
     * each other: the compose publication, the {@code pom.xml} system property and the IT's own
     * fallback. A drift in any one of them retargets the suite at another instance — most likely the
     * primary gateway on 10443, which dials plain-HTTP {@code go-httpbin} and would let every leg of
     * the control pass while testing nothing.
     */
    private static final String EGRESS_VERIFY_ON_PORT_PREFIX = "10450:";
    private static final String EGRESS_VERIFY_OFF_PORT_PREFIX = "10451:";

    private static final String EGRESS_TLS_BLOCK = "egress_tls";
    private static final String VERIFY_HOSTNAME_KEY = "upstream_verify_hostname";
    private static final String TLS_PROFILE_KEY = "upstream_tls_profile";
    private static final String EXPECTED_TLS_PROFILE = "it-upstream";

    /** The flattened path of the one scalar the pair is a control over. */
    private static final String VERIFY_HOSTNAME_PATH = EGRESS_TLS_BLOCK + "." + VERIFY_HOSTNAME_KEY;

    private static final String LOCATIONS_VARIABLE = "QUARKUS_CONFIG_LOCATIONS";
    private static final String UPSTREAM_VARIABLE = "TOPOLOGY_UPSTREAM";
    private static final String UNTRUSTED_VARIABLE = "TOPOLOGY_UNTRUSTED";

    /** The shared descriptor backing the chain-trust leg, and the alias it resolves. */
    private static final Path UNTRUSTED_ENDPOINT = DOCKER.resolve("sheriff-config/endpoints/untrusted.yaml");
    private static final String UNTRUSTED_ALIAS = "UNTRUSTED_UPSTREAM";
    private static final String UNTRUSTED_ROUTE_PREFIX = "/proxy/untrusted";
    private static final String UNTRUSTED_ORIGIN_SERVICE = "passthrough-backend";

    @Test
    @DisplayName("the two overlays differ in the hostname-verification scalar and in leg identity only")
    void theTwoOverlaysDifferInTheHostnameScalarAndLegIdentityOnly() throws Exception {
        // Arrange — compare PARSED structures, not file text, so the two documents' differing header
        // comments are correctly irrelevant.
        Map<String, Object> on = flatten(loadYaml(VERIFY_ON_OVERLAY));
        Map<String, Object> off = flatten(loadYaml(VERIFY_OFF_OVERLAY));
        assertFalse(on.isEmpty(), VERIFY_ON_OVERLAY + " parsed to no keys at all — every comparison"
                + " below would pass vacuously");

        // Act — the key sets first: a key present on one side only is a structural divergence that no
        // value comparison would surface, because there is no pair of values to compare.
        assertEquals(on.keySet(), off.keySet(), "the two overlays must declare the SAME keys; a key"
                + " on one side only means the pair no longer differs in one scalar and the IT's"
                + " observed difference is not attributable to " + VERIFY_HOSTNAME_PATH);

        Set<String> differing = new TreeSet<>();
        for (String path : on.keySet()) {
            if (!Objects.equals(on.get(path), off.get(path))) {
                differing.add(path);
            }
        }

        // Assert — the scalar under test must be one of the differences, with the two values the
        // control needs. Asserting the VALUES and not merely "they differ" is what rules out a pair
        // that is inverted (which would make the ITs fail confusingly) or that differs by some third
        // value entirely.
        assertTrue(differing.contains(VERIFY_HOSTNAME_PATH), VERIFY_HOSTNAME_PATH + " must DIFFER"
                + " between the two overlays — it is the only variable the control has; the differing"
                + " paths were " + differing);
        differing.remove(VERIFY_HOSTNAME_PATH);
        assertEquals(Boolean.TRUE, on.get(VERIFY_HOSTNAME_PATH),
                "the verify-on overlay must state " + VERIFY_HOSTNAME_PATH + ": true explicitly,"
                        + " rather than inheriting the shipped default — a reader diffing the pair must"
                        + " see the variable, not an absence whose default they have to know");
        assertEquals(Boolean.FALSE, off.get(VERIFY_HOSTNAME_PATH),
                "the verify-off overlay must state " + VERIFY_HOSTNAME_PATH + ": false");

        // Every REMAINING difference must be the leg restating its own identity. See the class
        // Javadoc for why this is a substitution rule rather than an exception list.
        for (String path : differing) {
            assertEquals(legIdentitySubstituted(on.get(path)), off.get(path),
                    "the two overlays differ at '" + path + "' by more than this leg's own name and"
                            + " port: verify-on has " + on.get(path) + ", verify-off has " + off.get(path)
                            + ". A second behavioural difference makes the pair an uncontrolled"
                            + " comparison — the IT would attribute it to " + VERIFY_HOSTNAME_PATH);
        }

        // Non-vacuity in the other direction: with NO identity-only difference the substitution rule
        // above would hold trivially, and that state is itself a defect — the oidc URLs would then
        // name one instance's port on both legs, so the verify-off document would point at its
        // sibling's edge.
        assertFalse(differing.isEmpty(), "the two overlays carry no leg-identity difference at all,"
                + " so each no longer names its own published port in the oidc URLs — one document is"
                + " now pointing at the other instance's edge");
    }

    @Test
    @DisplayName("both overlays bind the it-upstream trust profile, so chain trust succeeds on both legs")
    void bothOverlaysBindTheItUpstreamTrustProfile() throws Exception {
        // Assert — this is the property that makes hostname matching the ONLY variable. Dropping the
        // profile from the verify-off leg is the most tempting tidy-up in the whole fixture (it looks
        // like configuration that leg does not need) and is exactly what would break the control: that
        // instance would then fail on chain trust, the pair would still read red/green, and the knob
        // under test would never be exercised.
        for (Path overlay : List.of(VERIFY_ON_OVERLAY, VERIFY_OFF_OVERLAY)) {
            assertEquals(EXPECTED_TLS_PROFILE, egressTls(overlay).get(TLS_PROFILE_KEY),
                    overlay + " must name " + EGRESS_TLS_BLOCK + "." + TLS_PROFILE_KEY + ": "
                            + EXPECTED_TLS_PROFILE + " — the profile holds the mismatched upstream's own"
                            + " certificate, which is what makes chain trust succeed on BOTH legs and"
                            + " leaves hostname matching as the only variable");
        }
    }

    @Test
    @DisplayName("each compose service mounts its own overlay over the shared config directory")
    void eachComposeServiceMountsItsOwnOverlay() throws Exception {
        // Arrange
        Map<String, Object> services = composeServices();

        // Assert — the overlay is mounted as a single-FILE bind over the shared directory, so both the
        // shared mount and the file mount must be present: without the shared mount the endpoints tree
        // is absent and the instance boots with no routes; without the file mount it silently runs the
        // BASE gateway.yaml, which declares no egress_tls at all and would make both legs behave
        // identically. The sweep runs over the DERIVED family, so a leg added later cannot bypass it,
        // and deriving the expected overlay directory from each service's OWN name is what catches a
        // swap — which would otherwise invert the control.
        for (String service : egressVerifyServices(services)) {
            List<String> mounts = volumes(services, service);
            assertTrue(mounts.contains(SHARED_CONFIG_MOUNT),
                    service + " must mount the shared config directory " + SHARED_CONFIG_MOUNT
                            + " (mounts: " + mounts + ")");
            assertTrue(mounts.contains(overlayMountFor(service)),
                    service + " must overlay its OWN gateway.yaml: " + overlayMountFor(service)
                            + " (mounts: " + mounts + ")");
        }

        // The two legs the ITs actually address stay named explicitly on top of the sweep, so a
        // failure reports the concrete missing mount for a known leg rather than only a set mismatch.
        List<String> onMounts = volumes(services, VERIFY_ON_SERVICE);
        List<String> offMounts = volumes(services, VERIFY_OFF_SERVICE);
        assertTrue(onMounts.contains(overlayMountFor(VERIFY_ON_SERVICE)),
                VERIFY_ON_SERVICE + " must overlay " + overlayMountFor(VERIFY_ON_SERVICE)
                        + " (mounts: " + onMounts + ")");
        assertTrue(offMounts.contains(overlayMountFor(VERIFY_OFF_SERVICE)),
                VERIFY_OFF_SERVICE + " must overlay " + overlayMountFor(VERIFY_OFF_SERVICE)
                        + " (mounts: " + offMounts + ")");
    }

    @Test
    @DisplayName("both compose services load the it-upstream trust binding")
    void bothComposeServicesLoadTheTrustBinding() throws Exception {
        // Arrange
        Map<String, Object> services = composeServices();

        // Assert — membership over the comma-separated value, not whole-value equality: these two are
        // the only instances carrying a SECOND config location, so an equality assertion would have to
        // restate the benchmark-idp entry too and would break the moment a third were added. The
        // failure this catches is not quiet — the overlay names upstream_tls_profile: it-upstream, and
        // an unbound profile aborts boot by the resolver's fail-closed contract — but it costs a
        // native build and a container that never reaches readiness to discover.
        for (String service : egressVerifyServices(services)) {
            List<String> locations = configLocations(services, service);
            assertTrue(locations.contains(MOUNTED_TRUST_BINDING),
                    service + " must load " + MOUNTED_TRUST_BINDING + " via " + LOCATIONS_VARIABLE
                            + " (it loads " + locations + ") — its overlay names " + TLS_PROFILE_KEY
                            + ": " + EXPECTED_TLS_PROFILE + ", and an unbound profile aborts boot");
        }

        // The two known legs named explicitly on top of the derived sweep.
        assertTrue(configLocations(services, VERIFY_ON_SERVICE).contains(MOUNTED_TRUST_BINDING),
                VERIFY_ON_SERVICE + " must load " + MOUNTED_TRUST_BINDING);
        assertTrue(configLocations(services, VERIFY_OFF_SERVICE).contains(MOUNTED_TRUST_BINDING),
                VERIFY_OFF_SERVICE + " must load " + MOUNTED_TRUST_BINDING);
    }

    @Test
    @DisplayName("the trust binding points the it-upstream profile at the upstream truststore")
    void theTrustBindingResolvesToTheUpstreamTruststore() throws Exception {
        // Arrange
        Properties binding = trustBinding();

        // Assert — the logical profile the overlays name is only as good as what it resolves to. A
        // binding that pointed at the localhost truststore would leave the mismatched upstream's
        // certificate untrusted and both legs would fail on chain trust; a missing password fails the
        // store open. The store file itself must exist, or the resolver aborts boot on a path that
        // parses perfectly.
        assertEquals(EXPECTED_TRUST_STORE, binding.getProperty(TRUST_STORE_PATH_KEY),
                TRUST_BINDING + " must bind " + TRUST_STORE_PATH_KEY + " to " + EXPECTED_TRUST_STORE
                        + " — the store holding the mismatched upstream's own certificate");
        String password = binding.getProperty(TRUST_STORE_PASSWORD_KEY);
        assertNotNull(password, TRUST_BINDING + " must supply " + TRUST_STORE_PASSWORD_KEY);
        assertFalse(password.isBlank(), TRUST_STORE_PASSWORD_KEY + " must not be blank");
        assertTrue(Files.isRegularFile(DOCKER.resolve("certificates/upstream-truststore.p12")),
                "the bound truststore must be committed under certificates/, or the resolver aborts"
                        + " boot on a path that parses but resolves to nothing");
    }

    @Test
    @DisplayName("each compose service publishes the host port its Failsafe property names")
    void eachComposeServicePublishesItsPinnedHostPort() throws Exception {
        // Arrange
        Map<String, Object> services = composeServices();

        // Assert — see EGRESS_VERIFY_ON_PORT_PREFIX for why this lockstep needs a guard: compose, the
        // pom property and the IT fallback all spell these ports and none can check the others. A
        // drift most likely lands the suite on the primary gateway, which dials plain-HTTP go-httpbin
        // and performs no hostname verification, so every leg would pass while testing nothing.
        assertTrue(publishes(services, VERIFY_ON_SERVICE, EGRESS_VERIFY_ON_PORT_PREFIX),
                VERIFY_ON_SERVICE + " must publish host port " + EGRESS_VERIFY_ON_PORT_PREFIX
                        + " (test.egress.verify.on.port); published: " + ports(services, VERIFY_ON_SERVICE));
        assertTrue(publishes(services, VERIFY_OFF_SERVICE, EGRESS_VERIFY_OFF_PORT_PREFIX),
                VERIFY_OFF_SERVICE + " must publish host port " + EGRESS_VERIFY_OFF_PORT_PREFIX
                        + " (test.egress.verify.off.port); published: " + ports(services, VERIFY_OFF_SERVICE));
    }

    @Test
    @DisplayName("the chain-trust leg dials a DIFFERENT origin from the hostname-mismatch legs")
    void theChainTrustLegDialsAnIndependentUntrustedOrigin() throws Exception {
        // Arrange
        Map<String, Object> services = composeServices();
        String onUntrusted = environmentEntries(services, VERIFY_ON_SERVICE).get(UNTRUSTED_VARIABLE);
        String offUntrusted = environmentEntries(services, VERIFY_OFF_SERVICE).get(UNTRUSTED_VARIABLE);
        String offUpstream = environmentEntries(services, VERIFY_OFF_SERVICE).get(UPSTREAM_VARIABLE);

        // Assert — the third leg exists to separate "the knob relaxed hostname matching" from "the
        // knob disabled verification wholesale". Pointed at the mismatched backend it would answer
        // neither question: it would simply repeat the first leg while still going red on the
        // verify-off instance, so the suite would stay green with the distinction lost.
        assertNotNull(onUntrusted, VERIFY_ON_SERVICE + " must pin " + UNTRUSTED_VARIABLE);
        assertNotNull(offUpstream, VERIFY_OFF_SERVICE + " must pin " + UPSTREAM_VARIABLE
                + " — without it the shared /proxy route falls back to the plain-HTTP go-httpbin"
                + " default, where no TLS dial happens and the first two legs assert nothing");
        assertEquals(onUntrusted, offUntrusted, "both legs must pin the same " + UNTRUSTED_VARIABLE
                + " — the chain-trust origin is a constant of the control, exactly as the mismatched"
                + " upstream is");
        assertNotEquals(offUpstream, offUntrusted, UNTRUSTED_VARIABLE + " must name a DIFFERENT origin"
                + " from " + UPSTREAM_VARIABLE + "; pointed at the mismatched backend the chain-trust"
                + " leg becomes a duplicate of the hostname leg and stops proving that the relaxation"
                + " left chain trust intact");

        // The origin must be one whose certificate cannot be an anchor of the bound truststore. That
        // is structural for passthrough-backend — it generates its own self-signed certificate at
        // container start — which is why it is reused rather than a tenth service being added.
        assertTrue(offUntrusted.contains(UNTRUSTED_ORIGIN_SERVICE), UNTRUSTED_VARIABLE + " must name "
                + UNTRUSTED_ORIGIN_SERVICE + ", whose certificate is generated at container start and"
                + " therefore chains to nothing any committed truststore holds, was: " + offUntrusted);
        for (String service : egressVerifyServices(services)) {
            assertTrue(dependsOn(services, service).contains(UNTRUSTED_ORIGIN_SERVICE),
                    service + " must gate on " + UNTRUSTED_ORIGIN_SERVICE + " — a refusal from a"
                            + " listener that is not up yet reads exactly like the chain-trust refusal"
                            + " the third leg asserts, and the control would pass without dialling");
        }

        // The two known legs named explicitly on top of the derived sweep.
        assertTrue(dependsOn(services, VERIFY_ON_SERVICE).contains(UNTRUSTED_ORIGIN_SERVICE),
                VERIFY_ON_SERVICE + " must gate on " + UNTRUSTED_ORIGIN_SERVICE);
        assertTrue(dependsOn(services, VERIFY_OFF_SERVICE).contains(UNTRUSTED_ORIGIN_SERVICE),
                VERIFY_OFF_SERVICE + " must gate on " + UNTRUSTED_ORIGIN_SERVICE);
    }

    @Test
    @DisplayName("the shared untrusted endpoint resolves the alias the compose pin binds")
    void theSharedUntrustedEndpointResolvesTheAlias() throws Exception {
        // Arrange
        Map<String, Object> document = loadYaml(UNTRUSTED_ENDPOINT);
        Object endpoint = document.get("endpoint");
        assertInstanceOf(Map.class, endpoint, UNTRUSTED_ENDPOINT + " must declare an endpoint block");
        Map<?, ?> block = (Map<?, ?>) endpoint;

        // Assert — the descriptor and the compose pin are two halves of one wiring. The alias must be
        // the one topology.properties binds, or the endpoint fails to resolve at boot; and the route
        // prefix must be the one the IT requests, or the request falls through to the /proxy route
        // and silently retests the hostname leg against the mismatched backend.
        assertEquals(UNTRUSTED_ALIAS, block.get("base_url"),
                UNTRUSTED_ENDPOINT + " must resolve its upstream through the " + UNTRUSTED_ALIAS
                        + " alias, which is what " + UNTRUSTED_VARIABLE + " binds");
        assertNotNull(topology().getProperty(UNTRUSTED_ALIAS),
                UNTRUSTED_ALIAS + " must be declared in sheriff-config/topology.properties; an"
                        + " unresolvable alias fails the boot of all nine instances, not just the pair");
        List<String> prefixes = routePrefixes(block);
        assertTrue(prefixes.contains(UNTRUSTED_ROUTE_PREFIX),
                UNTRUSTED_ENDPOINT + " must declare a route matching " + UNTRUSTED_ROUTE_PREFIX
                        + " — the path UpstreamHostnameVerificationIT requests. Without it the request"
                        + " falls through to the shared /proxy route and retests the hostname leg;"
                        + " declared prefixes were " + prefixes);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Every compose service whose name places it in the egress-verify family, derived from the parsed
     * document by {@link #EGRESS_VERIFY_SERVICE_PREFIX} rather than enumerated. A leg added later is
     * therefore swept by the overlay, trust-binding and origin-gating assertions with no edit here.
     * <p>
     * The derived set is asserted NON-EMPTY, and asserted to still contain both known legs. A pattern
     * that stopped matching — a service rename, a family renamed wholesale — would otherwise leave
     * every loop over this set iterating nothing and passing vacuously, which is the failure mode a
     * derived population introduces and an enumerated one cannot have.
     *
     * @param services the parsed services block
     * @return the matching service names, in name order
     */
    private static Set<String> egressVerifyServices(Map<String, Object> services) {
        Set<String> matched = new TreeSet<>();
        for (String name : services.keySet()) {
            if (name.startsWith(EGRESS_VERIFY_SERVICE_PREFIX)) {
                matched.add(name);
            }
        }
        assertFalse(matched.isEmpty(), "no compose service name starts with '"
                + EGRESS_VERIFY_SERVICE_PREFIX + "' — every derived sweep would then iterate an empty"
                + " set and pass vacuously; declared services were " + services.keySet());
        assertTrue(matched.containsAll(List.of(VERIFY_ON_SERVICE, VERIFY_OFF_SERVICE)),
                "the derived egress-verify family must still contain both legs the ITs address ("
                        + VERIFY_ON_SERVICE + ", " + VERIFY_OFF_SERVICE + "), was: " + matched);
        return matched;
    }

    /**
     * The single-file overlay mount one egress-verify service must carry, with the overlay directory
     * derived from that service's OWN name. Deriving rather than passing the leg is what makes a
     * swapped pair fail: each service is checked against the overlay its name claims.
     *
     * @param service a compose service name carrying {@link #EGRESS_VERIFY_SERVICE_PREFIX}
     * @return the expected compose volume entry
     */
    private static String overlayMountFor(String service) {
        return "./src/main/docker/sheriff-config-egress-verify-"
                + service.substring(EGRESS_VERIFY_SERVICE_PREFIX.length())
                + "/gateway.yaml:/app/sheriff-config/gateway.yaml:ro";
    }

    /**
     * Applies the leg-identity substitution to a verify-ON value: the published port and the leg name
     * are replaced by the verify-OFF spellings. Non-string values are returned untouched, so a
     * boolean or numeric divergence can never be explained away by this rule.
     *
     * @param onValue the value declared by the verify-on overlay
     * @return the value the verify-off overlay must declare if the difference is identity only
     */
    private static Object legIdentitySubstituted(Object onValue) {
        if (onValue instanceof String text) {
            return text.replace("10450", "10451").replace("verify-on", "verify-off");
        }
        return onValue;
    }

    /**
     * The {@code egress_tls} block of one overlay.
     *
     * @param overlay the overlay document
     * @return the parsed block
     * @throws IOException when the document cannot be read
     */
    private static Map<?, ?> egressTls(Path overlay) throws IOException {
        Object block = loadYaml(overlay).get(EGRESS_TLS_BLOCK);
        assertInstanceOf(Map.class, block, overlay + " must declare an " + EGRESS_TLS_BLOCK + " block");
        return (Map<?, ?>) block;
    }

    /**
     * Flattens a parsed YAML document to dotted paths, indexing sequence members so a divergence
     * inside a list is reported at its position rather than as one opaque whole-list difference.
     *
     * @param document the parsed document
     * @return path to scalar value, ordered for a legible assertion diff
     */
    private static Map<String, Object> flatten(Map<String, Object> document) {
        Map<String, Object> flat = new TreeMap<>();
        flatten(document, "", flat);
        return flat;
    }

    private static void flatten(Object node, String prefix, Map<String, Object> flat) {
        switch (node) {
            case Map<?, ?> map -> map.forEach((key, value) ->
                    flatten(value, prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key, flat));
            case List<?> sequence -> {
                for (int index = 0; index < sequence.size(); index++) {
                    flatten(sequence.get(index), prefix + "[" + index + "]", flat);
                }
            }
            case null, default -> flat.put(prefix, node);
        }
    }

    /**
     * The {@code match.path_prefix} of every route an endpoint block declares.
     *
     * @param endpoint the parsed endpoint block
     * @return the declared prefixes, in declaration order
     */
    private static List<String> routePrefixes(Map<?, ?> endpoint) {
        Object routes = endpoint.get("routes");
        assertInstanceOf(List.class, routes, "the endpoint must declare routes");
        List<String> prefixes = new ArrayList<>();
        for (Object route : (List<?>) routes) {
            assertInstanceOf(Map.class, route, "each route must be a mapping");
            Object match = ((Map<?, ?>) route).get("match");
            assertInstanceOf(Map.class, match, "each route must declare a match block");
            prefixes.add(String.valueOf(((Map<?, ?>) match).get("path_prefix")));
        }
        return prefixes;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> composeServices() throws IOException {
        Map<String, Object> doc = loadYaml(MODULE.resolve("docker-compose.yml"));
        Object services = doc.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        return (Map<String, Object>) services;
    }

    private static Map<?, ?> service(Map<String, Object> services, String service) {
        Object node = services.get(service);
        assertNotNull(node, "compose must declare the '" + service + "' service");
        assertInstanceOf(Map.class, node, "the '" + service + "' service must be a mapping");
        return (Map<?, ?>) node;
    }

    /**
     * A compose service's environment as key/value pairs, accepting BOTH compose forms — the
     * {@code - KEY=VALUE} list this stack uses today and the {@code KEY: VALUE} mapping compose also
     * accepts. Reading only the list form would let a form switch turn an assertion vacuously green.
     *
     * @param services the parsed services block
     * @param service  the service name
     * @return the declared environment, empty when the service declares none
     */
    private static Map<String, String> environmentEntries(Map<String, Object> services, String service) {
        Object env = service(services, service).get("environment");
        Map<String, String> entries = new LinkedHashMap<>();
        switch (env) {
            case Map<?, ?> mapForm -> mapForm.forEach((key, value) -> entries.put(String.valueOf(key), String.valueOf(value)));
            case Iterable<?> listForm -> {
                for (Object entry : listForm) {
                    String text = String.valueOf(entry);
                    int split = text.indexOf('=');
                    if (split < 0) {
                        entries.put(text, "");
                    } else {
                        entries.put(text.substring(0, split), text.substring(split + 1));
                    }
                }
            }
            case null, default -> assertNull(env,
                    "the '" + service + "' service environment must be a list or a mapping, was: " + env);
        }
        return entries;
    }

    /**
     * One service's {@code QUARKUS_CONFIG_LOCATIONS} split into its comma-separated members.
     *
     * @param services the parsed services block
     * @param service  the service name
     * @return the declared locations, empty when the variable is absent
     */
    private static List<String> configLocations(Map<String, Object> services, String service) {
        String declared = environmentEntries(services, service).get(LOCATIONS_VARIABLE);
        if (declared == null || declared.isBlank()) {
            return List.of();
        }
        List<String> locations = new ArrayList<>();
        for (String entry : declared.split(",")) {
            locations.add(entry.trim());
        }
        return locations;
    }

    private static List<String> ports(Map<String, Object> services, String service) {
        Object declared = service(services, service).get("ports");
        assertInstanceOf(List.class, declared, "the '" + service + "' service must publish ports");
        List<String> published = new ArrayList<>();
        ((List<?>) declared).forEach(entry -> published.add(String.valueOf(entry)));
        return published;
    }

    private static boolean publishes(Map<String, Object> services, String service, String hostPortPrefix) {
        return ports(services, service).stream().anyMatch(entry -> entry.startsWith(hostPortPrefix));
    }

    private static List<String> volumes(Map<String, Object> services, String service) {
        Object declared = service(services, service).get("volumes");
        assertInstanceOf(List.class, declared, "the '" + service + "' service must declare volumes");
        List<String> mounts = new ArrayList<>();
        ((List<?>) declared).forEach(entry -> mounts.add(String.valueOf(entry)));
        return mounts;
    }

    /**
     * A compose service's {@code depends_on} names, accepting BOTH the short list form and the long
     * {@code service: {condition: ...}} mapping form.
     *
     * @param services the parsed services block
     * @param service  the service name
     * @return the declared dependency names, empty when the service declares none
     */
    private static Set<String> dependsOn(Map<String, Object> services, String service) {
        Object declared = service(services, service).get("depends_on");
        Set<String> names = new LinkedHashSet<>();
        switch (declared) {
            case Map<?, ?> mapForm -> mapForm.keySet().forEach(key -> names.add(String.valueOf(key)));
            case Iterable<?> listForm -> listForm.forEach(entry -> names.add(String.valueOf(entry)));
            case null, default -> assertNull(declared,
                    "the '" + service + "' service depends_on must be a list or a mapping, was: " + declared);
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }

    private static Properties trustBinding() throws IOException {
        return load(TRUST_BINDING);
    }

    private static Properties topology() throws IOException {
        return load(DOCKER.resolve("sheriff-config/topology.properties"));
    }

    private static Properties load(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        return properties;
    }
}
