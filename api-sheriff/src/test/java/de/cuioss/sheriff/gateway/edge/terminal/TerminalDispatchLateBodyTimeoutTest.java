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
package de.cuioss.sheriff.gateway.edge.terminal;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HTTP/1.x connection close that follows a request body outliving the request-body deadline after
 * the hand-over ({@link TerminalDispatch#closeIfEndedOn}). The late callback runs on the upstream
 * request's event loop while the client response is ended on the client connection's, so whether the
 * response has ended is decided on the client connection's context.
 * <p>
 * The client context is a hand-driven fake: a task it is given runs only when a case runs it, so a
 * case can end the response after the callback and before the decision, the order a relay that ends
 * normally on the client connection's loop just after the callback produces.
 */
@DisplayName("TerminalDispatch — the connection close after a request body outlived its deadline")
class TerminalDispatchLateBodyTimeoutTest {

    @Test
    @DisplayName("HTTP/1.x — a response ended normally after the callback and before the decision: the connection is closed")
    void responseEndedBeforeTheDecisionClosesTheConnection() {
        Exchange exchange = new Exchange();

        TerminalDispatch.closeIfEndedOn(exchange.clientContext(), exchange.request());
        int closedBeforeTheDecision = exchange.closes.get();
        exchange.ended.set(true);
        exchange.runClientTasks();

        assertAll("a response the relay ended normally just before the decision",
                () -> assertEquals(0, closedBeforeTheDecision, "nothing is decided on the callback's thread"),
                () -> assertEquals(1, exchange.tasksRun.get(), "the decision runs once, on the client context"),
                () -> assertEquals(1, exchange.closes.get(), "the connection of the ended response is closed"));
    }

    @Test
    @DisplayName("HTTP/1.x — a response not ended at the decision: the connection is left to the relay's end")
    void responseNotEndedAtTheDecisionIsLeftToTheRelaysEnd() {
        Exchange exchange = new Exchange();

        TerminalDispatch.closeIfEndedOn(exchange.clientContext(), exchange.request());
        exchange.runClientTasks();

        assertAll("a response still open at the decision",
                () -> assertEquals(1, exchange.tasksRun.get(), "the decision runs once, on the client context"),
                () -> assertEquals(0, exchange.closes.get(),
                        "the connection is not closed here: the relay's end closes the marked connection"));
    }

    /**
     * One client exchange made of fakes: the request, its response and connection, and the client
     * connection's context, which queues every task it is given until {@link #runClientTasks()}.
     */
    private static final class Exchange {

        private final AtomicBoolean ended = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger tasksRun = new AtomicInteger();
        private final List<Handler<?>> clientTasks = new ArrayList<>();

        void runClientTasks() {
            List<Handler<?>> due = List.copyOf(clientTasks);
            clientTasks.clear();
            for (Handler<?> task : due) {
                tasksRun.incrementAndGet();
                task.handle(null);
            }
        }

        Context clientContext() {
            return fake(Context.class, (name, args) -> {
                if ("runOnContext".equals(name)) {
                    clientTasks.add((Handler<?>) args[0]);
                    return null;
                }
                throw new UnsupportedOperationException("the client context fake does not answer " + name);
            });
        }

        HttpServerRequest request() {
            HttpServerResponse response = fake(HttpServerResponse.class, (name, args) -> {
                if ("ended".equals(name)) {
                    return ended.get();
                }
                throw new UnsupportedOperationException("the response fake does not answer " + name);
            });
            HttpConnection connection = fake(HttpConnection.class, (name, args) -> {
                if ("close".equals(name) && args.length == 0) {
                    closes.incrementAndGet();
                    return Future.succeededFuture();
                }
                throw new UnsupportedOperationException("the connection fake does not answer " + name);
            });
            return fake(HttpServerRequest.class, (name, args) -> switch (name) {
                case "response" -> response;
                case "connection" -> connection;
                default -> throw new UnsupportedOperationException("the request fake does not answer " + name);
            });
        }
    }

    /** Answers one method call of a fake by its name and arguments. */
    @FunctionalInterface
    private interface Answer {

        @Nullable
        Object answer(String name, Object[] args);
    }

    private static <T> T fake(Class<T> type, Answer answer) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (_, method, args) -> answer.answer(method.getName(), args == null ? new Object[0] : args)));
    }
}
