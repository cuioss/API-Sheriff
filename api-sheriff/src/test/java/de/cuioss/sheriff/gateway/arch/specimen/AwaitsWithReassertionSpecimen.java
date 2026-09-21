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
 * {@code AwaitsReassertionArchTest}: the two shapes the guard must leave alone, carried alongside
 * {@link AwaitsWithoutReassertionSpecimen}'s violations so the pair proves the guard discriminates
 * instead of always-failing.
 * <p>
 * <strong>The two shapes are not interchangeable.</strong> {@link #waitsThenReasserts()} is the
 * compliant spelling — it is in the guard's selection and passes on its merits. {@link #awaitSettled()}
 * is the <em>near miss</em>: an await with no re-assertion after it, excluded only because it is a
 * helper rather than a declared test, which is the guard's scope limit made concrete. The control
 * over it asserts that near-miss property first — that this file really does still carry an
 * un-reasserted await outside every test method — and only then that the guard does not report it.
 * Reversed, the control would pass just as happily against a file that had lost the helper
 * altogether, which is the always-passing failure one level down.
 * <p>
 * <strong>Every awaited condition is already satisfied</strong>, and the class name matches none of
 * Surefire's default include patterns, for the reasons given in the sibling specimen.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class AwaitsWithReassertionSpecimen {

    private static final String LABEL = "a condition that already holds";

    /** Already {@code true}, so no method here ever waits; see the class Javadoc. */
    private static final AtomicBoolean SETTLED = new AtomicBoolean(true);

    /**
     * The compliant shape: the wait is followed by an assertion on the state it waited for, so the
     * post-state — not the poll — is the evidence the method leaves behind.
     *
     * @throws TimeoutException never, because the condition is already satisfied
     */
    @Test
    void waitsThenReasserts() throws TimeoutException {
        Awaits.until(SETTLED::get, LABEL, Awaits.TEARDOWN_CEILING_SECONDS);

        assertTrue(SETTLED.get(), "the awaited state, re-asserted after the wait");
    }

    /**
     * The near miss: a wait primitive whose body is the await and nothing else. Its re-assertion
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
