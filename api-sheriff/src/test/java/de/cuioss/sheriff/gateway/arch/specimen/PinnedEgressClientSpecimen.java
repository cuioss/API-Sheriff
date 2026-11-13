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
package de.cuioss.sheriff.gateway.arch.specimen;

import java.net.http.HttpClient;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;


import de.cuioss.sheriff.token.client.config.ClientConfiguration;

/**
 * Standing <strong>matched positive control</strong> for {@code EgressTlsPostureArchTest}: the same two
 * constructions {@link UnpinnedEgressClientSpecimen} makes, each carrying its posture call, so the rule
 * must accept it.
 * <p>
 * Without this counterpart a rule that failed on every construction call would satisfy the negative
 * control alone. Accepting this class while rejecting its unpinned twin is what proves the rule
 * discriminates on the posture call rather than on the construction.
 * <p>
 * Never invoked — it exists only to be read from bytecode.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PinnedEgressClientSpecimen {

    private static final String ISSUER = "https://idp.specimen.invalid";
    private static final String CLIENT_ID = "specimen-client";

    private PinnedEgressClientSpecimen() {
        // Specimen: it exists to be read from bytecode, never to be instantiated.
    }

    /**
     * A JDK client whose TLS context is passed explicitly.
     *
     * @return an HTTP client pinned to the JVM default TLS context
     * @throws NoSuchAlgorithmException when the JVM offers no default TLS context
     */
    static HttpClient pinnedJdkClient() throws NoSuchAlgorithmException {
        return HttpClient.newBuilder().sslContext(SSLContext.getDefault()).build();
    }

    /**
     * A back-channel configuration whose hostname posture is passed explicitly.
     *
     * @return a client configuration carrying an explicit hostname-verification posture
     */
    static ClientConfiguration pinnedBackChannel() {
        return ClientConfiguration.builder().issuer(ISSUER).clientId(CLIENT_ID).verifyHostname(true).build();
    }
}
