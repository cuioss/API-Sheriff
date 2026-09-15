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


import de.cuioss.sheriff.token.client.config.ClientConfiguration;

/**
 * Standing <strong>negative control</strong> for {@code EgressTlsPostureArchTest}: a class that
 * constructs two outbound TLS clients and passes neither its posture, so the rule has known violations
 * to detect.
 * <p>
 * It builds a JDK {@link HttpClient} through {@link HttpClient#newBuilder()} without calling
 * {@code sslContext} or {@code sslParameters}, and a token-sheriff {@link ClientConfiguration} through
 * {@link ClientConfiguration#builder()} without calling {@code verifyHostname}. Two families rather than
 * one, so the control proves the rule discriminates per family instead of failing on the first match.
 * <p>
 * <strong>Neither construction may gain its posture call</strong>, and no posture call for either family
 * may be added anywhere in this class. The rule reads co-occurrence per class, so either change silently
 * disarms the control: the rule would stop reporting a violation here and the {@code assertThrows}
 * guarding it would fail, which is the loud failure this constraint exists to keep loud. Its matched
 * counterpart is {@link PinnedEgressClientSpecimen}.
 * <p>
 * Never invoked — it exists only to be read from bytecode and rejected. Living in {@code src/test} it is
 * never packaged, so the deliberate violation carries no runtime consequence of its own.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UnpinnedEgressClientSpecimen {

    private static final String ISSUER = "https://idp.specimen.invalid";
    private static final String CLIENT_ID = "specimen-client";

    private UnpinnedEgressClientSpecimen() {
        // Specimen: it exists to be read from bytecode, never to be instantiated.
    }

    /**
     * The first deliberate violation: a JDK client with no explicit TLS context or parameters.
     *
     * @return an HTTP client relying on the builder's implicit TLS default
     */
    static HttpClient implicitJdkClient() {
        return HttpClient.newBuilder().build();
    }

    /**
     * The second deliberate violation: a back-channel configuration with no explicit hostname posture.
     *
     * @return a client configuration relying on the library's implicit hostname-verification default
     */
    static ClientConfiguration implicitBackChannel() {
        return ClientConfiguration.builder().issuer(ISSUER).clientId(CLIENT_ID).build();
    }
}
