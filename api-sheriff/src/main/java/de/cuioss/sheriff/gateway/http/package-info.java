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
 * Protocol-level HTTP rules, and the URL-path primitive they rest on, shared by every path that
 * answers a client.
 * <p>
 * The package sits <em>below</em> the paths that produce a response, and depends on none of them, so
 * a rule about the HTTP protocol itself is declared once and read by every caller rather than
 * mirrored into each. Five such members live here:
 * <ul>
 *   <li>{@link de.cuioss.sheriff.gateway.http.ConnectionHeaders} — the connection-specific header
 *       names neither response path may relay, read by the proxy data plane's streamed relay in
 *       {@code edge} and the asset terminal action's buffered envelope in {@code asset}.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.http.LocationPathReview} — the open-redirect review of a
 *       gateway path about to be written into a {@code Location} header or handed to a browser as a
 *       link target, read by the boot review of a <em>configured</em> redirect target
 *       ({@code TerminalActionRules}) and of the portal path, its catalog entries and the reserved
 *       OIDC paths ({@code PortalRules}, {@code ReservedPathRules}) in {@code config.validation}, by
 *       the per-request mapping of an <em>upstream</em> {@code Location} in {@code routing}, and by
 *       the BFF return URL a login, widening or step-up lands on in {@code bff.pending}
 *       ({@code PendingAuthorizationRecord.sameOrigin}, which {@code OidcClientRules} also applies
 *       to {@code oidc.login.default_return_url} at boot).</li>
 *   <li>{@link de.cuioss.sheriff.gateway.http.GatewayCookies} — the gateway's own cookies, which an
 *       upstream may neither set nor receive, read by the response relay in {@code edge}.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.http.UpstreamIdentityHeaders} — the upstream response
 *       headers that name the server software behind the gateway, read by the proxy relay in
 *       {@code edge} and the upstream-backed asset envelope in {@code asset}.</li>
 *   <li>{@link de.cuioss.sheriff.gateway.http.PathSlashes} — the leading- and trailing-slash
 *       trimming every path join and split is built from, read by the route-table join in
 *       {@code config}, the {@code Location} mapping in {@code routing}, the match-key remainder and
 *       upstream request URI in {@code edge}, and the asset confinement and index lookup in
 *       {@code asset}.</li>
 * </ul>
 * All five exist for the same reason: a mirrored copy is what lets two paths quietly disagree about
 * one rule (issue #172 was exactly that, for a response header the proxy path stripped and the asset
 * path did not; issue #327 was seven private copies of the slash trimming, where only a reader of all
 * seven could tell how many slashes each one removed).
 * <p>
 * <strong>Framework-agnostic.</strong> The package carries no {@code io.vertx..} /
 * {@code io.quarkus..} / {@code jakarta..} imports, which is what lets the framework-coupled
 * {@code edge} and the agnostic {@code asset} both read it without either acquiring a dependency on
 * the other. Its only gateway dependencies are the ones {@code GatewayCookies} derives its names
 * from: the bound {@code config.model} and the cookie-name constants of the framework-free BFF cookie
 * codecs. Its only library dependency is the {@code de.cuioss.http.security} inbound-validation
 * surface the location review delegates to.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@NullMarked
package de.cuioss.sheriff.gateway.http;

import org.jspecify.annotations.NullMarked;
