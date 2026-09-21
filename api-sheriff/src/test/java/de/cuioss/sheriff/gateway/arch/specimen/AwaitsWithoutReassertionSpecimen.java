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

import java.util.concurrent.atomic.AtomicBoolean;


import de.cuioss.sheriff.gateway.testsupport.Awaits;
import org.junit.jupiter.api.Test;

/**
 * Standing <strong>negative control</strong> for the until-then-re-assert guard in
 * {@code AwaitsReassertionArchTest}: two test methods that deliberately end their wait without
 * re-asserting, so the guard has a known violation of each shape to detect.
 * <p>
 * <strong>Both shapes are present on purpose.</strong> The defect is that the <em>last</em> word on
 * the awaited state is the poll, not an assertion — and that happens two ways: a test whose body
 * carries no assertion at all, and a test that asserts something <em>before</em> the wait and then
 * lets the wait have the last word. A guard that merely looked for the presence of an assertion
 * somewhere in the method would pass the second one, so carrying both here is what makes an
 * order-blind guard fail this control rather than pass it.
 * <p>
 * <strong>Every awaited condition is already satisfied.</strong> The class name matches none of
 * Surefire's default include patterns, so these methods are never scheduled; the {@code @Test}
 * annotations exist only because the guard's selection is "methods declared as tests". Should an
 * include pattern ever widen, an already-satisfied condition returns at once instead of holding the
 * build for a ceiling it was never meant to reach.
 * <p>
 * Its matched positive counterpart is {@link AwaitsWithReassertionSpecimen}, which carries the
 * compliant shape plus the helper-tier near miss. The pair is what proves the guard
 * <em>discriminates</em> rather than merely always-failing.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class AwaitsWithoutReassertionSpecimen {

    private static final String LABEL = "a condition that already holds";

    /** Already {@code true}, so no method here ever waits; see the class Javadoc. */
    private static final AtomicBoolean SETTLED = new AtomicBoolean(true);

    /**
     * The deliberate violation in its plainest spelling: the wait is the whole test, so the only
     * evidence the method ever produces is that the condition held at some point during polling.
     *
     * @throws Exception never, because the condition is already satisfied. The clause is
     *                   {@code Exception} rather than the {@code TimeoutException} the wait declares
     *                   because {@code SimplifyTestThrows} broadens it; see
     *                   {@code doc/development/build-gate-discipline.adoc}
     */
    @Test
    void waitsAndAssertsNothing() throws Exception {
        Awaits.until(SETTLED::get, LABEL, Awaits.TEARDOWN_CEILING_SECONDS);
    }

    /**
     * The same violation with an assertion present but in the wrong place. The method asserts the
     * pre-state, then waits, and stops — so the post-state is still unasserted. A guard that asked
     * only "does this test assert anything?" would accept it.
     *
     * @throws Exception never, because the condition is already satisfied
     */
    @Test
    void assertsBeforeTheWaitAndNotAfter() throws Exception {
        assertTrue(SETTLED.get(), "the pre-state, asserted before the wait");
        Awaits.until(SETTLED::get, LABEL, Awaits.TEARDOWN_CEILING_SECONDS);
    }
}
