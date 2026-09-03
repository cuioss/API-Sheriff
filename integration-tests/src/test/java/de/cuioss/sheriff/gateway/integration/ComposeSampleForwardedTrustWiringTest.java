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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

import de.cuioss.sheriff.gateway.config.load.ConfigLoader;
import de.cuioss.sheriff.gateway.config.load.EnvSecretResolver;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard over the committed compose sample's forwarded-trust wiring.
 * <p>
 * The sample's {@code gateway.yaml} declares {@code forwarded.trusted_proxies} as a bare
 * {@code ${SHERIFF_TRUSTED_PROXIES}} and the sibling {@code docker-compose.yml} supplies the value.
 * Nothing about that pairing is checked by a build: {@code deployment} is a {@code packaging=pom}
 * module that compiles and tests nothing, so a sample that stopped loading — a renamed placeholder, a
 * dropped compose variable, a {@code :-} default quietly added — would ship green and fail first in an
 * operator's terminal. This test is what makes those an executing failure instead of a review item.
 * <p>
 * It drives the <strong>real</strong> {@link ConfigLoader} over the shipped configuration directory:
 * parse, substitute, schema-validate and bind, end to end. That is what distinguishes it from a text
 * assertion over the two files — it exercises the destination-typed list substitution the sample
 * depends on, rather than restating that the sample looks right.
 * <p>
 * Both sides are <strong>derived from the committed files</strong> and never mirrored into a constant
 * here. The environment is read out of the compose descriptor's own {@code api-sheriff}
 * {@code environment:} block, so adding a placeholder to the sample without adding the variable
 * beside it fails here rather than at an operator's first {@code up}. Each derived set is fronted by a
 * vacuity guard in the idiom {@code ImageLabelActivationWiringTest} establishes: a parser that found
 * nothing would leave the per-key loops asserting nothing while still passing green.
 * <p>
 * It parses the committed descriptors only — it starts no container and reaches no network.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class ComposeSampleForwardedTrustWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final Path SAMPLE = MODULE.resolve("../deployment/compose-sample").normalize();
    private static final Path COMPOSE = SAMPLE.resolve("docker-compose.yml");
    private static final Path CONFIG_DIR = SAMPLE.resolve("docker/sheriff-config");
    private static final Path GATEWAY_YAML = CONFIG_DIR.resolve("gateway.yaml");

    /** The service whose {@code environment:} block is the sample's deployment door. */
    private static final String GATEWAY_SERVICE = "api-sheriff";

    /** The variable the sample's allow-list is supplied by. */
    private static final String TRUSTED_PROXIES_VARIABLE = "SHERIFF_TRUSTED_PROXIES";

    /** Matches a {@code ${VAR}} / {@code ${VAR:-default}} reference, capturing the variable name. */
    private static final Pattern VARIABLE_REFERENCE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)[^}]*}");

    @Test
    @DisplayName("the shipped sample loads through the real loader and binds the compose-supplied list")
    void sampleLoadsAndBindsTheComposeSuppliedAllowList() throws Exception {
        Map<String, String> environment = sampleEnvironment();
        String supplied = environment.get(TRUSTED_PROXIES_VARIABLE);
        assertNotNull(supplied, () -> "the '" + GATEWAY_SERVICE + "' service must supply "
                + TRUSTED_PROXIES_VARIABLE + " — the sample's gateway.yaml references it with no"
                + " default, so an absent variable fails the boot rather than defaulting");
        List<String> expected = splitAndStrip(supplied);

        GatewayConfig gateway = new ConfigLoader(CONFIG_DIR, new EnvSecretResolver(environment::get))
                .load().gateway();

        // Vacuity guard: an empty expectation would make the equality below assert nothing meaningful.
        assertFalse(expected.isEmpty(),
                () -> "the committed " + TRUSTED_PROXIES_VARIABLE + " value parsed to no entries at all,"
                        + " so the binding assertion below would be vacuous");
        assertNotNull(gateway.forwarded(), "the sample must declare a forwarded block");
        assertEquals(expected, gateway.forwarded().trustedProxies(),
                () -> "the real loader must bind forwarded.trusted_proxies to the whole list the"
                        + " committed compose file supplies — one variable, split on ',' and stripped."
                        + " A mismatch means the sample no longer loads the way an operator's stack will.");
    }

    @Test
    @DisplayName("every ${VAR} the sample references is supplied by the compose environment block")
    void everyReferencedVariableIsSuppliedByTheComposeFile() throws Exception {
        Set<String> referenced = variablesReferencedByTheSample();
        Map<String, String> environment = sampleEnvironment();

        // Vacuity guards on BOTH derived sides — an empty either side makes the loop assert nothing.
        assertFalse(referenced.isEmpty(),
                () -> "no ${VAR} reference was derived from " + GATEWAY_YAML + " — either the parser"
                        + " broke or the sample stopped using the placeholder engine. Until this"
                        + " resolves the wiring below is unchecked, so this is a failure, not a pass.");
        assertFalse(environment.isEmpty(),
                () -> "the '" + GATEWAY_SERVICE + "' service declared no environment entries, so the"
                        + " per-variable check below would pass without checking anything");

        for (String variable : referenced) {
            assertTrue(environment.containsKey(variable),
                    () -> "the sample's gateway.yaml references ${" + variable + "} but the '"
                            + GATEWAY_SERVICE + "' service does not supply it. The placeholder carries no"
                            + " default, so this stack fails its boot — add the variable beside the"
                            + " placeholder rather than adding a default to the document.");
        }
    }

    @Test
    @DisplayName("the sample pins the bare ${SHERIFF_TRUSTED_PROXIES} form with no :- default")
    void trustedProxiesUsesTheBarePlaceholderForm() throws Exception {
        String raw = rawTrustedProxies();

        assertEquals("${" + TRUSTED_PROXIES_VARIABLE + "}", raw,
                () -> "trusted_proxies must be the BARE ${" + TRUSTED_PROXIES_VARIABLE + "} reference."
                        + " A ${VAR:-default} form would let the stack boot with a trust allow-list"
                        + " nobody chose — the silent fallback the boot-failure rule exists to refuse,"
                        + " and the decision a later well-meaning edit is most likely to undo.");
    }

    /**
     * Reads the {@code api-sheriff} service's {@code environment:} block out of the committed compose
     * descriptor, in the {@code KEY=value} list form the sample uses.
     *
     * @return the declared variables in declaration order
     * @throws IOException when the descriptor cannot be read
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> sampleEnvironment() throws IOException {
        assertTrue(Files.isRegularFile(COMPOSE),
                () -> "cannot read the sample's compose descriptor: " + COMPOSE + " does not exist."
                        + " It is resolved relative to the module root (" + MODULE + "); if the sample"
                        + " moved, point COMPOSE at its new location rather than dropping this guard.");
        Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(COMPOSE)) {
            doc = new Yaml().loadAs(in, Map.class);
        }
        Object services = doc.get("services");
        assertInstanceOf(Map.class, services, "docker-compose.yml must declare services");
        Object service = ((Map<String, Object>) services).get(GATEWAY_SERVICE);
        assertNotNull(service, "docker-compose.yml must declare the '" + GATEWAY_SERVICE + "' service");
        Object environment = ((Map<String, Object>) service).get("environment");
        assertInstanceOf(List.class, environment,
                "the '" + GATEWAY_SERVICE + "' service environment must be the KEY=value list form the"
                        + " neighbouring entries use");

        Map<String, String> declared = new LinkedHashMap<>();
        for (Object entry : (List<Object>) environment) {
            String text = String.valueOf(entry);
            int separator = text.indexOf('=');
            assertTrue(separator > 0,
                    () -> "environment entry '" + text + "' is not in KEY=value form");
            declared.put(text.substring(0, separator), text.substring(separator + 1));
        }
        return declared;
    }

    /**
     * Derives every {@code ${VAR}} name the sample's {@code gateway.yaml} references, from the file
     * itself rather than from a mirrored list — a mirrored list cannot notice a placeholder being added.
     * <p>
     * The walk is over the <strong>parsed</strong> document's scalar values, not its raw text, because
     * that is exactly the surface the loader substitutes: comments never reach the placeholder engine,
     * so a {@code ${VAR:-default}} written in prose to explain the rule is documentation and not a
     * reference. Scanning raw text would report it as one and demand a compose variable for it.
     *
     * @return the referenced variable names, in first-appearance order
     * @throws IOException when the document cannot be read
     */
    private static Set<String> variablesReferencedByTheSample() throws IOException {
        assertTrue(Files.isRegularFile(GATEWAY_YAML),
                () -> "cannot read the sample's gateway document: " + GATEWAY_YAML + " does not exist");
        Object document;
        try (InputStream in = Files.newInputStream(GATEWAY_YAML)) {
            document = new Yaml().load(in);
        }
        Set<String> referenced = new LinkedHashSet<>();
        collectVariableReferences(document, referenced);
        return referenced;
    }

    /** Walks a parsed YAML node, collecting the {@code ${VAR}} names any scalar value references. */
    private static void collectVariableReferences(Object node, Set<String> referenced) {
        switch (node) {
            case Map<?, ?> mapping -> mapping.values()
                    .forEach(value -> collectVariableReferences(value, referenced));
            case List<?> sequence -> sequence
                    .forEach(value -> collectVariableReferences(value, referenced));
            case String scalar -> {
                Matcher reference = VARIABLE_REFERENCE.matcher(scalar);
                while (reference.find()) {
                    referenced.add(reference.group(1));
                }
            }
            default -> {
                // A number, boolean or null carries no placeholder.
            }
        }
    }

    /**
     * Reads the raw, <em>pre-substitution</em> {@code forwarded.trusted_proxies} text so the
     * placeholder's exact form can be pinned — the bound value cannot show whether a default was used.
     *
     * @return the literal value the sample declares
     * @throws IOException when the document cannot be read
     */
    @SuppressWarnings("unchecked")
    private static String rawTrustedProxies() throws IOException {
        Map<String, Object> doc;
        try (InputStream in = Files.newInputStream(GATEWAY_YAML)) {
            doc = new Yaml().loadAs(in, Map.class);
        }
        Object forwarded = doc.get("forwarded");
        assertInstanceOf(Map.class, forwarded,
                "the sample gateway.yaml must declare a forwarded block");
        Object trusted = ((Map<String, Object>) forwarded).get("trusted_proxies");
        assertInstanceOf(String.class, trusted,
                "trusted_proxies must be a single placeholder string — the whole list arrives from one"
                        + " variable, so a YAML sequence here would defeat the mechanism the sample shows");
        return (String) trusted;
    }

    private static List<String> splitAndStrip(String value) {
        List<String> entries = new ArrayList<>();
        for (String element : value.split(",", -1)) {
            entries.add(element.strip());
        }
        return entries;
    }
}
