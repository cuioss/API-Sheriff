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
package de.cuioss.sheriff.gateway.bff.refresh;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.pending.BindingCookieCodec;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationRecord;
import de.cuioss.sheriff.gateway.bff.pending.PendingAuthorizationStore;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.token.client.flow.StepUpChallengeParser;
import de.cuioss.sheriff.token.client.flow.StepUpChallengeParser.StepUpChallenge;
import de.cuioss.sheriff.token.client.flow.StepUpHandler;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

/**
 * RFC 9470 step-up orchestration for a {@code require: session} route (D7) — the gateway-side
 * handling of an upstream {@code insufficient_user_authentication} challenge.
 * <p>
 * When a mediated upstream answers {@code 401} with a
 * {@code WWW-Authenticate: Bearer error="insufficient_user_authentication"} challenge (RFC 9470),
 * the gateway must obtain a token that satisfies the demanded authentication context
 * ({@code acr_values} / {@code max_age}) and replay the original request. The gateway re-implements
 * <strong>no</strong> OAuth leg: the engine ({@code token-sheriff-client}) owns the RFC 9470
 * challenge grammar ({@link StepUpChallengeParser}) and the step-up authorization-request
 * construction ({@link StepUpHandler} — carrying the elevated {@code acr_values} / {@code max_age}
 * into the auth-code flow). This coordinator only orchestrates the gateway-side concerns.
 * <p>
 * The two-step orchestration:
 * <ol>
 *   <li><strong>Silent satisfaction first.</strong> The {@link SilentSatisfaction} seam attempts to
 *       satisfy the challenge without a user-interactive redirect (the current session may already
 *       meet the demanded context, or the IdP may elevate silently). When it succeeds the original
 *       request is {@linkplain StepUpOutcome#satisfied(SessionRecord) replayed} straight away with
 *       the elevated session.</li>
 *   <li><strong>Re-drive otherwise.</strong> When silent satisfaction is not possible the engine
 *       builds a step-up authorization request carrying the challenge's elevated parameters; the
 *       coordinator persists its transaction as a single-use {@link PendingAuthorizationRecord}
 *       (whose same-origin-validated return URL is the URL of the request being replayed), mints the
 *       browser-binding cookie, and {@linkplain StepUpOutcome#reDrive(String, List) re-drives} the
 *       browser through the auth-code flow — exactly the {@code LoginFlow} shape, only with the
 *       elevated parameters. The recorded return URL is same-origin-validated so the post-step-up
 *       redirect is never an open redirect.</li>
 * </ol>
 * The class is framework-agnostic — it consumes a raw {@code WWW-Authenticate} header value and
 * returns a {@link StepUpOutcome} the edge renders, so it is unit-testable without a container or a
 * live IdP. It is exercised only when {@code session.step_up.honor_upstream_challenge} is enabled;
 * the enablement gate is the runtime's concern.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class StepUpCoordinator {

    private static final CuiLogger LOGGER = new CuiLogger(StepUpCoordinator.class);

    /** The safe default replay target when no valid same-origin replay URL is supplied. */
    public static final String DEFAULT_RETURN_URL = "/";

    private final StepUpChallengeParser challengeParser;
    private final SilentSatisfaction silentSatisfaction;
    private final StepUpInitiation stepUpInitiation;
    private final PendingAuthorizationStore pendingStore;
    private final BindingCookieCodec bindingCookieCodec;
    private final String gatewayOrigin;

    /**
     * Assembles the coordinator with the engine step-up seams and the gateway-side stores.
     *
     * @param silentSatisfaction the silent-step-up seam (bound to the engine's silent elevation; a
     *                           test binds a fixed present/absent result)
     * @param stepUpInitiation   the engine step-up authorization seam (bound to
     *                           {@code StepUpHandler#initiate}; a test binds a hand-built request)
     * @param pendingStore       the single-use pending-authorization store the re-drive transaction
     *                           is persisted to
     * @param bindingCookieCodec the browser-binding cookie codec
     * @param gatewayOrigin      the gateway's own origin (the {@code redirect_uri} origin) used to
     *                           same-origin-validate the replay return URL
     */
    public StepUpCoordinator(SilentSatisfaction silentSatisfaction, StepUpInitiation stepUpInitiation,
            PendingAuthorizationStore pendingStore, BindingCookieCodec bindingCookieCodec, String gatewayOrigin) {
        this.challengeParser = new StepUpChallengeParser();
        this.silentSatisfaction = Objects.requireNonNull(silentSatisfaction, "silentSatisfaction");
        this.stepUpInitiation = Objects.requireNonNull(stepUpInitiation, "stepUpInitiation");
        this.pendingStore = Objects.requireNonNull(pendingStore, "pendingStore");
        this.bindingCookieCodec = Objects.requireNonNull(bindingCookieCodec, "bindingCookieCodec");
        this.gatewayOrigin = Objects.requireNonNull(gatewayOrigin, "gatewayOrigin");
    }

    /**
     * Parses an upstream {@code WWW-Authenticate} header for an RFC 9470
     * {@code insufficient_user_authentication} challenge (the engine owns the grammar).
     *
     * @param wwwAuthenticate the upstream response's raw {@code WWW-Authenticate} header value, may be absent
     * @return the parsed step-up challenge, or empty when the header carries no step-up challenge
     */
    public Optional<StepUpChallenge> detectChallenge(@Nullable String wwwAuthenticate) {
        if (wwwAuthenticate == null || wwwAuthenticate.isBlank()) {
            return Optional.empty();
        }
        return challengeParser.parse(wwwAuthenticate);
    }

    /**
     * Coordinates a detected step-up challenge: attempts silent satisfaction, else re-drives the
     * auth-code flow with the challenge's elevated parameters and records the replay target.
     *
     * @param session   the live session whose upstream call was challenged
     * @param challenge the parsed RFC 9470 challenge (from {@link #detectChallenge})
     * @param replayUrl the URL of the request to replay after step-up (same-origin-validated)
     * @param now       the reference instant (the re-drive pending record's TTL anchor)
     * @return {@link StepUpOutcome#satisfied(SessionRecord) satisfied} when silent elevation worked,
     *         otherwise {@link StepUpOutcome#reDrive(String, List) reDrive} carrying the redirect and
     *         the browser-binding {@code Set-Cookie}
     */
    public StepUpOutcome coordinate(SessionRecord session, StepUpChallenge challenge, @Nullable String replayUrl,
            Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(challenge, "challenge");
        Objects.requireNonNull(now, "now");

        Optional<SessionRecord> elevated = silentSatisfaction.attempt(session, challenge, now);
        if (elevated.isPresent()) {
            LOGGER.debug("Step-up challenge satisfied silently — replaying the original request");
            return StepUpOutcome.satisfied(elevated.get());
        }

        StepUpHandler.StepUpRequest request = stepUpInitiation.initiate(challenge);
        String returnUrl = PendingAuthorizationRecord.sameOrigin(replayUrl, gatewayOrigin)
                ? replayUrl : DEFAULT_RETURN_URL;
        PendingAuthorizationRecord record = PendingAuthorizationRecord.create(request.context(), returnUrl, now);
        pendingStore.store(record);
        LOGGER.debug("Step-up challenge requires re-authentication — re-driving the auth-code flow");
        List<String> setCookies = List.of(bindingCookieCodec.toSetCookieHeader(record.id()));
        return StepUpOutcome.reDrive(request.authorizationUrl(), setCookies);
    }

    /**
     * The silent-step-up seam. The session runtime binds it to the engine's attempt to elevate the
     * authentication context without a user-interactive redirect (the current session may already
     * satisfy the demanded {@code acr}, or the IdP may elevate silently); a test binds a fixed
     * present/absent result. An empty result means a full user-interactive re-drive is required.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface SilentSatisfaction {

        /**
         * Attempts to satisfy the step-up challenge without a browser redirect.
         *
         * @param session   the live session being elevated
         * @param challenge the RFC 9470 challenge demanding a stronger authentication context
         * @param now       the reference instant
         * @return the elevated session when satisfied silently, empty when a re-drive is required
         */
        Optional<SessionRecord> attempt(SessionRecord session, StepUpChallenge challenge, Instant now);
    }

    /**
     * The engine step-up authorization seam. The session runtime binds it to the engine as
     * {@code challenge -> stepUpHandler.initiate(clientConfiguration, providerMetadata, challenge)};
     * a test binds a hand-built request. The engine carries the challenge's {@code acr_values} /
     * {@code max_age} into the authorization request; keeping the confidential-client wiring behind
     * the seam decouples the coordinator from it.
     *
     * @author API Sheriff Team
     * @since 1.0
     */
    @FunctionalInterface
    public interface StepUpInitiation {

        /**
         * Builds the step-up authorization request (URL + transaction context) for the challenge.
         *
         * @param challenge the RFC 9470 challenge whose elevated parameters drive the request
         * @return the engine step-up request (authorization URL + transaction {@code FlowContext})
         */
        StepUpHandler.StepUpRequest initiate(StepUpChallenge challenge);
    }

    /**
     * The framework-agnostic result of step-up coordination: either the challenge was
     * {@link Kind#SATISFIED} silently (replay the original request with the elevated session) or the
     * browser must be {@link Kind#RE_DRIVE re-driven} through the auth-code flow carrying the
     * browser-binding {@code Set-Cookie}. Token material never appears here.
     *
     * @param kind             which of the two step-up outcomes occurred
     * @param session          the elevated session, present only for {@link Kind#SATISFIED}
     * @param location         the step-up redirect target, present only for {@link Kind#RE_DRIVE}
     * @param setCookieHeaders the browser-binding {@code Set-Cookie} header values, empty when satisfied
     * @author API Sheriff Team
     * @since 1.0
     */
    public record StepUpOutcome(Kind kind, Optional<SessionRecord> session, Optional<String> location,
    List<String> setCookieHeaders) {

        /**
         * The two terminal states of a step-up coordination.
         *
         * @author API Sheriff Team
         * @since 1.0
         */
        public enum Kind {
            /** The challenge was satisfied silently — the original request is replayed. */
            SATISFIED,
            /** The browser is re-driven through the auth-code flow with the elevated parameters. */
            RE_DRIVE
        }

        /**
         * Canonical constructor normalizing absent optionals and defensively copying the cookies.
         */
        public StepUpOutcome {
            Objects.requireNonNull(kind, "kind");
            session = Objects.requireNonNullElse(session, Optional.empty());
            location = Objects.requireNonNullElse(location, Optional.empty());
            setCookieHeaders = setCookieHeaders == null ? List.of() : List.copyOf(setCookieHeaders);
        }

        /**
         * A silently-satisfied outcome carrying the elevated session for the replay.
         *
         * @param session the session whose authentication context now satisfies the challenge
         * @return the satisfied outcome
         */
        public static StepUpOutcome satisfied(SessionRecord session) {
            Objects.requireNonNull(session, "session");
            return new StepUpOutcome(Kind.SATISFIED, Optional.of(session), Optional.empty(), List.of());
        }

        /**
         * A re-drive outcome carrying the step-up redirect and the browser-binding {@code Set-Cookie}.
         *
         * @param location         the step-up authorization URL to redirect the browser to
         * @param setCookieHeaders the browser-binding {@code Set-Cookie} header values
         * @return the re-drive outcome
         */
        public static StepUpOutcome reDrive(String location, List<String> setCookieHeaders) {
            Objects.requireNonNull(location, "location");
            return new StepUpOutcome(Kind.RE_DRIVE, Optional.empty(), Optional.of(location), setCookieHeaders);
        }

        /**
         * @return {@code true} when the challenge was satisfied silently (the request is replayed)
         */
        public boolean isSatisfied() {
            return kind == Kind.SATISFIED;
        }
    }
}
