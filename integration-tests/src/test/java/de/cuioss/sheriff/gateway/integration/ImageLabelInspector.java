/*
 * Copyright © 2026 CUI-OpenSource-Software (info@cuioss.de)
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Reads a single OCI label off a built image, shared by the per-image metadata ITs.
 * <p>
 * The read is the same one {@code .github/workflows/release.yml} performs on the pulled digest in its
 * smoke step: {@code docker image inspect --format} with a Go template indexing {@code .Config.Labels}
 * by the label key.
 * <p>
 * It lives here rather than in one IT because two lanes assert against two different images — the
 * distroless image the default integration-test lane builds and the JFR image the {@code jfr} lane
 * builds. Sharing the reader keeps the subprocess handling (below) stated once; duplicating it would
 * let one copy silently regress.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class ImageLabelInspector {

    /** The Compose passthrough default for the provenance args ({@code ${APP_VERSION:-dev}}). */
    static final String DEFAULT_VALUE = "dev";

    private static final long INSPECT_TIMEOUT_SECONDS = 30L;

    private ImageLabelInspector() {
        // utility
    }

    /**
     * Resolves a build variable the way the Compose {@code ${VAR:-dev}} passthrough does: an unset
     * <em>or empty</em> value falls back to {@code dev}.
     *
     * @param variable the environment variable name
     * @return the resolved value, never empty
     */
    static String passthrough(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? DEFAULT_VALUE : value;
    }

    /**
     * Reads one label off a built image.
     * <p>
     * The process is waited on <em>before</em> its output is read, and that order is load-bearing.
     * Reading first would make the timeout unreachable: {@code readAllBytes()} blocks until EOF, and
     * EOF on the merged stream arrives only when the child closes stdout — so an unresponsive daemon
     * would hang in the read and never reach the guard that exists to catch exactly that. Waiting
     * first is safe here because the output is a single label value (or a short daemon error), orders
     * of magnitude below the pipe buffer that would otherwise deadlock a read-after-wait; that bound
     * is also why no reader thread is warranted.
     *
     * @param image the image reference to inspect
     * @param label the OCI label key
     * @return the label value, stripped; empty when the image does not carry the label
     */
    static String inspectLabel(String image, String label) {
        ProcessBuilder builder = new ProcessBuilder(
                "docker", "image", "inspect",
                "--format", "{{index .Config.Labels \"" + label + "\"}}",
                image);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(INSPECT_TIMEOUT_SECONDS))) {
                process.destroyForcibly();
                fail(() -> "docker image inspect on " + image + " did not complete within "
                        + INSPECT_TIMEOUT_SECONDS + "s");
            }
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            assertEquals(0, process.exitValue(), () -> "docker image inspect on " + image
                    + " failed — is the image built? Output: " + output.strip());
            return output.strip();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run docker image inspect on " + image, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while inspecting " + image, e);
        }
    }
}
