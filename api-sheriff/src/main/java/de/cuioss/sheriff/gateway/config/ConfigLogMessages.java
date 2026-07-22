/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;

import lombok.experimental.UtilityClass;

/**
 * DSL-style {@link LogRecord} catalogue for the configuration subsystem.
 * <p>
 * Structured {@code INFO} / {@code ERROR} messages carry the {@code ApiSheriff}
 * prefix and a stable numeric identifier, continuing the shared identifier space
 * used by the proxy edge, so they are greppable and assertable. {@code DEBUG} /
 * {@code TRACE} diagnostics use the logger directly and are not catalogued here.
 * <p>
 * The catalogue lives in the framework-agnostic {@code ...config} package because
 * the cui-tools {@link LogRecord} abstraction carries no framework dependency
 * (ADR-0005).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public final class ConfigLogMessages {

    private static final String PREFIX = "ApiSheriff";

    /**
     * Info-level messages (INFO range 1-99).
     */
    @UtilityClass
    public static final class INFO {

        /** The configuration was loaded, validated, and assembled successfully. */
        public static final LogRecord CONFIG_LOADED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(2)
                .template("Configuration loaded successfully (config_version='%s')")
                .build();

        /**
         * The materialized effective posture of a single route, printed once per
         * route during route-table assembly (the ADR-0007 discoverability answer:
         * anchors vanish at runtime, so the boot log reports the resolved posture).
         */
        public static final LogRecord ROUTE_POSTURE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(3)
                .template("Route '%s' effective posture: anchor='%s', auth.require='%s', filter='%s'")
                .build();
    }

    /**
     * Warn-level messages (WARN range 100-199).
     */
    @UtilityClass
    public static final class WARN {

        /**
         * A route replaces an anchor-provided non-auth policy block (wholesale
         * replacement). Logged so a weakening override is always an explicit, audited
         * choice (ADR-0007).
         */
        public static final LogRecord ANCHOR_POLICY_OVERRIDDEN = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(101)
                .template("Route '%s' overrides anchor '%s' %s policy (wholesale replacement)")
                .build();

        /**
         * A {@code trusted_proxies} CIDR entry covers a very broad — but not total —
         * address range (shorter than {@code /8} for IPv4 or {@code /32} for IPv6).
         * Broad proxy trust widens the set of hosts able to spoof forwarded headers,
         * so an overly broad prefix is surfaced as a boot WARN for review (D5).
         */
        public static final LogRecord BROAD_TRUSTED_PROXY = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(102)
                .template("trusted_proxies entry '%s' covers a very broad address range (prefix /%s) — review whether such broad proxy trust is intended")
                .build();
    }

    /**
     * Error-level messages (ERROR range 200-299).
     */
    @UtilityClass
    public static final class ERROR {

        /** A single collected configuration violation, one per problem found. */
        public static final LogRecord CONFIG_VALIDATION_FAILED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(200)
                .template("Invalid configuration in '%s' at '%s': %s")
                .build();

        /** Startup is aborted because the configuration is invalid. */
        public static final LogRecord CONFIG_STARTUP_ABORTED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(201)
                .template("Refusing to start — configuration is invalid: %s")
                .build();
    }
}
