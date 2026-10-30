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
package de.cuioss.sheriff.gateway.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Finds the repository root for the contract tests that read a document or a build file of the tree.
 * <p>
 * The search walks up from {@code user.dir} rather than taking a fixed one-level hop because the working
 * directory is not the same everywhere: surefire runs with the module as its working directory, but an IDE
 * runner or an aggregator invocation may use another. Probing for what the contract reads resolves the
 * root from a property of the tree instead of from an assumption about the runner, and an unresolvable
 * root fails naming the directory the walk started from rather than surfacing later as a
 * {@code NoSuchFileException} on a path nobody asked for:
 * {@snippet :
 * Path adrDirectory = RepositoryRoot.holding("doc/adr").resolve("doc/adr");
 * }
 * <p>
 * Thread-safe: the class is stateless.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class RepositoryRoot {

    private RepositoryRoot() {
        // static members only
    }

    /**
     * Returns the nearest ancestor of the working directory holding every probe.
     *
     * @param probes the files or directories the root must hold, relative to it
     * @return the repository root; the calling test fails when no ancestor holds every probe
     */
    public static Path holding(String... probes) {
        Path start = Path.of(System.getProperty("user.dir"));
        for (Path candidate = start; candidate != null; candidate = candidate.getParent()) {
            Path root = candidate;
            if (Arrays.stream(probes).allMatch(probe -> Files.exists(root.resolve(probe)))) {
                return root;
            }
        }
        return fail("cannot resolve the repository root from the working directory " + start
                + ": no ancestor of it holds " + String.join(" and ", probes));
    }
}
