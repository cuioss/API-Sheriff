/*
 * Copyright © 2022 CUI-OpenSource-Software (info@cuioss.de)
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
package de.cuioss.sheriff.api.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import de.cuioss.sheriff.api.integration.grpc.EchoGrpc;
import de.cuioss.sheriff.api.integration.grpc.EchoRequest;
import de.cuioss.sheriff.api.integration.grpc.EchoResponse;
import de.cuioss.sheriff.api.integration.grpc.SecureEchoGrpc;

import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

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
 * is rejected {@code UNAUTHENTICATED} as a trailers-only gRPC response before any upstream dial. The
 * bearer route rides a distinct {@code SecureEcho} service path (a bearer route on the same
 * {@code Echo} path would not be statically disjoint from the public route and would fail the boot),
 * driven by the stock generated {@link SecureEchoGrpc} stub.
 */
class GrpcProxyIT extends BaseIntegrationTest {

    private static final int CALL_TIMEOUT_SECONDS = 15;

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
    @DisplayName("a tokenless call on a bearer gRPC route is rejected UNAUTHENTICATED (trailers-only)")
    void unauthenticatedBearerGrpcRejected() {
        SecureEchoGrpc.SecureEchoBlockingStub bearerStub = SecureEchoGrpc.newBlockingStub(channel);

        StatusRuntimeException failure = assertThrows(StatusRuntimeException.class,
                () -> bearerStub.unary(EchoRequest.newBuilder().setMessage("no-token").build()),
                "a tokenless bearer gRPC call must be rejected before any upstream dial");

        assertEquals(Status.Code.UNAUTHENTICATED, failure.getStatus().getCode(),
                "a gateway-generated gRPC rejection maps 401 to UNAUTHENTICATED as a trailers-only response");
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
