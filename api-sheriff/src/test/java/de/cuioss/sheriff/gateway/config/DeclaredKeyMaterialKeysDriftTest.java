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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 */
@DisplayName("Declared key-material vocabulary — drift guard")
class DeclaredKeyMaterialKeysDriftTest {

    /** The marker that selects the certificate keys out of each constructor's injected names. */
    private static final String CERTIFICATE_MARKER = "ssl.certificate.";

    @Test
    @DisplayName("The main-listener audit injects exactly the shared http certificate key set")
    void terminatedListenerAuditAgreesWithTheSharedSet() {
        Set<String> injected = injectedCertificateKeys(TerminatedListenerTlsAudit.class);

        assertEquals(Set.copyOf(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS), injected,
                "TerminatedListenerTlsAudit must read every spelling the validator reads. A key "
                        + "present here but absent from DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS "
                        + "means the validator would accept a deployment the audit cannot see; a key "
                        + "present there but absent here means the audit reports a plain-HTTP "
                        + "downgrade (ApiSheriff-121) against a listener that is terminating TLS — "
                        + "the exact false positive this shared vocabulary was extracted to remove. "
                        + "Add the spelling to DeclaredKeyMaterialKeys and to BOTH audits.");
    }

    @Test
    @DisplayName("The management audit injects exactly the shared management certificate key set")
    void managementAuditAgreesWithTheSharedSet() {
        Set<String> injected = injectedCertificateKeys(ManagementPlainHttpAudit.class);

        assertEquals(Set.copyOf(DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_KEYS), injected,
                "ManagementPlainHttpAudit must read every management spelling the runtime honours. "
                        + "Reading fewer emits ApiSheriff-115 against a management port that is on "
                        + "HTTPS through a keystore file or a credentials provider.");
    }

    @Test
    @DisplayName("The two prefixes carry the same leaf set — one listener cannot gain a spelling alone")
    void bothPrefixesCarryTheSameLeafSet() {
        Set<String> httpLeaves = leavesUnder(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS,
                DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_PREFIX);
        Set<String> managementLeaves = leavesUnder(DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_KEYS,
                DeclaredKeyMaterialKeys.MANAGEMENT_CERTIFICATE_PREFIX);

        assertEquals(httpLeaves, managementLeaves,
                "both listeners fall through to the same legacy ssl.certificate.* block in "
                        + "HttpServerOptionsUtils, so a spelling that supplies material on one "
                        + "supplies it on the other. Adding one to a single prefix leaves the other "
                        + "leg with the false positive that was just fixed on this one.");
    }

    @Test
    @DisplayName("The guard is non-vacuous: the reflective scan really found four keys per audit")
    void guardIsNonVacuous() {
        Set<String> mainListener = injectedCertificateKeys(TerminatedListenerTlsAudit.class);
        Set<String> management = injectedCertificateKeys(ManagementPlainHttpAudit.class);

        assertAll("a scan that matched nothing would make every assertion above pass on two empty sets",
                () -> assertFalse(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_KEYS.isEmpty(),
                        "the shared http list is empty, so it constrains nothing"),
                () -> assertEquals(4, mainListener.size(),
                        "the four spellings TlsUtils.computeKeyStoreOptions accepts: " + mainListener),
                () -> assertEquals(4, management.size(),
                        "the same four on the management leg: " + management),
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
