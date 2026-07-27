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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


import de.cuioss.sheriff.gateway.bff.session.SessionBinding.BoundSession;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding.IdpDestruction;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CookieSessionBinding} — the stateless seam implementation. Covers the
 * bind/resolve/persist/destroy round trip, the server-side absolute-TTL enforcement (an expired
 * cookie the browser still holds is refused), the {@code Max-Age} reflecting the remaining rather
 * than the reset lifetime on a re-seal, the {@code previous_key} rotation (a retired-key cookie
 * resolves and its next write is sealed under the current key, while withdrawing the retired key
 * makes it no session), the stable-but-never-emitted session identity, and the
 * {@code UNSUPPORTED} IdP-driven destruction capability.
 */
class CookieSessionBindingTest {

    private static final String COOKIE_NAME = "__Host-sheriff-session";
    private static final Duration TTL = Duration.ofHours(8);
    private static final int BUDGET = SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET;
    private static final Instant LOGIN = Instant.parse("2026-07-27T10:00:00Z");
    private static final String ACCESS_TOKEN = "raw-access-token-SECRET-material";
    private static final String REFRESH_TOKEN = "raw-refresh-token-SECRET-material";
    private static final String ID_TOKEN = "raw-id-token-SECRET-material";
    private static final String SUB = "user-sub-1";
    private static final String SID = "idp-sid-9";
    private static final byte CURRENT_KEY_ID = 1;
    private static final byte PREVIOUS_KEY_ID = 7;

    private SealedSessionCookieCodec codec;
    private CookieSessionBinding binding;

    @BeforeEach
    void setUp() {
        codec = new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, aesKey((byte) 0x11), CURRENT_KEY_ID);
        binding = new CookieSessionBinding(codec, identitySalt());
    }

    private static SecretKey aesKey(byte fill) {
        byte[] material = new byte[32];
        Arrays.fill(material, fill);
        return new SecretKeySpec(material, "AES");
    }

    private static byte[] identitySalt() {
        byte[] salt = new byte[32];
        Arrays.fill(salt, (byte) 0x22);
        return salt;
    }

    /** Reads the key-id byte the emitted cookie value is stamped with (value layout: version, key-id, …). */
    private static byte keyIdOf(BoundSession bound) {
        String cookie = cookieHeaderOf(bound);
        String value = cookie.substring(cookie.indexOf('=') + 1);
        return Base64.getUrlDecoder().decode(value)[1];
    }

    private static SessionRecord session(String accessToken, Instant expiresAt) {
        return SessionRecord.builder()
                .sessionId("ignored-on-bind")
                .accessToken(accessToken)
                .refreshToken(Optional.of(REFRESH_TOKEN))
                .idToken(ID_TOKEN)
                .sub(SUB)
                .sid(Optional.of(SID))
                .expiresAt(expiresAt)
                .build();
    }

    private static String cookieHeaderOf(BoundSession bound) {
        String setCookie = bound.setCookieHeaders().getFirst();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    @Nested
    @DisplayName("Bind and resolve")
    class BindAndResolve {

        @Test
        @DisplayName("Should seal the session into a Set-Cookie and read it back intact")
        void shouldRoundTripThroughTheCookie() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);

            Optional<SessionRecord> resolved = binding.resolve(cookieHeaderOf(bound), LOGIN);

            assertTrue(resolved.isPresent());
            SessionRecord record = resolved.get();
            assertEquals(ACCESS_TOKEN, record.accessToken());
            assertEquals(Optional.of(REFRESH_TOKEN), record.refreshToken());
            assertEquals(ID_TOKEN, record.idToken());
            assertEquals(SUB, record.sub());
            assertEquals(Optional.of(SID), record.sid());
            assertEquals(LOGIN.plus(TTL), record.expiresAt(), "the deadline is derived from the sealed login instant");
        }

        @Test
        @DisplayName("Should emit exactly one hardened Set-Cookie carrying no token material")
        void shouldEmitOneHardenedCookie() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);

            assertEquals(1, bound.setCookieHeaders().size());
            String setCookie = bound.setCookieHeaders().getFirst();
            assertTrue(setCookie.startsWith(COOKIE_NAME + "="), setCookie);
            assertTrue(setCookie.contains("Secure"), setCookie);
            assertTrue(setCookie.contains("HttpOnly"), setCookie);
            assertTrue(setCookie.contains("SameSite=Lax"), setCookie);
            assertFalse(setCookie.contains(ACCESS_TOKEN), "token material is sealed, never emitted in the clear");
            assertFalse(setCookie.contains(REFRESH_TOKEN), "token material is sealed, never emitted in the clear");
        }

        @Test
        @DisplayName("Should resolve nothing without a session cookie or from a tampered one")
        void shouldResolveNothingWithoutAValidCookie() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            String tampered = cookieHeaderOf(bound).replace(COOKIE_NAME + "=", COOKIE_NAME + "=A");

            assertTrue(binding.resolve(null, LOGIN).isEmpty());
            assertTrue(binding.resolve("other=1", LOGIN).isEmpty());
            assertTrue(binding.resolve(tampered, LOGIN).isEmpty(), "a tampered cookie is no session, never an error");
        }
    }

    @Nested
    @DisplayName("Server-side absolute TTL")
    class AbsoluteTtl {

        @Test
        @DisplayName("Should refuse a cookie past its absolute deadline even though the browser still sent it")
        void shouldRefuseExpiredCookie() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            String cookieHeader = cookieHeaderOf(bound);

            assertTrue(binding.resolve(cookieHeader, LOGIN.plus(TTL).minusSeconds(1)).isPresent(),
                    "still live one second before the deadline");
            assertTrue(binding.resolve(cookieHeader, LOGIN.plus(TTL)).isEmpty(),
                    "the TTL is enforced server-side from the sealed login instant, not by the browser's Max-Age");
        }
    }

    @Nested
    @DisplayName("Persist (re-seal)")
    class Persist {

        @Test
        @DisplayName("Should re-seal the rotated material into a new cookie")
        void shouldResealRotatedMaterial() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();
            SessionRecord rotated = SessionRecord.builder()
                    .sessionId(resolved.sessionId())
                    .accessToken("rotated-access-token")
                    .refreshToken(Optional.of("rotated-refresh-token"))
                    .idToken(resolved.idToken())
                    .sub(resolved.sub())
                    .sid(resolved.sid())
                    .expiresAt(resolved.expiresAt())
                    .build();

            BoundSession reBound = binding.persist(rotated, LOGIN.plusSeconds(60));

            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60)).orElseThrow();
            assertEquals("rotated-access-token", reResolved.accessToken());
            assertEquals(Optional.of("rotated-refresh-token"), reResolved.refreshToken());
        }

        @Test
        @DisplayName("Should carry the remaining, not the reset, lifetime in Max-Age on a re-seal")
        void shouldNotExtendTheSessionOnReseal() {
            Instant halfway = LOGIN.plus(TTL.dividedBy(2));
            SessionRecord rotated = session("rotated-access-token", LOGIN.plus(TTL));

            BoundSession reBound = binding.persist(rotated, halfway);

            String setCookie = reBound.setCookieHeaders().getFirst();
            assertTrue(setCookie.contains("Max-Age=" + TTL.dividedBy(2).toSeconds()),
                    "a re-seal halfway through carries only the remaining lifetime: " + setCookie);
        }

        @Test
        @DisplayName("Should keep the absolute deadline anchored across a re-seal")
        void shouldKeepDeadlineAnchoredAcrossReseal() {
            Instant halfway = LOGIN.plus(TTL.dividedBy(2));
            BoundSession reBound = binding.persist(session("rotated-access-token", LOGIN.plus(TTL)), halfway);
            String cookieHeader = cookieHeaderOf(reBound);

            assertTrue(binding.resolve(cookieHeader, LOGIN.plus(TTL).minusSeconds(1)).isPresent());
            assertTrue(binding.resolve(cookieHeader, LOGIN.plus(TTL)).isEmpty(),
                    "the original deadline still applies after the re-seal");
        }
    }

    @Nested
    @DisplayName("previous_key rotation")
    class Rotation {

        private CookieSessionBinding retiredBinding;
        private CookieSessionBinding rotatingBinding;

        @BeforeEach
        void setUpRotation() {
            SecretKey previousKey = aesKey((byte) 0x33);
            retiredBinding = new CookieSessionBinding(
                    new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, previousKey, PREVIOUS_KEY_ID), identitySalt());
            rotatingBinding = new CookieSessionBinding(
                    new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, aesKey((byte) 0x11), CURRENT_KEY_ID,
                            previousKey, PREVIOUS_KEY_ID),
                    identitySalt());
        }

        @Test
        @DisplayName("Should resolve a previous-key cookie and re-seal its next write with the current key")
        void shouldResealOnNextWrite() {
            BoundSession sealedBeforeRotation = retiredBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            assertEquals(PREVIOUS_KEY_ID, keyIdOf(sealedBeforeRotation), "the old cookie carries the retired key id");

            SessionRecord resolved = rotatingBinding.resolve(cookieHeaderOf(sealedBeforeRotation), LOGIN).orElseThrow();
            BoundSession nextWrite = rotatingBinding.persist(resolved, LOGIN.plusSeconds(60));

            assertEquals(CURRENT_KEY_ID, keyIdOf(nextWrite),
                    "the session survives the rotation and its next write is sealed under the current key");
            assertEquals(ACCESS_TOKEN, rotatingBinding.resolve(cookieHeaderOf(nextWrite), LOGIN.plusSeconds(60))
                    .orElseThrow().accessToken(), "the re-sealed cookie still carries the session material");
        }

        @Test
        @DisplayName("Should keep the absolute deadline anchored across the rotation re-seal")
        void shouldNotExtendTheSessionOnRotation() {
            BoundSession sealedBeforeRotation = retiredBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            Instant halfway = LOGIN.plus(TTL.dividedBy(2));
            SessionRecord resolved = rotatingBinding.resolve(cookieHeaderOf(sealedBeforeRotation), halfway)
                    .orElseThrow();

            BoundSession reSealed = rotatingBinding.persist(resolved, halfway);

            assertTrue(rotatingBinding.resolve(cookieHeaderOf(reSealed), LOGIN.plus(TTL)).isEmpty(),
                    "rotating the key does not restart the absolute lifetime");
        }

        @Test
        @DisplayName("Should treat a previous-key cookie as no session once the previous key is withdrawn")
        void shouldRefusePreviousKeyCookieAfterWithdrawal() {
            BoundSession sealedBeforeRotation = retiredBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);

            assertTrue(binding.resolve(cookieHeaderOf(sealedBeforeRotation), LOGIN).isEmpty(),
                    "the current-key-only binding refuses the retired cookie as no session, never an error");
        }

        @Test
        @DisplayName("Should seal a fresh cookie-mode login under the current key while rotation is active")
        void shouldBindFreshLoginsUnderTheCurrentKey() {
            BoundSession fresh = rotatingBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);

            assertEquals(CURRENT_KEY_ID, keyIdOf(fresh), "the previous key is decrypt-only and never seals");
        }
    }

    @Nested
    @DisplayName("Session identity")
    class Identity {

        @Test
        @DisplayName("Should derive a stable identity across a re-seal, so single-flight coalescing keys the same")
        void shouldDeriveStableIdentity() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            SessionRecord first = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();

            BoundSession reBound = binding.persist(session("rotated-access-token", LOGIN.plus(TTL)),
                    LOGIN.plusSeconds(60));
            SessionRecord second = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60)).orElseThrow();

            assertEquals(first.sessionId(), second.sessionId(),
                    "the identity is derived from the login instant and sub, both fixed for the session's life");
        }

        @Test
        @DisplayName("Should never emit the derived identity to the browser")
        void shouldNeverEmitTheIdentity() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            String identity = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow().sessionId();

            assertFalse(bound.setCookieHeaders().getFirst().contains(identity),
                    "the derived identity is an in-instance key only — the browser sees only the sealed value");
        }

        @Test
        @DisplayName("Should derive different identities for different subjects")
        void shouldSeparateIdentitiesBySubject() {
            SessionRecord other = SessionRecord.builder()
                    .sessionId("ignored").accessToken(ACCESS_TOKEN).idToken(ID_TOKEN).sub("user-sub-2")
                    .expiresAt(LOGIN.plus(TTL)).build();

            String first = binding.resolve(cookieHeaderOf(binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN)),
                    LOGIN).orElseThrow().sessionId();
            String second = binding.resolve(cookieHeaderOf(binding.bind(other, LOGIN)), LOGIN)
                    .orElseThrow().sessionId();

            assertNotEquals(first, second);
        }
    }

    @Nested
    @DisplayName("Destruction")
    class Destruction {

        @Test
        @DisplayName("Should report UNSUPPORTED IdP-driven destruction — there is no index to scan")
        void shouldReportUnsupportedIdpDestruction() {
            assertEquals(IdpDestruction.UNSUPPORTED, binding.idpDestruction());
            assertEquals(0, binding.destroyBySid(SID), "a stateless binding destroys nothing by sid");
            assertEquals(0, binding.destroyBySub(SUB), "a stateless binding destroys nothing by sub");
        }

        @Test
        @DisplayName("Should clear the browser's copy through the clearing Set-Cookie")
        void shouldClearThroughTheCookie() {
            String clearing = binding.clearingSetCookieHeader();

            assertTrue(clearing.startsWith(COOKIE_NAME + "=;"), clearing);
            assertTrue(clearing.contains("Max-Age=0"), clearing);
        }

        @Test
        @DisplayName("Should leave destroy a local no-op — nothing is held server-side")
        void shouldTreatDestroyAsALocalNoOp() {
            SessionRecord record = session(ACCESS_TOKEN, LOGIN.plus(TTL));

            binding.destroy(record);

            assertEquals(codec.toClearingSetCookieHeader(), binding.clearingSetCookieHeader(),
                    "destruction is expressed through the clearing cookie the caller emits");
        }
    }
}
