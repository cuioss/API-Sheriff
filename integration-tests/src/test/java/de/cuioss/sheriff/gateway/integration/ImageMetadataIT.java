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

import static de.cuioss.sheriff.gateway.integration.ImageLabelInspector.inspectLabel;
import static de.cuioss.sheriff.gateway.integration.ImageLabelInspector.passthrough;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reads the OCI provenance labels off the <strong>built image</strong> and fails when either one is
 * dishonest.
 * <p>
 * The assertion target is deliberately the image, never the Dockerfile text. A Dockerfile can declare
 * {@code LABEL org.opencontainers.image.version="${APP_VERSION}"} perfectly while the value never
 * arrives — the build arg is not plumbed, the Compose service does not forward it, the release lane
 * does not export it — and every one of those breakages leaves the source reading correctly. Only the
 * built artifact can answer what the label actually says.
 * <p>
 * The read is the same one {@code .github/workflows/release.yml} performs on the pulled digest in its
 * smoke step: {@code docker image inspect --format} with a Go template indexing
 * {@code .Config.Labels} by the label key. This IT runs it at PR time against the locally built
 * {@value #IMAGE}, so a break in the arg chain surfaces on the pull request rather than during a
 * release.
 * <p>
 * The expected values are resolved exactly as the Compose passthrough resolves them —
 * {@code ${APP_VERSION:-dev}} and {@code ${APP_REVISION:-dev}}, where an unset <em>or empty</em>
 * variable falls back to {@code dev}. On a local or PR run both are {@code dev}; in the release lane
 * they are the released version and the release-tag commit.
 * <p>
 * The third test is a negative control. Both positive legs assert a specific non-empty value, and a
 * helper that returned the empty string for everything would fail them — but a helper that returned
 * some constant non-empty string would not be caught by them alone. Asserting that a label the image
 * does not carry reads back empty proves the helper discriminates between a present and an absent
 * label, so the positive legs are reading real metadata.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("Container image OCI metadata")
class ImageMetadataIT {

    /** The image the integration-test harness builds and the release lane publishes. */
    private static final String IMAGE = "api-sheriff:distroless";

    private static final String VERSION_LABEL = "org.opencontainers.image.version";
    private static final String REVISION_LABEL = "org.opencontainers.image.revision";

    /**
     * A label no image in this project declares. Fixed rather than generated: the control is only
     * meaningful while the name is guaranteed absent, and a generated name could collide with a label
     * the base image carries.
     */
    private static final String ABSENT_LABEL = "de.cuioss.sheriff.no-such-label";

    @Test
    @DisplayName("the version label carries the version the build was given")
    void versionLabelCarriesTheBuildVersion() {
        String expected = passthrough("APP_VERSION");

        String actual = inspectLabel(IMAGE, VERSION_LABEL);

        assertFalse(actual.isEmpty(), () -> IMAGE + " carries no " + VERSION_LABEL
                + " — the label is absent or the APP_VERSION build arg never reached the Dockerfile");
        assertEquals(expected, actual, () -> VERSION_LABEL + " on " + IMAGE + " is '" + actual
                + "' but the build was given APP_VERSION='" + expected
                + "'. The image is stating a version it was not built at.");
    }

    @Test
    @DisplayName("the revision label carries the commit the build was given")
    void revisionLabelCarriesTheBuildRevision() {
        String expected = passthrough("APP_REVISION");

        String actual = inspectLabel(IMAGE, REVISION_LABEL);

        assertFalse(actual.isEmpty(), () -> IMAGE + " carries no " + REVISION_LABEL
                + " — the label is absent or the APP_REVISION build arg never reached the Dockerfile."
                + " Check the api-sheriff service's build.args in docker-compose.yml: the release lane"
                + " runs no docker build of its own, so build.args is the only channel to the ARG.");
        assertEquals(expected, actual, () -> REVISION_LABEL + " on " + IMAGE + " is '" + actual
                + "' but the build was given APP_REVISION='" + expected
                + "'. The image is naming a commit it was not built from.");
    }

    @Test
    @DisplayName("a label the image does not carry reads back empty — the inspect helper discriminates")
    void absentLabelReadsBackEmpty() {
        String actual = inspectLabel(IMAGE, ABSENT_LABEL);

        assertTrue(actual.isEmpty(), () -> "inspecting the absent label " + ABSENT_LABEL + " on " + IMAGE
                + " returned '" + actual + "' rather than an empty result. The helper is not reading the"
                + " label map, so the version and revision assertions above prove nothing.");
    }
}
