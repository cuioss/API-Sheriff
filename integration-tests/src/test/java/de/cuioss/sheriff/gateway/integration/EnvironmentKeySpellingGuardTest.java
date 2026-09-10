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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.common.utils.StringUtil;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import de.cuioss.sheriff.gateway.tls.ServerTlsDeclarationGate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard proving that every {@code QUARKUS_*} environment key the
 * committed compose descriptors declare is spelled so that it can actually bind at boot.
 * <p>
 * <strong>The failure this exists to catch is silent.</strong> Quarkus reads environment variables
 * through {@code EnvConfigSource}, which publishes a dotted candidate produced by
 * {@link StringUtil#toLowerCaseAndDotted(String)}. That decoder treats a doubled {@code __} as a
 * <em>quote</em> around a map key that needs quoting. A key carrying an odd number of {@code __}
 * groups therefore decodes to an <em>unclosed</em> quote — {@code QUARKUS_TLS_DEFAULT_TRUST__STORE_P12_PATH}
 * becomes {@code quarkus.tls.default.trust."store.p12.path"} with the quote never closed — and no
 * such property resolves. Nothing reports it: the variable is present in the descriptor, the
 * container starts, and the setting an operator believes they configured was never in effect. Ten
 * such pairs shipped in this repository's own integration descriptor before they were removed.
 * <p>
 * <strong>The discriminator is the quote count after decoding, never a ban on {@code __}.</strong>
 * The paired spelling {@code QUARKUS_TLS__MY_IDP__TRUST_STORE_P12_PATH} is legitimate and
 * documented — it names a TLS bucket containing a {@code .} — so a guard that simply refused
 * {@code __} would reject correct configuration. A guard reading the raw compose YAML instead sees
 * no quote characters at all and can conclude nothing. The verdict is therefore delegated to
 * {@link ServerTlsDeclarationGate#canonical(String)}, the same method the production gate reaches
 * its refusal through: sharing it is what stops the guard and the gate drifting into two answers.
 * <p>
 * <strong>The descriptor set is glob-derived, never enumerated.</strong> Both directories are
 * scanned with {@link #DESCRIPTOR_GLOB}, which covers every name Compose itself reads, and each
 * directory is separately held to a floor so one of them falling out of the scan is a failure rather
 * than a quieter pass. A descriptor added later — an overlay, a new
 * deployment variant — is covered the moment it lands rather than when somebody remembers to extend
 * a list here. The Compose merge tags {@code !reset} and {@code !override} are registered on the
 * parser so a descriptor using them parses rather than failing as an unknown tag.
 * <p>
 * Every derived set is fronted by a vacuity guard in the idiom
 * {@code ComposeSampleForwardedTrustWiringTest} establishes: a parser that found nothing would leave
 * the per-key loop asserting nothing while still passing green, which is worse than a red test.
 * <p>
 * It parses the committed descriptors only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class EnvironmentKeySpellingGuardTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    /** The shipped deployment sample, a sibling of this module. */
    private static final Path SAMPLE = MODULE.resolve("../deployment/compose-sample").normalize();

    /**
     * The glob every descriptor directory is scanned with, deliberately not an enumerated list.
     * <p>
     * It covers <strong>both</strong> name forms Docker Compose reads and both YAML extensions:
     * {@code compose.yaml} and {@code compose.yml} are Compose's own default descriptor names, which
     * it prefers over the {@code docker-compose*} spelling, and {@code .yaml} is as valid as
     * {@code .yml} on either. A narrower glob is the silent escape this guard exists to close: a
     * descriptor named {@code compose.yaml} would carry unchecked {@code QUARKUS_*} keys — binding
     * nothing at boot, reporting nothing — while this test stayed green, which is precisely the
     * "covered the moment it lands" promise the class javadoc makes.
     * <p>
     * The two brace groups are siblings rather than nested, which is what keeps this a legal
     * {@code java.nio} glob; the empty alternative in the first group is what makes the
     * {@code docker-} prefix optional.
     */
    private static final String DESCRIPTOR_GLOB = "{docker-,}compose*.{yml,yaml}";

    /** Only Quarkus' own namespace decodes through {@code EnvConfigSource}'s dotted candidate. */
    private static final String QUARKUS_PREFIX = "QUARKUS_";

    /**
     * The floor the glob must clear <strong>in each scanned directory</strong>. Both ship at least
     * one descriptor today, so a directory yielding none means the glob or the working directory
     * moved, not that the repository genuinely stopped shipping one.
     * <p>
     * <strong>Per directory rather than over the concatenation, and that is the whole strength of
     * it.</strong> A combined floor of two is satisfied by the sample directory alone — it ships two
     * descriptors — so the module directory contributing ZERO would still clear it, and the key check
     * would silently stop covering the integration stack while passing green. Holding each directory
     * to its own floor is also what makes a combined count redundant: two directories each proven
     * non-empty already establish everything the sum could.
     */
    private static final int MINIMUM_DESCRIPTORS_PER_DIRECTORY = 1;

    /** Positive control: two {@code __} groups, a legitimately quoted bucket name. */
    private static final String PAIRED_SPELLING = "QUARKUS_TLS__MY_IDP__TRUST_STORE_P12_PATH";

    /** Negative control: one {@code __} group, decoding to an unclosed quote that binds nothing. */
    private static final String UNPAIRED_SPELLING = "QUARKUS_TLS_DEFAULT_TRUST__STORE_P12_PATH";

    @Test
    @DisplayName("every QUARKUS_* key the committed compose descriptors declare decodes to a resolvable name")
    void everyDeclaredQuarkusKeyDecodesToAResolvableName() throws Exception {
        List<Path> descriptors = descriptors();

        DescriptorScan scan = scan(descriptors);

        // Vacuity guards on every derived set — an empty one leaves the loop below asserting nothing.
        assertTrue(scan.services() > 0,
                () -> "no service was parsed out of any of the " + descriptors.size()
                        + " descriptor(s) found by the '" + DESCRIPTOR_GLOB + "' glob, so the key check"
                        + " below would pass without checking anything. Either the parser broke or the"
                        + " descriptors stopped declaring services — until that resolves this is a"
                        + " failure, not a pass.");
        assertTrue(scan.environmentEntries() > 0,
                () -> "the " + scan.services() + " parsed service(s) declared no environment entries at"
                        + " all, so no key could be checked. That is a broken scan rather than a clean"
                        + " result, because these descriptors are how the stack is configured.");
        assertFalse(scan.quarkusKeys().isEmpty(),
                () -> "none of the " + scan.environmentEntries() + " declared environment entries carried"
                        + " the '" + QUARKUS_PREFIX + "' prefix, so the decode assertion below never"
                        + " ran. The descriptors configure Quarkus through exactly this prefix, so an"
                        + " empty result means the scan stopped seeing them.");

        for (DeclaredKey key : scan.quarkusKeys()) {
            String decoded = StringUtil.toLowerCaseAndDotted(key.name());
            assertNotNull(ServerTlsDeclarationGate.canonical(decoded),
                    () -> "the environment key " + key.describe() + " decodes to '" + decoded
                            + "', which carries an UNPAIRED quote and can therefore never resolve to a"
                            + " property. Quarkus reads '__' as a quote around a bucket name that needs"
                            + " quoting, so an odd number of '__' groups leaves the quote open: the"
                            + " variable is present in the descriptor and the container starts, but the"
                            + " setting is never in effect and nothing reports it. Fix the spelling —"
                            + " use a single '_' where no bucket name is being quoted, or close the"
                            + " quote with a second '__' where one is. Do NOT delete this guard.");
        }
    }

    @Test
    @DisplayName("the quote-count discriminator accepts the paired __ spelling and refuses the unpaired one")
    void quoteCountDiscriminatorSeparatesPairedFromUnpairedSpellings() {
        String pairedDecoded = StringUtil.toLowerCaseAndDotted(PAIRED_SPELLING);
        String unpairedDecoded = StringUtil.toLowerCaseAndDotted(UNPAIRED_SPELLING);

        assertAll("the '__' quote-count discriminator, both directions",
                () -> assertNotNull(ServerTlsDeclarationGate.canonical(pairedDecoded),
                        () -> "the paired spelling " + PAIRED_SPELLING + " decodes to '" + pairedDecoded
                                + "' and MUST be accepted: its two '__' groups are a matched pair of"
                                + " quotes around a bucket name containing a '.', which is legitimate,"
                                + " documented configuration. A guard that refuses it has degenerated"
                                + " into a ban on '__' and would reject correct deployments."),
                () -> assertNull(ServerTlsDeclarationGate.canonical(unpairedDecoded),
                        () -> "the unpaired spelling " + UNPAIRED_SPELLING + " decodes to '"
                                + unpairedDecoded + "' and MUST be refused: its single '__' group leaves"
                                + " a quote open, so the property never resolves at boot. If this leg"
                                + " passes, the discriminator has no teeth and the descriptor scan above"
                                + " is green whatever the descriptors contain."));
    }

    /**
     * Lists the committed compose descriptors, derived by <strong>glob</strong> over both directories
     * that ship one rather than from an enumerated list a later descriptor would silently escape.
     * <p>
     * Each directory is held to {@link #MINIMUM_DESCRIPTORS_PER_DIRECTORY} as it is scanned, so a
     * directory that stops yielding descriptors fails here, naming itself, instead of being absorbed
     * by a combined count the other directory already clears.
     *
     * @return the discovered descriptors, ordered by directory then file name
     * @throws IOException when a directory cannot be listed
     */
    private static List<Path> descriptors() throws IOException {
        List<Path> descriptors = new ArrayList<>();
        for (Path directory : List.of(MODULE, SAMPLE)) {
            assertTrue(Files.isDirectory(directory),
                    () -> "cannot scan for compose descriptors: " + directory + " is not a directory."
                            + " It is resolved relative to the module root (" + MODULE + "); if the"
                            + " layout moved, point this guard at the new location rather than"
                            + " dropping it.");
            List<Path> found = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, DESCRIPTOR_GLOB)) {
                for (Path entry : entries) {
                    if (Files.isRegularFile(entry)) {
                        found.add(entry);
                    }
                }
            }
            assertTrue(found.size() >= MINIMUM_DESCRIPTORS_PER_DIRECTORY,
                    () -> "the '" + DESCRIPTOR_GLOB + "' glob found only " + found.size()
                            + " descriptor(s) in " + directory + ", below the per-directory floor of"
                            + " " + MINIMUM_DESCRIPTORS_PER_DIRECTORY + ". This directory ships at"
                            + " least one, so an empty result means the glob or the working directory"
                            + " moved — and the floor is asserted HERE, per directory, because a"
                            + " combined count over both is cleared by the other directory alone while"
                            + " this one silently contributes nothing to the key check below. Do NOT"
                            + " delete this guard.");
            found.sort(Comparator.comparing(path -> path.getFileName().toString()));
            descriptors.addAll(found);
        }
        return descriptors;
    }

    /**
     * Parses every descriptor and collects the counts the vacuity guards read alongside the
     * {@code QUARKUS_*} keys the decode assertion iterates.
     *
     * @param descriptors the descriptors to scan
     * @return the aggregated scan across all descriptors
     * @throws IOException when a descriptor cannot be read
     */
    private static DescriptorScan scan(List<Path> descriptors) throws IOException {
        int services = 0;
        int environmentEntries = 0;
        List<DeclaredKey> quarkusKeys = new ArrayList<>();

        for (Path descriptor : descriptors) {
            Object document;
            try (InputStream in = Files.newInputStream(descriptor)) {
                document = composeParser().load(in);
            }
            Map<?, ?> root = assertInstanceOf(Map.class, document,
                    () -> descriptor + " must parse as a YAML mapping — a compose descriptor that does"
                            + " not is either malformed or was truncated");

            Object declaredServices = root.get("services");
            if (declaredServices == null) {
                // An overlay may legitimately declare none; the global guards catch a total absence.
                continue;
            }
            Map<?, ?> serviceMap = assertInstanceOf(Map.class, declaredServices,
                    () -> "the 'services' block of " + descriptor.getFileName() + " must be a mapping");

            for (Map.Entry<?, ?> service : serviceMap.entrySet()) {
                String serviceName = String.valueOf(service.getKey());
                if (!(service.getValue() instanceof Map<?, ?> definition)) {
                    // A '!reset' service body constructs to null and declares nothing to check.
                    continue;
                }
                services++;
                for (String name : environmentKeys(definition.get("environment"), descriptor, serviceName)) {
                    environmentEntries++;
                    if (name.startsWith(QUARKUS_PREFIX)) {
                        quarkusKeys.add(new DeclaredKey(descriptor, serviceName, name));
                    }
                }
            }
        }
        return new DescriptorScan(services, environmentEntries, quarkusKeys);
    }

    /**
     * Reads the declared variable names out of a service's {@code environment:} block, accepting both
     * spellings Compose allows: the {@code KEY=value} list form these descriptors use, and the
     * mapping form. An entry with no {@code =} is the legal pass-through form ({@code - SOME_VAR}),
     * whose whole text is the name.
     *
     * @param environment the raw parsed {@code environment:} value, possibly {@code null}
     * @param descriptor  the descriptor being scanned, for failure messages
     * @param service     the service being scanned, for failure messages
     * @return the declared names in declaration order
     */
    private static List<String> environmentKeys(Object environment, Path descriptor, String service) {
        if (environment == null) {
            return List.of();
        }
        if (environment instanceof Map<?, ?> mapping) {
            return mapping.keySet().stream().map(String::valueOf).toList();
        }
        List<?> declared = assertInstanceOf(List.class, environment,
                () -> "the '" + service + "' service in " + descriptor.getFileName() + " declares an"
                        + " 'environment:' block that is neither the KEY=value list form nor the mapping"
                        + " form, so this guard cannot read it and would check nothing for that service");

        List<String> names = new ArrayList<>();
        for (Object entry : declared) {
            if (entry == null) {
                continue;
            }
            String text = String.valueOf(entry);
            int separator = text.indexOf('=');
            names.add(separator < 0 ? text : text.substring(0, separator));
        }
        return names;
    }

    /**
     * @return a parser that understands the Compose merge tags, so a descriptor using them is read
     *         rather than rejected as carrying an unknown tag
     */
    private static Yaml composeParser() {
        return new Yaml(new ComposeMergeTagConstructor());
    }

    /** One {@code QUARKUS_*} name as declared by one service of one descriptor. */
    private record DeclaredKey(Path descriptor, String service, String name) {

        /** @return a locator naming where the key was declared, for failure messages */
        private String describe() {
            return "'" + name + "' (service '" + service + "' in " + descriptor.getFileName() + ")";
        }
    }

    /**
     * The aggregate of one scan: the two counts the vacuity guards read, and the keys to check.
     *
     * @param services          how many service definitions were parsed
     * @param environmentEntries how many environment entries those services declared
     * @param quarkusKeys       the subset carrying the Quarkus prefix
     */
    private record DescriptorScan(int services, int environmentEntries, List<DeclaredKey> quarkusKeys) {
    }

    /**
     * A {@link SafeConstructor} that understands the two Compose-Spec merge tags.
     * <p>
     * {@code !reset} removes a value the base descriptor set, which {@code null} models. {@code !override}
     * keeps its value but suppresses list merging, so it constructs exactly as the untagged node would.
     * Registering both is what lets a descriptor that uses them parse here; without it SnakeYAML
     * rejects the document outright and the guard fails for a reason unrelated to key spelling.
     */
    private static final class ComposeMergeTagConstructor extends SafeConstructor {

        private ComposeMergeTagConstructor() {
            super(new LoaderOptions());
            yamlConstructors.put(new Tag("!reset"), new ConstructReset());
            yamlConstructors.put(new Tag("!override"), new ConstructUntagged());
        }

        /** Constructs a {@code !reset} node as {@code null} — the value the base declared is removed. */
        private static final class ConstructReset extends AbstractConstruct {

            @Override
            public Object construct(Node node) {
                return null;
            }
        }

        /**
         * Constructs an {@code !override} node as its untagged equivalent. The children are resolved
         * through the enclosing constructor, never the node itself — re-entering on the same node
         * would trip SnakeYAML's recursion guard.
         */
        private final class ConstructUntagged extends AbstractConstruct {

            @Override
            public Object construct(Node node) {
                return switch (node) {
                    case ScalarNode scalar -> scalar.getValue();
                    case SequenceNode sequence -> {
                        List<Object> values = new ArrayList<>();
                        for (Node child : sequence.getValue()) {
                            values.add(constructObject(child));
                        }
                        yield values;
                    }
                    case MappingNode mapping -> {
                        Map<Object, Object> values = new LinkedHashMap<>();
                        for (NodeTuple tuple : mapping.getValue()) {
                            values.put(constructObject(tuple.getKeyNode()),
                                    constructObject(tuple.getValueNode()));
                        }
                        yield values;
                    }
                    default -> null;
                };
            }
        }
    }
}
