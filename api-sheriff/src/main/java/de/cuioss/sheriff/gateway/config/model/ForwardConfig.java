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
package de.cuioss.sheriff.gateway.config.model;

import java.util.List;
import java.util.Map;


import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * The per-route {@code forward} block: a <strong>three-mode filter</strong> deciding which client
 * headers and query parameters cross to the upstream <em>inside a route the request is already
 * authorised for</em>.
 * <p>
 * Deny-by-default lives at the URL layer, not here: a request matching no route reaches no upstream
 * at all. Within a matched route this block chooses one posture per dimension (headers, query),
 * independently:
 * <ul>
 *   <li><strong>Positive-list</strong> — {@code headers_allow} / {@code query_allow} declared: only
 *       the named entries cross.</li>
 *   <li><strong>Negative-list</strong> — {@code headers_deny} / {@code query_deny} declared:
 *       everything crosses except the named entries.</li>
 *   <li><strong>Forward-all</strong> — neither declared for that dimension: every client entry
 *       crosses.</li>
 * </ul>
 * Declaring <em>both</em> lists for one dimension is refused at boot by the configuration validator;
 * the two are mutually exclusive, not composable.
 * <p>
 * <strong>Absent is not empty.</strong> The four list components are {@link Nullable}: {@code null}
 * means the operator declared no list for that dimension (so the mode is forward-all or, when the
 * opposite list is declared, that list's mode), while a declared empty list means "this list exists
 * and names nothing" — a positive-list that admits nothing, or a negative-list that denies nothing.
 * The canonical constructor therefore does <em>not</em> normalize {@code null} to
 * {@link List#of()}: doing so would erase the distinction the three modes are built on. Absence is
 * modelled as a {@code @Nullable} type rather than a stored {@code Optional} per ADR-0033.
 * <p>
 * The gateway-owned sets are <em>not</em> configured here and no mode can re-admit them wholesale:
 * the regenerated forwarding-header set is always skipped on the client copy, and the mediated
 * {@code Authorization} is injected last. {@code set_headers} adds static, gateway-controlled
 * headers and is unaffected by the mode.
 *
 * @param headersAllow the positive-list of forwardable request-header names, {@code null} when the
 *                     dimension declares no positive-list
 * @param headersDeny  the negative-list of withheld request-header names, {@code null} when the
 *                     dimension declares no negative-list
 * @param queryAllow   the positive-list of forwardable query-parameter names, {@code null} when the
 *                     dimension declares no positive-list
 * @param queryDeny    the negative-list of withheld query-parameter names, {@code null} when the
 *                     dimension declares no negative-list
 * @param setHeaders   static gateway-set headers, empty when none
 * @author API Sheriff Team
 * @since 1.0
 */
// cui-rewrite:disable AnnotationNewlineFormat
@Builder
public record ForwardConfig(
@Nullable List<String> headersAllow,
@Nullable List<String> headersDeny,
@Nullable List<String> queryAllow,
@Nullable List<String> queryDeny,
Map<String, String> setHeaders) {

    /**
     * Canonical constructor defensively copying every declared collection into an unmodifiable copy.
     * <p>
     * An absent list stays {@code null} — deliberately <em>not</em> normalized to
     * {@link List#of()} — because absent and declared-empty select different forward modes. Only
     * {@code setHeaders}, which carries no mode semantics, keeps the absent-means-empty
     * normalization.
     */
    public ForwardConfig {
        headersAllow = headersAllow == null ? null : List.copyOf(headersAllow);
        headersDeny = headersDeny == null ? null : List.copyOf(headersDeny);
        queryAllow = queryAllow == null ? null : List.copyOf(queryAllow);
        queryDeny = queryDeny == null ? null : List.copyOf(queryDeny);
        setHeaders = setHeaders == null ? Map.of() : Map.copyOf(setHeaders);
    }
}
