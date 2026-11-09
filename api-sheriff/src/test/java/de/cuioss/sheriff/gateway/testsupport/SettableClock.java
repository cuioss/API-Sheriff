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
package de.cuioss.sheriff.gateway.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A UTC {@link Clock} whose instant a test sets, so a test can move time inside a seam the code under
 * test calls — for example advance it during an authorization push, to observe which reading the code
 * takes after the push returned.
 * <p>
 * Thread-safe: the instant is held in an {@link AtomicReference}.
 */
public final class SettableClock extends Clock {

    private final AtomicReference<Instant> instant;

    /**
     * @param start the instant the clock reads until it is {@linkplain #set(Instant) set}
     */
    public SettableClock(Instant start) {
        this.instant = new AtomicReference<>(Objects.requireNonNull(start, "start"));
    }

    /**
     * @param next the instant every later reading returns
     */
    public void set(Instant next) {
        instant.set(Objects.requireNonNull(next, "next"));
    }

    @Override
    public Instant instant() {
        return instant.get();
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("a settable test clock stays in UTC");
    }
}
