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

import de.cuioss.http.security.config.SecurityConfiguration;
import de.cuioss.http.security.config.SecurityConfigurationBuilder;

import lombok.experimental.UtilityClass;

/**
 * The single seeding seam for the cui-http {@link SecurityConfiguration}: the one place in this
 * project that enumerates the record's component set when copying a policy.
 * <p>
 * <strong>Why a shared class rather than a private helper.</strong> {@link SecurityConfiguration#builder()}
 * starts from {@code defaults()} — the dropped {@code default} preset — so a caller that overrides one
 * dimension and omits the rest silently reverts every other dimension to that policy. Overriding a
 * single dimension safely therefore requires copying <em>all</em> components first. That copy is
 * needed in two packages: the {@code edge} package resolves a route's declared
 * {@code security_filter} limits and the two pre-route header-value carve-outs, and the
 * {@code pipeline} package derives the matching post-route carve-outs from the route's own policy. A
 * second, hand-written component list is exactly the defect class this seam exists to prevent — an
 * omitted component is invisible at the call site and shows up only as a validator that quietly
 * stopped applying.
 * <p>
 * Framework-agnostic (ADR-0005): the class touches the cui-http configuration API only.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class SecurityConfigurations {

    /**
     * Returns a {@link SecurityConfigurationBuilder} carrying every component of {@code preset}, so a
     * caller overriding one dimension leaves the other twenty-three on the preset's values. Copies the
     * record's full component set deliberately: an omitted component would silently fall back to the
     * {@code defaults()} policy the builder starts from.
     *
     * @param preset the policy every component is copied from; never {@code null}
     * @return a builder pre-loaded with {@code preset}'s complete component set
     */
    public static SecurityConfigurationBuilder builderSeededFrom(SecurityConfiguration preset) {
        return SecurityConfiguration.builder()
                .maxPathLength(preset.maxPathLength())
                .allowDoubleEncoding(preset.allowDoubleEncoding())
                .maxParameterNameLength(preset.maxParameterNameLength())
                .maxParameterValueLength(preset.maxParameterValueLength())
                .maxHeaderNameLength(preset.maxHeaderNameLength())
                .maxHeaderValueLength(preset.maxHeaderValueLength())
                .maxCookieNameLength(preset.maxCookieNameLength())
                .maxCookieValueLength(preset.maxCookieValueLength())
                .maxBodySize(preset.maxBodySize())
                .allowNullBytes(preset.allowNullBytes())
                .allowControlCharacters(preset.allowControlCharacters())
                .allowExtendedAscii(preset.allowExtendedAscii())
                .normalizeUnicode(preset.normalizeUnicode())
                .caseSensitiveComparison(preset.caseSensitiveComparison())
                .failOnSuspiciousPatterns(preset.failOnSuspiciousPatterns())
                .requireSecureCookies(preset.requireSecureCookies())
                .requireHttpOnlyCookies(preset.requireHttpOnlyCookies())
                .maxParameterCount(preset.maxParameterCount())
                .maxHeaderCount(preset.maxHeaderCount())
                .maxCookieCount(preset.maxCookieCount())
                .allowedHeaderNames(preset.allowedHeaderNames())
                .blockedHeaderNames(preset.blockedHeaderNames())
                .allowedContentTypes(preset.allowedContentTypes())
                .blockedContentTypes(preset.blockedContentTypes());
    }
}
