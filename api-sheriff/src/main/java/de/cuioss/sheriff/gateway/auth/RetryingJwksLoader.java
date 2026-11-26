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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
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
 * time and adds three things the library does not do: it keeps a <em>non-fetching record of whether a
 * key set has been loaded</em> for readiness, it <em>retries a failed first load fast</em>, and it
 * <em>refreshes the key set when a token names a key id it does not know</em>.
 * <p>
 * <strong>Why a wrapper.</strong> Since token-sheriff 0.9.9 the library's loader status is truthful:
 * an HTTP loader reports {@link LoaderStatus#OK} only while a usable key set is installed, a loader
 * whose first fetch failed recovers by its own background refresh, and a loader built with an opt-in
 * maximum staleness (the issuer's {@code jwks.max_staleness_seconds}) moves a loaded key set to
 * {@link LoaderStatus#UNDEFINED} once its refreshes have failed past that bound, while keeping the
 * keys. This wrapper therefore reports the current delegate's status as it is
 * ({@link #getLoaderStatus()}), so the library's issuer cache — which admits an issuer only while its
 * loader reports {@link LoaderStatus#OK} — rejects a stale-degraded issuer fail-closed until the
 * delegate's own background refresh installs usable keys again. That rejection comes before any key
 * lookup, so no token of a degraded issuer reaches {@link #getKeyInfo(String)} and no unknown key id
 * can claim a refresh for it. What the wrapper adds is the readiness view {@link #keySetState()},
 * which reads a loaded key set the delegate no longer reports usable as {@link KeySetState#FAILED}; the fast,
 * bounded first-load retry, because the library's own recovery waits a whole refresh interval; and the
 * bounded unknown-key-id refresh with the grace-period retirement of the key set it replaces.
 * <p>
 * <strong>Fast, bounded retry.</strong> The library retries a failed first fetch only after its whole
 * refresh interval, and its re-fetch hook is not public, so the only way to fetch again is a fresh
 * loader. When an attempt completes without a key set, this wrapper schedules the next attempt on one
 * shared daemon scheduler: the delay starts at {@link #INITIAL_RETRY_DELAY} and doubles per attempt,
 * capped at {@link #MAX_RETRY_DELAY} and never above the issuer's refresh interval. Each attempt builds
 * a fresh delegate (and so a fresh loader configuration, because a closed delegate may have taken
 * resources with it), publishes it, closes the one it replaced, and loads it. Retries stop at the first
 * loaded key set, after which the successful delegate's own background refresh keeps the keys current.
 * A delegate the library recovered by that background refresh before the next retry ran counts as
 * loaded too: that retry keeps it rather than replacing it.
 * Attempts are strictly sequential — the next one is scheduled only once the previous one has
 * completed — so at most one is in flight per issuer, whatever the request rate. Every attempt that
 * ends while the wrapper is open continues the sequence: an attempt whose delegate cannot be built,
 * whose replaced delegate cannot be closed or whose load cannot be started counts as a failed attempt
 * and schedules the next one, whatever was thrown. The sequence therefore ends only at a loaded key
 * set or at {@link #close()}, never silently on the scheduler thread.
 * <p>
 * <strong>Bounded refresh on an unknown key id.</strong> An identity provider that replaces its signing
 * key abruptly issues tokens naming a key id the loaded key set does not hold, and the library's
 * background refresh picks the new key up only at its next interval. A lookup that neither the current
 * nor a retired delegate can answer therefore tries to claim the issuer's refresh window. The claim is
 * one compare-and-set on the next-allowed reading of a monotonic clock and is made when the lookup
 * arrives, so at most one refresh is scheduled per issuer per window whatever its outcome, the request
 * rate and the number of distinct key ids presented — any caller can present a token naming an
 * arbitrary key id, so the bound must not depend on what is asked for. The claimed refresh runs on the
 * scheduler: it builds a fresh delegate and loads it <em>off to the side</em>, while the current
 * delegate keeps answering. The refresh does not carry the key id that triggered it. It carries its
 * <em>comparison basis</em> instead, captured when the window is claimed: the delegate current at that
 * moment, that delegate's key ids ({@link JwksLoader#getKeyIds()}), and the key ids of a retired
 * delegate still inside its grace period at that moment, which count as already held. A loaded
 * fresh delegate is published exactly when its key ids include at least one the basis lacks
 * <em>and</em> the basis is still current when the refresh settles — the current delegate is the
 * same one, holding the same key ids; the retired delegate plays no part in that check. An identity
 * provider that rotated its key therefore has the new key picked up by whichever unknown key id
 * claimed the window, whether or not that key id is in the published set, unless a refresh of an
 * earlier window is still in flight (a claim made then schedules nothing) or the basis moves during
 * the fetch (the refresh is then discarded). A load that fails, completes exceptionally, yields a
 * key set whose key ids add nothing to the basis, or settles after the basis moved closes the fresh
 * delegate and changes nothing:
 * <ul>
 * <li>a key set equal to the current delegate's is discarded, so an unchanged key set changes nothing
 * however many key ids triggered the refresh, and a flood of invented key ids against an unchanged
 * identity provider can neither displace the loaded key set nor roll the retired one forward;</li>
 * <li>a key set that only drops key ids is discarded: publishing it would gain no revocation speed,
 * because the replaced delegate keeps answering for the grace period, but would let any caller end an
 * older retired delegate's grace period whenever the identity provider drops a key — the current
 * delegate's own background refresh performs the shrink;</li>
 * <li>a key set that only brings back key ids of the retired delegate counted in the basis is
 * discarded: that delegate still answered for them when the window was claimed, so against the basis
 * the fetch gains nothing, and publishing it would only roll the retired delegate forward;</li>
 * <li>a key set holding no key id is never published over a loaded one;</li>
 * <li>a key set fetched while the basis moved is discarded, whatever it holds: the current
 * delegate's own background refresh may have dropped a key during the fetch, and the older fetched
 * set would then look like a gain against the newer one and bring the dropped key back for another
 * grace period. The next claimed window compares against the new basis.</li>
 * </ul>
 * No discarded refresh starts the failed-load retry, and every key set published is one the identity
 * provider itself served.
 * <p>
 * <strong>The replaced key set stays valid for the grace period.</strong> A fresh library loader knows
 * only the identity provider's current keys, so publishing it would at once reject every token signed
 * with the previous key. The replaced delegate is therefore kept as the <em>retired</em> one and keeps
 * answering lookups for its own key ids until the key-rotation grace period has passed; then it is
 * dropped and closed on the scheduler. At most one retired delegate exists per issuer: a second
 * replacement inside the grace period closes the older one. A grace period of zero retires the replaced
 * delegate at once.
 * <p>
 * <strong>Durations are measured on a monotonic clock.</strong> The unknown-key-id window and the
 * grace deadline are durations this wrapper enforces, not instants it reports, so both are read from a
 * monotonic nanosecond source and compared overflow-safely. A step of the wall clock therefore neither
 * lengthens nor shortens either of them. The last-loaded instant reported in the unknown-key-id WARN is
 * the one value read from the wall clock, because it is reported as an instant.
 * <p>
 * <strong>Refresh and retry never overlap.</strong> A refresh is claimed only while a key set is
 * loaded, and the retry sequence — its pending attempt included — exists only while none is, so the
 * two are mutually exclusive for one issuer. One in-flight flag additionally guards the refresh
 * itself.
 * <p>
 * <strong>Every read is non-fetching.</strong> {@link #getLoaderStatus()}, {@link #keySetState()} and
 * {@link #getKeyInfo(String)} read one atomically published field and, from it, the current delegate's
 * own non-fetching answers; none ever performs a JWKS request or waits for one, so neither request
 * threads nor the readiness probe can cause network activity on the calling thread. An unknown key id <em>schedules</em> a fetch, bounded as described above, and
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
     * @param state        whether the last completed load attempt yielded a key set; while a retry's
     *                     delegate is still loading, the outcome of the last completed attempt. It is
     *                     never rewritten when the delegate later stops reporting its key set usable:
     *                     {@link #keySetState()} derives that from the delegate's status, and the
     *                     unknown-key-id refresh stays claimable for as long as this reads
     *                     {@link KeySetState#LOADED}
     * @param retired      the delegate an unknown-key-id refresh replaced, still answering lookups for
     *                     its own key ids; {@code null} when there is none
     * @param retiredUntil the monotonic-clock reading, in nanoseconds, from which {@code retired} no
     *                     longer answers; meaningful only while {@code retired} is non-{@code null},
     *                     and {@code 0} otherwise
     */
    private record Snapshot(JwksLoader delegate, KeySetState state, @Nullable JwksLoader retired,
    long retiredUntil) {

        /** The same snapshot with {@code state} for {@code delegate}; a retired delegate is kept. */
        Snapshot with(JwksLoader delegate, KeySetState state) {
            return new Snapshot(delegate, state, retired, retiredUntil);
        }

        /** The same snapshot without its retired delegate. */
        Snapshot withoutRetired() {
            return new Snapshot(delegate, state, null, 0L);
        }
    }

    /**
     * What an unknown-key-id refresh is compared against, captured when its window is claimed. The key
     * ids the basis holds are the union of {@code keyIds} and {@code retiredKeyIds}: a key id a retired
     * delegate still answers for counts as already held.
     *
     * @param delegate      the delegate current when the window was claimed
     * @param keyIds        that delegate's key ids at that moment, as an immutable copy
     * @param retiredKeyIds the key ids, at that moment and as an immutable copy, of the retired
     *                      delegate still inside its grace period; empty when there was none
     */
    private record RefreshBasis(JwksLoader delegate, Set<String> keyIds, Set<String> retiredKeyIds) {

        /**
         * The basis of a refresh claimed now.
         *
         * @param current the current delegate
         * @param retired the retired delegate still inside its grace period, or {@code null} when
         *                there is none
         * @return the basis holding {@code current} and both delegates' key ids as they are now
         */
        static RefreshBasis of(JwksLoader current, @Nullable JwksLoader retired) {
            return new RefreshBasis(current, Set.copyOf(current.getKeyIds()),
                    retired == null ? Set.of() : Set.copyOf(retired.getKeyIds()));
        }

        /**
         * @param fetchedKeyIds the key ids of the key set a refresh fetched
         * @return {@code true} when the basis already holds every one of them, as a key id of the
         *         delegate or of the retired delegate, so the fetched key set adds nothing to it
         */
        boolean holdsAll(Set<String> fetchedKeyIds) {
            return fetchedKeyIds.stream()
                    .allMatch(keyId -> keyIds.contains(keyId) || retiredKeyIds.contains(keyId));
        }

        /**
         * @param current the delegate current now
         * @return {@code true} when the basis has not moved: {@code current} is the same delegate and
         *         still holds the same key ids. The retired delegate plays no part: the end of its
         *         grace period does not move the basis
         */
        boolean isCurrent(JwksLoader current) {
            return current == delegate && keyIds.equals(current.getKeyIds());
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
    private final LongSupplier monotonicClock;
    private final AtomicReference<Snapshot> snapshot;
    private final AtomicReference<@Nullable SecurityEventCounter> eventCounter = new AtomicReference<>();
    private final AtomicReference<@Nullable ScheduledFuture<?>> pendingRetry = new AtomicReference<>();
    private final AtomicReference<@Nullable ScheduledFuture<?>> pendingRetirement = new AtomicReference<>();
    /**
     * The earliest monotonic-clock reading, in nanoseconds, at which the next unknown key id may claim
     * the window. Starts at the reading taken at construction, so the first claim is admitted.
     */
    private final AtomicLong nextClaimAllowed;
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
                INITIAL_RETRY_DELAY, SharedScheduler.INSTANCE, InstantSource.system(), System::nanoTime);
    }

    /**
     * The full constructor, exposing the retry timing, the scheduler and both clocks to tests.
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
     * @param clock                  the source of the last-loaded instant, the one value this wrapper
     *                               reports as an instant
     * @param monotonicClock         a monotonic nanosecond source ({@link System#nanoTime()} in
     *                               production) the unknown-key-id window and the grace deadline are
     *                               measured on; only the difference between two readings is meaningful
     */
    // The nine parameters are the loader's complete timing surface; tests need each one separately.
    @SuppressWarnings("java:S107")
    RetryingJwksLoader(String issuerName, Supplier<JwksLoader> delegateFactory, Duration refreshInterval,
            Duration unknownKeyIdWindow, Duration keyRotationGracePeriod, Duration initialRetryDelay,
            ScheduledExecutorService scheduler, InstantSource clock, LongSupplier monotonicClock) {
        this.issuerName = Objects.requireNonNull(issuerName, "issuerName");
        this.delegateFactory = Objects.requireNonNull(delegateFactory, "delegateFactory");
        Objects.requireNonNull(refreshInterval, "refreshInterval");
        this.unknownKeyIdWindow = Objects.requireNonNull(unknownKeyIdWindow, "unknownKeyIdWindow");
        this.keyRotationGracePeriod = Objects.requireNonNull(keyRotationGracePeriod, "keyRotationGracePeriod");
        this.initialRetryDelay = Objects.requireNonNull(initialRetryDelay, "initialRetryDelay");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
        this.nextClaimAllowed = new AtomicLong(monotonicClock.getAsLong());
        this.maxRetryDelay = refreshInterval.isPositive() && refreshInterval.compareTo(MAX_RETRY_DELAY) < 0
                ? refreshInterval
                : MAX_RETRY_DELAY;
        JwksLoader first = buildDelegate();
        this.snapshot = new AtomicReference<>(new Snapshot(first, KeySetState.NOT_LOADED, null, 0L));
    }

    /**
     * Builds one fresh delegate from {@link #delegateFactory}. The constructor calls it as well, once
     * the factory field is assigned; it reads no other field, so the incomplete instance is safe to
     * use there.
     *
     * @return the fresh delegate, never {@code null}
     * @throws NullPointerException if the factory yields {@code null}; whatever the factory itself
     *                              throws is propagated unchanged
     */
    private JwksLoader buildDelegate() {
        return Objects.requireNonNull(delegateFactory.get(), "delegate");
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
     * Records one load attempt's outcome for {@code delegate}, provided it is still the current one: a
     * {@link LoaderStatus#OK} attempt as {@link KeySetState#LOADED}, any other as
     * {@link KeySetState#FAILED}. A loaded outcome also records the instant it was observed. The
     * recorded outcome is the published state {@link #keySetState()} starts from; it is not what
     * {@link #getLoaderStatus()} reports, which is always the delegate's own status.
     *
     * @param delegate the delegate the attempt ran against
     * @param status   the attempt's status, or {@code null} when it completed exceptionally
     * @return the attempt's status, or {@link LoaderStatus#ERROR} when it completed exceptionally
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

    /**
     * Schedules retry number {@code retry}, unless the wrapper is closed. The closed state is read
     * both before scheduling and again after the future is published, so a {@link #close()} racing
     * this call cannot leave a retry behind.
     */
    private void scheduleRetry(int retry) {
        if (closed.get()) {
            return;
        }
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
     * <p>
     * A retry first reads the current delegate's status. A delegate whose failed load the library has
     * since recovered by its own background refresh reports {@link LoaderStatus#OK}; the retry then
     * keeps it, records the outcome as {@link KeySetState#LOADED} and ends the sequence, logging the
     * episode's closing record with the number of retries that ran before the recovery (which is
     * {@code 0} when the library recovered before the first retry). Replacing such a delegate would
     * close one holding usable keys and report a fresh one's status while that one loads, so the
     * library's issuer cache would refuse a healthy issuer for the length of a fetch, and for longer
     * whenever the fetch failed.
     * <p>
     * Every exit that neither hands the fresh delegate's load to {@link #settle} nor finds the wrapper
     * closed schedules exactly one next attempt, whatever ended it. There is no in-flight flag to
     * release here: what an escaping failure would cost is the retry sequence itself, because nothing
     * else ever schedules the next attempt and the issuer would stay {@link KeySetState#FAILED} until
     * the next restart. The anticipated refusals ({@link GatewayException},
     * {@link IllegalArgumentException}, {@link IllegalStateException}) schedule from their own catch;
     * any other failure — a {@code null} delegate, or any other exception or error from building the
     * delegate, from closing the replaced one or from starting the load — schedules from the
     * {@code finally} and still propagates. A closed wrapper schedules nothing on any path.
     */
    private void runRetry(int retry) {
        SecurityEventCounter counter = eventCounter.get();
        if (closed.get() || counter == null) {
            return;
        }
        boolean continued = false;
        try {
            JwksLoader held = snapshot.get().delegate();
            if (held.getLoaderStatus() == LoaderStatus.OK) {
                // The library recovered the failed load by its own background refresh. Replacing the
                // delegate now would close one holding usable keys and report a fresh one's status
                // while it loads, so the issuer cache would refuse a healthy issuer for the length of
                // a fetch, and for longer whenever that fetch failed. Record the recovery instead.
                recordOutcome(held, LoaderStatus.OK);
                LOGGER.info(ConfigLogMessages.INFO.JWKS_KEY_SET_LOADED_AFTER_RETRY, issuerName, retry - 1);
                continued = true;
                return;
            }
            JwksLoader fresh = buildDelegate();
            Snapshot replaced = snapshot.getAndUpdate(current -> current.with(fresh, current.state()));
            closeDelegate(replaced.delegate());
            if (closed.get()) {
                closeDelegate(fresh);
                return;
            }
            load(fresh, counter, retry);
            continued = true;
        } catch (GatewayException | IllegalArgumentException | IllegalStateException failure) {
            // The factory refused: the gateway's own loader-config assembly (url, tls_profile
            // resolution) reports GatewayException, the library's config builder and loader
            // construction report IllegalArgumentException / IllegalStateException — the same
            // refusals runRefresh expects, here too from closing the replaced delegate or starting
            // the load. Count it as a failed attempt and keep retrying.
            LOGGER.debug(failure, "JWKS loader for issuer '%s' could not be built or started for retry %s",
                    issuerName, retry);
            // Set before scheduling, so a scheduler that refuses cannot make the finally try again.
            continued = true;
            scheduleRetry(retry + 1);
        } finally {
            if (!continued && !closed.get()) {
                // A failure nobody anticipated is escaping onto the scheduler thread. Keep the
                // sequence alive; the scheduleRetry WARN already reports the episode, so the
                // issuer name and the retry number are all this records.
                LOGGER.debug("JWKS retry %s for issuer '%s' ended unexpectedly; scheduling the next attempt",
                        retry, issuerName);
                scheduleRetry(retry + 1);
            }
        }
    }

    /**
     * Handles a lookup no held key set could answer while a key set is loaded: claims the issuer's
     * window, and on a successful claim reports the unknown key id once. That claim also schedules one
     * refresh, but only when the refresh is enabled and no refresh is still in flight: a claim made
     * while one is in flight logs its record and schedules nothing.
     * <p>
     * The claim is made here, when the lookup arrives, and not when a refresh completes, so the bound
     * of one refresh per window holds whatever the fetch outcome. The refresh's comparison basis — the
     * current delegate, its key ids and the key ids of a retired delegate still inside its grace
     * period — is captured here as well, before the refresh is scheduled. The key id that triggered
     * the lookup is not handed to the refresh: whether a refresh is published depends on the key ids
     * it fetched and on that basis (see {@link #settleRefresh}), never on the key id that asked for
     * it. Runs on the calling request thread and performs no fetch and no blocking wait.
     * <p>
     * Nothing here reaches the request thread as a failure: capturing the basis and handing the
     * refresh to the scheduler are shielded together. A scheduler that refuses the task stays
     * {@code DEBUG}; any other runtime failure — a delegate failing to report its key ids — records
     * {@link ConfigLogMessages.WARN#JWKS_REFRESH_FAILED} once, bounded like the claim itself. Either way
     * the in-flight flag, if this call took it, is released, and the window's refresh is spent.
     */
    private void onUnknownKeyId() {
        if (closed.get()) {
            return;
        }
        boolean refreshEnabled = unknownKeyIdWindow.isPositive();
        long now = monotonicClock.getAsLong();
        long allowed = nextClaimAllowed.get();
        long next = now + (refreshEnabled ? unknownKeyIdWindow : DEFAULT_UNKNOWN_KEY_ID_WINDOW).toNanos();
        // Monotonic readings are compared by their difference, never directly, so a wrap of the
        // nanosecond counter cannot open or close the window.
        if (now - allowed < 0 || !nextClaimAllowed.compareAndSet(allowed, next)) {
            LOGGER.debug("Unknown key id for issuer '%s' inside an already claimed window", issuerName);
            return;
        }
        LOGGER.warn(ConfigLogMessages.WARN.JWKS_UNKNOWN_KEY_ID, issuerName, lastLoaded.get());
        if (!refreshEnabled) {
            return;
        }
        // The lookup is answered from the held key sets whatever happens below, so nothing below may
        // surface on the request thread: capturing the basis reads the delegates' key ids, which is a
        // call into the library, and the scheduler may refuse the task.
        boolean claimed = false;
        boolean scheduled = false;
        // The library's failure types are not part of this contract and the lookup must be answered
        // whatever the capture throws — so the catch is deliberately broad.
        // cui-rewrite:disable InvalidExceptionUsageRecipe
        try {
            // Captured before the in-flight flag is taken; both delegates are read off one snapshot.
            Snapshot current = snapshot.get();
            RefreshBasis basis = RefreshBasis.of(current.delegate(), answeringRetired(current));
            claimed = refreshInFlight.compareAndSet(false, true);
            if (claimed) {
                scheduler.execute(() -> runRefresh(basis));
                scheduled = true;
            }
        } catch (RejectedExecutionException rejected) {
            // The scheduler is shutting down.
            LOGGER.debug(rejected, "JWKS refresh for issuer '%s' could not be scheduled", issuerName);
        } catch (RuntimeException failure) {
            // A failure nobody anticipated, most likely a delegate failing to report its key ids. The
            // claimed window then refreshes nothing, and nothing else reports that. The record names
            // the issuer only: the throwable's message is not under the gateway's control.
            LOGGER.warn(ConfigLogMessages.WARN.JWKS_REFRESH_FAILED, issuerName);
        } finally {
            if (claimed && !scheduled) {
                refreshInFlight.set(false);
            }
        }
    }

    /**
     * One unknown-key-id refresh: builds a fresh delegate and loads it without publishing it, so the
     * current delegate keeps answering until the outcome is known. Runs on the scheduler only.
     * <p>
     * The in-flight flag is released on every path that does not hand the fresh delegate to
     * {@link #settleRefresh}, whatever ended it. A refresh is started by any caller that presents an
     * unknown key id, so a flag left set by a failure nobody anticipated would switch the refresh off
     * for this issuer until the next restart.
     * <p>
     * Such a failure switches nothing off any more, but it still means a key change at the identity
     * provider may go unnoticed until the next window, and nothing else reports that. Every exit that
     * is neither handed to {@link #settleRefresh}, nor one of the anticipated refusals
     * ({@link GatewayException}, {@link IllegalArgumentException}, {@link IllegalStateException}, which
     * stay {@code DEBUG}), nor the return of a closed wrapper therefore records
     * {@link ConfigLogMessages.WARN#JWKS_REFRESH_FAILED} once, from the same {@code finally} that
     * releases the flag — so an error is covered without catching {@link Throwable}, and one refresh
     * yields at most one record. The claim bounds refreshes to one per issuer per window, so the
     * record is bounded the same way.
     *
     * @param basis the comparison basis captured when the window was claimed
     */
    private void runRefresh(RefreshBasis basis) {
        JwksLoader fresh = null;
        boolean handedToSettle = false;
        // True on every exit the WARN must not report: handed to settleRefresh, an anticipated
        // refusal, or a closed wrapper. Only a failure nobody anticipated leaves it false.
        boolean accountedFor = false;
        try {
            SecurityEventCounter counter = eventCounter.get();
            if (closed.get() || counter == null) {
                accountedFor = true;
                return;
            }
            fresh = buildDelegate();
            JwksLoader loading = fresh;
            loadingRefresh.set(loading);
            loading.initJWKSLoader(counter)
                    .whenComplete((status, failure) ->
                            settleRefresh(loading, failure == null ? status : null, basis));
            handedToSettle = true;
            accountedFor = true;
        } catch (GatewayException | IllegalArgumentException | IllegalStateException failure) {
            // The same refusals runRetry handles. A refresh that cannot be built or started changes
            // nothing: the loaded key set stays published and the next window may try again.
            LOGGER.debug(failure, "JWKS loader for issuer '%s' could not be built or started for a refresh",
                    issuerName);
            accountedFor = true;
        } finally {
            if (!handedToSettle) {
                if (fresh != null) {
                    loadingRefresh.compareAndSet(fresh, null);
                    closeDelegate(fresh);
                }
                refreshInFlight.set(false);
            }
            if (!accountedFor) {
                // A failure nobody anticipated is escaping onto the scheduler thread, which records
                // nothing of it. The record names the issuer only: the throwable's message is not
                // under the gateway's control and may carry a URL.
                LOGGER.warn(ConfigLogMessages.WARN.JWKS_REFRESH_FAILED, issuerName);
            }
        }
    }

    /**
     * Settles one refresh: publishes {@code fresh} exactly when it loaded ({@link LoaderStatus#OK}), its
     * key ids include at least one the basis lacks, and the basis is still current — the current
     * delegate is the basis's delegate and still holds the key ids it held at the claim. It closes
     * {@code fresh} otherwise. The basis was captured when the refresh's window was claimed, so the
     * fetched key set is compared against the key sets it was fetched to improve on: the basis lacks
     * a key id exactly when neither the delegate current at the claim nor a retired delegate still
     * inside its grace period at the claim held it. Which key id triggered the refresh plays no part,
     * so it need not be in the published set.
     * <p>
     * A fresh key set equal to the current delegate's, one that only drops key ids, one that only
     * brings back key ids of that retired delegate, and one holding no key id at all are discarded —
     * an unchanged key set changes nothing however many key ids triggered refreshes.
     * {@link LoaderStatus#OK} alone does not imply a key: a library loader may report it while holding
     * none, and the key-id comparison is what keeps such a set from being published. A refresh that
     * settles after the basis moved is discarded whatever it holds: while the fetch ran, the current
     * delegate's own background refresh may have dropped a key, and an older fetched set still holding
     * it would look like a gain against the newer one and bring the dropped key back. A discarded
     * refresh leaves the snapshot untouched and never starts the failed-load retry. A published one is
     * always a key set the identity provider itself served.
     * <p>
     * Concurrency: the delegate itself cannot be replaced while the refresh is in flight — only this
     * refresh publishes, the in-flight flag keeps a second refresh out, and a failed-load retry cannot
     * run concurrently, because a refresh is claimed only while a key set is loaded and the retry exists
     * only while none is. Its key set can move, through its own background refresh, and that is what the
     * basis check catches. The retired delegate can only leave, when its grace period ends; that does
     * not move the basis, so the key ids it held at the claim count as held until this refresh has
     * settled, and a key set that brings back only those is left to a later window. The check and the
     * publication in {@link #publishRefreshed} are a check-then-act without a lock; what remains open
     * is the few instructions between the two, not the duration of the fetch.
     * <p>
     * Settling runs as a completion stage of the fresh delegate's load, whose own future nobody reads,
     * so a failure escaping here would be recorded nowhere. Every exit therefore ends the refresh from
     * one {@code finally}: the fresh delegate is closed unless it is the published one — a failure
     * after the publication keeps it, because it is answering lookups — and the in-flight flag is
     * released. An exit that is neither a discard nor a completed publication records
     * {@link ConfigLogMessages.WARN#JWKS_REFRESH_FAILED} once, as {@link #runRefresh} does for a
     * failure before the load, so a refresh yields at most one record.
     *
     * @param fresh  the refresh's delegate, loaded off to the side and never published before this
     * @param status the load's status, or {@code null} when it completed exceptionally
     * @param basis  the comparison basis captured when the refresh's window was claimed
     */
    private void settleRefresh(JwksLoader fresh, @Nullable LoaderStatus status, RefreshBasis basis) {
        // True on a discard and on a completed publication; only a failure nobody anticipated leaves it
        // false.
        boolean accountedFor = false;
        try {
            if (closed.get() || status != LoaderStatus.OK
                    || basis.holdsAll(fresh.getKeyIds())
                    || !basis.isCurrent(snapshot.get().delegate())) {
                LOGGER.debug("JWKS refresh for issuer '%s' discarded (status %s)", issuerName, status);
                accountedFor = true;
                return;
            }
            publishRefreshed(fresh);
            accountedFor = true;
        } finally {
            if (!accountedFor) {
                LOGGER.warn(ConfigLogMessages.WARN.JWKS_REFRESH_FAILED, issuerName);
            }
            // The flag is still held, so only this refresh can have made fresh the current delegate.
            endRefresh(fresh, snapshot.get().delegate() != fresh);
        }
    }

    /**
     * Ends one settled refresh: closes {@code fresh} when it was not published, then releases the
     * in-flight flag. The flag is released even when closing fails, so a delegate that cannot be
     * closed cannot switch the refresh off for the issuer.
     *
     * @param fresh      the refresh's delegate
     * @param closeFresh whether {@code fresh} is to be closed, which is exactly when it is not the
     *                   current delegate
     */
    private void endRefresh(JwksLoader fresh, boolean closeFresh) {
        try {
            if (closeFresh) {
                closeDelegate(fresh);
            }
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
        boolean retain = keyRotationGracePeriod.isPositive();
        lastLoaded.set(clock.instant());
        long retiredUntil = retain ? monotonicClock.getAsLong() + keyRotationGracePeriod.toNanos() : 0L;
        Snapshot replaced = snapshot.getAndUpdate(current -> retain
                ? new Snapshot(fresh, KeySetState.LOADED, current.delegate(), retiredUntil)
                : new Snapshot(fresh, KeySetState.LOADED, null, 0L));
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

    /**
     * The retired delegate of {@code current} while its grace deadline has not passed. This is the one
     * deadline test, shared by the lookup and by the capture of a refresh's comparison basis, so a
     * retired delegate whose deadline has passed but whose retirement has not run on the scheduler yet
     * counts as gone for both: it answers no lookup, and its key ids do not count as held.
     *
     * @param current the snapshot to read
     * @return the retired delegate that still answers lookups, or {@code null} when there is none
     */
    private @Nullable JwksLoader answeringRetired(Snapshot current) {
        JwksLoader retired = current.retired();
        return retired != null && monotonicClock.getAsLong() - current.retiredUntil() < 0 ? retired : null;
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
     * The current key-set state, as readiness reads it: the outcome of the last completed load attempt,
     * except that a loaded key set whose delegate no longer reports {@link LoaderStatus#OK} — a library
     * loader that degraded it past the issuer's {@code jwks.max_staleness_seconds} bound — reads
     * {@link KeySetState#FAILED}. The degradation is read, not recorded: the published state stays
     * {@link KeySetState#LOADED}, and the next background refresh of the delegate that installs usable
     * keys ends the degradation by itself. No unknown-key-id refresh re-admits a degraded issuer: the
     * library's issuer cache and the back-channel logout verifier refuse its tokens before any key is
     * looked up, so no lookup of such a token reaches this wrapper (ADR-0063). A pure read that never
     * fetches.
     *
     * @return the current key-set state
     */
    KeySetState keySetState() {
        Snapshot current = snapshot.get();
        if (current.state() == KeySetState.LOADED && current.delegate().getLoaderStatus() != LoaderStatus.OK) {
            return KeySetState.FAILED;
        }
        return current.state();
    }

    /**
     * @return {@code true} while a retry is scheduled and has not started yet
     */
    boolean retryPending() {
        ScheduledFuture<?> pending = pendingRetry.get();
        return pending != null && !pending.isDone();
    }

    /**
     * Whether an unknown-key-id refresh has been claimed and not yet settled. A published refresh
     * makes its key set visible before this turns {@code false}, so a reader that must not race the
     * next window's claim waits for both. A pure read that never fetches.
     *
     * @return {@code true} from the claim that schedules a refresh until that refresh has settled
     */
    boolean refreshInProgress() {
        return refreshInFlight.get();
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
     * The current delegate's own status, unchanged: {@link LoaderStatus#OK} exactly while the library
     * reports a usable key set, so a delegate degraded past its staleness bound is reported as
     * {@link LoaderStatus#UNDEFINED}, a delegate that recovered a failed first load by its own refresh
     * as {@link LoaderStatus#OK}, a retry's fresh delegate as whatever it reports while it loads, and a
     * closed one as the library reports it after its close. A pure read: it never triggers a fetch.
     */
    @Override
    public LoaderStatus getLoaderStatus() {
        return snapshot.get().delegate().getLoaderStatus();
    }

    /**
     * Answers from the key sets held at the moment of the call: the current delegate first, then a
     * retired delegate whose grace deadline has not passed. It never fetches and never waits. A key id
     * neither holds yields an empty answer for this call and, while a key set is loaded, may schedule
     * one refresh for a later one. A delegate degraded past its staleness bound still holds its keys and
     * answers from them here; rejecting the degraded issuer fail-closed is the library issuer cache's
     * job, which reads {@link #getLoaderStatus()} and refuses the issuer's tokens before any key is
     * looked up. The delegate's own background refresh re-admits it, never a lookup of this wrapper.
     */
    @Override
    public Optional<KeyInfo> getKeyInfo(String kid) {
        Snapshot current = snapshot.get();
        Optional<KeyInfo> key = current.delegate().getKeyInfo(kid);
        if (key.isPresent()) {
            return key;
        }
        JwksLoader retired = answeringRetired(current);
        if (retired != null) {
            Optional<KeyInfo> retiredKey = retired.getKeyInfo(kid);
            if (retiredKey.isPresent()) {
                return retiredKey;
            }
        }
        if (current.state() == KeySetState.LOADED) {
            onUnknownKeyId();
        }
        return Optional.empty();
    }

    /**
     * The key ids of the current delegate's active key set, as an immutable snapshot taken at the
     * moment of the call. A retired delegate's key ids are not included, even while its grace period
     * lets {@link #getKeyInfo(String)} still resolve them. The current delegate's key ids, captured
     * when an unknown-key-id refresh is claimed, are also part of the basis that refresh is compared
     * against before it is published; that basis adds the key ids of a retired delegate still inside
     * its grace period, which this method leaves out. A pure read: it never fetches and never
     * schedules a refresh.
     *
     * @return the active key set's key ids, empty before the first load, never {@code null}
     */
    @Override
    public Set<String> getKeyIds() {
        return Set.copyOf(snapshot.get().delegate().getKeyIds());
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
