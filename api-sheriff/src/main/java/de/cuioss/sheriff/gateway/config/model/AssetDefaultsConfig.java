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
package de.cuioss.sheriff.gateway.config.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The global {@code asset_defaults} block: the operator's <strong>add-only</strong>
 * extension of the gateway's built-in asset content-type map.
 * <p>
 * {@code contentTypes} maps a lowercase filename extension (without the dot) to the
 * {@code Content-Type} the asset terminal action serves for it. The map is consulted
 * only for an extension the gateway does not already know: an entry naming one of the
 * built-in extensions is <em>refused at boot</em> rather than silently honoured or
 * silently ignored. That add-only ruling is a security bound, not an ergonomic one —
 * permitting an operator to remap {@code svg} to {@code text/html} (or to relax it away
 * from {@code image/svg+xml}) would turn a served asset into a stored-XSS lever on a
 * security gateway, while still leaving the stated problem — an unmapped extension
 * falling back to {@code application/octet-stream} — solved.
 * <p>
 * The block is optional; an absent or empty block resolves every built-in extension
 * exactly as an unconfigured gateway does.
 *
 * @param contentTypes the operator-declared extension-to-content-type additions, keyed
 *                     by lowercase extension, empty when none are configured
 * @author API Sheriff Team
 * @since 1.0
 */
public record AssetDefaultsConfig(Map<String, String> contentTypes) {

    /**
     * Canonical constructor defensively copying {@code contentTypes} and lowercasing
     * every declared extension key, so a key's case can never decide whether the
     * add-only boot refusal or the runtime lookup sees it.
     */
    public AssetDefaultsConfig {
        contentTypes = contentTypes == null ? Map.of() : lowercaseKeys(contentTypes);
    }

    private static Map<String, String> lowercaseKeys(Map<String, String> declared) {
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            normalized.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
        }
        return Map.copyOf(normalized);
    }
}
