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
package de.cuioss.sheriff.gateway.config.validation.rule;

import java.util.List;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.reserved.ReservedPathRegistry;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.CatalogConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.PortalConfig;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.http.LocationPathReview;
import lombok.experimental.UtilityClass;

/**
 * The boot-time {@link ValidationRule}s of the application portal: the {@code portal} block of
 * {@code gateway.yaml} and the {@code catalog} block of an endpoint file.
 * <p>
 * Four refusals, each appending a file- and pointer-annotated {@link ConfigError} and never
 * failing fast:
 * <ol>
 *   <li><strong>Canonical {@code portal.path}</strong> ({@link #validatePortalPathCanonical}). The
 *       portal matches the canonical request path by exact string equality, so a path that is not
 *       itself canonical — a doubled {@code /}, a dot segment, a {@code ?} or {@code #}, a
 *       percent-encoded separator, or anything the {@code cui-http} URL path review refuses — could
 *       never equal a request path and would silently never answer.</li>
 *   <li><strong>No reserved OIDC path</strong> ({@link #validatePortalPathNotReserved}). The portal
 *       matches on any host, so its path is compared host-independently against
 *       {@link ReservedPathRegistry#reservedPaths(de.cuioss.sheriff.gateway.config.model.OidcConfig)}
 *       — the same derivation the runtime registry is built from.</li>
 *   <li><strong>No enabled exact route</strong> ({@link #validatePortalPathNotExactRoute}). The
 *       portal is answered before the route table, so an enabled endpoint's exact route on the same
 *       path would be silently unreachable. A prefix route covering the path is deliberately
 *       admitted — claiming the context root next to prefix routes is the point of the seam.</li>
 *   <li><strong>Origin-relative {@code catalog.entry}</strong> ({@link #validateCatalogEntries}).
 *       Every enabled endpoint's entry must stay on the gateway's own origin: a single leading
 *       {@code /}, no scheme, no authority, no backslash, no percent-encoded separator, and a path
 *       the {@link LocationPathReview} admits. Anything else would turn the portal into an open
 *       redirect.</li>
 * </ol>
 * Disabled endpoints have already been dropped by the enablement filter; rules 3 and 4 additionally
 * skip any endpoint whose {@code enabled} flag is {@code false}, so a disabled endpoint is never
 * evaluated whichever list a caller supplies. A refusal message never echoes the offending value.
 * <p>
 * Framework-agnostic (ADR-0005) and stateless.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class PortalRules {

    private static final String GATEWAY_FILE = "gateway.yaml";

    /** The JSON pointer of {@code portal.path} in {@code gateway.yaml}. */
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    public static final String PORTAL_PATH_POINTER = "/portal/path";

    /** The JSON pointer of {@code endpoint.catalog.entry} in an endpoint file. */
    // java:S1075 — a fixed JSON-pointer into the config document (schema key), not a customizable URI/filesystem path.
    @SuppressWarnings("java:S1075")
    public static final String CATALOG_ENTRY_POINTER = "/endpoint/catalog/entry";

    private static final String SLASH = "/";
    private static final String DOUBLE_SLASH = "//";

    /**
     * The portal rules in their reporting order, for registration in the configuration validator's
     * default rule set.
     */
    public static final List<ValidationRule> RULES = List.of(
            (gateway, endpoints, topology, errors) -> validatePortalPathCanonical(gateway, errors),
            (gateway, endpoints, topology, errors) -> validatePortalPathNotReserved(gateway, errors),
            (gateway, endpoints, topology, errors) -> validatePortalPathNotExactRoute(gateway, endpoints, errors),
            (gateway, endpoints, topology, errors) -> validateCatalogEntries(endpoints, errors));

    /**
     * Rule 1: {@code portal.path} must be a canonical gateway path.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared error accumulator
     */
    public static void validatePortalPathCanonical(GatewayConfig gateway, List<ConfigError> errors) {
        PortalConfig portal = gateway.portal();
        if (portal == null) {
            return;
        }
        canonicalRefusal(portal.path()).ifPresent(reason -> errors.add(new ConfigError(GATEWAY_FILE,
                PORTAL_PATH_POINTER, ("portal.path must be a canonical gateway path, otherwise it can never equal a "
                + "canonical request path: %s").formatted(reason))));
    }

    /**
     * Rule 2: {@code portal.path} must not equal any reserved OIDC path, compared host-independently.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared error accumulator
     */
    public static void validatePortalPathNotReserved(GatewayConfig gateway, List<ConfigError> errors) {
        PortalConfig portal = gateway.portal();
        if (portal == null) {
            return;
        }
        if (ReservedPathRegistry.reservedPaths(gateway.oidc()).contains(portal.path())) {
            errors.add(new ConfigError(GATEWAY_FILE, PORTAL_PATH_POINTER,
                    "portal.path equals a reserved OIDC path (callback, logout, logout return, back-channel "
                            + "logout, user-info or login); the portal matches on any host and would collide "
                            + "with the reserved endpoint"));
        }
    }

    /**
     * Rule 3: {@code portal.path} must not equal an enabled endpoint's exact-route match path.
     *
     * @param gateway          the bound gateway document
     * @param enabledEndpoints the endpoints filtered to those enabled
     * @param errors           the shared error accumulator
     */
    public static void validatePortalPathNotExactRoute(GatewayConfig gateway, List<EndpointConfig> enabledEndpoints,
            List<ConfigError> errors) {
        PortalConfig portal = gateway.portal();
        if (portal == null) {
            return;
        }
        for (EndpointConfig endpoint : enabledEndpoints) {
            if (!endpoint.enabled()) {
                continue;
            }
            for (RouteConfig route : endpoint.routes()) {
                if (route.match().isExact() && portal.path().equals(route.match().path())) {
                    errors.add(new ConfigError(GATEWAY_FILE, PORTAL_PATH_POINTER,
                            ("portal.path equals the exact route '%s' of endpoint '%s'; the portal is answered "
                                    + "before the route table, so that route would be unreachable")
                                    .formatted(route.id(), endpoint.id())));
                }
            }
        }
    }

    /**
     * Rule 4: every enabled endpoint's {@code catalog.entry} must be an origin-relative path on the
     * gateway's own origin.
     *
     * @param enabledEndpoints the endpoints filtered to those enabled
     * @param errors           the shared error accumulator
     */
    public static void validateCatalogEntries(List<EndpointConfig> enabledEndpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : enabledEndpoints) {
            CatalogConfig catalog = endpoint.catalog();
            if (endpoint.enabled() && catalog != null) {
                entryRefusal(catalog.entry()).ifPresent(reason -> errors.add(new ConfigError(
                        endpointFile(endpoint), CATALOG_ENTRY_POINTER,
                        "catalog.entry of endpoint '%s' points outside the gateway's own origin: %s"
                                .formatted(endpoint.id(), reason))));
            }
        }
    }

    private static Optional<String> canonicalRefusal(String path) {
        if (!path.startsWith(SLASH)) {
            return Optional.of("it must start with '/'");
        }
        if (path.contains(DOUBLE_SLASH)) {
            return Optional.of("it must not contain '//'");
        }
        if (path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            return Optional.of("it must not carry a query or a fragment");
        }
        return LocationPathReview.refusalReason(path);
    }

    private static Optional<String> entryRefusal(String entry) {
        if (!entry.startsWith(SLASH) || entry.startsWith(DOUBLE_SLASH)) {
            return Optional.of("it must be an absolute path starting with a single '/' and carry no scheme or "
                    + "authority");
        }
        if (entry.indexOf('\\') >= 0) {
            return Optional.of("a '\\' is parsed as '/' by browsers and can name another origin");
        }
        if (LocationPathReview.carriesEncodedSeparator(entry)) {
            return Optional.of("a percent-encoded '/' or '\\' is decoded by intermediaries");
        }
        return LocationPathReview.refusalReason(pathPart(entry));
    }

    /** The path portion of an origin-relative reference: everything before the first {@code ?} or {@code #}. */
    private static String pathPart(String reference) {
        int end = reference.length();
        int query = reference.indexOf('?');
        if (query >= 0) {
            end = query;
        }
        int fragment = reference.indexOf('#');
        if (fragment >= 0 && fragment < end) {
            end = fragment;
        }
        return reference.substring(0, end);
    }

    private static String endpointFile(EndpointConfig endpoint) {
        return "endpoints/" + endpoint.id() + ".yaml";
    }
}
