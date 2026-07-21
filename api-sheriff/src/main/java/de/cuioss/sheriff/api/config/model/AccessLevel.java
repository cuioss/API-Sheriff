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
package de.cuioss.sheriff.api.config.model;

/**
 * The audience an anchor admits (decision: ADR-0013).
 * <p>
 * Every anchor declares a required {@code access} level; it is a classification
 * axis orthogonal to {@link AnchorType type}, enforced fail-closed at boot by the
 * access-to-auth matrix: {@link #AUTHENTICATED} requires a fully-backed auth
 * posture, and {@link #PUBLIC} combined with an auth block is a boot failure.
 * <p>
 * The constant name {@code PUBLIC} is the Java-safe rendering of the {@code public}
 * configuration value (Java reserves the lowercase word); the case-insensitive YAML
 * binding maps {@code public} / {@code authenticated} onto these constants.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public enum AccessLevel {

    /** An anonymous surface: no authentication required. */
    PUBLIC,
    /** An authenticated surface: requires a fully-backed auth posture. */
    AUTHENTICATED
}
