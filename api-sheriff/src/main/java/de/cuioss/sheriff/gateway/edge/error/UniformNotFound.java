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
 * oracle, ADR-0073).
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
 *       stage 4;</li>
 *   <li>{@link EventType#PRE_TRUST_INTERNAL_FAILURE} — an unexpected internal failure anywhere in the
 *       pre-trust window, reported by {@code ErrorResponseWriter.renderUnexpected}; without it a
 *       crafted input that makes a stage fail would answer {@code 500} on a routed address and
 *       {@code 404} on an absent one.</li>
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
 *   <li>{@link EventType#SOURCE_BLOCKED} — raised before route selection, on the event loop before
 *       admission, and keyed by the caller's source address rather than by the address it requested:
 *       address-independent, identical whether a route exists or not;</li>
 *   <li>the boot and informational events, {@link EventType#SOURCE_TRIPPED} among them — they have no
 *       HTTP mapping.</li>
 * </ul>
 * The events raised after stage 4 share a constant with closed-set members in two cases
 * ({@code CONTENT_TOO_LARGE}, {@code SECURITY_FILTER_VIOLATION}); the trust-boundary leg of the
 * predicate, not the closed set, keeps those honest.
 * <p>
 * <strong>The response-timing floor.</strong> An identical answer is not enough while the time it
 * takes still tells the two paths apart: an unrouted address is answered straight after route
 * selection, a resolved rejection only after the stages before it ran. So while the switch is on,
 * every answer rendered as the unrouted {@code 404} — the genuine {@link EventType#NO_ROUTE_MATCHED}
 * and {@link EventType#PASSTHROUGH_HOST_SMUGGLED} as much as a resolved rejection — waits for a release
 * time on a grid of {@link #RESPONSE_FLOOR} steps counted from admission ({@link #delaysAnswer},
 * {@link #release}). <strong>The step rule:</strong> the answer leaves no earlier than the first
 * floor step after admission that its processing has not passed, plus a random jitter below
 * {@link #RESPONSE_JITTER}. An answer ready within the floor leaves after one step; an answer whose
 * processing ran past the floor is held to the next step instead of being written at once, so every
 * such answer leaves on the same grid. The writer computes the release on the event loop at the moment
 * it arms the answer — after an HTML error page has rendered and after the hop from the virtual thread —
 * so neither can push an answer off the grid; an answer released on a step above the first is an
 * <em>overrun</em> ({@link Release#overran()}), which the writer meters. The wait is a Vert.x timer
 * ({@code edge.end.ResponseDelay}), so no thread is held while an answer waits. Honest answers are never
 * delayed.
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
            EventType.TOKEN_INVALID,
            EventType.PRE_TRUST_INTERNAL_FAILURE));

    /**
     * The least time after admission before which no unrouted {@code 404} leaves the edge, while the
     * switch is on, and the width of each step of the release grid ({@link #release}). It sits above the
     * processing cost of the pre-trust window, so that every rejection the window can raise has finished
     * before the first step and an unrouted address and a resolved rejection are released on the same
     * step. An answer whose processing ran past a step is held to the next one and counted as an overrun.
     * The value is measured, not only reasoned: {@code EnumerationOracleIT} drives every pre-trust path a
     * caller can reach on the compose stack — the unrouted address, the verb gate, a path outside
     * {@code allowed_paths}, a missing, malformed, oversized or unknown-key bearer token and the session
     * branch's CSRF refusal — and asserts that none of them recorded an overrun on
     * {@code sheriff_uniform_not_found_overruns_total}. A fixed constant of the gateway, like the
     * hardening bounds of ADR-0060 and ADR-0064 — no configuration key reaches it.
     */
    public static final Duration RESPONSE_FLOOR = Duration.ofMillis(50);

    /**
     * The exclusive upper bound of the random jitter added on top of the floor step an answer is
     * released on ({@link #release}). The jitter is drawn afresh for every answer, so the
     * release time within a step is not a fixed offset. A fixed constant like the floor; no
     * configuration key reaches it.
     */
    public static final Duration RESPONSE_JITTER = Duration.ofMillis(20);

    /**
     * The release of one unrouted {@code 404} under the step rule ({@link UniformNotFound#release}).
     *
     * @param atNanos the {@link System#nanoTime()} value before which the answer is not written
     * @param step    the floor step the answer is released on, counted from admission; {@code 1} for an
     *                answer ready within the floor
     */
    public record Release(long atNanos, long step) {

        /**
         * @return {@code true} when the answer is released on a step above the first — its processing ran
         *         past the floor
         */
        public boolean overran() {
            return step > 1;
        }
    }

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
     * @return {@code true} when the answer is released no earlier than {@link #release}
     */
    public boolean delaysAnswer(@Nullable EventType renderedAs) {
        return enabled && (renderedAs == EventType.NO_ROUTE_MATCHED
                || renderedAs == EventType.PASSTHROUGH_HOST_SMUGGLED);
    }

    /**
     * The release of an unrouted {@code 404} under the step rule: the first {@link #RESPONSE_FLOOR}
     * step after admission that the processing has not passed, plus a fresh random jitter in
     * {@code [0, RESPONSE_JITTER)}, together with the index of that step.
     * <p>
     * With {@code elapsed = max(0, nowNanos - admittedAtNanos)}, the answer is released after
     * {@code step = max(1, ceil(elapsed / RESPONSE_FLOOR))} floor steps. An elapsed time within the
     * floor gives step {@code 1}, admission plus the floor. An elapsed time that lands exactly on a step
     * boundary stays on that step; one nanosecond past it moves to the next. A {@code nowNanos} read
     * before {@code admittedAtNanos} counts as no elapsed time. Without the jitter, the release time is
     * never earlier than {@code nowNanos}.
     * <p>
     * The arithmetic runs on the difference of the two readings, never on their absolute values, so it
     * holds across the wrap of {@link System#nanoTime()}.
     *
     * @param admittedAtNanos the {@link System#nanoTime()} reading taken when the request was admitted
     * @param nowNanos        the {@link System#nanoTime()} reading taken when the answer is armed
     * @return the release time and the step it falls on
     */
    public Release release(long admittedAtNanos, long nowNanos) {
        long floorNanos = RESPONSE_FLOOR.toNanos();
        long elapsedNanos = Math.max(0L, nowNanos - admittedAtNanos);
        long step = Math.max(1L, Math.ceilDiv(elapsedNanos, floorNanos));
        return new Release(admittedAtNanos + Math.multiplyExact(step, floorNanos)
                + jitterSource.nextLong(RESPONSE_JITTER.toNanos()), step);
    }
}
