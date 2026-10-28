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
package de.cuioss.sheriff.gateway.edge.upstream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.cuioss.test.generator.Generators;
import de.cuioss.test.generator.junit.EnableGeneratorController;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The client-departure channel of {@link DispatchStage.InboundBody}: how a response that ended without
 * being answered reaches the dispatch that is still waiting for its upstream answer. The end-to-end
 * effect — the upstream request released at once — is pinned by {@code GatewayEdgeHttp2AbuseTest}.
 */
@EnableGeneratorController
@DisplayName("InboundBody — a client departure reaches the dispatch once, replayed to a late handler")
class InboundBodyDepartureTest {

    private static Throwable departureCause() {
        return new IllegalStateException(Generators.letterStrings(8, 16).next());
    }

    @Test
    @DisplayName("a departure recorded before the dispatch registers is replayed to it on registration")
    void departureBeforeRegistrationIsReplayed() {
        DispatchStage.InboundBody body = DispatchStage.InboundBody.arm(new SilentStream());
        Throwable cause = departureCause();
        List<Throwable> delivered = new CopyOnWriteArrayList<>();

        body.clientDeparted(cause);
        body.departureHandler(delivered::add);

        assertAll("a departure before registration",
                () -> assertSame(cause, body.departure(), "the departure is recorded"),
                () -> assertEquals(List.of(cause), delivered, "the late handler receives it once"));
    }

    @Test
    @DisplayName("a departure reaches a registered handler once; a second departure is ignored")
    void departureIsDeliveredOnce() {
        DispatchStage.InboundBody body = DispatchStage.InboundBody.arm(new SilentStream());
        Throwable first = departureCause();
        List<Throwable> delivered = new CopyOnWriteArrayList<>();
        body.departureHandler(delivered::add);

        body.clientDeparted(first);
        body.clientDeparted(departureCause());

        assertAll("two departures",
                () -> assertSame(first, body.departure(), "the first departure stands"),
                () -> assertEquals(List.of(first), delivered, "the handler receives the first one only"));
    }

    @Test
    @DisplayName("control — a stream the gateway reset itself records no departure")
    void gatewayResetStreamRecordsNoDeparture() {
        SilentStream delegate = new SilentStream();
        DispatchStage.InboundBody body = DispatchStage.InboundBody.arm(delegate);
        List<Throwable> delivered = new CopyOnWriteArrayList<>();
        body.departureHandler(delivered::add);

        body.resetByGateway();
        body.clientDeparted(departureCause());

        assertAll("a departure after the gateway's own reset",
                () -> assertNull(body.departure(), "no departure is recorded"),
                () -> assertTrue(delivered.isEmpty(), "nothing is delivered"),
                () -> assertTrue(delegate.paused, "the gateway's reset paused the body"));
    }

    /** A request body that never emits anything; it only remembers being paused. */
    private static final class SilentStream implements ReadStream<Buffer> {

        private volatile boolean paused;

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            paused = true;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
        }
    }
}
