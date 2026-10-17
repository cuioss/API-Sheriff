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
package de.cuioss.sheriff.gateway.arch.specimen.edgecycle;

/**
 * The root class of the specimen root {@code arch.specimen.edgecycle}, standing in for
 * {@code GatewayEdgeRoute} in the edge root: the class {@code EdgeSubPackageArchTest}'s root-class
 * rule forbids every sub-package class to depend on.
 * <p>
 * It is the target of the deliberate back-reference in
 * {@link de.cuioss.sheriff.gateway.arch.specimen.edgecycle.beta.CycleBetaSpecimen}, which is what makes
 * that rule's negative control fail. It references nothing itself, so the only violation the specimen
 * root carries for that rule is the one the control expects.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class EdgeRootSpecimen {

    /**
     * Present only so the class is not empty; never invoked.
     *
     * @return a fixed label
     */
    public String label() {
        return "edge root specimen";
    }
}
