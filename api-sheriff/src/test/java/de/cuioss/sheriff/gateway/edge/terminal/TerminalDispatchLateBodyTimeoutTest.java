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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
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
 * <p>
 * The head the failed relay answers with ({@link TerminalDispatch#prepareFailedRelayHead}) is driven
 * against a fake response: a relay a late request-body timeout ended before its head was written keeps
 * the status the upstream sent, also when the relay had not set it yet — the deadline passed between the
 * hand-over and the relay starting.
 */
@DisplayName("TerminalDispatch — the connection close and the answer after a request body outlived its deadline")
class TerminalDispatchLateBodyTimeoutTest {

    private static final String CONTENT_LENGTH = "Content-Length";
    private static final String RELAYED_HEADER = "X-Relayed";
    private static final String RELAYED_VALUE = "kept";
    /** A status other than the {@code 200} the relay of a fresh response starts from. */
    private static final int UPSTREAM_STATUS = 201;
    private static final int BAD_GATEWAY = 502;

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

    @Test
    @DisplayName("a relay a late request-body timeout failed before its head was written: the upstream status stands, with no body")
    void lateBodyTimeoutBeforeTheHeadKeepsTheUpstreamStatus() {
        FailedRelayResponse response = new FailedRelayResponse(false);

        TerminalDispatch.prepareFailedRelayHead(response.fake(), true, UPSTREAM_STATUS);

        assertAll("the unwritten head of a relay a late request-body timeout ended",
                () -> assertEquals(List.of(UPSTREAM_STATUS), response.statuses,
                        "the status the upstream sent is set, whatever the relay had set before it failed"),
                () -> assertFalse(response.headers.contains(CONTENT_LENGTH), "no body byte follows the head"),
                () -> assertEquals(RELAYED_VALUE, response.headers.get(RELAYED_HEADER),
                        "the headers the relay copied are kept"));
    }

    @Test
    @DisplayName("a relay that failed for any other reason before its head was written: 502, with no body")
    void otherFailureBeforeTheHeadIsAnswered502() {
        FailedRelayResponse response = new FailedRelayResponse(false);

        TerminalDispatch.prepareFailedRelayHead(response.fake(), false, UPSTREAM_STATUS);

        assertAll("the unwritten head of a relay that failed on its own",
                () -> assertEquals(List.of(BAD_GATEWAY), response.statuses, "the failure is answered 502"),
                () -> assertFalse(response.headers.contains(CONTENT_LENGTH), "the 502 carries no body"));
    }

    @Test
    @DisplayName("a relay that failed after its head was written: the head is left as it was")
    void failureAfterTheHeadLeavesTheHeadAsItWas() {
        FailedRelayResponse response = new FailedRelayResponse(true);

        TerminalDispatch.prepareFailedRelayHead(response.fake(), true, UPSTREAM_STATUS);

        assertAll("the written head of a failed relay",
                () -> assertEquals(List.of(), response.statuses, "no status is set on a written head"),
                () -> assertTrue(response.headers.contains(CONTENT_LENGTH), "the written head is not changed"));
    }

    /**
     * The response of a failed relay as a fake: whether its head was written, the headers the relay copied
     * from the upstream — a {@code Content-Length} among them — and every status set on it.
     */
    private static final class FailedRelayResponse {

        private final boolean headWritten;
        private final MultiMap headers = MultiMap.caseInsensitiveMultiMap()
                .add(CONTENT_LENGTH, "42")
                .add(RELAYED_HEADER, RELAYED_VALUE);
        private final List<Integer> statuses = new ArrayList<>();

        FailedRelayResponse(boolean headWritten) {
            this.headWritten = headWritten;
        }

        HttpServerResponse fake() {
            return TerminalDispatchLateBodyTimeoutTest.fake(HttpServerResponse.class, (name, args) -> switch (name) {
                case "headWritten" -> headWritten;
                case "headers" -> headers;
                case "setStatusCode" -> {
                    statuses.add((Integer) args[0]);
                    yield null;
                }
                default -> throw new UnsupportedOperationException("the response fake does not answer " + name);
            });
        }
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
