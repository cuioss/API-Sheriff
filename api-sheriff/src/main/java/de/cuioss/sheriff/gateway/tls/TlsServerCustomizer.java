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
package de.cuioss.sheriff.gateway.tls;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;


import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.TlsConfig;
import de.cuioss.tools.logging.CuiLogger;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpVersion;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Maps the resolved global {@code tls} block of {@code gateway.yaml} onto the terminated Quarkus
 * HTTPS listener: {@code tls.min_version} becomes the listener's enabled protocol set,
 * {@code tls.cipher_suites} its cipher allowlist, and {@code tls.alpn} its advertised ALPN
 * protocols. {@code gateway.yaml} is thereby the single effective source of terminated-listener TLS
 * policy — the raw {@code quarkus.tls.*} keys that previously declared the same policy never reached
 * the listener and have been deleted (ADR-0025).
 * <p>
 * Every mapping is a no-op when its key is absent, so an omitted {@code tls} block — or an omitted
 * individual key — leaves the corresponding Quarkus default untouched.
 * <p>
 * All three knobs are <em>fail-closed</em>: an unrecognized {@code min_version}, an unrecognized
 * {@code alpn} identifier and an unusable {@code cipher_suites} list each abort the boot with an
 * actionable {@link IllegalStateException} rather than being silently ignored. The cipher check
 * matters most, because nothing downstream rejects a bad allowlist: Vert.x binds the listener happily
 * and the defect only surfaces as an {@code SSLHandshakeException} on every client connection — a
 * listener that accepts TCP but can never complete a handshake. Two distinct operator errors produce
 * exactly that outcome, and the guard rejects both:
 * <ul>
 * <li>a suite <em>name</em> this JDK does not know — including a partly mistyped list, which would
 * otherwise silently narrow the declared policy instead of announcing itself; and</li>
 * <li>a list that is <em>unreachable</em> under one of the listener's enabled protocols: a
 * {@code min_version} of {@code 1.2} enables TLSv1.2 alongside TLSv1.3, so an allowlist holding only
 * TLS 1.3 suites passes every name check yet leaves TLSv1.2 with nothing to negotiate.</li>
 * </ul>
 * The per-protocol negotiable suite sets backing the second check are derived from the platform's own
 * {@link SSLContext}s rather than from a hardcoded suite classification, so they track whatever the
 * running JDK actually offers.
 * <p>
 * This customizer governs the <em>terminated</em> listener only. The SNI front listener
 * ({@link SniFrontListener}) is a raw Vert.x {@code NetServer} that terminates nothing — it splits
 * passthrough connections off at L4 before any handshake — so it is unaffected by this policy
 * (ADR-0017). Client-auth on the terminated listener is owned by the sibling
 * {@link MtlsServerCustomizer} and is deliberately not touched here.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class TlsServerCustomizer implements HttpServerOptionsCustomizer {

    private static final CuiLogger LOGGER = new CuiLogger(TlsServerCustomizer.class);

    private static final String TLS_V1_2 = "TLSv1.2";
    private static final String TLS_V1_3 = "TLSv1.3";

    private final GatewayConfig gatewayConfig;

    /**
     * @param gatewayConfig the bound global gateway document (source of the {@code tls} block)
     */
    @Inject
    public TlsServerCustomizer(GatewayConfig gatewayConfig) {
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
    }

    /**
     * Applies the declared TLS floor, cipher allowlist and ALPN protocol list to the terminated
     * HTTPS listener; each mapping is skipped when its {@code gateway.yaml} key is absent.
     *
     * @param options the HTTPS server options Quarkus is about to start the terminated listener with
     */
    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        TlsConfig config = gatewayConfig.tls();
        if (config == null) {
            return;
        }
        applyMinVersion(config, options);
        applyCipherSuites(config, options);
        applyAlpn(config, options);
    }

    /**
     * Maps the declared floor onto the enabled protocol set: a {@code 1.2} floor enables TLSv1.2 and
     * TLSv1.3, a {@code 1.3} floor enables TLSv1.3 only.
     */
    private static void applyMinVersion(TlsConfig config, HttpServerOptions options) {
        String minVersion = config.minVersion();
        if (minVersion == null) {
            return;
        }
        Set<String> enabledProtocols = switch (minVersion) {
            case "1.2" -> Set.of(TLS_V1_2, TLS_V1_3);
            case "1.3" -> Set.of(TLS_V1_3);
            default -> throw new IllegalStateException(
                    "Refusing to start — unsupported tls.min_version '%s'; expected '1.2' or '1.3'"
                            .formatted(minVersion));
        };
        options.setEnabledSecureTransportProtocols(enabledProtocols);
        LOGGER.debug("Terminated HTTPS listener TLS floor '%s' enables protocols %s", minVersion,
                enabledProtocols);
    }

    /**
     * Adds each declared cipher suite to the listener's allowlist; an empty list leaves the Quarkus
     * default suite selection untouched. The list must clear two checks first, because nothing
     * downstream performs either one — both a name the JDK does not know and a list no enabled
     * protocol can negotiate bind a listener that fails every handshake instead of refusing to start.
     * <p>
     * Runs after {@link #applyMinVersion}, so the listener's enabled protocol set is already in place
     * on {@code options} by the time the reachability check reads it; when {@code min_version} was
     * absent that set is the Vert.x/JSSE default, which is exactly the set the listener will use.
     */
    private static void applyCipherSuites(TlsConfig config, HttpServerOptions options) {
        List<String> cipherSuites = config.cipherSuites();
        if (cipherSuites.isEmpty()) {
            return;
        }
        Set<String> supported = supportedCipherSuites();
        for (String suite : cipherSuites) {
            if (!supported.contains(suite)) {
                List<String> closest = closestSupported(suite, supported);
                throw new IllegalStateException(
                        "Refusing to start — unsupported tls.cipher_suites entry '%s'; this JDK supports no such suite%s"
                                .formatted(suite,
                                        closest.isEmpty() ? "" : ", closest supported: " + closest));
            }
        }
        assertEveryEnabledProtocolNegotiates(cipherSuites, options);
        cipherSuites.forEach(options::addEnabledCipherSuite);
        LOGGER.debug("Terminated HTTPS listener cipher-suite allowlist: %s", cipherSuites);
    }

    /**
     * Rejects an allowlist that leaves one of the listener's enabled protocols with nothing to
     * negotiate. Every name may be JDK-supported and the listener still bind while every client
     * speaking that protocol fails the handshake — the same fail-open shape an unsupported name
     * produces, reached by a different operator error.
     * <p>
     * A protocol whose negotiable set cannot be derived from the platform is skipped rather than
     * treated as unreachable: an underivable set is a gap in this JVM's {@link SSLContext} exposure,
     * not evidence of a misconfiguration, and rejecting on it would fail a boot the operator
     * configured correctly. The protocols this customizer itself enables (TLSv1.2, TLSv1.3) always
     * derive, so the guard is never skipped for a {@code min_version}-driven set.
     */
    private static void assertEveryEnabledProtocolNegotiates(List<String> declared, HttpServerOptions options) {
        Set<String> declaredSuites = Set.copyOf(declared);
        for (String protocol : options.getEnabledSecureTransportProtocols()) {
            Set<String> negotiable = negotiableCipherSuites(protocol);
            if (negotiable.isEmpty()) {
                LOGGER.debug("Skipping cipher-suite reachability check for enabled protocol '%s' — this JDK "
                        + "exposes no negotiable suite set for it", protocol);
                continue;
            }
            if (Collections.disjoint(negotiable, declaredSuites)) {
                throw new IllegalStateException(
                        ("Refusing to start — tls.cipher_suites declares no suite negotiable under enabled "
                                + "protocol '%s', so the listener would bind while every %s client fails the "
                                + "handshake; either exclude '%s' from the enabled set via tls.min_version, or "
                                + "add a suite it can negotiate to tls.cipher_suites, e.g. %s")
                                .formatted(protocol, protocol, protocol, sample(negotiable)));
            }
        }
    }

    /**
     * The suites negotiable under {@code protocol} <em>alone</em>, derived entirely from the platform.
     * A protocol-named {@link SSLContext} reports the suites applicable to that protocol and every
     * protocol below it, and names those lower protocols itself; subtracting their sets therefore
     * isolates the suites unique to this protocol without any hardcoded suite classification.
     * <p>
     * Returns an empty set when the platform exposes no context for the protocol, which callers read
     * as "not derivable" rather than "nothing is negotiable".
     */
    private static Set<String> negotiableCipherSuites(String protocol) {
        Optional<SSLParameters> parameters = defaultParametersOf(protocol);
        if (parameters.isEmpty()) {
            return Set.of();
        }
        Set<String> suites = new LinkedHashSet<>(Arrays.asList(parameters.get().getCipherSuites()));
        for (String lower : parameters.get().getProtocols()) {
            if (!lower.equals(protocol)) {
                defaultParametersOf(lower)
                        .ifPresent(lowerParameters -> Arrays.asList(lowerParameters.getCipherSuites())
                                .forEach(suites::remove));
            }
        }
        return suites;
    }

    /**
     * The default {@link SSLParameters} of a protocol-named {@link SSLContext}, or empty when this
     * platform has no such context.
     */
    private static Optional<SSLParameters> defaultParametersOf(String protocol) {
        try {
            SSLContext context = SSLContext.getInstance(protocol);
            context.init(null, null, null);
            return Optional.of(context.getDefaultSSLParameters());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            LOGGER.debug(e, "No default SSLContext available for protocol '%s'", protocol);
            return Optional.empty();
        }
    }

    /**
     * The first three suites of a negotiable set in stable alphabetical order, so an abort message
     * offers a short actionable suggestion instead of the platform's full list.
     */
    private static List<String> sample(Set<String> negotiable) {
        return negotiable.stream().sorted().limit(3).toList();
    }

    /**
     * The cipher suites the platform's default {@link SSLContext} can actually negotiate — the same
     * set the {@code SSLEngine} backing this listener is built from, and therefore the only correct
     * authority for validating a declared allowlist. Resolved per boot rather than cached in a static
     * initializer so a native-image build cannot bake a build-host value into the executable.
     * <p>
     * Collected with {@link Set#copyOf} rather than {@link Set#of(Object[])} because the array is read
     * from a pluggable JSSE provider: a custom or aliasing provider may report a name twice, and
     * {@code Set.of} would turn that into a context-free {@link IllegalArgumentException} at boot
     * instead of validating the allowlist normally.
     */
    private static Set<String> supportedCipherSuites() {
        try {
            return Set.copyOf(
                    Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getCipherSuites()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "Refusing to start — the platform default SSLContext is unavailable, so the declared "
                            + "tls.cipher_suites allowlist cannot be validated",
                    e);
        }
    }

    /**
     * Ranks the supported suites by how many underscore-separated tokens they share with the rejected
     * name, returning the best three. Suites sharing only the universal {@code TLS} token are dropped,
     * so a wholly unrelated string yields no misleading suggestion at all.
     */
    private static List<String> closestSupported(String declared, Set<String> supported) {
        Set<String> declaredTokens = Arrays.stream(declared.toUpperCase(Locale.ROOT).split("_"))
                .collect(Collectors.toSet());
        return supported.stream()
                .filter(candidate -> sharedTokens(candidate, declaredTokens) > 1)
                .sorted(Comparator.comparingLong((String candidate) -> sharedTokens(candidate, declaredTokens))
                        .reversed().thenComparing(Comparator.naturalOrder()))
                .limit(3)
                .toList();
    }

    private static long sharedTokens(String candidate, Set<String> declaredTokens) {
        return Arrays.stream(candidate.split("_")).distinct().filter(declaredTokens::contains).count();
    }

    /**
     * Enables ALPN and advertises the declared protocols; an empty list leaves ALPN as Quarkus
     * configured it.
     */
    private static void applyAlpn(TlsConfig config, HttpServerOptions options) {
        List<String> alpn = config.alpn();
        if (alpn.isEmpty()) {
            return;
        }
        List<HttpVersion> alpnVersions = new ArrayList<>(alpn.size());
        for (String protocol : alpn) {
            alpnVersions.add(toHttpVersion(protocol));
        }
        options.setUseAlpn(true);
        options.setAlpnVersions(alpnVersions);
        LOGGER.debug("Terminated HTTPS listener ALPN protocols: %s", alpn);
    }

    /**
     * Resolves an ALPN protocol identifier to its Vert.x {@link HttpVersion}. An unrecognized
     * identifier aborts the boot rather than being silently dropped from the advertised set.
     */
    private static HttpVersion toHttpVersion(String alpnProtocol) {
        return switch (alpnProtocol) {
            case "h2" -> HttpVersion.HTTP_2;
            case "http/1.1" -> HttpVersion.HTTP_1_1;
            case "http/1.0" -> HttpVersion.HTTP_1_0;
            default -> throw new IllegalStateException(
                    "Refusing to start — unsupported tls.alpn protocol '%s'; expected 'h2', 'http/1.1' or 'http/1.0'"
                            .formatted(alpnProtocol));
        };
    }
}
