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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.SpecificationVersion;
import de.cuioss.sheriff.gateway.asset.AssetResponseEnvelope;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Binds the operator-facing documents and the bundled JSON Schema back to the code that
 * <em>authoritatively</em> defines the sets they enumerate.
 * <p>
 * <strong>Why this test exists.</strong> Ten shipped surfaces restate a set whose definition lives
 * in Java: three of them list the built-in asset extensions carried by
 * {@link AssetResponseEnvelope#builtInExtensions()}, five list the inbound-filter mode set carried by
 * {@link SecurityProfile} — {@code doc/configuration.adoc} and {@code doc/user/README.adoc} plus the
 * three symmetric {@code profile} enum sites the two bundled JSON Schemas declare — and those two
 * schemas each additionally list the
 * authentication posture set carried by {@link Require}. A restated list has no mechanical tie to its source, so
 * adding a mapping, a mode or a posture leaves every restatement silently stale — the documentation
 * still reads as authoritative while describing a gateway that no longer exists. The project's own review policy
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
 * <strong>The array-key inventory is bound to the schemas themselves.</strong> {@code doc/configuration.adoc}
 * enumerates which configuration keys are arrays — the string-item keys of {@code gateway.yaml}, the
 * string-item keys an endpoint document adds of its own, and the arrays of objects across both — and
 * states a count beside each list. Here the authoritative source is not Java but the two bundled
 * schemas, so the sets are <em>derived structurally</em> from them rather than restated: the walk
 * follows {@code properties}, {@code patternProperties} and object-valued {@code additionalProperties},
 * descends into object items with a {@code []} suffix, and resolves {@code $ref}. A property that
 * references a shared definition names its keys from that property, so {@code auth} reached from an
 * anchor and from a route is the one key the document names once; each definition is counted once,
 * and one definition reached under two different names fails rather than guessing which name the
 * document should use. A negative control injects an array key into a copy of the gateway schema and
 * proves the derivation reaches it through the {@code $ref} and that the documented set no longer
 * matches.
 * <p>
 * <strong>The walk fails closed on shapes it does not model.</strong> A derivation that silently
 * skips what it cannot read produces a smaller inventory that a document written against the larger
 * one still matches, so the walk validates a node at every point it <em>visits</em> one — the
 * derivation root, the node a key declares (ahead of the {@code $ref} branch), the definition a
 * {@code $ref} resolves to, and an array's {@code items} (ahead of classification) — rather than only
 * where it enumerates children. Refused there are: an applicator carrying a subschema the walk does
 * not descend into ({@code allOf}, {@code anyOf}, {@code oneOf}, {@code if}/{@code then}/{@code else},
 * {@code dependentSchemas}, {@code prefixItems}, {@code contains}, {@code unevaluatedProperties},
 * {@code unevaluatedItems}, {@code $dynamicRef}); a union {@code type}; {@code items} written as a
 * Draft-07 tuple array; and — because Draft 2020-12 applies a {@code $ref}'s siblings rather than
 * discarding them — any sibling beside a {@code $ref} that carries schema rather than annotation.
 * {@code resolve} separately refuses a chained and a non-local {@code $ref}. {@code not} and
 * {@code propertyNames} are the two deliberate exemptions: neither can contribute a usable array key,
 * so both are walked past. A {@code oneOf} is the one applicator refused <em>conditionally</em>: it is
 * modelled — walked past — exactly when every branch carries nothing but {@code required} and
 * annotation, because such a branch asserts <em>which</em> keys a document declares and can declare
 * none of its own, and it is refused as before the moment a branch carries anything that could. That
 * is the shape the endpoint schema uses to make {@code match.path_prefix} and {@code match.path}
 * mutually exclusive. One negative control per refusal injects the unmodelled shape into a copy of the
 * gateway schema and asserts the derivation refuses it naming that shape — including a {@code oneOf}
 * whose branch declares a key — and two further controls inject the exemptions and the keyless
 * {@code oneOf} and assert the derived inventory is unchanged, so neither the fail-closed walk can
 * quietly widen into a blanket refusal nor the carve-out into a blanket exemption.
 * <p>
 * <strong>No vacuous pass.</strong> Every extraction is anchored on a named constant — a literal
 * sentence fragment for the prose surfaces, and a JSON pointer for the posture set, which the schema
 * already models as an array and so needs no text scraping. When an anchor cannot be located, or
 * locates an empty set, the test fails naming both the document and the anchor rather than asserting
 * over nothing. A guard that stops
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
    private static final String GATEWAY_SCHEMA_RESOURCE = "/schema/gateway.schema.json";

    /**
     * The bundled endpoint schema. It declares the same {@code auth} block as the gateway schema, so
     * both copies restate the {@link Require} posture set and both are asserted against it.
     */
    private static final String ENDPOINT_SCHEMA_RESOURCE = "/schema/endpoint.schema.json";

    /**
     * JSON pointer to the {@code require} enum array both bundled schemas declare on their shared
     * {@code auth} definition. A pointer is used rather than a text anchor because the value being
     * asserted is a JSON array the schema already models — extracting it structurally cannot be
     * defeated by reformatting, and a moved definition fails naming this pointer.
     */
    private static final String REQUIRE_ENUM_POINTER = "/$defs/auth/properties/require/enum";

    /**
     * JSON pointer to the {@code profile} enum array declared on the shared {@code securityFilter}
     * definition. Both bundled schemas carry that definition, so this one pointer names two of the
     * three sites the mode set is gated at.
     * <p>
     * <strong>Why this guard exists.</strong> The value range of {@code profile} is enforced by the
     * bundled schema rather than by {@code ConfigValidator}, and nothing mechanically tied those
     * enum arrays to {@link SecurityProfile}. A mode added to the enum while one of the three sites
     * was missed produced <em>unreachable configuration</em> — the mode exists, the operator writes
     * it, and the boot refuses it with a value-range violation — while every unit test stayed green.
     * That is the same drift class {@link #REQUIRE_ENUM_POINTER} closes for the posture set.
     */
    private static final String SECURITY_FILTER_PROFILE_ENUM_POINTER =
            "/$defs/securityFilter/properties/profile/enum";

    /**
     * JSON pointer to the third {@code profile} site: the gateway-wide {@code security_defaults}
     * block, which declares its own enum rather than referencing the {@code securityFilter}
     * definition. Asserting it separately is what makes the guard cover all three sites — the two
     * sharing a definition cannot stand in for it.
     */
    private static final String SECURITY_DEFAULTS_PROFILE_ENUM_POINTER =
            "/properties/security_defaults/properties/profile/enum";

    /** The property name every inbound-filter mode site declares; the derivation keys off it. */
    private static final String PROFILE_KEY = "profile";

    /** The JSON Schema keyword carrying a closed value set. */
    private static final String SCHEMA_ENUM = "enum";

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
     * Anchor for {@code doc/user/README.adoc}'s bare mode enumeration ("The mode set is exactly
     * `strict`, …"). The backticked list runs from the anchor to the sentence's own full stop, which
     * no mode name can contain.
     * <p>
     * The operator guide restates the mode set twice — this sentence and the mode table its
     * {@code | `mode`} rows build — and neither restatement was bound to {@link SecurityProfile}
     * until this anchor and {@link #hasModeDefinitionRow(String, String)} were pointed at it. That
     * left the document an operator is most likely to read as the one that could silently describe a
     * mode set the gateway no longer has.
     */
    private static final String README_PROFILE_MODE_SET_ANCHOR = "The mode set is exactly";

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

    /** Where the gateway schema's array-key inventory is derived from: the document root. */
    private static final String GATEWAY_ARRAY_ROOT = "";

    /**
     * Where the endpoint schema's array-key inventory is derived from: the {@code endpoint} block,
     * because the document names an endpoint file's keys relative to it ({@code routes}, not
     * {@code endpoint.routes}).
     */
    private static final String ENDPOINT_ARRAY_ROOT = "/properties/endpoint";

    /**
     * Anchor for the total number of array keys {@code gateway.yaml} declares ("…declares 18 such keys,
     * of which…"). The stated count immediately precedes it.
     */
    private static final String ARRAY_TOTAL_ANCHOR = "such keys, of which";

    /**
     * Anchor for the string-item subset of {@code gateway.yaml}'s array keys. Its count precedes it,
     * and the backticked list runs from the anchor to the next blank line. The sentence wraps inside
     * the anchor in the document, so it is matched whitespace-tolerantly.
     */
    private static final String GATEWAY_STRING_KEYS_ANCHOR = "declare string items and are the usable set:";

    /**
     * Anchor for the string-item keys an endpoint document adds beyond those it shares by name with
     * {@code gateway.yaml}. Its count precedes it, and the backticked list runs to the closing
     * {@code --} of the open block.
     */
    private static final String ENDPOINT_OWN_KEYS_ANCHOR = "further string-item keys of its own:";

    /**
     * Anchor for the arrays of objects across both schemas. Its count precedes it, and the anchor ends
     * with the parenthesis that opens the backticked list.
     */
    private static final String OBJECT_ARRAYS_ANCHOR = "are arrays of *objects* rather than strings (";

    /** Terminates the gateway string-item list: the paragraph break that follows it. */
    private static final String BLANK_LINE = "\n\n";

    /** Terminates the endpoint list: the delimiter closing the AsciiDoc open block it sits in. */
    private static final String OPEN_BLOCK_CLOSE = "\n--";

    /**
     * The negative control's injection point: the {@code properties} of the shared
     * {@code securityFilter} definition, reached in the gateway schema only through a {@code $ref} from
     * an anchor. Injecting there proves the derivation follows references, not merely inline arrays.
     */
    private static final String INJECTED_ARRAY_PARENT_POINTER = "/$defs/securityFilter/properties";

    /** The string-item array key the negative control injects under {@link #INJECTED_ARRAY_PARENT_POINTER}. */
    private static final String INJECTED_ARRAY_KEY = "injected_paths";

    /** The key name the derivation must produce for {@link #INJECTED_ARRAY_KEY}. */
    private static final String INJECTED_ARRAY_NAME = "security_filter." + INJECTED_ARRAY_KEY;

    /**
     * A shipped {@code $ref} node the {@code $ref}-sibling control injects schema beside. It is a
     * property of the document root, so the walk reaches it on every derivation.
     */
    private static final String REF_SIBLING_POINTER = "/properties/allowed_methods";

    /**
     * The shared definition the applicator and exemption controls mutate. The gateway schema reaches
     * it only through a {@code $ref}, so injecting there also proves the validation runs on a node the
     * walk resolved rather than only on nodes written inline.
     */
    private static final String INJECTED_APPLICATOR_POINTER = "/$defs/securityFilter";

    /**
     * The shipped string-item array whose {@code items} the items-validation control mutates. Its
     * {@code items} is written inline, so the control replaces a node the walk classifies directly.
     */
    private static final String INJECTED_ITEMS_PARENT_POINTER = "/properties/tls/properties/alpn";

    /** Where the shipped gateway schema declares {@code not}; see {@link #EXEMPT_NOT}. */
    private static final String NOT_EXEMPTION_POINTER = "/properties/management/properties/port/not";

    /** Where the shipped gateway schema declares {@code propertyNames}; see {@link #EXEMPT_PROPERTY_NAMES}. */
    private static final String PROPERTY_NAMES_EXEMPTION_POINTER =
            "/properties/asset_defaults/properties/content_types/propertyNames";

    /**
     * Names what a document may <em>not</em> contain, so nothing beneath it is a usable key. It is
     * therefore walked past rather than refused — see {@link #UNMODELLED_APPLICATORS}.
     */
    private static final String EXEMPT_NOT = "not";

    /**
     * Constrains property <em>names</em> rather than their values, so nothing beneath it can be an
     * array-typed value. It is therefore walked past rather than refused — see
     * {@link #UNMODELLED_APPLICATORS}.
     */
    private static final String EXEMPT_PROPERTY_NAMES = "propertyNames";

    private static final String APPLICATOR_ONE_OF = "oneOf";
    private static final String APPLICATOR_IF = "if";
    private static final String APPLICATOR_THEN = "then";
    private static final String APPLICATOR_DEPENDENT_SCHEMAS = "dependentSchemas";

    /**
     * Where the shipped endpoint schema declares the {@code oneOf} the keyless carve-out exists for: the
     * route matcher, whose two path forms are mutually exclusive. See {@link #KEYLESS_BRANCH_KEYWORDS}.
     */
    private static final String ONE_OF_CARVE_OUT_POINTER =
            "/properties/endpoint/properties/routes/items/properties/match/" + APPLICATOR_ONE_OF;

    private static final String SCHEMA_REF = "$ref";
    private static final String SCHEMA_REQUIRED = "required";
    private static final String SCHEMA_TYPE = "type";
    private static final String SCHEMA_ITEMS = "items";
    private static final String SCHEMA_PROPERTIES = "properties";
    private static final String SCHEMA_PATTERN_PROPERTIES = "patternProperties";
    private static final String SCHEMA_ADDITIONAL_PROPERTIES = "additionalProperties";
    private static final String TYPE_ARRAY = "array";
    private static final String TYPE_STRING = "string";
    private static final String TYPE_OBJECT = "object";

    /** The name segment standing for a key that is not fixed by the schema (pattern or additional properties). */
    private static final String ANY_KEY = "*";

    /**
     * Applicators the derivation does not model. Each carries a subschema that could introduce an array
     * key the walk would not see, so a schema that starts using one fails the derivation rather than
     * under-reporting.
     * <p>
     * {@link #EXEMPT_NOT} and {@link #EXEMPT_PROPERTY_NAMES} are schema-bearing too and are deliberately
     * absent: neither can contribute a <em>usable</em> array key — {@code not} names what a document may
     * not contain, and {@code propertyNames} constrains property names rather than their values — and
     * the shipped gateway schema declares both, so refusing them would refuse the schema this test
     * derives from. {@code notAndPropertyNamesAreWalkedPastRatherThanRefused} pins that exemption.
     * <p>
     * {@link #APPLICATOR_ONE_OF} is listed and refused like the rest, except where
     * {@link #declaresNoKey(String, JsonNode)} proves the particular {@code oneOf} can declare no key at
     * all; that carve-out is pinned from both sides by
     * {@code combinatorInsideArrayItemsIsRefused} and {@code keylessOneOfIsWalkedPastRatherThanRefused}.
     */
    private static final List<String> UNMODELLED_APPLICATORS = List.of("allOf", "anyOf", APPLICATOR_ONE_OF,
            APPLICATOR_IF, APPLICATOR_THEN, "else", APPLICATOR_DEPENDENT_SCHEMAS, "prefixItems", "contains",
            "unevaluatedProperties", "unevaluatedItems", "$dynamicRef");

    /**
     * The keywords a {@code oneOf} branch may carry for the derivation to model the combinator by walking
     * past it: {@code required} — an assertion over <em>which</em> keys a document declares — plus
     * annotation. None of them is a subschema and none can declare a key, so a branch built from these
     * alone contributes nothing to the inventory and descending into it would derive nothing.
     * <p>
     * The list is an allow-list rather than a deny-list, so it fails closed: a branch carrying any other
     * keyword — a nested applicator, a {@code $ref}, a {@code type}, a {@code properties} — is one that
     * could declare an array key, and {@code oneOf} is then refused exactly as every other unmodelled
     * applicator is.
     */
    private static final Set<String> KEYLESS_BRANCH_KEYWORDS = Set.of(SCHEMA_REQUIRED, "description", "title",
            "$comment", ERROR_MESSAGE_KEYWORD);

    /**
     * The keywords allowed to sit beside a {@code $ref}. Draft 2020-12 <em>applies</em> a {@code $ref}'s
     * siblings rather than discarding them the way Draft-07 did, while the derivation follows the
     * reference alone — so a sibling carrying schema would declare keys the walk never reads. These
     * carry annotation only, and the shipped schemas use no other.
     */
    private static final Set<String> ANNOTATIONS_BESIDE_REF = Set.of(SCHEMA_REF, "description", "title",
            "default", "deprecated", "examples", "readOnly", "writeOnly", "$comment", ERROR_MESSAGE_KEYWORD);

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
        String schema = readSchema(GATEWAY_SCHEMA_RESOURCE);
        int anchor = anchorIndex(schema, SCHEMA_EXTENSION_ANCHOR, GATEWAY_SCHEMA_RESOURCE);

        // Act — the schema lists the extensions bare (no backticks), comma separated
        int listStart = anchor + SCHEMA_EXTENSION_ANCHOR.length();
        int listEnd = schema.indexOf(')', listStart);
        if (listEnd < 0) {
            fail(GATEWAY_SCHEMA_RESOURCE + ": the extension list opened by \"" + SCHEMA_EXTENSION_ANCHOR
                    + "\" is never closed; the anchor no longer describes the schema");
        }
        TokenList documented = separatedTokens(schema.substring(listStart, listEnd), ",");

        // Assert — the schema states no count of its own, so only the set and the number of entries
        // it listed to name that set are asserted
        assertExtensionSet(documented, GATEWAY_SCHEMA_RESOURCE);
    }

    @Test
    @DisplayName("the bundled gateway schema enumerates exactly the Require posture set")
    void gatewaySchemaEnumeratesTheRequirePostures() throws Exception {
        assertRequirePostures(GATEWAY_SCHEMA_RESOURCE);
    }

    @Test
    @DisplayName("the bundled endpoint schema enumerates exactly the Require posture set")
    void endpointSchemaEnumeratesTheRequirePostures() throws Exception {
        assertRequirePostures(ENDPOINT_SCHEMA_RESOURCE);
    }

    @ParameterizedTest(name = "{1} in {0}")
    @MethodSource("profileEnumSites")
    @DisplayName("every profile enum the bundled schemas declare equals the SecurityProfile mode set")
    void everyDeclaredProfileEnumEqualsTheProfileModes(String resource, String pointer) throws Exception {
        assertProfileModes(resource, pointer);
    }

    /**
     * Guards the derivation that feeds {@link #everyDeclaredProfileEnumEqualsTheProfileModes}: the walk
     * must still reach each site known to exist.
     * <p>
     * The three pointers below are a <em>floor</em>, not the asserted population — that population is
     * derived, which is what makes a fourth site covered on the day it is declared. Without this floor
     * a walk that silently stopped reaching a site would leave the parameterized guard above passing
     * over fewer sites than the schemas declare, which is the same blindness in a new place.
     *
     * @throws Exception when a bundled schema cannot be read
     */
    @Test
    @DisplayName("the profile-enum derivation still reaches all three known schema sites")
    void profileEnumDerivationReachesTheKnownSites() throws Exception {
        Set<String> gateway = profileEnumPointers(schemaTree(GATEWAY_SCHEMA_RESOURCE));
        Set<String> endpoint = profileEnumPointers(schemaTree(ENDPOINT_SCHEMA_RESOURCE));

        assertAll("derived profile-enum sites",
                () -> assertTrue(gateway.contains(SECURITY_FILTER_PROFILE_ENUM_POINTER),
                        GATEWAY_SCHEMA_RESOURCE + ": the walk no longer reaches "
                                + SECURITY_FILTER_PROFILE_ENUM_POINTER + ". Derived: " + gateway),
                () -> assertTrue(gateway.contains(SECURITY_DEFAULTS_PROFILE_ENUM_POINTER),
                        GATEWAY_SCHEMA_RESOURCE + ": the walk no longer reaches "
                                + SECURITY_DEFAULTS_PROFILE_ENUM_POINTER + ". Derived: " + gateway),
                () -> assertTrue(endpoint.contains(SECURITY_FILTER_PROFILE_ENUM_POINTER),
                        ENDPOINT_SCHEMA_RESOURCE + ": the walk no longer reaches "
                                + SECURITY_FILTER_PROFILE_ENUM_POINTER + ". Derived: " + endpoint));
    }

    /**
     * Every {@code profile} enum site the two bundled schemas declare, <em>derived</em> by walking each
     * schema rather than listed.
     * <p>
     * Listing the sites is what the earlier form did, and it covered exactly the sites that existed
     * when it was written: a {@code properties/profile/enum} added anywhere else went unasserted and
     * could drift from {@link SecurityProfile} while the suite stayed green. Deriving the population
     * means a new site is covered on the day it is declared. One argument set per site keeps the
     * per-site failure naming the earlier form had — a pooled assertion would report the very drift
     * this guard exists to localise as one anonymous mismatch.
     *
     * @return one {@code (resource, pointer)} pair per declared site
     * @throws IOException when a bundled schema cannot be read
     */
    static Stream<Arguments> profileEnumSites() throws IOException {
        List<Arguments> sites = new ArrayList<>();
        for (String resource : List.of(GATEWAY_SCHEMA_RESOURCE, ENDPOINT_SCHEMA_RESOURCE)) {
            for (String pointer : profileEnumPointers(schemaTree(resource))) {
                sites.add(Arguments.of(resource, pointer));
            }
        }
        return sites.stream();
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
    @DisplayName("doc/user/README.adoc's mode enumeration equals the SecurityProfile value set")
    void userReadmeEnumeratesTheSecurityProfileModes() throws Exception {
        // Arrange
        String document = read(USER_README_ADOC);
        int anchor = anchorIndex(document, README_PROFILE_MODE_SET_ANCHOR, USER_README_ADOC.toString());

        // Act — the sentence names the set inline and ends at its own full stop; the raw token count
        // comes back alongside the set because the set alone cannot observe a mode named twice
        int listStart = anchor + README_PROFILE_MODE_SET_ANCHOR.length();
        int listEnd = document.indexOf('.', listStart);
        if (listEnd < 0) {
            fail(USER_README_ADOC + ": the mode list after the anchor \"" + README_PROFILE_MODE_SET_ANCHOR
                    + "\" is not terminated by a full stop; the anchor no longer describes the document"
                    + " and this guard would otherwise assert over the rest of the file");
        }
        TokenList documented = backtickedTokens(document.substring(listStart, listEnd));

        // Assert
        assertFalse(documented.tokens().isEmpty(), USER_README_ADOC + ": anchor \""
                + README_PROFILE_MODE_SET_ANCHOR + "\" matched but yielded no modes — the guard would"
                + " pass vacuously");
        assertEquals(modeNames(), sorted(documented.tokens()),
                USER_README_ADOC + " enumerates the security_defaults.profile mode set, which is"
                        + " authoritatively defined by SecurityProfile, and has drifted from it. This is the"
                        + " document an operator reads to decide which mode to set, so a mode it omits is one"
                        + " nobody is told exists, and one it names that the enum does not is a value the"
                        + " gateway refuses to boot on");
        assertEquals(SecurityProfile.values().length, documented.rawCount(),
                USER_README_ADOC + " lists a different number of modes than SecurityProfile declares."
                        + " The count is taken over the raw backticked tokens rather than over the"
                        + " de-duplicated set, so a mode listed twice fails here even though the set"
                        + " equality above still holds");
    }

    @Test
    @DisplayName("doc/user/README.adoc gives every SecurityProfile mode a definition row of its own")
    void userReadmeDocumentsEverySecurityProfileMode() throws Exception {
        // Arrange — the per-mode guidance is free-form prose, so the table row that introduces it is
        // the structural thing worth asserting, exactly as it is for doc/configuration.adoc
        String document = read(USER_README_ADOC);

        // Act + Assert
        for (SecurityProfile profile : SecurityProfile.values()) {
            String mode = profile.name().toLowerCase(Locale.ROOT);
            assertTrue(hasModeDefinitionRow(document, mode),
                    USER_README_ADOC + " has no definition row of its own for the mode '" + mode
                            + "' declared by SecurityProfile. The operator guide's mode table must carry"
                            + " one cell holding exactly \"" + modeDefinitionRow(mode) + "\" per mode, so a"
                            + " newly added mode gets the guidance that tells an operator when to choose it"
                            + " and a removed one has its entry deleted");
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

    @Test
    @DisplayName("doc/configuration.adoc names exactly the gateway schema's string-item array keys, and states both counts")
    void configurationAdocEnumeratesTheGatewayArrayKeys() throws Exception {
        // Arrange
        String document = read(CONFIGURATION_ADOC);
        Map<String, ItemKind> derived = deriveArrayKeys(schemaTree(GATEWAY_SCHEMA_RESOURCE), GATEWAY_ARRAY_ROOT,
                GATEWAY_SCHEMA_RESOURCE);

        // Act
        TokenList documented = documentedGatewayStringKeys(document);
        int statedTotal = statedCountBefore(document,
                anchorSpan(document, ARRAY_TOTAL_ANCHOR, CONFIGURATION_ADOC.toString()).start(),
                CONFIGURATION_ADOC.toString(), ARRAY_TOTAL_ANCHOR);
        int statedStringKeys = statedCountBefore(document,
                anchorSpan(document, GATEWAY_STRING_KEYS_ANCHOR, CONFIGURATION_ADOC.toString()).start(),
                CONFIGURATION_ADOC.toString(), GATEWAY_STRING_KEYS_ANCHOR);

        // Assert
        Set<String> derivedStringKeys = keysOfKind(derived, ItemKind.STRING);
        assertFalse(documented.tokens().isEmpty(), CONFIGURATION_ADOC + ": anchor \"" + GATEWAY_STRING_KEYS_ANCHOR
                + "\" matched but yielded no keys — the guard would pass vacuously");
        assertEquals(derivedStringKeys, sorted(documented.tokens()),
                CONFIGURATION_ADOC + " enumerates the string-item array keys of gateway.yaml, which are"
                        + " authoritatively the array-typed nodes of " + GATEWAY_SCHEMA_RESOURCE
                        + ", and has drifted from them");
        assertEquals(derivedStringKeys.size(), documented.rawCount(),
                CONFIGURATION_ADOC + " lists a different number of gateway string-item keys than the schema"
                        + " declares. The count is taken over the raw backticked entries rather than over the"
                        + " de-duplicated set, so a key listed twice fails here even though the set equality"
                        + " above still holds");
        assertEquals(derivedStringKeys.size(), statedStringKeys,
                CONFIGURATION_ADOC + " states a string-item key count that no longer matches "
                        + GATEWAY_SCHEMA_RESOURCE + "; the list and the stated count must move together");
        assertEquals(derived.size(), statedTotal,
                CONFIGURATION_ADOC + " states a total array-key count for gateway.yaml that no longer matches"
                        + " the array-typed nodes of " + GATEWAY_SCHEMA_RESOURCE + ". Derived keys: " + derived);
    }

    @Test
    @DisplayName("doc/configuration.adoc names exactly the endpoint schema's own string-item array keys, and states their count")
    void configurationAdocEnumeratesTheEndpointOwnArrayKeys() throws Exception {
        // Arrange — "own" is what an endpoint document declares beyond the keys it shares by name with
        // gateway.yaml, so both inventories are derived
        String document = read(CONFIGURATION_ADOC);
        Set<String> ownKeys = keysOfKind(deriveArrayKeys(schemaTree(ENDPOINT_SCHEMA_RESOURCE), ENDPOINT_ARRAY_ROOT,
                ENDPOINT_SCHEMA_RESOURCE), ItemKind.STRING);
        ownKeys.removeAll(keysOfKind(deriveArrayKeys(schemaTree(GATEWAY_SCHEMA_RESOURCE), GATEWAY_ARRAY_ROOT,
                GATEWAY_SCHEMA_RESOURCE), ItemKind.STRING));
        Span anchor = anchorSpan(document, ENDPOINT_OWN_KEYS_ANCHOR, CONFIGURATION_ADOC.toString());

        // Act
        TokenList documented = backtickedTokens(segmentUntil(document, anchor.end(), OPEN_BLOCK_CLOSE,
                CONFIGURATION_ADOC.toString(), ENDPOINT_OWN_KEYS_ANCHOR));
        int statedCount = statedCountBefore(document, anchor.start(), CONFIGURATION_ADOC.toString(),
                ENDPOINT_OWN_KEYS_ANCHOR);

        // Assert
        assertFalse(ownKeys.isEmpty(), ENDPOINT_SCHEMA_RESOURCE + " derives no string-item array key beyond"
                + " those gateway.yaml shares, so this guard would pass vacuously against an empty list");
        assertEquals(ownKeys, sorted(documented.tokens()),
                CONFIGURATION_ADOC + " enumerates the string-item array keys an endpoint document adds of its"
                        + " own, which are authoritatively the array-typed nodes of " + ENDPOINT_SCHEMA_RESOURCE
                        + " not shared by name with " + GATEWAY_SCHEMA_RESOURCE + ", and has drifted from them");
        assertEquals(ownKeys.size(), documented.rawCount(),
                CONFIGURATION_ADOC + " lists a different number of endpoint-own string-item keys than the"
                        + " schemas derive. The count is taken over the raw backticked entries, so a key listed"
                        + " twice fails here even though the set equality above still holds");
        assertEquals(ownKeys.size(), statedCount,
                CONFIGURATION_ADOC + " states an endpoint-own key count that no longer matches the schemas;"
                        + " the list and the stated count must move together");
    }

    @Test
    @DisplayName("doc/configuration.adoc names exactly the arrays of objects across both schemas, and states their count")
    void configurationAdocEnumeratesTheObjectItemArrays() throws Exception {
        // Arrange
        String document = read(CONFIGURATION_ADOC);
        Set<String> objectArrays = keysOfKind(deriveArrayKeys(schemaTree(GATEWAY_SCHEMA_RESOURCE),
                GATEWAY_ARRAY_ROOT, GATEWAY_SCHEMA_RESOURCE), ItemKind.OBJECT);
        objectArrays.addAll(keysOfKind(deriveArrayKeys(schemaTree(ENDPOINT_SCHEMA_RESOURCE), ENDPOINT_ARRAY_ROOT,
                ENDPOINT_SCHEMA_RESOURCE), ItemKind.OBJECT));
        Span anchor = anchorSpan(document, OBJECT_ARRAYS_ANCHOR, CONFIGURATION_ADOC.toString());

        // Act
        TokenList documented = backtickedTokens(
                parenthesised(document, anchor.start(), CONFIGURATION_ADOC.toString(), OBJECT_ARRAYS_ANCHOR));
        int statedCount = statedCountBefore(document, anchor.start(), CONFIGURATION_ADOC.toString(),
                OBJECT_ARRAYS_ANCHOR);

        // Assert
        assertFalse(documented.tokens().isEmpty(), CONFIGURATION_ADOC + ": anchor \"" + OBJECT_ARRAYS_ANCHOR
                + "\" matched but yielded no keys — the guard would pass vacuously");
        assertEquals(objectArrays, sorted(documented.tokens()),
                CONFIGURATION_ADOC + " enumerates the arrays of objects, which are authoritatively the"
                        + " object-item array nodes of " + GATEWAY_SCHEMA_RESOURCE + " and "
                        + ENDPOINT_SCHEMA_RESOURCE + ", and has drifted from them");
        assertEquals(objectArrays.size(), documented.rawCount(),
                CONFIGURATION_ADOC + " lists a different number of object-item arrays than the schemas derive."
                        + " The count is taken over the raw backticked entries, so a key listed twice fails here"
                        + " even though the set equality above still holds");
        assertEquals(objectArrays.size(), statedCount,
                CONFIGURATION_ADOC + " states an object-item array count that no longer matches the schemas;"
                        + " the list and the stated count must move together");
    }

    @Test
    @DisplayName("the array-key derivation detects a string-item array key added to the gateway schema")
    void addedSchemaArrayKeyIsDetectedAgainstTheDocumentedSet() throws Exception {
        // Arrange — the negative control: a copy of the shipped schema with one string-item array key
        // injected into a definition the gateway reaches only through a $ref
        String document = read(CONFIGURATION_ADOC);
        TokenList documented = documentedGatewayStringKeys(document);
        int statedTotal = statedCountBefore(document,
                anchorSpan(document, ARRAY_TOTAL_ANCHOR, CONFIGURATION_ADOC.toString()).start(),
                CONFIGURATION_ADOC.toString(), ARRAY_TOTAL_ANCHOR);
        JsonNode shipped = schemaTree(GATEWAY_SCHEMA_RESOURCE);
        JsonNode injected = shipped.deepCopy();
        ObjectNode parent = injected.at(INJECTED_ARRAY_PARENT_POINTER) instanceof ObjectNode node ? node
                : fail(GATEWAY_SCHEMA_RESOURCE + ": nothing resolves at " + INJECTED_ARRAY_PARENT_POINTER
                + ", so the negative control has no definition to inject into. Update"
                + " INJECTED_ARRAY_PARENT_POINTER to a $ref'd definition the gateway schema still declares.");
        ObjectNode injectedArray = parent.objectNode().put(SCHEMA_TYPE, TYPE_ARRAY);
        injectedArray.set(SCHEMA_ITEMS, parent.objectNode().put(SCHEMA_TYPE, TYPE_STRING));
        parent.set(INJECTED_ARRAY_KEY, injectedArray);
        String label = GATEWAY_SCHEMA_RESOURCE + " with '" + INJECTED_ARRAY_NAME + "' injected";

        // Act
        Map<String, ItemKind> shippedKeys = deriveArrayKeys(shipped, GATEWAY_ARRAY_ROOT, GATEWAY_SCHEMA_RESOURCE);
        Map<String, ItemKind> injectedKeys = deriveArrayKeys(injected, GATEWAY_ARRAY_ROOT, label);

        // Assert — the precondition first: against the shipped schema the documented set matches, so any
        // mismatch below is caused by the injection alone
        assertEquals(keysOfKind(shippedKeys, ItemKind.STRING), sorted(documented.tokens()),
                "the negative control needs the shipped schema and the document to agree before injecting;"
                        + " they already disagree, which the gateway array-key guard reports in detail");
        assertEquals(ItemKind.STRING, injectedKeys.get(INJECTED_ARRAY_NAME),
                "the derivation did not report '" + INJECTED_ARRAY_NAME + "' as a string-item array key after it"
                        + " was injected under " + INJECTED_ARRAY_PARENT_POINTER + ". That definition is reached only"
                        + " through a $ref, so a derivation that misses it would silently accept every key added"
                        + " to a shared definition. Derived keys: " + injectedKeys);
        assertNotEquals(keysOfKind(injectedKeys, ItemKind.STRING), sorted(documented.tokens()),
                "the documented gateway string-item key set still equals the derived set after a key was added"
                        + " to the schema, so the gateway array-key guard cannot detect schema drift");
        assertNotEquals(injectedKeys.size(), statedTotal,
                "the documented total array-key count still equals the derived count after a key was added to"
                        + " the schema, so the stated-count assertion cannot detect schema drift");
    }

    @Test
    @DisplayName("the array-key derivation refuses a schema-bearing sibling beside a $ref")
    void schemaBearingSiblingBesideARefIsRefused() throws Exception {
        // Arrange — Draft 2020-12 applies a $ref's siblings rather than discarding them, so a sibling
        // carrying schema declares keys a walk that follows only the reference would never see
        JsonNode injected = schemaTree(GATEWAY_SCHEMA_RESOURCE).deepCopy();
        ObjectNode referencing = mutableAt(injected, REF_SIBLING_POINTER, GATEWAY_SCHEMA_RESOURCE);
        assertTrue(referencing.has(SCHEMA_REF), GATEWAY_SCHEMA_RESOURCE + ": " + REF_SIBLING_POINTER
                + " no longer declares a " + SCHEMA_REF + ", so this control would place its sibling beside"
                + " nothing and would stop exercising the path it was written for");
        referencing.set(SCHEMA_PROPERTIES, stringArrayProperties(referencing));

        // Act + Assert
        assertDerivationRefuses(injected, GATEWAY_SCHEMA_RESOURCE + " with '" + SCHEMA_PROPERTIES
                + "' placed beside the " + SCHEMA_REF + " at " + REF_SIBLING_POINTER, "beside a " + SCHEMA_REF);
    }

    @Test
    @DisplayName("the array-key derivation refuses a conditional applicator")
    void conditionalApplicatorIsRefused() throws Exception {
        // Arrange — 'if'/'then' carries a subschema that can declare an array key, and sits outside the
        // three combinators the derivation started out refusing
        JsonNode injected = schemaTree(GATEWAY_SCHEMA_RESOURCE).deepCopy();
        ObjectNode conditional = mutableAt(injected, INJECTED_APPLICATOR_POINTER, GATEWAY_SCHEMA_RESOURCE);
        conditional.set(APPLICATOR_IF, conditional.objectNode());
        conditional.set(APPLICATOR_THEN, conditional.objectNode()
                .set(SCHEMA_PROPERTIES, stringArrayProperties(conditional)));

        // Act + Assert
        assertDerivationRefuses(injected, GATEWAY_SCHEMA_RESOURCE + " with '" + APPLICATOR_IF + "'/'"
                + APPLICATOR_THEN + "' injected at " + INJECTED_APPLICATOR_POINTER, "uses " + APPLICATOR_IF);
    }

    @Test
    @DisplayName("the array-key derivation refuses dependentSchemas")
    void dependentSchemasApplicatorIsRefused() throws Exception {
        // Arrange — the same gap in a second shape: a subschema applied when a sibling key is present
        JsonNode injected = schemaTree(GATEWAY_SCHEMA_RESOURCE).deepCopy();
        ObjectNode dependent = mutableAt(injected, INJECTED_APPLICATOR_POINTER, GATEWAY_SCHEMA_RESOURCE);
        dependent.set(APPLICATOR_DEPENDENT_SCHEMAS, dependent.objectNode()
                .set(INJECTED_ARRAY_KEY, dependent.objectNode()
                        .set(SCHEMA_PROPERTIES, stringArrayProperties(dependent))));

        // Act + Assert
        assertDerivationRefuses(injected, GATEWAY_SCHEMA_RESOURCE + " with '" + APPLICATOR_DEPENDENT_SCHEMAS
                + "' injected at " + INJECTED_APPLICATOR_POINTER, "uses " + APPLICATOR_DEPENDENT_SCHEMAS);
    }

    @Test
    @DisplayName("the array-key derivation refuses a combinator inside an array's items")
    void combinatorInsideArrayItemsIsRefused() throws Exception {
        // Arrange — an items node reaches the classifier directly, so a combinator there was never seen
        // by the check that refuses one anywhere else
        JsonNode injected = schemaTree(GATEWAY_SCHEMA_RESOURCE).deepCopy();
        ObjectNode items = mutableItems(injected);
        items.set(APPLICATOR_ONE_OF, items.arrayNode()
                .add(items.objectNode().set(SCHEMA_PROPERTIES, stringArrayProperties(items))));

        // Act + Assert
        assertDerivationRefuses(injected, itemsControlLabel(APPLICATOR_ONE_OF), "uses " + APPLICATOR_ONE_OF);
    }

    @Test
    @DisplayName("the array-key derivation refuses a union type inside an array's items")
    void unionTypeInsideArrayItemsIsRefused() throws Exception {
        // Arrange — the union check guarded the node that declares the array, never the items it
        // declares, so a union there classified as 'neither strings nor objects' and left both lists
        JsonNode injected = schemaTree(GATEWAY_SCHEMA_RESOURCE).deepCopy();
        ObjectNode items = mutableItems(injected);
        items.set(SCHEMA_TYPE, items.arrayNode().add(TYPE_STRING).add(TYPE_OBJECT));

        // Act + Assert
        assertDerivationRefuses(injected, itemsControlLabel("a union " + SCHEMA_TYPE), "declares a union type");
    }

    @Test
    @DisplayName("the array-key derivation refuses items written as a tuple array")
    void tupleArrayItemsAreRefused() throws Exception {
        // Arrange — the Draft-07 tuple spelling is not a schema object, so every position it declares
        // went unread while the array itself was still counted
        JsonNode injected = schemaTree(GATEWAY_SCHEMA_RESOURCE).deepCopy();
        ObjectNode parent = mutableAt(injected, INJECTED_ITEMS_PARENT_POINTER, GATEWAY_SCHEMA_RESOURCE);
        parent.set(SCHEMA_ITEMS, parent.arrayNode().add(parent.objectNode().put(SCHEMA_TYPE, TYPE_STRING)));

        // Act + Assert
        assertDerivationRefuses(injected, itemsControlLabel("a tuple"), "as a tuple array");
    }

    @Test
    @DisplayName("the fail-closed walk still walks past 'not' and 'propertyNames' rather than refusing them")
    void notAndPropertyNamesAreWalkedPastRatherThanRefused() throws Exception {
        // Arrange — the two exemptions earn their place only while the shipped schema still uses them,
        // so their presence is asserted before the behaviour that depends on it
        JsonNode shipped = schemaTree(GATEWAY_SCHEMA_RESOURCE);
        assertTrue(shipped.at(NOT_EXEMPTION_POINTER).isObject(), GATEWAY_SCHEMA_RESOURCE + " no longer declares '"
                + EXEMPT_NOT + "' at " + NOT_EXEMPTION_POINTER + ", so the exemption this guard protects is no"
                + " longer exercised by the shipped schema and should be reconsidered rather than kept untested");
        assertTrue(shipped.at(PROPERTY_NAMES_EXEMPTION_POINTER).isObject(), GATEWAY_SCHEMA_RESOURCE + " no longer"
                + " declares '" + EXEMPT_PROPERTY_NAMES + "' at " + PROPERTY_NAMES_EXEMPTION_POINTER + ", so the"
                + " exemption this guard protects is no longer exercised by the shipped schema and should be"
                + " reconsidered rather than kept untested");
        JsonNode injected = shipped.deepCopy();
        ObjectNode definition = mutableAt(injected, INJECTED_APPLICATOR_POINTER, GATEWAY_SCHEMA_RESOURCE);
        definition.set(EXEMPT_NOT, definition.objectNode());
        definition.set(EXEMPT_PROPERTY_NAMES, definition.objectNode().put(SCHEMA_TYPE, TYPE_STRING));

        // Act — the derivation throws on refusal, so completing at all is half of what is asserted here
        Map<String, ItemKind> derived = deriveArrayKeys(injected, GATEWAY_ARRAY_ROOT, GATEWAY_SCHEMA_RESOURCE
                + " with '" + EXEMPT_NOT + "' and '" + EXEMPT_PROPERTY_NAMES + "' injected at "
                + INJECTED_APPLICATOR_POINTER);

        // Assert
        assertEquals(deriveArrayKeys(shipped, GATEWAY_ARRAY_ROOT, GATEWAY_SCHEMA_RESOURCE), derived,
                "injecting '" + EXEMPT_NOT + "' and '" + EXEMPT_PROPERTY_NAMES + "' changed the derived array-key"
                        + " inventory. Neither can contribute a usable key — '" + EXEMPT_NOT + "' names what a"
                        + " document may not contain and '" + EXEMPT_PROPERTY_NAMES + "' constrains property names"
                        + " rather than their values — so the walk must pass over both without refusing them and"
                        + " without deriving anything from them");
    }

    @Test
    @DisplayName("the fail-closed walk walks past a oneOf whose branches declare no key rather than refusing it")
    void keylessOneOfIsWalkedPastRatherThanRefused() throws Exception {
        // Arrange — the carve-out earns its place only while a shipped schema still writes that shape, so
        // its presence is asserted before the behaviour that depends on it
        assertTrue(schemaTree(ENDPOINT_SCHEMA_RESOURCE).at(ONE_OF_CARVE_OUT_POINTER).isArray(),
                ENDPOINT_SCHEMA_RESOURCE + " no longer declares '" + APPLICATOR_ONE_OF + "' at "
                        + ONE_OF_CARVE_OUT_POINTER + ", so the carve-out this guard protects is no longer"
                        + " exercised by a shipped schema and should be reconsidered rather than kept untested");
        // The injection goes into a definition the gateway schema reaches only through a $ref, so the
        // carve-out is exercised on a node the walk resolved. Which keys the branches name is immaterial —
        // a branch carrying nothing but 'required' declares none of its own whatever it names
        JsonNode shipped = schemaTree(GATEWAY_SCHEMA_RESOURCE);
        JsonNode injected = shipped.deepCopy();
        ObjectNode definition = mutableAt(injected, INJECTED_APPLICATOR_POINTER, GATEWAY_SCHEMA_RESOURCE);
        definition.set(APPLICATOR_ONE_OF, keylessOneOf(definition, "profile", "max_body_bytes"));

        // Act — the derivation throws on refusal, so completing at all is half of what is asserted here
        Map<String, ItemKind> derived = deriveArrayKeys(injected, GATEWAY_ARRAY_ROOT, GATEWAY_SCHEMA_RESOURCE
                + " with a keyless '" + APPLICATOR_ONE_OF + "' injected at " + INJECTED_APPLICATOR_POINTER);

        // Assert
        assertEquals(deriveArrayKeys(shipped, GATEWAY_ARRAY_ROOT, GATEWAY_SCHEMA_RESOURCE), derived,
                "injecting a '" + APPLICATOR_ONE_OF + "' whose branches carry nothing but '" + SCHEMA_REQUIRED
                        + "' changed the derived array-key inventory. Such a branch asserts which of the"
                        + " surrounding object's keys a document declares and carries no subschema of its own, so"
                        + " the walk must pass over it without refusing it and without deriving anything from it."
                        + " The matching refusal — a '" + APPLICATOR_ONE_OF + "' whose branch does declare a key —"
                        + " is pinned by combinatorInsideArrayItemsIsRefused");
    }

    // --- helpers ---------------------------------------------------------------------------------

    /**
     * Drives a deliberately unmodelled schema through the array-key derivation and asserts it is
     * refused <em>for the reason under test</em>, not merely that something failed.
     * <p>
     * Every control below injects a shape that the pre-fix walk derived from silently — a smaller
     * inventory, still matching a document written against it. Asserting the refusal message names the
     * construct is what keeps each control bound to its own gap: an unrelated failure elsewhere in the
     * walk would otherwise keep all of them green after they had stopped proving anything.
     *
     * @param injected         the mutated schema
     * @param label            how the mutated schema is named in every failure message
     * @param expectedFragment a fragment the refusal message must carry
     */
    private static void assertDerivationRefuses(JsonNode injected, String label, String expectedFragment) {
        AssertionError refusal = assertThrows(AssertionError.class,
                () -> deriveArrayKeys(injected, GATEWAY_ARRAY_ROOT, label),
                "the array-key derivation accepted " + label + " instead of refusing it, so a schema using that"
                        + " shape would derive a smaller inventory that the documented set still matches");
        assertTrue(refusal.getMessage() != null && refusal.getMessage().contains(expectedFragment),
                "the derivation refused " + label + ", but not for the reason under test: its message carries no \""
                        + expectedFragment + "\". Observed: " + refusal.getMessage());
    }

    /**
     * A {@code properties} object declaring one string-item array key, so an injected shape carries a
     * key the derivation would have to report if it walked into it.
     *
     * @param factory any node of the tree being mutated, used only as a node factory
     * @return the {@code properties} object
     */
    private static ObjectNode stringArrayProperties(ObjectNode factory) {
        ObjectNode array = factory.objectNode().put(SCHEMA_TYPE, TYPE_ARRAY);
        array.set(SCHEMA_ITEMS, factory.objectNode().put(SCHEMA_TYPE, TYPE_STRING));
        ObjectNode properties = factory.objectNode();
        properties.set(INJECTED_ARRAY_KEY, array);
        return properties;
    }

    /**
     * A {@code oneOf} whose branches carry nothing but {@code required} — the shape the shipped endpoint
     * schema writes to make two optional keys mutually exclusive, and the only combinator the derivation
     * models rather than refuses.
     *
     * @param factory any node of the tree being mutated, used only as a node factory
     * @param keys    the key names to require, one branch per name
     * @return the {@code oneOf} branches
     */
    private static ArrayNode keylessOneOf(ObjectNode factory, String... keys) {
        ArrayNode branches = factory.arrayNode();
        for (String key : keys) {
            ObjectNode branch = factory.objectNode();
            branch.set(SCHEMA_REQUIRED, factory.arrayNode().add(key));
            branches.add(branch);
        }
        return branches;
    }

    /**
     * The mutable node a control injects into, failing when the pointer no longer resolves rather than
     * mutating nothing and passing.
     *
     * @param schema  the schema copy being mutated
     * @param pointer the JSON pointer of the node to mutate
     * @param label   the schema label used in the failure message
     * @return the node at that pointer
     */
    private static ObjectNode mutableAt(JsonNode schema, String pointer, String label) {
        return schema.at(pointer) instanceof ObjectNode node ? node
                : fail(label + ": nothing resolves at " + pointer + ", so the control that mutates it has nothing"
                + " to inject into. Update the pointer to a node the schema still declares.");
    }

    /**
     * The {@code items} node of {@link #INJECTED_ITEMS_PARENT_POINTER} in a schema copy.
     *
     * @param schema the schema copy being mutated
     * @return the items node
     */
    private static ObjectNode mutableItems(JsonNode schema) {
        return mutableAt(schema, INJECTED_ITEMS_PARENT_POINTER + "/" + SCHEMA_ITEMS, GATEWAY_SCHEMA_RESOURCE);
    }

    /**
     * How one items-validation control names its mutated schema in failure messages.
     *
     * @param shape what was injected into the items node
     * @return the label
     */
    private static String itemsControlLabel(String shape) {
        return GATEWAY_SCHEMA_RESOURCE + " with " + shape + " injected into the " + SCHEMA_ITEMS + " of "
                + INJECTED_ITEMS_PARENT_POINTER;
    }

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
     * Asserts one bundled schema's {@code auth.require} enum array against {@link Require} — the set
     * it names, and how many entries it listed to name it.
     * <p>
     * The comparison is against {@link Require#toString()} rather than against the constant names
     * because that method <em>is</em> the configuration spelling: the constants are uppercase per Java
     * convention and the schema declares the lowercase form an operator writes. Binding to the
     * spelling the type itself publishes means a renamed constant and a schema that was not updated
     * with it fail here, and so does a {@code toString()} that stops producing the config spelling.
     * <p>
     * The listed-entry count is asserted alongside the set for the same reason the extension guards
     * assert it: a {@link Set} cannot observe a duplicate, so an array naming {@code bearer} twice
     * collapses into exactly the set a correct array produces and would otherwise pass.
     *
     * @param resource the classpath resource of the schema to assert
     * @throws IOException when the bundled schema cannot be read
     */
    private static void assertRequirePostures(String resource) throws IOException {
        TokenList declared = schemaEnumAt(resource, REQUIRE_ENUM_POINTER);

        assertFalse(declared.tokens().isEmpty(), resource + ": " + REQUIRE_ENUM_POINTER + " resolved to"
                + " an empty enum array — the guard would pass vacuously");
        assertEquals(postureNames(), sorted(declared.tokens()),
                resource + " enumerates the auth.require posture set, which is authoritatively defined"
                        + " by the Require enum, and has drifted from it. The schema is what refuses an"
                        + " unknown posture before binding ever reaches the type, so a posture the enum"
                        + " declares and the schema omits is unreachable configuration, and one the schema"
                        + " declares and the enum omits fails the boot bind instead of the validation");
        assertEquals(Require.values().length, declared.rawCount(),
                resource + " lists a different number of postures than Require declares. The count is"
                        + " taken over the raw array entries rather than over the de-duplicated set, so a"
                        + " posture listed twice fails here even though the set equality above still holds");
    }

    /**
     * Asserts one {@code profile} enum array against {@link SecurityProfile} — the set it names, and
     * how many entries it listed to name it.
     * <p>
     * The comparison is against the lower-cased constant names because that is the spelling an
     * operator writes and the schema declares. Each of the three sites is asserted separately and
     * names its own pointer on failure: the whole point of the guard is that a mode reaching two
     * sites and missing the third is <em>unreachable configuration at the third</em>, so an assertion
     * that pooled the sites would report the very drift it exists to localise as a single anonymous
     * mismatch.
     * <p>
     * The listed-entry count rides along for the reason every other enumeration here asserts it: a
     * {@link Set} cannot observe a duplicate, so an array naming {@code strict} twice collapses into
     * exactly the set a correct array produces and would otherwise pass.
     *
     * @param resource the classpath resource of the schema to assert
     * @param pointer  the JSON pointer of the {@code profile} enum array within it
     * @throws IOException when the bundled schema cannot be read
     */
    private static void assertProfileModes(String resource, String pointer) throws IOException {
        TokenList declared = schemaEnumAt(resource, pointer);

        assertFalse(declared.tokens().isEmpty(), resource + ": " + pointer + " resolved to an empty enum"
                + " array — the guard would pass vacuously");
        assertEquals(modeNames(), sorted(declared.tokens()),
                resource + " enumerates the inbound-filter mode set at " + pointer + ", which is"
                        + " authoritatively defined by SecurityProfile, and has drifted from it. The schema is"
                        + " what refuses an unknown mode before binding ever reaches the type, so a mode the"
                        + " enum declares and this site omits is unreachable configuration at this site even"
                        + " when the other sites carry it, and one this site declares and the enum omits fails"
                        + " the boot bind instead of the validation");
        assertEquals(SecurityProfile.values().length, declared.rawCount(),
                resource + " lists a different number of modes at " + pointer + " than SecurityProfile"
                        + " declares. The count is taken over the raw array entries rather than over the"
                        + " de-duplicated set, so a mode listed twice fails here even though the set equality"
                        + " above still holds");
    }

    /**
     * Every JSON pointer at which a schema declares a {@code profile} enum, found by walking the tree.
     * <p>
     * A site is a node carrying {@code properties/profile} whose value declares an {@code enum} array —
     * the shape all three known sites are written in. Requiring the {@code properties} parent is what
     * keeps the walk from claiming an unrelated field that merely happens to be named {@code profile}.
     * The walk descends through every object field and array element, so a site is found wherever it is
     * declared: under {@code $defs}, under {@code properties}, or anywhere a future schema puts one.
     *
     * @param schema the parsed schema tree
     * @return the declared {@code profile} enum pointers, in pointer order
     */
    private static Set<String> profileEnumPointers(JsonNode schema) {
        Set<String> pointers = new TreeSet<>();
        collectProfileEnumPointers(schema, "", pointers);
        return pointers;
    }

    /**
     * Recursive half of {@link #profileEnumPointers(JsonNode)}.
     *
     * @param node     the node being visited
     * @param pointer  the JSON pointer of {@code node}
     * @param pointers the accumulating result
     */
    private static void collectProfileEnumPointers(JsonNode node, String pointer, Set<String> pointers) {
        if (node.isObject()) {
            if (node.path(SCHEMA_PROPERTIES).path(PROFILE_KEY).path(SCHEMA_ENUM).isArray()) {
                pointers.add(pointer + "/" + SCHEMA_PROPERTIES + "/" + PROFILE_KEY + "/" + SCHEMA_ENUM);
            }
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                collectProfileEnumPointers(field.getValue(), pointer + "/" + field.getKey(), pointers);
            }
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collectProfileEnumPointers(node.get(index), pointer + "/" + index, pointers);
            }
        }
    }

    /**
     * The enum array a bundled schema declares at a pointer, read structurally rather than scraped:
     * the schema already models these as arrays, so extraction cannot be defeated by reformatting and
     * a moved declaration fails naming the pointer that stopped resolving.
     *
     * @param resource the classpath resource of the schema to read
     * @param pointer  the JSON pointer of the enum array
     * @return the de-duplicated values and the number of entries that produced them
     * @throws IOException when the bundled schema cannot be read
     */
    private static TokenList schemaEnumAt(String resource, String pointer) throws IOException {
        JsonNode array = new ObjectMapper().readTree(readSchema(resource)).at(pointer);
        if (!array.isArray()) {
            return fail(resource + ": nothing resolves at " + pointer + ", so this contract guard no"
                    + " longer reaches the enumeration it protects. Restore the declaration, or update the"
                    + " matching pointer constant in DocumentedSetsContractTest to match where the schema"
                    + " now declares it.");
        }
        Set<String> tokens = new LinkedHashSet<>();
        int rawCount = 0;
        for (JsonNode value : array) {
            tokens.add(value.asText());
            rawCount++;
        }
        return new TokenList(tokens, rawCount);
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
        try (InputStream in = DocumentedSetsContractTest.class.getResourceAsStream(GATEWAY_SCHEMA_RESOURCE)) {
            if (in == null) {
                return fail("the bundled schema " + GATEWAY_SCHEMA_RESOURCE + " is not on the test classpath");
            }
            return registry.getSchema(in);
        } catch (IOException e) {
            return fail("cannot read the bundled schema " + GATEWAY_SCHEMA_RESOURCE + ": " + e.getMessage());
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
     * The gateway string-item array keys {@code doc/configuration.adoc} lists: the backticked entries
     * from {@link #GATEWAY_STRING_KEYS_ANCHOR} to the paragraph break that ends the list.
     *
     * @param document the configuration document
     * @return the de-duplicated keys and the number of entries that produced them
     */
    private static TokenList documentedGatewayStringKeys(String document) {
        Span anchor = anchorSpan(document, GATEWAY_STRING_KEYS_ANCHOR, CONFIGURATION_ADOC.toString());
        return backtickedTokens(segmentUntil(document, anchor.end(), BLANK_LINE, CONFIGURATION_ADOC.toString(),
                GATEWAY_STRING_KEYS_ANCHOR));
    }

    /**
     * Where an anchor sits in a document, matched with any run of whitespace standing for each space
     * in the anchor — AsciiDoc prose wraps freely, so a sentence fragment may span a line break.
     *
     * @param start the index of the anchor's first character
     * @param end   the index just past the anchor's last character
     */
    private record Span(int start, int end) {
    }

    /**
     * Locates an anchor whitespace-tolerantly, failing with a message naming the document when it is
     * absent.
     *
     * @param text     the document text
     * @param anchor   the literal anchor fragment; each space matches any run of whitespace
     * @param document the document label used in the failure message
     * @return where the first occurrence of the anchor starts and ends
     */
    private static Span anchorSpan(String text, String anchor, String document) {
        String regex = Arrays.stream(anchor.split(" ")).map(Pattern::quote).collect(Collectors.joining("\\s+"));
        Matcher matcher = Pattern.compile(regex).matcher(text);
        if (!matcher.find()) {
            return fail(document + ": the anchor \"" + anchor + "\" is gone, so this contract guard no"
                    + " longer reaches the enumeration it protects. Restore the sentence, or update the"
                    + " anchor constant in DocumentedSetsContractTest to match the rewritten wording.");
        }
        return new Span(matcher.start(), matcher.end());
    }

    /**
     * The text from an index up to the next occurrence of a terminator.
     *
     * @param text       the document text
     * @param from       the index the segment starts at
     * @param terminator the literal that ends the segment
     * @param document   the document label used in the failure message
     * @param label      the anchor fragment, named in the failure message
     * @return the segment, without the terminator
     */
    private static String segmentUntil(String text, int from, String terminator, String document, String label) {
        int end = text.indexOf(terminator, from);
        if (end < 0) {
            return fail(document + ": the list after the anchor \"" + label + "\" is not terminated by "
                    + terminator.replace("\n", "\\n") + "; the anchor no longer describes the document and this"
                    + " guard would otherwise assert over the rest of the file");
        }
        return text.substring(from, end);
    }

    /** What an array-typed schema node declares as its items. */
    private enum ItemKind {
        /** Items are strings, directly or through a referenced definition. */
        STRING,
        /** Items are objects, whose own array keys are derived with a {@code []} suffix. */
        OBJECT,
        /** Anything else, including an array declaring no items at all. */
        OTHER
    }

    /**
     * A bundled schema parsed into a tree, read off the classpath.
     *
     * @param resource the classpath resource of the schema to read
     * @return the parsed schema
     * @throws IOException when the resource cannot be read or parsed
     */
    private static JsonNode schemaTree(String resource) throws IOException {
        return new ObjectMapper().readTree(readSchema(resource));
    }

    /**
     * Derives every array-typed key reachable from a node of a schema, named the way
     * {@code doc/configuration.adoc} names them.
     *
     * @param schema       the whole schema, against which every {@code $ref} resolves
     * @param startPointer the JSON pointer of the node the derivation starts at; key names are relative to it
     * @param label        the schema label used in every failure message
     * @return each derived key name mapped to what its items are, sorted by name
     */
    private static Map<String, ItemKind> deriveArrayKeys(JsonNode schema, String startPointer, String label) {
        JsonNode start = schema.at(startPointer);
        if (start.isMissingNode()) {
            return fail(label + ": nothing resolves at the derivation root '" + startPointer + "', so the"
                    + " array-key inventory cannot be derived from it");
        }
        assertModelledShape(start, "", label);
        Map<String, ItemKind> keys = new TreeMap<>();
        walkChildren(schema, start, "", keys, new HashMap<>(), label);
        if (keys.isEmpty()) {
            return fail(label + ": no array-typed key is derivable from '" + startPointer + "', so every"
                    + " assertion over the inventory would pass vacuously");
        }
        return keys;
    }

    /**
     * Walks the keys an object schema declares: its {@code properties}, its {@code patternProperties}
     * and an object-valued {@code additionalProperties}. Keys not fixed by the schema are named
     * {@link #ANY_KEY}.
     * <p>
     * The node reaching here has already been through {@link #assertModelledShape(JsonNode, String, String)}
     * — at the derivation root, on entry to {@link #walkNode}, or as a validated {@code items} node — so
     * this method walks a shape the derivation is known to model and adds no check of its own.
     *
     * @param schema   the whole schema
     * @param node     the object schema node
     * @param name     the key name of the node, empty at the derivation root
     * @param keys     the inventory being derived
     * @param followed each referenced definition already followed, mapped to the name it was followed under
     * @param label    the schema label used in every failure message
     */
    private static void walkChildren(JsonNode schema, JsonNode node, String name, Map<String, ItemKind> keys,
            Map<String, String> followed, String label) {
        for (Map.Entry<String, JsonNode> property : node.path(SCHEMA_PROPERTIES).properties()) {
            walkKey(schema, property.getValue(), property.getKey(), qualified(name, property.getKey()), keys,
                    followed, label);
        }
        for (Map.Entry<String, JsonNode> pattern : node.path(SCHEMA_PATTERN_PROPERTIES).properties()) {
            walkKey(schema, pattern.getValue(), ANY_KEY, qualified(name, ANY_KEY), keys, followed, label);
        }
        JsonNode additional = node.path(SCHEMA_ADDITIONAL_PROPERTIES);
        if (additional.isObject()) {
            walkKey(schema, additional, ANY_KEY, qualified(name, ANY_KEY), keys, followed, label);
        }
    }

    /**
     * Walks one declared key. A key that references a shared definition names its descendants from the
     * key itself rather than from its full path, so a definition reached from several places yields one
     * set of names; the definition is walked once, and reaching it again under a different name fails.
     * <p>
     * The key's own node is validated <em>before</em> the {@code $ref} branch, so a shape the derivation
     * does not model is refused whether it is written inline or beside a reference — the walk never
     * reaches a node it has not first agreed it understands.
     *
     * @param schema    the whole schema
     * @param node      the key's schema node
     * @param key       the key's own name
     * @param qualified the key's name qualified by its parents
     * @param keys      the inventory being derived
     * @param followed  each referenced definition already followed, mapped to the name it was followed under
     * @param label     the schema label used in every failure message
     */
    private static void walkKey(JsonNode schema, JsonNode node, String key, String qualified,
            Map<String, ItemKind> keys, Map<String, String> followed, String label) {
        assertModelledShape(node, qualified, label);
        if (!node.has(SCHEMA_REF)) {
            walkNode(schema, node, qualified, keys, followed, label);
            return;
        }
        assertRefSiblingsCarryNoSchema(node, qualified, label);
        String pointer = node.get(SCHEMA_REF).asText();
        String previous = followed.putIfAbsent(pointer, key);
        if (previous == null) {
            walkNode(schema, resolve(schema, pointer, label), key, keys, followed, label);
        } else if (!previous.equals(key)) {
            fail(label + ": the definition " + pointer + " is referenced as both '" + previous + "' and '" + key
                    + "', so the array keys it declares have no single documented name. Either give the"
                    + " references one name or teach DocumentedSetsContractTest how the document names them");
        }
    }

    /**
     * Records a node when it is array-typed — descending into object items with a {@code []} suffix —
     * and otherwise walks its children.
     * <p>
     * The node is validated on entry, and an array's {@code items} is validated before it is classified:
     * a shape the derivation does not model is refused ahead of the array branch rather than reaching
     * {@link #kindOf(JsonNode)}, where it would classify as {@link ItemKind#OTHER} and quietly leave both
     * documented lists.
     *
     * @param schema   the whole schema
     * @param node     the (already resolved) schema node
     * @param name     the node's key name
     * @param keys     the inventory being derived
     * @param followed each referenced definition already followed, mapped to the name it was followed under
     * @param label    the schema label used in every failure message
     */
    private static void walkNode(JsonNode schema, JsonNode node, String name, Map<String, ItemKind> keys,
            Map<String, String> followed, String label) {
        assertModelledShape(node, name, label);
        if (!TYPE_ARRAY.equals(node.path(SCHEMA_TYPE).asText())) {
            walkChildren(schema, node, name, keys, followed, label);
            return;
        }
        JsonNode items = node.path(SCHEMA_ITEMS);
        if (items.isObject() && items.has(SCHEMA_REF)) {
            assertRefSiblingsCarryNoSchema(items, qualified(name, SCHEMA_ITEMS), label);
            items = resolve(schema, items.get(SCHEMA_REF).asText(), label);
        }
        assertModelledItems(items, name, label);
        ItemKind kind = kindOf(items);
        if (keys.put(name, kind) != null) {
            fail(label + ": two array-typed nodes derive the same key name '" + name + "' without sharing a"
                    + " definition, so the document could not tell them apart");
        }
        if (kind == ItemKind.OBJECT) {
            walkChildren(schema, items, name + "[]", keys, followed, label);
        }
    }

    /**
     * Refuses a schema node whose shape the derivation does not model, before the walk acts on it.
     * <p>
     * This runs at every point the walk <em>visits</em> a node — the derivation root, the node a key
     * declares (ahead of the {@code $ref} branch), the definition a {@code $ref} resolves to, and an
     * array's {@code items} (ahead of classification). Checking only where children are enumerated is
     * what let a shape slip through: a node whose keys are never enumerated was never inspected.
     *
     * @param node  the schema node about to be walked
     * @param name  the node's key name, empty at the derivation root
     * @param label the schema label used in every failure message
     */
    private static void assertModelledShape(JsonNode node, String name, String label) {
        for (String applicator : UNMODELLED_APPLICATORS) {
            if (node.has(applicator) && !declaresNoKey(applicator, node.get(applicator))) {
                fail(label + ": '" + qualified(name, applicator) + "' uses " + applicator + ", which the array-key"
                        + " derivation does not model; extend DocumentedSetsContractTest before relying on it, or"
                        + " the inventory would silently miss any array key declared inside it");
            }
        }
        JsonNode type = node.path(SCHEMA_TYPE);
        if (type.isArray()) {
            fail(label + ": '" + name + "' declares a union type " + type + ", which the array-key derivation"
                    + " does not model; it could be an array the inventory silently misses");
        }
    }

    /**
     * Whether an applicator's value provably declares no key, so the derivation models it by walking past
     * it rather than refusing it.
     * <p>
     * Only {@link #APPLICATOR_ONE_OF} qualifies, and only when it is a non-empty array of non-empty
     * objects built exclusively from {@link #KEYLESS_BRANCH_KEYWORDS}. Such a branch expresses a
     * constraint over keys declared elsewhere — the mutual exclusion the endpoint schema writes over
     * {@code match.path_prefix} and {@code match.path} — and carries no subschema of its own, so nothing
     * inside it can reach the inventory and nothing is missed by not descending. Every other applicator,
     * and every {@code oneOf} carrying a branch that could declare a key, is refused unchanged.
     * <p>
     * The narrowing is deliberately confined to {@code oneOf}: an empty subschema under {@code if},
     * {@code then} or any other combinator would satisfy the same emptiness test while its sibling
     * carried the keys, so those stay refused on sight.
     *
     * @param applicator the applicator keyword
     * @param value      the keyword's value
     * @return {@code true} only for a {@code oneOf} no branch of which can declare a key
     */
    private static boolean declaresNoKey(String applicator, JsonNode value) {
        if (!APPLICATOR_ONE_OF.equals(applicator) || !value.isArray() || value.isEmpty()) {
            return false;
        }
        for (JsonNode branch : value) {
            if (!branch.isObject() || branch.isEmpty()) {
                return false;
            }
            for (Map.Entry<String, JsonNode> keyword : branch.properties()) {
                if (!KEYLESS_BRANCH_KEYWORDS.contains(keyword.getKey())) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Refuses a {@code $ref} that carries a schema-bearing sibling.
     * <p>
     * Draft 2020-12 applies a {@code $ref}'s siblings rather than discarding them, while this derivation
     * follows the reference alone — so any sibling beyond {@link #ANNOTATIONS_BESIDE_REF} declares schema
     * the walk would never read.
     *
     * @param node  the referencing node
     * @param name  the node's key name
     * @param label the schema label used in every failure message
     */
    private static void assertRefSiblingsCarryNoSchema(JsonNode node, String name, String label) {
        for (Map.Entry<String, JsonNode> sibling : node.properties()) {
            if (!ANNOTATIONS_BESIDE_REF.contains(sibling.getKey())) {
                fail(label + ": '" + name + "' places '" + sibling.getKey() + "' beside a " + SCHEMA_REF
                        + ". Draft 2020-12 applies a " + SCHEMA_REF + "'s siblings rather than discarding them,"
                        + " and the array-key derivation follows only the reference, so any array key that"
                        + " sibling declares would be missed");
            }
        }
    }

    /**
     * Refuses an array's (already resolved) {@code items} when it is not a schema object the derivation
     * models, and validates it like any other visited node when it is.
     * <p>
     * A {@code items} written as a tuple array is the Draft-07 spelling: each position carries its own
     * schema, and none of them is read here. An {@code items} that is missing or boolean constrains
     * nothing and names no key, so it stays {@link ItemKind#OTHER} rather than failing.
     *
     * @param items the items node
     * @param name  the array node's key name
     * @param label the schema label used in every failure message
     */
    private static void assertModelledItems(JsonNode items, String name, String label) {
        if (items.isArray()) {
            fail(label + ": '" + name + "' declares its items as a tuple array, which the array-key derivation"
                    + " does not model; each position carries its own schema, so an array key declared in one"
                    + " of them would be missed");
        }
        if (items.isObject()) {
            assertModelledShape(items, qualified(name, SCHEMA_ITEMS), label);
        }
    }

    /**
     * Classifies an array's (already resolved and validated) items node.
     *
     * @param items the items node, missing when the array declares none
     * @return what the items are
     */
    private static ItemKind kindOf(JsonNode items) {
        String type = items.path(SCHEMA_TYPE).asText();
        if (TYPE_STRING.equals(type)) {
            return ItemKind.STRING;
        }
        if (TYPE_OBJECT.equals(type) || items.has(SCHEMA_PROPERTIES)) {
            return ItemKind.OBJECT;
        }
        return ItemKind.OTHER;
    }

    /**
     * Resolves a local {@code $ref}. A non-local reference, a dangling pointer and a reference to
     * another reference each fail, because following any of them wrongly would shrink the inventory.
     *
     * @param schema  the whole schema
     * @param pointer the {@code $ref} value
     * @param label   the schema label used in every failure message
     * @return the referenced definition
     */
    private static JsonNode resolve(JsonNode schema, String pointer, String label) {
        if (!pointer.startsWith("#")) {
            return fail(label + ": the reference " + pointer + " is not local to the schema, which the array-key"
                    + " derivation does not follow");
        }
        JsonNode target = schema.at(pointer.substring(1));
        if (target.isMissingNode()) {
            return fail(label + ": the reference " + pointer + " resolves to nothing");
        }
        if (target.has(SCHEMA_REF)) {
            return fail(label + ": the reference " + pointer + " points at another reference, which the array-key"
                    + " derivation does not follow");
        }
        return target;
    }

    private static String qualified(String parent, String key) {
        return parent.isEmpty() ? key : parent + "." + key;
    }

    /**
     * The derived key names whose items are of one kind.
     *
     * @param keys the derived inventory
     * @param kind the item kind to select
     * @return a mutable, sorted set of the matching names
     */
    private static Set<String> keysOfKind(Map<String, ItemKind> keys, ItemKind kind) {
        Set<String> names = new TreeSet<>();
        keys.forEach((name, itemKind) -> {
            if (itemKind == kind) {
                names.add(name);
            }
        });
        return names;
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

    /**
     * The configuration spellings {@link Require} publishes, sorted for comparison.
     *
     * @return the lowercase posture values as an operator writes them
     */
    private static Set<String> postureNames() {
        Set<String> names = new TreeSet<>();
        for (Require posture : Require.values()) {
            names.add(posture.toString());
        }
        return names;
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

    /**
     * One bundled schema's text, read off the classpath so every assertion sees the shipped copy.
     *
     * @param resource the classpath resource of the schema to read
     * @return the schema document
     * @throws IOException when the resource cannot be read
     */
    private static String readSchema(String resource) throws IOException {
        try (InputStream in = DocumentedSetsContractTest.class.getResourceAsStream(resource)) {
            if (in == null) {
                return fail("the bundled schema " + resource + " is not on the test classpath");
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
