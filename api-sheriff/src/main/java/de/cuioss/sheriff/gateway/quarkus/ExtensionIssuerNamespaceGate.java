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
package de.cuioss.sheriff.gateway.quarkus;

import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;


import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;

/**
 * Refuses the boot when any key of the token-validation extension's own issuer namespace,
 * {@code sheriff.token.issuers.*}, is configured, and logs {@code ERROR ApiSheriff-203} naming the
 * offending keys.
 * <p>
 * <strong>Why the namespace must stay empty.</strong> The gateway builds its validator from the
 * {@code token_validation} block of {@code gateway.yaml} and injects it under the
 * {@code @GatewayValidator} qualifier (ADR-0074). A key in the extension's namespace makes the
 * extension build a second validator of its own: eagerly at boot through its
 * {@code TokenValidatorStartupInitializer}, and through the {@code ObservedValidatorResolver} its
 * metrics collector reads. That validator loads its key sets through the extension's loader, so the
 * fetches bypass the gateway's JWKS loader, the {@code jwks.tls_profile} resolution and the derived
 * egress allowance, and the collector then reports on a validator the request path never uses.
 * <p>
 * <strong>Both spellings are matched.</strong> A key is in the namespace when its property spelling
 * starts with {@code sheriff.token.issuers.}, or its environment spelling starts with
 * {@code SHERIFF_TOKEN_ISSUERS_} in any letter case — the extension discovers an issuer configured only
 * through the environment ({@code SHERIFF_TOKEN_ISSUERS_<NAME>_ENABLED}), and the environment source
 * resolves a lower-case variable name as well. The gate refuses on any such key rather than only on an
 * enabled issuer: the extension's own enablement probe is the thing being guarded against, so the
 * gate does not reproduce it, and no gateway key lives in that namespace.
 * <p>
 * <strong>It runs before every extension observer.</strong> The observer carries the priority
 * {@code Interceptor.Priority.PLATFORM_BEFORE - 1}. The extension's
 * startup initializer observes {@code StartupEvent} at the default priority, and the Quarkus scheduler
 * that drives the metrics collector starts at {@code PLATFORM_BEFORE}; a lower value is notified
 * earlier, so the refusal is raised before either can build the extension's validator. Throwing from a
 * {@code StartupEvent} observer aborts the start.
 * <p>
 * <strong>Lazy-proxy hazard (lesson 2026-07-20-18-002).</strong> The observer actively invokes
 * {@link #refuseConfiguredExtensionIssuers()}, which in turn enumerates the injected {@link Config};
 * nothing here is delegated to a collaborator's lifecycle callback that a never-touched proxy could
 * skip.
 * <p>
 * The record names configuration keys only, each rendered through
 * {@link RuleSupport#renderForMessage(String)} so a key cannot forge a log line. It never carries a
 * configured value, since an issuer key can hold a client secret or key material.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class ExtensionIssuerNamespaceGate {

    private static final CuiLogger LOGGER = new CuiLogger(ExtensionIssuerNamespaceGate.class);

    /**
     * The observer priority: one below {@code Interceptor.Priority.PLATFORM_BEFORE}, so the gate is
     * notified of {@code StartupEvent} ahead of the Quarkus scheduler and of every observer at the
     * default priority, the extension's startup initializer included.
     */
    static final int OBSERVER_PRIORITY = Interceptor.Priority.PLATFORM_BEFORE - 1;

    /** The property spelling of the extension's issuer namespace. */
    static final String PROPERTY_PREFIX = "sheriff.token.issuers.";

    /** The environment spelling of the extension's issuer namespace, matched in any letter case. */
    static final String ENVIRONMENT_PREFIX = "SHERIFF_TOKEN_ISSUERS_";

    private final Config config;

    /**
     * @param config the resolved configuration, enumerated for keys of the extension's issuer
     *               namespace
     */
    @Inject
    public ExtensionIssuerNamespaceGate(Config config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * Runs the gate on the real startup event, ahead of the extension's observers.
     *
     * @param event the Quarkus startup event
     * @throws IllegalStateException when a key of the extension's issuer namespace is configured —
     *                               the start is aborted
     */
    void onStartup(@Observes @Priority(OBSERVER_PRIORITY) StartupEvent event) {
        refuseConfiguredExtensionIssuers();
    }

    /**
     * Refuses the configuration when any key of the extension's issuer namespace is present.
     *
     * @throws IllegalStateException when at least one such key is configured, after logging
     *                               {@code ApiSheriff-203} naming every offending key
     */
    public void refuseConfiguredExtensionIssuers() {
        SortedSet<String> offending = offendingKeys(config);
        if (offending.isEmpty()) {
            LOGGER.debug("No sheriff.token.issuers.* key configured — the extension builds no validator of its own");
            return;
        }
        String rendered = offending.stream()
                .map(RuleSupport::renderForMessage)
                .collect(Collectors.joining("', '", "'", "'"));
        LOGGER.error(ConfigLogMessages.ERROR.EXTENSION_ISSUER_NAMESPACE_CONFIGURED, rendered);
        throw new IllegalStateException("Refusing to start — the token-validation extension's own issuer "
                + "namespace is configured: " + rendered);
    }

    /**
     * @param config the configuration to enumerate
     * @return every configured key in the extension's issuer namespace, in either spelling, sorted
     */
    static SortedSet<String> offendingKeys(Config config) {
        SortedSet<String> offending = new TreeSet<>();
        for (String name : config.getPropertyNames()) {
            if (inExtensionIssuerNamespace(name)) {
                offending.add(name);
            }
        }
        return offending;
    }

    /**
     * @param name a configuration key in property or environment spelling
     * @return whether the key belongs to the extension's issuer namespace
     */
    static boolean inExtensionIssuerNamespace(String name) {
        return name.startsWith(PROPERTY_PREFIX)
                || name.regionMatches(true, 0, ENVIRONMENT_PREFIX, 0, ENVIRONMENT_PREFIX.length());
    }
}
