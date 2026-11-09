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
package de.cuioss.sheriff.gateway.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.gateway.edge.upstream.GrpcDispatchStage;
import de.cuioss.sheriff.gateway.edge.upstream.UpstreamGuards;
import de.cuioss.sheriff.gateway.integration.grpc.EchoGrpc;
import de.cuioss.sheriff.gateway.integration.grpc.EchoRequest;
import de.cuioss.sheriff.gateway.integration.grpc.EchoResponse;
import de.cuioss.sheriff.gateway.integration.grpc.SecureEchoGrpc;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code protocol: grpc} dispatch path end-to-end through the gateway against the
 * in-repo Quarkus gRPC echo upstream, driving the opaque forced-h2 relay, the {@code grpc-status} /
 * trailer relay, and the trailers-only rejection contract (deliverable 11) over the mounted
 * {@code endpoints/grpc.yaml} routes.
 * <p>
 * The gateway routes gRPC on the <em>bare service path</em> (operator decision 2026-07-21): a route
 * matches the service-rooted {@code /{package}.Echo} prefix and sets an identical
 * {@code upstream.path}, so the full {@code /{package}.Echo/{Method}} path reaches the upstream
 * unchanged. There is no synthetic anchor prefix and no client-side path rewriting — a stock
 * grpc-java channel dials the real service path and works as-is, so this suite installs
 * <em>no</em> path-rewriting interceptor.
 * <p>
 * Metadata injection is exercised two ways on the echo route: the client attaches an
 * {@code x-echo-meta} request-metadata header (allow-listed in {@code grpc.yaml}), and the route's
 * {@code forward.set_headers} injects a gateway-side {@code x-sheriff-injected} header toward the
 * upstream. The echo scaffolding does not reflect metadata back (the gateway relay is opaque and
 * never inspects the protobuf payload), so the assertion is that the unary/streaming echo
 * round-trips faithfully with both metadata surfaces active — proving the metadata path does not
 * corrupt the relay. Following the {@link BearerValidationIT} precedent, the bearer gRPC route is
 * exercised through its <em>rejection</em> path (no signing key to mint a token): a tokenless call
 * is refused before any upstream dial. The refusal is raised before the trust boundary, so the shipped
 * default answers it as the unrouted HTTP {@code 404} rather than the trailers-only
 * {@code UNAUTHENTICATED} that would disclose the route (ADR-0073); the gRPC client surfaces an HTTP
 * {@code 404} as {@code UNIMPLEMENTED}, exactly as it surfaces a call on a service no route serves
 * ({@code EnumerationOracleIT} compares the two). The trailers-only contract itself still holds for
 * every rejection past the trust boundary. The
 * bearer route rides a distinct {@code SecureEcho} service path (a bearer route on the same
 * {@code Echo} path would not be statically disjoint from the public route and would fail the boot),
 * driven by the stock generated {@link SecureEchoGrpc} stub.
 */
class GrpcProxyIT extends BaseIntegrationTest {

    private static final int CALL_TIMEOUT_SECONDS = 15;

    /**
     * The client deadline of the HEADERS-only call: beyond the guard's 30-second timeout, so the client
     * never ends the call itself and the status it observes is always the gateway's.
     */
    private static final long IDLE_CALL_DEADLINE_SECONDS = UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS + 15L;

    /** Slack around the first-message bound for scheduling and the client's own clock. */
    private static final long TIMING_TOLERANCE_MILLIS = 1_000;

    private static ManagedChannel channel;

    @BeforeAll
    static void setUpGrpcChannel() throws Exception {
        int testPort = Integer.parseInt(System.getProperty("test.https.port", "10443"));
        // Trust-all TLS over ALPN h2 for the stack's self-signed localhost certificate — the gRPC
        // analogue of BaseIntegrationTest's relaxed REST Assured validation. Scoped strictly to this
        // black-box integration test against a throwaway local certificate, never production trust.
        SslContext sslContext = GrpcSslContexts.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
        channel = NettyChannelBuilder.forAddress("localhost", testPort)
                .overrideAuthority("localhost")
                .sslContext(sslContext)
                .build();
    }

    @AfterAll
    static void tearDownGrpcChannel() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("a unary echo round-trips through the gateway with client + gateway-injected metadata")
    void unaryEchoThroughGateway() {
        EchoResponse response = echoStub().unary(EchoRequest.newBuilder().setMessage("sheriff-grpc").build());

        assertEquals("sheriff-grpc", response.getMessage(), "the opaque relay must round-trip the unary echo verbatim");
        assertEquals(0, response.getIndex(), "the unary echo is a single response at index 0");
    }

    @Test
    @DisplayName("a server-streaming echo relays every response frame in order through the gateway")
    void serverStreamEchoThroughGateway() {
        Iterator<EchoResponse> responses = echoStub()
                .serverStream(EchoRequest.newBuilder().setMessage("stream-me").setCount(3).build());

        List<EchoResponse> collected = new ArrayList<>();
        responses.forEachRemaining(collected::add);

        assertEquals(3, collected.size(), "the gateway must relay every server-streamed response frame");
        for (int index = 0; index < collected.size(); index++) {
            assertEquals("stream-me", collected.get(index).getMessage(), "each streamed frame echoes the message");
            assertEquals(index, collected.get(index).getIndex(), "streamed frames arrive in order with their index");
        }
    }

    @Test
    @DisplayName("the gateway relays a non-OK grpc-status and its trailers from the failing method")
    void grpcStatusRelayFromFailingMethod() {
        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> echoStub().fail(EchoRequest.newBuilder().setMessage("boom").build()),
                "the failing method must surface a non-OK gRPC status through the gateway");

        assertEquals(Status.Code.FAILED_PRECONDITION, failure.getStatus().getCode(),
                "the upstream grpc-status must be relayed to the client unchanged");
        assertTrue(failure.getStatus().getDescription() != null
                && failure.getStatus().getDescription().contains("intentional failure"),
                "the upstream grpc-message trailer must be relayed to the client");
    }

    @Test
    @DisplayName("a tokenless call on a bearer gRPC route is refused as the unrouted HTTP 404, surfacing as UNIMPLEMENTED")
    void unauthenticatedBearerGrpcRejected() {
        SecureEchoGrpc.SecureEchoBlockingStub bearerStub = SecureEchoGrpc.newBlockingStub(channel)
                .withDeadlineAfter(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> bearerStub.unary(EchoRequest.newBuilder().setMessage("no-token").build()),
                "a tokenless bearer gRPC call must be rejected before any upstream dial");

        assertEquals(Status.Code.UNIMPLEMENTED, failure.getStatus().getCode(),
                "a pre-trust refusal on a gRPC route is the unrouted HTTP 404, which a gRPC client surfaces as "
                        + "UNIMPLEMENTED — never the trailers-only UNAUTHENTICATED that would name the bearer route");
    }

    /**
     * A call that opens its stream and never sends a request message is ended by the gateway's
     * first-message bound, as the client's own abort, and does not count against the upstream.
     * <p>
     * The call is driven through a raw {@link ClientCall} that is started and never sent anything — no
     * {@code sendMessage}, no {@code halfClose} — so the gateway holds a gRPC call whose upstream request
     * head it can never write. It dials the real {@code Echo/Unary} path, but through a descriptor typed
     * {@link MethodDescriptor.MethodType#BIDI_STREAMING}: grpc-netty flushes the HEADERS frame on
     * {@code start} only for a method whose client may send more than one message. For a unary (or
     * server-streaming) descriptor it queues HEADERS unflushed until the first message, so a unary
     * call that sends nothing never reaches the wire, and the gateway would never see it. The method
     * type is client-side only — it is not on the wire — so the route, the upstream and the path are
     * those of the ordinary unary call. The gateway must end it after
     * {@link GrpcDispatchStage#GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS} with the trailers-only rendering of
     * {@code INBOUND_BODY_ABORTED}, {@code INVALID_ARGUMENT}, well before the guard's
     * {@link UpstreamGuards#UPSTREAM_TIMEOUT_SECONDS}-second timeout would end it as the upstream's
     * failure with {@code DEADLINE_EXCEEDED}. The client's own deadline is set beyond both, so neither
     * status can be the client giving up.
     * <p>
     * The ordinary unary call that follows on the same route is the matched control: a gateway that
     * counted the idle call against the upstream's circuit breaker, or left the route wedged, would not
     * relay it.
     */
    @Test
    @DisplayName("a call sending HEADERS only is ended as a client abort after the first-message bound, "
            + "and the route still serves the next call")
    void headersOnlyCallIsEndedByTheFirstMessageBound() throws Exception {
        long bound = GrpcDispatchStage.GRPC_FIRST_MESSAGE_TIMEOUT_MILLIS;
        long guardTimeout = TimeUnit.SECONDS.toMillis(UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS);
        CompletableFuture<Status> closed = new CompletableFuture<>();
        MethodDescriptor<EchoRequest, EchoResponse> headersFlushedOnStart = EchoGrpc.getUnaryMethod().toBuilder()
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .build();
        ClientCall<EchoRequest, EchoResponse> call = channel.newCall(headersFlushedOnStart,
                CallOptions.DEFAULT.withDeadlineAfter(IDLE_CALL_DEADLINE_SECONDS, TimeUnit.SECONDS));

        long started = System.nanoTime();
        call.start(new ClientCall.Listener<>() {
            @Override
            public void onClose(Status status, Metadata trailers) {
                closed.complete(status);
            }
        }, new Metadata());
        call.request(1);
        Status status = closed.get(IDLE_CALL_DEADLINE_SECONDS + 5L, TimeUnit.SECONDS);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertAll("the idle call is ended by the first-message bound, as the client's abort",
                () -> assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode(),
                        () -> "a call with no request message ends as INBOUND_BODY_ABORTED, rendered "
                                + "INVALID_ARGUMENT — DEADLINE_EXCEEDED would mean the guard's timeout ended "
                                + "it as the upstream's failure. Status was " + status),
                () -> assertTrue(elapsedMillis >= bound - TIMING_TOLERANCE_MILLIS,
                        () -> "the gateway must wait out the " + bound + " ms first-message bound before "
                                + "ending the call; it ended after " + elapsedMillis + " ms"),
                () -> assertTrue(elapsedMillis < bound + TIMING_TOLERANCE_MILLIS * 10,
                        () -> "the call must end at the first-message bound, well before the guard's "
                                + guardTimeout + " ms timeout; it ended after " + elapsedMillis + " ms"));

        EchoResponse next = echoStub().unary(EchoRequest.newBuilder().setMessage("after-idle-call").build());

        assertEquals("after-idle-call", next.getMessage(),
                "THE CONTROL: the next ordinary call on the same route is relayed, so the idle call left "
                        + "the route serving and the breaker did not count it against the upstream");
    }

    /**
     * A blocking stub for the public {@code grpc-echo} route: a stock channel dials the real
     * {@code /{package}.Echo/{Method}} service path (no path rewriting), and an {@code x-echo-meta}
     * request-metadata header is attached to exercise the client-side metadata path.
     */
    private static EchoGrpc.EchoBlockingStub echoStub() {
        Metadata metadata = new Metadata();
        metadata.put(Metadata.Key.of("x-echo-meta", Metadata.ASCII_STRING_MARSHALLER), "sheriff-client");
        return EchoGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
                .withDeadlineAfter(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }
}
