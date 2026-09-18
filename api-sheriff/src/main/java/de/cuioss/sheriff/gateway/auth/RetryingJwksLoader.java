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
package de.cuioss.sheriff.gateway.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus.KeySetState;
import de.cuioss.sheriff.token.commons.events.SecurityEventCounter;
import de.cuioss.sheriff.token.commons.transport.JwksType;
import de.cuioss.sheriff.token.commons.transport.LoaderStatus;
import de.cuioss.sheriff.token.validation.jwks.JwksLoader;
import de.cuioss.sheriff.token.validation.jwks.http.HttpJwksLoader;
import de.cuioss.sheriff.token.validation.jwks.key.KeyInfo;
import de.cuioss.tools.logging.CuiLogger;
import org.jspecify.annotations.Nullable;

/**
 * The gateway-owned {@link JwksLoader} installed for every configured issuer through the token
 * library's public {@code IssuerConfigBuilder.jwksLoader(..)} seam. It wraps one library loader and
 * owns the one fact the library does not report reliably: <em>whether a key set has been loaded</em>.
 * <p>
 * <strong>Why a wrapper.</strong> The library's loader status is a pure read, but it is not truthful
 * after a failure — an HTTP loader whose first fetch failed stays non-{@code OK} even once keys
 * arrive, and the library's issuer cache re-admits an issuer only when its loader reports
 * {@link LoaderStatus#OK}. This wrapper records the outcome of each load attempt itself and reports
 * {@link LoaderStatus#OK} exactly when a key set is loaded, which both gives readiness something true
 * to read ({@link #keySetState()}) and repairs the library's recovery path.
 * <p>
 * <strong>Every read is non-fetching.</strong> {@link #getLoaderStatus()} and {@link #keySetState()}
 * read one atomically published field; neither ever triggers a JWKS request, so request threads and
 * the readiness probe cannot cause network activity.
 * <p>
 * <strong>One snapshot, never two fields.</strong> The current delegate and its key-set state are
 * published together as one immutable {@link Snapshot}. A reader therefore always sees a state that
 * belongs to the delegate it reads beside it, and a later delegate swap (the retry) replaces both in
 * one atomic step — there is no window in which a status read pairs a new delegate with an old
 * outcome.
 * <p>
 * Delegates are built by a {@link Supplier}; the first one is built at construction, so a loader
 * configuration that the gateway refuses (a missing url, an unresolvable {@code tls_profile}) aborts
 * assembly exactly as before.
 * <p>
 * Thread-safety: all mutable state lives in one {@link AtomicReference}; safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class RetryingJwksLoader implements JwksLoader, AutoCloseable {

    private static final CuiLogger LOGGER = new CuiLogger(RetryingJwksLoader.class);

    /**
     * The delegate and the key-set state it produced, published together.
     *
     * @param delegate the library loader currently answering key lookups
     * @param state    whether {@code delegate} has loaded a key set
     */
    private record Snapshot(JwksLoader delegate, KeySetState state) {
    }

    private final String issuerName;
    private final Supplier<JwksLoader> delegateFactory;
    private final AtomicReference<Snapshot> snapshot;

    /**
     * @param issuerName      the configured issuer's logical name, for diagnostics only (never a URL)
     * @param delegateFactory builds a fresh library loader; invoked once here, eagerly
     */
    RetryingJwksLoader(String issuerName, Supplier<JwksLoader> delegateFactory) {
        this.issuerName = Objects.requireNonNull(issuerName, "issuerName");
        this.delegateFactory = Objects.requireNonNull(delegateFactory, "delegateFactory");
        JwksLoader first = Objects.requireNonNull(delegateFactory.get(), "delegate");
        this.snapshot = new AtomicReference<>(new Snapshot(first, KeySetState.NOT_LOADED));
    }

    /**
     * Runs the first load of the current delegate and records its outcome. The returned future
     * completes with {@link LoaderStatus#OK} when a key set was loaded and with the delegate's own
     * status otherwise; a load that completes exceptionally is recorded as
     * {@link KeySetState#FAILED}.
     *
     * @param securityEventCounter the library's security event counter, handed to the delegate
     * @return the outcome of the first load
     */
    @Override
    public CompletableFuture<LoaderStatus> initJWKSLoader(SecurityEventCounter securityEventCounter) {
        Objects.requireNonNull(securityEventCounter, "securityEventCounter");
        JwksLoader delegate = snapshot.get().delegate();
        return delegate.initJWKSLoader(securityEventCounter)
                .handle((status, failure) -> recordOutcome(delegate, failure == null ? status : null));
    }

    /**
     * Records one load attempt's outcome for {@code delegate}, provided it is still the current one.
     *
     * @param delegate the delegate the attempt ran against
     * @param status   the attempt's status, or {@code null} when it completed exceptionally
     * @return {@link LoaderStatus#OK} for a loaded key set, the attempt's status otherwise
     */
    LoaderStatus recordOutcome(JwksLoader delegate, @Nullable LoaderStatus status) {
        KeySetState outcome = status == LoaderStatus.OK ? KeySetState.LOADED : KeySetState.FAILED;
        snapshot.updateAndGet(current -> current.delegate() == delegate
                ? new Snapshot(delegate, outcome)
                : current);
        if (outcome == KeySetState.FAILED) {
            LOGGER.debug("JWKS key set for issuer '%s' not loaded (status %s)", issuerName, status);
        }
        return status == null ? LoaderStatus.ERROR : status;
    }

    /**
     * @return the current key-set state — a pure read that never fetches
     */
    KeySetState keySetState() {
        return snapshot.get().state();
    }

    /**
     * {@link LoaderStatus#OK} once a key set has been loaded, the current delegate's status otherwise.
     * A pure read: it never triggers a fetch.
     */
    @Override
    public LoaderStatus getLoaderStatus() {
        Snapshot current = snapshot.get();
        return current.state() == KeySetState.LOADED ? LoaderStatus.OK : current.delegate().getLoaderStatus();
    }

    @Override
    public Optional<KeyInfo> getKeyInfo(String kid) {
        return snapshot.get().delegate().getKeyInfo(kid);
    }

    @Override
    public JwksType getJwksType() {
        return snapshot.get().delegate().getJwksType();
    }

    @Override
    public Optional<String> getIssuerIdentifier() {
        return snapshot.get().delegate().getIssuerIdentifier();
    }

    /**
     * Closes the current delegate when it holds resources (the HTTP loader's refresh executor).
     */
    @Override
    public void close() {
        closeDelegate(snapshot.get().delegate());
    }

    static void closeDelegate(JwksLoader delegate) {
        if (delegate instanceof HttpJwksLoader httpLoader) {
            httpLoader.close();
        }
    }

    @Override
    public String toString() {
        // The issuer's logical name and state only — never the JWKS URL or key material.
        return "RetryingJwksLoader[issuer=" + issuerName + ", state=" + keySetState() + "]";
    }
}
