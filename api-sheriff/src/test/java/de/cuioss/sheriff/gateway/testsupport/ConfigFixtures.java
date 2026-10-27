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
package de.cuioss.sheriff.gateway.testsupport;

import java.util.Set;

import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EnumerationDetectionConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.MatchConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.routing.Route;
import lombok.experimental.UtilityClass;

/**
 * Pre-seeded builders for the configuration this module's tests build again and again.
 * <p>
 * Every method returns a <em>builder</em>, never a finished record. The seed holds only what every
 * caller would otherwise write identically, so a test still states, on its own line, the one field
 * its behaviour depends on:
 * {@snippet :
 * GatewayConfig config = ConfigFixtures.gateway().oidc(oidc).build();
 * Route route = ConfigFixtures.route("api", "/api").upstream(upstream).build();
 * }
 * <p>
 * Thread-safe: the class is stateless and every call returns a fresh builder.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ConfigFixtures {

    /**
     * The gateway document every fixture starts from.
     * <p>
     * It declares {@code enumeration_detection.enabled: false}. The edge tests built on it send many
     * rejected requests from one loopback source; under the shipped default {@code observe} those would
     * change no answer, but they would trip the source and raise its WARN and event-counter side effects
     * in tests about something else. A test about detection overrides the block, or sets it to
     * {@code null} to run the omitted-block default.
     *
     * @return a builder carrying {@code version: 1} and the disabled detection block, nothing else
     */
    public GatewayConfig.GatewayConfigBuilder gateway() {
        return GatewayConfig.builder().version(1)
                .enumerationDetection(EnumerationDetectionConfig.builder().enabled(false).build());
    }

    /**
     * A seed route: an HTTP route under a path prefix that requires no authentication and admits only
     * {@code GET}.
     * <p>
     * The seed is not what route-table assembly resolves for a route that declares neither field; it
     * differs in two ways. A route no scope declares {@code allowed_methods} for resolves to every
     * standard HTTP method, not to {@code GET} alone ({@code PolicyBlock.ALLOWED_METHODS}). A route no
     * scope gives an {@code auth} block is refused at boot, not resolved to {@code require: none}
     * ({@code PolicyBlock.AUTH}).
     * <p>
     * The caller adds what the case is about — typically the upstream, and any other method, posture
     * or block — and may override every seeded field.
     *
     * @param id         the route id
     * @param pathPrefix the {@code match.path_prefix}
     * @return the seeded builder
     */
    public Route.RouteBuilder route(String id, String pathPrefix) {
        return Route.builder()
                .id(id)
                .protocol(Protocol.HTTP)
                .match(MatchConfig.builder().pathPrefix(pathPrefix).build())
                .effectiveAuth(AuthConfig.builder().require(Require.NONE).build())
                .effectiveAllowedMethods(Set.of(HttpMethod.GET));
    }
}
