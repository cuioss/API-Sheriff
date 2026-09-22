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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;


import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import de.cuioss.tools.logging.CuiLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the flood policy {@link LogoutRejectionLog} implements and the classification
 * {@link LogoutRejection} carries, which together decide how loudly the back-channel logout path
 * reports a refusal.
 * <p>
 * Both halves of the policy are asserted by <strong>exact occurrence count</strong> rather than by
 * mere presence or absence, because each half has a distinct failure mode and a presence-only
 * assertion is blind to one of them: a latched reason that stopped latching floods a reserved,
 * unauthenticated path, and an unlatched reason that started latching hides every repeat of a genuine
 * identity-provider misconfiguration after the first.
 */
@EnableTestLogger
class LogoutRejectionLogTest {

    private static final CuiLogger LOGGER = new CuiLogger(LogoutRejectionLogTest.class);
    private static final int REPEATS = 5;

    private final LogoutRejectionLog rejectionLog = new LogoutRejectionLog(LOGGER);

    private static int warningsFor(LogoutRejection reason) {
        return TestLoggerFactory.getTestHandler()
                .resolveLogMessagesContaining(TestLogLevel.WARN, reason.token()).size();
    }

    @Nested
    @DisplayName("Emission policy")
    class EmissionPolicy {

        @Test
        @DisplayName("Should report an attacker-reachable reason once, however many times it recurs")
        void shouldLatchAttackerReachableReason() {
            for (int occurrence = 0; occurrence < REPEATS; occurrence++) {
                rejectionLog.record(LogoutRejection.SIGNATURE_REJECTED);
            }

            assertEquals(1, warningsFor(LogoutRejection.SIGNATURE_REJECTED),
                    "the reserved, unauthenticated path must not let a caller drive an unbounded WARN flood");
        }

        @Test
        @DisplayName("Should report a signature-verified reason on every occurrence")
        void shouldNotLatchSignatureVerifiedReason() {
            for (int occurrence = 0; occurrence < REPEATS; occurrence++) {
                rejectionLog.record(LogoutRejection.EVENTS_MISSING);
            }

            assertEquals(REPEATS, warningsFor(LogoutRejection.EVENTS_MISSING),
                    "only a genuinely signed token reaches this reason, so there is no flood to bound "
                            + "and every occurrence is operationally interesting");
        }

        @Test
        @DisplayName("Should latch per reason, never across reasons")
        void shouldLatchPerReason() {
            rejectionLog.record(LogoutRejection.MISSING_LOGOUT_TOKEN);
            rejectionLog.record(LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY);
            rejectionLog.record(LogoutRejection.MISSING_LOGOUT_TOKEN);

            assertEquals(1, warningsFor(LogoutRejection.MISSING_LOGOUT_TOKEN));
            assertEquals(1, warningsFor(LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY),
                    "one reason consuming its latch must not silence a different one");
        }

        @Test
        @DisplayName("Should latch per instance, so a second runtime reports independently")
        void shouldLatchPerInstance() {
            rejectionLog.record(LogoutRejection.SIGNATURE_REJECTED);
            new LogoutRejectionLog(LOGGER).record(LogoutRejection.SIGNATURE_REJECTED);

            assertEquals(2, warningsFor(LogoutRejection.SIGNATURE_REJECTED),
                    "the latch belongs to the assembled component, not to the JVM");
        }

        @Test
        @DisplayName("Should reject a null reason rather than recording an unnamed refusal")
        void shouldRejectNullReason() {
            assertThrows(NullPointerException.class, () -> rejectionLog.record(null));
        }
    }

    /**
     * The classification table itself. It is asserted exhaustively — every member is named on exactly
     * one side — so adding a reason without deciding whether an unauthenticated caller can drive it
     * fails here rather than silently defaulting into a flood vector.
     */
    @Nested
    @DisplayName("Rejection classification")
    class Classification {

        private static final Set<LogoutRejection> ATTACKER_REACHABLE = EnumSet.of(
                LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY,
                LogoutRejection.MISSING_LOGOUT_TOKEN,
                LogoutRejection.SIGNATURE_REJECTED);

        @Test
        @DisplayName("Should classify exactly the pre-signature reasons as attacker-reachable")
        void shouldClassifyEveryReason() {
            for (LogoutRejection reason : LogoutRejection.values()) {
                boolean expectedSignatureVerified = !ATTACKER_REACHABLE.contains(reason);
                assertEquals(expectedSignatureVerified, reason.isSignatureVerified(),
                        "classification of " + reason + " decides whether it is latched — a reason "
                                + "reachable before signature verification must never be unlatched");
            }
        }

        @Test
        @DisplayName("Should carry a bounded, non-sensitive lower-case token for every reason")
        void shouldCarryBoundedTokens() {
            for (LogoutRejection reason : LogoutRejection.values()) {
                String token = reason.token();
                assertFalse(token.isBlank(), reason + " must carry a reason token");
                assertEquals(token.toLowerCase(Locale.ROOT), token,
                        "reason tokens are a fixed lower-case vocabulary: " + token);
                assertTrue(token.matches("[a-z-]+"),
                        "a reason token must not be able to carry token material or an offending "
                                + "value — it is a closed vocabulary, not a message: " + token);
            }
        }
    }
}
