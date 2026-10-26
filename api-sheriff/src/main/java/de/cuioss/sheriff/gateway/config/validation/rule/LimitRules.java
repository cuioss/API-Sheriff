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

import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.ENDPOINT_ROUTES_POINTER;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.GATEWAY_FILE;
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;

import java.util.ArrayList;
import java.util.List;

import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.config.PolicyBlock;
import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.RouteTableBuilder;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AccessLevel;
import de.cuioss.sheriff.gateway.config.model.AnchorConfig;
import de.cuioss.sheriff.gateway.config.model.AnchorType;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EdgeHardeningConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.tools.logging.CuiLogger;
import lombok.experimental.UtilityClass;

/**
 * The boot-time {@link ValidationRule}s that bound the gateway's inbound limits: the inbound-filter
 * profile a route may run under, the edge admission budget, the {@code Authorization}
 * header-value carve-out, and the per-route {@code rate_limit} block the gateway does not enforce.
 * <p>
 * The inbound-filter mode refusal ({@link #validateSecurityProfileMinimal}, ADR-0024): a route whose
 * effective {@code profile} resolves to {@code minimal} must be neither effectively authenticated nor
 * anchored under a {@code type: bff} anchor. The {@code profile} <em>value range</em> is owned by the
 * bundled JSON Schema, so no post-binding range rule exists — only this posture refusal.
 * <p>
 * The admission-budget rule ({@link #validateEdgeHardening}) bounds the {@code edge_hardening} block:
 * both caps must admit at least one request, and the WebSocket relay sub-budget must stay within the
 * admission pool it draws from.
 * <p>
 * The carve-out rule ({@link #validateAuthorizationHeaderValueLength}) refuses a declared
 * {@code security_defaults.max_authorization_header_value_length} below the resolved gateway-wide
 * header-value baseline, since the key can only raise the pre-route cap for {@code Authorization}
 * values.
 * <p>
 * The unenforced-limit rule ({@link #validateRateLimitNotEnforced}) refuses nothing: it emits
 * {@link ConfigLogMessages.WARN#RATE_LIMIT_NOT_ENFORCED} for every route that declares the
 * {@code rate_limit} block, because the block is parsed but never enforced.
 * <p>
 * Every refusing rule appends file- and pointer-annotated {@link ConfigError}s to the shared list and
 * never fails fast. Stateless, and free of framework dependencies as the pre-boot rule requires of
 * this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class LimitRules {

    private static final CuiLogger LOGGER = new CuiLogger(LimitRules.class);

    private static final String EDGE_HARDENING_ADMISSION_POINTER = "/edge_hardening/admission_cap";
    private static final String EDGE_HARDENING_WEBSOCKET_POINTER = "/edge_hardening/websocket_relay_cap";
    private static final String SECURITY_DEFAULTS_AUTHORIZATION_POINTER =
            "/security_defaults/max_authorization_header_value_length";

    /**
     * Rule: the fail-closed inbound-filter mode refusal (ADR-0024). A route whose effective
     * {@code profile} resolves to {@link SecurityProfile#MINIMAL} must be neither effectively
     * authenticated nor anchored under a {@code type: bff} anchor. {@code minimal} drops the
     * url-parameter name/value validation, and dropping it in front of a token- or session-bearing
     * surface is never a deliberate posture — so the boot fails rather than serving the weakened
     * route.
     * <p>
     * The access level is derived through {@link RouteTableBuilder#effectiveAccessLevel} — the same
     * seam the route table uses — rather than from {@link AnchorConfig#access()} directly: a route or
     * endpoint may legally <em>strengthen</em> a {@code public}-access anchor's auth floor (ADR-0007
     * forbids weakening it, not strengthening it), and reading the anchor's static {@code access}
     * would under-refuse exactly those routes. The profile is read through
     * {@link RouteTableBuilder#effectiveProfile} over the {@link PolicyBlock#SECURITY_FILTER} block —
     * the seam the boot posture line reports — so the refusal and that line can never disagree. The
     * gateway-wide case needs no separate rule: a
     * {@code security_defaults.profile: minimal} reaching an authenticated or BFF route through the
     * fallback resolves to {@code minimal} here and is refused identically.
     * <p>
     * The message names the route, the refusing dimension and the remedy, and echoes no configured
     * scalar value. Every violation collects into the shared list; the rule never fails fast
     * (ADR-0009).
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared error accumulator
     */
    public static void validateSecurityProfileMinimal(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                ScopeChain chain = ScopeChain.of(gateway, endpoint, route);
                if (RouteTableBuilder.effectiveProfile(PolicyBlock.SECURITY_FILTER.resolve(chain), gateway)
                        != SecurityProfile.MINIMAL) {
                    continue;
                }
                List<String> refusals = minimalRefusalDimensions(chain);
                if (!refusals.isEmpty()) {
                    errors.add(new ConfigError(endpointFile(endpoint), ENDPOINT_ROUTES_POINTER,
                            ("route '%s' resolves inbound-filter profile 'minimal' but %s; 'minimal' is refused on "
                                    + "authenticated and bff routes — declare profile 'strict' or 'lenient' for this "
                                    + "route, or stop routing it through a 'minimal' fallback")
                                    .formatted(route.id(), String.join(" and ", refusals))));
                }
            }
        }
    }

    /**
     * The dimensions on which a {@code profile: minimal} route is refused: an
     * effectively-authenticated access level, a {@code type: bff} anchor, or both. An empty list
     * means the route may legally run under {@code minimal}.
     */
    private static List<String> minimalRefusalDimensions(ScopeChain chain) {
        List<String> refusals = new ArrayList<>();
        AnchorConfig anchor = chain.anchor();
        AuthConfig auth = PolicyBlock.AUTH.declared(chain);
        if (auth != null && RouteTableBuilder.effectiveAccessLevel(anchor, auth) == AccessLevel.AUTHENTICATED) {
            refusals.add("its effective access level is 'authenticated'");
        }
        if (anchor != null && anchor.type() == AnchorType.BFF) {
            refusals.add("its anchor '%s' is type 'bff'".formatted(anchor.name()));
        }
        return refusals;
    }

    /**
     * Bounds the operator-facing admission budget. Both caps must admit at least one request — a cap
     * of zero or below would refuse every request (or every relay) and is always a misconfiguration
     * rather than a deliberate posture. The WebSocket sub-budget must additionally stay within the
     * general pool it draws from: a {@code websocket_relay_cap} above {@code admission_cap} can never
     * bind, so it would silently disable the sub-cap the operator meant to impose.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared error accumulator
     */
    public static void validateEdgeHardening(GatewayConfig gateway, List<ConfigError> errors) {
        EdgeHardeningConfig hardening = gateway.edgeHardening();
        if (hardening == null) {
            return;
        }
        Integer admissionCap = hardening.admissionCap();
        if (admissionCap != null && admissionCap < 1) {
            errors.add(new ConfigError(GATEWAY_FILE, EDGE_HARDENING_ADMISSION_POINTER,
                    "admission_cap must be at least 1 (was %d)".formatted(admissionCap)));
        }
        Integer relayCap = hardening.websocketRelayCap();
        if (relayCap != null && relayCap < 1) {
            errors.add(new ConfigError(GATEWAY_FILE, EDGE_HARDENING_WEBSOCKET_POINTER,
                    "websocket_relay_cap must be at least 1 (was %d)".formatted(relayCap)));
        }
        int admission = hardening.effectiveAdmissionCap();
        int relay = hardening.effectiveWebsocketRelayCap();
        if (admission >= 1 && relay >= 1 && relay > admission) {
            errors.add(new ConfigError(GATEWAY_FILE, EDGE_HARDENING_WEBSOCKET_POINTER,
                    "websocket_relay_cap %d must not exceed admission_cap %d — the relay budget is a "
                            .formatted(relay, admission)
                            + "sub-budget of the admission pool and could never bind"));
        }
    }

    /**
     * Bounds the operator-declared {@code Authorization} header-value carve-out: a
     * <em>declared</em> {@code security_defaults.max_authorization_header_value_length} must not fall
     * below the resolved gateway-wide baseline {@code maxHeaderValueLength}.
     * <p>
     * The key exists to <em>raise</em> the non-skippable pre-route floor's header-value cap for
     * {@code Authorization} values alone (ADR-0019), because a bearer token plus its {@code Bearer }
     * prefix routinely exceeds the {@code strict} preset's cap and would otherwise be rejected before
     * route selection. A value below the baseline is therefore not a carve-out at all but a per-header
     * <em>tightening</em> expressed through the one key that cannot express one, and it would
     * silently make {@code Authorization} the single most-restricted header while reading like a
     * relaxation key. That is always a misconfiguration rather than a deliberate posture, the same
     * shape {@link #validateEdgeHardening} applies to the admission budget.
     * <p>
     * The refusal deliberately offers no downward path, because none exists: neither remedy an
     * operator might reach for can lower an {@code Authorization} value cap. A route's
     * {@code security_filter} cannot, since
     * {@code ThoroughChecksStage.carveOutConfigurationFor} floors the effective cap back up to
     * {@code max(routeConfig.maxHeaderValueLength(), budget)} for the carved-out header name; and
     * {@code security_defaults.profile} cannot either, since the budget resolves through
     * {@link SecurityDefaultsConfig#effectiveMaxAuthorizationHeaderValueLength()} — the declared key
     * or the fixed {@link SecurityDefaultsConfig#DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH} —
     * and does not track the profile at all. Both govern general header values instead.
     * <p>
     * The comparison is genuinely cross-cutting and cannot move into the bundled JSON Schema: the
     * baseline is the <em>resolved</em> profile's preset limit, which the schema cannot see. The
     * schema owns the value range ({@code integer}, {@code minimum: 1}) alone. The baseline is
     * resolved through the same {@link RouteTableBuilder#globalProfile} plus
     * {@link SecurityProfile#limitsProfile} chain the edge performs, so the boot refusal and the
     * runtime cap can never disagree.
     * <p>
     * An omitted key is never refused — it resolves to
     * {@link SecurityDefaultsConfig#DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH}. No upper bound is
     * enforced here: the gateway's 16 KiB inbound header-block transport limit already caps what can
     * arrive, and it is owned by the edge layer — restating it as a second constant in this
     * pre-boot class, which runs before the framework and its edge exist (ADR-0061, ADR-0062), would
     * fork the value.
     *
     * @param gateway the bound gateway document
     * @param errors  the shared error accumulator
     */
    public static void validateAuthorizationHeaderValueLength(GatewayConfig gateway, List<ConfigError> errors) {
        SecurityDefaultsConfig securityDefaults = gateway.securityDefaults();
        Integer declared = securityDefaults == null ? null : securityDefaults.maxAuthorizationHeaderValueLength();
        if (declared == null) {
            return;
        }
        SecurityProfile global = RouteTableBuilder.globalProfile(gateway);
        int baseline = SecurityProfile.limitsProfile(global, global).preset().maxHeaderValueLength();
        if (declared < baseline) {
            errors.add(new ConfigError(GATEWAY_FILE, SECURITY_DEFAULTS_AUTHORIZATION_POINTER,
                    ("max_authorization_header_value_length %d is below the resolved baseline "
                            + "max_header_value_length %d; the key raises the pre-route cap for "
                            + "Authorization values only and can never lower it — declare at least "
                            + "the baseline here, as security_defaults.profile and a route "
                            + "security_filter bound general header values rather than the "
                            + "Authorization carve-out")
                            .formatted(declared, baseline)));
        }
    }

    /**
     * Rule: every route that declares the per-route {@code rate_limit} block is reported with the boot
     * WARN {@link ConfigLogMessages.WARN#RATE_LIMIT_NOT_ENFORCED}, naming the route and its endpoint.
     * <p>
     * Rate limiting is not implemented. The block is parsed and bound into {@code RateLimitConfig},
     * but no request-time component reads it: no rate limit is applied and no request is ever
     * answered {@code 429}. A configuration key that parses but does not act reads like a control the
     * operator has in force, so the gap is announced at every boot instead of being left for the
     * operator to discover under load.
     * <p>
     * The rule refuses nothing and appends no violation, so the boot continues: refusing would break
     * every descriptor that already carries the block, while the WARN keeps the gap visible. It is
     * the same shape as the forwarded-trust breadth WARN of {@code EdgeRules}. A declared block is
     * reported whatever it holds — an empty {@code rate_limit: {}} enforces exactly as little as a
     * populated one. The WARN carries the route and endpoint ids only, never a configured limit value.
     *
     * @param endpoints the enabled endpoints, whose every route's {@code rate_limit} is inspected
     */
    public static void validateRateLimitNotEnforced(List<EndpointConfig> endpoints) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                if (route.rateLimit() != null) {
                    LOGGER.warn(ConfigLogMessages.WARN.RATE_LIMIT_NOT_ENFORCED, route.id(), endpoint.id());
                }
            }
        }
    }
}
