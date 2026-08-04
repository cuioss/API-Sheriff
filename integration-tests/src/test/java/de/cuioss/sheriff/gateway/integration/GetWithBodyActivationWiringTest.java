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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed base descriptor actually
 * <strong>activates</strong> the {@code GET}-body opt-in, and that no descriptor declares it with a
 * non-boolean value the schema would refuse.
 * <p>
 * This closes a unit-green / integration-red blind spot. {@code FramingGateTest} proves
 * {@code config -> behaviour} — with the opt-in on, a {@code Content-Length}-framed {@code GET} body
 * is admitted while {@code Transfer-Encoding} on {@code GET} and any {@code HEAD} body stay
 * rejected. What it cannot prove is {@code committed-descriptor -> activated}: that the shipped
 * {@code gateway.yaml} declares the key at all. Without this assertion the opt-in could ship fully
 * unit-covered and entirely inert, with the containerised suite still meeting a {@code 400} on the
 * very request the feature exists to admit.
 * <p>
 * The descriptors are discovered by glob rather than hard-coded, so a new {@code sheriff-config-*}
 * directory comes under the type assertion automatically — and a glob matching fewer than the
 * descriptors present today fails rather than passing vacuously. Only the base descriptor is
 * required to declare the opt-in: the sibling instances (mtls, cookie, ws-admission) deliberately
 * keep the strict default, which is itself worth pinning — an accidental gateway-wide relaxation
 * across every instance would go unnoticed otherwise. That enumeration remains exactly accurate:
 * those three are still the whole set of independently-authored siblings, and every one of them is
 * still held to the strict default by {@link #siblingDescriptorsKeepTheStrictDefault()}.
 * <p>
 * The one exception is a <em>base copy</em>: {@code sheriff-config-passthrough-empty} is the base
 * document with the {@code tls.passthrough_sni} block — and only that block — removed, because the
 * absence of passthrough is the single variable the passthrough-baseline benchmark isolates. Its
 * {@code security_defaults} must therefore stay identical to the base's, opt-in included. Such
 * descriptors are exempted from the strict-default assertion by LITERAL PATH in
 * {@link #BASE_DERIVED_DESCRIPTORS} — never by prefix, glob or substring — so the carve-out stays
 * fail-closed: a sibling nobody enumerated still fails the guard, which
 * {@link #theCarveOutStaysFailClosedForANonEnumeratedSibling()} pins as a negative control.
 * <p>
 * Membership of that set is <strong>earned, not asserted</strong>. A descriptor only belongs there
 * while it really is a base copy, and that claim is itself under test elsewhere:
 * {@code TlsEdgeActivationWiringTest}'s single-variable equality guard asserts the
 * passthrough-empty overlay equals the base document once {@code tls.passthrough_sni} is removed
 * from it. Should that overlay ever drift into an independently-authored sibling, the equality guard
 * goes red — so the exemption here cannot outlive the property that justifies it.
 * <p>
 * It parses the committed descriptors only (YAML text) and starts no container and reaches no
 * network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class GetWithBodyActivationWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path DOCKER = MODULE.resolve("src/main/docker");

    /** The base descriptor — the one instance the suite expects the opt-in activated on. */
    private static final Path BASE_DESCRIPTOR = DOCKER.resolve("sheriff-config/gateway.yaml");

    /**
     * The base descriptor plus every descriptor that is a DELIBERATE COPY of it and therefore
     * inherits its {@code security_defaults} verbatim. Membership is an EXPLICIT ENUMERATION of
     * literal paths: no prefix match, no directory wildcard, no {@code contains} predicate. A
     * descriptor that is not named here stays subject to the strict-default assertion, so a NEW
     * sibling added later fails the guard unless someone deliberately adds it to this set — the
     * carve-out is fail-closed by construction and cannot widen by accident.
     */
    private static final Set<Path> BASE_DERIVED_DESCRIPTORS = Set.of(
            BASE_DESCRIPTOR,
            // A deliberate base copy for the benchmark's empty-passthrough arm: the base document
            // with the tls.passthrough_sni block — and ONLY that block — removed. The absence of
            // passthrough is the SINGLE variable separating the two benchmark arms, which is the
            // whole point of the arm; a second difference (softening security_defaults here to
            // satisfy this guard) would silently make the no-regression comparison uncontrolled.
            // So this descriptor legitimately carries the base's opt-in and is exempted by name.
            DOCKER.resolve("sheriff-config-passthrough-empty/gateway.yaml"));

    private static final String SECURITY_DEFAULTS_KEY = "security_defaults";
    private static final String OPT_IN_KEY = "allow_get_with_content_length_body";

    /**
     * The descriptor count committed today. The glob must match at least this many, so an empty or
     * mis-rooted glob fails loudly instead of satisfying the per-descriptor loop vacuously.
     */
    private static final int COMMITTED_DESCRIPTOR_COUNT = 5;

    @Test
    @DisplayName("the base descriptor activates the GET-body opt-in")
    void baseDescriptorActivatesTheOptIn() throws Exception {
        // Arrange
        Object declared = declaredOptIn(loadYaml(BASE_DESCRIPTOR));

        // Assert — an absent key means the framing gate keeps the strict default, so the feature
        // would be inert in the shipped deployment while every unit test stayed green.
        assertNotNull(declared, BASE_DESCRIPTOR + " must declare " + SECURITY_DEFAULTS_KEY + "."
                + OPT_IN_KEY + ", otherwise the shipped gateway never exercises the GET-body path");
        assertEquals(Boolean.TRUE, declared,
                BASE_DESCRIPTOR + " must declare " + OPT_IN_KEY + ": true to activate the opt-in");
    }

    @Test
    @DisplayName("every committed descriptor declares the opt-in as a boolean, or not at all")
    void everyCommittedDescriptorDeclaresABooleanOrNothing() throws Exception {
        // Arrange
        List<Path> descriptors = committedGatewayDescriptors();

        // Act + Assert — the glob must actually find the committed descriptors before the loop below
        // can mean anything.
        assertTrue(descriptors.size() >= COMMITTED_DESCRIPTOR_COUNT,
                "expected at least " + COMMITTED_DESCRIPTOR_COUNT
                        + " committed sheriff-config*/gateway.yaml descriptors under " + DOCKER
                        + ", found " + descriptors.size() + ": " + descriptors);

        // The schema types this key as a boolean; a string "true" would fail binding at boot.
        for (Path descriptor : descriptors) {
            Object declared = declaredOptIn(loadYaml(descriptor));
            if (declared != null) {
                assertInstanceOf(Boolean.class, declared,
                        descriptor + " declares " + OPT_IN_KEY + " as " + declared.getClass().getSimpleName()
                                + "; the schema types it as a boolean and that instance would fail its boot");
            }
        }
    }

    @Test
    @DisplayName("the sibling descriptors keep the strict default rather than relaxing gateway-wide")
    void siblingDescriptorsKeepTheStrictDefault() throws Exception {
        // Arrange
        List<Path> descriptors = committedGatewayDescriptors();

        // Assert — pinning the siblings is what makes an accidental blanket relaxation visible.
        for (Path descriptor : descriptors) {
            Object declared = declaredOptIn(loadYaml(descriptor));
            assertFalse(relaxesTheFramingGate(descriptor, declared),
                    descriptor + " declares " + OPT_IN_KEY + "=" + declared
                            + "; only the enumerated base-derived descriptors " + BASE_DERIVED_DESCRIPTORS
                            + " activate the opt-in, so a relaxation here is an accidental gateway-wide"
                            + " widening of the framing gate");
        }
    }

    @Test
    @DisplayName("the base-copy carve-out is fail-closed: a non-enumerated sibling declaring the opt-in still fails")
    void theCarveOutStaysFailClosedForANonEnumeratedSibling() {
        // Arrange — a sibling of exactly the shape a future instance would take, which nobody has
        // enumerated in BASE_DERIVED_DESCRIPTORS.
        Path unenumerated = DOCKER.resolve("sheriff-config-not-enumerated/gateway.yaml");

        // Act + Assert (negative control) — declaring the opt-in from a descriptor the set does not
        // name IS a violation. Without this, the carve-out could silently widen into a blanket
        // exemption (a prefix or "contains" predicate) and the guard would stop guarding.
        assertTrue(relaxesTheFramingGate(unenumerated, Boolean.TRUE),
                unenumerated + " is not enumerated in " + BASE_DERIVED_DESCRIPTORS
                        + ", so declaring " + OPT_IN_KEY + "=true from it must still fail the guard");
        assertFalse(relaxesTheFramingGate(unenumerated, null),
                "a non-enumerated sibling that declares nothing keeps the strict default and must pass");
        assertFalse(relaxesTheFramingGate(unenumerated, Boolean.FALSE),
                "a non-enumerated sibling that declares " + OPT_IN_KEY + "=false must pass");

        // Assert (matched positive control) — each ENUMERATED base-derived descriptor may declare it.
        for (Path baseDerived : BASE_DERIVED_DESCRIPTORS) {
            assertFalse(relaxesTheFramingGate(baseDerived, Boolean.TRUE),
                    baseDerived + " is an enumerated base-derived descriptor and inherits the base's"
                            + " " + OPT_IN_KEY + " activation");
            // A carve-out entry naming a path that no longer exists is a silent hole: the guard would
            // keep exempting a descriptor nobody can see. Pin every entry to a committed file.
            assertTrue(Files.isRegularFile(baseDerived),
                    baseDerived + " is enumerated in BASE_DERIVED_DESCRIPTORS but is not a committed"
                            + " descriptor; remove the stale carve-out entry rather than leaving a hole");
        }
    }

    /**
     * The guard predicate shared by the committed-descriptor sweep and its negative control: whether
     * {@code descriptor} widens the framing gate. Only the descriptors ENUMERATED BY LITERAL PATH in
     * {@link #BASE_DERIVED_DESCRIPTORS} are exempt; every other descriptor must keep the strict
     * default (the key absent, or declared {@code false}).
     *
     * @param descriptor the descriptor path, compared by equality against the enumeration
     * @param declared the declared opt-in value, or {@code null} when absent
     * @return {@code true} when the descriptor relaxes the gate without being an enumerated base copy
     */
    private static boolean relaxesTheFramingGate(Path descriptor, Object declared) {
        if (BASE_DERIVED_DESCRIPTORS.contains(descriptor)) {
            return false;
        }
        return declared != null && !Boolean.FALSE.equals(declared);
    }

    /**
     * The declared {@code security_defaults.allow_get_with_content_length_body} value.
     *
     * @param document the parsed gateway descriptor
     * @return the declared value, or {@code null} when the block or key is absent
     */
    private static Object declaredOptIn(Map<String, Object> document) {
        Object securityDefaults = document.get(SECURITY_DEFAULTS_KEY);
        if (securityDefaults == null) {
            return null;
        }
        assertInstanceOf(Map.class, securityDefaults, SECURITY_DEFAULTS_KEY + " must be a mapping");
        return ((Map<?, ?>) securityDefaults).get(OPT_IN_KEY);
    }

    /**
     * Every committed gateway descriptor under a {@code sheriff-config} directory, discovered by glob
     * so a new instance directory is covered automatically.
     *
     * @return the descriptor paths, in directory-stream order
     * @throws IOException when the docker directory cannot be listed
     */
    private static List<Path> committedGatewayDescriptors() throws IOException {
        List<Path> descriptors = new ArrayList<>();
        try (DirectoryStream<Path> directories = Files.newDirectoryStream(DOCKER, "sheriff-config*")) {
            for (Path directory : directories) {
                Path descriptor = directory.resolve("gateway.yaml");
                if (Files.isRegularFile(descriptor)) {
                    descriptors.add(descriptor);
                }
            }
        }
        return descriptors;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadYaml(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return new Yaml().loadAs(in, Map.class);
        }
    }
}
