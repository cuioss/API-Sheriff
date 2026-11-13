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
package de.cuioss.sheriff.gateway.edge.admission;

import java.util.Objects;
import java.util.OptionalInt;

import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.tools.logging.CuiLogger;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Audits, at startup, whether the configured {@code edge_hardening.admission_cap} is below the
 * connection cap of the terminated listener, and emits {@code WARN ApiSheriff-148} when it is not.
 * <p>
 * <strong>What the audit protects against.</strong> The connection cap
 * ({@code quarkus.http.limits.max-connections}) is a fixed deployment value, not a {@code gateway.yaml}
 * key, while the admission cap is configurable and has no schema maximum. On HTTP/1.1 one request needs
 * one connection, so an admission cap at or above the connection cap is never reached: the cap closes
 * connections, unserved and without a {@code 503}, first. Without this audit the configured number would
 * be capped silently, which the never-silent-downgrade rule forbids.
 * <p>
 * <strong>Why a WARN and never a boot refusal.</strong> A deployment that is mostly HTTP/2, or that
 * raises the connection cap together with its descriptor limit, may legitimately configure a larger
 * admission cap; refusing it would block that. The record names both remedies.
 * <p>
 * <strong>The boundary.</strong> The audit warns when the admission cap is greater than <em>or equal
 * to</em> the connection cap: at equality HTTP/1.1 admission still cannot answer first, which is the
 * state the shipped headroom exists to avoid. With no connection cap declared nothing closes connections
 * ahead of admission, so there is nothing to honour and nothing to warn about.
 * <p>
 * <strong>Lazy-proxy hazard.</strong> The observer actively invokes {@link #audit()}, which reads the
 * injected {@link EdgeHardeningOptions} (a normal-scoped bean) — a held but untouched proxy would
 * silently never run the check. The accompanying test fires the {@link StartupEvent} through the real
 * event bus rather than calling the method directly.
 * <p>
 * Thread-safety: immutable after construction; the audit runs once on the startup event.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@ApplicationScoped
public class ConnectionCapAudit {

    private static final CuiLogger LOGGER = new CuiLogger(ConnectionCapAudit.class);

    private final EdgeHardeningOptions options;
    private final OptionalInt connectionCap;

    /**
     * @param options       the resolved admission budget
     * @param connectionCap the terminated listener's connection cap, empty when none is declared
     */
    @Inject
    public ConnectionCapAudit(EdgeHardeningOptions options,
            @ConfigProperty(name = "quarkus.http.limits.max-connections") OptionalInt connectionCap) {
        this.options = Objects.requireNonNull(options, "options");
        this.connectionCap = Objects.requireNonNull(connectionCap, "connectionCap");
    }

    /**
     * Runs the audit on the real startup event.
     *
     * @param event the Quarkus startup event
     */
    void onStartup(@Observes StartupEvent event) {
        audit();
    }

    /**
     * Compares the configured admission cap with the connection cap and warns when the connection cap
     * cannot honour it.
     *
     * @return {@code true} when the admission cap is not below the connection cap and the warning was
     *         emitted
     */
    public boolean audit() {
        if (connectionCap.isEmpty()) {
            return false;
        }
        int admissionCap = options.admissionCap();
        int cap = connectionCap.getAsInt();
        if (admissionCap < cap) {
            return false;
        }
        LOGGER.warn(ConfigLogMessages.WARN.ADMISSION_CAP_NOT_BELOW_CONNECTION_CAP, admissionCap, cap, cap);
        return true;
    }
}
