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
package de.cuioss.sheriff.gateway.pipeline;

import java.util.Objects;


import org.jspecify.annotations.Nullable;

/**
 * One query pair of the request-target, in its <strong>raw, still-percent-encoded wire form</strong>
 * (ADR-0047): the name and value are exactly the bytes between the {@code &} delimiters, split on the
 * pair's first {@code =}, with no percent-decoding and no {@code +}-to-space translation.
 * <p>
 * The query is carried as an <em>ordered sequence</em> of these pairs, never as a map keyed by name,
 * because a map cannot represent interleaved repeated names: {@code a=1&b=2&a=3} grouped by name
 * would be forwarded as {@code a=1&a=3&b=2}, a request-target the gateway never validated. Keeping
 * each pair in wire order is what lets the forward path emit exactly the sequence the security filter
 * judged.
 * <p>
 * Instances are immutable and therefore thread-safe.
 *
 * @param name  the raw, still-encoded parameter name, never {@code null}
 * @param value the raw, still-encoded parameter value, or {@code null} for a bare pair without
 *              {@code =} (so {@code ?flag} is forwarded as {@code flag}, never {@code flag=})
 * @author API Sheriff Team
 * @since 1.0
 */
public record QueryParameter(String name, @Nullable String value) {

    /**
     * Canonical constructor rejecting a {@code null} name.
     */
    public QueryParameter {
        Objects.requireNonNull(name, "name");
    }
}
