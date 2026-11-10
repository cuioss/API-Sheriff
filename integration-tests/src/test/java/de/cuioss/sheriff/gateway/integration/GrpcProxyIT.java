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
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.RequestOptions;
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
     * The client deadline of a call the gateway is expected to end itself: beyond the guard's 30-second
     * timeout, so the client never ends the call itself and the status it observes is always the gateway's.
     */
    private static final long IDLE_CALL_DEADLINE_SECONDS = UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS + 15L;

    /** Slack around the message bound for scheduling and the client's own clock. */
    private static final long TIMING_TOLERANCE_MILLIS = 1_000;

    /** The length of a gRPC message's prefix: one compressed-flag byte and a 4-byte big-endian length. */
    private static final int GRPC_PREFIX_BYTES = 5;

    private static ManagedChannel channel;

    /** The raw HTTP/2 client that frames gRPC messages by hand, so a call can stop inside a message. */
    private static Vertx vertx;
    private static HttpClient rawClient;

    @BeforeAll
    static void setUpGrpcChannel() throws Exception {
        int testPort = testPort();
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
        vertx = Vertx.vertx();
        // The same trust-all scope as the channel above: the stack's self-signed localhost certificate,
        // trusted blindly by this black-box IT only.
        rawClient = vertx.createHttpClient(new HttpClientOptions()
                .setProtocolVersion(HttpVersion.HTTP_2)
                .setSsl(true)
                .setUseAlpn(true)
                .setTrustAll(true)
                .setVerifyHost(false));
    }

    @AfterAll
    static void tearDownGrpcChannel() throws Exception {
        if (channel != null) {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
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
     * message bound, as the client's own abort, and does not count against the upstream.
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
     * {@link GrpcDispatchStage#GRPC_MESSAGE_TIMEOUT_MILLIS} with the trailers-only rendering of
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
    @DisplayName("a call sending HEADERS only is ended as a client abort after the message bound, "
            + "and the route still serves the next call")
    void headersOnlyCallIsEndedByTheMessageBound() throws Exception {
        long bound = GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS;
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

        assertAll("the idle call is ended by the message bound, as the client's abort",
                () -> assertEquals(Status.Code.INVALID_ARGUMENT, status.getCode(),
                        () -> "a call with no request message ends as INBOUND_BODY_ABORTED, rendered "
                                + "INVALID_ARGUMENT — DEADLINE_EXCEEDED would mean the guard's timeout ended "
                                + "it as the upstream's failure. Status was " + status),
                () -> assertTrue(elapsedMillis >= bound - TIMING_TOLERANCE_MILLIS,
                        () -> "the gateway must wait out the " + bound + " ms message bound before "
                                + "ending the call; it ended after " + elapsedMillis + " ms"),
                () -> assertTrue(elapsedMillis < bound + TIMING_TOLERANCE_MILLIS * 10,
                        () -> "the call must end at the message bound, well before the guard's "
                                + guardTimeout + " ms timeout; it ended after " + elapsedMillis + " ms"));

        EchoResponse next = echoStub().unary(EchoRequest.newBuilder().setMessage("after-idle-call").build());

        assertEquals("after-idle-call", next.getMessage(),
                "THE CONTROL: the next ordinary call on the same route is relayed, so the idle call left "
                        + "the route serving (the breaker skip itself is proven by the INVALID_ARGUMENT client-abort "
                        + "classification above and at unit level; one call cannot reach the breaker's request volume)");
    }

    /**
     * A call that sends part of a length-prefixed request message and then stalls is ended by the
     * gateway's message bound, as the client's own abort — a forwarded byte of an incomplete message does
     * not stop the bound.
     * <p>
     * grpc-java frames every message whole, so the call is sent through a raw HTTP/2 client that frames
     * the message by hand ({@link #grpcFrame}): it writes the request HEADERS, the complete 5-byte prefix
     * of one {@code Echo/Unary} message and only the first half of the payload that prefix declares, and
     * then neither writes nor ends its stream. The upstream cannot act on half a message, so the time the
     * call is held open is the client's. The gateway must end it after
     * {@link GrpcDispatchStage#GRPC_MESSAGE_TIMEOUT_MILLIS} as {@code INBOUND_BODY_ABORTED}, rendered
     * trailers-only as {@code INVALID_ARGUMENT}. A gateway whose bound stopped at the first forwarded byte
     * would leave the call to the guard's {@link UpstreamGuards#UPSTREAM_TIMEOUT_SECONDS}-second timeout,
     * which ends it as the upstream's failure with {@code DEADLINE_EXCEEDED} and counts it against the
     * upstream's circuit breaker. The client's own idle timeout is set beyond both.
     * <p>
     * The ordinary unary call that follows on the same route shows the route still serves: the stalled
     * call neither wedged it nor opened its breaker.
     */
    @Test
    @DisplayName("a call stalling inside its first request message is ended as a client abort after the "
            + "message bound, and the route still serves the next call")
    void partialFirstMessageIsEndedByTheMessageBound() throws Exception {
        long bound = GrpcDispatchStage.GRPC_MESSAGE_TIMEOUT_MILLIS;
        long guardTimeout = TimeUnit.SECONDS.toMillis(UpstreamGuards.UPSTREAM_TIMEOUT_SECONDS);
        Buffer frame = grpcFrame(EchoRequest.newBuilder().setMessage("stalls-inside-its-message").build());
        int payloadBytes = frame.length() - GRPC_PREFIX_BYTES;
        Buffer partialFrame = frame.getBuffer(0, GRPC_PREFIX_BYTES + payloadBytes / 2);

        RawCallOutcome outcome = rawUnaryCall(partialFrame, false)
                .get(IDLE_CALL_DEADLINE_SECONDS + 5L, TimeUnit.SECONDS);

        assertAll("the call stalled inside its message is ended by the message bound, as the client's abort",
                () -> assertEquals(Status.Code.INVALID_ARGUMENT, outcome.code(),
                        () -> "a call stalled inside its first message ends as INBOUND_BODY_ABORTED, rendered "
                                + "INVALID_ARGUMENT — DEADLINE_EXCEEDED would mean the guard's timeout ended it "
                                + "as the upstream's failure. Status was " + outcome.code()),
                () -> assertEquals(0, outcome.body().length(),
                        "the gateway's client-abort answer is trailers-only: it carries no response message"),
                () -> assertTrue(outcome.elapsedMillis() >= bound - TIMING_TOLERANCE_MILLIS,
                        () -> "the gateway must wait out the " + bound + " ms message bound before ending the "
                                + "call; it ended after " + outcome.elapsedMillis() + " ms"),
                () -> assertTrue(outcome.elapsedMillis() < bound + TIMING_TOLERANCE_MILLIS * 10,
                        () -> "the call must end at the message bound, well before the guard's " + guardTimeout
                                + " ms timeout; it ended after " + outcome.elapsedMillis() + " ms"));

        EchoResponse next = echoStub().unary(EchoRequest.newBuilder().setMessage("after-partial-call").build());

        assertEquals("after-partial-call", next.getMessage(),
                "the next ordinary call on the same route is relayed, so the stalled call left the route serving "
                        + "and did not open its breaker");
    }

    /**
     * THE MATCHED CONTROL of {@link #partialFirstMessageIsEndedByTheMessageBound()}: the same raw client,
     * the same route and the same hand-framed message, sent whole and followed by the end of the request
     * stream, is answered by the upstream normally. The only difference from the stalled call is the second
     * half of the payload and the half-close — so the bound does not cut off a well-formed call, and the
     * hand framing the stalled call relies on is a valid gRPC message.
     */
    @Test
    @DisplayName("THE CONTROL: the same hand-framed message, sent whole and half-closed, is answered by the upstream")
    void completeHalfClosedMessageIsAnsweredByTheUpstream() throws Exception {
        Buffer frame = grpcFrame(EchoRequest.newBuilder().setMessage("sent-whole").build());

        RawCallOutcome outcome = rawUnaryCall(frame, true).get(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(Status.Code.OK, outcome.code(),
                () -> "a complete, half-closed call must be answered by the upstream. Status was " + outcome.code());
        EchoResponse response = EchoResponse.parseFrom(
                outcome.body().getBytes(GRPC_PREFIX_BYTES, outcome.body().length()));
        assertAll("the upstream's answer to the complete call",
                () -> assertEquals(GRPC_PREFIX_BYTES + response.getSerializedSize(), outcome.body().length(),
                        "the answer carries exactly one length-prefixed response message"),
                () -> assertEquals("sent-whole", response.getMessage(),
                        "the upstream must echo the hand-framed request message verbatim"));
    }

    /**
     * Encodes a message as one uncompressed gRPC message: a {@code 0} compressed flag, its serialized
     * length as a 4-byte big-endian integer, then the serialized message.
     */
    private static Buffer grpcFrame(EchoRequest message) {
        byte[] payload = message.toByteArray();
        return Buffer.buffer(GRPC_PREFIX_BYTES + payload.length)
                .appendByte((byte) 0)
                .appendInt(payload.length)
                .appendBytes(payload);
    }

    /**
     * Sends {@code requestBytes} on a raw HTTP/2 {@code Echo/Unary} call and, when {@code halfClose} is
     * set, ends the request stream after them; otherwise the stream is left open with nothing more to
     * come. Completes with the gRPC status — from the response headers on a trailers-only answer, else
     * from the trailers — the response body and the time from sending to the end of the response. A
     * failure to send completes it exceptionally with that failure.
     */
    private static CompletableFuture<RawCallOutcome> rawUnaryCall(Buffer requestBytes, boolean halfClose) {
        CompletableFuture<RawCallOutcome> outcome = new CompletableFuture<>();
        RequestOptions options = new RequestOptions()
                .setMethod(HttpMethod.POST)
                .setHost("localhost")
                .setPort(testPort())
                .setURI("/" + EchoGrpc.getUnaryMethod().getFullMethodName())
                .setIdleTimeout(TimeUnit.SECONDS.toMillis(IDLE_CALL_DEADLINE_SECONDS))
                .putHeader("content-type", "application/grpc")
                .putHeader("te", "trailers");
        rawClient.request(options)
                .onFailure(outcome::completeExceptionally)
                .onSuccess(request -> {
                    // A reset that follows the end of the answer ends only the stream; one that comes
                    // first fails the call, and the response future below reports it.
                    request.exceptionHandler(outcome::completeExceptionally);
                    long started = System.nanoTime();
                    request.response()
                            .compose(response -> response.body().map(body -> new RawCallOutcome(grpcStatus(response),
                                    body, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))))
                            .onSuccess(outcome::complete)
                            .onFailure(outcome::completeExceptionally);
                    try {
                        if (halfClose) {
                            request.end(requestBytes).onFailure(outcome::completeExceptionally);
                        } else {
                            // A body write that does not end the request needs either a Content-Length or
                            // chunked mode, or Vert.x refuses it before the request head is sent. Chunked
                            // mode only lifts that client-side check: on HTTP/2 Vert.x builds the HEADERS frame
                            // from the request headers alone, so no transfer-encoding header reaches the wire
                            // and the call declares no length — as a real gRPC client's request does not.
                            request.setChunked(true);
                            request.write(requestBytes).onFailure(outcome::completeExceptionally);
                        }
                    } catch (RuntimeException e) {
                        // A synchronous send failure is a client-side defect: the call never reached the
                        // gateway. Fail the case with its cause now rather than leaving it to the idle timeout,
                        // whose stream reset would hide it.
                        outcome.completeExceptionally(e);
                    }
                });
        return outcome;
    }

    /** The gRPC status of a raw call: the headers carry it on a trailers-only answer, else the trailers. */
    private static Status.Code grpcStatus(HttpClientResponse response) {
        String value = response.getHeader("grpc-status");
        if (value == null) {
            value = response.getTrailer("grpc-status");
        }
        return value == null ? null : Status.fromCodeValue(Integer.parseInt(value)).getCode();
    }

    private static int testPort() {
        return Integer.parseInt(System.getProperty("test.https.port", "10443"));
    }

    /**
     * How a raw call ended.
     *
     * @param code          the gRPC status the gateway or the upstream ended it with, {@code null} when the
     *                      answer carried none
     * @param body          the response body as received, length prefixes included
     * @param elapsedMillis the time from sending the request to the end of the response
     */
    private record RawCallOutcome(Status.Code code, Buffer body, long elapsedMillis) {
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
