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
package de.cuioss.sheriff.gateway.config;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.util.LinkedHashSet;
import java.util.Set;


import de.cuioss.sheriff.gateway.tls.ManagementPlainHttpAudit;
import de.cuioss.sheriff.gateway.tls.TerminatedListenerTlsAudit;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The drift guard over the one thing three components must agree on: <em>which configuration keys
 * count as declared server key material</em>.
 * <p>
 * <strong>Why this cannot be settled by a shared method call.</strong> The consumers run at
 * different times. {@code ServerTlsDeclarationGate} reads keys by name off the resolved
 * configuration from the {@code HttpServerOptionsCustomizer} hook, before the listener is built; the
 * two audits receive theirs through {@code @ConfigProperty} injection at {@code StartupEvent}, and
 * that annotation needs a compile-time constant name per parameter. What is shared is the vocabulary
 * in
 * {@link DeclaredKeyMaterialKeys} — and a vocabulary shared by convention drifts the moment someone
 * adds a spelling at one site. This test converts that drift into a failing build.
 * <p>
 * <strong>The audits are read reflectively rather than restated.</strong> Listing the expected names
 * here in a literal would only move the drift: the copy could fall behind the constructor and the
 * test would keep passing. Reading each constructor's actual {@code @ConfigProperty} annotations is
 * what makes the assertion about the injection points that really exist.
 * <p>
 * <strong>The scan is bounded to the {@code ssl.certificate.} prefix</strong>, because that is the
 * set under contract. Each constructor legitimately injects other keys — a port, a
 * {@code tls-configuration-name} — and folding those in would make the assertion a restatement of
 * each constructor's whole signature rather than of the shared predicate.
 * <p>
 * <strong>The contract is the key-bearing set, and the full vocabulary is pinned beside it.</strong>
 * The audits read the three spellings that can put a certificate on a listener; the credentials
 * provider only supplies a keystore password, so reading it would silence a plain-HTTP warning on a
 * listener that serves cleartext. The complete four-spelling vocabulary stays declared, and this test
 * pins that it does, so the distinction is never made by deleting a name.
 */
@DisplayName("Declared key-material vocabulary — drift guard")
class DeclaredKeyMaterialKeysDriftTest {

    /** The marker that selects the certificate keys out of each constructor's injected names. */
    private static final String CERTIFICATE_MARKER = "ssl.certificate.";

    @Test
    @DisplayName("The main-listener audit injects exactly the shared http key-bearing set")
    void terminatedListenerAuditAgreesWithTheSharedSet() {
        Set<String> injected = injectedCertificateKeys(TerminatedListenerTlsAudit.class);

        assertEquals(Set.copyOf(DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS), injected,
                "TerminatedListenerTlsAudit must read every key-bearing spelling the gate reads. A key "
                        + "present here but absent from "
                        + "DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS means the audit "
                        + "counts something as a certificate that the gate does not — the password "
                        + "source would silence ApiSheriff-121 on a cleartext listener; a key present "
                        + "there but absent here means the audit reports a plain-HTTP downgrade "
                        + "(ApiSheriff-121) against a listener that is terminating TLS. Add the "
                        + "spelling to DeclaredKeyMaterialKeys and to BOTH audits.");
    }

    @Test
    @DisplayName("The management audit injects exactly the shared management key-bearing set")
    void managementAuditAgreesWithTheSharedSet() {
        Set<String> injected = injectedCertificateKeys(ManagementPlainHttpAudit.class);

        assertEquals(Set.copyOf(DeclaredKeyMaterialKeys.MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS), injected,
                "ManagementPlainHttpAudit must read every key-bearing management spelling and nothing "
                        + "else. Reading fewer emits ApiSheriff-115 against a management port that is "
                        + "on HTTPS through a keystore file; reading the credentials provider silences "
                        + "ApiSheriff-115 on a port that serves plain HTTP.");
    }

    @Test
    @DisplayName("The two prefixes carry the same leaf set — one listener cannot gain a spelling alone")
    void bothPrefixesCarryTheSameLeafSet() {
        Set<String> httpLeaves = leavesUnder(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS,
                DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_PREFIX);
        Set<String> managementLeaves = leavesUnder(DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_KEYS,
                DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_PREFIX);
        Set<String> httpKeyBearingLeaves = leavesUnder(DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS,
                DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_PREFIX);
        Set<String> managementKeyBearingLeaves = leavesUnder(
                DeclaredKeyMaterialKeys.MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS,
                DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_PREFIX);

        assertAll("both listeners fall through to the same legacy ssl.certificate.* block in "
                + "HttpServerOptionsUtils, so a spelling that supplies material on one "
                + "supplies it on the other. Adding one to a single prefix leaves the other "
                + "leg with the false positive that was just fixed on this one.",
                () -> assertEquals(httpLeaves, managementLeaves, "full vocabulary"),
                () -> assertEquals(httpKeyBearingLeaves, managementKeyBearingLeaves, "key-bearing view"));
    }

    @Test
    @DisplayName("The key-bearing view is the full vocabulary minus exactly the password source")
    void keyBearingViewNarrowsOnlyThePasswordSource() {
        Set<String> httpPasswordOnly = new LinkedHashSet<>(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS);
        httpPasswordOnly.removeAll(DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS);
        Set<String> managementPasswordOnly =
                new LinkedHashSet<>(DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_KEYS);
        managementPasswordOnly.removeAll(DeclaredKeyMaterialKeys.MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS);

        assertAll("the vocabulary is never narrowed: the key-bearing view drops the credentials "
                + "provider and nothing else, and every key-bearing name stays in the full list",
                () -> assertEquals(4, DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS.size(),
                        "the full http vocabulary lost a spelling: "
                                + DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS),
                () -> assertEquals(4, DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_KEYS.size(),
                        "the full management vocabulary lost a spelling: "
                                + DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_KEYS),
                () -> assertTrue(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS
                                .containsAll(DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS),
                        "a key-bearing http spelling is missing from the full vocabulary"),
                () -> assertTrue(DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_KEYS
                                .containsAll(DeclaredKeyMaterialKeys.MANAGEMENT_KEY_BEARING_CERTIFICATE_KEYS),
                        "a key-bearing management spelling is missing from the full vocabulary"),
                () -> assertEquals(Set.of(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_CREDENTIALS_PROVIDER),
                        httpPasswordOnly, "the http password source"),
                () -> assertEquals(Set.of(DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_CREDENTIALS_PROVIDER),
                        managementPasswordOnly, "the management password source"));
    }

    @Test
    @DisplayName("The guard is non-vacuous: the reflective scan really found three keys per audit")
    void guardIsNonVacuous() {
        Set<String> mainListener = injectedCertificateKeys(TerminatedListenerTlsAudit.class);
        Set<String> management = injectedCertificateKeys(ManagementPlainHttpAudit.class);

        assertAll("a scan that matched nothing would make every assertion above pass on two empty sets",
                () -> assertFalse(DeclaredKeyMaterialKeys.HTTP_KEY_BEARING_CERTIFICATE_KEYS.isEmpty(),
                        "the shared http key-bearing list is empty, so it constrains nothing"),
                () -> assertEquals(3, mainListener.size(),
                        "the three key-bearing spellings TlsUtils.computeKeyStoreOptions builds key "
                                + "options from: " + mainListener),
                () -> assertEquals(3, management.size(),
                        "the same three on the management leg: " + management),
                () -> assertTrue(mainListener.contains(
                                DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEY_STORE_FILE),
                        "key-store-file specifically: it is the spelling whose omission produced the "
                                + "reported false positive, so its presence is the regression pin"));
    }

    /**
     * Reads a class's {@code @Inject} constructor and collects the {@code @ConfigProperty} names
     * that name a certificate key.
     *
     * @param type the audit to read
     * @return the injected certificate key names, in declaration order
     */
    private static Set<String> injectedCertificateKeys(Class<?> type) {
        Set<String> names = new LinkedHashSet<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!constructor.isAnnotationPresent(Inject.class)) {
                continue;
            }
            for (Parameter parameter : constructor.getParameters()) {
                for (Annotation annotation : parameter.getAnnotations()) {
                    if (annotation instanceof ConfigProperty configProperty
                            && configProperty.name().contains(CERTIFICATE_MARKER)) {
                        names.add(configProperty.name());
                    }
                }
            }
        }
        return names;
    }

    private static Set<String> leavesUnder(Iterable<String> keys, String prefix) {
        Set<String> leaves = new LinkedHashSet<>();
        for (String key : keys) {
            assertTrue(key.startsWith(prefix),
                    () -> "'" + key + "' does not carry the '" + prefix + "' prefix its list is "
                            + "declared under, so the two lists cannot be compared leaf by leaf");
            leaves.add(key.substring(prefix.length()));
        }
        return leaves;
    }
}
