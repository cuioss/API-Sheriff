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
 * The kind of surface an anchor classifies (decision: ADR-0013).
 * <p>
 * Every anchor declares a required {@code type}; it is a classification axis
 * orthogonal to {@link AccessLevel access}, and selects what terminal behaviour
 * the anchor's routes expose. The former {@code passthrough} value is renamed to
 * {@link #PROXY} — an anchor-{@code type} value rename only; the unrelated TLS-L4
 * {@code tls.passthrough_sni} surface keeps its name and is untouched.
 * <p>
 * The configuration values ({@code proxy} / {@code bff} / {@code asset}) are
 * lowercase; the case-insensitive YAML binding maps them onto these constants.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum AnchorType {

    /** A plain reverse-proxy surface (formerly {@code passthrough}). */
    PROXY,
    /** A Backend-For-Frontend surface. */
    BFF,
    /** A static-asset surface (decision: ADR-0014). */
    ASSET
}
