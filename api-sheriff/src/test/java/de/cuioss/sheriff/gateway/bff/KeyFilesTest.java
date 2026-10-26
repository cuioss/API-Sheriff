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
package de.cuioss.sheriff.gateway.bff;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link KeyFiles} — the bounded read the BFF key files share.
 * <p>
 * The contracts under test are: a file at the caller's bound is returned byte for byte; every
 * refusal branch (unusable path, missing path, directory, oversize file, unreadable file) names the
 * caller's configuration field and the defect, never echoes the configured path, and chains no
 * cause, because the JDK's own failure text carries the path.
 */
class KeyFilesTest {

    private static final String FIELD = "oidc.session.key_file";
    private static final int BOUND = 16;

    @TempDir
    Path directory;

    /** Asserts a refusal: the field opens the message, the defect is named, the path is not echoed. */
    private static void assertRefusal(String configured, String defect) {
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> KeyFiles.readBounded(configured, FIELD, BOUND), "the key file should be refused");

        String message = thrown.getMessage();
        assertAll("refusal of " + defect,
                () -> assertEquals(FIELD + " " + defect, message, "the refusal should be the field and the defect"),
                () -> assertFalse(message.contains(configured), "the configured path should never be echoed"),
                () -> assertNull(thrown.getCause(), "no cause should be chained, a JDK failure text carries the path"));
    }

    @Test
    @DisplayName("Should return a file exactly at the bound byte for byte")
    void shouldReturnAFileAtTheBound() throws Exception {
        byte[] content = new byte[BOUND];
        Arrays.fill(content, (byte) 0x41);
        Path keyFile = Files.write(directory.resolve("at-bound.key"), content);

        byte[] read = KeyFiles.readBounded(keyFile.toString(), FIELD, BOUND);

        assertArrayEquals(content, read, "a file at the bound should be returned unchanged");
    }

    @Test
    @DisplayName("Should return an empty file as no bytes, leaving the judgement to the caller")
    void shouldReturnAnEmptyFileAsNoBytes() throws Exception {
        Path keyFile = Files.write(directory.resolve("empty.key"), new byte[0]);

        assertEquals(0, KeyFiles.readBounded(keyFile.toString(), FIELD, BOUND).length,
                "the read parses nothing, so an empty file is the caller's to refuse");
    }

    @Test
    @DisplayName("Should refuse a file one byte above the bound")
    void shouldRefuseAFileAboveTheBound() throws Exception {
        Path keyFile = Files.write(directory.resolve("oversize.key"), new byte[BOUND + 1]);

        assertRefusal(keyFile.toString(), "names a file larger than the 16 byte limit");
    }

    @Test
    @DisplayName("Should refuse a missing file")
    void shouldRefuseAMissingFile() {
        assertRefusal(directory.resolve("absent.key").toString(), "names a file that does not exist");
    }

    @Test
    @DisplayName("Should refuse a directory, which is not a regular file")
    void shouldRefuseADirectory() {
        assertRefusal(directory.toString(), "does not name a regular file");
    }

    @Test
    @DisplayName("Should refuse a path the platform cannot represent")
    void shouldRefuseAnUnusablePath() {
        assertRefusal("key\0file.key", "does not name a usable file path");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    @DisplayName("Should refuse an unreadable file")
    void shouldRefuseAnUnreadableFile() throws Exception {
        Path keyFile = Files.write(directory.resolve("unreadable.key"), new byte[BOUND]);
        Files.setPosixFilePermissions(keyFile, Set.of());
        assumeFalse(Files.isReadable(keyFile), "this user reads any file, so the case cannot be staged");

        assertRefusal(keyFile.toString(), "names a file that cannot be read");
    }

    @Test
    @DisplayName("Should name the caller's field, so each key file is refused under its own name")
    void shouldNameTheCallersField() {
        String otherField = "oidc.sender_constraint.key_file";
        String absentKeyFile = directory.resolve("absent.key").toString();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> KeyFiles.readBounded(absentKeyFile, otherField, BOUND));

        assertTrue(thrown.getMessage().startsWith(otherField + " "), thrown.getMessage());
    }
}
