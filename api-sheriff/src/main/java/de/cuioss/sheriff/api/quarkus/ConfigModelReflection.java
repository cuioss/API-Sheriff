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
package de.cuioss.sheriff.api.quarkus;

import de.cuioss.sheriff.api.config.model.AuthConfig;
import de.cuioss.sheriff.api.config.model.EndpointConfig;
import de.cuioss.sheriff.api.config.model.ForwardConfig;
import de.cuioss.sheriff.api.config.model.ForwardedConfig;
import de.cuioss.sheriff.api.config.model.GatewayConfig;
import de.cuioss.sheriff.api.config.model.HttpMethod;
import de.cuioss.sheriff.api.config.model.IssuerConfig;
import de.cuioss.sheriff.api.config.model.MatchConfig;
import de.cuioss.sheriff.api.config.model.Metadata;
import de.cuioss.sheriff.api.config.model.OidcConfig;
import de.cuioss.sheriff.api.config.model.Protocol;
import de.cuioss.sheriff.api.config.model.RateLimitConfig;
import de.cuioss.sheriff.api.config.model.RouteConfig;
import de.cuioss.sheriff.api.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.api.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.api.config.model.SecurityHeadersConfig;
import de.cuioss.sheriff.api.config.model.TlsConfig;
import de.cuioss.sheriff.api.config.model.TokenValidationConfig;
import de.cuioss.sheriff.api.config.model.UpstreamConfig;
import de.cuioss.sheriff.api.config.model.UpstreamDefaultsConfig;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Registers the framework-agnostic configuration model records for reflection so
 * Jackson can bind the YAML configuration trees into them in a native image.
 * <p>
 * The {@link de.cuioss.sheriff.api.config.model} records carry no framework imports
 * (ADR-0005 seam); this framework-bound holder consolidates their native
 * reflection registration in one place — listing every Jackson-bound record, its
 * nested records, and the bound enums — rather than annotating each model type.
 * The class has no runtime behavior; it exists solely to carry the
 * {@link RegisterForReflection} targets.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@RegisterForReflection(targets = {
        GatewayConfig.class,
        EndpointConfig.class,
        Metadata.class,
        TlsConfig.class,
        TlsConfig.Mtls.class,
        SecurityHeadersConfig.class,
        SecurityHeadersConfig.Hsts.class,
        SecurityHeadersConfig.Cors.class,
        SecurityDefaultsConfig.class,
        SecurityFilterConfig.class,
        UpstreamDefaultsConfig.class,
        ForwardedConfig.class,
        ForwardConfig.class,
        TokenValidationConfig.class,
        IssuerConfig.class,
        IssuerConfig.Jwks.class,
        OidcConfig.class,
        OidcConfig.Logout.class,
        OidcConfig.Session.class,
        OidcConfig.Csrf.class,
        OidcConfig.Refresh.class,
        OidcConfig.StepUp.class,
        AuthConfig.class,
        RouteConfig.class,
        MatchConfig.class,
        MatchConfig.HeaderMatcher.class,
        UpstreamConfig.class,
        UpstreamConfig.Retry.class,
        UpstreamConfig.NotModified.class,
        UpstreamConfig.CircuitBreaker.class,
        RateLimitConfig.class,
        HttpMethod.class,
        Protocol.class
})
public final class ConfigModelReflection {

    private ConfigModelReflection() {
        // Reflection-registration holder; not instantiable.
    }
}
