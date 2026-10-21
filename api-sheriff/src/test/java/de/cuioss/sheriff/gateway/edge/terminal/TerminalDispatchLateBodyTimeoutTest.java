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

import de.cuioss.sheriff.gateway.edge.end.ConnectionRetirement;
import de.cuioss.sheriff.gateway.events.EventType;
import de.cuioss.sheriff.gateway.pipeline.FramingGate;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;
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
 * <p>
 * The retirement of the connection after such a timeout is recorded from the upstream request's event
 * loop, so it touches only the synchronized retired set; the failed relay's end learns of it from the
 * late refusal's event, and neither side reads or writes the request's context data for it. The fake
 * routing context counts every context-data access, so a case sees whether the map was touched.
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

    @Test
    @DisplayName("HTTP/1.x — a late request-body timeout is recorded in the retired set without writing the request's context data")
    void lateTimeoutRetirementLeavesTheContextDataAlone() {
        RetiringExchange exchange = new RetiringExchange(HttpVersion.HTTP_1_1, false);
        ConnectionRetirement retirement = new ConnectionRetirement(new FramingGate(false));

        retirement.recordRetirement(exchange.routingContext());

        assertAll("the retirement a foreign event loop records",
                () -> assertTrue(retirement.isRetired(exchange.connection()), "the connection is retired"),
                () -> assertEquals(0, exchange.contextWrites.get(), "the context data map is not written"));
    }

    @Test
    @DisplayName("HTTP/2 — a late request-body timeout retires no connection: only the stream ends")
    void lateTimeoutOnHttp2RetiresNothing() {
        RetiringExchange exchange = new RetiringExchange(HttpVersion.HTTP_2, false);
        ConnectionRetirement retirement = new ConnectionRetirement(new FramingGate(false));

        retirement.recordRetirement(exchange.routingContext());

        assertFalse(retirement.isRetired(exchange.connection()), "an HTTP/2 connection is never retired");
    }

    @Test
    @DisplayName("HTTP/1.x — the failed relay's answer after a late request-body timeout carries Connection: close and closes the connection")
    void failedRelayAfterLateTimeoutClosesTheConnection() {
        RetiringExchange exchange = new RetiringExchange(HttpVersion.HTTP_1_1, false);

        TerminalDispatch.retireFailedRelayConnection(exchange.routingContext(), EventType.REQUEST_BODY_TIMEOUT);
        int closedBeforeTheEnd = exchange.closes.get();
        exchange.endHandlers.forEach(handler -> handler.handle(null));

        assertAll("a late request-body timeout on an HTTP/1.x relay",
                () -> assertEquals(List.of("close"), exchange.connectionHeaders,
                        "the unwritten head advertises Connection: close"),
                () -> assertEquals(1, exchange.endHandlers.size(), "the close is tied to the end of the response"),
                () -> assertEquals(0, closedBeforeTheEnd, "nothing is closed before the response has been written"),
                () -> assertEquals(1, exchange.closes.get(), "the connection is closed once the response has ended"),
                () -> assertEquals(0, exchange.contextReads.get(),
                        "the late timeout is told by its event, not by a mark in the context data"));
    }

    @Test
    @DisplayName("HTTP/1.x — a late request-body timeout on a response whose head is written: the connection is still closed at the end")
    void failedRelayAfterLateTimeoutWithWrittenHeadStillCloses() {
        RetiringExchange exchange = new RetiringExchange(HttpVersion.HTTP_1_1, true);

        TerminalDispatch.retireFailedRelayConnection(exchange.routingContext(), EventType.REQUEST_BODY_TIMEOUT);
        exchange.endHandlers.forEach(handler -> handler.handle(null));

        assertAll("a late request-body timeout after the head went out",
                () -> assertEquals(List.of(), exchange.connectionHeaders, "a written head is not changed"),
                () -> assertEquals(1, exchange.closes.get(), "the connection is closed once the response has ended"));
    }

    @Test
    @DisplayName("HTTP/2 — a late request-body timeout closes no connection at the failed relay's end")
    void failedRelayAfterLateTimeoutOnHttp2ClosesNothing() {
        RetiringExchange exchange = new RetiringExchange(HttpVersion.HTTP_2, false);

        TerminalDispatch.retireFailedRelayConnection(exchange.routingContext(), EventType.REQUEST_BODY_TIMEOUT);

        assertAll("a late request-body timeout on an HTTP/2 stream",
                () -> assertEquals(List.of(), exchange.connectionHeaders, "no Connection header on HTTP/2"),
                () -> assertEquals(0, exchange.endHandlers.size(), "no close is tied to the end of the stream"));
    }

    @Test
    @DisplayName("any other relay failure retires the connection only where an earlier rejection marked the request")
    void otherFailureRetiresOnlyAMarkedRequest() {
        RetiringExchange marked = new RetiringExchange(HttpVersion.HTTP_1_1, false);
        marked.markedForRetirement = true;
        RetiringExchange unmarked = new RetiringExchange(HttpVersion.HTTP_1_1, false);

        TerminalDispatch.retireFailedRelayConnection(marked.routingContext(), null);
        marked.endHandlers.forEach(handler -> handler.handle(null));
        TerminalDispatch.retireFailedRelayConnection(unmarked.routingContext(), null);
        unmarked.endHandlers.forEach(handler -> handler.handle(null));

        assertAll("a relay that failed for another reason",
                () -> assertEquals(1, marked.closes.get(), "a marked request's connection is closed"),
                () -> assertEquals(0, unmarked.closes.get(), "an unmarked request's connection is left open"));
    }

    /**
     * A request on a fake connection and its routing context: the HTTP version, whether the response head
     * has been written, the {@code Connection} headers put on it, the handlers registered for its end, the
     * closes of the connection, and how often the context data map is written or read. A context datum
     * reads as the retirement mark exactly when {@link #markedForRetirement} is set.
     */
    private static final class RetiringExchange {

        private final HttpVersion version;
        private final boolean headWritten;
        private final AtomicInteger closes = new AtomicInteger();
        private final AtomicInteger contextWrites = new AtomicInteger();
        private final AtomicInteger contextReads = new AtomicInteger();
        private final List<String> connectionHeaders = new ArrayList<>();
        private final List<Handler<AsyncResult<Void>>> endHandlers = new ArrayList<>();
        private boolean markedForRetirement;
        private final HttpConnection connection = fake(HttpConnection.class, (name, args) -> {
            if ("close".equals(name) && args.length == 0) {
                closes.incrementAndGet();
                return Future.succeededFuture();
            }
            throw new UnsupportedOperationException("the connection fake does not answer " + name);
        });

        RetiringExchange(HttpVersion version, boolean headWritten) {
            this.version = version;
            this.headWritten = headWritten;
        }

        HttpConnection connection() {
            return connection;
        }

        RoutingContext routingContext() {
            HttpServerRequest request = fake(HttpServerRequest.class, (name, args) -> switch (name) {
                case "version" -> version;
                case "connection" -> connection;
                default -> throw new UnsupportedOperationException("the request fake does not answer " + name);
            });
            HttpServerResponse response = fake(HttpServerResponse.class, (name, args) -> switch (name) {
                case "headWritten" -> headWritten;
                case "putHeader" -> {
                    connectionHeaders.add(String.valueOf(args[1]));
                    yield null;
                }
                default -> throw new UnsupportedOperationException("the response fake does not answer " + name);
            });
            return fake(RoutingContext.class, (name, args) -> switch (name) {
                case "request" -> request;
                case "response" -> response;
                case "put" -> {
                    contextWrites.incrementAndGet();
                    yield null;
                }
                case "get" -> {
                    contextReads.incrementAndGet();
                    yield markedForRetirement ? Boolean.TRUE : null;
                }
                case "addEndHandler" -> {
                    @SuppressWarnings("unchecked") // the fake hands back what the caller registered
                    Handler<AsyncResult<Void>> handler = (Handler<AsyncResult<Void>>) args[0];
                    endHandlers.add(handler);
                    yield endHandlers.size(); // the id Vert.x hands back for the registered handler
                }
                default -> throw new UnsupportedOperationException("the routing context fake does not answer " + name);
            });
        }
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
                (proxy, method, args) -> switch (method.getName()) {
                    // The identity methods a retired-connection set or a map key asks of any object.
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> type.getSimpleName() + " fake";
                    default -> answer.answer(method.getName(), args == null ? new Object[0] : args);
                }));
    }
}
