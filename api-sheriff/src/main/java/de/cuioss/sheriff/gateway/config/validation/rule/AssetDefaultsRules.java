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

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.GATEWAY_FILE;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.renderForMessage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import de.cuioss.sheriff.gateway.asset.AssetResponseEnvelope;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AssetDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import lombok.experimental.UtilityClass;

/**
 * The boot-time {@link ValidationRule}s that judge the {@code asset_defaults.content_types} block: the
 * operator's additions to the gateway's built-in asset content-type map.
 * <p>
 * The add-only rule ({@link #validateAssetContentTypesAddOnly}) refuses an entry whose extension the
 * built-in map already carries, because remapping a built-in extension would turn a served asset into
 * a stored-XSS lever. The value rule ({@link #validateAssetContentTypeValues}) refuses an entry whose
 * value is not a well-formed {@code type/subtype} media type, because the value is served verbatim as
 * the asset's {@code Content-Type} response header.
 * <p>
 * Every rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and never
 * fails fast. Stateless, and therefore safe for concurrent use. Free of framework dependencies as the
 * pre-boot rule requires of this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class AssetDefaultsRules {

    private static final String ASSET_DEFAULTS_CONTENT_TYPES_POINTER = "/asset_defaults/content_types";

    /**
     * A well-formed {@code type/subtype} media type with optional {@code ;name=value} parameters,
     * matched against the WHOLE value ({@link java.util.regex.Matcher#matches()}). The whole-input
     * match is load-bearing: an {@code ^…$}-anchored {@code find()} would admit a trailing newline,
     * since Java's {@code $} also matches before a final line terminator — exactly the CR/LF this
     * gate exists to refuse. Parameter values are tokens only; a quoted-string parameter has no
     * place in a static asset's {@code Content-Type} and admitting one would widen the character set
     * this bound narrows.
     * <p>
     * Every quantifier here is <em>possessive</em>, the parameter-list repetition included. A greedy
     * {@code (?:…)*} whose body itself carries quantified runs makes the engine recurse once per
     * iteration and retain a backtracking position for each, so a long enough input exhausts the
     * stack ({@code StackOverflowError}) instead of simply failing to match. Possessive quantifiers
     * turn the repetition into a flat, allocation-free scan. The change is behaviour-neutral for the
     * same reason it is on {@link RuleSupport#MEDIA_TYPE_TOKEN}: the delimiters are disjoint from the
     * token character class, so no backtrack into an already-matched run can ever succeed.
     */
    private static final Pattern MEDIA_TYPE = Pattern.compile(
            RuleSupport.MEDIA_TYPE_TOKEN + "/" + RuleSupport.MEDIA_TYPE_TOKEN
                    + "(?:[ \\t]*+;[ \\t]*+" + RuleSupport.MEDIA_TYPE_TOKEN + "=" + RuleSupport.MEDIA_TYPE_TOKEN
                    + ")*+");

    /**
     * Enforces the <strong>add-only</strong> ruling on {@code asset_defaults.content_types}: an entry
     * whose extension is already carried by the gateway's built-in asset content-type map is refused.
     * <p>
     * The refusal is a security bound rather than an ergonomic one. The built-in map is what makes an
     * asset response's {@code Content-Type} gateway-governed instead of source-dictated, and several
     * of its entries are load-bearing for that governance — remapping {@code svg} away from
     * {@code image/svg+xml}, or pointing any built-in extension at {@code text/html}, turns a served
     * asset into a stored-XSS lever on a security gateway. Refusing at boot (rather than silently
     * ignoring the entry at request time, which is what the runtime precedence in
     * {@code AssetResponseEnvelope.contentTypeFor} would otherwise do) means the operator learns the
     * mapping did not take effect, instead of shipping a descriptor that reads as though it did.
     * <p>
     * Every offending entry is collected rather than failing on the first, per ADR-0009 — the operator
     * sees the whole list in one boot attempt. Keys arrive already lowercased by
     * {@link AssetDefaultsConfig}, so an entry's case cannot decide whether this refusal sees it.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared list every violation is appended to
     */
    public static void validateAssetContentTypesAddOnly(GatewayConfig gateway, List<ConfigError> errors) {
        AssetDefaultsConfig assetDefaults = gateway.assetDefaults();
        if (assetDefaults == null) {
            return;
        }
        Set<String> builtIn = AssetResponseEnvelope.builtInExtensions();
        for (String extension : assetDefaults.contentTypes().keySet()) {
            if (builtIn.contains(extension)) {
                errors.add(new ConfigError(GATEWAY_FILE, ASSET_DEFAULTS_CONTENT_TYPES_POINTER,
                        ("asset_defaults.content_types declares '%s', which the gateway already maps; "
                                + "the built-in content-type mappings are immutable and the block is "
                                + "add-only — declare only extensions the gateway does not map, and "
                                + "remove this entry")
                                .formatted(extension)));
            }
        }
    }

    /**
     * Enforces that every {@code asset_defaults.content_types} <em>value</em> is a well-formed
     * {@code type/subtype} media type, refusing the entry at boot otherwise.
     * <p>
     * The value half needs its own bound because it reaches further than the key half does: the
     * declared value is what {@code AssetResponseEnvelope.governedHeaders} puts under
     * {@code Content-Type} and {@code GatewayEdgeRoute} then applies verbatim as a response-header
     * value. Without this rule a value carrying CR/LF — or any non-media-type text at all — is
     * accepted at boot and only misbehaves per request, deep inside the response write where the
     * transport's own header-value validation throws after the status code is already set. That is
     * the exact inversion of the fail-fast startup-validation posture the rest of the validator holds,
     * and it is asymmetric with the key half, which IS boot-refused.
     * <p>
     * This is a <strong>well-formedness</strong> gate, not a policy gate: it does not rank media
     * types by safety and carries no allow-list of "safe" ones. The policy bound already exists one
     * rule up — {@link #validateAssetContentTypesAddOnly} fences off every built-in,
     * script-executable extension, so the stored-XSS lever cannot be reached through a built-in key
     * whatever this rule permits.
     * <p>
     * The refusal names the offending extension and value, because an operator cannot fix a typo
     * they cannot see. The value is rendered through {@link RuleSupport#renderForMessage} rather than echoed
     * raw: a {@link ConfigError} is emitted into the boot ERROR log, so echoing an unescaped CR/LF
     * would forge log lines (CWE-117) through the very key this rule constrains.
     * <p>
     * Every offending entry is collected rather than failing on the first, per ADR-0009.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared list every violation is appended to
     */
    public static void validateAssetContentTypeValues(GatewayConfig gateway, List<ConfigError> errors) {
        AssetDefaultsConfig assetDefaults = gateway.assetDefaults();
        if (assetDefaults == null) {
            return;
        }
        for (Map.Entry<String, String> entry : assetDefaults.contentTypes().entrySet()) {
            // A null value is structurally impossible: AssetDefaultsConfig hands back a Map.copyOf,
            // which refuses null values at binding time.
            String value = entry.getValue();
            if (!MEDIA_TYPE.matcher(value).matches()) {
                errors.add(new ConfigError(GATEWAY_FILE, ASSET_DEFAULTS_CONTENT_TYPES_POINTER,
                        ("asset_defaults.content_types maps '%s' to '%s', which is not a well-formed media type; "
                                + "the value is served verbatim as the asset's Content-Type response header, so it "
                                + "must be a 'type/subtype' (optionally followed by ';name=value' parameters) and "
                                + "must carry no control characters")
                                .formatted(entry.getKey(), renderForMessage(value))));
            }
        }
    }
}
