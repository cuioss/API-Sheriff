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
/**
 * Protocol-level HTTP rules shared by every path that answers a client.
 * <p>
 * The package sits <em>below</em> both response-producing paths — the proxy data plane's streamed
 * relay in {@code edge} and the asset terminal action's buffered envelope in {@code asset} — and
 * depends on neither, so a rule about the HTTP protocol itself is declared once and read by both
 * rather than mirrored into each. {@link de.cuioss.sheriff.gateway.http.ConnectionHeaders} is that
 * rule for connection-specific response headers.
 * <p>
 * <strong>Framework-agnostic.</strong> The package carries no {@code io.vertx..} /
 * {@code io.quarkus..} / {@code jakarta..} imports and depends on no other gateway package, which
 * is what lets the framework-coupled {@code edge} and the agnostic {@code asset} both read it
 * without either acquiring a dependency on the other.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.http;

import org.jspecify.annotations.NullMarked;
