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
package de.cuioss.sheriff.gateway.arch.specimen.edgecycle.beta;

import de.cuioss.sheriff.gateway.arch.specimen.edgecycle.EdgeRootSpecimen;
import de.cuioss.sheriff.gateway.arch.specimen.edgecycle.alpha.CycleAlphaSpecimen;

/**
 * The other half of the deliberate cycle in the specimen root {@code arch.specimen.edgecycle}, and the
 * carrier of its deliberate back-reference to the root:
 * <ul>
 *   <li>{@link #alpha()} closes the {@code alpha ↔ beta} cycle that {@code EdgeSubPackageArchTest}'s
 *       cycle rule must report;</li>
 *   <li>{@link #root()} depends on {@link EdgeRootSpecimen}, the specimen root class, which the
 *       root-class rule must report.</li>
 * </ul>
 * <p>
 * <strong>Both references must stay real bytecode dependencies</strong>, for the reason given on
 * {@link CycleAlphaSpecimen}. Nothing here is ever invoked.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CycleBetaSpecimen {

    /**
     * The {@code beta → alpha} edge that closes the cycle.
     *
     * @return a new alpha specimen; never invoked
     */
    public CycleAlphaSpecimen alpha() {
        return new CycleAlphaSpecimen();
    }

    /**
     * The sub-package-to-root dependency the root-class rule forbids.
     *
     * @return a new root specimen; never invoked
     */
    public EdgeRootSpecimen root() {
        return new EdgeRootSpecimen();
    }
}
