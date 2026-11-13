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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalInt;

import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ConnectionCapAudit}: a configured {@code edge_hardening.admission_cap} that the
 * connection cap of the terminated listener cannot honour is reported at boot, and one it can honour
 * is not.
 * <p>
 * The load-bearing test is {@link #warnsThroughTheRealStartupEventPath()}: it fires a
 * {@link StartupEvent} through the container's own event bus under a profile that lowers the cap below
 * the default admission cap, rather than calling the observer directly, because a normal-scoped
 * observer bean can hold a lazy proxy that is never touched and then never runs in production while a
 * test calling the method directly stays green. The direct cases pin the boundary: an admission cap
 * above or equal to the connection cap warns, one below it does not (matched control), and a listener
 * with no declared cap has nothing to honour.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@QuarkusTest
@TestProfile(MainListenerConnectionCapTest.SmallConnectionCapProfile.class)
@EnableTestLogger
@Tag("quarkus-fork")
@DisplayName("Connection-cap audit — an admission_cap the connection cap cannot honour")
class ConnectionCapAuditTest {

    private static final int CONNECTION_CAP = 4096;

    private static final String WARNING_FRAGMENT = "edge_hardening.admission_cap";

    @Inject
    Event<StartupEvent> startupEvent;

    private static ConnectionCapAudit auditOf(int admissionCap, OptionalInt connectionCap) {
        EdgeHardeningConfig hardening = EdgeHardeningConfig.builder().admissionCap(admissionCap).build();
        return new ConnectionCapAudit(new EdgeHardeningOptions(hardening), connectionCap);
    }

    @Test
    @DisplayName("Warns through the real startup-event path, not by direct invocation")
    void warnsThroughTheRealStartupEventPath() {
        startupEvent.fire(new StartupEvent());

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, WARNING_FRAGMENT);
    }

    @Test
    @DisplayName("Warns when the admission cap lies above the connection cap")
    void warnsWhenAdmissionCapIsAboveTheConnectionCap() {
        boolean warned = auditOf(CONNECTION_CAP + 1000, OptionalInt.of(CONNECTION_CAP)).audit();

        assertTrue(warned, "an admission_cap above the connection cap is not honoured on HTTP/1.1");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, WARNING_FRAGMENT);
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, String.valueOf(CONNECTION_CAP + 1000));
    }

    @Test
    @DisplayName("Warns when the admission cap equals the connection cap")
    void warnsWhenAdmissionCapEqualsTheConnectionCap() {
        boolean warned = auditOf(CONNECTION_CAP, OptionalInt.of(CONNECTION_CAP)).audit();

        assertTrue(warned, "at equality the cap closes connections before admission can answer 503");
        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN, WARNING_FRAGMENT);
    }

    @Test
    @DisplayName("Stays silent when the admission cap lies below the connection cap (matched control)")
    void staysSilentWhenAdmissionCapIsBelowTheConnectionCap() {
        boolean warned = auditOf(CONNECTION_CAP - 1, OptionalInt.of(CONNECTION_CAP)).audit();

        assertFalse(warned, "the connection cap leaves headroom above the admission cap");
        LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, ConnectionCapAudit.class);
    }

    @Test
    @DisplayName("Stays silent when no connection cap is declared")
    void staysSilentWhenNoConnectionCapIsDeclared() {
        boolean warned = auditOf(CONNECTION_CAP * 4, OptionalInt.empty()).audit();

        assertFalse(warned, "with no cap declared nothing closes connections ahead of admission");
        LogAsserts.assertNoLogMessagePresent(TestLogLevel.WARN, ConnectionCapAudit.class);
    }
}
