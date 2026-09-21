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
package de.cuioss.sheriff.gateway.arch.specimen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;


import de.cuioss.sheriff.gateway.testsupport.Awaits;
import org.junit.jupiter.api.Test;

/**
 * Standing <strong>matched positive controls</strong> for the until-then-re-assert guard in
 * {@code AwaitsReassertionArchTest}: the three shapes the guard must leave alone, carried alongside
 * {@link AwaitsWithoutReassertionSpecimen}'s violations so the set proves the guard discriminates
 * instead of always-failing.
 * <p>
 * <strong>The three shapes are not interchangeable.</strong> {@link #waitsThenReasserts()} is the
 * compliant spelling — it is in the guard's selection and passes on its merits. The other two are
 * <em>near misses</em>, each pinning a different accepted limit of the guard, and each has a control
 * that asserts the near-miss property FIRST and only then that the guard does not report it.
 * Reversed, either control would pass just as happily against a file that had lost the shape
 * altogether, which is the always-passing failure one level down.
 * <ul>
 *   <li>{@link #awaitSettled()} pins the <em>scope</em> limit: an await with no assertion after it,
 *       excluded only because it is a helper rather than a declared test.</li>
 *   <li>{@link #waitsThenAssertsSomethingElse()} pins the <em>position-only</em> limit: a declared
 *       test that waits on one value and then asserts a different one. The guard relates the
 *       assertion to the wait by position, never by subject, so it accepts this — and that
 *       acceptance is a stated limit rather than a defect.</li>
 * </ul>
 * <p>
 * <strong>Order within this file is load-bearing.</strong> {@link #awaitSettled()} must stay LAST:
 * the helper-tier control asserts that no assertion follows the file's final helper-tier await, so a
 * method carrying an assertion placed after it would fail that control.
 * <p>
 * <strong>Every awaited condition is already satisfied</strong>, and the class name matches none of
 * Surefire's default include patterns, for the reasons given in the sibling specimen.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@SuppressWarnings("java:S3577") // the non-matching name is load-bearing; see the class Javadoc
final class AwaitsWithReassertionSpecimen {

    private static final String LABEL = "a condition that already holds";

    /** Already {@code true}, so no method here ever waits; see the class Javadoc. */
    private static final AtomicBoolean SETTLED = new AtomicBoolean(true);

    /**
     * A second, deliberately unrelated flag. Nothing waits on it — its only job is to be the subject
     * of {@link #waitsThenAssertsSomethingElse()}'s post-wait assertion, so that assertion is
     * demonstrably not about the state that was awaited.
     */
    private static final AtomicBoolean UNRELATED = new AtomicBoolean(true);

    /**
     * The compliant shape: the wait is followed by an assertion on the state it waited for, so the
     * post-state — not the poll — is the evidence the method leaves behind.
     *
     * @throws Exception never, because the condition is already satisfied. The clause is
     *                   {@code Exception} rather than the {@code TimeoutException} the wait declares
     *                   because {@code SimplifyTestThrows} broadens it on every declared test; the
     *                   helper below keeps the narrow clause precisely because it is not one
     */
    @Test
    void waitsThenReasserts() throws Exception {
        Awaits.until(SETTLED::get, LABEL, Awaits.TEARDOWN_CEILING_SECONDS);

        assertTrue(SETTLED.get(), "the awaited state, re-asserted after the wait");
    }

    /**
     * The position-only near miss: a declared test that waits on {@code SETTLED} and then asserts
     * {@code UNRELATED}. The awaited state is never re-asserted, so this method does NOT satisfy the
     * invariant the guard is named for — yet the guard accepts it, because its predicate asks only
     * whether an assertion appears after the wait in the same body. That acceptance is the guard's
     * stated position-only limit made concrete, and it is what the matched control over this method
     * pins.
     *
     * @throws Exception never, because the condition is already satisfied; broadened for the same
     *                   {@code SimplifyTestThrows} reason given on {@link #waitsThenReasserts()}
     */
    @Test
    void waitsThenAssertsSomethingElse() throws Exception {
        Awaits.until(SETTLED::get, LABEL, Awaits.TEARDOWN_CEILING_SECONDS);

        assertTrue(UNRELATED.get(), "a value the wait never mentioned, asserted after the wait");
    }

    /**
     * The scope near miss: a wait primitive whose body is the await and nothing else. Its re-assertion
     * obligation belongs to whichever test calls it, so the guard — which selects declared test
     * methods — must not report this, even though the shape is character-for-character the violation
     * {@link AwaitsWithoutReassertionSpecimen#waitsAndAssertsNothing()} carries.
     *
     * @throws TimeoutException never, because the condition is already satisfied
     */
    void awaitSettled() throws TimeoutException {
        Awaits.until(SETTLED::get, LABEL, Awaits.TEARDOWN_CEILING_SECONDS);
    }
}
