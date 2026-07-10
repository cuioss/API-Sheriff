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
package de.cuioss.sheriff.api.gateway.proxy;

import de.cuioss.tools.logging.LogRecord;
import de.cuioss.tools.logging.LogRecordModel;
import lombok.experimental.UtilityClass;

/**
 * DSL-style {@link LogRecord} catalogue for the API Sheriff proxy edge.
 * <p>
 * Structured {@code INFO} / {@code WARN} / {@code ERROR} messages carry the
 * {@code ApiSheriff} prefix and a stable numeric identifier, so they are
 * greppable and assertable. {@code DEBUG} / {@code TRACE} diagnostics use the
 * logger directly and are not catalogued here.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public final class ProxyLogMessages {

    private static final String PREFIX = "ApiSheriff";

    /**
     * Info-level messages (INFO range 1-99).
     */
    @UtilityClass
    public static final class INFO {

        /** The catch-all proxy route was registered on the data plane. */
        public static final LogRecord ROUTE_REGISTERED = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(1)
                .template("Proxy route registered: path-prefix='%s' upstream='%s'")
                .build();
    }

    /**
     * Warn-level messages (WARN range 100-199).
     */
    @UtilityClass
    public static final class WARN {

        /** Forwarding a request to the configured upstream failed. */
        public static final LogRecord UPSTREAM_FAILURE = LogRecordModel.builder()
                .prefix(PREFIX)
                .identifier(100)
                .template("Proxy request to upstream '%s' failed: %s")
                .build();
    }
}
