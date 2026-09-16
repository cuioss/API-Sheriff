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
 *       {@code //host} value, a relative-path reference, an unparseable value, a path outside the base
 *       path, a path carrying a {@code .} or {@code ..} segment in either its literal or its
 *       percent-encoded spelling — and any mapping that would itself start with {@code //}, since
 *       emitting a scheme-relative value would turn an upstream-internal path into another origin.</li>
 * </ul>
 * <p>
 * The dot-segment refusal is a confinement rule rather than a tidiness one. The base-path test is
 * performed on the path as received, so {@code /svc/v1/../login} continues the base path
 * {@code /svc/v1} textually and would map onto {@code /api/../login} — which the browser resolves to
 * {@code /login}, outside the match key the mapping is supposed to confine the redirect to. Canonical
 * confinement cannot be proved for such a value without resolving it, so it is not mapped at all.
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
    /** The percent-encoded spelling of {@code .}; matched case-insensitively, so {@code %2E} counts too. */
    private static final String ENCODED_DOT = "%2e";

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
        if (uri.isOpaque() || !pointsAtUpstream(uri)) {
            return location;
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            rawPath = "/";
        } else if (!rawPath.startsWith("/")) {
            return location;
        }
        if (carriesDotSegment(rawPath)) {
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
        if (mapped.startsWith(SCHEME_RELATIVE)) {
            return location;
        }
        if (exactMatch && !mapped.equals(matchKey)) {
            return location;
        }
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
     * Whether any segment of {@code rawPath} is a {@code .} or {@code ..} segment. The test runs on the
     * raw (still percent-encoded) path and treats {@code %2e} / {@code %2E} as the dot it encodes, so
     * an encoded traversal is caught on the same terms as a literal one — decoding the path first would
     * mean mapping a value whose confinement was proved against a different string than the one the
     * browser receives.
     *
     * @param rawPath the candidate's raw path
     * @return {@code true} when the path carries a dot segment in either spelling
     */
    private static boolean carriesDotSegment(String rawPath) {
        for (String segment : rawPath.split("/", -1)) {
            if (isDotSegment(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return {@code true} when {@code segment} consists of exactly one or two dots, each written
     *         literally or as {@code %2e} in either case
     */
    private static boolean isDotSegment(String segment) {
        int index = 0;
        int dots = 0;
        while (index < segment.length()) {
            if (segment.charAt(index) == '.') {
                index++;
            } else if (segment.regionMatches(true, index, ENCODED_DOT, 0, ENCODED_DOT.length())) {
                index += ENCODED_DOT.length();
            } else {
                return false;
            }
            dots++;
        }
        return dots == 1 || dots == 2;
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
