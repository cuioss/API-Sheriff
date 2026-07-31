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

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the supported plain-HTTP management downgrade end to end against the dedicated
 * {@code api-sheriff-plain-mgmt} compose instance (management published on 19005).
 * <p>
 * The downgrade is Quarkus-native: the instance sets
 * {@code QUARKUS_MANAGEMENT_TLS_CONFIGURATION_NAME=plain-management}, selecting the TLS bucket that
 * {@code application.properties} declares with no key material, so the recorder finds no key/cert and
 * starts the management listener plain. Nothing is rebuilt for it — the instance runs the same native
 * image as every other gateway service.
 * <p>
 * Three properties are asserted together because each alone is satisfiable by an accident: that the
 * port answers over plain HTTP, that it does <em>not</em> answer over HTTPS (so the assertion above is
 * not merely a redirect or a tolerant client), and that boot said so out loud with WARN
 * {@code ApiSheriff-115}. A silent downgrade would satisfy the first two and is exactly the outcome
 * the audit exists to prevent.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Plain-HTTP management opt-out")
class ManagementPlainHttpOptOutIT {

    private static final String DEFAULT_PLAIN_MANAGEMENT_PORT = "19005";
    private static final String LOG_FILE = "quarkus-plain-mgmt.log";

    private static String plainManagementBaseUri() {
        return "http://localhost:"
                + System.getProperty("test.plain.mgmt.management.port", DEFAULT_PLAIN_MANAGEMENT_PORT);
    }

    @Test
    @DisplayName("the management port serves health over PLAIN HTTP")
    void managementServesHealthOverPlainHttp() {
        var response = given()
                .baseUri(plainManagementBaseUri())
                .when()
                .get("/q/health/live")
                .then()
                .statusCode(200)
                .extract();

        assertEquals("UP", response.path("status"),
                "the downgraded management interface must still serve a working health endpoint");
    }

    @Test
    @DisplayName("the same management port refuses HTTPS — the downgrade is real, not a tolerant client")
    void managementRefusesHttps() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(plainManagementBaseUri().replace("http://", "https://") + "/q/health/live"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        assertThrows(IOException.class, () -> client.send(request, HttpResponse.BodyHandlers.discarding()),
                "a plain listener cannot complete a TLS handshake; if this succeeds the instance is "
                        + "serving HTTPS and the opt-out silently did nothing");
    }

    @Test
    @DisplayName("boot announced the downgrade with WARN ApiSheriff-115")
    void bootWarnedAboutTheDowngrade() {
        String log = readContainerLog();

        assertTrue(log.contains("ApiSheriff-115"),
                "the downgrade must be announced with the WARN identifier so it is greppable");
        assertTrue(log.contains("Management interface is serving PLAIN HTTP on port"),
                "the WARN must name what actually happened, not merely fire");
    }

    private static String readContainerLog() {
        Path logDir = Path.of(System.getProperty("test.log.dir", "target/quarkus-logs"));
        Path logFile = logDir.resolve(LOG_FILE);
        assertTrue(Files.isRegularFile(logFile),
                () -> "expected the plain-management instance log at " + logFile.toAbsolutePath());
        try {
            return Files.readString(logFile);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + logFile, e);
        }
    }
}
