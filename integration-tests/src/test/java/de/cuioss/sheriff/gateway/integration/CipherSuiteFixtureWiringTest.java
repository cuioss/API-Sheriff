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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLContext;
import org.yaml.snakeyaml.Yaml;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard that the committed {@code gateway.yaml} fixture declares a
 * {@code tls.cipher_suites} allowlist this JDK can actually support.
 * <p>
 * The gateway boots fail-closed on that allowlist: {@code TlsServerCustomizer} rejects any suite name
 * the running JDK does not know, so a fixture that drifts onto an unsupported name takes the whole
 * containerised stack down at startup — a slow, opaque failure a long way from its cause. This test
 * reads the same file the container mounts and turns that into an immediate, located surefire
 * failure.
 * <p>
 * It reads the fixture directly rather than mirroring it. An earlier version of this guard lived in
 * the api-sheriff module and carried a hand-copied duplicate of the four suite names; editing the
 * fixture left that copy asserting the old list and passing while the guarantee it stated no longer
 * held. A hardcoded list that must mirror a set defined elsewhere is a defect unless it is derived
 * from that source, so the list is parsed here and never restated.
 * <p>
 * Scope note: this guard covers suite <em>names</em>. The sibling fail-closed check — that every
 * protocol enabled by {@code tls.min_version} retains a negotiable suite — is deliberately not
 * re-implemented here, because doing so would duplicate production logic in a test and could drift
 * from it. That check has a real matched control instead: it runs at container boot, so a fixture
 * that strands a protocol fails the containerised suite outright.
 * <p>
 * It parses the committed descriptor only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("gateway.yaml cipher-suite fixture — deployment wiring")
class CipherSuiteFixtureWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));
    private static final Path GATEWAY_FIXTURE =
            MODULE.resolve("src/main/docker/sheriff-config/gateway.yaml");

    @Test
    @DisplayName("the fixture declares a non-empty cipher allowlist")
    void theFixtureDeclaresACipherAllowlist() throws Exception {
        List<String> declared = declaredCipherSuites();

        assertFalse(declared.isEmpty(),
                "gateway.yaml must declare tls.cipher_suites — an empty or absent list means the stack "
                        + "silently falls back to the platform default selection this fixture exists "
                        + "to override");
    }

    @Test
    @DisplayName("every declared suite is supported by this JDK")
    void everyDeclaredSuiteIsSupportedByThisJdk() throws Exception {
        // Arrange — the same authority TlsServerCustomizer validates against at boot.
        Set<String> supported = Set.copyOf(
                Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites()));
        List<String> declared = declaredCipherSuites();
        assertFalse(declared.isEmpty(),
                "gateway.yaml declares no cipher suites — the support assertion below would pass "
                        + "without checking anything, so this precondition is asserted here rather "
                        + "than left to a sibling test that a rename or deletion could remove");

        // Act
        List<String> unsupported = declared.stream()
                .filter(suite -> !supported.contains(suite))
                .toList();

        // Assert
        assertTrue(unsupported.isEmpty(),
                () -> "gateway.yaml declares cipher suites this JDK does not support: " + unsupported
                        + " — the gateway is fail-closed on this list, so the container would abort at "
                        + "boot rather than start with a narrowed allowlist");
    }

    @SuppressWarnings("unchecked")
    private static List<String> declaredCipherSuites() throws IOException {
        Map<String, Object> document;
        try (InputStream in = Files.newInputStream(GATEWAY_FIXTURE)) {
            document = new Yaml().loadAs(in, Map.class);
        }
        assertNotNull(document, "gateway.yaml must parse into a mapping");
        Object tls = document.get("tls");
        assertInstanceOf(Map.class, tls, "gateway.yaml must declare a tls block");
        Object cipherSuites = ((Map<String, Object>) tls).get("cipher_suites");
        assertInstanceOf(List.class, cipherSuites, "tls.cipher_suites must be a list");
        return ((List<?>) cipherSuites).stream().map(String::valueOf).toList();
    }
}
