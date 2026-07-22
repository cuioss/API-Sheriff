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
package de.cuioss.sheriff.gateway.auth;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.inject.Qualifier;

/**
 * CDI qualifier distinguishing the gateway's own {@code TokenValidator} — the single shared
 * validator {@link TokenValidatorProducer} builds from the {@code token_validation} block of
 * {@code gateway.yaml} — from the unqualified validator the {@code token-sheriff-validation-quarkus}
 * extension produces from its {@code cui.jwt.*} property surface.
 * <p>
 * The gateway configures issuers through its own YAML model, not the extension's MicroProfile
 * properties, so the two producers must coexist without an ambiguous-dependency clash. The gateway
 * request pipeline injects {@code @GatewayValidator TokenValidator}; the extension's own beans keep
 * their unqualified default.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@Qualifier
@Documented
@Retention(RUNTIME)
@Target({METHOD, FIELD, PARAMETER, TYPE})
public @interface GatewayValidator {
}
