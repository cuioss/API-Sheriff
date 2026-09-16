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
 * The package sits <em>below</em> the paths that produce a response, and depends on none of them, so
 * a rule about the HTTP protocol itself is declared once and read by every caller rather than
 * mirrored into each. Two such rules live here:
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.http.ConnectionHeaders} — the connection-specific header
 *       names neither response path may relay, read by the proxy data plane's streamed relay in
 *       {@code edge} and the asset terminal action's buffered envelope in {@code asset}.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.http.LocationPathReview} — the open-redirect review of a
 *       gateway path about to be written into a {@code Location} header, read by the boot review of
 *       a <em>configured</em> redirect target in {@code config.validation} and by the per-request
 *       mapping of an <em>upstream</em> {@code Location} in {@code routing}.</li>
 * </ul>
 * Both exist for the same reason: a mirrored copy is what lets two paths quietly disagree about one
 * protocol rule (issue #172 was exactly that, for a response header the proxy path stripped and the
 * asset path did not).
 * <p>
 * <strong>Framework-agnostic.</strong> The package carries no {@code io.vertx..} /
 * {@code io.quarkus..} / {@code jakarta..} imports and depends on no other gateway package, which
 * is what lets the framework-coupled {@code edge} and the agnostic {@code asset} both read it
 * without either acquiring a dependency on the other. Its only library dependency is the
 * {@code de.cuioss.http.security} inbound-validation surface the location review delegates to.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.http;

import org.jspecify.annotations.NullMarked;
