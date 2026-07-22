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
package de.cuioss.sheriff.gateway.asset;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;


import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.core.HttpSecurityValidator;
import de.cuioss.http.security.exceptions.UrlSecurityException;
import de.cuioss.http.security.monitoring.SecurityEventCounter;
import de.cuioss.http.security.pipeline.PipelineFactory;

/**
 * Canonicalize-and-confine for asset paths — the single boundary that turns an
 * untrusted request sub-path into a filesystem target proven to lie inside a
 * configured root (decision: ADR-0014, threat GW-asset-traversal).
 * <p>
 * Confinement is applied <strong>before any source is touched</strong> and is shared
 * by every {@link AssetSource}. It runs in two stages, both fail-closed:
 * <ol>
 *   <li><strong>Canonicalize</strong> the raw sub-path through the {@code cui-http}
 *       {@code URL_PATH} validation pipeline ({@code strict} policy). The pipeline
 *       rejects the whole class of encoding attacks — percent-encoded traversal and
 *       slashes, double/mixed encoding, {@code %00} null bytes, overlong sequences,
 *       backslash and {@code ..;/} tricks — throwing {@link UrlSecurityException},
 *       which this class maps to a rejection rather than a leaked exception.</li>
 *   <li><strong>Confine</strong> the normalized path under the root: resolve it
 *       against the absolute, normalized root and confirm the result still starts
 *       with the root. Any {@code ..} segment that survives canonicalization and
 *       would escape the root is rejected here.</li>
 * </ol>
 * Rejecting the whole class — not one spelling — is deliberate: the confinement is
 * the sole gate, so a single missed encoding is a traversal. The adversarial test
 * corpus proves no member of the class escapes.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class PathConfinement {

    private final HttpSecurityValidator pathValidator;

    /**
     * Creates a confinement backed by the supplied inbound-validation policy and the
     * application's shared security event counter.
     *
     * @param configuration the {@code cui-http} inbound validation policy
     * @param eventCounter  the shared security event counter (never a local instance
     *                      in production wiring)
     */
    public PathConfinement(SecurityConfiguration configuration, SecurityEventCounter eventCounter) {
        this.pathValidator = PipelineFactory.createUrlPathPipeline(
                Objects.requireNonNull(configuration, "configuration"),
                Objects.requireNonNull(eventCounter, "eventCounter"));
    }

    /**
     * Creates a confinement with the {@code strict} inbound-validation policy and a
     * fresh event counter — the convenience wiring for tests and single-root use.
     */
    public PathConfinement() {
        this(SecurityConfiguration.strict(), new SecurityEventCounter());
    }

    /**
     * Canonicalizes {@code requestSubPath} and confines it under {@code root}.
     *
     * @param root           the configured directory root; confinement guarantees the
     *                       returned path is inside it
     * @param requestSubPath the untrusted request sub-path (relative to the asset
     *                       mount); may be {@code null}
     * @return the confined, absolute, normalized path, or {@link Optional#empty()}
     *         when the input is rejected by the canonicalization pipeline or would
     *         escape {@code root}
     */
    public Optional<Path> confine(Path root, String requestSubPath) {
        Objects.requireNonNull(root, "root");
        if (requestSubPath == null) {
            return Optional.empty();
        }
        String canonical;
        try {
            String asAbsolute = "/" + stripLeadingSlashes(requestSubPath);
            canonical = pathValidator.validate(asAbsolute).orElse("/");
        } catch (UrlSecurityException _) {
            return Optional.empty();
        }
        String relative = stripLeadingSlashes(canonical);
        Path confinedRoot = root.toAbsolutePath().normalize();
        try {
            Path resolved = confinedRoot.resolve(relative).normalize();
            if (!resolved.startsWith(confinedRoot)) {
                return Optional.empty();
            }
            return Optional.of(resolved);
        } catch (InvalidPathException _) {
            return Optional.empty();
        }
    }

    private static String stripLeadingSlashes(String value) {
        int index = 0;
        while (index < value.length() && value.charAt(index) == '/') {
            index++;
        }
        return value.substring(index);
    }
}
