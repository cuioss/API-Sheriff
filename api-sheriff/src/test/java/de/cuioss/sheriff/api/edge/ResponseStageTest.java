/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.edge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("ResponseStage — stage 7 streamed response header policy")
class ResponseStageTest {

    @Nested
    @DisplayName("hop-by-hop stripping")
    class HopByHop {

        @ParameterizedTest
        @ValueSource(strings = {
                "Connection", "Keep-Alive", "Proxy-Authenticate", "Proxy-Authorization",
                "TE", "Trailer", "Transfer-Encoding", "Upgrade", "Content-Length"})
        @DisplayName("strips hop-by-hop and framing headers regardless of not_modified")
        void stripsHopByHop(String header) {
            assertFalse(ResponseStage.isForwardableResponseHeader(header, true),
                    header + " is hop-by-hop and must never relay (not_modified enabled)");
            assertFalse(ResponseStage.isForwardableResponseHeader(header, false),
                    header + " is hop-by-hop and must never relay (not_modified disabled)");
        }

        @Test
        @DisplayName("matching is case-insensitive")
        void caseInsensitive() {
            assertFalse(ResponseStage.isForwardableResponseHeader("transfer-encoding", true));
            assertFalse(ResponseStage.isForwardableResponseHeader("CONTENT-LENGTH", true));
        }
    }

    @Nested
    @DisplayName("conditional-response headers gated by not_modified")
    class ConditionalHeaders {

        @ParameterizedTest
        @ValueSource(strings = {"ETag", "Last-Modified"})
        @DisplayName("relays validators untouched when the route enables not_modified")
        void relaysValidatorsWhenEnabled(String header) {
            assertTrue(ResponseStage.isForwardableResponseHeader(header, true),
                    header + " must relay on a not_modified-enabled route (304 pass-through)");
        }

        @ParameterizedTest
        @ValueSource(strings = {"ETag", "Last-Modified"})
        @DisplayName("strips validators when the route disables not_modified")
        void stripsValidatorsWhenDisabled(String header) {
            assertFalse(ResponseStage.isForwardableResponseHeader(header, false),
                    header + " must be stripped on a not_modified-disabled route");
        }
    }

    @Nested
    @DisplayName("ordinary headers")
    class OrdinaryHeaders {

        @ParameterizedTest
        @ValueSource(strings = {"Content-Type", "Cache-Control", "Set-Cookie", "Location"})
        @DisplayName("relays ordinary response headers regardless of not_modified")
        void relaysOrdinaryHeaders(String header) {
            assertTrue(ResponseStage.isForwardableResponseHeader(header, true));
            assertTrue(ResponseStage.isForwardableResponseHeader(header, false));
        }
    }

    @Nested
    @DisplayName("body-framing eligibility (Content-Length preservation vs chunked streaming)")
    class BodyFraming {

        @ParameterizedTest
        @ValueSource(ints = {200, 201, 301, 400, 404, 500, 502})
        @DisplayName("a body-bearing status streams a relayed body (chunked when length is unknown)")
        void statusMayCarryBody(int status) {
            assertTrue(ResponseStage.mayCarryBody(status),
                    status + " permits a message body and must frame the streamed relay");
        }

        @ParameterizedTest
        @ValueSource(ints = {100, 101, 199, 204, 304})
        @DisplayName("a bodyless status never frames a streamed body")
        void statusForbidsBody(int status) {
            assertFalse(ResponseStage.mayCarryBody(status),
                    status + " forbids a message body (1xx / 204 / 304) and must not be chunked");
        }
    }
}
