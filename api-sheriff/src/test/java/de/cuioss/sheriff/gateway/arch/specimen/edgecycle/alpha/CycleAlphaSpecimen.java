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
package de.cuioss.sheriff.gateway.arch.specimen.edgecycle.alpha;

import de.cuioss.sheriff.gateway.arch.specimen.edgecycle.beta.CycleBetaSpecimen;

/**
 * One half of the deliberate cycle in the specimen root {@code arch.specimen.edgecycle}: this class
 * in the {@code alpha} sub-package references {@link CycleBetaSpecimen} in {@code beta}, which
 * references this class back. The cycle is the violation {@code EdgeSubPackageArchTest}'s cycle rule
 * must report in its negative control.
 * <p>
 * <strong>The reference must stay a real bytecode dependency.</strong> ArchUnit reads bytecode, so a
 * reference held only in Javadoc or in a comment would not count, the cycle would disappear and the
 * negative control would fail — the loud failure this constraint exists to keep loud. Nothing here is
 * ever invoked.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CycleAlphaSpecimen {

    /**
     * The {@code alpha → beta} edge of the cycle.
     *
     * @return a new beta specimen; never invoked
     */
    public CycleBetaSpecimen beta() {
        return new CycleBetaSpecimen();
    }
}
