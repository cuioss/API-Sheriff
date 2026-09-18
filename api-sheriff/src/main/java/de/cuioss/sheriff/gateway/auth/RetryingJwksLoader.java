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

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;


import de.cuioss.sheriff.gateway.auth.IssuerKeySetStatus.KeySetState;
import de.cuioss.sheriff.gateway.config.ConfigLogMessages;
import de.cuioss.sheriff.gateway.events.GatewayException;
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
 * library's public {@code IssuerConfigBuilder.jwksLoader(..)} seam. It wraps one library loader at a
 * time and owns the two things the library does not do reliably: it reports <em>whether a key set has
 * been loaded</em>, and it <em>retries a failed load fast</em>.
 * <p>
 * <strong>Why a wrapper.</strong> The library's loader status is a pure read, but it is not truthful
 * after a failure — an HTTP loader whose first fetch failed stays non-{@code OK} even once keys
 * arrive, and the library's issuer cache re-admits an issuer only when its loader reports
 * {@link LoaderStatus#OK}. This wrapper records the outcome of each load attempt itself and reports
 * {@link LoaderStatus#OK} exactly when a key set is loaded, which both gives readiness something true
 * to read ({@link #keySetState()}) and repairs the library's recovery path.
 * <p>
 * <strong>Fast, bounded retry.</strong> The library retries a failed first fetch only after its whole
 * refresh interval, and its re-fetch hook is not public, so the only way to fetch again is a fresh
 * loader. When an attempt completes without a key set, this wrapper schedules the next attempt on one
 * shared daemon scheduler: the delay starts at {@link #INITIAL_RETRY_DELAY} and doubles per attempt,
 * capped at {@link #MAX_RETRY_DELAY} and never above the issuer's refresh interval. Each attempt builds
 * a fresh delegate (and so a fresh loader configuration, because a closed delegate may have taken
 * resources with it), publishes it, closes the one it replaced, and loads it. Retries stop at the first
 * loaded key set, after which the successful delegate's own background refresh keeps the keys current.
 * Attempts are strictly sequential — the next one is scheduled only once the previous one has
 * completed — so at most one is in flight per issuer, whatever the request rate.
 * <p>
 * <strong>Every read is non-fetching.</strong> {@link #getLoaderStatus()}, {@link #keySetState()} and
 * {@link #getKeyInfo(String)} read one atomically published field; none ever triggers a JWKS request,
 * so neither request threads nor the readiness probe can cause network activity. Only the scheduler
 * fetches.
 * <p>
 * <strong>One snapshot, never two fields.</strong> The current delegate and the key-set state are
 * published together as one immutable {@link Snapshot}. A reader therefore always sees a state beside
 * the delegate it belongs to, and a retry replaces the delegate in one atomic step. The replaced
 * delegate is closed only after the replacement is published, so no reader is ever handed a closed
 * delegate as the current one. While a retry's fresh delegate is still loading, the snapshot keeps the
 * state of the last completed attempt ({@link KeySetState#FAILED}), so readiness keeps reporting the
 * last known outcome instead of a fresh start.
 * <p>
 * Delegates are built by a {@link Supplier}; the first one is built at construction, so a loader
 * configuration that the gateway refuses (a missing url, an unresolvable {@code tls_profile}) aborts
 * assembly exactly as before.
 * <p>
 * Thread-safety: all mutable state lives in atomic references; safe for concurrent use.
 *
 * @author API Sheriff Team
 * @since 1.0
 */
final class RetryingJwksLoader implements JwksLoader, AutoCloseable {

    private static final CuiLogger LOGGER = new CuiLogger(RetryingJwksLoader.class);

    /** The delay before the first retry of a failed load. */
    static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(1);

    /** The upper bound of the retry delay, whatever the attempt count. */
    static final Duration MAX_RETRY_DELAY = Duration.ofSeconds(30);

    /**
     * The delegate and the key-set state, published together.
     *
     * @param delegate the library loader currently answering key lookups
     * @param state    whether a key set is loaded; while a retry's delegate is still loading, the
     *                 outcome of the last completed attempt
     */
    private record Snapshot(JwksLoader delegate, KeySetState state) {
    }

    /**
     * The one scheduler every issuer's retries share. A daemon thread, so a pending retry never keeps
     * the JVM alive; held in a nested class so it is created on first use at runtime, never at image
     * build time.
     */
    private static final class SharedScheduler {

        private static final ScheduledExecutorService INSTANCE = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("sheriff-jwks-retry").daemon(true).factory());
    }

    private final String issuerName;
    private final Supplier<JwksLoader> delegateFactory;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Snapshot> snapshot;
    private final AtomicReference<@Nullable SecurityEventCounter> eventCounter = new AtomicReference<>();
    private final AtomicReference<@Nullable ScheduledFuture<?>> pendingRetry = new AtomicReference<>();
    private final AtomicBoolean initialised = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param issuerName      the configured issuer's logical name, for diagnostics only (never a URL)
     * @param delegateFactory builds a fresh library loader; invoked once here, eagerly, and once per
     *                        retry
     * @param refreshInterval the issuer's key refresh interval; the retry delay never exceeds it. A
     *                        non-positive interval (no background refresh) leaves only
     *                        {@link #MAX_RETRY_DELAY} as the bound
     */
    RetryingJwksLoader(String issuerName, Supplier<JwksLoader> delegateFactory, Duration refreshInterval) {
        this(issuerName, delegateFactory, refreshInterval, INITIAL_RETRY_DELAY, SharedScheduler.INSTANCE);
    }

    /**
     * The full constructor, exposing the retry timing and the scheduler to tests.
     *
     * @param issuerName        the configured issuer's logical name, for diagnostics only
     * @param delegateFactory   builds a fresh library loader; invoked once here, eagerly
     * @param refreshInterval   the issuer's key refresh interval, bounding the retry delay
     * @param initialRetryDelay the delay before the first retry
     * @param scheduler         the scheduler running the retries
     */
    RetryingJwksLoader(String issuerName, Supplier<JwksLoader> delegateFactory, Duration refreshInterval,
            Duration initialRetryDelay, ScheduledExecutorService scheduler) {
        this.issuerName = Objects.requireNonNull(issuerName, "issuerName");
        this.delegateFactory = Objects.requireNonNull(delegateFactory, "delegateFactory");
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        this.initialRetryDelay = Objects.requireNonNull(initialRetryDelay, "initialRetryDelay");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.maxRetryDelay = refreshInterval.isPositive() && refreshInterval.compareTo(MAX_RETRY_DELAY) < 0
                ? refreshInterval
                : MAX_RETRY_DELAY;
        JwksLoader first = Objects.requireNonNull(delegateFactory.get(), "delegate");
        this.snapshot = new AtomicReference<>(new Snapshot(first, KeySetState.NOT_LOADED));
    }

    /**
     * The delay before retry number {@code attempt}: {@code initial} doubled per earlier retry, and
     * never above {@code cap}.
     *
     * @param attempt the 1-based retry number
     * @param initial the delay before the first retry
     * @param cap     the upper bound
     * @return the delay before that retry
     */
    static Duration backoffDelay(int attempt, Duration initial, Duration cap) {
        Duration delay = initial;
        for (int doubling = 1; doubling < attempt && delay.compareTo(cap) < 0; doubling++) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(cap) > 0 ? cap : delay;
    }

    /**
     * Runs the first load of the current delegate and records its outcome. The returned future
     * completes with {@link LoaderStatus#OK} when a key set was loaded and with the delegate's own
     * status otherwise; a load that completes exceptionally is recorded as
     * {@link KeySetState#FAILED}. Either failure schedules the first retry. A repeated call starts
     * nothing and reports the current status, so the retry sequence exists at most once.
     *
     * @param securityEventCounter the library's security event counter, handed to every delegate
     * @return the outcome of the first load
     */
    @Override
    public CompletableFuture<LoaderStatus> initJWKSLoader(SecurityEventCounter securityEventCounter) {
        Objects.requireNonNull(securityEventCounter, "securityEventCounter");
        if (!initialised.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(getLoaderStatus());
        }
        eventCounter.set(securityEventCounter);
        return load(snapshot.get().delegate(), securityEventCounter, 0);
    }

    /**
     * Loads {@code delegate} and settles the outcome: records it, and either closes a retry episode or
     * schedules the next attempt.
     *
     * @param retries the number of retries this load is ({@code 0} for the first load)
     */
    private CompletableFuture<LoaderStatus> load(JwksLoader delegate, SecurityEventCounter counter, int retries) {
        return delegate.initJWKSLoader(counter)
                .handle((status, failure) -> settle(delegate, failure == null ? status : null, retries));
    }

    private LoaderStatus settle(JwksLoader delegate, @Nullable LoaderStatus status, int retries) {
        LoaderStatus reported = recordOutcome(delegate, status);
        Snapshot current = snapshot.get();
        if (current.delegate() != delegate || closed.get()) {
            return reported;
        }
        if (current.state() == KeySetState.LOADED) {
            if (retries > 0) {
                LOGGER.info(ConfigLogMessages.INFO.JWKS_KEY_SET_LOADED_AFTER_RETRY, issuerName, retries);
            }
        } else {
            scheduleRetry(retries + 1);
        }
        return reported;
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

    private void scheduleRetry(int retry) {
        Duration delay = backoffDelay(retry, initialRetryDelay, maxRetryDelay);
        LOGGER.warn(ConfigLogMessages.WARN.JWKS_KEY_SET_RETRY, issuerName, delay.toMillis());
        pendingRetry.set(scheduler.schedule(() -> runRetry(retry), delay.toMillis(), TimeUnit.MILLISECONDS));
        // A close() racing this schedule may have run before the future was published; honour it.
        if (closed.get()) {
            cancelPendingRetry();
        }
    }

    /**
     * One retry: builds a fresh delegate, publishes it in place of the current one, closes the
     * replaced delegate, and loads the fresh one. Runs on the scheduler only.
     */
    private void runRetry(int retry) {
        SecurityEventCounter counter = eventCounter.get();
        if (closed.get() || counter == null) {
            return;
        }
        JwksLoader fresh;
        try {
            fresh = Objects.requireNonNull(delegateFactory.get(), "delegate");
        } catch (GatewayException | IllegalArgumentException | IllegalStateException failure) {
            // The factory refused: the gateway's own loader-config assembly (url, tls_profile
            // resolution) reports GatewayException, the library's config builder and loader
            // construction report IllegalArgumentException / IllegalStateException. Count it as a
            // failed attempt and keep retrying — never let it escape onto the scheduler thread,
            // which would silently end the retry sequence.
            LOGGER.debug(failure, "JWKS loader for issuer '%s' could not be built for retry %s", issuerName, retry);
            scheduleRetry(retry + 1);
            return;
        }
        Snapshot replaced = snapshot.getAndUpdate(current -> new Snapshot(fresh, current.state()));
        closeDelegate(replaced.delegate());
        if (closed.get()) {
            closeDelegate(fresh);
            return;
        }
        load(fresh, counter, retry);
    }

    /**
     * @return the current key-set state — a pure read that never fetches
     */
    KeySetState keySetState() {
        return snapshot.get().state();
    }

    /**
     * @return {@code true} while a retry is scheduled and has not started yet
     */
    boolean retryPending() {
        ScheduledFuture<?> pending = pendingRetry.get();
        return pending != null && !pending.isDone();
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
     * Cancels any pending retry and closes the current delegate when it holds resources (the HTTP
     * loader's refresh executor). A retry already running when this is called closes the delegate it
     * built itself.
     */
    @Override
    public void close() {
        closed.set(true);
        cancelPendingRetry();
        closeDelegate(snapshot.get().delegate());
    }

    private void cancelPendingRetry() {
        ScheduledFuture<?> pending = pendingRetry.get();
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private static void closeDelegate(JwksLoader delegate) {
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
