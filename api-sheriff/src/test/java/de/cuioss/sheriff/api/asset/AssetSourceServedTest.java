/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.asset;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;


import de.cuioss.sheriff.api.asset.AssetSource.Served;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Contract of the {@link Served} value object: the generated record accessors would
 * compare and hash the {@code byte[] body} by array identity and dump its bytes in
 * {@code toString}. The overrides fix both — equality and hashing are content-based, and
 * {@code toString} renders only the body length, never the (possibly sensitive) bytes.
 */
class AssetSourceServedTest {

    private static final int OK = 200;
    private static final byte[] BODY = "asset-bytes".getBytes(StandardCharsets.UTF_8);
    private static final Map<String, String> HEADERS = Map.of("Content-Type", "text/plain");

    @Test
    @DisplayName("equals/hashCode compare the body by content, not array identity")
    void equalsIsContentBased() {
        // Arrange — two Served built from independent byte[] instances carrying the same bytes.
        Served first = new Served(OK, HEADERS, BODY.clone());
        Served second = new Served(OK, HEADERS, BODY.clone());

        // Act + Assert
        assertAll(
                () -> assertEquals(first, second, "equal body bytes make two Served equal"),
                () -> assertEquals(first.hashCode(), second.hashCode(), "equal Served share a hashCode"),
                () -> assertEquals(first, first, "a Served equals itself"));
    }

    @Test
    @DisplayName("differing status, headers, or body bytes break equality")
    void inequalityAcrossComponents() {
        // Arrange
        Served base = new Served(OK, HEADERS, BODY);

        // Act + Assert
        assertAll(
                () -> assertNotEquals(base, new Served(404, HEADERS, BODY), "status participates in equality"),
                () -> assertNotEquals(base, new Served(OK, Map.of("X", "y"), BODY),
                        "headers participate in equality"),
                () -> assertNotEquals(base,
                        new Served(OK, HEADERS, "other".getBytes(StandardCharsets.UTF_8)),
                        "body bytes participate in equality"),
                () -> assertNotEquals("not-a-served", base, "a Served never equals an unrelated type"));
    }

    @Test
    @DisplayName("toString reports only the body length, never the raw bytes")
    void toStringHidesBody() {
        // Arrange
        byte[] secret = "session-cookie-value".getBytes(StandardCharsets.UTF_8);
        Served served = new Served(OK, HEADERS, secret);

        // Act
        String rendered = served.toString();

        // Assert
        assertAll(
                () -> assertTrue(rendered.contains("status=" + OK), "the status is rendered"),
                () -> assertTrue(rendered.contains("body.length=" + secret.length),
                        "the body length is rendered for diagnostics"),
                () -> assertFalse(rendered.contains("session-cookie-value"),
                        "the raw response body must never be dumped"));
    }
}
