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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import de.cuioss.sheriff.gateway.bff.session.SessionBinding.BoundSession;
import de.cuioss.sheriff.gateway.bff.session.SessionBinding.IdpDestruction;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link CookieSessionBinding} — the stateless seam implementation. Covers the
 * bind/resolve/persist/destroy round trip, the server-side absolute-TTL enforcement (an expired
 * cookie the browser still holds is refused), the {@code Max-Age} reflecting the remaining rather
 * than the reset lifetime on a re-seal, the re-seal emitting exactly one hardened {@code Set-Cookie}
 * distinct from the one {@code bind} emitted and carrying the login nonce verbatim, the active and
 * granted scope sets surviving bind and re-seal as independent fields, the single-key
 * change semantics (a cookie sealed under a
 * withdrawn key is no session, and every fresh login seals under the one active key id), the
 * stable-but-never-emitted session identity, the {@code UNSUPPORTED} IdP-driven destruction
 * capability, and the {@link SessionRecord} session-nonce contract (absent is the valid server-mode
 * shape; a present-but-blank value is refused because it would degrade the derived identity).
 * <p>
 * It further covers the idle timeout: the deadline is measured from the last access a valid activity
 * cookie of this very session proves and from the login instant otherwise, so an activity cookie that
 * is missing, tampered, bound to another session or sealed under another key never extends a session;
 * the access notification returns an activity cookie at most once per interval and never a session
 * cookie; an idle timeout below 60 seconds re-issues at half of itself, so activity keeps such a
 * session alive and its absence ends it; and the clearing list clears both cookies.
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
    private static final Set<String> ACTIVE_SCOPES = Set.of("openid", "profile", "email", "orders:read");
    private static final Set<String> GRANTED_SCOPES = Set.of("openid", "profile", "email", "orders:read",
            "orders:write");
    private static final byte CURRENT_KEY_ID = 1;

    /** The id a cookie sealed before a key change still carries — a generation the binding no longer holds. */
    private static final byte WITHDRAWN_KEY_ID = 7;

    private static final byte ACTIVITY_KEY_ID = 5;
    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);

    private SealedSessionCookieCodec codec;
    private SessionActivityCookieCodec activityCodec;
    /**
     * The binding the lifetime, re-seal, scope and identity cases run against. Its idle timeout equals
     * the absolute lifetime, so the absolute deadline is the only one in play there.
     */
    private CookieSessionBinding binding;
    /** The binding of the idle-timeout cases: same codecs and salt, idle timeout below the lifetime. */
    private CookieSessionBinding idleBinding;

    @BeforeEach
    void setUp() {
        codec = new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, aesKey((byte) 0x11), CURRENT_KEY_ID);
        activityCodec = new SessionActivityCookieCodec(COOKIE_NAME, aesKey((byte) 0x44), ACTIVITY_KEY_ID);
        binding = new CookieSessionBinding(codec, identitySalt(), activityCodec, TTL);
        idleBinding = new CookieSessionBinding(codec, identitySalt(), activityCodec, IDLE_TIMEOUT);
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
        return sealedHeaderOf(bound)[1];
    }

    /** Reads the format-version byte the emitted cookie value is stamped with. */
    private static byte formatVersionOf(BoundSession bound) {
        return sealedHeaderOf(bound)[0];
    }

    private static byte[] sealedHeaderOf(BoundSession bound) {
        String cookie = cookieHeaderOf(bound);
        String value = cookie.substring(cookie.indexOf('=') + 1);
        return Base64.getUrlDecoder().decode(value);
    }

    private static SessionRecord session(String accessToken, Instant expiresAt) {
        return SessionRecord.builder()
                .sessionId("ignored-on-bind")
                .accessToken(accessToken)
                .refreshToken(REFRESH_TOKEN)
                .idToken(ID_TOKEN)
                .sub(SUB)
                .sid(SID)
                .expiresAt(expiresAt)
                .activeScopes(ACTIVE_SCOPES)
                .grantedScopes(GRANTED_SCOPES)
                .build();
    }

    /**
     * Rebuilds {@code original} with a rotated access token, carrying every other component —
     * notably the session nonce — over verbatim. A re-seal test must carry the nonce forward this
     * way: {@code persist} refuses a record without one rather than re-minting it.
     */
    private static SessionRecord withRotatedAccessToken(SessionRecord original, String rotatedAccessToken) {
        return SessionRecord.builder()
                .sessionId(original.sessionId())
                .accessToken(rotatedAccessToken)
                .refreshToken(original.refreshToken())
                .idToken(original.idToken())
                .sub(original.sub())
                .sid(original.sid())
                .expiresAt(original.expiresAt())
                .acr(original.acr())
                .authTime(original.authTime())
                .sessionNonce(original.sessionNonce())
                .activeScopes(original.activeScopes())
                .grantedScopes(original.grantedScopes())
                .build();
    }

    /**
     * Rebuilds {@code original} with the given scope sets — the shape a refresh or a widening hands
     * to {@code persist}.
     */
    private static SessionRecord withScopes(SessionRecord original, Set<String> activeScopes,
            Set<String> grantedScopes) {
        return SessionRecord.builder()
                .sessionId(original.sessionId())
                .accessToken("rotated-access-token")
                .refreshToken(original.refreshToken())
                .idToken(original.idToken())
                .sub(original.sub())
                .sid(original.sid())
                .expiresAt(original.expiresAt())
                .acr(original.acr())
                .authTime(original.authTime())
                .sessionNonce(original.sessionNonce())
                .activeScopes(activeScopes)
                .grantedScopes(grantedScopes)
                .build();
    }

    /**
     * A record that is legal to re-seal: bound then resolved, so it carries the session nonce minted
     * at login.
     */
    private SessionRecord resealable(String rotatedAccessToken) {
        BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
        SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();
        return withRotatedAccessToken(resolved, rotatedAccessToken);
    }

    private static String cookieHeaderOf(BoundSession bound) {
        String setCookie = bound.setCookieHeaders().getFirst();
        return setCookie.substring(0, setCookie.indexOf(';'));
    }

    /**
     * Re-seals {@code updated} through {@code persist} and unwraps the result. The stateless binding
     * never reports a session gone, so an empty result is a defect of the binding and fails the test
     * that asked for the re-seal.
     */
    private BoundSession reseal(SessionRecord updated, Instant now) {
        return binding.persist(updated, now)
                .orElseThrow(() -> new AssertionError("the stateless binding must never report a session gone"));
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
            SessionRecord resolvedSession = resolved.get();
            assertEquals(ACCESS_TOKEN, resolvedSession.accessToken());
            assertEquals(REFRESH_TOKEN, resolvedSession.refreshToken());
            assertEquals(ID_TOKEN, resolvedSession.idToken());
            assertEquals(SUB, resolvedSession.sub());
            assertEquals(SID, resolvedSession.sid());
            assertEquals(LOGIN.plus(TTL), resolvedSession.expiresAt(),
                    "the deadline is derived from the sealed login instant");
        }

        @Test
        @DisplayName("Should stamp the emitted cookie with the current format version")
        void shouldStampTheCurrentFormatVersion() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);

            assertEquals(SealedSessionCookieCodec.FORMAT_VERSION, formatVersionOf(bound),
                    "the binding emits through the codec, so a format bump must reach the browser-facing "
                            + "value rather than stopping at the codec's own tests");
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
                    .refreshToken("rotated-refresh-token")
                    .idToken(resolved.idToken())
                    .sub(resolved.sub())
                    .sid(resolved.sid())
                    .expiresAt(resolved.expiresAt())
                    .sessionNonce(resolved.sessionNonce())
                    .build();

            BoundSession reBound = reseal(rotated, LOGIN.plusSeconds(60));

            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60)).orElseThrow();
            assertEquals("rotated-access-token", reResolved.accessToken());
            assertEquals("rotated-refresh-token", reResolved.refreshToken());
        }

        @Test
        @DisplayName("Should emit exactly one hardened Set-Cookie, distinct from the one bind emitted")
        void shouldEmitOneHardenedCookieDistinctFromBind() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();

            BoundSession reBound = reseal(withRotatedAccessToken(resolved, "rotated-access-token"),
                    LOGIN.plusSeconds(60));

            // In this mode the cookie IS the session, so the re-seal is the ONLY place the rotated
            // material can be kept — and it must arrive with the same hardening a login's cookie
            // carries, since it replaces that cookie in the browser wholesale.
            assertEquals(1, reBound.setCookieHeaders().size(),
                    "a re-seal replaces the session cookie, so it emits exactly one Set-Cookie");
            String setCookie = reBound.setCookieHeaders().getFirst();
            assertTrue(setCookie.startsWith(COOKIE_NAME + "="), setCookie);
            assertTrue(setCookie.contains("Secure"), setCookie);
            assertTrue(setCookie.contains("HttpOnly"), setCookie);
            assertTrue(setCookie.contains("SameSite=Lax"), setCookie);
            assertFalse(setCookie.contains("rotated-access-token"),
                    "rotated material is sealed, never emitted in the clear");
            assertNotEquals(cookieHeaderOf(bound), cookieHeaderOf(reBound),
                    "a re-seal reproducing bind's value byte for byte would leave the browser holding "
                            + "the pre-rotation tokens while every other assertion still passed");
        }

        @Test
        @DisplayName("Should re-seal the original session nonce verbatim rather than mint a new one")
        void shouldResealTheOriginalNonceVerbatim() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();
            String nonceAtLogin = resolved.sessionNonce();
            assertNotNull(nonceAtLogin, "bind mints the session's one nonce");

            BoundSession reBound = reseal(withRotatedAccessToken(resolved, "rotated-access-token"),
                    LOGIN.plusSeconds(60));

            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60))
                    .orElseThrow();
            // Asserted on the NONCE itself rather than only on the derived identity: the identity is a
            // digest over three inputs, so an identity assertion alone could be satisfied by a
            // compensating change elsewhere, and the nonce is the input a re-mint would move.
            assertEquals(nonceAtLogin, reResolved.sessionNonce(),
                    "the nonce is an identity input fixed at login; re-minting it on a re-seal would "
                            + "change the session identity mid-flight and break single-flight coalescing");
        }

        @Test
        @DisplayName("Should carry the remaining, not the reset, lifetime in Max-Age on a re-seal")
        void shouldNotExtendTheSessionOnReseal() {
            Instant halfway = LOGIN.plus(TTL.dividedBy(2));
            SessionRecord rotated = resealable("rotated-access-token");

            BoundSession reBound = reseal(rotated, halfway);

            String setCookie = reBound.setCookieHeaders().getFirst();
            assertTrue(setCookie.contains("Max-Age=" + TTL.dividedBy(2).toSeconds()),
                    "a re-seal halfway through carries only the remaining lifetime: " + setCookie);
        }

        @Test
        @DisplayName("Should keep the absolute deadline anchored across a re-seal")
        void shouldKeepDeadlineAnchoredAcrossReseal() {
            Instant halfway = LOGIN.plus(TTL.dividedBy(2));
            BoundSession reBound = reseal(resealable("rotated-access-token"), halfway);
            String cookieHeader = cookieHeaderOf(reBound);

            assertTrue(binding.resolve(cookieHeader, LOGIN.plus(TTL).minusSeconds(1)).isPresent());
            assertTrue(binding.resolve(cookieHeader, LOGIN.plus(TTL)).isEmpty(),
                    "the original deadline still applies after the re-seal");
        }

        @Test
        @DisplayName("Should always return the re-sealed session — the update never reports the session gone")
        void shouldNeverReportTheSessionGone() {
            Optional<BoundSession> persisted = binding.persist(resealable("rotated-access-token"),
                    LOGIN.plusSeconds(60));

            assertTrue(persisted.isPresent(), "a stateless binding has nothing to find the session gone in");
        }

        /**
         * The stateless limit, pinned rather than hidden: {@code destroy} and the two IdP-driven forms
         * remove nothing here, so an update made after them still re-seals a cookie that resolves. In
         * server mode the same sequence reports the session gone.
         */
        @ParameterizedTest(name = "persist after {0} still re-seals")
        @ValueSource(strings = {"destroy", "destroyBySid", "destroyBySub"})
        @DisplayName("Should re-seal as before after a destruction, because a stateless binding cannot observe one")
        void shouldResealAfterDestruction(String destruction) {
            SessionRecord updated = resealable("rotated-access-token");
            switch (destruction) {
                case "destroy" -> binding.destroy(updated);
                case "destroyBySid" -> binding.destroyBySid(SID);
                default -> binding.destroyBySub(SUB);
            }

            Optional<BoundSession> persisted = binding.persist(updated, LOGIN.plusSeconds(60));

            BoundSession reBound = persisted
                    .orElseThrow(() -> new AssertionError("persist after " + destruction + " must still re-seal"));
            assertEquals(1, reBound.setCookieHeaders().size(), "the re-seal still emits its one Set-Cookie");
            assertEquals("rotated-access-token",
                    binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60)).orElseThrow().accessToken(),
                    "the re-sealed cookie resolves, carrying the updated material");
        }
    }

    @Nested
    @DisplayName("Active scope set")
    class ActiveScopeSet {

        @Test
        @DisplayName("Should carry the active scope set through bind and resolve")
        void shouldSurviveBindAndResolve() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);

            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();

            assertEquals(ACTIVE_SCOPES, bound.session().activeScopes(), "the bound record carries A");
            assertEquals(ACTIVE_SCOPES, resolved.activeScopes(),
                    "A is sealed into the cookie, so a stateless gateway recovers it on the next request");
        }

        @Test
        @DisplayName("Should resolve an empty active scope set as empty")
        void shouldSurviveEmptyScopes() {
            SessionRecord unscoped = SessionRecord.builder()
                    .sessionId("ignored-on-bind").accessToken(ACCESS_TOKEN).idToken(ID_TOKEN).sub(SUB)
                    .expiresAt(LOGIN.plus(TTL)).build();

            BoundSession bound = binding.bind(unscoped, LOGIN);

            assertTrue(binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow().activeScopes().isEmpty());
        }

        @Test
        @DisplayName("Should re-seal a changed active scope set and keep the session identity")
        void shouldResealChangedScopes() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();
            Set<String> narrowed = Set.of("openid", "profile");
            SessionRecord rotated = SessionRecord.builder()
                    .sessionId(resolved.sessionId())
                    .accessToken("rotated-access-token")
                    .refreshToken(resolved.refreshToken())
                    .idToken(resolved.idToken())
                    .sub(resolved.sub())
                    .sid(resolved.sid())
                    .expiresAt(resolved.expiresAt())
                    .sessionNonce(resolved.sessionNonce())
                    .activeScopes(narrowed)
                    .build();

            BoundSession reBound = reseal(rotated, LOGIN.plusSeconds(60));
            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60))
                    .orElseThrow();

            assertEquals(narrowed, reResolved.activeScopes(),
                    "a refresh that changes A must reach the next request, so the re-seal carries it");
            assertEquals(resolved.sessionId(), reResolved.sessionId(),
                    "A is not an identity input, so changing it leaves single-flight keying intact");
        }

        @Test
        @DisplayName("Should carry an unchanged active scope set across a re-seal")
        void shouldResealUnchangedScopes() {
            BoundSession reBound = reseal(resealable("rotated-access-token"), LOGIN.plusSeconds(60));

            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60))
                    .orElseThrow();

            assertEquals(ACTIVE_SCOPES, reResolved.activeScopes());
        }
    }

    @Nested
    @DisplayName("Granted scope set")
    class GrantedScopeSet {

        @Test
        @DisplayName("Should carry the granted scope set through bind and resolve")
        void shouldSurviveBindAndResolve() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);

            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();

            assertEquals(GRANTED_SCOPES, bound.session().grantedScopes(), "the bound record carries S");
            assertEquals(GRANTED_SCOPES, resolved.grantedScopes(),
                    "S is sealed into the cookie, so a stateless gateway recovers it on the next request");
        }

        @Test
        @DisplayName("Should carry the granted scope set through persist and resolve")
        void shouldSurvivePersistAndResolve() {
            BoundSession reBound = reseal(resealable("rotated-access-token"), LOGIN.plusSeconds(60));

            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60))
                    .orElseThrow();

            assertEquals(GRANTED_SCOPES, reResolved.grantedScopes(), "a re-seal carries S forward unchanged");
        }

        @Test
        @DisplayName("Should keep S when a re-seal narrows A, since the two are independent fields")
        void shouldKeepGrantedScopesWhenActiveNarrows() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();
            Set<String> narrowed = Set.of("openid");

            BoundSession reBound = reseal(withScopes(resolved, narrowed, resolved.grantedScopes()),
                    LOGIN.plusSeconds(60));
            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60))
                    .orElseThrow();

            assertEquals(narrowed, reResolved.activeScopes(), "A follows the refresh");
            assertEquals(GRANTED_SCOPES, reResolved.grantedScopes(), "S is not derived from A and stays as granted");
        }

        @Test
        @DisplayName("Should keep A when a re-seal widens S, and keep the session identity")
        void shouldKeepActiveScopesWhenGrantedWidens() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();
            Set<String> widened = Set.of("openid", "profile", "email", "orders:read", "orders:write",
                    "billing:read");

            BoundSession reBound = reseal(withScopes(resolved, resolved.activeScopes(), widened),
                    LOGIN.plusSeconds(60));
            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60))
                    .orElseThrow();

            assertEquals(ACTIVE_SCOPES, reResolved.activeScopes(), "A is not derived from S");
            assertEquals(widened, reResolved.grantedScopes(), "a widened S reaches the next request");
            assertEquals(resolved.sessionId(), reResolved.sessionId(),
                    "S is not an identity input, so widening it leaves single-flight keying intact");
        }

        @Test
        @DisplayName("Should resolve an absent granted scope set as empty")
        void shouldSurviveEmptyGrantedScopes() {
            SessionRecord ungranted = SessionRecord.builder()
                    .sessionId("ignored-on-bind").accessToken(ACCESS_TOKEN).idToken(ID_TOKEN).sub(SUB)
                    .expiresAt(LOGIN.plus(TTL)).activeScopes(ACTIVE_SCOPES).build();

            BoundSession bound = binding.bind(ungranted, LOGIN);
            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();

            assertTrue(resolved.grantedScopes().isEmpty());
            assertEquals(ACTIVE_SCOPES, resolved.activeScopes());
        }
    }

    @Nested
    @DisplayName("Key change")
    class KeyChange {

        @Test
        @DisplayName("Should treat a cookie sealed under a withdrawn key as no session, never an error")
        void shouldRefuseCookieSealedUnderWithdrawnKey() {
            CookieSessionBinding beforeTheKeyChange = new CookieSessionBinding(
                    new SealedSessionCookieCodec(COOKIE_NAME, TTL, BUDGET, aesKey((byte) 0x33), WITHDRAWN_KEY_ID),
                    identitySalt(), activityCodec, TTL);
            BoundSession sealedBeforeTheKeyChange =
                    beforeTheKeyChange.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            assertEquals(WITHDRAWN_KEY_ID, keyIdOf(sealedBeforeTheKeyChange),
                    "the outstanding cookie still carries the withdrawn generation's id");

            assertTrue(binding.resolve(cookieHeaderOf(sealedBeforeTheKeyChange), LOGIN).isEmpty(),
                    "there is no decrypt-only companion key to fall back on, so the binding refuses the "
                            + "outstanding cookie as no session and its owner re-authenticates");
        }

        @Test
        @DisplayName("Should seal every fresh login under the one active key id")
        void shouldBindFreshLoginsUnderTheActiveKey() {
            BoundSession fresh = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);

            assertEquals(CURRENT_KEY_ID, keyIdOf(fresh), "the binding holds exactly one sealing key");
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

            SessionRecord rotated = withRotatedAccessToken(first, "rotated-access-token");
            BoundSession reBound = reseal(rotated, LOGIN.plusSeconds(60));
            SessionRecord second = binding.resolve(cookieHeaderOf(reBound), LOGIN.plusSeconds(60)).orElseThrow();

            assertEquals(first.sessionId(), second.sessionId(),
                    "the identity is derived from the login instant, sub and session nonce — all three fixed "
                            + "for the session's life, so a re-seal reproduces it byte-identically");
        }

        @Test
        @DisplayName("Should derive distinct identities for two logins by the same subject in one clock second")
        void shouldSeparateIdentitiesForSameSubjectWithinOneSecond() {
            // Same subject, same login instant — before the per-session nonce these two collided,
            // cross-wiring the refresh coordinator's single-flight keying between distinct sessions.
            String first = binding.resolve(cookieHeaderOf(binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN)),
                    LOGIN).orElseThrow().sessionId();
            String second = binding.resolve(cookieHeaderOf(binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN)),
                    LOGIN).orElseThrow().sessionId();

            assertNotEquals(first, second,
                    "two logins by the same sub inside one clock second must not share a session identity");
        }

        @Test
        @DisplayName("Should refuse to re-seal a record carrying no session nonce rather than re-mint one")
        void shouldRefuseReSealWithoutNonce() {
            // A record reconstructed without the nonce would otherwise silently acquire a new identity.
            SessionRecord nonceless = session(ACCESS_TOKEN, LOGIN.plus(TTL));

            assertThrows(IllegalStateException.class, () -> binding.persist(nonceless, LOGIN),
                    "re-minting here would change the derived identity mid-session");
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
    @DisplayName("Session-record nonce contract")
    class SessionRecordNonceContract {

        private static SessionRecord.SessionRecordBuilder baseRecord() {
            return SessionRecord.builder()
                    .sessionId("ignored-on-bind")
                    .accessToken(ACCESS_TOKEN)
                    .idToken(ID_TOKEN)
                    .sub(SUB)
                    .expiresAt(LOGIN.plus(TTL));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", " ", "\t", "\n", "   "})
        @DisplayName("Should reject a present-but-blank session nonce")
        void shouldRejectPresentButBlankNonce(String blank) {
            SessionRecord.SessionRecordBuilder builder = baseRecord().sessionNonce(blank);

            assertThrows(IllegalArgumentException.class, builder::build,
                    "a blank nonce would silently degrade the derived identity to the colliding pre-nonce shape");
        }

        @Test
        @DisplayName("Should keep the rejected nonce out of the exception message")
        void shouldNotLeakNonceIntoRejectionMessage() {
            SessionRecord.SessionRecordBuilder builder = baseRecord().sessionNonce("   ");

            IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class, builder::build);

            assertEquals("sessionNonce must not be blank when present", rejection.getMessage());
        }

        @Test
        @DisplayName("Should accept an absent session nonce — that is the server-mode shape")
        void shouldAcceptAbsentNonce() {
            SessionRecord serverMode = baseRecord().build();

            assertNull(serverMode.sessionNonce(),
                    "server-mode records carry no nonce and must stay constructible");
        }

        @Test
        @DisplayName("Should accept an explicitly-null session nonce rather than reject it")
        void shouldAcceptExplicitlyNullNonce() {
            SessionRecord serverMode = baseRecord().sessionNonce(null).build();

            assertNull(serverMode.sessionNonce());
        }

        @Test
        @DisplayName("Should accept a non-blank session nonce")
        void shouldAcceptNonBlankNonce() {
            SessionRecord cookieMode = baseRecord().sessionNonce("session-nonce-material").build();

            assertEquals("session-nonce-material", cookieMode.sessionNonce());
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
            String clearing = binding.clearingSetCookieHeaders().getFirst();

            assertTrue(clearing.startsWith(COOKIE_NAME + "=;"), clearing);
            assertTrue(clearing.contains("Max-Age=0"), clearing);
        }

        @Test
        @DisplayName("Should clear both cookies the binding sets: the session cookie and its activity cookie")
        void shouldClearBothCookies() {
            List<String> clearing = binding.clearingSetCookieHeaders();

            assertEquals(List.of(codec.toClearingSetCookieHeader(), activityCodec.toClearingSetCookieHeader()),
                    clearing, "one clearing header per cookie the binding sets, the session cookie first");
            assertTrue(clearing.get(1).startsWith(COOKIE_NAME + "-activity=;"), clearing.get(1));
            assertTrue(clearing.get(1).contains("Max-Age=0"), clearing.get(1));
        }

        @Test
        @DisplayName("Should leave destroy a local no-op — nothing is held server-side")
        void shouldTreatDestroyAsALocalNoOp() {
            SessionRecord liveSession = session(ACCESS_TOKEN, LOGIN.plus(TTL));

            binding.destroy(liveSession);

            assertEquals(codec.toClearingSetCookieHeader(), binding.clearingSetCookieHeaders().getFirst(),
                    "destruction is expressed through the clearing cookie the caller emits");
        }
    }

    /** The request {@code Cookie} header carrying a session cookie and an activity cookie value. */
    private String withActivity(String sessionCookieHeader, String signedActivity) {
        return sessionCookieHeader + "; " + activityCodec.cookieName() + "=" + signedActivity;
    }

    @Nested
    @DisplayName("Re-issuing write (step-up and scope widening)")
    class ReissuingWrite {

        @Test
        @DisplayName("Should re-seal the widened material into one session cookie, keeping identity and nonce")
        void shouldKeepIdentityAndNonce() {
            BoundSession bound = binding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN);
            SessionRecord resolved = binding.resolve(cookieHeaderOf(bound), LOGIN).orElseThrow();
            Instant later = LOGIN.plusSeconds(60);

            BoundSession reissued = binding
                    .persistReissuingCookie(withRotatedAccessToken(resolved, "widened-access-token"), later)
                    .orElseThrow(() -> new AssertionError("the stateless binding never reports a session gone"));

            assertEquals(1, reissued.setCookieHeaders().size(), "the re-issue emits the session cookie alone");
            assertTrue(reissued.setCookieHeaders().getFirst().startsWith(COOKIE_NAME + "="));
            assertNotEquals(cookieHeaderOf(bound), cookieHeaderOf(reissued), "the browser is handed a new value");
            SessionRecord reResolved = binding.resolve(cookieHeaderOf(reissued), later).orElseThrow();
            assertEquals("widened-access-token", reResolved.accessToken());
            assertEquals(resolved.sessionId(), reResolved.sessionId(), "the derived identity is unchanged");
            assertEquals(resolved.sessionNonce(), reResolved.sessionNonce(),
                    "the nonce is carried verbatim: none is minted outside bind");
            assertEquals(resolved.expiresAt(), reResolved.expiresAt(), "the absolute expiry is unchanged");
        }

        @Test
        @DisplayName("Should refuse to re-issue a record carrying no session nonce rather than mint one")
        void shouldRefuseReissueWithoutNonce() {
            SessionRecord nonceless = session(ACCESS_TOKEN, LOGIN.plus(TTL));

            assertThrows(IllegalStateException.class, () -> binding.persistReissuingCookie(nonceless, LOGIN));
        }
    }

    @Nested
    @DisplayName("Idle deadline against the activity cookie")
    class IdleDeadline {

        private String sessionCookie;
        private SessionRecord resolvedAtLogin;

        @BeforeEach
        void bindSession() {
            sessionCookie = cookieHeaderOf(idleBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN));
            resolvedAtLogin = idleBinding.resolve(sessionCookie, LOGIN).orElseThrow();
        }

        @Test
        @DisplayName("Should measure idleness from the login instant when the request carries no activity cookie")
        void shouldMeasureFromLoginWithoutActivityCookie() {
            Instant idleDeadline = LOGIN.plus(IDLE_TIMEOUT);

            assertTrue(idleBinding.resolve(sessionCookie, idleDeadline.minusSeconds(1)).isPresent(),
                    "live up to one idle timeout after the login");
            assertTrue(idleBinding.resolve(sessionCookie, idleDeadline).isEmpty(),
                    "a missing activity cookie never extends the session: it ends one idle timeout after login");
        }

        @Test
        @DisplayName("Should measure idleness from the last access a valid activity cookie proves")
        void shouldMeasureFromActivityCookie() {
            Instant lastAccess = LOGIN.plus(Duration.ofMinutes(20));
            String cookies = withActivity(sessionCookie, activityCodec.sign(resolvedAtLogin.sessionId(), lastAccess));

            assertTrue(idleBinding.resolve(cookies, LOGIN.plus(IDLE_TIMEOUT)).isPresent(),
                    "the activity cookie moved the deadline past the one measured from login");
            assertTrue(idleBinding.resolve(cookies, lastAccess.plus(IDLE_TIMEOUT).minusSeconds(1)).isPresent());
            assertTrue(idleBinding.resolve(cookies, lastAccess.plus(IDLE_TIMEOUT)).isEmpty(),
                    "the session ends one idle timeout after the proven access, inclusive of the boundary");
        }

        @Test
        @DisplayName("Should ignore a tampered activity cookie and measure idleness from the login instant")
        void shouldIgnoreTamperedActivityCookie() {
            String signed = activityCodec.sign(resolvedAtLogin.sessionId(), LOGIN.plus(Duration.ofMinutes(20)));
            char last = signed.charAt(signed.length() - 1);
            String tampered = signed.substring(0, signed.length() - 1) + (last == 'A' ? 'B' : 'A');

            assertIdlenessMeasuredFromLogin(withActivity(sessionCookie, tampered));
        }

        @Test
        @DisplayName("Should ignore the activity cookie of another session and measure idleness from the login instant")
        void shouldIgnoreActivityCookieOfAnotherSession() {
            String otherCookie = cookieHeaderOf(idleBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN));
            String otherIdentity = idleBinding.resolve(otherCookie, LOGIN).orElseThrow().sessionId();
            assertNotEquals(resolvedAtLogin.sessionId(), otherIdentity, "two logins are two sessions");
            String foreign = activityCodec.sign(otherIdentity, LOGIN.plus(Duration.ofMinutes(20)));

            assertIdlenessMeasuredFromLogin(withActivity(sessionCookie, foreign));
        }

        @Test
        @DisplayName("Should ignore an activity cookie signed under another key and measure idleness from the login instant")
        void shouldIgnoreActivityCookieOfAnotherKey() {
            SessionActivityCookieCodec foreignKey =
                    new SessionActivityCookieCodec(COOKIE_NAME, aesKey((byte) 0x55), ACTIVITY_KEY_ID);
            String foreign = foreignKey.sign(resolvedAtLogin.sessionId(), LOGIN.plus(Duration.ofMinutes(20)));

            assertIdlenessMeasuredFromLogin(withActivity(sessionCookie, foreign));
        }

        @Test
        @DisplayName("Should ignore the sealed session cookie value presented as the activity cookie")
        void shouldIgnoreSessionCookieValueAsActivityCookie() {
            String sessionValue = sessionCookie.substring(sessionCookie.indexOf('=') + 1);

            assertIdlenessMeasuredFromLogin(withActivity(sessionCookie, sessionValue));
        }

        @Test
        @DisplayName("Should not accept a last access before the login instant")
        void shouldNotAcceptAccessBeforeLogin() {
            String beforeLogin = activityCodec.sign(resolvedAtLogin.sessionId(), LOGIN.minus(Duration.ofHours(1)));

            assertIdlenessMeasuredFromLogin(withActivity(sessionCookie, beforeLogin));
        }

        @Test
        @DisplayName("Should never extend the absolute deadline through an activity cookie")
        void shouldNotExtendAbsoluteDeadline() {
            Instant absoluteDeadline = LOGIN.plus(TTL);
            String cookies = withActivity(sessionCookie,
                    activityCodec.sign(resolvedAtLogin.sessionId(), absoluteDeadline.minusSeconds(60)));

            assertTrue(idleBinding.resolve(cookies, absoluteDeadline.minusSeconds(1)).isPresent(),
                    "the recent access keeps the session live up to its absolute deadline");
            assertTrue(idleBinding.resolve(cookies, absoluteDeadline).isEmpty(),
                    "the session ends at the absolute deadline however recently it was accessed");
        }

        @Test
        @DisplayName("Should not move the idle deadline by resolving")
        void shouldNotExtendOnResolve() {
            assertTrue(idleBinding.resolve(sessionCookie, LOGIN.plus(IDLE_TIMEOUT).minusSeconds(1)).isPresent());

            assertTrue(idleBinding.resolve(sessionCookie, LOGIN.plus(IDLE_TIMEOUT)).isEmpty(),
                    "the resolve a second before the deadline was not an access");
        }

        @Test
        @DisplayName("Should reject a non-positive idle timeout")
        void shouldRejectNonPositiveIdleTimeout() {
            byte[] salt = identitySalt();
            Duration negative = Duration.ofSeconds(-1);

            assertThrows(IllegalArgumentException.class,
                    () -> new CookieSessionBinding(codec, salt, activityCodec, Duration.ZERO));
            assertThrows(IllegalArgumentException.class,
                    () -> new CookieSessionBinding(codec, salt, activityCodec, negative));
        }

        /**
         * The fail-closed fallback: the activity cookie in {@code cookies} proves nothing, so the session
         * is live up to one idle timeout after the login and gone at it — exactly as without the cookie.
         */
        private void assertIdlenessMeasuredFromLogin(String cookies) {
            Instant idleDeadline = LOGIN.plus(IDLE_TIMEOUT);
            assertTrue(idleBinding.resolve(cookies, idleDeadline.minusSeconds(1)).isPresent(),
                    "an activity cookie that proves nothing does not end the session early either");
            assertTrue(idleBinding.resolve(cookies, idleDeadline).isEmpty(),
                    "an activity cookie that proves nothing never extends the session");
        }
    }

    @Nested
    @DisplayName("Access notification")
    class AccessNotification {

        private String sessionCookie;
        private SessionRecord resolvedAtLogin;

        @BeforeEach
        void bindSession() {
            sessionCookie = cookieHeaderOf(idleBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN));
            resolvedAtLogin = idleBinding.resolve(sessionCookie, LOGIN).orElseThrow();
        }

        @Test
        @DisplayName("Should return nothing while the last access is younger than 60 seconds")
        void shouldReturnNothingBeforeTheInterval() {
            List<String> cookies = idleBinding.recordAccess(resolvedAtLogin, sessionCookie, LOGIN.plusSeconds(59));

            assertTrue(cookies.isEmpty(), "most responses carry no Set-Cookie for the access");
        }

        @Test
        @DisplayName("Should return one activity cookie, and never a session cookie, once the last access is 60 seconds old")
        void shouldReturnActivityCookieAtTheInterval() {
            Instant access = LOGIN.plus(CookieSessionBinding.ACTIVITY_COOKIE_INTERVAL);

            List<String> cookies = idleBinding.recordAccess(resolvedAtLogin, sessionCookie, access);

            assertEquals(1, cookies.size(), "exactly one cookie is set for the access");
            String setCookie = cookies.getFirst();
            assertTrue(setCookie.startsWith(COOKIE_NAME + "-activity="), setCookie);
            assertFalse(setCookie.startsWith(COOKIE_NAME + "="), "the session cookie is never rewritten on access");
            assertFalse(setCookie.contains(ACCESS_TOKEN), "the activity cookie carries no token material");
            assertTrue(setCookie.contains("; Max-Age=" + TTL.minusSeconds(60).toSeconds() + ";"),
                    "its Max-Age is the session's remaining absolute lifetime: " + setCookie);
            assertTrue(setCookie.endsWith("; Path=/; Secure; HttpOnly; SameSite=Lax"), setCookie);
        }

        @Test
        @DisplayName("Should extend the session through the activity cookie it returned")
        void shouldExtendThroughTheReturnedCookie() {
            Instant access = LOGIN.plus(Duration.ofMinutes(20));
            String setCookie = idleBinding.recordAccess(resolvedAtLogin, sessionCookie, access).getFirst();
            String cookies = sessionCookie + "; " + setCookie.substring(0, setCookie.indexOf(';'));

            assertTrue(idleBinding.resolve(cookies, LOGIN.plus(IDLE_TIMEOUT)).isPresent(),
                    "the returned cookie is bound to this session and carries the access");
            assertTrue(idleBinding.resolve(cookies, access.plus(IDLE_TIMEOUT)).isEmpty());
        }

        @Test
        @DisplayName("Should measure the interval from the access a valid activity cookie proves")
        void shouldMeasureIntervalFromActivityCookie() {
            Instant lastAccess = LOGIN.plus(Duration.ofMinutes(10));
            String cookies = withActivity(sessionCookie, activityCodec.sign(resolvedAtLogin.sessionId(), lastAccess));

            assertTrue(idleBinding.recordAccess(resolvedAtLogin, cookies, lastAccess.plusSeconds(59)).isEmpty(),
                    "an activity cookie younger than the interval is not replaced");
            assertEquals(1, idleBinding.recordAccess(resolvedAtLogin, cookies, lastAccess.plusSeconds(60)).size());
        }

        @Test
        @DisplayName("Should not let the activity cookie of another session suppress this session's own")
        void shouldNotCountForeignActivityCookie() {
            String otherCookie = cookieHeaderOf(idleBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN));
            String otherIdentity = idleBinding.resolve(otherCookie, LOGIN).orElseThrow().sessionId();
            Instant now = LOGIN.plus(Duration.ofMinutes(10));
            String cookies = withActivity(sessionCookie, activityCodec.sign(otherIdentity, now));

            List<String> returned = idleBinding.recordAccess(resolvedAtLogin, cookies, now);

            assertEquals(1, returned.size(),
                    "the foreign cookie proves no access of this session, so a new activity cookie is issued");
        }
    }

    @Nested
    @DisplayName("Idle timeout shorter than the 60-second re-issue ceiling")
    class ShortIdleTimeout {

        /** Below {@link CookieSessionBinding#ACTIVITY_COOKIE_INTERVAL}, so the re-issue interval is half of it. */
        private static final Duration SHORT_IDLE_TIMEOUT = Duration.ofSeconds(30);

        private CookieSessionBinding shortIdleBinding;
        private String sessionCookie;

        @BeforeEach
        void bindSession() {
            shortIdleBinding = new CookieSessionBinding(codec, identitySalt(), activityCodec, SHORT_IDLE_TIMEOUT);
            sessionCookie = cookieHeaderOf(shortIdleBinding.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN));
        }

        /**
         * One request of a browser at {@code now}: the session must resolve from {@code cookies}, the
         * access is recorded, and the browser keeps an activity cookie the response set.
         *
         * @return the request {@code Cookie} header the browser sends next
         */
        private String access(String cookies, Instant now) {
            SessionRecord live = shortIdleBinding.resolve(cookies, now)
                    .orElseThrow(() -> new AssertionError("the session must still be live at " + now));
            List<String> setCookies = shortIdleBinding.recordAccess(live, cookies, now);
            if (setCookies.isEmpty()) {
                return cookies;
            }
            String setCookie = setCookies.getFirst();
            return sessionCookie + "; " + setCookie.substring(0, setCookie.indexOf(';'));
        }

        @Test
        @DisplayName("Should keep a session alive past the idle timeout while it is used more often than the timeout")
        void shouldStayAliveWhileUsed() {
            String cookies = sessionCookie;

            for (int seconds = 10; seconds <= 120; seconds += 10) {
                cookies = access(cookies, LOGIN.plusSeconds(seconds));
            }

            assertTrue(shortIdleBinding.resolve(cookies, LOGIN.plusSeconds(125)).isPresent(),
                    "used every 10 seconds, the session outlives four idle timeouts of 30 seconds");
        }

        @Test
        @DisplayName("Should end a session one idle timeout after its last recorded access when it is not used")
        void shouldEndWhenNotUsed() {
            Instant lastAccess = LOGIN.plusSeconds(20);
            String cookies = access(sessionCookie, lastAccess);

            assertTrue(shortIdleBinding.resolve(sessionCookie, LOGIN.plus(SHORT_IDLE_TIMEOUT)).isEmpty(),
                    "never used, the session ends one idle timeout after the login");
            assertTrue(shortIdleBinding.resolve(cookies, lastAccess.plus(SHORT_IDLE_TIMEOUT).minusSeconds(1)).isPresent(),
                    "the recorded access moved the deadline past the one measured from the login");
            assertTrue(shortIdleBinding.resolve(cookies, lastAccess.plus(SHORT_IDLE_TIMEOUT)).isEmpty(),
                    "left alone, the session ends one idle timeout after the access");
        }

        @Test
        @DisplayName("Should re-issue the activity cookie at half the idle timeout, not at the 60-second ceiling")
        void shouldReissueAtHalfTheIdleTimeout() {
            SessionRecord live = shortIdleBinding.resolve(sessionCookie, LOGIN).orElseThrow();

            assertTrue(shortIdleBinding.recordAccess(live, sessionCookie, LOGIN.plusSeconds(14)).isEmpty(),
                    "an access inside half the idle timeout writes no cookie");
            assertEquals(1, shortIdleBinding.recordAccess(live, sessionCookie, LOGIN.plusSeconds(15)).size(),
                    "an access half an idle timeout after the last one is recorded, before the deadline it moves");
        }

        @Test
        @DisplayName("Should make the shortest accepted idle timeout of one second keepable by activity")
        void shouldKeepAOneSecondIdleTimeoutAlive() {
            CookieSessionBinding oneSecond =
                    new CookieSessionBinding(codec, identitySalt(), activityCodec, Duration.ofSeconds(1));
            String cookie = cookieHeaderOf(oneSecond.bind(session(ACCESS_TOKEN, LOGIN.plus(TTL)), LOGIN));
            Instant access = LOGIN.plusMillis(500);
            SessionRecord live = oneSecond.resolve(cookie, access).orElseThrow();

            String setCookie = oneSecond.recordAccess(live, cookie, access).getFirst();
            String cookies = cookie + "; " + setCookie.substring(0, setCookie.indexOf(';'));

            assertTrue(oneSecond.resolve(cookie, LOGIN.plusSeconds(1)).isEmpty(), "without the access it ends");
            assertTrue(oneSecond.resolve(cookies, LOGIN.plusMillis(1400)).isPresent(),
                    "the access half a second in is recorded and carries the session past the login's deadline");
            assertTrue(oneSecond.resolve(cookies, LOGIN.plusMillis(1500)).isEmpty());
        }
    }
}
