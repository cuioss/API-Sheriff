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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ManagementPlainHttpAudit}.
 * <p>
 * The load-bearing test here is {@link #warnsThroughTheRealStartupEventPath()}: it fires a
 * {@link StartupEvent} through the container's own event bus and asserts the warning appears, rather
 * than calling the observer method directly. Lesson 2026-07-20-18-002 is precisely that a
 * normal-scoped observer bean can get a lazy CDI proxy that is never touched — the check silently
 * never runs in production while a test that invokes the method directly stays green. Only delivery
 * through the real event bus proves the observer is registered and reachable.
 * <p>
 * The test boot resolves to plain management: the test configuration supplies its certificate through
 * {@code quarkus.http.ssl.certificate.*}, so the default TLS registry bucket carries no key material,
 * and no management certificate is configured. That is the same shape the opt-out produces, which is
 * why the positive case needs no override.
 */
@QuarkusTest
@EnableTestLogger
@DisplayName("Management plain-HTTP audit")
class ManagementPlainHttpAuditTest {

    @Inject
    Event<StartupEvent> startupEvent;

    @Test
    @DisplayName("Warns through the real startup-event path, not by direct invocation")
    void warnsThroughTheRealStartupEventPath() {
        startupEvent.fire(new StartupEvent());

        LogAsserts.assertLogMessagePresentContaining(TestLogLevel.WARN,
                "Management interface is serving PLAIN HTTP on port");
    }

    @Test
    @DisplayName("A resolved configuration carrying no key material means plain HTTP")
    void keyLessResolvedConfigurationMeansPlainHttp() {
        assertTrue(ManagementPlainHttpAudit.resolvesToPlainHttp(keyLessConfiguration(), true),
                "selecting a key-less bucket replaces the deployment's certificate rather than adding "
                        + "to it, so the listener ends up with no key material even when a certificate "
                        + "is configured");
    }

    @Test
    @DisplayName("A resolved configuration carrying key material means HTTPS")
    void keyBearingResolvedConfigurationMeansHttps() {
        assertFalse(ManagementPlainHttpAudit.resolvesToPlainHttp(keyBearingConfiguration(), false),
                "a bucket that carries key material keeps the management listener on HTTPS");
    }

    @Test
    @DisplayName("With no resolved configuration the management certificate decides")
    void withoutAResolvedConfigurationTheCertificateDecides() {
        assertFalse(ManagementPlainHttpAudit.resolvesToPlainHttp(null, true),
                "quarkus.management.ssl.certificate.* alone still produces HTTPS — this is the shipped "
                        + "default posture and it must not warn");
        assertTrue(ManagementPlainHttpAudit.resolvesToPlainHttp(null, false),
                "no bucket and no certificate leaves nothing to terminate with");
    }

    private static TlsConfiguration keyLessConfiguration() {
        return new BaseTlsConfiguration() {
        };
    }

    private static TlsConfiguration keyBearingConfiguration() {
        return new BaseTlsConfiguration() {
            @Override
            public KeyCertOptions getKeyStoreOptions() {
                return new PemKeyCertOptions();
            }
        };
    }
}
