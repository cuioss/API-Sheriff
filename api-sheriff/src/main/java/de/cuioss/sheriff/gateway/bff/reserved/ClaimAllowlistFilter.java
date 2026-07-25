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
package de.cuioss.sheriff.gateway.bff.reserved;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The operator-owned claim allowlist that caps what the session/user-info endpoint (D11) may
 * disclose to the browser.
 * <p>
 * <strong>Secure default closed.</strong> The allowlist is the sole authority over which identity
 * claims leave the gateway — an <em>empty</em> allowlist discloses <strong>nothing</strong>, so the
 * operator (never the browser client) is the only party that widens disclosure. Every response the
 * endpoint serves is filtered through {@link #filter(Map, List)}, so a claim that is not on the
 * allowlist can never appear in the output even when it is present in the validated ID token.
 * <p>
 * The {@linkplain #defaultView() curated default view} — returned when the caller requests no
 * explicit claims — is itself capped to the allowlist at construction: any {@code default_view}
 * entry outside {@code allowed_claims} is dropped, so the default can never widen disclosure beyond
 * the operator cap. The filter is immutable and framework-agnostic, so a single instance is safely
 * shared across threads.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class ClaimAllowlistFilter {

    private final Set<String> allowedClaims;
    private final List<String> defaultView;

    /**
     * Assembles the filter from the operator allowlist and the curated default view.
     *
     * @param allowedClaims the operator claim allowlist; an empty collection is the secure closed
     *                      default that discloses nothing
     * @param defaultView   the curated default-view selector returned when the caller requests no
     *                      explicit claims; entries outside {@code allowedClaims} are dropped so the
     *                      default can never disclose beyond the allowlist
     */
    public ClaimAllowlistFilter(Collection<String> allowedClaims, Collection<String> defaultView) {
        Objects.requireNonNull(allowedClaims, "allowedClaims");
        Objects.requireNonNull(defaultView, "defaultView");
        this.allowedClaims = Set.copyOf(allowedClaims);
        this.defaultView = defaultView.stream()
                .distinct()
                .filter(this.allowedClaims::contains)
                .toList();
    }

    /**
     * @param claim the claim name to test
     * @return {@code true} when the claim is on the operator allowlist and may be disclosed
     */
    public boolean isAllowed(String claim) {
        Objects.requireNonNull(claim, "claim");
        return allowedClaims.contains(claim);
    }

    /**
     * @return {@code true} when the allowlist is empty — the secure closed default that discloses nothing
     */
    public boolean isEmpty() {
        return allowedClaims.isEmpty();
    }

    /**
     * @return the operator claim allowlist (immutable)
     */
    public Set<String> allowedClaims() {
        return allowedClaims;
    }

    /**
     * @return the curated default-view selection, already capped to the allowlist (immutable)
     */
    public List<String> defaultView() {
        return defaultView;
    }

    /**
     * Filters a source claim map to the allowlisted subset of the requested selection, preserving the
     * selection order. Only keys that are both on the allowlist and present in {@code source} appear
     * in the result; a requested claim outside the allowlist is silently dropped (the endpoint rejects
     * an explicitly-requested disallowed claim {@code 403} before calling this).
     *
     * @param source    the candidate claim values (validated ID-token claims)
     * @param selection the ordered claim names the caller selected
     * @return an ordered, unmodifiable map of the disclosed claims
     */
    public Map<String, Object> filter(Map<String, ?> source, List<String> selection) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(selection, "selection");
        Map<String, Object> filtered = new LinkedHashMap<>();
        for (String claim : selection) {
            if (allowedClaims.contains(claim) && source.containsKey(claim)) {
                filtered.put(claim, source.get(claim));
            }
        }
        return Collections.unmodifiableMap(filtered);
    }
}
