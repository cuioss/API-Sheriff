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
package de.cuioss.sheriff.gateway.edge.error;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import de.cuioss.sheriff.gateway.routing.Route;
import org.jspecify.annotations.Nullable;

/**
 * The first producer's predicate for the per-source detection substrate
 * ({@code de.cuioss.sheriff.gateway.detection}): decides whether a rejection is a reconnaissance
 * signal, and with what weight it is admitted to the recon ledger.
 * <p>
 * <strong>The predicate.</strong> {@link #weightOf} is {@link #RECON_WEIGHT} exactly when all of these
 * hold, and {@code 0} otherwise:
 * <ol>
 *   <li>the event is a member of the closed set below;</li>
 *   <li>the request has not passed the trust boundary — {@link PipelineRequest#trustBoundaryPassed()}
 *       is {@code false}, or no request was built yet;</li>
 *   <li>the request is not for a static resource (see below).</li>
 * </ol>
 * The event judged is the TRUE event of the rejection, never the one it is rendered as: a {@code 401}
 * or {@code 405} answered as the uniform {@code 404} ({@link UniformNotFound}) counts under its own
 * event, whatever status the answer carries.
 * <p>
 * <strong>The closed set</strong> ({@link #RECON_SET}), fixed against {@link EventType}:
 * <ul>
 *   <li>{@link EventType#NO_ROUTE_MATCHED} and {@link EventType#PASSTHROUGH_HOST_SMUGGLED} — the
 *       unrouted answer, the plainest probe for an address that does not exist;</li>
 *   <li>{@link EventType#METHOD_NOT_ALLOWED} — a verb probe against a route;</li>
 *   <li>{@link EventType#SECURITY_FILTER_VIOLATION}, {@link EventType#PATH_NOT_ALLOWED} and
 *       {@link EventType#PARAMETER_LIMIT_EXCEEDED} — malformed or out-of-whitelist input before the
 *       trust boundary;</li>
 *   <li>{@link EventType#CONTENT_TOO_LARGE} — counted only for the declared-{@code Content-Length}
 *       fast-reject; the streaming cap is raised after stage 4 and is kept out by the trust leg;</li>
 *   <li>{@link EventType#CSRF_REJECTED} — a cross-site attempt before stage 4;</li>
 *   <li>{@link EventType#TOKEN_MISSING} and {@link EventType#TOKEN_INVALID} — a protected address
 *       reached without, or with a forged, credential.</li>
 * </ul>
 * A new {@link EventType} constant is not a member until it is added here; {@code ReconEventsTest}
 * fails for a constant that is neither a member nor in its recorded exclusion table.
 * <p>
 * <strong>Excluded, each for a recorded reason:</strong>
 * <ul>
 *   <li>every event raised after the trust boundary — {@link EventType#SCOPE_MISSING},
 *       {@link EventType#WEBSOCKET_ORIGIN_REJECTED}, and the post-stage-4 raises of
 *       {@code CONTENT_TOO_LARGE} and {@code SECURITY_FILTER_VIOLATION}: the caller is trusted, or the
 *       route is anonymous and its own success answer already discloses it;</li>
 *   <li>the upstream events {@link EventType#UPSTREAM_ERROR}, {@link EventType#UPSTREAM_CIRCUIT_OPEN}
 *       and {@link EventType#UPSTREAM_TIMEOUT} — they describe the upstream, not the caller's
 *       probing;</li>
 *   <li>{@link EventType#INBOUND_BODY_ABORTED} and {@link EventType#REQUEST_BODY_TIMEOUT} — a body
 *       that stopped arriving is a network or client condition and says nothing about which addresses
 *       exist;</li>
 *   <li>the reserved-path events {@link EventType#RESERVED_BODY_TOO_LARGE} and
 *       {@link EventType#LOGOUT_TOKEN_INVALID} — the reserved paths are a fixed, public set
 *       (ADR-0018), so probing them discloses nothing;</li>
 *   <li>{@link EventType#SOURCE_BLOCKED} — the refusal of a source already blocked, answered before
 *       admission and never reported to this predicate; counting it would extend a block by the very
 *       requests it refuses;</li>
 *   <li>{@link EventType#PRE_TRUST_INTERNAL_FAILURE} — an operator-side failure is not caller behaviour
 *       and carries no detection weight. It is still answered as the uniform {@code 404} and counted
 *       under its own event, but an outage behind the edge (a failed identity-provider back-channel
 *       during a login start, for one) must not charge the source addresses of the users retrying
 *       through it;</li>
 *   <li>the boot, session-lifecycle and informational events, {@link EventType#SOURCE_TRIPPED} among
 *       them — they are not rejections of a request.</li>
 * </ul>
 * <p>
 * <strong>Static resources are excluded</strong> ({@link #isStaticResource}): a request is a static
 * resource when its selected route carries the asset terminal action — a miss there is a page's own
 * asset reference — or when its canonical path — the raw request path when stage 1 has not run — is
 * one of the {@linkplain #BROWSER_AMBIENT_PATHS browser-ambient fetches}, which browsers and crawlers
 * request of every site unasked, compared case-insensitively. A file extension alone does not make a
 * request static: an unrouted or bearer-route path ending in {@code .png} or {@code .js} is counted
 * like any other path.
 * <p>
 * Stateless and thread-safe.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ReconEvents {

    /** The weight a reconnaissance signal is admitted with. */
    public static final int RECON_WEIGHT = 1;

    /**
     * The closed set of events counted as reconnaissance before the trust boundary. A new
     * {@link EventType} constant is not a member until it is added here.
     */
    public static final Set<EventType> RECON_SET = Collections.unmodifiableSet(EnumSet.of(
            EventType.NO_ROUTE_MATCHED,
            EventType.PASSTHROUGH_HOST_SMUGGLED,
            EventType.METHOD_NOT_ALLOWED,
            EventType.SECURITY_FILTER_VIOLATION,
            EventType.PATH_NOT_ALLOWED,
            EventType.PARAMETER_LIMIT_EXCEEDED,
            EventType.CONTENT_TOO_LARGE,
            EventType.CSRF_REJECTED,
            EventType.TOKEN_MISSING,
            EventType.TOKEN_INVALID));

    /**
     * The closed set of browser-ambient fetches, lower case: the root paths browsers and crawlers
     * request of every site without being told they exist. A fixed set, no configuration key reaches
     * it. {@link #isStaticResource} also accepts the sized variants of the touch icon, such as
     * {@code /apple-touch-icon-180x180.png} and {@code /apple-touch-icon-152x152-precomposed.png}.
     */
    public static final Set<String> BROWSER_AMBIENT_PATHS = Set.of(
            "/favicon.ico", "/robots.txt", "/apple-touch-icon.png", "/apple-touch-icon-precomposed.png");

    /**
     * The sized touch-icon fetches, such as {@code /apple-touch-icon-180x180.png} and
     * {@code /apple-touch-icon-152x152-precomposed.png}, matched against the lower-cased path.
     */
    private static final Pattern APPLE_TOUCH_ICON =
            Pattern.compile("/apple-touch-icon-\\d{1,4}x\\d{1,4}(-precomposed)?\\.png");

    private ReconEvents() {
        // stateless predicate holder; not instantiable
    }

    /**
     * Decides the weight a rejection is admitted to the recon ledger with; see the class documentation
     * for the three legs of the predicate.
     *
     * @param request   the rejected request, {@code null} for a rejection raised before one was built —
     *                  such a rejection is pre-trust and is never a static resource
     * @param eventType the TRUE event the rejection reports, never the one it is rendered as
     * @return {@link #RECON_WEIGHT} when the rejection is a reconnaissance signal, {@code 0} otherwise
     */
    public static int weightOf(@Nullable PipelineRequest request, EventType eventType) {
        if (!RECON_SET.contains(eventType)) {
            return 0;
        }
        if (request == null) {
            return RECON_WEIGHT;
        }
        if (request.trustBoundaryPassed() || isStaticResource(request)) {
            return 0;
        }
        return RECON_WEIGHT;
    }

    /**
     * @param request the request
     * @return {@code true} when the selected route serves assets, or when the canonical path (the raw
     *         request path before stage 1) is a {@linkplain #BROWSER_AMBIENT_PATHS browser-ambient
     *         fetch}
     */
    public static boolean isStaticResource(PipelineRequest request) {
        Route route = request.selectedRoute();
        if (route != null && route.asset() != null) {
            return true;
        }
        String canonicalPath = request.canonicalPath();
        String path = (canonicalPath != null ? canonicalPath : request.requestPath()).toLowerCase(Locale.ROOT);
        return BROWSER_AMBIENT_PATHS.contains(path) || APPLE_TOUCH_ICON.matcher(path).matches();
    }
}
