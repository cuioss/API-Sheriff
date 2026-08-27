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
package de.cuioss.sheriff.gateway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    private static final String CATALOGUE_SOURCE_SUFFIX = "LogMessages.java";
    private static final String JAVA_EXTENSION = ".java";

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
    @DisplayName("Should discover exactly the catalogues the sources declare, so neither check can pass vacuously")
    void shouldDiscoverEveryCatalogueAndConstant() throws Exception {
        // Without this, a reflection walk that silently found nothing — a renamed suffix, a changed
        // build layout, a holder that stopped being nested — would satisfy both checks above by
        // examining an empty list, and the contract would be unenforced while still reporting green.
        //
        // The anchor has to come from OUTSIDE the walk to say anything at all. A count of catalogues
        // maintained here by hand could not: after a fourth catalogue is added, a rename that drops
        // one out of the walk still leaves three, and the collision and band checks then pass over a
        // set that quietly shrank. The module's own SOURCE tree is the set both this walk and that
        // count were trying to mirror, so it is read directly instead — a catalogue the walk stops
        // seeing now fails here, and a new one is expected the moment its file exists.
        List<String> declared = declaredCatalogueNames();
        List<Class<?>> catalogues = discoverCatalogues();
        List<Catalogued> catalogued = collectCatalogued();

        // Binary names on BOTH sides, never simple names: erasing the package would let a catalogue
        // the walk lost pass unnoticed whenever some other package held a compiled *LogMessages with
        // the same simple name — which is precisely the scenario this comparison exists to catch.
        List<String> discovered = catalogues.stream().map(Class::getName).sorted().toList();

        assertAll(
                () -> assertFalse(declared.isEmpty(),
                        "no *LogMessages source was found at all, so the expected set is empty and "
                                + "every comparison against it is vacuous"),
                () -> assertEquals(declared, discovered,
                        "the compiled catalogues the walk finds must be exactly the ones the module's "
                                + "sources declare — a catalogue missing here is one whose identifiers "
                                + "no longer reach the collision and band checks"),
                () -> assertFalse(catalogued.isEmpty(),
                        "the walk collected no constants at all, so every other assertion here is vacuous"),
                () -> assertAll(catalogues.stream().map(catalogue -> () -> assertTrue(
                        catalogued.stream()
                                .anyMatch(entry -> entry.owner().equals(catalogue.getSimpleName())),
                        () -> catalogue.getSimpleName() + " contributed no constant to the collision "
                                + "check — a catalogue whose holders the walk cannot read is a "
                                + "catalogue nothing stops from colliding"))));
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
                    LogRecord logRecord = (LogRecord) field.get(null);
                    collected.add(new Catalogued(catalogue.getSimpleName(), holderName, field.getName(),
                            logRecord.getPrefix(), logRecord.getIdentifier()));
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
     * The catalogue names the module's SOURCE tree declares, read at run time rather than listed.
     * <p>
     * This is the one anchor in this test that does not derive from the reflection walk, which is
     * what makes the walk falsifiable: a catalogue the walk stops seeing still has a source file, so
     * the two sets diverge and the comparison fails. Deriving it from the sources also means a newly
     * added catalogue is expected the moment its file lands — no second place to update by hand, and
     * therefore no second place to be silently wrong.
     *
     * @return the binary names of every {@code *LogMessages} source file in this module, sorted.
     *         Binary rather than simple names, so the comparison keeps package identity: two
     *         catalogues sharing a simple name across packages would otherwise let a lost one pass.
     */
    private static List<String> declaredCatalogueNames() throws Exception {
        // <module>/target/classes -> <module>. Resolved relatively rather than through getParent()
        // so the derivation carries no nullable hop.
        Path sourceRoot = compiledClassesRoot().resolve("../../src/main/java").normalize();
        assertTrue(Files.isDirectory(sourceRoot),
                () -> "expected the module's Java source root, got " + sourceRoot
                        + " — the derivation below is what anchors the reflection walk, so a layout "
                        + "change must fail here rather than yield an empty expected set");
        try (Stream<Path> entries = Files.walk(sourceRoot)) {
            return entries
                    .filter(path -> path.getFileName().toString().endsWith(CATALOGUE_SOURCE_SUFFIX))
                    .map(path -> sourceRoot.relativize(path).toString()
                            .replace(File.separatorChar, '.'))
                    .map(name -> name.substring(0, name.length() - JAVA_EXTENSION.length()))
                    .sorted()
                    .toList();
        }
    }

    /**
     * @return the module's compiled-classes directory, the root both the catalogue walk and the
     *         source-root derivation are anchored on
     */
    private static Path compiledClassesRoot() throws Exception {
        Path classesRoot = Path.of(ApiSheriffLogMessages.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classesRoot),
                () -> "expected the module's compiled classes directory, got " + classesRoot);
        return classesRoot;
    }

    /**
     * Discovers the catalogues from the module's compiled output rather than from a hand-written
     * list. A list would reintroduce exactly the defect this test exists to remove: a second place
     * that must be kept in step by hand, and that is silently wrong the moment it is not.
     *
     * @return every compiled {@code *LogMessages} class in this module, in a stable order
     */
    private static List<Class<?>> discoverCatalogues() throws Exception {
        Path classesRoot = compiledClassesRoot();
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
