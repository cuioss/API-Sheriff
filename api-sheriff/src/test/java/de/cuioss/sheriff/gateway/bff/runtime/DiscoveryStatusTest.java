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
package de.cuioss.sheriff.gateway.bff.runtime;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import de.cuioss.sheriff.gateway.bff.runtime.DiscoveryStatus.State;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link DiscoveryStatus}: a tracking view starts {@code not-attempted}, each attempt of the
 * wrapped fetch records its own outcome — {@code resolved} or {@code failed}, last write wins — and
 * reading the state never invokes the fetch. The inert view of a gateway without an active BFF runtime
 * is not applicable and records nothing.
 */
@DisplayName("DiscoveryStatus — non-fetching view of the most recent provider-metadata resolution")
class DiscoveryStatusTest {

    private static final String METADATA = "provider-metadata";

    /** A fetch that counts its invocations and answers {@link #METADATA}. */
    private static final class CountingFetch implements Supplier<String> {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public String get() {
            invocations.incrementAndGet();
            return METADATA;
        }
    }

    /** A fetch that throws its one failure on every call. */
    private static Supplier<String> failingWith(RuntimeException failure) {
        return () -> {
            throw failure;
        };
    }

    @Nested
    @DisplayName("Tracking view")
    class Tracking {

        @Test
        @DisplayName("starts not-attempted and applicable")
        void startsNotAttempted() {
            DiscoveryStatus status = DiscoveryStatus.tracking();

            assertAll("a fresh tracking view",
                    () -> assertEquals(State.NOT_ATTEMPTED, status.state()),
                    () -> assertTrue(status.isApplicable(), "it belongs to an active BFF runtime"));
        }

        @Test
        @DisplayName("wrapping a fetch neither invokes it nor changes the state")
        void wrappingDoesNotFetch() {
            DiscoveryStatus status = DiscoveryStatus.tracking();
            CountingFetch fetch = new CountingFetch();

            status.recording(fetch);

            assertAll("a wrapped but unused fetch",
                    () -> assertEquals(0, fetch.invocations.get(), "wrapping is not an attempt"),
                    () -> assertEquals(State.NOT_ATTEMPTED, status.state()));
        }

        @Test
        @DisplayName("reading the state never invokes the fetch")
        void readingNeverFetches() {
            DiscoveryStatus status = DiscoveryStatus.tracking();
            CountingFetch fetch = new CountingFetch();
            status.recording(fetch);

            for (int read = 0; read < 10; read++) {
                status.state();
            }

            assertAll("ten reads of a view whose fetch was never used",
                    () -> assertEquals(0, fetch.invocations.get(), "a read is never a fetch"),
                    () -> assertEquals(State.NOT_ATTEMPTED, status.state()));
        }

        @Test
        @DisplayName("records resolved when the fetch returns, and hands its value through")
        void recordsResolved() {
            DiscoveryStatus status = DiscoveryStatus.tracking();
            CountingFetch fetch = new CountingFetch();
            Supplier<String> recording = status.recording(fetch);

            String resolved = recording.get();

            assertAll("a successful attempt",
                    () -> assertEquals(METADATA, resolved, "the fetch's value is returned unchanged"),
                    () -> assertEquals(1, fetch.invocations.get(), "one attempt is one fetch"),
                    () -> assertEquals(State.RESOLVED, status.state()));
        }

        @Test
        @DisplayName("records failed when the fetch throws, and rethrows the same exception")
        void recordsFailed() {
            DiscoveryStatus status = DiscoveryStatus.tracking();
            IllegalStateException failure = new IllegalStateException("discovery refused");
            Supplier<String> recording = status.recording(failingWith(failure));

            IllegalStateException thrown = assertThrows(IllegalStateException.class, recording::get);

            assertAll("a failed attempt",
                    () -> assertSame(failure, thrown, "the caller's failure handling sees the original exception"),
                    () -> assertEquals(State.FAILED, status.state()));
        }

        @Test
        @DisplayName("a later successful attempt replaces a failure: the last attempt wins")
        void resolvedAfterFailed() {
            DiscoveryStatus status = DiscoveryStatus.tracking();
            assertThrows(IllegalStateException.class,
                    () -> status.recording(failingWith(new IllegalStateException("first attempt"))).get());
            State afterFailure = status.state();

            status.recording(new CountingFetch()).get();

            assertAll("a failure followed by a resolution",
                    () -> assertEquals(State.FAILED, afterFailure, "precondition: the first attempt failed"),
                    () -> assertEquals(State.RESOLVED, status.state(), "a failure is not sticky"));
        }

        @Test
        @DisplayName("a later failed attempt replaces a resolution: the last attempt wins")
        void failedAfterResolved() {
            DiscoveryStatus status = DiscoveryStatus.tracking();
            status.recording(new CountingFetch()).get();
            State afterResolution = status.state();

            assertThrows(IllegalStateException.class,
                    () -> status.recording(failingWith(new IllegalStateException("second attempt"))).get());

            assertAll("a resolution followed by a failure",
                    () -> assertEquals(State.RESOLVED, afterResolution, "precondition: the first attempt resolved"),
                    () -> assertEquals(State.FAILED, status.state(), "a resolution is not sticky either"));
        }

        @Test
        @DisplayName("rejects a null fetch")
        void rejectsNullFetch() {
            DiscoveryStatus status = DiscoveryStatus.tracking();

            assertThrows(NullPointerException.class, () -> status.recording(null));
            assertEquals(State.NOT_ATTEMPTED, status.state(), "a rejected wrap is no attempt");
        }
    }

    @Nested
    @DisplayName("Inert view")
    class Inert {

        @Test
        @DisplayName("is not applicable and reports not-attempted")
        void isNotApplicable() {
            DiscoveryStatus status = DiscoveryStatus.inert();

            assertAll("the view of a gateway without an active BFF runtime",
                    () -> assertFalse(status.isApplicable()),
                    () -> assertEquals(State.NOT_ATTEMPTED, status.state()));
        }

        @Test
        @DisplayName("refuses to record a fetch and never invokes it")
        void refusesToRecord() {
            DiscoveryStatus status = DiscoveryStatus.inert();
            CountingFetch fetch = new CountingFetch();

            assertThrows(IllegalStateException.class, () -> status.recording(fetch));

            assertAll("an inert view stays inert",
                    () -> assertEquals(0, fetch.invocations.get()),
                    () -> assertEquals(State.NOT_ATTEMPTED, status.state()));
        }
    }

    @Test
    @DisplayName("each state is reported under its fixed, non-disclosing token")
    void tokensAreFixed() {
        assertAll("the readiness payload's oidc_discovery values",
                () -> assertEquals("not-attempted", State.NOT_ATTEMPTED.token()),
                () -> assertEquals("resolved", State.RESOLVED.token()),
                () -> assertEquals("failed", State.FAILED.token()),
                () -> assertEquals(Set.of("not-attempted", "resolved", "failed"),
                        Arrays.stream(State.values()).map(State::token).collect(Collectors.toSet()),
                        "the datum draws from exactly these three tokens"),
                () -> assertEquals(List.of(State.NOT_ATTEMPTED, State.RESOLVED, State.FAILED),
                        List.of(State.values())));
    }
}
