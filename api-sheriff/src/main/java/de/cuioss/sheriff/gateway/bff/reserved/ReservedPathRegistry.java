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
package de.cuioss.sheriff.gateway.bff.reserved;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import org.jspecify.annotations.Nullable;

/**
 * The exact-match registry of the gateway's reserved OIDC endpoints (D2).
 * <p>
 * The BFF variants carve up to six gateway-owned paths out of the proxy route table — the
 * {@code oidc.redirect_uri} callback, the RP-initiated {@code oidc.logout.path}, its
 * {@code post_logout_redirect_uri} return leg, the {@code oidc.logout.backchannel_path}
 * receiver, the {@code oidc.user_info.path} session/user-info fold (D11), and the
 * {@code oidc.login.path} login-initiation fold (D12). Each is matched <strong>exactly</strong>
 * (never by prefix). Five of the six are matched <strong>only on the OIDC host</strong> (the host of
 * {@code oidc.redirect_uri}); the back-channel logout receiver is matched on <strong>every</strong>
 * host, for the reason {@link #match(String, String)} documents. The gateway edge consults this
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
        BACKCHANNEL_LOGOUT,

        /** The {@code oidc.user_info.path} session/user-info fold endpoint (D11). */
        USER_INFO,

        /** The {@code oidc.login.path} login-initiation fold endpoint (D12). */
        LOGIN
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
     * @param config the global OIDC configuration, {@code null} when the gateway serves no BFF variant
     * @return the reserved-path registry — {@linkplain #isEmpty() empty} when no OIDC callback is configured
     */
    public static ReservedPathRegistry from(@Nullable OidcConfig config) {
        if (config == null) {
            return new ReservedPathRegistry(null, Map.of());
        }
        String host = redirectUri(config).map(URI::getHost).orElse(null);
        if (host == null) {
            return new ReservedPathRegistry(null, Map.of());
        }
        return new ReservedPathRegistry(host, pathsOf(config));
    }

    /**
     * Derives the configured reserved OIDC path set <strong>host-independently</strong>: every path
     * the {@code oidc} block reserves, through the same normalisation {@link #from(OidcConfig)}
     * registers them with. The boot-time configuration validator uses it to refuse another
     * gateway-owned exact path — the application portal's {@code portal.path} — that would collide
     * with a reserved OIDC path; comparing without the host is deliberate, because such a path
     * matches on any host and would therefore shadow, or be shadowed by, the OIDC carve-out.
     * <p>
     * Wherever {@link #from(OidcConfig)} yields a non-empty registry, every returned path
     * {@linkplain #match(String, String) matches} it on the OIDC host.
     *
     * @param config the global OIDC configuration, {@code null} when the gateway serves no BFF variant
     * @return the reserved paths in declaration order, empty without an {@code oidc} block
     */
    public static Set<String> reservedPaths(@Nullable OidcConfig config) {
        if (config == null) {
            return Set.of();
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(pathsOf(config).keySet()));
    }

    /**
     * The single derivation of the reserved path map shared by {@link #from(OidcConfig)} and
     * {@link #reservedPaths(OidcConfig)}: the callback path of {@code redirect_uri} first, then the
     * logout, logout-return, back-channel, user-info and login paths, keeping the first
     * registration for a path.
     */
    private static Map<String, ReservedEndpoint> pathsOf(OidcConfig config) {
        Map<String, ReservedEndpoint> paths = new LinkedHashMap<>();
        redirectUri(config).map(URI::getPath).filter(ReservedPathRegistry::isAbsolutePath)
                .ifPresent(path -> paths.put(path, ReservedEndpoint.CALLBACK));
        OidcConfig.Logout logout = config.logout();
        if (logout != null) {
            reservePath(paths, logout.path(), ReservedEndpoint.LOGOUT);
            reservePath(paths, logout.postLogoutRedirectUri(), ReservedEndpoint.LOGOUT_RETURN);
            reservePath(paths, logout.backchannelPath(), ReservedEndpoint.BACKCHANNEL_LOGOUT);
        }
        OidcConfig.UserInfo userInfo = config.userInfo();
        if (userInfo != null) {
            reservePath(paths, userInfo.path(), ReservedEndpoint.USER_INFO);
        }
        OidcConfig.Login login = config.login();
        if (login != null) {
            reservePath(paths, login.path(), ReservedEndpoint.LOGIN);
        }
        return paths;
    }

    private static Optional<URI> redirectUri(OidcConfig config) {
        String redirectUri = config.redirectUri();
        return redirectUri == null ? Optional.empty() : parseUri(redirectUri);
    }

    /**
     * Registers {@code value}'s {@linkplain #toPath(String) absolute path component} under
     * {@code endpoint}, keeping the first registration for a path. A {@code null}, blank, relative,
     * or unparseable value contributes nothing.
     */
    private static void reservePath(Map<String, ReservedEndpoint> paths, @Nullable String value,
            ReservedEndpoint endpoint) {
        if (value == null) {
            return;
        }
        toPath(value).ifPresent(path -> paths.putIfAbsent(path, endpoint));
    }

    /**
     * Resolves the reserved endpoint for a request, matching the path <strong>exactly</strong>. A
     * prefix that merely contains a reserved path (e.g. {@code /auth} against the reserved
     * {@code /auth/callback}) never matches — that is precisely the carve-out this registry
     * guarantees.
     * <p>
     * <strong>Five of the six endpoints additionally require the request host to be the OIDC
     * host</strong> (the host of {@code oidc.redirect_uri}). That is right for all five, because each
     * of them is reached by a <em>browser</em> at the origin the gateway published to it: the callback,
     * the login initiation, the RP-initiated logout and its return leg, and the user-info fold are all
     * navigations or fetches from the session's own origin, so a request for one of those paths
     * arriving on a different virtual host is not the browser and must fall through to the proxy route
     * table.
     * <p>
     * <strong>{@link ReservedEndpoint#BACKCHANNEL_LOGOUT} is matched on every host, and that
     * asymmetry is the contract rather than a relaxation of it.</strong> The back-channel receiver is
     * the one reserved endpoint no browser ever reaches: the identity provider dials it
     * server-to-server at whatever address the relying party registered as its
     * {@code backchannel_logout_uri}, and that address is routinely an internal one — a container or
     * service name on the network the two share — while the OIDC host is the public name the browser
     * uses. Requiring the two to coincide makes back-channel logout silently unreachable in exactly
     * those deployments: the {@code POST} is delivered, answered {@code 404} by the proxy route table
     * because no route claims the reserved path, and the session it was meant to destroy survives with
     * no diagnostic on either side. Host-gating the browser endpoints and not this one is therefore the
     * faithful rule, not an exception to it.
     * <p>
     * Widening the host for this one path costs no authorization: the receiver rejects anything that is
     * not a JWKS-signature-verified logout token carrying the expected {@code iss}/{@code aud}, so
     * reaching it on a second host confers nothing a caller could not already attempt on the first. What
     * it does cost is the ability to proxy the configured back-channel path on another virtual host —
     * the same price ADR-0018 already records for the reserved paths on the OIDC host, now paid on all
     * of them for this single path.
     *
     * @param host the request host authority (without port), may be {@code null}
     * @param path the single canonical request path, may be {@code null} before canonicalization
     * @return the reserved endpoint when the path matches exactly and the host requirement for that
     *         endpoint is met; empty otherwise
     */
    public Optional<ReservedEndpoint> match(@Nullable String host, @Nullable String path) {
        if (path == null) {
            return Optional.empty();
        }
        ReservedEndpoint endpoint = endpointsByPath.get(path);
        if (endpoint == null) {
            return Optional.empty();
        }
        if (endpoint == ReservedEndpoint.BACKCHANNEL_LOGOUT) {
            return Optional.of(endpoint);
        }
        if (host == null || oidcHost == null || !oidcHost.equalsIgnoreCase(host)) {
            return Optional.empty();
        }
        return Optional.of(endpoint);
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
        if (value.isBlank()) {
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
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
    }

    private static boolean isAbsolutePath(@Nullable String path) {
        return path != null && path.startsWith("/");
    }
}
