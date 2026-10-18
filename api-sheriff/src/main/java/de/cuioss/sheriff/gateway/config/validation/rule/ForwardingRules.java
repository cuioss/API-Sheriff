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
import static de.cuioss.sheriff.gateway.config.validation.rule.RuleSupport.endpointFile;

import java.util.List;

import de.cuioss.sheriff.gateway.config.PolicyBlock;
import de.cuioss.sheriff.gateway.config.PolicyBlock.ScopeChain;
import de.cuioss.sheriff.gateway.config.load.ConfigError;
import de.cuioss.sheriff.gateway.config.model.AuthConfig;
import de.cuioss.sheriff.gateway.config.model.EndpointConfig;
import de.cuioss.sheriff.gateway.config.model.ForwardConfig;
import de.cuioss.sheriff.gateway.config.model.GatewayConfig;
import de.cuioss.sheriff.gateway.config.model.RouteConfig;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.Nullable;

/**
 * The boot-time {@link ValidationRule}s that judge the {@code forward} block: which inbound headers
 * and query parameters a route hands on to its upstream.
 * <p>
 * The forward-mode exclusivity rule ({@link #validateForwardModeExclusivity}) refuses an endpoint or
 * route declaring both {@code *_allow} and {@code *_deny} for the same {@code forward} dimension,
 * because each dimension resolves exactly one of the three modes (positive-list, negative-list,
 * forward-all). The token-relay conflict rule ({@link #validateTokenRelayForwardConflict}) refuses a
 * route resolving {@code auth.token_relay: false} whose effective {@code forward.headers_allow}
 * re-admits {@code Authorization}.
 * <p>
 * Policy blocks are resolved only through the {@link PolicyBlock} descriptors over
 * {@link ScopeChain#of}, the same resolution the route table uses. Every rule appends file- and
 * pointer-annotated {@link ConfigError}s to the shared list and never fails fast. Stateless, and
 * therefore safe for concurrent use. Free of framework dependencies as the pre-boot rule requires of
 * this package (ADR-0061, ADR-0062).
 *
 * @author API Sheriff Team
 * @since 1.0
 */
@UtilityClass
public class ForwardingRules {

    private static final String ENDPOINT_FORWARD_POINTER = "/endpoint/forward";

    /** The request header a {@code token_relay: false} route must not re-admit through {@code headers_allow}. */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * Rule: neither an endpoint nor a route may declare both the positive-list and the negative-list
     * for the same {@code forward} dimension.
     * <p>
     * Each dimension — headers, query — resolves <em>exactly one</em> of three modes: a positive-list
     * when {@code *_allow} is declared, a negative-list when {@code *_deny} is declared, and
     * forward-all when neither is. Declaring both expresses no coherent posture, and any precedence
     * the gateway silently picked would be a guess about which one the operator meant. The two are
     * therefore refused together at boot rather than reconciled at request time.
     * <p>
     * The bound is genuinely cross-cutting and cannot move into the bundled JSON Schema: the schema's
     * {@code additionalProperties: false} bounds the <em>key set</em> of the {@code forward} object,
     * but a mutual exclusion between two of its optional keys belongs with the other post-binding
     * rules. The block is declarable at every scope {@link PolicyBlock#FORWARD} names — endpoint and
     * route — and is checked at each of them, so an endpoint-level block that pairs the two lists is
     * refused exactly like a route-level one (a block declared at a scope this rule did not read would
     * reach the runtime unrefused). Both dimensions of every declaration are checked and every
     * violation is collected rather than failing on the first, per ADR-0009 — the operator sees the
     * whole list in one boot attempt.
     *
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateForwardModeExclusivity(List<EndpointConfig> endpoints, List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            checkForwardModes(endpoint, endpoint.forward(), "endpoint '%s'".formatted(endpoint.id()),
                    ENDPOINT_FORWARD_POINTER, errors);
            for (RouteConfig route : endpoint.routes()) {
                checkForwardModes(endpoint, route.forward(), "route '%s'".formatted(route.id()),
                        ENDPOINT_ROUTES_POINTER, errors);
            }
        }
    }

    /**
     * Checks both dimensions of one declared {@code forward} block; an undeclared block has nothing to
     * check.
     */
    private static void checkForwardModes(EndpointConfig endpoint, @Nullable ForwardConfig forward, String subject,
            String pointer, List<ConfigError> errors) {
        if (forward == null) {
            return;
        }
        checkForwardDimension(endpoint, subject, pointer, "headers", forward.headersAllow(), forward.headersDeny(),
                errors);
        checkForwardDimension(endpoint, subject, pointer, "query", forward.queryAllow(), forward.queryDeny(), errors);
    }

    /**
     * Refuses one {@code forward} dimension that declares both of its mutually exclusive lists. A
     * <em>declared empty</em> list counts as declared: {@code headers_allow: []} is a positive-list
     * admitting nothing, not an omission, so pairing it with a {@code headers_deny} is the same
     * contradiction as pairing two populated lists.
     */
    private static void checkForwardDimension(EndpointConfig endpoint, String subject, String pointer,
            String dimension, @Nullable List<String> allow, @Nullable List<String> deny, List<ConfigError> errors) {
        if (allow == null || deny == null) {
            return;
        }
        String allowKey = dimension + "_allow";
        String denyKey = dimension + "_deny";
        errors.add(new ConfigError(endpointFile(endpoint), pointer,
                ("%s declares both %s and %s; the %s forward dimension resolves exactly one "
                        + "mode, so the positive-list and the negative-list are mutually exclusive — "
                        + "declare %s for a positive-list, %s for a negative-list, or neither for the "
                        + "forward-all baseline")
                        .formatted(subject, allowKey, denyKey, dimension, allowKey, denyKey)));
    }

    /**
     * Rule: a route whose effective {@code auth.token_relay} is {@code false} must not name
     * {@code Authorization} (in any letter case) in its {@code forward.headers_allow}.
     * <p>
     * {@code token_relay: false} states that no access token reaches this route's upstream, while
     * naming {@code Authorization} in {@code headers_allow} is the one way an inbound
     * {@code Authorization} header is re-admitted past the gateway-owned never-forward set. The pair
     * therefore declares two opposite postures for the same header, and whichever one the gateway
     * silently honoured would be a guess about the operator's intent — so the boot refuses it. The
     * effective auth and the effective {@code forward} block are resolved through
     * {@link PolicyBlock#AUTH} and {@link PolicyBlock#FORWARD}, the descriptors the route table
     * resolves through, so an {@code Authorization} re-admitted by an endpoint-level {@code forward}
     * block is refused exactly like a route-level one, and a route-level block that replaces it
     * without {@code Authorization} is not. The refusal points at the scope that declared the
     * offending block. The message names the route and the two keys, never a header value.
     * Every violation collects into the shared list; the rule never fails fast (ADR-0009).
     *
     * @param gateway   the bound gateway document
     * @param endpoints the enabled endpoints
     * @param errors    the shared list every violation is appended to
     */
    public static void validateTokenRelayForwardConflict(GatewayConfig gateway, List<EndpointConfig> endpoints,
            List<ConfigError> errors) {
        for (EndpointConfig endpoint : endpoints) {
            for (RouteConfig route : endpoint.routes()) {
                ScopeChain chain = ScopeChain.of(gateway, endpoint, route);
                AuthConfig auth = PolicyBlock.AUTH.declared(chain);
                List<String> headersAllow = PolicyBlock.FORWARD.resolve(chain).headersAllow();
                if (auth == null || auth.effectiveTokenRelay() || headersAllow == null) {
                    continue;
                }
                if (headersAllow.stream().anyMatch(AUTHORIZATION_HEADER::equalsIgnoreCase)) {
                    String pointer = PolicyBlock.isDeclared(route.forward()) ? ENDPOINT_ROUTES_POINTER
                            : ENDPOINT_FORWARD_POINTER;
                    errors.add(new ConfigError(endpointFile(endpoint), pointer,
                            ("route '%s' resolves auth.token_relay: false but its forward.headers_allow names "
                                    + "Authorization; token_relay: false keeps every access token away from the "
                                    + "upstream, so re-admitting the Authorization header contradicts it — drop "
                                    + "Authorization from headers_allow, or drop token_relay: false")
                                    .formatted(route.id())));
                }
            }
        }
    }
}
