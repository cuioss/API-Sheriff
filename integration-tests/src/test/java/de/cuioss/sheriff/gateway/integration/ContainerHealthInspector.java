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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.restassured.path.json.JsonPath;

/**
 * Reads container-health facts off the built image and off the running Compose project, in the same
 * shape as {@link ImageLabelInspector}: a {@link ProcessBuilder} around {@code docker}, the child
 * waited on before its output is read, a {@value #INSPECT_TIMEOUT_SECONDS}s timeout, and a hard
 * failure on any non-zero exit.
 * <p>
 * Three reads live here. {@link #inspectHealthcheckTest(String)} and
 * {@link #inspectHealthStatus(String)} answer the two halves of "is the health signal real?" — what
 * the image <em>declares</em> and what a running container <em>reports</em> — and
 * {@link #composeContainers()} supplies the container set both are asked about, derived from the
 * running project rather than restated as a service list that would drift from
 * {@code docker-compose.yml}.
 * <p>
 * <strong>Why the output is redirected to a file rather than read from a pipe.</strong>
 * {@link ImageLabelInspector} reads the merged pipe because its output is a single label value,
 * orders of magnitude below the pipe buffer. {@code docker compose ps --format json} is not: it
 * emits every label of every container in the project, tens of kilobytes for this stack, which
 * exceeds the default pipe capacity on macOS. Draining a pipe after {@code waitFor} would then
 * deadlock — the child blocks writing, the parent blocks waiting — and draining it before
 * {@code waitFor} would make the timeout unreachable, which is the very trade
 * {@code ImageLabelInspector} documents. Redirecting to a file removes the buffer bound entirely, so
 * wait-then-read stays both safe and bounded at any output size, and one subprocess shape serves all
 * three reads.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class ContainerHealthInspector {

    /** Upper bound on any single docker call, matching {@link ImageLabelInspector}. */
    private static final long INSPECT_TIMEOUT_SECONDS = 30L;

    /**
     * Reads the container's health state without dereferencing an absent one. A container with no
     * healthcheck carries no {@code .State.Health} key at all, and the unguarded
     * {@code {{.State.Health.Status}}} template does not render empty for it — it aborts with a
     * template error and a non-zero exit, which this class reports as a failure. The {@code if}
     * guard is what turns "declares no healthcheck" into the empty string the negative control
     * needs, while leaving a declared status rendered verbatim.
     */
    private static final String HEALTH_STATUS_FORMAT = "{{if .State.Health}}{{.State.Health.Status}}{{end}}";

    private ContainerHealthInspector() {
        // utility
    }

    /**
     * Reads the {@code Test} vector of the {@code HEALTHCHECK} baked into an image.
     * <p>
     * The vector's first element is the form marker Docker resolved the declaration to — {@code CMD}
     * for the exec form, {@code CMD-SHELL} for the string form — which is why the raw vector is
     * returned rather than a boolean: the distinction between the two forms is the assertion, not an
     * implementation detail of it.
     *
     * @param image the image reference to inspect
     * @return the healthcheck's {@code Test} vector; empty when the image declares no healthcheck
     */
    static List<String> inspectHealthcheckTest(String image) {
        String output = runDocker("docker image inspect on " + image,
                "docker", "image", "inspect", "--format", "{{json .Config.Healthcheck}}", image);
        if (output.isEmpty() || "null".equals(output)) {
            return List.of();
        }
        List<String> test = new JsonPath(output).getList("Test", String.class);
        return test == null ? List.of() : List.copyOf(test);
    }

    /**
     * Reads what a running container currently reports about its own health.
     *
     * @param container the container name to inspect
     * @return the reported status ({@code starting}, {@code healthy}, {@code unhealthy}); the empty
     *         string when the container declares no healthcheck at all
     */
    static String inspectHealthStatus(String container) {
        return runDocker("docker inspect on " + container,
                "docker", "inspect", "--format", HEALTH_STATUS_FORMAT, container);
    }

    /**
     * Maps every service of the running Compose project to the container currently realising it.
     * <p>
     * The set is read from the project rather than restated here, so a service added, removed or
     * renamed in {@code docker-compose.yml} needs no edit in the tests that consume this.
     *
     * @return service name to container name, in the order {@code docker compose ps} reported them
     */
    static Map<String, String> composeContainers() {
        String output = runDocker("docker compose ps",
                "docker", "compose", "ps", "--format", "json");
        JsonPath json = new JsonPath(toJsonArray(output));
        List<String> services = json.getList("Service", String.class);
        List<String> names = json.getList("Name", String.class);
        if (services == null || names == null || services.size() != names.size()) {
            return fail(() -> "docker compose ps returned no usable Service/Name pairs. Output: " + output);
        }
        Map<String, String> containers = new LinkedHashMap<>();
        for (int i = 0; i < services.size(); i++) {
            containers.put(services.get(i), names.get(i));
        }
        return containers;
    }

    /**
     * Normalises {@code docker compose ps --format json} to a single JSON array.
     * <p>
     * Compose has emitted this output in both shapes across its 2.x line — newline-delimited objects
     * on the version this stack runs, a single array on others — so both are accepted rather than
     * pinning the test to one Compose build. Lines that open neither shape are dropped, which also
     * discards any warning Compose writes alongside the payload.
     *
     * @param output the raw command output
     * @return a JSON array document, {@code []} when nothing parseable was produced
     */
    private static String toJsonArray(String output) {
        if (output.startsWith("[")) {
            return output;
        }
        StringBuilder array = new StringBuilder("[");
        output.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("{"))
                .forEach(line -> array.append(array.length() > 1 ? "," : "").append(line));
        return array.append("]").toString();
    }

    /**
     * Runs one docker command and returns its stripped output.
     * <p>
     * The child is waited on before its output is read — see the class Javadoc for why that order is
     * safe here at any output size. {@code stderr} is merged into the captured stream so a daemon
     * error is reported in the failure message rather than lost.
     *
     * @param description how to name the call in a failure message
     * @param command     the command and its arguments
     * @return the command's output, stripped
     */
    private static String runDocker(String description, String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Path captured = null;
        try {
            captured = Files.createTempFile("api-sheriff-docker-", ".out");
            builder.redirectOutput(captured.toFile());
            Process process = builder.start();
            if (!process.waitFor(Duration.ofSeconds(INSPECT_TIMEOUT_SECONDS))) {
                process.destroyForcibly();
                fail(() -> description + " did not complete within " + INSPECT_TIMEOUT_SECONDS + "s");
            }
            String output = Files.readString(captured, StandardCharsets.UTF_8).strip();
            assertEquals(0, process.exitValue(), () -> description
                    + " failed — is the stack up and the image built? Output: " + output);
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run " + description, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during " + description, e);
        } finally {
            deleteQuietly(captured);
        }
    }

    /**
     * Removes the capture file without letting its cleanup mask the outcome of the call it served.
     *
     * @param captured the file to remove; ignored when {@code null}
     */
    private static void deleteQuietly(Path captured) {
        if (captured != null && !captured.toFile().delete()) {
            // The capture lives in the system temp directory, so an undeletable one is cleanup noise
            // rather than a health-signal defect — deferring it to JVM exit keeps it from being
            // reported as a test failure.
            captured.toFile().deleteOnExit();
        }
    }
}
