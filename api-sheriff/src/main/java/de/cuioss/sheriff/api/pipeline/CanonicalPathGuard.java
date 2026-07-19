/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.pipeline;

import java.util.Locale;
import java.util.Objects;


import de.cuioss.sheriff.api.events.EventType;
import de.cuioss.sheriff.api.events.GatewayException;

/**
 * D3b GW-01 single-canonical-path guard, run at stage 1.
 * <p>
 * The GW-01 threat is a decoding split: the gateway authorizes one interpretation of a path while
 * the upstream dispatches a different one, letting a request slip past route/verb-gate/auth/whitelist
 * checks. The guard closes it by rejecting the two constructs that make a path multi-valued —
 * <strong>an encoded path separator</strong> ({@code %2f} / {@code %2F}, and the encoded backslash
 * {@code %5c} / {@code %5C}) and a <strong>matrix parameter</strong> ({@code ;}) — with a 400
 * {@link EventType#SECURITY_FILTER_VIOLATION}. The guard inspects the raw inbound path (before
 * normalization can hide the ambiguity) and additionally asserts that stage 1 recorded exactly one
 * {@link PipelineRequest#canonicalPath() canonical path}, which every later stage then consumes.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class CanonicalPathGuard {

    private static final String ENCODED_SLASH = "%2f";
    private static final String ENCODED_BACKSLASH = "%5c";
    private static final char MATRIX_PARAM = ';';

    /**
     * Rejects ambiguous path encodings and asserts the single-canonical-path invariant.
     *
     * @param request the in-flight request context; its canonical path must be set (stage 1)
     * @throws GatewayException with {@link EventType#SECURITY_FILTER_VIOLATION} on an encoded
     *                          separator or a matrix parameter
     */
    public void process(PipelineRequest request) {
        Objects.requireNonNull(request, "request");
        String rawPath = request.requestPath();
        String lowered = rawPath.toLowerCase(Locale.ROOT);
        if (lowered.contains(ENCODED_SLASH)) {
            throw violation("encoded path separator %2f");
        }
        if (lowered.contains(ENCODED_BACKSLASH)) {
            throw violation("encoded backslash %5c");
        }
        if (rawPath.indexOf(MATRIX_PARAM) >= 0) {
            throw violation("matrix parameter ';'");
        }
        if (request.canonicalPath() == null) {
            throw new IllegalStateException("Canonical path guard requires the canonical path resolved at stage 1");
        }
    }

    private static GatewayException violation(String detail) {
        return new GatewayException(EventType.SECURITY_FILTER_VIOLATION,
                "Non-canonical path rejected: " + detail);
    }
}
