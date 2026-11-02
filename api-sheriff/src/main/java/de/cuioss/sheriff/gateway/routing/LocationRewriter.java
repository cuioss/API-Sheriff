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
package de.cuioss.sheriff.gateway.routing;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.config.model.ResolvedUpstream;
import de.cuioss.sheriff.gateway.http.LocationPathReview;

/**
 * The {@code upstream.rewrite_location} mapping (AS-11): maps an upstream {@code Location} response
 * header that points inside the route's upstream back onto the gateway, so a redirect issued by the
 * backend keeps the browser on the gateway origin and under the route's match key.
 * <p>
 * The rewriter is built once per opted-in proxy route, at boot, from the route's effective upstream
 * (scheme, host, port and the effective base path — the alias base path with {@code upstream.path}
 * appended) and its match key. {@link #rewrite(String)} is then a pure function of the header value:
 * <ul>
 *   <li><strong>Candidates.</strong> A path-absolute reference ({@code /...}), or an absolute URI
 *       whose scheme, host and effective port (the scheme default when the URI omits it) all equal
 *       the route upstream and which carries no user-info.</li>
 *   <li><strong>Mapping.</strong> A candidate whose path equals the effective base path, or continues
 *       it on a segment boundary, is mapped onto {@code stripTrailingSlash(matchKey) + remainder} and
 *       emitted as a gateway-relative path. With an empty base path every candidate path continues
 *       it. The query and fragment are carried over verbatim.</li>
 *   <li><strong>Exact routes only map onto their own match key.</strong> An exact route's
 *       {@link RouteMatcher} admits the match key by string equality and nothing below it, so a
 *       mapping that is not <em>equal</em> to the match key names a path this gateway does not route
 *       and the browser would follow it into a {@code 404}. Such a candidate is therefore returned
 *       untouched rather than mapped; a prefix route is unaffected, since its matcher admits the whole
 *       subtree.</li>
 *   <li><strong>Everything else is returned untouched</strong>: a foreign origin, a scheme-relative
 *       {@code //host} value, a relative-path reference, a <em>scheme-less</em> reference with an
 *       empty path ({@code ?query}, {@code #fragment} or the empty value — RFC 3986 section 5.3
 *       resolves such a path against the current request path, not against the upstream root, so
 *       mapping it onto the match key would move the redirect), an unparseable value, a path outside
 *       the base path, and a path — or a computed mapping — that fails the
 *       {@link LocationPathReview emitted-path review}.</li>
 * </ul>
 * <p>
 * <strong>The refusal set is not this class's own.</strong> Both the candidate path and the computed
 * mapping are handed to {@link LocationPathReview#refusalReason(String)}, the single place the gateway
 * decides which {@code Location} path is dangerous. That review refuses a scheme-relative {@code //}
 * prefix, a percent-encoded {@code /} or {@code \}, a {@code .} or {@code ..} segment in either
 * spelling, and everything the {@code cui-http} {@code URL_PATH} pipeline owns — double encoding,
 * escaping traversal, illegal and control characters, null bytes and the length cap. The same review
 * judges a <em>configured</em> redirect target at boot ({@code ConfigValidator}, AS-3 / GW-13), so the
 * boot review and the runtime rewrite cannot disagree; read {@link LocationPathReview} for why each
 * test exists and which of them the library can and cannot carry.
 * <p>
 * Two consequences of that review are specific to this class. <strong>Confinement</strong>: the
 * base-path test is performed on the path as received, so {@code /svc/v1/../login} continues the base
 * path {@code /svc/v1} textually and would map onto {@code /api/../login} — which the browser resolves
 * to {@code /login}, outside the match key the mapping is supposed to confine the redirect to.
 * <strong>Origin</strong>: the mapping is the one place a cross-origin redirect would be
 * <em>manufactured</em> out of a value that did not carry one, because an absolute upstream URI binds
 * its authority before its path. {@code https://upstream:8443/%5Cattacker.com} keeps the browser on
 * the upstream; the gateway-relative mapping {@code /%5Cattacker.com} does not, since a client or
 * intermediary that decodes before resolving reads {@code /\attacker.com} and the WHATWG URL Standard
 * parses {@code \} as {@code /} in the special schemes.
 * <p>
 * The review is scoped to the <strong>path</strong>. The query and fragment are carried over verbatim
 * and cannot form an authority — a relative reference's authority is decided before the {@code ?} —
 * and refusing them would stop mapping the ordinary encoded-path query parameter an upstream redirect
 * carries. An <em>absolute</em> same-origin URI with no path ({@code http://upstream:8080}) does name
 * the origin root, so it alone still maps through {@code /}.
 * <p>
 * An upstream {@code Location} path longer than the review's 1024-character cap is relayed unchanged
 * rather than mapped. That is the fail-safe direction: the browser follows the upstream's own value
 * instead of a mapping whose confinement was never proved.
 * <p>
 * Immutable and therefore thread-safe; one instance serves every request on its route.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class LocationRewriter {

    private static final String HTTP = "http";
    private static final String HTTPS = "https";
    private static final int HTTP_DEFAULT_PORT = 80;
    private static final int HTTPS_DEFAULT_PORT = 443;
    private static final String SCHEME_RELATIVE = "//";

    private final String scheme;
    private final String host;
    private final int port;
    private final String basePath;
    private final String matchKey;
    private final boolean exactMatch;
    private final String gatewayPrefix;

    /**
     * @param upstream   the route's effective upstream: scheme, host, port and the effective base path
     * @param matchKey   the route's match key — its {@code path_prefix}, or its exact {@code path}
     * @param exactMatch whether {@code matchKey} is an exact {@code path} matcher; an exact route only
     *                   maps a {@code Location} whose mapping equals the match key, because its
     *                   {@link RouteMatcher} routes nothing below it
     */
    public LocationRewriter(ResolvedUpstream upstream, String matchKey, boolean exactMatch) {
        Objects.requireNonNull(upstream, "upstream");
        Objects.requireNonNull(matchKey, "matchKey");
        this.scheme = upstream.scheme().toLowerCase(Locale.ROOT);
        this.host = upstream.host().toLowerCase(Locale.ROOT);
        this.port = upstream.port();
        this.basePath = stripTrailingSlashes(upstream.basePath());
        this.matchKey = matchKey;
        this.exactMatch = exactMatch;
        this.gatewayPrefix = stripTrailingSlashes(matchKey);
    }

    /**
     * Maps an upstream {@code Location} value onto the gateway, or returns it unchanged.
     *
     * @param location the upstream {@code Location} header value
     * @return the gateway-relative mapping, or {@code location} itself when it is not a candidate
     */
    public String rewrite(String location) {
        Objects.requireNonNull(location, "location");
        if (location.startsWith(SCHEME_RELATIVE)) {
            return location;
        }
        URI uri;
        try {
            uri = new URI(location);
        } catch (URISyntaxException _) {
            return location;
        }
        Optional<String> candidate = candidatePath(uri);
        if (candidate.isEmpty()) {
            return location;
        }
        String rawPath = candidate.get();
        if (LocationPathReview.refusalReason(rawPath).isPresent()) {
            return location;
        }
        Optional<String> remainder = remainderBelowBasePath(rawPath);
        if (remainder.isEmpty()) {
            return location;
        }
        String mapped = gatewayPrefix + remainder.get();
        if (mapped.isEmpty()) {
            mapped = "/";
        }
        // The mapping is reviewed on its own terms, not just the candidate it came from: the gateway
        // prefix is the route's match key, which this class never parsed, so a mapping can carry a
        // shape the candidate did not.
        if (LocationPathReview.refusalReason(mapped).isPresent()) {
            return location;
        }
        if (exactMatch && !mapped.equals(matchKey)) {
            return location;
        }
        return withQueryAndFragment(mapped, uri);
    }

    /**
     * The path a {@code Location} value offers as a mapping candidate — the first of the refusals
     * {@link #rewrite} applies in order, and the only one that decides <em>whether the value names a
     * path at all</em> rather than whether that path is safe. Empty means relay the value unchanged:
     * it is opaque, points somewhere other than the route upstream, is a relative-path reference, or
     * is a scheme-less reference with an empty path.
     *
     * @param uri the parsed {@code Location} value
     * @return the path-absolute candidate path, or empty when the value is not a candidate
     */
    private Optional<String> candidatePath(URI uri) {
        if (uri.isOpaque() || !pointsAtUpstream(uri)) {
            return Optional.empty();
        }
        String rawPath = uri.getRawPath();
        if (rawPath != null && !rawPath.isEmpty()) {
            return rawPath.startsWith("/") ? Optional.of(rawPath) : Optional.empty();
        }
        // An empty path means the ORIGIN ROOT only when the reference is absolute. Without a
        // scheme it is a relative reference whose empty path RFC 3986 section 5.3 resolves
        // against the current request path, so `?page=2`, `#section` and the empty reference
        // all keep the browser where it is. Synthesizing "/" here would map them onto the match
        // key instead — turning `?page=2` into `/api/?page=2` and moving the redirect to a
        // different address than the upstream named.
        return uri.getScheme() == null ? Optional.empty() : Optional.of("/");
    }

    /**
     * Reassembles the emitted value once every refusal has passed. The query and fragment are carried
     * over verbatim and are deliberately not reviewed — a relative reference's authority is decided
     * before the {@code ?}, so neither can form one.
     *
     * @param mapped the reviewed gateway-relative path
     * @param uri    the parsed {@code Location} value the query and fragment come from
     * @return {@code mapped} carrying the upstream value's query and fragment
     */
    private static String withQueryAndFragment(String mapped, URI uri) {
        StringBuilder rewritten = new StringBuilder(mapped);
        String rawQuery = uri.getRawQuery();
        if (rawQuery != null) {
            rewritten.append('?').append(rawQuery);
        }
        String rawFragment = uri.getRawFragment();
        if (rawFragment != null) {
            rewritten.append('#').append(rawFragment);
        }
        return rewritten.toString();
    }

    /**
     * A reference without a scheme is relative to the upstream origin by definition; an absolute one
     * must name the same scheme, host and effective port, and carry no user-info.
     */
    private boolean pointsAtUpstream(URI uri) {
        String locationScheme = uri.getScheme();
        if (locationScheme == null) {
            return uri.getRawAuthority() == null;
        }
        String normalizedScheme = locationScheme.toLowerCase(Locale.ROOT);
        String locationHost = uri.getHost();
        return scheme.equals(normalizedScheme)
                && locationHost != null
                && host.equals(locationHost.toLowerCase(Locale.ROOT))
                && port == effectivePort(normalizedScheme, uri.getPort())
                && uri.getRawUserInfo() == null;
    }

    private static int effectivePort(String normalizedScheme, int declaredPort) {
        if (declaredPort != -1) {
            return declaredPort;
        }
        return switch (normalizedScheme) {
            case HTTP -> HTTP_DEFAULT_PORT;
            case HTTPS -> HTTPS_DEFAULT_PORT;
            default -> -1;
        };
    }

    /**
     * @return the part of {@code path} below the effective base path — empty for the base path itself —
     *         or empty when the path does not continue the base path on a segment boundary
     */
    private Optional<String> remainderBelowBasePath(String path) {
        if (basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/")) {
            return Optional.of(path.substring(basePath.length()));
        }
        return Optional.empty();
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }
}
