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

import static de.cuioss.sheriff.gateway.integration.ImageLabelInspector.inspectLabel;
import static de.cuioss.sheriff.gateway.integration.ImageLabelInspector.passthrough;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The JFR image's counterpart to {@link ImageMetadataIT}: reads the version label off the
 * <strong>built</strong> {@value #IMAGE} and fails when it is dishonest.
 * <p>
 * This runs in the {@code jfr} Maven profile, the lane that actually builds this image — its
 * {@code docker-build-jfr} execution runs
 * {@code docker compose -f docker-compose.yml -f docker-compose.jfr.yml build api-sheriff} at
 * {@code pre-integration-test}. The overlay re-specifies only {@code image}, {@code build.context} and
 * {@code build.dockerfile}, so it INHERITS {@code build.args.APP_VERSION} from the base service by
 * mapping merge, and that arg reaches {@code Dockerfile.native.jfr}'s {@code ARG APP_VERSION}. The
 * whole chain is therefore machine-assertable here, and this test is what asserts it — the
 * {@code jfr} profile excludes {@link ImageMetadataIT} only because that one is pinned to the
 * distroless tag this lane never produces, not because the JFR image's labels go unchecked.
 * <p>
 * The asserted set is deliberately narrower than {@link ImageMetadataIT}'s.
 * {@code Dockerfile.native.jfr} declares {@code ARG APP_VERSION} and the matching version label and
 * <em>nothing for revision</em> — no {@code APP_REVISION} arg, no
 * {@code org.opencontainers.image.revision} label — because this profiling image is never published
 * and carries no release provenance. Asserting a revision leg here would assert a label the image is
 * not meant to have.
 * <p>
 * The second test is the same negative control {@link ImageMetadataIT} carries, for the same reason: a
 * helper returning a constant non-empty string would satisfy the positive leg alone, so proving it
 * reads back empty for a label the image does not carry is what makes the positive leg evidence.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@DisplayName("JFR container image OCI metadata")
class ImageMetadataJfrIT {

    /** The image the {@code jfr} profile builds and tags. */
    private static final String IMAGE = "api-sheriff:jfr";

    private static final String VERSION_LABEL = "org.opencontainers.image.version";

    /**
     * A label no image in this project declares. Fixed rather than generated: the control is only
     * meaningful while the name is guaranteed absent, and a generated name could collide with a label
     * the base image carries.
     */
    private static final String ABSENT_LABEL = "de.cuioss.sheriff.no-such-label";

    @Test
    @DisplayName("the JFR image's version label carries the version the build was given")
    void versionLabelCarriesTheBuildVersion() {
        String expected = passthrough("APP_VERSION");

        String actual = inspectLabel(IMAGE, VERSION_LABEL);

        assertFalse(actual.isEmpty(), () -> IMAGE + " carries no " + VERSION_LABEL
                + " — the label is absent or the APP_VERSION build arg never reached"
                + " Dockerfile.native.jfr. The jfr Compose overlay re-specifies only image, context and"
                + " dockerfile, so it inherits build.args from the api-sheriff service in"
                + " docker-compose.yml; a build.args entry removed there silently disarms this image"
                + " too.");
        assertEquals(expected, actual, () -> VERSION_LABEL + " on " + IMAGE + " is '" + actual
                + "' but the build was given APP_VERSION='" + expected
                + "'. The image is stating a version it was not built at.");
    }

    @Test
    @DisplayName("a label the JFR image does not carry reads back empty — the inspect helper discriminates")
    void absentLabelReadsBackEmpty() {
        String actual = inspectLabel(IMAGE, ABSENT_LABEL);

        assertTrue(actual.isEmpty(), () -> "inspecting the absent label " + ABSENT_LABEL + " on " + IMAGE
                + " returned '" + actual + "' rather than an empty result. The helper is not reading the"
                + " label map, so the version assertion above proves nothing.");
    }
}
