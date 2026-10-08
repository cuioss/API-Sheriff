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
package de.cuioss.sheriff.gateway.bff.cookie;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec.CookieSizeBudgetExceededException;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import org.jspecify.annotations.Nullable;

/**
 * The stateless cookie-mode {@link SessionBinding} ({@code session.mode: cookie}) — the sealed
 * cookie <em>is</em> the session.
 * <p>
 * {@link #bind} seals the {@link SessionRecord} into a hardened {@code Set-Cookie};
 * {@link #resolve} unseals the request cookie, enforces the absolute TTL <strong>server-side</strong>
 * against the sealed login instant (a browser that keeps an expired cookie past its {@code Max-Age}
 * still gets "no session"), and reconstructs the record; {@link #persist} re-seals the updated
 * material; {@link #destroy} is a no-op locally — the browser's copies are cleared through
 * {@link #clearingSetCookieHeaders()}, which the logout edge emits. A client that retains the cookie of a
 * session the refresh coordinator ended is refused by that coordinator's ended-refresh-token marker,
 * not by this binding.
 * <p>
 * <strong>Idle timeout, kept in a separate activity cookie.</strong> {@link #resolve} additionally
 * refuses a session whose last access is older than the idle timeout. The last access is the instant
 * in the activity cookie the same request carries ({@link SessionActivityCookieCodec}), provided that
 * cookie verifies for this very session. When it is missing, unreadable, forged or bound to
 * another session, the last access is the session's <em>login instant</em>: an activity cookie can
 * only ever lengthen a session up to the idle timeout past a real access, and its absence can only
 * shorten it. {@link #recordAccess} returns a new activity cookie once the last access is at least one
 * <em>re-issue interval</em> old and nothing before that, so most responses carry no
 * {@code Set-Cookie}. The re-issue interval is the smaller of {@link #ACTIVITY_COOKIE_INTERVAL} and
 * half the idle timeout, so it is shorter than every idle timeout and an access can always be recorded
 * before the deadline it is meant to move. The recorded last access is therefore at most one interval
 * older than the real one: a session ends no later than the idle timeout after its last access and at
 * most one interval earlier, and a session accessed at least once per idle timeout minus one interval
 * stays alive up to its absolute lifetime. <strong>The session cookie
 * is never rewritten on access</strong> — the idle touch writes the activity cookie alone, so it cannot
 * race a refresh for the token-bearing cookie, and the sealed session payload gains no field. The
 * absolute lifetime is enforced against the sealed login instant as before; no activity cookie
 * extends it.
 * <p>
 * <strong>An update cannot observe a logout.</strong> Because {@link #destroy} removes nothing — there
 * is nothing held to remove — {@link #persist} has no state to consult and always re-seals: it never
 * reports the session gone. A refresh or a widening that was in flight while the browser logged out
 * therefore still produces a sealed {@code Set-Cookie}; if that response reaches the browser after the
 * logout response, the browser again holds a cookie this gateway resolves as a session, until the
 * session's absolute deadline. This is a limit of the stateless mode, not of this method, and the
 * server-mode binding does not share it.
 * <p>
 * <strong>No IdP-driven destruction.</strong> A stateless gateway holds no index and cannot reach
 * another browser's cookie, so {@link #idpDestruction()} reports
 * {@link IdpDestruction#UNSUPPORTED} and both {@code destroyBySid} / {@code destroyBySub} report
 * zero destroyed. That capability signal is what gates the back-channel logout endpoint off in this
 * mode rather than letting it claim a destruction that never happened.
 * <p>
 * <strong>One sealing key.</strong> The codec holds exactly one key, so there is no rollover state
 * to carry and no re-seal to schedule: a cookie sealed under a withdrawn key carries an unknown key
 * id, unseals to "no session", and the caller re-authenticates.
 * <p>
 * <strong>Session identity.</strong> Per the seam's single identity model, the reconstructed
 * {@link SessionRecord#sessionId()} is a keyed digest over the sealed payload's login instant,
 * {@code sub} and the per-session nonce minted once at login. It is stable for the life of the
 * session — so the refresh coordinator's single-flight
 * coalescing behaves exactly as in server mode — and is <strong>never emitted to the browser</strong>:
 * only the sealed value crosses. Single-flight is necessarily <em>per instance</em> here; a
 * cross-instance duplicate refresh cannot be prevented without shared state and is this variant's
 * documented, accepted trade-off.
 * <p>
 * <strong>Active and granted scope sets.</strong> The session's active scope set {@code A} and its
 * granted scope set {@code S} are sealed alongside the token material on {@link #bind}, restored on
 * {@link #resolve}, and re-sealed from the rotated record on {@link #persist}, so a refresh that
 * changes {@code A} or reduces {@code S}, or a widening that replaces both, is carried into the next
 * request. The same holds for the {@code sid} a widening takes from its grant's ID token. The two sets
 * are sealed as independent fields and never derived from one another. Neither is an identity input:
 * a changed {@code A} or {@code S} leaves the derived session identity untouched.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CookieSessionBinding implements SessionBinding {

    /**
     * The upper bound of the re-issue interval — the shortest distance between two activity cookies of
     * one session. A new activity cookie is issued only when the last access is at least one re-issue
     * interval old, which keeps the {@code Set-Cookie} traffic of a well-behaved client to one per
     * session per interval, at the price of an idle deadline that can fall up to one interval early.
     * It is not a bound a client has to honour: requests sent in parallel, or sent again with an old
     * activity cookie, each receive a new one, which is why the activity cookie is authenticated with
     * a primitive that has no per-key usage limit. The interval in force is this value for an idle timeout of at least twice it,
     * and half the idle timeout below that, so it never reaches the idle timeout. Not configurable.
     */
    public static final Duration ACTIVITY_COOKIE_INTERVAL = Duration.ofSeconds(60);

    private static final String DIGEST_ALGORITHM = "SHA-256";

    /** The derived identity's width — the width the activity cookie binds itself to. */
    private static final int IDENTITY_BYTES = SessionActivityCookieCodec.SESSION_IDENTITY_BYTES;

    /**
     * The per-session nonce width, matched to {@code SessionRecord.newSessionId()}'s 32 bytes so the
     * cookie-mode nonce carries the same entropy as the server-mode session id it stands in for.
     */
    private static final int SESSION_NONCE_BYTES = 32;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SealedSessionCookieCodec codec;
    private final byte[] identitySalt;
    private final SessionActivityCookieCodec activityCodec;
    private final Duration idleTimeout;
    /**
     * The re-issue interval in force: the smaller of {@link #ACTIVITY_COOKIE_INTERVAL} and half the
     * idle timeout. Being shorter than the idle timeout is what lets an access be recorded before the
     * idle deadline it moves, for every idle timeout.
     */
    private final Duration activityCookieInterval;

    /**
     * Assembles the cookie-mode binding over its sealing codec and its activity-cookie codec.
     *
     * @param codec         the AES-256-GCM sealed-cookie codec
     * @param identitySalt  the per-gateway salt keying the derived session identity, so the identity
     *                      cannot be recomputed from the payload alone by anything outside this
     *                      gateway. Never emitted to the browser.
     * @param activityCodec the codec of the activity cookie the last access is kept in
     * @param idleTimeout   how long a session may go without an access before it is refused; must be
     *                      positive
     * @throws IllegalArgumentException when {@code idleTimeout} is not positive
     */
    public CookieSessionBinding(SealedSessionCookieCodec codec, byte[] identitySalt,
            SessionActivityCookieCodec activityCodec, Duration idleTimeout) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.identitySalt = Objects.requireNonNull(identitySalt, "identitySalt").clone();
        this.activityCodec = Objects.requireNonNull(activityCodec, "activityCodec");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must be positive, but was " + idleTimeout);
        }
        this.idleTimeout = idleTimeout;
        Duration halfIdleTimeout = idleTimeout.dividedBy(2);
        this.activityCookieInterval = halfIdleTimeout.compareTo(ACTIVITY_COOKIE_INTERVAL) < 0
                ? halfIdleTimeout
                : ACTIVITY_COOKIE_INTERVAL;
    }

    @Override
    public BoundSession bind(SessionRecord session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        // A fresh login anchors the absolute lifetime at this instant and mints the session's one
        // nonce. Minting here — once, on the login path only — is what makes two logins by the same
        // subject within a single clock second resolve to distinct identities.
        return seal(payloadOf(session, now, newSessionNonce()), now);
    }

    @Override
    public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");
        return codec.readSealedValue(cookieHeader)
                .flatMap(codec::unseal)
                .filter(unsealed -> !unsealed.payload().isExpired(codec.sessionTtl(), now))
                .map(unsealed -> toSessionRecord(unsealed.payload()))
                // The idle deadline, inclusive of the boundary like the absolute one. It is checked
                // against the last access this same request proves, and never moved here.
                .filter(session -> now.isBefore(lastAccess(session, cookieHeader, now).plus(idleTimeout)));
    }

    /**
     * The last access of {@code session} as this request proves it: the instant in the request's
     * activity cookie when that cookie verifies for this session, otherwise the session's
     * login instant.
     * <p>
     * Falling back to the login instant is what makes a missing, unreadable, forged or foreign activity
     * cookie harmless: it never counts as an access, so withholding or swapping the cookie can only
     * end a session sooner. An instant before the login instant is not accepted either — a session
     * cannot have been accessed before it existed.
     */
    private Instant lastAccess(SessionRecord session, @Nullable String cookieHeader, Instant now) {
        Instant loginInstant = session.expiresAt().minus(codec.sessionTtl());
        return activityCodec.read(cookieHeader, session.sessionId(), now)
                .filter(signed -> signed.isAfter(loginInstant))
                .orElse(loginInstant);
    }

    /**
     * Re-seals the updated material into a fresh {@code Set-Cookie} <em>without</em> extending the
     * session.
     * <p>
     * There is nothing to write server-side, so the whole of "persisting" a refresh or a widening
     * here is emitting the new sealed value. The original login instant is re-derived from the
     * record's absolute deadline rather than taken from {@code now}, so a session still dies at its
     * original absolute deadline however often it is re-sealed: an update replaces the tokens, never
     * the session's lifetime.
     * <p>
     * The session nonce is re-sealed <strong>verbatim</strong> and never re-minted, for the same
     * reason the login instant is re-derived rather than refreshed: both are identity inputs, so
     * minting a new nonce here would change the derived {@link SessionRecord#sessionId()} mid-session
     * and break the refresh coordinator's single-flight coalescing.
     * <p>
     * <strong>Never reports the session gone.</strong> This binding holds no server-side state, so it
     * cannot tell whether the browser logged out since the caller resolved the session: the result is
     * always present. The re-sealed cookie it returns is one this gateway resolves as a session until
     * the absolute deadline, even when a logout response has already cleared the browser's previous
     * one (see the class documentation).
     * <p>
     * Thread-safe: the method reads only its arguments and the immutable collaborators.
     *
     * @param updated the session carrying the new token material
     * @param now     the reference instant, used only for the cookie's remaining {@code Max-Age}
     * @return the re-sealed session and its single {@code Set-Cookie}; never empty
     * @throws IllegalStateException when the record carries no session nonce, or the sealed value
     *         exceeds the cookie size budget
     */
    @Override
    public Optional<BoundSession> persist(SessionRecord updated, Instant now) {
        Objects.requireNonNull(updated, "updated");
        Objects.requireNonNull(now, "now");
        Instant loginInstant = updated.expiresAt().minus(codec.sessionTtl());
        // Fail loud rather than mint a replacement: a cookie-mode record always carries the nonce
        // sealed at login, so an absent one means a caller reconstructed the record and dropped it —
        // silently re-minting would change the session's identity mid-flight.
        String sessionNonce = updated.sessionNonce();
        if (sessionNonce == null) {
            throw new IllegalStateException(
                    "cookie-mode session record carries no sessionNonce — cannot re-seal without changing "
                            + "the derived session identity");
        }
        return Optional.of(seal(payloadOf(updated, loginInstant, sessionNonce), now));
    }

    /**
     * Re-seals the updated material exactly as {@link #persist} does — the step-up and widening write.
     * <p>
     * In this mode "re-issuing the cookie" and "persisting" are the same act: the sealed value
     * <em>is</em> the cookie, so every update already hands the browser a new one. The session nonce is
     * carried verbatim (ADR-0018 decision (v)); the derived {@link SessionRecord#sessionId()} therefore
     * does not change, and no nonce is minted outside {@link #bind}.
     *
     * @param updated the session carrying the new token material
     * @param now     the reference instant, used only for the cookie's remaining {@code Max-Age}
     * @return the re-sealed session and its single {@code Set-Cookie}; never empty
     * @throws IllegalStateException when the record carries no session nonce, or the sealed value
     *         exceeds the cookie size budget
     */
    @Override
    public Optional<BoundSession> persistReissuingCookie(SessionRecord updated, Instant now) {
        return persist(updated, now);
    }

    /**
     * Returns a new activity cookie when the session's last access is at least one re-issue interval
     * old — the smaller of {@link #ACTIVITY_COOKIE_INTERVAL} and half the idle timeout — and nothing
     * otherwise.
     * <p>
     * It never returns a session cookie: the token-bearing value is left exactly as the browser holds
     * it. The activity cookie is bound to {@code session}'s derived identity and carries {@code now} as
     * the last access; its {@code Max-Age} is the session's remaining absolute lifetime.
     * <p>
     * Thread-safe: the method reads only its arguments and the immutable collaborators. Two concurrent
     * requests of one session may each return an activity cookie; either one is correct.
     *
     * @param session      the live session the request was let through with
     * @param cookieHeader the raw request {@code Cookie} header value the session was resolved from
     * @param now          the instant of the access
     * @return the single activity {@code Set-Cookie}, or an empty list while the last access is younger
     *         than the re-issue interval
     */
    @Override
    public List<String> recordAccess(SessionRecord session, @Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        Instant lastAccess = lastAccess(session, cookieHeader, now);
        if (Duration.between(lastAccess, now).compareTo(activityCookieInterval) < 0) {
            return List.of();
        }
        String signedActivity = activityCodec.sign(session.sessionId(), now);
        return List.of(activityCodec.toSetCookieHeader(signedActivity, session.expiresAt(), now));
    }

    @Override
    public void destroy(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        // Nothing is held server-side. The browser's copies are cleared by the caller emitting
        // clearingSetCookieHeaders() — an expired-by-Max-Age cookie the browser keeps anyway is
        // still refused by resolve()'s server-side TTL check.
    }

    @Override
    public int destroyBySid(String sid) {
        Objects.requireNonNull(sid, "sid");
        return 0;
    }

    @Override
    public int destroyBySub(String sub) {
        Objects.requireNonNull(sub, "sub");
        return 0;
    }

    @Override
    public IdpDestruction idpDestruction() {
        return IdpDestruction.UNSUPPORTED;
    }

    /**
     * @return the clearing header of the session cookie followed by that of the activity cookie
     */
    @Override
    public List<String> clearingSetCookieHeaders() {
        return List.of(codec.toClearingSetCookieHeader(), activityCodec.toClearingSetCookieHeader());
    }

    private BoundSession seal(SealedSessionPayload payload, Instant now) {
        String sealedValue;
        try {
            sealedValue = codec.seal(payload);
        } catch (CookieSizeBudgetExceededException overBudget) {
            // The browser would silently drop a cookie this large, so the session could never be
            // resolved again — fail loudly rather than hand back a binding that cannot work.
            throw new IllegalStateException("cookie-mode session exceeds the cookie size budget", overBudget);
        }
        SessionRecord bound = toSessionRecord(payload);
        return new BoundSession(bound,
                List.of(codec.toSetCookieHeader(sealedValue, payload.loginInstant(), now)));
    }

    private static SealedSessionPayload payloadOf(SessionRecord session, Instant loginInstant,
            String sessionNonce) {
        return new SealedSessionPayload(session.accessToken(), session.refreshToken(), session.idToken(),
                session.sub(), session.sid(), session.acr(), session.authTime(), loginInstant, sessionNonce,
                session.activeScopes(), session.grantedScopes());
    }

    /**
     * Mints one session nonce: {@link #SESSION_NONCE_BYTES} secure-random bytes, base64url-encoded so
     * it survives the payload's base64 field encoding unchanged.
     */
    private static String newSessionNonce() {
        byte[] bytes = new byte[SESSION_NONCE_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private SessionRecord toSessionRecord(SealedSessionPayload payload) {
        return SessionRecord.builder()
                .sessionId(derivedIdentity(payload))
                .sessionNonce(payload.sessionNonce())
                .accessToken(payload.accessToken())
                .refreshToken(payload.refreshToken())
                .idToken(payload.idToken())
                .sub(payload.sub())
                .sid(payload.sid())
                .expiresAt(payload.loginInstant().plus(codec.sessionTtl()))
                .acr(payload.acr())
                .authTime(payload.authTime())
                .activeScopes(payload.activeScopes())
                .grantedScopes(payload.grantedScopes())
                .build();
    }

    /**
     * Derives this binding's stable per-session identity: a salted digest over the sealed payload's
     * login instant, {@code sub} and per-session nonce. All three inputs are fixed for the life of
     * the session, so the identity is stable across re-seals; the salt keeps it un-recomputable
     * outside this gateway; and it is never emitted to the browser.
     * <p>
     * The nonce is what makes the identity <em>unique</em> rather than merely stable: login instant
     * and {@code sub} alone collide for two logins by the same subject inside one clock second, which
     * would cross-wire the refresh coordinator's single-flight keying between two distinct sessions.
     * The identity remains a digest — the raw nonce is never used as, or substituted for, the
     * session id.
     */
    private String derivedIdentity(SealedSessionPayload payload) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(DIGEST_ALGORITHM + " is required for the cookie-mode session identity",
                    unavailable);
        }
        digest.update(identitySalt);
        digest.update(Long.toString(payload.loginInstant().getEpochSecond()).getBytes(StandardCharsets.UTF_8));
        digest.update(payload.sub().getBytes(StandardCharsets.UTF_8));
        digest.update(payload.sessionNonce().getBytes(StandardCharsets.UTF_8));
        byte[] identity = new byte[IDENTITY_BYTES];
        System.arraycopy(digest.digest(), 0, identity, 0, IDENTITY_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(identity);
    }
}
