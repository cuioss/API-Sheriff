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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
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
 * <h2>The plain-HTTP override</h2>
 *
 * The sample also ships {@code docker-compose.plain-http.yml}, an override that takes TLS
 * termination away from the gateway and puts an nginx hop in front of it. Every assertion about that
 * variant below is computed over the <strong>merged</strong> base-plus-override model, never over the
 * override document alone — because Compose MERGES an override into the base rather than replacing
 * it, so what the override document says in isolation is not what the deployment gets. An assertion
 * that read the override alone would pass on a file that clears nothing.
 * <p>
 * Each cleared value is asserted <strong>present-but-blank in the merged model</strong> and, as the
 * matched control, <strong>non-blank in the base</strong>. The control is what makes the first
 * assertion mean something: without it, a merged blank is equally explained by the base never having
 * declared the key at all, and the override would be proven to have done nothing.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
class ComposeSampleForwardedTrustWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final Path SAMPLE = MODULE.resolve("../deployment/compose-sample").normalize();
    private static final Path COMPOSE = SAMPLE.resolve("docker-compose.yml");
    /** The plain-HTTP variant's override document, layered on {@link #COMPOSE}. */
    private static final Path OVERRIDE = SAMPLE.resolve("docker-compose.plain-http.yml");
    private static final Path CONFIG_DIR = SAMPLE.resolve("docker/sheriff-config");
    private static final Path GATEWAY_YAML = CONFIG_DIR.resolve("gateway.yaml");

    /** The service whose {@code environment:} block is the sample's deployment door. */
    private static final String GATEWAY_SERVICE = "api-sheriff";

    /** The override's TLS-terminating hop, and the only peer the variant trusts. */
    private static final String TERMINATOR_SERVICE = "tls-terminator";

    /** The network both services join, and the one the terminator's static address is declared on. */
    private static final String SAMPLE_NETWORK = "api-sheriff";

    /** The variable the sample's allow-list is supplied by. */
    private static final String TRUSTED_PROXIES_VARIABLE = "SHERIFF_TRUSTED_PROXIES";

    /** The key that expresses the variant's deliberate plain-HTTP opt-in. */
    private static final String INSECURE_REQUESTS_VARIABLE = "QUARKUS_HTTP_INSECURE_REQUESTS";

    /** The value of {@link #INSECURE_REQUESTS_VARIABLE} that opts the main listener into plain HTTP. */
    private static final String INSECURE_REQUESTS_ENABLED = "enabled";

    /**
     * The main listener's certificate variables the override must clear. Blanking them is what makes
     * the opt-in coherent: a declared certificate alongside it is a combination the gateway refuses.
     */
    private static final List<String> CLEARED_CERTIFICATE_VARIABLES = List.of(
            "QUARKUS_HTTP_SSL_CERTIFICATE_FILES",
            "QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES");

    /**
     * The management listener's certificate, which the override must NOT clear — it is a separate
     * listener, so port 9000 stays HTTPS and the readiness gate keeps working.
     */
    private static final String MANAGEMENT_CERTIFICATE_VARIABLE =
            "QUARKUS_MANAGEMENT_SSL_CERTIFICATE_FILES";

    /**
     * The Compose-Spec merge tags that make an override REPLACE a sequence rather than append to it.
     * Either one drops the inherited entry; {@code !override} additionally keeps the entries the
     * override itself declares, which is why the variant uses it for {@code ports:}.
     */
    private static final Set<String> COMPOSE_MERGE_TAGS = Set.of("!reset", "!override");

    /** The container port the base publishes for the gateway's own TLS listener. */
    private static final String PUBLIC_TLS_PORT = "8443";

    /** The container port the readiness probe is derived from, which must survive the merge. */
    private static final String MANAGEMENT_PORT = "9000";

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
    @DisplayName("every ${VAR} the sample's YAML config documents reference is supplied by the compose environment block")
    void everyReferencedVariableIsSuppliedByTheComposeFile() throws Exception {
        Set<String> referenced = variablesReferencedByTheSample();
        Map<String, String> environment = sampleEnvironment();

        // Vacuity guards on BOTH derived sides — an empty either side makes the loop assert nothing.
        assertFalse(referenced.isEmpty(),
                () -> "no ${VAR} reference was derived from the sample's YAML config documents ("
                        + GATEWAY_YAML.getFileName() + " and endpoints/*.yaml) — either the parser"
                        + " broke or the sample stopped using the placeholder engine. Until this"
                        + " resolves the wiring below is unchecked, so this is a failure, not a pass.");
        assertFalse(environment.isEmpty(),
                () -> "the '" + GATEWAY_SERVICE + "' service declared no environment entries, so the"
                        + " per-variable check below would pass without checking anything");

        for (String variable : referenced) {
            assertTrue(environment.containsKey(variable),
                    () -> "a YAML config document of the sample references ${" + variable
                            + "} but the '" + GATEWAY_SERVICE + "' service does not supply it. In the"
                            + " bare ${VAR} form that fails the stack's boot outright; the sample also"
                            + " supplies its defaulted placeholders, so that every externally-bound"
                            + " value is readable from the compose file rather than only from the"
                            + " document. Add the variable beside the placeholder rather than adding a"
                            + " default to the document.");
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

    @Test
    @DisplayName("the merged plain-HTTP model clears the certificates the base declares, and keeps the management one")
    void mergedPlainHttpModelClearsTheCertificatesTheBaseDeclares() throws Exception {
        Map<String, String> base = serviceEnvironment(COMPOSE, GATEWAY_SERVICE);
        Map<String, String> merged = mergedGatewayEnvironment();

        // Vacuity guards on both derived sides — an empty either side makes the loop assert nothing.
        assertFalse(base.isEmpty(),
                () -> "the base descriptor's '" + GATEWAY_SERVICE + "' service declared no environment"
                        + " entries, so the base-is-non-blank control below would be vacuous");
        assertFalse(merged.isEmpty(),
                () -> "the merged base+override model carries no environment entries at all, so every"
                        + " assertion below would pass without checking anything");

        assertEquals(INSECURE_REQUESTS_ENABLED, merged.get(INSECURE_REQUESTS_VARIABLE),
                () -> "the merged model must declare " + INSECURE_REQUESTS_VARIABLE + "="
                        + INSECURE_REQUESTS_ENABLED + ". Plain HTTP is DECLARED, never inferred from a"
                        + " missing certificate — without this key the gateway refuses to boot with its"
                        + " certificates cleared, because it would serve neither HTTPS nor a"
                        + " deliberately-declared cleartext listener.");

        for (String variable : CLEARED_CERTIFICATE_VARIABLES) {
            assertAll("the cleared certificate variable " + variable,
                    // The override's effect, stated positively: the key is PRESENT and BLANK. Asserting
                    // its absence instead would also pass on an override that simply forgot it, since
                    // Compose would then leave the base's value standing in the merged model.
                    () -> assertTrue(merged.containsKey(variable),
                            () -> "the merged model must still CARRY " + variable + ". Compose merges"
                                    + " environment by variable name, so a variable is dropped by"
                                    + " declaring it EMPTY, never by omitting it — an omission would"
                                    + " inherit the base's certificate path and the variant would not"
                                    + " start."),
                    () -> assertTrue(merged.get(variable) != null && merged.get(variable).isBlank(),
                            () -> "the merged " + variable + " must be blank, but is '"
                                    + merged.get(variable) + "'. A present-but-blank value declares"
                                    + " nothing, which is what lets the gateway serve plain HTTP; a"
                                    + " non-blank one is a declared server certificate alongside the"
                                    + " plain-HTTP opt-in, which the gateway refuses to boot on."),
                    // The MATCHED CONTROL. Without it a blank merged value is equally explained by the
                    // base never declaring the key, and the override would be proven to do nothing.
                    () -> assertFalse(base.getOrDefault(variable, "").isBlank(),
                            () -> "the BASE descriptor must declare a non-blank " + variable
                                    + ". This is the control for the assertion above: if the base did"
                                    + " not declare it, the merged blank would prove nothing about the"
                                    + " override having cleared anything."));
        }

        assertAll("the management certificate, which the override must leave alone",
                () -> assertFalse(base.getOrDefault(MANAGEMENT_CERTIFICATE_VARIABLE, "").isBlank(),
                        () -> "the base must declare a non-blank " + MANAGEMENT_CERTIFICATE_VARIABLE
                                + " — the control for the equality below"),
                () -> assertEquals(base.get(MANAGEMENT_CERTIFICATE_VARIABLE),
                        merged.get(MANAGEMENT_CERTIFICATE_VARIABLE),
                        () -> "the merged " + MANAGEMENT_CERTIFICATE_VARIABLE + " must be UNCHANGED"
                                + " from the base. Quarkus' management interface is a separate listener"
                                + " with its own key material, so it stays HTTPS in this variant —"
                                + " which is what keeps the de.cuioss.sheriff.management-scheme label"
                                + " accurate and the readiness probe passing."));
    }

    @Test
    @DisplayName("the merged trusted-proxy list is exactly the terminator's declared static address as a /32")
    void mergedTrustedProxiesNamesExactlyTheTerminatorsStaticAddress() throws Exception {
        String address = terminatorStaticAddress();
        Map<String, String> merged = mergedGatewayEnvironment();

        assertFalse(address.isBlank(),
                () -> "no ipv4_address was derived for the '" + TERMINATOR_SERVICE + "' service on the"
                        + " '" + SAMPLE_NETWORK + "' network, so the comparison below would be vacuous."
                        + " The static address is what makes the /32 below exact rather than a range.");

        String supplied = merged.get(TRUSTED_PROXIES_VARIABLE);
        assertNotNull(supplied,
                () -> "the merged model must supply " + TRUSTED_PROXIES_VARIABLE + " — the sample's"
                        + " gateway.yaml references it with no default, so an absent variable fails the"
                        + " boot rather than defaulting");
        List<String> entries = splitAndStrip(supplied);

        assertEquals(List.of(address + "/32"), entries,
                () -> "the variant must trust EXACTLY the terminating hop, as an exact /32 host route"
                        + " naming its declared static address. Every other container on this network —"
                        + " keycloak, demo-api — holds an address in the same subnet, so a range here"
                        + " would let any of them state its own client address via X-Forwarded-For."
                        + " The address is derived from the terminator's own ipv4_address declaration,"
                        + " so moving the hop moves this expectation with it.");
    }

    @Test
    @DisplayName("the override drops the gateway's public TLS port with a merge tag and keeps management published")
    void overrideDropsThePublicTlsPortAndKeepsManagementPublished() throws Exception {
        String tag = gatewayPortsMergeTag();
        List<String> basePorts = servicePorts(COMPOSE, GATEWAY_SERVICE);
        List<String> mergedPorts = mergedGatewayPorts();

        // Vacuity guard: an empty base makes the "8443 was removed" assertion below meaningless.
        assertFalse(basePorts.isEmpty(),
                () -> "the base descriptor publishes no ports for '" + GATEWAY_SERVICE + "', so there"
                        + " would be nothing for the override to drop and the assertions below would"
                        + " be vacuous");

        assertAll("the override's ports: merge behaviour",
                // Asserted as a PRESENT tag, never inferred from an absent ports: key. Compose
                // CONCATENATES sequences, so an override that simply omits ports: leaves the base's
                // 8443 standing — the exact failure this assertion exists to catch.
                () -> assertTrue(COMPOSE_MERGE_TAGS.contains(tag),
                        () -> "the override's '" + GATEWAY_SERVICE + "' ports: must carry a Compose"
                                + " merge tag (one of " + COMPOSE_MERGE_TAGS + "), but carries '" + tag
                                + "'. Compose CONCATENATES port lists, so without the tag the base's "
                                + PUBLIC_TLS_PORT + " survives the merge and collides with the"
                                + " terminator's binding on the same host port."),
                () -> assertTrue(publishesContainerPort(basePorts, PUBLIC_TLS_PORT),
                        () -> "the BASE must publish container port " + PUBLIC_TLS_PORT + " — the"
                                + " control proving the merge below actually removed something"),
                () -> assertFalse(publishesContainerPort(mergedPorts, PUBLIC_TLS_PORT),
                        () -> "the merged model must NOT publish container port " + PUBLIC_TLS_PORT
                                + " for '" + GATEWAY_SERVICE + "' — that host port belongs to the TLS"
                                + " terminator in this variant, and the gateway's own listener no"
                                + " longer terminates, so publishing it would either collide on the"
                                + " host binding or expose a port that answers nothing. Found: "
                                + mergedPorts),
                () -> assertTrue(publishesContainerPort(mergedPorts, MANAGEMENT_PORT),
                        () -> "the merged model must STILL publish container port " + MANAGEMENT_PORT
                                + " for '" + GATEWAY_SERVICE + "'. scripts/start-sample.sh derives its"
                                + " whole readiness probe from the host port published against it"
                                + " (ADR-0031), so dropping it — which a bare '!reset' of the list"
                                + " would do — leaves the readiness gate with no target at all."
                                + " Found: " + mergedPorts));
    }

    /**
     * Reads the {@code api-sheriff} service's {@code environment:} block out of the committed compose
     * descriptor, in the {@code KEY=value} list form the sample uses.
     *
     * @return the declared variables in declaration order
     * @throws IOException when the descriptor cannot be read
     */
    private static Map<String, String> sampleEnvironment() throws IOException {
        return serviceEnvironment(COMPOSE, GATEWAY_SERVICE);
    }

    /**
     * Reads one service's {@code environment:} block out of one committed descriptor, in the
     * {@code KEY=value} list form the sample uses. A key with an empty value — the shape an override
     * clears with — is returned as a present entry with a blank value, which is exactly the
     * distinction the merged-model assertions turn on.
     *
     * @param descriptor the compose document to read
     * @param service    the service whose block to read
     * @return the declared variables in declaration order
     * @throws IOException when the descriptor cannot be read
     */
    private static Map<String, String> serviceEnvironment(Path descriptor, String service)
            throws IOException {
        Object environment = serviceKey(descriptor, service, "environment");
        List<?> declaredEntries = assertInstanceOf(List.class, environment,
                () -> "the '" + service + "' service in " + descriptor.getFileName() + " must declare"
                        + " its environment in the KEY=value list form the neighbouring entries use");

        Map<String, String> declared = new LinkedHashMap<>();
        for (Object entry : declaredEntries) {
            String text = String.valueOf(entry);
            int separator = text.indexOf('=');
            assertTrue(separator > 0,
                    () -> "environment entry '" + text + "' is not in KEY=value form");
            declared.put(text.substring(0, separator), text.substring(separator + 1));
        }
        return declared;
    }

    /**
     * Derives every {@code ${VAR}} name the sample references, from the committed files themselves
     * rather than from a mirrored list — a mirrored list cannot notice a placeholder being added.
     * <p>
     * The scan covers <strong>every document the loader substitutes</strong>, not only
     * {@code gateway.yaml}: {@code ConfigLoader} runs the same {@code substitute()} pass over each
     * {@code endpoints/*.yaml} document, so a placeholder written there fails an operator's boot
     * exactly as one in the gateway document does. Scanning only the gateway document would let the
     * test's name claim a closure it does not deliver — an endpoint placeholder added later would
     * stay unchecked while this stayed green.
     * <p>
     * The walk is over each <strong>parsed</strong> document's scalar values, not its raw text, because
     * that is exactly the surface the loader substitutes: comments never reach the placeholder engine,
     * so a {@code ${VAR:-default}} written in prose to explain the rule is documentation and not a
     * reference. Scanning raw text would report it as one and demand a compose variable for it.
     *
     * @return the referenced variable names, in first-appearance order
     * @throws IOException when a document cannot be read
     */
    private static Set<String> variablesReferencedByTheSample() throws IOException {
        assertTrue(Files.isRegularFile(GATEWAY_YAML),
                () -> "cannot read the sample's gateway document: " + GATEWAY_YAML + " does not exist");
        Set<String> referenced = new LinkedHashSet<>();
        for (Path document : substitutedDocuments()) {
            Object parsed;
            try (InputStream in = Files.newInputStream(document)) {
                parsed = new Yaml().load(in);
            }
            collectVariableReferences(parsed, referenced);
        }
        return referenced;
    }

    /**
     * Lists the sample's <strong>YAML config documents</strong> — the ones {@code ConfigLoader} runs
     * its {@code ${VAR}} substitution over — in the order it reads them: the gateway document, then
     * each {@code endpoints/*.yaml}.
     * <p>
     * The {@code .yaml} filter and the name ordering mirror {@code ConfigLoader.listEndpointFiles} so
     * this test's population is the loader's population. An absent {@code endpoints/} directory is
     * legal (the loader treats it as no endpoints), so it contributes nothing rather than failing.
     * <p>
     * {@code topology.properties} sits in the same directory and is deliberately <em>not</em> here.
     * It is substituted by {@code TopologyResolver}, not by this loader, and under a different rule:
     * a defaulted {@code ${VAR:-default}} is legitimate there, so the sample's
     * {@code ${TOPOLOGY_UPSTREAM:-http://demo-api:8080}} is correctly absent from the compose
     * {@code environment:} block. Including it would demand a variable the sample is right not to
     * supply and turn this test red — the caller's assertions therefore say "YAML config documents",
     * never "every document in the directory".
     *
     * @return the readable YAML config documents, gateway first
     * @throws IOException when the endpoints directory cannot be listed
     */
    private static List<Path> substitutedDocuments() throws IOException {
        List<Path> documents = new ArrayList<>();
        documents.add(GATEWAY_YAML);
        Path endpoints = CONFIG_DIR.resolve("endpoints");
        if (Files.isDirectory(endpoints)) {
            try (Stream<Path> entries = Files.list(endpoints)) {
                entries.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".yaml"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .forEach(documents::add);
            }
        }
        return documents;
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

    // ---- The merged base+override model ---------------------------------------------------------

    /**
     * Overlays the override's gateway environment onto the base's, by variable name — which is
     * exactly how Compose merges an {@code environment:} block. The result is what the deployment
     * actually gets, and is the only model the variant's assertions are computed over.
     *
     * @return the merged variables, base entries first
     * @throws IOException when either descriptor cannot be read
     */
    private static Map<String, String> mergedGatewayEnvironment() throws IOException {
        Map<String, String> merged = new LinkedHashMap<>(serviceEnvironment(COMPOSE, GATEWAY_SERVICE));
        merged.putAll(serviceEnvironment(OVERRIDE, GATEWAY_SERVICE));
        return merged;
    }

    /**
     * Applies Compose's {@code ports:} merge rule to the two descriptors: a sequence CONCATENATES
     * unless the override tags it, {@code !reset} drops it entirely, and {@code !override} replaces
     * it with the override's own entries. Modelling the rule here — rather than assuming the override
     * simply wins — is what lets the assertions distinguish a tagged override from an untagged one
     * that silently appends.
     *
     * @return the gateway's effective published ports in the merged model
     * @throws IOException when either descriptor cannot be read
     */
    private static List<String> mergedGatewayPorts() throws IOException {
        String tag = gatewayPortsMergeTag();
        if ("!reset".equals(tag)) {
            return List.of();
        }
        if ("!override".equals(tag)) {
            return servicePorts(OVERRIDE, GATEWAY_SERVICE);
        }
        List<String> merged = new ArrayList<>(servicePorts(COMPOSE, GATEWAY_SERVICE));
        merged.addAll(servicePorts(OVERRIDE, GATEWAY_SERVICE));
        return merged;
    }

    /**
     * Reads the YAML tag carried by the override's gateway {@code ports:} value, at the NODE level so
     * the tag is observable at all — constructing the document would resolve it away and leave the
     * assertion unable to tell a tagged sequence from a plain one.
     *
     * @return the tag's literal text, e.g. {@code !override}, or the resolved default tag when the
     *         override carries none
     * @throws IOException when the override cannot be read
     */
    private static String gatewayPortsMergeTag() throws IOException {
        Node document;
        try (Reader reader = Files.newBufferedReader(OVERRIDE)) {
            document = new Yaml().compose(reader);
        }
        Node services = childNode(document, "services", OVERRIDE);
        Node gateway = childNode(services, GATEWAY_SERVICE, OVERRIDE);
        return childNode(gateway, "ports", OVERRIDE).getTag().getValue();
    }

    /**
     * @param node       the mapping node to look in
     * @param key        the key to resolve
     * @param descriptor the document being read, for failure messages
     * @return the value node declared under {@code key}
     */
    private static Node childNode(Node node, String key, Path descriptor) {
        MappingNode mapping = assertInstanceOf(MappingNode.class, node,
                () -> descriptor.getFileName() + ": expected a mapping while resolving '" + key + "'");
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode scalar && key.equals(scalar.getValue())) {
                return tuple.getValueNode();
            }
        }
        return fail(descriptor.getFileName() + " declares no '" + key + "' key where one is required");
    }

    /**
     * @param descriptor the compose document to read
     * @param service    the service whose {@code ports:} to read
     * @return the declared port strings, or an empty list when the service declares none
     * @throws IOException when the descriptor cannot be read
     */
    private static List<String> servicePorts(Path descriptor, String service) throws IOException {
        Object ports = serviceKey(descriptor, service, "ports");
        if (ports == null) {
            return List.of();
        }
        List<?> declared = assertInstanceOf(List.class, ports,
                () -> "the '" + service + "' service in " + descriptor.getFileName()
                        + " must declare ports: as a sequence");
        List<String> entries = new ArrayList<>();
        for (Object entry : declared) {
            entries.add(String.valueOf(entry));
        }
        return entries;
    }

    /**
     * Tests whether any published-port entry targets a given CONTAINER port. The container port is
     * the last colon-separated segment in every Compose short-form spelling, so this reads
     * {@code "8443:8443"} and {@code "127.0.0.1:9000:9000"} alike without caring which host interface
     * an entry binds.
     *
     * @param ports         the declared port entries
     * @param containerPort the container-side port to look for
     * @return {@code true} when at least one entry publishes it
     */
    private static boolean publishesContainerPort(List<String> ports, String containerPort) {
        for (String entry : ports) {
            String[] segments = entry.split(":");
            if (containerPort.equals(segments[segments.length - 1])) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads the terminating hop's declared static address from the override itself, so the {@code /32}
     * the trusted-proxy assertion expects is DERIVED from the deployment rather than mirrored into a
     * constant here — moving the hop moves the expectation with it.
     *
     * @return the declared {@code ipv4_address}, or the empty string when none is declared
     * @throws IOException when the override cannot be read
     */
    private static String terminatorStaticAddress() throws IOException {
        Object networks = serviceKey(OVERRIDE, TERMINATOR_SERVICE, "networks");
        Map<?, ?> declared = assertInstanceOf(Map.class, networks,
                () -> "the '" + TERMINATOR_SERVICE + "' service must declare its networks in the mapping"
                        + " form — the list form cannot carry the ipv4_address this variant needs");
        Object attachment = declared.get(SAMPLE_NETWORK);
        Map<?, ?> settings = assertInstanceOf(Map.class, attachment,
                () -> "the '" + TERMINATOR_SERVICE + "' service must attach to the '" + SAMPLE_NETWORK
                        + "' network with an explicit settings mapping");
        Object address = settings.get("ipv4_address");
        return address == null ? "" : String.valueOf(address);
    }

    /**
     * @param descriptor the compose document to read
     * @param service    the service to resolve
     * @param key        the key to read off that service
     * @return the declared value, or {@code null} when the service declares no such key
     * @throws IOException when the descriptor cannot be read
     */
    private static Object serviceKey(Path descriptor, String service, String key) throws IOException {
        assertTrue(Files.isRegularFile(descriptor),
                () -> "cannot read the compose descriptor " + descriptor + ": it does not exist. It is"
                        + " resolved relative to the module root (" + MODULE + "); if the sample moved,"
                        + " point this test at its new location rather than dropping the guard.");
        Object document;
        try (InputStream in = Files.newInputStream(descriptor)) {
            document = composeParser().load(in);
        }
        Map<?, ?> root = assertInstanceOf(Map.class, document,
                () -> descriptor.getFileName() + " must parse as a YAML mapping");
        Map<?, ?> services = assertInstanceOf(Map.class, root.get("services"),
                () -> descriptor.getFileName() + " must declare services");
        Map<?, ?> definition = assertInstanceOf(Map.class, services.get(service),
                () -> descriptor.getFileName() + " must declare the '" + service + "' service");
        return definition.get(key);
    }

    /**
     * @return a parser that understands the Compose merge tags, so the override document parses at all
     */
    private static Yaml composeParser() {
        return new Yaml(new ComposeMergeTagConstructor());
    }

    /**
     * A {@link SafeConstructor} that understands the two Compose-Spec merge tags, so the override
     * document loads rather than being rejected as carrying an unknown tag.
     * <p>
     * {@code !reset} removes the value the base declared, which {@code null} models. {@code !override}
     * keeps its own value but suppresses sequence merging, so it constructs exactly as the untagged
     * node would — the merge SEMANTICS are applied by {@link #mergedGatewayPorts()}, which reads the
     * tag separately, not by this constructor.
     * <p>
     * {@code EnvironmentKeySpellingGuardTest} registers the same two tags for the same reason; the
     * registrations are deliberately local to each test rather than shared, so neither test's parser
     * can be changed out from under it by an edit made for the other.
     */
    private static final class ComposeMergeTagConstructor extends SafeConstructor {

        private ComposeMergeTagConstructor() {
            super(new LoaderOptions());
            yamlConstructors.put(new Tag("!reset"), new ConstructReset());
            yamlConstructors.put(new Tag("!override"), new ConstructUntagged());
        }

        /** Constructs a {@code !reset} node as {@code null} — the base's value is removed. */
        private static final class ConstructReset extends AbstractConstruct {

            @Override
            public Object construct(Node node) {
                return null;
            }
        }

        /**
         * Constructs an {@code !override} node as its untagged equivalent. Children are resolved
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
