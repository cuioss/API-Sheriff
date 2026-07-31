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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link NeutralTlsConfigSource}: the ordinal contract that lets a policy named in
 * {@code gateway.yaml} outrank the same knob supplied as a deployment environment variable, the
 * silent-degradation contract for an absent or malformed document, and the negative constraints that
 * bound what the source is allowed to project.
 * <p>
 * The negative constraints carry most of the weight here. The source runs at an ordinal above every
 * environment variable, so <em>anything</em> it projects wins over the deployment — which is safe
 * only for as long as it projects nothing but policy. Two keys classes are asserted absent by name:
 * a port-shaped key (which would silently override {@code QUARKUS_MANAGEMENT_PORT}) and any
 * {@code quarkus.tls.key-store.*} key (which would populate the DEFAULT TLS registry bucket and make
 * the management interface inherit HTTPS through {@code registry.getDefault()}, a path no
 * configuration file mentions — upstream quarkus issue 43380).
 */
class NeutralTlsConfigSourceTest {

    @TempDir
    Path configDir;

    private void writeGateway(String content) throws IOException {
        Files.writeString(configDir.resolve("gateway.yaml"), content);
    }

    @Test
    @DisplayName("The ordinal outranks the environment-variable source")
    void ordinalOutranksTheEnvironmentVariableSource() {
        assertTrue(NeutralTlsConfigSource.ORDINAL > 300,
                "an ordinal at or below the environment source's 300 would let a deployment variable "
                        + "silently override a policy named in gateway.yaml");
    }

    @Test
    @DisplayName("A document declaring both policy blocks projects no key")
    void wellFormedDocumentProjectsNoKey() throws Exception {
        writeGateway("""
                version: 1
                tls:
                  min_version: "1.3"
                management:
                  tls:
                    enabled: true
                """);

        NeutralTlsConfigSource source = new NeutralTlsConfigSource(configDir);

        assertAll("projection",
                () -> assertTrue(source.getPropertyNames().isEmpty(),
                        "the seam is delivered inert: it projects the parse and the ordinal, no keys"),
                () -> assertTrue(source.getProperties().isEmpty(), "getProperties must agree with getPropertyNames"));
    }

    @Test
    @DisplayName("An absent document degrades silently rather than failing the boot early")
    void absentDocumentProjectsNoKey() {
        NeutralTlsConfigSource source = new NeutralTlsConfigSource(configDir);

        assertTrue(source.getProperties().isEmpty(),
                "ConfigLoader reports a missing gateway.yaml with full context moments later; failing "
                        + "here would replace that diagnostic with a context-free config-system error");
    }

    @Test
    @DisplayName("A malformed document degrades silently rather than failing the boot early")
    void malformedDocumentProjectsNoKey() throws Exception {
        writeGateway("this: [is: not: valid: yaml");

        NeutralTlsConfigSource source = new NeutralTlsConfigSource(configDir);

        assertTrue(source.getProperties().isEmpty(), "a parse failure here is re-reported by ConfigLoader");
    }

    @ParameterizedTest
    @DisplayName("No deployment-bound or key-material key is ever projected")
    @ValueSource(strings = {
            "quarkus.management.port",
            "quarkus.http.port",
            "quarkus.http.ssl-port",
            "quarkus.tls.key-store.pem.0.cert",
            "quarkus.tls.key-store.p12.path",
            "quarkus.tls.trust-store.p12.path"
    })
    void neverProjectsADeploymentBoundOrKeyMaterialKey(String forbiddenKey) throws Exception {
        writeGateway("""
                version: 1
                tls:
                  min_version: "1.3"
                management:
                  tls:
                    enabled: false
                """);

        NeutralTlsConfigSource source = new NeutralTlsConfigSource(configDir);

        assertNull(source.getValue(forbiddenKey),
                () -> forbiddenKey + " must never be projected: this source outranks every environment "
                        + "variable, so projecting a port would override the deployment's own knob and "
                        + "projecting key material would populate the default TLS registry bucket");
    }

    @Test
    @DisplayName("The source names itself after the document it reads")
    void nameIdentifiesTheDocumentItReads() {
        assertEquals("NeutralTlsConfigSource[gateway.yaml]", new NeutralTlsConfigSource(configDir).getName());
    }

    @Test
    @DisplayName("getOrdinal reports the declared ordinal")
    void getOrdinalReportsTheDeclaredOrdinal() {
        assertEquals(NeutralTlsConfigSource.ORDINAL, new NeutralTlsConfigSource(configDir).getOrdinal());
    }

    @Test
    @DisplayName("The registered provider yields exactly one source")
    void providerYieldsExactlyOneSource() {
        var sources = new NeutralTlsConfigSource.Provider().getConfigSources(getClass().getClassLoader());

        assertEquals(1, StreamSupport.stream(sources.spliterator(), false).count(),
                "the ServiceLoader entry must contribute the single neutral source, no more");
    }
}
