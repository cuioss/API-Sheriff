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
package de.cuioss.sheriff.gateway.tls;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;


import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClient;
import io.vertx.core.net.NetSocket;

/**
 * Proxies that let a relay test take control of the two events a {@link PassthroughRelay} races
 * during its dial phase: the backend dial completing and the client leaving. Shared by
 * {@link PassthroughRelayTest} and {@link SniFrontListenerTest}, so the front's slot accounting is
 * driven by exactly the dial the relay's own tests hold. Two more proxies make the first dial fail,
 * either by throwing from the dial call itself, so a front test can drive a hand-off the relay refuses
 * before any dial starts, or by returning a failed dial.
 * <p>
 * Thread-safe: the class is stateless; each proxy confines its callbacks to the Vert.x context it was
 * called on, and each first-dial proxy decides its one failure with an atomic compare-and-set.
 */
final class RelayTestProxies {

    /** {@link Handler#handle}, invoked reflectively so a chained handler needs no unchecked cast. */
    private static final Method HANDLE = handleMethod();

    private RelayTestProxies() {
        // utility class
    }

    /**
     * A dial client whose {@code connect(int, String)} — the one the relay calls — runs
     * {@code onRequest} and dials only once {@code release} completes, on the caller's own context so
     * the relay's callbacks keep running on the accepted connection's event loop. A dial requested
     * after {@code release} completed proceeds at once. Every other call goes to {@code delegate}.
     *
     * @param delegate  the real dial client
     * @param onRequest run once per dial the relay requests, on the requesting event loop
     * @param release   completing it lets every held dial, and every later one, proceed
     * @return the holding dial client
     */
    static NetClient heldDialClient(NetClient delegate, Runnable onRequest, CompletableFuture<Void> release) {
        return (NetClient) Proxy.newProxyInstance(NetClient.class.getClassLoader(), new Class<?>[]{NetClient.class},
                (proxy, method, args) -> {
                    if ("connect".equals(method.getName()) && args != null && args.length == 2
                            && args[0] instanceof Integer port && args[1] instanceof String host) {
                        Context caller = Vertx.currentContext();
                        Promise<NetSocket> dialled = Promise.promise();
                        release.thenRun(() -> caller.runOnContext(v -> delegate.connect(port, host)
                                .onComplete(result -> {
                                    if (result.succeeded()) {
                                        dialled.complete(result.result());
                                    } else {
                                        dialled.fail(result.cause());
                                    }
                                })));
                        onRequest.run();
                        return dialled.future();
                    }
                    return invokeOn(delegate, method, args);
                });
    }

    /**
     * A dial client whose first {@code connect(int, String)} — the one the relay calls — throws
     * {@code failure} instead of returning a future, as Vert.x does for a backend port outside 0 to
     * 65535. Every later dial, and every other call, goes to {@code delegate}.
     *
     * @param delegate the real dial client
     * @param failure  thrown by the first dial
     * @return the dial client whose first dial throws
     */
    static NetClient firstDialThrows(NetClient delegate, RuntimeException failure) {
        AtomicBoolean thrown = new AtomicBoolean();
        return (NetClient) Proxy.newProxyInstance(NetClient.class.getClassLoader(), new Class<?>[]{NetClient.class},
                (proxy, method, args) -> {
                    if ("connect".equals(method.getName()) && args != null && args.length == 2
                            && args[0] instanceof Integer && args[1] instanceof String
                            && thrown.compareAndSet(false, true)) {
                        throw failure;
                    }
                    return invokeOn(delegate, method, args);
                });
    }

    /**
     * A dial client whose first {@code connect(int, String)} — the one the relay calls — returns a
     * failed future carrying {@code failure}, as a refused or timed-out connect does. Every later dial,
     * and every other call, goes to {@code delegate}.
     *
     * @param delegate the real dial client
     * @param failure  the cause the first dial fails with
     * @return the dial client whose first dial fails
     */
    static NetClient firstDialFails(NetClient delegate, Throwable failure) {
        AtomicBoolean failed = new AtomicBoolean();
        return (NetClient) Proxy.newProxyInstance(NetClient.class.getClassLoader(), new Class<?>[]{NetClient.class},
                (proxy, method, args) -> {
                    if ("connect".equals(method.getName()) && args != null && args.length == 2
                            && args[0] instanceof Integer && args[1] instanceof String
                            && failed.compareAndSet(false, true)) {
                        return Future.failedFuture(failure);
                    }
                    return invokeOn(delegate, method, args);
                });
    }

    /**
     * The accepted connection as the relay sees it, with its close observed: {@code closed} completes
     * when the connection closes, whether or not the relay has installed a close handler of its own —
     * one the relay installs is run first and chained, never replaced.
     *
     * @param accepted the accepted connection
     * @param closed   completed once the connection has closed and the relay's handler has run
     * @return the observing view of {@code accepted}
     */
    static NetSocket closeObserved(NetSocket accepted, CompletableFuture<Void> closed) {
        accepted.closeHandler(v -> closed.complete(null));
        return (NetSocket) Proxy.newProxyInstance(NetSocket.class.getClassLoader(), new Class<?>[]{NetSocket.class},
                (proxy, method, args) -> {
                    if ("closeHandler".equals(method.getName()) && args != null && args.length == 1) {
                        Object relayHandler = args[0];
                        accepted.closeHandler(v -> {
                            if (relayHandler != null) {
                                invokeOn(relayHandler, HANDLE, new Object[]{v});
                            }
                            closed.complete(null);
                        });
                        return proxy;
                    }
                    return invokeOn(accepted, method, args);
                });
    }

    private static Method handleMethod() {
        try {
            return Handler.class.getMethod("handle", Object.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Invokes {@code method} on {@code target}, rethrowing what the method itself threw. */
    private static Object invokeOn(Object target, Method method, Object[] args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }
}
