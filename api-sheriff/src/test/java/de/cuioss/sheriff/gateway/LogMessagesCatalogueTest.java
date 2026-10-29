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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;


import de.cuioss.tools.logging.LogRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/**
 * The executable form of the identifier-allocation contract every {@link LogRecord} catalogue in the
 * reactor shares.
 * <p>
 * A structured log line is identified by its prefix and number, so two constants carrying the same
 * number under one prefix are indistinguishable to anyone reading, grepping, or alerting on the log.
 * Nothing in the language prevents that: the catalogues are separate classes, in separate packages and
 * modules, each numbering its own constants, so a collision is invisible at the point it is written.
 * The contract used to live as a hand-maintained inventory of ranges in each catalogue's javadoc, and
 * that inventory was stale more than once; this test replaces it.
 * <p>
 * <strong>The guard spans the reactor, not one module.</strong> The modules are derived from every
 * {@code <module>} the root {@code pom.xml} declares, profile-scoped ones included, and every
 * {@code *LogMessages.java} under a module's {@code src/main/java} is a catalogue. A module without
 * Java sources contributes nothing, and a catalogue added to any module is covered the moment its
 * source exists.
 * <p>
 * <strong>Why sources, not compiled classes.</strong> This test runs in {@code api-sheriff}, which
 * is built before {@code benchmarks} and has no dependency edge to it, so another module's compiled
 * output is absent on a clean build and may be stale on any other. The catalogue sources exist in
 * every build order. The source reader is strict — a builder call it cannot read, or a prefix or
 * identifier it cannot resolve to a literal, fails naming the file and field; nothing is skipped —
 * and it is calibrated against the compiled catalogues of this module: for the host module it must
 * read exactly the records a reflection walk over the compiled classes collects.
 * <p>
 * <strong>Controls</strong> (ADR-0030). The populations are asserted non-empty as properties, never
 * as equality with today's membership, and the reader and both rules are driven with in-memory
 * specimen catalogues they must reject or accept.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Every LogMessages catalogue in the reactor allocates each identifier once per prefix, inside its band")
class LogMessagesCatalogueTest {

    private static final String CATALOGUE_CLASS_SUFFIX = "LogMessages.class";
    private static final String CATALOGUE_SOURCE_SUFFIX = "LogMessages.java";
    private static final String JAVA_EXTENSION = ".java";
    private static final Path MAIN_SOURCES = Path.of("src", "main", "java");
    private static final String TEXT_BLOCK = "\"\"\"";

    /** Stands in for a string or char literal in sanitised source, around the literal's index. */
    private static final char LITERAL_MARK = '\0';

    private static final String NAME = "[A-Za-z_$][\\w$]*";
    private static final Pattern LITERAL = Pattern.compile("\\x00(\\d+)\\x00");
    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "\\bstatic\\s+final\\s+String\\s+(" + NAME + ")\\s*=\\s*\\x00(\\d+)\\x00\\s*;");
    private static final Pattern TYPE_DECLARATION = Pattern.compile(
            "(?<![.\\w$])(?:(?:class|interface|enum)\\s+(" + NAME + ")|record\\s+(" + NAME + ")\\s*[(<])");
    private static final Pattern BUILDER_CALL = Pattern.compile("\\bLogRecordModel\\s*\\.\\s*builder\\s*\\(");
    private static final Pattern RECORD_DECLARATION = Pattern.compile("\\bstatic\\s+final\\s+LogRecord\\s+("
            + NAME + ")\\s*=\\s*LogRecordModel\\s*\\.\\s*builder\\s*\\(\\s*\\)([^;]*);");
    /** A {@code .prefix(..)} call; group 1 the whole argument, surrounding whitespace included. */
    private static final Pattern PREFIX_CALL = Pattern.compile("\\.\\s*prefix\\s*\\(([^()]*)\\)");
    /** A {@code .identifier(..)} call; group 1 the whole argument, surrounding whitespace included. */
    private static final Pattern IDENTIFIER_CALL = Pattern.compile("\\.\\s*identifier\\s*\\(([^()]*)\\)");
    private static final Pattern INTEGER_LITERAL = Pattern.compile("\\d+");
    /** A multi-digit integer literal with a leading zero, which Java reads as octal. */
    private static final Pattern LEADING_ZERO_LITERAL = Pattern.compile("0\\d+");

    /**
     * The identifier band each level holder owns, as documented on every catalogue. A nested holder
     * named here is a level holder; a record in any other nested holder fails the band rule.
     */
    private static final Map<String, int[]> BANDS = Map.of(
            "INFO", new int[]{1, 99},
            "WARN", new int[]{100, 199},
            "ERROR", new int[]{200, 299});

    /**
     * One catalogued constant, carrying enough context to name it in a failure message.
     *
     * @param module     the reactor module declaring the catalogue
     * @param owner      the catalogue's simple class name
     * @param holder     the nested level holder's simple name ({@code INFO} / {@code WARN} /
     *                   {@code ERROR}), or the empty string for a constant on the catalogue itself
     * @param field      the constant's field name
     * @param prefix     the record's own prefix — identifiers are unique per prefix, not globally
     * @param identifier the record's numeric identifier
     */
    private record Catalogued(String module, String owner, String holder, String field, String prefix,
    int identifier) {

        String qualifiedName() {
            String member = holder.isEmpty() ? owner + "." + field : owner + "." + holder + "." + field;
            return module + ":" + member;
        }
    }

    /**
     * One catalogue source file and the records the reader took from it.
     *
     * @param module  the reactor module declaring it
     * @param file    the source file
     * @param records the records read from it
     */
    private record CatalogueSource(String module, Path file, List<Catalogued> records) {
    }

    /** A class body open at some point of a source: its name and the brace depth it opened at. */
    private record Scope(String name, int depth) {
    }

    @Nested
    @DisplayName("Across every reactor module")
    class AcrossTheReactor {

        @Test
        @DisplayName("Should allocate every catalogued identifier exactly once per logging prefix")
        void shouldNotCollideOnAnyIdentifierWithinAPrefix() throws Exception {
            List<String> collisions = collisions(reactorRecords());

            assertTrue(collisions.isEmpty(),
                    () -> "two constants share one identifier, so their log lines are indistinguishable "
                            + "to any reader, grep, or alert: " + collisions);
        }

        @Test
        @DisplayName("Should keep every catalogued identifier inside the band its level holder owns")
        void shouldKeepEveryIdentifierInsideItsDeclaredBand() throws Exception {
            List<String> strays = strays(reactorRecords());

            assertTrue(strays.isEmpty(),
                    () -> "an identifier outside its holder's band makes the level unreadable from the "
                            + "number, which is the whole point of the banding: " + strays);
        }

        @Test
        @DisplayName("Should read a catalogue with at least one record, so neither rule can pass vacuously")
        void shouldReadEveryCatalogueOfEveryReactorModule() throws Exception {
            List<String> modules = reactorModules();
            String host = hostModule();
            List<CatalogueSource> catalogues = reactorCatalogues();

            assertAll(
                    () -> assertFalse(modules.isEmpty(),
                            "the root pom.xml yields no <module>, so no module is searched at all"),
                    () -> assertTrue(modules.contains(host),
                            () -> "the derived module set " + modules + " does not contain the host module "
                                    + host + ", so the derivation no longer reads the reactor"),
                    () -> assertFalse(catalogues.isEmpty(),
                            "no *LogMessages source was found in any reactor module, so both rules "
                                    + "examine an empty list"),
                    () -> assertAll(catalogues.stream().map(catalogue -> () -> assertFalse(
                            catalogue.records().isEmpty(),
                            () -> catalogue.module() + ":" + catalogue.file().getFileName()
                                    + " contributed no record — a catalogue the reader takes nothing from "
                                    + "is a catalogue nothing stops from colliding"))));
        }
    }

    @Nested
    @DisplayName("Calibrated against the compiled catalogues of the host module")
    class Calibration {

        @Test
        @DisplayName("Should read the host module's sources exactly as the reflection walk reads its classes")
        void shouldReadTheHostSourcesAsTheReflectionWalkReadsTheClasses() throws Exception {
            String host = hostModule();
            List<String> fromSources = reactorCatalogues().stream()
                    .filter(catalogue -> catalogue.module().equals(host))
                    .flatMap(catalogue -> catalogue.records().stream())
                    .map(Catalogued::toString).sorted().toList();
            List<String> fromClasses = collectCompiled().stream().map(Catalogued::toString).sorted().toList();

            assertAll(
                    () -> assertFalse(fromClasses.isEmpty(),
                            "the reflection walk collected no constant, so the calibration is vacuous"),
                    () -> assertEquals(fromClasses, fromSources,
                            "the source reader must read the host module's catalogues exactly as the "
                                    + "compiled classes declare them — a difference is a form the reader "
                                    + "misreads, and it would misread it in every other module too"));
        }

        @Test
        @DisplayName("Should discover exactly the compiled catalogues the host module's sources declare")
        void shouldDiscoverEveryCatalogueAndConstant() throws Exception {
            // The anchor has to come from OUTSIDE the reflection walk to say anything: the module's
            // own SOURCE tree is the set the walk mirrors, so a catalogue the walk stops seeing — a
            // renamed suffix, a changed build layout — fails here instead of shrinking the calibration.
            // Binary names on both sides, so a lost catalogue cannot hide behind a same-named one.
            List<String> declared = declaredCatalogueNames();
            List<Class<?>> catalogues = discoverCatalogues();
            List<Catalogued> compiled = collectCompiled();
            List<String> discovered = catalogues.stream().map(Class::getName).sorted().toList();

            assertAll(
                    () -> assertFalse(declared.isEmpty(),
                            "no *LogMessages source was found in the host module, so the expected set is empty"),
                    () -> assertEquals(declared, discovered,
                            "the compiled catalogues the walk finds must be exactly the ones the module's "
                                    + "sources declare"),
                    () -> assertAll(catalogues.stream().map(catalogue -> () -> assertTrue(
                            compiled.stream().anyMatch(entry -> entry.owner().equals(catalogue.getSimpleName())),
                            () -> catalogue.getSimpleName() + " contributed no constant to the walk"))));
        }
    }

    @Nested
    @DisplayName("Negative controls over specimen catalogues")
    class SpecimenControls {

        private static final String MODULE = "specimen-module";
        private static final String FILE = "SpecimenLogMessages.java";

        /**
         * A well-formed catalogue carrying the shapes the reader must not be fooled by: a builder call
         * named in a javadoc and a line comment, a template whose string holds braces, a comment
         * marker and the builder call, a literal prefix, and a constant on the catalogue itself.
         */
        private static final String WELL_FORMED = """
                package specimen;

                /** Names LogRecordModel.builder() in a comment, which declares no record. */
                public final class SpecimenLogMessages {

                    private static final String PREFIX = "Specimen";

                    // LogRecordModel.builder() in a line comment declares no record either.
                    public static final LogRecord DIRECT = LogRecordModel.builder()
                            .prefix("Direct")
                            .identifier(7)
                            .template("Braces { } a // marker and LogRecordModel.builder() in a string")
                            .build();

                    public static final class INFO {
                        public static final LogRecord STARTED = LogRecordModel.builder()
                                .prefix(PREFIX).identifier(1).template("Started %s").build();
                    }

                    public static final class WARN {
                        public static final LogRecord SLOW = LogRecordModel.builder()
                                .prefix(PREFIX).identifier(100).template("Slow '%s'").build();
                    }

                    public static final class ERROR {
                        public static final LogRecord BROKEN = LogRecordModel.builder()
                                .prefix(PREFIX).identifier(299).template("Broken").build();
                    }
                }
                """;

        @Test
        @DisplayName("Should accept a well-formed specimen and read every record of it")
        void shouldAcceptAWellFormedSpecimen() {
            List<Catalogued> records = readCatalogue(MODULE, FILE, WELL_FORMED);

            assertAll(
                    () -> assertEquals(List.of(
                                    new Catalogued(MODULE, "SpecimenLogMessages", "", "DIRECT", "Direct", 7),
                                    new Catalogued(MODULE, "SpecimenLogMessages", "INFO", "STARTED", "Specimen", 1),
                                    new Catalogued(MODULE, "SpecimenLogMessages", "WARN", "SLOW", "Specimen", 100),
                                    new Catalogued(MODULE, "SpecimenLogMessages", "ERROR", "BROKEN", "Specimen", 299)),
                            records, "the reader must read every record with its holder, prefix and identifier"),
                    () -> assertEquals(List.of(), collisions(records), "a well-formed specimen has no collision"),
                    () -> assertEquals(List.of(), strays(records), "a well-formed specimen has no stray"));
        }

        @Test
        @DisplayName("Should reject a duplicate identifier within one prefix, naming the prefix and both fields")
        void shouldRejectADuplicateIdentifier() {
            List<Catalogued> records = readCatalogue(MODULE, FILE, specimen(holder("ERROR",
                    recordDeclaration("FIRST", "PREFIX", "210"), recordDeclaration("SECOND", "PREFIX", "210"))));

            List<String> collisions = collisions(records);

            assertEquals(1, collisions.size(), () -> "expected exactly one collision, got " + collisions);
            assertAll(
                    () -> assertTrue(collisions.getFirst().contains("Specimen-210"), collisions::toString),
                    () -> assertTrue(collisions.getFirst().contains(MODULE + ":SpecimenLogMessages.ERROR.FIRST"),
                            collisions::toString),
                    () -> assertTrue(collisions.getFirst().contains(MODULE + ":SpecimenLogMessages.ERROR.SECOND"),
                            collisions::toString));
        }

        @Test
        @DisplayName("Should check two modules sharing a prefix together, and keep distinct prefixes apart")
        void shouldCheckModulesSharingAPrefixTogether() {
            List<Catalogued> records = new ArrayList<>();
            records.addAll(readCatalogue("module-a", FILE, specimen(holder("INFO",
                    recordDeclaration("ALPHA", "PREFIX", "5")))));
            records.addAll(readCatalogue("module-b", FILE, specimen(holder("INFO",
                    recordDeclaration("BETA", "PREFIX", "5"), recordDeclaration("GAMMA", "\"Other\"", "6")))));
            records.addAll(readCatalogue("module-c", FILE, specimen(holder("INFO",
                    recordDeclaration("DELTA", "\"Other\"", "5")))));

            List<String> collisions = collisions(records);

            assertEquals(1, collisions.size(), () -> "only Specimen-5 collides, across modules: " + collisions);
            assertAll(
                    () -> assertTrue(collisions.getFirst().contains("module-a:SpecimenLogMessages.INFO.ALPHA"),
                            collisions::toString),
                    () -> assertTrue(collisions.getFirst().contains("module-b:SpecimenLogMessages.INFO.BETA"),
                            collisions::toString));
        }

        @Test
        @DisplayName("Should reject an identifier outside its holder's band, naming the field and the band")
        void shouldRejectAnIdentifierOutsideItsBand() {
            List<Catalogued> records = readCatalogue(MODULE, FILE,
                    specimen(holder("WARN", recordDeclaration("MISPLACED", "PREFIX", "99"))));

            List<String> strays = strays(records);

            assertEquals(List.of(MODULE + ":SpecimenLogMessages.WARN.MISPLACED = 99 (band 100-199)"), strays);
        }

        @Test
        @DisplayName("Should reject a record in a nested holder that owns no band, naming the module, holder and record")
        void shouldRejectARecordInAHolderWithoutABand() {
            List<Catalogued> records = readCatalogue(MODULE, FILE,
                    specimen(holder("DEBUG", recordDeclaration("TRACED", "PREFIX", "5"))));

            List<String> strays = strays(records);

            assertEquals(List.of(MODULE + ":SpecimenLogMessages.DEBUG.TRACED = 5 (holder DEBUG owns no band)"), strays);
        }

        @Test
        @DisplayName("Should fail on a non-literal identifier, naming the file and the field")
        void shouldRejectANonLiteralIdentifier() {
            String source = specimen(holder("ERROR", recordDeclaration("COMPUTED", "PREFIX", "BASE + 1")));

            var thrown = assertThrows(IllegalArgumentException.class, () -> readCatalogue(MODULE, FILE, source));

            assertAll(
                    () -> assertTrue(thrown.getMessage().contains(FILE), thrown::getMessage),
                    () -> assertTrue(thrown.getMessage().contains("COMPUTED"), thrown::getMessage));
        }

        @Test
        @DisplayName("Should fail on a prefix that does not resolve to a literal, naming the file and the field")
        void shouldRejectANonLiteralPrefix() {
            String source = specimen(holder("INFO", recordDeclaration("DERIVED", "prefixFor(\"x\")", "3")));

            var thrown = assertThrows(IllegalArgumentException.class, () -> readCatalogue(MODULE, FILE, source));

            assertAll(
                    () -> assertTrue(thrown.getMessage().contains(FILE), thrown::getMessage),
                    () -> assertTrue(thrown.getMessage().contains("DERIVED"), thrown::getMessage));
        }

        @Test
        @DisplayName("Should fail on a builder call outside the supported declaration form rather than skip it")
        void shouldRejectAnUnsupportedForm() {
            String source = specimen(holder("INFO", recordDeclaration("READ", "PREFIX", "3"),
                    recordDeclaration("LOOSE", "PREFIX", "4").replace("static final", "static")));

            var thrown = assertThrows(IllegalArgumentException.class, () -> readCatalogue(MODULE, FILE, source));

            assertTrue(thrown.getMessage().contains(FILE + " holds 2 LogRecordModel.builder() call(s)"),
                    thrown::getMessage);
        }

        @Test
        @DisplayName("Should fail on a leading-zero identifier, which Java reads as octal, naming the module, catalogue and literal")
        void shouldRejectALeadingZeroIdentifier() {
            String source = specimen(holder("INFO", recordDeclaration("OCTAL", "PREFIX", "010")));

            var thrown = assertThrows(IllegalArgumentException.class, () -> readCatalogue(MODULE, FILE, source));

            assertAll(
                    () -> assertTrue(thrown.getMessage().contains(MODULE + ":" + FILE), thrown::getMessage),
                    () -> assertTrue(thrown.getMessage().contains("OCTAL"), thrown::getMessage),
                    () -> assertTrue(thrown.getMessage().contains("'010'"), thrown::getMessage));
        }

        @Test
        @DisplayName("Should read a single zero and a plain decimal identifier as the compiler does")
        void shouldReadZeroAndPlainDecimalIdentifiers() {
            String source = specimen(recordDeclaration("ZERO", "PREFIX", "0")
                    + holder("INFO", recordDeclaration("TEN", "PREFIX", "10")));

            List<Catalogued> records = readCatalogue(MODULE, FILE, source);

            assertEquals(List.of(
                            new Catalogued(MODULE, "SpecimenLogMessages", "", "ZERO", "Specimen", 0),
                            new Catalogued(MODULE, "SpecimenLogMessages", "INFO", "TEN", "Specimen", 10)),
                    records, "a single 0 and a decimal without a leading zero are read as the compiler reads them");
        }

        private static String specimen(String members) {
            return """
                    package specimen;

                    public final class SpecimenLogMessages {

                        private static final String PREFIX = "Specimen";

                    %s
                    }
                    """.formatted(members);
        }

        private static String holder(String name, String... records) {
            return "public static final class " + name + " {\n" + String.join("\n", records) + "}\n";
        }

        private static String recordDeclaration(String field, String prefixArgument, String identifierArgument) {
            return """
                    public static final LogRecord %s = LogRecordModel.builder()
                            .prefix(%s)
                            .identifier(%s)
                            .template("Specimen record")
                            .build();
                    """.formatted(field, prefixArgument, identifierArgument);
        }
    }

    // --- rules -----------------------------------------------------------------------------------

    /**
     * @param catalogued the records to check, from any number of modules
     * @return one entry per {@code prefix-identifier} allocated more than once, naming every holder
     */
    private static List<String> collisions(List<Catalogued> catalogued) {
        Map<String, List<Catalogued>> byIdentity = new LinkedHashMap<>();
        for (Catalogued entry : catalogued) {
            byIdentity.computeIfAbsent(entry.prefix() + "-" + entry.identifier(), _ -> new ArrayList<>()).add(entry);
        }
        return byIdentity.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " allocated by "
                        + entry.getValue().stream().map(Catalogued::qualifiedName).toList())
                .toList();
    }

    /**
     * Checks every record of a nested holder against {@link #BANDS}. A record in a nested holder that
     * is no key of {@link #BANDS} is a stray too: its level cannot be read from its number, so it is
     * reported rather than skipped. A record on the catalogue itself (empty holder) belongs to no level
     * and is not banded.
     *
     * @param catalogued the records to check
     * @return one entry per record of a nested holder that owns no band, or whose identifier lies
     *         outside the holder's band
     */
    private static List<String> strays(List<Catalogued> catalogued) {
        List<String> strays = new ArrayList<>();
        for (Catalogued entry : catalogued) {
            if (entry.holder().isEmpty()) {
                continue;
            }
            int[] band = BANDS.get(entry.holder());
            if (band == null) {
                strays.add(entry.qualifiedName() + " = " + entry.identifier() + " (holder " + entry.holder()
                        + " owns no band)");
            } else if (entry.identifier() < band[0] || entry.identifier() > band[1]) {
                strays.add(entry.qualifiedName() + " = " + entry.identifier() + " (band " + band[0] + "-" + band[1]
                        + ")");
            }
        }
        return strays;
    }

    // --- the reactor -----------------------------------------------------------------------------

    private static List<Catalogued> reactorRecords() throws Exception {
        return reactorCatalogues().stream().flatMap(catalogue -> catalogue.records().stream()).toList();
    }

    /**
     * Reads every catalogue source of every reactor module. A module without {@code src/main/java}
     * contributes nothing; a catalogue source that cannot be read or parsed fails the run.
     *
     * @return the catalogue sources, module by module, each in a stable order
     */
    private static List<CatalogueSource> reactorCatalogues() throws Exception {
        List<CatalogueSource> catalogues = new ArrayList<>();
        for (String module : reactorModules()) {
            Path sourceRoot = reactorRoot().resolve(module).resolve(MAIN_SOURCES);
            if (!Files.isDirectory(sourceRoot)) {
                continue;
            }
            List<Path> files;
            try (Stream<Path> entries = Files.walk(sourceRoot)) {
                files = entries.filter(path -> path.getFileName().toString().endsWith(CATALOGUE_SOURCE_SUFFIX))
                        .sorted().toList();
            }
            for (Path file : files) {
                catalogues.add(new CatalogueSource(module, file,
                        readCatalogue(module, file.getFileName().toString(), Files.readString(file))));
            }
        }
        return catalogues;
    }

    /**
     * @return the text of every {@code <module>} element of the root {@code pom.xml}, profile-scoped
     *         ones included, in document order
     */
    private static List<String> reactorModules() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        Path pom = reactorRoot().resolve("pom.xml");
        NodeList elements;
        try (Reader reader = Files.newBufferedReader(pom)) {
            elements = factory.newDocumentBuilder().parse(new InputSource(reader))
                    .getElementsByTagNameNS("*", "module");
        }
        List<String> modules = new ArrayList<>();
        for (int index = 0; index < elements.getLength(); index++) {
            modules.add(elements.item(index).getTextContent().strip());
        }
        return modules;
    }

    /** @return the host module's directory: {@code <module>/target/classes -> <module>} */
    private static Path hostModuleDirectory() throws Exception {
        return compiledClassesRoot().resolve("../..").normalize();
    }

    /** @return the host module's name, as the root {@code pom.xml} declares it */
    private static String hostModule() throws Exception {
        return String.valueOf(hostModuleDirectory().getFileName());
    }

    /** @return the reactor root holding the root {@code pom.xml}, one level above the host module */
    private static Path reactorRoot() throws Exception {
        Path root = hostModuleDirectory().resolve("..").normalize();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                () -> "expected the reactor root holding pom.xml, got " + root);
        return root;
    }

    // --- the source reader -----------------------------------------------------------------------

    /**
     * Reads the records one catalogue source declares. A record is a
     * {@code static final LogRecord <field> = LogRecordModel.builder()...;} declaration with exactly
     * one {@code .prefix(..)} and one {@code .identifier(..)} call, inside the top-level class or one
     * of its directly nested holders.
     *
     * @param module   the module declaring the catalogue, for the records and the failure messages
     * @param fileName the source file's name; its stem is the catalogue's class name
     * @param source   the source text
     * @return the records, in source order
     * @throws IllegalArgumentException naming the file, and the field where there is one, when a
     *                                  builder call is not in the supported form, a prefix is not a
     *                                  string literal or a constant holding one, an identifier is not
     *                                  a decimal integer literal (one with a leading zero, which Java
     *                                  reads as octal, is refused — see {@link #resolveIdentifier}), or
     *                                  a record sits outside a level holder
     */
    private static List<Catalogued> readCatalogue(String module, String fileName, String source) {
        String file = module + ":" + fileName;
        String owner = fileName.substring(0, fileName.length() - JAVA_EXTENSION.length());
        List<String> literals = new ArrayList<>();
        String code = sanitise(source, literals, file);
        Map<String, String> constants = new HashMap<>();
        Matcher constant = STRING_CONSTANT.matcher(code);
        while (constant.find()) {
            String value = literals.get(Integer.parseInt(constant.group(2)));
            String previous = constants.putIfAbsent(constant.group(1), value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalArgumentException(file + ": constant " + constant.group(1)
                        + " is declared twice with different values, so a prefix naming it is ambiguous");
            }
        }
        List<Catalogued> records = new ArrayList<>();
        Matcher declaration = RECORD_DECLARATION.matcher(code);
        while (declaration.find()) {
            String where = file + " field " + declaration.group(1);
            String chain = declaration.group(2);
            records.add(new Catalogued(module, owner, holderAt(code, declaration.start(), owner, where),
                    declaration.group(1),
                    resolvePrefix(argumentOf(PREFIX_CALL, chain, "prefix", where), constants, literals, where),
                    resolveIdentifier(argumentOf(IDENTIFIER_CALL, chain, "identifier", where), where)));
        }
        long builders = BUILDER_CALL.matcher(code).results().count();
        if (builders != records.size()) {
            throw new IllegalArgumentException(file + " holds " + builders + " LogRecordModel.builder() call(s) but "
                    + records.size() + " parse as 'static final LogRecord <field> = LogRecordModel.builder()...;'"
                    + " — the reader never skips a form it cannot read");
        }
        return records;
    }

    private static String argumentOf(Pattern call, String chain, String name, String where) {
        List<String> arguments = call.matcher(chain).results().map(match -> match.group(1).strip()).toList();
        if (arguments.size() != 1) {
            throw new IllegalArgumentException(where + ": expected exactly one ." + name
                    + "(..) call with a plain argument, found " + arguments.size());
        }
        return arguments.getFirst();
    }

    private static String resolvePrefix(String argument, Map<String, String> constants, List<String> literals,
            String where) {
        Matcher literal = LITERAL.matcher(argument);
        if (literal.matches()) {
            return literals.get(Integer.parseInt(literal.group(1)));
        }
        if (constants.containsKey(argument)) {
            return constants.get(argument);
        }
        throw new IllegalArgumentException(where + ": prefix argument '" + argument.replace(LITERAL_MARK, '"')
                + "' resolves to no string literal");
    }

    /**
     * Reads an {@code .identifier(..)} argument as the compiler does, for the one form the reader
     * supports: a decimal integer literal, that is a single {@code 0} or digits without a leading zero.
     * A multi-digit literal with a leading zero is octal in Java ({@code 010} compiles to 8), so it is
     * refused rather than read as a decimal or parsed as octal — the reader stays strict, and a record it
     * would number differently from the compiler could collide at run time without failing this test.
     *
     * @param argument the argument text, stripped
     * @param where    the file and field, for the failure message
     * @return the identifier
     * @throws IllegalArgumentException naming the file, the field and the literal when the argument is
     *                                  not an integer literal or has a leading zero
     */
    private static int resolveIdentifier(String argument, String where) {
        if (!INTEGER_LITERAL.matcher(argument).matches()) {
            throw new IllegalArgumentException(where + ": identifier argument '"
                    + argument.replace(LITERAL_MARK, '"') + "' is not an integer literal");
        }
        if (LEADING_ZERO_LITERAL.matcher(argument).matches()) {
            throw new IllegalArgumentException(where + ": identifier argument '" + argument + "' has a leading zero,"
                    + " so Java reads it as an octal literal; this reader reads decimal literals only — write the"
                    + " identifier in decimal");
        }
        return Integer.parseInt(argument);
    }

    /**
     * @return the level holder enclosing {@code position}: the empty string for the top-level class,
     *         or the name of a class nested directly inside it
     */
    private static String holderAt(String code, int position, String owner, String where) {
        List<Scope> scopes = new ArrayList<>();
        Matcher type = TYPE_DECLARATION.matcher(code);
        int nextType = type.find() ? type.start() : -1;
        String pending = null;
        int depth = 0;
        for (int index = 0; index < position; index++) {
            if (index == nextType) {
                pending = Objects.requireNonNullElse(type.group(1), type.group(2));
                nextType = type.find() ? type.start() : -1;
            }
            char current = code.charAt(index);
            if (current == '{') {
                depth++;
                if (pending != null) {
                    scopes.add(new Scope(pending, depth));
                    pending = null;
                }
            } else if (current == '}') {
                if (!scopes.isEmpty() && scopes.getLast().depth() == depth) {
                    scopes.removeLast();
                }
                depth--;
            }
        }
        List<String> names = scopes.stream().map(Scope::name).toList();
        if (names.isEmpty() || !names.getFirst().equals(owner) || names.size() > 2) {
            throw new IllegalArgumentException(where + ": declared in " + names + ", not in " + owner
                    + " or a level holder nested directly inside it");
        }
        return names.size() == 1 ? "" : names.get(1);
    }

    /**
     * Strips comments and replaces every string, char and text-block literal by
     * {@code LITERAL_MARK index LITERAL_MARK}, so braces, semicolons, comment markers and builder
     * calls inside a literal or a comment can no longer be mistaken for code.
     */
    private static String sanitise(String source, List<String> literals, String file) {
        StringBuilder code = new StringBuilder(source.length());
        int index = 0;
        while (index < source.length()) {
            char current = source.charAt(index);
            if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index);
                index = end < 0 ? source.length() : end;
            } else if (source.startsWith("/*", index)) {
                index = closing(source, "*/", index + 2, file) + 2;
                code.append(' ');
            } else if (source.startsWith(TEXT_BLOCK, index)) {
                int end = closing(source, TEXT_BLOCK, index + TEXT_BLOCK.length(), file);
                appendLiteral(code, literals, source.substring(index + TEXT_BLOCK.length(), end));
                index = end + TEXT_BLOCK.length();
            } else if (current == '"' || current == '\'') {
                StringBuilder literal = new StringBuilder();
                int cursor = index + 1;
                while (cursor < source.length() && source.charAt(cursor) != current
                        && source.charAt(cursor) != '\n') {
                    int width = source.charAt(cursor) == '\\' ? 2 : 1;
                    literal.append(source, cursor, Math.min(cursor + width, source.length()));
                    cursor += width;
                }
                if (cursor >= source.length() || source.charAt(cursor) != current) {
                    throw new IllegalArgumentException(file + ": unterminated literal at offset " + index);
                }
                appendLiteral(code, literals, literal.toString());
                index = cursor + 1;
            } else {
                code.append(current);
                index++;
            }
        }
        return code.toString();
    }

    private static int closing(String source, String delimiter, int from, String file) {
        int end = source.indexOf(delimiter, from);
        if (end < 0) {
            throw new IllegalArgumentException(file + ": unterminated '" + delimiter + "' opened before offset " + from);
        }
        return end;
    }

    private static void appendLiteral(StringBuilder code, List<String> literals, String literal) {
        code.append(LITERAL_MARK).append(literals.size()).append(LITERAL_MARK);
        literals.add(literal);
    }

    // --- the reflection walk the reader is calibrated against ------------------------------------

    /** @return every {@link LogRecord} constant of every compiled catalogue in the host module */
    private static List<Catalogued> collectCompiled() throws Exception {
        List<Catalogued> collected = new ArrayList<>();
        for (Class<?> catalogue : discoverCatalogues()) {
            for (Class<?> holder : Stream.concat(Stream.of(catalogue), Stream.of(catalogue.getDeclaredClasses()))
                    .toList()) {
                String holderName = holder.equals(catalogue) ? "" : holder.getSimpleName();
                for (Field field : holder.getDeclaredFields()) {
                    int modifiers = field.getModifiers();
                    if (!Modifier.isStatic(modifiers) || !Modifier.isFinal(modifiers)
                            || !LogRecord.class.isAssignableFrom(field.getType())) {
                        continue;
                    }
                    field.setAccessible(true);
                    LogRecord logRecord = (LogRecord) field.get(null);
                    collected.add(new Catalogued(hostModule(), catalogue.getSimpleName(), holderName,
                            field.getName(), logRecord.getPrefix(), logRecord.getIdentifier()));
                }
            }
        }
        return collected;
    }

    /**
     * @return the binary names of every {@code *LogMessages} source file of the host module, sorted —
     *         the anchor the reflection walk is held to, because it does not derive from the walk
     */
    private static List<String> declaredCatalogueNames() throws Exception {
        Path sourceRoot = hostModuleDirectory().resolve(MAIN_SOURCES);
        assertTrue(Files.isDirectory(sourceRoot), () -> "expected the host module's Java source root, got "
                + sourceRoot);
        try (Stream<Path> entries = Files.walk(sourceRoot)) {
            return entries
                    .filter(path -> path.getFileName().toString().endsWith(CATALOGUE_SOURCE_SUFFIX))
                    .map(path -> sourceRoot.relativize(path).toString().replace(File.separatorChar, '.'))
                    .map(name -> name.substring(0, name.length() - JAVA_EXTENSION.length()))
                    .sorted()
                    .toList();
        }
    }

    /** @return the host module's compiled-classes directory, the root every derivation hangs off */
    private static Path compiledClassesRoot() throws Exception {
        Path classesRoot = Path.of(ApiSheriffLogMessages.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classesRoot),
                () -> "expected the module's compiled classes directory, got " + classesRoot);
        return classesRoot;
    }

    /** @return every compiled {@code *LogMessages} class of the host module, in a stable order */
    private static List<Class<?>> discoverCatalogues() throws Exception {
        Path classesRoot = compiledClassesRoot();
        List<String> binaryNames;
        try (Stream<Path> entries = Files.walk(classesRoot)) {
            binaryNames = entries
                    .filter(path -> path.getFileName().toString().endsWith(CATALOGUE_CLASS_SUFFIX))
                    .map(path -> classesRoot.relativize(path).toString()
                            .replace(File.separatorChar, '.')
                            .replace(".class", ""))
                    .sorted()
                    .toList();
        }
        List<Class<?>> catalogues = new ArrayList<>();
        for (String binaryName : binaryNames) {
            catalogues.add(Class.forName(binaryName));
        }
        return catalogues;
    }
}
