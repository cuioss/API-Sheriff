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
package de.cuioss.sheriff.gateway.config;

import java.util.List;


import lombok.experimental.UtilityClass;

/**
 * The single definition of which configuration keys count as <em>declared</em> server key material,
 * for both listeners.
 *
 * <h2>Why the shared artifact is the key NAMES rather than a shared call</h2>
 *
 * The consumers run at different times and can only ask their question through different APIs.
 * {@code ServerTlsDeclarationGate} runs from the {@code HttpServerOptionsCustomizer} hook, before
 * the listener is built and therefore before any listener state exists to inspect, and reads keys
 * by name off the resolved {@link org.eclipse.microprofile.config.Config}. The two startup audits
 * ({@code TerminatedListenerTlsAudit}, {@code ManagementPlainHttpAudit}) run at
 * {@code StartupEvent} and receive their values through {@code @ConfigProperty} injection, which
 * needs a <em>compile-time constant</em> name per injected parameter. No single runtime call can
 * serve both, so what is shared is the vocabulary: the constants below, and the per-prefix
 * {@link List} views over them.
 * <p>
 * {@code DeclaredKeyMaterialKeysDriftTest} reads each audit constructor's {@code @ConfigProperty}
 * annotations reflectively and asserts the injected name sets equal the lists here, so adding a
 * spelling at one site without the other fails the build rather than reintroducing the disagreement
 * this class was extracted to end.
 *
 * <h2>Every spelling Quarkus honours, and why nothing may be dropped</h2>
 *
 * {@code TlsUtils.computeKeyStoreOptions} accepts server key material through four distinct
 * {@code ssl.certificate.*} spellings: a PEM chain ({@code files} + {@code key-files}), a keystore
 * file ({@code key-store-file}), and a credentials provider supplying the keystore password
 * ({@code credentials-provider}). A predicate that reads only {@code files} sees no material on a
 * keystore-based or credentials-provider-based deployment and reports plain HTTP for a listener that
 * is terminating TLS perfectly well. Narrowing this set is therefore never a safe simplification: it
 * turns a correct HTTPS deployment into a false plain-HTTP report on the audits, and it would turn
 * the same deployment into an inferred cleartext downgrade under any scheme that read intent out of
 * a missing certificate.
 * <p>
 * A key that is present but <em>blank</em> is not a declaration. A compose file's bare
 * {@code QUARKUS_HTTP_SSL_CERTIFICATE_FILES=} and a test profile's empty override both produce that
 * shape, and neither supplies any material; every consumer of these names applies the same rule.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public final class DeclaredKeyMaterialKeys {

    /** The exposure strategy: the one key that expresses a deliberate plain-HTTP opt-in. */
    public static final String INSECURE_REQUESTS = "quarkus.http.insecure-requests";

    /** The value of {@value #INSECURE_REQUESTS} that opts the main listener into plain HTTP. */
    public static final String INSECURE_REQUESTS_ENABLED = "enabled";

    /** The named TLS registry bucket selected for the terminated main listener. */
    public static final String HTTP_TLS_CONFIGURATION_NAME = "quarkus.http.tls-configuration-name";

    /** The named TLS registry bucket selected for the management interface. */
    public static final String MANAGEMENT_TLS_CONFIGURATION_NAME =
            "quarkus.management.tls-configuration-name";

    /** Main listener — the PEM certificate chain. */
    public static final String HTTP_CERTIFICATE_FILES = "quarkus.http.ssl.certificate.files";

    /** Main listener — the PEM private keys matching {@value #HTTP_CERTIFICATE_FILES}. */
    public static final String HTTP_CERTIFICATE_KEY_FILES = "quarkus.http.ssl.certificate.key-files";

    /** Main listener — a keystore file carrying the chain and its key. */
    public static final String HTTP_CERTIFICATE_KEY_STORE_FILE =
            "quarkus.http.ssl.certificate.key-store-file";

    /** Main listener — the credentials provider supplying the keystore password. */
    public static final String HTTP_CERTIFICATE_CREDENTIALS_PROVIDER =
            "quarkus.http.ssl.certificate.credentials-provider";

    /** Management interface — the PEM certificate chain. */
    public static final String MANAGEMENT_CERTIFICATE_FILES =
            "quarkus.management.ssl.certificate.files";

    /** Management interface — the PEM private keys matching {@value #MANAGEMENT_CERTIFICATE_FILES}. */
    public static final String MANAGEMENT_CERTIFICATE_KEY_FILES =
            "quarkus.management.ssl.certificate.key-files";

    /** Management interface — a keystore file carrying the chain and its key. */
    public static final String MANAGEMENT_CERTIFICATE_KEY_STORE_FILE =
            "quarkus.management.ssl.certificate.key-store-file";

    /** Management interface — the credentials provider supplying the keystore password. */
    public static final String MANAGEMENT_CERTIFICATE_CREDENTIALS_PROVIDER =
            "quarkus.management.ssl.certificate.credentials-provider";

    /** The main listener's four certificate spellings, in the order they are documented. */
    public static final List<String> HTTP_CERTIFICATE_KEYS = List.of(
            HTTP_CERTIFICATE_FILES,
            HTTP_CERTIFICATE_KEY_FILES,
            HTTP_CERTIFICATE_KEY_STORE_FILE,
            HTTP_CERTIFICATE_CREDENTIALS_PROVIDER);

    /** The management interface's four certificate spellings, mirroring the main listener's. */
    public static final List<String> MANAGEMENT_CERTIFICATE_KEYS = List.of(
            MANAGEMENT_CERTIFICATE_FILES,
            MANAGEMENT_CERTIFICATE_KEY_FILES,
            MANAGEMENT_CERTIFICATE_KEY_STORE_FILE,
            MANAGEMENT_CERTIFICATE_CREDENTIALS_PROVIDER);

    /** The prefix every main-listener certificate key carries. */
    public static final String HTTP_CERTIFICATE_PREFIX = "quarkus.http.ssl.certificate.";

    /** The prefix every management certificate key carries. */
    public static final String MANAGEMENT_CERTIFICATE_PREFIX = "quarkus.management.ssl.certificate.";
}
