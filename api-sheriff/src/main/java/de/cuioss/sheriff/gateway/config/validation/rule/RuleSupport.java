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
package de.cuioss.sheriff.gateway.config.validation.rule;

import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.Protocol;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The helpers every boot-time {@link ValidationRule} shares, whichever concern class or
 * not-yet-extracted rule of the configuration validator it belongs to: where a {@link ConfigError}
 * is reported (the file and the JSON pointer), how an offending configured value is echoed into a
 * refusal message, which anchor a route resolves, and which protocol a route rides.
 * <p>
 * Each helper exists exactly once. {@link #renderForMessage} above all must stay the single copy of
 * the CWE-117 escaping. The rule for every refusal: a configured scalar echoed into its message is
 * passed through {@link #renderForMessage} first, so that a hostile value cannot forge a boot ERROR
 * log line.
 * <p>
 * Stateless, and therefore safe for concurrent use. Free of framework dependencies as the pre-boot
 * rule requires of this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class RuleSupport {

    /** The file name every refusal of a {@code gateway.yaml} key is reported against. */
    public static final String GATEWAY_FILE = "gateway.yaml";

    /** The JSON pointer of the {@code routes} list in an endpoint file. */
    public static final String ENDPOINT_ROUTES_POINTER = "/endpoint/routes";

    /** The cap on the offending value a refusal message echoes back to the operator. */
    private static final int ECHOED_VALUE_MAX_LENGTH = 60;

    /**
     * The file name every refusal of an endpoint-file key is reported against.
     *
     * @param endpoint the endpoint whose file is named
     * @return the endpoint file, relative to the configuration root
     */
    public static String endpointFile(EndpointConfig endpoint) {
        return "endpoints/" + endpoint.id() + ".yaml";
    }

    /**
     * Renders an offending configuration value for a refusal message: every character outside
     * printable ASCII — CR and LF above all — becomes a {@code \\uXXXX} escape, and at most
     * {@code ECHOED_VALUE_MAX_LENGTH} (60) characters of the input are rendered, followed by
     * {@code ...} when the input is longer. Because each escape expands one input character to six,
     * the rendering itself can be longer than 60 characters. The operator still sees what they
     * typed; the boot ERROR log cannot be line-forged by it.
     *
     * @param value the configured value to echo
     * @return the escaped and capped rendering, safe to embed in a refusal message
     */
    public static String renderForMessage(String value) {
        StringBuilder rendered = new StringBuilder();
        int limit = Math.min(value.length(), ECHOED_VALUE_MAX_LENGTH);
        for (int index = 0; index < limit; index++) {
            char current = value.charAt(index);
            if (current >= 0x20 && current < 0x7F) {
                rendered.append(current);
            } else {
                rendered.append("\\u%04X".formatted((int) current));
            }
        }
        if (value.length() > limit) {
            rendered.append("...");
        }
        return rendered.toString();
    }

    /**
     * The anchor name a route declares: its own {@code anchor}, or its endpoint's when the route
     * declares none.
     *
     * @param endpoint the endpoint declaring the route
     * @param route    the route whose anchor name is read
     * @return the declared anchor name, {@code null} when neither the route nor its endpoint names one
     */
    public static @Nullable String declaredAnchorName(EndpointConfig endpoint, RouteConfig route) {
        return route.anchor() != null ? route.anchor() : endpoint.anchor();
    }

    /**
     * The route's effective protocol — the declared value, or {@link Protocol#HTTP} when omitted.
     *
     * @param route the route whose protocol is resolved
     * @return the declared protocol, {@link Protocol#HTTP} when the route declares none
     */
    public static Protocol effectiveProtocol(RouteConfig route) {
        Protocol declared = route.protocol();
        return declared != null ? declared : Protocol.HTTP;
    }
}
