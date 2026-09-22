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
package de.cuioss.sheriff.gateway.bff.runtime;

import org.jspecify.annotations.Nullable;

/**
 * The display identity of the browser session behind a request, as a gateway-rendered page — the
 * application portal — shows it: whether a live session was resolved and, when it was, the
 * {@code preferred_username} of the signed-in user.
 * <p>
 * The username is taken from the <em>validated</em> ID-token claims of the resolved session, never
 * from raw token material. It is absent both for an anonymous request and for a live session whose
 * ID token carries no {@code preferred_username}. No other claim and no session metadata travel in
 * this record.
 * <p>
 * Immutable; thread-safe.
 *
 * @param authenticated whether a live session was resolved for the request
 * @param username      the session's {@code preferred_username}, {@code null} when anonymous or when
 *                      the claim is absent; must be {@code null} when not {@code authenticated}
 * @author API Sheriff Team
 * @since 1.0
 */
public record SessionIdentity(boolean authenticated, @Nullable String username) {

    private static final SessionIdentity ANONYMOUS = new SessionIdentity(false, null);

    /**
     * Canonical constructor enforcing that an anonymous identity carries no username.
     *
     * @throws IllegalArgumentException if {@code username} is set without {@code authenticated}
     */
    public SessionIdentity {
        if (!authenticated && username != null) {
            throw new IllegalArgumentException("an anonymous session identity carries no username");
        }
    }

    /**
     * @return the identity of a request without a live session — the only identity an inert BFF
     * runtime ever reports
     */
    public static SessionIdentity anonymous() {
        return ANONYMOUS;
    }
}
