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

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Non-fetching view of the outcome of the most recent OIDC provider-metadata resolution
 * ({@code .well-known/openid-configuration}) of the BFF runtime.
 * <p>
 * The BFF resolves the provider metadata lazily, on the first engine use, so a gateway boots without
 * its identity provider. This view reports what that lazy resolution last did, without ever causing
 * one:
 * <ul>
 *   <li>{@link State#NOT_ATTEMPTED} — no engine use has asked for the metadata yet;</li>
 *   <li>{@link State#RESOLVED} — the most recent attempt produced the metadata;</li>
 *   <li>{@link State#FAILED} — the most recent attempt threw.</li>
 * </ul>
 * <strong>Only the supplier that performs the fetch records.</strong> The BFF runtime producer wraps
 * its discovery supplier with {@link #recording(Supplier)}, so each attempt records its own outcome.
 * {@link #state()} only reads; it never triggers a fetch, so polling it — for example from the
 * readiness probe — causes no network request. The view carries a state token and nothing else: no
 * issuer, no URL and no cause.
 * <p>
 * A gateway without an active BFF runtime gets the {@linkplain #inert() inert} view, which reports
 * {@link #isApplicable()} {@code false} and never leaves {@link State#NOT_ATTEMPTED}.
 * <p>
 * <strong>Thread safety:</strong> instances are thread-safe. The state is held in an
 * {@link AtomicReference}; concurrent attempts each record their own outcome, and the last write
 * wins. Usage:
 *
 * <pre>{@code
 * DiscoveryStatus status = DiscoveryStatus.tracking();
 * Supplier<ProviderMetadata> metadata = memoize(status.recording(() -> resolver.resolve()));
 * status.state(); // NOT_ATTEMPTED until metadata.get() runs
 * }</pre>
 *
 * @author API Sheriff Team
 * @since 1.0
 */
public final class DiscoveryStatus {

    /**
     * The outcome of the most recent provider-metadata resolution, with the fixed token the readiness
     * payload reports it under.
     *
     * @since 1.0
     */
    public enum State {
        /** No engine use has asked for the provider metadata yet. */
        NOT_ATTEMPTED("not-attempted"),
        /** The most recent resolution attempt produced the provider metadata. */
        RESOLVED("resolved"),
        /** The most recent resolution attempt threw. */
        FAILED("failed");

        private final String token;

        State(String token) {
            this.token = token;
        }

        /**
         * @return the fixed, non-disclosing token this state is reported under
         */
        public String token() {
            return token;
        }
    }

    private final boolean applicable;
    private final AtomicReference<State> state = new AtomicReference<>(State.NOT_ATTEMPTED);

    private DiscoveryStatus(boolean applicable) {
        this.applicable = applicable;
    }

    /**
     * @return a view for an active BFF runtime, in {@link State#NOT_ATTEMPTED}, that records through
     *         {@link #recording(Supplier)}
     */
    public static DiscoveryStatus tracking() {
        return new DiscoveryStatus(true);
    }

    /**
     * @return the view for a gateway without an active BFF runtime: not applicable, permanently
     *         {@link State#NOT_ATTEMPTED}
     */
    public static DiscoveryStatus inert() {
        return new DiscoveryStatus(false);
    }

    /**
     * @return {@code true} when this view belongs to an active BFF runtime, so its state is part of
     *         readiness
     */
    public boolean isApplicable() {
        return applicable;
    }

    /**
     * Reads the most recent outcome. Never triggers a fetch.
     *
     * @return the outcome of the most recent provider-metadata resolution
     */
    public State state() {
        return state.get();
    }

    /**
     * Wraps the supplier that performs the provider-metadata fetch so every attempt records its
     * outcome: {@link State#RESOLVED} when it returns, {@link State#FAILED} when it throws. The
     * exception is rethrown unchanged, so the caller's failure handling is untouched.
     *
     * @param fetch the supplier that performs the fetch
     * @param <T>   the type of the resolved metadata
     * @return a supplier that delegates to {@code fetch} and records each attempt's outcome
     * @throws IllegalStateException when this is the {@linkplain #inert() inert} view, which has no
     *                               fetch to record
     */
    public <T> Supplier<T> recording(Supplier<T> fetch) {
        Objects.requireNonNull(fetch, "fetch");
        if (!applicable) {
            throw new IllegalStateException("the inert discovery view records no fetch");
        }
        return () -> {
            try {
                T result = fetch.get();
                state.set(State.RESOLVED);
                return result;
            } catch (RuntimeException failure) {
                state.set(State.FAILED);
                throw failure;
            }
        };
    }
}
