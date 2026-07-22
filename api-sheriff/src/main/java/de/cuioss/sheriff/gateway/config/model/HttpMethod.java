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
package de.cuioss.sheriff.gateway.config.model;

/**
 * The proxyable HTTP verbs the gateway is permitted to serve.
 * <p>
 * {@code TRACE} and {@code CONNECT} are deliberately absent: they are not
 * proxyable through the data plane and are never permitted. Any configuration
 * naming either verb is rejected by the configuration validator with a boot
 * failure — they are simply not representable in the model.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum HttpMethod {

    /** HTTP {@code GET}. */
    GET,
    /** HTTP {@code HEAD}. */
    HEAD,
    /** HTTP {@code POST}. */
    POST,
    /** HTTP {@code PUT}. */
    PUT,
    /** HTTP {@code PATCH}. */
    PATCH,
    /** HTTP {@code DELETE}. */
    DELETE,
    /** HTTP {@code OPTIONS}. */
    OPTIONS
}
