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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.Locale;
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

import de.cuioss.sheriff.gateway.config.DeclaredKeyMaterialKeys;
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
 * <h2>The bare pass-through entry</h2>
 *
 * The gateway's {@code environment:} list carries two entry shapes, and they are kept apart rather than
 * folded into one map. A {@code KEY=value} entry is a value the descriptor itself supplies (a blank one
 * included — that is how an override clears a key). A bare {@code - NAME} entry is Compose's
 * pass-through form: the host's value is copied in when the host sets one, and the variable is
 * <em>absent</em> in the container when it does not. A pass-through therefore <strong>supplies
 * nothing</strong> the descriptor can vouch for, and every assertion below treats it that way: the real
 * loader runs with it unset (the shipped default an operator who sets nothing gets), and a YAML
 * placeholder whose variable is only passed through must carry its own {@code :-} default in the
 * document, or an unset host fails the boot.
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
     * The prefix every MAIN-listener certificate variable carries, in its environment spelling — the
     * selector the override-must-clear set is derived by rather than mirrored from.
     * <p>
     * Blanking those variables is what makes the opt-in coherent: a declared certificate alongside it
     * is a combination the gateway refuses to boot on. A hardcoded two-element list here would
     * therefore be a correctness gap and not merely a style one: let the base descriptor add a third
     * {@code QUARKUS_HTTP_SSL_CERTIFICATE_*} variable and the override retain material through it, and
     * {@code ServerTlsDeclarationGate} would refuse the variant's boot while this test stayed green.
     * <p>
     * It is derived from {@link DeclaredKeyMaterialKeys#HTTP_CERTIFICATE_PREFIX}, the same constant the
     * gate reads its own key set from. The management listener's variables carry the disjoint
     * {@code QUARKUS_MANAGEMENT_SSL_CERTIFICATE_} prefix, so they are excluded by construction — and
     * {@link #MANAGEMENT_CERTIFICATE_VARIABLE} below stays as the matched control proving that.
     */
    private static final String CERTIFICATE_ENVIRONMENT_PREFIX =
            environmentSpelling(DeclaredKeyMaterialKeys.HTTP_CERTIFICATE_PREFIX);

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

    /**
     * Matches a {@code ${VAR}} / {@code ${VAR:-default}} reference, capturing the variable name and —
     * as group 2, present only for the defaulted form — the {@code :-} marker.
     */
    private static final Pattern VARIABLE_REFERENCE =
            Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(:-)?[^}]*}");

    /** The name a bare pass-through {@code environment:} entry must be — Compose's variable-name shape. */
    private static final Pattern PASS_THROUGH_ENTRY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    @Test
    @DisplayName("the shipped sample loads through the real loader and binds the compose-supplied list")
    void sampleLoadsAndBindsTheComposeSuppliedAllowList() throws Exception {
        // Pass-through entries are deliberately left out: they supply nothing unless the host sets them,
        // so the loader runs against the environment an operator who sets nothing actually gets.
        Map<String, String> environment = sampleEnvironment().values();
        String supplied = environment.get(TRUSTED_PROXIES_VARIABLE);
        assertNotNull(supplied, () -> "the '" + GATEWAY_SERVICE + "' service must supply "
                + TRUSTED_PROXIES_VARIABLE + " — the sample's gateway.yaml references it with no"
                + " default, so an absent variable fails the boot rather than defaulting");
        List<String> expected = splitAndStrip(supplied);

        GatewayConfig gateway = new ConfigLoader(CONFIG_DIR, new EnvSecretResolver(environment::get))
                .load(defaulted -> {
                    // This test asserts the bound allow-list, not which placeholders fell back.
                }).gateway();

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
    @DisplayName("every ${VAR} the sample's YAML config documents reference is supplied by the compose environment block,"
            + " or only passed through with its own in-document default")
    void everyReferencedVariableIsSuppliedByTheComposeFile() throws Exception {
        Map<String, Boolean> referenced = variablesReferencedByTheSample();
        ServiceEnvironment environment = sampleEnvironment();

        // Vacuity guards on BOTH derived sides — an empty either side makes the check assert nothing.
        assertFalse(referenced.isEmpty(),
                () -> "no ${VAR} reference was derived from the sample's YAML config documents ("
                        + GATEWAY_YAML.getFileName() + " and endpoints/*.yaml) — either the parser"
                        + " broke or the sample stopped using the placeholder engine. Until this"
                        + " resolves the wiring below is unchecked, so this is a failure, not a pass.");
        assertFalse(environment.values().isEmpty(),
                () -> "the '" + GATEWAY_SERVICE + "' service declared no KEY=value environment entries,"
                        + " so the per-variable check below would pass without checking anything");

        Map<String, String> unsupplied = unsuppliedReferences(referenced, environment);

        assertTrue(unsupplied.isEmpty(),
                () -> String.join(System.lineSeparator(), unsupplied.values()));
    }

    @Test
    @DisplayName("the supply rule refuses an unsupplied variable and a bare reference to a passed-through one")
    void supplyRuleRefusesUnsuppliedAndBarePassThroughReferences() {
        ServiceEnvironment environment = new ServiceEnvironment(Map.of("SUPPLIED", "value"),
                Set.of("PASSED_DEFAULTED", "PASSED_BARE"));
        Map<String, Boolean> referenced = new LinkedHashMap<>();
        referenced.put("SUPPLIED", false);
        referenced.put("PASSED_DEFAULTED", true);
        referenced.put("PASSED_BARE", false);
        referenced.put("UNSUPPLIED", true);

        Map<String, String> unsupplied = unsuppliedReferences(referenced, environment);

        assertEquals(Set.of("PASSED_BARE", "UNSUPPLIED"), unsupplied.keySet(),
                () -> "the supply rule must refuse exactly the two references the compose block cannot"
                        + " vouch for: a variable it does not declare at all (even one the document"
                        + " defaults — the sample states every externally-bound value), and a bare"
                        + " ${VAR} whose variable is only passed through, which an unset host leaves"
                        + " absent. A rule that passes either has no teeth; one that refuses a supplied"
                        + " value or a defaulted pass-through refuses the sample's own shape. Got: "
                        + unsupplied);
    }

    @Test
    @DisplayName("the environment parser keeps a bare pass-through entry apart from a KEY=value one, and refuses any other shape")
    void environmentParserSeparatesPassThroughFromSuppliedEntries() {
        ServiceEnvironment parsed = parseEnvironment(List.of("SUPPLIED=value", "CLEARED=", "PASSED"),
                "a synthetic block");

        assertAll("the two Compose list-entry shapes, and the shapes that are neither",
                () -> assertEquals(Map.of("SUPPLIED", "value", "CLEARED", ""), parsed.values(),
                        "KEY=value entries — a blank value included — must be read as supplied values"),
                () -> assertEquals(Set.of("PASSED"), parsed.passThrough(),
                        "a bare NAME entry must be read as a pass-through, never as a supplied value"
                                + " and never silently dropped"),
                () -> assertThrows(AssertionError.class,
                        () -> parseEnvironment(List.of("=value"), "a synthetic block"),
                        "an entry with an empty name must be refused"),
                () -> assertThrows(AssertionError.class,
                        () -> parseEnvironment(List.of("NOT A NAME"), "a synthetic block"),
                        "a bare entry that is not a variable name must be refused"));
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
        Map<String, String> base = serviceEnvironment(COMPOSE, GATEWAY_SERVICE).values();
        Map<String, String> merged = mergedGatewayEnvironment().values();

        // Vacuity guards on both derived sides — an empty either side makes the loop assert nothing.
        assertFalse(base.isEmpty(),
                () -> "the base descriptor's '" + GATEWAY_SERVICE + "' service declared no environment"
                        + " entries, so the base-is-non-blank control below would be vacuous");
        assertFalse(merged.isEmpty(),
                () -> "the merged base+override model carries no environment entries at all, so every"
                        + " assertion below would pass without checking anything");

        // DERIVED from the base descriptor by prefix, never mirrored into a constant: a certificate
        // variable added to the base later must be covered here the moment it lands, because the
        // override retaining material through it is a boot the gateway refuses.
        List<String> clearedCertificateVariables = base.keySet().stream()
                .filter(variable -> variable.startsWith(CERTIFICATE_ENVIRONMENT_PREFIX))
                .toList();
        assertFalse(clearedCertificateVariables.isEmpty(),
                () -> "no '" + CERTIFICATE_ENVIRONMENT_PREFIX + "*' variable was derived from the base"
                        + " descriptor's '" + GATEWAY_SERVICE + "' service, so the per-variable loop"
                        + " below would assert nothing while passing green. The base declares the"
                        + " gateway's own TLS material through exactly this prefix — an empty"
                        + " derivation means the descriptor stopped doing so, or the prefix moved.");

        assertEquals(INSECURE_REQUESTS_ENABLED, merged.get(INSECURE_REQUESTS_VARIABLE),
                () -> "the merged model must declare " + INSECURE_REQUESTS_VARIABLE + "="
                        + INSECURE_REQUESTS_ENABLED + ". Plain HTTP is DECLARED, never inferred from a"
                        + " missing certificate — without this key the gateway refuses to boot with its"
                        + " certificates cleared, because it would serve neither HTTPS nor a"
                        + " deliberately-declared cleartext listener.");

        for (String variable : clearedCertificateVariables) {
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
        Map<String, String> merged = mergedGatewayEnvironment().values();

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
                                + " (the readiness-gate rules in"
                                + " doc/development/integration-test-topology.adoc), so dropping it"
                                + " — which a bare '!reset' of the list"
                                + " would do — leaves the readiness gate with no target at all."
                                + " Found: " + mergedPorts));
    }

    /**
     * Reads the {@code api-sheriff} service's {@code environment:} block out of the committed compose
     * descriptor.
     *
     * @return the declared entries, split into supplied values and pass-throughs
     * @throws IOException when the descriptor cannot be read
     */
    private static ServiceEnvironment sampleEnvironment() throws IOException {
        return serviceEnvironment(COMPOSE, GATEWAY_SERVICE);
    }

    /**
     * Reads one service's {@code environment:} block out of one committed descriptor, in the list form
     * the sample uses.
     *
     * @param descriptor the compose document to read
     * @param service    the service whose block to read
     * @return the declared entries, split into supplied values and pass-throughs
     * @throws IOException when the descriptor cannot be read
     */
    private static ServiceEnvironment serviceEnvironment(Path descriptor, String service)
            throws IOException {
        Object environment = serviceKey(descriptor, service, "environment");
        List<?> declaredEntries = assertInstanceOf(List.class, environment,
                () -> "the '" + service + "' service in " + descriptor.getFileName() + " must declare"
                        + " its environment in the list form the neighbouring entries use");
        return parseEnvironment(declaredEntries,
                "the '" + service + "' service in " + descriptor.getFileName());
    }

    /**
     * Splits a Compose {@code environment:} list into its two legal entry shapes and refuses anything
     * else. A {@code KEY=value} entry is a supplied value; a key with an empty value — the shape an
     * override clears with — is kept as a present entry with a blank value, which is exactly the
     * distinction the merged-model assertions turn on. A bare {@code NAME} entry is Compose's
     * pass-through form and is kept apart, never folded in as a value: it supplies nothing the
     * descriptor can vouch for.
     *
     * @param entries the parsed list entries
     * @param where   the block being read, for failure messages
     * @return the entries in declaration order, split by shape
     */
    private static ServiceEnvironment parseEnvironment(List<?> entries, String where) {
        Map<String, String> values = new LinkedHashMap<>();
        Set<String> passThrough = new LinkedHashSet<>();
        for (Object entry : entries) {
            String text = String.valueOf(entry);
            int separator = text.indexOf('=');
            if (separator < 0) {
                assertTrue(PASS_THROUGH_ENTRY.matcher(text).matches(),
                        () -> "environment entry '" + text + "' of " + where + " is neither in KEY=value"
                                + " form nor a bare pass-through variable name");
                passThrough.add(text);
            } else {
                assertTrue(separator > 0,
                        () -> "environment entry '" + text + "' of " + where + " has an empty name");
                values.put(text.substring(0, separator), text.substring(separator + 1));
            }
        }
        return new ServiceEnvironment(values, passThrough);
    }

    /**
     * Applies the supply rule to the referenced variables and returns every reference the compose block
     * cannot vouch for, keyed by variable name.
     * <p>
     * A reference is supplied by a {@code KEY=value} entry — whether or not the document defaults it,
     * because the sample states every externally-bound value in the compose file rather than only in
     * the document. A bare pass-through entry supplies <strong>nothing</strong>: Compose leaves the
     * variable absent in the container when the host does not set it. A variable that is only passed
     * through therefore counts as supplied only when every reference to it carries its own
     * {@code :-} default; a bare {@code ${VAR}} reference to it fails the boot on an unset host.
     *
     * @param referenced  each referenced variable, mapped to whether EVERY reference to it is defaulted
     * @param environment the gateway service's declared environment
     * @return the unsupplied references, each with the reason, in reference order
     */
    private static Map<String, String> unsuppliedReferences(Map<String, Boolean> referenced,
            ServiceEnvironment environment) {
        Map<String, String> unsupplied = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> reference : referenced.entrySet()) {
            String variable = reference.getKey();
            if (environment.values().containsKey(variable)) {
                continue;
            }
            if (environment.passThrough().contains(variable)) {
                if (!reference.getValue()) {
                    unsupplied.put(variable, "a YAML config document of the sample references ${" + variable
                            + "} with no default, but the '" + GATEWAY_SERVICE + "' service only passes "
                            + variable + " through as a bare entry. A pass-through supplies NOTHING: Compose"
                            + " copies the host's value when one is set and leaves the variable absent in"
                            + " the container when it is not, so on a host that sets nothing the bare"
                            + " reference fails the boot. Supply it as " + variable + "=<value> beside the"
                            + " placeholder, or give every reference its own ${" + variable
                            + ":-default} in the document.");
                }
                continue;
            }
            unsupplied.put(variable, "a YAML config document of the sample references ${" + variable
                    + "} but the '" + GATEWAY_SERVICE + "' service does not supply it. In the"
                    + " bare ${VAR} form that fails the stack's boot outright; the sample also"
                    + " supplies its defaulted placeholders, so that every externally-bound"
                    + " value is readable from the compose file rather than only from the"
                    + " document. Add the variable beside the placeholder rather than adding a"
                    + " default to the document.");
        }
        return unsupplied;
    }

    /**
     * One service's declared environment, split by the two list-entry shapes Compose accepts.
     *
     * @param values      the {@code KEY=value} entries — values the descriptor itself supplies, a blank
     *                    one included
     * @param passThrough the bare {@code NAME} entries — copied from the host when set there and absent
     *                    in the container otherwise, so the descriptor supplies no value for them
     */
    private record ServiceEnvironment(Map<String, String> values, Set<String> passThrough) {

        /**
         * Lays an override's entries over this one BY NAME, which is how Compose merges an
         * {@code environment:} block: whichever shape the override declares a name in replaces the
         * base's entry for that name.
         *
         * @param override the override document's environment
         * @return the merged environment, base entries first
         */
        ServiceEnvironment overlaidBy(ServiceEnvironment override) {
            Map<String, String> mergedValues = new LinkedHashMap<>(values);
            Set<String> mergedPassThrough = new LinkedHashSet<>(passThrough);
            for (Map.Entry<String, String> value : override.values().entrySet()) {
                mergedPassThrough.remove(value.getKey());
                mergedValues.put(value.getKey(), value.getValue());
            }
            for (String name : override.passThrough()) {
                mergedValues.remove(name);
                mergedPassThrough.add(name);
            }
            return new ServiceEnvironment(mergedValues, mergedPassThrough);
        }
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
     * @return the referenced variable names, in first-appearance order, each mapped to whether EVERY
     *         reference to it carries a {@code :-} default — one bare reference makes it required
     * @throws IOException when a document cannot be read
     */
    private static Map<String, Boolean> variablesReferencedByTheSample() throws IOException {
        assertTrue(Files.isRegularFile(GATEWAY_YAML),
                () -> "cannot read the sample's gateway document: " + GATEWAY_YAML + " does not exist");
        Map<String, Boolean> referenced = new LinkedHashMap<>();
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
     * {@code ${TOPOLOGY_UPSTREAM:-http://demo-api:8080}} is correctly given no value by the compose
     * {@code environment:} block — it is forwarded only as a bare pass-through entry, which
     * {@code EnvironmentKeySpellingGuardTest} holds it to. Including it would demand a value the sample
     * is right not to supply and turn this test red — the caller's assertions therefore say "YAML
     * config documents", never "every document in the directory".
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

    /**
     * Walks a parsed YAML node, collecting the {@code ${VAR}} names any scalar value references, each
     * with whether every reference so far is defaulted.
     */
    private static void collectVariableReferences(Object node, Map<String, Boolean> referenced) {
        switch (node) {
            case Map<?, ?> mapping -> mapping.values()
                    .forEach(value -> collectVariableReferences(value, referenced));
            case List<?> sequence -> sequence
                    .forEach(value -> collectVariableReferences(value, referenced));
            case String scalar -> {
                Matcher reference = VARIABLE_REFERENCE.matcher(scalar);
                while (reference.find()) {
                    referenced.merge(reference.group(1), reference.group(2) != null, Boolean::logicalAnd);
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

    /**
     * Renders a configuration-property name in the environment-variable spelling a compose
     * {@code environment:} block declares it under: upper-cased, with every non-alphanumeric
     * character mapped to a single {@code _}.
     * <p>
     * One underscore per character, never a collapsed run — that is SmallRye's own
     * {@code EnvConfigSource} rule, and the reason a doubled {@code __} means something else entirely
     * (see {@code EnvironmentKeySpellingGuardTest}). Deriving the spelling rather than writing it out
     * is what lets the property-side constant stay the single definition of which keys these are.
     *
     * @param propertyName the property name or prefix, in dotted spelling
     * @return its environment spelling
     */
    private static String environmentSpelling(String propertyName) {
        return propertyName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
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
     * @return the merged environment, base entries first
     * @throws IOException when either descriptor cannot be read
     */
    private static ServiceEnvironment mergedGatewayEnvironment() throws IOException {
        return serviceEnvironment(COMPOSE, GATEWAY_SERVICE)
                .overlaidBy(serviceEnvironment(OVERRIDE, GATEWAY_SERVICE));
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
