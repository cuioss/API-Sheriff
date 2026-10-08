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

import java.util.Locale;


import de.cuioss.test.juli.TestLogLevel;
import de.cuioss.test.juli.TestLoggerFactory;
import de.cuioss.test.juli.junit5.EnableTestLogger;
import de.cuioss.tools.logging.CuiLogger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Covers the emission rule {@link LogoutRejectionLog} implements over the vocabulary
 * {@link LogoutRejection} carries, which together decide how loudly the back-channel logout path
 * reports a refusal.
 * <p>
 * The rule is asserted by <strong>exact occurrence count</strong> rather than by mere presence or
 * absence: a presence-only assertion cannot tell a reason that is reported once from one that is
 * reported on every occurrence.
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

        /**
         * Exhaustive over the vocabulary, so a reason added later is covered without being named here.
         */
        @ParameterizedTest(name = "{0} is reported once")
        @EnumSource(LogoutRejection.class)
        @DisplayName("Should report every reason once, however many times it recurs")
        void shouldLatchEveryReason(LogoutRejection reason) {
            for (int occurrence = 0; occurrence < REPEATS; occurrence++) {
                rejectionLog.recordRejection(reason);
            }

            assertEquals(1, warningsFor(reason),
                    reason + " must be reported at WARN on its first occurrence and never again");
        }

        @Test
        @DisplayName("Should latch per reason, never across reasons")
        void shouldLatchPerReason() {
            rejectionLog.recordRejection(LogoutRejection.MISSING_LOGOUT_TOKEN);
            rejectionLog.recordRejection(LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY);
            rejectionLog.recordRejection(LogoutRejection.MISSING_LOGOUT_TOKEN);

            assertEquals(1, warningsFor(LogoutRejection.MISSING_LOGOUT_TOKEN));
            assertEquals(1, warningsFor(LogoutRejection.NO_IDP_DESTRUCTION_CAPABILITY),
                    "one reason consuming its latch must not silence a different one");
        }

        @Test
        @DisplayName("Should latch per instance, so a second runtime reports independently")
        void shouldLatchPerInstance() {
            rejectionLog.recordRejection(LogoutRejection.SIGNATURE_REJECTED);
            new LogoutRejectionLog(LOGGER).recordRejection(LogoutRejection.SIGNATURE_REJECTED);

            assertEquals(2, warningsFor(LogoutRejection.SIGNATURE_REJECTED),
                    "the latch belongs to the assembled component, not to the JVM");
        }

        @Test
        @DisplayName("Should reject a null reason rather than recording an unnamed refusal")
        void shouldRejectNullReason() {
            assertThrows(NullPointerException.class, () -> rejectionLog.recordRejection(null));
        }
    }

    @Nested
    @DisplayName("Rejection vocabulary")
    class Vocabulary {

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
