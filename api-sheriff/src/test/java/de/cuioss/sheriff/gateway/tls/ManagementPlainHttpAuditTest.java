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
package de.cuioss.sheriff.gateway.tls;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;


import de.cuioss.test.juli.LogAsserts;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
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
 * <p>
 * <strong>The default-bucket regression.</strong> {@link #defaultBucketWithKeyMaterialIsNotPlainHttp()}
 * pins the false positive this audit used to carry: it was built on {@code TlsConfiguration.from},
 * which never consults the registry's default bucket, while the recorder does — so a populated
 * default {@code quarkus.tls.key-store.*} bucket produced a management listener on HTTPS that this
 * audit reported as plain HTTP. The audit is constructed directly there rather than booted, because
 * the regression is about which registry lookups the audit performs and a stub registry is the only
 * way to present a populated default bucket without adding key material to the shipped test boot —
 * which {@code application.properties} forbids by name. The startup-event test above remains the
 * proof that the observer path itself works.
 */
@QuarkusTest
@EnableTestLogger
@DisplayName("Management plain-HTTP audit")
class ManagementPlainHttpAuditTest {

    private static final int MANAGEMENT_PORT = 9000;

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
    @DisplayName("A default bucket carrying key material is not reported as plain HTTP")
    void defaultBucketWithKeyMaterialIsNotPlainHttp() {
        ManagementPlainHttpAudit audit = auditOver(defaultBucketRegistry(keyBearingConfiguration()),
                Optional.empty());

        assertFalse(audit.auditManagementTls(),
                "the recorder takes the default registry bucket whenever it carries key material, so "
                        + "the listener is on HTTPS and reporting plain HTTP is a false positive");
    }

    @Test
    @DisplayName("A key-less default bucket with no management certificate still means plain HTTP")
    void keyLessDefaultBucketStillMeansPlainHttp() {
        ManagementPlainHttpAudit audit = auditOver(defaultBucketRegistry(keyLessConfiguration()),
                Optional.empty());

        assertTrue(audit.auditManagementTls(),
                "the positive control for the case above — a default bucket without key material is "
                        + "not a fallback the recorder takes, so nothing terminates the port");
    }

    @Test
    @DisplayName("A key-less default bucket with a management certificate means HTTPS")
    void keyLessDefaultBucketWithCertificateMeansHttps() {
        ManagementPlainHttpAudit audit = auditOver(defaultBucketRegistry(keyLessConfiguration()),
                Optional.of(List.of("management.crt")));

        assertFalse(audit.auditManagementTls(),
                "quarkus.management.ssl.certificate.* alone still produces HTTPS — the shipped "
                        + "posture for a TLS-terminated management port, which must not warn");
    }

    private static ManagementPlainHttpAudit auditOver(TlsConfigurationRegistry registry,
            Optional<List<String>> certificateFiles) {
        return new ManagementPlainHttpAudit(registry, Optional.empty(), certificateFiles,
                MANAGEMENT_PORT);
    }

    /**
     * A registry holding only a default bucket — the shape the F1 regression turns on, since the
     * audit selects no configuration name.
     *
     * @param fallback the configuration returned as the default bucket
     * @return a registry over that single answer
     */
    private static TlsConfigurationRegistry defaultBucketRegistry(TlsConfiguration fallback) {
        return new TlsConfigurationRegistry() {

            @Override
            public Optional<TlsConfiguration> get(String name) {
                return Optional.empty();
            }

            @Override
            public Optional<TlsConfiguration> getDefault() {
                return Optional.of(fallback);
            }

            @Override
            public void register(String name, TlsConfiguration configuration) {
                throw new UnsupportedOperationException("the audit never registers");
            }
        };
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
