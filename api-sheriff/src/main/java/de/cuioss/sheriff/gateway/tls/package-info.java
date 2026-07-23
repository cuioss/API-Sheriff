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
 * The accept-time TLS edge: the Vert.x {@link io.vertx.core.net.NetServer} front listener that
 * reassembles the full TLS ClientHello (RFC 6066), reads the SNI, and either opaquely L4-relays a
 * {@code tls.passthrough_sni} match to the topology-resolved backend (the gateway never handshakes)
 * or hands the still-encrypted stream to the internal terminated Quarkus HTTPS listener.
 * <p>
 * Fail-closed (GW-06): the full ClientHello is reassembled before any decision, and an
 * empty/unresolved/malformed SNI always takes the terminated-strict path — never a passthrough. When
 * {@code tls.passthrough_sni} is empty the front listener is never started, so the default
 * single-listener topology is unchanged (zero-overhead default).
 * <p>
 * This package is framework-coupled (Vert.x) and is therefore outside the ADR-0005
 * framework-agnostic arch-gate rule set, like {@code edge} and {@code routing}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.tls;

import org.jspecify.annotations.NullMarked;
