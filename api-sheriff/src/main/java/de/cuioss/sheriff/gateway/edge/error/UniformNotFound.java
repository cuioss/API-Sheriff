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

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

import de.cuioss.sheriff.gateway.events.EventCategory;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.PipelineRequest;
import org.jspecify.annotations.Nullable;

/**
 * The trust-boundary branch of the rejection renderer: decides whether a rejection is answered to
 * the caller as the unrouted {@code 404} instead of its honest status, so that an untrusted caller
 * cannot tell an existing route from an absent one by the answer it gets (the endpoint-existence
 * oracle, ADR-0072).
 * <p>
 * <strong>The trust boundary.</strong> A request has passed it once a bearer token validated offline
 * or the stage-4 authentication stage completed on any branch — a live session, or an anonymous
 * route that authenticates nothing ({@link PipelineRequest#trustBoundaryPassed()}). The window
 * between route selection and that point is the <em>pre-trust window</em>: every rejection raised
 * there would otherwise disclose that the address names a route.
 * <p>
 * <strong>The predicate.</strong> {@link #applies} is {@code true} exactly when all of these hold:
 * <ol>
 *   <li>the switch {@code security_defaults.uniform_not_found} is on (the secure default);</li>
 *   <li>the request has a selected route — a rejection raised before route selection is already
 *       identical for every address;</li>
 *   <li>the request has not passed the trust boundary;</li>
 *   <li>the event is a member of the closed set below;</li>
 *   <li>the request is not on the session branch with an
 *       {@link EventCategory#AUTHENTICATION AUTHENTICATION}-category event — the session branch keeps
 *       its {@code 302} / {@code 401} negotiation (the verify-first clause, ADR-0055).</li>
 * </ol>
 * <p>
 * <strong>The closed set</strong> ({@link #CLOSED_SET}), fixed against {@link EventType}, each member
 * with its raise site:
 * <ul>
 *   <li>{@link EventType#METHOD_NOT_ALLOWED} — the per-route verb gate, stage 2b;</li>
 *   <li>{@link EventType#PATH_NOT_ALLOWED} — the route's {@code allowed_paths} check, stage 3;</li>
 *   <li>{@link EventType#SECURITY_FILTER_VIOLATION} — the route-scoped security-filter run, stage 3;</li>
 *   <li>{@link EventType#PARAMETER_LIMIT_EXCEEDED} — raised only at stage 1 today, so inert here, but
 *       listed so that a future route-scoped raise cannot reopen the oracle;</li>
 *   <li>{@link EventType#CONTENT_TOO_LARGE} — the declared-{@code Content-Length} fast-reject of
 *       stage 3;</li>
 *   <li>{@link EventType#CSRF_REJECTED} — the session CSRF defence and the cookie-forwarding
 *       same-origin check, both before stage 4;</li>
 *   <li>{@link EventType#TOKEN_MISSING} and {@link EventType#TOKEN_INVALID} — the bearer branch of
 *       stage 4.</li>
 * </ul>
 * <p>
 * <strong>Excluded, each for a recorded reason:</strong>
 * <ul>
 *   <li>{@link EventType#NO_ROUTE_MATCHED} and {@link EventType#PASSTHROUGH_HOST_SMUGGLED} — they
 *       <em>are</em> the unrouted answer, raised before route selection (ADR-0059 already keeps them
 *       identical on the wire);</li>
 *   <li>{@link EventType#SCOPE_MISSING} — the credential validated, so the caller is trusted;</li>
 *   <li>a session-branch {@link EventType#TOKEN_MISSING} — the session branch's {@code 302} /
 *       {@code 401} negotiation (the verify-first clause);</li>
 *   <li>{@link EventType#WEBSOCKET_ORIGIN_REJECTED}, {@link EventType#INBOUND_BODY_ABORTED},
 *       {@link EventType#REQUEST_BODY_TIMEOUT}, the streaming {@link EventType#CONTENT_TOO_LARGE}, the
 *       dispatch-time {@link EventType#SECURITY_FILTER_VIOLATION} for an undeclared body on a bodyless
 *       method, the asset action's dispatch-path {@code 405} and {@link EventType#UPSTREAM_ERROR} /
 *       {@link EventType#UPSTREAM_CIRCUIT_OPEN} / {@link EventType#UPSTREAM_TIMEOUT} — raised after
 *       stage 4 completed: the caller is trusted, or the route is anonymous and its own success
 *       answer already discloses that it exists;</li>
 *   <li>{@link EventType#RESERVED_BODY_TOO_LARGE} and {@link EventType#LOGOUT_TOKEN_INVALID} — the
 *       reserved paths are answered before route selection and are a fixed, public set
 *       (ADR-0018);</li>
 *   <li>every stage-1 rejection, the framing gate included, and the {@code 405} of an unparseable
 *       method — address-independent: identical whether a route exists or not;</li>
 *   <li>the boot and informational events — they have no HTTP mapping.</li>
 * </ul>
 * The events raised after stage 4 share a constant with closed-set members in two cases
 * ({@code CONTENT_TOO_LARGE}, {@code SECURITY_FILTER_VIOLATION}); the trust-boundary leg of the
 * predicate, not the closed set, keeps those honest.
 * <p>
 * <strong>The response-timing floor.</strong> An identical answer is not enough while the time it
 * takes still tells the two paths apart: an unrouted address is answered straight after route
 * selection, a resolved rejection only after the stages before it ran. So while the switch is on,
 * every answer rendered as the unrouted {@code 404} — the genuine {@link EventType#NO_ROUTE_MATCHED}
 * and {@link EventType#PASSTHROUGH_HOST_SMUGGLED} as much as a resolved rejection — leaves the edge no
 * earlier than {@link #RESPONSE_FLOOR} plus a random jitter below {@link #RESPONSE_JITTER} after the
 * request was admitted ({@link #delaysAnswer}, {@link #releaseAtNanos}). The wait is a Vert.x timer
 * ({@code edge.end.ResponseDelay}), so no thread is held while an answer waits. Honest answers are
 * never delayed.
 * <p>
 * Thread-safe: besides the switch, the only state is the jitter source, a {@link SecureRandom}.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class UniformNotFound {

    /**
     * The closed set of events answered as the unrouted {@code 404} to a caller that has not passed
     * the trust boundary. A new {@link EventType} constant is not a member until it is added here with
     * its raise site; {@code UniformNotFoundTest} fails for a constant that is neither a member nor in
     * the recorded exclusion table.
     */
    public static final Set<EventType> CLOSED_SET = Collections.unmodifiableSet(EnumSet.of(
            EventType.METHOD_NOT_ALLOWED,
            EventType.PATH_NOT_ALLOWED,
            EventType.SECURITY_FILTER_VIOLATION,
            EventType.PARAMETER_LIMIT_EXCEEDED,
            EventType.CONTENT_TOO_LARGE,
            EventType.CSRF_REJECTED,
            EventType.TOKEN_MISSING,
            EventType.TOKEN_INVALID));

    /**
     * The least time after admission before which no unrouted {@code 404} leaves the edge, while the
     * switch is on. It is meant to sit above the worst-case processing cost of the pre-trust window,
     * whose slowest arm is the offline validation of a well-formed bearer token with a wrong signature,
     * so that every rejection the window can raise has finished before the floor and an unrouted address
     * and a resolved rejection are released at the same moment. The value is a reasoned choice, not a
     * measurement: ADR-0072 records the reasoning and that the worst-case cost has not been measured. A
     * fixed constant of the gateway, like the hardening bounds of ADR-0060 and ADR-0064 — no
     * configuration key reaches it.
     */
    public static final Duration RESPONSE_FLOOR = Duration.ofMillis(50);

    /**
     * The exclusive upper bound of the random jitter added on top of {@link #RESPONSE_FLOOR}. Should
     * processing overrun the floor — a garbage-collection pause, an unusually large token — the
     * residual difference is blurred by a jitter drawn afresh for every answer instead of showing up as
     * a fixed offset. A fixed constant like the floor; no configuration key reaches it.
     */
    public static final Duration RESPONSE_JITTER = Duration.ofMillis(20);

    private final boolean enabled;
    /** Draws the jitter; a predictable jitter could be subtracted out by a caller that learned it. */
    private final SecureRandom jitterSource = new SecureRandom();

    /**
     * @param enabled the resolved {@code security_defaults.uniform_not_found} switch
     */
    public UniformNotFound(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Decides whether a rejection is answered as the unrouted {@code 404}; see the class
     * documentation for the five legs of the predicate.
     *
     * @param request   the rejected request, {@code null} for a rejection raised before one was built
     *                  — such a rejection is never resolved
     * @param eventType the event the rejection reports — the TRUE event, which stays the one metered
     * @return {@code true} when the rejection is rendered as the unrouted {@code 404}
     */
    public boolean applies(@Nullable PipelineRequest request, EventType eventType) {
        if (!enabled || request == null || request.selectedRoute() == null || request.trustBoundaryPassed()) {
            return false;
        }
        if (!CLOSED_SET.contains(eventType)) {
            return false;
        }
        return !(request.onSessionBranch() && eventType.category() == EventCategory.AUTHENTICATION);
    }

    /**
     * Decides whether an answer waits for the response-timing floor: {@code true} exactly when the
     * switch is on and the answer is rendered as the unrouted {@code 404} —
     * {@link EventType#NO_ROUTE_MATCHED}, which a resolved rejection is rendered as, or
     * {@link EventType#PASSTHROUGH_HOST_SMUGGLED}, which is identical to it on the wire.
     *
     * @param renderedAs the event the answer is rendered as, {@code null} for an unexpected internal
     *                   failure
     * @return {@code true} when the answer is released no earlier than {@link #releaseAtNanos}
     */
    public boolean delaysAnswer(@Nullable EventType renderedAs) {
        return enabled && (renderedAs == EventType.NO_ROUTE_MATCHED
                || renderedAs == EventType.PASSTHROUGH_HOST_SMUGGLED);
    }

    /**
     * The release time of an unrouted {@code 404}: admission plus {@link #RESPONSE_FLOOR} plus a fresh
     * random jitter in {@code [0, RESPONSE_JITTER)}.
     *
     * @param admittedAtNanos the {@link System#nanoTime()} reading taken when the request was admitted
     * @return the {@link System#nanoTime()} value before which the answer is not written
     */
    public long releaseAtNanos(long admittedAtNanos) {
        return admittedAtNanos + RESPONSE_FLOOR.toNanos() + jitterSource.nextLong(RESPONSE_JITTER.toNanos());
    }
}
