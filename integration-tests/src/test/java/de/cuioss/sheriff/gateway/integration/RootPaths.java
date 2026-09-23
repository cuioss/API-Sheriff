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
package de.cuioss.sheriff.gateway.integration;

/**
 * The <strong>single Java-side owner</strong> of the context-path normalisation rule this suite
 * applies before composing or comparing a management or application root path.
 * <p>
 * Its shell peer is {@code normalize_root_path} in
 * {@code integration-tests/scripts/lib-docker-compose.sh}, which the host-side bring-up scripts
 * ({@code start-integration-container.sh}, {@code demo-client/scripts/start-dev-environment.sh})
 * apply to the {@code de.cuioss.sheriff.management-root-path} label they read off the resolved
 * Compose model. The two implement the SAME rule on opposite sides of the language boundary, and
 * each language boundary has exactly one home for it — that is the whole point of this class. A
 * second Java copy beside a caller is what would let the two sides drift, and
 * {@link ManagementRootPathLabelIT} compares a host-derived spelling against a Java-derived one, so
 * a drift surfaces there as a reported divergence that is not one.
 * <p>
 * {@code deployment/compose-sample/scripts/start-sample.sh} deliberately keeps its own inline copy:
 * the operator sample is shipped to be read and adapted standalone, so it does not source this
 * module's shell library.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class RootPaths {

    private RootPaths() {
        // utility
    }

    /**
     * Normalises a context path for concatenation by removing its ENTIRE trailing run of slashes.
     * <p>
     * Consequently {@code "/"} — a root-mounted interface — becomes the EMPTY STRING, which is the
     * correct rendering of "served at the port root" rather than a degenerate case: every caller
     * appends an endpoint that already begins with a slash ({@code "/health"},
     * {@code "/health/ready"}, {@code "/metrics"}), so the empty string is what makes
     * {@code normalize(root) + "/health"} resolve to {@code "/health"}. A retained slash would
     * compose {@code "//health"}, a path the gateway does not serve.
     * <p>
     * The whole run rather than a single character is load-bearing: stripping only the last
     * separator leaves {@code "/ops//"} as {@code "/ops/"}, which still splices a doubled separator
     * into every composed URL — and would diverge from the shell peer named on this class, which
     * collapses the full run.
     * <p>
     * A path carrying no trailing slash is returned unchanged, so the method is idempotent.
     *
     * @param path the context path to normalise, never {@code null}
     * @return the path with every trailing slash removed; the empty string for {@code "/"}
     */
    static String normalize(String path) {
        return path.replaceAll("/+$", "");
    }
}
