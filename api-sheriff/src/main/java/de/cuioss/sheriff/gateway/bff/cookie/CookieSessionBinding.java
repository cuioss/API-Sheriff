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
package de.cuioss.sheriff.gateway.bff.cookie;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec.CookieSizeBudgetExceededException;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.tools.logging.CuiLogger;

import org.jspecify.annotations.Nullable;

/**
 * The stateless cookie-mode {@link SessionBinding} ({@code session.mode: cookie}) — the sealed
 * cookie <em>is</em> the session.
 * <p>
 * {@link #bind} seals the {@link SessionRecord} into a hardened {@code Set-Cookie};
 * {@link #resolve} unseals the request cookie, enforces the absolute TTL <strong>server-side</strong>
 * against the sealed login instant (a browser that keeps an expired cookie past its {@code Max-Age}
 * still gets "no session"), and reconstructs the record; {@link #persist} re-seals the rotated
 * material; {@link #destroy} is a no-op locally — the browser's copy is cleared through
 * {@link #clearingSetCookieHeader()}, which the logout edge emits.
 * <p>
 * <strong>No IdP-driven destruction.</strong> A stateless gateway holds no index and cannot reach
 * another browser's cookie, so {@link #idpDestruction()} reports
 * {@link IdpDestruction#UNSUPPORTED} and both {@code destroyBySid} / {@code destroyBySub} report
 * zero destroyed. That capability signal is what gates the back-channel logout endpoint off in this
 * mode rather than letting it claim a destruction that never happened.
 * <p>
 * <strong>Key rotation (D2).</strong> When the codec carries a decrypt-only {@code previous_key}, a
 * cookie still sealed under it resolves normally and is marked for re-seal: because
 * {@link SealedSessionCookieCodec#seal} is unconditionally current-key, the session's next write —
 * {@link #bind} on a fresh login or {@link #persist} on a refresh — emits a cookie stamped with the
 * current key id, completing the rollover. The event is recorded once as INFO
 * {@code COOKIE_RESEALED_WITH_CURRENT_KEY} carrying only a bounded disposition. Rotation composes
 * with the <em>passed-key</em> mode only: when the key material is generated on startup there is by
 * construction no previous key, so no cookie can carry a retired key id and this path never fires.
 * <p>
 * <strong>Session identity.</strong> Per the seam's single identity model, the reconstructed
 * {@link SessionRecord#sessionId()} is a keyed digest over the sealed payload's login instant and
 * {@code sub}. It is stable for the life of the session — so the refresh coordinator's single-flight
 * coalescing behaves exactly as in server mode — and is <strong>never emitted to the browser</strong>:
 * only the sealed value crosses. Single-flight is necessarily <em>per instance</em> here; a
 * cross-instance duplicate refresh cannot be prevented without shared state and is this variant's
 * documented, accepted trade-off.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CookieSessionBinding implements SessionBinding {

    private static final CuiLogger LOGGER = new CuiLogger(CookieSessionBinding.class);

    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final int IDENTITY_BYTES = 16;

    /** The bounded, non-sensitive disposition recorded when a retired-key session is rolled over. */
    private static final String RESEAL_DISPOSITION = "previous-key rollover";

    private final SealedSessionCookieCodec codec;
    private final byte[] identitySalt;

    /**
     * Assembles the cookie-mode binding over its sealing codec.
     *
     * @param codec        the AES-256-GCM sealed-cookie codec
     * @param identitySalt the per-gateway salt keying the derived session identity, so the identity
     *                     cannot be recomputed from the payload alone by anything outside this
     *                     gateway. Never emitted to the browser.
     */
    public CookieSessionBinding(SealedSessionCookieCodec codec, byte[] identitySalt) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.identitySalt = Objects.requireNonNull(identitySalt, "identitySalt").clone();
    }

    @Override
    public BoundSession bind(SessionRecord session, Instant now) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(now, "now");
        // A fresh login anchors the absolute lifetime at this instant.
        return seal(payloadOf(session, now), now);
    }

    @Override
    public Optional<SessionRecord> resolve(@Nullable String cookieHeader, Instant now) {
        Objects.requireNonNull(now, "now");
        return codec.readSealedValue(cookieHeader)
                .flatMap(codec::unseal)
                .filter(unsealed -> !unsealed.payload().isExpired(codec.sessionTtl(), now))
                .map(this::markForReseal);
    }

    @Override
    public BoundSession persist(SessionRecord rotated, Instant now) {
        Objects.requireNonNull(rotated, "rotated");
        Objects.requireNonNull(now, "now");
        // The absolute deadline lives in expiresAt, which the login anchored; re-deriving the login
        // instant from it keeps the re-seal from extending the session.
        Instant loginInstant = rotated.expiresAt().minus(codec.sessionTtl());
        return seal(payloadOf(rotated, loginInstant), now);
    }

    @Override
    public void destroy(SessionRecord session) {
        Objects.requireNonNull(session, "session");
        // Nothing is held server-side. The browser's copy is cleared by the caller emitting
        // clearingSetCookieHeader() — an expired-by-Max-Age cookie the browser keeps anyway is
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

    @Override
    public String clearingSetCookieHeader() {
        return codec.toClearingSetCookieHeader();
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

    /**
     * Reconstructs the session record and records the {@code previous_key} rollover when the value
     * was authenticated by the retired key.
     * <p>
     * Nothing is held to make the re-seal happen: {@link SealedSessionCookieCodec#seal} is
     * unconditionally current-key, so every write this binding performs — {@link #bind} on a fresh
     * login and {@link #persist} on a refresh — necessarily emits a cookie stamped with the current
     * key id. The marking is therefore purely the audit record of the rollover, kept stateless so
     * the binding holds no server-side session index.
     */
    private SessionRecord markForReseal(SealedSessionCookieCodec.Unsealed unsealed) {
        if (unsealed.sealedWithPreviousKey()) {
            LOGGER.info(BffLogMessages.INFO.COOKIE_RESEALED_WITH_CURRENT_KEY, RESEAL_DISPOSITION);
        }
        return toSessionRecord(unsealed.payload());
    }

    private static SealedSessionPayload payloadOf(SessionRecord session, Instant loginInstant) {
        return new SealedSessionPayload(session.accessToken(), session.refreshToken(), session.idToken(),
                session.sub(), session.sid(), session.acr(), session.authTime(), loginInstant);
    }

    private SessionRecord toSessionRecord(SealedSessionPayload payload) {
        return SessionRecord.builder()
                .sessionId(derivedIdentity(payload))
                .accessToken(payload.accessToken())
                .refreshToken(payload.refreshToken())
                .idToken(payload.idToken())
                .sub(payload.sub())
                .sid(payload.sid())
                .expiresAt(payload.loginInstant().plus(codec.sessionTtl()))
                .acr(payload.acr())
                .authTime(payload.authTime())
                .build();
    }

    /**
     * Derives this binding's stable per-session identity: a salted digest over the sealed payload's
     * login instant and {@code sub}. Both inputs are fixed for the life of the session, so the
     * identity is stable across re-seals; the salt keeps it un-recomputable outside this gateway;
     * and it is never emitted to the browser.
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
        byte[] identity = new byte[IDENTITY_BYTES];
        System.arraycopy(digest.digest(), 0, identity, 0, IDENTITY_BYTES);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(identity);
    }
}
