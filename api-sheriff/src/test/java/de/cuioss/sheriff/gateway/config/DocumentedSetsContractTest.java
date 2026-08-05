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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


import de.cuioss.sheriff.gateway.asset.AssetResponseEnvelope;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Binds the operator-facing documents and the bundled JSON Schema back to the code that
 * <em>authoritatively</em> defines the sets they enumerate.
 * <p>
 * <strong>Why this test exists.</strong> Four shipped surfaces restate a set whose definition lives
 * in Java: three of them list the built-in asset extensions carried by
 * {@link AssetResponseEnvelope#builtInExtensions()}, and one lists the inbound-filter mode set
 * carried by {@link SecurityProfile}. A restated list has no mechanical tie to its source, so adding
 * a mapping or a mode leaves every restatement silently stale — the documentation still reads as
 * authoritative while describing a gateway that no longer exists. The project's own review policy
 * treats a hardcoded list mirroring a set defined elsewhere as a defect unless it is derived from
 * that source at build or run time; deriving these at build time would mean generating prose, so
 * this contract test is the sanctioned alternative: the lists stay hand-written and readable, and
 * drift becomes a failing build instead of a silent inaccuracy.
 * <p>
 * <strong>Two assertion strengths, chosen per surface.</strong> A bare enumeration is asserted by
 * <em>set equality</em>, plus the count the prose states in its own sentence — so appending an
 * extension to the list while leaving "21" untouched fails just as loudly as forgetting the list.
 * Every enumeration additionally asserts how many entries it <em>listed</em>, counted before
 * de-duplication: a set cannot see a duplicate, so a document naming one extension twice collapses
 * into exactly the set a correct document produces and would otherwise pass unnoticed.
 * The mode set's per-mode documentation is asserted <em>structurally</em> instead, by requiring each
 * mode a table row of its own: the prose around it is free-form, so equality over it would be
 * brittle, but a row is something the document either has or has not. A bare-word containment check
 * would not do — {@code strict}, {@code lenient} and {@code minimal} are ordinary English
 * adjectives, so a sentence like "with minimal overhead" satisfies one while the mode it names has
 * no entry at all.
 * <p>
 * <strong>Shipped exhibits round-trip through the bundled schema.</strong> The same drift problem has
 * a second shape: a document may restate not a <em>set</em> but a whole <em>configuration example</em>
 * an operator is invited to copy. Such an exhibit has no mechanical tie to the schema that governs
 * the file it exemplifies, so a key the schema never declares reads as supported while the gateway
 * refuses it at boot — the failure mode this guard was written for, where the shipped cookie-mode
 * exhibits declared {@code oidc.session.max_cookie_size} against a schema whose
 * {@code additionalProperties: false} rejected it. The two shipped exhibits are therefore validated
 * against the bundled schema through the same {@code com.networknt} code path {@code ConfigLoader}
 * boots with, and must produce zero errors.
 * <p>
 * <strong>No vacuous pass.</strong> Every extraction is anchored on a literal sentence fragment held
 * in a named constant. When an anchor cannot be located, or locates an empty set, the test fails
 * naming both the document and the anchor rather than asserting over nothing. A guard that stops
 * matching after a rewrite must break loudly; one that quietly matches nothing is worse than absent.
 * The exhibit guards carry the same discipline in two parts: each extracted exhibit must be non-empty
 * and must still carry the budget key whose absence from the schema motivated the guard, and a
 * negative control drives an undeclared {@code oidc.session} key through the identical code path to
 * prove that path can still reject.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Documented sets stay bound to their authoritative source")
class DocumentedSetsContractTest {

    /** The working directory the runner started in — the module root under surefire. */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final Path CONFIGURATION_ADOC = repoRoot().resolve("doc/configuration.adoc");
    private static final Path USER_README_ADOC = repoRoot().resolve("doc/user/README.adoc");
    private static final Path BFF_COOKIE_ADOC = repoRoot().resolve("doc/user/bff-cookie.adoc");

    /** The bundled schema, read off the classpath so the assertion sees the shipped copy. */
    private static final String SCHEMA_RESOURCE = "/schema/gateway.schema.json";

    /**
     * Anchor for {@code doc/configuration.adoc}'s bare extension enumeration. The stated count
     * immediately precedes it ("…own 21-entry extension map"), and the parenthesised, backticked
     * list immediately follows.
     */
    private static final String CONFIG_EXTENSION_ANCHOR = "-entry extension map";

    /**
     * Anchor for {@code doc/user/README.adoc}'s bare extension enumeration ("The 21 built-in
     * extensions -- `html`, … -- always win"). The count precedes it, the backticked list runs from
     * the anchor to the closing {@code --}.
     */
    private static final String README_EXTENSION_ANCHOR = "built-in extensions --";

    /** Anchor for the schema's restatement, which lists the extensions bare inside parentheses. */
    private static final String SCHEMA_EXTENSION_ANCHOR = "an entry naming a built-in extension (";

    /**
     * Anchor for the mode enumeration in {@code doc/configuration.adoc}'s annotated skeleton. The
     * literal appears more than once in the document, so the match is narrowed to the occurrence
     * whose own line also carries a {@code #} comment containing the {@code |}-separated mode list.
     */
    private static final String CONFIG_PROFILE_ANCHOR = "profile: strict";

    /**
     * Anchor for the cookie-mode exhibit in {@code doc/user/bff-cookie.adoc} — the sentence that
     * introduces it. The {@code [source,yaml]} block that follows is the fragment operators copy.
     */
    private static final String COOKIE_EXHIBIT_ANCHOR = "A minimal cookie-based configuration:";

    /**
     * Anchor for the annotated {@code gateway.yaml} skeleton in {@code doc/configuration.adoc} — the
     * section heading it opens. Unlike the cookie exhibit this block is already a whole document.
     */
    private static final String SKELETON_ANCHOR = "== Gateway Configuration";

    /** Opens an AsciiDoc YAML listing; the delimited block itself follows on the next line. */
    private static final String YAML_BLOCK_OPEN = "[source,yaml]";

    /** The AsciiDoc listing-block delimiter enclosing an exhibit's body. */
    private static final String YAML_FENCE = "----";

    /**
     * The key whose absence from the bundled schema motivated these exhibit guards. Only the key is
     * asserted, never its value: the numeric bounds are owned by {@code SealedSessionCookieCodec} and
     * enforced by {@code ConfigValidator}, so restating a numeral here would create the third copy
     * this plan exists to avoid.
     */
    private static final String COOKIE_BUDGET_KEY = "max_cookie_size:";

    /**
     * The undeclared key the {@code oidc.session} negative control injects, and the JSON pointer the
     * validator reports it against. Both must stay in step with the control's own document literal.
     * <p>
     * A rendered error is {@code "<instanceLocation>: <message>"}, and only these two parts are
     * locale-stable: the message wording is emitted in the JVM's default locale (it reads
     * {@code "Eigenschaft 'max_cookie_sizes' ist im Schema nicht definiert…"} on a German JVM), while
     * the pointer and the key name interpolated into it are not translated. The control's predicate
     * is therefore built from these alone — asserting any translated wording would make the guard
     * fail on a machine whose locale merely differs.
     * <p>
     * The pointer names the {@code oidc.session} node rather than the key itself because that is
     * where {@code additionalProperties: false} reports the violation.
     */
    private static final String INJECTED_SESSION_KEY = "max_cookie_sizes";

    /** The JSON pointer the injected key's rejection is reported against; see {@link #INJECTED_SESSION_KEY}. */
    private static final String INJECTED_SESSION_KEY_LOCATION = "/oidc/session";

    /**
     * The single root key the bundled schema requires. The cookie exhibit is a fragment rooted at
     * {@code oidc}, so it is wrapped with this line to become a document the schema can judge.
     */
    private static final String MINIMAL_DOCUMENT_ROOT = "version: 1\n";

    /**
     * Enables the validator's {@code errorMessage} extension, mirroring {@code ConfigLoader}'s
     * registry configuration so these guards judge the exhibits through the boot code path rather
     * than through a differently-configured validator of their own.
     */
    private static final String ERROR_MESSAGE_KEYWORD = "errorMessage";

    private static final Pattern BACKTICKED = Pattern.compile("`([^`]+)`");

    @Test
    @DisplayName("doc/configuration.adoc enumerates exactly the built-in asset extensions, and states their count")
    void configurationAdocEnumeratesTheBuiltInExtensions() throws Exception {
        // Arrange
        String document = read(CONFIGURATION_ADOC);
        int anchor = anchorIndex(document, CONFIG_EXTENSION_ANCHOR, CONFIGURATION_ADOC.toString());

        // Act
        TokenList documented = backtickedTokens(
                parenthesised(document, anchor, CONFIGURATION_ADOC.toString(), CONFIG_EXTENSION_ANCHOR));
        int statedCount = statedCountBefore(document, anchor, CONFIGURATION_ADOC.toString(), CONFIG_EXTENSION_ANCHOR);

        // Assert
        assertExtensionsMatch(documented, statedCount, CONFIGURATION_ADOC.toString());
    }

    @Test
    @DisplayName("doc/user/README.adoc enumerates exactly the built-in asset extensions, and states their count")
    void userReadmeEnumeratesTheBuiltInExtensions() throws Exception {
        // Arrange
        String document = read(USER_README_ADOC);
        int anchor = anchorIndex(document, README_EXTENSION_ANCHOR, USER_README_ADOC.toString());

        // Act
        int listStart = anchor + README_EXTENSION_ANCHOR.length();
        int listEnd = document.indexOf("--", listStart);
        if (listEnd < 0) {
            fail(USER_README_ADOC + ": the extension list after the anchor \"" + README_EXTENSION_ANCHOR
                    + "\" is not terminated by '--'; the anchor no longer describes the document and this"
                    + " guard would otherwise assert over the rest of the file");
        }
        TokenList documented = backtickedTokens(document.substring(listStart, listEnd));
        int statedCount = statedCountBefore(document, anchor, USER_README_ADOC.toString(), README_EXTENSION_ANCHOR);

        // Assert
        assertExtensionsMatch(documented, statedCount, USER_README_ADOC.toString());
    }

    @Test
    @DisplayName("the bundled gateway schema enumerates exactly the built-in asset extensions")
    void gatewaySchemaEnumeratesTheBuiltInExtensions() throws Exception {
        // Arrange
        String schema = readSchema();
        int anchor = anchorIndex(schema, SCHEMA_EXTENSION_ANCHOR, SCHEMA_RESOURCE);

        // Act — the schema lists the extensions bare (no backticks), comma separated
        int listStart = anchor + SCHEMA_EXTENSION_ANCHOR.length();
        int listEnd = schema.indexOf(')', listStart);
        if (listEnd < 0) {
            fail(SCHEMA_RESOURCE + ": the extension list opened by \"" + SCHEMA_EXTENSION_ANCHOR
                    + "\" is never closed; the anchor no longer describes the schema");
        }
        TokenList documented = separatedTokens(schema.substring(listStart, listEnd), ",");

        // Assert — the schema states no count of its own, so only the set and the number of entries
        // it listed to name that set are asserted
        assertExtensionSet(documented, SCHEMA_RESOURCE);
    }

    @Test
    @DisplayName("doc/configuration.adoc's mode enumeration equals the SecurityProfile value set")
    void configurationAdocEnumeratesTheSecurityProfileModes() throws Exception {
        // Arrange
        String document = read(CONFIGURATION_ADOC);
        String modeComment = profileModeComment(document);

        // Act — the raw token count comes back alongside the set: the set collapses duplicates, so
        // only that count can observe a mode the document lists twice
        TokenList documented = separatedTokens(modeComment, "\\|");

        // Assert
        assertFalse(documented.tokens().isEmpty(), CONFIGURATION_ADOC + ": anchor \"" + CONFIG_PROFILE_ANCHOR
                + "\" matched but yielded no modes — the guard would pass vacuously");
        assertEquals(modeNames(), sorted(documented.tokens()),
                CONFIGURATION_ADOC + " enumerates the security_defaults.profile mode set, which is"
                        + " authoritatively defined by SecurityProfile, and has drifted from it");
        assertEquals(SecurityProfile.values().length, documented.rawCount(),
                CONFIGURATION_ADOC + " lists a different number of modes than SecurityProfile declares."
                        + " The count is taken over the raw '|'-separated tokens rather than over the"
                        + " de-duplicated set, so a mode listed twice fails here even though the set"
                        + " equality above still holds");
    }

    @Test
    @DisplayName("doc/configuration.adoc gives every SecurityProfile mode a definition row of its own")
    void configurationAdocDocumentsEverySecurityProfileMode() throws Exception {
        // Arrange — the per-mode text is free-form prose, so the row that introduces it is the
        // structural thing worth asserting
        String document = read(CONFIGURATION_ADOC);

        // Act + Assert
        for (SecurityProfile profile : SecurityProfile.values()) {
            String mode = profile.name().toLowerCase(Locale.ROOT);
            assertTrue(hasModeDefinitionRow(document, mode),
                    CONFIGURATION_ADOC + " has no definition row of its own for the mode '" + mode
                            + "' declared by SecurityProfile. The mode-set table must carry one cell"
                            + " holding exactly \"" + modeDefinitionRow(mode) + "\" per mode, so a newly"
                            + " added mode gets its entry and a removed one has its entry deleted");
        }
    }

    @Test
    @DisplayName("doc/user/bff-cookie.adoc's cookie-mode exhibit validates against the bundled schema")
    void cookieExhibitValidatesAgainstTheBundledSchema() throws Exception {
        // Arrange — the exhibit is a fragment rooted at 'oidc', so it is wrapped into the minimal
        // document the schema accepts; 'version' is the only key it requires at the root
        String exhibit = yamlBlockAfter(read(BFF_COOKIE_ADOC), COOKIE_EXHIBIT_ANCHOR,
                BFF_COOKIE_ADOC.toString());
        assertCarriesBudgetKey(exhibit, BFF_COOKIE_ADOC.toString());

        // Act
        List<String> errors = validationErrors(MINIMAL_DOCUMENT_ROOT + exhibit, BFF_COOKIE_ADOC.toString());

        // Assert
        assertEquals(List.of(), errors, BFF_COOKIE_ADOC + " ships a cookie-mode configuration exhibit"
                + " that the bundled gateway schema rejects. An operator copying it would have the"
                + " boot refused, so either the exhibit or the schema is wrong — they are not allowed"
                + " to disagree");
    }

    @Test
    @DisplayName("doc/configuration.adoc's annotated gateway.yaml skeleton validates against the bundled schema")
    void configurationSkeletonValidatesAgainstTheBundledSchema() throws Exception {
        // Arrange — this exhibit already begins at 'version: 1', so it needs no wrapping
        String skeleton = yamlBlockAfter(read(CONFIGURATION_ADOC), SKELETON_ANCHOR,
                CONFIGURATION_ADOC.toString());
        assertCarriesBudgetKey(skeleton, CONFIGURATION_ADOC.toString());

        // Act
        List<String> errors = validationErrors(skeleton, CONFIGURATION_ADOC.toString());

        // Assert
        assertEquals(List.of(), errors, CONFIGURATION_ADOC + " ships an annotated gateway.yaml skeleton"
                + " that the bundled gateway schema rejects. The skeleton is the reference document"
                + " every other example is derived from, so a key it declares must be a key the schema"
                + " declares");
    }

    @Test
    @DisplayName("the exhibit code path still rejects an undeclared key under oidc.session")
    void undeclaredSessionKeyIsRejectedByTheSameCodePath() {
        // Arrange — the negative control: the same shape as the exhibits above, with one key
        // deliberately misspelled into a key the schema does not declare
        String document = """
                version: 1
                oidc:
                  session:
                    mode: cookie
                    max_cookie_sizes: 4096
                """;

        // Act
        List<String> errors = validationErrors(document, "the oidc.session negative control");

        // Assert
        assertTrue(errors.stream().anyMatch(error -> error.contains(INJECTED_SESSION_KEY_LOCATION)
                        && error.contains(INJECTED_SESSION_KEY)),
                "no reported error points at '" + INJECTED_SESSION_KEY + "' under "
                        + INJECTED_SESSION_KEY_LOCATION + ", so the bundled gateway schema did not reject"
                        + " the key it does not declare. The two exhibit guards above assert zero"
                        + " errors through this same code path, so without a demonstrated rejection they would"
                        + " pass just as happily against a schema that validates nothing at all. The"
                        + " oidc.session node sets additionalProperties: false and must refuse an undeclared key."
                        + " Asserting on the injected key rather than merely on a non-empty list is what keeps"
                        + " this control honest: any unrelated schema error — a new required key at the root or"
                        + " under oidc — would otherwise keep it green after it had stopped proving anything."
                        + " Observed errors: " + errors);
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Asserts a documented extension enumeration against {@link AssetResponseEnvelope} — the set it
     * names, and how many entries it actually listed to name it.
     * <p>
     * The listed-token count is asserted alongside the set because the set alone cannot see a
     * duplicate: an enumeration that names {@code html} twice de-duplicates into exactly the set a
     * correct enumeration produces, so equality holds while the document is wrong. Counting the raw
     * tokens is what makes that failure visible.
     *
     * @param documented the extensions extracted from the document
     * @param document   the document label used in every failure message
     */
    private static void assertExtensionSet(TokenList documented, String document) {
        assertFalse(documented.tokens().isEmpty(), document + ": the anchor matched but yielded no"
                + " extensions — the guard would pass vacuously");
        assertEquals(sorted(AssetResponseEnvelope.builtInExtensions()), sorted(documented.tokens()),
                document + " enumerates the built-in asset extensions, which are authoritatively"
                        + " defined by AssetResponseEnvelope.CONTENT_TYPES, and has drifted from them");
        assertEquals(AssetResponseEnvelope.builtInExtensions().size(), documented.rawCount(),
                document + " lists a different number of extension entries than"
                        + " AssetResponseEnvelope.CONTENT_TYPES declares. The count is taken over the raw"
                        + " tokens rather than over the de-duplicated set, so an extension listed twice"
                        + " fails here even though the set equality above still holds");
    }

    /**
     * Asserts a documented extension enumeration against {@link AssetResponseEnvelope} — the set and
     * its listed-entry count per {@link #assertExtensionSet(TokenList, String)}, plus the count the
     * prose states alongside it, so a list and a count cannot drift apart.
     *
     * @param documented  the extensions extracted from the document
     * @param statedCount the count the document's own sentence claims
     * @param document    the document label used in every failure message
     */
    private static void assertExtensionsMatch(TokenList documented, int statedCount, String document) {
        assertExtensionSet(documented, document);
        assertEquals(AssetResponseEnvelope.builtInExtensions().size(), statedCount,
                document + " states a count that no longer matches"
                        + " AssetResponseEnvelope.CONTENT_TYPES; the list and the stated count must move"
                        + " together");
    }

    /**
     * The body of the first AsciiDoc {@code [source,yaml]} listing block following an anchor.
     *
     * @param document the document text
     * @param anchor   the literal anchor fragment preceding the block
     * @param label    the document label used in every failure message
     * @return the block's body, without the enclosing delimiters
     */
    private static String yamlBlockAfter(String document, String anchor, String label) {
        int from = anchorIndex(document, anchor, label);
        int open = document.indexOf(YAML_BLOCK_OPEN, from);
        if (open < 0) {
            return fail(label + ": no " + YAML_BLOCK_OPEN + " listing follows the anchor \"" + anchor
                    + "\"; the anchor no longer reaches the exhibit this guard protects");
        }
        int fence = document.indexOf(YAML_FENCE, open + YAML_BLOCK_OPEN.length());
        if (fence < 0) {
            return fail(label + ": the " + YAML_BLOCK_OPEN + " listing after the anchor \"" + anchor
                    + "\" opens no '" + YAML_FENCE + "' delimited block");
        }
        int bodyStart = fence + YAML_FENCE.length();
        int close = document.indexOf("\n" + YAML_FENCE, bodyStart);
        if (close < 0) {
            return fail(label + ": the exhibit opened after the anchor \"" + anchor + "\" is never"
                    + " closed by '" + YAML_FENCE + "'; this guard would otherwise assert over the rest"
                    + " of the file");
        }
        return document.substring(bodyStart, close);
    }

    /**
     * Guards an extracted exhibit against passing vacuously: it must be non-empty, and it must still
     * carry {@link #COOKIE_BUDGET_KEY} — the key whose omission from the bundled schema is precisely
     * what these guards exist to catch. An exhibit that no longer declares it would validate cleanly
     * against a schema that never declared it either, which is the silent pass this check refuses.
     *
     * @param exhibit  the extracted exhibit body
     * @param document the document label used in every failure message
     */
    private static void assertCarriesBudgetKey(String exhibit, String document) {
        assertFalse(exhibit.isBlank(), document + ": the extracted exhibit is empty, so validating it"
                + " would assert nothing");
        assertTrue(exhibit.contains(COOKIE_BUDGET_KEY), document + ": the extracted exhibit no longer"
                + " declares '" + COOKIE_BUDGET_KEY + "'. That key is the one this guard was written"
                + " for — without it the exhibit would validate against a schema that omits it too,"
                + " and the drift this test protects against would go unnoticed");
    }

    /**
     * Validates a YAML document against the bundled gateway schema through the same
     * {@code com.networknt} path {@code ConfigLoader} uses at boot, including its
     * {@code errorMessage} registry configuration.
     * <p>
     * The parsed tree is bridged to the validator through its own JSON rendering exactly as the
     * loader does it, so these guards exercise the shipped behaviour rather than an approximation of
     * it.
     *
     * @param yaml     the document to validate
     * @param label    the document label used in every failure message
     * @return the validation messages, empty when the document satisfies the schema
     */
    private static List<String> validationErrors(String yaml, String label) {
        JsonNode tree;
        try {
            tree = new YAMLMapper().readTree(yaml);
        } catch (IOException e) {
            return fail(label + ": the exhibit is not parseable YAML, so the gateway could never load"
                    + " it as written: " + e.getMessage());
        }
        List<String> messages = new ArrayList<>();
        for (Error error : gatewaySchema().validate(tree.toString(), InputFormat.JSON)) {
            messages.add(error.getInstanceLocation() + ": " + error.getMessage());
        }
        return messages;
    }

    /**
     * The bundled gateway schema, compiled off the classpath so the assertion sees the shipped copy.
     *
     * @return the compiled schema
     */
    private static Schema gatewaySchema() {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12,
                builder -> builder.schemaRegistryConfig(SchemaRegistryConfig.builder()
                        .errorMessageKeyword(ERROR_MESSAGE_KEYWORD)
                        .build()));
        try (InputStream in = DocumentedSetsContractTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                return fail("the bundled schema " + SCHEMA_RESOURCE + " is not on the test classpath");
            }
            return registry.getSchema(in);
        } catch (IOException e) {
            return fail("cannot read the bundled schema " + SCHEMA_RESOURCE + ": " + e.getMessage());
        }
    }

    /**
     * One mode's own definition row in the mode-set table — an AsciiDoc cell holding nothing but the
     * backticked mode name.
     *
     * @param mode the lower-cased mode name
     * @return the exact row text the document must carry for that mode
     */
    private static String modeDefinitionRow(String mode) {
        return "| `" + mode + "`";
    }

    /**
     * Whether the document carries {@link #modeDefinitionRow(String)} as a line of its own. The
     * comparison is against the <em>stripped</em> line rather than against the raw document text, so
     * incidental indentation or trailing whitespace cannot decide whether a documented mode counts.
     *
     * @param document the configuration document
     * @param mode     the lower-cased mode name
     * @return {@code true} when some line of the document is exactly that row
     */
    private static boolean hasModeDefinitionRow(String document, String mode) {
        String row = modeDefinitionRow(mode);
        return document.lines().map(String::strip).anyMatch(row::equals);
    }

    /**
     * The {@code #}-comment carrying the {@code |}-separated mode list from the annotated skeleton.
     *
     * @param document the configuration document
     * @return the comment text, without the leading {@code #} and without any trailing parenthetical
     */
    private static String profileModeComment(String document) {
        int from = 0;
        while (true) {
            int anchor = document.indexOf(CONFIG_PROFILE_ANCHOR, from);
            if (anchor < 0) {
                return fail(CONFIGURATION_ADOC + ": no line carrying the anchor \"" + CONFIG_PROFILE_ANCHOR
                        + "\" also carries a '#' comment enumerating the modes with '|'; the anchor no"
                        + " longer describes the document, so this guard cannot assert anything");
            }
            int lineEnd = document.indexOf('\n', anchor);
            int end = lineEnd < 0 ? document.length() : lineEnd;
            int hash = document.indexOf('#', anchor);
            if (hash >= 0 && hash < end) {
                String comment = document.substring(hash + 1, end);
                int parenthesis = comment.indexOf('(');
                String modes = parenthesis < 0 ? comment : comment.substring(0, parenthesis);
                if (modes.indexOf('|') >= 0) {
                    return modes;
                }
            }
            from = anchor + CONFIG_PROFILE_ANCHOR.length();
        }
    }

    /**
     * Locates an anchor, failing with a message naming the document when it is absent.
     *
     * @param text     the document text
     * @param anchor   the literal anchor fragment
     * @param document the document label used in the failure message
     * @return the anchor's index
     */
    private static int anchorIndex(String text, String anchor, String document) {
        int index = text.indexOf(anchor);
        if (index < 0) {
            return fail(document + ": the anchor \"" + anchor + "\" is gone, so this contract guard no"
                    + " longer reaches the enumeration it protects. Restore the sentence, or update the"
                    + " anchor constant in DocumentedSetsContractTest to match the rewritten wording.");
        }
        return index;
    }

    /**
     * The integer immediately preceding an anchor — the count the document's own sentence states.
     *
     * @param text     the document text
     * @param anchor   the anchor's index
     * @param document the document label used in the failure message
     * @param label    the anchor fragment, named in the failure message
     * @return the stated count
     */
    private static int statedCountBefore(String text, int anchor, String document, String label) {
        int end = anchor;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isDigit(text.charAt(start - 1))) {
            start--;
        }
        if (start == end) {
            return fail(document + ": no count precedes the anchor \"" + label + "\"; the sentence must"
                    + " state how many entries it enumerates so a list edit that forgets the count still"
                    + " fails");
        }
        return Integer.parseInt(text.substring(start, end));
    }

    /**
     * The text of the first parenthesised group following an index.
     *
     * @param text     the document text
     * @param from     the index to search from
     * @param document the document label used in the failure message
     * @param label    the anchor fragment, named in the failure message
     * @return the group's contents, without the surrounding parentheses
     */
    private static String parenthesised(String text, int from, String document, String label) {
        int open = text.indexOf('(', from);
        int close = open < 0 ? -1 : text.indexOf(')', open);
        if (open < 0 || close < 0) {
            return fail(document + ": no parenthesised list follows the anchor \"" + label
                    + "\"; the anchor no longer describes the document");
        }
        return text.substring(open + 1, close);
    }

    /**
     * A token enumeration lifted out of a document: the de-duplicated set, alongside the number of
     * non-empty tokens the extraction actually saw.
     * <p>
     * The raw count is carried separately because a {@link Set} cannot observe a duplicate — a
     * document listing an extension twice collapses to exactly the set a correct document produces,
     * so set equality alone passes. Counting before de-duplication is the only thing that sees it.
     *
     * @param tokens   the de-duplicated tokens, in the order the document lists them
     * @param rawCount how many non-empty tokens were read before de-duplication
     */
    private record TokenList(Set<String> tokens, int rawCount) {
    }

    private static TokenList backtickedTokens(String segment) {
        Set<String> tokens = new LinkedHashSet<>();
        int rawCount = 0;
        Matcher matcher = BACKTICKED.matcher(segment);
        while (matcher.find()) {
            String token = matcher.group(1).strip();
            if (!token.isEmpty()) {
                tokens.add(token);
                rawCount++;
            }
        }
        return new TokenList(tokens, rawCount);
    }

    /**
     * Splits a segment on a separator, keeping every non-empty token.
     *
     * @param segment   the raw list text
     * @param separator the separator, as a regular expression
     * @return the de-duplicated tokens and the number of non-empty tokens that produced them
     */
    private static TokenList separatedTokens(String segment, String separator) {
        Set<String> tokens = new LinkedHashSet<>();
        int rawCount = 0;
        for (String token : segment.split(separator)) {
            String stripped = token.strip();
            if (!stripped.isEmpty()) {
                tokens.add(stripped);
                rawCount++;
            }
        }
        return new TokenList(tokens, rawCount);
    }

    private static Set<String> modeNames() {
        Set<String> names = new TreeSet<>();
        for (SecurityProfile profile : SecurityProfile.values()) {
            names.add(profile.name().toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static Set<String> sorted(Set<String> values) {
        return new TreeSet<>(values);
    }

    private static String read(Path document) throws IOException {
        return Files.readString(document);
    }

    private static String readSchema() throws IOException {
        try (InputStream in = DocumentedSetsContractTest.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                return fail("the bundled schema " + SCHEMA_RESOURCE + " is not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The repository root — the nearest ancestor of the working directory that actually holds the
     * {@code doc/} tree these contracts assert against.
     * <p>
     * The search walks up rather than taking a fixed one-level hop because the working directory is
     * not the same everywhere: surefire runs with the module as its working directory, but an IDE
     * runner or an aggregator invocation may use another. Probing for {@code doc/} resolves the root
     * from a property of the tree instead of from an assumption about the runner, and a genuinely
     * unresolvable root then fails naming the directory it started from rather than surfacing later
     * as a {@code NoSuchFileException} on a path nobody asked for.
     */
    private static Path repoRoot() {
        for (Path candidate = MODULE; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("doc"))) {
                return candidate;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + MODULE
                + ": no ancestor of it contains a doc/ directory");
    }
}
