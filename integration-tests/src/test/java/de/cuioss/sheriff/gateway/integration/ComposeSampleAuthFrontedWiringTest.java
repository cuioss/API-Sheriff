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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Fast, no-Docker <em>surefire</em> guard binding the compose sample's {@code /auth}-fronted override to
 * the configuration set it mounts, so the two cannot drift apart while both still parse.
 * <p>
 * <strong>Why a binding test.</strong> The variant is two halves written in two vocabularies:
 * {@code docker-compose.auth-fronted.yml} moves Keycloak under {@code /auth} and behind the gateway, and
 * {@code docker/sheriff-config-auth-fronted/} tells the gateway where Keycloak now lives and whose identity
 * it carries. Each half is valid on its own whatever the other says — the configuration set boots, the
 * override merges — so a drift between them is otherwise visible only on a running stack, as a login that
 * loops through the gateway's own listener, a 401 on every token, or an admin console published on the
 * host. {@code deployment} is a {@code packaging=pom} module that tests nothing, and CI brings the variant
 * up only after the integration suite; this guard turns each drift into a red surefire run first.
 * <p>
 * <strong>What is bound.</strong>
 * <ul>
 *   <li>{@code KC_HTTP_RELATIVE_PATH} equals the path prefix of the anchor the {@code idp} endpoint hangs
 *       under, and the path of {@code KC_HOSTNAME} — the three spellings of {@code /auth} are one value,
 *       which is what lets the gateway forward {@code /auth/...} with the path kept.</li>
 *   <li>{@code oidc.issuer} and every {@code token_validation} issuer equal {@code KC_HOSTNAME} plus
 *       {@code /realms/} plus the realm the sample imports — the identity Keycloak mints into every
 *       token.</li>
 *   <li>{@code oidc.back_channel_origin} is a bare https origin, and not the issuer's own origin, which is
 *       the gateway's listener in this variant — dialling it would send every back-channel call round
 *       through the gateway's own {@code /auth} route.</li>
 *   <li>Keycloak publishes no host port in the MERGED model — the override's {@code ports:} carries a
 *       Compose merge tag, and the base it clears publishes one, so the clearing is shown to have done
 *       something.</li>
 *   <li>{@code KC_PROXY_TRUSTED_ADDRESSES} is exactly the gateway's static address on the sample
 *       network.</li>
 *   <li>The override mounts the variant's own set over the base set's mount target — Compose merges
 *       {@code volumes} by target, so a different target would leave the base set mounted as well.</li>
 * </ul>
 * Every expectation is <strong>derived from the committed files</strong> and none is mirrored into a
 * constant here: the anchor from {@code endpoints/idp.yaml} and {@code gateway.yaml}, the realm from
 * {@code sample-realm.json}, the static address from the override's own network block, the mount target
 * from the base descriptor.
 * <p>
 * <strong>Red against a diverged copy.</strong> Each binding is a check returning its failures, and
 * {@link DivergedCopies} runs every check over a copy of the committed files with exactly one value
 * diverged — each must be reported. A check that could report nothing would otherwise pass this class
 * green.
 * <p>
 * It parses the committed files only — it starts no container and reaches no network. House pattern of
 * {@code BackChannelOriginActivationWiringTest} and {@code ComposeSampleForwardedTrustWiringTest}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("/auth-fronted compose override bound to the configuration set it mounts")
class ComposeSampleAuthFrontedWiringTest {

    /** The module base directory (surefire runs with the module root as the working directory). */
    private static final Path MODULE = Path.of(System.getProperty("user.dir"));

    private static final Path SAMPLE = MODULE.resolve("../deployment/compose-sample").normalize();
    private static final Path BASE_COMPOSE = SAMPLE.resolve("docker-compose.yml");
    private static final Path OVERRIDE = SAMPLE.resolve("docker-compose.auth-fronted.yml");
    private static final Path VARIANT_SET = SAMPLE.resolve("docker/sheriff-config-auth-fronted");
    private static final Path BASE_SET = SAMPLE.resolve("docker/sheriff-config");
    private static final Path GATEWAY_YAML = VARIANT_SET.resolve("gateway.yaml");
    private static final Path IDP_ENDPOINT = VARIANT_SET.resolve("endpoints/idp.yaml");
    private static final Path REALM = SAMPLE.resolve("docker/keycloak/sample-realm.json");

    private static final String KEYCLOAK_SERVICE = "keycloak";
    private static final String GATEWAY_SERVICE = "api-sheriff";
    private static final String SAMPLE_NETWORK = "api-sheriff";

    private static final String HOSTNAME_VARIABLE = "KC_HOSTNAME";
    private static final String RELATIVE_PATH_VARIABLE = "KC_HTTP_RELATIVE_PATH";
    private static final String TRUSTED_ADDRESSES_VARIABLE = "KC_PROXY_TRUSTED_ADDRESSES";

    /** The Compose-Spec merge tags that make an override REPLACE a sequence rather than append to it. */
    private static final Set<String> COMPOSE_MERGE_TAGS = Set.of("!reset", "!override");

    @Test
    @DisplayName("KC_HTTP_RELATIVE_PATH equals the idp anchor's prefix and the path of KC_HOSTNAME")
    void relativePathIsTheAnchorPrefixAndTheHostnamePath() throws IOException {
        assertNoFailure(Variant.committed().relativePathFailures());
    }

    @Test
    @DisplayName("oidc.issuer and every token_validation issuer are KC_HOSTNAME plus /realms/<imported realm>")
    void issuersDeriveFromTheHostname() throws IOException {
        assertNoFailure(Variant.committed().issuerFailures());
    }

    @Test
    @DisplayName("oidc.back_channel_origin is a bare https origin other than the issuer's own")
    void backChannelOriginIsABareOriginOffTheGatewaysListener() throws IOException {
        assertNoFailure(Variant.committed().backChannelOriginFailures());
    }

    @Test
    @DisplayName("Keycloak publishes no host port in the merged model, and the base it clears publishes one")
    void keycloakPublishesNoHostPort() throws IOException {
        assertNoFailure(Variant.committed().publishedPortFailures());
    }

    @Test
    @DisplayName("KC_PROXY_TRUSTED_ADDRESSES is exactly the gateway's static address on the sample network")
    void trustedAddressIsTheGatewaysStaticAddress() throws IOException {
        assertNoFailure(Variant.committed().trustedAddressFailures());
    }

    @Test
    @DisplayName("the override mounts the variant's own set over the base set's mount target")
    void overrideMountsTheVariantSetOverTheBaseSet() throws IOException {
        assertNoFailure(Variant.committed().configMountFailures());
    }

    private static void assertNoFailure(List<String> failures) {
        assertTrue(failures.isEmpty(), () -> String.join(System.lineSeparator(), failures));
    }

    /**
     * Each check, run over a copy of the committed files with exactly ONE value diverged, must report a
     * failure. These are the red runs of the checks above, executed on every build.
     */
    @Nested
    @DisplayName("every binding is reported against a diverged copy")
    class DivergedCopies {

        @Test
        @DisplayName("a relative path, anchor prefix or hostname path that differs from the other two is reported")
        void divergedRelativePathIsReported() throws IOException {
            Variant committed = Variant.committed();

            assertAll(
                    () -> assertReported(committed.withOverride(
                            text -> replace(text, RELATIVE_PATH_VARIABLE + "=/auth", RELATIVE_PATH_VARIABLE + "=/kc"))
                            .relativePathFailures(), "KC_HTTP_RELATIVE_PATH moved to /kc"),
                    () -> assertReported(committed.withGateway(
                            text -> replace(text, "path_prefix: /auth", "path_prefix: /idp"))
                            .relativePathFailures(), "the idp anchor moved to /idp"),
                    () -> assertReported(committed.withOverride(
                            text -> replace(text, HOSTNAME_VARIABLE + "=https://localhost:8443/auth",
                                    HOSTNAME_VARIABLE + "=https://localhost:8443"))
                            .relativePathFailures(), "KC_HOSTNAME lost its path"));
        }

        @Test
        @DisplayName("an oidc issuer, a token_validation issuer or a realm name that drifts is reported")
        void divergedIssuerIsReported() throws IOException {
            Variant committed = Variant.committed();

            assertAll(
                    () -> assertReported(committed.withGateway(
                            text -> replace(text, "\n  issuer: https://localhost:8443/auth/realms/sample",
                                    "\n  issuer: https://localhost:8443/realms/sample"))
                            .issuerFailures(), "oidc.issuer lost the /auth path"),
                    () -> assertReported(committed.withGateway(
                            text -> replace(text, "      issuer: https://localhost:8443/auth/realms/sample",
                                    "      issuer: https://keycloak:8443/auth/realms/sample"))
                            .issuerFailures(), "the token_validation issuer names the dial address"),
                    () -> assertReported(committed.withRealm(
                            text -> replace(text, "\"realm\": \"sample\"", "\"realm\": \"renamed\""))
                            .issuerFailures(), "the imported realm was renamed"));
        }

        @Test
        @DisplayName("a back-channel origin carrying a path, or naming the gateway's own listener, is reported")
        void divergedBackChannelOriginIsReported() throws IOException {
            Variant committed = Variant.committed();

            assertAll(
                    () -> assertReported(committed.withGateway(
                            text -> replace(text, "back_channel_origin: https://keycloak:8443",
                                    "back_channel_origin: https://keycloak:8443/auth"))
                            .backChannelOriginFailures(), "the back-channel origin carries a path"),
                    () -> assertReported(committed.withGateway(
                            text -> replace(text, "back_channel_origin: https://keycloak:8443",
                                    "back_channel_origin: https://localhost:8443"))
                            .backChannelOriginFailures(), "the back-channel origin is the gateway's own listener"));
        }

        @Test
        @DisplayName("an untagged keycloak ports: list, which Compose concatenates onto the base, is reported")
        void untaggedPortsAreReported() throws IOException {
            Variant diverged = Variant.committed().withOverride(
                    text -> replace(text, "ports: !override []", "ports: []"));

            assertReported(diverged.publishedPortFailures(), "keycloak ports: lost its merge tag");
        }

        @Test
        @DisplayName("a trusted-address range, or a gateway address it does not name, is reported")
        void divergedTrustedAddressIsReported() throws IOException {
            Variant committed = Variant.committed();

            assertAll(
                    () -> assertReported(committed.withOverride(
                            text -> replace(text, TRUSTED_ADDRESSES_VARIABLE + "=10.89.0.20",
                                    TRUSTED_ADDRESSES_VARIABLE + "=10.89.0.0/24"))
                            .trustedAddressFailures(), "Keycloak trusts the whole subnet"),
                    () -> assertReported(committed.withOverride(
                            text -> replace(text, "ipv4_address: 10.89.0.20", "ipv4_address: 10.89.0.21"))
                            .trustedAddressFailures(), "the gateway moved off the trusted address"));
        }

        @Test
        @DisplayName("an override mounting the base set, or mounting at another target, is reported")
        void divergedConfigMountIsReported() throws IOException {
            Variant committed = Variant.committed();

            assertAll(
                    () -> assertReported(committed.withOverride(
                            text -> replace(text, "./docker/sheriff-config-auth-fronted:/app/sheriff-config:ro",
                                    "./docker/sheriff-config:/app/sheriff-config:ro"))
                            .configMountFailures(), "the override mounts the base set"),
                    () -> assertReported(committed.withOverride(
                            text -> replace(text, "./docker/sheriff-config-auth-fronted:/app/sheriff-config:ro",
                                    "./docker/sheriff-config-auth-fronted:/app/sheriff-config-variant:ro"))
                            .configMountFailures(), "the override mounts beside the base set"));
        }

        private static void assertReported(List<String> failures, String divergence) {
            assertFalse(failures.isEmpty(), () -> "the check passed a copy in which " + divergence
                    + ", so it cannot detect that drift in the committed files");
        }

        /**
         * Replaces the one committed occurrence of {@code from}. A specimen whose text is absent would
         * diverge nothing and make its control vacuous, so absence fails the control.
         */
        private static String replace(String text, String from, String to) {
            int index = text.indexOf(from);
            assertTrue(index >= 0, () -> "the committed text no longer contains '" + from + "', so this "
                    + "diverged copy would be identical to the committed one — re-point the control");
            assertEquals(-1, text.indexOf(from, index + 1),
                    () -> "the committed text contains '" + from + "' more than once, so the diverged copy is ambiguous");
            return text.substring(0, index) + to + text.substring(index + from.length());
        }
    }

    /**
     * The texts of the five committed files the bindings read, and the checks over them. Held as text so
     * a diverged copy is the committed text with one value replaced.
     *
     * @param override the {@code docker-compose.auth-fronted.yml} text
     * @param base     the base {@code docker-compose.yml} text
     * @param gateway  the variant set's {@code gateway.yaml} text
     * @param idp      the variant set's {@code endpoints/idp.yaml} text
     * @param realm    the imported {@code sample-realm.json} text
     */
    private record Variant(String override, String base, String gateway, String idp, String realm) {

        static Variant committed() throws IOException {
            return new Variant(read(OVERRIDE), read(BASE_COMPOSE), read(GATEWAY_YAML), read(IDP_ENDPOINT),
                    read(REALM));
        }

        Variant withOverride(Function<String, String> divergence) {
            return new Variant(divergence.apply(override), base, gateway, idp, realm);
        }

        Variant withGateway(Function<String, String> divergence) {
            return new Variant(override, base, divergence.apply(gateway), idp, realm);
        }

        Variant withRealm(Function<String, String> divergence) {
            return new Variant(override, base, gateway, idp, divergence.apply(realm));
        }

        List<String> relativePathFailures() {
            List<String> failures = new ArrayList<>();
            Map<String, String> keycloak = environment(parseCompose(override), KEYCLOAK_SERVICE);
            String relativePath = keycloak.get(RELATIVE_PATH_VARIABLE);
            String hostname = keycloak.get(HOSTNAME_VARIABLE);
            if (relativePath == null || hostname == null) {
                failures.add(OVERRIDE.getFileName() + " must set both " + RELATIVE_PATH_VARIABLE + " and "
                        + HOSTNAME_VARIABLE + " on the keycloak service, found " + keycloak.keySet());
                return failures;
            }
            String anchorName = String.valueOf(mapping(parseYaml(idp), "endpoint").get("anchor"));
            Object anchor = mapping(parseYaml(gateway), "anchors").get(anchorName);
            String anchorPrefix = anchor instanceof Map<?, ?> declared
                    ? String.valueOf(declared.get("path_prefix")) : null;
            if (!relativePath.equals(anchorPrefix)) {
                failures.add(RELATIVE_PATH_VARIABLE + "=" + relativePath + " is not the path prefix " + anchorPrefix
                        + " of the anchor '" + anchorName + "' the idp endpoint hangs under. The gateway forwards "
                        + "the request path unchanged, so Keycloak must serve under exactly the prefix the "
                        + "gateway fronts it under.");
            }
            String hostnamePath = uri(hostname).getPath();
            if (!relativePath.equals(hostnamePath)) {
                failures.add(HOSTNAME_VARIABLE + "=" + hostname + " carries the path '" + hostnamePath + "', not "
                        + relativePath + ". Keycloak mints every URL from " + HOSTNAME_VARIABLE + " but serves under "
                        + RELATIVE_PATH_VARIABLE + ", so the two must name the same path or every URL it mints "
                        + "points where it does not serve.");
            }
            return failures;
        }

        List<String> issuerFailures() {
            List<String> failures = new ArrayList<>();
            String hostname = environment(parseCompose(override), KEYCLOAK_SERVICE).get(HOSTNAME_VARIABLE);
            Object realmName = mapping(parseYaml(realm), null).get("realm");
            if (hostname == null || realmName == null) {
                failures.add("cannot derive the expected issuer: " + HOSTNAME_VARIABLE + "=" + hostname + ", realm="
                        + realmName);
                return failures;
            }
            String expected = hostname.replaceAll("/+$", "") + "/realms/" + realmName;
            Map<?, ?> document = parseYaml(gateway);
            Object oidcIssuer = mapping(document, "oidc").get("issuer");
            if (!expected.equals(oidcIssuer)) {
                failures.add("oidc.issuer is " + oidcIssuer + ", expected " + expected + " — the identity Keycloak "
                        + "mints from " + HOSTNAME_VARIABLE + " for the imported realm. Any other value fails every "
                        + "issuer check the BFF runs.");
            }
            List<?> issuers = mapping(document, "token_validation").get("issuers") instanceof List<?> declared
                    ? declared : List.of();
            if (issuers.isEmpty()) {
                failures.add("the variant declares no token_validation issuer, so the tokens its BFF obtains are "
                        + "validated against nothing");
            }
            for (Object entry : issuers) {
                Object issuer = entry instanceof Map<?, ?> declared ? declared.get("issuer") : null;
                if (!expected.equals(issuer)) {
                    failures.add("token_validation issuer " + issuer + " is not " + expected + " — every token of the "
                            + "realm carries that iss value and would be rejected with a 401");
                }
            }
            return failures;
        }

        List<String> backChannelOriginFailures() {
            List<String> failures = new ArrayList<>();
            Map<?, ?> oidc = mapping(parseYaml(gateway), "oidc");
            Object declared = oidc.get("back_channel_origin");
            if (!(declared instanceof String origin)) {
                failures.add("the variant declares no oidc.back_channel_origin, so the gateway would dial the issuer "
                        + "origin — its own listener — for every back-channel call");
                return failures;
            }
            URI parsed = uri(origin);
            boolean bare = "https".equals(parsed.getScheme()) && parsed.getHost() != null
                    && (parsed.getRawPath() == null || parsed.getRawPath().isEmpty()) && parsed.getRawQuery() == null
                    && parsed.getRawFragment() == null && parsed.getRawUserInfo() == null;
            if (!bare) {
                failures.add("oidc.back_channel_origin " + origin + " is not a bare https origin. The mapping swaps "
                        + "the origin and keeps the path, so a path here would be doubled onto every call.");
            }
            URI issuer = uri(String.valueOf(oidc.get("issuer")));
            if (Objects.equals(parsed.getAuthority(), issuer.getAuthority())) {
                failures.add("oidc.back_channel_origin " + origin + " is the issuer's own origin, which in this "
                        + "variant is the gateway's listener — every back-channel call would loop through the "
                        + "gateway's own /auth route");
            }
            return failures;
        }

        List<String> publishedPortFailures() {
            List<String> failures = new ArrayList<>();
            List<String> basePorts = ports(parseCompose(base));
            if (basePorts.isEmpty()) {
                failures.add(BASE_COMPOSE.getFileName() + " publishes no keycloak port, so there is nothing for the "
                        + "override to clear and the merged-model check below proves nothing");
            }
            String tag = keycloakPortsTag(override);
            List<String> merged = switch (tag) {
                case "!reset" -> List.of();
                case "!override" -> ports(parseCompose(override));
                default -> {
                    List<String> concatenated = new ArrayList<>(basePorts);
                    concatenated.addAll(ports(parseCompose(override)));
                    yield concatenated;
                }
            };
            if (!COMPOSE_MERGE_TAGS.contains(tag)) {
                failures.add("the override's keycloak ports: carries '" + tag + "', not one of " + COMPOSE_MERGE_TAGS
                        + ". Compose CONCATENATES port lists, so without the tag the base's admin-console port "
                        + "survives the merge.");
            }
            if (!merged.isEmpty()) {
                failures.add("Keycloak publishes " + merged + " in the merged model. In this variant the browser "
                        + "reaches it through the gateway alone; a published port is the admin console on the host.");
            }
            return failures;
        }

        List<String> trustedAddressFailures() {
            List<String> failures = new ArrayList<>();
            Map<?, ?> compose = parseCompose(override);
            String trusted = environment(compose, KEYCLOAK_SERVICE).get(TRUSTED_ADDRESSES_VARIABLE);
            Object networks = service(compose, GATEWAY_SERVICE).get("networks");
            Object attachment = networks instanceof Map<?, ?> declared ? declared.get(SAMPLE_NETWORK) : null;
            Object address = attachment instanceof Map<?, ?> settings ? settings.get("ipv4_address") : null;
            if (address == null || String.valueOf(address).isBlank()) {
                failures.add("the override declares no ipv4_address for the gateway on the '" + SAMPLE_NETWORK
                        + "' network, so " + TRUSTED_ADDRESSES_VARIABLE + " cannot name it exactly");
                return failures;
            }
            if (!String.valueOf(address).equals(trusted)) {
                failures.add(TRUSTED_ADDRESSES_VARIABLE + "=" + trusted + " is not the gateway's static address "
                        + address + ". Every container on the sample network holds an address in the same subnet, "
                        + "so anything wider lets a co-located container state the client address Keycloak records.");
            }
            return failures;
        }

        List<String> configMountFailures() {
            List<String> failures = new ArrayList<>();
            Optional<Mount> baseMount = gatewayMounts(parseCompose(base)).stream()
                    .filter(mount -> SAMPLE.resolve(mount.source()).normalize().equals(BASE_SET))
                    .findFirst();
            if (baseMount.isEmpty()) {
                failures.add(BASE_COMPOSE.getFileName() + " mounts no " + BASE_SET.getFileName()
                        + " for the gateway, so there is no base mount the override could replace");
                return failures;
            }
            List<Mount> variantMounts = gatewayMounts(parseCompose(override)).stream()
                    .filter(mount -> SAMPLE.resolve(mount.source()).normalize().equals(VARIANT_SET))
                    .toList();
            if (variantMounts.size() != 1) {
                failures.add("the override must mount " + VARIANT_SET.getFileName() + " for the gateway exactly once, "
                        + "found " + variantMounts + " — otherwise the gateway does not boot the set this guard and "
                        + "IdpFrontingCanonicalPathTest read");
                return failures;
            }
            if (!variantMounts.getFirst().target().equals(baseMount.get().target())) {
                failures.add("the override mounts the variant set at " + variantMounts.getFirst().target()
                        + ", not at the base set's target " + baseMount.get().target() + ". Compose merges volumes by "
                        + "target, so only the same target replaces the base set.");
            }
            return failures;
        }
    }

    /** One short-syntax bind mount of the gateway service. */
    private record Mount(String source, String target) {
    }

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), () -> "cannot read " + file + ": it does not exist. It is resolved "
                + "relative to the module root (" + MODULE + "); if the sample moved, point this guard at its new "
                + "location rather than dropping it.");
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Map<?, ?> parseYaml(String text) {
        return asMapping(new Yaml(new SafeConstructor(new LoaderOptions())).load(text));
    }

    private static Map<?, ?> parseCompose(String text) {
        return asMapping(new Yaml(new ComposeMergeTagConstructor()).load(text));
    }

    /**
     * The mapping under {@code key}, or the document itself for a {@code null} key. An absent or non-mapping
     * value reads as the empty mapping, so the check that needed it reports the missing value as a failure.
     */
    private static Map<?, ?> mapping(Map<?, ?> document, String key) {
        return key == null ? document : asMapping(document.get(key));
    }

    private static Map<?, ?> asMapping(Object node) {
        return node instanceof Map<?, ?> mapping ? mapping : Map.of();
    }

    private static Map<?, ?> service(Map<?, ?> compose, String name) {
        return mapping(mapping(compose, "services"), name);
    }

    /** A service's {@code environment:} block, in the {@code KEY=value} list form the sample uses. */
    private static Map<String, String> environment(Map<?, ?> compose, String name) {
        Map<String, String> declared = new LinkedHashMap<>();
        if (service(compose, name).get("environment") instanceof List<?> entries) {
            for (Object entry : entries) {
                String text = String.valueOf(entry);
                int separator = text.indexOf('=');
                if (separator > 0) {
                    declared.put(text.substring(0, separator), text.substring(separator + 1));
                }
            }
        }
        return declared;
    }

    private static List<String> ports(Map<?, ?> compose) {
        return service(compose, KEYCLOAK_SERVICE).get("ports") instanceof List<?> declared
                ? declared.stream().map(String::valueOf).toList() : List.of();
    }

    /** The gateway's short-syntax {@code source:target[:mode]} mounts; long-syntax entries are not read. */
    private static List<Mount> gatewayMounts(Map<?, ?> compose) {
        List<Mount> mounts = new ArrayList<>();
        if (service(compose, GATEWAY_SERVICE).get("volumes") instanceof List<?> declared) {
            for (Object entry : declared) {
                String[] parts = String.valueOf(entry).split(":");
                if (entry instanceof String && parts.length >= 2) {
                    mounts.add(new Mount(parts[0], parts[1]));
                }
            }
        }
        return mounts;
    }

    /**
     * The YAML tag on the override's keycloak {@code ports:} value, read at the NODE level — constructing
     * the document resolves the tag away, which would leave a tagged and an untagged list indistinguishable.
     */
    private static String keycloakPortsTag(String overrideText) {
        Node node = new Yaml().compose(new StringReader(overrideText));
        for (String key : List.of("services", KEYCLOAK_SERVICE, "ports")) {
            node = childNode(node, key);
            if (node == null) {
                return "<absent>";
            }
        }
        return node.getTag().getValue();
    }

    private static Node childNode(Node node, String key) {
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode scalar && key.equals(scalar.getValue())) {
                    return tuple.getValueNode();
                }
            }
        }
        return null;
    }

    private static URI uri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new AssertionError("'" + value + "' is not a URI", e);
        }
    }

    /**
     * A {@link SafeConstructor} that understands the two Compose merge tags, so the override document
     * parses rather than being rejected as carrying an unknown tag. {@code !reset} constructs as
     * {@code null}; {@code !override} as its untagged equivalent. The merge semantics are applied by the
     * checks, which read the tag separately. The registrations are local to this test for the reason
     * {@code ComposeSampleForwardedTrustWiringTest} states: no other test's edit can change this parser.
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

        /** Constructs an {@code !override} node as its untagged equivalent, children through the constructor. */
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
                            values.put(constructObject(tuple.getKeyNode()), constructObject(tuple.getValueNode()));
                        }
                        yield values;
                    }
                    default -> null;
                };
            }
        }
    }
}
