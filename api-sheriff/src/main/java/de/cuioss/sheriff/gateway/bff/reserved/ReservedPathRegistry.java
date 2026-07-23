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
package de.cuioss.sheriff.gateway.bff.reserved;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import de.cuioss.sheriff.gateway.config.model.OidcConfig;

/**
 * The exact-match registry of the gateway's reserved OIDC endpoints (D2).
 * <p>
 * The BFF variants carve four gateway-owned paths out of the proxy route table — the
 * {@code oidc.redirect_uri} callback, the RP-initiated {@code oidc.logout.path}, its
 * {@code post_logout_redirect_uri} return leg, and the {@code oidc.logout.backchannel_path}
 * receiver. Each is matched <strong>exactly</strong> (never by prefix) and <strong>only on the
 * OIDC host</strong> (the host of {@code oidc.redirect_uri}). The gateway edge consults this
 * registry <em>before</em> the route table, so a proxy route such as {@code path_prefix: /auth}
 * can never swallow the exact {@code /auth/callback}: the reserved path is resolved here first
 * and the prefix route never sees it.
 * <p>
 * The registry is built once at boot from the frozen {@link OidcConfig}. When no {@code oidc}
 * block (or no {@code redirect_uri}) is configured the registry is {@linkplain #isEmpty() empty}
 * and {@link #match(String, String)} never matches — the pure-proxy gateway is unchanged. The
 * type is framework-agnostic (raw host/path strings only, no JAX-RS/Vert.x coupling), so it is
 * unit-testable without a container.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ReservedPathRegistry {

    /**
     * The kind of reserved gateway endpoint an exact host+path match resolves to. The runtime
     * handler for each kind is wired by the session runtime; this registry only classifies the
     * carve-out.
     */
    public enum ReservedEndpoint {

        /** The {@code oidc.redirect_uri} OIDC auth-code callback. */
        CALLBACK,

        /** The RP-initiated {@code oidc.logout.path}. */
        LOGOUT,

        /** The {@code post_logout_redirect_uri} return leg of RP-initiated logout. */
        LOGOUT_RETURN,

        /** The {@code oidc.logout.backchannel_path} back-channel logout receiver. */
        BACKCHANNEL_LOGOUT
    }

    private final @Nullable String oidcHost;
    private final Map<String, ReservedEndpoint> endpointsByPath;

    private ReservedPathRegistry(@Nullable String oidcHost, Map<String, ReservedEndpoint> endpointsByPath) {
        this.oidcHost = oidcHost;
        this.endpointsByPath = Map.copyOf(endpointsByPath);
    }

    /**
     * Builds the registry from the global {@code oidc} block. The OIDC host and the callback path
     * are derived from {@code oidc.redirect_uri}; without a {@code redirect_uri} there is no OIDC
     * host to bind reserved paths to, so the registry is empty.
     *
     * @param oidc the global OIDC configuration, empty when the gateway serves no BFF variant
     * @return the reserved-path registry — {@linkplain #isEmpty() empty} when no OIDC callback is configured
     */
    public static ReservedPathRegistry from(Optional<OidcConfig> oidc) {
        Objects.requireNonNull(oidc, "oidc");
        Optional<URI> redirect = oidc.flatMap(OidcConfig::redirectUri).flatMap(ReservedPathRegistry::parseUri);
        String host = redirect.map(URI::getHost).orElse(null);
        if (host == null) {
            return new ReservedPathRegistry(null, Map.of());
        }
        Map<String, ReservedEndpoint> paths = new LinkedHashMap<>();
        redirect.map(URI::getPath).filter(ReservedPathRegistry::isAbsolutePath)
                .ifPresent(path -> paths.put(path, ReservedEndpoint.CALLBACK));
        Optional<OidcConfig.Logout> logout = oidc.flatMap(OidcConfig::logout);
        logout.flatMap(OidcConfig.Logout::path).flatMap(ReservedPathRegistry::toPath)
                .ifPresent(path -> paths.putIfAbsent(path, ReservedEndpoint.LOGOUT));
        logout.flatMap(OidcConfig.Logout::postLogoutRedirectUri).flatMap(ReservedPathRegistry::toPath)
                .ifPresent(path -> paths.putIfAbsent(path, ReservedEndpoint.LOGOUT_RETURN));
        logout.flatMap(OidcConfig.Logout::backchannelPath).flatMap(ReservedPathRegistry::toPath)
                .ifPresent(path -> paths.putIfAbsent(path, ReservedEndpoint.BACKCHANNEL_LOGOUT));
        return new ReservedPathRegistry(host, paths);
    }

    /**
     * Resolves the reserved endpoint for a request, matching the path <strong>exactly</strong> and
     * <strong>only</strong> when the request host is the OIDC host. A prefix that merely contains a
     * reserved path (e.g. {@code /auth} against the reserved {@code /auth/callback}) never matches —
     * that is precisely the carve-out this registry guarantees.
     *
     * @param host the request host authority (without port), may be {@code null}
     * @param path the single canonical request path, may be {@code null} before canonicalization
     * @return the reserved endpoint when the host and path match exactly; empty otherwise
     */
    public Optional<ReservedEndpoint> match(@Nullable String host, @Nullable String path) {
        if (oidcHost == null || host == null || path == null) {
            return Optional.empty();
        }
        if (!oidcHost.equalsIgnoreCase(host)) {
            return Optional.empty();
        }
        return Optional.ofNullable(endpointsByPath.get(path));
    }

    /**
     * Convenience predicate over {@link #match(String, String)} for the edge carve-out check.
     *
     * @param host the request host authority (without port), may be {@code null}
     * @param path the single canonical request path, may be {@code null}
     * @return {@code true} when the request targets a reserved gateway endpoint
     */
    public boolean isReserved(@Nullable String host, @Nullable String path) {
        return match(host, path).isPresent();
    }

    /**
     * @return {@code true} when no reserved endpoint is registered (no OIDC callback configured)
     */
    public boolean isEmpty() {
        return endpointsByPath.isEmpty();
    }

    /**
     * Normalizes a configured value to its absolute path component: a full URI ({@code https://…})
     * contributes its path, a bare absolute path ({@code /auth/logout}) is taken verbatim. A blank,
     * relative, or unparseable value contributes nothing.
     */
    private static Optional<String> toPath(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        if (value.contains("://")) {
            return parseUri(value).map(URI::getPath).filter(ReservedPathRegistry::isAbsolutePath);
        }
        return isAbsolutePath(value) ? Optional.of(value) : Optional.empty();
    }

    private static Optional<URI> parseUri(String value) {
        try {
            return Optional.of(URI.create(value.trim()));
        } catch (IllegalArgumentException unparseable) {
            return Optional.empty();
        }
    }

    private static boolean isAbsolutePath(@Nullable String path) {
        return path != null && path.startsWith("/");
    }
}
