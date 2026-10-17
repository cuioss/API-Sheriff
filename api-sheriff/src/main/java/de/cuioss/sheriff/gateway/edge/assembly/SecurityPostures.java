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
package de.cuioss.sheriff.gateway.edge.assembly;

import java.util.Set;

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.config.SecurityConfigurationBuilder;
import de.cuioss.sheriff.gateway.bff.cookie.SealedSessionCookieCodec;
import de.cuioss.sheriff.gateway.bff.runtime.BffRuntime;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.OidcConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityConfigurations;
import de.cuioss.sheriff.gateway.config.model.SecurityDefaultsConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityFilterConfig;
import de.cuioss.sheriff.gateway.config.model.SecurityProfile;
import de.cuioss.sheriff.gateway.pipeline.BasicChecksStage;
import de.cuioss.sheriff.gateway.pipeline.ThoroughChecksStage;
import org.jspecify.annotations.Nullable;

/**
 * Resolves the inbound-filter policies the edge measures every request against, once, at boot: a
 * route's {@linkplain #securityPostureFor security posture}, and the two gateway-wide header-value
 * carve-outs for the {@linkplain #cookieHeaderConfigurationFor Cookie} and the
 * {@linkplain #authorizationHeaderConfigurationFor Authorization} header.
 * <p>
 * Stateless; every method is a pure function of its arguments and safe to call from any thread.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class SecurityPostures {

    /**
     * Headroom added to the configured sealed-cookie budget when deriving the pre-route
     * {@code Cookie} header-value cap. The header value carries {@code <name>=<value>} and may carry
     * co-resident cookies alongside the session cookie — the short-lived binding cookie and, in cookie
     * mode, the activity cookie that remembers the session's last access — so the cap cannot be the
     * bare value budget. The headroom is shared by every cookie the browser sends beside the session
     * cookie, the proxied applications' own included. The sum stays far below the gateway's 16 KiB inbound
     * header-block limit ({@link de.cuioss.sheriff.gateway.edge.admission.EdgeHardeningOptions}) even at
     * the configurable budget ceiling.
     */
    public static final int COOKIE_HEADER_OVERHEAD_BYTES = 512;

    private SecurityPostures() {
        // static resolvers only
    }

    /**
     * Derives the pre-route {@code Cookie} header-value policy from the gateway document, returning
     * {@code null} for every gateway that is not an active cookie-mode BFF.
     * <p>
     * <strong>Why this exists.</strong> Stage 1 validates every inbound header value against the
     * resolved baseline {@code maxHeaderValueLength} — whatever {@code security_defaults.profile}
     * resolves to, 1024 characters under {@code strict} and 8192 under {@code lenient} — while a
     * cookie-mode BFF's sealed session cookie is designed to a multi-kilobyte budget. Encoded as two
     * independent constants, the two limits contradicted each other inside the same product: every
     * request carrying a live sealed cookie was rejected {@code 400} at the edge before any BFF logic
     * ran. The single declared budget ({@code oidc.session.max_cookie_size}, defaulting to
     * {@link SealedSessionCookieCodec#DEFAULT_COOKIE_VALUE_BUDGET}) now drives BOTH ends of the round
     * trip — the codec's seal-time budget and this pre-route cap.
     * <p>
     * <strong>The relaxation is scoped twice.</strong> By MODE: a bearer-only or server-mode gateway
     * gets {@code null}, so no cookie carve-out exists there and its {@code Cookie} /
     * {@code Set-Cookie} values are measured at the resolved baseline like any other header. By
     * HEADER: within a cookie-mode gateway the raised cap reaches the {@code Cookie} /
     * {@code Set-Cookie} values only. {@link BasicChecksStage} selects the value pipeline on the
     * header NAME across three branches — an {@code Authorization} value takes the separate,
     * unconditional carve-out of {@link #authorizationHeaderConfigurationFor}, and every OTHER header
     * keeps the resolved baseline policy. Every non-length validator (null-byte, control-character,
     * injection-pattern) still applies to the cookie value. It remains a deliberate relaxation of an
     * inbound hardening control, held as narrow as the pre-route position allows.
     * <p>
     * <strong>The cap is {@code max}-bounded, so the carve-out can only ever ADMIT MORE length.</strong>
     * The effective cap is {@code max(baseline.maxHeaderValueLength(), budget + }{@value #COOKIE_HEADER_OVERHEAD_BYTES}{@code )}.
     * Setting it outright from the budget made the carve-out LOWER the cap on a {@code lenient}
     * gateway: the shipped default budget
     * ({@link SealedSessionCookieCodec#DEFAULT_COOKIE_VALUE_BUDGET}, 4019) plus the overhead is 4531,
     * below that profile's 8192 baseline, so a cookie-mode BFF rejected {@code 400} every request
     * whose {@code Cookie} value sat between the two — while every OTHER header on the same gateway
     * was admitted to 8192. No operator misconfiguration was required. The {@code max} restores
     * ADR-0019's stated bound, "the direction of change is admit-more-length-only", and matches the
     * two sibling sites: {@code ThoroughChecksStage.carveOutConfigurationFor} caps at
     * {@code max(routeCap, budget)} post-route, and {@code ConfigValidator} refuses a below-baseline
     * {@code Authorization} budget outright at boot.
     * <p>
     * The returned policy is handed to {@link ThoroughChecksStage} as well as to
     * {@link BasicChecksStage}: the post-route stage re-validates every header under the ROUTE's
     * configuration whenever that configuration diverges from the baseline, so it needs the same
     * budget or it would undo this carve-out one stage later. Only the policy's
     * {@code maxHeaderValueLength} is read there, as the budget; the remaining dimensions applied
     * post-route come from the route's own configuration.
     * <p>
     * <strong>Recorded constraint — enforcement is gateway-wide, not per-anchor.</strong> Stage 1
     * runs BEFORE route selection and the edge holds the whole route table, so no anchor exists at
     * that point. The configuration key lives on the global {@code oidc.session} block for exactly
     * that reason; its placement must not be read as per-anchor enforcement.
     * <p>
     * Public so the edge root wires it and {@code GatewayEdgeRouteTest} asserts the seeding directly
     * instead of only through a booted edge, matching {@link #securityPostureFor}.
     *
     * @param gatewayConfig the bound gateway document supplying the cookie budget
     * @param bffRuntime    the BFF runtime whose active-cookie-mode state gates the carve-out
     * @param baseline      the gateway's resolved baseline configuration the returned policy is
     *                      seeded from, so it differs from the baseline in
     *                      {@code maxHeaderValueLength} and nothing else
     * @return the {@code Cookie} / {@code Set-Cookie} value policy, or {@code null} for a gateway
     *         that is not an active cookie-mode BFF
     */
    public static @Nullable SecurityConfiguration cookieHeaderConfigurationFor(GatewayConfig gatewayConfig,
            BffRuntime bffRuntime, SecurityConfiguration baseline) {
        OidcConfig oidc = gatewayConfig.oidc();
        OidcConfig.Session session = oidc == null ? null : oidc.session();
        // The SHARED cookie-mode predicate on the config model — never a locally-declared constant
        // compared here. A private constant plus a local comparison is what let this cap relax for a
        // mode spelling boot validation had already skipped.
        boolean cookieMode = bffRuntime.isActive() && session != null && session.isCookieMode();
        if (!cookieMode) {
            return null;
        }
        Integer declaredBudget = session.maxCookieSize();
        int budget = declaredBudget != null ? declaredBudget : SealedSessionCookieCodec.DEFAULT_COOKIE_VALUE_BUDGET;
        // max(), not an outright set: a carve-out may only ever ADMIT MORE length. The default budget
        // plus the overhead is 4531, which sits BELOW the lenient profile's 8192 baseline, so setting
        // the cap outright turned this carve-out into a per-header TIGHTENING on every shipped-default
        // lenient cookie-mode gateway. Same bound as ThoroughChecksStage.carveOutConfigurationFor.
        int cap = Math.max(baseline.maxHeaderValueLength(), budget + COOKIE_HEADER_OVERHEAD_BYTES);
        // Seeded component-by-component from the RESOLVED baseline, so this differs from it in
        // maxHeaderValueLength and nothing else — which is what makes ADR-0019's "only the length cap
        // changes" bound structurally true. Seeding from SecurityConfiguration.builder() instead (its
        // defaults being the dropped `default` preset) silently relaxed failOnSuspiciousPatterns,
        // allowExtendedAscii and caseSensitiveComparison for cookie values on a strict gateway.
        return SecurityConfigurations.builderSeededFrom(baseline)
                .maxHeaderValueLength(cap)
                .build();
    }

    /**
     * Derives the pre-route {@code Authorization} header-value policy from the gateway document.
     * <p>
     * <strong>Why this exists.</strong> A bearer token plus its {@code Bearer } prefix routinely
     * exceeds the resolved baseline {@code maxHeaderValueLength} — a Keycloak access token measures
     * above the {@code strict} preset's 1024-character cap — so without a raised cap Stage 1, the
     * non-skippable pre-route floor, rejects every bearer request {@code 400} before route selection
     * and bearer validation never runs at all.
     * <p>
     * <strong>The relaxation is scoped, but unconditionally present.</strong> By HEADER: the raised
     * cap reaches {@code Authorization} values only. By VALIDATOR: the returned configuration is
     * seeded from {@code baseline} and overrides {@code maxHeaderValueLength} alone, so every
     * non-length validator still applies. Unlike the cookie carve-out there is no MODE axis — every
     * gateway is bearer-capable, so this policy is always built and never {@code null}. That axis is
     * genuinely weaker than the cookie carve-out's, not equivalent to it.
     * <p>
     * As with the cookie carve-out, the returned policy is handed to {@link ThoroughChecksStage} as
     * well as to {@link BasicChecksStage}, so the raised cap survives the post-route re-run a route
     * with a divergent {@code security_filter} triggers — a route declaring nothing more than a
     * raised {@code max_body_bytes} already diverges. Without that, the floor would admit a bearer
     * token and the very next stage would reject it, still ahead of bearer validation.
     * <p>
     * The budget comes from the operator-declared {@code security_defaults.max_authorization_header_value_length},
     * defaulting to {@link SecurityDefaultsConfig#DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH}. It
     * sits on the gateway-wide {@code security_defaults} block because Stage 1 runs before route
     * selection, so no anchor exists at that point; a declared value below the resolved baseline is
     * refused at boot by {@code ConfigValidator}.
     * <p>
     * Public for the same reason as {@link #cookieHeaderConfigurationFor}.
     *
     * @param gatewayConfig the bound gateway document supplying the declared budget
     * @param baseline      the gateway's resolved baseline configuration the returned policy is
     *                      seeded from
     * @return the {@code Authorization} value policy, differing from {@code baseline} in
     *         {@code maxHeaderValueLength} alone
     */
    public static SecurityConfiguration authorizationHeaderConfigurationFor(GatewayConfig gatewayConfig,
            SecurityConfiguration baseline) {
        SecurityDefaultsConfig securityDefaults = gatewayConfig.securityDefaults();
        int budget = securityDefaults != null
                ? securityDefaults.effectiveMaxAuthorizationHeaderValueLength()
                : SecurityDefaultsConfig.DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH;
        return SecurityConfigurations.builderSeededFrom(baseline)
                .maxHeaderValueLength(budget)
                .build();
    }

    /**
     * Resolves a route's inbound-filter posture: the effective {@link SecurityProfile} after the
     * {@code security_filter → security_defaults} fallback, plus the cui-http
     * {@link SecurityConfiguration} carrying its limits.
     * <p>
     * The limits are seeded from the <em>preset</em> of the nearest non-{@code minimal} profile in the
     * chain (see {@link SecurityProfile#limitsProfile}), never from bare builder defaults, and only
     * the dimensions the route actually declared are overridden on top — so an undeclared dimension
     * lands exactly on the resolved preset rather than below it. A {@code minimal} route therefore
     * still carries a concrete, enforceable {@code maxBodySize}. The gateway-wide
     * {@code security_defaults.allow_extended_ascii} override is applied to that preset before the
     * route's own limits, exactly as it is applied to the baseline.
     * <p>
     * Invoked for every route, including one that declares no {@code security_filter} block at all,
     * which is what lets a gateway-wide {@code profile} govern a block-less route.
     * <p>
     * Public so the edge root hands it to the {@link RouteAssembler} as its
     * {@link SecurityConfigurationFactory}, and so the posture resolution — the fallback chain and the
     * preset-seeded limit overrides — is asserted directly by {@code GatewayEdgeRouteTest} instead
     * of only through a booted edge, whose assembled routes are not observable.
     *
     * @param filter             the route's declared {@code security_filter} block, {@code null} when
     *                           it declares none
     * @param globalProfile      the resolved gateway-wide {@code security_defaults} profile
     * @param allowExtendedAscii the gateway-wide {@code security_defaults.allow_extended_ascii}
     *                           override, {@code null} when undeclared
     * @return the route's resolved posture
     */
    public static SecurityPosture securityPostureFor(
            @Nullable SecurityFilterConfig filter, SecurityProfile globalProfile,
            @Nullable Boolean allowExtendedAscii) {
        SecurityProfile effective = SecurityProfile.parse(filter == null ? null : filter.profile())
                .orElse(globalProfile);
        SecurityConfiguration preset = SecurityConfigurations.withDeclaredCharacterPolicy(
                SecurityProfile.limitsProfile(effective, globalProfile).preset(), allowExtendedAscii);
        return new SecurityPosture(effective,
                filter == null ? preset : applyDeclaredLimits(preset, filter));
    }

    /**
     * Applies a route's declared {@code security_filter} limits on top of the resolved preset. The
     * builder is seeded component-by-component from {@code preset} because the cui-http
     * {@link SecurityConfiguration} exposes no preset-seeded builder — {@link SecurityConfiguration#builder()}
     * starts from {@code defaults()}, so seeding explicitly is the only way an undeclared dimension
     * keeps the preset's value instead of silently reverting to the default policy. The component
     * list lives once, in {@link SecurityConfigurations#builderSeededFrom(SecurityConfiguration)}.
     */
    private static SecurityConfiguration applyDeclaredLimits(SecurityConfiguration preset,
            SecurityFilterConfig filter) {
        SecurityConfigurationBuilder builder = SecurityConfigurations.builderSeededFrom(preset);
        Integer maxBodyBytes = filter.maxBodyBytes();
        if (maxBodyBytes != null) {
            builder.maxBodySize(maxBodyBytes.longValue());
        }
        Integer maxQueryParams = filter.maxQueryParams();
        if (maxQueryParams != null) {
            builder.maxParameterCount(maxQueryParams);
        }
        Integer maxHeaderCount = filter.maxHeaderCount();
        if (maxHeaderCount != null) {
            builder.maxHeaderCount(maxHeaderCount);
        }
        Integer maxParamValueLength = filter.maxParamValueLength();
        if (maxParamValueLength != null) {
            builder.maxParameterValueLength(maxParamValueLength);
        }
        Integer maxHeaderValueLength = filter.maxHeaderValueLength();
        if (maxHeaderValueLength != null) {
            builder.maxHeaderValueLength(maxHeaderValueLength);
        }
        if (!filter.allowedHeaderNames().isEmpty()) {
            builder.allowedHeaderNames(Set.copyOf(filter.allowedHeaderNames()));
        }
        if (!filter.blockedHeaderNames().isEmpty()) {
            builder.blockedHeaderNames(Set.copyOf(filter.blockedHeaderNames()));
        }
        if (!filter.allowedContentTypes().isEmpty()) {
            builder.allowedContentTypes(Set.copyOf(filter.allowedContentTypes()));
        }
        return builder.build();
    }
}
