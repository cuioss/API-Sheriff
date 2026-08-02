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

import org.jspecify.annotations.Nullable;

/**
 * The global {@code security_defaults} block of {@code gateway.yaml}.
 * <p>
 * {@code profile} selects the gateway-wide inbound-filter mode — {@code strict} /
 * {@code lenient} / {@code minimal}, see {@link SecurityProfile}. The value range is enforced by the
 * bundled JSON Schema (an unrecognized value, the dropped {@code default} preset included, fails
 * boot there), not by this model and not by the configuration validator.
 * <p>
 * <strong>Fallback.</strong> Every route resolves its effective profile through
 * {@code route security_filter → anchor security_filter → security_defaults} — the endpoint level
 * carries no {@code security_filter} block, so it is not part of the chain. A route that declares no
 * {@code security_filter} — or one whose block omits {@code profile} — therefore inherits the value
 * carried here, and an entirely omitted {@code security_defaults} block resolves to
 * {@link SecurityProfile#DEFAULT_PROFILE}.
 * <p>
 * <strong>{@code max_authorization_header_value_length}.</strong> The budget backing the pre-route
 * {@code Authorization} header-value carve-out. A bearer token is routinely larger than the resolved
 * preset's {@code maxHeaderValueLength} — a Keycloak access token plus the {@code Bearer } prefix
 * measures ~1028 characters against the {@code strict} preset's 1024 — so without a raised cap the
 * non-skippable pre-route floor rejects every bearer request {@code 400} before route selection, and
 * stage-4 bearer validation never runs. The value is operator-declared rather than a fixed constant
 * because the right budget is a property of the deployment's identity provider, not of the gateway.
 * It is boot-validated: a declared value below the resolved baseline {@code maxHeaderValueLength}
 * would make the "carve-out" a tightening in disguise and is refused.
 *
 * <p>
 * <strong>{@code allow_get_with_content_length_body}.</strong> The gateway-wide opt-in admitting a
 * {@code Content-Length}-framed body on {@code GET}. It exists for upstreams whose API genuinely
 * takes a request body on {@code GET} — Elasticsearch's {@code _search} being the motivating case.
 * It is a <em>partial</em> relaxation of the framing gate, never a disable: see
 * {@link #effectiveAllowGetWithContentLengthBody()} for the exact boundary.
 *
 * @param profile the baseline security-filter profile, {@code null} when omitted
 * @param maxAuthorizationHeaderValueLength the {@code Authorization} header-value cap, {@code null}
 *                                          when omitted — resolve it through
 *                                          {@link #effectiveMaxAuthorizationHeaderValueLength()}
 *                                          rather than reading this component directly
 * @param allowGetWithContentLengthBody whether a {@code Content-Length}-framed body is admitted on
 *                                      {@code GET}, {@code null} when omitted — resolve it through
 *                                      {@link #effectiveAllowGetWithContentLengthBody()} rather than
 *                                      reading this component directly
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
public record SecurityDefaultsConfig(@Nullable String profile, @Nullable Integer maxAuthorizationHeaderValueLength,
@Nullable Boolean allowGetWithContentLengthBody) {

    /**
     * The {@code Authorization} header-value cap an omitted
     * {@code max_authorization_header_value_length} resolves to.
     * <p>
     * The number is the {@code lenient} preset's own {@code maxHeaderValueLength}, chosen so the
     * carve-out never admits a header value the loosest <em>shipped</em> profile would itself reject
     * — the relaxation stays inside the product's existing envelope rather than inventing a wider
     * one. It also sits comfortably inside the gateway's 16 KiB inbound header-block transport
     * bound, so a value that clears this cap can still actually arrive; a larger default would
     * promise headroom the transport never delivers. It remains a bounded cap, never an exemption.
     */
    public static final int DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH = 8192;

    /**
     * Resolves the {@code Authorization} header-value cap actually enforced at the pre-route floor.
     *
     * @return the declared {@code max_authorization_header_value_length}, or
     *         {@link #DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH} when the key is omitted
     */
    public int effectiveMaxAuthorizationHeaderValueLength() {
        return maxAuthorizationHeaderValueLength != null
                ? maxAuthorizationHeaderValueLength
                : DEFAULT_MAX_AUTHORIZATION_HEADER_VALUE_LENGTH;
    }

    /**
     * Resolves whether the framing gate admits a {@code Content-Length}-framed body on {@code GET}.
     * <p>
     * <strong>The relaxation is partial and its boundary is deliberate.</strong> When enabled, exactly
     * two rejection legs are skipped, and only for {@code GET}: the body-present leg and the declared
     * {@code Content-Length > 0} leg. Everything else the framing gate enforces is untouched:
     * <ul>
     *   <li>{@code Transfer-Encoding} on {@code GET} stays rejected <em>unconditionally</em>. Chunked
     *       framing on an otherwise-bodyless method is precisely the shape the request-smuggling
     *       defences exist to constrain, and it costs the motivating use case nothing — an
     *       Elasticsearch {@code _search} client sends a {@code Content-Length}-framed body, never a
     *       chunked one.</li>
     *   <li>{@code HEAD} is unaffected on every leg — a body on {@code HEAD} is still rejected.</li>
     *   <li>The CL+TE coexistence rule and the {@code Connection}-header framing-strip rule are
     *       separate checks that run independently, so a {@code GET} carrying both
     *       {@code Content-Length} and {@code Transfer-Encoding} is still rejected before this leg is
     *       reached.</li>
     * </ul>
     * The knob is gateway-wide rather than per-route because the framing gate runs at stage 1, before
     * route selection — there is no route resolved yet to scope it to.
     *
     * @return the declared {@code allow_get_with_content_length_body}, or {@code false} when the key
     *         is omitted — the secure default, matching the behaviour of a gateway that never
     *         declared it
     */
    public boolean effectiveAllowGetWithContentLengthBody() {
        return allowGetWithContentLengthBody != null && allowGetWithContentLengthBody;
    }
}
