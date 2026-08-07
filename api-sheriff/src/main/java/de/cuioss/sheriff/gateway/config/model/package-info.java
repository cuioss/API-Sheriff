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
/**
 * Immutable configuration model for the API Sheriff gateway.
 * <p>
 * The records in this package are the binding target for the YAML configuration
 * loader: {@code gateway.yaml} binds to {@link de.cuioss.sheriff.gateway.config.model.GatewayConfig}
 * and each {@code endpoints/*.yaml} file binds to
 * {@link de.cuioss.sheriff.gateway.config.model.EndpointConfig}. Configuration is
 * <em>static, file-based, and immutable</em>: it is read once at startup,
 * validated in full, frozen into these value objects, and never mutated at
 * runtime.
 * <p>
 * <strong>Immutability and thread-safety.</strong> Every type here is an
 * immutable Java record. Collection components are defensively copied into
 * unmodifiable collections by the canonical constructors. Under this package's
 * {@link org.jspecify.annotations.NullMarked} default every component is
 * non-null unless it is explicitly annotated
 * {@link org.jspecify.annotations.Nullable}: an absent optional scalar is
 * represented as {@code null}, and an absent collection as an empty collection.
 * The one deliberate exception is
 * {@link de.cuioss.sheriff.gateway.config.model.ForwardConfig}'s four list
 * components, which are explicitly {@code @Nullable} because <em>absent</em> and
 * <em>declared empty</em> select different forward modes there and collapsing the
 * two would erase the distinction. Instances are safe to publish and share across
 * threads without external synchronization.
 * <p>
 * <strong>Framework-agnostic seam (ADR-0005).</strong> This package carries no
 * CDI, Quarkus, Vert.x, MicroProfile, or Micrometer imports. Collaborators are
 * supplied by constructor parameters; the framework-bound wiring lives in the
 * edge package.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.config.model;

import org.jspecify.annotations.NullMarked;
