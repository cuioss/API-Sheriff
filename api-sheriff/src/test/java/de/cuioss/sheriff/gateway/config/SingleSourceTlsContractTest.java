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
package de.cuioss.sheriff.gateway.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Machine-asserts the completion bar of the neutral TLS surface: <strong>no raw server-TLS
 * {@code quarkus.*} POLICY key remains in the shipped {@code application.properties}' default
 * profile</strong>, and the one build-time knob that left the runtime surface really landed in the
 * build rather than simply being deleted.
 * <p>
 * <strong>Why this test exists at all.</strong> The keys this plan removed were not merely
 * redundant — they were <em>inert</em>. Quarkus consults its TLS-registry configuration only when the
 * server certificate also comes from that registry, and this gateway supplies its certificate through
 * {@code quarkus.http.ssl.certificate.*}, so a declared protocol floor or cipher allowlist never
 * reached the listener. A configuration surface that reads as enforcement while enforcing nothing is
 * the specific defect the neutral surface replaces, and nothing stops it growing back by accident: a
 * future contributor reaching for a familiar {@code quarkus.tls.*} name would reintroduce it silently
 * and green. This test is the guard that makes that reintroduction a failing build.
 * <p>
 * <strong>The allowlist is deliberately explicit and deliberately small.</strong> Every entry is a
 * ruling-sanctioned exception with its reason recorded inline and in
 * {@code doc/configuration.adoc}'s per-key verdict table. Widening it is therefore a visible diff
 * that a reviewer must weigh against the documented classification, rather than a silent relaxation
 * of the bar.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Single-source TLS contract")
class SingleSourceTlsContractTest {

    /** The module base directory — surefire runs with the module root as the working directory. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path APPLICATION_PROPERTIES = MODULE.resolve("src/main/resources/application.properties");
    private static final Path POM = MODULE.resolve("pom.xml");

    private static final String NATIVE_SSL_KEY = "quarkus.ssl.native";

    /**
     * The configuration namespaces that carry server-TLS settings. A default-profile key under one of
     * these is presumed to be TLS POLICY unless it is material (below) or explicitly excepted.
     * <p>
     * {@code quarkus.http.ssl} is listed <em>without</em> a trailing dot on purpose, so the bar also
     * reaches the sibling {@code quarkus.http.ssl-port}. Requiring the dot would silently exclude it,
     * and its allowlist entry below would then be decorative rather than load-bearing — the very
     * shape of defect this test exists to catch.
     */
    private static final List<String> SERVER_TLS_NAMESPACES = List.of(
            "quarkus.tls.",
            "quarkus.ssl.",
            "quarkus.http.ssl",
            "quarkus.management.ssl");

    /**
     * Server-TLS-relevant keys that no namespace prefix reaches. {@code insecure-requests} governs how
     * the TLS listener treats plain-HTTP traffic, so it is in scope of the bar even though its name
     * sits outside every {@code ssl}/{@code tls} namespace.
     */
    private static final Set<String> ADDITIONAL_IN_SCOPE = Set.of("quarkus.http.insecure-requests");

    /**
     * Substrings identifying certificate / key / trust MATERIAL rather than policy. Material is
     * deployment-bound by the same ruling that makes policy neutral, so it legitimately stays outside
     * {@code gateway.yaml} and is not what this bar is about.
     */
    private static final List<String> MATERIAL_MARKERS = List.of(
            "certificate", "key-store", "trust-store", "keystore", "truststore", "credentials-provider");

    /**
     * The ruling-sanctioned exceptions, each named individually so widening this set is a visible
     * diff. Every entry is also recorded in {@code doc/configuration.adoc}'s per-key verdict table and
     * in ADR-0025 — an undocumented exception would be a silent trim of the completion bar.
     */
    private static final Set<String> SANCTIONED_EXCEPTIONS = Set.of(
            // Deployment-bound port. Promoting it would break the ADR-0017 SNI split, where a
            // deployment moves the terminated listener to the internal loopback port through
            // QUARKUS_HTTP_SSL_PORT — a topology a gateway.yaml-owned port could not express.
            "quarkus.http.ssl-port",
            // Deployment-bound exposure knob of the same class: a port/exposure decision the
            // deployment owns, not TLS policy.
            "quarkus.http.insecure-requests",
            // NOT policy: the key-less TLS bucket the plain-HTTP management opt-out selects. Its
            // emptiness IS the mechanism — reload-period declares the map key without declaring any
            // material. Adding key material to this bucket would defeat the opt-out; adding a
            // DEFAULT quarkus.tls.key-store.* bucket would make management inherit HTTPS invisibly.
            "quarkus.tls.plain-management.reload-period");

    @Test
    @DisplayName("No raw server-TLS policy key remains in the default profile")
    void noRawServerTlsPolicyKeyRemainsInTheDefaultProfile() throws Exception {
        List<String> offenders = defaultProfileKeys().stream()
                .filter(SingleSourceTlsContractTest::isInScopeOfTheBar)
                .filter(key -> !isMaterial(key))
                .filter(key -> !SANCTIONED_EXCEPTIONS.contains(key))
                .toList();

        assertTrue(offenders.isEmpty(),
                () -> "raw server-TLS policy key(s) found in application.properties' default profile: "
                        + offenders + ". TLS policy is single-sourced from gateway.yaml's `tls` and "
                        + "`management` blocks and bound by TlsServerCustomizer / NeutralTlsConfigSource "
                        + "(ADR-0025). A raw key here either competes with that binding or — as the keys "
                        + "this bar replaced did — reaches no listener at all while reading as "
                        + "enforcement. If the key is genuinely deployment-bound or build-time, add it "
                        + "to SANCTIONED_EXCEPTIONS *and* to doc/configuration.adoc's per-key verdict "
                        + "table; an undocumented exception is a silent trim.");
    }

    @Test
    @DisplayName("Every sanctioned exception is real: present in the file and reached by the detector")
    void everySanctionedExceptionIsLoadBearing() throws Exception {
        List<String> declaredKeys = allKeys();

        for (String exception : SANCTIONED_EXCEPTIONS) {
            assertTrue(declaredKeys.contains(exception),
                    () -> "SANCTIONED_EXCEPTIONS names '" + exception + "', which application.properties "
                            + "no longer declares. A stale exception is an inert allowlist entry that "
                            + "reads as a live carve-out — remove it, and remove its row from "
                            + "doc/configuration.adoc's per-key verdict table.");
            assertTrue(isInScopeOfTheBar(exception) && !isMaterial(exception),
                    () -> "SANCTIONED_EXCEPTIONS names '" + exception + "', but the detector would not "
                            + "have flagged it anyway — the entry is decorative, so the bar is weaker "
                            + "than it reads. Either widen the detector or drop the entry.");
        }
    }

    @Test
    @DisplayName("Profiled keys are outside the bar, but the exceptions are not smuggled through one")
    void theBarAppliesToTheDefaultProfile() throws Exception {
        // The shipped file now declares NO profiled key at all — ShippedApplicationPropertiesTest
        // asserts that separately, and this assertion is consequently vacuous today. It is kept as a
        // standing guard rather than deleted: it pins the RULE that a profile prefix does not change
        // a key's class, so a policy key cannot be sidestepped back in behind one.
        List<String> profiledPolicyKeys = profiledKeys().stream()
                .filter(SingleSourceTlsContractTest::isInScopeOfTheBar)
                .filter(key -> !isMaterial(key))
                .filter(key -> !SANCTIONED_EXCEPTIONS.contains(key))
                .toList();

        assertTrue(profiledPolicyKeys.isEmpty(),
                () -> "profile-scoped server-TLS policy key(s) found: " + profiledPolicyKeys
                        + ". A profile prefix does not change a key's class — policy belongs in "
                        + "gateway.yaml whichever profile declares it.");
    }

    @Test
    @DisplayName("The native SSL knob is absent from application.properties")
    void nativeSslKnobIsAbsentFromTheRuntimeSurface() throws Exception {
        assertFalse(allKeys().contains(NATIVE_SSL_KEY),
                NATIVE_SSL_KEY + " is a BUILD-TIME GraalVM knob fixed when the image is built. In "
                        + "application.properties it would only look like a runtime key, since no "
                        + "runtime document could make it take effect (ADR-0025)");
    }

    @Test
    @DisplayName("The native SSL knob is present in the pom's native profile")
    void nativeSslKnobIsPresentInTheNativeProfile() throws Exception {
        String pom = Files.readString(POM);

        int nativeProfileId = pom.indexOf("<id>native</id>");
        assertTrue(nativeProfileId >= 0, "the pom must declare a <profile> with <id>native</id>");
        int profileEnd = pom.indexOf("</profile>", nativeProfileId);
        assertTrue(profileEnd > nativeProfileId, "the native profile must be closed");
        int knob = pom.indexOf("<" + NATIVE_SSL_KEY + ">", nativeProfileId);

        // This is the half that stops the relocation degenerating into a silent deletion. Asserting
        // only the absence above would pass just as happily if the knob had been dropped outright,
        // and a native image without SSL support fails at runtime rather than at build.
        assertTrue(knob > nativeProfileId && knob < profileEnd,
                () -> NATIVE_SSL_KEY + " must be declared INSIDE the native profile — it was relocated "
                        + "there, not deleted, and the profile is active exactly when the knob has "
                        + "meaning. Found at index " + knob + ", native profile spans ["
                        + nativeProfileId + ", " + profileEnd + ")");
        assertEquals("true", valueOfPomProperty(pom, knob),
                NATIVE_SSL_KEY + " must be enabled — a relocated-but-disabled knob is the same "
                        + "regression as a deleted one");
    }

    private static String valueOfPomProperty(String pom, int elementStart) {
        int valueStart = pom.indexOf('>', elementStart) + 1;
        int valueEnd = pom.indexOf('<', valueStart);
        return pom.substring(valueStart, valueEnd).strip();
    }

    /** Keys declared without a {@code %profile} prefix — the shipped default posture. */
    private static List<String> defaultProfileKeys() throws IOException {
        return propertyLines().stream().filter(line -> !line.startsWith("%")).map(SingleSourceTlsContractTest::keyOf)
                .toList();
    }

    /** Keys declared under a {@code %profile} prefix, with the prefix stripped. */
    private static List<String> profiledKeys() throws IOException {
        return propertyLines().stream().filter(line -> line.startsWith("%"))
                .map(line -> keyOf(line.substring(line.indexOf('.') + 1))).toList();
    }

    private static List<String> allKeys() throws IOException {
        return propertyLines().stream()
                .map(line -> line.startsWith("%") ? line.substring(line.indexOf('.') + 1) : line)
                .map(SingleSourceTlsContractTest::keyOf).toList();
    }

    private static String keyOf(String line) {
        int separator = line.indexOf('=');
        return separator < 0 ? line.strip() : line.substring(0, separator).strip();
    }

    private static List<String> propertyLines() throws IOException {
        return Files.readAllLines(APPLICATION_PROPERTIES).stream().map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
    }

    private static boolean isInScopeOfTheBar(String key) {
        return SERVER_TLS_NAMESPACES.stream().anyMatch(key::startsWith) || ADDITIONAL_IN_SCOPE.contains(key);
    }

    private static boolean isMaterial(String key) {
        return MATERIAL_MARKERS.stream().anyMatch(key::contains);
    }
}
