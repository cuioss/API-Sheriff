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
package de.cuioss.sheriff.gateway.bff.logout;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.LogRecord;

import de.cuioss.sheriff.gateway.bff.BffLogMessages;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.EndSessionEndpointSource;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.LogoutRedirect;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.LogoutReturn;
import de.cuioss.sheriff.gateway.bff.logout.RpInitiatedLogout.TokenRevocation;
import de.cuioss.sheriff.gateway.bff.session.SessionRecord;
import de.cuioss.sheriff.gateway.testsupport.SheriffDebugCapture;
import de.cuioss.sheriff.token.client.logout.EndSessionFlow;
import de.cuioss.sheriff.token.client.logout.PostLogoutRedirectValidator;
import de.cuioss.sheriff.token.commons.error.ClientProtocolException;
import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link RpInitiatedLogout}: the gateway-side RP-initiated logout orchestration exercised
 * against the <em>real</em> engine {@link EndSessionFlow} (constructed with a
 * {@link PostLogoutRedirectValidator}), so the exact-match {@code post_logout_redirect_uri}
 * open-redirect defence and the constant-time {@code state} verification are the genuine engine code
 * paths — not stubs.
 * <p>
 * Covered: the initiate leg (the token-revocation seam, handed the logged-out session once and before
 * the end-session redirect is built, engine end-session redirect carrying
 * {@code id_token_hint}/{@code post_logout_redirect_uri}/{@code state}, and the single-use
 * {@code __Host-sheriff-logout} cookie), a failing seam reported as the latched {@code ApiSheriff-147}
 * {@code WARN} without stopping the logout (a cause chain that loops back on itself included), the
 * logout-state cookie round-trip through
 * {@link RpInitiatedLogout#completeReturn}, the open-redirect rejection of an unregistered
 * {@code post_logout_redirect_uri}, and the cases in which no end-session redirect is built at all.
 * <p>
 * The log handler captures from {@code DEBUG} upwards ({@link SheriffDebugCapture}), so a case that
 * states what a failed revocation does <em>not</em> write examines the {@code DEBUG} line as well.
 */
@EnableTestLogger(rootLevel = TestLogLevel.DEBUG)
@ExtendWith(SheriffDebugCapture.class)
@EnableGeneratorController
class RpInitiatedLogoutTest {

    private static final String END_SESSION = "https://idp.example.com/protocol/openid-connect/logout";
    private static final String REGISTERED_RETURN = "https://gw.example.com/auth/logout/return";
    private static final String FINAL_REDIRECT = "/goodbye";
    private static final Duration STATE_TTL = Duration.ofSeconds(60);
    private static final Instant NOW = Instant.parse("2026-07-23T10:00:00Z");
    private static final String RAW_ID_TOKEN = "raw-id-token-hint";
    /** A provider that publishes {@link #END_SESSION} as its end-session endpoint. */
    private static final EndSessionEndpointSource PUBLISHED = () -> Optional.of(END_SESSION);
    /** The text of the DEBUG line every failed revocation leaves, the first one included. */
    private static final String REVOCATION_FAILED_DEBUG = "Refresh-token revocation failed during RP-initiated logout";
    /** The part of the DEBUG line only a repeated failure leaves. */
    private static final String REVOCATION_FAILED_AGAIN_DEBUG = "already reported once";

    private List<SessionRecord> revocations;
    private EndSessionFlow endSessionFlow;
    private RpInitiatedLogout logout;
    private String refreshToken;
    private SessionRecord session;

    @BeforeEach
    void setUp() {
        revocations = new CopyOnWriteArrayList<>();
        TokenRevocation revocation = revocations::add;
        endSessionFlow = new EndSessionFlow(new PostLogoutRedirectValidator(Set.of(REGISTERED_RETURN)));
        logout = new RpInitiatedLogout(endSessionFlow, revocation, PUBLISHED, REGISTERED_RETURN, FINAL_REDIRECT,
                STATE_TTL);
        refreshToken = Generators.letterStrings(24, 32).next();
        session = sessionWithIdToken(RAW_ID_TOKEN);
    }

    /** A session holding {@code idToken} and the case's refresh token. */
    private SessionRecord sessionWithIdToken(String idToken) {
        return SessionRecord.builder()
                .sessionId(SessionRecord.newSessionId())
                .accessToken("mediated-access-token")
                .refreshToken(refreshToken)
                .idToken(idToken)
                .sub("user-sub-1")
                .expiresAt(NOW.plus(Duration.ofHours(8)))
                .build();
    }

    /** A logout over the real engine flow whose end-session endpoint comes from {@code source}. */
    private RpInitiatedLogout logoutOver(EndSessionEndpointSource source) {
        return logoutRevokingWith(revocations::add, source);
    }

    /** A logout over the real engine flow whose revocation seam is {@code revocation}. */
    private RpInitiatedLogout logoutRevokingWith(TokenRevocation revocation, EndSessionEndpointSource source) {
        return new RpInitiatedLogout(endSessionFlow, revocation, source, REGISTERED_RETURN, FINAL_REDIRECT,
                STATE_TTL);
    }

    /** A revocation seam that fails the way an unreachable or refusing revocation endpoint does. */
    private static TokenRevocation failingRevocation(AtomicInteger attempts) {
        return ended -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("revocation endpoint answered 503");
        };
    }

    private static int missingEndpointWarnings() {
        return TestLoggerFactory.getTestHandler()
                .resolveLogMessagesContaining(TestLogLevel.WARN, "ApiSheriff-135").size();
    }

    private static List<LogRecord> revocationFailureWarnings() {
        return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.WARN,
                BffLogMessages.WARN.LOGOUT_REVOCATION_FAILED.resolveIdentifierString());
    }

    private static List<LogRecord> debugLinesContaining(String part) {
        return TestLoggerFactory.getTestHandler().resolveLogMessagesContaining(TestLogLevel.DEBUG, part);
    }

    /**
     * Asserts that no captured record of any level — its message or the message of a throwable it
     * chains — carries {@code value}, after proving that a {@code DEBUG} line of {@link RpInitiatedLogout}
     * is captured at all.
     */
    private static void assertNoRecordCarries(String value, String what) {
        SheriffDebugCapture.assertDebugIsCaptured(RpInitiatedLogout.class);
        List<LogRecord> captured = SheriffDebugCapture.capturedRecords();
        assertFalse(captured.isEmpty(), "no record was captured, so the absence of the " + what + " proves nothing");
        assertEquals(List.of(), captured.stream().map(SheriffDebugCapture::rendered)
                        .filter(line -> line.contains(value)).toList(),
                "no captured record carries the " + what);
    }

    private static String cookieValue(String setCookieHeader) {
        String firstPair = setCookieHeader.split(";", 2)[0];
        return firstPair.substring(firstPair.indexOf('=') + 1);
    }

    @Nested
    @DisplayName("Logout initiation")
    class Initiation {

        @Test
        @DisplayName("Should build an end-session redirect carrying id_token_hint, post_logout_redirect_uri, and state")
        void shouldBuildEndSessionRedirect() {
            LogoutRedirect redirect = logout.initiate(session).orElseThrow();

            assertTrue(redirect.location().startsWith(END_SESSION), redirect.location());
            assertTrue(redirect.location().contains("id_token_hint="), "the id_token_hint is present");
            assertTrue(redirect.location().contains("post_logout_redirect_uri="),
                    "the exact post_logout_redirect_uri is present");
            String state = cookieValue(redirect.setCookieHeaders().getFirst());
            assertTrue(redirect.location().contains("state=" + state),
                    "the redirect state matches the minted cookie state");
        }

        @Test
        @DisplayName("Should set the single-use __Host-sheriff-logout state cookie hardened and short-lived")
        void shouldSetHardenedStateCookie() {
            LogoutRedirect redirect = logout.initiate(session).orElseThrow();

            assertEquals(1, redirect.setCookieHeaders().size());
            String cookie = redirect.setCookieHeaders().getFirst();
            assertTrue(cookie.startsWith(RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "="), cookie);
            assertTrue(cookie.contains("Max-Age=" + STATE_TTL.toSeconds()), cookie);
            assertTrue(cookie.contains("Path=/"), cookie);
            assertTrue(cookie.contains("Secure"), cookie);
            assertTrue(cookie.contains("HttpOnly"), cookie);
            assertTrue(cookie.contains("SameSite=Lax"), cookie);
        }

        @Test
        @DisplayName("Should mint a fresh unpredictable state on every initiation")
        void shouldMintFreshStatePerInitiation() {
            String first = cookieValue(logout.initiate(session).orElseThrow().setCookieHeaders().getFirst());
            String second = cookieValue(logout.initiate(session).orElseThrow().setCookieHeaders().getFirst());

            assertNotEquals(first, second, "each logout mints a distinct state");
        }

        @Test
        @DisplayName("Should hand the logged-out session to the revocation seam once, before the end-session redirect is built")
        void shouldRevokeMediatedTokens() {
            List<String> steps = new CopyOnWriteArrayList<>();
            RpInitiatedLogout ordered = logoutRevokingWith(ended -> {
                revocations.add(ended);
                steps.add("revoke");
            }, () -> {
                steps.add("resolve end-session endpoint");
                return Optional.of(END_SESSION);
            });

            LogoutRedirect redirect = ordered.initiate(session).orElseThrow();

            assertAll("the revocation seam of one initiated logout",
                    () -> assertEquals(List.of(session), revocations,
                            "the seam receives the logged-out session, exactly once"),
                    () -> assertEquals(List.of("revoke", "resolve end-session endpoint"), steps,
                            "the revocation runs before the end-session redirect is built"),
                    () -> assertTrue(redirect.location().startsWith(END_SESSION), "and the redirect is built"),
                    () -> assertEquals(List.of(), revocationFailureWarnings(), "a revocation that succeeds is not reported"));
        }

        @Test
        @DisplayName("Should still build the end-session redirect when the revocation seam throws")
        void shouldProceedWhenRevocationFails() {
            AtomicInteger attempts = new AtomicInteger();
            RpInitiatedLogout resilient = logoutRevokingWith(failingRevocation(attempts), PUBLISHED);

            LogoutRedirect redirect = resilient.initiate(session).orElseThrow();

            assertTrue(redirect.location().startsWith(END_SESSION),
                    "a revocation failure never strands the browser half-logged-out");
            assertEquals(1, attempts.get(), "the seam was called");
        }

        /**
         * A caller can repeat a logout at will, so the volume of {@code WARN} lines it produces must not
         * be its to set (ADR-0051). The first failure is the {@code ApiSheriff-147} {@code WARN}, which
         * carries no value and no throwable; every failure leaves a {@code DEBUG} line naming the
         * exception type — never the throwable itself — and a repeat leaves that line only.
         */
        @Test
        @DisplayName("Should report a failed revocation once as ApiSheriff-147 over two failing logouts, the second at DEBUG only")
        void shouldReportAFailedRevocationOnceAndRepeatsAtDebug() {
            AtomicInteger attempts = new AtomicInteger();
            RpInitiatedLogout resilient = logoutRevokingWith(failingRevocation(attempts), PUBLISHED);

            Optional<LogoutRedirect> first = resilient.initiate(session);
            int warningsAfterFirst = revocationFailureWarnings().size();
            int repeatLinesAfterFirst = debugLinesContaining(REVOCATION_FAILED_AGAIN_DEBUG).size();
            Optional<LogoutRedirect> second = resilient.initiate(session);

            List<LogRecord> warnings = revocationFailureWarnings();
            List<LogRecord> debugLines = debugLinesContaining(REVOCATION_FAILED_DEBUG);
            assertAll("two logouts whose revocation fails",
                    () -> assertTrue(first.orElseThrow().location().startsWith(END_SESSION),
                            "the first logout still redirects"),
                    () -> assertTrue(second.orElseThrow().location().startsWith(END_SESSION),
                            "and so does the second"),
                    () -> assertEquals(2, attempts.get(), "the seam was called once per logout"),
                    () -> assertEquals(1, warningsAfterFirst, "the first failure is the WARN"),
                    () -> assertEquals(0, repeatLinesAfterFirst, "the first failure is not reported as a repeat"),
                    () -> assertEquals(1, warnings.size(), "the WARN is recorded exactly once over two failures"),
                    () -> assertNull(warnings.getFirst().getThrown(),
                            "the WARN carries no throwable, whose message may hold the provider's answer"),
                    () -> assertEquals(2, debugLines.size(), "every failure leaves a DEBUG line"),
                    () -> assertTrue(debugLines.stream()
                                    .allMatch(line -> line.getMessage().contains(IllegalStateException.class.getName())),
                            "each DEBUG line names the type of the seam's exception"),
                    () -> assertTrue(debugLines.stream().allMatch(line -> line.getThrown() == null),
                            "no DEBUG line carries the throwable, whose message may hold the token or the provider's answer"),
                    () -> assertEquals(1, debugLinesContaining(REVOCATION_FAILED_AGAIN_DEBUG).size(),
                            "the second failure is a DEBUG line only"));
            assertNoRecordCarries(refreshToken, "refresh token");
        }

        /**
         * A seam may put the refresh token itself into the exception it raises — in its message, or in
         * a cause's. The failure is still reported, but only by the types of its cause chain: no record
         * at any level, {@code DEBUG} included, carries the token. The seam here does carry the token,
         * which the case proves before asserting its absence from the log, so the assertion can fail.
         */
        @Test
        @DisplayName("Should keep a refresh token the failing seam carries in its exception chain out of every log record")
        void shouldKeepATokenCarryingRevocationFailureOutOfTheLog() {
            AtomicReference<RuntimeException> raised = new AtomicReference<>();
            RpInitiatedLogout resilient = logoutRevokingWith(ended -> {
                IllegalStateException failure = new IllegalStateException(
                        "revocation of refresh token " + ended.refreshToken() + " refused",
                        new IllegalArgumentException("token=" + ended.refreshToken()));
                raised.set(failure);
                throw failure;
            }, PUBLISHED);

            LogoutRedirect redirect = resilient.initiate(session).orElseThrow();

            RuntimeException failure = raised.get();
            List<LogRecord> debugLines = debugLinesContaining(REVOCATION_FAILED_DEBUG);
            assertAll("a failed revocation whose exception chain carries the refresh token",
                    () -> assertTrue(redirect.location().startsWith(END_SESSION), "the logout still redirects"),
                    () -> assertNotNull(failure, "the seam raised"),
                    () -> assertTrue(failure.getMessage().contains(refreshToken),
                            "the seam's own message carries the refresh token"),
                    () -> assertTrue(failure.getCause().getMessage().contains(refreshToken),
                            "and so does its cause's"),
                    () -> assertEquals(1, revocationFailureWarnings().size(), "the failure is reported"),
                    () -> assertEquals(1, debugLines.size(), "and leaves one DEBUG line"),
                    () -> assertTrue(debugLines.getFirst().getMessage().contains(IllegalStateException.class.getName()
                                    + " caused by " + IllegalArgumentException.class.getName()),
                            "the DEBUG line names every exception type of the chain, outermost first"),
                    () -> assertNull(debugLines.getFirst().getThrown(), "the DEBUG line carries no throwable"));
            assertNoRecordCarries(refreshToken, "refresh token");
        }

        /**
         * {@link Throwable#initCause} refuses only self-causation, so a seam can raise an exception whose
         * cause chain loops back on itself through a second exception. Naming the chain's types must stop
         * at the first repeat: without that guard the walk never ends, which the bounded timeout turns
         * into a failure instead of a hung build.
         */
        @Test
        @DisplayName("Should name each type of a cause chain that loops back on itself once and complete the logout")
        void shouldCutACauseChainThatLoopsBackOnItself() {
            IllegalStateException outer = new IllegalStateException("revocation refused");
            IllegalArgumentException inner = new IllegalArgumentException("refusal detail");
            outer.initCause(inner);
            inner.initCause(outer);
            AtomicInteger attempts = new AtomicInteger();
            RpInitiatedLogout resilient = logoutRevokingWith(ended -> {
                attempts.incrementAndGet();
                throw outer;
            }, PUBLISHED);

            Optional<LogoutRedirect> redirect = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> resilient.initiate(session), "naming the types of a looping cause chain terminates");

            List<LogRecord> debugLines = debugLinesContaining(REVOCATION_FAILED_DEBUG);
            assertAll("a failed revocation whose cause chain loops back on itself",
                    () -> assertSame(outer, inner.getCause(), "precondition: the cause chain loops back on itself"),
                    () -> assertEquals(1, attempts.get(), "the seam was called and threw"),
                    () -> assertTrue(redirect.orElseThrow().location().startsWith(END_SESSION),
                            "the logout still redirects"),
                    () -> assertEquals(1, revocationFailureWarnings().size(), "the failure is reported"),
                    () -> assertEquals(1, debugLines.size(), "and leaves one DEBUG line"),
                    () -> assertTrue(debugLines.getFirst().getMessage().contains("(" + IllegalStateException.class.getName()
                                    + " caused by " + IllegalArgumentException.class.getName() + ")"),
                            "the DEBUG line names each type of the loop once, outermost first: "
                                    + debugLines.getFirst().getMessage()),
                    () -> assertNull(debugLines.getFirst().getThrown(), "the DEBUG line carries no throwable"));
        }
    }

    @Nested
    @DisplayName("Open-redirect defence (exact post_logout_redirect_uri match)")
    class OpenRedirectDefence {

        @Test
        @DisplayName("Should reject an unregistered post_logout_redirect_uri via the engine validator")
        void shouldRejectUnregisteredReturnUri() {
            RpInitiatedLogout evil = new RpInitiatedLogout(endSessionFlow, revocations::add, PUBLISHED,
                    "https://evil.example.com/steal", FINAL_REDIRECT, STATE_TTL);

            assertThrows(ClientProtocolException.class, () -> evil.initiate(session),
                    "the engine PostLogoutRedirectValidator rejects a non-exact-match return URI");
        }
    }

    @Nested
    @DisplayName("Return leg (logout-state cookie round-trip)")
    class ReturnLeg {

        @Test
        @DisplayName("Should redirect to final_redirect and clear the state cookie on a matching state")
        void shouldCompleteMatchingReturn() {
            LogoutRedirect initiated = logout.initiate(session).orElseThrow();
            String state = cookieValue(initiated.setCookieHeaders().getFirst());
            String cookieHeader = RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "=" + state;

            LogoutReturn result = logout.completeReturn(state, cookieHeader);

            assertTrue(result.isRedirect(), "a matching state completes the logout");
            assertEquals(302, result.status());
            assertEquals(FINAL_REDIRECT, result.location());
            String clearing = result.setCookieHeaders().getFirst();
            assertTrue(clearing.startsWith(RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "="), clearing);
            assertTrue(clearing.contains("Max-Age=0"), "the single-use state cookie is cleared");
        }

        @Test
        @DisplayName("Should reject a returned state that does not match the cookie 400")
        void shouldRejectMismatchedState() {
            LogoutRedirect initiated = logout.initiate(session).orElseThrow();
            String state = cookieValue(initiated.setCookieHeaders().getFirst());
            String cookieHeader = RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "=" + state;

            LogoutReturn result = logout.completeReturn("not-the-state", cookieHeader);

            assertFalse(result.isRedirect());
            assertEquals(400, result.status());
            assertTrue(result.setCookieHeaders().isEmpty());
        }

        @Test
        @DisplayName("Should reject a return that carries no logout-state cookie 400")
        void shouldRejectMissingStateCookie() {
            LogoutReturn result = logout.completeReturn("some-state", null);

            assertFalse(result.isRedirect());
            assertEquals(400, result.status());
        }

        @Test
        @DisplayName("Should reject a return whose state parameter is absent 400")
        void shouldRejectMissingStateParameter() {
            LogoutRedirect initiated = logout.initiate(session).orElseThrow();
            String state = cookieValue(initiated.setCookieHeaders().getFirst());
            String cookieHeader = RpInitiatedLogout.LOGOUT_STATE_COOKIE_NAME + "=" + state;

            LogoutReturn result = logout.completeReturn(null, cookieHeader);

            assertEquals(400, result.status());
        }
    }

    @Nested
    @DisplayName("Accessors and argument contract")
    class Contract {

        @Test
        @DisplayName("Should expose the configured final_redirect landing")
        void shouldExposeFinalRedirect() {
            assertEquals(FINAL_REDIRECT, logout.finalRedirect());
        }

        @Test
        @DisplayName("Should reject a null session on initiate")
        void shouldRejectNullSession() {
            assertThrows(NullPointerException.class, () -> logout.initiate(null));
        }

        @Test
        @DisplayName("Should reject blank required constructor settings")
        void shouldRejectBlankSettings() {
            assertThrows(IllegalArgumentException.class, () -> new RpInitiatedLogout(endSessionFlow, revocations::add,
                    PUBLISHED, "  ", FINAL_REDIRECT, STATE_TTL));
            assertThrows(IllegalArgumentException.class, () -> new RpInitiatedLogout(endSessionFlow, revocations::add,
                    PUBLISHED, REGISTERED_RETURN, "  ", STATE_TTL));
        }

        @Test
        @DisplayName("Should reject a missing end-session endpoint source")
        void shouldRejectMissingEndpointSource() {
            assertThrows(NullPointerException.class, () -> new RpInitiatedLogout(endSessionFlow, revocations::add,
                    null, REGISTERED_RETURN, FINAL_REDIRECT, STATE_TTL));
        }

        @Test
        @DisplayName("Should keep the end-session location and the state cookie out of the redirect's text form")
        void shouldRedactRedirectTextForm() {
            LogoutRedirect redirect = logout.initiate(session).orElseThrow();
            String state = cookieValue(redirect.setCookieHeaders().getFirst());

            String text = redirect.toString();

            assertFalse(text.contains(RAW_ID_TOKEN), text);
            assertFalse(text.contains(END_SESSION), text);
            assertFalse(text.contains(state), text);
        }
    }

    /**
     * A logout the browser cannot be sent to the identity provider for yields no redirect and never
     * raises, so the caller's local logout stands on its own. Every such case is run beside the provider
     * that does publish a usable endpoint ({@code Initiation}), so an implementation that never
     * redirected would fail there. The revocation seam is called in every one of them, once.
     */
    @Nested
    @DisplayName("No usable end-session endpoint")
    class NoUsableEndSessionEndpoint {

        @ParameterizedTest(name = "end_session_endpoint \"{0}\"")
        @ValueSource(strings = {"", "   ", "javascript:alert(1)", "/protocol/openid-connect/logout",
                "idp.example.com/logout", "ftp://idp.example.com/logout", "https:///logout", "not a uri",
                "http://idp.example.com/logout"})
        @DisplayName("Should yield no redirect, and report it, for an endpoint that is no absolute https address")
        void shouldYieldNoRedirectForUnusableEndpoint(String published) {
            RpInitiatedLogout local = logoutOver(() -> Optional.of(published));

            assertTrue(local.initiate(session).isEmpty(), "no redirect is built on " + published);
            assertEquals(1, missingEndpointWarnings());
            assertEquals(List.of(session), revocations, "the revocation seam is still called, once");
        }

        @Test
        @DisplayName("Should yield no redirect when the provider publishes no end-session endpoint")
        void shouldYieldNoRedirectWithoutEndpoint() {
            RpInitiatedLogout local = logoutOver(Optional::empty);

            assertTrue(local.initiate(session).isEmpty());
            assertEquals(List.of(session), revocations, "the revocation seam is still called, once");
            assertEquals(1, missingEndpointWarnings());
        }

        @Test
        @DisplayName("Should report a failed revocation and yield no redirect when the provider publishes no end-session endpoint")
        void shouldReportAFailedRevocationWithoutEndpoint() {
            AtomicInteger attempts = new AtomicInteger();
            RpInitiatedLogout local = logoutRevokingWith(failingRevocation(attempts), Optional::empty);

            Optional<LogoutRedirect> redirect = assertDoesNotThrow(() -> local.initiate(session),
                    "a failed revocation never turns a local logout into an error");

            assertAll("a failed revocation on a logout that cannot be redirected",
                    () -> assertTrue(redirect.isEmpty(), "no redirect is built"),
                    () -> assertEquals(1, attempts.get(), "the seam was called"),
                    () -> assertEquals(1, revocationFailureWarnings().size(), "the failure is reported"),
                    () -> assertEquals(1, missingEndpointWarnings(), "beside the missing endpoint"));
        }

        @Test
        @DisplayName("Should report the missing endpoint once, however many logouts meet it")
        void shouldReportMissingEndpointOnce() {
            RpInitiatedLogout local = logoutOver(Optional::empty);

            for (int attempt = 0; attempt < 4; attempt++) {
                assertTrue(local.initiate(session).isEmpty());
            }

            assertEquals(1, missingEndpointWarnings());
        }

        @Test
        @DisplayName("Should yield no redirect and no warning when discovery fails, and ask again next time")
        void shouldRetryDiscoveryAfterFailure() {
            AtomicInteger asked = new AtomicInteger();
            RpInitiatedLogout recovering = logoutOver(() -> {
                if (asked.incrementAndGet() == 1) {
                    throw new IllegalStateException("discovery unreachable");
                }
                return Optional.of(END_SESSION);
            });

            Optional<LogoutRedirect> duringOutage = recovering.initiate(session);
            Optional<LogoutRedirect> afterOutage = recovering.initiate(session);

            assertTrue(duringOutage.isEmpty(), "a failed discovery is not a failed logout");
            assertEquals(0, missingEndpointWarnings(), "an outage is not a provider without an endpoint");
            assertTrue(afterOutage.orElseThrow().location().startsWith(END_SESSION),
                    "the failure was not remembered");
            assertEquals(2, asked.get());
        }

        @Test
        @DisplayName("Should yield no redirect for a session that holds no ID token")
        void shouldYieldNoRedirectWithoutIdToken() {
            SessionRecord withoutIdToken = sessionWithIdToken("");

            assertTrue(logout.initiate(withoutIdToken).isEmpty(),
                    "there is no id_token_hint to send, so the browser is not sent to the provider");
            assertEquals(List.of(withoutIdToken), revocations, "the revocation seam is still called, once");
            assertEquals(0, missingEndpointWarnings());
        }
    }
}
