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
import java.time.Instant;
import java.time.InstantSource;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
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
 * time and owns the three things the library does not do reliably: it reports <em>whether a key set
 * has been loaded</em>, it <em>retries a failed load fast</em>, and it <em>refreshes the key set when a
 * token names a key id it does not know</em>.
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
 * <strong>Bounded refresh on an unknown key id.</strong> An identity provider that replaces its signing
 * key abruptly issues tokens naming a key id the loaded key set does not hold, and the library's
 * background refresh picks the new key up only at its next interval. A lookup that neither the current
 * nor a retired delegate can answer therefore tries to claim the issuer's refresh window. The claim is
 * one compare-and-set on the next-allowed instant and is made when the lookup arrives, so at most one
 * refresh is scheduled per issuer per window whatever its outcome, the request rate and the number of
 * distinct key ids presented — any caller can present a token naming an arbitrary key id, so the bound
 * must not depend on what is asked for. The claimed refresh runs on the scheduler: it builds a fresh
 * delegate and loads it <em>off to the side</em>, while the current delegate keeps answering. A load
 * that fails, completes exceptionally, or yields a key set that does not hold the triggering key id
 * closes the fresh delegate and changes nothing, so a flood of invented key ids can neither displace
 * the loaded key set nor start the failed-load retry. Only a fresh delegate that holds the triggering
 * key id is published.
 * <p>
 * <strong>The replaced key set stays valid for the grace period.</strong> A fresh library loader knows
 * only the identity provider's current keys, so publishing it would at once reject every token signed
 * with the previous key. The replaced delegate is therefore kept as the <em>retired</em> one and keeps
 * answering lookups for its own key ids until the key-rotation grace period has passed; then it is
 * dropped and closed on the scheduler. At most one retired delegate exists per issuer: a second
 * replacement inside the grace period closes the older one. A grace period of zero retires the replaced
 * delegate at once.
 * <p>
 * <strong>Refresh and retry never overlap.</strong> A refresh is claimed only while a key set is
 * loaded, and the retry sequence — its pending attempt included — exists only while none is, so the
 * two are mutually exclusive for one issuer. One in-flight flag additionally guards the refresh
 * itself.
 * <p>
 * <strong>Every read is non-fetching.</strong> {@link #getLoaderStatus()}, {@link #keySetState()} and
 * {@link #getKeyInfo(String)} read one atomically published field; none ever performs a JWKS request
 * or waits for one, so neither request threads nor the readiness probe can cause network activity on
 * the calling thread. An unknown key id <em>schedules</em> a fetch, bounded as described above, and
 * never performs one: the lookup that triggers a refresh is itself answered from the key sets held at
 * that moment. Only the scheduler fetches.
 * <p>
 * <strong>One snapshot, never separate fields.</strong> The current delegate, the key-set state and the
 * retired delegate with its deadline are published together as one immutable {@link Snapshot}. A reader
 * therefore always sees a state beside the delegate it belongs to, and a retry or a refresh replaces
 * the delegate in one atomic step. A replaced delegate is closed only after its replacement is
 * published, so no reader is ever handed a closed delegate as the current one. While a retry's fresh
 * delegate is still loading, the snapshot keeps the state of the last completed attempt
 * ({@link KeySetState#FAILED}), so readiness keeps reporting the last known outcome instead of a fresh
 * start.
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
     * The unknown-key-id window of an issuer that declares none, and the interval that rate-limits the
     * unknown-key-id WARN of an issuer whose refresh is disabled.
     */
    static final Duration DEFAULT_UNKNOWN_KEY_ID_WINDOW = Duration.ofSeconds(30);

    /**
     * The delegates and the key-set state, published together.
     *
     * @param delegate     the library loader currently answering key lookups
     * @param state        whether a key set is loaded; while a retry's delegate is still loading, the
     *                     outcome of the last completed attempt
     * @param retired      the delegate an unknown-key-id refresh replaced, still answering lookups for
     *                     its own key ids; {@code null} when there is none
     * @param retiredUntil the instant from which {@code retired} no longer answers; {@code null}
     *                     exactly when {@code retired} is
     */
    private record Snapshot(JwksLoader delegate, KeySetState state, @Nullable JwksLoader retired,
            @Nullable Instant retiredUntil) {

        /** The same snapshot with {@code state} for {@code delegate}; a retired delegate is kept. */
        Snapshot with(JwksLoader delegate, KeySetState state) {
            return new Snapshot(delegate, state, retired, retiredUntil);
        }

        /** The same snapshot without its retired delegate. */
        Snapshot withoutRetired() {
            return new Snapshot(delegate, state, null, null);
        }
    }

    /**
     * The one scheduler every issuer's retries and refreshes share. A daemon thread, so a pending task
     * never keeps the JVM alive; held in a nested class so it is created on first use at runtime, never
     * at image build time.
     */
    private static final class SharedScheduler {

        private static final ScheduledExecutorService INSTANCE = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("sheriff-jwks-retry").daemon(true).factory());
    }

    private final String issuerName;
    private final Supplier<JwksLoader> delegateFactory;
    private final Duration initialRetryDelay;
    private final Duration maxRetryDelay;
    private final Duration unknownKeyIdWindow;
    private final Duration keyRotationGracePeriod;
    private final ScheduledExecutorService scheduler;
    private final InstantSource clock;
    private final AtomicReference<Snapshot> snapshot;
    private final AtomicReference<@Nullable SecurityEventCounter> eventCounter = new AtomicReference<>();
    private final AtomicReference<@Nullable ScheduledFuture<?>> pendingRetry = new AtomicReference<>();
    private final AtomicReference<@Nullable ScheduledFuture<?>> pendingRetirement = new AtomicReference<>();
    /** The earliest instant at which the next unknown key id may claim the window. */
    private final AtomicReference<Instant> nextClaimAllowed = new AtomicReference<>(Instant.MIN);
    /** The instant this wrapper last observed a load attempt yield a key set. */
    private final AtomicReference<@Nullable Instant> lastLoaded = new AtomicReference<>();
    /** The fresh delegate of a refresh that is still loading off to the side. */
    private final AtomicReference<@Nullable JwksLoader> loadingRefresh = new AtomicReference<>();
    private final AtomicBoolean refreshInFlight = new AtomicBoolean();
    private final AtomicBoolean initialised = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * @param issuerName             the configured issuer's logical name, for diagnostics only (never a
     *                               URL)
     * @param delegateFactory        builds a fresh library loader; invoked once here, eagerly, once per
     *                               retry and once per unknown-key-id refresh
     * @param refreshInterval        the issuer's key refresh interval; the retry delay never exceeds
     *                               it. A non-positive interval (no background refresh) leaves only
     *                               {@link #MAX_RETRY_DELAY} as the bound
     * @param unknownKeyIdWindow     the window within which an unknown key id schedules at most one
     *                               refresh. A non-positive window disables the refresh — which is what
     *                               a {@code file} source passes, having nothing to fetch — and leaves
     *                               the WARN, rate-limited by {@link #DEFAULT_UNKNOWN_KEY_ID_WINDOW}
     * @param keyRotationGracePeriod how long a delegate replaced by a refresh keeps answering lookups
     *                               for its own key ids; non-positive retires it at once
     */
    RetryingJwksLoader(String issuerName, Supplier<JwksLoader> delegateFactory, Duration refreshInterval,
            Duration unknownKeyIdWindow, Duration keyRotationGracePeriod) {
        this(issuerName, delegateFactory, refreshInterval, unknownKeyIdWindow, keyRotationGracePeriod,
                INITIAL_RETRY_DELAY, SharedScheduler.INSTANCE, InstantSource.system());
    }

    /**
     * The full constructor, exposing the retry timing, the scheduler and the clock to tests.
     *
     * @param issuerName             the configured issuer's logical name, for diagnostics only
     * @param delegateFactory        builds a fresh library loader; invoked once here, eagerly
     * @param refreshInterval        the issuer's key refresh interval, bounding the retry delay
     * @param unknownKeyIdWindow     the window of the unknown-key-id refresh; non-positive disables it
     * @param keyRotationGracePeriod how long a replaced delegate keeps answering; non-positive retires
     *                               it at once
     * @param initialRetryDelay      the delay before the first retry
     * @param scheduler              the scheduler running the retries, the refreshes and the retirement
     *                               of a replaced delegate
     * @param clock                  the source of the instants the window, the grace deadline and the
     *                               last-loaded instant are read from
     */
    // The eight parameters are the loader's complete timing surface; tests need each one separately.
    @SuppressWarnings("java:S107")
    RetryingJwksLoader(String issuerName, Supplier<JwksLoader> delegateFactory, Duration refreshInterval,
            Duration unknownKeyIdWindow, Duration keyRotationGracePeriod, Duration initialRetryDelay,
            ScheduledExecutorService scheduler, InstantSource clock) {
        this.issuerName = Objects.requireNonNull(issuerName, "issuerName");
        this.delegateFactory = Objects.requireNonNull(delegateFactory, "delegateFactory");
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        this.unknownKeyIdWindow = Objects.requireNonNull(unknownKeyIdWindow, "unknownKeyIdWindow");
        this.keyRotationGracePeriod = Objects.requireNonNull(keyRotationGracePeriod, "keyRotationGracePeriod");
        this.initialRetryDelay = Objects.requireNonNull(initialRetryDelay, "initialRetryDelay");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maxRetryDelay = refreshInterval.isPositive() && refreshInterval.compareTo(MAX_RETRY_DELAY) < 0
                ? refreshInterval
                : MAX_RETRY_DELAY;
        JwksLoader first = Objects.requireNonNull(delegateFactory.get(), "delegate");
        this.snapshot = new AtomicReference<>(new Snapshot(first, KeySetState.NOT_LOADED, null, null));
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
     * Records one load attempt's outcome for {@code delegate}, provided it is still the current one. A
     * loaded outcome also records the instant it was observed.
     *
     * @param delegate the delegate the attempt ran against
     * @param status   the attempt's status, or {@code null} when it completed exceptionally
     * @return {@link LoaderStatus#OK} for a loaded key set, the attempt's status otherwise
     */
    LoaderStatus recordOutcome(JwksLoader delegate, @Nullable LoaderStatus status) {
        KeySetState outcome = status == LoaderStatus.OK ? KeySetState.LOADED : KeySetState.FAILED;
        if (outcome == KeySetState.LOADED && snapshot.get().delegate() == delegate) {
            // Before the state is published, so a reader that sees LOADED also sees the instant.
            lastLoaded.set(clock.instant());
        }
        snapshot.updateAndGet(current -> current.delegate() == delegate
                ? current.with(delegate, outcome)
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
            cancel(pendingRetry.get());
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
        Snapshot replaced = snapshot.getAndUpdate(current -> current.with(fresh, current.state()));
        closeDelegate(replaced.delegate());
        if (closed.get()) {
            closeDelegate(fresh);
            return;
        }
        load(fresh, counter, retry);
    }

    /**
     * Handles a lookup no held key set could answer while a key set is loaded: claims the issuer's
     * window, and on a successful claim reports the unknown key id once and schedules one refresh.
     * <p>
     * The claim is made here, when the lookup arrives, and not when a refresh completes, so the bound
     * of one refresh per window holds whatever the fetch outcome. Runs on the calling request thread
     * and performs no fetch and no blocking wait.
     *
     * @param kid the key id that triggered the lookup; handed to the refresh, never logged
     */
    private void onUnknownKeyId(String kid) {
        if (closed.get()) {
            return;
        }
        boolean refreshEnabled = unknownKeyIdWindow.isPositive();
        Instant now = clock.instant();
        Instant allowed = nextClaimAllowed.get();
        Instant next = now.plus(refreshEnabled ? unknownKeyIdWindow : DEFAULT_UNKNOWN_KEY_ID_WINDOW);
        if (now.isBefore(allowed) || !nextClaimAllowed.compareAndSet(allowed, next)) {
            LOGGER.debug("Unknown key id for issuer '%s' inside an already claimed window", issuerName);
            return;
        }
        LOGGER.warn(ConfigLogMessages.WARN.JWKS_UNKNOWN_KEY_ID, issuerName, lastLoaded.get());
        if (refreshEnabled && refreshInFlight.compareAndSet(false, true)) {
            try {
                scheduler.execute(() -> runRefresh(kid));
            } catch (RejectedExecutionException rejected) {
                // The scheduler is shutting down. The lookup is answered from the held key sets either
                // way, so the refusal must not surface on the request thread.
                refreshInFlight.set(false);
                LOGGER.debug(rejected, "JWKS refresh for issuer '%s' could not be scheduled", issuerName);
            }
        }
    }

    /**
     * One unknown-key-id refresh: builds a fresh delegate and loads it without publishing it, so the
     * current delegate keeps answering until the outcome is known. Runs on the scheduler only.
     */
    private void runRefresh(String kid) {
        SecurityEventCounter counter = eventCounter.get();
        if (closed.get() || counter == null) {
            refreshInFlight.set(false);
            return;
        }
        JwksLoader fresh;
        try {
            fresh = Objects.requireNonNull(delegateFactory.get(), "delegate");
        } catch (GatewayException | IllegalArgumentException | IllegalStateException failure) {
            // The same refusals runRetry handles. A refresh that cannot be built changes nothing: the
            // loaded key set stays published and the next window may try again.
            LOGGER.debug(failure, "JWKS loader for issuer '%s' could not be built for a refresh", issuerName);
            refreshInFlight.set(false);
            return;
        }
        loadingRefresh.set(fresh);
        fresh.initJWKSLoader(counter)
                .whenComplete((status, failure) -> settleRefresh(fresh, kid, failure == null ? status : null));
    }

    /**
     * Settles one refresh: publishes {@code fresh} when it loaded a key set holding the triggering key
     * id, and closes it otherwise. A discarded refresh leaves the snapshot untouched and never starts
     * the failed-load retry.
     *
     * @param status the load's status, or {@code null} when it completed exceptionally
     */
    private void settleRefresh(JwksLoader fresh, String kid, @Nullable LoaderStatus status) {
        try {
            if (closed.get() || status != LoaderStatus.OK || fresh.getKeyInfo(kid).isEmpty()) {
                LOGGER.debug("JWKS refresh for issuer '%s' discarded (status %s)", issuerName, status);
                closeDelegate(fresh);
                return;
            }
            publishRefreshed(fresh);
        } finally {
            loadingRefresh.compareAndSet(fresh, null);
            refreshInFlight.set(false);
        }
    }

    /**
     * Publishes {@code fresh} as the current delegate. The delegate it replaces becomes the retired one
     * until the grace period has passed, or is closed at once when there is no grace period; a delegate
     * that was already retired is closed, so at most one exists.
     */
    private void publishRefreshed(JwksLoader fresh) {
        Instant now = clock.instant();
        boolean retain = keyRotationGracePeriod.isPositive();
        lastLoaded.set(now);
        Snapshot replaced = snapshot.getAndUpdate(current -> retain
                ? new Snapshot(fresh, KeySetState.LOADED, current.delegate(), now.plus(keyRotationGracePeriod))
                : new Snapshot(fresh, KeySetState.LOADED, null, null));
        cancel(pendingRetirement.getAndSet(null));
        JwksLoader olderRetired = replaced.retired();
        if (olderRetired != null) {
            closeDelegate(olderRetired);
        }
        LOGGER.debug("JWKS key set for issuer '%s' replaced by an unknown-key-id refresh", issuerName);
        JwksLoader retired = replaced.delegate();
        if (!retain) {
            closeDelegate(retired);
        } else {
            pendingRetirement.set(scheduler.schedule(() -> retire(retired), keyRotationGracePeriod.toMillis(),
                    TimeUnit.MILLISECONDS));
        }
        // A close() racing this publication read the snapshot before it changed; honour it here.
        if (closed.get()) {
            close();
        }
    }

    /** Drops {@code retired} from the snapshot, provided it is still the retired one, and closes it. */
    private void retire(JwksLoader retired) {
        Snapshot before = snapshot.getAndUpdate(current -> current.retired() == retired
                ? current.withoutRetired()
                : current);
        if (before.retired() == retired) {
            closeDelegate(retired);
        }
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
     * @return the window within which an unknown key id schedules at most one refresh; non-positive
     *         when the refresh is disabled
     */
    Duration unknownKeyIdWindow() {
        return unknownKeyIdWindow;
    }

    /**
     * @return how long a delegate replaced by a refresh keeps answering lookups for its own key ids
     */
    Duration keyRotationGracePeriod() {
        return keyRotationGracePeriod;
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

    /**
     * Answers from the key sets held at the moment of the call: the current delegate first, then a
     * retired delegate whose grace deadline has not passed. It never fetches and never waits. A key id
     * neither holds yields an empty answer for this call and, while a key set is loaded, may schedule
     * one refresh for a later one.
     */
    @Override
    public Optional<KeyInfo> getKeyInfo(String kid) {
        Snapshot current = snapshot.get();
        Optional<KeyInfo> key = current.delegate().getKeyInfo(kid);
        if (key.isPresent()) {
            return key;
        }
        JwksLoader retired = current.retired();
        Instant retiredUntil = current.retiredUntil();
        if (retired != null && retiredUntil != null && clock.instant().isBefore(retiredUntil)) {
            Optional<KeyInfo> retiredKey = retired.getKeyInfo(kid);
            if (retiredKey.isPresent()) {
                return retiredKey;
            }
        }
        if (current.state() == KeySetState.LOADED) {
            onUnknownKeyId(kid);
        }
        return Optional.empty();
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
     * Cancels any pending retry and any pending retirement, and closes every delegate this wrapper
     * holds when it holds resources (the HTTP loader's refresh executor): the current one, a retired
     * one, and the fresh one of a refresh that is still loading. A retry or a refresh already running
     * when this is called closes the delegate it built itself.
     */
    @Override
    public void close() {
        closed.set(true);
        cancel(pendingRetry.get());
        cancel(pendingRetirement.get());
        Snapshot current = snapshot.get();
        closeDelegate(current.delegate());
        JwksLoader retired = current.retired();
        if (retired != null) {
            closeDelegate(retired);
        }
        JwksLoader loading = loadingRefresh.getAndSet(null);
        if (loading != null) {
            closeDelegate(loading);
        }
    }

    private static void cancel(@Nullable ScheduledFuture<?> pending) {
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
