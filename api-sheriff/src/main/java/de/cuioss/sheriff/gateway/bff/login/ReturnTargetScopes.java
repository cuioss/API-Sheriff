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
package de.cuioss.sheriff.gateway.bff.login;

import java.net.URI;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;


import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.config.model.HttpMethod;
import de.cuioss.sheriff.gateway.config.model.Require;
import de.cuioss.sheriff.gateway.config.model.ResolvedRoute;
import de.cuioss.sheriff.gateway.config.model.RouteTable;
import de.cuioss.sheriff.gateway.routing.RouteMatcher;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the scope set a {@code /auth/login?returnUrl=} login requests, from the route the
 * browser will land on after the callback.
 * <p>
 * A login started through the login-initiation endpoint has no selected route of its own — the
 * only hint of what the session is for is the post-login {@code returnUrl}. This resolver maps that
 * target onto the boot-built route table: when the same-origin, canonicalized path of the target
 * selects a route whose effective auth is not {@code none}, the login requests that route's
 * {@link ResolvedRoute#neededScopes() neededScopes} — the same single derivation the session stage
 * requests for a navigation on that route and the bearer check enforces. In every other case the
 * login requests {@code oidc.scopes} only:
 * <ul>
 *   <li>no {@code returnUrl}, a blank one, or one that fails the same-origin check
 *       ({@link PendingAuthorizationRecord#sameOrigin}) — cross-origin, schema-relative, backslash
 *       authority or unparseable;</li>
 *   <li>a path that is not canonical — an encoded separator ({@code %2f}, {@code %5c}), a matrix
 *       parameter ({@code ;}), or a dot-segment that escapes the root — which the gateway's stage-1
 *       floor would reject as a navigation anyway;</li>
 *   <li>no route matches the path;</li>
 *   <li>the matched route is {@code require: none}.</li>
 * </ul>
 * <p>
 * <strong>Matching.</strong> The target is matched the way stage-2 route selection would match a
 * {@code GET} navigation to it on the gateway host: the routes are walked in the route table's
 * exact-first, then longest-prefix-first order, the first match wins, and the query part of the
 * target never takes part. A route declaring {@code match.headers} is skipped, because a header
 * matcher cannot be evaluated for a request that has not happened yet — it is treated as
 * non-matching rather than guessed.
 * <p>
 * The resolved set only decides what the login <em>requests</em>; it is never an authorization
 * decision. A session route runs no scope check, so a target that resolves to {@code oidc.scopes}
 * yields a session that is still admitted to that route.
 * <p>
 * <strong>Thread safety.</strong> Immutable once built at boot; safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ReturnTargetScopes {

    private static final String ENCODED_SLASH = "%2f";
    private static final String ENCODED_BACKSLASH = "%5c";
    private static final char MATRIX_PARAM = ';';
    private static final String ROOT_PATH = "/";

    private final List<Candidate> candidates;
    private final String gatewayOrigin;
    private final @Nullable String gatewayHost;
    private final Set<String> oidcScopes;

    /**
     * Builds the resolver over the frozen route table.
     *
     * @param routeTable    the boot-built route table, in selection order
     * @param gatewayOrigin the gateway's own origin (the {@code redirect_uri} origin) — both the
     *                      same-origin reference and the host a target is matched on
     * @param oidcScopes    the configured {@code oidc.scopes}, the set every non-route-specific login
     *                      requests
     */
    public ReturnTargetScopes(RouteTable routeTable, String gatewayOrigin, Collection<String> oidcScopes) {
        Objects.requireNonNull(routeTable, "routeTable");
        this.gatewayOrigin = Objects.requireNonNull(gatewayOrigin, "gatewayOrigin");
        this.gatewayHost = URI.create(gatewayOrigin).getHost();
        this.oidcScopes = Set.copyOf(Objects.requireNonNull(oidcScopes, "oidcScopes"));
        this.candidates = routeTable.routes().stream().map(Candidate::from).toList();
    }

    /**
     * Resolves the scope set a login returning to {@code returnUrl} requests.
     *
     * @param returnUrl the post-login return target the browser asked for, may be absent
     * @return the matched authenticated route's {@code neededScopes}, or {@code oidc.scopes} in
     *         every other case; never {@code null}
     */
    public Set<String> resolve(@Nullable String returnUrl) {
        if (!PendingAuthorizationRecord.sameOrigin(returnUrl, gatewayOrigin)) {
            return oidcScopes;
        }
        return canonicalPath(Objects.requireNonNull(returnUrl, "returnUrl"))
                .flatMap(this::select)
                .filter(candidate -> candidate.require() != Require.NONE)
                .map(Candidate::neededScopes)
                .orElse(oidcScopes);
    }

    private Optional<Candidate> select(String path) {
        return candidates.stream()
                .filter(candidate -> candidate.matcher().matchHeaderNames().isEmpty())
                .filter(candidate -> candidate.matcher().matches(path, HttpMethod.GET, gatewayHost, Map.of()))
                .findFirst();
    }

    /**
     * The canonical path of an already same-origin-validated target: the decoded path with dot
     * segments removed, the query and fragment discarded. Empty when the target does not parse or its
     * path is not canonical.
     */
    private static Optional<String> canonicalPath(String returnUrl) {
        URI target;
        try {
            target = URI.create(returnUrl);
        } catch (IllegalArgumentException _) {
            return Optional.empty();
        }
        String rawPath = target.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            return Optional.of(ROOT_PATH);
        }
        String lowered = rawPath.toLowerCase(Locale.ROOT);
        if (lowered.contains(ENCODED_SLASH) || lowered.contains(ENCODED_BACKSLASH)
                || rawPath.indexOf(MATRIX_PARAM) >= 0) {
            return Optional.empty();
        }
        String normalized = target.normalize().getPath();
        if (normalized == null || !normalized.startsWith(ROOT_PATH) || escapesRoot(normalized)) {
            return Optional.empty();
        }
        return Optional.of(normalized);
    }

    /**
     * {@link URI#normalize()} keeps a leading {@code ..} segment it cannot resolve, so a path that
     * climbs above the root survives normalization as {@code /..} or {@code /../...}.
     */
    private static boolean escapesRoot(String normalizedPath) {
        return "/..".equals(normalizedPath) || normalizedPath.startsWith("/../");
    }

    /**
     * One route reduced to what resolution reads: its compiled matcher, its effective
     * {@code require}, and its materialized {@code neededScopes}.
     */
    private record Candidate(RouteMatcher matcher, Require require, Set<String> neededScopes) {

        static Candidate from(ResolvedRoute route) {
            return new Candidate(RouteMatcher.from(route.match()), route.effectiveAuth().require(),
                    route.neededScopes());
        }
    }
}
