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
package de.cuioss.sheriff.gateway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;


import de.cuioss.tools.logging.LogRecord;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The executable form of the identifier-allocation contract the gateway's {@link LogRecord}
 * catalogues share.
 * <p>
 * Every catalogue in this module emits under the same {@code ApiSheriff} prefix, so a structured log
 * line is identified by its number alone and two constants carrying the same number are
 * indistinguishable to anyone reading, grepping, or alerting on the log. Nothing in the language
 * prevents that: the catalogues are separate classes in separate packages, each numbering its own
 * constants, so a collision is invisible at the point it is written.
 * <p>
 * The contract used to live as a hand-maintained inventory of ranges in each catalogue's javadoc,
 * under the instruction "never renumber one catalogue without checking the other for a collision".
 * That inventory was stale more than once — it named two of the three catalogues and omitted ranges
 * that had already been allocated — so following it faithfully still led to a collision. This test
 * replaces it: the catalogues are DISCOVERED from the compiled output rather than listed, so a new
 * catalogue is covered the moment it is compiled, and the assertions below are what actually stops a
 * duplicate number from being merged.
 */
class LogMessagesCatalogueTest {

    private static final String CATALOGUE_SUFFIX = "LogMessages.class";
    private static final int MINIMUM_EXPECTED_CATALOGUES = 3;

    /**
     * One catalogued constant, carrying enough context to name it in a failure message.
     *
     * @param owner      the catalogue's simple class name
     * @param holder     the nested level holder's simple name ({@code INFO} / {@code WARN} /
     *                   {@code ERROR}), or the empty string for a constant on the catalogue itself
     * @param field      the constant's field name
     * @param prefix     the record's own prefix — identifiers are unique per prefix, not globally
     * @param identifier the record's numeric identifier
     */
    private record Catalogued(String owner, String holder, String field, String prefix, int identifier) {

        String qualifiedName() {
            return holder.isEmpty() ? owner + "." + field : owner + "." + holder + "." + field;
        }
    }

    /**
     * The identifier band each level holder owns, as documented on every catalogue.
     */
    private static final Map<String, int[]> BANDS = Map.of(
            "INFO", new int[]{1, 99},
            "WARN", new int[]{100, 199},
            "ERROR", new int[]{200, 299});

    @Test
    @DisplayName("Should allocate every catalogued identifier exactly once per logging prefix")
    void shouldNotCollideOnAnyIdentifierWithinAPrefix() throws Exception {
        List<Catalogued> catalogued = collectCatalogued();

        Map<String, List<Catalogued>> byIdentity = new LinkedHashMap<>();
        for (Catalogued entry : catalogued) {
            byIdentity.computeIfAbsent(entry.prefix() + "-" + entry.identifier(), _ -> new ArrayList<>())
                    .add(entry);
        }
        List<String> collisions = byIdentity.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " allocated by "
                        + entry.getValue().stream().map(Catalogued::qualifiedName).toList())
                .toList();

        assertTrue(collisions.isEmpty(),
                () -> "two constants share one identifier, so their log lines are indistinguishable "
                        + "to any reader, grep, or alert: " + collisions);
    }

    @Test
    @DisplayName("Should keep every catalogued identifier inside the band its level holder owns")
    void shouldKeepEveryIdentifierInsideItsDeclaredBand() throws Exception {
        List<Catalogued> catalogued = collectCatalogued();

        List<String> strays = catalogued.stream()
                .filter(entry -> BANDS.containsKey(entry.holder()))
                .filter(entry -> {
                    int[] band = BANDS.get(entry.holder());
                    return entry.identifier() < band[0] || entry.identifier() > band[1];
                })
                .map(entry -> entry.qualifiedName() + " = " + entry.identifier()
                        + " (band " + BANDS.get(entry.holder())[0] + "-" + BANDS.get(entry.holder())[1] + ")")
                .toList();

        assertTrue(strays.isEmpty(),
                () -> "an identifier outside its holder's band makes the level unreadable from the "
                        + "number, which is the whole point of the banding: " + strays);
    }

    @Test
    @DisplayName("Should discover every catalogue and every constant, so neither check can pass vacuously")
    void shouldDiscoverEveryCatalogueAndConstant() throws Exception {
        // Without this, a reflection walk that silently found nothing — a renamed suffix, a changed
        // build layout, a holder that stopped being nested — would satisfy both checks above by
        // examining an empty list, and the contract would be unenforced while still reporting green.
        List<Class<?>> catalogues = discoverCatalogues();
        List<Catalogued> catalogued = collectCatalogued();

        long declaredFields = catalogues.stream()
                .flatMap(LogMessagesCatalogueTest::holdersOf)
                .flatMap(holder -> Stream.of(holder.getDeclaredFields()))
                .filter(LogMessagesCatalogueTest::isCatalogueConstant)
                .count();

        assertAll(
                () -> assertTrue(catalogues.size() >= MINIMUM_EXPECTED_CATALOGUES,
                        () -> "expected at least " + MINIMUM_EXPECTED_CATALOGUES
                                + " catalogues on the compiled output, found " + catalogues),
                () -> assertTrue(catalogued.size() > 0,
                        "the walk collected no constants at all, so every other assertion here is vacuous"),
                () -> assertEquals(declaredFields, catalogued.size(),
                        "every declared LogRecord constant must reach the collision check — a constant "
                                + "the walk misses is a constant nothing stops from colliding"));
    }

    /**
     * @return every {@link LogRecord} constant declared by every discovered catalogue
     */
    private static List<Catalogued> collectCatalogued() throws Exception {
        List<Catalogued> collected = new ArrayList<>();
        for (Class<?> catalogue : discoverCatalogues()) {
            for (Class<?> holder : holdersOf(catalogue).toList()) {
                String holderName = holder.equals(catalogue) ? "" : holder.getSimpleName();
                for (Field field : holder.getDeclaredFields()) {
                    if (!isCatalogueConstant(field)) {
                        continue;
                    }
                    field.setAccessible(true);
                    LogRecord record = (LogRecord) field.get(null);
                    collected.add(new Catalogued(catalogue.getSimpleName(), holderName, field.getName(),
                            record.getPrefix(), record.getIdentifier()));
                }
            }
        }
        return collected;
    }

    /**
     * @param catalogue a catalogue class
     * @return the catalogue itself followed by its nested level holders, so a constant declared
     *         directly on the catalogue is collected as readily as one inside {@code INFO}
     */
    private static Stream<Class<?>> holdersOf(Class<?> catalogue) {
        return Stream.concat(Stream.of(catalogue), Stream.of(catalogue.getDeclaredClasses()));
    }

    private static boolean isCatalogueConstant(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers)
                && Modifier.isFinal(modifiers)
                && LogRecord.class.isAssignableFrom(field.getType());
    }

    /**
     * Discovers the catalogues from the module's compiled output rather than from a hand-written
     * list. A list would reintroduce exactly the defect this test exists to remove: a second place
     * that must be kept in step by hand, and that is silently wrong the moment it is not.
     *
     * @return every compiled {@code *LogMessages} class in this module, in a stable order
     */
    private static List<Class<?>> discoverCatalogues() throws Exception {
        Path classesRoot = Path.of(ApiSheriffLogMessages.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classesRoot),
                () -> "expected the module's compiled classes directory, got " + classesRoot);
        try (Stream<Path> entries = Files.walk(classesRoot)) {
            List<String> binaryNames = entries
                    .filter(path -> path.getFileName().toString().endsWith(CATALOGUE_SUFFIX))
                    .map(path -> classesRoot.relativize(path).toString()
                            .replace(File.separatorChar, '.')
                            .replace(".class", ""))
                    .sorted()
                    .toList();
            List<Class<?>> catalogues = new ArrayList<>();
            for (String binaryName : binaryNames) {
                catalogues.add(Class.forName(binaryName));
            }
            return catalogues;
        }
    }
}
